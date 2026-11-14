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
import com.soulfiremc.server.api.PluginSettingsPageRegistration;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.lifecycle.ServerSettingsRegistryInitEvent;
import com.soulfiremc.server.api.event.session.InstanceInitEvent;
import com.soulfiremc.server.api.metadata.MetadataHolder;
import com.soulfiremc.server.command.ServerCommandManager;
import com.soulfiremc.server.database.DatabaseManager;
import com.soulfiremc.server.database.InstallationId;
import com.soulfiremc.server.database.InstanceConstants;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.grpc.LogServiceImpl;
import com.soulfiremc.server.grpc.RPCServer;
import com.soulfiremc.server.metrics.ServerMetricsCollector;
import com.soulfiremc.server.proxy.ProxyAuthenticator;
import com.soulfiremc.server.settings.lib.*;
import com.soulfiremc.server.settings.server.DevSettings;
import com.soulfiremc.server.settings.server.ServerSettings;
import com.soulfiremc.server.spark.SFSparkPlugin;
import com.soulfiremc.server.task.BotTaskManager;
import com.soulfiremc.server.user.*;
import com.soulfiremc.server.util.KeyHelper;
import com.soulfiremc.server.util.SFHelpers;
import com.soulfiremc.server.util.SFPathConstants;
import com.soulfiremc.server.util.structs.GsonInstance;
import com.soulfiremc.server.util.structs.SFUpdateChecker;
import com.soulfiremc.server.util.structs.ShutdownManager;
import com.viaversion.viaversion.api.Via;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jooq.DSLContext;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/// The main class of the Soulfire server.
 /// A Soulfire server can hold and manage multiple instances.
 /// It also provides an RPC server to communicate with the Soulfire client.
 /// More than one SoulFireServer can be running at the same time, but on different ports.
 /// Usually there is only one SoulFireServer running on a jvm, but it is possible to run multiple.
@Slf4j
@Getter
public final class SoulFireServer {
  private static final ScopedValue<SoulFireServer> CURRENT = ScopedValue.newInstance();

  public static SoulFireServer current() {
    var current = CURRENT.isBound() ? CURRENT.get() : null;
    if (!SFConstants.NOT_REGISTRY_INIT_PHASE) {
      return current;
    }

    if (current == null) {
      new RuntimeException().printStackTrace();
    }
    return Objects.requireNonNull(current, "No soulfire server in current thread");
  }

