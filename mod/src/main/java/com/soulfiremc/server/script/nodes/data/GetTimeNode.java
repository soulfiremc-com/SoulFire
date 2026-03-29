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

import java.util.Map;

/// Data node that gets the current game time.
/// Outputs: gameTime, dayTime, isDay, isNight
public final class GetTimeNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("data.get_time")
    .displayName("Get Time")
    .category(CategoryRegistry.DATA)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn()
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("gameTime", "Game Time", PortType.NUMBER, "Total game time in ticks"),
      PortDefinition.output("dayTime", "Day Time", PortType.NUMBER, "Time of day (0-24000)"),
      PortDefinition.output("isDay", "Is Day", PortType.BOOLEAN, "True if daytime (6000-18000)"),
      PortDefinition.output("isNight", "Is Night", PortType.BOOLEAN, "True if nighttime")
    )
    .description("Gets the current game time and day/night status")
    .icon("clock")
    .color("#9C27B0")
    .addKeywords("time", "day", "night", "tick", "clock")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var level = bot.minecraft().level;

    if (level == null) {
      return completedMono(results(
        "gameTime", 0L,
        "dayTime", 0L,
        "isDay", true,
        "isNight", false
      ));
    }

    var gameTime = level.getGameTime();
    var dayTime = level.getLevelData().getGameTime() % 24000;
    // Day is roughly 0-12000 (6am-6pm), night is 12000-24000 (6pm-6am)
    // More precisely: 0=6am, 6000=noon, 12000=6pm, 18000=midnight
    var isDay = dayTime >= 0 && dayTime < 12000;

    return completedMono(results(
      "gameTime", gameTime,
      "dayTime", dayTime,
      "isDay", isDay,
      "isNight", !isDay
    ));
  }
}
