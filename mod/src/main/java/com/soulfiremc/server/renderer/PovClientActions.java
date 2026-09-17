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

import java.net.URI;
import java.util.function.Consumer;

/// Local desktop effects are forwarded only while processing authenticated POV input.
public record PovClientActions(Consumer<String> clipboard, Consumer<String> openUrl) {
  public static final ScopedValue<PovClientActions> CURRENT = ScopedValue.newInstance();

  public static void copy(String text) {
    if (CURRENT.isBound()) CURRENT.get().clipboard.accept(text);
  }

  public static void open(URI uri) {
    if (CURRENT.isBound() && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
      && uri.getHost() != null && uri.toString().length() <= 16_384) {
      CURRENT.get().openUrl.accept(uri.toString());
    }
  }
}
