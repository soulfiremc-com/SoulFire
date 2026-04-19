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

import com.google.protobuf.Timestamp;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.adventure.SoulFireAdventure;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.bot.BotPostTickEvent;
import com.soulfiremc.server.api.event.bot.ChatMessageReceiveEvent;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.pathfinding.goals.PosGoal;
import com.soulfiremc.server.pathfinding.goals.XZGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import com.soulfiremc.server.user.PermissionContext;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

/// BotLiveService is the automation-first API for SoulFire bots. It provides the
/// streaming event channel, imperative per-position / per-entity actions, world
/// queries, and pathfinding RPCs that make the public gRPC surface feel like a
/// mineflayer/azalea style bot library.
@Slf4j
@RequiredArgsConstructor
public final class BotLiveServiceImpl extends BotLiveServiceGrpc.BotLiveServiceImplBase {
  private static final int MAX_FIND_BLOCKS_DISTANCE = 128;
  private static final int MAX_FIND_BLOCKS_COUNT = 256;
  private static final float MAX_ENTITY_RADIUS = 128.0F;
  private static final long PATH_PROGRESS_INTERVAL_MS = 500L;

  private final SoulFireServer soulFireServer;
  private final ConcurrentHashMap<UUID, AtomicReference<CompletableFuture<Void>>> activePaths =
    new ConcurrentHashMap<>();

