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
import com.soulfiremc.grpc.generated.FishCompletionReason;
import com.soulfiremc.grpc.generated.FishTask;
import com.soulfiremc.grpc.generated.FishTaskResult;
import com.soulfiremc.grpc.generated.ItemSelector;
import com.soulfiremc.mod.mixin.soulfire.FishingHookAccessor;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.grpc.InventoryServiceImpl;
import io.grpc.Status;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FishingRodItem;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/// Owns fishing-rod selection, cast timing, bite detection, retrieval, and
/// repetition on the bot game thread.
public final class FishTaskProvider implements BotTaskProvider<FishTask> {
  private static final int DEFAULT_CAST_TIMEOUT_TICKS = 100;
  private static final int MAX_CAST_TIMEOUT_TICKS = 1_200;
  private static final int DEFAULT_BITE_TIMEOUT_TICKS = 12_000;
  private static final int MAX_BITE_TIMEOUT_TICKS = 72_000;
  private static final int OUT_OF_WATER_RECAST_TICKS = 40;
  private static final int RECAST_DELAY_TICKS = 10;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public FishTask inputPrototype() {
    return FishTask.getDefaultInstance();
  }

  @Override
  public String summary(FishTask input) {
    return input.getMaximumCatches() == 0
      ? "Fish until cancelled"
      : "Catch up to " + input.getMaximumCatches() + " fish";
  }

  @Override
  public Set<ControlResource> resources(FishTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, FishTask input) {
    var castTimeout = input.getCastTimeoutTicks() == 0
      ? DEFAULT_CAST_TIMEOUT_TICKS
      : Math.min(input.getCastTimeoutTicks(), MAX_CAST_TIMEOUT_TICKS);
    var biteTimeout = input.getBiteTimeoutTicks() == 0
      ? DEFAULT_BITE_TIMEOUT_TICKS
      : Math.min(input.getBiteTimeoutTicks(), MAX_BITE_TIMEOUT_TICKS);
    var result = new CompletableFuture<FishTaskResult>();
    return new BotTaskExecution(
      new FishControl(
        context,
        input.getMaximumCatches(),
        input.getMaximumFailedCasts(),
        input.hasRod() ? input.getRod() : null,
        castTimeout,
        biteTimeout,
        input.getCompleteWhenNoRod(),
        input.getRestoreSelectedSlot(),
        result
      ),
      result
    );
  }

  private static final class FishControl implements ControlTask {
    private final BotTaskContext context;
    private final int maximumCatches;
    private final int maximumFailedCasts;
    private final @Nullable ItemSelector rodSelector;
    private final int castTimeoutTicks;
    private final int biteTimeoutTicks;
    private final boolean completeWhenNoRod;
    private final boolean restoreSelectedSlot;
    private final CompletableFuture<FishTaskResult> result;
    private int originalSelectedSlot;
    private boolean active;
    private Stage stage = Stage.SELECT_ROD;
    private int stageTicks;
    private int catches;
    private int failedCasts;
    private int outOfWaterTicks;
    private boolean pendingCatch;
    private boolean completeAfterRetrieval;

    private FishControl(
      BotTaskContext context,
      int maximumCatches,
      int maximumFailedCasts,
      @Nullable ItemSelector rodSelector,
      int castTimeoutTicks,
      int biteTimeoutTicks,
      boolean completeWhenNoRod,
      boolean restoreSelectedSlot,
      CompletableFuture<FishTaskResult> result
    ) {
      this.context = context;
      this.maximumCatches = maximumCatches;
      this.maximumFailedCasts = maximumFailedCasts;
      this.rodSelector = rodSelector;
      this.castTimeoutTicks = castTimeoutTicks;
      this.biteTimeoutTicks = biteTimeoutTicks;
      this.completeWhenNoRod = completeWhenNoRod;
      this.restoreSelectedSlot = restoreSelectedSlot;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        switch (stage) {
          case SELECT_ROD -> selectRod();
          case CAST -> cast();
          case WAIT_FOR_HOOK -> waitForHook();
          case WAIT_FOR_BITE -> waitForBite();
          case WAIT_FOR_RETRIEVAL -> waitForRetrieval();
          case RECAST_DELAY -> recastDelay();
        }
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      }
    }

    private void selectRod() {
      var selected = TaskInventorySupport.ensureHolding(
        context.bot(),
        stack -> stack.getItem() instanceof FishingRodItem
          && (rodSelector == null
          || InventoryServiceImpl.matches(stack, rodSelector))
      );
      if (!selected) {
        if (completeWhenNoRod) {
          complete(FishCompletionReason.FISH_COMPLETION_REASON_NO_ROD);
          return;
        }
        stageTicks++;
        if (stageTicks % 20 == 0) {
          report("Waiting for a matching fishing rod");
        }
        return;
      }
      transition(Stage.CAST, "Casting fishing line");
    }

    private void cast() {
      var player = requirePlayer();
      if (player.fishing != null) {
        transition(Stage.WAIT_FOR_BITE, "Waiting for a bite");
        return;
      }
      var interaction = requireGameMode().useItem(
        player,
        InteractionHand.MAIN_HAND
      );
      if (!(interaction instanceof InteractionResult.Success)) {
        throw Status.FAILED_PRECONDITION
          .withDescription("The fishing rod could not be cast")
          .asRuntimeException();
      }
      transition(Stage.WAIT_FOR_HOOK, "Waiting for cast confirmation");
    }

    private void waitForHook() {
      if (requirePlayer().fishing != null) {
        transition(Stage.WAIT_FOR_BITE, "Waiting for a bite");
        return;
      }
      stageTicks++;
      if (stageTicks >= castTimeoutTicks) {
        failedCasts++;
        if (completeForFailedCastLimit()) {
          return;
        }
        transition(Stage.SELECT_ROD, "Cast was not confirmed");
      }
    }

