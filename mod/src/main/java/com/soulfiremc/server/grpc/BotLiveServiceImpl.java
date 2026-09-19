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
package com.soulfiremc.server.grpc;

import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.adventure.SoulFireAdventure;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.SoulFireEvent;
import com.soulfiremc.server.api.event.bot.BotBlockUpdateEvent;
import com.soulfiremc.server.api.event.bot.BotConnectedEvent;
import com.soulfiremc.server.api.event.bot.BotConnectionInitEvent;
import com.soulfiremc.server.api.event.bot.BotDamageEvent;
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.bot.BotOpenContainerEvent;
import com.soulfiremc.server.api.event.bot.BotPacketPreReceiveEvent;
import com.soulfiremc.server.api.event.bot.BotPostEntityTickEvent;
import com.soulfiremc.server.api.event.bot.BotPostTickEvent;
import com.soulfiremc.server.api.event.bot.ChatMessageReceiveEvent;
import com.soulfiremc.server.bot.BlockPredictionSupport;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotControlLeaseManager;
import com.soulfiremc.server.bot.BotInteractionSupport;
import com.soulfiremc.server.bot.CompletableControlTask;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.execution.BlockPlacementSupport;
import com.soulfiremc.server.user.PermissionContext;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/// BotLiveService is the remote-control API for SoulFire bots. It provides the
/// streaming event channel, imperative per-position / per-entity actions, world
/// queries, and pathfinding RPCs that make the public gRPC surface feel like a
/// mineflayer/azalea style bot library.
@Slf4j
@RequiredArgsConstructor
public final class BotLiveServiceImpl extends BotLiveServiceGrpc.BotLiveServiceImplBase {
  private static final int MAX_FIND_BLOCKS_DISTANCE = 128;
  private static final int MAX_FIND_BLOCKS_COUNT = 256;
  private static final float MAX_ENTITY_RADIUS = 128.0F;
  private static final float MAX_BLOCK_RADIUS = 64.0F;
  private static final long ENTITY_SCAN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
  private static final Duration DEFAULT_ACTION_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DIG_ACTION_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration DEFAULT_CHUNK_WAIT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration MAX_CHUNK_WAIT_TIMEOUT = Duration.ofMinutes(5);
  private static final int MAX_CHUNK_WAIT_RADIUS = 16;
  private static final int CHUNK_WAIT_POLL_MILLIS = 50;
  private static final Duration DEFAULT_PATH_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration MAX_PATH_TIMEOUT = Duration.ofHours(1);
  private static final int DEFAULT_HEARTBEAT_SECONDS = 15;
  private static final int MIN_HEARTBEAT_SECONDS = 5;
  private static final int MAX_HEARTBEAT_SECONDS = 60;
  private static final ConcurrentHashMap<ServerCallStreamObserver<BotEvent>, BotEventContext>
    EVENT_CONTEXTS = new ConcurrentHashMap<>();

  private final SoulFireServer soulFireServer;

  private static <T> T callInBotContext(BotConnection botConnection, Callable<T> callable) throws Exception {
    return botConnection.runnableWrapper().wrap(callable).call();
  }

