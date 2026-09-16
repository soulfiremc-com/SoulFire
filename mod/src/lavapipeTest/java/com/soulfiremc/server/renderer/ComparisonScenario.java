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
package com.soulfiremc.server.renderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Catalog shared by the manual client, Gradle task, and batch runner.
record ComparisonScenario(String name, String family, String variant, float partialTick,
                          int animationTick, String dimension, String biome) {
  private static final Map<String, ComparisonScenario> SCENARIOS = load();

  static ComparisonScenario named(String name) {
    return Objects.requireNonNull(SCENARIOS.get(name), "Unknown comparison scene: " + name);
  }

  boolean isolatedWorld() {
    return !family.equals("legacy");
  }

  private static Map<String, ComparisonScenario> load() {
    var scenarios = new LinkedHashMap<String, ComparisonScenario>();
    try (var stream = Objects.requireNonNull(ComparisonScenario.class.getResourceAsStream("/lavapipe-scenes.csv"));
         var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      reader.readLine();
      for (var line = reader.readLine(); line != null; line = reader.readLine()) {
        var fields = line.split(",");
        if (fields.length != 7) throw new IllegalStateException("Invalid scene catalog row: " + line);
        var scenario = new ComparisonScenario(fields[0], fields[1], fields[2], Float.parseFloat(fields[3]),
          Integer.parseInt(fields[4]), fields[5], fields[6]);
        if (scenario.partialTick < 0 || scenario.partialTick > 1 || scenarios.put(scenario.name, scenario) != null) {
          throw new IllegalStateException("Invalid or duplicate scene: " + scenario.name);
        }
      }
      return Map.copyOf(scenarios);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
