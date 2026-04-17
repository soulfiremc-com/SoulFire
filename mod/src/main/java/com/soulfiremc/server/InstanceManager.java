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

import com.google.gson.JsonElement;
import com.soulfiremc.mod.util.SFConstants;
import com.soulfiremc.server.account.MCAuthService;
import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.api.SessionLifecycle;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.api.event.session.SessionBotRemoveEvent;
import com.soulfiremc.server.api.event.session.SessionEndedEvent;
import com.soulfiremc.server.api.event.session.SessionStartEvent;
import com.soulfiremc.server.api.event.session.SessionTickEvent;
import com.soulfiremc.server.api.metadata.MetadataHolder;
import com.soulfiremc.server.automation.AutomationTeamCoordinator;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotConnectionFactory;
import com.soulfiremc.server.database.AuditLogType;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.metrics.InstanceMetricsCollector;
import com.soulfiremc.server.settings.instance.*;
import com.soulfiremc.server.settings.lib.*;
import com.soulfiremc.server.settings.property.Property;
import com.soulfiremc.server.user.SoulFireUser;
import com.soulfiremc.server.util.MathHelper;
import com.soulfiremc.server.util.SFHelpers;
import com.soulfiremc.server.util.TimeUtil;
import com.soulfiremc.server.util.structs.CachedLazyObject;
import com.soulfiremc.server.util.structs.GsonInstance;
import com.soulfiremc.shared.SFLogAppender;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Represents a single instance.
/// An instance persists settings over restarts and manages bot sessions and session state.
@Slf4j
@Getter
public final class InstanceManager {
  private static final ThreadLocal<InstanceManager> CURRENT = new ThreadLocal<>();
  private final Map<UUID, BotConnection> botConnections = new ConcurrentHashMap<>();
  private final MetadataHolder<Object> metadata = new MetadataHolder<>();
  private final MetadataHolder<JsonElement> persistentMetadata = new MetadataHolder<>();
  private final UUID id;
  private final SoulFireScheduler scheduler;
  private final InstanceSettingsDelegate settingsSource;
  private final SoulFireServer soulFireServer;
  private final DSLContext dsl;
  private final SoulFireScheduler.RunnableWrapper runnableWrapper;
  private final CachedLazyObject<String> friendlyNameCache;
  private final SettingsPageRegistry instanceSettingsPageRegistry;
  private final InstanceMetricsCollector metricsCollector;
  private final AutomationTeamCoordinator automationCoordinator;
  private final AtomicBoolean allBotsConnected = new AtomicBoolean(false);
  private SessionLifecycle sessionLifecycle = SessionLifecycle.STOPPED;

  public static InstanceManager current() {
    var current = CURRENT.get();
    if (!SFConstants.NOT_REGISTRY_INIT_PHASE) {
      return current;
    }

    if (current == null) {
      new RuntimeException().printStackTrace();
    }
    return Objects.requireNonNull(current, "No instance manager in current thread");
  }

  public static Optional<InstanceManager> currentOptional() {
    return Optional.ofNullable(CURRENT.get());
  }

