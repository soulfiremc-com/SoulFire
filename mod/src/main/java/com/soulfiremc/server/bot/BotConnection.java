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
package com.soulfiremc.server.bot;

import com.google.common.collect.Queues;
import com.google.gson.JsonElement;
import com.mojang.authlib.minecraft.UserApiService;
import com.soulfiremc.mod.access.IMinecraft;
import com.soulfiremc.mod.util.SFConstants;
import com.soulfiremc.mod.util.SFModHelpers;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.account.service.BedrockData;
import com.soulfiremc.server.account.service.OfflineJavaData;
import com.soulfiremc.server.account.service.OnlineChainJavaData;
import com.soulfiremc.server.account.service.OnlineSimpleJavaData;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.bot.PreBotConnectEvent;
import com.soulfiremc.server.api.metadata.MetadataHolder;
import com.soulfiremc.server.automation.AutomationController;
import com.soulfiremc.server.proxy.ProxyType;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.settings.lib.BotSettingsDelegate;
import com.soulfiremc.server.settings.lib.BotSettingsSource;
import com.soulfiremc.server.util.SFHelpers;
import com.soulfiremc.shared.SFLogAppender;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ResourceLoadStateTracker;
import net.minecraft.client.User;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Getter
public final class BotConnection {
  private static final ThreadLocal<BotConnection> CURRENT = new ThreadLocal<>();
  private final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();
  private final Queue<Runnable> preTickHooks = new ConcurrentLinkedQueue<>();
  private final MetadataHolder<Object> metadata = new MetadataHolder<>();
  private final MetadataHolder<JsonElement> persistentMetadata;
  private final ControlState controlState = new ControlState();
  private final BotControlAPI botControl = new BotControlAPI();
  private final AutomationController automation;
  private final SoulFireScheduler scheduler;
  private final BotConnectionFactory factory;
  private final InstanceManager instanceManager;
  private final BotSettingsSource settingsSource;
  private final UUID accountProfileId;
  private final String accountName;
  private final ServerAddress serverAddress;
  private final SoulFireScheduler.RunnableWrapper runnableWrapper;
  private final AtomicBoolean shutdownExecuting = new AtomicBoolean(false);
  private final Minecraft minecraft;
  @Nullable
  private final SFProxy proxy;
  private final SFSessionService sessionService;
  private final boolean isStatusPing;
  @Setter
  private ProtocolVersion currentProtocolVersion;
  private boolean isDisconnected;

  public static BotConnection current() {
    var current = CURRENT.get();
    if (!SFConstants.NOT_REGISTRY_INIT_PHASE) {
      return current;
    }

    if (current == null) {
      new RuntimeException().printStackTrace();
    }
    return Objects.requireNonNull(current, "No bot connection in current thread");
  }

  public static Optional<BotConnection> currentOptional() {
    return Optional.ofNullable(CURRENT.get());
  }

  public BotConnection(
    BotConnectionFactory factory,
    InstanceManager instanceManager,
    BotSettingsSource settingsSource,
    ProtocolVersion currentProtocolVersion,
    ServerAddress serverAddress,
    @Nullable
    SFProxy proxyData,
    boolean isStatusPing) {
    this.factory = factory;
    this.instanceManager = instanceManager;
    this.settingsSource = settingsSource;
    var minecraftAccount = settingsSource.stem();
    this.accountProfileId = minecraftAccount.profileId();
    this.accountName = minecraftAccount.lastKnownName();
    this.persistentMetadata = fillPersistentMetadata(minecraftAccount);
    this.runnableWrapper = instanceManager.runnableWrapper().with(new BotRunnableWrapper(this));
    this.scheduler = new SoulFireScheduler(runnableWrapper);
    this.automation = new AutomationController(this);
    this.serverAddress = serverAddress;
    this.proxy = proxyData;
    this.minecraft = createMinecraftCopy(minecraftAccount);
    this.sessionService = new SFSessionService(this);
    this.currentProtocolVersion = currentProtocolVersion;
    this.isStatusPing = isStatusPing;
    this.shutdownHooks.add(() -> instanceManager.automationCoordinator().releaseBot(this));
  }

