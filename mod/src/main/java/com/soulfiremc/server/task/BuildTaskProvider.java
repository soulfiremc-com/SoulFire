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
import com.soulfiremc.grpc.generated.BuildBlockOutcome;
import com.soulfiremc.grpc.generated.BuildBlockStatus;
import com.soulfiremc.grpc.generated.BuildCompletionReason;
import com.soulfiremc.grpc.generated.BuildMaterialSubstitution;
import com.soulfiremc.grpc.generated.BuildMirror;
import com.soulfiremc.grpc.generated.BuildRotation;
import com.soulfiremc.grpc.generated.BuildTask;
import com.soulfiremc.grpc.generated.BuildTaskResult;
import com.soulfiremc.grpc.generated.WorldPosition;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BlockPredictionSupport;
import com.soulfiremc.server.bot.BotInteractionSupport;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.DelegatePathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import com.soulfiremc.server.util.SFBlockHelpers;
import io.grpc.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/// Places an inline, transformable schematic with exact material and block
/// state verification.
public final class BuildTaskProvider implements BotTaskProvider<BuildTask> {
  private static final int MAX_BLOCKS = 8_192;
  private static final int PLACEMENT_CONFIRMATION_TICKS = 40;
  private static final int REACH_RADIUS = 3;
  private static final int RECOVERY_CHUNK_TICKS = 100;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public BuildTask inputPrototype() {
    return BuildTask.getDefaultInstance();
  }

  @Override
  public String summary(BuildTask input) {
    return "Build " + input.getBlocksCount() + " schematic blocks";
  }

  @Override
  public Set<ControlResource> resources(BuildTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, BuildTask input) {
    return start(context, input, new RecoveryState());
  }

  @Override
  public ExecutionFactory prepare(BuildTask input) {
    var recovery = new RecoveryState();
    return context -> start(context, input, recovery);
  }

