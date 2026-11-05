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
package com.soulfiremc.server;

import com.google.protobuf.util.Timestamps;
import com.soulfiremc.grpc.generated.BotDesiredState;
import com.soulfiremc.grpc.generated.BotFleetSummary;
import com.soulfiremc.grpc.generated.BotRuntimeState;
import com.soulfiremc.grpc.generated.BotStatus;
import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotConnectionFactory;
import com.soulfiremc.server.database.AuditLogType;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.plugins.AutoReconnect;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.settings.instance.AccountSettings;
import com.soulfiremc.server.settings.instance.BotSettings;
import com.soulfiremc.server.settings.instance.ProxySettings;
import com.soulfiremc.server.settings.lib.BotSettingsDelegate;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.server.settings.lib.BotSettingsSource;
import com.soulfiremc.server.user.SoulFireUser;
import com.soulfiremc.server.util.structs.CachedLazyObject;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Persists per-bot connection intent and reconciles it with live connections.
@Slf4j
public final class BotStateManager {
  private final InstanceManager instanceManager;
  private final Object stateLock = new Object();
  // Keep durable intent changes ordered without blocking status reads on database I/O.
  private final Object intentLock = new Object();
  private final Set<UUID> desiredBotIds = new HashSet<>();
  private final Map<UUID, RuntimeEntry> runtimeEntries = new HashMap<>();
  private final Map<UUID, Long> generations = new HashMap<>();
  private final ArrayDeque<QueuedBot> connectQueue = new ArrayDeque<>();
  private final Set<UUID> queuedBotIds = new HashSet<>();
  private final Map<UUID, SFProxy> proxyLeases = new HashMap<>();
  private final CopyOnWriteArraySet<Consumer<StatusEvent>> statusListeners = new CopyOnWriteArraySet<>();
  private int activeStarts;
  private long nextStartAtMillis;
  private long generationSequence;
  private boolean shuttingDown;

  public BotStateManager(InstanceManager instanceManager) {
    this.instanceManager = instanceManager;
    loadDesiredBots();
    syncConfiguredAccounts();
  }

  public void restoreDesiredBots() {
    var accounts = instanceManager.settingsSource().accounts();
    var disabledRestores = new ArrayList<UUID>();
    synchronized (stateLock) {
      for (var botId : desiredBotIds) {
        var account = accounts.get(botId);
        if (account == null || !botSettings(account).get(BotSettings.RESTORE_ON_REBOOT)) {
          disabledRestores.add(botId);
        }
      }
    }

    if (!disabledRestores.isEmpty()) {
      markStopped(disabledRestores);
      synchronized (stateLock) {
        for (var botId : disabledRestores) {
          setRuntimeLocked(botId, BotRuntimeState.BOT_RUNTIME_STATE_STOPPED, null);
        }
      }
    }

    queueBots(desiredBotIdsSnapshot());
  }

  public List<BotStatus> setDesiredState(
    SoulFireUser initiator,
    Collection<UUID> botIds,
    BotDesiredState desiredState) {
    var normalizedBotIds = validateBotIds(botIds);
    switch (desiredState) {
      case BOT_DESIRED_STATE_RUNNING -> {
        markRunning(normalizedBotIds);
        queueBots(normalizedBotIds);
      }
      case BOT_DESIRED_STATE_STOPPED -> {
        markStopped(normalizedBotIds);
        stopRuntimeBots(normalizedBotIds);
      }
      case BOT_DESIRED_STATE_UNSPECIFIED, UNRECOGNIZED ->
        throw new IllegalArgumentException("desired_state must be RUNNING or STOPPED");
    }

    instanceManager.addAuditLog(
      initiator,
      AuditLogType.BOT_DESIRED_STATE_CHANGE,
      auditData(desiredState.name(), normalizedBotIds));
    return statuses(normalizedBotIds);
  }

