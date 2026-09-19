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
import com.soulfiremc.server.util.MouseClickHelper;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Action node that makes the bot attack the entity it's looking at.
/// Uses the bot's current view direction to determine the target.
public final class AttackNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.attack")
    .displayName("Attack")
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn()
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Makes the bot attack the entity it's looking at (left click)")
    .icon("swords")
    .color("#FF9800")
    .addKeywords("attack", "hit", "fight", "damage", "combat", "left click")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);

    runOnTickThread(runtime, bot, () -> MouseClickHelper.performLeftClick(bot.minecraft()));

    return completedEmptyMono();
  }
}
