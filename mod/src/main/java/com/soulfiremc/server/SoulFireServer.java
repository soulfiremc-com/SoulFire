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
import com.soulfiremc.builddata.BuildData;
import com.soulfiremc.mod.util.SFConstants;
import com.soulfiremc.server.api.SessionLifecycle;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.lifecycle.ServerSettingsRegistryInitEvent;
import com.soulfiremc.server.api.event.session.InstanceInitEvent;
import com.soulfiremc.server.api.metadata.MetadataHolder;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.command.ServerCommandManager;
import com.soulfiremc.server.database.DatabaseManager;
import com.soulfiremc.server.database.InstanceConstants;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.grpc.LogServiceImpl;
import com.soulfiremc.server.grpc.RPCServer;
import com.soulfiremc.server.metrics.ServerMetricsCollector;
import com.soulfiremc.server.settings.lib.InstanceSettingsImpl;
import com.soulfiremc.server.settings.lib.ServerSettingsDelegate;
import com.soulfiremc.server.settings.lib.ServerSettingsImpl;
import com.soulfiremc.server.settings.lib.ServerSettingsSource;
import com.soulfiremc.server.settings.lib.SettingsPageRegistry;
import com.soulfiremc.server.settings.server.DevSettings;
import com.soulfiremc.server.settings.server.ServerSettings;
import com.soulfiremc.server.spark.SFSparkPlugin;
import com.soulfiremc.server.user.*;
import com.soulfiremc.server.util.KeyHelper;
import com.soulfiremc.server.util.SFHelpers;
import com.soulfiremc.server.util.SFPathConstants;
import com.soulfiremc.server.util.structs.CachedLazyObject;
import com.soulfiremc.server.util.structs.GsonInstance;
import com.soulfiremc.server.util.structs.SFUpdateChecker;
import com.soulfiremc.server.util.structs.ShutdownManager;
import com.viaversion.viaversion.api.Via;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/// The main class of the SoulFire server.
/// A SoulFire server can hold and manage multiple instances.
/// It also provides an RPC server to communicate with the SoulFire client.
/// More than one SoulFireServer can be running at the same time, but on different ports.
/// Usually there is only one SoulFireServer running on a jvm, but it is possible to run multiple.
@Slf4j
@Getter
public final class SoulFireServer {
  private static final ThreadLocal<SoulFireServer> CURRENT = new ThreadLocal<>();

  public static SoulFireServer current() {
    var current = CURRENT.get();
    if (!SFConstants.NOT_REGISTRY_INIT_PHASE) {
      return current;
    }

    if (current == null) {
      new RuntimeException().printStackTrace();
    }
    return Objects.requireNonNull(current, "No soulfire server in current thread");
  }

  public static Optional<SoulFireServer> currentOptional() {
    return Optional.ofNullable(CURRENT.get());
  }

  private final SoulFireScheduler.RunnableWrapper runnableWrapper = new ServerRunnableWrapper(this);
  private final SoulFireScheduler scheduler = new SoulFireScheduler(runnableWrapper);
  private final Map<UUID, InstanceManager> instances = new ConcurrentHashMap<>();
  private final MetadataHolder<Object> metadata = new MetadataHolder<>();
  private final ServerSettingsDelegate settingsSource;
  private final RPCServer rpcServer;
  private final AuthSystem authSystem;
  private final SettingsPageRegistry settingsPageRegistry;
  private final ServerCommandManager serverCommandManager;
  private final ServerMetricsCollector serverMetricsCollector;
  private final ShutdownManager shutdownManager;
  private final DatabaseManager.DatabaseContext databaseContext;
  private final SecretKey jwtSecretKey;
  private final SFSparkPlugin sparkPlugin;
  @Getter
  private final LogServiceImpl.StateHolder logStateHolder = new LogServiceImpl.StateHolder();

