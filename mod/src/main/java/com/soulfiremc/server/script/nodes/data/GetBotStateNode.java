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
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/// Data node that gets various boolean states of the bot.
/// Outputs: isOnGround, isInWater, isSwimming, isSprinting, isSneaking, isFallFlying
public final class GetBotStateNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("data.get_bot_state")
    .displayName("Get movement state")
    .keywords(List.of("Get Bot State"))
    .category(CategoryRegistry.DATA)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn()
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("isOnGround", "On Ground", PortType.BOOLEAN, "True if bot is on ground"),
      PortDefinition.output("isInWater", "In Water", PortType.BOOLEAN, "True if bot is in water"),
      PortDefinition.output("isSwimming", "Swimming", PortType.BOOLEAN, "True if bot is swimming"),
      PortDefinition.output("isSprinting", "Sprinting", PortType.BOOLEAN, "True if bot is sprinting"),
      PortDefinition.output("isSneaking", "Sneaking", PortType.BOOLEAN, "True if bot is sneaking"),
      PortDefinition.output("isFallFlying", "Elytra Flying", PortType.BOOLEAN, "True if bot is elytra flying")
    )
    .description("Gets various movement and state flags of the bot")
    .icon("user-check")
    .color("#9C27B0")
    .addKeywords("state", "ground", "water", "swimming", "sprinting", "sneaking", "flying", "elytra")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var player = bot.minecraft().player;

    if (player == null) {
      return completedMono(results(
        "isOnGround", false,
        "isInWater", false,
        "isSwimming", false,
        "isSprinting", false,
        "isSneaking", false,
        "isFallFlying", false
      ));
    }

    return completedMono(results(
      "isOnGround", player.onGround(),
      "isInWater", player.isInWater(),
      "isSwimming", player.isSwimming(),
      "isSprinting", player.isSprinting(),
      "isSneaking", player.isShiftKeyDown(),
      "isFallFlying", player.isFallFlying()
    ));
  }
}
