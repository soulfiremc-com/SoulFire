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
import com.soulfiremc.grpc.generated.ContainerTransferOperation;
import com.soulfiremc.grpc.generated.InventoryArea;
import com.soulfiremc.grpc.generated.LoadoutRequirementResult;
import com.soulfiremc.grpc.generated.MaintainLoadoutCompletionReason;
import com.soulfiremc.grpc.generated.MaintainLoadoutTask;
import com.soulfiremc.grpc.generated.MaintainLoadoutTaskResult;
import com.soulfiremc.grpc.generated.WorldPosition;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.grpc.InventoryServiceImpl;
import com.soulfiremc.server.grpc.InventoryServiceImpl.InventoryMutationTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.SFInventoryHelpers;
import io.grpc.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Keeps semantic inventory counts within configured bounds by restocking from
/// and depositing into one block container.
public final class MaintainLoadoutTaskProvider
  implements BotTaskProvider<MaintainLoadoutTask> {
  private static final int MAX_REQUIREMENTS = 64;
  private static final int MAX_COUNT = 1_000_000;
  private static final int DEFAULT_CHECK_INTERVAL_TICKS = 100;
  private static final int MAX_CHECK_INTERVAL_TICKS = 72_000;
  private static final int CONTAINER_APPROACH_RADIUS = 1;
  private static final int MAX_APPROACH_ATTEMPTS = 3;
  private static final int OPEN_TIMEOUT_TICKS = 100;
  private static final int CONFIRM_TIMEOUT_TICKS = 200;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY,
    ControlResource.CONTAINER
  );

  @Override
  public MaintainLoadoutTask inputPrototype() {
    return MaintainLoadoutTask.getDefaultInstance();
  }

  @Override
  public String summary(MaintainLoadoutTask input) {
    return input.getMaximumRebalances() == 0
      ? "Maintain inventory loadout"
      : "Rebalance inventory loadout up to "
        + input.getMaximumRebalances() + " times";
  }

  @Override
  public Set<ControlResource> resources(MaintainLoadoutTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(
    BotTaskContext context,
    MaintainLoadoutTask input
  ) {
    validate(context, input);
    var checkInterval = input.getCheckIntervalTicks() == 0
      ? DEFAULT_CHECK_INTERVAL_TICKS
      : Math.min(
        input.getCheckIntervalTicks(),
        MAX_CHECK_INTERVAL_TICKS
      );
    var result = new CompletableFuture<MaintainLoadoutTaskResult>();
    return new BotTaskExecution(
      new MaintainControl(
        context,
        input,
        new BlockPos(
          input.getContainer().getX(),
          input.getContainer().getY(),
          input.getContainer().getZ()
        ),
        checkInterval,
        PathfindingSupport.buildConstraint(
          context.bot(),
          input.getOptions()
        ),
        result
      ),
      result
    );
  }

  private static void validate(
    BotTaskContext context,
    MaintainLoadoutTask input
  ) {
    if (!input.hasContainer()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("container must be set")
        .asRuntimeException();
    }
    if (input.getRequirementsCount() == 0
      || input.getRequirementsCount() > MAX_REQUIREMENTS) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "requirements must contain between one and "
            + MAX_REQUIREMENTS + " entries"
        )
        .asRuntimeException();
    }
    var selectors = new HashSet<com.google.protobuf.ByteString>();
    for (var requirement : input.getRequirementsList()) {
      if (requirement.getTargetCount() > MAX_COUNT
        || requirement.getMinimumCount() > requirement.getTargetCount()
        || requirement.getMaximumCount() > 0
        && requirement.getMaximumCount() < requirement.getTargetCount()) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "Each loadout requirement needs minimum_count <= target_count"
              + " <= maximum_count when maximum_count is set"
          )
          .asRuntimeException();
      }
      if (!selectors.add(requirement.getSelector().toByteString())) {
        throw Status.INVALID_ARGUMENT
          .withDescription("Duplicate loadout selectors are not allowed")
          .asRuntimeException();
      }
    }
    var level = Objects.requireNonNull(context.bot().minecraft().level);
    var requestedDimension = input.getContainer().getDimension();
    var actualDimension = level.dimension().identifier().toString();
    if (!requestedDimension.isBlank()
      && !requestedDimension.equals(actualDimension)) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Container is in '%s', but the bot is in '%s'"
            .formatted(requestedDimension, actualDimension)
        )
        .asRuntimeException();
    }
  }

  private static final class MaintainControl implements ControlTask {
    private final BotTaskContext context;
    private final MaintainLoadoutTask input;
    private final BlockPos container;
    private final int checkIntervalTicks;
    private final PathConstraint constraint;
    private final CompletableFuture<MaintainLoadoutTaskResult> result;
    private final int[] withdrawn;
    private final int[] deposited;
    private @Nullable PathExecutor path;
    private @Nullable InventoryMutationTask<List<Integer>> pendingTransfer;
    private List<Integer> transferIndexes = List.of();
    private List<ContainerTransferOperation> pendingDeposits = List.of();
    private List<Integer> pendingDepositIndexes = List.of();
    private boolean withdrawing;
    private int movedInRebalance;
    private Stage stage = Stage.CHECK;
    private int waitTicks;
    private int stageTicks;
    private int contentsTicks;
    private int initialContainerId;
    private int approachAttempts;
    private int rebalances;
    private boolean openedMenu;

    private MaintainControl(
      BotTaskContext context,
      MaintainLoadoutTask input,
      BlockPos container,
      int checkIntervalTicks,
      PathConstraint constraint,
      CompletableFuture<MaintainLoadoutTaskResult> result
    ) {
      this.context = context;
      this.input = input;
      this.container = container;
      this.checkIntervalTicks = checkIntervalTicks;
      this.constraint = constraint;
      this.result = result;
      this.withdrawn = new int[input.getRequirementsCount()];
      this.deposited = new int[input.getRequirementsCount()];
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        switch (stage) {
          case CHECK -> check();
          case NAVIGATE -> navigate();
          case OPEN -> open();
          case WAIT_FOR_MENU -> waitForMenu();
          case TRANSFER -> transfer();
          case CONFIRM_TRANSFER -> confirmTransfer();
          case WAIT -> waitForNextCheck();
        }
      } catch (Throwable throwable) {
        stopTransfer(ControlStopReason.FAILED, throwable);
        closeOpenedMenu();
        result.completeExceptionally(throwable);
      }
    }

    private void check() {
      var counts = currentCounts();
      if (isSatisfied(counts)) {
        if (input.getCompleteWhenSatisfied()) {
          complete(
            MaintainLoadoutCompletionReason
              .MAINTAIN_LOADOUT_COMPLETION_REASON_SATISFIED
          );
          return;
        }
        transition(Stage.WAIT, "Loadout is balanced");
        return;
      }
      if (input.getMaximumRebalances() > 0
        && rebalances >= input.getMaximumRebalances()) {
        complete(
          MaintainLoadoutCompletionReason
            .MAINTAIN_LOADOUT_COMPLETION_REASON_REBALANCE_LIMIT_REACHED
        );
        return;
      }
      if (hasOpenContainer()) {
        transition(Stage.TRANSFER, "Rebalancing loadout");
      } else {
        transition(Stage.NAVIGATE, "Navigating to loadout container");
      }
    }

    private void navigate() {
      var player = requirePlayer();
      if (player.isWithinBlockInteractionRange(container, 0)) {
        approachAttempts = 0;
        transition(Stage.OPEN, "Opening loadout container");
        return;
      }
      if (path == null) {
        path = PathExecutor.createPathfinding(
          context.bot(),
          new CloseToPosGoal(
            SFVec3i.fromInt(container),
            CONTAINER_APPROACH_RADIUS
          ),
          constraint
        );
        path.onStarted();
      }
      if (!path.isDone()) {
        path.tick();
        report(path.progress().planning()
          ? "Planning route to loadout container"
          : "Navigating to loadout container");
        return;
      }
      var completed = path;
      path = null;
      try {
        completed.completion().join();
        completed.onStopped(ControlStopReason.COMPLETED, null);
        if (requirePlayer().isWithinBlockInteractionRange(container, 0)) {
          approachAttempts = 0;
          transition(Stage.OPEN, "Opening loadout container");
          return;
        }
        approachAttempts++;
        if (approachAttempts >= MAX_APPROACH_ATTEMPTS) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Could not reach the loadout container's interaction range"
            )
            .asRuntimeException();
        }
        report("Closing distance to loadout container");
      } catch (CompletionException exception) {
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        completed.onStopped(ControlStopReason.FAILED, cause);
        throw new CompletionException(cause);
      }
    }

    private void open() {
      var player = requirePlayer();
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      if (!level.getChunkSource().hasChunk(
        container.getX() >> 4,
        container.getZ() >> 4
      )) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Loadout container position is not loaded")
          .asRuntimeException();
      }
      if (!player.isWithinBlockInteractionRange(container, 0)) {
        transition(Stage.NAVIGATE, "Returning to loadout container");
        return;
      }
      if (!(player.containerMenu instanceof InventoryMenu)) {
        player.closeContainer();
      }
      initialContainerId = player.containerMenu.containerId;
      contentsTicks = 0;
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode,
        "Bot game mode is not available"
      );
      gameMode.useItemOn(
        player,
        InteractionHand.MAIN_HAND,
        new BlockHitResult(
          Vec3.atCenterOf(container),
          Direction.UP,
          container,
          false
        )
      );
      transition(Stage.WAIT_FOR_MENU, "Waiting for loadout container");
    }

    private void waitForMenu() {
      var menu = requirePlayer().containerMenu;
      if (menu.containerId != initialContainerId
        && !(menu instanceof InventoryMenu)) {
        openedMenu = true;
        // The slots arrive in a packet after the screen opens: counting them
        // before that sees an empty container.
        if (
          !SFInventoryHelpers.hasReceivedContents(menu)
            && ++contentsTicks < SFInventoryHelpers.MENU_CONTENTS_GRACE_TICKS
        ) {
          return;
        }
        transition(Stage.TRANSFER, "Rebalancing loadout");
        return;
      }
      if (++stageTicks >= OPEN_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Server did not open the loadout container")
          .asRuntimeException();
      }
    }

    private void transfer() {
      var before = currentCounts();
      var withdrawOperations = new ArrayList<ContainerTransferOperation>();
      var withdrawIndexes = new ArrayList<Integer>();
      var depositOperations = new ArrayList<ContainerTransferOperation>();
      var depositIndexes = new ArrayList<Integer>();
      for (var index = 0; index < input.getRequirementsCount(); index++) {
        var requirement = input.getRequirements(index);
        var current = before[index];
        if (current < requirement.getMinimumCount()) {
          withdrawIndexes.add(index);
          withdrawOperations.add(ContainerTransferOperation.newBuilder()
            .setSelector(requirement.getSelector())
            .setCount(requirement.getTargetCount() - current)
            .setAllowPartial(true)
            .build());
        } else if (requirement.getMaximumCount() > 0
          && current > requirement.getMaximumCount()) {
          depositIndexes.add(index);
          depositOperations.add(ContainerTransferOperation.newBuilder()
            .setSelector(requirement.getSelector())
            .setCount(current - requirement.getTargetCount())
            .setAllowPartial(true)
            .build());
        }
      }
      movedInRebalance = 0;
      pendingDeposits = List.copyOf(depositOperations);
      pendingDepositIndexes = List.copyOf(depositIndexes);
      if (!withdrawOperations.isEmpty()) {
        beginTransfer(withdrawOperations, withdrawIndexes, true);
      } else {
        beginDepositsOrComplete();
      }
    }

    private void beginTransfer(List<ContainerTransferOperation> operations, List<Integer> indexes, boolean withdraw) {
      withdrawing = withdraw;
      transferIndexes = List.copyOf(indexes);
      pendingTransfer = InventoryServiceImpl.transferBatchForTask(
        context.bot(), operations,
        withdraw ? InventoryArea.INVENTORY_AREA_CONTAINER : InventoryArea.INVENTORY_AREA_PLAYER,
        withdraw ? InventoryArea.INVENTORY_AREA_PLAYER : InventoryArea.INVENTORY_AREA_CONTAINER
      );
      pendingTransfer.onStarted();
      transition(Stage.CONFIRM_TRANSFER, "Confirming loadout transfer");
    }

    private void confirmTransfer() {
      var transfer = Objects.requireNonNull(pendingTransfer);
      transfer.tick();
      if (!transfer.isDone()) {
        if (++stageTicks >= CONFIRM_TIMEOUT_TICKS) {
          throw Status.DEADLINE_EXCEEDED.withDescription("Server did not confirm the loadout transfer").asRuntimeException();
        }
        return;
      }
      var counts = transfer.result();
      var totals = withdrawing ? withdrawn : deposited;
      for (var index = 0; index < counts.size(); index++) {
        var count = counts.get(index);
        totals[transferIndexes.get(index)] += count;
        movedInRebalance += count;
      }
      stopTransfer(ControlStopReason.COMPLETED, null);
      if (withdrawing) beginDepositsOrComplete();
      else completeRebalance();
    }

    private void beginDepositsOrComplete() {
      if (pendingDeposits.isEmpty()) {
        completeRebalance();
        return;
      }
      beginTransfer(pendingDeposits, pendingDepositIndexes, false);
      pendingDeposits = List.of();
      pendingDepositIndexes = List.of();
    }

    private void completeRebalance() {
      rebalances++;
      if (input.getCloseContainer()) {
        closeOpenedMenu();
      }
      var after = currentCounts();
      if (isSatisfied(after) && input.getCompleteWhenSatisfied()) {
        complete(
          MaintainLoadoutCompletionReason
            .MAINTAIN_LOADOUT_COMPLETION_REASON_SATISFIED
        );
      } else if (movedInRebalance == 0 && input.getCompleteWhenSatisfied()) {
        complete(
          MaintainLoadoutCompletionReason
            .MAINTAIN_LOADOUT_COMPLETION_REASON_CONTAINER_EXHAUSTED
        );
      } else if (input.getMaximumRebalances() > 0
        && rebalances >= input.getMaximumRebalances()) {
        complete(
          MaintainLoadoutCompletionReason
            .MAINTAIN_LOADOUT_COMPLETION_REASON_REBALANCE_LIMIT_REACHED
        );
      } else {
        transition(Stage.WAIT, movedInRebalance == 0
          ? "Container cannot currently satisfy loadout"
          : "Loadout rebalanced");
      }
    }

    private void waitForNextCheck() {
      if (++waitTicks >= checkIntervalTicks) {
        waitTicks = 0;
        transition(Stage.CHECK, "Checking loadout");
      }
    }

    private void stopTransfer(ControlStopReason reason, @Nullable Throwable cause) {
      var transfer = pendingTransfer;
      pendingTransfer = null;
      transferIndexes = List.of();
      if (transfer != null) transfer.onStopped(reason, cause);
    }

    private int[] currentCounts() {
      var player = requirePlayer();
      var menu = player.inventoryMenu;
      var counts = new int[input.getRequirementsCount()];
      for (var index = 0; index < input.getRequirementsCount(); index++) {
        var selector = input.getRequirements(index).getSelector();
        counts[index] = SFInventoryHelpers.playerInventorySlots(menu)
          .map(slot -> {
            var stack = menu.getSlot(slot).getItem();
            return InventoryServiceImpl.matches(stack, selector)
              ? stack.getCount()
              : 0;
          })
          .sum();
      }
      return counts;
    }

    private boolean isSatisfied(int[] counts) {
      for (var index = 0; index < input.getRequirementsCount(); index++) {
        var requirement = input.getRequirements(index);
        if (counts[index] < requirement.getMinimumCount()
          || requirement.getMaximumCount() > 0
          && counts[index] > requirement.getMaximumCount()) {
          return false;
        }
      }
      return true;
    }

    private boolean hasOpenContainer() {
      return !(requirePlayer().containerMenu instanceof InventoryMenu);
    }

    private void complete(MaintainLoadoutCompletionReason reason) {
      closeOpenedMenu();
      var counts = currentCounts();
      var requirements = new ArrayList<LoadoutRequirementResult>();
      var totalWithdrawn = 0;
      var totalDeposited = 0;
      for (var index = 0; index < input.getRequirementsCount(); index++) {
        var requirement = input.getRequirements(index);
        var satisfied = counts[index] >= requirement.getMinimumCount()
          && (requirement.getMaximumCount() == 0
          || counts[index] <= requirement.getMaximumCount());
        requirements.add(LoadoutRequirementResult.newBuilder()
          .setSelector(requirement.getSelector())
          .setFinalCount(counts[index])
          .setWithdrawn(withdrawn[index])
          .setDeposited(deposited[index])
          .setSatisfied(satisfied)
          .build());
        totalWithdrawn += withdrawn[index];
        totalDeposited += deposited[index];
      }
      var player = requirePlayer();
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      result.complete(MaintainLoadoutTaskResult.newBuilder()
        .setReason(reason)
        .setRebalances(rebalances)
        .setTotalWithdrawn(totalWithdrawn)
        .setTotalDeposited(totalDeposited)
        .addAllRequirements(requirements)
        .setFinalPosition(WorldPosition.newBuilder()
          .setX(player.getX())
          .setY(player.getY())
          .setZ(player.getZ())
          .setDimension(level.dimension().identifier().toString()))
        .build());
    }

    private net.minecraft.client.player.LocalPlayer requirePlayer() {
      return Objects.requireNonNull(
        context.bot().minecraft().player,
        "Bot player is not available"
      );
    }

    private void closeOpenedMenu() {
      if (openedMenu) {
        var player = context.bot().minecraft().player;
        if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
          player.closeContainer();
        }
      }
      openedMenu = false;
    }

    private void transition(Stage next, String message) {
      stage = next;
      stageTicks = 0;
      report(message);
    }

    private void report(String message) {
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(rebalances)
        .setTotal(input.getMaximumRebalances())
        .setFraction(input.getMaximumRebalances() == 0
          ? 0.0
          : Math.min(
            1.0,
            (double) rebalances / input.getMaximumRebalances()
          ))
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
      if (path != null) {
        path.onSuspended();
      }
      if (pendingTransfer != null) pendingTransfer.onSuspended();
    }

    @Override
    public void onResumed() {
      if (path != null) {
        path.onResumed();
      }
      if (pendingTransfer != null) pendingTransfer.onResumed();
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var activePath = path;
      path = null;
      stopTransfer(reason, cause);
      if (activePath != null) {
        activePath.onStopped(reason, cause);
      }
      closeOpenedMenu();
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Maintain loadout";
    }
  }

  private enum Stage {
    CHECK,
    NAVIGATE,
    OPEN,
    WAIT_FOR_MENU,
    TRANSFER,
    CONFIRM_TRANSFER,
    WAIT
  }
}
