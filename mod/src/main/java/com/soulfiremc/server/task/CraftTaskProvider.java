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
import com.soulfiremc.grpc.generated.CraftTask;
import com.soulfiremc.grpc.generated.CraftTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Executes ordinary shaped and shapeless recipes through the recipe book.
public final class CraftTaskProvider implements BotTaskProvider<CraftTask> {
  private static final int MAX_CRAFT_OPERATIONS = 4_096;
  private static final int MENU_TIMEOUT_TICKS = 100;
  private static final int INVENTORY_SYNC_TICKS = 2;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY,
    ControlResource.CONTAINER
  );

  @Override
  public CraftTask inputPrototype() {
    return CraftTask.getDefaultInstance();
  }

  @Override
  public String summary(CraftTask input) {
    return "Craft " + Math.max(1, input.getCount())
      + " operation(s) of " + input.getRecipeId();
  }

  @Override
  public Set<ControlResource> resources(CraftTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, CraftTask input) {
    if (input.getRecipeId().isBlank()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("recipe_id is required")
        .asRuntimeException();
    }
    var count = input.getCount() <= 0 ? 1 : input.getCount();
    if (count > MAX_CRAFT_OPERATIONS) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "count may not exceed " + MAX_CRAFT_OPERATIONS
        )
        .asRuntimeException();
    }
    var entry = RecipeSupport.find(context.bot(), input.getRecipeId());
    requireCraftingRecipe(entry);
    if (entry.craftingRequirements().isEmpty()) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "This special recipe cannot be executed from fixed ingredients"
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

    var inventoryRecipe = RecipeSupport.canCraftInInventory(entry);
    BlockPos station = null;
    if (!inventoryRecipe) {
      if (input.hasStation()) {
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
              "Crafting station is in '%s', but the bot is in '%s'"
                .formatted(requested.getDimension(), currentDimension)
            )
            .asRuntimeException();
        }
        station = new BlockPos(
          requested.getX(),
          requested.getY(),
          requested.getZ()
        );
      } else if (!(player.containerMenu instanceof CraftingMenu)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "station is required for a crafting-table recipe unless a crafting table is already open"
          )
          .asRuntimeException();
      }
    }

    var result = new CompletableFuture<CraftTaskResult>();
    var control = new CraftControl(
      context,
      entry,
      RecipeSupport.result(context.bot(), entry),
      count,
      inventoryRecipe,
      station,
      result
    );
    return new BotTaskExecution(control, result);
  }

  private static final class CraftControl implements ControlTask {
    private final BotTaskContext context;
    private final RecipeDisplayEntry recipe;
    private final ItemStack output;
    private final int targetCount;
    private final boolean inventoryRecipe;
    private final @Nullable BlockPos station;
    private final CompletableFuture<CraftTaskResult> result;
    private @Nullable PathExecutor activePath;
    private Stage stage;
    private int stageTicks;
    private int syncStateId;
    private int syncStableTicks;
    private int crafted;
    private boolean ingredientsChecked;

    private CraftControl(
      BotTaskContext context,
      RecipeDisplayEntry recipe,
      ItemStack output,
      int targetCount,
      boolean inventoryRecipe,
      @Nullable BlockPos station,
      CompletableFuture<CraftTaskResult> result
    ) {
      this.context = context;
      this.recipe = recipe;
      this.output = output;
      this.targetCount = targetCount;
      this.inventoryRecipe = inventoryRecipe;
      this.station = station;
      this.result = result;
      this.stage = inventoryRecipe || station == null
        ? Stage.OPEN_MENU
        : Stage.PREPARE_INVENTORY;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        if (!ingredientsChecked) {
          // On the first tick, not in start(): a queued task runs later.
          requireIngredients(
            recipe.craftingRequirements().orElseThrow(),
            availableIngredients(requirePlayer()),
            targetCount,
            "display:" + recipe.id().index()
          );
          ingredientsChecked = true;
        }
        if (crafted >= targetCount) {
          complete();
          return;
        }
        switch (stage) {
          case PREPARE_INVENTORY -> prepareInventory();
          case WAIT_FOR_INVENTORY_SYNC -> waitForInventorySync();
          case NAVIGATE -> navigate();
          case OPEN_MENU -> openMenu();
          case CLEAR_GRID -> clearGrid();
          case PLACE_INGREDIENTS -> placeIngredients();
          case WAIT_FOR_INGREDIENT_SYNC -> waitForIngredientSync();
          case WAIT_FOR_RESULT -> waitForResult();
          case TAKE_RESULT -> takeResult();
          case DEPOSIT_RESULT -> depositResult();
        }
      } catch (Throwable throwable) {
        fail(throwable);
      }
    }

    private void prepareInventory() {
      var player = requirePlayer();
      if (!(player.containerMenu instanceof InventoryMenu menu)) {
        player.closeContainer();
        stageTicks++;
        if (stageTicks > MENU_TIMEOUT_TICKS) {
          throw Status.DEADLINE_EXCEEDED
            .withDescription(
              "Timed out preparing the player inventory for crafting"
            )
            .asRuntimeException();
        }
        return;
      }

      var offhand = menu.getSlot(InventoryMenu.SHIELD_SLOT).getItem();
      if (offhand.isEmpty() || !recipeAccepts(offhand)) {
        transition(Stage.NAVIGATE, "Navigating to crafting table");
        return;
      }

      handleContainerInput(
        menu,
        InventoryMenu.SHIELD_SLOT,
        0,
        ContainerInput.QUICK_MOVE,
        player
      );
      syncStateId = menu.getStateId();
      syncStableTicks = 0;
      transition(
        Stage.WAIT_FOR_INVENTORY_SYNC,
        "Making offhand ingredients accessible"
      );
    }

    private void waitForInventorySync() {
      var player = requirePlayer();
      if (!(player.containerMenu instanceof InventoryMenu menu)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "The player inventory was closed while preparing to craft"
          )
          .asRuntimeException();
      }
      var stateId = menu.getStateId();
      if (stateId != syncStateId) {
        syncStateId = stateId;
        syncStableTicks = 0;
      } else {
        syncStableTicks++;
      }

      if (syncStableTicks >= INVENTORY_SYNC_TICKS) {
        var offhand = menu.getSlot(InventoryMenu.SHIELD_SLOT).getItem();
        if (!offhand.isEmpty() && recipeAccepts(offhand)) {
          throw Status.RESOURCE_EXHAUSTED
            .withDescription(
              "Inventory has no room to move an offhand recipe ingredient"
            )
            .asRuntimeException();
        }
        transition(Stage.NAVIGATE, "Navigating to crafting table");
        return;
      }

      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription(
            "Timed out synchronizing an offhand recipe ingredient"
          )
          .asRuntimeException();
      }
    }

    private void navigate() {
      var stationPosition = Objects.requireNonNull(station);
      var player = requirePlayer();
      if (player.position().distanceToSqr(Vec3.atCenterOf(stationPosition)) <= 9) {
        stopPath(ControlStopReason.CANCELLED, null);
        transition(Stage.OPEN_MENU, "Opening crafting table");
        return;
      }
      if (activePath == null) {
        context.reportProgress(progress("Planning route to crafting table"));
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
            ? "Planning route to crafting table"
            : "Walking to crafting table"
        ));
        return;
      }
      var path = activePath;
      activePath = null;
      try {
        path.completion().join();
        path.onStopped(ControlStopReason.COMPLETED, null);
        transition(Stage.OPEN_MENU, "Opening crafting table");
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
      if (inventoryRecipe) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
          player.closeContainer();
        }
        transition(Stage.CLEAR_GRID, "Preparing recipe");
        return;
      }
      if (player.containerMenu instanceof CraftingMenu) {
        transition(Stage.CLEAR_GRID, "Preparing recipe");
        return;
      }
      var stationPosition = Objects.requireNonNull(station);
      var level = Objects.requireNonNull(
        context.bot().minecraft().level,
        "Bot level is not available"
      );
      var expectedStation = RecipeSupport.requiredStation(
        context.bot(),
        recipe
      );
      var actualStation = BuiltInRegistries.BLOCK
        .getKey(level.getBlockState(stationPosition).getBlock())
        .toString();
      if (!expectedStation.equals(actualStation)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Expected %s at the crafting station, found %s"
              .formatted(expectedStation, actualStation)
          )
          .asRuntimeException();
      }
      if (stageTicks % 10 == 0) {
        var gameMode = Objects.requireNonNull(
          context.bot().minecraft().gameMode,
          "Bot game mode is not available"
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
          .withDescription("Timed out opening the crafting table")
          .asRuntimeException();
      }
    }

    private void clearGrid() {
      var player = requirePlayer();
      var menu = requireCraftingMenu(player.containerMenu);
      if (!menu.getCarried().isEmpty()) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "The inventory cursor must be empty before preparing a recipe"
          )
          .asRuntimeException();
      }
      var occupiedInput = craftingMenu(menu).getInputGridSlots().stream()
        .mapToInt(menu.slots::indexOf)
        .filter(slotIndex -> !menu.getSlot(slotIndex).getItem().isEmpty())
        .findFirst();
      if (occupiedInput.isEmpty()) {
        transition(Stage.PLACE_INGREDIENTS, "Placing recipe ingredients");
        return;
      }
      handleContainerInput(
        menu,
        occupiedInput.getAsInt(),
        0,
        ContainerInput.QUICK_MOVE,
        player
      );
      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out clearing the crafting grid")
          .asRuntimeException();
      }
    }

    private void placeIngredients() {
      var player = requirePlayer();
      var menu = requireCraftingMenu(player.containerMenu);
      if (!menu.getCarried().isEmpty()) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "The inventory cursor was not cleared after placing an ingredient"
          )
          .asRuntimeException();
      }
      var placement = missingIngredientPlacement(menu);
      if (placement == null) {
        transition(Stage.WAIT_FOR_RESULT, "Waiting for crafted result");
        return;
      }
      handleContainerInput(
        menu,
        placement.sourceSlot(),
        0,
        ContainerInput.PICKUP,
        player
      );
      var carried = menu.getCarried();
      if (carried.isEmpty()) {
        throw Status.ABORTED
          .withDescription("The selected recipe ingredient could not be picked up")
          .asRuntimeException();
      }
      if (!placement.matches(carried)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "The selected inventory item no longer matches the recipe ingredient"
          )
          .asRuntimeException();
      }

      for (var ingredient : ingredientPlacements(craftingMenu(menu), menu)) {
        carried = menu.getCarried();
        if (carried.isEmpty()) {
          break;
        }
        var target = menu.getSlot(ingredient.targetSlot()).getItem();
        if (ingredient.matches(target)) {
          continue;
        }
        if (!target.isEmpty()) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "The crafting grid contains an item that does not match the recipe"
            )
            .asRuntimeException();
        }
        if (ingredient.matches(carried)) {
          handleContainerInput(
            menu,
            ingredient.targetSlot(),
            1,
            ContainerInput.PICKUP,
            player
          );
        }
      }

      if (!menu.getCarried().isEmpty()) {
        handleContainerInput(
          menu,
          placement.sourceSlot(),
          0,
          ContainerInput.PICKUP,
          player
        );
      }
      if (!menu.getCarried().isEmpty()) {
        throw Status.ABORTED
          .withDescription(
            "The recipe ingredient transaction left an item on the inventory cursor"
          )
          .asRuntimeException();
      }

      syncStateId = menu.getStateId();
      syncStableTicks = 0;
      transition(
        Stage.WAIT_FOR_INGREDIENT_SYNC,
        "Synchronizing recipe ingredients"
      );
    }

    private void waitForIngredientSync() {
      var player = requirePlayer();
      var menu = requireCraftingMenu(player.containerMenu);
      var stateId = menu.getStateId();
      if (stateId != syncStateId) {
        syncStateId = stateId;
        syncStableTicks = 0;
      } else {
        syncStableTicks++;
      }

      if (syncStableTicks >= INVENTORY_SYNC_TICKS) {
        var carried = menu.getCarried();
        if (!carried.isEmpty()) {
          var target = SFInventoryHelpers.playerInventorySlots(menu)
            .filter(slot -> canDeposit(menu, slot, carried))
            .findFirst()
            .orElseThrow(() -> Status.RESOURCE_EXHAUSTED
              .withDescription(
                "Inventory has no room to clear the crafting cursor"
              )
              .asRuntimeException()
            );
          handleContainerInput(
            menu,
            target,
            0,
            ContainerInput.PICKUP,
            player
          );
          syncStateId = menu.getStateId();
          syncStableTicks = 0;
        } else {
          transition(Stage.PLACE_INGREDIENTS, "Placing recipe ingredients");
          return;
        }
      }

      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out synchronizing recipe ingredients")
          .asRuntimeException();
      }
    }

    private void waitForResult() {
      var menu = requireCraftingMenu(requirePlayer().containerMenu);
      var resultStack = menu.getSlot(0).getItem();
      if (!resultStack.isEmpty()) {
        if (!ItemStack.isSameItemSameComponents(resultStack, output)) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "The crafting result does not match the selected recipe"
            )
            .asRuntimeException();
        }
        transition(Stage.TAKE_RESULT, "Taking crafted result");
        return;
      }
      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Recipe result did not appear; ingredients may be missing"
          )
          .asRuntimeException();
      }
    }

    private @Nullable IngredientPlacement missingIngredientPlacement(
      AbstractContainerMenu menu
    ) {
      var placements = ingredientPlacements(craftingMenu(menu), menu);
      for (var ingredient : placements) {
        var target = menu.getSlot(ingredient.targetSlot()).getItem();
        if (ingredient.matches(target)) {
          continue;
        }
        if (!target.isEmpty()) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "The crafting grid contains an item that does not match the recipe"
            )
            .asRuntimeException();
        }
        var source = SFInventoryHelpers.playerInventorySlots(menu)
          .filter(slotIndex ->
            ingredient.matches(menu.getSlot(slotIndex).getItem()))
          .findFirst();
        if (source.isEmpty()) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "A recipe ingredient is no longer available in the player inventory"
            )
            .asRuntimeException();
        }
        return new IngredientPlacement(
          source.getAsInt(),
          ingredient.targetSlot(),
          ingredient.acceptedStacks()
        );
      }
      return null;
    }

    private List<TargetIngredient> ingredientPlacements(
      AbstractCraftingMenu craftingMenu,
      AbstractContainerMenu menu
    ) {
      var displayContext = RecipeSupport.displayContext(context.bot());
      var inputSlots = craftingMenu.getInputGridSlots();
      var gridWidth = craftingMenu.getGridWidth();
      var result = new ArrayList<TargetIngredient>();
      switch (recipe.display()) {
        case ShapedCraftingRecipeDisplay shaped -> {
          for (var index = 0; index < shaped.ingredients().size(); index++) {
            var accepted = acceptedStacks(
              shaped.ingredients().get(index),
              displayContext
            );
            if (accepted.isEmpty()) {
              continue;
            }
            var row = index / shaped.width();
            var column = index % shaped.width();
            var gridIndex = row * gridWidth + column;
            result.add(new TargetIngredient(
              menu.slots.indexOf(inputSlots.get(gridIndex)),
              accepted
            ));
          }
        }
        case ShapelessCraftingRecipeDisplay shapeless -> {
          var gridIndex = 0;
          for (var ingredient : shapeless.ingredients()) {
            var accepted = acceptedStacks(ingredient, displayContext);
            if (accepted.isEmpty()) {
              continue;
            }
            result.add(new TargetIngredient(
              menu.slots.indexOf(inputSlots.get(gridIndex)),
              accepted
            ));
            gridIndex++;
          }
        }
        default -> throw Status.FAILED_PRECONDITION
          .withDescription("The selected recipe is not a crafting recipe")
          .asRuntimeException();
      }
      return List.copyOf(result);
    }

    private boolean recipeAccepts(ItemStack stack) {
      var displayContext = RecipeSupport.displayContext(context.bot());
      return switch (recipe.display()) {
        case ShapedCraftingRecipeDisplay shaped ->
          shaped.ingredients().stream()
            .map(ingredient -> acceptedStacks(ingredient, displayContext))
            .anyMatch(accepted -> ingredientMatches(accepted, stack));
        case ShapelessCraftingRecipeDisplay shapeless ->
          shapeless.ingredients().stream()
            .map(ingredient -> acceptedStacks(ingredient, displayContext))
            .anyMatch(accepted -> ingredientMatches(accepted, stack));
        default -> false;
      };
    }

    private static List<ItemStack> acceptedStacks(
      SlotDisplay ingredient,
      net.minecraft.util.context.ContextMap displayContext
    ) {
      return ingredient.resolveForStacks(displayContext).stream()
        .filter(stack -> !stack.isEmpty())
        .toList();
    }

    private static AbstractCraftingMenu craftingMenu(
      AbstractContainerMenu menu
    ) {
      if (menu instanceof AbstractCraftingMenu craftingMenu) {
        return craftingMenu;
      }
      throw Status.FAILED_PRECONDITION
        .withDescription("The required crafting menu was closed")
        .asRuntimeException();
    }

    private void handleContainerInput(
      AbstractContainerMenu menu,
      int slot,
      int button,
      ContainerInput input,
      net.minecraft.client.player.LocalPlayer player
    ) {
      Objects.requireNonNull(
        context.bot().minecraft().gameMode,
        "Bot game mode is not available"
      ).handleContainerInput(
        menu.containerId,
        slot,
        button,
        input,
        player
      );
    }

    private void takeResult() {
      var player = requirePlayer();
      var menu = requireCraftingMenu(player.containerMenu);
      handleContainerInput(
        menu,
        0,
        0,
        ContainerInput.PICKUP,
        player
      );
      transition(Stage.DEPOSIT_RESULT, "Storing crafted result");
    }

    private void depositResult() {
      var player = requirePlayer();
      var menu = requireCraftingMenu(player.containerMenu);
      var carried = menu.getCarried();
      if (carried.isEmpty()) {
        crafted++;
        transition(Stage.CLEAR_GRID, "Preparing next craft");
        return;
      }
      var target = SFInventoryHelpers.playerInventorySlots(menu)
        .filter(slot -> canDeposit(menu, slot, carried))
        .findFirst();
      if (target.isEmpty()) {
        throw Status.RESOURCE_EXHAUSTED
          .withDescription("Inventory has no room for the crafted result")
          .asRuntimeException();
      }
      handleContainerInput(
        menu,
        target.getAsInt(),
        0,
        ContainerInput.PICKUP,
        player
      );
      stageTicks++;
      if (stageTicks > MENU_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out storing the crafted result")
          .asRuntimeException();
      }
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

    private AbstractContainerMenu requireCraftingMenu(
      AbstractContainerMenu menu
    ) {
      if (inventoryRecipe && menu instanceof InventoryMenu) {
        return menu;
      }
      if (!inventoryRecipe && menu instanceof CraftingMenu) {
        return menu;
      }
      throw Status.FAILED_PRECONDITION
        .withDescription("The required crafting menu was closed")
        .asRuntimeException();
    }

    private net.minecraft.client.player.LocalPlayer requirePlayer() {
      return Objects.requireNonNull(
        context.bot().minecraft().player,
        "Bot player is not available"
      );
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

    private void recoverCraftingContents(
      net.minecraft.client.player.LocalPlayer player
    ) {
      if (!(player.containerMenu instanceof AbstractCraftingMenu menu)) {
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
      for (var inputSlot : menu.getInputGridSlots()) {
        if (!inputSlot.getItem().isEmpty()) {
          gameMode.handleContainerInput(
            menu.containerId,
            menu.slots.indexOf(inputSlot),
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
        .setCurrent(crafted)
        .setTotal(targetCount)
        .setFraction(Math.min(1.0, (double) crafted / targetCount))
        .build();
    }

    private void complete() {
      result.complete(CraftTaskResult.newBuilder()
        .setResult(MinecraftDomainMapper.item(output))
        .setCrafted(crafted)
        .build());
    }

    private void fail(Throwable throwable) {
      var player = context.bot().minecraft().player;
      if (player != null) {
        try {
          recoverCraftingContents(player);
        } catch (Throwable recoveryFailure) {
          throwable.addSuppressed(recoveryFailure);
        }
      }
      result.completeExceptionally(throwable);
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
          recoverCraftingContents(player);
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
      return "Craft recipe";
    }
  }

  private enum Stage {
    PREPARE_INVENTORY,
    WAIT_FOR_INVENTORY_SYNC,
    NAVIGATE,
    OPEN_MENU,
    CLEAR_GRID,
    PLACE_INGREDIENTS,
    WAIT_FOR_INGREDIENT_SYNC,
    WAIT_FOR_RESULT,
    TAKE_RESULT,
    DEPOSIT_RESULT
  }

  private record TargetIngredient(
    int targetSlot,
    List<ItemStack> acceptedStacks
  ) {
    private boolean matches(ItemStack stack) {
      return ingredientMatches(acceptedStacks, stack);
    }
  }

  private record IngredientPlacement(
    int sourceSlot,
    int targetSlot,
    List<ItemStack> acceptedStacks
  ) {
    private boolean matches(ItemStack stack) {
      return ingredientMatches(acceptedStacks, stack);
    }
  }

  static void requireCraftingRecipe(RecipeDisplayEntry entry) {
    if (!RecipeSupport.isCraftingRecipe(entry)) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "display:%d is a %s recipe; CraftTask supports minecraft:crafting_shaped and minecraft:crafting_shapeless recipes"
            .formatted(entry.id().index(), RecipeSupport.type(entry))
        )
        .asRuntimeException();
    }
  }

  /// Stacks the task can use: the inventory, the off-hand and the crafting grids.
  private static List<ItemStack> availableIngredients(
    net.minecraft.client.player.LocalPlayer player
  ) {
    var stacks = new ArrayList<>(player.getInventory().getNonEquipmentItems());
    stacks.add(player.getOffhandItem());
    player.inventoryMenu.getInputGridSlots()
      .forEach(slot -> stacks.add(slot.getItem()));
    if (player.containerMenu instanceof CraftingMenu menu) {
      menu.getInputGridSlots().forEach(slot -> stacks.add(slot.getItem()));
    }
    return stacks;
  }

  /// Fails unless the stacks hold the ingredients for `count` crafts.
  static void requireIngredients(
    List<Ingredient> requirements,
    List<ItemStack> available,
    int count,
    String recipeId
  ) {
    var maximum = maximumCrafts(requirements, available);
    if (maximum < count) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "Not enough ingredients for %s: count is %d, the bot has enough for %d"
            .formatted(recipeId, count, maximum)
        )
        .asRuntimeException();
    }
  }

  /// How many crafts the stacks cover, one craft at a time, so each craft may use another
  /// alternative of an ingredient. Every set of ingredients needs that many accepted items
  /// per ingredient in the set (Hall's theorem), so the answer is the smallest such ratio.
  private static int maximumCrafts(
    List<Ingredient> requirements,
    List<ItemStack> available
  ) {
    var maximum = Long.MAX_VALUE;
    for (var set = 1; set < 1 << requirements.size(); set++) {
      var supply = 0L;
      for (var stack : available) {
        for (var index = 0; index < requirements.size(); index++) {
          if ((set & 1 << index) != 0 && requirements.get(index).test(stack)) {
            supply += stack.getCount();
            break;
          }
        }
      }
      maximum = Math.min(maximum, supply / Integer.bitCount(set));
    }
    return (int) Math.min(maximum, Integer.MAX_VALUE);
  }

  static boolean ingredientMatches(
    List<ItemStack> acceptedStacks,
    ItemStack stack
  ) {
    return !stack.isEmpty() && acceptedStacks.stream()
      .anyMatch(accepted -> ItemStack.isSameItem(accepted, stack));
  }
}
