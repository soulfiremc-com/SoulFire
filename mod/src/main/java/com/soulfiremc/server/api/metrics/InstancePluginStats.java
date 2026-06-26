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
package com.soulfiremc.server.api.metrics;

import com.soulfiremc.grpc.generated.PluginRuntimeStat;
import com.soulfiremc.server.api.PluginInfo;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Per-instance registry of plugin runtime stats.
///
/// Each instance owns one of these. Plugins look up their {@link PluginStatsHandle}
/// by {@link PluginInfo} and report counters / gauges and active bots on it. The
/// PluginStatsService reads {@link #buildStats} to expose the data over gRPC.
///
/// A handle is created lazily the first time a plugin reports, which also stamps
/// its running-since time. This mirrors how the rest of the server treats a
/// plugin as "active in this instance" once it does work here.
public final class InstancePluginStats {
  private final ConcurrentHashMap<String, PluginStatsHandle> handles = new ConcurrentHashMap<>();

  /// Returns the handle for the given plugin, creating it on first use.
  public PluginStatsHandle forPlugin(PluginInfo pluginInfo) {
    return forPlugin(pluginInfo.id());
  }

  /// Returns the handle for the given plugin id, creating it on first use.
  public PluginStatsHandle forPlugin(String pluginId) {
    return handles.computeIfAbsent(pluginId, id -> new PluginStatsHandle(id, Instant.now()));
  }

  /// Builds proto stats for every plugin that has reported, intersecting tracked
  /// bots with the supplied set of currently-online bot ids.
  public List<PluginRuntimeStat> buildStats(Set<UUID> onlineBotIds) {
    return handles.values().stream()
      .map(handle -> handle.toProto(onlineBotIds))
      .toList();
  }

  /// Drops all reported stats. Called when a session restarts so counters and
  /// running-since times reflect the new session.
  public void reset() {
    handles.clear();
  }
}
