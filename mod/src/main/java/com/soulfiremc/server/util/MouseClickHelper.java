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

import net.minecraft.client.Minecraft;

/// Dispatches single clicks through Minecraft's handlers, including ViaFabricPlus mixins.
/// Call on the bot's tick thread with its Minecraft instance and connection context.
public final class MouseClickHelper {
  private MouseClickHelper() {
  }

  /// Refreshes the target and performs vanilla attack dispatch, including miss cooldowns.
  public static void performLeftClick(Minecraft minecraft) {
    if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
      return;
    }

    minecraft.pick(1.0F);
    minecraft.startAttack();
  }

  /// Refreshes the target and performs vanilla interaction and item-use dispatch for both hands.
  public static void performRightClick(Minecraft minecraft) {
    if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
      return;
    }

    minecraft.pick(1.0F);
    minecraft.startUseItem();
  }
}