  private BotTaskExecution start(BotTaskContext context, BuildTask input, RecoveryState recovery) {
    validate(context, input);
    var origin = new BlockPos(
      input.getOrigin().getX(),
      input.getOrigin().getY(),
      input.getOrigin().getZ()
    );
    var substitutions = substitutions(input.getSubstitutionsList());
    var transformed = new ArrayList<Placement>();
    var occupied = new HashSet<BlockPos>();
    for (var index = 0; index < input.getBlocksCount(); index++) {
      var block = input.getBlocks(index);
      var offset = transformOffset(
        block.getOffset().getX(),
        block.getOffset().getY(),
        block.getOffset().getZ(),
        input.getMirror(),
        input.getRotation()
      );
      var position = origin.offset(offset.x(), offset.y(), offset.z());
      if (!occupied.add(position)) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "Multiple build blocks resolve to %d, %d, %d"
              .formatted(position.getX(), position.getY(), position.getZ())
          )
          .asRuntimeException();
      }
      var requestedId = normalizeId(block.getBlockId());
      resolveBlock(requestedId);
      var properties = transformProperties(
        block.getPropertiesMap(),
        input.getMirror(),
        input.getRotation()
      );
      transformed.add(new Placement(
        index,
        position.immutable(),
        requestedId,
        properties,
        substitutions.getOrDefault(requestedId, List.of())
      ));
    }
    transformed.sort(Comparator
      .comparingInt((Placement placement) -> placement.position().getY())
      .thenComparingInt(Placement::sourceIndex));

    var partitionCount = Math.max(1, input.getPartitionCount());
    var selected = new ArrayList<Placement>();
    for (var index = 0; index < transformed.size(); index++) {
      if (index % partitionCount == input.getPartitionIndex()) {
        selected.add(transformed.get(index));
      }
    }
    var navigationConstraint = protectSchematic(
      PathfindingSupport.buildConstraint(
        context.bot(),
        input.getOptions()
      ),
      occupied.stream()
        .map(SFVec3i::fromInt)
        .collect(Collectors.toUnmodifiableSet())
    );
    var result = new CompletableFuture<BuildTaskResult>();
    return new BotTaskExecution(
      new BuildControl(
        context,
        List.copyOf(selected),
        navigationConstraint,
        input.getBreakIncorrectBlocks(),
        input.getRestoreSelectedSlot(),
        result,
        recovery
      ),
      result
    );
  }

  private static void validate(BotTaskContext context, BuildTask input) {
    if (!input.hasOrigin()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("origin must be set")
        .asRuntimeException();
    }
    if (input.getBlocksCount() == 0 || input.getBlocksCount() > MAX_BLOCKS) {
      throw Status.INVALID_ARGUMENT
        .withDescription("blocks must contain between one and " + MAX_BLOCKS + " entries")
        .asRuntimeException();
    }
    var level = Objects.requireNonNull(context.bot().minecraft().level);
    var requestedDimension = input.getOrigin().getDimension();
    var actualDimension = level.dimension().identifier().toString();
    if (!requestedDimension.isBlank()
      && !requestedDimension.equals(actualDimension)) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Build origin is in '%s', but the bot is in '%s'"
            .formatted(requestedDimension, actualDimension)
        )
        .asRuntimeException();
    }
    var partitionCount = Math.max(1, input.getPartitionCount());
    if (input.getPartitionIndex() >= partitionCount) {
      throw Status.INVALID_ARGUMENT
        .withDescription("partition_index must be smaller than partition_count")
        .asRuntimeException();
    }
    for (var substitution : input.getSubstitutionsList()) {
      if (substitution.getSourceBlockId().isBlank()
        || substitution.getReplacementBlockIdsList().isEmpty()) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "Every substitution needs a source block and at least one replacement"
          )
          .asRuntimeException();
      }
      resolveBlock(normalizeId(substitution.getSourceBlockId()));
      substitution.getReplacementBlockIdsList().stream()
        .map(BuildTaskProvider::normalizeId)
        .forEach(BuildTaskProvider::resolveBlock);
    }
  }

  private static Map<String, List<String>> substitutions(
    List<BuildMaterialSubstitution> substitutions
  ) {
    var result = new LinkedHashMap<String, List<String>>();
    for (var substitution : substitutions) {
      var source = normalizeId(substitution.getSourceBlockId());
      if (result.put(
        source,
        substitution.getReplacementBlockIdsList().stream()
          .map(BuildTaskProvider::normalizeId)
          .toList()
      ) != null) {
        throw Status.INVALID_ARGUMENT
          .withDescription("Duplicate substitution for " + source)
          .asRuntimeException();
      }
    }
    return Map.copyOf(result);
  }

  private static String normalizeId(String value) {
    if (value.isBlank()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("block_id must not be blank")
        .asRuntimeException();
    }
    return value.indexOf(':') < 0 ? "minecraft:" + value : value;
  }

  private static Block resolveBlock(String id) {
    final Identifier identifier;
    try {
      identifier = Identifier.parse(id);
    } catch (RuntimeException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Invalid block ID '" + id + "'")
        .withCause(exception)
        .asRuntimeException();
    }
    var block = BuiltInRegistries.BLOCK.getValue(identifier);
    if (block == null
      || block == Blocks.AIR && !id.equals("minecraft:air")) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Unknown block ID '" + id + "'")
        .asRuntimeException();
    }
    if (block == Blocks.AIR) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Build placements cannot use minecraft:air")
        .asRuntimeException();
    }
    return block;
  }

  private static Offset transformOffset(
    int x,
    int y,
    int z,
    BuildMirror mirror,
    BuildRotation rotation
  ) {
    if (mirror == BuildMirror.BUILD_MIRROR_X) {
      x = -x;
    } else if (mirror == BuildMirror.BUILD_MIRROR_Z) {
      z = -z;
    }
    return switch (rotation) {
      case BUILD_ROTATION_CLOCKWISE_90 -> new Offset(-z, y, x);
      case BUILD_ROTATION_HALF -> new Offset(-x, y, -z);
      case BUILD_ROTATION_COUNTERCLOCKWISE_90 -> new Offset(z, y, -x);
      default -> new Offset(x, y, z);
    };
  }

  private static Map<String, String> transformProperties(
    Map<String, String> properties,
    BuildMirror mirror,
    BuildRotation rotation
  ) {
    var transformed = new HashMap<String, String>();
    properties.forEach((name, value) -> {
      var normalized = value.toLowerCase(Locale.ROOT);
      if (name.equals("facing")
        && Set.of("north", "south", "east", "west").contains(normalized)) {
        normalized = transformDirection(normalized, mirror, rotation);
      } else if (name.equals("axis")
        && (normalized.equals("x") || normalized.equals("z"))
        && (rotation == BuildRotation.BUILD_ROTATION_CLOCKWISE_90
          || rotation == BuildRotation.BUILD_ROTATION_COUNTERCLOCKWISE_90)) {
        normalized = normalized.equals("x") ? "z" : "x";
      }
      transformed.put(name, normalized);
    });
    return Map.copyOf(transformed);
  }

  private static String transformDirection(
    String direction,
    BuildMirror mirror,
    BuildRotation rotation
  ) {
    if (mirror == BuildMirror.BUILD_MIRROR_X) {
      direction = switch (direction) {
        case "east" -> "west";
        case "west" -> "east";
        default -> direction;
      };
    } else if (mirror == BuildMirror.BUILD_MIRROR_Z) {
      direction = switch (direction) {
        case "north" -> "south";
        case "south" -> "north";
        default -> direction;
      };
    }
    var order = List.of("north", "east", "south", "west");
    var index = order.indexOf(direction);
    var steps = switch (rotation) {
      case BUILD_ROTATION_CLOCKWISE_90 -> 1;
      case BUILD_ROTATION_HALF -> 2;
      case BUILD_ROTATION_COUNTERCLOCKWISE_90 -> 3;
      default -> 0;
    };
    return order.get((index + steps) % order.size());
  }

  private static PathConstraint breakConstraint(
    com.soulfiremc.server.bot.BotConnection bot,
    SFVec3i target
  ) {
    var delegate = new PathConstraintImpl(bot);
    return new DelegatePathConstraint() {
      @Override
      public boolean canBreakBlock(SFVec3i position, BlockState state) {
        return position.equals(target)
          && delegate.canBreakBlock(position, state);
      }

      @Override
      public boolean canPlaceBlock(SFVec3i position) {
        return false;
      }

      @Override
      public PathConstraint delegate() {
        return delegate;
      }
    };
  }

  private static PathConstraint protectSchematic(
    PathConstraint delegate,
    Set<SFVec3i> positions
  ) {
    return new DelegatePathConstraint() {
      @Override
      public boolean canBreakBlock(SFVec3i position, BlockState state) {
        return !positions.contains(position)
          && delegate.canBreakBlock(position, state);
      }

      @Override
      public boolean canPlaceBlock(SFVec3i position) {
        return !positions.contains(position)
          && delegate.canPlaceBlock(position);
      }

      @Override
      public PathConstraint delegate() {
        return delegate;
      }
    };
  }

  private record Offset(int x, int y, int z) {
  }

  private record Placement(
    int sourceIndex,
    BlockPos position,
    String requestedBlockId,
    Map<String, String> properties,
    List<String> substitutions
  ) {
    List<String> materialCandidates() {
      return substitutions.isEmpty()
        ? List.of(requestedBlockId)
        : substitutions;
    }
  }

  private record Material(
    String blockId,
    Block block,
    BlockState expectedState,
    Map<String, String> requestedProperties
  ) {
    boolean matches(BlockState state) {
      return matchesRequestedState(
        state,
        block,
        expectedState,
        requestedProperties.keySet()
      );
    }
  }

  private record Support(BlockPos position, Direction face) {
  }

  private static final class BuildControl implements ControlTask {
    private final BotTaskContext context;
    private final List<Placement> placements;
    private final PathConstraint navigationConstraint;
    private final boolean breakIncorrect;
    private final boolean restoreSelectedSlot;
    private final CompletableFuture<BuildTaskResult> result;
    private boolean active;
    private final RecoveryState recovery;
    private boolean recoveryPending;
    private int recoveryTicks;
    private @Nullable PathExecutor activePath;
    private @Nullable Placement current;
    private @Nullable Material currentMaterial;

    private BuildControl(
      BotTaskContext context,
      List<Placement> placements,
      PathConstraint navigationConstraint,
      boolean breakIncorrect,
      boolean restoreSelectedSlot,
      CompletableFuture<BuildTaskResult> result,
      RecoveryState recovery
    ) {
      this.context = context;
      this.placements = placements;
      this.navigationConstraint = navigationConstraint;
      this.breakIncorrect = breakIncorrect;
      this.restoreSelectedSlot = restoreSelectedSlot;
      this.result = result;
      this.recovery = recovery;
      recoveryPending = recovery.dimension != null;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      if (recoveryPending) {
        if (!recoverPlacement()) {
          return;
        }
        recoveryPending = false;
      }
      if (recovery.placementIndex >= placements.size()) {
        complete();
        return;
      }
      current = placements.get(recovery.placementIndex);
      switch (recovery.stage) {
        case PREPARE -> prepare();
        case BREAK, NAVIGATE -> tickPath();
        case PLACE -> place();
        case CONFIRM -> confirm();
      }
    }

    private boolean recoverPlacement() {
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      if (!level.dimension().identifier().toString().equals(recovery.dimension)) {
        result.completeExceptionally(Status.FAILED_PRECONDITION
          .withDescription("Cannot resume build placements in another dimension")
          .asRuntimeException());
        return false;
      }
      if (recovery.placementIndex >= placements.size()) {
        return true;
      }
      current = placements.get(recovery.placementIndex);
      if (!SFBlockHelpers.isChunkLoaded(level, current.position())) {
        context.reportProgress(progress("Waiting for build target chunk"));
        if (++recoveryTicks >= RECOVERY_CHUNK_TICKS) {
          result.completeExceptionally(Status.DEADLINE_EXCEEDED
            .withDescription("Build target chunk did not arrive after reconnect")
            .asRuntimeException());
        }
        return false;
      }
      switch (recovery.stage) {
        case CONFIRM -> currentMaterial = material(
          Objects.requireNonNull(recovery.placingMaterialId), current.properties());
        case BREAK -> {
          if (BlockPredictionSupport.isClearedBreakTarget(level.getBlockState(current.position()))) {
            recovery.incorrectBroken++;
          }
          recovery.stage = Stage.PREPARE;
        }
        case NAVIGATE, PLACE -> recovery.stage = Stage.PREPARE;
        case PREPARE -> {}
      }
      return true;
    }

    private void prepare() {
      var placement = Objects.requireNonNull(current);
      var level = context.bot().minecraft().level;
      if (level == null) {
        result.completeExceptionally(new IllegalStateException("Bot level is not available"));
        return;
      }
      if (!SFBlockHelpers.isChunkLoaded(level, placement.position())) {
        fail(
          BuildBlockStatus.BUILD_BLOCK_STATUS_UNREACHABLE,
          "",
          "Target chunk is not loaded"
        );
        return;
      }
      var state = level.getBlockState(placement.position());
      var matching = matchingCurrentMaterial(placement, state);
      if (matching.isPresent()) {
        currentMaterial = matching.orElseThrow();
        recovery.alreadyCorrect++;
        outcome(
          BuildBlockStatus.BUILD_BLOCK_STATUS_ALREADY_CORRECT,
          currentMaterial.blockId(),
          "Block was already correct"
        );
        advance();
        return;
      }
      if (!state.canBeReplaced()) {
        if (!breakIncorrect) {
          fail(
            BuildBlockStatus.BUILD_BLOCK_STATUS_INCORRECT_BLOCK,
            blockId(state),
            "An incorrect block occupies the target"
          );
          return;
        }
        activePath = PathExecutor.createPathfinding(
          context.bot(),
          new BreakBlockPosGoal(SFVec3i.fromInt(placement.position())),
          breakConstraint(
            context.bot(),
            SFVec3i.fromInt(placement.position())
          )
        );
        activePath.onStarted();
        recovery.stage = Stage.BREAK;
        context.reportProgress(progress("Breaking incorrect block"));
        return;
      }
      currentMaterial = selectMaterial(placement).orElse(null);
      if (currentMaterial == null) {
        fail(
          BuildBlockStatus.BUILD_BLOCK_STATUS_MISSING_MATERIAL,
          "",
          "No requested or substituted block item is in the inventory"
        );
        return;
      }
      activePath = PathExecutor.createPathfinding(
        context.bot(),
        new CloseToPosGoal(SFVec3i.fromInt(placement.position()), REACH_RADIUS),
        navigationConstraint
      );
      activePath.onStarted();
      recovery.stage = Stage.NAVIGATE;
      context.reportProgress(progress("Navigating to build placement"));
    }

    private void tickPath() {
      var path = activePath;
      if (path == null) {
        recovery.stage = Stage.PREPARE;
        return;
      }
      if (!path.isDone()) {
        path.tick();
        context.reportProgress(progress(
          path.progress().planning()
            ? "Planning build route"
            : recovery.stage == Stage.BREAK
              ? "Breaking incorrect block"
              : "Navigating to build placement"
        ));
        return;
      }
      activePath = null;
      try {
        path.completion().join();
        path.onStopped(ControlStopReason.COMPLETED, null);
        if (recovery.stage == Stage.BREAK) {
          recovery.incorrectBroken++;
          recovery.stage = Stage.PREPARE;
        } else {
          recovery.stage = Stage.PLACE;
        }
      } catch (CompletionException exception) {
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        path.onStopped(ControlStopReason.FAILED, cause);
        fail(
          BuildBlockStatus.BUILD_BLOCK_STATUS_UNREACHABLE,
          "",
          "Pathfinder could not reach the placement"
        );
      }
    }

    private void place() {
      var placement = Objects.requireNonNull(current);
      var material = currentMaterial;
      var player = context.bot().minecraft().player;
      var gameMode = context.bot().minecraft().gameMode;
      var level = context.bot().minecraft().level;
      if (material == null || player == null || gameMode == null || level == null) {
        result.completeExceptionally(new IllegalStateException("Bot game state is unavailable"));
        return;
      }
      if (!TaskInventorySupport.ensureHolding(
        context.bot(),
        stack -> stack.getItem() instanceof BlockItem blockItem
          && blockItem.getBlock() == material.block()
      )) {
        fail(
          BuildBlockStatus.BUILD_BLOCK_STATUS_MISSING_MATERIAL,
          "",
          "Selected material is no longer in the inventory"
        );
        return;
      }
      var support = findSupport(level, placement.position());
      if (support.isEmpty()) {
        fail(
          BuildBlockStatus.BUILD_BLOCK_STATUS_UNSUPPORTED,
          "",
          "No solid neighboring face can support this placement"
        );
        return;
      }
      var selectedSupport = support.orElseThrow();
      var hitPosition = Vec3.atCenterOf(selectedSupport.position())
        .add(
          selectedSupport.face().getStepX() * 0.5,
          selectedSupport.face().getStepY() * 0.5,
          selectedSupport.face().getStepZ() * 0.5
        );
      context.bot().rotationControl().lookAt(hitPosition);
      if (!context.bot().rotationControl().isFacing(hitPosition)) {
        return;
      }
      var hit = new BlockHitResult(
        hitPosition,
        selectedSupport.face(),
        selectedSupport.position(),
        false
      );
      var swingAnimation = player.getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation();
      var interaction = BotInteractionSupport.withSneaking(
        player,
        true,
        () -> gameMode.useItemOn(
          player,
          InteractionHand.MAIN_HAND,
          hit
        )
      );
      if (!(interaction instanceof InteractionResult.Success success)) {
        fail(
          BuildBlockStatus.BUILD_BLOCK_STATUS_STATE_MISMATCH,
          "",
          "Minecraft rejected the placement interaction"
        );
        return;
      }
      if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
        player.swing(InteractionHand.MAIN_HAND, swingAnimation, false);
      }
      recovery.stageTicks = 0;
      recovery.placingMaterialId = material.blockId();
      recovery.stage = Stage.CONFIRM;
      context.reportProgress(progress("Confirming block placement"));
    }

    private void confirm() {
      var placement = Objects.requireNonNull(current);
      var material = Objects.requireNonNull(currentMaterial);
      var level = context.bot().minecraft().level;
      if (level == null) {
        result.completeExceptionally(new IllegalStateException("Bot level is not available"));
        return;
      }
      var state = level.getBlockState(placement.position());
      if (
        material.matches(state)
          && !BlockPredictionSupport.hasPendingPrediction(
          context.bot(),
          placement.position()
        )
      ) {
        recovery.blocksPlaced++;
        outcome(
          BuildBlockStatus.BUILD_BLOCK_STATUS_PLACED,
          material.blockId(),
          "Block placed and state verified"
        );
        advance();
        return;
      }
      recovery.stageTicks++;
      if (recovery.stageTicks >= PLACEMENT_CONFIRMATION_TICKS) {
        fail(
          BuildBlockStatus.BUILD_BLOCK_STATUS_STATE_MISMATCH,
          blockId(state),
          "Placed block did not match the requested state"
        );
      }
    }

    private Optional<Material> matchingCurrentMaterial(
      Placement placement,
      BlockState currentState
    ) {
      return placement.materialCandidates().stream()
        .map(id -> material(id, placement.properties()))
        .filter(material -> material.matches(currentState))
        .findFirst();
    }

    private Optional<Material> selectMaterial(Placement placement) {
      var candidates = placement.materialCandidates().stream()
        .map(id -> material(id, placement.properties()))
        .toList();
      for (var candidate : candidates) {
        if (TaskInventorySupport.findInventorySlot(
          context.bot(),
          stack -> stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() == candidate.block()
        ).isPresent()) {
          return Optional.of(candidate);
        }
      }
      return Optional.empty();
    }

    private Material material(String blockId, Map<String, String> properties) {
      var block = resolveBlock(blockId);
      return new Material(
        blockId,
        block,
        applyProperties(block.defaultBlockState(), properties, blockId),
        properties
      );
    }

    private void fail(
      BuildBlockStatus status,
      String placedBlockId,
      String message
    ) {
      recovery.failed++;
      outcome(status, placedBlockId, message);
      advance();
    }

    private void outcome(
      BuildBlockStatus status,
      String placedBlockId,
      String message
    ) {
      var placement = Objects.requireNonNull(current);
      var level = context.bot().minecraft().level;
      var dimension = level == null
        ? ""
        : level.dimension().identifier().toString();
      recovery.outcomes.add(BuildBlockOutcome.newBuilder()
        .setPosition(BlockPosition.newBuilder()
          .setX(placement.position().getX())
          .setY(placement.position().getY())
          .setZ(placement.position().getZ())
          .setDimension(dimension))
        .setRequestedBlockId(placement.requestedBlockId())
        .setPlacedBlockId(placedBlockId)
        .setStatus(status)
        .setMessage(message)
        .build());
    }

    private void advance() {
      recovery.placementIndex++;
      current = null;
      currentMaterial = null;
      recovery.placingMaterialId = null;
      activePath = null;
      recovery.stageTicks = 0;
      recovery.stage = Stage.PREPARE;
    }

    private BotTaskProgress progress(String message) {
      var total = placements.size();
      return BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(recovery.placementIndex)
        .setTotal(total)
        .setFraction(total == 0
          ? 1.0
          : Math.min(1.0, (double) recovery.placementIndex / total))
        .build();
    }

    private void complete() {
      var builder = BuildTaskResult.newBuilder()
        .setReason(recovery.failed == 0
          ? BuildCompletionReason.BUILD_COMPLETION_REASON_COMPLETED
          : BuildCompletionReason.BUILD_COMPLETION_REASON_PARTIAL)
        .setBlocksPlaced(recovery.blocksPlaced)
        .setBlocksAlreadyCorrect(recovery.alreadyCorrect)
        .setIncorrectBlocksBroken(recovery.incorrectBroken)
        .setBlocksFailed(recovery.failed)
        .addAllOutcomes(recovery.outcomes);
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      if (player != null && level != null) {
        builder.setFinalPosition(WorldPosition.newBuilder()
          .setX(player.getX())
          .setY(player.getY())
          .setZ(player.getZ())
          .setDimension(level.dimension().identifier().toString()));
      }
      result.complete(builder.build());
    }

    private void restoreSelectedSlot() {
      var player = context.bot().minecraft().player;
      if (restoreSelectedSlot && player != null) {
        recovery.originalSelectedSlot.ifPresent(player.getInventory()::setSelectedSlot);
      }
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
      if (recovery.originalSelectedSlot.isEmpty()) {
        recovery.originalSelectedSlot = OptionalInt.of(
          Objects.requireNonNull(context.bot().minecraft().player).getInventory().getSelectedSlot());
        recovery.dimension = Objects.requireNonNull(context.bot().minecraft().level)
          .dimension().identifier().toString();
      }
      active = true;
    }

    @Override
    public void onSuspended() {
      active = false;
      if (activePath != null) {
        activePath.onSuspended();
      }
      context.bot().controlState().resetAll();
    }

    @Override
    public void onResumed() {
      active = true;
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
        var path = activePath;
        activePath = null;
        if (path != null) {
          path.onStopped(reason, cause);
        }
        if (active) {
          active = false;
          context.bot().controlState().resetAll();
          restoreSelectedSlot();
        }
      } finally {
        if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
          result.cancel(true);
        }
      }
    }

    @Override
    public String description() {
      return "Build schematic";
    }
  }

  private static final class RecoveryState {
    private OptionalInt originalSelectedSlot = OptionalInt.empty();
    private @Nullable String dimension;
    private final List<BuildBlockOutcome> outcomes = new ArrayList<>();
    private Stage stage = Stage.PREPARE;
    private int placementIndex;
    private int stageTicks;
    private int blocksPlaced;
    private int alreadyCorrect;
    private int incorrectBroken;
    private int failed;
    private @Nullable String placingMaterialId;
  }

  private static Optional<Support> findSupport(
    net.minecraft.world.level.Level level,
    BlockPos target
  ) {
    for (var direction : List.of(
      Direction.DOWN,
      Direction.NORTH,
      Direction.SOUTH,
      Direction.WEST,
      Direction.EAST,
      Direction.UP
    )) {
      var supportPosition = target.relative(direction);
      var face = direction.getOpposite();
      var state = level.getBlockState(supportPosition);
      if (!state.canBeReplaced()
        && state.isFaceSturdy(level, supportPosition, face)) {
        return Optional.of(new Support(supportPosition, face));
      }
    }
    return Optional.empty();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static BlockState applyProperties(
    BlockState initial,
    Map<String, String> properties,
    String blockId
  ) {
    var state = initial;
    for (var entry : properties.entrySet()) {
      Property property = state.getBlock()
        .getStateDefinition()
        .getProperty(entry.getKey());
      if (property == null) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "Block '%s' has no property '%s'"
              .formatted(blockId, entry.getKey())
          )
          .asRuntimeException();
      }
      var value = property.getValue(entry.getValue());
      if (value.isEmpty()) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "Property '%s' on block '%s' does not accept '%s'"
              .formatted(entry.getKey(), blockId, entry.getValue())
          )
          .asRuntimeException();
      }
      state = state.setValue(property, (Comparable) value.orElseThrow());
    }
    return state;
  }

  static boolean matchesRequestedState(
    BlockState state,
    Block requestedBlock,
    BlockState requestedState,
    Set<String> requestedProperties
  ) {
    if (state.getBlock() != requestedBlock) {
      return false;
    }
    for (var propertyName : requestedProperties) {
      var property = requestedBlock.getStateDefinition().getProperty(propertyName);
      if (property == null
        || !matchesProperty(state, requestedState, property)) {
        return false;
      }
    }
    return true;
  }

  private static <T extends Comparable<T>> boolean matchesProperty(
    BlockState state,
    BlockState requestedState,
    Property<T> property
  ) {
    return state.getValue(property).equals(requestedState.getValue(property));
  }

  private static String blockId(BlockState state) {
    return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
  }

  private enum Stage {
    PREPARE,
    BREAK,
    NAVIGATE,
    PLACE,
    CONFIRM
  }
}
