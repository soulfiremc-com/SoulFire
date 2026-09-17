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

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PovClientActionsTest {
  @Test
  void forwardOnlyWithinTheCurrentInputScope() {
    var copies = new ArrayList<String>();
    var links = new ArrayList<String>();
    var actions = new PovClientActions(copies::add, links::add);
    var url = URI.create("https://example.com/store");
    PovClientActions.open(url);
    PovClientActions.copy("outside");
    assertTrue(copies.isEmpty());
    assertTrue(links.isEmpty());
    ScopedValue.where(PovClientActions.CURRENT, actions).run(() -> {
      PovClientActions.copy(url.toString());
      PovClientActions.open(url);
      PovClientActions.open(URI.create("file:///tmp/test"));
      PovClientActions.open(URI.create("javascript:alert(1)"));
      PovClientActions.open(URI.create("https:relative"));
    });
    assertEquals(1, copies.size());
    assertEquals(url.toString(), copies.getFirst());
    assertEquals(copies, links);
    PovClientActions.copy("after");
    assertEquals(1, copies.size());
  }
}