  public SoulFireServer(
    String host,
    int port,
    Instant startTime) {
    log.info("Starting SoulFire v{} ({} @ {})", BuildData.VERSION, BuildData.BRANCH, BuildData.COMMIT_SHORT);

    this.shutdownManager = new ShutdownManager(this::shutdownHook);

    this.jwtSecretKey = KeyHelper.getOrCreateJWTSecretKey(SFPathConstants.BASE_DIR.resolve("secret-key.bin"));

    try {
      Files.createDirectories(getObjectStoragePath());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Authenticator.setDefault(new Authenticator() {
      @Override
      protected @Nullable PasswordAuthentication getPasswordAuthentication() {
        var connection = BotConnection.current();
        if (connection == null
          || connection.proxy() == null) {
          return null;
        }

        var proxyUsername = connection.proxy().username();
        var proxyPassword = connection.proxy().password();
        if (proxyUsername == null || proxyPassword == null) {
          return null;
        }

        return new PasswordAuthentication(
          proxyUsername,
          proxyPassword.toCharArray()
        );
      }
    });

    var serverCommandManagerFuture = scheduler.supplyAsync(() -> new ServerCommandManager(this));
    var databaseContextFuture = scheduler.supplyAsync(DatabaseManager::select);
    var authSystemFuture = databaseContextFuture.thenApplyAsync(databaseContext -> new AuthSystem(this, databaseContext.dsl()), scheduler);
    var rpcServerFuture = scheduler.supplyAsync(() -> new RPCServer(host, port, this));

    var configDirectory = SFPathConstants.getConfigDirectory();
    var sparkStart =
      scheduler.supplyAsync(
        () -> {
          var sparkPlugin = new SFSparkPlugin(configDirectory.resolve("spark"), this);
          sparkPlugin.init();

          return sparkPlugin;
        });
    var updateCheck =
      scheduler.supplyAsync(
        () -> {
          log.info("Checking for updates...");
          return SFUpdateChecker.check()
            .getUpdateVersion()
            .orElse(null);
        });

    var serverSettingsRegistryFuture = scheduler.supplyAsync(() -> {
      var registry = new SettingsPageRegistry()
        .addInternalPage(ServerSettings.class, "server", "Server Settings", "server")
        .addInternalPage(DevSettings.class, "dev", "Developer Settings", "code");

      SoulFireAPI.postEvent(new ServerSettingsRegistryInitEvent(this, registry));

      return registry;
    });

    this.databaseContext = databaseContextFuture.join();
    this.settingsSource = new ServerSettingsDelegate(new CachedLazyObject<>(this::fetchSettingsSource, 1, TimeUnit.SECONDS));
    this.authSystem = authSystemFuture.join();
    this.rpcServer = rpcServerFuture.join();

    var newVersion = updateCheck.join();
    if (newVersion != null) {
      log.warn(
        "SoulFire is outdated! Current version: {}, latest version: {}",
        BuildData.VERSION,
        newVersion);
    } else {
      log.info("SoulFire is up to date!");
    }

    this.settingsPageRegistry = serverSettingsRegistryFuture.join();
    this.sparkPlugin = sparkStart.join();
    this.serverCommandManager = serverCommandManagerFuture.join();
    this.serverMetricsCollector = new ServerMetricsCollector(this);
    this.scheduler.scheduleWithFixedDelay(serverMetricsCollector::sampleSnapshot, 3, 3, TimeUnit.SECONDS);

    // Via is ready, we can now set up all config stuff
    setupLogging();

    log.info("Loading instances...");
    loadInstances();

    var rpcServerStart =
      scheduler.runAsync(
        () -> {
          try {
            rpcServer.start();
          } catch (IOException e) {
            throw new CompletionException(e);
          }
        });

    rpcServerStart.join();

    log.info(
      "Finished loading! (Took {}ms)", Duration.between(startTime, Instant.now()).toMillis());
  }

  public DSLContext dsl() {
    return databaseContext.dsl();
  }

  private ServerSettingsSource fetchSettingsSource() {
    var record = dsl().selectFrom(Tables.SERVER_CONFIG).where(Tables.SERVER_CONFIG.ID.eq(1L)).fetchOne();
    if (record == null) {
      dsl().insertInto(Tables.SERVER_CONFIG)
        .set(Tables.SERVER_CONFIG.ID, 1L)
        .set(Tables.SERVER_CONFIG.SETTINGS, GsonInstance.GSON.toJson(ServerSettingsImpl.Stem.EMPTY.serializeToTree()))
        .set(Tables.SERVER_CONFIG.VERSION, 0L)
        .execute();
      return new ServerSettingsImpl(ServerSettingsImpl.Stem.EMPTY);
    }

    var settingsStr = record.getSettings();
    var stem = ServerSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(settingsStr, JsonElement.class));
    return new ServerSettingsImpl(stem);
  }

  public EmailSender emailSender() {
    return switch (settingsSource.get(ServerSettings.EMAIL_TYPE, ServerSettings.EmailType.class)) {
      case CONSOLE -> new ConsoleEmailSender();
      case SMTP -> new SmtpEmailSender(this);
    };
  }

  public Path getObjectStoragePath() {
    return SFPathConstants.BASE_DIR.resolve("object-storage");
  }

