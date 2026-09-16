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
package com.soulfiremc.server.grpc;

import com.soulfiremc.grpc.generated.PovInputEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PovInputValidationTest {
  @Test
  void rejectsNonFiniteAndOutOfBoundsPointerInput() {
    for (var value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01}) {
      var event = PovInputEvent.newBuilder().setKind(PovInputEvent.Kind.MOVE).setX(value).setY(0.5).build();
      assertThrows(IllegalArgumentException.class, () -> PovServiceImpl.validate(event));
    }
    assertDoesNotThrow(() -> PovServiceImpl.validate(PovInputEvent.newBuilder()
      .setKind(PovInputEvent.Kind.MOVE).setRelative(true).setX(-120).setY(150).build()));
  }

  @Test
  void validatesKeyButtonAndCharacterRanges() {
    assertThrows(IllegalArgumentException.class, () -> PovServiceImpl.validate(PovInputEvent.newBuilder()
      .setKind(PovInputEvent.Kind.KEY).setCode(999).build()));
    assertThrows(IllegalArgumentException.class, () -> PovServiceImpl.validate(PovInputEvent.newBuilder()
      .setKind(PovInputEvent.Kind.BUTTON).setCode(-1).build()));
    assertThrows(IllegalArgumentException.class, () -> PovServiceImpl.validate(PovInputEvent.newBuilder()
      .setKind(PovInputEvent.Kind.CHARACTER).setCode(0x110000).build()));
    assertThrows(IllegalArgumentException.class, () -> PovServiceImpl.validate(PovInputEvent.newBuilder()
      .setKind(PovInputEvent.Kind.CHARACTER).setCode(0xD800).build()));
    assertDoesNotThrow(() -> PovServiceImpl.validate(PovInputEvent.newBuilder()
      .setKind(PovInputEvent.Kind.CHARACTER).setCode(0x1F600).build()));
  }
}
