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

import com.soulfiremc.grpc.generated.AutoEatCompletionReason;
import com.soulfiremc.grpc.generated.AutoEatTask;
import com.soulfiremc.grpc.generated.AutoEatTaskResult;
import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.CompletableControlTask;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.util.SFInventoryHelpers;
import com.soulfiremc.server.util.SFItemHelpers;
import io.grpc.Status;
import net.minecraft.world.item.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/// Core resource-aware automatic eating provider.
public final class AutoEatTaskProvider
  implements BotTaskProvider<AutoEatTask> {
  private static final int DEFAULT_FOOD_LEVEL = 14;
  private static final int DEFAULT_CHECK_INTERVAL_TICKS = 20;
  private static final int MAX_CHECK_INTERVAL_TICKS = 1_200;
  private static final int MAX_FAILED_MEAL_ATTEMPTS = 3;
  // Monitoring does not own a mutable bot resource. Each meal claims only
  // the hand and inventory resources it needs while it is being consumed.
  private static final Set<ControlResource> TASK_RESOURCES = Set.of();


  @Override
  public AutoEatTask inputPrototype() {
    return AutoEatTask.getDefaultInstance();
  }

  @Override
  public String summary(AutoEatTask input) {
    return input.getMaximumMeals() == 0
      ? "Automatically eat when hungry"
      : "Eat up to " + input.getMaximumMeals() + " meals when hungry";
  }

  @Override
  public Set<ControlResource> resources(AutoEatTask input) {
    return TASK_RESOURCES;
  }

  @Override
  public BotTaskExecution start(
    BotTaskContext context,
    AutoEatTask input
  ) {
    var foodLevel = input.getFoodLevel() == 0
      ? DEFAULT_FOOD_LEVEL
      : Math.min(input.getFoodLevel(), 20);
    var interval = input.getCheckIntervalTicks() == 0
      ? DEFAULT_CHECK_INTERVAL_TICKS
      : Math.min(
        input.getCheckIntervalTicks(),
        MAX_CHECK_INTERVAL_TICKS
      );
    var result = new CompletableFuture<AutoEatTaskResult>();
    var control = new AutoEatControl(
      context,
      Set.copyOf(input.getFoodItemIdsList()),
      foodLevel,
      interval,
      input.getMaximumMeals(),
      input.getCompleteWhenNoFood(),
      input.getRestoreSelectedSlot(),
      result
    );
    return new BotTaskExecution(control, result);
  }

  private static final class AutoEatControl implements ControlTask {
    private final BotTaskContext context;
    private final Set<String> foodItemIds;
    private final int foodLevel;
    private final int checkIntervalTicks;
    private final int maximumMeals;
    private final boolean completeWhenNoFood;
    private final boolean restoreSelectedSlot;
    private final CompletableFuture<AutoEatTaskResult> result;
    private @Nullable ActiveMeal activeMeal;
    private int mealsEaten;
    private int failedMealAttempts;
    private int ticks;

    private AutoEatControl(
      BotTaskContext context,
      Set<String> foodItemIds,
      int foodLevel,
      int checkIntervalTicks,
      int maximumMeals,
      boolean completeWhenNoFood,
      boolean restoreSelectedSlot,
      CompletableFuture<AutoEatTaskResult> result
    ) {
      this.context = context;
      this.foodItemIds = foodItemIds;
      this.foodLevel = foodLevel;
      this.checkIntervalTicks = checkIntervalTicks;
      this.maximumMeals = maximumMeals;
      this.completeWhenNoFood = completeWhenNoFood;
      this.restoreSelectedSlot = restoreSelectedSlot;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      ticks++;
      // A dead bot cannot eat, and waiting for it hung a live run's action
      // for ten minutes on the death screen.
      var currentPlayer = context.bot().minecraft().player;
      if (currentPlayer != null && currentPlayer.isDeadOrDying()) {
        abortForPlayerDeath();
        return;
      }
      if (finishActiveMeal()) {
        return;
      }
      if (activeMeal != null || ticks % checkIntervalTicks != 0) {
        return;
      }

      var bot = context.bot();
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player == null || gameMode == null) {
        return;
      }
      if (player.isDeadOrDying()) {
        return;
      }
      var currentFood = player.getFoodData().getFoodLevel();
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage(currentFood <= foodLevel
          ? "Looking for food"
          : "Monitoring hunger")
        .setCurrent(mealsEaten)
        .build());
      if (currentFood > foodLevel) {
        if (maximumMeals > 0) {
          complete(
            AutoEatCompletionReason
              .AUTO_EAT_COMPLETION_REASON_FOOD_LEVEL_REACHED
          );
        }
        return;
      }
      if (player.hasContainerOpen() || !player.inventoryMenu.getCarried().isEmpty()) {
        return;
      }

      var menu = player.inventoryMenu;
      var slot = SFInventoryHelpers.findMatchingSlotForAction(
        player.getInventory(),
        menu,
        stack -> isPermittedFood(stack, foodItemIds)
      );
      if (slot.isEmpty()) {
        if (completeWhenNoFood) {
          complete(AutoEatCompletionReason.AUTO_EAT_COMPLETION_REASON_NO_FOOD);
        }
        return;
      }

      var consumption = new EatMealTask(bot, slot.getAsInt(), restoreSelectedSlot, ControlPriority.HIGH);
      var meal = new CompletableControlTask(consumption);
      if (bot.botControl().submit(meal)) {
        activeMeal = new ActiveMeal(consumption, meal);
      }
    }

    private boolean finishActiveMeal() {
      var meal = activeMeal;
      if (meal == null || !meal.control().completion().isDone()) {
        return false;
      }
      activeMeal = null;
      if (meal.control().completion().isCompletedExceptionally()) {
        failedMealAttempts++;
        if (failedMealAttempts >= MAX_FAILED_MEAL_ATTEMPTS) {
          result.completeExceptionally(
            Status.FAILED_PRECONDITION
              .withDescription(
                "Food use completed without a confirmed meal"
              )
              .asRuntimeException()
          );
        }
        return true;
      }
      if (meal.consumption().consumed()) {
        failedMealAttempts = 0;
        mealsEaten++;
        if (maximumMeals > 0 && mealsEaten >= maximumMeals) {
          complete(
            AutoEatCompletionReason
              .AUTO_EAT_COMPLETION_REASON_MEAL_LIMIT_REACHED
          );
          return true;
        }
      }
      return false;
    }

    private void abortForPlayerDeath() {
      var meal = activeMeal;
      activeMeal = null;
      if (meal != null) {
        context.bot().botControl().cancel(meal.control());
      }
      result.completeExceptionally(
        Status.FAILED_PRECONDITION
          .withDescription("Player died while eating")
          .asRuntimeException()
      );
    }

    private void complete(AutoEatCompletionReason reason) {
      var player = context.bot().minecraft().player;
      result.complete(AutoEatTaskResult.newBuilder()
        .setReason(reason)
        .setMealsEaten(mealsEaten)
        .setFinalFoodLevel(player == null
          ? 0
          : player.getFoodData().getFoodLevel())
        .build());
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public Set<ControlResource> resources() {
      return TASK_RESOURCES;
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var meal = activeMeal;
      activeMeal = null;
      if (meal != null) {
        context.bot().botControl().cancel(meal.control());
      }
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Auto eat";
    }
  }

  private record ActiveMeal(EatMealTask consumption, CompletableControlTask control) {}

  static boolean isPermittedFood(
    ItemStack stack,
    Set<String> foodItemIds
  ) {
    return isPermittedFood(
      SFItemHelpers.isEdibleFood(stack),
      SFItemHelpers.isGoodEdibleFood(stack),
      stack.typeHolder().getRegisteredName(),
      foodItemIds
    );
  }

  static boolean isPermittedFood(
    boolean edible,
    boolean safeEdible,
    String itemId,
    Set<String> foodItemIds
  ) {
    return foodItemIds.isEmpty()
      ? safeEdible
      : edible && foodItemIds.contains(itemId);
  }

}