  public Path getScriptCodePath(UUID id) {
    return getObjectStoragePath().resolve("script-code-" + id);
  }

  public void configUpdateHook() {
    settingsSource.invalidate();
    setupLogging();
  }

  public void setupLogging() {
    Configurator.setLevel("com.soulfiremc", settingsSource.get(DevSettings.SOULFIRE_DEBUG) ? Level.DEBUG : Level.INFO);
    Configurator.setLevel("net.minecraft", settingsSource.get(DevSettings.MINECRAFT_DEBUG) ? Level.DEBUG : Level.INFO);
    Configurator.setLevel("io.netty", settingsSource.get(DevSettings.NETTY_DEBUG) ? Level.DEBUG : Level.INFO);
    Configurator.setLevel("io.grpc", settingsSource.get(DevSettings.GRPC_DEBUG) ? Level.DEBUG : Level.INFO);
    Configurator.setLevel("org.jooq", settingsSource.get(DevSettings.DATABASE_DEBUG) ? Level.DEBUG : Level.INFO);
    if (!Boolean.getBoolean("sf.unit.test")) {
      Via.getManager().debugHandler().setEnabled(settingsSource.get(DevSettings.VIA_DEBUG));
    }
    Configurator.setRootLevel(settingsSource.get(DevSettings.OTHER_DEBUG) ? Level.DEBUG : Level.INFO);
  }

  private void loadInstances() {
    try {
      for (var record : dsl().selectFrom(Tables.INSTANCES).fetch()) {
        try {
          var instanceId = UUID.fromString(record.getId());
          var lifecycle = SessionLifecycle.valueOf(record.getSessionLifecycle());
          var instance = new InstanceManager(this, dsl(), instanceId, lifecycle);
          SoulFireAPI.postEvent(new InstanceInitEvent(instance));

          instances.put(instance.id(), instance);

          log.info("Restored instance with id {}", instance.id());
        } catch (Exception e) {
          log.error("Failed to load existing instance", e);
        }
      }
    } catch (Exception e) {
      log.error("Failed to load existing instances", e);
    }
  }

  private void shutdownHook() {
    // Shut down RPC
    try {
      rpcServer.shutdown();
    } catch (InterruptedException e) {
      log.error("Failed to stop RPC server", e);
    }

    // Shutdown the sessions if there is any
    shutdownInstances().join();

    // Shutdown scheduled tasks
    scheduler.shutdown();

    // Shutdown database
    databaseContext.close();
  }

  public UUID createInstance(String friendlyName, SoulFireUser owner) {
    var now = LocalDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    dsl().insertInto(Tables.INSTANCES)
      .set(Tables.INSTANCES.ID, id.toString())
      .set(Tables.INSTANCES.FRIENDLY_NAME, friendlyName)
      .set(Tables.INSTANCES.ICON, InstanceConstants.randomInstanceIcon())
      .set(Tables.INSTANCES.OWNER_ID, owner.getUniqueId().toString())
      .set(Tables.INSTANCES.SESSION_LIFECYCLE, SessionLifecycle.STOPPED.name())
      .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(InstanceSettingsImpl.Stem.EMPTY.serializeToTree()))
      .set(Tables.INSTANCES.CREATED_AT, now)
      .set(Tables.INSTANCES.UPDATED_AT, now)
      .set(Tables.INSTANCES.VERSION, 0L)
      .execute();

    var instanceManager = new InstanceManager(this, dsl(), id, SessionLifecycle.STOPPED);
    SoulFireAPI.postEvent(new InstanceInitEvent(instanceManager));

    instances.put(instanceManager.id(), instanceManager);

    log.debug("Created instance with id {}", instanceManager.id());

    return instanceManager.id();
  }

  public CompletableFuture<?> shutdownInstances() {
    return CompletableFuture.allOf(instances.values().stream()
      .map(InstanceManager::shutdownHook)
      .toArray(CompletableFuture[]::new));
  }

  public Optional<CompletableFuture<?>> deleteInstance(UUID id) {
    dsl().deleteFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).execute();

    return Optional.ofNullable(instances.remove(id)).map(InstanceManager::deleteInstance);
  }

  public Optional<InstanceManager> getInstance(UUID id) {
    return Optional.ofNullable(instances.get(id));
  }

  private record ServerRunnableWrapper(SoulFireServer server) implements SoulFireScheduler.RunnableWrapper {
    @Override
    public Runnable wrap(Runnable runnable) {
      return () -> {
        try (
          var ignored1 = SFHelpers.smartThreadLocalCloseable(CURRENT, server)) {
          runnable.run();
        }
      };
    }
  }
}
