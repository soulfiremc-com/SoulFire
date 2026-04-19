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
package com.soulfiremc.test.script;

import com.google.gson.JsonParser;
import com.soulfiremc.server.script.NodeValue;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.soulfiremc.test.script.ScriptTestHelper.executeNode;
import static org.junit.jupiter.api.Assertions.*;

/// Tests the VECTOR3 node execution path.
final class Vector3NodeTest {

  @Test
  void createAndSplitVector3RoundTripsTypedValue() {
    var created = executeNode("util.create_vector3", Map.of(
      "x", NodeValue.of(1.5),
      "y", NodeValue.of(2.5),
      "z", NodeValue.of(3.5)
    ));
    var vector = created.get("vector");

    assertInstanceOf(NodeValue.Vector3.class, vector, "Vector outputs should remain typed");
    assertEquals(new Vec3(1.5, 2.5, 3.5), vector.asVec3(), "Created vector should preserve coordinates");

    var split = executeNode("util.split_vector3", Map.of("vector", vector));
    assertEquals(1.5, split.get("x").asDouble(0.0));
    assertEquals(2.5, split.get("y").asDouble(0.0));
    assertEquals(3.5, split.get("z").asDouble(0.0));
  }

  @Test
  void splitVector3AcceptsLegacyJsonVectorInput() {
    var split = executeNode("util.split_vector3", Map.of(
      "vector", NodeValue.fromJson(JsonParser.parseString("[1.5,2.5,3.5]"))
    ));

    assertEquals(1.5, split.get("x").asDouble(0.0));
    assertEquals(2.5, split.get("y").asDouble(0.0));
    assertEquals(3.5, split.get("z").asDouble(0.0));
  }

  @Test
  void vectorConstantProvidesTypedVectorOutput() {
    var outputs = executeNode("constant.vector3", Map.of(
      "x", NodeValue.of("1.5"),
      "y", NodeValue.of(true),
      "z", NodeValue.of(3)
    ));

    var vector = outputs.get("vector");
    assertInstanceOf(NodeValue.Vector3.class, vector, "Vector constant should expose a typed vector output");
    assertEquals(new Vec3(1.5, 1.0, 3.0), vector.asVec3(), "Vector constant should use centralized coercion");
  }

  @Test
  void invalidLegacyVectorShapeNoLongerSilentlyParses() {
    assertNull(NodeValue.fromJson(JsonParser.parseString("[1.5,2.5]")).asVec3(),
      "Malformed vector arrays should not decode");
  }
}
