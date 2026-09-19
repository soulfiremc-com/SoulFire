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
package com.soulfiremc.server.util;

import com.soulfiremc.server.util.structs.IDBooleanMap;
import com.soulfiremc.server.util.structs.IDMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public final class SFBlockHelpers {
  public static final IDMap<BlockState, VoxelShape> RAW_COLLISION_SHAPES = new IDMap<>(
    Block.BLOCK_STATE_REGISTRY, blockState -> blockState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
  public static final IDBooleanMap<BlockState> COLLISION_SHAPE_TOP_FACE_FULL = new IDBooleanMap<>(
    Block.BLOCK_STATE_REGISTRY, blockState -> Block.isFaceFull(RAW_COLLISION_SHAPES.get(blockState), Direction.UP));
  public static final IDBooleanMap<BlockState> WALKABLE_FLOOR_BLOCK = new IDBooleanMap<>(
    Block.BLOCK_STATE_REGISTRY, SFBlockHelpers::computeWalkableFloorBlock);
  public static final IDBooleanMap<BlockState> FEET_SUPPORT_IN_BLOCK = new IDBooleanMap<>(
    Block.BLOCK_STATE_REGISTRY, SFBlockHelpers::computeFeetSupportInBlock);
  public static final IDBooleanMap<BlockState> BODY_PASSABLE_BLOCK = new IDBooleanMap<>(
    Block.BLOCK_STATE_REGISTRY, SFBlockHelpers::computeBodyPassableBlock);
  public static final IDBooleanMap<BlockState> OPENABLE_PASSAGE_BLOCK = new IDBooleanMap<>(
    Block.BLOCK_STATE_REGISTRY, SFBlockHelpers::computeOpenablePassageBlock);

  private SFBlockHelpers() {}

  public static boolean isBlockFree(BlockState blockState) {
    return isCollisionShapeEmpty(blockState)
      && blockState.getFluidState().getType() == Fluids.EMPTY
      && !affectsTouchMovementSpeed(blockState.getBlock());
  }

  public static boolean isCollisionShapeEmpty(BlockState blockState) {
    return blockState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
  }

  public static boolean isCollisionShapeFullBlock(BlockState blockState) {
    return blockState.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
  }

  public static boolean affectsTouchMovementSpeed(Block blockType) {
    return blockType == Blocks.COBWEB
      || blockType == Blocks.POWDER_SNOW
      || blockType == Blocks.SOUL_SAND
      || blockType == Blocks.SOUL_SOIL
      || blockType == Blocks.HONEY_BLOCK
      || blockType == Blocks.SLIME_BLOCK;
  }

  public static boolean isHurtOnTouchFluid(Fluid fluidType) {
    return fluidType == Fluids.LAVA
      || fluidType == Fluids.FLOWING_LAVA;
  }

  public static boolean isSwimmableWaterBlock(BlockState state) {
    var fluidType = state.getFluidState().getType();
    return (fluidType == Fluids.WATER || fluidType == Fluids.FLOWING_WATER)
      && isCollisionShapeEmpty(state);
  }

  public static boolean isHurtOnTouchSide(BlockState blockState) {
    var blockType = blockState.getBlock();
    return blockType == Blocks.CACTUS
      || blockType == Blocks.SWEET_BERRY_BUSH
      || blockType == Blocks.WITHER_ROSE
      || blockType == Blocks.FIRE
      || blockType == Blocks.SOUL_FIRE
      // Not hurt, but definitely slows you down
      || blockType == Blocks.COBWEB
      || blockType == Blocks.POWDER_SNOW
      || isHurtOnTouchFluid(blockState.getFluidState().getType());
  }

  public static boolean isHurtWhenStoodOn(BlockState blockState) {
    var blockType = blockState.getBlock();
    return blockType == Blocks.MAGMA_BLOCK
      || isHurtOnTouchFluid(blockState.getFluidState().getType());
  }

  public static boolean isSafeBlockToStandOn(BlockState state) {
    return isTopFullBlock(state) && !isHurtWhenStoodOn(state);
  }

  public static boolean isWalkableFloorBlock(BlockState state) {
    return WALKABLE_FLOOR_BLOCK.get(state);
  }

  /// Returns whether a floor is safe to use after gravity updates settle.
  ///
  /// World generation can leave gravel, sand, and other falling blocks
  /// suspended over air or fluid until a nearby player loads or updates the
  /// chunk. Treating that temporary state as a landing surface can turn an
  /// otherwise safe descent into an unbounded fall.
  public static boolean isStableWalkableFloorBlock(
    BlockGetter level,
    BlockPos position,
    BlockState state
  ) {
    if (!isWalkableFloorBlock(state)) {
      return false;
    }
    if (!isGravityAffected(state)) {
      return true;
    }

    var supportPosition = position.below();
    var support = level.getBlockState(supportPosition);
    while (isGravityAffected(support)) {
      supportPosition = supportPosition.below();
      if (supportPosition.getY() < level.getMinY()) {
        return false;
      }
      support = level.getBlockState(supportPosition);
    }
    return !FallingBlock.isFree(support);
  }

  public static boolean canSupportFeetInBlock(BlockState state) {
    return FEET_SUPPORT_IN_BLOCK.get(state);
  }

  public static boolean isBodyPassableBlock(BlockState state) {
    return BODY_PASSABLE_BLOCK.get(state);
  }

  public static boolean isOpenablePassageBlock(BlockState state) {
    return OPENABLE_PASSAGE_BLOCK.get(state);
  }

  public static boolean isPassageBlockOpen(BlockState state) {
    var block = state.getBlock();
    if (block instanceof DoorBlock door) {
      return door.isOpen(state);
    }

    if (block instanceof FenceGateBlock) {
      return state.getValue(FenceGateBlock.OPEN);
    }

    return false;
  }

  public static BlockPos getOpenablePassagePos(BlockPos pos, BlockState state) {
    if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
      return pos.below();
    }

    return pos;
  }

  public static boolean isTopFullBlock(BlockState blockState) {
    return COLLISION_SHAPE_TOP_FACE_FULL.get(blockState);
  }

  public static boolean isDiggable(Block type) {
    return type.defaultDestroyTime() != -1;
  }

  public static boolean isGravityAffected(BlockState state) {
    return state.getBlock() instanceof FallingBlock;
  }

  public static boolean isUsableBlockItem(Block blockType) {
    return BlockItems.hasItem(blockType);
  }

  public static boolean isEmptyBlock(Block type) {
    // Void air stands for not loaded blocks, so we do not know what is there
    return type instanceof AirBlock && type != Blocks.VOID_AIR;
  }

  private static boolean computeWalkableFloorBlock(BlockState state) {
    if (isSafeBlockToStandOn(state)) {
      return true;
    }

    if (isHurtWhenStoodOn(state)) {
      return false;
    }

    if (state.is(BlockTags.STAIRS)) {
      return true;
    }

    if (state.getBlock() instanceof SlabBlock) {
      return state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM;
    }

    return false;
  }

  private static boolean computeFeetSupportInBlock(BlockState state) {
    if (isHurtWhenStoodOn(state) || affectsTouchMovementSpeed(state.getBlock())) {
      return false;
    }

    var block = state.getBlock();
    if (block instanceof CarpetBlock) {
      return true;
    }

    if (block instanceof SnowLayerBlock) {
      return state.getValue(SnowLayerBlock.LAYERS) < SnowLayerBlock.HEIGHT_IMPASSABLE;
    }

    if (block instanceof SlabBlock) {
      return state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    if (block instanceof StairBlock) {
      return state.getValue(StairBlock.HALF) == Half.BOTTOM;
    }

    return false;
  }

  private static boolean computeBodyPassableBlock(BlockState state) {
    if (isBlockFree(state)) {
      return true;
    }

    var block = state.getBlock();
    if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
      return isPassageBlockOpen(state);
    }

    return false;
  }

  private static boolean computeOpenablePassageBlock(BlockState state) {
    var block = state.getBlock();
    if (block instanceof DoorBlock) {
      return DoorBlock.isWoodenDoor(state) && !isPassageBlockOpen(state);
    }

    if (block instanceof FenceGateBlock) {
      return !isPassageBlockOpen(state);
    }

    return false;
  }
}
