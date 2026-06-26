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

import com.google.protobuf.Timestamp;
import com.soulfiremc.grpc.generated.PluginMetric;
import com.soulfiremc.grpc.generated.PluginRuntimeStat;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.DoubleAdder;

/// Runtime stats reported by a single plugin within one instance.
///
/// Plugins obtain a handle via {@link InstancePluginStats#forPlugin}, then
/// register named counters / gauges and track which bots they are acting on.
/// All operations are thread-safe; plugins typically report from bot threads.
@Getter
public final class PluginStatsHandle {
  private final String pluginId;
  private final Instant runningSince;
  private final Set<UUID> activeBots = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, Metric> metrics = new ConcurrentHashMap<>();
  // Preserves registration order for stable display.
  private final CopyOnWriteArrayList<String> metricOrder = new CopyOnWriteArrayList<>();

  PluginStatsHandle(String pluginId, Instant runningSince) {
    this.pluginId = pluginId;
    this.runningSince = runningSince;
  }

  /// Returns (creating on first use) an additive counter, e.g. "diamonds mined".
  public Counter counter(String key, String displayName, String unit, String icon) {
    return (Counter) metrics.computeIfAbsent(key, k -> {
      metricOrder.add(k);
      return new Counter(k, displayName, unit, icon);
    });
  }

  /// Returns (creating on first use) a settable gauge, e.g. a current speed.
  public Gauge gauge(String key, String displayName, String unit, String icon) {
    return (Gauge) metrics.computeIfAbsent(key, k -> {
      metricOrder.add(k);
      return new Gauge(k, displayName, unit, icon);
    });
  }

  /// Marks that this plugin is currently acting on the given bot.
  public void trackBot(UUID botId) {
    activeBots.add(botId);
  }

  /// Marks that this plugin is no longer acting on the given bot.
  public void untrackBot(UUID botId) {
    activeBots.remove(botId);
  }

  /// Builds the proto representation. The active bot count is intersected with
  /// the supplied online bot ids so stale bots do not inflate the number.
  PluginRuntimeStat toProto(Set<UUID> onlineBotIds) {
    var activeCount = 0;
    for (var botId : activeBots) {
      if (onlineBotIds.contains(botId)) {
        activeCount++;
      }
    }

    var builder = PluginRuntimeStat.newBuilder()
      .setPluginId(pluginId)
      .setEnabled(true)
      .setActiveBotCount(activeCount)
      .setRunningSince(Timestamp.newBuilder()
        .setSeconds(runningSince.getEpochSecond())
        .setNanos(runningSince.getNano())
        .build());

    for (var key : metricOrder) {
      var metric = metrics.get(key);
      if (metric != null) {
        builder.addMetrics(metric.toProto());
      }
    }

    return builder.build();
  }

  /// Base type for a named plugin metric. Carries display metadata and exposes
  /// the current value; subtypes decide how that value is mutated.
  public abstract static sealed class Metric permits Counter, Gauge {
    @Getter
    private final String key;
    @Getter
    private final String displayName;
    @Getter
    private final String unit;
    private final String icon;

    private Metric(String key, String displayName, String unit, String icon) {
      this.key = key;
      this.displayName = displayName;
      this.unit = unit;
      this.icon = icon;
    }

    public abstract double value();

    PluginMetric toProto() {
      var builder = PluginMetric.newBuilder()
        .setKey(key)
        .setDisplayName(displayName)
        .setValue(value())
        .setUnit(unit == null ? "" : unit);
      if (icon != null && !icon.isEmpty()) {
        builder.setIcon(icon);
      }
      return builder.build();
    }
  }

  /// An additive metric that only ever grows during a session.
  public static final class Counter extends Metric {
    private final DoubleAdder adder = new DoubleAdder();

    private Counter(String key, String displayName, String unit, String icon) {
      super(key, displayName, unit, icon);
    }

    public void increment() {
      adder.add(1.0);
    }

    public void add(double amount) {
      adder.add(amount);
    }

    @Override
    public double value() {
      return adder.sum();
    }
  }

  /// A metric whose absolute value is set by the plugin (e.g. a live rate).
  public static final class Gauge extends Metric {
    private volatile double current;

    private Gauge(String key, String displayName, String unit, String icon) {
      super(key, displayName, unit, icon);
    }

    public void set(double value) {
      this.current = value;
    }

    @Override
    public double value() {
      return current;
    }
  }

  /// Returns an immutable view of the currently registered metrics in order.
  public List<Metric> metricsInOrder() {
    return metricOrder.stream().map(metrics::get).filter(java.util.Objects::nonNull).toList();
  }
}
