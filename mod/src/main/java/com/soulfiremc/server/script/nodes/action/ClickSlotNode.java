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

/// Action node that performs an inventory slot click.
/// Supports left click, right click, and shift click operations.
public final class ClickSlotNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.click_slot")
    .displayName("Click Slot")
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("slot", "Slot", PortType.NUMBER, "0", "Inventory menu slot index to click (-999 to drop the carried item)"),
      PortDefinition.inputWithDefault("clickType", "Click Type", PortType.STRING, "\"left\"", "Click type: left, right, or shift")
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Clicks an inventory slot (left, right, or shift click)")
    .icon("mouse-pointer-click")
    .color("#FF9800")
    .addKeywords("click", "slot", "inventory", "move", "item", "swap")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var slot = getIntInput(inputs, "slot", 0);
    var clickTypeStr = getStringInput(inputs, "clickType", "left");

    var clickType = switch (clickTypeStr.toLowerCase()) {
      case "right" -> ContainerInput.PICKUP;
      case "shift" -> ContainerInput.QUICK_MOVE;
      default -> ContainerInput.PICKUP;
    };
    var button = "right".equalsIgnoreCase(clickTypeStr) ? 1 : 0;

    runOnTickThread(runtime, bot, () -> {
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player != null && gameMode != null) {
        gameMode.handleContainerInput(
          player.containerMenu.containerId, slot, button, clickType, player
        );
      }
    });

    return completedEmptyMono();
  }
}
