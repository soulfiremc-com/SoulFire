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
import com.soulfiremc.grpc.generated.FarmCompletionReason;
import com.soulfiremc.grpc.generated.FarmTask;
import com.soulfiremc.grpc.generated.FarmTaskResult;
import com.soulfiremc.grpc.generated.WorldPosition;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import io.grpc.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/// Harvests supported mature crops and replants destructive crop strategies.
public final class FarmTaskProvider implements BotTaskProvider<FarmTask> {
  private static final int DEFAULT_RADIUS = 24;
  private static final int MAX_RADIUS = 48;
  private static final int DEFAULT_RESCAN_INTERVAL_TICKS = 100;
  private static final int MAX_RESCAN_INTERVAL_TICKS = 72_000;
  private static final int BREAK_TIMEOUT_TICKS = 100;
  private static final int INTERACTION_TIMEOUT_TICKS = 40;
  private static final int REPLANT_ITEM_TIMEOUT_TICKS = 100;
  private static final int REPLANT_CONFIRMATION_TIMEOUT_TICKS = 40;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );
  private static final Map<Block, CropDefinition> CROPS =
    createCropDefinitions();
  private static final Set<String> SUPPORTED_IDS = CROPS.keySet().stream()
    .map(BuiltInRegistries.BLOCK::getKey)
    .map(Identifier::toString)
    .collect(Collectors.toUnmodifiableSet());

  @Override
  public FarmTask inputPrototype() {
    return FarmTask.getDefaultInstance();
  }

  @Override
  public String summary(FarmTask input) {
    return input.getMaximumHarvests() == 0
      ? "Farm mature crops until cancelled"
      : "Harvest up to " + input.getMaximumHarvests() + " mature crops";
  }

  @Override
  public Set<ControlResource> resources(FarmTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, FarmTask input) {
    var radius = input.getRadius() == 0
      ? DEFAULT_RADIUS
      : Math.min(input.getRadius(), MAX_RADIUS);
    var rescanInterval = input.getRescanIntervalTicks() == 0
      ? DEFAULT_RESCAN_INTERVAL_TICKS
      : Math.min(
        input.getRescanIntervalTicks(),
        MAX_RESCAN_INTERVAL_TICKS
      );
    var selectedCropIds = normalizeCropIds(input.getCropIdsList());
    BlockPos center = null;
    if (input.hasCenter()) {
      validateDimension(context, input.getCenter());
      center = toBlockPos(input.getCenter());
    }
    var player = Objects.requireNonNull(
      context.bot().minecraft().player,
      "Bot player is not available"
    );
    var result = new CompletableFuture<FarmTaskResult>();
    return new BotTaskExecution(
      new FarmControl(
        context,
        selectedCropIds,
        center,
        radius,
        input.getMaximumHarvests(),
        input.getReplant(),
        input.getCompleteWhenNoMatureCrops(),
        rescanInterval,
        input.getRestoreSelectedSlot(),
        player.getInventory().getSelectedSlot(),
        PathfindingSupport.buildConstraint(
          context.bot(),
          input.getOptions()
        ),
        result
      ),
      result
    );
  }

  private static Set<String> normalizeCropIds(List<String> cropIds) {
    if (cropIds.isEmpty()) {
      return Set.of();
    }
    var normalized = cropIds.stream()
      .map(value -> value.indexOf(':') < 0
        ? "minecraft:" + value
        : value)
      .collect(Collectors.toUnmodifiableSet());
    var unsupported = normalized.stream()
      .filter(id -> !SUPPORTED_IDS.contains(id))
      .sorted()
      .toList();
    if (!unsupported.isEmpty()) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Unsupported crop IDs: %s. Supported crop IDs: %s"
            .formatted(
              String.join(", ", unsupported),
              SUPPORTED_IDS.stream().sorted().collect(
                Collectors.joining(", ")
              )
            )
        )
        .asRuntimeException();
    }
    return normalized;
  }

  private static void validateDimension(
    BotTaskContext context,
    BlockPosition position
  ) {
    var level = Objects.requireNonNull(context.bot().minecraft().level);
    var actual = level.dimension().identifier().toString();
    if (
      !position.getDimension().isBlank()
        && !position.getDimension().equals(actual)
    ) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Farm center is in '%s', but the bot is in '%s'"
            .formatted(position.getDimension(), actual)
        )
        .asRuntimeException();
    }
  }

  private static BlockPos toBlockPos(BlockPosition position) {
    return new BlockPos(
      position.getX(),
      position.getY(),
      position.getZ()
    );
  }

  private static Map<Block, CropDefinition> createCropDefinitions() {
    var crops = new LinkedHashMap<Block, CropDefinition>();
    crops.put(
      Blocks.WHEAT,
      CropDefinition.breakAndReplant(Items.WHEAT_SEEDS)
    );
    crops.put(
      Blocks.CARROTS,
      CropDefinition.breakAndReplant(Items.CARROT)
    );
    crops.put(
      Blocks.POTATOES,
      CropDefinition.breakAndReplant(Items.POTATO)
    );
    crops.put(
      Blocks.BEETROOTS,
      CropDefinition.breakAndReplant(Items.BEETROOT_SEEDS)
    );
    crops.put(
      Blocks.TORCHFLOWER_CROP,
      CropDefinition.breakAndReplant(Items.TORCHFLOWER_SEEDS)
    );
    crops.put(
      Blocks.PITCHER_CROP,
      CropDefinition.breakAndReplant(Items.PITCHER_POD)
    );
    crops.put(
      Blocks.NETHER_WART,
      CropDefinition.breakAndReplant(Items.NETHER_WART)
    );
    crops.put(
      Blocks.COCOA,
      CropDefinition.breakAndReplant(Items.COCOA_BEANS)
    );
    crops.put(Blocks.SWEET_BERRY_BUSH, CropDefinition.interact());
    crops.put(Blocks.CAVE_VINES, CropDefinition.interact());
    crops.put(Blocks.CAVE_VINES_PLANT, CropDefinition.interact());
    return Map.copyOf(crops);
  }

  private static final class FarmControl implements ControlTask {
    private final BotTaskContext context;
    private final Set<String> cropIds;
    private final @Nullable BlockPos fixedCenter;
    private final int radius;
    private final int maximumHarvests;
    private final boolean replant;
    private final boolean completeWhenNoMatureCrops;
    private final int rescanIntervalTicks;
    private final boolean restoreSelectedSlot;
    private final int originalSelectedSlot;
    private final PathConstraint constraint;
    private final CompletableFuture<FarmTaskResult> result;
    private @Nullable CropTarget target;
    private @Nullable PathExecutor path;
    private Stage stage = Stage.SCAN;
    private int stageTicks;
    private int cropsHarvested;
    private int cropsReplanted;
    private int failedHarvests;
    private Direction breakFace = Direction.UP;
    private boolean breaking;

    private FarmControl(
      BotTaskContext context,
      Set<String> cropIds,
      @Nullable BlockPos fixedCenter,
      int radius,
      int maximumHarvests,
      boolean replant,
      boolean completeWhenNoMatureCrops,
      int rescanIntervalTicks,
      boolean restoreSelectedSlot,
      int originalSelectedSlot,
      PathConstraint constraint,
      CompletableFuture<FarmTaskResult> result
    ) {
      this.context = context;
      this.cropIds = cropIds;
      this.fixedCenter = fixedCenter;
      this.radius = radius;
      this.maximumHarvests = maximumHarvests;
      this.replant = replant;
      this.completeWhenNoMatureCrops = completeWhenNoMatureCrops;
      this.rescanIntervalTicks = rescanIntervalTicks;
      this.restoreSelectedSlot = restoreSelectedSlot;
      this.originalSelectedSlot = originalSelectedSlot;
      this.constraint = constraint;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        if (
          maximumHarvests > 0
            && cropsHarvested >= maximumHarvests
        ) {
          complete(
            FarmCompletionReason
              .FARM_COMPLETION_REASON_HARVEST_LIMIT_REACHED
          );
          return;
        }
        switch (stage) {
          case SCAN -> scan();
          case WAIT_TO_RESCAN -> waitToRescan();
          case NAVIGATE -> navigate();
          case BREAK -> breakCrop();
          case INTERACT -> interact();
          case WAIT_FOR_INTERACTION -> waitForInteraction();
          case WAIT_FOR_REPLANT_ITEM -> waitForReplantItem();
          case REPLANT -> replant();
          case WAIT_FOR_REPLANT -> waitForReplant();
        }
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      }
    }

    private void scan() {
      target = findNearestMatureCrop();
      if (target == null) {
        if (completeWhenNoMatureCrops) {
          complete(
            FarmCompletionReason
              .FARM_COMPLETION_REASON_NO_MATURE_CROPS
          );
          return;
        }
        transition(
          Stage.WAIT_TO_RESCAN,
          "Waiting for a mature crop"
        );
        return;
      }
      transition(Stage.NAVIGATE, "Walking to mature crop");
    }

    private void waitToRescan() {
      stageTicks++;
      if (stageTicks >= rescanIntervalTicks) {
        transition(Stage.SCAN, "Scanning for mature crops");
      }
    }

    private void navigate() {
      var currentTarget = requireTarget();
      if (!isStillMature(currentTarget)) {
        failedHarvests++;
        stopPath(ControlStopReason.CANCELLED, null);
        clearTargetAndScan("Crop was harvested before arrival");
        return;
      }
      var player = requirePlayer();
      if (
        player.getEyePosition().distanceToSqr(
          Vec3.atCenterOf(currentTarget.position())
        ) <= 25
      ) {
        stopPath(ControlStopReason.CANCELLED, null);
        transition(
          currentTarget.definition().method() == HarvestMethod.INTERACT
            ? Stage.INTERACT
            : Stage.BREAK,
          currentTarget.definition().method() == HarvestMethod.INTERACT
            ? "Harvesting crop"
            : "Breaking mature crop"
        );
        return;
      }
      if (path == null) {
        path = PathExecutor.createPathfinding(
          context.bot(),
          new CloseToPosGoal(
            SFVec3i.fromInt(currentTarget.position()),
            2
          ),
          constraint
        );
        path.onStarted();
      }
      if (!path.isDone()) {
        path.tick();
        report(path.progress().planning()
          ? "Planning route to mature crop"
          : "Walking to mature crop");
        return;
      }
      var completed = path;
      path = null;
      try {
        completed.completion().join();
        completed.onStopped(ControlStopReason.COMPLETED, null);
      } catch (CompletionException exception) {
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        completed.onStopped(ControlStopReason.FAILED, cause);
        result.completeExceptionally(cause);
      }
    }

    private void breakCrop() {
      var currentTarget = requireTarget();
      var player = requirePlayer();
      var gameMode = requireGameMode();
      var state = requireLevel().getBlockState(currentTarget.position());
      if (!isSameCrop(currentTarget, state)) {
        breaking = false;
        gameMode.stopDestroyBlock();
        cropsHarvested++;
        afterHarvest();
        return;
      }
      if (!isMature(currentTarget.position(), state)) {
        breaking = false;
        gameMode.stopDestroyBlock();
        failedHarvests++;
        clearTargetAndScan("Crop is no longer mature");
        return;
      }
      if (!breaking) {
        breakFace = nearestFace(
          player.getEyePosition(),
          currentTarget.position()
        );
        if (
          !gameMode.startDestroyBlock(
            currentTarget.position(),
            breakFace
          )
        ) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The mature crop could not be broken")
            .asRuntimeException();
        }
        player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
        breaking = true;
      } else {
        if (
          !gameMode.continueDestroyBlock(
            currentTarget.position(),
            breakFace
          )
        ) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Crop breaking was rejected")
            .asRuntimeException();
        }
        player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
      }
      stageTicks++;
      if (stageTicks >= BREAK_TIMEOUT_TICKS) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Timed out breaking a mature crop")
          .asRuntimeException();
      }
    }

    private void interact() {
      var currentTarget = requireTarget();
      if (!isStillMature(currentTarget)) {
        failedHarvests++;
        clearTargetAndScan("Crop is no longer mature");
        return;
      }
      selectNeutralInteractionHand();
      useOn(
        currentTarget.position(),
        nearestFace(
          requirePlayer().getEyePosition(),
          currentTarget.position()
        )
      );
      transition(
        Stage.WAIT_FOR_INTERACTION,
        "Waiting for harvest confirmation"
      );
    }

    private void waitForInteraction() {
      var currentTarget = requireTarget();
      if (!isStillMature(currentTarget)) {
        cropsHarvested++;
        clearTargetAndScan("Crop harvested");
        return;
      }
      stageTicks++;
      if (
        stageTicks % 10 == 0
          && stageTicks < INTERACTION_TIMEOUT_TICKS
      ) {
        useOn(
          currentTarget.position(),
          nearestFace(
            requirePlayer().getEyePosition(),
            currentTarget.position()
          )
        );
      }
      if (stageTicks >= INTERACTION_TIMEOUT_TICKS) {
        failedHarvests++;
        clearTargetAndScan("Crop did not confirm the harvest");
      }
    }

    private void afterHarvest() {
      var currentTarget = requireTarget();
      if (replant && currentTarget.definition().replantItem() != null) {
        transition(
          Stage.WAIT_FOR_REPLANT_ITEM,
          "Waiting for replant item"
        );
        return;
      }
      clearTargetAndScan("Crop harvested");
    }

    private void waitForReplantItem() {
      var currentTarget = requireTarget();
      var replantItem = Objects.requireNonNull(
        currentTarget.definition().replantItem()
      );
      if (
        TaskInventorySupport.ensureHolding(
          context.bot(),
          stack -> stack.is(replantItem)
        )
      ) {
        transition(Stage.REPLANT, "Replanting crop");
        return;
      }
      stageTicks++;
      if (stageTicks >= REPLANT_ITEM_TIMEOUT_TICKS) {
        complete(
          FarmCompletionReason
            .FARM_COMPLETION_REASON_NO_REPLANT_ITEM
        );
      }
    }

    private void replant() {
      var currentTarget = requireTarget();
      var placement = currentTarget.placement();
      if (!requireLevel().getBlockState(currentTarget.position()).isAir()) {
        failedHarvests++;
        clearTargetAndScan("Crop position is no longer empty");
        return;
      }
      useOn(placement.against(), placement.face());
      transition(
        Stage.WAIT_FOR_REPLANT,
        "Waiting for replant confirmation"
      );
    }

    private void waitForReplant() {
      var currentTarget = requireTarget();
      var state = requireLevel().getBlockState(currentTarget.position());
      if (isSameCrop(currentTarget, state)) {
        cropsReplanted++;
        clearTargetAndScan("Crop replanted");
        return;
      }
      stageTicks++;
      if (
        stageTicks % 10 == 0
          && stageTicks < REPLANT_CONFIRMATION_TIMEOUT_TICKS
      ) {
        var placement = currentTarget.placement();
        useOn(placement.against(), placement.face());
      }
      if (stageTicks >= REPLANT_CONFIRMATION_TIMEOUT_TICKS) {
        throw Status.FAILED_PRECONDITION
          .withDescription("The server did not confirm crop replanting")
          .asRuntimeException();
      }
    }

    private @Nullable CropTarget findNearestMatureCrop() {
      var player = requirePlayer();
      var level = requireLevel();
      var center = fixedCenter == null
        ? player.blockPosition()
        : fixedCenter;
      var radiusSquared = radius * radius;
      var minimumY = Math.max(level.getMinY(), center.getY() - radius);
      var maximumY = Math.min(level.getMaxY(), center.getY() + radius);
      CropTarget nearest = null;
      var nearestDistance = Double.POSITIVE_INFINITY;
      for (var x = -radius; x <= radius; x++) {
        for (var z = -radius; z <= radius; z++) {
          if (x * x + z * z > radiusSquared) {
            continue;
          }
          for (var y = minimumY; y <= maximumY; y++) {
            var offsetY = y - center.getY();
            if (
              x * x + offsetY * offsetY + z * z
                > radiusSquared
            ) {
              continue;
            }
            var position = center.offset(x, offsetY, z);
            if (!level.hasChunkAt(position)) {
              continue;
            }
            var state = level.getBlockState(position);
            var definition = CROPS.get(state.getBlock());
            if (
              definition == null
                || !selected(state.getBlock())
                || !isMature(position, state)
            ) {
              continue;
            }
            var distance = position.distSqr(player.blockPosition());
            if (distance < nearestDistance) {
              nearestDistance = distance;
              nearest = new CropTarget(
                position.immutable(),
                state.getBlock(),
                definition,
                placementFor(position, state)
              );
            }
          }
        }
      }
      return nearest;
    }

    private boolean selected(Block block) {
      return cropIds.isEmpty() || cropIds.contains(
        BuiltInRegistries.BLOCK.getKey(block).toString()
      );
    }

    private boolean isStillMature(CropTarget cropTarget) {
      var state = requireLevel().getBlockState(cropTarget.position());
      return isSameCrop(cropTarget, state)
        && isMature(cropTarget.position(), state);
    }

    private static boolean isSameCrop(
      CropTarget cropTarget,
      BlockState state
    ) {
      return state.is(cropTarget.block());
    }

    private static boolean isMature(
      BlockPos position,
      BlockState state
    ) {
      var block = state.getBlock();
      if (block instanceof CropBlock crop) {
        return crop.isMaxAge(state);
      }
      if (block == Blocks.NETHER_WART) {
        return state.getValue(NetherWartBlock.AGE)
          == NetherWartBlock.MAX_AGE;
      }
      if (block == Blocks.SWEET_BERRY_BUSH) {
        return state.getValue(SweetBerryBushBlock.AGE) > 1;
      }
      if (
        block == Blocks.CAVE_VINES
          || block == Blocks.CAVE_VINES_PLANT
      ) {
        return CaveVines.hasGlowBerries(state);
      }
      if (block == Blocks.COCOA) {
        return state.getValue(CocoaBlock.AGE) == CocoaBlock.MAX_AGE;
      }
      if (block == Blocks.PITCHER_CROP) {
        return state.getValue(PitcherCropBlock.HALF)
          == DoubleBlockHalf.LOWER
          && state.getValue(PitcherCropBlock.AGE)
          == PitcherCropBlock.MAX_AGE;
      }
      return false;
    }

    private static Placement placementFor(
      BlockPos position,
      BlockState state
    ) {
      if (state.is(Blocks.COCOA)) {
        var supportDirection = state.getValue(
          HorizontalDirectionalBlock.FACING
        );
        return new Placement(
          position.relative(supportDirection),
          supportDirection.getOpposite()
        );
      }
      return new Placement(position.below(), Direction.UP);
    }

    private void selectNeutralInteractionHand() {
      var player = requirePlayer();
      if (!player.getMainHandItem().is(Items.BONE_MEAL)) {
        return;
      }
      if (
        TaskInventorySupport.ensureHolding(
          context.bot(),
          stack -> !stack.is(Items.BONE_MEAL)
        )
      ) {
        return;
      }
      for (
        var slot = 0;
        slot < player.getInventory().getSelectionSize();
        slot++
      ) {
        if (player.getInventory().getItem(slot).isEmpty()) {
          player.getInventory().setSelectedSlot(slot);
          return;
        }
      }
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "A neutral main-hand slot is required to harvest this crop"
        )
        .asRuntimeException();
    }

    private void useOn(BlockPos position, Direction face) {
      var player = requirePlayer();
      var hitPosition = Vec3.atCenterOf(position).add(
        face.getStepX() * 0.5,
        face.getStepY() * 0.5,
        face.getStepZ() * 0.5
      );
      var swingAnimation = player.getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation();
      var interaction = requireGameMode().useItemOn(
        player,
        InteractionHand.MAIN_HAND,
        new BlockHitResult(
          hitPosition,
          face,
          position,
          false
        )
      );
      if (!(interaction instanceof InteractionResult.Success success)) {
        throw Status.FAILED_PRECONDITION
          .withDescription("The crop rejected the interaction")
          .asRuntimeException();
      }
      if (
        success.swingSource() == InteractionResult.SwingSource.PREDICTED
      ) {
        player.swing(InteractionHand.MAIN_HAND, swingAnimation, false);
      }
    }

    private static Direction nearestFace(
      Vec3 eyePosition,
      BlockPos target
    ) {
      var center = Vec3.atCenterOf(target);
      var dx = eyePosition.x - center.x;
      var dy = eyePosition.y - center.y;
      var dz = eyePosition.z - center.z;
      var ax = Math.abs(dx);
      var ay = Math.abs(dy);
      var az = Math.abs(dz);
      if (ay >= ax && ay >= az) {
        return dy >= 0 ? Direction.UP : Direction.DOWN;
      }
      if (ax >= az) {
        return dx >= 0 ? Direction.EAST : Direction.WEST;
      }
      return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private CropTarget requireTarget() {
      return Objects.requireNonNull(target, "Farm target is not available");
    }

    private net.minecraft.client.player.LocalPlayer requirePlayer() {
      return Objects.requireNonNull(
        context.bot().minecraft().player,
        "Bot player is not available"
      );
    }

    private net.minecraft.client.multiplayer.ClientLevel requireLevel() {
      return Objects.requireNonNull(
        context.bot().minecraft().level,
        "Bot level is not available"
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
      report(message);
    }

    private void clearTargetAndScan(String message) {
      target = null;
      breaking = false;
      transition(Stage.SCAN, message);
    }

    private void report(String message) {
      var builder = BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(cropsHarvested);
      if (maximumHarvests > 0) {
        builder
          .setTotal(maximumHarvests)
          .setFraction(Math.min(
            1.0,
            (double) cropsHarvested / maximumHarvests
          ));
      }
      context.reportProgress(builder.build());
    }

    private void complete(FarmCompletionReason reason) {
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      var builder = FarmTaskResult.newBuilder()
        .setReason(reason)
        .setCropsHarvested(cropsHarvested)
        .setCropsReplanted(cropsReplanted)
        .setFailedHarvests(failedHarvests);
      if (player != null && level != null) {
        builder.setFinalPosition(WorldPosition.newBuilder()
          .setX(player.getX())
          .setY(player.getY())
          .setZ(player.getZ())
          .setDimension(level.dimension().identifier().toString()));
      }
      result.complete(builder.build());
    }

    private void stopPath(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var activePath = path;
      path = null;
      if (activePath != null) {
        activePath.onStopped(reason, cause);
      }
    }

    private void restoreSelectedSlot() {
      if (!restoreSelectedSlot) {
        return;
      }
      var player = context.bot().minecraft().player;
      if (player != null) {
        player.getInventory().setSelectedSlot(originalSelectedSlot);
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
    public void onSuspended() {
      if (path != null) {
        path.onSuspended();
      }
      if (breaking) {
        var gameMode = context.bot().minecraft().gameMode;
        if (gameMode != null) {
          gameMode.stopDestroyBlock();
        }
        breaking = false;
      }
    }

    @Override
    public void onResumed() {
      if (path != null) {
        path.onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      stopPath(reason, cause);
      if (breaking) {
        var gameMode = context.bot().minecraft().gameMode;
        if (gameMode != null) {
          gameMode.stopDestroyBlock();
        }
        breaking = false;
      }
      restoreSelectedSlot();
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Farm crops";
    }
  }

  private record CropDefinition(
    HarvestMethod method,
    @Nullable Item replantItem
  ) {
    private static CropDefinition breakAndReplant(Item item) {
      return new CropDefinition(HarvestMethod.BREAK, item);
    }

    private static CropDefinition interact() {
      return new CropDefinition(HarvestMethod.INTERACT, null);
    }
  }

  private record CropTarget(
    BlockPos position,
    Block block,
    CropDefinition definition,
    Placement placement
  ) {
  }

  private record Placement(BlockPos against, Direction face) {
  }

  private enum HarvestMethod {
    BREAK,
    INTERACT
  }

  private enum Stage {
    SCAN,
    WAIT_TO_RESCAN,
    NAVIGATE,
    BREAK,
    INTERACT,
    WAIT_FOR_INTERACTION,
    WAIT_FOR_REPLANT_ITEM,
    REPLANT,
    WAIT_FOR_REPLANT
  }
}
