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
package com.soulfiremc.server.pathfinding.graph.constraint;

import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.graph.DiagonalCollisionCalculator;
import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import com.soulfiremc.server.settings.instance.PathfindingSettings;
import com.soulfiremc.server.settings.lib.BotSettingsSource;
import com.soulfiremc.server.settings.lib.InstanceSettingsSource;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.util.SFBlockHelpers;
import com.soulfiremc.server.util.SFItemHelpers;
import com.soulfiremc.server.util.structs.CachedLazyObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public final class PathConstraintImpl implements PathConstraint {
  @Nullable
  private final LocalPlayer entity;
  private final LevelHeightAccessor levelHeightAccessor;
  private final boolean allowBreakingUndiggable;
  private final boolean avoidDiagonalSqueeze;
  private final boolean avoidHarmfulEntities;
  private final int maxEnemyPenalty;
  private final int breakBlockPenalty;
  private final int placeBlockPenalty;
  private final int expireTimeout;
  private final boolean disablePruning;
  private final double yawJitterMin;
  private final double yawJitterMax;
  private final double pitchJitterMin;
  private final double pitchJitterMax;
  private final CachedLazyObject<List<EntityRangeData>> unfriendlyEntities = new CachedLazyObject<>(this::getUnfriendlyEntitiesExpensive, 10, TimeUnit.SECONDS);

  public PathConstraintImpl(
    @Nullable LocalPlayer entity,
    LevelHeightAccessor levelHeightAccessor,
    boolean allowBreakingUndiggable,
    boolean avoidDiagonalSqueeze,
    boolean avoidHarmfulEntities,
    int maxEnemyPenalty,
    int breakBlockPenalty,
    int placeBlockPenalty,
    int expireTimeout,
    boolean disablePruning,
    double yawJitterMin,
    double yawJitterMax,
    double pitchJitterMin,
    double pitchJitterMax) {
    this.entity = entity;
    this.levelHeightAccessor = levelHeightAccessor;
    this.allowBreakingUndiggable = allowBreakingUndiggable;
    this.avoidDiagonalSqueeze = avoidDiagonalSqueeze;
    this.avoidHarmfulEntities = avoidHarmfulEntities;
    this.maxEnemyPenalty = maxEnemyPenalty;
    this.breakBlockPenalty = breakBlockPenalty;
    this.placeBlockPenalty = placeBlockPenalty;
    this.expireTimeout = expireTimeout;
    this.disablePruning = disablePruning;
    this.yawJitterMin = yawJitterMin;
    this.yawJitterMax = yawJitterMax;
    this.pitchJitterMin = pitchJitterMin;
    this.pitchJitterMax = pitchJitterMax;
  }

  public PathConstraintImpl(BotConnection botConnection) {
    this(
      botConnection.minecraft().player,
      botConnection.minecraft().level,
      botConnection.settingsSource()
    );
  }

  public PathConstraintImpl(
    @Nullable LocalPlayer entity,
    LevelHeightAccessor levelHeightAccessor,
    BotSettingsSource settingsSource) {
    this(
      entity,
      levelHeightAccessor,
      settingsSource.get(PathfindingSettings.ALLOW_BREAKING_UNDIGGABLE),
      settingsSource.get(PathfindingSettings.AVOID_DIAGONAL_SQUEEZE),
      settingsSource.get(PathfindingSettings.AVOID_HARMFUL_ENTITIES),
      settingsSource.get(PathfindingSettings.MAX_ENEMY_PENALTY),
      settingsSource.get(PathfindingSettings.BREAK_BLOCK_PENALTY),
      settingsSource.get(PathfindingSettings.PLACE_BLOCK_PENALTY),
      settingsSource.get(PathfindingSettings.EXPIRE_TIMEOUT),
      settingsSource.get(PathfindingSettings.DISABLE_PRUNING),
      settingsSource.get(PathfindingSettings.YAW_JITTER_MIN),
      settingsSource.get(PathfindingSettings.YAW_JITTER_MAX),
      settingsSource.get(PathfindingSettings.PITCH_JITTER_MIN),
      settingsSource.get(PathfindingSettings.PITCH_JITTER_MAX)
    );
  }

  @Override
  public boolean doUsableBlocksDecreaseWhenPlaced() {
    return entity == null || !entity.hasInfiniteMaterials();
  }

  @Override
  public boolean canBlocksDropWhenBroken() {
    return entity == null || !entity.preventsBlockDrops();
  }

  @Override
  public boolean canBreakBlocks() {
    return true;
  }

  @Override
  public boolean canPlaceBlocks() {
    return true;
  }

  @Override
  public boolean isPlaceable(ItemStack item) {
    return SFItemHelpers.isSafeFullBlockItem(item);
  }

  @Override
  public boolean isTool(ItemStack item) {
    return SFItemHelpers.isTool(item);
  }

  @Override
  public boolean isOutOfLevel(BlockState blockState, SFVec3i pos) {
    return blockState.getBlock() == Blocks.VOID_AIR && !levelHeightAccessor.isOutsideBuildHeight(pos.y);
  }

  @Override
  public boolean canBreakBlock(SFVec3i pos, BlockState blockState) {
    if (!canBreakBlocks()) {
      return false;
    } else if (levelHeightAccessor.isOutsideBuildHeight(pos.y)) {
      return false;
    } else if (allowBreakingUndiggable) {
      return true;
    } else {
      return SFBlockHelpers.isDiggable(blockState.getBlock());
    }
  }

  @Override
  public boolean canPlaceBlock(SFVec3i pos) {
    return canPlaceBlocks() && !levelHeightAccessor.isOutsideBuildHeight(pos.y);
  }

  @Override
  public boolean collidesWithAtEdge(DiagonalCollisionCalculator.CollisionData collisionData) {
    if (avoidDiagonalSqueeze) {
      return !SFBlockHelpers.isCollisionShapeEmpty(collisionData.blockState());
    }

    if (SFBlockHelpers.isCollisionShapeEmpty(collisionData.blockState())) {
      return false;
    }

    return DiagonalCollisionCalculator.collidesWith(collisionData);
  }

  @Override
  public GraphInstructions modifyAsNeeded(GraphInstructions instruction) {
    if (avoidHarmfulEntities) {
      var addedPenalty = 0D;
      for (var entity : unfriendlyEntities.get()) {
        var followRange = entity.followRange;
        var distance = instruction.blockPosition().distance(entity.entityPosition);
        if (distance <= followRange) {
          addedPenalty += maxEnemyPenalty * (followRange - distance) / followRange;
        }
      }

      if (addedPenalty > 0) {
        instruction = instruction.withActionCost(instruction.actionCost() + addedPenalty);
      }
    }

    return instruction;
  }

  @Override
  public double breakBlockPenalty() {
    return breakBlockPenalty;
  }

  @Override
  public double placeBlockPenalty() {
    return placeBlockPenalty;
  }

  @Override
  public int expireTimeout() {
    return expireTimeout;
  }

  @Override
  public boolean disablePruning() {
    return disablePruning;
  }

  @Override
  public double yawJitterMin() {
    return yawJitterMin;
  }

  @Override
  public double yawJitterMax() {
    return yawJitterMax;
  }

  @Override
  public double pitchJitterMin() {
    return pitchJitterMin;
  }

  @Override
  public double pitchJitterMax() {
    return pitchJitterMax;
  }

  private List<EntityRangeData> getUnfriendlyEntitiesExpensive() {
    if (entity == null) {
      return List.of();
    }

    return StreamSupport.stream(((ClientLevel) entity.level()).entitiesForRendering().spliterator(), false)
      .filter(e -> e != entity)
      .filter(_ -> !entity.getType().getCategory().isFriendly())
      .flatMap(e -> e instanceof LivingEntity livingEntity
        && livingEntity.getAttributeValue(Attributes.FOLLOW_RANGE) > 0
        ? Stream.of(livingEntity) : Stream.empty())
      .map(e -> new EntityRangeData(
        e.getAttributeValue(Attributes.FOLLOW_RANGE),
        SFVec3i.fromInt(e.blockPosition())
      ))
      .toList();
  }

  private record EntityRangeData(double followRange, SFVec3i entityPosition) {}
}