  public static Optional<SoulFireServer> currentOptional() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
  }

  private final SoulFireScheduler.RunnableWrapper runnableWrapper = new ServerRunnableWrapper(this);
  private final SoulFireScheduler scheduler = new SoulFireScheduler(runnableWrapper);
  private final Map<UUID, InstanceManager> instances = new ConcurrentHashMap<>();
  private final Map<UUID, CompletableFuture<?>> instanceDeletions = new ConcurrentHashMap<>();
  private final MetadataHolder<Object> metadata = new MetadataHolder<>();
  private final ServerSettingsDelegate settingsSource;
  private final RPCServer rpcServer;
  private final AuthSystem authSystem;
  private final BotTaskManager botTaskManager;
  private final SettingsPageRegistry settingsPageRegistry;
  private final ServerCommandManager serverCommandManager;
  private final ServerMetricsCollector serverMetricsCollector;
  private final ShutdownManager shutdownManager;
  private final DatabaseManager.DatabaseContext databaseContext;
  private final UUID installationId;
  private final SecretKey jwtSecretKey;
  private final SFSparkPlugin sparkPlugin;
  @Getter
  private final LogServiceImpl.StateHolder logStateHolder = new LogServiceImpl.StateHolder();

  public SoulFireServer(
    String host,
    int port,
    Instant startTime) {
    log.info("Starting Soulfire v{} ({} @ {})", BuildData.VERSION, BuildData.BRANCH, BuildData.COMMIT_SHORT);

    this.shutdownManager = new ShutdownManager(this::shutdownHook);

    this.jwtSecretKey = KeyHelper.getOrCreateJWTSecretKey(SFPathConstants.baseDirectory().resolve("secret-key.bin"));

    try {
      Files.createDirectories(getObjectStoragePath());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Authenticator.setDefault(new ProxyAuthenticator());

    var serverCommandManagerFuture = scheduler.supplyAsync(() -> new ServerCommandManager(this));
    var databaseContextFuture = scheduler.supplyAsync(DatabaseManager::select);
    this.botTaskManager = new BotTaskManager(this);
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

      SoulFireAPI.pluginApis().applySettingsPages(
        PluginSettingsPageRegistration.Scope.SERVER,
        registry
      );
      SoulFireAPI.postEvent(new ServerSettingsRegistryInitEvent(this, registry));

      return registry;
    });

    this.databaseContext = databaseContextFuture.join();
    this.installationId = InstallationId.getOrCreate(databaseContext.dsl());
    this.settingsSource = new ServerSettingsDelegate(this::fetchSettingsSource);
    this.authSystem = new AuthSystem(this, databaseContext.dsl());
    this.rpcServer = rpcServerFuture.join();

    var newVersion = updateCheck.join();
    if (newVersion != null) {
      log.warn(
        "Soulfire is outdated! Current version: {}, latest version: {}",
        BuildData.VERSION,
        newVersion);
    } else {
      log.info("Soulfire is up to date!");
    }

    this.settingsPageRegistry = serverSettingsRegistryFuture.join();
    this.sparkPlugin = sparkStart.join();
    this.serverCommandManager = serverCommandManagerFuture.join();
    this.serverMetricsCollector = new ServerMetricsCollector(this);
    this.scheduler.scheduleWithFixedDelay(serverMetricsCollector::sampleSnapshot, 3, 3, TimeUnit.SECONDS);

    // Via is ready, we can now set up all config stuff
    setupLogging();

    SoulFireAPI.enablePlugins(this);

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
        .set(Tables.SERVER_CONFIG.SETTINGS, GsonInstance.CONFIG_GSON.toJson(ServerSettingsImpl.Stem.EMPTY.serializeToTree()))
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
    return SFPathConstants.baseDirectory().resolve("object-storage");
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
          var instance = new InstanceManager(this, dsl(), instanceId);
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

    // Stop accepting or recovering durable bot work before connections close.
    botTaskManager.close();

    // Shutdown the sessions if there is any
    shutdownInstances().join();

    // Stop plugin runtime work while server facilities are still available
    SoulFireAPI.disablePlugins();

    // Shutdown scheduled tasks
    scheduler.shutdown();

    // Shutdown database
    databaseContext.close();
  }

  public UUID createInstance(String friendlyName, SoulFireUser owner) {
    return createInstance(friendlyName, owner, InstanceSettingsImpl.Stem.EMPTY);
  }

  public UUID createInstance(String friendlyName, SoulFireUser owner, InstanceSettingsImpl.Stem settings) {
    return createInstance(friendlyName, owner, settings, InstanceConstants.randomInstanceIcon());
  }

  public UUID createInstance(String friendlyName, SoulFireUser owner, InstanceSettingsImpl.Stem settings, String icon) {
    var now = LocalDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    dsl().insertInto(Tables.INSTANCES)
      .set(Tables.INSTANCES.ID, id.toString())
      .set(Tables.INSTANCES.FRIENDLY_NAME, friendlyName)
      .set(Tables.INSTANCES.ICON, icon)
      .set(Tables.INSTANCES.OWNER_ID, owner.getUniqueId().toString())
      .set(Tables.INSTANCES.SETTINGS, GsonInstance.CONFIG_GSON.toJson(settings.serializeToTree()))
      .set(Tables.INSTANCES.CREATED_AT, now)
      .set(Tables.INSTANCES.UPDATED_AT, now)
      .set(Tables.INSTANCES.VERSION, 0L)
      .execute();

    var instanceManager = new InstanceManager(this, dsl(), id);
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
    var instance = instances.get(id);
    if (instance != null) {
      synchronized (instance) {
        return deleteInstance(id, instance);
      }
    }
    return deleteInstance(id, null);
  }

  private Optional<CompletableFuture<?>> deleteInstance(UUID id, @Nullable InstanceManager instance) {
    if (instance == null && !dsl().fetchExists(Tables.INSTANCES, Tables.INSTANCES.ID.eq(id.toString()))) {
      return Optional.empty();
    }

    var deletion = new CompletableFuture<Void>();
    var existing = instanceDeletions.putIfAbsent(id, deletion);
    if (existing != null) {
      return Optional.of(existing);
    }
    try {
      if (instance != null) instance.markDeleting();
      rpcServer.scriptService().stopAllScripts(id);
      var cleanup = instance == null
        ? CompletableFuture.runAsync(() -> deleteInstanceStorage(id), scheduler)
        : instance.deleteInstance();
      cleanup.thenRunAsync(() -> {
        // Disconnect callbacks and scheduler cleanup can still reload the profile.
        dsl().deleteFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(id.toString())).execute();
        if (instance != null) instances.remove(id, instance);
      }, scheduler).whenComplete((_, failure) -> {
        instanceDeletions.remove(id, deletion);
        if (failure == null) deletion.complete(null);
        else deletion.completeExceptionally(failure);
      });
    } catch (Throwable failure) {
      instanceDeletions.remove(id, deletion);
      deletion.completeExceptionally(failure);
    }
    return Optional.of(deletion);
  }

  void deleteInstanceStorage(UUID id) {
    for (var path : List.of(
      getObjectStoragePath().resolve("instance-" + id),
      SFPathConstants.baseDirectory().resolve("client-data").resolve("instance-" + id))) {
      try {
        SFHelpers.deleteDirectory(path);
      } catch (IOException e) {
        throw new UncheckedIOException("Could not delete instance storage " + path, e);
      }
    }
  }

  public boolean isInstanceDeleting(UUID id) {
    var instance = instances.get(id);
    return (instance != null && instance.deleting()) || instanceDeletions.containsKey(id);
  }

  public Optional<InstanceManager> getInstance(UUID id) {
    return Optional.ofNullable(instances.get(id));
  }

  private record ServerRunnableWrapper(SoulFireServer server) implements SoulFireScheduler.RunnableWrapper {
    @Override
    public Runnable wrap(Runnable runnable) {
      return () -> ScopedValue.where(CURRENT, server).run(runnable);
    }
  }
}
