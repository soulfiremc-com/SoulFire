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
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.UserApiService.UserProperties;
import com.mojang.authlib.yggdrasil.FriendsService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.response.FriendData;
import com.mojang.authlib.yggdrasil.response.PresenceResponse;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.TextInputManager;
import com.soulfiremc.mod.access.IMinecraft;
import com.soulfiremc.mod.access.ITextureManager;
import com.soulfiremc.mod.util.SFConstants;
import com.soulfiremc.mod.util.SFModHelpers;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.account.MinecraftAuthUserApiService;
import com.soulfiremc.server.account.service.BedrockData;
import com.soulfiremc.server.account.service.OfflineJavaData;
import com.soulfiremc.server.account.service.OnlineChainJavaData;
import com.soulfiremc.server.account.service.OnlineSimpleJavaData;
import com.soulfiremc.server.account.service.TheAlteningJavaData;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.bot.PreBotConnectEvent;
import com.soulfiremc.server.api.metadata.MetadataHolder;
import com.soulfiremc.server.pathfinding.NavigationWorldState;
import com.soulfiremc.server.proxy.ProxyAuthenticator;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.renderer.PovFrameTasks;
import com.soulfiremc.server.renderer.VulkanRenderer;
import com.soulfiremc.server.settings.lib.BotSettingsDelegate;
import com.soulfiremc.server.settings.lib.BotSettingsSource;
import com.soulfiremc.server.util.SFHelpers;
import com.soulfiremc.shared.SFLogAppender;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.Channel;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.ResourceLoadStateTracker;
import net.minecraft.client.User;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.player.LocalPlayerResolver;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.tutorial.Tutorial;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ContinuousProfiler;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Modifier;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter
public final class BotConnection {
  private static final Duration DISCONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final ScopedValue<BotConnection> CURRENT = ScopedValue.newInstance();
  private final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();
  private final Queue<Runnable> preTickHooks = new ConcurrentLinkedQueue<>();
  private final MetadataHolder<Object> metadata = new MetadataHolder<>();
  private final MetadataHolder<JsonElement> persistentMetadata;
  private final PovFrameTasks povFrameTasks = new PovFrameTasks();
  private final PovInputController povInput = new PovInputController(this);
  private final ControlState controlState = new ControlState();
  private final BotControlAPI botControl = new BotControlAPI();
  private final NavigationWorldState navigationWorldState =
    new NavigationWorldState();
  private final BotRotationController rotationControl;
  private final SoulFireScheduler scheduler;
  private final BotConnectionFactory factory;
  private final InstanceManager instanceManager;
  private final BotSettingsSource settingsSource;
  private final UUID connectionEpoch = UUID.randomUUID();
  private final UUID accountProfileId;
  private final String accountName;
  private final ServerAddress serverAddress;
  private final SoulFireScheduler.RunnableWrapper runnableWrapper;
  @Getter(AccessLevel.NONE)
  private final NetworkChannelTracker networkChannelTracker = new NetworkChannelTracker();
  @Getter(AccessLevel.NONE)
  private final AtomicReference<CompletableFuture<Void>> shutdownFuture = new AtomicReference<>();
  private final Minecraft minecraft;
  @Nullable
  private final SFProxy proxy;
  private final boolean isStatusPing;
  @Setter
  private ProtocolVersion currentProtocolVersion;
  @Nullable
  private volatile String disconnectReason;
  private volatile boolean isDisconnected;

  public static BotConnection current() {
    var current = CURRENT.isBound() ? CURRENT.get() : null;
    if (!SFConstants.NOT_REGISTRY_INIT_PHASE) {
      return current;
    }

    if (current == null) {
      new RuntimeException().printStackTrace();
    }
    return Objects.requireNonNull(current, "No bot connection in current thread");
  }

  public static Optional<BotConnection> currentOptional() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
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
    this.rotationControl = new BotRotationController(this);
    this.serverAddress = serverAddress;
    this.proxy = proxyData;
    this.minecraft = createMinecraftCopy(minecraftAccount);
    this.currentProtocolVersion = currentProtocolVersion;
    this.isStatusPing = isStatusPing;
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
    var javaProxy = ProxyAuthenticator.createProxy(proxy);
    var authSession = createAuthSession(minecraftAccount, javaProxy);
    var userApiService = authSession.userApiService();