  public List<BotStatus> restartBots(SoulFireUser initiator, Collection<UUID> botIds) {
    var normalizedBotIds = validateBotIds(botIds);
    markRunning(normalizedBotIds);

    var restartFutures = new ArrayList<CompletableFuture<?>>();
    for (var botId : normalizedBotIds) {
      restartFutures.add(restartBot(botId));
    }
    CompletableFuture.allOf(restartFutures.toArray(CompletableFuture[]::new))
      .thenRun(() -> queueBots(normalizedBotIds));

    instanceManager.addAuditLog(
      initiator,
      AuditLogType.BOT_RESTART,
      auditData(BotDesiredState.BOT_DESIRED_STATE_RUNNING.name(), normalizedBotIds));
    return statuses(normalizedBotIds);
  }

  private void scheduleReconnect(UUID botId, long expectedGeneration, long delaySeconds) {
    final long generation;
    synchronized (stateLock) {
      if (!isCurrentAndDesiredLocked(botId, expectedGeneration)
        || hasLiveConnection(botId)
        || queuedBotIds.contains(botId)) {
        return;
      }

      generation = nextGenerationLocked(botId);
      setRuntimeLocked(botId, BotRuntimeState.BOT_RUNTIME_STATE_RETRYING, lastErrorLocked(botId));
    }
    publishStatus(botId);

    instanceManager.scheduler().schedule(
      () -> {
        synchronized (stateLock) {
          if (!isCurrentAndDesiredLocked(botId, generation) || hasLiveConnection(botId)) {
            return;
          }
          enqueueLocked(botId, generation);
        }
        publishStatus(botId);
        drainQueue();
      },
      Math.max(0L, delaySeconds),
      TimeUnit.SECONDS);
  }

  public CompletableFuture<Void> removeAccounts(Collection<UUID> botIds) {
    var normalizedBotIds = List.copyOf(new LinkedHashSet<>(botIds));
    markStopped(normalizedBotIds);
    var stops = stopRuntimeBots(normalizedBotIds);
    return CompletableFuture.allOf(stops.stream().map(PendingStop::completion).toArray(CompletableFuture[]::new))
      .thenRun(() -> {
        var removedIds = new ArrayList<UUID>();
        synchronized (stateLock) {
          for (var stop : stops) {
            var botId = stop.botId();
            // A new start can be accepted while the old connection finishes disconnecting.
            if (generations.getOrDefault(botId, 0L) != stop.generation()) {
              continue;
            }
            runtimeEntries.remove(botId);
            generations.remove(botId);
            proxyLeases.remove(botId);
            instanceManager.botControlLeaseManager().clear(botId);
            removedIds.add(botId);
          }
        }
        removedIds.forEach(this::publishRemoved);
      });
  }

  public void syncConfiguredAccounts() {
    var configuredIds = Set.copyOf(instanceManager.settingsSource().accounts().keySet());
    var addedIds = new ArrayList<UUID>();
    var removedIds = new ArrayList<UUID>();
    var removedConnections = new ArrayList<BotConnection>();
    synchronized (stateLock) {
      for (var botId : configuredIds) {
        if (!runtimeEntries.containsKey(botId)) {
          runtimeEntries.put(
            botId,
            new RuntimeEntry(BotRuntimeState.BOT_RUNTIME_STATE_STOPPED, null, Instant.now()));
          addedIds.add(botId);
        }
      }
      var knownIds = new HashSet<>(runtimeEntries.keySet());
      knownIds.addAll(desiredBotIds);
      knownIds.addAll(generations.keySet());
      knownIds.addAll(instanceManager.botConnections().keySet());
      for (var botId : knownIds) {
        if (!configuredIds.contains(botId)) {
          runtimeEntries.remove(botId);
          generations.remove(botId);
          queuedBotIds.remove(botId);
          proxyLeases.remove(botId);
          instanceManager.botControlLeaseManager().clear(botId);
          connectQueue.removeIf(queued -> queued.botId().equals(botId));
          var connection = instanceManager.botConnections().get(botId);
          if (connection != null) removedConnections.add(connection);
          removedIds.add(botId);
        }
      }
    }
    if (!removedIds.isEmpty()) {
      markStopped(removedIds);
      var disconnected = removedConnections.stream()
        .map(bot -> bot.disconnect(Component.text("Bot account was removed"))
          .thenRun(() -> instanceManager.removeBot(bot)))
        .toArray(CompletableFuture[]::new);
      CompletableFuture.allOf(disconnected).join();
      removedIds.forEach(this::publishRemoved);
    }
    addedIds.forEach(this::publishStatus);
    drainQueue();
  }

