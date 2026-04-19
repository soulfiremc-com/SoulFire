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
package com.soulfiremc.server.metrics;

import com.google.protobuf.Timestamp;
import com.soulfiremc.grpc.generated.BotPosition;
import com.soulfiremc.grpc.generated.MetricsDistributions;
import com.soulfiremc.grpc.generated.MetricsSnapshot;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.api.event.bot.*;
import com.soulfiremc.server.api.event.session.SessionBotRemoveEvent;
import com.soulfiremc.server.api.event.session.SessionStartEvent;
import com.soulfiremc.server.api.event.session.SessionTickEvent;
import com.soulfiremc.server.bot.BotConnection;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// Collects and stores per-instance metrics in a ring buffer.
 /// Metrics are sampled every 3 seconds (every 6th session tick at 500ms intervals).
 /// Thread-safe: counters use atomic operations, snapshot buffer is synchronized.
@Slf4j
public final class InstanceMetricsCollector {
  private static final int MAX_SNAPSHOTS = 600; // 30 min at 3s intervals
  private static final int TICKS_PER_SAMPLE = 6; // 6 * 500ms = 3s

  private final InstanceManager instanceManager;

  // Packet counters (thread-safe, updated from bot threads)
  private final LongAdder packetsSent = new LongAdder();
  private final LongAdder packetsReceived = new LongAdder();

  // Byte counters (estimated from packet events)
  private final LongAdder bytesSent = new LongAdder();
  private final LongAdder bytesReceived = new LongAdder();

  // Connection event counters (reset each sample)
  private final LongAdder connections = new LongAdder();
  private final LongAdder disconnections = new LongAdder();

  // Tick timing (updated from bot tick threads)
  private final LongAdder totalTickNanos = new LongAdder();
  private final LongAdder tickCount = new LongAdder();
  private final AtomicLong maxTickNanos = new AtomicLong(0);

  // Thread-local for tick start time
  private static final ThreadLocal<Long> tickStartNanos = new ThreadLocal<>();

  // Ring buffer for snapshots
  private final ArrayDeque<MetricsSnapshot> snapshots = new ArrayDeque<>();

  // Previous counter values for rate calculation
  private long prevPacketsSent;
  private long prevPacketsReceived;
  private long prevBytesSent;
  private long prevBytesReceived;
  private long prevSampleTimeNanos = System.nanoTime();

  // Tick counter for sampling interval
  private int tickCounter;

  public InstanceMetricsCollector(InstanceManager instanceManager) {
    this.instanceManager = instanceManager;
  }

  @EventHandler
  public void onPacketReceive(BotPacketPreReceiveEvent event) {
    if (!isOurInstance(event.connection())) {
      return;
    }

    if (event.packet() != null) {
      packetsReceived.increment();
    }
  }

  @EventHandler
  public void onPacketSend(BotPacketPreSendEvent event) {
    if (!isOurInstance(event.connection())) {
      return;
    }

    if (event.packet() != null) {
      packetsSent.increment();
    }
  }

  @EventHandler
  public void onPreTick(BotPreTickEvent event) {
    if (!isOurInstance(event.connection())) {
      return;
    }

    tickStartNanos.set(System.nanoTime());
  }

  @EventHandler
  public void onPostTick(BotPostTickEvent event) {
    if (!isOurInstance(event.connection())) {
      return;
    }

    var start = tickStartNanos.get();
    if (start != null) {
      var duration = System.nanoTime() - start;
      totalTickNanos.add(duration);
      tickCount.increment();
      maxTickNanos.accumulateAndGet(duration, Math::max);
    }
  }

  @EventHandler
  public void onSessionTick(SessionTickEvent event) {
    if (event.instanceManager() != instanceManager) {
      return;
    }

    tickCounter++;
    if (tickCounter < TICKS_PER_SAMPLE) {
      return;
    }
    tickCounter = 0;

    sampleSnapshot();
  }

  @EventHandler
  public void onSessionStart(SessionStartEvent event) {
    if (event.instanceManager() != instanceManager) {
      return;
    }

    resetCounters();
  }

  @EventHandler
  public void onBotConnectionInit(BotConnectionInitEvent event) {
    if (!isOurInstance(event.connection())) {
      return;
    }

    connections.increment();
  }

