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
package com.soulfiremc.server.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PovInputCodesTest {
  @ParameterizedTest
  @CsvSource({"87,26,119", "65,4,97", "90,29,122", "49,30,49", "48,39,48", "256,41,27", "257,40,13", "261,76,127", "-1,0,0"})
  void translatesMovementTextAndEditingKeys(int wireCode, int scancode, int keycode) {
    assertEquals(scancode, PovInputCodes.scancode(wireCode));
    assertEquals(keycode, PovInputCodes.keycode(wireCode));
  }

  @Test
  void translatesCombinedShortcutModifiersAndMouseButtons() {
    assertEquals(3 | 192 | 768 | 3072 | 8192 | 4096, PovInputCodes.modifiers(63));
    assertEquals(0, PovInputCodes.modifiers(0));
    assertEquals(1, PovInputCodes.mouseButton(0));
    assertEquals(3, PovInputCodes.mouseButton(1));
    assertEquals(2, PovInputCodes.mouseButton(2));
    assertEquals(8, PovInputCodes.mouseButton(7));
  }
}
