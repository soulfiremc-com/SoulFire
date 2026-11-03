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

/// Trigger node that fires when the bot has fully joined and is ready to interact.
/// This waits until the player object is initialized (unlike OnBotInit which fires earlier).
/// Outputs: bot (the bot that joined), serverAddress, username
public final class OnJoinNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("trigger.on_join")
    .displayName("On player ready")
    .keywords(List.of("On Join"))
    .category(CategoryRegistry.TRIGGERS)
    .addInputs()
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("bot", "Bot", PortType.BOT, "The bot that joined"),
      PortDefinition.output("serverAddress", "Server Address", PortType.STRING, "The server address"),
      PortDefinition.output("username", "Username", PortType.STRING, "The bot's username")
    )
    .isTrigger(true)
    .description("Fires when the bot has fully joined and the player is ready to interact")
    .icon("log-in")
    .color("#4CAF50")
    .addKeywords("join", "connect", "login", "server", "spawn", "ready")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = getBotInput(inputs);

    var serverAddress = bot != null ? bot.serverAddress().toString() : "";
    var username = bot != null ? bot.accountName() : "";

    // Output data so it can be wired to downstream nodes
    return completedMono(results(
      "bot", bot,
      "serverAddress", serverAddress,
      "username", username
    ));
  }
}
