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

import com.soulfiremc.server.bot.BotInteractionSupport;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/// Simulates mouse clicks using Minecraft's item, entity, and block targeting rules.
public final class MouseClickHelper {
  private MouseClickHelper() {
  }

  /// Attacks the targeted entity, starts breaking the targeted block, or swings on a miss.
  public static void performLeftClick(LocalPlayer player, MultiPlayerGameMode gameMode) {
    var hitResult = player.raycastHitResult(1.0F, player);
    if (hitResult instanceof EntityHitResult entityHitResult) {
      gameMode.attack(player, entityHitResult.getEntity());
    } else if (hitResult instanceof BlockHitResult blockHitResult && hitResult.getType() == HitResult.Type.BLOCK) {
      gameMode.startDestroyBlock(blockHitResult.getBlockPos(), blockHitResult.getDirection());
    }
    player.swing(InteractionHand.MAIN_HAND);
  }

  /// Interacts with the target or uses the main-hand item when the block interaction passes.
  public static void performRightClick(LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
    var hand = InteractionHand.MAIN_HAND;
    var hitResult = player.raycastHitResult(1.0F, player);

    InteractionResult result;
    if (hitResult instanceof EntityHitResult entityHitResult) {
      result = gameMode.interact(player, entityHitResult.getEntity(), entityHitResult, hand);
    } else if (hitResult instanceof BlockHitResult blockHitResult && hitResult.getType() == HitResult.Type.BLOCK) {
      result = BotInteractionSupport.withItemUseFallback(
        gameMode.useItemOn(player, hand, blockHitResult),
        () -> useItem(player, level, gameMode)
      );
    } else {
      result = useItem(player, level, gameMode);
    }

    if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
      player.swing(hand);
    }
  }

  private static InteractionResult useItem(LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
    var hand = InteractionHand.MAIN_HAND;
    var itemStack = player.getItemInHand(hand);
    return !itemStack.isEmpty() && itemStack.isItemEnabled(level.enabledFeatures())
      ? gameMode.useItem(player, hand)
      : InteractionResult.PASS;
  }
}