  public InstanceManager(SoulFireServer soulFireServer, DSLContext dsl, UUID id, SessionLifecycle lastState) {
    this.id = id;
    this.runnableWrapper = soulFireServer.runnableWrapper().with(new InstanceRunnableWrapper(this));
    this.scheduler = new SoulFireScheduler(runnableWrapper);
    this.soulFireServer = soulFireServer;
    this.dsl = dsl;
    this.settingsSource = new InstanceSettingsDelegate(new CachedLazyObject<>(this::fetchSettingsSource, 1, TimeUnit.SECONDS));
    this.friendlyNameCache = new CachedLazyObject<>(this::fetchFriendlyName, 1, TimeUnit.SECONDS);
    initPersistentMetadata();

    this.metricsCollector = new InstanceMetricsCollector(this);
    SoulFireAPI.registerListenersOfObject(metricsCollector);
    this.automationCoordinator = new AutomationTeamCoordinator(this);

    try {
      Files.createDirectories(getInstanceObjectStoragePath());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    this.instanceSettingsPageRegistry = scheduler.supplyAsync(() -> {
      var registry = new SettingsPageRegistry()
        // Needs Via loaded to have all protocol versions
        .addInternalPage(BotSettings.class, "bot", "Bot Settings", "bot")
        .addInternalPage(AccountSettings.class, "account", "Account Settings", "users")
        .addInternalPage(ProxySettings.class, "proxy", "Proxy Settings", "waypoints")
        .addInternalPage(AISettings.class, "ai", "AI Settings", "sparkles")
        .addInternalPage(AutomationSettings.class, "automation", "Automation Settings", "bot-message-square")
        .addInternalPage(PathfindingSettings.class, "pathfinding", "Pathfinding Settings", "route");

      SoulFireAPI.postEvent(new InstanceSettingsRegistryInitEvent(this, registry));

      return registry;
    }).join();

    this.scheduler.scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
    this.scheduler.scheduleWithFixedDelay(this::refreshExpiredAccounts, 0, 1, TimeUnit.HOURS);

    // Resync stopped state to DB
    this.sessionLifecycle(SessionLifecycle.STOPPED);

    if (settingsSource.get(BotSettings.RESTORE_ON_REBOOT)) {
      switchToState(null, lastState);
    }
  }

  private void initPersistentMetadata() {
    var record = dsl.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();
    if (record != null) {
      var stem = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
      persistentMetadata.resetFrom(stem.persistentMetadata());
    }
  }

  private InstanceSettingsSource fetchSettingsSource() {
    var record = dsl.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();

    if (record == null) {
      throw new IllegalStateException("Instance not found");
    } else {
      var stem = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
      return new InstanceSettingsImpl(stem, soulFireServer.settingsSource());
    }
  }

  public void invalidateSettingsCache() {
    settingsSource.invalidate();
    friendlyNameCache.invalidate();
    for (var bot : botConnections.values()) {
      bot.invalidateSettingsCache();
    }
  }

  public void updateInstanceSetting(Property<SettingsSource.Instance> property, JsonElement value) {
    dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();
      if (record == null) {
        throw new IllegalStateException("Instance not found");
      }

      var currentSettings = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
      var updatedSettings = SettingsSource.Stem.withUpdatedEntry(
        currentSettings.settings(),
        property.namespace(),
        property.key(),
        value);
      var updatedStem = currentSettings.withSettings(updatedSettings);

      ctx.update(Tables.INSTANCES)
        .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(updatedStem.serializeToTree()))
        .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
        .where(Tables.INSTANCES.ID.eq(id.toString()))
        .execute();
    });

    invalidateSettingsCache();
  }

  public void updateBotSetting(UUID botId, Property<SettingsSource.Bot> property, JsonElement value) {
    dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();
      if (record == null) {
        throw new IllegalStateException("Instance not found");
      }

      var currentSettings = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
      var updatedStem = currentSettings.withAccounts(currentSettings.accounts().stream()
        .map(account -> {
          if (!account.profileId().equals(botId)) {
            return account;
          }

          var currentBotSettings = account.settings() == null ? Map.<String, Map<String, JsonElement>>of() : account.settings();
          var updatedBotSettings = SettingsSource.Stem.withUpdatedEntry(
            currentBotSettings,
            property.namespace(),
            property.key(),
            value);
          return account.withSettings(updatedBotSettings);
        })
        .toList());

      ctx.update(Tables.INSTANCES)
        .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(updatedStem.serializeToTree()))
        .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
        .where(Tables.INSTANCES.ID.eq(id.toString()))
        .execute();
    });

    invalidateSettingsCache();
  }

  private String fetchFriendlyName() {
    var record = dsl.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();

    if (record == null) {
      return "Unknown";
    } else {
      return record.getFriendlyName();
    }
  }

  private void tick() {
    automationCoordinator.tick();

    if (sessionLifecycle().isTicking()) {
      SoulFireAPI.postEvent(new SessionTickEvent(this));
    }

    persistDirtyMetadata();
    evictBots();
  }

  private void persistDirtyMetadata() {
    var dirtyBots = new ArrayList<Map.Entry<UUID, Map<String, Map<String, JsonElement>>>>();
    for (var entry : botConnections.entrySet()) {
      var bot = entry.getValue();
      bot.persistentMetadata().exportIfDirty().ifPresent(metadata -> {
        dirtyBots.add(Map.entry(entry.getKey(), metadata));
        bot.persistentMetadata().markClean();
      });
    }

    var dirtyInstanceMetadata = persistentMetadata.exportIfDirty();
    dirtyInstanceMetadata.ifPresent(_ -> persistentMetadata.markClean());

    if (dirtyBots.isEmpty() && dirtyInstanceMetadata.isEmpty()) {
      return;
    }

    dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();
      if (record == null) {
        return;
      }

      var currentSettings = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));

      if (!dirtyBots.isEmpty()) {
        var newAccounts = currentSettings.accounts().stream()
          .map(account -> {
            for (var dirtyEntry : dirtyBots) {
              if (account.profileId().equals(dirtyEntry.getKey())) {
                return account.withPersistentMetadata(dirtyEntry.getValue());
              }
            }
            return account;
          })
          .toList();
        currentSettings = currentSettings.withAccounts(newAccounts);
      }

      if (dirtyInstanceMetadata.isPresent()) {
        currentSettings = currentSettings.withPersistentMetadata(dirtyInstanceMetadata.get());
      }

      ctx.update(Tables.INSTANCES)
        .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(currentSettings.serializeToTree()))
        .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
        .where(Tables.INSTANCES.ID.eq(id.toString()))
        .execute();
    });
  }

  private void evictBots() {
    // Remove botConnections from the map that are closed
    botConnections.entrySet().removeIf(entry -> {
      var bot = entry.getValue();
      if (bot.isDisconnected()) {
        log.debug("Removing bot {}", bot.accountName());
        SoulFireAPI.postEvent(new SessionBotRemoveEvent(this, bot));
        return true;
      }
      return false;
    });
  }

  private void refreshExpiredAccounts() {
    if (settingsSource.accounts().isEmpty()) {
      // Nothing to refresh
      return;
    }

    var accounts = new ArrayList<MinecraftAccount>();
    var refreshed = 0;
    for (var account : settingsSource.accounts().values()) {
      var authService = MCAuthService.convertService(account.authType());
      if (authService.isExpired(account)) {
        if (refreshed == 0) {
          log.info("Refreshing expired accounts");
        }

        accounts.add(authService.refresh(
          account,
          settingsSource.get(AccountSettings.USE_PROXIES_FOR_ACCOUNT_AUTH)
            ? SFHelpers.getRandomEntry(settingsSource.proxies()) : null,
          scheduler
        ).join());
        refreshed++;
      } else {
        accounts.add(account);
      }
    }

    if (refreshed > 0) {
      log.info("Refreshed {} accounts", refreshed);
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();
        if (record == null) {
          return;
        }

        var currentSettings = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
        var newSettings = currentSettings.withAccounts(accounts);
        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(newSettings.serializeToTree()))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(id.toString()))
          .execute();
      });
    }
  }

  private void persistAccountMetadataUpdates(Map<UUID, Map<String, Map<String, JsonElement>>> metadataUpdates) {
    if (metadataUpdates.isEmpty()) {
      return;
    }

    dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();
      if (record == null) {
        return;
      }

      var currentSettings = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
      var newAccounts = currentSettings.accounts().stream()
        .map(account -> {
          var updatedMetadata = metadataUpdates.get(account.profileId());
          return updatedMetadata != null ? account.withPersistentMetadata(updatedMetadata) : account;
        })
        .toList();

      ctx.update(Tables.INSTANCES)
        .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(currentSettings.withAccounts(newAccounts).serializeToTree()))
        .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
        .where(Tables.INSTANCES.ID.eq(id.toString()))
        .execute();
    });
  }

  private MinecraftAccount refreshAccount(MinecraftAccount account) {
    var authService = MCAuthService.convertService(account.authType());
    if (!authService.isExpired(account)) {
      return account;
    }

    log.info("Account {} is expired, refreshing before connecting", account.lastKnownName());
    var refreshedAccount = authService.refresh(
      account,
      settingsSource.get(AccountSettings.USE_PROXIES_FOR_ACCOUNT_AUTH)
        ? SFHelpers.getRandomEntry(settingsSource.proxies()) : null,
      scheduler
    ).join();
    var accounts = new ArrayList<>(settingsSource.accounts().values());
    accounts.replaceAll(a -> a.authType().equals(refreshedAccount.authType())
      && a.profileId().equals(refreshedAccount.profileId()) ? refreshedAccount : a);
    dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).fetchOne();
      if (record == null) {
        return;
      }

      var currentSettings = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
      var newSettings = currentSettings.withAccounts(accounts);
      ctx.update(Tables.INSTANCES)
        .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(newSettings.serializeToTree()))
        .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
        .where(Tables.INSTANCES.ID.eq(id.toString()))
        .execute();
    });

    return refreshedAccount;
  }

  public CompletableFuture<?> switchToState(@Nullable SoulFireUser initiator, SessionLifecycle targetState) {
    return switch (targetState) {
      case STARTING, RUNNING -> switch (sessionLifecycle()) {
        case STARTING, RUNNING, STOPPING -> CompletableFuture.completedFuture(null);
        case PAUSED -> {
          if (initiator != null) {
            addAuditLog(initiator, AuditLogType.RESUME_SESSION, null);
          }

          this.sessionLifecycle(allBotsConnected.get() ? SessionLifecycle.RUNNING : SessionLifecycle.STARTING);
          yield CompletableFuture.completedFuture(null);
        }
        case STOPPED -> {
          if (initiator != null) {
            addAuditLog(initiator, AuditLogType.START_SESSION, null);
          }

          yield scheduler.runAsync(this::start);
        }
      };
      case PAUSED -> switch (sessionLifecycle()) {
        case STARTING, RUNNING -> {
          if (initiator != null) {
            addAuditLog(initiator, AuditLogType.PAUSE_SESSION, null);
          }

          this.sessionLifecycle(SessionLifecycle.PAUSED);
          yield CompletableFuture.completedFuture(null);
        }
        case STOPPING, PAUSED -> CompletableFuture.completedFuture(null);
        case STOPPED -> {
          if (initiator != null) {
            addAuditLog(initiator, AuditLogType.START_SESSION, null);
            addAuditLog(initiator, AuditLogType.PAUSE_SESSION, null);
          }

          yield scheduler.runAsync(this::start)
            .thenRunAsync(() -> this.sessionLifecycle(SessionLifecycle.PAUSED), scheduler);
        }
      };
      case STOPPING, STOPPED -> switch (sessionLifecycle()) {
        case STARTING, RUNNING, PAUSED -> {
          if (initiator != null) {
            addAuditLog(initiator, AuditLogType.STOP_SESSION, null);
          }
          yield stopSessionPermanently();
        }
        case STOPPING, STOPPED -> CompletableFuture.completedFuture(null);
      };
    };
  }

  private void start() {
    if (!sessionLifecycle().isFullyStopped()) {
      throw new IllegalStateException("Another session is still running");
    }

    allBotsConnected.set(false);
    this.sessionLifecycle(SessionLifecycle.STARTING);

    log.info("Preparing bot session");

    var botAmount = settingsSource.get(BotSettings.AMOUNT); // How many bots to connect
    var botsPerProxy =
      settingsSource.get(ProxySettings.BOTS_PER_PROXY); // How many bots per proxy are allowed
    var stickyProxiesEnabled = settingsSource.get(ProxySettings.STICKY_PROXIES);

    var proxies = new ArrayList<>(settingsSource.proxies()
      .stream()
      .map(p -> new StickyProxyAllocator.ProxyAllocation(p, botsPerProxy))
      .toList());
    {
      var availableProxies = proxies.size();
      if (availableProxies == 0) {
        log.info("No proxies provided, session will be performed without proxies");
      } else {
        var maxBots = MathHelper.sumCapOverflow(proxies.stream().mapToInt(StickyProxyAllocator.ProxyAllocation::availableBots));
        if (botAmount > maxBots) {
          log.warn("You have requested {} bots, but only {} are possible with the current amount of proxies.", botAmount, maxBots);
          log.warn("Continuing with {} bots due to proxies.", maxBots);
          botAmount = maxBots;
        }

        if (settingsSource.get(ProxySettings.SHUFFLE_PROXIES)) {
          Collections.shuffle(proxies);
        }
      }
    }

    List<MinecraftAccount> selectedAccounts;
    {
      var accounts = new ArrayList<>(settingsSource.accounts().values());
      var availableAccounts = accounts.size();
      if (availableAccounts == 0) {
        throw new IllegalStateException("No accounts configured. Please import accounts before starting a session.");
      }

      if (botAmount > availableAccounts) {
        throw new IllegalStateException(
          "Not enough accounts. You requested %d bots but only have %d accounts imported.".formatted(botAmount, availableAccounts));
      }

      if (settingsSource.get(AccountSettings.SHUFFLE_ACCOUNTS)) {
        Collections.shuffle(accounts);
      }

      selectedAccounts = accounts.subList(0, botAmount);
    }

    var refreshedAccounts = selectedAccounts.stream().map(this::refreshAccount).toList();
    var assignedProxies = StickyProxyAllocator.assign(refreshedAccounts, proxies, stickyProxiesEnabled);
    var stickyMetadataUpdates = new LinkedHashMap<UUID, Map<String, Map<String, JsonElement>>>();
    var preparedAssignments = new ArrayList<StickyProxyAllocator.AssignedProxy>(assignedProxies.size());
    for (var assignedProxy : assignedProxies) {
      var minecraftAccount = assignedProxy.account();
      if (stickyProxiesEnabled && assignedProxy.proxy() != null) {
        var updatedMetadata = StickyProxyAllocator.withSelectedProxy(minecraftAccount.persistentMetadata(), assignedProxy.proxy());
        if (!updatedMetadata.equals(minecraftAccount.persistentMetadata())) {
          stickyMetadataUpdates.put(minecraftAccount.profileId(), updatedMetadata);
        }
        minecraftAccount = minecraftAccount.withPersistentMetadata(updatedMetadata);
      }
      preparedAssignments.add(new StickyProxyAllocator.AssignedProxy(minecraftAccount, assignedProxy.proxy()));
    }

    if (!stickyMetadataUpdates.isEmpty()) {
      persistAccountMetadataUpdates(stickyMetadataUpdates);
      invalidateSettingsCache();
    }

    var factories = new ArrayBlockingQueue<BotConnectionFactory>(preparedAssignments.size());
    for (var preparedAssignment : preparedAssignments) {
      var minecraftAccount = preparedAssignment.account();
      var lastAccountObject = new AtomicReference<>(minecraftAccount);
      var botSettings = new BotSettingsDelegate(new CachedLazyObject<>(() -> {
        var fetchedSettingsSource = this.settingsSource();
        var fetchedAccount = fetchedSettingsSource.accounts().get(minecraftAccount.profileId());
        if (fetchedAccount == null) {
          fetchedAccount = lastAccountObject.get();
        } else {
          lastAccountObject.set(fetchedAccount);
        }

        return new BotSettingsImpl(fetchedAccount, fetchedSettingsSource);
      }, 1, TimeUnit.SECONDS));
      var protocolVersion = botSettings.get(BotSettings.PROTOCOL_VERSION, BotSettings.PROTOCOL_VERSION_PARSER);
      var serverAddress = BotConnectionFactory.parseAddress(settingsSource.get(BotSettings.ADDRESS), protocolVersion);
      factories.add(
        new BotConnectionFactory(
          this,
          botSettings,
          protocolVersion,
          serverAddress,
          preparedAssignment.proxy()
        ));
    }

    var usedProxies = proxies.stream().filter(StickyProxyAllocator.ProxyAllocation::hasBots).count();
    if (usedProxies == 0) {
      log.info("Starting session with {} bots", factories.size());
    } else {
      log.info("Starting session with {} bots and {} active proxies", factories.size(), usedProxies);
    }

    SoulFireAPI.postEvent(new SessionStartEvent(this));

    var connectSemaphore = new Semaphore(settingsSource.get(BotSettings.CONCURRENT_CONNECTS));
    scheduler.schedule(
      () -> {
        allBotsConnected.set(false);
        while (!factories.isEmpty()) {
          var factory = factories.poll();
          if (factory == null) {
            break;
          }

          try {
            connectSemaphore.acquire();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            break;
          }

          log.debug("Scheduling bot {}", factory.settingsSource().stem().lastKnownName());
          scheduler.schedule(
            SoulFireScheduler.FinalizableRunnable.withFinalizer(() -> {
              if (sessionLifecycle().isStoppedOrStopping()) {
                return;
              }

              TimeUtil.waitCondition(() -> sessionLifecycle().isPaused());

              log.debug("Connecting bot {}", factory.settingsSource().stem().lastKnownName());
              var botConnection = factory.prepareConnection(false);
              storeNewBot(botConnection);

              try {
                botConnection.connect().get();
              } catch (Throwable e) {
                log.error("Error while connecting", e);
              }
            }, connectSemaphore::release));

          TimeUtil.waitTime(
            settingsSource.getRandom(BotSettings.JOIN_DELAY).getAsLong(),
            TimeUnit.MILLISECONDS);
        }

        allBotsConnected.set(true);
        if (this.sessionLifecycle() == SessionLifecycle.STARTING) {
          this.sessionLifecycle(SessionLifecycle.RUNNING);
        }
      });
  }

  public CompletableFuture<?> deleteInstance() {
    return stopSessionPermanently()
      .thenRunAsync(scheduler::shutdown, soulFireServer.scheduler())
      .thenRunAsync(() -> {
        try {
          SFHelpers.deleteDirectory(getInstanceObjectStoragePath());
        } catch (IOException e) {
          log.error("Error while deleting instance storage", e);
        }
      }, soulFireServer.scheduler());
  }

  public CompletableFuture<?> shutdownHook() {
    return stopSession()
      .thenRunAsync(scheduler::shutdown, soulFireServer.scheduler());
  }

  public CompletableFuture<?> stopSessionPermanently() {
    if (sessionLifecycle().isStoppedOrStopping()) {
      return CompletableFuture.completedFuture(null);
    }

    log.info("Stopping bot session");
    this.sessionLifecycle(SessionLifecycle.STOPPING);

    return this.stopSession()
      .thenRunAsync(() -> {
        this.sessionLifecycle(SessionLifecycle.STOPPED);
        log.info("Session stopped");
      }, scheduler);
  }

  private void sessionLifecycle(SessionLifecycle sessionLifecycle) {
    this.sessionLifecycle = sessionLifecycle;
    dsl.update(Tables.INSTANCES)
      .set(Tables.INSTANCES.SESSION_LIFECYCLE, sessionLifecycle.name())
      .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
      .where(Tables.INSTANCES.ID.eq(id.toString()))
      .execute();
  }

  public CompletableFuture<?> stopSession() {
    return scheduler.runAsync(() -> {
      allBotsConnected.set(false);
      log.info("Disconnecting bots");
      do {
        var disconnectFuture = new ArrayList<CompletableFuture<?>>();
        botConnections.entrySet().removeIf(entry -> {
          var botConnection = entry.getValue();
          disconnectFuture.add(scheduler.runAsync(() -> botConnection.disconnect(Component.text("Session stopped"))));
          return true;
        });

        log.info("Waiting for all bots to fully disconnect");
        for (var future : disconnectFuture) {
          try {
            future.get();
          } catch (InterruptedException | ExecutionException e) {
            log.error("Error while shutting down", e);
          }
        }
      } while (!botConnections.isEmpty()); // To make sure really all bots are disconnected

      // Notify plugins of state change
      SoulFireAPI.postEvent(new SessionEndedEvent(this));
    });
  }

  public void addAuditLog(SoulFireUser source, AuditLogType logType, @Nullable String data) {
    scheduler.execute(() -> dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      // Verify instance and user exist
      var instanceExists = ctx.fetchExists(Tables.INSTANCES, Tables.INSTANCES.ID.eq(id.toString()));
      var userExists = ctx.fetchExists(Tables.USERS, Tables.USERS.ID.eq(source.getUniqueId().toString()));
      if (!instanceExists || !userExists) {
        return;
      }

      var now = LocalDateTime.now(ZoneOffset.UTC);
      ctx.insertInto(Tables.INSTANCE_AUDIT_LOGS)
        .set(Tables.INSTANCE_AUDIT_LOGS.ID, UUID.randomUUID().toString())
        .set(Tables.INSTANCE_AUDIT_LOGS.TYPE, logType.name())
        .set(Tables.INSTANCE_AUDIT_LOGS.DATA, data)
        .set(Tables.INSTANCE_AUDIT_LOGS.INSTANCE_ID, id.toString())
        .set(Tables.INSTANCE_AUDIT_LOGS.USER_ID, source.getUniqueId().toString())
        .set(Tables.INSTANCE_AUDIT_LOGS.CREATED_AT, now)
        .set(Tables.INSTANCE_AUDIT_LOGS.UPDATED_AT, now)
        .set(Tables.INSTANCE_AUDIT_LOGS.VERSION, 0L)
        .execute();
    }));
  }

  public Path getInstanceObjectStoragePath() {
    return soulFireServer.getObjectStoragePath().resolve("instance-" + id.toString());
  }

  public List<BotConnection> getConnectedBots() {
    return new ArrayList<>(botConnections.values());
  }

  public void storeNewBot(BotConnection connection) {
    botConnections.put(connection.accountProfileId(), connection);
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof InstanceManager that)) {
      return false;
    }

    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  private record InstanceRunnableWrapper(InstanceManager instanceManager) implements SoulFireScheduler.RunnableWrapper {
    @Override
    public Runnable wrap(Runnable runnable) {
      return () -> {
        try (
          var ignored1 = SFHelpers.smartThreadLocalCloseable(CURRENT, instanceManager);
          var ignored2 = SFHelpers.smartMDCCloseable(SFLogAppender.SF_INSTANCE_ID, instanceManager.id().toString());
          var ignored3 = SFHelpers.smartMDCCloseable(SFLogAppender.SF_INSTANCE_NAME, instanceManager.friendlyNameCache().get())) {
          runnable.run();
        }
      };
    }
  }
}
