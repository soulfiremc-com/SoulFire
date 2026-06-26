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
package com.soulfiremc.test.utils;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class MinecraftBootstrapExtension implements BeforeAllCallback, BeforeEachCallback {
  @Override
  public void beforeAll(ExtensionContext context) {
    prepareThreadContext();
    TestBootstrap.bootstrapForTest();
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    prepareThreadContext();
    TestBootstrap.bootstrapForTest();
  }

  private static void prepareThreadContext() {
    Thread.currentThread().setContextClassLoader(MinecraftBootstrapExtension.class.getClassLoader());
  }
}
