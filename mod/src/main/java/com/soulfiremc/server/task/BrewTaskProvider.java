/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.server.task;

import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.BrewTask;
import com.soulfiremc.grpc.generated.BrewTaskResult;
import com.soulfiremc.grpc.generated.ItemSelector;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.grpc.InventoryServiceImpl;
import com.soulfiremc.server.grpc.MinecraftDomainMapper;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.NoBlockBreakingConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.NoBlockPlacingConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import com.soulfiremc.server.util.SFBlockHelpers;
import com.soulfiremc.server.util.SFInventoryHelpers;
import io.grpc.Status;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Brews exact potion outputs in batches while keeping all container
/// interaction and progress tracking on the bot thread.
public final class BrewTaskProvider implements BotTaskProvider<BrewTask> {
  private static final int MAX_BOTTLES = 4_096;
  private static final int MAX_BATCH_SIZE = 3;
  private static final int MENU_TIMEOUT_TICKS = 100;
  private static final int BREW_TIMEOUT_TICKS = 1_200;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY,
    ControlResource.CONTAINER
  );

  @Override
  public BrewTask inputPrototype() {
    return BrewTask.getDefaultInstance();
  }

  @Override
  public String summary(BrewTask input) {
    return "Brew " + Math.max(1, input.getCount()) + " bottle(s)";
  }

  @Override
  public Set<ControlResource> resources(BrewTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, BrewTask input) {
    return start(context, input, new RecoveryState());
  }

  @Override
  public ExecutionFactory prepare(BrewTask input) {
    var recovery = new RecoveryState();
    return context -> start(context, input, recovery);
  }

  private BotTaskExecution start(BotTaskContext context, BrewTask input, RecoveryState recovery) {
    var count = input.getCount() <= 0 ? 1 : input.getCount();
    if (count > MAX_BOTTLES) {
      throw Status.INVALID_ARGUMENT
        .withDescription("count may not exceed " + MAX_BOTTLES)
        .asRuntimeException();
    }
    var player = Objects.requireNonNull(
      context.bot().minecraft().player,
      "Bot player is not available"
    );
    if (!player.containerMenu.getCarried().isEmpty()) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "The bot must not be carrying an item on the inventory cursor"
        )
        .asRuntimeException();
    }
    var dimension = Objects.requireNonNull(context.bot().minecraft().level).dimension().identifier().toString();
    if (recovery.dimension != null && !recovery.dimension.equals(dimension)) {
      throw Status.FAILED_PRECONDITION.withDescription("The unfinished recipe is in another dimension").asRuntimeException();
    }
    recovery.dimension = dimension;

    if (!recovery.materialsValidated) {
      validateMaterials(context, input, count);
      recovery.materialsValidated = true;
    }

    BlockPos station = null;
    if (input.hasStation() || !(player.containerMenu instanceof BrewingStandMenu)) {
      if (!input.hasStation()) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "station is required unless a brewing stand is already open"
          )
          .asRuntimeException();
      }
      var requested = input.getStation();
      var level = Objects.requireNonNull(
        context.bot().minecraft().level,
        "Bot level is not available"
      );
      var currentDimension = level.dimension().identifier().toString();
      if (!requested.getDimension().isBlank()
        && !requested.getDimension().equals(currentDimension)) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "Brewing station is in '%s', but the bot is in '%s'"
              .formatted(requested.getDimension(), currentDimension)
          )
          .asRuntimeException();
      }
      station = new BlockPos(
        requested.getX(),
        requested.getY(),
        requested.getZ()
      );
    }

    var result = new CompletableFuture<BrewTaskResult>();
    return new BotTaskExecution(
      new BrewControl(context, input, count, station, result, recovery,
        new RecipeItemValues(Objects.requireNonNull(context.bot().minecraft().level))),
      result
    );
  }

  private static void validateMaterials(
    BotTaskContext context,
    BrewTask input,
    int count
  ) {
    var player = Objects.requireNonNull(context.bot().minecraft().player);
    var level = Objects.requireNonNull(context.bot().minecraft().level);
    var reagents = level.recipeAccess().propertySet(RecipePropertySet.BREWING_REAGENTS);
    var inventory = player.getInventory().getNonEquipmentItems();
    var inputs = level.recipeAccess().propertySet(RecipePropertySet.BREWING_INPUTS);
    var inputCount = inventory.stream()
      .filter(inputs::test)
      .filter(stack -> InventoryServiceImpl.matches(stack, input.getInput()))
      .mapToInt(ItemStack::getCount)
      .sum();
    if (inputCount < count) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "Only %d matching potion input(s) are available, but %d are required"
            .formatted(inputCount, count)
        )
        .asRuntimeException();
    }
    var ingredientCount = inventory.stream()
      .filter(stack -> InventoryServiceImpl.matches(
        stack,
        input.getIngredient()
      ))
      .filter(reagents::test)
      .mapToInt(ItemStack::getCount)
      .sum();
    var requiredIngredients = (count + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
    if (ingredientCount < requiredIngredients) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "Only %d matching brewing ingredient(s) are available, but %d are required"
            .formatted(ingredientCount, requiredIngredients)
        )
        .asRuntimeException();
    }
  }

  private static void validateFuel(
    BotTaskContext context,
    @Nullable ItemSelector fuelSelector,
    BrewingStandMenu menu
  ) {
    var player = Objects.requireNonNull(context.bot().minecraft().player);
    var inventoryFuel = player.getInventory().getNonEquipmentItems().stream()
      .filter(stack -> stack.has(DataComponents.BREWING_FUEL))
      .filter(stack -> fuelSelector == null
        || InventoryServiceImpl.matches(stack, fuelSelector))
      .findFirst();
    var stationFuel = menu.getSlot(4).getItem();
    var stationFuelMatches = stationFuel.has(DataComponents.BREWING_FUEL)
      && (fuelSelector == null
      || InventoryServiceImpl.matches(stationFuel, fuelSelector));
    if (menu.getFuel() <= 0 && inventoryFuel.isEmpty() && !stationFuelMatches) {
      throw Status.FAILED_PRECONDITION
        .withDescription("No matching brewing fuel is available")
        .asRuntimeException();
    }
  }

  private static final class BrewControl implements ControlTask {
    private final BotTaskContext context;
    private final ItemSelector inputSelector;
    private final ItemSelector ingredientSelector;
    private final @Nullable ItemSelector fuelSelector;
    private final @Nullable ItemSelector expectedResultSelector;
    private final int targetCount;
    private final @Nullable BlockPos station;
    private final CompletableFuture<BrewTaskResult> result;
    private final RecoveryState recovery;
    private final RecipeItemValues items;
    private boolean reconcileOutput;
    private @Nullable PathExecutor activePath;
    private boolean active;
    private @Nullable BrewingStandMenu openedMenu;
    private @Nullable Stage resumeStage;
    private int resumeStageTicks;
    private Stage stage;
    private int stageTicks;

    private BrewControl(
      BotTaskContext context,
      BrewTask input,
      int targetCount,
      @Nullable BlockPos station,
      CompletableFuture<BrewTaskResult> result,
      RecoveryState recovery,
      RecipeItemValues items
    ) {
      this.context = context;
      this.inputSelector = input.getInput();
      this.ingredientSelector = input.getIngredient();
      this.fuelSelector = input.hasFuel() ? input.getFuel() : null;
      this.expectedResultSelector = input.hasExpectedResult()
        ? input.getExpectedResult()
        : null;
      this.targetCount = targetCount;
      this.station = station;
      this.result = result;
      this.recovery = recovery;
      this.items = items;
      this.resumeStage = recovery.phase;
      this.resumeStageTicks = recovery.phaseTicks;
      this.stage = station == null ? Stage.OPEN_MENU : Stage.NAVIGATE;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        if (reconcileOutput && stage != Stage.NAVIGATE && stage != Stage.OPEN_MENU) {
          if (!reconcilePickup()) return;
          reconcileOutput = false;
        }
        if (recovery.operationsCompleted >= targetCount) {
          complete();
          return;
        }
        switch (stage) {
          case NAVIGATE -> navigate();
          case OPEN_MENU -> openMenu();
          case VALIDATE_MENU -> validateMenu();
          case LOAD_BOTTLES -> loadBottles();
          case LOAD_INGREDIENT -> loadIngredient();
          case LOAD_FUEL -> loadFuel();
          case WAIT_FOR_OUTPUT -> waitForOutput();
          case TAKE_OUTPUT -> takeOutput();
          case AWAIT_OUTPUT_PICKUP -> awaitOutputPickup();
          case DEPOSIT_OUTPUT -> depositOutput();
          case AWAIT_OUTPUT_DEPOSIT -> awaitOutputDeposit();
        }
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      } finally {
        if (stage != Stage.NAVIGATE && stage != Stage.OPEN_MENU) {
          recovery.phase = stage;
          recovery.phaseTicks = stageTicks;
        }
      }
    }

    private void navigate() {
      var stationPosition = Objects.requireNonNull(station);
      var player = requirePlayer();
      if (player.position().distanceToSqr(Vec3.atCenterOf(stationPosition)) <= 9) {
        stopPath(ControlStopReason.CANCELLED, null);
        transition(Stage.OPEN_MENU, "Opening brewing stand");
        return;
      }
      if (activePath == null) {
        var constraint = new NoBlockBreakingConstraint(
          new NoBlockPlacingConstraint(
            new PathConstraintImpl(context.bot())
          )
        );
        activePath = PathExecutor.createPathfinding(
          context.bot(),
          new CloseToPosGoal(SFVec3i.fromInt(stationPosition), 3),
          constraint
        );
        activePath.onStarted();
      }
      if (!activePath.isDone()) {
        activePath.tick();
        context.reportProgress(progress(
          activePath.progress().planning()
            ? "Planning route to brewing stand"
            : "Walking to brewing stand"
        ));
        return;
      }
      var path = activePath;
      activePath = null;
      try {
        path.completion().join();
        path.onStopped(ControlStopReason.COMPLETED, null);
        transition(Stage.OPEN_MENU, "Opening brewing stand");
      } catch (CompletionException exception) {
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        path.onStopped(ControlStopReason.FAILED, cause);
        throw exception;
      }
    }

    private void openMenu() {
      var player = requirePlayer();
      if (player.containerMenu instanceof BrewingStandMenu menu) {
        openedMenu = menu;
        reconcileOutput = recovery.pickup != null;
        var resumed = resumeStage;
        resumeStage = null;
        transition(
          resumed == null ? Stage.VALIDATE_MENU : resumed,
          resumed == null ? "Checking brewing stand" : "Resuming brewing"
        );
        if (resumed != null) {
          stageTicks = resumeStageTicks;
        }
        return;
      }
      var stationPosition = Objects.requireNonNull(station);
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      if (!SFBlockHelpers.isChunkLoaded(level, stationPosition)) {
        if (++stageTicks > MENU_TIMEOUT_TICKS) {
          throw Status.DEADLINE_EXCEEDED.withDescription("Timed out observing the brewing stand").asRuntimeException();
        }
        return;
      }
      var actual = BuiltInRegistries.BLOCK
        .getKey(level.getBlockState(stationPosition).getBlock())
        .toString();
      if (!actual.equals("minecraft:brewing_stand")) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Expected minecraft:brewing_stand at the station, found " + actual
          )
          .asRuntimeException();
      }
      if (stageTicks % 10 == 0) {
        Objects.requireNonNull(context.bot().minecraft().gameMode).useItemOn(
          player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(
            Vec3.atCenterOf(stationPosition),
            Direction.UP,
            stationPosition,
            false
          )
        );
      }
      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out opening the brewing stand")
          .asRuntimeException();
      }
    }

    private void validateMenu() {
      var menu = requireBrewingMenu();
      for (var index = 0; index <= 3; index++) {
        if (!menu.getSlot(index).getItem().isEmpty()) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Brewing stand bottle and ingredient slots must be empty"
            )
            .asRuntimeException();
        }
      }
      var existingFuel = menu.getSlot(4).getItem();
      if (!existingFuel.isEmpty()
        && (!existingFuel.has(DataComponents.BREWING_FUEL)
        || fuelSelector != null
        && !InventoryServiceImpl.matches(existingFuel, fuelSelector))) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Existing brewing stand fuel does not match the task fuel policy"
          )
          .asRuntimeException();
      }
      validateFuel(context, fuelSelector, menu);
      transition(Stage.LOAD_BOTTLES, "Loading potion bottles");
    }

    private void loadBottles() {
      var menu = requireBrewingMenu();
      for (var index = 0; index <= 3; index++) {
        if (!menu.getSlot(index).getItem().isEmpty()) {
          throw Status.ABORTED
            .withDescription("Brewing stand contents changed before loading the batch")
            .asRuntimeException();
        }
      }
      var reagents = requireLevel().recipeAccess().propertySet(RecipePropertySet.BREWING_REAGENTS);
      var maximumBatchSize = Math.min(
        MAX_BATCH_SIZE,
        targetCount - recovery.operationsCompleted
      );
      recovery.batchOutputs.clear();
      recovery.batchInputs.clear();
      recovery.outputIndex = 0;

      recovery.selectedIngredient = items.capture(playerSlots(menu)
        .mapToObj(slot -> menu.getSlot(slot).getItem())
        .filter(stack -> InventoryServiceImpl.matches(stack, ingredientSelector))
        .filter(reagents::test)
        .findFirst()
        .orElseThrow(() -> Status.FAILED_PRECONDITION
          .withDescription("No matching brewing ingredient remains")
          .asRuntimeException())
        .copy());
      if (!recovery.inputsSelected) {
        selectInputs(menu);
      }
      recovery.activeBatchSize = Math.min(maximumBatchSize, recovery.remainingInputs.size());
      for (var index = 0; index < recovery.activeBatchSize; index++) {
        var stored = recovery.remainingInputs.getFirst();
        var selected = items.resolve(stored);
        var source = playerSlots(menu)
          .filter(slot -> ItemStack.isSameItemSameComponents(menu.getSlot(slot).getItem(), selected))
          .findFirst()
          .orElseThrow(() -> Status.FAILED_PRECONDITION
            .withDescription("A selected brewing input is no longer available")
            .asRuntimeException());
        recovery.batchInputs.add(stored);
        moveOne(menu, source, index);
        recovery.remainingInputs.removeFirst();
      }
      transition(Stage.LOAD_INGREDIENT, "Loading brewing ingredient");
    }

    private void selectInputs(BrewingStandMenu menu) {
      var inputs = requireLevel().recipeAccess().propertySet(RecipePropertySet.BREWING_INPUTS);
      var candidates = playerSlots(menu)
        .mapToObj(slot -> menu.getSlot(slot).getItem())
        .filter(stack -> InventoryServiceImpl.matches(stack, inputSelector))
        .filter(inputs::test)
        .toList();
      for (var stack : candidates) {
        var amount = Math.min(stack.getCount(), targetCount - recovery.remainingInputs.size());
        var selected = items.capture(stack.copyWithCount(1));
        for (var index = 0; index < amount; index++) {
          recovery.remainingInputs.addLast(selected);
        }
        if (recovery.remainingInputs.size() == targetCount) {
          break;
        }
      }
      if (recovery.remainingInputs.size() < targetCount) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Not enough matching brewing inputs remain")
          .asRuntimeException();
      }
      recovery.inputsSelected = true;
    }

    private void loadIngredient() {
      var menu = requireBrewingMenu();
      if (!menu.getSlot(3).getItem().isEmpty()) {
        throw Status.ABORTED
          .withDescription("Brewing ingredient changed before loading")
          .asRuntimeException();
      }
      var reagents = requireLevel().recipeAccess().propertySet(RecipePropertySet.BREWING_REAGENTS);
      var source = playerSlots(menu)
        .filter(slot -> {
          var ingredient = menu.getSlot(slot).getItem();
          return InventoryServiceImpl.matches(ingredient, ingredientSelector)
            && reagents.test(ingredient)
            && ItemStack.isSameItemSameComponents(ingredient, items.resolve(recovery.selectedIngredient));
        })
        .findFirst()
        .orElseThrow(() -> Status.FAILED_PRECONDITION
          .withDescription("No compatible brewing ingredient remains")
          .asRuntimeException());
      moveOne(menu, source, 3);
      transition(Stage.LOAD_FUEL, "Checking brewing fuel");
    }

    private void loadFuel() {
      var menu = requireBrewingMenu();
      if (menu.getFuel() > 0) {
        transition(Stage.WAIT_FOR_OUTPUT, "Brewing");
        return;
      }
      if (!menu.getSlot(4).getItem().isEmpty()) {
        transition(Stage.WAIT_FOR_OUTPUT, "Brewing");
        return;
      }
      var source = playerSlots(menu)
        .filter(slot -> {
          var stack = menu.getSlot(slot).getItem();
          return stack.has(DataComponents.BREWING_FUEL)
            && (fuelSelector == null
            || InventoryServiceImpl.matches(stack, fuelSelector));
        })
        .findFirst()
        .orElseThrow(() -> Status.FAILED_PRECONDITION
          .withDescription("No matching brewing fuel remains")
          .asRuntimeException());
      moveOne(menu, source, 4);
      transition(Stage.WAIT_FOR_OUTPUT, "Brewing");
    }

    private void waitForOutput() {
      var menu = requireBrewingMenu();
      var complete = true;
      for (var index = 0; index < recovery.activeBatchSize; index++) {
        var output = menu.getSlot(index).getItem();
        if (output.isEmpty() || ItemStack.isSameItemSameComponents(output, items.resolve(recovery.batchInputs.get(index)))) {
          complete = false;
          break;
        }
      }
      if (complete) {
        for (var index = 0; index < recovery.activeBatchSize; index++) {
          var output = menu.getSlot(index).getItem();
          if (expectedResultSelector != null && !InventoryServiceImpl.matches(output, expectedResultSelector)) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Brewing output does not match expected_result")
              .asRuntimeException();
          }
          recovery.batchOutputs.add(items.capture(output));
        }
        transition(Stage.TAKE_OUTPUT, "Collecting brewed potions");
        return;
      }
      stageTicks++;
      if (stageTicks > BREW_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out waiting for brewing output")
          .asRuntimeException();
      }
    }

    private void takeOutput() {
      var menu = requireBrewingMenu();
      if (!menu.getCarried().isEmpty()
        || !ItemStack.matches(menu.getSlot(recovery.outputIndex).getItem(), items.resolve(recovery.batchOutputs.get(recovery.outputIndex)))) {
        throw Status.ABORTED
          .withDescription("Brewing output changed before collection")
          .asRuntimeException();
      }
      if (recovery.pickup == null) {
        recovery.pickup = RecipeOutputCheckpoint.capture(items.resolve(recovery.batchOutputs.get(recovery.outputIndex)), requirePlayer());
      }
      sendUnpredictedPickup(menu, recovery.outputIndex);
      transition(Stage.AWAIT_OUTPUT_PICKUP, "Confirming brewed potion collection");
    }

    private void awaitOutputPickup() {
      var carried = requireBrewingMenu().getCarried();
      if (ItemStack.matches(carried, items.resolve(recovery.batchOutputs.get(recovery.outputIndex)))) {
        transition(Stage.DEPOSIT_OUTPUT, "Storing brewed potion");
        return;
      }
      if (!carried.isEmpty()) {
        throw Status.ABORTED
          .withDescription("Collected brewing output does not match the task batch")
          .asRuntimeException();
      }
      awaitOutputConfirmation("Timed out confirming brewed potion collection");
    }

    private void depositOutput() {
      var menu = requireBrewingMenu();
      var carried = menu.getCarried();
      var expected = items.resolve(recovery.batchOutputs.get(recovery.outputIndex));
      if (carried.isEmpty()
        || !ItemStack.isSameItemSameComponents(carried, expected)
        || carried.getCount() > expected.getCount()) {
        throw Status.ABORTED
          .withDescription("Brewing output cursor changed before storage")
          .asRuntimeException();
      }
      var target = playerSlots(menu)
        .filter(slot -> canDeposit(menu, slot, carried))
        .findFirst();
      if (target.isEmpty()) {
        throw Status.RESOURCE_EXHAUSTED
          .withDescription("Inventory has no room for brewed potion")
          .asRuntimeException();
      }
      recovery.depositingCount = carried.getCount();
      sendUnpredictedPickup(menu, target.getAsInt());
      transition(Stage.AWAIT_OUTPUT_DEPOSIT, "Confirming brewed potion storage");
    }

    private void awaitOutputDeposit() {
      var carried = requireBrewingMenu().getCarried();
      if (carried.isEmpty()) {
        confirmOutput();
        return;
      }
      if (!ItemStack.isSameItemSameComponents(carried, items.resolve(recovery.batchOutputs.get(recovery.outputIndex)))
        || carried.getCount() > recovery.depositingCount) {
        throw Status.ABORTED
          .withDescription("Brewing output cursor changed during storage")
          .asRuntimeException();
      }
      if (carried.getCount() < recovery.depositingCount) {
        transition(Stage.DEPOSIT_OUTPUT, "Storing remaining brewed output");
        return;
      }
      awaitOutputConfirmation("Timed out confirming brewed potion storage");
    }

    private void confirmOutput() {
      recovery.outputs.add(recovery.batchOutputs.get(recovery.outputIndex));
      recovery.pickup = null;
      recovery.outputIndex++;
      if (recovery.outputIndex < recovery.activeBatchSize) {
        transition(Stage.TAKE_OUTPUT, "Collecting brewed potion");
        return;
      }
      recovery.operationsCompleted += recovery.activeBatchSize;
      transition(recovery.operationsCompleted >= targetCount ? Stage.WAIT_FOR_OUTPUT : Stage.LOAD_BOTTLES,
        recovery.operationsCompleted >= targetCount ? "Brewing complete" : "Loading next potion batch");
    }

    private boolean reconcilePickup() {
      var menu = requireBrewingMenu();
      var pickup = Objects.requireNonNull(recovery.pickup);
      switch (pickup.locate(requirePlayer(), items.resolve(recovery.batchOutputs.get(recovery.outputIndex)), menu.getCarried(), menu.getSlot(recovery.outputIndex).getItem())) {
        case STORED -> confirmOutput();
        case CURSOR -> transition(Stage.DEPOSIT_OUTPUT, "Storing recovered brewing output");
        case STATION -> transition(Stage.TAKE_OUTPUT, "Collecting recovered brewing output");
        case PENDING -> {
          awaitOutputConfirmation("Timed out reconciling brewing output after reopening");
          return false;
        }
      }
      return true;
    }

    private void sendUnpredictedPickup(BrewingStandMenu menu, int slot) {
      var connection = Objects.requireNonNull(context.bot().minecraft().getConnection());
      SFInventoryHelpers.sendUnpredictedPickup(connection, menu, slot);
    }

    private void awaitOutputConfirmation(String message) {
      if (++stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED.withDescription(message).asRuntimeException();
      }
    }

    private void moveOne(
      BrewingStandMenu menu,
      int source,
      int destination
    ) {
      var player = requirePlayer();
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode
      );
      gameMode.handleContainerInput(
        menu.containerId,
        source,
        0,
        ContainerInput.PICKUP,
        player
      );
      gameMode.handleContainerInput(
        menu.containerId,
        destination,
        1,
        ContainerInput.PICKUP,
        player
      );
      if (!menu.getCarried().isEmpty()) {
        gameMode.handleContainerInput(
          menu.containerId,
          source,
          0,
          ContainerInput.PICKUP,
          player
        );
      }
    }

    private static java.util.stream.IntStream playerSlots(
      BrewingStandMenu menu
    ) {
      return SFInventoryHelpers.playerInventorySlots(menu);
    }

    private static boolean canDeposit(
      AbstractContainerMenu menu,
      int slotIndex,
      ItemStack carried
    ) {
      var slot = menu.getSlot(slotIndex);
      if (!slot.mayPlace(carried)) {
        return false;
      }
      var existing = slot.getItem();
      return existing.isEmpty()
        || ItemStack.isSameItemSameComponents(existing, carried)
        && existing.getCount() < Math.min(
        existing.getMaxStackSize(),
        slot.getMaxStackSize(existing)
      );
    }

    private ClientLevel requireLevel() {
      return Objects.requireNonNull(context.bot().minecraft().level);
    }

    private BrewingStandMenu requireBrewingMenu() {
      var menu = requirePlayer().containerMenu;
      if (menu instanceof BrewingStandMenu brewingMenu) {
        return brewingMenu;
      }
      throw Status.FAILED_PRECONDITION
        .withDescription("The brewing stand menu was closed")
        .asRuntimeException();
    }

    private net.minecraft.client.player.LocalPlayer requirePlayer() {
      return Objects.requireNonNull(context.bot().minecraft().player);
    }

    private void stopPath(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var path = activePath;
      activePath = null;
      if (path != null) {
        path.onStopped(reason, cause);
      }
    }

    private void transition(Stage next, String message) {
      stage = next;
      stageTicks = 0;
      context.reportProgress(progress(message));
    }

    private BotTaskProgress progress(String message) {
      return BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(recovery.operationsCompleted)
        .setTotal(targetCount)
        .setFraction(Math.min(
          1.0,
          (double) recovery.operationsCompleted / targetCount
        ))
        .build();
    }

    private void complete() {
      result.complete(BrewTaskResult.newBuilder()
        .addAllOutputs(recovery.outputs.stream()
          .map(items::resolve)
          .map(MinecraftDomainMapper::item)
          .toList())
        .build());
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.HIGH;
    }

    @Override
    public Set<ControlResource> resources() {
      return RESOURCES;
    }

    @Override
    public void onStarted() {
      active = true;
      if (station != null) {
        var player = requirePlayer();
        if (!(player.containerMenu instanceof InventoryMenu)) {
          player.closeContainer();
        }
      }
    }

    @Override
    public void onSuspended() {
      active = false;
      if (activePath != null) {
        activePath.onSuspended();
      }
    }

    @Override
    public void onResumed() {
      active = true;
      var player = requirePlayer();
      if (station != null && openedMenu != null && player.containerMenu != openedMenu) {
        if (resumeStage == null) {
          resumeStage = stage;
          resumeStageTicks = stageTicks;
        }
        if (!(player.containerMenu instanceof InventoryMenu)) {
          player.closeContainer();
        }
        stopPath(ControlStopReason.CANCELLED, null);
        transition(Stage.NAVIGATE, "Returning to brewing stand");
        return;
      }
      if (activePath != null) {
        activePath.onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      try {
        stopPath(reason, cause);
        if (active) {
          active = false;
          var player = context.bot().minecraft().player;
          if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
            player.closeContainer();
          }
        }
      } finally {
        if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
          result.cancel(true);
        }
      }
    }

    @Override
    public String description() {
      return "Brew potions";
    }
  }

  /// Detached batches and progress survive a connection without keeping its menu or control.
  private static final class RecoveryState {
    private @Nullable String dimension;
    private boolean materialsValidated;
    private @Nullable Stage phase;
    private int phaseTicks;
    private final List<RecipeItemValues.StoredItem> outputs = new ArrayList<>();
    private final List<RecipeItemValues.StoredItem> batchInputs = new ArrayList<>();
    private final List<RecipeItemValues.StoredItem> batchOutputs = new ArrayList<>();
    private final Deque<RecipeItemValues.StoredItem> remainingInputs = new ArrayDeque<>();
    private boolean inputsSelected;
    private int operationsCompleted;
    private int activeBatchSize;
    private int outputIndex;
    private int depositingCount;
    private RecipeItemValues.StoredItem selectedIngredient = RecipeItemValues.EMPTY;
    private @Nullable RecipeOutputCheckpoint pickup;
  }

  private enum Stage {
    NAVIGATE,
    OPEN_MENU,
    VALIDATE_MENU,
    LOAD_BOTTLES,
    LOAD_INGREDIENT,
    LOAD_FUEL,
    WAIT_FOR_OUTPUT,
    TAKE_OUTPUT,
    AWAIT_OUTPUT_PICKUP,
    DEPOSIT_OUTPUT,
    AWAIT_OUTPUT_DEPOSIT
  }
}