    //noinspection DataFlowIssue
    newInstance.packetProcessor = new PacketProcessor(null); // Null until we spawn game thread
    newInstance.pendingRunnables = Queues.newConcurrentLinkedQueue();
    newInstance.running = true;
    newInstance.proxy = javaProxy;
    newInstance.user = new User(
      minecraftAccount.lastKnownName(),
      minecraftAccount.profileId(),
      authSession.accessToken(),
      Optional.empty(),
      Optional.empty()
    );
    newInstance.userApiService = userApiService;
    newInstance.userPropertiesFuture = fetchUserProperties(userApiService);
    newInstance.profileFuture = CompletableFuture.completedFuture(null);
    initializeBotOptions(newInstance);
    initializeBotTextureManager(newInstance);
    var friendsService = authSession.friendsService();
    newInstance.profileKeyPairManager =
      ProfileKeyPairManager.create(userApiService, newInstance.user, newInstance.gameDirectory.toPath());
    newInstance.reportingContext = ReportingContext.create(ReportEnvironment.local(), userApiService);
    newInstance.deltaTracker = new DeltaTracker.Timer(20.0F, 0L, newInstance::getTickTargetMillis);
    newInstance.reloadStateTracker = new ResourceLoadStateTracker();
    var userData = new GameConfig.UserData(newInstance.user, javaProxy);
    newInstance.downloadedPackSource = new DownloadedPackSource(
      newInstance,
      newInstance.gameDirectory.toPath().resolve("downloads"),
      userData
    );
    var skinTextureDownloader = new SkinTextureDownloader(javaProxy, newInstance.getTextureManager(), newInstance);
    newInstance.skinManager = new SkinManager(
      ((IMinecraft) newInstance).soulfire$getGameConfig().location.assetDirectory.toPath().resolve("skins"),
      newInstance.services(),
      skinTextureDownloader,
      newInstance
    );
    var localProfileResolver = new LocalPlayerResolver(newInstance, newInstance.services().profileResolver());
    newInstance.playerSkinRenderCache = new PlayerSkinRenderCache(newInstance.getTextureManager(), newInstance.getSkinManager(), localProfileResolver);

    VulkanRenderer.DEVICE_LOCK.lock();
    try (var ignored = SFHelpers.smartThreadLocalCloseable(SFConstants.MINECRAFT_INSTANCE, newInstance)) {
      initializeBotClientComponents(newInstance);
    } finally {
      VulkanRenderer.DEVICE_LOCK.unlock();
    }

    var remoteFriendListUpdateHandler = new RemoteFriendListUpdateHandler(friendsService, newInstance);
    newInstance.remoteFriendListUpdateHandler = remoteFriendListUpdateHandler;
    newInstance.playerSocialManager = new PlayerSocialManager(
      newInstance,
      userApiService,
      friendsService,
      remoteFriendListUpdateHandler);
    if (newInstance.playerSocialManager.isFriendListEnabled()) {
      remoteFriendListUpdateHandler.start();
    }
    newInstance.gui.chatListener().setMessageDelay(newInstance.options.chatDelay().get());
    shutdownHooks.add(remoteFriendListUpdateHandler::close);

    ((IMinecraft) newInstance).soulfire$setConnection(this);

