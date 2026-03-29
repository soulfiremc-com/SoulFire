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
import net.minecraft.world.inventory.ContainerInput;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Action node that drops items from a specific inventory slot.
/// First picks up the item from the slot (left-click), then drops it
/// by clicking outside the inventory window (slot -999).
public final class DropSlotNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.drop_slot")
    .displayName("Drop Slot")
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("slot", "Slot", PortType.NUMBER, "0", "Inventory menu slot index to drop from")
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Picks up an item from a slot and drops it outside the inventory")
    .icon("trash-2")
    .color("#FF9800")
    .addKeywords("drop", "slot", "inventory", "throw", "discard", "remove")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var slot = getIntInput(inputs, "slot", 0);

    runOnTickThread(runtime, bot, () -> {
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player != null && gameMode != null) {
        var containerId = player.containerMenu.containerId;

        // Step 1: Left-click the slot to pick up the item onto the cursor
        gameMode.handleContainerInput(
          containerId, slot, 0, ContainerInput.PICKUP, player
        );

        // Step 2: Left-click outside the inventory (slot -999) to drop the cursor item
        gameMode.handleContainerInput(
          containerId, -999, 0, ContainerInput.PICKUP, player
        );
      }
    });

    return completedEmptyMono();
  }
}