  private MetadataHolder<JsonElement> fillPersistentMetadata(MinecraftAccount minecraftAccount) {
    var holder = new MetadataHolder<JsonElement>();
    var persistentMetadata = minecraftAccount.persistentMetadata();
    if (persistentMetadata != null) {
      holder.resetFrom(persistentMetadata);
    }
    return holder;
  }

  @SneakyThrows
  private Minecraft createMinecraftCopy(MinecraftAccount minecraftAccount) {
    var newInstance = SFModHelpers.deepCopy(SFConstants.BASE_MC_INSTANCE);
    var userApiService = UserApiService.OFFLINE;

    //noinspection DataFlowIssue
    newInstance.packetProcessor = new PacketProcessor(null); // Null until we spawn game thread
    newInstance.pendingRunnables = Queues.newConcurrentLinkedQueue();
    newInstance.toastManager = new ToastManager(newInstance, newInstance.options);
    newInstance.gui = new Gui(newInstance);
    newInstance.running = true;
    newInstance.user = new User(
      minecraftAccount.lastKnownName(),
      minecraftAccount.profileId(),
      switch (minecraftAccount.accountData()) {
        case BedrockData ignored -> "bedrock";
        case OfflineJavaData ignored -> "offline";
        case OnlineChainJavaData onlineChainJavaData -> onlineChainJavaData.getJavaAuthManager(proxy).getMinecraftToken().getUpToDateUnchecked().getToken();
        case OnlineSimpleJavaData onlineSimpleJavaData -> onlineSimpleJavaData.accessToken();
      },
      Optional.empty(),
      Optional.empty()
    );
    newInstance.playerSocialManager = new PlayerSocialManager(newInstance, userApiService);
    newInstance.profileKeyPairManager =
      ProfileKeyPairManager.create(userApiService, newInstance.user, newInstance.gameDirectory.toPath());
    newInstance.chatListener = new ChatListener(newInstance);
    newInstance.chatListener.setMessageDelay(newInstance.options.chatDelay().get());
    newInstance.reportingContext = ReportingContext.create(ReportEnvironment.local(), userApiService);
    newInstance.deltaTracker = new DeltaTracker.Timer(20.0F, 0L, newInstance::getTickTargetMillis);
    newInstance.reloadStateTracker = new ResourceLoadStateTracker();
    var javaProxy = proxy != null
      ? new Proxy(
      proxy.type() == ProxyType.HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
      proxy.address())
      : Proxy.NO_PROXY;
    var userData = new GameConfig.UserData(newInstance.user, javaProxy);
    newInstance.downloadedPackSource = new DownloadedPackSource(
      newInstance,
      newInstance.gameDirectory.toPath().resolve("downloads"),
      userData
    );

    ((IMinecraft) newInstance).soulfire$setConnection(this);

    return newInstance;
  }

