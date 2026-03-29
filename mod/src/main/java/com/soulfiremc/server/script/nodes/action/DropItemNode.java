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

/// Action node that drops whatever item is currently held on the cursor.
/// Equivalent to left-clicking outside the inventory window (slot -999).
/// Use right-click mode to drop only one item from the cursor stack.
public final class DropItemNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.drop_item")
    .displayName("Drop Item")
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("dropAll", "Drop All", PortType.BOOLEAN, "true", "Drop entire stack (true) or one item (false)")
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Drops the item currently held on the cursor")
    .icon("hand")
    .color("#FF9800")
    .addKeywords("drop", "item", "cursor", "throw", "discard")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var dropAll = getBooleanInput(inputs, "dropAll", true);

    // Button 0 = drop entire stack (left click outside), button 1 = drop one item (right click outside)
    var button = dropAll ? 0 : 1;

    runOnTickThread(runtime, bot, () -> {
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player != null && gameMode != null) {
        gameMode.handleContainerInput(
          player.containerMenu.containerId, -999, button, ContainerInput.PICKUP, player
        );
      }
    });

    return completedEmptyMono();
  }
}
