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
package com.soulfiremc.server.script.nodes.trigger;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/// Trigger node that fires when the bot connection is initialized.
/// This fires early during connection setup, before the player object is ready.
/// Use OnJoin instead if you need to interact with the player.
/// Outputs: bot (the bot), serverAddress, username
public final class OnBotInitNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("trigger.on_bot_init")
    .displayName("On connection created")
    .keywords(List.of("On Bot Init"))
    .category(CategoryRegistry.TRIGGERS)
    .addInputs()
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("bot", "Bot", PortType.BOT, "The bot being initialized"),
      PortDefinition.output("serverAddress", "Server Address", PortType.STRING, "The server address"),
      PortDefinition.output("username", "Username", PortType.STRING, "The bot's username")
    )
    .isTrigger(true)
    .description("Fires early when the bot connection is initialized (before player is ready)")
    .icon("power")
    .color("#FF9800")
    .addKeywords("init", "initialize", "start", "connect", "early")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = getBotInput(inputs);

    var serverAddress = bot != null ? bot.serverAddress().toString() : "";
    var username = bot != null ? bot.accountName() : "";

    return completedMono(results(
      "bot", bot,
      "serverAddress", serverAddress,
      "username", username
    ));
  }
}
