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
import com.soulfiremc.grpc.generated.ItemSelector;
import com.soulfiremc.grpc.generated.SmeltTask;
import com.soulfiremc.grpc.generated.SmeltTaskResult;
import com.soulfiremc.mod.mixin.soulfire.AbstractFurnaceMenuAccessor;
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
import com.soulfiremc.server.recipe.RecipeSupport;
import com.soulfiremc.server.util.SFInventoryHelpers;
import io.grpc.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// Executes furnace, blast-furnace, and smoker recipes in stack-sized batches.
public final class SmeltTaskProvider implements BotTaskProvider<SmeltTask> {
  private static final int MAX_SMELT_OPERATIONS = 4_096;
  private static final int MENU_TIMEOUT_TICKS = 100;
  private static final int COOK_TIMEOUT_TICKS = 12_000;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY,
    ControlResource.CONTAINER
  );

  @Override
  public SmeltTask inputPrototype() {
    return SmeltTask.getDefaultInstance();
  }

  @Override
  public String summary(SmeltTask input) {
    return "Smelt " + Math.max(1, input.getCount()) + " item(s)";
  }

  @Override
  public Set<ControlResource> resources(SmeltTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, SmeltTask input) {
    var count = input.getCount() <= 0 ? 1 : input.getCount();
    if (count > MAX_SMELT_OPERATIONS) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "count may not exceed " + MAX_SMELT_OPERATIONS
        )
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
    BlockPos station = null;
    String stationId;
    if (player.containerMenu instanceof AbstractFurnaceMenu menu) {
      stationId = menuStation(menu);
    } else {
      if (!input.hasStation()) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "station is required unless a compatible furnace is already open"
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
            "Smelting station is in '%s', but the bot is in '%s'"
              .formatted(requested.getDimension(), currentDimension)
          )
          .asRuntimeException();
      }
      station = new BlockPos(
        requested.getX(),
        requested.getY(),
        requested.getZ()
      );
      stationId = BuiltInRegistries.BLOCK
        .getKey(level.getBlockState(station).getBlock())
        .toString();
    }
    var selection = selectRecipe(
      context,
      input.getInput(),
      count,
      stationId
    );
    validateFuel(context, input);

    var result = new CompletableFuture<SmeltTaskResult>();
    return new BotTaskExecution(
      new SmeltControl(
        context,
        input.getInput(),
        input.hasFuel() ? input.getFuel() : null,
        selection,
        count,
        station,
        result
      ),
      result
    );
  }

  private static RecipeSelection selectRecipe(
    BotTaskContext context,
    ItemSelector selector,
    int count,
    String stationId
  ) {
    var player = Objects.requireNonNull(context.bot().minecraft().player);
    var inventory = Stream.concat(
      player.getInventory().getNonEquipmentItems().stream(),
      Stream.of(player.getOffhandItem())
    ).toList();
    var recipes = RecipeSupport.recipes(context.bot()).stream()
      .filter(entry -> entry.display() instanceof FurnaceRecipeDisplay)
      .filter(entry ->
        RecipeSupport.requiredStation(context.bot(), entry).equals(stationId)
      )
      .sorted(Comparator.comparingInt(entry -> entry.id().index()))
      .toList();
    for (var recipe : recipes) {
      var requirements = recipe.craftingRequirements().orElse(List.of());
      if (requirements.size() != 1) {
        continue;
      }
      var matchingCount = inventory.stream()
        .filter(stack -> InventoryServiceImpl.matches(stack, selector))
        .filter(requirements.getFirst())
        .mapToInt(ItemStack::getCount)
        .sum();
      if (matchingCount >= count) {
        return new RecipeSelection(
          recipe,
          (FurnaceRecipeDisplay) recipe.display(),
          requirements.getFirst()::test
        );
      }
    }
    throw Status.FAILED_PRECONDITION
      .withDescription(
        "No known %s recipe has enough matching input items"
          .formatted(stationId)
      )
      .asRuntimeException();
  }

  private static void validateFuel(
    BotTaskContext context,
    SmeltTask input
  ) {
    var player = Objects.requireNonNull(context.bot().minecraft().player);
    Predicate<ItemStack> matchesFuel = stack -> !stack.isEmpty()
      && (!input.hasFuel()
      || InventoryServiceImpl.matches(stack, input.getFuel()))
      && stack.has(DataComponents.COOKING_FUEL);
    var inventoryFuel = player.getInventory().getNonEquipmentItems().stream()
      .filter(matchesFuel)
      .findFirst();
    var offhandFuelMatches = matchesFuel.test(player.getOffhandItem());
    var stationFuel = player.containerMenu instanceof AbstractFurnaceMenu menu
      ? menu.getSlot(1).getItem()
      : ItemStack.EMPTY;
    var stationFuelMatches = matchesFuel.test(stationFuel);
    if (
      inventoryFuel.isEmpty()
        && !offhandFuelMatches
        && !stationFuelMatches
    ) {
      throw Status.FAILED_PRECONDITION
        .withDescription("No matching valid furnace fuel is available")
        .asRuntimeException();
    }
  }

  private static final class SmeltControl implements ControlTask {
    private final BotTaskContext context;
    private final ItemSelector inputSelector;
    private final @Nullable ItemSelector fuelSelector;
    private final RecipeSelection recipe;
    private final int targetCount;
    private final @Nullable BlockPos station;
    private final CompletableFuture<SmeltTaskResult> result;
    private final List<ItemStack> outputs = new ArrayList<>();
    private @Nullable PathExecutor activePath;
    private Stage stage;
    private int stageTicks;
    private int operationsCompleted;
    private int batchOperations;

    private SmeltControl(
      BotTaskContext context,
      ItemSelector inputSelector,
      @Nullable ItemSelector fuelSelector,
      RecipeSelection recipe,
      int targetCount,
      @Nullable BlockPos station,
      CompletableFuture<SmeltTaskResult> result
    ) {
      this.context = context;
      this.inputSelector = inputSelector;
      this.fuelSelector = fuelSelector;
      this.recipe = recipe;
      this.targetCount = targetCount;
      this.station = station;
      this.result = result;
      this.stage = station == null ? Stage.OPEN_MENU : Stage.NAVIGATE;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        if (operationsCompleted >= targetCount) {
          complete();
          return;
        }
        switch (stage) {
          case NAVIGATE -> navigate();
          case OPEN_MENU -> openMenu();
          case VALIDATE_MENU -> validateMenu();
          case UNLOAD_FUEL -> unloadFuel();
          case LOAD_INPUT -> loadInput();
          case LOAD_FUEL -> loadFuel();
          case WAIT_FOR_OUTPUT -> waitForOutput();
          case TAKE_OUTPUT -> takeOutput();
          case DEPOSIT_OUTPUT -> depositOutput();
        }
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      }
    }

    private void navigate() {
      var stationPosition = Objects.requireNonNull(station);
      var player = requirePlayer();
      if (player.position().distanceToSqr(Vec3.atCenterOf(stationPosition)) <= 9) {
        stopPath(ControlStopReason.CANCELLED, null);
        transition(Stage.OPEN_MENU, "Opening smelting station");
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
            ? "Planning route to smelting station"
            : "Walking to smelting station"
        ));
        return;
      }
      var path = activePath;
      activePath = null;
      try {
        path.completion().join();
        path.onStopped(ControlStopReason.COMPLETED, null);
        transition(Stage.OPEN_MENU, "Opening smelting station");
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
      if (player.containerMenu instanceof AbstractFurnaceMenu) {
        transition(Stage.VALIDATE_MENU, "Checking smelting station");
        return;
      }
      var stationPosition = Objects.requireNonNull(station);
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      var expected = RecipeSupport.requiredStation(
        context.bot(),
        recipe.entry
      );
      var actual = BuiltInRegistries.BLOCK
        .getKey(level.getBlockState(stationPosition).getBlock())
        .toString();
      if (!expected.equals(actual)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Expected %s at the smelting station, found %s"
              .formatted(expected, actual)
          )
          .asRuntimeException();
      }
      if (stageTicks % 10 == 0) {
        var gameMode = Objects.requireNonNull(
          context.bot().minecraft().gameMode
        );
        gameMode.useItemOn(
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
          .withDescription("Timed out opening the smelting station")
          .asRuntimeException();
      }
    }

    private void validateMenu() {
      var menu = requireFurnaceMenu();
      var expected = RecipeSupport.requiredStation(context.bot(), recipe.entry);
      var actualStation = menuStation(menu);
      if (!expected.equals(actualStation)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Selected recipe requires %s, but the open menu is %s"
              .formatted(expected, actualStation)
          )
          .asRuntimeException();
      }
      if (!menu.getSlot(0).getItem().isEmpty()
        || !menu.getSlot(2).getItem().isEmpty()) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Smelting station input and output slots must be empty"
          )
          .asRuntimeException();
      }
      var existingFuel = menu.getSlot(1).getItem();
      if (!existingFuel.isEmpty()
        && (!existingFuel.has(DataComponents.COOKING_FUEL)
        || fuelSelector != null
        && !InventoryServiceImpl.matches(existingFuel, fuelSelector))) {
        transition(
          Stage.UNLOAD_FUEL,
          "Unloading fuel that does not match the task policy"
        );
        return;
      }
      transition(Stage.LOAD_INPUT, "Loading smelting input");
    }

    private void unloadFuel() {
      var menu = requireFurnaceMenu();
      var player = requirePlayer();
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode
      );
      var carried = menu.getCarried();
      if (carried.isEmpty()) {
        var existingFuel = menu.getSlot(1).getItem();
        if (existingFuel.isEmpty()) {
          transition(Stage.LOAD_INPUT, "Loading smelting input");
          return;
        }
        var canStoreFuel = SFInventoryHelpers.playerInventorySlots(menu)
          .anyMatch(slot -> canDeposit(menu, slot, existingFuel));
        if (!canStoreFuel) {
          throw Status.RESOURCE_EXHAUSTED
            .withDescription(
              "Player inventory has no room for existing furnace fuel"
            )
            .asRuntimeException();
        }
        gameMode.handleContainerInput(
          menu.containerId,
          1,
          0,
          ContainerInput.PICKUP,
          player
        );
        carried = menu.getCarried();
      }
      if (carried.isEmpty()) {
        transition(Stage.LOAD_INPUT, "Loading smelting input");
        return;
      }
      var fuelToStore = carried;
      var target = SFInventoryHelpers.playerInventorySlots(menu)
        .filter(slot -> canDeposit(menu, slot, fuelToStore))
        .findFirst()
        .orElseThrow(() -> Status.RESOURCE_EXHAUSTED
          .withDescription(
            "Player inventory has no room for existing furnace fuel"
          )
          .asRuntimeException()
        );
      gameMode.handleContainerInput(
        menu.containerId,
        target,
        0,
        ContainerInput.PICKUP,
        player
      );
      if (menu.getCarried().isEmpty()) {
        transition(Stage.LOAD_INPUT, "Loading smelting input");
        return;
      }
      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out unloading existing furnace fuel")
          .asRuntimeException();
      }
    }

    private void loadInput() {
      var menu = requireFurnaceMenu();
      if (!menu.getSlot(0).getItem().isEmpty()) {
        transition(Stage.LOAD_FUEL, "Checking furnace fuel");
        return;
      }
      var source = findSource(
        menu,
        stack -> InventoryServiceImpl.matches(stack, inputSelector)
          && recipe.acceptsInput.test(stack)
      );
      if (source.isEmpty()) {
        throw Status.FAILED_PRECONDITION
          .withDescription("No matching smelting input remains")
          .asRuntimeException();
      }
      var sourceStack = menu.getSlot(source.getAsInt()).getItem();
      var expectedOutput = expectedOutput();
      batchOperations = batchOperationCount(
        targetCount - operationsCompleted,
        Math.min(
          sourceStack.getCount(),
          menu.getSlot(0).getMaxStackSize(sourceStack)
        ),
        menu.getSlot(2).getMaxStackSize(expectedOutput),
        expectedOutput.getCount()
      );
      moveAmount(
        menu,
        source.getAsInt(),
        0,
        batchOperations
      );
      transition(Stage.LOAD_FUEL, "Checking furnace fuel");
    }

    private void loadFuel() {
      var menu = requireFurnaceMenu();
      Predicate<ItemStack> matchesFuel = stack ->
        stack.has(DataComponents.COOKING_FUEL)
          && (fuelSelector == null
          || InventoryServiceImpl.matches(stack, fuelSelector));
      var existingFuel = menu.getSlot(1).getItem();
      if (!existingFuel.isEmpty() && !matchesFuel.test(existingFuel)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Existing station fuel does not match the task fuel policy"
          )
          .asRuntimeException();
      }
      var source = findSource(
        menu,
        stack -> matchesFuel.test(stack)
          && (existingFuel.isEmpty()
          || ItemStack.isSameItemSameComponents(stack, existingFuel))
      );
      if (source.isEmpty()) {
        if (menu.isLit() || !existingFuel.isEmpty()) {
          transition(Stage.WAIT_FOR_OUTPUT, "Smelting");
          return;
        }
        throw Status.FAILED_PRECONDITION
          .withDescription("No matching valid furnace fuel remains")
          .asRuntimeException();
      }
      var sourceStack = menu.getSlot(source.getAsInt()).getItem();
      var fuelPrototype = existingFuel.isEmpty()
        ? sourceStack
        : existingFuel;
      var fuel = Objects.requireNonNull(fuelPrototype.get(DataComponents.COOKING_FUEL));
      // Let the server resolve contextual fuel values and nonstandard cooking speeds.
      var fuelTicks = fuel.burnTime() instanceof ResolvableInt.Constant burnTime
        && fuel.speedMultiplier() instanceof ResolvableFloat.Constant speed && speed.value() == 1.0F
        ? burnTime.value() : 0;
      var additionalFuel = fuelTicks <= 0 ? (existingFuel.isEmpty() && !menu.isLit() ? 1 : 0) : additionalFuelItems(
        batchOperations,
        recipe.display.duration(),
        remainingBurnTicks(menu),
        existingFuel.getCount(),
        fuelTicks
      );
      if (additionalFuel == 0) {
        transition(Stage.WAIT_FOR_OUTPUT, "Smelting");
        return;
      }
      var capacity = menu.getSlot(1).getMaxStackSize(sourceStack)
        - existingFuel.getCount();
      var transferAmount = Math.min(
        additionalFuel,
        Math.min(sourceStack.getCount(), capacity)
      );
      if (transferAmount == 0) {
        transition(Stage.WAIT_FOR_OUTPUT, "Smelting");
        return;
      }
      moveAmount(
        menu,
        source.getAsInt(),
        1,
        transferAmount
      );
      transition(Stage.WAIT_FOR_OUTPUT, "Smelting");
    }

    private void waitForOutput() {
      var menu = requireFurnaceMenu();
      var stack = menu.getSlot(2).getItem();
      if (!stack.isEmpty()) {
        if (!ItemStack.isSameItemSameComponents(
          stack,
          recipe.display.result().resolveForFirstStack(
            RecipeSupport.displayContext(context.bot())
          )
        )) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Smelting output does not match the selected recipe"
            )
            .asRuntimeException();
        }
        var expectedCount = Math.multiplyExact(
          batchOperations,
          expectedOutput().getCount()
        );
        if (stack.getCount() >= expectedCount) {
          outputs.add(stack.copy());
          transition(Stage.TAKE_OUTPUT, "Collecting smelted output");
          return;
        }
      }
      if (!menu.isLit() && !hasUsableFuel(menu)) {
        transition(Stage.LOAD_FUEL, "Refueling smelting station");
        return;
      }
      stageTicks++;
      if (stageTicks > COOK_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out waiting for smelting output")
          .asRuntimeException();
      }
    }

    private boolean hasUsableFuel(AbstractFurnaceMenu menu) {
      var stack = menu.getSlot(1).getItem();
      return stack.has(DataComponents.COOKING_FUEL)
        && (fuelSelector == null
        || InventoryServiceImpl.matches(stack, fuelSelector));
    }

    private void takeOutput() {
      var menu = requireFurnaceMenu();
      var player = requirePlayer();
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode
      );
      gameMode.handleContainerInput(
        menu.containerId,
        2,
        0,
        ContainerInput.PICKUP,
        player
      );
      transition(Stage.DEPOSIT_OUTPUT, "Storing smelted output");
    }

    private void depositOutput() {
      var menu = requireFurnaceMenu();
      var carried = menu.getCarried();
      if (carried.isEmpty()) {
        operationsCompleted += batchOperations;
        batchOperations = 0;
        transition(
          operationsCompleted >= targetCount
            ? Stage.WAIT_FOR_OUTPUT
            : Stage.LOAD_INPUT,
          operationsCompleted >= targetCount
            ? "Smelting complete"
            : "Loading next smelting input"
        );
        return;
      }
      var target = SFInventoryHelpers.playerInventorySlots(menu)
        .filter(slot -> canDeposit(menu, slot, carried))
        .findFirst();
      if (target.isEmpty()) {
        throw Status.RESOURCE_EXHAUSTED
          .withDescription("Inventory has no room for smelting output")
          .asRuntimeException();
      }
      Objects.requireNonNull(context.bot().minecraft().gameMode)
        .handleContainerInput(
          menu.containerId,
          target.getAsInt(),
          0,
          ContainerInput.PICKUP,
          requirePlayer()
        );
      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out storing smelting output")
          .asRuntimeException();
      }
    }

    private void moveAmount(
      AbstractFurnaceMenu menu,
      int source,
      int destination,
      int amount
    ) {
      if (amount <= 0) {
        throw Status.FAILED_PRECONDITION
          .withDescription("No room remains for the requested furnace batch")
          .asRuntimeException();
      }
      var sourceStack = menu.getSlot(source).getItem();
      var sourceCount = sourceStack.getCount();
      var destinationStack = menu.getSlot(destination).getItem();
      var destinationCapacity =
        menu.getSlot(destination).getMaxStackSize(sourceStack)
          - destinationStack.getCount();
      var transferCount = Math.min(
        amount,
        Math.min(sourceCount, destinationCapacity)
      );
      if (transferCount != amount) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "The selected furnace slot cannot hold the requested batch"
          )
          .asRuntimeException();
      }
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
      if (transferCount == sourceCount) {
        gameMode.handleContainerInput(
          menu.containerId,
          destination,
          0,
          ContainerInput.PICKUP,
          player
        );
      } else {
        for (var i = 0; i < transferCount; i++) {
          gameMode.handleContainerInput(
            menu.containerId,
            destination,
            1,
            ContainerInput.PICKUP,
            player
          );
        }
      }
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

    private ItemStack expectedOutput() {
      return recipe.display.result().resolveForFirstStack(
        RecipeSupport.displayContext(context.bot())
      );
    }

    private OptionalInt findSource(
      AbstractFurnaceMenu menu,
      Predicate<ItemStack> selector
    ) {
      var source = SFInventoryHelpers.playerInventorySlots(menu)
        .filter(slot -> selector.test(menu.getSlot(slot).getItem()))
        .findFirst();
      if (source.isPresent()) {
        return source;
      }

      var player = requirePlayer();
      var offhand = player.getOffhandItem();
      if (!selector.test(offhand)) {
        return OptionalInt.empty();
      }
      var stagingSlot = SFInventoryHelpers.playerInventorySlots(menu)
        .filter(slot -> {
          var menuSlot = menu.getSlot(slot);
          return menuSlot.getItem().isEmpty() && menuSlot.mayPlace(offhand);
        })
        .findFirst()
        .orElseThrow(() -> Status.RESOURCE_EXHAUSTED
          .withDescription(
            "Player inventory has no room to move the matching offhand item"
          )
          .asRuntimeException()
      );
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode
      );
      gameMode.handleContainerInput(
        menu.containerId,
        stagingSlot,
        Inventory.SLOT_OFFHAND,
        ContainerInput.SWAP,
        player
      );
      if (!selector.test(menu.getSlot(stagingSlot).getItem())) {
        throw Status.ABORTED
          .withDescription(
            "Matching offhand item did not move into the player inventory"
          )
          .asRuntimeException();
      }
      return OptionalInt.of(stagingSlot);
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

    private AbstractFurnaceMenu requireFurnaceMenu() {
      var menu = requirePlayer().containerMenu;
      if (menu instanceof AbstractFurnaceMenu furnaceMenu) {
        return furnaceMenu;
      }
      throw Status.FAILED_PRECONDITION
        .withDescription("The smelting station menu was closed")
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

    private void recoverFurnaceContents(
      net.minecraft.client.player.LocalPlayer player
    ) {
      if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
        return;
      }
      var gameMode = context.bot().minecraft().gameMode;
      if (gameMode == null) {
        return;
      }
      var carried = menu.getCarried();
      if (!carried.isEmpty()) {
        SFInventoryHelpers.playerInventorySlots(menu)
          .filter(slot -> canDeposit(menu, slot, carried))
          .findFirst()
          .ifPresent(slot -> gameMode.handleContainerInput(
            menu.containerId,
            slot,
            0,
            ContainerInput.PICKUP,
            player
          ));
      }
      if (!menu.getCarried().isEmpty()) {
        return;
      }
      for (var slot : List.of(2, 0, 1)) {
        if (!menu.getSlot(slot).getItem().isEmpty()) {
          gameMode.handleContainerInput(
            menu.containerId,
            slot,
            0,
            ContainerInput.QUICK_MOVE,
            player
          );
        }
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
        .setCurrent(operationsCompleted)
        .setTotal(targetCount)
        .setFraction(Math.min(
          1.0,
          (double) operationsCompleted / targetCount
        ))
        .build();
    }

    private void complete() {
      result.complete(SmeltTaskResult.newBuilder()
        .addAllOutputs(outputs.stream()
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
    public void onSuspended() {
      if (activePath != null) {
        activePath.onSuspended();
      }
    }

    @Override
    public void onResumed() {
      if (activePath != null) {
        activePath.onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      stopPath(reason, cause);
      var player = context.bot().minecraft().player;
      try {
        if (player != null) {
          recoverFurnaceContents(player);
        }
      } finally {
        if (
          player != null
            && !(player.containerMenu instanceof InventoryMenu)
        ) {
          player.closeContainer();
        }
        if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
          result.cancel(true);
        }
      }
    }

    @Override
    public String description() {
      return "Smelt items";
    }
  }

  private static String menuStation(AbstractFurnaceMenu menu) {
    var menuId = BuiltInRegistries.MENU.getKey(menu.getType()).toString();
    return switch (menuId) {
      case "minecraft:furnace" -> "minecraft:furnace";
      case "minecraft:blast_furnace" -> "minecraft:blast_furnace";
      case "minecraft:smoker" -> "minecraft:smoker";
      default -> menuId;
    };
  }

  private static int remainingBurnTicks(AbstractFurnaceMenu menu) {
    return Math.max(
      0,
      ((AbstractFurnaceMenuAccessor) menu).soulfire$getData().get(0)
    );
  }

  private record RecipeSelection(
    RecipeDisplayEntry entry,
    FurnaceRecipeDisplay display,
    Predicate<ItemStack> acceptsInput
  ) {
  }

  static int batchOperationCount(
    int remainingOperations,
    int inputCapacity,
    int outputCapacity,
    int outputPerOperation
  ) {
    if (
      remainingOperations <= 0
        || inputCapacity <= 0
        || outputCapacity <= 0
        || outputPerOperation <= 0
    ) {
      throw new IllegalArgumentException(
        "Batch capacities and operation counts must be positive"
      );
    }
    return Math.min(
      remainingOperations,
      Math.min(inputCapacity, outputCapacity / outputPerOperation)
    );
  }

  static int additionalFuelItems(
    int operations,
    int cookingTimeTicks,
    int remainingBurnTicks,
    int existingFuelItems,
    int fuelTicksPerItem
  ) {
    if (
      operations <= 0
        || cookingTimeTicks <= 0
        || remainingBurnTicks < 0
        || existingFuelItems < 0
        || fuelTicksPerItem <= 0
    ) {
      throw new IllegalArgumentException(
        "Fuel calculation inputs must be positive"
      );
    }
    var requiredTicks = (long) operations * cookingTimeTicks;
    var availableTicks = remainingBurnTicks
      + (long) existingFuelItems * fuelTicksPerItem;
    return (int) Math.max(
      0,
      Math.ceilDiv(requiredTicks - availableTicks, fuelTicksPerItem)
    );
  }

  private enum Stage {
    NAVIGATE,
    OPEN_MENU,
    VALIDATE_MENU,
    UNLOAD_FUEL,
    LOAD_INPUT,
    LOAD_FUEL,
    WAIT_FOR_OUTPUT,
    TAKE_OUTPUT,
    DEPOSIT_OUTPUT
  }
}