  private static BotConnection requireOnlineBot(SoulFireServer soulFireServer, UUID instanceId, UUID botId) {
    var instance = soulFireServer.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
    var bot = instance.botConnections().get(botId);
    if (bot == null) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot '%s' is not online".formatted(botId))
        .asRuntimeException();
    }
    return bot;
  }

  private static BotConnection requireControlledOnlineBot(
    SoulFireServer soulFireServer,
    UUID instanceId,
    UUID botId
  ) {
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(
        InstancePermission.CONTROL_BOT_ACTIONS,
        instanceId));
    var instance = requireConfiguredBot(soulFireServer, instanceId, botId);
    try {
      instance.botControlLeaseManager().authorize(
        botId,
        ServerRPCConstants.BOT_CONTROL_TOKEN_CONTEXT_KEY.get());
    } catch (BotControlLeaseManager.InvalidLeaseException e) {
      throw Status.PERMISSION_DENIED
        .withDescription(e.getMessage())
        .asRuntimeException();
    }
    var bot = instance.botConnections().get(botId);
    if (bot == null || bot.isDisconnected()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot '%s' is not online".formatted(botId))
        .asRuntimeException();
    }
    return bot;
  }

  private static <T> void submitAction(
    BotConnection bot,
    ControlTask delegate,
    Duration timeout,
    Function<BotActionResult, T> responseFactory,
    StreamObserver<T> responseObserver
  ) {
    var task = new CompletableControlTask(delegate);
    var serverObserver = (ServerCallStreamObserver<T>) responseObserver;
    serverObserver.setOnCancelHandler(() -> bot.botControl().cancel(task));
    try {
      callInBotContext(bot, () -> {
        bot.botControl().replace(task);
        return null;
      });
    } catch (Throwable t) {
      responseObserver.onError(toGrpcError("Failed to submit bot action", t));
      return;
    }

    task.completion()
      .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
      .whenComplete((reason, error) -> {
        if (serverObserver.isCancelled()) {
          return;
        }
        if (unwrapAsyncError(error) instanceof TimeoutException) {
          bot.botControl().cancel(task);
        }
        var result = buildActionResult(task, reason, error);
        synchronized (serverObserver) {
          if (serverObserver.isCancelled()) {
            return;
          }
          serverObserver.onNext(responseFactory.apply(result));
          serverObserver.onCompleted();
        }
      });
  }

  private static BotActionResult buildActionResult(
    CompletableControlTask task,
    @Nullable ControlStopReason reason,
    @Nullable Throwable error
  ) {
    var builder = BotActionResult.newBuilder().setActionId(task.actionId().toString());
    if (error != null) {
      var cause = unwrapAsyncError(error);
      return builder
        .setStatus(BotActionStatus.BOT_ACTION_STATUS_FAILED)
        .setError(Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName()))
        .build();
    }
    return builder
      .setStatus(switch (Objects.requireNonNull(reason)) {
        case COMPLETED, CLAIMED -> BotActionStatus.BOT_ACTION_STATUS_COMPLETED;
        case CANCELLED, REPLACED -> BotActionStatus.BOT_ACTION_STATUS_CANCELLED;
        case FAILED -> BotActionStatus.BOT_ACTION_STATUS_FAILED;
      })
      .build();
  }

  private static @Nullable Throwable unwrapAsyncError(@Nullable Throwable error) {
    var current = error;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
      && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static RuntimeException toGrpcError(String message, Throwable throwable) {
    var cause = Objects.requireNonNull(unwrapAsyncError(throwable));
    if (cause instanceof io.grpc.StatusRuntimeException statusError) {
      return statusError;
    }
    return Status.INTERNAL
      .withDescription(message + ": " + Objects.requireNonNullElse(
        cause.getMessage(),
        cause.getClass().getSimpleName()))
      .withCause(cause)
      .asRuntimeException();
  }

  private static InteractionHand toMcHand(Hand hand) {
    return switch (hand) {
      case HAND_OFF -> InteractionHand.OFF_HAND;
      case HAND_MAIN, HAND_UNSPECIFIED, UNRECOGNIZED -> InteractionHand.MAIN_HAND;
    };
  }

  private static Direction toMcDirection(BlockFace face) {
    return switch (face) {
      case BLOCK_FACE_DOWN -> Direction.DOWN;
      case BLOCK_FACE_UP -> Direction.UP;
      case BLOCK_FACE_NORTH -> Direction.NORTH;
      case BLOCK_FACE_SOUTH -> Direction.SOUTH;
      case BLOCK_FACE_WEST -> Direction.WEST;
      case BLOCK_FACE_EAST -> Direction.EAST;
      case BLOCK_FACE_UNSPECIFIED, UNRECOGNIZED ->
        throw Status.INVALID_ARGUMENT.withDescription("block face must be specified").asRuntimeException();
    };
  }

  private static BlockPosition toProtoBlockPosition(BlockPos pos, String dimension) {
    return BlockPosition.newBuilder()
      .setX(pos.getX())
      .setY(pos.getY())
      .setZ(pos.getZ())
      .setDimension(dimension)
      .build();
  }

  private static BlockPos toMcBlockPos(BlockPosition pos) {
    return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
  }

  private static com.soulfiremc.grpc.generated.BlockState buildBlockState(BlockPos pos, BlockState state, String dimension) {
    var builder = com.soulfiremc.grpc.generated.BlockState.newBuilder()
      .setPosition(toProtoBlockPosition(pos, dimension))
      .setBlockId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    for (var property : state.getProperties()) {
      @SuppressWarnings({"rawtypes", "unchecked"})
      var name = property.getName();
      builder.putProperties(name, getPropertyValueAsString(state, property));
    }
    return builder.build();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String getPropertyValueAsString(BlockState state, Property property) {
    return property.getName(state.getValue(property));
  }

  private static WorldPosition buildWorldPosition(Vec3 pos, String dimension) {
    return WorldPosition.newBuilder()
      .setX(pos.x)
      .setY(pos.y)
      .setZ(pos.z)
      .setDimension(dimension)
      .build();
  }

  private static NearbyEntity buildNearbyEntity(Entity entity, Vec3 relativeTo, String dimension) {
    var builder = NearbyEntity.newBuilder()
      .setEntityId(entity.getId())
      .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
      .setPosition(buildWorldPosition(entity.position(), dimension))
      .setDistance((float) Math.sqrt(entity.position().distanceToSqr(relativeTo)))
      .setIsPlayer(entity instanceof Player);
    var customName = entity.getCustomName();
    if (customName != null) {
      builder.setDisplayName(customName.getString());
    } else if (entity instanceof Player player) {
      builder.setDisplayName(player.getGameProfile().name());
    }
    if (entity instanceof LivingEntity living) {
      builder.setHealth(living.getHealth());
    }
    return builder.build();
  }

  // =====================================================================
  // WatchBotEvents
  // =====================================================================

  @Override
  public void watchBotEvents(WatchBotEventsRequest request, StreamObserver<BotEvent> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    var instance = requireConfiguredBot(soulFireServer, instanceId, botId);
    var filter = request.getFilter();
    var serverObserver = (ServerCallStreamObserver<BotEvent>) responseObserver;
    var closed = new AtomicBoolean(false);
    var eventContext = new BotEventContext(botId);
    EVENT_CONTEXTS.put(serverObserver, eventContext);
    var lastState = new AtomicReference<BotLiveState>(null);
    var lastInventory = new AtomicReference<BotInventoryStateResponse>(null);
    var lastEntities = new AtomicReference<Map<Integer, ObservedEntity>>(Map.of());
    var lastEntityScan = new AtomicLong();
    var spawnedConnection = new AtomicReference<BotConnection>(null);
    var dead = new AtomicReference<Boolean>(null);
    var cleanupActions = new CopyOnWriteArrayList<Runnable>();
    Runnable cleanup = () -> {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      cleanupActions.forEach(action -> {
        try {
          action.run();
        } catch (Throwable t) {
          log.debug("Failed to clean up bot event subscription", t);
        }
      });
      EVENT_CONTEXTS.remove(serverObserver);
    };
    serverObserver.setOnCancelHandler(cleanup);
    serverObserver.setOnReadyHandler(() -> {
      if (!eventContext.consumeDropped()) {
        return;
      }
      lastState.set(null);
      lastInventory.set(null);
      lastEntities.set(Map.of());
      emitBotEvent(serverObserver, closed, BotEvent.newBuilder()
        .setResyncRequired(BotResyncRequired.newBuilder()
          .setReason("Events were dropped because the consumer could not keep up"))
        .build());
    });

    if (request.getAfterSequence() > 0 || request.hasStreamEpoch()) {
      var resync = BotResyncRequired.newBuilder()
        .setReason("The requested event position is no longer retained")
        .setRequestedAfterSequence(request.getAfterSequence());
      if (request.hasStreamEpoch()) {
        resync.setRequestedEpoch(request.getStreamEpoch());
      }
      emitBotEvent(serverObserver, closed, BotEvent.newBuilder()
        .setResyncRequired(resync)
        .build());
    }

    scheduleHeartbeat(
      serverObserver,
      closed,
      normalizedHeartbeatSeconds(request.getHeartbeatIntervalSeconds()));

    emitBotEvent(serverObserver, closed, BotEvent.newBuilder()
      .setStatus(instance.botStateManager().status(botId))
      .build());

    var current = instance.botConnections().get(botId);
    if (current != null && !current.isDisconnected()) {
      emitCurrentSnapshot(
        current,
        filter,
        serverObserver,
        closed,
        lastState,
        lastInventory,
        spawnedConnection,
        dead);
      if (filter.getIncludeEntityEvents()) {
        emitEntityChanges(
          current,
          filter,
          serverObserver,
          closed,
          lastEntities,
          true);
      }
      emitCurrentAuxiliarySnapshots(
        current,
        filter,
        serverObserver,
        closed);
    }

    var removeStatusListener = instance.botStateManager().addStatusListener(event -> {
      if (closed.get()) {
        return;
      }
      if (event.removedBotId() != null && event.removedBotId().equals(botId)) {
        synchronized (serverObserver) {
          if (!closed.get() && !serverObserver.isCancelled()) {
            serverObserver.onCompleted();
          }
        }
        cleanup.run();
        return;
      }
      if (event.status() != null && event.status().getProfileId().equals(botId.toString())) {
        emitBotEvent(serverObserver, closed, BotEvent.newBuilder()
          .setStatus(event.status())
          .build());
      }
    });
    cleanupActions.add(removeStatusListener);

    Consumer<BotConnectionInitEvent> connectionInitListener = event -> {
      if (!matches(event.connection(), instance, botId)) {
        return;
      }
      lastState.set(null);
      lastInventory.set(null);
      lastEntities.set(Map.of());
      spawnedConnection.set(null);
      dead.set(null);
      eventContext.newEpoch();
      if (filter.getIncludeLifecycle()) {
        emitLifecycle(
          serverObserver,
          closed,
          BotLifecycleKind.BOT_LIFECYCLE_CONNECTING,
          null);
      }
    };
    register(cleanupActions, BotConnectionInitEvent.class, connectionInitListener);

    Consumer<BotConnectedEvent> connectedListener = event -> {
      if (filter.getIncludeLifecycle() && matches(event.connection(), instance, botId)) {
        emitLifecycle(
          serverObserver,
          closed,
          BotLifecycleKind.BOT_LIFECYCLE_CONNECTED,
          null);
      }
    };
    register(cleanupActions, BotConnectedEvent.class, connectedListener);

    Consumer<BotPostTickEvent> stateListener = event -> {
      var connection = event.connection();
      if (!matches(connection, instance, botId)) {
        return;
      }
      emitCurrentSnapshot(
        connection,
        filter,
        serverObserver,
        closed,
        lastState,
        lastInventory,
        spawnedConnection,
        dead);
    };
    register(cleanupActions, BotPostTickEvent.class, stateListener);

    Consumer<BotDisconnectedEvent> disconnectListener = event -> {
      if (!matches(event.connection(), instance, botId)) {
        return;
      }
      if (filter.getIncludeLifecycle()) {
        var reason = event.message() == null
          ? null
          : SoulFireAdventure.PLAIN_MESSAGE_SERIALIZER.serialize(event.message());
        emitLifecycle(
          serverObserver,
          closed,
          BotLifecycleKind.BOT_LIFECYCLE_DISCONNECTED,
          reason);
      }
      lastState.set(null);
      lastInventory.set(null);
      lastEntities.set(Map.of());
      spawnedConnection.set(null);
      dead.set(null);
    };
    register(cleanupActions, BotDisconnectedEvent.class, disconnectListener);

    if (filter.getIncludeChat()) {
      Consumer<ChatMessageReceiveEvent> chatListener = event -> {
        if (!matches(event.connection(), instance, botId)) {
          return;
        }
        var received = Instant.ofEpochMilli(event.timestamp());
        var chat = BotChatEvent.newBuilder()
          .setSource(ChatSource.CHAT_SOURCE_UNKNOWN)
          .setPlainText(event.parseToPlainText())
          .setJsonComponent(GsonComponentSerializer.gson().serialize(event.message()))
          .setReceivedAt(Timestamp.newBuilder()
            .setSeconds(received.getEpochSecond())
            .setNanos(received.getNano())
            .build())
          .build();
        emitBotEvent(serverObserver, closed, BotEvent.newBuilder().setChat(chat).build());
      };
      register(cleanupActions, ChatMessageReceiveEvent.class, chatListener);
    }

    if (includesPacketEvents(filter)) {
      Consumer<BotPacketPreReceiveEvent> packetListener = event -> {
        if (!matches(event.connection(), instance, botId) || event.packet() == null) {
          return;
        }
        try {
          emitTypedPacketEvent(
            event.connection(),
            filter,
            event.packet(),
            serverObserver,
            closed);
        } catch (Throwable t) {
          log.debug("Failed to map typed bot packet event", t);
        }
      };
      register(cleanupActions, BotPacketPreReceiveEvent.class, packetListener);
    }

    if (filter.getIncludeEntityEvents()) {
      Consumer<BotPostEntityTickEvent> entityListener = event -> {
        if (!matches(event.connection(), instance, botId)) {
          return;
        }
        var now = System.nanoTime();
        var previousScan = lastEntityScan.get();
        if (now - previousScan < ENTITY_SCAN_INTERVAL_NANOS
          || !lastEntityScan.compareAndSet(previousScan, now)) {
          return;
        }
        emitEntityChanges(
          event.connection(),
          filter,
          serverObserver,
          closed,
          lastEntities,
          false);
      };
      register(cleanupActions, BotPostEntityTickEvent.class, entityListener);
    }

    if (filter.getIncludeBlockUpdates()) {
      var radius = normalizedRadius(filter.getBlockRadius(), 16.0F, MAX_BLOCK_RADIUS);
      Consumer<BotBlockUpdateEvent> blockListener = event -> {
        if (!matches(event.connection(), instance, botId)
          || !withinRadius(event.connection(), event.position(), radius)) {
          return;
        }
        var dimension = currentDimension(event.connection());
        var level = event.connection().minecraft().level;
        var update = com.soulfiremc.grpc.generated.BotBlockUpdateEvent.newBuilder()
          .setPosition(toProtoBlockPosition(event.position(), dimension))
          .setOldBlockId(BuiltInRegistries.BLOCK.getKey(event.previousState().getBlock()).toString())
          .setNewBlockId(BuiltInRegistries.BLOCK.getKey(event.state().getBlock()).toString());
        if (level != null) {
          update.setBlock(MinecraftDomainMapper.block(
            level,
            event.position(),
            event.state(),
            true,
            false));
        }
        emitBotEvent(serverObserver, closed, BotEvent.newBuilder()
          .setBlockUpdate(update)
          .build());
      };
      register(cleanupActions, BotBlockUpdateEvent.class, blockListener);
    }

    if (filter.getIncludeDamage()) {
      Consumer<BotDamageEvent> damageListener = event -> {
        if (!matches(event.connection(), instance, botId)) {
          return;
        }
        var damage = com.soulfiremc.grpc.generated.BotDamageEvent.newBuilder()
          .setPreviousHealth(event.previousHealth())
          .setHealth(event.newHealth())
          .setAmount(event.damageAmount())
          .build();
        emitBotEvent(serverObserver, closed, BotEvent.newBuilder().setDamage(damage).build());
      };
      register(cleanupActions, BotDamageEvent.class, damageListener);
    }

    if (filter.getIncludeInventory()) {
      Consumer<BotOpenContainerEvent> containerListener = event -> {
        if (matches(event.connection(), instance, botId)) {
          emitCurrentInventory(
            event.connection(),
            serverObserver,
            closed,
            lastInventory);
        }
      };
      register(cleanupActions, BotOpenContainerEvent.class, containerListener);
    }
  }

  private static InstanceManager requireConfiguredBot(
    SoulFireServer soulFireServer,
    UUID instanceId,
    UUID botId
  ) {
    var instance = soulFireServer.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
    if (!instance.settingsSource().accounts().containsKey(botId)) {
      throw Status.NOT_FOUND
        .withDescription("Bot '%s' is not configured".formatted(botId))
        .asRuntimeException();
    }
    return instance;
  }

  private static boolean matches(BotConnection connection, InstanceManager instance, UUID botId) {
    return connection.instanceManager() == instance
      && connection.accountProfileId().equals(botId);
  }

  private static <E extends SoulFireEvent> void register(
    List<Runnable> cleanupActions,
    Class<E> eventType,
    Consumer<E> listener
  ) {
    SoulFireAPI.registerListener(eventType, listener);
    cleanupActions.add(() -> SoulFireAPI.unregisterListener(eventType, listener));
  }

  private static void emitBotEvent(
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    BotEvent event
  ) {
    synchronized (observer) {
      if (closed.get() || observer.isCancelled()) {
        return;
      }
      var context = EVENT_CONTEXTS.get(observer);
      if (context == null) {
        return;
      }
      if (!observer.isReady()) {
        context.markDropped();
        return;
      }
      observer.onNext(context.decorate(event));
    }
  }

  private void scheduleHeartbeat(
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    int intervalSeconds
  ) {
    soulFireServer.scheduler().schedule(new Runnable() {
      @Override
      public void run() {
        if (closed.get() || observer.isCancelled()) {
          return;
        }
        emitBotEvent(observer, closed, BotEvent.newBuilder()
          .setHeartbeat(BotHeartbeat.getDefaultInstance())
          .build());
        soulFireServer.scheduler().schedule(this, intervalSeconds, TimeUnit.SECONDS);
      }
    }, intervalSeconds, TimeUnit.SECONDS);
  }

  private static int normalizedHeartbeatSeconds(int requested) {
    if (requested == 0) {
      return DEFAULT_HEARTBEAT_SECONDS;
    }
    return Math.max(MIN_HEARTBEAT_SECONDS, Math.min(requested, MAX_HEARTBEAT_SECONDS));
  }

  private static void emitLifecycle(
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    BotLifecycleKind kind,
    String message
  ) {
    var lifecycle = BotLifecycleEvent.newBuilder().setKind(kind);
    if (message != null && !message.isBlank()) {
      lifecycle.setMessage(message);
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setLifecycle(lifecycle)
      .build());
  }

  private static void emitCurrentSnapshot(
    BotConnection connection,
    BotEventFilter filter,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    AtomicReference<BotLiveState> lastState,
    AtomicReference<BotInventoryStateResponse> lastInventory,
    AtomicReference<BotConnection> spawnedConnection,
    AtomicReference<Boolean> dead
  ) {
    try {
      var snapshot = callInBotContext(connection, () -> {
        var minecraft = connection.minecraft();
        var player = minecraft.player;
        if (player == null) {
          return null;
        }
        return new TickSnapshot(
          BotServiceImpl.buildLiveStatePublic(minecraft, player, false),
          filter.getIncludeInventory()
            ? BotServiceImpl.buildInventoryStatePublic(minecraft, player, false)
            : null,
          player.isDeadOrDying());
      });
      if (snapshot == null) {
        return;
      }

      var previousState = lastState.getAndSet(snapshot.state());
      if (previousState == null) {
        emitBotEvent(observer, closed, BotEvent.newBuilder()
          .setSnapshot(snapshot.state())
          .build());
      } else if (filter.getIncludeStateDeltas()) {
        var delta = computeDelta(previousState, snapshot.state());
        if (delta != null) {
          emitBotEvent(observer, closed, BotEvent.newBuilder()
            .setStateDelta(delta)
            .build());
        }
      }

      if (filter.getIncludeLifecycle()
        && spawnedConnection.getAndSet(connection) != connection) {
        emitLifecycle(observer, closed, BotLifecycleKind.BOT_LIFECYCLE_SPAWNED, null);
      }

      var previousDead = dead.getAndSet(snapshot.dead());
      if (filter.getIncludeLifecycle()
        && previousDead != null
        && previousDead != snapshot.dead()) {
        emitLifecycle(
          observer,
          closed,
          snapshot.dead()
            ? BotLifecycleKind.BOT_LIFECYCLE_DIED
            : BotLifecycleKind.BOT_LIFECYCLE_RESPAWNED,
          null);
      }

      if (snapshot.inventory() != null) {
        var previousInventory = lastInventory.getAndSet(snapshot.inventory());
        if (!snapshot.inventory().equals(previousInventory)) {
          emitInventory(observer, closed, snapshot.inventory());
        }
      }
    } catch (Throwable t) {
      log.debug("Failed to emit current bot snapshot", t);
    }
  }

  private static void emitCurrentInventory(
    BotConnection connection,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    AtomicReference<BotInventoryStateResponse> lastInventory
  ) {
    try {
      var inventory = callInBotContext(connection, () -> {
        var minecraft = connection.minecraft();
        var player = minecraft.player;
        return player == null
          ? null
          : BotServiceImpl.buildInventoryStatePublic(minecraft, player, false);
      });
      if (inventory != null && !inventory.equals(lastInventory.getAndSet(inventory))) {
        emitInventory(observer, closed, inventory);
      }
    } catch (Throwable t) {
      log.debug("Failed to emit current bot inventory", t);
    }
  }

  private static void emitInventory(
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    BotInventoryStateResponse inventory
  ) {
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setInventory(BotInventoryEvent.newBuilder().setState(inventory))
      .build());
  }

  private static void emitEntityChanges(
    BotConnection connection,
    BotEventFilter filter,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    AtomicReference<Map<Integer, ObservedEntity>> lastEntities,
    boolean initial
  ) {
    try {
      var radius = normalizedRadius(filter.getEntityRadius(), 32.0F, MAX_ENTITY_RADIUS);
      var next = callInBotContext(connection, () -> observedEntities(connection, radius));
      var previous = lastEntities.getAndSet(next);
      for (var entry : next.entrySet()) {
        var prior = previous.get(entry.getKey());
        if (prior == null || !prior.equals(entry.getValue())) {
          var kind = prior == null
            ? EntityEventKind.ENTITY_EVENT_SPAWN
            : EntityEventKind.ENTITY_EVENT_UPDATE;
          emitEntityEvent(observer, closed, kind, entry.getValue(), true);
        }
      }
      if (!initial) {
        for (var entry : previous.entrySet()) {
          if (!next.containsKey(entry.getKey())) {
            emitEntityEvent(
              observer,
              closed,
              EntityEventKind.ENTITY_EVENT_DESPAWN,
              entry.getValue(),
              false);
          }
        }
      }
    } catch (Throwable t) {
      log.debug("Failed to emit entity changes", t);
    }
  }

  private static Map<Integer, ObservedEntity> observedEntities(
    BotConnection connection,
    float radius
  ) {
    var minecraft = connection.minecraft();
    var player = minecraft.player;
    var level = minecraft.level;
    if (player == null || level == null) {
      return Map.of();
    }
    var radiusSquared = radius * radius;
    var dimension = level.dimension().identifier().toString();
    var entities = new HashMap<Integer, ObservedEntity>();
    for (var entity : level.entitiesForRendering()) {
      if (entity == player || entity.distanceToSqr(player) > radiusSquared) {
        continue;
      }
      entities.put(
        entity.getId(),
        new ObservedEntity(
          buildNearbyEntity(entity, player.position(), dimension),
          MinecraftDomainMapper.entity(connection, entity)));
    }
    return Map.copyOf(entities);
  }

  private static void emitEntityEvent(
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed,
    EntityEventKind kind,
    ObservedEntity entity,
    boolean includeSnapshot
  ) {
    var event = BotEntityEvent.newBuilder()
      .setKind(kind)
      .setEntity(entity.summary());
    if (includeSnapshot) {
      event.setSnapshot(entity.snapshot());
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setEntityEvent(event)
      .build());
  }

  private static boolean includesPacketEvents(BotEventFilter filter) {
    return filter.getIncludeEnvironment()
      || filter.getIncludePlayerList()
      || filter.getIncludeBossBars()
      || filter.getIncludeSounds()
      || filter.getIncludeParticles()
      || filter.getIncludeScoreboard()
      || filter.getIncludeResourcePacks()
      || filter.getIncludeTitles()
      || filter.getIncludeChunks();
  }

  private static void emitCurrentAuxiliarySnapshots(
    BotConnection connection,
    BotEventFilter filter,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    try {
      var events = callInBotContext(connection, () -> {
        var minecraft = connection.minecraft();
        var snapshots = new ArrayList<BotEvent>();
        if (filter.getIncludeEnvironment() && minecraft.level != null) {
          snapshots.add(BotEvent.newBuilder()
            .setEnvironment(BotEnvironmentEvent.newBuilder()
              .setTime(BotTimeEvent.newBuilder()
                .setGameTime(minecraft.level.getGameTime())))
            .build());
          snapshots.add(BotEvent.newBuilder()
            .setEnvironment(BotEnvironmentEvent.newBuilder()
              .setWeather(BotWeatherEvent.newBuilder()
                .setKind(minecraft.level.isRaining()
                  ? WeatherEventKind.WEATHER_EVENT_STARTED_RAINING
                  : WeatherEventKind.WEATHER_EVENT_STOPPED_RAINING)))
            .build());
          snapshots.add(BotEvent.newBuilder()
            .setEnvironment(BotEnvironmentEvent.newBuilder()
              .setWeather(BotWeatherEvent.newBuilder()
                .setKind(WeatherEventKind.WEATHER_EVENT_RAIN_LEVEL_CHANGED)
                .setLevel(minecraft.level.getRainLevel(1.0F))))
            .build());
          snapshots.add(BotEvent.newBuilder()
            .setEnvironment(BotEnvironmentEvent.newBuilder()
              .setWeather(BotWeatherEvent.newBuilder()
                .setKind(WeatherEventKind.WEATHER_EVENT_THUNDER_LEVEL_CHANGED)
                .setLevel(minecraft.level.getThunderLevel(1.0F))))
            .build());
        }
        var listener = minecraft.getConnection();
        if (filter.getIncludePlayerList() && listener != null) {
          var listed = listener.getListedOnlinePlayers().stream()
            .map(info -> info.getProfile().id())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
          var playerList = BotPlayerListEvent.newBuilder()
            .setKind(PlayerListEventKind.PLAYER_LIST_EVENT_UPSERT);
          listener.getOnlinePlayers().stream()
            .sorted(Comparator.comparing(info -> info.getProfile().id()))
            .map(info -> {
              var player = PlayerListEntrySnapshot.newBuilder()
                .setProfileId(info.getProfile().id().toString())
                .setProfileName(info.getProfile().name())
                .setListed(listed.contains(info.getProfile().id()))
                .setLatencyMs(info.getLatency())
                .setGameMode(toProtoGameMode(info.getGameMode()))
                .setShowHat(info.showHat())
                .setListOrder(info.getTabListOrder())
                .addChangedFields("snapshot");
              if (info.getTabListDisplayName() != null) {
                player.setDisplayName(
                  MinecraftDomainMapper.text(info.getTabListDisplayName()));
              }
              return player;
            })
            .forEach(playerList::addEntries);
          snapshots.add(BotEvent.newBuilder()
            .setPlayerList(playerList)
            .build());
        }
        return List.copyOf(snapshots);
      });
      events.forEach(event -> emitBotEvent(observer, closed, event));
    } catch (Throwable t) {
      log.debug("Failed to emit current auxiliary bot snapshots", t);
    }
  }

  private static void emitTypedPacketEvent(
    BotConnection connection,
    BotEventFilter filter,
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    if (filter.getIncludeTitles()
      && emitTitlePacket(packet, observer, closed)) {
      return;
    }
    if (filter.getIncludeChunks()
      && emitChunkPacket(connection, packet, observer, closed)) {
      return;
    }
    if (filter.getIncludeEnvironment()
      && emitEnvironmentPacket(packet, observer, closed)) {
      return;
    }
    if (filter.getIncludePlayerList()
      && emitPlayerListPacket(packet, observer, closed)) {
      return;
    }
    if (filter.getIncludeBossBars()
      && packet instanceof ClientboundBossEventPacket bossEventPacket) {
      emitBossBarPacket(bossEventPacket, observer, closed);
      return;
    }
    if (filter.getIncludeSounds()
      && emitSoundPacket(connection, packet, observer, closed)) {
      return;
    }
    if (filter.getIncludeParticles()
      && packet instanceof ClientboundLevelParticlesPacket particlePacket) {
      var particle = BotParticleEvent.newBuilder()
        .setParticleId(BuiltInRegistries.PARTICLE_TYPE
          .getKey(particlePacket.particle().getType()).toString())
        .setPosition(WorldPosition.newBuilder()
          .setX(particlePacket.x())
          .setY(particlePacket.y())
          .setZ(particlePacket.z())
          .setDimension(currentDimension(connection)))
        .setOffset(com.soulfiremc.grpc.generated.Vec3.newBuilder()
          .setX(particlePacket.xDist())
          .setY(particlePacket.yDist())
          .setZ(particlePacket.zDist()))
        .setMaxSpeed(Math.max(particlePacket.xMaxSpeed(), Math.max(particlePacket.yMaxSpeed(), particlePacket.zMaxSpeed())))
        .setCount(particlePacket.count())
        .setAlwaysShow(particlePacket.alwaysShow())
        .setOverrideLimiter(particlePacket.overrideLimiter())
        .setOptions(particlePacket.particle().toString());
      emitBotEvent(observer, closed, BotEvent.newBuilder()
        .setParticle(particle)
        .build());
      return;
    }
    if (filter.getIncludeScoreboard()) {
      if (emitScoreboardPacket(packet, observer, closed)) {
        return;
      }
    }
    if (filter.getIncludeResourcePacks()) {
      emitResourcePackPacket(packet, observer, closed);
    }
  }

  private static boolean emitTitlePacket(
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    var title = BotTitleEvent.newBuilder();
    if (packet instanceof ClientboundSetTitleTextPacket titlePacket) {
      title
        .setKind(TitleEventKind.TITLE_EVENT_TITLE)
        .setText(MinecraftDomainMapper.text(titlePacket.text()));
    } else if (packet instanceof ClientboundSetSubtitleTextPacket subtitlePacket) {
      title
        .setKind(TitleEventKind.TITLE_EVENT_SUBTITLE)
        .setText(MinecraftDomainMapper.text(subtitlePacket.text()));
    } else if (packet instanceof ClientboundSetTitlesAnimationPacket animationPacket) {
      title
        .setKind(TitleEventKind.TITLE_EVENT_TIMES)
        .setFadeInTicks(animationPacket.getFadeIn())
        .setStayTicks(animationPacket.getStay())
        .setFadeOutTicks(animationPacket.getFadeOut());
    } else if (packet instanceof ClientboundClearTitlesPacket clearPacket) {
      title.setKind(clearPacket.shouldResetTimes()
        ? TitleEventKind.TITLE_EVENT_RESET
        : TitleEventKind.TITLE_EVENT_CLEAR);
    } else {
      return false;
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setTitle(title)
      .build());
    return true;
  }

  private static boolean emitChunkPacket(
    BotConnection connection,
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    var chunk = BotChunkEvent.newBuilder()
      .setDimension(currentDimension(connection));
    if (packet instanceof ClientboundLevelChunkWithLightPacket loadPacket) {
      chunk
        .setKind(ChunkEventKind.CHUNK_EVENT_LOAD)
        .setChunkX(loadPacket.x())
        .setChunkZ(loadPacket.z());
    } else if (packet instanceof ClientboundForgetLevelChunkPacket unloadPacket) {
      chunk
        .setKind(ChunkEventKind.CHUNK_EVENT_UNLOAD)
        .setChunkX(unloadPacket.pos().x())
        .setChunkZ(unloadPacket.pos().z());
    } else {
      return false;
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setChunk(chunk)
      .build());
    return true;
  }

  private static boolean emitEnvironmentPacket(
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    if (packet instanceof ClientboundSetTimePacket timePacket) {
      var time = BotTimeEvent.newBuilder().setGameTime(timePacket.gameTime());
      timePacket.clockUpdates().entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().getRegisteredName()))
        .forEach(entry -> time.addClocks(ClockSnapshot.newBuilder()
          .setClockId(entry.getKey().getRegisteredName())
          .setTotalTicks(entry.getValue().totalTicks())
          .setPartialTick(entry.getValue().partialTick())
          .setRate(entry.getValue().rate())));
      emitBotEvent(observer, closed, BotEvent.newBuilder()
        .setEnvironment(BotEnvironmentEvent.newBuilder().setTime(time))
        .build());
      return true;
    }
    if (!(packet instanceof ClientboundGameEventPacket gameEventPacket)) {
      return false;
    }
    var type = gameEventPacket.getEvent();
    var environment = BotEnvironmentEvent.newBuilder();
    if (type == ClientboundGameEventPacket.START_RAINING) {
      environment.setWeather(BotWeatherEvent.newBuilder()
        .setKind(WeatherEventKind.WEATHER_EVENT_STARTED_RAINING));
    } else if (type == ClientboundGameEventPacket.STOP_RAINING) {
      environment.setWeather(BotWeatherEvent.newBuilder()
        .setKind(WeatherEventKind.WEATHER_EVENT_STOPPED_RAINING));
    } else if (type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
      environment.setWeather(BotWeatherEvent.newBuilder()
        .setKind(WeatherEventKind.WEATHER_EVENT_RAIN_LEVEL_CHANGED)
        .setLevel(gameEventPacket.getParam()));
    } else if (type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
      environment.setWeather(BotWeatherEvent.newBuilder()
        .setKind(WeatherEventKind.WEATHER_EVENT_THUNDER_LEVEL_CHANGED)
        .setLevel(gameEventPacket.getParam()));
    } else {
      environment.setGameEvent(BotGameEvent.newBuilder()
        .setEvent(gameEventName(type))
        .setParameter(gameEventPacket.getParam()));
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setEnvironment(environment)
      .build());
    return true;
  }

  private static String gameEventName(ClientboundGameEventPacket.Type type) {
    if (type == ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE) {
      return "no_respawn_block_available";
    }
    if (type == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
      return "change_game_mode";
    }
    if (type == ClientboundGameEventPacket.WIN_GAME) {
      return "win_game";
    }
    if (type == ClientboundGameEventPacket.DEMO_EVENT) {
      return "demo_event";
    }
    if (type == ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND) {
      return "play_arrow_hit_sound";
    }
    if (type == ClientboundGameEventPacket.PUFFER_FISH_STING) {
      return "puffer_fish_sting";
    }
    if (type == ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT) {
      return "guardian_elder_effect";
    }
    if (type == ClientboundGameEventPacket.IMMEDIATE_RESPAWN) {
      return "immediate_respawn";
    }
    if (type == ClientboundGameEventPacket.LIMITED_CRAFTING) {
      return "limited_crafting";
    }
    if (type == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START) {
      return "level_chunks_load_start";
    }
    return "unknown";
  }

  private static boolean emitPlayerListPacket(
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    if (packet instanceof ClientboundPlayerInfoRemovePacket removePacket) {
      var event = BotPlayerListEvent.newBuilder()
        .setKind(PlayerListEventKind.PLAYER_LIST_EVENT_REMOVE);
      removePacket.profileIds().forEach(id ->
        event.addRemovedProfileIds(id.toString()));
      emitBotEvent(observer, closed, BotEvent.newBuilder()
        .setPlayerList(event)
        .build());
      return true;
    }
    if (!(packet instanceof ClientboundPlayerInfoUpdatePacket updatePacket)) {
      return false;
    }
    var changedFields = updatePacket.actions().stream()
      .map(action -> action.name().toLowerCase(Locale.ROOT))
      .sorted()
      .toList();
    var event = BotPlayerListEvent.newBuilder()
      .setKind(PlayerListEventKind.PLAYER_LIST_EVENT_UPSERT);
    for (var entry : updatePacket.entries()) {
      var player = PlayerListEntrySnapshot.newBuilder()
        .setProfileId(entry.profileId().toString())
        .setListed(entry.listed())
        .setLatencyMs(entry.latency())
        .setGameMode(toProtoGameMode(entry.gameMode()))
        .setShowHat(entry.showHat())
        .setListOrder(entry.listOrder())
        .addAllChangedFields(changedFields);
      if (entry.profile() != null) {
        player.setProfileName(entry.profile().name());
      }
      if (entry.displayName() != null) {
        player.setDisplayName(MinecraftDomainMapper.text(entry.displayName()));
      }
      event.addEntries(player);
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setPlayerList(event)
      .build());
    return true;
  }

  private static GameMode toProtoGameMode(
    @Nullable GameType gameMode
  ) {
    if (gameMode == null) {
      return GameMode.GAME_MODE_UNSPECIFIED;
    }
    return switch (gameMode) {
      case SURVIVAL -> GameMode.GAME_MODE_SURVIVAL;
      case CREATIVE -> GameMode.GAME_MODE_CREATIVE;
      case ADVENTURE -> GameMode.GAME_MODE_ADVENTURE;
      case SPECTATOR -> GameMode.GAME_MODE_SPECTATOR;
    };
  }

  private static void emitBossBarPacket(
    ClientboundBossEventPacket packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    packet.dispatch(new ClientboundBossEventPacket.Handler() {
      private void emit(BotBossBarEvent.Builder event) {
        emitBotEvent(observer, closed, BotEvent.newBuilder()
          .setBossBar(event)
          .build());
      }

      @Override
      public void add(
        UUID id,
        net.minecraft.network.chat.Component name,
        float progress,
        net.minecraft.world.BossEvent.BossBarColor color,
        net.minecraft.world.BossEvent.BossBarOverlay overlay,
        boolean darkenScreen,
        boolean playMusic,
        boolean createWorldFog
      ) {
        emit(BotBossBarEvent.newBuilder()
          .setBossBarId(id.toString())
          .setKind(BossBarEventKind.BOSS_BAR_EVENT_ADD)
          .setName(MinecraftDomainMapper.text(name))
          .setProgress(progress)
          .setColor(color.getSerializedName())
          .setOverlay(overlay.getSerializedName())
          .setDarkenScreen(darkenScreen)
          .setPlayMusic(playMusic)
          .setCreateWorldFog(createWorldFog));
      }

      @Override
      public void remove(UUID id) {
        emit(BotBossBarEvent.newBuilder()
          .setBossBarId(id.toString())
          .setKind(BossBarEventKind.BOSS_BAR_EVENT_REMOVE));
      }

      @Override
      public void updateProgress(UUID id, float progress) {
        emit(BotBossBarEvent.newBuilder()
          .setBossBarId(id.toString())
          .setKind(BossBarEventKind.BOSS_BAR_EVENT_UPDATE_PROGRESS)
          .setProgress(progress));
      }

      @Override
      public void updateName(UUID id, net.minecraft.network.chat.Component name) {
        emit(BotBossBarEvent.newBuilder()
          .setBossBarId(id.toString())
          .setKind(BossBarEventKind.BOSS_BAR_EVENT_UPDATE_NAME)
          .setName(MinecraftDomainMapper.text(name)));
      }

      @Override
      public void updateStyle(
        UUID id,
        net.minecraft.world.BossEvent.BossBarColor color,
        net.minecraft.world.BossEvent.BossBarOverlay overlay
      ) {
        emit(BotBossBarEvent.newBuilder()
          .setBossBarId(id.toString())
          .setKind(BossBarEventKind.BOSS_BAR_EVENT_UPDATE_STYLE)
          .setColor(color.getSerializedName())
          .setOverlay(overlay.getSerializedName()));
      }

      @Override
      public void updateProperties(
        UUID id,
        boolean darkenScreen,
        boolean playMusic,
        boolean createWorldFog
      ) {
        emit(BotBossBarEvent.newBuilder()
          .setBossBarId(id.toString())
          .setKind(BossBarEventKind.BOSS_BAR_EVENT_UPDATE_PROPERTIES)
          .setDarkenScreen(darkenScreen)
          .setPlayMusic(playMusic)
          .setCreateWorldFog(createWorldFog));
      }
    });
  }

  private static boolean emitSoundPacket(
    BotConnection connection,
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    var sound = BotSoundEvent.newBuilder();
    if (packet instanceof ClientboundSoundPacket soundPacket) {
      sound
        .setKind(SoundEventKind.SOUND_EVENT_PLAY_AT_POSITION)
        .setSoundId(soundPacket.getSound().getRegisteredName())
        .setSource(soundPacket.getSource().getName())
        .setPosition(WorldPosition.newBuilder()
          .setX(soundPacket.getX())
          .setY(soundPacket.getY())
          .setZ(soundPacket.getZ())
          .setDimension(currentDimension(connection)))
        .setVolume(soundPacket.getVolume())
        .setPitch(soundPacket.getPitch())
        .setSeed(soundPacket.getSeed());
    } else if (packet instanceof ClientboundSoundEntityPacket soundPacket) {
      sound
        .setKind(SoundEventKind.SOUND_EVENT_PLAY_AT_ENTITY)
        .setSoundId(soundPacket.getSound().getRegisteredName())
        .setSource(soundPacket.getSource().getName())
        .setEntityId(soundPacket.getId())
        .setVolume(soundPacket.getVolume())
        .setPitch(soundPacket.getPitch())
        .setSeed(soundPacket.getSeed());
    } else if (packet instanceof ClientboundStopSoundPacket stopPacket) {
      sound.setKind(SoundEventKind.SOUND_EVENT_STOP);
      if (stopPacket.getName() != null) {
        sound.setSoundId(stopPacket.getName().toString());
      }
      if (stopPacket.getSource() != null) {
        sound.setSource(stopPacket.getSource().getName());
      }
    } else {
      return false;
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setSound(sound)
      .build());
    return true;
  }

  private static boolean emitScoreboardPacket(
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    var event = BotScoreboardEvent.newBuilder();
    if (packet instanceof ClientboundSetObjectivePacket objectivePacket) {
      event
        .setObjectiveName(objectivePacket.getObjectiveName())
        .setKind(switch (objectivePacket.getMethod()) {
          case ClientboundSetObjectivePacket.METHOD_ADD ->
            ScoreboardEventKind.SCOREBOARD_EVENT_OBJECTIVE_ADD;
          case ClientboundSetObjectivePacket.METHOD_REMOVE ->
            ScoreboardEventKind.SCOREBOARD_EVENT_OBJECTIVE_REMOVE;
          case ClientboundSetObjectivePacket.METHOD_CHANGE ->
            ScoreboardEventKind.SCOREBOARD_EVENT_OBJECTIVE_UPDATE;
          default -> ScoreboardEventKind.SCOREBOARD_EVENT_UNSPECIFIED;
        });
      if (objectivePacket.getMethod() != ClientboundSetObjectivePacket.METHOD_REMOVE) {
        event
          .setDisplayName(MinecraftDomainMapper.text(objectivePacket.getDisplayName()))
          .setRenderType(objectivePacket.getRenderType().getSerializedName());
      }
    } else if (packet instanceof ClientboundSetDisplayObjectivePacket displayPacket) {
      event
        .setKind(ScoreboardEventKind.SCOREBOARD_EVENT_DISPLAY_OBJECTIVE)
        .setDisplaySlot(displayPacket.getSlot().getSerializedName())
        .setObjectiveName(displayPacket.getObjectiveName());
    } else if (packet instanceof ClientboundSetScorePacket scorePacket) {
      event
        .setKind(ScoreboardEventKind.SCOREBOARD_EVENT_SCORE_SET)
        .setObjectiveName(scorePacket.objectiveName())
        .setOwner(scorePacket.owner())
        .setScore(scorePacket.score());
      scorePacket.display().ifPresent(display ->
        event.setDisplayName(MinecraftDomainMapper.text(display)));
    } else if (packet instanceof ClientboundResetScorePacket resetPacket) {
      event
        .setKind(ScoreboardEventKind.SCOREBOARD_EVENT_SCORE_RESET)
        .setOwner(resetPacket.owner());
      if (resetPacket.objectiveName() != null) {
        event.setObjectiveName(resetPacket.objectiveName());
      }
    } else if (packet instanceof ClientboundSetPlayerTeamPacket teamPacket) {
      populateTeamEvent(event, teamPacket);
    } else {
      return false;
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setScoreboard(event)
      .build());
    return true;
  }

  private static void populateTeamEvent(
    BotScoreboardEvent.Builder event,
    ClientboundSetPlayerTeamPacket packet
  ) {
    event
      .setTeamName(packet.getName())
      .addAllPlayers(packet.getPlayers());
    if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
      event.setKind(ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_ADD);
    } else if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
      event.setKind(ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_REMOVE);
    } else if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
      event.setKind(ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_PLAYERS_ADD);
    } else if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
      event.setKind(ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_PLAYERS_REMOVE);
    } else {
      event.setKind(ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_UPDATE);
    }
    packet.getParameters().ifPresent(parameters -> {
      var options = Byte.toUnsignedInt(parameters.options());
      event
        .setDisplayName(MinecraftDomainMapper.text(parameters.displayName()))
        .setPrefix(MinecraftDomainMapper.text(parameters.playerPrefix()))
        .setSuffix(MinecraftDomainMapper.text(parameters.playerSuffix()))
        .setNameTagVisibility(parameters.nameTagVisibility().name)
        .setCollisionRule(parameters.collisionRule().name)
        .setAllowFriendlyFire((options & 1) != 0)
        .setSeeFriendlyInvisibles((options & 2) != 0);
      parameters.color().ifPresent(color ->
        event.setColor(color.getSerializedName()));
    });
  }

  private static boolean emitResourcePackPacket(
    net.minecraft.network.protocol.Packet<?> packet,
    ServerCallStreamObserver<BotEvent> observer,
    AtomicBoolean closed
  ) {
    var event = BotResourcePackEvent.newBuilder();
    if (packet instanceof ClientboundResourcePackPushPacket pushPacket) {
      event
        .setKind(ResourcePackEventKind.RESOURCE_PACK_EVENT_OFFERED)
        .setPackId(pushPacket.id().toString())
        .setUrl(pushPacket.url())
        .setHash(pushPacket.hash())
        .setRequired(pushPacket.required());
      pushPacket.prompt().ifPresent(prompt ->
        event.setPrompt(MinecraftDomainMapper.text(prompt)));
    } else if (packet instanceof ClientboundResourcePackPopPacket popPacket) {
      if (popPacket.id().isPresent()) {
        event
          .setKind(ResourcePackEventKind.RESOURCE_PACK_EVENT_REMOVED)
          .setPackId(popPacket.id().orElseThrow().toString());
      } else {
        event.setKind(ResourcePackEventKind.RESOURCE_PACK_EVENT_CLEARED);
      }
    } else {
      return false;
    }
    emitBotEvent(observer, closed, BotEvent.newBuilder()
      .setResourcePack(event)
      .build());
    return true;
  }

  private static float normalizedRadius(float requested, float defaultRadius, float maxRadius) {
    return Math.min(requested > 0.0F ? requested : defaultRadius, maxRadius);
  }

  private static boolean withinRadius(BotConnection connection, BlockPos position, float radius) {
    var player = connection.minecraft().player;
    return player != null && player.blockPosition().distSqr(position) <= radius * radius;
  }

  private static String currentDimension(BotConnection connection) {
    var level = connection.minecraft().level;
    return level == null ? "" : level.dimension().identifier().toString();
  }

  private static ChunkLoadSnapshot chunkLoadSnapshot(
    BotConnection connection,
    int radius
  ) {
    var minecraft = connection.minecraft();
    var player = minecraft.player;
    var level = minecraft.level;
    if (player == null || level == null) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot player or client level is not available")
        .asRuntimeException();
    }
    var center = player.chunkPosition();
    var loaded = 0;
    for (var x = center.x() - radius; x <= center.x() + radius; x++) {
      for (var z = center.z() - radius; z <= center.z() + radius; z++) {
        if (level.hasChunk(x, z)) {
          loaded++;
        }
      }
    }
    var diameter = radius * 2 + 1;
    return new ChunkLoadSnapshot(
      center.x(),
      center.z(),
      loaded,
      diameter * diameter,
      level.dimension().identifier().toString());
  }

  private record ChunkLoadSnapshot(
    int centerChunkX,
    int centerChunkZ,
    int loadedChunks,
    int requiredChunks,
    String dimension
  ) {}

  private record TickSnapshot(
    BotLiveState state,
    @Nullable BotInventoryStateResponse inventory,
    boolean dead
  ) {}

  private record ObservedEntity(
    NearbyEntity summary,
    EntitySnapshot snapshot
  ) {}

  private static BotStateDelta computeDelta(BotLiveState prev, BotLiveState next) {
    if (prev == null) {
      return null;
    }
    var b = BotStateDelta.newBuilder();
    var changed = false;
    if (prev.getX() != next.getX()) { b.setX(next.getX()); changed = true; }
    if (prev.getY() != next.getY()) { b.setY(next.getY()); changed = true; }
    if (prev.getZ() != next.getZ()) { b.setZ(next.getZ()); changed = true; }
    if (prev.getXRot() != next.getXRot()) { b.setXRot(next.getXRot()); changed = true; }
    if (prev.getYRot() != next.getYRot()) { b.setYRot(next.getYRot()); changed = true; }
    if (prev.getHealth() != next.getHealth()) { b.setHealth(next.getHealth()); changed = true; }
    if (prev.getMaxHealth() != next.getMaxHealth()) { b.setMaxHealth(next.getMaxHealth()); changed = true; }
    if (prev.getFoodLevel() != next.getFoodLevel()) { b.setFoodLevel(next.getFoodLevel()); changed = true; }
    if (prev.getSaturationLevel() != next.getSaturationLevel()) { b.setSaturationLevel(next.getSaturationLevel()); changed = true; }
    if (prev.getSelectedHotbarSlot() != next.getSelectedHotbarSlot()) { b.setSelectedHotbarSlot(next.getSelectedHotbarSlot()); changed = true; }
    if (!Objects.equals(prev.getDimension(), next.getDimension())) { b.setDimension(next.getDimension()); changed = true; }
    if (prev.getExperienceLevel() != next.getExperienceLevel()) { b.setExperienceLevel(next.getExperienceLevel()); changed = true; }
    if (prev.getExperienceProgress() != next.getExperienceProgress()) { b.setExperienceProgress(next.getExperienceProgress()); changed = true; }
    if (prev.getGameMode() != next.getGameMode()) { b.setGameMode(next.getGameMode()); changed = true; }
    return changed ? b.build() : null;
  }

  // =====================================================================
  // SendChat
  // =====================================================================

  @Override
  public void sendChat(SendChatRequest request, StreamObserver<SendChatResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once(
        "SDK send chat",
        Set.of(ControlResource.CHAT),
        () -> bot.sendChatMessage(request.getMessage())),
      DEFAULT_ACTION_TIMEOUT,
      result -> SendChatResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  // =====================================================================
  // GetBlock
  // =====================================================================

  @Override
  public void getBlock(GetBlockRequest request, StreamObserver<GetBlockResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var response = callInBotContext(bot, () -> {
        var level = bot.minecraft().level;
        if (level == null) {
          return GetBlockResponse.newBuilder().setLoaded(false).build();
        }
        var pos = toMcBlockPos(request.getPosition());
        if (!level.hasChunkAt(pos)) {
          return GetBlockResponse.newBuilder().setLoaded(false).build();
        }
        var state = level.getBlockState(pos);
        var dimension = level.dimension().identifier().toString();
        return GetBlockResponse.newBuilder()
          .setLoaded(true)
          .setBlock(buildBlockState(pos, state, dimension))
          .build();
      });
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting block", t);
      throw toGrpcError("Failed to get block", t);
    }
  }

  // =====================================================================
  // FindBlocks
  // =====================================================================

  @Override
  public void findBlocks(FindBlocksRequest request, StreamObserver<FindBlocksResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var maxDistance = Math.min(Math.max(request.getMaxDistance(), 0), MAX_FIND_BLOCKS_DISTANCE);
      var maxCount = Math.min(Math.max(request.getMaxCount(), 0), MAX_FIND_BLOCKS_COUNT);
      var blockIds = request.getBlockIdsList();
      if (blockIds.isEmpty() || maxDistance == 0 || maxCount == 0) {
        responseObserver.onNext(FindBlocksResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      }

      var response = callInBotContext(bot, () -> {
        var player = bot.minecraft().player;
        var level = bot.minecraft().level;
        if (player == null || level == null) {
          return FindBlocksResponse.getDefaultInstance();
        }

        var matchSet = new HashSet<>(blockIds);
        var origin = player.blockPosition();
        var dimension = level.dimension().identifier().toString();

        // Collect matches with their squared distance, then sort ascending.
        var matches = new ArrayList<ScoredMatch>();
        var radius = maxDistance;
        for (var dx = -radius; dx <= radius; dx++) {
          for (var dy = -radius; dy <= radius; dy++) {
            for (var dz = -radius; dz <= radius; dz++) {
              var pos = origin.offset(dx, dy, dz);
              if (!level.hasChunkAt(pos)) {
                continue;
              }
              var state = level.getBlockState(pos);
              var id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
              if (!matchSet.contains(id)) {
                continue;
              }
              var sqDistance = origin.distSqr(pos);
              if (sqDistance > (double) radius * radius) {
                continue;
              }
              matches.add(new ScoredMatch(pos.immutable(), state, sqDistance));
            }
          }
        }

        matches.sort(Comparator.comparingDouble(ScoredMatch::sqDistance));
        var responseBuilder = FindBlocksResponse.newBuilder();
        var limit = Math.min(matches.size(), maxCount);
        for (var i = 0; i < limit; i++) {
          var match = matches.get(i);
          responseBuilder.addBlocks(buildBlockState(match.pos(), match.state(), dimension));
        }
        return responseBuilder.build();
      });
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error finding blocks", t);
      throw toGrpcError("Failed to find blocks", t);
    }
  }

  private record ScoredMatch(BlockPos pos, BlockState state, double sqDistance) {}

  // =====================================================================
  // ListNearbyEntities
  // =====================================================================

  @Override
  public void listNearbyEntities(ListNearbyEntitiesRequest request, StreamObserver<ListNearbyEntitiesResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var radius = Math.min(Math.max(request.getRadius(), 0), MAX_ENTITY_RADIUS);
      var typeFilter = request.getEntityTypesList();
      var includePlayers = request.getIncludePlayers();

      var response = callInBotContext(bot, () -> {
        var player = bot.minecraft().player;
        var level = bot.minecraft().level;
        if (player == null || level == null) {
          return ListNearbyEntitiesResponse.getDefaultInstance();
        }
        var origin = player.position();
        var dimension = level.dimension().identifier().toString();
        var typeSet = typeFilter.isEmpty() ? null : new HashSet<>(typeFilter);

        var results = StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
          .filter(entity -> entity != player)
          .filter(entity -> includePlayers || !(entity instanceof Player))
          .filter(entity -> {
            if (typeSet == null) {
              return true;
            }
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return typeSet.contains(id);
          })
          .filter(entity -> entity.position().distanceToSqr(origin) <= (double) radius * radius)
          .sorted(Comparator.comparingDouble(e -> e.position().distanceToSqr(origin)))
          .map(entity -> buildNearbyEntity(entity, origin, dimension))
          .toList();

        return ListNearbyEntitiesResponse.newBuilder()
          .addAllEntities(results)
          .build();
      });
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error listing nearby entities", t);
      throw toGrpcError("Failed to list nearby entities", t);
    }
  }

  // =====================================================================
  // DigBlock
  // =====================================================================

  @Override
  public void digBlock(DigBlockRequest request, StreamObserver<DigBlockResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      new DigBlockTask(bot, toMcBlockPos(request.getPosition()), request.getCancel()),
      DIG_ACTION_TIMEOUT,
      result -> DigBlockResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  private static Direction nearestFaceTo(Vec3 eyePos, BlockPos target) {
    var center = Vec3.atCenterOf(target);
    var dx = eyePos.x - center.x;
    var dy = eyePos.y - center.y;
    var dz = eyePos.z - center.z;
    var ax = Math.abs(dx);
    var ay = Math.abs(dy);
    var az = Math.abs(dz);
    if (ay >= ax && ay >= az) {
      return dy >= 0 ? Direction.UP : Direction.DOWN;
    }
    if (ax >= az) {
      return dx >= 0 ? Direction.EAST : Direction.WEST;
    }
    return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
  }

  private static void requireReach(LocalPlayer player, BlockPos position) {
    if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > 36.0D) {
      throw Status.OUT_OF_RANGE
        .withDescription("Target block is outside the bot's interaction reach")
        .asRuntimeException();
    }
  }

  private static final class DigBlockTask implements ControlTask {
    private static final int MAXIMUM_BLOCK_REPLACEMENT_RETRIES = 16;
    private static final int CLEARED_STATE_SETTLE_TICKS = 4;

    private final BotConnection bot;
    private final BlockPos position;
    private final boolean cancel;
    private Direction face = Direction.UP;
    private boolean started;
    private boolean predictedBroken;
    private BlockState attemptedState;
    private boolean done;
    private int ticks;
    private int blockReplacementRetries;
    private int clearedStateTicks;

    private DigBlockTask(BotConnection bot, BlockPos position, boolean cancel) {
      this.bot = bot;
      this.position = position;
      this.cancel = cancel;
    }

    @Override
    public void tick() {
      var minecraft = bot.minecraft();
      var gameMode = minecraft.gameMode;
      var player = minecraft.player;
      var level = minecraft.level;
      if (gameMode == null || player == null || level == null) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot player, level, or game mode is not available")
          .asRuntimeException();
      }
      if (cancel) {
        gameMode.stopDestroyBlock();
        done = true;
        return;
      }
      var pendingPrediction =
        BlockPredictionSupport.hasPendingPrediction(bot, position);
      var currentState = level.getBlockState(position);
      if (!started && BlockPredictionSupport.isClearedBreakTarget(currentState)) {
        done = true;
        return;
      }
      if (started && BlockPredictionSupport.isClearedBreakTarget(currentState)) {
        predictedBroken = true;
      }
      var reconciliation = BlockPredictionSupport.reconcileBreak(
        currentState,
        attemptedState,
        started,
        predictedBroken,
        pendingPrediction,
        blockReplacementRetries,
        MAXIMUM_BLOCK_REPLACEMENT_RETRIES
      );
      switch (reconciliation) {
        case COMPLETE -> {
          if (!started || ++clearedStateTicks >= CLEARED_STATE_SETTLE_TICKS) {
            done = true;
          }
          return;
        }
        case AWAIT_CONFIRMATION -> {
          clearedStateTicks = 0;
          return;
        }
        case RETRY_REPLACEMENT -> {
          gameMode.stopDestroyBlock();
          started = false;
          predictedBroken = false;
          attemptedState = null;
          clearedStateTicks = 0;
          blockReplacementRetries++;
        }
        case REJECTED -> throw Status.FAILED_PRECONDITION
          .withDescription("The server rejected breaking the target block")
          .asRuntimeException();
        case CONTINUE -> {
          clearedStateTicks = 0;
        }
      }
      requireReach(player, position);
      if (!started) {
        face = nearestFaceTo(player.getEyePosition(), position);
        if (!gameMode.startDestroyBlock(position, face)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The target block cannot be broken")
            .asRuntimeException();
        }
        player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
        started = true;
        attemptedState = currentState;
        return;
      }
      if (!gameMode.continueDestroyBlock(position, face)) {
        if (BlockPredictionSupport.isClearedBreakTarget(level.getBlockState(position))) {
          predictedBroken = true;
          done = !BlockPredictionSupport.hasPendingPrediction(bot, position);
          return;
        }
        throw Status.FAILED_PRECONDITION
          .withDescription("Block breaking was rejected")
          .asRuntimeException();
      }
      player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
      player.connection.send(ServerboundPunchPacket.INSTANCE);
      ticks++;
      if (BlockPredictionSupport.isClearedBreakTarget(level.getBlockState(position))) {
        predictedBroken = true;
        done = !BlockPredictionSupport.hasPendingPrediction(bot, position);
      } else if (ticks >= DIG_ACTION_TIMEOUT.toSeconds() * 20) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Block breaking timed out")
          .asRuntimeException();
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      if (reason != ControlStopReason.COMPLETED) {
        var gameMode = bot.minecraft().gameMode;
        if (gameMode != null) {
          gameMode.stopDestroyBlock();
        }
      }
      done = true;
    }

    @Override
    public String description() {
      return "SDK dig block";
    }
  }

  // =====================================================================
  // PlaceBlock
  // =====================================================================

  @Override
  public void placeBlock(PlaceBlockRequest request, StreamObserver<PlaceBlockResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    var against = toMcBlockPos(request.getAgainst());
    var direction = toMcDirection(request.getFace());
    var hand = toMcHand(request.getHand());
    submitAction(
      bot,
      new PlaceBlockTask(bot, against, direction, hand),
      DEFAULT_ACTION_TIMEOUT,
      result -> PlaceBlockResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  private static final class PlaceBlockTask implements ControlTask {
    private static final int MAX_INTERACTION_FAILURES = 4;
    private static final int INTERACTION_RETRY_TICKS = 4;
    private static final int NON_READY_GRACE_TICKS = 20;

    private final BotConnection bot;
    private final BlockPos against;
    private final Direction direction;
    private final InteractionHand hand;
    private final BlockPos target;
    private @Nullable Block expectedBlock;
    private boolean awaitingConfirmation;
    private boolean done;
    private int ticks;
    private int nonReadyTicks;
    private int interactionFailures;
    private int retryTicks;

    private PlaceBlockTask(
      BotConnection bot,
      BlockPos against,
      Direction direction,
      InteractionHand hand
    ) {
      this.bot = bot;
      this.against = against;
      this.direction = direction;
      this.hand = hand;
      this.target = against.relative(direction);
    }

    @Override
    public void tick() {
      ticks++;
      var minecraft = bot.minecraft();
      var gameMode = minecraft.gameMode;
      var player = minecraft.player;
      var level = minecraft.level;
      if (gameMode == null || player == null || level == null) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot player, level, or game mode is not available")
          .asRuntimeException();
      }
      bot.controlState().resetAll();

      if (ticks >= DEFAULT_ACTION_TIMEOUT.toSeconds() * 20 - 10) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription(
            "Block placement did not become ready or confirm: %s"
              .formatted(placementDiagnostic(player, level))
          )
          .asRuntimeException();
      }

      var expected = expectedBlock;
      if (expected != null
        && level.getBlockState(target).getBlock() == expected
        && !BlockPredictionSupport.hasPendingPrediction(bot, target)) {
        done = true;
        return;
      }

      if (awaitingConfirmation) {
        if (BlockPredictionSupport.hasPendingPrediction(bot, target)) {
          return;
        }
        // A settled prediction without the expected block is an authoritative
        // rejection. Re-evaluate position, visibility, and held item before a
        // bounded retry instead of repeating the same click immediately.
        awaitingConfirmation = false;
        interactionFailures++;
        retryTicks = INTERACTION_RETRY_TICKS;
        if (interactionFailures >= MAX_INTERACTION_FAILURES) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "The server repeatedly rejected block placement: %s"
                .formatted(placementDiagnostic(player, level))
            )
            .asRuntimeException();
        }
        return;
      }

      if (retryTicks > 0) {
        retryTicks--;
        return;
      }

      var placement = BlockPlacementSupport.evaluate(
        bot,
        hand,
        target,
        against,
        direction
      );
      if (placement.readiness()
        == BlockPlacementSupport.Readiness.ALREADY_PLACED) {
        done = true;
        return;
      }
      if (placement.readiness()
        == BlockPlacementSupport.Readiness.PLAYER_INTERSECTION) {
        BlockPlacementSupport.moveToPlayerClearance(bot, target);
        nonReadyTicks = 0;
        return;
      }
      if ((placement.readiness()
        == BlockPlacementSupport.Readiness.FACE_OCCLUDED
        || placement.readiness()
        == BlockPlacementSupport.Readiness.OUT_OF_REACH)
        && BlockPlacementSupport.moveTowardPlacementView(
        bot,
        target,
        against,
        direction
      )) {
        nonReadyTicks = 0;
        return;
      }
      if (!placement.ready()) {
        nonReadyTicks++;
        if (nonReadyTicks < NON_READY_GRACE_TICKS
          && isTransientPlacementState(placement.readiness())) {
          return;
        }
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Block placement is not ready (%s): %s"
              .formatted(placement.readiness(), placement.detail())
          )
          .asRuntimeException();
      }

      nonReadyTicks = 0;
      var candidate = placement.candidate();
      expectedBlock = candidate.expectedState().getBlock();
      bot.controlState().shift(true);
      bot.rotationControl().lookAt(candidate.hitPosition());
      if (!bot.rotationControl().isFacing(candidate.hitPosition())) {
        return;
      }

      var swingAnimation = player.getItemInHand(hand).getInteractAnimation();
      var result = BotInteractionSupport.withSneaking(
        player,
        true,
        () -> gameMode.useItemOn(player, hand, candidate.hitResult())
      );
      if (!(result instanceof InteractionResult.Success success)) {
        interactionFailures++;
        retryTicks = INTERACTION_RETRY_TICKS;
        if (interactionFailures >= MAX_INTERACTION_FAILURES) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Minecraft repeatedly refused the placement interaction: %s"
                .formatted(placementDiagnostic(player, level))
            )
            .asRuntimeException();
        }
        return;
      }
      if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
        player.swing(hand, swingAnimation, false);
      }
      awaitingConfirmation = true;
    }

    private static boolean isTransientPlacementState(
      BlockPlacementSupport.Readiness readiness
    ) {
      return switch (readiness) {
        case HELD_ITEM_NOT_BLOCK,
             PLAYER_MOVING,
             ENTITY_OBSTRUCTION,
             FACE_OCCLUDED,
             GAME_STATE_UNAVAILABLE -> true;
        case READY,
             ALREADY_PLACED,
             TARGET_NOT_REPLACEABLE,
             NO_SUPPORT,
             OUT_OF_REACH,
             PLAYER_INTERSECTION,
             INVALID_PLACEMENT_STATE -> false;
      };
    }

    private String placementDiagnostic(
      LocalPlayer player,
      ClientLevel level
    ) {
      var held = player.getItemInHand(hand);
      return (
        "target=%s, targetState=%s, against=%s, againstState=%s, "
          + "held=%s, player=(%.3f, %.3f, %.3f), motion=(%.3f, %.3f, %.3f)"
      ).formatted(
          target,
          level.getBlockState(target),
          against,
          level.getBlockState(against),
          held,
          player.getX(),
          player.getY(),
          player.getZ(),
          player.getDeltaMovement().x,
          player.getDeltaMovement().y,
          player.getDeltaMovement().z
      );
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      bot.controlState().resetAll();
      done = true;
    }

    @Override
    public String description() {
      return "SDK place block";
    }
  }

  // =====================================================================
  // InteractBlock
  // =====================================================================

  @Override
  public void interactBlock(
    InteractBlockRequest request,
    StreamObserver<InteractBlockResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    var position = toMcBlockPos(request.getPosition());
    var direction = toMcDirection(request.getFace());
    var hand = toMcHand(request.getHand());
    submitAction(
      bot,
      ControlTask.once("SDK interact block", () -> {
        var gameMode = bot.minecraft().gameMode;
        var player = bot.minecraft().player;
        var level = bot.minecraft().level;
        if (gameMode == null || player == null || level == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Bot player, level, or game mode is not available"
            )
            .asRuntimeException();
        }
        if (!level.hasChunkAt(position)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Target block is not loaded")
            .asRuntimeException();
        }
        requireReach(player, position);
        var hitPosition = Vec3.atCenterOf(position).add(
          direction.getStepX() * 0.5,
          direction.getStepY() * 0.5,
          direction.getStepZ() * 0.5
        );
        var swingAnimation = player.getItemInHand(hand).getInteractAnimation();
        var result = BotInteractionSupport.withSneaking(
          player,
          request.getSneaking(),
          () -> BotInteractionSupport.withItemUseFallback(
            gameMode.useItemOn(
              player,
              hand,
              new BlockHitResult(
                hitPosition,
                direction,
                position,
                false
              )
            ),
            () -> {
              var itemStack = player.getItemInHand(hand);
              return !itemStack.isEmpty()
                && itemStack.isItemEnabled(level.enabledFeatures())
                ? gameMode.useItem(player, hand)
                : InteractionResult.PASS;
            }
          )
        );
        if (!(result instanceof InteractionResult.Success success)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The target block rejected the interaction")
            .asRuntimeException();
        }
        if (
          success.swingSource()
            == InteractionResult.SwingSource.PREDICTED
        ) {
          player.swing(hand, swingAnimation, false);
        }
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> InteractBlockResponse.newBuilder()
        .setResult(result)
        .build(),
      responseObserver
    );
  }

  // =====================================================================
  // UseItem
  // =====================================================================

  @Override
  public void useItem(UseItemRequest request, StreamObserver<UseItemResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    var hand = toMcHand(request.getHand());
    submitAction(
      bot,
      ControlTask.once("SDK use item", () -> {
        var gameMode = bot.minecraft().gameMode;
        var player = bot.minecraft().player;
        if (gameMode == null || player == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player or game mode is not available")
            .asRuntimeException();
        }
        var swingAnimation = player.getItemInHand(hand).getInteractAnimation();
        var result = gameMode.useItem(player, hand);
        if (!(result instanceof InteractionResult.Success success)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The held item could not be used")
            .asRuntimeException();
        }
        if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
          player.swing(hand, swingAnimation, false);
        }
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> UseItemResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  @Override
  public void releaseItem(ReleaseItemRequest request, StreamObserver<ReleaseItemResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once("SDK release item", () -> {
        var gameMode = bot.minecraft().gameMode;
        var player = bot.minecraft().player;
        if (gameMode == null || player == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player or game mode is not available")
            .asRuntimeException();
        }
        if (!player.isUsingItem()) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The bot is not using an item")
            .asRuntimeException();
        }
        gameMode.releaseUsingItem(player);
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> ReleaseItemResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  // =====================================================================
  // AttackEntity
  // =====================================================================

  @Override
  public void attackEntity(AttackEntityRequest request, StreamObserver<AttackEntityResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    requireConnectionEpoch(bot, request.hasConnectionEpoch()
      ? request.getConnectionEpoch()
      : null);
    submitAction(
      bot,
      ControlTask.once("SDK attack entity", () -> {
        var gameMode = bot.minecraft().gameMode;
        var player = bot.minecraft().player;
        var level = bot.minecraft().level;
        if (gameMode == null || player == null || level == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player, level, or game mode is not available")
            .asRuntimeException();
        }
        var target = findEntityById(level, request.getEntityId());
        if (target == null) {
          throw Status.NOT_FOUND
            .withDescription("Target entity is not observable")
            .asRuntimeException();
        }
        if (target.distanceToSqr(player) > 36.0D) {
          throw Status.OUT_OF_RANGE
            .withDescription("Target entity is outside the bot's interaction reach")
            .asRuntimeException();
        }
        var wasSprinting = player.isSprinting();
        player.setSprinting(request.getSprinting());
        try {
          gameMode.attack(player, target);
          player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
          player.connection.send(ServerboundPunchPacket.INSTANCE);
        } finally {
          player.setSprinting(wasSprinting);
        }
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> AttackEntityResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  private static Entity findEntityById(ClientLevel level, int id) {
    return StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
      .filter(e -> e.getId() == id)
      .findFirst()
      .orElse(null);
  }

  private static void requireConnectionEpoch(
    BotConnection bot,
    @Nullable String requestedEpoch
  ) {
    if (
      requestedEpoch != null
        && !requestedEpoch.equals(bot.connectionEpoch().toString())
    ) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "Entity reference belongs to an earlier bot connection"
        )
        .asRuntimeException();
    }
  }

  // =====================================================================
  // InteractEntity
  // =====================================================================

  @Override
  public void interactEntity(InteractEntityRequest request, StreamObserver<InteractEntityResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    requireConnectionEpoch(bot, request.hasConnectionEpoch()
      ? request.getConnectionEpoch()
      : null);
    var hand = toMcHand(request.getHand());
    submitAction(
      bot,
      ControlTask.once("SDK interact entity", () -> {
        var gameMode = bot.minecraft().gameMode;
        var player = bot.minecraft().player;
        var level = bot.minecraft().level;
        if (gameMode == null || player == null || level == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player, level, or game mode is not available")
            .asRuntimeException();
        }
        var target = findEntityById(level, request.getEntityId());
        if (target == null) {
          throw Status.NOT_FOUND
            .withDescription("Target entity is not observable")
            .asRuntimeException();
        }
        if (target.distanceToSqr(player) > 36.0D) {
          throw Status.OUT_OF_RANGE
            .withDescription("Target entity is outside the bot's interaction reach")
            .asRuntimeException();
        }
        var swingAnimation = player.getItemInHand(hand).getInteractAnimation();
        var result = BotInteractionSupport.withSneaking(
          player,
          request.getSneaking(),
          () -> gameMode.interact(
            player,
            target,
            new EntityHitResult(target),
            hand
          )
        );
        if (!(result instanceof InteractionResult.Success success)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The target entity rejected the interaction")
            .asRuntimeException();
        }
        if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
          player.swing(hand, swingAnimation, false);
        }
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> InteractEntityResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  // =====================================================================
  // SwingArm
  // =====================================================================

  @Override
  public void swingArm(SwingArmRequest request, StreamObserver<SwingArmResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    var hand = toMcHand(request.getHand());
    submitAction(
      bot,
      ControlTask.once("SDK swing arm", () -> {
        var player = bot.minecraft().player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player is not available")
            .asRuntimeException();
        }
        player.swing(hand, player.getItemInHand(hand).getAttackAnimation(), false);
        if (hand == InteractionHand.MAIN_HAND) {
          player.connection.send(ServerboundPunchPacket.INSTANCE);
        }
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> SwingArmResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  @Override
  public void respawn(RespawnRequest request, StreamObserver<RespawnResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once("SDK respawn", () -> {
        var player = bot.minecraft().player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player is not available")
            .asRuntimeException();
        }
        var screen = bot.minecraft().gui.screen();
        if (!player.isDeadOrDying() && !(screen instanceof WinScreen)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot is not dead or viewing the End credits")
            .asRuntimeException();
        }
        if (screen instanceof WinScreen winScreen) {
          winScreen.onClose();
        } else {
          player.respawn();
        }
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> RespawnResponse.newBuilder().setResult(result).build(),
      responseObserver);
  }

  @Override
  public void sleep(
    SleepRequest request,
    StreamObserver<SleepResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      new SleepControl(
        bot,
        toMcBlockPos(request.getBed()),
        toMcHand(request.getHand())
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> SleepResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void wake(
    WakeRequest request,
    StreamObserver<WakeResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once("SDK wake from bed", () -> {
        var player = bot.minecraft().player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player is not available")
            .asRuntimeException();
        }
        if (!player.isSleeping()) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot is not sleeping")
            .asRuntimeException();
        }
        player.connection.send(new ServerboundPlayerCommandPacket(
          player,
          ServerboundPlayerCommandPacket.Action.STOP_SLEEPING
        ));
      }),
      DEFAULT_ACTION_TIMEOUT,
      result -> WakeResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void mountEntity(
    MountEntityRequest request,
    StreamObserver<MountEntityResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    requireConnectionEpoch(bot, request.hasConnectionEpoch()
      ? request.getConnectionEpoch()
      : null);
    var mountedVehicle = new AtomicReference<EntityReference>();
    submitAction(
      bot,
      new MountControl(
        bot,
        request.getEntityId(),
        toMcHand(request.getHand()),
        mountedVehicle
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> {
        var response = MountEntityResponse.newBuilder().setResult(result);
        var vehicle = mountedVehicle.get();
        if (vehicle != null) {
          response.setVehicle(vehicle);
        }
        return response.build();
      },
      responseObserver
    );
  }

  @Override
  public void dismount(
    DismountRequest request,
    StreamObserver<DismountResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      new DismountControl(bot),
      DEFAULT_ACTION_TIMEOUT,
      result -> DismountResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void setVehicleControl(
    SetVehicleControlRequest request,
    StreamObserver<SetVehicleControlResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    var controlledVehicle = new AtomicReference<EntityReference>();
    submitAction(
      bot,
      ControlTask.once(
        "SDK set vehicle control",
        Set.of(ControlResource.MOVEMENT, ControlResource.ROTATION, ControlResource.VEHICLE),
        () -> {
          var player = bot.minecraft().player;
          if (player == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot player is not available")
              .asRuntimeException();
          }
          var vehicle = player.getControlledVehicle();
          if (vehicle == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot is not controlling a vehicle")
              .asRuntimeException();
          }
          var control = bot.controlState();
          if (request.hasForward()) {
            control.up(request.getForward());
          }
          if (request.hasBackward()) {
            control.down(request.getBackward());
          }
          if (request.hasLeft()) {
            control.left(request.getLeft());
          }
          if (request.hasRight()) {
            control.right(request.getRight());
          }
          if (request.hasJump()) {
            control.jump(request.getJump());
          }
          if (request.hasSneak()) {
            control.shift(request.getSneak());
          }
          if (request.hasSprint()) {
            control.sprint(request.getSprint());
          }
          if (request.hasYaw()) {
            player.setYRot(request.getYaw());
            vehicle.setYRot(request.getYaw());
          }
          if (request.hasPitch()) {
            player.setXRot(request.getPitch());
            vehicle.setXRot(request.getPitch());
          }
          if (request.hasYaw() || request.hasPitch()) {
            player.connection.send(
              ServerboundMoveVehiclePacket.fromEntity(vehicle));
          }
          controlledVehicle.set(
            MinecraftDomainMapper.reference(bot, vehicle));
        }
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> {
        var response = SetVehicleControlResponse.newBuilder()
          .setResult(result);
        var vehicle = controlledVehicle.get();
        if (vehicle != null) {
          response.setVehicle(vehicle);
        }
        return response.build();
      },
      responseObserver
    );
  }

  @Override
  public void updateSign(
    UpdateSignRequest request,
    StreamObserver<UpdateSignResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once(
        "SDK update sign",
        Set.of(ControlResource.MAIN_HAND),
        () -> {
          if (request.getLinesCount() != 4) {
            throw Status.INVALID_ARGUMENT
              .withDescription("Sign text must contain exactly four lines")
              .asRuntimeException();
          }
          var level = bot.minecraft().level;
          var player = bot.minecraft().player;
          if (level == null || player == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot player or level is not available")
              .asRuntimeException();
          }
          var requestedDimension = request.getPosition().getDimension();
          if (!requestedDimension.isBlank()
            && !requestedDimension.equals(level.dimension().identifier().toString())) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Sign position is in a different dimension")
              .asRuntimeException();
          }
          var position = toMcBlockPos(request.getPosition());
          if (!level.hasChunkAt(position)) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Sign position is not loaded")
              .asRuntimeException();
          }
          if (!(level.getBlockState(position).getBlock() instanceof SignBlock)) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Target block is not a sign")
              .asRuntimeException();
          }
          if (player.distanceToSqr(Vec3.atCenterOf(position)) > 64.0D) {
            throw Status.OUT_OF_RANGE
              .withDescription("Sign is outside the bot's interaction reach")
              .asRuntimeException();
          }
          var lines = request.getLinesList();
          player.connection.send(new ServerboundSignUpdatePacket(
            position,
            lines,
            request.getFrontText() ? SignTextSlot.FRONT : SignTextSlot.BACK
          ));
        }
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> UpdateSignResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void writeBook(
    WriteBookRequest request,
    StreamObserver<WriteBookResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once(
        "SDK write book",
        Set.of(ControlResource.INVENTORY, ControlResource.MAIN_HAND),
        () -> {
          if (request.getInventorySlot() < 0 || request.getInventorySlot() > 8) {
            throw Status.INVALID_ARGUMENT
              .withDescription("Writable book slot must be between zero and eight")
              .asRuntimeException();
          }
          if (request.getPagesCount() == 0
            || request.getPagesCount() > WritableBookContent.MAX_PAGES) {
            throw Status.INVALID_ARGUMENT
              .withDescription(
                "Book must contain between one and %d pages"
                  .formatted(WritableBookContent.MAX_PAGES))
              .asRuntimeException();
          }
          for (var page : request.getPagesList()) {
            if (page.length() > WritableBookContent.PAGE_EDIT_LENGTH) {
              throw Status.INVALID_ARGUMENT
                .withDescription(
                  "Book pages must not exceed %d characters"
                    .formatted(WritableBookContent.PAGE_EDIT_LENGTH))
                .asRuntimeException();
            }
          }
          if (request.hasTitle()
            && (request.getTitle().isBlank()
              || request.getTitle().length() > WrittenBookContent.TITLE_MAX_LENGTH)) {
            throw Status.INVALID_ARGUMENT
              .withDescription(
                "Book title must contain between one and %d characters"
                  .formatted(WrittenBookContent.TITLE_MAX_LENGTH))
              .asRuntimeException();
          }
          var player = bot.minecraft().player;
          if (player == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot player is not available")
              .asRuntimeException();
          }
          var stack = player.getInventory().getItem(request.getInventorySlot());
          if (!stack.is(Items.WRITABLE_BOOK)) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Selected inventory slot does not contain a writable book")
              .asRuntimeException();
          }
          player.connection.send(new ServerboundEditBookPacket(
            request.getInventorySlot(),
            List.copyOf(request.getPagesList()),
            request.hasTitle() ? Optional.of(request.getTitle()) : Optional.empty()
          ));
        }
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> WriteBookResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void respondResourcePack(
    RespondResourcePackRequest request,
    StreamObserver<RespondResourcePackResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once(
        "SDK respond to resource pack",
        Set.of(ControlResource.PROTOCOL),
        () -> {
          var player = bot.minecraft().player;
          if (player == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot player is not available")
              .asRuntimeException();
          }
          var action = switch (request.getResponse()) {
            case RESOURCE_PACK_RESPONSE_ACCEPTED ->
              ServerboundResourcePackPacket.Action.ACCEPTED;
            case RESOURCE_PACK_RESPONSE_DOWNLOADED ->
              ServerboundResourcePackPacket.Action.DOWNLOADED;
            case RESOURCE_PACK_RESPONSE_SUCCESSFULLY_LOADED ->
              ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED;
            case RESOURCE_PACK_RESPONSE_DECLINED ->
              ServerboundResourcePackPacket.Action.DECLINED;
            case RESOURCE_PACK_RESPONSE_FAILED_DOWNLOAD ->
              ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD;
            case RESOURCE_PACK_RESPONSE_INVALID_URL ->
              ServerboundResourcePackPacket.Action.INVALID_URL;
            case RESOURCE_PACK_RESPONSE_FAILED_RELOAD ->
              ServerboundResourcePackPacket.Action.FAILED_RELOAD;
            case RESOURCE_PACK_RESPONSE_DISCARDED ->
              ServerboundResourcePackPacket.Action.DISCARDED;
            case RESOURCE_PACK_RESPONSE_UNSPECIFIED, UNRECOGNIZED ->
              throw Status.INVALID_ARGUMENT
                .withDescription("Resource pack response must be specified")
                .asRuntimeException();
          };
          player.connection.send(new ServerboundResourcePackPacket(
            UUID.fromString(request.getPackId()),
            action
          ));
        }
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> RespondResourcePackResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void setFlying(
    SetFlyingRequest request,
    StreamObserver<SetFlyingResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once(
        "SDK set flying",
        Set.of(ControlResource.MOVEMENT),
        () -> {
          var player = bot.minecraft().player;
          if (player == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot player is not available")
              .asRuntimeException();
          }
          var abilities = player.getAbilities();
          if (request.getFlying() && !abilities.mayfly) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Current game mode does not allow flight")
              .asRuntimeException();
          }
          abilities.flying = request.getFlying();
          player.connection.send(new ServerboundPlayerAbilitiesPacket(abilities));
        }
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> SetFlyingResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void startElytraFlight(
    StartElytraFlightRequest request,
    StreamObserver<StartElytraFlightResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once(
        "SDK start elytra flight",
        Set.of(ControlResource.MOVEMENT),
        () -> {
          var player = bot.minecraft().player;
          if (player == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot player is not available")
              .asRuntimeException();
          }
          if (player.onGround()) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Elytra flight can only start while the bot is airborne")
              .asRuntimeException();
          }
          if (player.isPassenger()) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Elytra flight cannot start while the bot is riding")
              .asRuntimeException();
          }
          player.connection.send(new ServerboundPlayerCommandPacket(
            player,
            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
          ));
        }
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> StartElytraFlightResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void setCreativeSlot(
    SetCreativeSlotRequest request,
    StreamObserver<SetCreativeSlotResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    submitAction(
      bot,
      ControlTask.once(
        "SDK set creative inventory slot",
        Set.of(ControlResource.INVENTORY),
        () -> {
          if (request.getSlot() < 0 || request.getSlot() > 45) {
            throw Status.INVALID_ARGUMENT
              .withDescription("Creative inventory slot must be between zero and 45")
              .asRuntimeException();
          }
          var player = bot.minecraft().player;
          var gameMode = bot.minecraft().gameMode;
          if (player == null || gameMode == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot player or game mode is not available")
              .asRuntimeException();
          }
          if (!gameMode.getPlayerMode().isCreative()) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Creative inventory editing requires creative mode")
              .asRuntimeException();
          }
          var stack = ItemStack.EMPTY;
          if (request.hasItem()) {
            var identifier = Identifier.tryParse(request.getItem().getItemId());
            if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
              throw Status.INVALID_ARGUMENT
                .withDescription("Unknown item id: " + request.getItem().getItemId())
                .asRuntimeException();
            }
            var item = BuiltInRegistries.ITEM.getValue(identifier);
            if (request.getItem().getCount() < 1
              || request.getItem().getCount() > item.getDefaultMaxStackSize()) {
              throw Status.INVALID_ARGUMENT
                .withDescription(
                  "Creative item count must be between one and %d"
                    .formatted(item.getDefaultMaxStackSize()))
                .asRuntimeException();
            }
            stack = new ItemStack(item, request.getItem().getCount());
          }
          player.connection.send(new ServerboundSetCreativeModeSlotPacket(
            request.getSlot(),
            stack
          ));
        }
      ),
      DEFAULT_ACTION_TIMEOUT,
      result -> SetCreativeSlotResponse.newBuilder().setResult(result).build(),
      responseObserver
    );
  }

  @Override
  public void waitForChunks(
    WaitForChunksRequest request,
    StreamObserver<WaitForChunksResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(
        InstancePermission.READ_BOT_INFO,
        instanceId));

    var requestedRadius = Integer.toUnsignedLong(request.getRadiusChunks());
    if (requestedRadius > MAX_CHUNK_WAIT_RADIUS) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Chunk wait radius must not exceed " + MAX_CHUNK_WAIT_RADIUS)
        .asRuntimeException();
    }
    var requestedTimeoutMillis = Integer.toUnsignedLong(request.getTimeoutMs());
    var timeout = requestedTimeoutMillis == 0
      ? DEFAULT_CHUNK_WAIT_TIMEOUT
      : Duration.ofMillis(requestedTimeoutMillis);
    if (timeout.compareTo(MAX_CHUNK_WAIT_TIMEOUT) > 0) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Chunk wait timeout must not exceed " + MAX_CHUNK_WAIT_TIMEOUT.toMillis() + " ms")
        .asRuntimeException();
    }

    var bot = requireOnlineBot(soulFireServer, instanceId, botId);
    var serverObserver = (ServerCallStreamObserver<WaitForChunksResponse>) responseObserver;
    var completed = new AtomicBoolean();
    var deadlineNanos = System.nanoTime() + timeout.toNanos();
    serverObserver.setOnCancelHandler(() -> completed.set(true));

    var poll = new Runnable() {
      @Override
      public void run() {
        if (completed.get() || serverObserver.isCancelled()) {
          return;
        }
        try {
          var snapshot = callInBotContext(
            bot,
            () -> chunkLoadSnapshot(bot, (int) requestedRadius));
          if (snapshot.loadedChunks() == snapshot.requiredChunks()) {
            if (!completed.compareAndSet(false, true)) {
              return;
            }
            synchronized (serverObserver) {
              if (serverObserver.isCancelled()) {
                return;
              }
              serverObserver.onNext(WaitForChunksResponse.newBuilder()
                .setCenterChunkX(snapshot.centerChunkX())
                .setCenterChunkZ(snapshot.centerChunkZ())
                .setLoadedChunks(snapshot.loadedChunks())
                .setRequiredChunks(snapshot.requiredChunks())
                .setDimension(snapshot.dimension())
                .build());
              serverObserver.onCompleted();
            }
            return;
          }
          if (System.nanoTime() >= deadlineNanos) {
            if (completed.compareAndSet(false, true)) {
              serverObserver.onError(Status.DEADLINE_EXCEEDED
                .withDescription(
                  "Timed out waiting for chunks: %d of %d loaded"
                    .formatted(snapshot.loadedChunks(), snapshot.requiredChunks()))
                .asRuntimeException());
            }
            return;
          }
          bot.scheduler().schedule(this, CHUNK_WAIT_POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
          if (completed.compareAndSet(false, true)) {
            serverObserver.onError(toGrpcError("Failed while waiting for chunks", t));
          }
        }
      }
    };

    try {
      bot.scheduler().execute(poll);
    } catch (Throwable t) {
      if (completed.compareAndSet(false, true)) {
        responseObserver.onError(toGrpcError("Failed to schedule chunk wait", t));
      }
    }
  }

  // =====================================================================
  // GoTo
  // =====================================================================

  @Override
  public void goTo(GoToRequest request, StreamObserver<PathfindProgress> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var bot = requireControlledOnlineBot(soulFireServer, instanceId, botId);
    var serverObserver = (ServerCallStreamObserver<PathfindProgress>) responseObserver;
    WorldPositionSupplier goalPositionSupplier;
    try {
      var resolved = PathfindingSupport.resolveGoal(bot, request.getGoal());
      goalPositionSupplier = resolved.position()::apply;
    } catch (Throwable t) {
      responseObserver.onError(toGrpcError("Failed to resolve pathfinding goal", t));
      return;
    }

    BotTask task;
    try {
      var pathTimeout = normalizePathTimeout(request.getOptions().getTimeoutSeconds());
      task = soulFireServer.botTaskManager().start(
        StartBotTaskRequest.newBuilder()
          .setInstanceId(request.getInstanceId())
          .setBotId(request.getBotId())
          .setInput(Any.pack(GoToTask.newBuilder()
            .setGoal(request.getGoal())
            .setOptions(request.getOptions())
            .build()))
          .setConflictPolicy(BotTaskConflictPolicy.BOT_TASK_CONFLICT_POLICY_REPLACE)
          .setDisconnectPolicy(
            BotTaskDisconnectPolicy.BOT_TASK_DISCONNECT_POLICY_CANCEL_WITH_CALL)
          .setReconnectPolicy(BotTaskReconnectPolicy.BOT_TASK_RECONNECT_POLICY_FAIL)
          .setPriority(BotTaskPriority.BOT_TASK_PRIORITY_HIGH)
          .setDeadline(timestamp(Instant.now().plus(pathTimeout)))
          .build(),
        ServerRPCConstants.USER_CONTEXT_KEY.get()
      );
      serverObserver.onNext(PathfindProgress.newBuilder()
        .setStatus(pathStatus(task.getStatus()))
        .setActionId(task.getTaskId())
        .build());
    } catch (Throwable t) {
      responseObserver.onError(toGrpcError("Failed to start pathfinding task", t));
      return;
    }

    var taskId = UUID.fromString(task.getTaskId());
    var completed = new AtomicBoolean();
    var lastRevision = new AtomicLong(task.getRevision());
    var subscription = new AtomicReference<AutoCloseable>(() -> {
    });
    var actualSubscription = soulFireServer.botTaskManager().subscribe(event -> {
      var update = event.getTask();
      if (!update.getTaskId().equals(task.getTaskId())
        || update.getRevision() <= lastRevision.get()
        || completed.get()) {
        return;
      }
      lastRevision.set(update.getRevision());
      emitTaskProgress(
        taskId,
        bot,
        serverObserver,
        goalPositionSupplier,
        update
      );
      if (com.soulfiremc.server.task.BotTaskManager.isTerminal(update.getStatus())
        && completed.compareAndSet(false, true)) {
        closeQuietly(subscription.get());
        if (!serverObserver.isCancelled()) {
          serverObserver.onCompleted();
        }
      }
    });
    subscription.set(actualSubscription);
    if (completed.get()) {
      closeQuietly(actualSubscription);
      return;
    }
    serverObserver.setOnCancelHandler(() -> {
      if (completed.compareAndSet(false, true)) {
        closeQuietly(subscription.get());
        soulFireServer.botTaskManager().cancel(
          taskId,
          "Legacy pathfinding stream was cancelled"
        );
      }
    });
    var latest = soulFireServer.botTaskManager().get(taskId);
    if (latest.getRevision() > lastRevision.get()) {
      lastRevision.set(latest.getRevision());
      emitTaskProgress(taskId, bot, serverObserver, goalPositionSupplier, latest);
    }
    if (com.soulfiremc.server.task.BotTaskManager.isTerminal(latest.getStatus())
      && completed.compareAndSet(false, true)) {
      closeQuietly(subscription.get());
      if (!serverObserver.isCancelled()) {
        serverObserver.onCompleted();
      }
    }
  }

  private static Duration normalizePathTimeout(int timeoutSeconds) {
    if (timeoutSeconds <= 0) {
      return DEFAULT_PATH_TIMEOUT;
    }
    var requested = Duration.ofSeconds(timeoutSeconds);
    return requested.compareTo(MAX_PATH_TIMEOUT) > 0 ? MAX_PATH_TIMEOUT : requested;
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
      .setSeconds(instant.getEpochSecond())
      .setNanos(instant.getNano())
      .build();
  }

  private static void emitTaskProgress(
    UUID taskId,
    BotConnection bot,
    ServerCallStreamObserver<PathfindProgress> observer,
    WorldPositionSupplier goalPositionSupplier,
    BotTask task
  ) {
    var error = task.hasFailure() ? task.getFailure().getMessage() : null;
    emitProgress(
      taskId,
      bot,
      observer,
      goalPositionSupplier,
      pathStatus(task.getStatus()),
      error
    );
  }

  private static PathfindStatus pathStatus(BotTaskStatus status) {
    return switch (status) {
      case BOT_TASK_STATUS_QUEUED,
           BOT_TASK_STATUS_WAITING_FOR_RESOURCES ->
        PathfindStatus.PATHFIND_STATUS_PLANNING;
      case BOT_TASK_STATUS_RUNNING,
           BOT_TASK_STATUS_SUSPENDED,
           BOT_TASK_STATUS_RECOVERING ->
        PathfindStatus.PATHFIND_STATUS_MOVING;
      case BOT_TASK_STATUS_COMPLETED -> PathfindStatus.PATHFIND_STATUS_COMPLETED;
      case BOT_TASK_STATUS_CANCELLED -> PathfindStatus.PATHFIND_STATUS_CANCELLED;
      case BOT_TASK_STATUS_FAILED,
           BOT_TASK_STATUS_TIMED_OUT -> PathfindStatus.PATHFIND_STATUS_FAILED;
      case BOT_TASK_STATUS_UNSPECIFIED, UNRECOGNIZED ->
        PathfindStatus.PATHFIND_STATUS_UNSPECIFIED;
    };
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception exception) {
      log.debug("Failed to close task subscription", exception);
    }
  }

  private static void emitProgress(UUID actionId,
                                   BotConnection bot,
                                   ServerCallStreamObserver<PathfindProgress> observer,
                                   WorldPositionSupplier goalPosSupplier,
                                   PathfindStatus status,
                                   String error) {
    if (observer.isCancelled()) {
      return;
    }
    var progressBuilder = PathfindProgress.newBuilder()
      .setStatus(status)
      .setActionId(actionId.toString());
    try {
      var player = bot.minecraft().player;
      var level = bot.minecraft().level;
      if (player != null && level != null) {
        var dimension = level.dimension().identifier().toString();
        progressBuilder.setPosition(buildWorldPosition(player.position(), dimension));
        if (goalPosSupplier != null) {
          var goalPos = goalPosSupplier.get(bot);
          if (goalPos != null) {
            var dx = goalPos.x - player.getX();
            var dy = goalPos.y - player.getY();
            var dz = goalPos.z - player.getZ();
            progressBuilder.setDistanceRemaining((float) Math.sqrt(dx * dx + dy * dy + dz * dz));
          }
        }
      }
    } catch (Throwable t) {
      log.trace("Failed to enrich progress", t);
    }
    if (error != null) {
      progressBuilder.setError(error);
    }
    synchronized (observer) {
      if (!observer.isCancelled()) {
        observer.onNext(progressBuilder.build());
      }
    }
  }

  @FunctionalInterface
  private interface WorldPositionSupplier {
    Vec3 get(BotConnection bot);
  }

  // =====================================================================
  // StopPathfinding
  // =====================================================================

  @Override
  public void stopPathfinding(StopPathfindingRequest request, StreamObserver<StopPathfindingResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());

    try {
      requireControlledOnlineBot(soulFireServer, instanceId, botId);
      var statuses = Set.of(
        BotTaskStatus.BOT_TASK_STATUS_QUEUED,
        BotTaskStatus.BOT_TASK_STATUS_WAITING_FOR_RESOURCES,
        BotTaskStatus.BOT_TASK_STATUS_RUNNING,
        BotTaskStatus.BOT_TASK_STATUS_SUSPENDED,
        BotTaskStatus.BOT_TASK_STATUS_RECOVERING
      );
      var pathTasks = new ArrayList<BotTask>();
      var pageToken = "";
      do {
        var page = soulFireServer.botTaskManager().list(
          Optional.of(instanceId),
          Optional.of(botId),
          statuses,
          true,
          500,
          pageToken,
          ServerRPCConstants.USER_CONTEXT_KEY.get()
        );
        pathTasks.addAll(page.tasks().stream()
          .filter(task -> task.getTaskType().equals(
            "type.googleapis.com/soulfire.v1.GoToTask"))
          .toList());
        pageToken = page.nextPageToken();
      } while (!pageToken.isBlank());
      pathTasks.forEach(task -> soulFireServer.botTaskManager().cancel(
        UUID.fromString(task.getTaskId()),
        "Pathfinding was stopped"
      ));
      responseObserver.onNext(StopPathfindingResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      responseObserver.onError(toGrpcError("Failed to stop pathfinding", t));
    }
  }

  @Override
  public void acquireBotControl(
    AcquireBotControlRequest request,
    StreamObserver<AcquireBotControlResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(
        InstancePermission.CONTROL_BOT_ACTIONS,
        instanceId));
    var instance = requireConfiguredBot(soulFireServer, instanceId, botId);
    try {
      var lease = instance.botControlLeaseManager().acquire(
        botId,
        ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId(),
        Duration.ofSeconds(request.getTtlSeconds()));
      responseObserver.onNext(AcquireBotControlResponse.newBuilder()
        .setLease(buildControlLease(lease))
        .build());
      responseObserver.onCompleted();
    } catch (BotControlLeaseManager.LeaseUnavailableException e) {
      responseObserver.onError(Status.ALREADY_EXISTS
        .withDescription(e.getMessage())
        .asRuntimeException());
    }
  }

  @Override
  public void renewBotControl(
    RenewBotControlRequest request,
    StreamObserver<RenewBotControlResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(
        InstancePermission.CONTROL_BOT_ACTIONS,
        instanceId));
    var instance = requireConfiguredBot(soulFireServer, instanceId, botId);
    try {
      var lease = instance.botControlLeaseManager().renew(
        botId,
        ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId(),
        request.getToken(),
        Duration.ofSeconds(request.getTtlSeconds()));
      responseObserver.onNext(RenewBotControlResponse.newBuilder()
        .setLease(buildControlLease(lease))
        .build());
      responseObserver.onCompleted();
    } catch (BotControlLeaseManager.InvalidLeaseException e) {
      responseObserver.onError(Status.PERMISSION_DENIED
        .withDescription(e.getMessage())
        .asRuntimeException());
    }
  }

  @Override
  public void releaseBotControl(
    ReleaseBotControlRequest request,
    StreamObserver<ReleaseBotControlResponse> responseObserver
  ) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(
        InstancePermission.CONTROL_BOT_ACTIONS,
        instanceId));
    var instance = requireConfiguredBot(soulFireServer, instanceId, botId);
    try {
      instance.botControlLeaseManager().release(
        botId,
        ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId(),
        request.getToken());
      responseObserver.onNext(ReleaseBotControlResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (BotControlLeaseManager.InvalidLeaseException e) {
      responseObserver.onError(Status.PERMISSION_DENIED
        .withDescription(e.getMessage())
        .asRuntimeException());
    }
  }

  private static BotControlLease buildControlLease(BotControlLeaseManager.Lease lease) {
    return BotControlLease.newBuilder()
      .setToken(lease.token())
      .setExpiresAt(Timestamp.newBuilder()
        .setSeconds(lease.expiresAt().getEpochSecond())
        .setNanos(lease.expiresAt().getNano())
        .build())
      .build();
  }

  private static final class MountControl implements ControlTask {
    private static final int CONFIRMATION_TIMEOUT_TICKS = 100;

    private final BotConnection bot;
    private final int entityId;
    private final InteractionHand hand;
    private final AtomicReference<EntityReference> mountedVehicle;
    private boolean requested;
    private boolean done;
    private int ticks;

    private MountControl(
      BotConnection bot,
      int entityId,
      InteractionHand hand,
      AtomicReference<EntityReference> mountedVehicle
    ) {
      this.bot = bot;
      this.entityId = entityId;
      this.hand = hand;
      this.mountedVehicle = mountedVehicle;
    }

    @Override
    public void tick() {
      if (done) {
        return;
      }
      var gameMode = bot.minecraft().gameMode;
      var player = bot.minecraft().player;
      var level = bot.minecraft().level;
      if (gameMode == null || player == null || level == null) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot player, level, or game mode is not available")
          .asRuntimeException();
      }
      var vehicle = player.getVehicle();
      if (vehicle != null) {
        if (vehicle.getId() != entityId) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot is already riding a different entity")
            .asRuntimeException();
        }
        mountedVehicle.set(MinecraftDomainMapper.reference(bot, vehicle));
        done = true;
        return;
      }
      if (!requested) {
        var target = findEntityById(level, entityId);
        if (target == null) {
          throw Status.NOT_FOUND
            .withDescription("Mount target is not observable")
            .asRuntimeException();
        }
        if (target.distanceToSqr(player) > 36.0D) {
          throw Status.OUT_OF_RANGE
            .withDescription("Mount target is outside the bot's interaction reach")
            .asRuntimeException();
        }
        var swingAnimation = player.getItemInHand(hand).getInteractAnimation();
        var result = gameMode.interact(
          player,
          target,
          new EntityHitResult(target),
          hand
        );
        if (!(result instanceof InteractionResult.Success success)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The target entity rejected the mount interaction")
            .asRuntimeException();
        }
        if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
          player.swing(hand, swingAnimation, false);
        }
        requested = true;
      }
      ticks++;
      if (ticks >= CONFIRMATION_TIMEOUT_TICKS) {
        throw Status.FAILED_PRECONDITION
          .withDescription("The server did not confirm the mounted state")
          .asRuntimeException();
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public Set<ControlResource> resources() {
      return Set.of(
        ControlResource.MOVEMENT,
        ControlResource.VEHICLE,
        hand == InteractionHand.MAIN_HAND
          ? ControlResource.MAIN_HAND
          : ControlResource.OFF_HAND
      );
    }

    @Override
    public String description() {
      return "SDK mount entity";
    }
  }

  private static final class DismountControl implements ControlTask {
    private static final int CONFIRMATION_TIMEOUT_TICKS = 100;

    private final BotConnection bot;
    private boolean requested;
    private boolean done;
    private int ticks;

    private DismountControl(BotConnection bot) {
      this.bot = bot;
    }

    @Override
    public void tick() {
      if (done) {
        return;
      }
      var player = bot.minecraft().player;
      if (player == null) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot player is not available")
          .asRuntimeException();
      }
      if (!requested && player.getVehicle() == null) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot is not riding a vehicle")
          .asRuntimeException();
      }
      if (player.getVehicle() == null) {
        bot.controlState().shift(false);
        done = true;
        return;
      }
      bot.controlState().shift(true);
      requested = true;
      ticks++;
      if (ticks >= CONFIRMATION_TIMEOUT_TICKS) {
        throw Status.FAILED_PRECONDITION
          .withDescription("The server did not confirm the dismounted state")
          .asRuntimeException();
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public Set<ControlResource> resources() {
      return Set.of(ControlResource.MOVEMENT, ControlResource.VEHICLE);
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      bot.controlState().shift(false);
      done = true;
    }

    @Override
    public String description() {
      return "SDK dismount vehicle";
    }
  }

  private static final class SleepControl implements ControlTask {
    private static final int CONFIRMATION_TIMEOUT_TICKS = 100;
    private static final Set<ControlResource> RESOURCES = Set.of(
      ControlResource.MOVEMENT,
      ControlResource.ROTATION,
      ControlResource.MAIN_HAND
    );

    private final BotConnection bot;
    private final BlockPos bed;
    private final InteractionHand hand;
    private boolean requested;
    private boolean done;
    private int ticks;

    private SleepControl(
      BotConnection bot,
      BlockPos bed,
      InteractionHand hand
    ) {
      this.bot = bot;
      this.bed = bed;
      this.hand = hand;
    }

    @Override
    public void tick() {
      if (done) {
        return;
      }
      var gameMode = bot.minecraft().gameMode;
      var player = bot.minecraft().player;
      var level = bot.minecraft().level;
      if (gameMode == null || player == null || level == null) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot player, level, or game mode is not available")
          .asRuntimeException();
      }
      if (player.isSleeping()) {
        done = true;
        return;
      }
      if (!requested) {
        if (!level.hasChunkAt(bed)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bed is not loaded")
            .asRuntimeException();
        }
        if (!(level.getBlockState(bed).getBlock() instanceof BedBlock)) {
          throw Status.INVALID_ARGUMENT
            .withDescription("Target block is not a bed")
            .asRuntimeException();
        }
        requireReach(player, bed);
        var swingAnimation = player.getItemInHand(hand).getInteractAnimation();
        var result = gameMode.useItemOn(
          player,
          hand,
          new BlockHitResult(
            Vec3.atCenterOf(bed).add(0, 0.5, 0),
            Direction.UP,
            bed,
            false
          )
        );
        if (!(result instanceof InteractionResult.Success success)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("The bed rejected the sleep interaction")
            .asRuntimeException();
        }
        if (
          success.swingSource()
            == InteractionResult.SwingSource.PREDICTED
        ) {
          player.swing(hand, swingAnimation, false);
        }
        requested = true;
      }
      ticks++;
      if (ticks >= CONFIRMATION_TIMEOUT_TICKS) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "The server did not confirm the sleeping state"
          )
          .asRuntimeException();
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public Set<ControlResource> resources() {
      return RESOURCES;
    }

    @Override
    public String description() {
      return "SDK sleep in bed";
    }
  }

  private static final class BotEventContext {
    private final UUID botId;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong snapshotRevision = new AtomicLong();
    private final AtomicBoolean dropped = new AtomicBoolean();
    private final AtomicReference<UUID> epoch = new AtomicReference<>(UUID.randomUUID());

    private BotEventContext(UUID botId) {
      this.botId = botId;
    }

    private BotEvent decorate(BotEvent event) {
      var observedAt = Instant.now();
      var revision = event.hasSnapshot()
        ? snapshotRevision.incrementAndGet()
        : snapshotRevision.get();
      return event.toBuilder()
        .setEnvelope(BotEventEnvelope.newBuilder()
          .setBotId(botId.toString())
          .setStreamEpoch(epoch.get().toString())
          .setSequence(sequence.incrementAndGet())
          .setObservedAt(Timestamp.newBuilder()
            .setSeconds(observedAt.getEpochSecond())
            .setNanos(observedAt.getNano()))
          .setSnapshotRevision(revision))
        .build();
    }

    private void newEpoch() {
      epoch.set(UUID.randomUUID());
      sequence.set(0);
      snapshotRevision.set(0);
    }

    private void markDropped() {
      dropped.set(true);
    }

    private boolean consumeDropped() {
      return dropped.getAndSet(false);
    }
  }

}