  public boolean removeConnection(BotConnection bot) {
    var botId = bot.accountProfileId();
    var shouldReconnect = false;
    final long disconnectedGeneration;
    synchronized (stateLock) {
      if (!instanceManager.botConnections().remove(botId, bot)) {
        return false;
      }
      disconnectedGeneration = generations.getOrDefault(botId, 0L);
      proxyLeases.remove(botId);
      if (!instanceManager.settingsSource().accounts().containsKey(botId)) {
        runtimeEntries.remove(botId);
      } else if (shuttingDown || !desiredBotIds.contains(botId)) {
        setRuntimeLocked(botId, BotRuntimeState.BOT_RUNTIME_STATE_STOPPED, null);
      } else if (!queuedBotIds.contains(botId)) {
        var reason = bot.disconnectReason();
        setRuntimeLocked(botId, BotRuntimeState.BOT_RUNTIME_STATE_FAILED, reason);
        shouldReconnect = true;
      }
    }
    publishStatus(botId);
    drainQueue();

    if (shouldReconnect) {
      AutoReconnect.reconnectDelaySeconds(bot.settingsSource())
        .ifPresent(delay -> scheduleReconnect(botId, disconnectedGeneration, delay));
    }
    return true;
  }

  public BotStatus status(UUID botId) {
    synchronized (stateLock) {
      var entry = runtimeEntries.getOrDefault(
        botId,
        new RuntimeEntry(BotRuntimeState.BOT_RUNTIME_STATE_STOPPED, null, Instant.EPOCH));
      var builder = BotStatus.newBuilder()
        .setProfileId(botId.toString())
        .setDesiredState(desiredBotIds.contains(botId)
          ? BotDesiredState.BOT_DESIRED_STATE_RUNNING
          : BotDesiredState.BOT_DESIRED_STATE_STOPPED)
        .setRuntimeState(entry.runtimeState())
        .setUpdatedAt(Timestamps.fromMillis(entry.updatedAt().toEpochMilli()));
      if (entry.lastError() != null && !entry.lastError().isBlank()) {
        builder.setLastError(entry.lastError());
      }
      return builder.build();
    }
  }

  public List<BotStatus> statuses() {
    return statuses(instanceManager.settingsSource().accounts().keySet());
  }

  public BotFleetSummary summary() {
    synchronized (stateLock) {
      var totalBots = instanceManager.settingsSource().accounts().size();
      var desiredBots = 0;
      var startingBots = 0;
      var retryingBots = 0;
      var failedBots = 0;
      for (var botId : instanceManager.settingsSource().accounts().keySet()) {
        if (desiredBotIds.contains(botId)) {
          desiredBots++;
        }
        var entry = runtimeEntries.get(botId);
        if (entry == null) {
          continue;
        }
        switch (entry.runtimeState()) {
          case BOT_RUNTIME_STATE_QUEUED, BOT_RUNTIME_STATE_STARTING -> startingBots++;
          case BOT_RUNTIME_STATE_RETRYING -> retryingBots++;
          case BOT_RUNTIME_STATE_FAILED -> failedBots++;
          default -> {
          }
        }
      }

      var onlineBots = (int) instanceManager.botConnections().values().stream()
        .filter(bot -> !bot.isDisconnected() && !bot.isStatusPing())
        .count();
      return BotFleetSummary.newBuilder()
        .setTotalBots(totalBots)
        .setDesiredBots(desiredBots)
        .setOnlineBots(onlineBots)
        .setStartingBots(startingBots)
        .setRetryingBots(retryingBots)
        .setFailedBots(failedBots)
        .build();
    }
  }