  @EventHandler
  public void onBotRemove(SessionBotRemoveEvent event) {
    if (event.instanceManager() != instanceManager) {
      return;
    }

    disconnections.increment();
  }

  private boolean isOurInstance(BotConnection connection) {
    return instanceManager.botConnections().containsKey(connection.accountProfileId());
  }

  private void resetCounters() {
    packetsSent.reset();
    packetsReceived.reset();
    bytesSent.reset();
    bytesReceived.reset();
    connections.reset();
    disconnections.reset();
    totalTickNanos.reset();
    tickCount.reset();
    maxTickNanos.set(0);
    prevPacketsSent = 0;
    prevPacketsReceived = 0;
    prevBytesSent = 0;
    prevBytesReceived = 0;
    prevSampleTimeNanos = System.nanoTime();
    tickCounter = 0;
    synchronized (snapshots) {
      snapshots.clear();
    }
  }

  private void sampleSnapshot() {
    var now = Instant.now();
    var nowNanos = System.nanoTime();
    var timeDeltaSeconds = (nowNanos - prevSampleTimeNanos) / 1_000_000_000.0;
    if (timeDeltaSeconds <= 0) {
      timeDeltaSeconds = 3.0; // fallback
    }

    // Read cumulative counters
    var currentPacketsSent = packetsSent.sum();
    var currentPacketsReceived = packetsReceived.sum();
    var currentBytesSent = bytesSent.sum();
    var currentBytesReceived = bytesReceived.sum();

    // Calculate rates
    var packetsSentRate = (currentPacketsSent - prevPacketsSent) / timeDeltaSeconds;
    var packetsReceivedRate = (currentPacketsReceived - prevPacketsReceived) / timeDeltaSeconds;
    var bytesSentRate = (currentBytesSent - prevBytesSent) / timeDeltaSeconds;
    var bytesReceivedRate = (currentBytesReceived - prevBytesReceived) / timeDeltaSeconds;

    // Tick duration stats
    var totalTicks = tickCount.sumThenReset();
    var totalNanos = totalTickNanos.sumThenReset();
    var maxNanos = maxTickNanos.getAndSet(0);
    var avgTickMs = totalTicks > 0 ? (totalNanos / (double) totalTicks) / 1_000_000.0 : 0.0;
    var maxTickMs = maxNanos / 1_000_000.0;

    // Connection events (read and reset)
    var connectCount = connections.sumThenReset();
    var disconnectCount = disconnections.sumThenReset();

    // Aggregate bot state
    var botConnections = instanceManager.botConnections();
    var ref = new Object() {
      int botsWithPlayerData;
      int onlineCount;
      double totalHealth = 0.0;
      double totalFood = 0.0;
      int totalChunks;
      int totalEntities;
    };

    for (var bot : botConnections.values()) {
      if (bot.isDisconnected()) {
        continue;
      }

      ref.onlineCount++;

      bot.runnableWrapper().runWrapped(() -> {
        var minecraft = bot.minecraft();
        var player = minecraft.player;
        var level = minecraft.level;
        if (player != null) {
          ref.botsWithPlayerData++;
          ref.totalHealth += player.getHealth();
          ref.totalFood += player.getFoodData().getFoodLevel();

          if (level != null) {
            ref.totalEntities += level.getEntityCount();
            var chunkSource = level.getChunkSource();
            ref.totalChunks += chunkSource.getLoadedChunksCount();
          }
        }
      });
    }

    var avgHealth = ref.botsWithPlayerData > 0 ? ref.totalHealth / ref.botsWithPlayerData : 0.0;
    var avgFood = ref.botsWithPlayerData > 0 ? ref.totalFood / ref.botsWithPlayerData : 0.0;
    var totalBots = instanceManager.settingsSource().accounts().size();

    var snapshot = MetricsSnapshot.newBuilder()
      .setTimestamp(Timestamp.newBuilder()
        .setSeconds(now.getEpochSecond())
        .setNanos(now.getNano())
        .build())
      .setBotsOnline(ref.onlineCount)
      .setBotsTotal(totalBots)
      .setPacketsSentTotal(currentPacketsSent)
      .setPacketsReceivedTotal(currentPacketsReceived)
      .setBytesSentTotal(currentBytesSent)
      .setBytesReceivedTotal(currentBytesReceived)
      .setPacketsSentPerSecond(packetsSentRate)
      .setPacketsReceivedPerSecond(packetsReceivedRate)
      .setBytesSentPerSecond(bytesSentRate)
      .setBytesReceivedPerSecond(bytesReceivedRate)
      .setAvgTickDurationMs(avgTickMs)
      .setMaxTickDurationMs(maxTickMs)
      .setAvgHealth(avgHealth)
      .setAvgFoodLevel(avgFood)
      .setTotalLoadedChunks(ref.totalChunks)
      .setTotalTrackedEntities(ref.totalEntities)
      .setConnections(connectCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) connectCount)
      .setDisconnections(disconnectCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) disconnectCount)
      .build();

    synchronized (snapshots) {
      snapshots.addLast(snapshot);
      while (snapshots.size() > MAX_SNAPSHOTS) {
        snapshots.removeFirst();
      }
    }

    // Update previous values for next rate calculation
    prevPacketsSent = currentPacketsSent;
    prevPacketsReceived = currentPacketsReceived;
    prevBytesSent = currentBytesSent;
    prevBytesReceived = currentBytesReceived;
    prevSampleTimeNanos = nowNanos;
  }

  public void addBytesSent(long bytes) {
    bytesSent.add(bytes);
  }

  public void addBytesReceived(long bytes) {
    bytesReceived.add(bytes);
  }

  /// Returns all stored snapshots, optionally filtered by a "since" timestamp.
  public List<MetricsSnapshot> getSnapshots(Timestamp since) {
    synchronized (snapshots) {
      if (since == null || (since.getSeconds() == 0 && since.getNanos() == 0)) {
        return List.copyOf(snapshots);
      }

      var result = new ArrayList<MetricsSnapshot>();
      for (var snapshot : snapshots) {
        var ts = snapshot.getTimestamp();
        if (ts.getSeconds() > since.getSeconds()
          || (ts.getSeconds() == since.getSeconds() && ts.getNanos() > since.getNanos())) {
          result.add(snapshot);
        }
      }
      return result;
    }
  }

  /// Builds current-state distributions from live bot data.
  public MetricsDistributions buildDistributions() {
    var builder = MetricsDistributions.newBuilder();

    // Initialize histograms with 10 buckets each
    var healthHist = new int[10];
    var foodHist = new int[10];
    var dimensionCounts = new HashMap<String, Integer>();
    var gameModeCounts = new HashMap<String, Integer>();

    var botConnections = instanceManager.botConnections();
    for (var bot : botConnections.values()) {
      if (bot.isDisconnected()) {
        continue;
      }

      var minecraft = bot.minecraft();
      var player = minecraft.player;
      if (player == null) {
        continue;
      }

      // Health histogram: buckets [0,2), [2,4), ..., [18,20]
      var health = player.getHealth();
      var healthBucket = Math.min((int) (health / 2), 9);
      healthHist[healthBucket]++;

      // Food histogram: same bucketing
      var food = player.getFoodData().getFoodLevel();
      var foodBucket = Math.min(food / 2, 9);
      foodHist[foodBucket]++;

      // Dimension counts
      var level = minecraft.level;
      if (level != null) {
        var dimension = level.dimension().identifier().toString();
        dimensionCounts.merge(dimension, 1, Integer::sum);
      }

      // Game mode counts
      var gameMode = minecraft.gameMode;
      if (gameMode != null) {
        var modeName = gameMode.getPlayerMode().name();
        gameModeCounts.merge(modeName, 1, Integer::sum);
      }

      // Bot position
      if (level != null) {
        var dimension = level.dimension().identifier().toString();
        builder.addBotPositions(BotPosition.newBuilder()
          .setX(player.getX())
          .setZ(player.getZ())
          .setDimension(dimension)
          .build());
      }
    }

    for (var count : healthHist) {
      builder.addHealthHistogram(count);
    }
    for (var count : foodHist) {
      builder.addFoodHistogram(count);
    }
    builder.putAllDimensionCounts(dimensionCounts);
    builder.putAllGameModeCounts(gameModeCounts);

    return builder.build();
  }
}