    return newInstance;
  }

  private static CompletableFuture<UserProperties> fetchUserProperties(UserApiService userApiService) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return userApiService.fetchProperties();
      } catch (AuthenticationException e) {
        log.debug("Failed to fetch account properties", e);
        return UserApiService.OFFLINE_PROPERTIES;
      }
    }, Util.nonCriticalIoPool());
  }

  private static void initializeBotOptions(Minecraft minecraft) {
    var options = SFModHelpers.deepCopy(minecraft.options);
    // Options are copied shallowly; key mappings contain mutable pressed/click state.
    var mappings = new IdentityHashMap<KeyMapping, KeyMapping>();
    try {
      for (var field : Options.class.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) continue;
        if (field.getType() == KeyMapping.class) {
          field.setAccessible(true);
          var original = (KeyMapping) field.get(options);
          field.set(options, mappings.computeIfAbsent(original, SFModHelpers::deepCopy));
        } else if (field.getType() == KeyMapping[].class) {
          field.setAccessible(true);
          var originals = (KeyMapping[]) field.get(options);
          var copies = new KeyMapping[originals.length];
          for (var i = 0; i < originals.length; i++) copies[i] = mappings.computeIfAbsent(originals[i], SFModHelpers::deepCopy);
          field.set(options, copies);
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot isolate bot key bindings", e);
    }
    options.minecraft = minecraft;
    minecraft.options = options;
  }

  private static void initializeBotTextureManager(Minecraft minecraft) {
    VulkanRenderer.DEVICE_LOCK.lock();
    try {
      var sharedTextureManager = minecraft.getTextureManager();
      var textureManager = SFModHelpers.deepCopy(sharedTextureManager);
      textureManager.byPath = new HashMap<>(sharedTextureManager.byPath);
      textureManager.tickableTextures = new HashSet<>();
      ((ITextureManager) textureManager).soulfire$initializeBotCopy(sharedTextureManager);
      minecraft.textureManager = textureManager;
    } finally {
      VulkanRenderer.DEVICE_LOCK.unlock();
    }
  }

  private void initializeBotClientComponents(Minecraft minecraft) {
    minecraft.window = SFModHelpers.deepCopy(minecraft.getWindow());
    minecraft.window.eventHandler = minecraft;
    minecraft.textInputManager = new TextInputManager(minecraft.getWindow());
    minecraft.mouseHandler = new MouseHandler(minecraft);
    minecraft.keyboardHandler = new KeyboardHandler(minecraft);
    minecraft.narrator = new GameNarrator(minecraft);
    minecraft.tutorial = new Tutorial(minecraft, minecraft.options);
    minecraft.musicManager = new MusicManager(minecraft);
    minecraft.telemetryManager = new ClientTelemetryManager(
      minecraft,
      minecraft.userApiService,
      minecraft.user);
    minecraft.framerateLimitTracker = new FramerateLimitTracker(minecraft.options, minecraft);
    minecraft.fpsPieProfiler = new ContinuousProfiler(
      Util.timeSource(),
      () -> minecraft.fpsPieRenderTicks,
      minecraft.framerateLimitTracker::isHeavilyThrottled);
    minecraft.perTickGizmos = new SimpleGizmoCollector();
    minecraft.drainedLatestTickGizmos = new ArrayList<>();

    var particleResources = minecraft.particleEngine.resourceManager;
    minecraft.particleEngine = new ParticleEngine(null, particleResources);

    minecraft.mapTextureManager = new MapTextureManager(minecraft.getTextureManager());
    minecraft.mapRenderer = new MapRenderer(
      minecraft.getAtlasManager(),
      minecraft.mapTextureManager);

    var entityRenderDispatcher = SFModHelpers.deepCopy(minecraft.getEntityRenderDispatcher());
    entityRenderDispatcher.options = minecraft.options;
    entityRenderDispatcher.camera = null;
    entityRenderDispatcher.crosshairPickEntity = null;
    entityRenderDispatcher.textureManager = minecraft.getTextureManager();
    entityRenderDispatcher.mapRenderer = minecraft.getMapRenderer();
    entityRenderDispatcher.playerSkinRenderCache = minecraft.playerSkinRenderCache();
    var itemInHandRenderer = new ItemInHandRenderer(
      minecraft,
      entityRenderDispatcher,
      minecraft.getItemModelResolver());
    entityRenderDispatcher.itemInHandRenderer = itemInHandRenderer;
    minecraft.entityRenderDispatcher = entityRenderDispatcher;

    var gameRenderer = SFModHelpers.deepCopy(minecraft.gameRenderer);
    gameRenderer.minecraft = minecraft;
    gameRenderer.gameRenderState = new GameRenderState();
    gameRenderer.random = RandomSource.create();
    gameRenderer.itemInHandRenderer = itemInHandRenderer;
    gameRenderer.screenEffectRenderer = new ScreenEffectRenderer(minecraft, minecraft.getAtlasManager());
    gameRenderer.handAndScreenSubmitNodeStorage = new SubmitNodeStorage();
    gameRenderer.mainCamera = new Camera();
    minecraft.gameRenderer = gameRenderer;
    gameRenderer.lightmapRenderStateExtractor = new LightmapRenderStateExtractor(gameRenderer, minecraft);
    shutdownHooks.add(povFrameTasks::close);
    shutdownHooks.add(() -> VulkanRenderer.release(minecraft));

    var blockEntityRenderDispatcher = SFModHelpers.deepCopy(minecraft.getBlockEntityRenderDispatcher());
    blockEntityRenderDispatcher.cameraPos = Vec3.ZERO;
    blockEntityRenderDispatcher.entityRenderer = entityRenderDispatcher;
    blockEntityRenderDispatcher.playerSkinRenderCache = minecraft.playerSkinRenderCache();
    minecraft.blockEntityRenderDispatcher = blockEntityRenderDispatcher;

    entityRenderDispatcher.onResourceManagerReload(minecraft.getResourceManager());
    blockEntityRenderDispatcher.onResourceManagerReload(minecraft.getResourceManager());

    minecraft.levelExtractor = new LevelExtractor(
      minecraft,
      gameRenderer.gameRenderState().levelRenderState,
      minecraft.levelRenderer);
    var loadedSplashes = minecraft.gui.splashManager().splashes;
    var hud = new Hud(minecraft);
    // Bots join after resource reload; reuse the loaded styles from the original HUD.
    hud.waypointStyles = minecraft.gui.hud.getWaypointStyles();
    minecraft.gui = new Gui(
      minecraft,
      hud,
      gameRenderer.gameRenderState().guiRenderState);
    minecraft.gui.splashManager().splashes = loadedSplashes;

    shutdownHooks.add(minecraft.tutorial::stop);
    shutdownHooks.add(minecraft.particleEngine::clearParticles);
    shutdownHooks.add(minecraft.mapTextureManager::close);
    shutdownHooks.add(minecraft.getTextureManager()::close);
    shutdownHooks.add(minecraft.telemetryManager::close);
  }

  private AuthSession createAuthSession(MinecraftAccount minecraftAccount, Proxy javaProxy) {
    return switch (minecraftAccount.accountData()) {
      case BedrockData ignored -> new AuthSession(UserApiService.OFFLINE, OfflineFriendsService.INSTANCE, "bedrock");
      case OfflineJavaData ignored -> new AuthSession(UserApiService.OFFLINE, OfflineFriendsService.INSTANCE, "offline");
      case OnlineChainJavaData onlineChainJavaData -> {
        var authManager = onlineChainJavaData.getJavaAuthManager(proxy);
        yield new AuthSession(
          new MinecraftAuthUserApiService(authManager),
          OfflineFriendsService.INSTANCE,
          authManager.getMinecraftToken().getUpToDateUnchecked().getToken());
      }
      case OnlineSimpleJavaData onlineSimpleJavaData -> {
        var authService = new YggdrasilAuthenticationService(javaProxy);
        var accessToken = onlineSimpleJavaData.accessToken();
        yield new AuthSession(
          authService.createUserApiService(accessToken),
          authService.createFriendsService(accessToken),
          accessToken);
      }
      case TheAlteningJavaData theAlteningJavaData -> new AuthSession(
        UserApiService.OFFLINE,
        OfflineFriendsService.INSTANCE,
        theAlteningJavaData.accessToken());
    };
  }

  private record AuthSession(UserApiService userApiService, FriendsService friendsService, String accessToken) {}

  private enum OfflineFriendsService implements FriendsService {
    INSTANCE;

    @Override
    public ResultCode getFriendData(java.util.function.Consumer<FriendData> friendData) {
      friendData.accept(FriendData.empty());
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public ResultCode removeFriend(UUID playerID) {
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public ResultCode acceptIncomingFriendRequest(UUID id) {
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public ResultCode declineIncomingFriendRequest(UUID id) {
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public ResultCode sendFriendRequest(String name) {
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public ResultCode sendFriendRequest(UUID playerID) {
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public ResultCode revokeOutgoingFriendRequest(UUID id) {
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public ResultCode updateFriendSettings(boolean enableFriendlist, boolean enableFriendInvites) {
      return ResultCode.SERVICE_NOT_AVAILABLE;
    }

    @Override
    public PresenceResponse presence(String status) {
      return PresenceResponse.empty();
    }
  }

  public CompletableFuture<?> connect() {
    return scheduler.runAsync(
      () -> {
        SoulFireAPI.postEvent(new PreBotConnectEvent(this));
        var serverAddress = BotConnectionFactory.resolveLegacyAddress(
          this.serverAddress, currentProtocolVersion, ServerNameResolver.DEFAULT.redirectHandler);
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

              // renderFrame is cancelled in headless mode, so the vanilla frame limiter never runs
              FramerateLimiter.limitDisplayFPS(minecraft.getFramerateLimitTracker().getFramerateLimit());
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

  public void trackNetworkChannel(Channel channel) {
    networkChannelTracker.track(channel);
  }

  public CompletableFuture<Void> disconnect(Component reason) {
    var currentFuture = shutdownFuture.get();
    if (currentFuture != null) {
      return currentFuture;
    }

    var newFuture = new CompletableFuture<Void>();
    if (!shutdownFuture.compareAndSet(null, newFuture)) {
      return Objects.requireNonNull(shutdownFuture.get());
    }

    instanceManager.scheduler().execute(() -> {
      try {
        disconnectNow(reason);
        newFuture.complete(null);
      } catch (Throwable t) {
        newFuture.completeExceptionally(t);
      }
    });
    return newFuture;
  }

  private void disconnectNow(Component reason) throws InterruptedException, TimeoutException {
    disconnectReason = PlainTextComponentSerializer.plainText().serialize(reason);
    log.debug("Got Disconnected with reason: {}", disconnectReason);
    SoulFireAPI.postEvent(new BotDisconnectedEvent(this, reason));

    try {
      networkChannelTracker.closeAll(DISCONNECT_TIMEOUT);
      stopMinecraftClient();
      networkChannelTracker.closeAll(DISCONNECT_TIMEOUT);
      if (networkChannelTracker.hasOpenChannels()) {
        throw new IllegalStateException("Bot still has an open network channel after disconnecting");
      }

      isDisconnected = true;
    } finally {
      minecraft.stop();
      runShutdownHooks();
      scheduler.shutdown();
    }
  }

  private void stopMinecraftClient() {
    if (!minecraft.isRunning()) {
      return;
    }

    try {
      minecraft.submit(() -> {
        if (minecraft.level != null) {
          minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }

        minecraft.disconnectWithProgressScreen();
      }).get(DISCONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while cleaning up Minecraft client state for {}", accountName, e);
    } catch (ExecutionException | TimeoutException e) {
      log.warn("Failed to clean up Minecraft client state for {}", accountName, e);
    }
  }

  private void runShutdownHooks() {
    VulkanRenderer.DEVICE_LOCK.lock();
    try {
      runShutdownHooksLocked();
    } finally {
      VulkanRenderer.DEVICE_LOCK.unlock();
    }
  }

  private void runShutdownHooksLocked() {
    for (var shutdownHook : shutdownHooks) {
      try {
        shutdownHook.run();
      } catch (Throwable t) {
        log.error("Bot shutdown hook failed for {}", accountName, t);
      }
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

  public void sendPublicChatMessage(String message) {
    var connection = minecraft.getConnection();
    if (connection == null) {
      throw new IllegalStateException("Bot is not connected");
    }
    connection.sendChat(message);
  }

  public void sendCommand(String command) {
    var connection = minecraft.getConnection();
    if (connection == null) {
      throw new IllegalStateException("Bot is not connected");
    }
    connection.sendCommand(command);
  }

  private record BotRunnableWrapper(BotConnection botConnection) implements SoulFireScheduler.RunnableWrapper {
    @Override
    public Runnable wrap(Runnable runnable) {
      return () -> ScopedValue.where(CURRENT, botConnection).run(() -> {
        try (
          var _ = SFHelpers.smartMDCCloseable(SFLogAppender.SF_BOT_ACCOUNT_ID, botConnection.accountProfileId().toString());
          var _ = SFHelpers.smartMDCCloseable(SFLogAppender.SF_BOT_ACCOUNT_NAME, botConnection.accountName());
          var _ = SFHelpers.smartThreadLocalCloseable(SFConstants.MINECRAFT_INSTANCE, botConnection.minecraft)) {
          runnable.run();
        }
      });
    }
  }
}
