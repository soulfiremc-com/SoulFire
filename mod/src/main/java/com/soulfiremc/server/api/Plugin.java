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
package com.soulfiremc.server.api;

import lombok.Getter;

/// Base type for plugins that extend the Soulfire server.
@Getter
public abstract sealed class Plugin permits ExternalPlugin, InternalPlugin {
  private final PluginInfo pluginInfo;

  protected Plugin(PluginInfo pluginInfo) {
    this.pluginInfo = pluginInfo;
  }

  /// Whether this plugin can load in the current runtime.
  public boolean isAvailable() {
    return true;
  }

  /// Declares permissions, RPC services, SDK metadata, and other static
  /// contributions during the controlled registration phase.
  protected void onLoad(PluginContext context) {}

  /// Starts work that needs access to a fully initialized server.
  protected void onEnable(ServerContext context) {}

  /// Stops runtime work and releases resources owned by this plugin.
  protected void onDisable() {}
}
