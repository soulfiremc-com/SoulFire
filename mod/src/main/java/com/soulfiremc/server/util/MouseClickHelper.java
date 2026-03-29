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

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Helper class for simulating mouse click actions (left/right click).
 * Shared between gRPC service and server commands.
 */
public final class MouseClickHelper {
  private static final double PICK_RANGE = 4.5; // Standard Minecraft reach distance

  private MouseClickHelper() {
  }

  /**
   * Performs a left mouse button click action.
   * Attacks an entity if looking at one, otherwise starts breaking a block.
   *
   * @param player   The player performing the action
   * @param level    The level/world
   * @param gameMode The game mode controller
   */
  public static void performLeftClick(LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
    var eyePos = player.getEyePosition();
    var lookVec = player.getLookAngle();
    var reachVec = eyePos.add(lookVec.scale(PICK_RANGE));

    // Check for entity hit first
    var entityHitResult = findEntityHit(player, eyePos, reachVec);

    if (entityHitResult != null && entityHitResult.getEntity() != null) {
      // Attack entity
      gameMode.attack(player, entityHitResult.getEntity());
      player.swing(InteractionHand.MAIN_HAND);
    } else {
      // Check for block hit
      var blockHitResult = level.clip(new ClipContext(
        eyePos,
        reachVec,
        ClipContext.Block.OUTLINE,
        ClipContext.Fluid.NONE,
        player
      ));

      if (blockHitResult.getType() == HitResult.Type.BLOCK) {
        // Start breaking block
        gameMode.startDestroyBlock(blockHitResult.getBlockPos(), blockHitResult.getDirection());
        player.swing(InteractionHand.MAIN_HAND);
      } else {
        // Just swing (miss)
        player.swing(InteractionHand.MAIN_HAND);
      }
    }
  }

  /**
   * Performs a right mouse button click action.
   * Interacts with an entity, uses item on a block, or uses the item in hand.
   *
   * @param player   The player performing the action
   * @param level    The level/world
   * @param gameMode The game mode controller
   */
  public static void performRightClick(LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
    var hand = InteractionHand.MAIN_HAND;
    var eyePos = player.getEyePosition();
    var lookVec = player.getLookAngle();
    var reachVec = eyePos.add(lookVec.scale(PICK_RANGE));

    // Check for entity hit first
    var entityHitResult = findEntityHit(player, eyePos, reachVec);

    if (entityHitResult != null && entityHitResult.getEntity() != null) {
      // Interact with entity
      if (gameMode.interact(player, entityHitResult.getEntity(), entityHitResult, hand) instanceof InteractionResult.Success success) {
        if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
          player.swing(hand);
        }
      }
    } else {
      // Check for block hit
      var blockHitResult = level.clip(new ClipContext(
        eyePos,
        reachVec,
        ClipContext.Block.OUTLINE,
        ClipContext.Fluid.NONE,
        player
      ));

      if (blockHitResult.getType() == HitResult.Type.BLOCK) {
        // Use item on block
        if (gameMode.useItemOn(player, hand, blockHitResult) instanceof InteractionResult.Success success) {
          if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
          }
        }
      } else {
        // Just use item in hand
        var itemStack = player.getItemInHand(hand);
        if (!itemStack.isEmpty() && itemStack.isItemEnabled(level.enabledFeatures())) {
          if (gameMode.useItem(player, hand) instanceof InteractionResult.Success success) {
            if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
              player.swing(hand);
            }
          }
        }
      }
    }
  }

  /**
   * Finds an entity that the player is looking at within the pick range.
   */
  private static @Nullable EntityHitResult findEntityHit(LocalPlayer player, Vec3 eyePos, Vec3 reachVec) {
    var aabb = player.getBoundingBox().expandTowards(player.getLookAngle().scale(PICK_RANGE)).inflate(1.0);

    return ProjectileUtil.getEntityHitResult(
      player,
      eyePos,
      reachVec,
      aabb,
      entity -> !entity.isSpectator() && entity.isPickable(),
      PICK_RANGE * PICK_RANGE
    );
  }
}