  private static <T> T callInBotContext(BotConnection botConnection, java.util.concurrent.Callable<T> callable) throws Exception {
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

  private static com.soulfiremc.grpc.generated.BlockPosition toProtoBlockPosition(BlockPos pos, String dimension) {
    return com.soulfiremc.grpc.generated.BlockPosition.newBuilder()
      .setX(pos.getX())
      .setY(pos.getY())
      .setZ(pos.getZ())
      .setDimension(dimension)
      .build();
  }

  private static BlockPos toMcBlockPos(com.soulfiremc.grpc.generated.BlockPosition pos) {
    return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
  }

  private static com.soulfiremc.grpc.generated.BlockState buildBlockState(BlockPos pos, BlockState state, String dimension) {
    var builder = com.soulfiremc.grpc.generated.BlockState.newBuilder()
      .setPosition(toProtoBlockPosition(pos, dimension))
      .setBlockId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    for (var property : state.getProperties()) {
      @SuppressWarnings({"rawtypes", "unchecked"})
      var name = ((Property) property).getName();
      builder.putProperties(name, getPropertyValueAsString(state, property));
    }
    return builder.build();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String getPropertyValueAsString(BlockState state, Property property) {
    return property.getName(state.getValue(property));
  }

  private static com.soulfiremc.grpc.generated.WorldPosition buildWorldPosition(Vec3 pos, String dimension) {
    return com.soulfiremc.grpc.generated.WorldPosition.newBuilder()
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
    if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
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

    var bot = requireOnlineBot(soulFireServer, instanceId, botId);
    var filter = request.getFilter();
    var serverObserver = (ServerCallStreamObserver<BotEvent>) responseObserver;
    var closed = new AtomicBoolean(false);

    // Per-subscription state: last emitted snapshot fields so we can diff.
    var lastState = new AtomicReference<BotLiveState>(null);

    // Emit the initial snapshot.
    try {
      var snapshot = callInBotContext(bot, () -> {
        var mc = bot.minecraft();
        var player = mc.player;
        if (player == null) {
          return null;
        }
        return BotServiceImpl.buildLiveStatePublic(mc, player, false);
      });
      if (snapshot != null) {
        lastState.set(snapshot);
        serverObserver.onNext(BotEvent.newBuilder().setSnapshot(snapshot).build());
      }
    } catch (Exception e) {
      responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
      return;
    }

    // Event listeners — created and registered only if the corresponding
    // category is enabled in the filter.
    Consumer<BotPostTickEvent> stateListener = null;
    Consumer<ChatMessageReceiveEvent> chatListener = null;
    Consumer<BotDisconnectedEvent> disconnectListener = null;

    if (filter.getIncludeStateDeltas()) {
      stateListener = event -> {
        if (closed.get()) return;
        if (event.connection() != bot) return;
        try {
          var next = callInBotContext(bot, () -> {
            var mc = bot.minecraft();
            var player = mc.player;
            return player != null ? BotServiceImpl.buildLiveStatePublic(mc, player, false) : null;
          });
          if (next == null) return;
          var prev = lastState.getAndSet(next);
          var delta = computeDelta(prev, next);
          if (delta != null) {
            synchronized (serverObserver) {
              if (!closed.get() && !serverObserver.isCancelled()) {
                serverObserver.onNext(BotEvent.newBuilder().setStateDelta(delta).build());
              }
            }
          }
        } catch (Exception e) {
          log.debug("Error computing state delta", e);
        }
      };
      SoulFireAPI.registerListener(BotPostTickEvent.class, stateListener);
    }

    if (filter.getIncludeChat()) {
      chatListener = event -> {
        if (closed.get()) return;
        if (event.connection() != bot) return;
        var plain = event.parseToPlainText();
        var json = GsonComponentSerializer.gson().serialize(event.message());
        var nowSec = Instant.ofEpochMilli(event.timestamp()).getEpochSecond();
        var chatBuilder = BotChatEvent.newBuilder()
          .setSource(ChatSource.CHAT_SOURCE_SYSTEM)
          .setPlainText(plain)
          .setJsonComponent(json)
          .setReceivedAt(Timestamp.newBuilder().setSeconds(nowSec).build());
        synchronized (serverObserver) {
          if (!closed.get() && !serverObserver.isCancelled()) {
            serverObserver.onNext(BotEvent.newBuilder().setChat(chatBuilder.build()).build());
          }
        }
      };
      SoulFireAPI.registerListener(ChatMessageReceiveEvent.class, chatListener);
    }

    if (filter.getIncludeLifecycle()) {
      disconnectListener = event -> {
        if (closed.get()) return;
        if (event.connection() != bot) return;
        var reason = event.message() == null
          ? ""
          : SoulFireAdventure.PLAIN_MESSAGE_SERIALIZER.serialize(event.message());
        var lifecycle = BotLifecycleEvent.newBuilder()
          .setKind(BotLifecycleKind.BOT_LIFECYCLE_DISCONNECTED)
          .setMessage(reason)
          .build();
        synchronized (serverObserver) {
          if (!closed.get() && !serverObserver.isCancelled()) {
            serverObserver.onNext(BotEvent.newBuilder().setLifecycle(lifecycle).build());
          }
        }
      };
      SoulFireAPI.registerListener(BotDisconnectedEvent.class, disconnectListener);
    }

    var finalStateListener = stateListener;
    var finalChatListener = chatListener;
    var finalDisconnectListener = disconnectListener;

    serverObserver.setOnCancelHandler(() -> {
      if (!closed.compareAndSet(false, true)) return;
      if (finalStateListener != null) {
        SoulFireAPI.unregisterListener(BotPostTickEvent.class, finalStateListener);
      }
      if (finalChatListener != null) {
        SoulFireAPI.unregisterListener(ChatMessageReceiveEvent.class, finalChatListener);
      }
      if (finalDisconnectListener != null) {
        SoulFireAPI.unregisterListener(BotDisconnectedEvent.class, finalDisconnectListener);
      }
    });
  }

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
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var message = request.getMessage();
      callInBotContext(bot, () -> {
        bot.sendChatMessage(message);
        return null;
      });
      responseObserver.onNext(SendChatResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error sending chat", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
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
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
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

        var matchSet = new java.util.HashSet<>(blockIds);
        var origin = player.blockPosition();
        var dimension = level.dimension().identifier().toString();

        // Collect matches with their squared distance, then sort ascending.
        var matches = new java.util.ArrayList<ScoredMatch>();
        var radius = maxDistance;
        for (var dx = -radius; dx <= radius; dx++) {
          for (var dy = -radius; dy <= radius; dy++) {
            for (var dz = -radius; dz <= radius; dz++) {
              var pos = origin.offset(dx, dy, dz);
              if (!level.hasChunkAt(pos)) continue;
              var state = level.getBlockState(pos);
              var id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
              if (!matchSet.contains(id)) continue;
              var sqDistance = origin.distSqr(pos);
              if (sqDistance > (double) radius * radius) continue;
              matches.add(new ScoredMatch(pos.immutable(), state, sqDistance));
            }
          }
        }

        matches.sort(java.util.Comparator.comparingDouble(ScoredMatch::sqDistance));
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
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
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
        var typeSet = typeFilter.isEmpty() ? null : new java.util.HashSet<>(typeFilter);

        var results = StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
          .filter(entity -> entity != player)
          .filter(entity -> includePlayers || !(entity instanceof Player))
          .filter(entity -> {
            if (typeSet == null) return true;
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return typeSet.contains(id);
          })
          .filter(entity -> entity.position().distanceToSqr(origin) <= (double) radius * radius)
          .sorted(java.util.Comparator.comparingDouble(e -> e.position().distanceToSqr(origin)))
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
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  // =====================================================================
  // DigBlock
  // =====================================================================

  @Override
  public void digBlock(DigBlockRequest request, StreamObserver<DigBlockResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var pos = toMcBlockPos(request.getPosition());
      var cancel = request.getCancel();

      callInBotContext(bot, () -> {
        if (bot.minecraft().gameMode == null || bot.minecraft().player == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Bot player or game mode is not available")
            .asRuntimeException();
        }
        bot.botControl().replace(ControlTask.once(() -> {
          var gameMode = bot.minecraft().gameMode;
          var player = bot.minecraft().player;
          if (gameMode == null || player == null) return;
          if (cancel) {
            gameMode.stopDestroyBlock();
            return;
          }
          // Pick a face facing the player. UP is a safe default for floors; use
          // closest face derived from look direction if possible.
          var face = nearestFaceTo(player.getEyePosition(), pos);
          gameMode.startDestroyBlock(pos, face);
          player.swing(InteractionHand.MAIN_HAND);
        }));
        return null;
      });

      responseObserver.onNext(DigBlockResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error digging block", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  private static Direction nearestFaceTo(Vec3 eyePos, BlockPos target) {
    var center = target.getCenter();
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

  // =====================================================================
  // PlaceBlock
  // =====================================================================

  @Override
  public void placeBlock(PlaceBlockRequest request, StreamObserver<PlaceBlockResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var against = toMcBlockPos(request.getAgainst());
      var direction = toMcDirection(request.getFace());
      var hand = toMcHand(request.getHand());

      callInBotContext(bot, () -> {
        bot.botControl().replace(ControlTask.once(() -> {
          var gameMode = bot.minecraft().gameMode;
          var player = bot.minecraft().player;
          if (gameMode == null || player == null) return;
          // Construct a synthetic BlockHitResult pointing at the face center.
          var hitPos = Vec3.atCenterOf(against)
            .add(direction.getStepX() * 0.5, direction.getStepY() * 0.5, direction.getStepZ() * 0.5);
          var hit = new BlockHitResult(hitPos, direction, against, false);
          if (gameMode.useItemOn(player, hand, hit) instanceof InteractionResult.Success success
            && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
          }
        }));
        return null;
      });

      responseObserver.onNext(PlaceBlockResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error placing block", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  // =====================================================================
  // UseItem
  // =====================================================================

  @Override
  public void useItem(UseItemRequest request, StreamObserver<UseItemResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var hand = toMcHand(request.getHand());

      callInBotContext(bot, () -> {
        bot.botControl().replace(ControlTask.once(() -> {
          var gameMode = bot.minecraft().gameMode;
          var player = bot.minecraft().player;
          if (gameMode == null || player == null) return;
          if (gameMode.useItem(player, hand) instanceof InteractionResult.Success success
            && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
          }
        }));
        return null;
      });

      responseObserver.onNext(UseItemResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error using item", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  // =====================================================================
  // AttackEntity
  // =====================================================================

  @Override
  public void attackEntity(AttackEntityRequest request, StreamObserver<AttackEntityResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var entityId = request.getEntityId();

      callInBotContext(bot, () -> {
        bot.botControl().replace(ControlTask.once(() -> {
          var gameMode = bot.minecraft().gameMode;
          var player = bot.minecraft().player;
          var level = bot.minecraft().level;
          if (gameMode == null || player == null || level == null) return;
          var target = findEntityById(level, entityId);
          if (target == null) {
            log.debug("AttackEntity: entity id {} not found", entityId);
            return;
          }
          gameMode.attack(player, target);
          player.swing(InteractionHand.MAIN_HAND);
        }));
        return null;
      });

      responseObserver.onNext(AttackEntityResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error attacking entity", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  private static Entity findEntityById(net.minecraft.client.multiplayer.ClientLevel level, int id) {
    return StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
      .filter(e -> e.getId() == id)
      .findFirst()
      .orElse(null);
  }

  // =====================================================================
  // InteractEntity
  // =====================================================================

  @Override
  public void interactEntity(InteractEntityRequest request, StreamObserver<InteractEntityResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var entityId = request.getEntityId();
      var hand = toMcHand(request.getHand());
      // NOTE: the `sneaking` request field is currently advisory — Minecraft
      // derives sneak state from the player's control state at execution time.

      callInBotContext(bot, () -> {
        bot.botControl().replace(ControlTask.once(() -> {
          var gameMode = bot.minecraft().gameMode;
          var player = bot.minecraft().player;
          var level = bot.minecraft().level;
          if (gameMode == null || player == null || level == null) return;
          var target = findEntityById(level, entityId);
          if (target == null) {
            log.debug("InteractEntity: entity id {} not found", entityId);
            return;
          }
          if (gameMode.interact(player, target, new EntityHitResult(target), hand) instanceof InteractionResult.Success success
            && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
          }
        }));
        return null;
      });

      responseObserver.onNext(InteractEntityResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error interacting with entity", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  // =====================================================================
  // SwingArm
  // =====================================================================

  @Override
  public void swingArm(SwingArmRequest request, StreamObserver<SwingArmResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var bot = requireOnlineBot(soulFireServer, instanceId, botId);
      var hand = toMcHand(request.getHand());

      callInBotContext(bot, () -> {
        bot.botControl().replace(ControlTask.once(() -> {
          var player = bot.minecraft().player;
          if (player == null) return;
          player.swing(hand);
        }));
        return null;
      });

      responseObserver.onNext(SwingArmResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error swinging arm", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  // =====================================================================
  // GoTo
  // =====================================================================

  @Override
  public void goTo(GoToRequest request, StreamObserver<PathfindProgress> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    var bot = requireOnlineBot(soulFireServer, instanceId, botId);
    var serverObserver = (ServerCallStreamObserver<PathfindProgress>) responseObserver;

    // Resolve the goal.
    GoalScorer goalScorer;
    WorldPositionSupplier goalPositionSupplier;
    try {
      var resolved = resolveGoal(bot, request.getGoal());
      goalScorer = resolved.scorer();
      goalPositionSupplier = resolved.positionSupplier();
    } catch (Throwable t) {
      responseObserver.onError(Status.INVALID_ARGUMENT
        .withDescription(t.getMessage())
        .withCause(t)
        .asRuntimeException());
      return;
    }

    // Cancel any prior path for this bot.
    var priorRef = activePaths.get(botId);
    if (priorRef != null) {
      var priorFuture = priorRef.get();
      if (priorFuture != null && !priorFuture.isDone()) {
        priorFuture.cancel(true);
      }
    }

    // Send PLANNING immediately.
    try {
      serverObserver.onNext(PathfindProgress.newBuilder()
        .setStatus(PathfindStatus.PATHFIND_STATUS_PLANNING)
        .build());
    } catch (Throwable t) {
      log.debug("Failed to emit PLANNING", t);
      return;
    }

    CompletableFuture<Void> future;
    try {
      future = callInBotContext(bot, () -> PathExecutor.executePathfinding(bot, goalScorer, new PathConstraintImpl(bot)));
    } catch (Throwable t) {
      responseObserver.onError(Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException());
      return;
    }

    var ref = activePaths.computeIfAbsent(botId, k -> new AtomicReference<>());
    ref.set(future);

    // Send MOVING once on start.
    emitProgress(bot, serverObserver, goalPositionSupplier, PathfindStatus.PATHFIND_STATUS_MOVING, null);

    // Schedule periodic MOVING updates until completion.
    var completed = new AtomicBoolean(false);
    Runnable schedule = new Runnable() {
      @Override
      public void run() {
        if (completed.get() || serverObserver.isCancelled()) return;
        if (future.isDone()) return;
        emitProgress(bot, serverObserver, goalPositionSupplier, PathfindStatus.PATHFIND_STATUS_MOVING, null);
        soulFireServer.scheduler().schedule(this, PATH_PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS);
      }
    };
    soulFireServer.scheduler().schedule(schedule, PATH_PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS);

    serverObserver.setOnCancelHandler(() -> {
      completed.set(true);
      if (!future.isDone()) {
        future.cancel(true);
      }
      activePaths.remove(botId, ref);
    });

    future.whenComplete((result, error) -> {
      if (!completed.compareAndSet(false, true)) return;
      activePaths.remove(botId, ref);
      if (serverObserver.isCancelled()) return;
      try {
        if (error == null) {
          emitProgress(bot, serverObserver, goalPositionSupplier, PathfindStatus.PATHFIND_STATUS_COMPLETED, null);
        } else if (error instanceof java.util.concurrent.CancellationException) {
          emitProgress(bot, serverObserver, goalPositionSupplier, PathfindStatus.PATHFIND_STATUS_CANCELLED, "cancelled");
        } else {
          var msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
          emitProgress(bot, serverObserver, goalPositionSupplier, PathfindStatus.PATHFIND_STATUS_FAILED, msg);
        }
        serverObserver.onCompleted();
      } catch (Throwable t) {
        log.debug("Failed to emit final pathfind progress", t);
      }
    });
  }

  private static void emitProgress(BotConnection bot,
                                   ServerCallStreamObserver<PathfindProgress> observer,
                                   WorldPositionSupplier goalPosSupplier,
                                   PathfindStatus status,
                                   String error) {
    if (observer.isCancelled()) return;
    var progressBuilder = PathfindProgress.newBuilder().setStatus(status);
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

  private record ResolvedGoal(GoalScorer scorer, WorldPositionSupplier positionSupplier) {}

  @FunctionalInterface
  private interface WorldPositionSupplier {
    Vec3 get(BotConnection bot);
  }

  private static ResolvedGoal resolveGoal(BotConnection bot, PathfindGoal goal) {
    return switch (goal.getGoalCase()) {
      case BLOCK -> {
        var block = goal.getBlock();
        var pos = toMcBlockPos(block.getPosition());
        var vec = SFVec3i.from(pos.getX(), pos.getY(), pos.getZ());
        var radius = Math.max(1, Math.round(block.getRadius()));
        var scorer = block.getRadius() <= 0
          ? (GoalScorer) new PosGoal(vec)
          : new CloseToPosGoal(vec, radius);
        WorldPositionSupplier supplier = b -> Vec3.atCenterOf(pos);
        yield new ResolvedGoal(scorer, supplier);
      }
      case NEAR -> {
        var near = goal.getNear();
        var pos = near.getPosition();
        var vec = SFVec3i.fromDouble(new Vec3(pos.getX(), pos.getY(), pos.getZ()));
        var radius = Math.max(1, Math.round(near.getRadius()));
        yield new ResolvedGoal(
          new CloseToPosGoal(vec, radius),
          b -> new Vec3(pos.getX(), pos.getY(), pos.getZ()));
      }
      case ENTITY -> {
        var entityGoal = goal.getEntity();
        var id = entityGoal.getEntityId();
        // Snapshot the entity's position at resolution time. Live follow is not
        // yet implemented; clients can re-submit GoTo to retarget.
        var level = bot.minecraft().level;
        if (level == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Level not loaded").asRuntimeException();
        }
        var entity = findEntityById(level, id);
        if (entity == null) {
          throw Status.NOT_FOUND.withDescription("Entity '%d' not observable".formatted(id)).asRuntimeException();
        }
        var entityPos = entity.position();
        var vec = SFVec3i.fromDouble(entityPos);
        var radius = Math.max(1, Math.round(entityGoal.getRadius()));
        yield new ResolvedGoal(
          new CloseToPosGoal(vec, radius),
          b -> {
            var live = b.minecraft().level;
            if (live == null) return entityPos;
            var found = findEntityById(live, id);
            return found == null ? entityPos : found.position();
          });
      }
      case XZ -> {
        var xz = goal.getXz();
        var scorer = new XZGoal((int) Math.round(xz.getX()), (int) Math.round(xz.getZ()));
        yield new ResolvedGoal(
          scorer,
          b -> {
            var player = b.minecraft().player;
            var y = player != null ? player.getY() : 0.0;
            return new Vec3(xz.getX(), y, xz.getZ());
          });
      }
      case GOAL_NOT_SET ->
        throw Status.INVALID_ARGUMENT.withDescription("goal must be set").asRuntimeException();
    };
  }

  // =====================================================================
  // StopPathfinding
  // =====================================================================

  @Override
  public void stopPathfinding(StopPathfindingRequest request, StreamObserver<StopPathfindingResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      // Validate instance/bot existence for consistent error behavior.
      requireOnlineBot(soulFireServer, instanceId, botId);
      var ref = activePaths.get(botId);
      if (ref != null) {
        var future = ref.get();
        if (future != null && !future.isDone()) {
          future.cancel(true);
        }
      }
      responseObserver.onNext(StopPathfindingResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error stopping pathfinding", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }
}