  public CompletableFuture<?> connect() {
    return scheduler.runAsync(
      () -> {
        SoulFireAPI.postEvent(new PreBotConnectEvent(this));
        var serverData = new ServerData("soulfire-target", serverAddress.toString(), ServerData.Type.OTHER);
        serverData.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);

        if (isStatusPing) {
          minecraft.execute(runnableWrapper.wrap(() -> {
            try {
              new ServerStatusPinger().pingServer(
                serverData,
                () -> {},
                () -> {},
                EventLoopGroupHolder.remote(this.minecraft.options.useNativeTransport())
              );
            } catch (Throwable t) {
              this.disconnect(Component.text("Failed to ping server: " + t.getMessage()));
            }
          }));
        } else {
          minecraft.execute(runnableWrapper.wrap(() -> ConnectScreen.startConnecting(
            new JoinMultiplayerScreen(new TitleScreen()),
            minecraft,
            serverAddress,
            serverData,
            false,
            null
          )));
        }

        scheduler.execute(() -> {
          var disconnectReason = Component.text("Tick loop ended");
          try {
            minecraft.gameThread = Thread.currentThread();
            minecraft.packetProcessor.runningThread = minecraft.gameThread;
            while (minecraft.running && !isDisconnected && !Thread.currentThread().isInterrupted()) {
              minecraft.runTick(true);
            }
          } catch (Throwable t) {
            var conciseConnectionError = conciseConnectionError(t);
            if (conciseConnectionError.isPresent()) {
              var message = conciseConnectionError.get();
              disconnectReason = Component.text(message);
              log.warn("Bot connection ended: {}", message);
              log.debug("Full bot connection error", t);
            } else {
              log.error("Error while running bot connection", t);
            }
          } finally {
            this.disconnect(disconnectReason);
          }
        });
      });
  }

  private static Optional<String> conciseConnectionError(Throwable throwable) {
    var joinServerException = findCause(throwable, SFSessionService.JoinServerException.class);
    if (joinServerException.isPresent()) {
      return Optional.of("Failed to join server: session server returned HTTP " + joinServerException.get().statusCode());
    }

    var rootCause = rootCause(throwable);
    if (rootCause.getClass().getName().equals("reactor.netty.http.client.PrematureCloseException")) {
      return Optional.of("Failed to join server: connection closed before the session server responded");
    }

    if (rootCause instanceof IllegalArgumentException illegalArgumentException) {
      return Optional.ofNullable(illegalArgumentException.getMessage());
    }

    return Optional.empty();
  }

  private static <T extends Throwable> Optional<T> findCause(Throwable throwable, Class<T> type) {
    for (var current = throwable; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return Optional.of(type.cast(current));
      }
    }

    return Optional.empty();
  }

  private static Throwable rootCause(Throwable throwable) {
    var current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }

    return current;
  }

  public void disconnect(Component reason) {
    if (!shutdownExecuting.getAndSet(true)) {
      log.debug("Got Disconnected with reason: {}", PlainTextComponentSerializer.plainText().serialize(reason));
      SoulFireAPI.postEvent(new BotDisconnectedEvent(this, reason));

      if (minecraft.isRunning()) {
        try {
          minecraft.submit(() -> {
            if (minecraft.level != null) {
              minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
            }

            minecraft.disconnectWithProgressScreen();
          }).orTimeout(5, TimeUnit.SECONDS).join();
        } catch (Throwable _) {
        }

        minecraft.stop();
      }

      isDisconnected = true;

      // Run all shutdown hooks
      shutdownHooks.forEach(Runnable::run);

      // Shut down all executors
      scheduler.shutdown();
    }
  }

  public void invalidateSettingsCache() {
    if (settingsSource instanceof BotSettingsDelegate delegate) {
      delegate.invalidate();
    }
  }

  public void sendChatMessage(String message) {
    if (minecraft.player == null) {
      return;
    }

    try {
      var chatScreen = new ChatScreen("", false);
      chatScreen.init(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
      chatScreen.handleChatInput(message, false);
    } catch (NullPointerException e) {
      // Player may disconnect between our null check and ChatScreen accessing minecraft.player
      log.debug("Failed to send chat message, player likely disconnected", e);
    }
  }

  private record BotRunnableWrapper(BotConnection botConnection) implements SoulFireScheduler.RunnableWrapper {
    @Override
    public Runnable wrap(Runnable runnable) {
      return () -> {
        try (
          var ignored1 = SFHelpers.smartThreadLocalCloseable(CURRENT, botConnection);
          var ignored2 = SFHelpers.smartMDCCloseable(SFLogAppender.SF_BOT_ACCOUNT_ID, botConnection.accountProfileId().toString());
          var ignored3 = SFHelpers.smartMDCCloseable(SFLogAppender.SF_BOT_ACCOUNT_NAME, botConnection.accountName());
          var ignored4 = SFHelpers.smartThreadLocalCloseable(SFConstants.MINECRAFT_INSTANCE, botConnection.minecraft)) {
          runnable.run();
        }
      };
    }
  }
}
