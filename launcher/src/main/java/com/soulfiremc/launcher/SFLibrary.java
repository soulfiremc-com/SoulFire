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
package com.soulfiremc.launcher;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.LibClassifier;

public enum SFLibrary implements LibClassifier.LibraryType {
  JLINE_TERMINAL("org/jline/terminal/Terminal.class"),
  JLINE_READER("org/jline/reader/LineReader.class"),
  JUL("org/apache/logging/log4j/jul/LogManager.class"),
  JANSI("org/fusesource/jansi/AnsiConsole.class"),
  DISRUPTOR("com/lmax/disruptor/EventTranslatorVararg.class");

  public static final SFLibrary[] LOGGING = {
    JLINE_TERMINAL,
    JLINE_READER,
    JUL,
    JANSI,
    DISRUPTOR
  };

  private final EnvType env;
  private final String[] paths;

  SFLibrary(String path) {
    this(null, new String[]{path});
  }

  SFLibrary(String... paths) {
    this(null, paths);
  }

  SFLibrary(EnvType env, String... paths) {
    this.paths = paths;
    this.env = env;
  }

  @Override
  public boolean isApplicable(EnvType env) {
    return this.env == null || this.env == env;
  }

  @Override
  public String[] getPaths() {
    return paths;
  }
}
