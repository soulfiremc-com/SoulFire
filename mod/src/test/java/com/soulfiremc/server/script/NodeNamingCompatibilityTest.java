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
package com.soulfiremc.server.script;

import com.soulfiremc.server.script.nodes.NodeRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NodeNamingCompatibilityTest {
  @ParameterizedTest
  @CsvSource({
    "flow.debounce, Cooldown, Debounce",
    "trigger.on_bot_init, On connection created, On Bot Init",
    "trigger.on_join, On player ready, On Join",
    "data.get_bot_state, Get movement state, Get Bot State",
    "action.select_slot, Select hotbar slot, Select Slot",
    "action.drop_item, Drop cursor item, Drop Item",
    "action.print, Log message, Print",
    "action.pathfind_to, Go to position, Pathfind To",
    "variable.get_session_instance, Get project runtime variable, Get Session Instance Variable",
    "variable.get_session_bot, Get connection variable, Get Session Bot Variable",
    "variable.get_persistent_instance, Get persistent project variable, Get Persistent Instance Variable",
    "variable.get_persistent_bot, Get persistent bot variable, Get Persistent Bot Variable",
    "variable.set_session_instance, Set project runtime variable, Set Session Instance Variable",
    "variable.set_session_bot, Set connection variable, Set Session Bot Variable",
    "variable.set_persistent_instance, Set persistent project variable, Set Persistent Instance Variable",
    "variable.set_persistent_bot, Set persistent bot variable, Set Persistent Bot Variable"
  })
  void existingNodeTypesResolveWithNewNamesAndLegacySearchTerms(String type, String displayName, String previousName) {
    assertDoesNotThrow(() -> NodeRegistry.create(type));
    var metadata = NodeRegistry.getMetadata(type);

    assertEquals(type, metadata.type());
    assertEquals(displayName, metadata.displayName());
    assertTrue(metadata.keywords().contains(previousName));
  }
}
