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
package com.soulfiremc.bootstrap;

import com.soulfiremc.server.api.InternalPluginClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternalPluginDiscoveryTest {
  @Test
  void discoversPackagedPluginsAndTheirOrderingMetadata() {
    try (var scan = SoulFireAbstractBootstrap.internalPluginScanner().scan()) {
      var plugins = scan.getClassesWithAnnotation(InternalPluginClass.class);
      assertTrue(plugins.containsName("com.soulfiremc.server.plugins.SdkExamplePlugin"));
      var forwarding = plugins.get("com.soulfiremc.server.plugins.ForwardingBypass");
      assertNotNull(forwarding);
      assertEquals(2, forwarding.getAllAnnotationInfo().get(InternalPluginClass.class.getName()).getParameterValues().getValue("order"));
      assertTrue(plugins.stream().allMatch(plugin -> plugin.getPackageName().equals("com.soulfiremc.server.plugins")));
    }
  }
}
