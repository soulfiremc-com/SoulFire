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
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/// Action node that selects a hotbar slot (0-8).
/// Input: slot (0-8)
public final class SelectSlotNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.select_slot")
    .displayName("Select hotbar slot")
    .keywords(List.of("Select Slot"))
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("slot", "Slot", PortType.NUMBER, "0", "Hotbar slot (0-8)")
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Selects a hotbar slot (0-8)")
    .icon("hand")
    .color("#FF9800")
    .addKeywords("slot", "hotbar", "select", "inventory", "hand")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var slot = getIntInput(inputs, "slot", 0);

    // Clamp slot to valid range
    slot = Math.max(0, Math.min(8, slot));
    var finalSlot = slot;

    runOnTickThread(runtime, bot, () -> {
      var player = bot.minecraft().player;
      if (player != null) {
        player.getInventory().setSelectedSlot(finalSlot);
      }
    });

    return completedEmptyMono();
  }
}
