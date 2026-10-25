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

import com.soulfiremc.grpc.generated.BlockPosition;
import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.ContainerTransferCompletionReason;
import com.soulfiremc.grpc.generated.ContainerTransferDirection;
import com.soulfiremc.grpc.generated.ContainerTransferOperation;
import com.soulfiremc.grpc.generated.ContainerTransferOutcome;
import com.soulfiremc.grpc.generated.ContainerTransferTask;
import com.soulfiremc.grpc.generated.ContainerTransferTaskResult;
import com.soulfiremc.grpc.generated.InventoryArea;
import com.soulfiremc.grpc.generated.WorldPosition;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Moves exact or best-effort item counts through one block container without
/// exposing menu click sequencing to SDK applications.
public final class ContainerTransferTaskProvider
  implements BotTaskProvider<ContainerTransferTask> {
  private static final int MAX_OPERATIONS = 64;
  private static final int MAX_COUNT = 1_000_000;
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
  public ContainerTransferTask inputPrototype() {
    return ContainerTransferTask.getDefaultInstance();
  }

  @Override
  public String summary(ContainerTransferTask input) {
    return switch (input.getDirection()) {
      case CONTAINER_TRANSFER_DIRECTION_DEPOSIT ->
        "Deposit items into a block container";
      case CONTAINER_TRANSFER_DIRECTION_WITHDRAW ->
        "Withdraw items from a block container";
      default -> "Transfer items through a block container";
    };
  }

  @Override
  public Set<ControlResource> resources(ContainerTransferTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(
    BotTaskContext context,
    ContainerTransferTask input
  ) {
    validate(context, input);
    var result = new CompletableFuture<ContainerTransferTaskResult>();
    return new BotTaskExecution(
      new TransferControl(
        context,
        input,
        new BlockPos(
          input.getContainer().getX(),
          input.getContainer().getY(),
          input.getContainer().getZ()
        ),
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
    ContainerTransferTask input
  ) {
    var direction = input.getDirection();
    if (
      direction == ContainerTransferDirection
        .CONTAINER_TRANSFER_DIRECTION_UNSPECIFIED
        || direction == ContainerTransferDirection.UNRECOGNIZED
    ) {
      throw Status.INVALID_ARGUMENT
        .withDescription("direction must be DEPOSIT or WITHDRAW")
        .asRuntimeException();
    }
    if (
      input.getOperationsCount() == 0
        || input.getOperationsCount() > MAX_OPERATIONS
    ) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "operations must contain between one and " + MAX_OPERATIONS
        )
        .asRuntimeException();
    }
    for (var operation : input.getOperationsList()) {
      if (operation.getCount() == 0 || operation.getCount() > MAX_COUNT) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "operation count must be between one and " + MAX_COUNT
          )
          .asRuntimeException();
      }
    }
    var level = Objects.requireNonNull(context.bot().minecraft().level);
    var requestedDimension = input.getContainer().getDimension();
    var actualDimension = level.dimension().identifier().toString();
    if (
      !requestedDimension.isBlank()
        && !requestedDimension.equals(actualDimension)
    ) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Container is in '%s', but the bot is in '%s'"
            .formatted(requestedDimension, actualDimension)
        )
        .asRuntimeException();
    }
  }

  private static final class TransferControl implements ControlTask {
    private final BotTaskContext context;
    private final ContainerTransferTask input;
    private final BlockPos container;
    private final PathConstraint constraint;
    private final CompletableFuture<ContainerTransferTaskResult> result;
    private final List<ContainerTransferOutcome> outcomes =
      new ArrayList<>();
    private @Nullable PathExecutor path;
    private @Nullable InventoryMutationTask<List<Integer>> pendingTransfer;
    private Stage stage = Stage.NAVIGATE;
    private int initialContainerId;
    private int stageTicks;
    private int contentsTicks;
    private int approachAttempts;
    private int totalTransferred;
    private long containerRevision;
    private boolean openedMenu;
    private boolean partial;

    private TransferControl(
      BotTaskContext context,
      ContainerTransferTask input,
      BlockPos container,
      PathConstraint constraint,
      CompletableFuture<ContainerTransferTaskResult> result
    ) {
      this.context = context;
      this.input = input;
      this.container = container;
      this.constraint = constraint;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        switch (stage) {
          case NAVIGATE -> navigate();
          case OPEN -> open();
          case WAIT_FOR_MENU -> waitForMenu();
          case TRANSFER -> transfer();
          case CONFIRM_TRANSFER -> confirmTransfer();
          case COMPLETE -> complete();
        }
      } catch (Throwable throwable) {
        stopTransfer(ControlStopReason.FAILED, throwable);
        closeOpenedMenu();
        result.completeExceptionally(throwable);
      }
    }

    private void navigate() {
      var player = requirePlayer();
      if (player.isWithinBlockInteractionRange(container, 0)) {
        approachAttempts = 0;
        stopPath(ControlStopReason.CANCELLED, null);
        transition(Stage.OPEN, "Opening container");
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
          ? "Planning route to container"
          : "Walking to container");
        return;
      }
      var completed = path;
      path = null;
      try {
        completed.completion().join();
        completed.onStopped(ControlStopReason.COMPLETED, null);
        if (requirePlayer().isWithinBlockInteractionRange(container, 0)) {
          approachAttempts = 0;
          transition(Stage.OPEN, "Opening container");
          return;
        }
        approachAttempts++;
        if (approachAttempts >= MAX_APPROACH_ATTEMPTS) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Could not reach the container's interaction range"
            )
            .asRuntimeException();
        }
        report("Closing distance to container");
      } catch (CompletionException exception) {
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        completed.onStopped(ControlStopReason.FAILED, cause);
        throw exception;
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
          .withDescription("Container position is not loaded")
          .asRuntimeException();
      }
      if (!player.isWithinBlockInteractionRange(container, 0)) {
        transition(Stage.NAVIGATE, "Returning to container");
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
      transition(Stage.WAIT_FOR_MENU, "Waiting for container");
    }

    private void waitForMenu() {
      var menu = requirePlayer().containerMenu;
      if (
        menu.containerId != initialContainerId
          && !(menu instanceof InventoryMenu)
      ) {
        openedMenu = true;
        // The slots arrive in a packet after the screen opens: moving items
        // before that sees an empty container.
        if (
          !SFInventoryHelpers.hasReceivedContents(menu)
            && ++contentsTicks < SFInventoryHelpers.MENU_CONTENTS_GRACE_TICKS
        ) {
          return;
        }
        transition(Stage.TRANSFER, "Transferring items");
        return;
      }
      if (++stageTicks >= OPEN_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Server did not open the selected container")
          .asRuntimeException();
      }
    }

    private void transfer() {
      var from = input.getDirection()
        == ContainerTransferDirection
        .CONTAINER_TRANSFER_DIRECTION_DEPOSIT
        ? InventoryArea.INVENTORY_AREA_PLAYER
        : InventoryArea.INVENTORY_AREA_CONTAINER;
      var to = input.getDirection()
        == ContainerTransferDirection
        .CONTAINER_TRANSFER_DIRECTION_DEPOSIT
        ? InventoryArea.INVENTORY_AREA_CONTAINER
        : InventoryArea.INVENTORY_AREA_PLAYER;
      pendingTransfer = InventoryServiceImpl.transferBatchForTask(
        context.bot(),
        input.getOperationsList(),
        from,
        to
      );
      pendingTransfer.onStarted();
      transition(Stage.CONFIRM_TRANSFER, "Confirming container transfer");
    }

    private void confirmTransfer() {
      var transfer = Objects.requireNonNull(pendingTransfer);
      transfer.tick();
      if (!transfer.isDone()) {
        if (++stageTicks >= CONFIRM_TIMEOUT_TICKS) {
          throw Status.DEADLINE_EXCEEDED.withDescription("Server did not confirm the container transfer").asRuntimeException();
        }
        return;
      }
      var transferred = transfer.result();
      containerRevision = transfer.container().getRevision();
      stopTransfer(ControlStopReason.COMPLETED, null);
      for (var index = 0; index < input.getOperationsCount(); index++) {
        var operation = input.getOperations(index);
        var moved = transferred.get(index);
        outcomes.add(ContainerTransferOutcome.newBuilder()
          .setSelector(operation.getSelector())
          .setRequested(operation.getCount())
          .setTransferred(moved)
          .build());
        totalTransferred += moved;
        partial |= moved != operation.getCount();
      }
      transition(Stage.COMPLETE, "Finalizing container transfer");
    }

    private void complete() {
      if (input.getCloseContainer()) {
        closeOpenedMenu();
      }
      var player = requirePlayer();
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      result.complete(ContainerTransferTaskResult.newBuilder()
        .setReason(partial
          ? ContainerTransferCompletionReason
            .CONTAINER_TRANSFER_COMPLETION_REASON_PARTIAL
          : ContainerTransferCompletionReason
            .CONTAINER_TRANSFER_COMPLETION_REASON_COMPLETED)
        .addAllOutcomes(outcomes)
        .setTotalTransferred(totalTransferred)
        .setContainerRevision(containerRevision)
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

    private void transition(Stage next, String message) {
      stage = next;
      stageTicks = 0;
      report(message);
    }

    private void report(String message) {
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(outcomes.size())
        .setTotal(input.getOperationsCount())
        .setFraction(
          (double) outcomes.size() / input.getOperationsCount()
        )
        .build());
    }

    private void closeOpenedMenu() {
      if (!openedMenu) {
        return;
      }
      var player = context.bot().minecraft().player;
      if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
        player.closeContainer();
      }
      openedMenu = false;
    }

    private void stopPath(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var active = path;
      path = null;
      if (active != null) {
        active.onStopped(reason, cause);
      }
    }

    private void stopTransfer(ControlStopReason reason, @Nullable Throwable cause) {
      var transfer = pendingTransfer;
      pendingTransfer = null;
      if (transfer != null) transfer.onStopped(reason, cause);
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
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      stopPath(reason, cause);
      stopTransfer(reason, cause);
      context.bot().controlState().resetAll();
      if (reason != ControlStopReason.COMPLETED) {
        closeOpenedMenu();
      }
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Container transfer";
    }
  }

  private enum Stage {
    NAVIGATE,
    OPEN,
    WAIT_FOR_MENU,
    TRANSFER,
    CONFIRM_TRANSFER,
    COMPLETE
  }
}
