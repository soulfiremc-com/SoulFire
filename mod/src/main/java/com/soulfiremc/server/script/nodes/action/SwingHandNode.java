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
package com.soulfiremc.server.script.nodes.action;

import com.soulfiremc.server.script.*;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Action node that swings the bot's arm without attacking.
/// Useful for cosmetic swings, fake attacks, or intentional misses.
public final class SwingHandNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.swing_hand")
    .displayName("Swing Hand")
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn()
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Swings the bot's arm without attacking (cosmetic swing)")
    .icon("hand")
    .color("#FF9800")
    .addKeywords("swing", "arm", "wave", "miss", "fake")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);

    runOnTickThread(runtime, bot, () -> {
      var player = bot.minecraft().player;
      if (player != null) {
        player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
      }
    });

    return completedEmptyMono();
  }
}