  public Set<UUID> desiredBotIdsSnapshot() {
    synchronized (stateLock) {
      return Set.copyOf(desiredBotIds);
    }
  }

  public Runnable addStatusListener(Consumer<StatusEvent> listener) {
    statusListeners.add(listener);
    return () -> statusListeners.remove(listener);
  }

  public StatusSubscription subscribe(Consumer<StatusEvent> listener) {
    synchronized (stateLock) {
      statusListeners.add(listener);
      return new StatusSubscription(
        statuses(instanceManager.settingsSource().accounts().keySet()),
        () -> statusListeners.remove(listener));
    }
  }

  public CompletableFuture<Void> shutdown() {
    List<BotConnection> bots;
    synchronized (stateLock) {
      shuttingDown = true;
      connectQueue.clear();
      queuedBotIds.clear();
      proxyLeases.clear();
      for (var botId : runtimeEntries.keySet()) {
        nextGenerationLocked(botId);
      }
      bots = List.copyOf(instanceManager.botConnections().values());
    }

    var futures = bots.stream()
      .map(bot -> bot.disconnect(Component.text("Soulfire is shutting down"))
        .thenRun(() -> instanceManager.removeBot(bot)))
      .toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures);
  }

  private void loadDesiredBots() {
    var records = instanceManager.dsl()
      .select(Tables.INSTANCE_DESIRED_BOTS.PROFILE_ID)
      .from(Tables.INSTANCE_DESIRED_BOTS)
      .where(Tables.INSTANCE_DESIRED_BOTS.INSTANCE_ID.eq(instanceManager.id().toString()))
      .fetch(Tables.INSTANCE_DESIRED_BOTS.PROFILE_ID);
    for (var profileId : records) {
      try {
        desiredBotIds.add(UUID.fromString(profileId));
      } catch (IllegalArgumentException e) {
        log.warn("Ignoring invalid desired bot id {} for instance {}", profileId, instanceManager.id(), e);
      }
    }
  }

  private List<UUID> validateBotIds(Collection<UUID> botIds) {
    var normalized = List.copyOf(new LinkedHashSet<>(botIds));
    var accounts = instanceManager.settingsSource().accounts();
    for (var botId : normalized) {
      if (!accounts.containsKey(botId)) {
        throw new IllegalArgumentException(
          "Bot '%s' is not configured in instance '%s'".formatted(botId, instanceManager.id()));
      }
    }
    return normalized;
  }

  private void markRunning(Collection<UUID> botIds) {
    if (botIds.isEmpty()) {
      return;
    }
    synchronized (intentLock) {
      // An account can be removed while this command waits behind another intent.
      validateBotIds(botIds);
      var now = LocalDateTime.now(ZoneOffset.UTC);
      instanceManager.dsl().transaction(cfg -> {
        var ctx = DSL.using(cfg);
        for (var botId : botIds) {
          ctx.insertInto(Tables.INSTANCE_DESIRED_BOTS)
            .set(Tables.INSTANCE_DESIRED_BOTS.INSTANCE_ID, instanceManager.id().toString())
            .set(Tables.INSTANCE_DESIRED_BOTS.PROFILE_ID, botId.toString())
            .set(Tables.INSTANCE_DESIRED_BOTS.REQUESTED_AT, now)
            .onDuplicateKeyIgnore()
            .execute();
        }
        touchInstance(ctx, now);
      });
      synchronized (stateLock) {
        desiredBotIds.addAll(botIds);
      }
    }
  }

  private void markStopped(Collection<UUID> botIds) {
    if (botIds.isEmpty()) {
      return;
    }
    var ids = botIds.stream().map(UUID::toString).toList();
    synchronized (intentLock) {
      var now = LocalDateTime.now(ZoneOffset.UTC);
      instanceManager.dsl().transaction(cfg -> {
        var ctx = DSL.using(cfg);
        ctx.deleteFrom(Tables.INSTANCE_DESIRED_BOTS)
          .where(Tables.INSTANCE_DESIRED_BOTS.INSTANCE_ID.eq(instanceManager.id().toString()))
          .and(Tables.INSTANCE_DESIRED_BOTS.PROFILE_ID.in(ids))
          .execute();
        touchInstance(ctx, now);
      });
      synchronized (stateLock) {
        desiredBotIds.removeAll(botIds);
      }
    }
  }

  private void touchInstance(org.jooq.DSLContext ctx, LocalDateTime now) {
    ctx.update(Tables.INSTANCES)
      .set(Tables.INSTANCES.UPDATED_AT, now)
      .where(Tables.INSTANCES.ID.eq(instanceManager.id().toString()))
      .execute();
  }

  private void queueBots(Collection<UUID> botIds) {
    var orderedIds = instanceManager.settingsSource().accounts().values().stream()
      .map(MinecraftAccount::profileId)
      .filter(botIds::contains)
      .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (instanceManager.settingsSource().get(AccountSettings.SHUFFLE_ACCOUNTS)) {
      Collections.shuffle(orderedIds);
    }

    var changedIds = new ArrayList<UUID>();
    synchronized (stateLock) {
      for (var botId : orderedIds) {
        if (shuttingDown
          || !desiredBotIds.contains(botId)
          || queuedBotIds.contains(botId)
          || isStartingLocked(botId)) {
          continue;
        }
        var connection = instanceManager.botConnections().get(botId);
        if (connection != null && !connection.isDisconnected() && !connection.isDisconnecting() && !isStoppingLocked(botId)) {
          continue;
        }
        enqueueLocked(botId, nextGenerationLocked(botId));
        changedIds.add(botId);
      }
    }
    changedIds.forEach(this::publishStatus);
    drainQueue();
  }

  private void enqueueLocked(UUID botId, long generation) {
    connectQueue.addLast(new QueuedBot(botId, generation));
    queuedBotIds.add(botId);
    setRuntimeLocked(botId, BotRuntimeState.BOT_RUNTIME_STATE_QUEUED, null);
  }

  private void drainQueue() {
    var launches = new ArrayList<Launch>();
    synchronized (stateLock) {
      if (shuttingDown || connectQueue.isEmpty()) {
        return;
      }

      var maxConcurrent = Math.max(1, instanceManager.settingsSource().get(BotSettings.CONCURRENT_CONNECTS));
      var attempts = connectQueue.size();
      while (activeStarts < maxConcurrent && !connectQueue.isEmpty() && attempts-- > 0) {
        var queued = connectQueue.removeFirst();
        queuedBotIds.remove(queued.botId());
        if (!isCurrentAndDesiredLocked(queued.botId(), queued.generation())) {
          continue;
        }
        if (instanceManager.botConnections().containsKey(queued.botId())) {
          // The retiring connection must leave the registry before a replacement can be stored.
          connectQueue.addLast(queued);
          queuedBotIds.add(queued.botId());
          continue;
        }

        var account = instanceManager.settingsSource().accounts().get(queued.botId());
        if (account == null) {
          desiredBotIds.remove(queued.botId());
          setRuntimeLocked(queued.botId(), BotRuntimeState.BOT_RUNTIME_STATE_STOPPED, null);
          continue;
        }

        var proxySelection = selectProxyLocked(account);
        if (!proxySelection.available()) {
          connectQueue.addLast(queued);
          queuedBotIds.add(queued.botId());
          continue;
        }

        if (proxySelection.proxy() != null) {
          proxyLeases.put(queued.botId(), proxySelection.proxy());
        }
        activeStarts++;
        setRuntimeLocked(queued.botId(), BotRuntimeState.BOT_RUNTIME_STATE_STARTING, null);

        var now = System.currentTimeMillis();
        var scheduledAt = Math.max(now, nextStartAtMillis);
        var delay = Math.max(0L, scheduledAt - now);
        nextStartAtMillis = scheduledAt
          + instanceManager.settingsSource().getRandom(BotSettings.JOIN_DELAY).getAsLong();
        launches.add(new Launch(queued, proxySelection.proxy(), delay));
      }
    }

    for (var launch : launches) {
      publishStatus(launch.queuedBot().botId());
      instanceManager.scheduler().schedule(
        () -> launchBot(launch.queuedBot(), launch.proxy()),
        launch.delayMillis(),
        TimeUnit.MILLISECONDS);
    }
  }

  private ProxySelection selectProxyLocked(MinecraftAccount account) {
    var configuredProxies = instanceManager.settingsSource().proxies();
    if (configuredProxies.isEmpty()) {
      return new ProxySelection(true, null);
    }

    var botsPerProxy = instanceManager.settingsSource().get(ProxySettings.BOTS_PER_PROXY);
    var allocations = configuredProxies.stream()
      .map(proxy -> new StickyProxyAllocator.ProxyAllocation(proxy, botsPerProxy))
      .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    for (var leasedProxy : proxyLeases.values()) {
      allocations.stream()
        .filter(allocation -> StickyProxyAllocator.proxyFingerprint(allocation.proxy())
          .equals(StickyProxyAllocator.proxyFingerprint(leasedProxy)))
        .findFirst()
        .ifPresent(StickyProxyAllocator.ProxyAllocation::reserve);
    }
    if (instanceManager.settingsSource().get(ProxySettings.SHUFFLE_PROXIES)) {
      Collections.shuffle(allocations);
    }

    try {
      var assignment = StickyProxyAllocator.assign(
        List.of(account),
        allocations,
        instanceManager.settingsSource().get(ProxySettings.STICKY_PROXIES))
        .getFirst();
      return new ProxySelection(true, assignment.proxy());
    } catch (IllegalStateException _) {
      return new ProxySelection(false, null);
    }
  }

  private void launchBot(QueuedBot queued, @Nullable SFProxy proxy) {
    var botId = queued.botId();
    try {
      if (!isCurrentAndDesired(botId, queued.generation())) {
        return;
      }

      var account = instanceManager.settingsSource().accounts().get(botId);
      if (account == null) {
        return;
      }
      account = instanceManager.refreshAccount(account);
      account = persistStickyProxy(account, proxy);

      if (!isCurrentAndDesired(botId, queued.generation())) {
        return;
      }

      var factory = createFactory(account, proxy);
      var connection = factory.prepareConnection(false);
      Optional<CompletableFuture<?>> connecting;
      synchronized (stateLock) {
        // Schedule only while this launch still owns the current generation.
        connecting = isCurrentAndDesiredLocked(botId, queued.generation()) && instanceManager.storeNewBot(connection)
          ? Optional.of(connection.connect()) : Optional.empty();
      }
      if (connecting.isEmpty()) {
        connection.disconnect(Component.text("Bot start was cancelled or a connection already exists"));
        return;
      }
      connecting.orElseThrow().join();

      synchronized (stateLock) {
        if (isCurrentAndDesiredLocked(botId, queued.generation()) && !connection.isDisconnected()) {
          setRuntimeLocked(botId, BotRuntimeState.BOT_RUNTIME_STATE_RUNNING, null);
        }
      }
      publishStatus(botId);
    } catch (Throwable throwable) {
      var message = conciseMessage(throwable);
      synchronized (stateLock) {
        if (!isCurrentAndDesiredLocked(botId, queued.generation())) {
          log.debug("Ignoring failure from a retired start for bot {}", botId, throwable);
          return;
        }
        proxyLeases.remove(botId);
        setRuntimeLocked(botId, BotRuntimeState.BOT_RUNTIME_STATE_FAILED, message);
      }
      log.error("Failed to start bot {}", botId, throwable);
      publishStatus(botId);

      var account = instanceManager.settingsSource().accounts().get(botId);
      if (account != null) {
        AutoReconnect.reconnectDelaySeconds(botSettings(account))
          .ifPresent(delay -> scheduleReconnect(botId, queued.generation(), delay));
      }
    } finally {
      synchronized (stateLock) {
        activeStarts = Math.max(0, activeStarts - 1);
        if (generations.getOrDefault(botId, 0L) == queued.generation() && !hasLiveConnection(botId)) {
          proxyLeases.remove(botId);
        }
      }
      drainQueue();
    }
  }

  private MinecraftAccount persistStickyProxy(MinecraftAccount account, @Nullable SFProxy proxy) {
    if (proxy == null || !instanceManager.settingsSource().get(ProxySettings.STICKY_PROXIES)) {
      return account;
    }
    var metadata = StickyProxyAllocator.withSelectedProxy(account.persistentMetadata(), proxy);
    if (metadata.equals(account.persistentMetadata())) {
      return account;
    }
    instanceManager.persistAccountMetadataUpdates(Map.of(account.profileId(), metadata));
    return account.withPersistentMetadata(metadata);
  }

  private BotConnectionFactory createFactory(MinecraftAccount account, @Nullable SFProxy proxy) {
    var lastAccountObject = new AtomicReference<>(account);
    var botSettings = new BotSettingsDelegate(new CachedLazyObject<>(() -> {
      var instanceSettings = instanceManager.settingsSource();
      var fetchedAccount = instanceSettings.accounts().get(account.profileId());
      if (fetchedAccount == null) {
        fetchedAccount = lastAccountObject.get();
      } else {
        lastAccountObject.set(fetchedAccount);
      }
      return new BotSettingsImpl(fetchedAccount, instanceSettings);
    }, 1, TimeUnit.SECONDS));
    var protocolVersion = botSettings.get(BotSettings.PROTOCOL_VERSION, BotSettings.PROTOCOL_VERSION_PARSER);
    var serverAddress = BotConnectionFactory.parseAddress(
      instanceManager.settingsSource().get(BotSettings.ADDRESS),
      protocolVersion);
    return new BotConnectionFactory(instanceManager, botSettings, protocolVersion, serverAddress, proxy);
  }

  private CompletableFuture<?> restartBot(UUID botId) {
    BotConnection connection;
    synchronized (stateLock) {
      nextGenerationLocked(botId);
      queuedBotIds.remove(botId);
      connectQueue.removeIf(queued -> queued.botId().equals(botId));
      connection = instanceManager.botConnections().get(botId);
      if (connection == null) proxyLeases.remove(botId);
      setRuntimeLocked(
        botId,
        connection == null
          ? BotRuntimeState.BOT_RUNTIME_STATE_STOPPED
          : BotRuntimeState.BOT_RUNTIME_STATE_STOPPING,
        null);
    }
    publishStatus(botId);
    if (connection == null) {
      return CompletableFuture.completedFuture(null);
    }
    return connection.disconnect(Component.text("Bot restart requested"))
      .thenRun(() -> instanceManager.removeBot(connection));
  }

  private List<PendingStop> stopRuntimeBots(Collection<UUID> botIds) {
    var stops = new ArrayList<PendingStop>();
    for (var botId : botIds) {
      BotConnection connection;
      final long generation;
      synchronized (stateLock) {
        generation = nextGenerationLocked(botId);
        queuedBotIds.remove(botId);
        connectQueue.removeIf(queued -> queued.botId().equals(botId));
        connection = instanceManager.botConnections().get(botId);
        setRuntimeLocked(
          botId,
          connection == null
            ? BotRuntimeState.BOT_RUNTIME_STATE_STOPPED
            : BotRuntimeState.BOT_RUNTIME_STATE_STOPPING,
          null);
        if (connection == null) {
          proxyLeases.remove(botId);
        }
      }
      publishStatus(botId);
      var completion = connection == null ? CompletableFuture.completedFuture(null)
        : connection.disconnect(Component.text("Bot stopped"))
          .thenRun(() -> instanceManager.removeBot(connection));
      stops.add(new PendingStop(botId, generation, completion));
    }
    return stops;
  }

  private List<BotStatus> statuses(Collection<UUID> botIds) {
    return botIds.stream().map(this::status).toList();
  }

  private void setRuntimeLocked(UUID botId, BotRuntimeState state, @Nullable String lastError) {
    runtimeEntries.put(botId, new RuntimeEntry(state, lastError, Instant.now()));
  }

  private @Nullable String lastErrorLocked(UUID botId) {
    var entry = runtimeEntries.get(botId);
    return entry == null ? null : entry.lastError();
  }

  private boolean isStartingLocked(UUID botId) {
    var entry = runtimeEntries.get(botId);
    return entry != null && entry.runtimeState() == BotRuntimeState.BOT_RUNTIME_STATE_STARTING;
  }

  private boolean isStoppingLocked(UUID botId) {
    var entry = runtimeEntries.get(botId);
    return entry != null && entry.runtimeState() == BotRuntimeState.BOT_RUNTIME_STATE_STOPPING;
  }

  private long nextGenerationLocked(UUID botId) {
    // Removed accounts may be re-added while their old launches are still retiring.
    var generation = ++generationSequence;
    generations.put(botId, generation);
    return generation;
  }

  private boolean isCurrentAndDesired(UUID botId, long generation) {
    synchronized (stateLock) {
      return isCurrentAndDesiredLocked(botId, generation);
    }
  }

  private boolean isCurrentAndDesiredLocked(UUID botId, long generation) {
    return !shuttingDown
      && desiredBotIds.contains(botId)
      && instanceManager.settingsSource().accounts().containsKey(botId)
      && generations.getOrDefault(botId, 0L) == generation;
  }

  private boolean hasLiveConnection(UUID botId) {
    var connection = instanceManager.botConnections().get(botId);
    return connection != null && !connection.isDisconnected();
  }

  private BotSettingsSource botSettings(MinecraftAccount account) {
    return new BotSettingsImpl(account, instanceManager.settingsSource());
  }

  private void publishStatus(UUID botId) {
    if (!instanceManager.settingsSource().accounts().containsKey(botId)) {
      return;
    }
    var event = StatusEvent.updated(status(botId));
    statusListeners.forEach(listener -> notifyListener(listener, event));
  }

  private void publishRemoved(UUID botId) {
    var event = StatusEvent.removed(botId);
    statusListeners.forEach(listener -> notifyListener(listener, event));
  }

  private void notifyListener(Consumer<StatusEvent> listener, StatusEvent event) {
    try {
      listener.accept(event);
    } catch (Throwable throwable) {
      log.debug("Bot status listener failed", throwable);
    }
  }

  private static String auditData(String state, List<UUID> botIds) {
    var data = new LinkedHashMap<String, Object>();
    data.put("state", state);
    data.put("count", botIds.size());
    data.put("botIds", botIds.stream().limit(80).map(UUID::toString).toList());
    if (botIds.size() > 80) {
      data.put("truncated", true);
    }
    return com.soulfiremc.server.util.structs.GsonInstance.GSON.toJson(data);
  }

  private static String conciseMessage(Throwable throwable) {
    var current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    var message = current.getMessage();
    return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
  }

  public record StatusEvent(@Nullable BotStatus status, @Nullable UUID removedBotId) {
    public static StatusEvent updated(BotStatus status) {
      return new StatusEvent(status, null);
    }

    public static StatusEvent removed(UUID botId) {
      return new StatusEvent(null, botId);
    }
  }

  public record StatusSubscription(List<BotStatus> snapshot, Runnable close) {
  }

  private record RuntimeEntry(
    BotRuntimeState runtimeState,
    @Nullable String lastError,
    Instant updatedAt) {
  }

  private record QueuedBot(UUID botId, long generation) {
  }

  private record PendingStop(UUID botId, long generation, CompletableFuture<?> completion) {
  }

  private record Launch(QueuedBot queuedBot, @Nullable SFProxy proxy, long delayMillis) {
  }

  private record ProxySelection(boolean available, @Nullable SFProxy proxy) {
  }
}
