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
package com.soulfiremc.server.script.nodes.data;

import com.soulfiremc.server.script.*;
import com.soulfiremc.server.util.SFInventoryHelpers;
import com.soulfiremc.server.util.SFItemHelpers;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Data node that searches the bot's inventory for items matching criteria.
/// Can filter by item ID or food-only. Returns the first match using
/// the same priority as plugin inventory helpers (selected > offhand > hotbar > main > armor).
public final class FindItemNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("data.find_item")
    .displayName("Find Item")
    .category(CategoryRegistry.DATA)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("itemId", "Item ID", PortType.STRING, "", "Item registry name to search for (empty = any)"),
      PortDefinition.inputWithDefault("foodOnly", "Food Only", PortType.BOOLEAN, "false", "Only match edible food items")
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("found", "Found", PortType.BOOLEAN, "Whether a matching item was found"),
      PortDefinition.output("slot", "Slot", PortType.NUMBER, "Inventory menu slot index of the match"),
      PortDefinition.output("itemId", "Item ID", PortType.STRING, "Registry name of the found item"),
      PortDefinition.output("count", "Count", PortType.NUMBER, "Stack count of the found item"),
      PortDefinition.output("isHotbar", "Is Hotbar", PortType.BOOLEAN, "Whether the slot is in the hotbar"),
      PortDefinition.output("hotbarIndex", "Hotbar Index", PortType.NUMBER, "Hotbar index (0-8), or -1 if not hotbar")
    )
    .description("Searches inventory for items by ID or food filter")
    .icon("search")
    .color("#9C27B0")
    .addKeywords("find", "item", "inventory", "search", "food", "slot")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var player = bot.minecraft().player;
    var itemIdFilter = getStringInput(inputs, "itemId", "");
    var foodOnly = getBooleanInput(inputs, "foodOnly", false);

    if (player == null) {
      return completedMono(results(
        "found", false, "slot", -1, "itemId", "minecraft:air",
        "count", 0, "isHotbar", false, "hotbarIndex", -1
      ));
    }

    var inventory = player.getInventory();
    var menu = player.inventoryMenu;

    var match = SFInventoryHelpers.findMatchingSlotForAction(inventory, menu, itemStack -> {
      if (itemStack.isEmpty()) {
        return false;
      }
      if (foodOnly && !SFItemHelpers.isGoodEdibleFood(itemStack)) {
        return false;
      }
      if (!itemIdFilter.isEmpty() && !itemStack.typeHolder().getRegisteredName().equals(itemIdFilter)) {
        return false;
      }
      return true;
    });

    if (match.isEmpty()) {
      return completedMono(results(
        "found", false, "slot", -1, "itemId", "minecraft:air",
        "count", 0, "isHotbar", false, "hotbarIndex", -1
      ));
    }

    var slot = match.getAsInt();
    var item = menu.getSlot(slot).getItem();
    var isHotbar = SFInventoryHelpers.isSelectableHotbarSlot(slot);
    var hotbarIndex = isHotbar ? SFInventoryHelpers.toHotbarIndex(slot) : -1;

    return completedMono(results(
      "found", true,
      "slot", slot,
      "itemId", item.typeHolder().getRegisteredName(),
      "count", item.getCount(),
      "isHotbar", isHotbar,
      "hotbarIndex", hotbarIndex
    ));
  }
}