    private void waitForBite() {
      var player = requirePlayer();
      var hook = player.fishing;
      if (hook == null || hook.isRemoved()) {
        failedCasts++;
        if (completeForFailedCastLimit()) {
          return;
        }
        transition(Stage.RECAST_DELAY, "Fishing line was lost");
        return;
      }
      outOfWaterTicks = hook.isInWater() ? 0 : outOfWaterTicks + 1;
      if (outOfWaterTicks >= OUT_OF_WATER_RECAST_TICKS) {
        requireGameMode().useItem(player, InteractionHand.MAIN_HAND);
        failedCasts++;
        pendingCatch = false;
        if (failedCastLimitReached()) {
          completeAfterRetrieval = true;
        }
        transition(
          Stage.WAIT_FOR_RETRIEVAL,
          "Reeling in a cast that missed the water"
        );
        return;
      }
      if (
        ((FishingHookAccessor) hook).soulfire$isBiting()
          || hook.getHookedIn() != null
      ) {
        var interaction = requireGameMode().useItem(
          player,
          InteractionHand.MAIN_HAND
        );
        if (!(interaction instanceof InteractionResult.Success)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The fishing line could not be reeled in")
            .asRuntimeException();
        }
        pendingCatch = true;
        transition(Stage.WAIT_FOR_RETRIEVAL, "Reeling in catch");
        return;
      }
      stageTicks++;
      if (stageTicks % 20 == 0) {
        report(hook.isOpenWaterFishing()
          ? "Waiting for an open-water bite"
          : "Waiting for a bite");
      }
      if (stageTicks >= biteTimeoutTicks) {
        requireGameMode().useItem(player, InteractionHand.MAIN_HAND);
        failedCasts++;
        pendingCatch = false;
        if (failedCastLimitReached()) {
          completeAfterRetrieval = true;
        }
        transition(Stage.WAIT_FOR_RETRIEVAL, "Reeling in timed-out cast");
      }
    }

    private void waitForRetrieval() {
      if (requirePlayer().fishing == null) {
        if (completeAfterRetrieval) {
          complete(
            FishCompletionReason
              .FISH_COMPLETION_REASON_FAILED_CAST_LIMIT_REACHED
          );
          return;
        }
        if (pendingCatch) {
          catches++;
          pendingCatch = false;
          if (maximumCatches > 0 && catches >= maximumCatches) {
            complete(
              FishCompletionReason
                .FISH_COMPLETION_REASON_CATCH_LIMIT_REACHED
            );
            return;
          }
        }
        transition(Stage.RECAST_DELAY, "Preparing next cast");
        return;
      }
      stageTicks++;
      if (stageTicks >= castTimeoutTicks) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out retrieving the fishing line")
          .asRuntimeException();
      }
    }

    private void recastDelay() {
      stageTicks++;
      if (stageTicks >= RECAST_DELAY_TICKS) {
        transition(Stage.SELECT_ROD, "Selecting fishing rod");
      }
    }

    private net.minecraft.client.player.LocalPlayer requirePlayer() {
      return Objects.requireNonNull(
        context.bot().minecraft().player,
        "Bot player is not available"
      );
    }

    private net.minecraft.client.multiplayer.MultiPlayerGameMode
    requireGameMode() {
      return Objects.requireNonNull(
        context.bot().minecraft().gameMode,
        "Bot game mode is not available"
      );
    }

    private void transition(Stage next, String message) {
      stage = next;
      stageTicks = 0;
      outOfWaterTicks = 0;
      report(message);
    }

    private void report(String message) {
      var builder = BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(catches);
      if (maximumCatches > 0) {
        builder
          .setTotal(maximumCatches)
          .setFraction(Math.min(
            1.0,
            (double) catches / maximumCatches
          ));
      }
      context.reportProgress(builder.build());
    }

    private boolean failedCastLimitReached() {
      return maximumFailedCasts > 0 && failedCasts >= maximumFailedCasts;
    }

    private boolean completeForFailedCastLimit() {
      if (!failedCastLimitReached()) {
        return false;
      }
      complete(
        FishCompletionReason
          .FISH_COMPLETION_REASON_FAILED_CAST_LIMIT_REACHED
      );
      return true;
    }

    private void complete(FishCompletionReason reason) {
      result.complete(FishTaskResult.newBuilder()
        .setReason(reason)
        .setCatches(catches)
        .setFailedCasts(failedCasts)
        .build());
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public Set<ControlResource> resources() {
      return RESOURCES;
    }

    @Override
    public void onStarted() {
      originalSelectedSlot = requirePlayer().getInventory().getSelectedSlot();
      active = true;
    }

    @Override
    public void onSuspended() {
      active = false;
    }

    @Override
    public void onResumed() {
      active = true;
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      try {
        if (active) {
          active = false;
          var player = context.bot().minecraft().player;
          var gameMode = context.bot().minecraft().gameMode;
          if (
            player != null
              && gameMode != null
              && player.fishing != null
              && player.getMainHandItem().getItem() instanceof FishingRodItem
          ) {
            gameMode.useItem(player, InteractionHand.MAIN_HAND);
          }
          if (restoreSelectedSlot && player != null) {
            player.getInventory().setSelectedSlot(originalSelectedSlot);
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
      return "Fish";
    }
  }

  private enum Stage {
    SELECT_ROD,
    CAST,
    WAIT_FOR_HOOK,
    WAIT_FOR_BITE,
    WAIT_FOR_RETRIEVAL,
    RECAST_DELAY
  }
}
