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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.protobuf.util.Timestamps;
import com.mojang.authlib.GameProfile;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.mod.access.IDialogScreen;
import com.soulfiremc.mod.access.IMenuIdentity;
import com.soulfiremc.mod.mixin.soulfire.AnvilScreenAccessor;
import com.soulfiremc.mod.mixin.soulfire.CrafterScreenAccessor;
import com.soulfiremc.mod.mixin.soulfire.MerchantScreenAccessor;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotControlLeaseManager;
import com.soulfiremc.server.bot.CompletableControlTask;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.plugins.DialogHandler;
import com.soulfiremc.server.renderer.InventoryItemIconRenderer;
import com.soulfiremc.server.renderer.RenderConstants;
import com.soulfiremc.server.renderer.VulkanRenderer;
import com.soulfiremc.server.settings.lib.InstanceSettingsImpl;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.util.GrpcErrors;
import com.soulfiremc.server.util.MouseClickHelper;
import com.soulfiremc.server.util.SFBlockHelpers;
import com.soulfiremc.server.util.SocketAddressHelper;
import com.soulfiremc.server.util.structs.GsonInstance;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.inventory.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jooq.impl.DSL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RequiredArgsConstructor
public final class BotServiceImpl extends BotServiceGrpc.BotServiceImplBase {
  private final SoulFireServer soulFireServer;

  /// Runtime snapshot for a single bot computed inside the bot's context, used
  /// to populate a {@link BotListEntry}. Any field may be absent when the bot
  /// has not fully spawned.
  private record BotRuntimeInfo(BotLiveState liveState, BotConnectionPhase phase, Integer pingMs) {}

  @Override
  public void setBotsDesiredState(
    SetBotsDesiredStateRequest request,
    StreamObserver<SetBotsDesiredStateResponse> responseObserver) {
    try {
      var instanceId = UUID.fromString(request.getInstanceId());
      var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
      user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.CONTROL_BOTS, instanceId));
      var instance = soulFireServer.getInstance(instanceId)
        .orElseThrow(() -> Status.NOT_FOUND
          .withDescription("Instance '%s' not found".formatted(instanceId))
          .asRuntimeException());
      var botIds = parseBotIds(request.getBotIdsList());
      var statuses = instance.botStateManager()
        .setDesiredState(user, botIds, request.getDesiredState());

      responseObserver.onNext(SetBotsDesiredStateResponse.newBuilder()
        .addAllBots(statuses)
        .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException exception) {
      responseObserver.onError(exception);
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(Status.INVALID_ARGUMENT
        .withDescription(exception.getMessage())
        .withCause(exception)
        .asRuntimeException());
    } catch (Throwable throwable) {
      log.error("Error setting bot desired state", throwable);
      responseObserver.onError(Status.INTERNAL
        .withDescription(throwable.getMessage())
        .withCause(throwable)
        .asRuntimeException());
    }
  }

  @Override
  public void restartBots(
    RestartBotsRequest request,
    StreamObserver<RestartBotsResponse> responseObserver) {
    try {
      var instanceId = UUID.fromString(request.getInstanceId());
      var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
      user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.CONTROL_BOTS, instanceId));
      var instance = soulFireServer.getInstance(instanceId)
        .orElseThrow(() -> Status.NOT_FOUND
          .withDescription("Instance '%s' not found".formatted(instanceId))
          .asRuntimeException());
      var statuses = instance.botStateManager()
        .restartBots(user, parseBotIds(request.getBotIdsList()));

      responseObserver.onNext(RestartBotsResponse.newBuilder()
        .addAllBots(statuses)
        .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException exception) {
      responseObserver.onError(exception);
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(Status.INVALID_ARGUMENT
        .withDescription(exception.getMessage())
        .withCause(exception)
        .asRuntimeException());
    } catch (Throwable throwable) {
      log.error("Error restarting bots", throwable);
      responseObserver.onError(Status.INTERNAL
        .withDescription(throwable.getMessage())
        .withCause(throwable)
        .asRuntimeException());
    }
  }

  @Override
  public void watchBotStatuses(
    WatchBotStatusesRequest request,
    StreamObserver<WatchBotStatusesResponse> responseObserver) {
    Runnable closeSubscription = () -> {
    };
    try {
      var instanceId = UUID.fromString(request.getInstanceId());
      ServerRPCConstants.USER_CONTEXT_KEY.get()
        .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));
      var instance = soulFireServer.getInstance(instanceId)
        .orElseThrow(() -> Status.NOT_FOUND
          .withDescription("Instance '%s' not found".formatted(instanceId))
          .asRuntimeException());
      var observerLock = new Object();
      var pendingEvents = new ArrayDeque<WatchBotStatusesResponse>();
      var initialSnapshotSent = new boolean[]{false};
      var subscription = instance.botStateManager().subscribe(event -> {
        var response = WatchBotStatusesResponse.newBuilder();
        if (event.status() != null) {
          response.setUpdate(event.status());
        } else if (event.removedBotId() != null) {
          response.setRemovedBotId(event.removedBotId().toString());
        } else {
          return;
        }
        synchronized (observerLock) {
          if (initialSnapshotSent[0]) {
            responseObserver.onNext(response.build());
          } else {
            pendingEvents.addLast(response.build());
          }
        }
      });
      closeSubscription = subscription.close();

      if (responseObserver instanceof ServerCallStreamObserver<WatchBotStatusesResponse> serverObserver) {
        serverObserver.setOnCancelHandler(closeSubscription);
      }
      synchronized (observerLock) {
        responseObserver.onNext(WatchBotStatusesResponse.newBuilder()
          .setSnapshot(BotStatusSnapshot.newBuilder()
            .addAllBots(subscription.snapshot())
            .build())
          .build());
        initialSnapshotSent[0] = true;
        while (!pendingEvents.isEmpty()) {
          responseObserver.onNext(pendingEvents.removeFirst());
        }
      }
    } catch (StatusRuntimeException exception) {
      closeSubscription.run();
      responseObserver.onError(exception);
    } catch (IllegalArgumentException exception) {
      closeSubscription.run();
      responseObserver.onError(Status.INVALID_ARGUMENT
        .withDescription(exception.getMessage())
        .withCause(exception)
        .asRuntimeException());
    } catch (Throwable throwable) {
      closeSubscription.run();
      log.error("Error watching bot statuses", throwable);
      responseObserver.onError(Status.INTERNAL
        .withDescription(throwable.getMessage())
        .withCause(throwable)
        .asRuntimeException());
    }
  }

  private static List<UUID> parseBotIds(Collection<String> values) {
    return values.stream().map(UUID::fromString).toList();
  }

  /**
   * Builds a BotLiveState from a LocalPlayer. Exposed package-private so
   * {@link BotLiveServiceImpl} can reuse the same snapshot structure for its
   * initial and resync messages without duplicating the serialization logic.
   *
   * @param player           The player to extract data from
   * @param includeInventory Whether to include full inventory data (more expensive)
   * @return The built BotLiveState
   */
  static BotLiveState buildLiveStatePublic(Minecraft minecraft, LocalPlayer player, boolean includeInventory) {
    return buildLiveState(minecraft, player, includeInventory);
  }

  private static BotLiveState buildLiveState(Minecraft minecraft, LocalPlayer player, boolean includeInventory) {
    var builder = BotLiveState.newBuilder()
      .setX(player.getX())
      .setY(player.getY())
      .setZ(player.getZ())
      .setXRot(player.getXRot())
      .setYRot(player.getYRot())
      .setHealth(player.getHealth())
      .setMaxHealth(player.getMaxHealth())
      .setFoodLevel(player.getFoodData().getFoodLevel())
      .setSaturationLevel(player.getFoodData().getSaturationLevel())
      .setSelectedHotbarSlot(player.getInventory().getSelectedSlot())
      .setExperienceLevel(player.experienceLevel)
      .setExperienceProgress(player.experienceProgress);

    // Add dimension
    var level = Objects.requireNonNull(
      minecraft.level,
      "Bot level is not available"
    );
    var dimension = level.dimension().identifier().toString();
    builder.setDimension(dimension);

    // Add game mode
    var gameMode = minecraft.gameMode;
    if (gameMode != null) {
      var playerMode = gameMode.getPlayerMode();
      builder.setGameMode(switch (playerMode) {
        case SURVIVAL -> GameMode.GAME_MODE_SURVIVAL;
        case CREATIVE -> GameMode.GAME_MODE_CREATIVE;
        case ADVENTURE -> GameMode.GAME_MODE_ADVENTURE;
        case SPECTATOR -> GameMode.GAME_MODE_SPECTATOR;
      });
    }

    // Add skin texture hash if available
    var skinHash = extractSkinTextureHash(player.getGameProfile());
    if (skinHash != null) {
      builder.setSkinTextureHash(skinHash);
    }

    // Add inventory items if requested (only non-empty slots)
    if (includeInventory) {
      var container = player.inventoryMenu;
      for (var slot : container.slots) {
        var item = slot.getItem();
        if (!item.isEmpty()) {
          builder.addInventory(buildInventorySlot(minecraft, slot.index, item));
        }
      }
    }

    return builder.build();
  }

  private static String toBase64PNG(BufferedImage image) {
    try (var os = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", os);
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to convert image to base64", e);
    }
  }

  private static <T> T callInBotContext(BotConnection botConnection, Callable<T> callable) throws Exception {
    return botConnection.runnableWrapper().wrap(callable).call();
  }

  private static void executeAction(BotConnection bot, Runnable action) throws Exception {
    executeAction(bot, ControlResource.all(), action);
  }

  private static void executeAction(
    BotConnection bot,
    Set<ControlResource> resources,
    Runnable action
  ) throws Exception {
    var task = new CompletableControlTask(
      ControlTask.once("gRPC bot action", resources, action));
    callInBotContext(bot, () -> {
      bot.botControl().replace(task);
      return null;
    });
    try {
      var reason = task.completion().get(10, TimeUnit.SECONDS);
      if (reason != ControlStopReason.COMPLETED && reason != ControlStopReason.CLAIMED) {
        throw Status.ABORTED
          .withDescription("Bot action was " + reason.name().toLowerCase(Locale.ROOT))
          .asRuntimeException();
      }
    } catch (TimeoutException e) {
      bot.botControl().cancel(task);
      throw Status.DEADLINE_EXCEEDED
        .withDescription("Bot action did not execute within 10 seconds")
        .withCause(e)
        .asRuntimeException();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
  }

  private static void requireBotControl(
    SoulFireServer soulFireServer,
    UUID instanceId,
    UUID botId
  ) {
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(
        InstancePermission.CONTROL_BOT_ACTIONS,
        instanceId));
    var instance = soulFireServer.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
    if (!instance.settingsSource().accounts().containsKey(botId)) {
      throw Status.NOT_FOUND
        .withDescription("Bot '%s' is not configured".formatted(botId))
        .asRuntimeException();
    }
    try {
      instance.botControlLeaseManager().authorize(
        botId,
        ServerRPCConstants.BOT_CONTROL_TOKEN_CONTEXT_KEY.get());
    } catch (BotControlLeaseManager.InvalidLeaseException e) {
      throw Status.PERMISSION_DENIED
        .withDescription(e.getMessage())
        .asRuntimeException();
    }
  }

  private static InventorySlot buildInventorySlot(Minecraft minecraft, int slotIndex, ItemStack item) {
    var slotBuilder = InventorySlot.newBuilder()
      .setSlot(slotIndex)
      .setItemId(item.typeHolder().getRegisteredName())
      .setCount(item.getCount())
      .addAllTooltipLines(Screen.getTooltipFromItem(minecraft, item).stream()
        .map(Component::getString)
        .toList());

    if (item.has(DataComponents.CUSTOM_NAME)) {
      slotBuilder.setDisplayName(item.getHoverName().getString());
    }

    return slotBuilder.build();
  }

  private static InventorySlot buildInventorySlot(
    Minecraft minecraft,
    ClientLevel level,
    ItemOwner itemOwner,
    int slotIndex,
    ItemStack item
  ) {
    var slotBuilder = buildInventorySlot(minecraft, slotIndex, item).toBuilder();

    try {
      var renderedIcon = InventoryItemIconRenderer.render(minecraft, level, itemOwner, item, slotIndex);
      if (!renderedIcon.base64().isEmpty()) {
        slotBuilder
          .setIconBase64(renderedIcon.base64())
          .setIconMimeType(renderedIcon.mimeType());
      }
    } catch (Throwable t) {
      log.debug("Failed to render inventory icon for {}", item.typeHolder().getRegisteredName(), t);
    }

    return slotBuilder.build();
  }

  static BotInventoryStateResponse buildInventoryStatePublic(
    Minecraft minecraft,
    LocalPlayer player,
    boolean renderIcons
  ) {
    var container = player.containerMenu;
    var responseBuilder = BotInventoryStateResponse.newBuilder()
      .setLayout(buildContainerLayout(container, getContainerTitle(container, minecraft), minecraft))
      .setSelectedHotbarSlot(player.getInventory().getSelectedSlot())
      .setMenuId(((IMenuIdentity) container).soulfire$getMenuId());

    for (var slot : container.slots) {
      var item = slot.getItem();
      if (item.isEmpty()) {
        continue;
      }
      responseBuilder.addSlots(renderIcons
        ? buildInventorySlot(minecraft, minecraft.level, player, slot.index, item)
        : buildInventorySlot(minecraft, slot.index, item));
    }

    var carried = container.getCarried();
    if (!carried.isEmpty()) {
      responseBuilder.setCarriedItem(renderIcons
        ? buildInventorySlot(minecraft, minecraft.level, player, -1, carried)
        : buildInventorySlot(minecraft, -1, carried));
    }

    return responseBuilder.build();
  }

  /**
   * Extracts the skin texture hash from a GameProfile.
   * The hash is the ID at the end of the texture URL like:
   * http://textures.minecraft.net/texture/abc123def456...
   *
   * @param profile The player's game profile
   * @return The texture hash, or null if not available
   */
  private static String extractSkinTextureHash(GameProfile profile) {
    try {
      var properties = profile.properties();
      if (properties == null) {
        return null;
      }

      var texturesProperty = properties.get("textures");
      if (texturesProperty == null || texturesProperty.isEmpty()) {
        return null;
      }

      var property = texturesProperty.iterator().next();
      var base64Value = property.value();
      if (base64Value == null || base64Value.isEmpty()) {
        return null;
      }

      // Decode base64
      var decoded = new String(Base64.getDecoder().decode(base64Value));

      // Parse JSON to get the skin URL
      var json = JsonParser.parseString(decoded).getAsJsonObject();
      var textures = json.getAsJsonObject("textures");
      if (textures == null) {
        return null;
      }

      var skin = textures.getAsJsonObject("SKIN");
      if (skin == null) {
        return null;
      }

      var url = skin.get("url").getAsString();
      if (url == null || url.isEmpty()) {
        return null;
      }

      // Extract hash from URL (last segment after /texture/)
      var lastSlash = url.lastIndexOf('/');
      if (lastSlash >= 0 && lastSlash < url.length() - 1) {
        return url.substring(lastSlash + 1);
      }

      return null;
    } catch (Exception e) {
      log.debug("Failed to extract skin texture hash", e);
      return null;
    }
  }

  @Override
  public void updateBotConfigEntry(BotUpdateConfigEntryRequest request, StreamObserver<BotUpdateConfigEntryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      soulFireServer.dsl().transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(record.getSettings(), JsonElement.class));
        var newSettings = currentSettings.withAccounts(currentSettings.accounts().stream()
          .map(minecraftAccount -> {
            if (minecraftAccount.profileId().equals(botId)) {
              var currentStem = minecraftAccount.settings() == null ? Map.<String, Map<String, JsonElement>>of() : minecraftAccount.settings();
              var updatedSettings = SettingsSource.Stem.withUpdatedEntry(
                currentStem,
                request.getNamespace(),
                request.getKey(),
                SettingsSource.Stem.valueToJsonElement(request.getValue())
              );
              return minecraftAccount.withSettings(updatedSettings);
            } else {
              return minecraftAccount;
            }
          })
          .toList());

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, GsonInstance.CONFIG_GSON.toJson(newSettings.serializeToTree()))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(BotUpdateConfigEntryResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error updating bot config entry", t);
    }
  }

  @Override
  public void getBotList(BotListRequest request, StreamObserver<BotListResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var botConnections = instance.botConnections();

      var responseBuilder = BotListResponse.newBuilder();
      for (var account : instance.settingsSource().accounts().values()) {
        var profileId = account.profileId();
        var activeBot = botConnections.get(profileId);
        var isOnline = activeBot != null && !activeBot.isDisconnected();
        var entryBuilder = BotListEntry.newBuilder()
          .setProfileId(profileId.toString())
          .setIsOnline(isOnline)
          .setStatus(instance.botStateManager().status(profileId));

        var accountName = account.lastKnownName();
        if (accountName != null) {
          entryBuilder.setAccountName(accountName);
        }

        if (!isOnline) {
          entryBuilder.setConnectionPhase(BotConnectionPhase.BOT_CONNECTION_PHASE_DISCONNECTED);
        } else {
          var info = callInBotContext(activeBot, () -> {
            var minecraft = activeBot.minecraft();
            var player = minecraft.player;
            if (player == null) {
              // Connected/handshaking but the player has not spawned yet.
              return new BotRuntimeInfo(null, BotConnectionPhase.BOT_CONNECTION_PHASE_CONNECTING, null);
            }
            // Don't include inventory for list view (too expensive)
            var liveState = buildLiveState(minecraft, player, false);
            Integer pingMs = null;
            var playerInfo = player.connection.getPlayerInfo(player.getUUID());
            if (playerInfo != null) {
              pingMs = playerInfo.getLatency();
            }
            return new BotRuntimeInfo(liveState, BotConnectionPhase.BOT_CONNECTION_PHASE_SPAWNED, pingMs);
          });

          entryBuilder.setConnectionPhase(info.phase());
          if (info.liveState() != null) {
            entryBuilder.setLiveState(info.liveState());
          }
          if (info.pingMs() != null) {
            entryBuilder.setPingMs(info.pingMs());
          }
        }

        responseBuilder.addBots(entryBuilder.build());
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error getting bot list", t);
    }
  }

  @Override
  public void getBotInfo(BotInfoRequest request, StreamObserver<BotInfoResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var account = instance.settingsSource().accounts().get(botId);
      if (account == null) {
        throw Status.NOT_FOUND.withDescription("Bot '%s' not found in instance '%s'".formatted(botId, instanceId)).asRuntimeException();
      }

      var botInfoResponseBuilder = BotInfoResponse.newBuilder()
        .setStatus(instance.botStateManager().status(botId));
      var activeBot = instance.botConnections().get(botId);
      if (activeBot != null) {
        var connectionInfo = BotConnectionInfo.newBuilder();
        var proxy = activeBot.proxy();
        if (proxy != null) {
          connectionInfo.setProxy(BotProxyInfo.newBuilder()
            .setType(ProxyProto.Type.valueOf(proxy.type().name()))
            .setAddress(SocketAddressHelper.serialize(proxy.address())));
        }
        botInfoResponseBuilder.setConnection(connectionInfo);
        var movement = activeBot.controlState();
        botInfoResponseBuilder.setMovementState(BotMovementState.newBuilder()
          .setForward(movement.up())
          .setBackward(movement.down())
          .setLeft(movement.left())
          .setRight(movement.right())
          .setJump(movement.jump())
          .setSneak(movement.shift())
          .setSprint(movement.sprint()));

        var liveState = callInBotContext(activeBot, () -> {
          var minecraft = activeBot.minecraft();
          var player = minecraft.player;
          return player != null
            ? buildLiveState(minecraft, player, true)
            : null;
        });
        if (liveState != null) {
          // Include full inventory data for detail view
          botInfoResponseBuilder.setLiveState(liveState);
        }
      }

      responseObserver.onNext(botInfoResponseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error getting instance info", t);
    }
  }

  @Override
  public void renderBotPov(BotRenderPovRequest request, StreamObserver<BotRenderPovResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      responseObserver.onNext(renderPovFrame(activeBot, request));
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error rendering bot POV", t);
    }
  }

  @Override
  public void watchBotPov(BotWatchPovRequest request, StreamObserver<BotPovFrame> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));
    var instance = soulFireServer.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
    if (!instance.settingsSource().accounts().containsKey(botId)) {
      throw Status.NOT_FOUND
        .withDescription("Bot '%s' not found in instance '%s'".formatted(botId, instanceId))
        .asRuntimeException();
    }

    var observer = (ServerCallStreamObserver<BotPovFrame>) responseObserver;
    var closed = new AtomicBoolean();
    var sequence = new AtomicLong();
    var dropped = new AtomicLong();
    var intervalMs = Math.min(Math.max(request.getIntervalMs() > 0 ? request.getIntervalMs() : 1000, 100), 60_000);
    var renderRequest = povRenderRequest(request);
    observer.setOnCancelHandler(() -> closed.set(true));
    schedulePovFrame(instanceId, botId, intervalMs, renderRequest, observer, closed, sequence, dropped);
  }

  @Override
  public void getBotWorldMap(BotWorldMapRequest request, StreamObserver<BotWorldMapResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
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
      responseObserver.onNext(buildWorldMap(bot, request));
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error sampling bot world map", t);
    }
  }

  private void schedulePovFrame(UUID instanceId,
                                UUID botId,
                                int intervalMs,
                                BotRenderPovRequest renderRequest,
                                ServerCallStreamObserver<BotPovFrame> observer,
                                AtomicBoolean closed,
                                AtomicLong sequence,
                                AtomicLong dropped) {
    soulFireServer.scheduler().schedule(new Runnable() {
      @Override
      public void run() {
        if (closed.get() || observer.isCancelled()) {
          return;
        }
        try {
          if (!observer.isReady()) {
            dropped.incrementAndGet();
            return;
          }
          var instance = soulFireServer.getInstance(instanceId)
            .orElseThrow(() -> Status.NOT_FOUND
              .withDescription("Instance '%s' no longer exists".formatted(instanceId))
              .asRuntimeException());
          var bot = instance.botConnections().get(botId);
          if (bot == null) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Bot '%s' is no longer online".formatted(botId))
              .asRuntimeException();
          }
          var frame = BotPovFrame.newBuilder()
            .setSequence(sequence.incrementAndGet())
            .setCapturedAt(Timestamps.fromMillis(System.currentTimeMillis()))
            .setRender(renderPovFrame(bot, renderRequest))
            .setDroppedBefore(dropped.getAndSet(0))
            .build();
          synchronized (observer) {
            if (!closed.get() && !observer.isCancelled()) {
              observer.onNext(frame);
            }
          }
        } catch (Throwable throwable) {
          if (closed.compareAndSet(false, true)) {
            synchronized (observer) {
              if (!observer.isCancelled()) {
                observer.onError(GrpcErrors.logAndMap(log, "Error streaming bot POV", throwable));
              }
            }
          }
        } finally {
          if (!closed.get() && !observer.isCancelled()) {
            soulFireServer.scheduler().schedule(this, intervalMs, TimeUnit.MILLISECONDS);
          }
        }
      }
    }, 0, TimeUnit.MILLISECONDS);
  }

  private static BotRenderPovRequest povRenderRequest(BotWatchPovRequest request) {
    var builder = BotRenderPovRequest.newBuilder()
      .setInstanceId(request.getInstanceId())
      .setBotId(request.getBotId())
      .setWidth(request.getWidth())
      .setHeight(request.getHeight())
      .setIncludeDebugTrace(request.getIncludeDebugTrace());
    if (request.hasMaxDistance()) {
      builder.setMaxDistance(request.getMaxDistance());
    }
    if (request.hasFov()) {
      builder.setFov(request.getFov());
    }
    if (request.hasCameraX()) {
      builder.setCameraX(request.getCameraX());
    }
    if (request.hasCameraY()) {
      builder.setCameraY(request.getCameraY());
    }
    if (request.hasCameraZ()) {
      builder.setCameraZ(request.getCameraZ());
    }
    if (request.hasYRot()) {
      builder.setYRot(request.getYRot());
    }
    if (request.hasXRot()) {
      builder.setXRot(request.getXRot());
    }
    if (request.hasIncludeHud()) {
      builder.setIncludeHud(request.getIncludeHud());
    }
    if (request.hasIncludeHands()) {
      builder.setIncludeHands(request.getIncludeHands());
    }
    return builder.build();
  }

  private static BotRenderPovResponse renderPovFrame(BotConnection activeBot,
                                                     BotRenderPovRequest request) throws Exception {
    var renderWidth = effectiveDimension(request.getWidth(), RenderConstants.DEFAULT_WIDTH, 1920);
    var renderHeight = effectiveDimension(request.getHeight(), RenderConstants.DEFAULT_HEIGHT, 1080);
    var includeHud = !request.hasIncludeHud() || request.getIncludeHud();
    var includeHands = !request.hasIncludeHands() || request.getIncludeHands();
    return callInBotContext(activeBot, () -> {
      var minecraft = activeBot.minecraft();
      var player = minecraft.player;
      var level = minecraft.level;
      if (player == null || level == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot player or level is not available").asRuntimeException();
      }

      var options = new VulkanRenderer.Options(
        effectiveEyePosition(request, player),
        effectiveYRot(request, player),
        effectiveXRot(request, player),
        renderWidth,
        renderHeight,
        effectiveFov(request),
        effectiveMaxDistance(request, minecraft.options.getEffectiveRenderDistance()),
        includeHands,
        includeHud,
        request.getIncludeDebugTrace()
      );
      var result = VulkanRenderer.renderWithResult(level, player, options);
      var response = BotRenderPovResponse.newBuilder()
        .setImageBase64(toBase64PNG(result.image()))
        .setImageMimeType("image/png")
        .setMetadata(buildPovMetadata(options));
      if (request.getIncludeDebugTrace()) {
        response.setDebugTrace(buildPovDebugTrace(result.debugTrace()));
      }
      return response.build();
    });
  }

  private static BotWorldMapResponse buildWorldMap(BotConnection bot,
                                                   BotWorldMapRequest request) throws Exception {
    return callInBotContext(bot, () -> {
      var level = bot.minecraft().level;
      var player = bot.minecraft().player;
      if (player == null || level == null) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot player or level is not available")
          .asRuntimeException();
      }
      var centerX = request.hasCenterX() ? request.getCenterX() : player.getBlockX();
      var centerZ = request.hasCenterZ() ? request.getCenterZ() : player.getBlockZ();
      var radius = Math.min(Math.max(request.getRadius() > 0 ? request.getRadius() : 64, 1), 256);
      var step = Math.min(Math.max(request.getSampleStep() > 0 ? request.getSampleStep() : 1, 1), 16);
      var response = BotWorldMapResponse.newBuilder()
        .setDimension(level.dimension().identifier().toString())
        .setCenterX(centerX)
        .setCenterZ(centerZ)
        .setRadius(radius)
        .setSampleStep(step)
        .setMinY(level.getMinY())
        .setMaxY(level.getMaxY())
        .setWorldRevision(Math.max(0, level.getGameTime()))
        .setSampledAt(Timestamps.fromMillis(System.currentTimeMillis()));

      for (var x = centerX - radius; x <= centerX + radius; x += step) {
        for (var z = centerZ - radius; z <= centerZ + radius; z += step) {
          var probe = new BlockPos(x, player.getBlockY(), z);
          var column = BotWorldMapColumn.newBuilder().setX(x).setZ(z);
          if (!SFBlockHelpers.isChunkLoaded(level, probe)) {
            response.addColumns(column.setLoaded(false));
            continue;
          }
          column.setLoaded(true);
          var surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
          if (surfaceY >= level.getMinY() && surfaceY < level.getMaxY()) {
            var surface = new BlockPos(x, surfaceY, z);
            var state = level.getBlockState(surface);
            column
              .setSurfaceY(surfaceY)
              .setBlockId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
              .setSkyLight(level.getBrightness(LightLayer.SKY, surface))
              .setBlockLight(level.getBrightness(LightLayer.BLOCK, surface));
            level.getBiome(surface).unwrapKey()
              .ifPresent(key -> column.setBiomeId(key.identifier().toString()));
          }
          response.addColumns(column);
        }
      }

      if (request.getIncludeEntities()) {
        var radiusSquared = (double) radius * radius;
        for (var entity : level.entitiesForRendering()) {
          if (entity == player) {
            continue;
          }
          var dx = entity.getX() - centerX;
          var dz = entity.getZ() - centerZ;
          if (dx * dx + dz * dz > radiusSquared) {
            continue;
          }
          response.addEntities(BotWorldMapEntity.newBuilder()
            .setEntityId(entity.getUUID().toString())
            .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
            .setDisplayName(entity.getName().getString())
            .setX(entity.getX())
            .setY(entity.getY())
            .setZ(entity.getZ())
            .setYaw(entity.getYRot()));
        }
      }
      return response.build();
    });
  }

  private static int effectiveDimension(int requestedDimension, int defaultDimension, int maxDimension) {
    var dimension = requestedDimension > 0 ? requestedDimension : defaultDimension;
    return Math.min(Math.max(dimension, 1), maxDimension);
  }

  private static int effectiveMaxDistance(BotRenderPovRequest request, int renderDistanceChunks) {
    var maxDistance = request.hasMaxDistance() && request.getMaxDistance() > 0
      ? request.getMaxDistance()
      : renderDistanceChunks * 16;
    return Math.min(Math.max(maxDistance, 1), 2048);
  }

  private static double effectiveFov(BotRenderPovRequest request) {
    if (!request.hasFov() || !Double.isFinite(request.getFov())) {
      return RenderConstants.DEFAULT_FOV;
    }

    return Math.min(Math.max(request.getFov(), 1.0D), 179.0D);
  }

  private static Vec3 effectiveEyePosition(BotRenderPovRequest request, LocalPlayer player) {
    var playerEyePosition = player.getEyePosition();
    return new Vec3(
      request.hasCameraX() && Double.isFinite(request.getCameraX()) ? request.getCameraX() : playerEyePosition.x,
      request.hasCameraY() && Double.isFinite(request.getCameraY()) ? request.getCameraY() : playerEyePosition.y,
      request.hasCameraZ() && Double.isFinite(request.getCameraZ()) ? request.getCameraZ() : playerEyePosition.z
    );
  }

  private static float effectiveYRot(BotRenderPovRequest request, LocalPlayer player) {
    return request.hasYRot() && Float.isFinite(request.getYRot()) ? request.getYRot() : player.getYRot();
  }

  private static float effectiveXRot(BotRenderPovRequest request, LocalPlayer player) {
    var xRot = request.hasXRot() && Float.isFinite(request.getXRot()) ? request.getXRot() : player.getXRot();
    return Math.min(Math.max(xRot, -90.0F), 90.0F);
  }

  private static BotRenderPovMetadata buildPovMetadata(VulkanRenderer.Options options) {
    return BotRenderPovMetadata.newBuilder()
      .setWidth(options.width())
      .setHeight(options.height())
      .setFov(options.fov())
      .setMaxDistance(options.maxDistance())
      .setCameraX(options.eyePos().x)
      .setCameraY(options.eyePos().y)
      .setCameraZ(options.eyePos().z)
      .setYRot(options.yRot())
      .setXRot(options.xRot())
      .setIncludedHud(options.includeHud())
      .setIncludedHands(options.includeHands())
      .build();
  }

  private static BotRenderPovDebugTrace buildPovDebugTrace(VulkanRenderer.Trace snapshot) {
    return BotRenderPovDebugTrace.newBuilder()
      .setTotalNanos(snapshot.totalNanos())
      .addNotableEvents("Headless Vulkan: " + snapshot.device())
      .build();
  }

  /**
   * Builds a ContainerLayout describing the slot regions for the given menu.
   * This allows the frontend to render any container type dynamically.
   */
  static ContainerLayout buildContainerLayout(AbstractContainerMenu menu, String title, Minecraft minecraft) {
    if (menu instanceof CreativeModeInventoryScreen.ItemPickerMenu
      && minecraft.gui.screen() instanceof CreativeModeInventoryScreen screen && screen.getMenu() == menu) {
      return CreativeInventoryControls.layout(screen);
    }
    var layoutBuilder = ContainerLayout.newBuilder()
      .setTitle(title)
      .setTotalSlots(menu.slots.size());

    // Player inventory always has these standard regions at the end
    // The slot indices vary based on container type, but layout is consistent
    int playerInvStart;
    int hotbarStart;

    if (menu instanceof InventoryMenu) {
      // Player inventory screen layout:
      // 0: crafting output
      // 1-4: crafting grid (2x2)
      // 5-8: armor slots
      // 9-35: main inventory (27 slots, 3 rows of 9)
      // 36-44: hotbar (9 slots)
      // 45: offhand

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("crafting_output")
        .setLabel("Crafting")
        .setStartIndex(0)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("crafting_grid")
        .setLabel("Crafting Grid")
        .setStartIndex(1)
        .setSlotCount(4)
        .setColumns(2)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("armor")
        .setLabel("Armor")
        .setStartIndex(5)
        .setSlotCount(4)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_ARMOR)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("offhand")
        .setLabel("Offhand")
        .setStartIndex(45)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 9;
      hotbarStart = 36;

    } else if (menu instanceof AbstractMountInventoryMenu) {
      for (var index = 0; index < 2; index++) {
        if (!menu.getSlot(index).isActive()) continue;
        layoutBuilder.addRegions(SlotRegion.newBuilder()
          .setId(index == 0 ? "saddle" : "equipment")
          .setLabel(index == 0 ? "Saddle" : "Equipment")
          .setStartIndex(index)
          .setSlotCount(1)
          .setColumns(1)
          .setType(index == 0 ? SlotRegionType.SLOT_REGION_NORMAL : SlotRegionType.SLOT_REGION_ARMOR)
          .build());
      }
      var storageSlots = menu.slots.size() - 38;
      if (storageSlots > 0) {
        layoutBuilder.addRegions(SlotRegion.newBuilder()
          .setId("container")
          .setLabel("Storage")
          .setStartIndex(2)
          .setSlotCount(storageSlots)
          .setColumns(storageSlots / 3)
          .setType(SlotRegionType.SLOT_REGION_NORMAL)
          .build());
      }
      playerInvStart = 2 + storageSlots;
      hotbarStart = playerInvStart + 27;

    } else if (menu instanceof ChestMenu chestMenu) {
      // Chest layout: container slots first, then player inventory
      int containerSize = chestMenu.getRowCount() * 9;
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("container")
        .setLabel(title)
        .setStartIndex(0)
        .setSlotCount(containerSize)
        .setColumns(9)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = containerSize;
      hotbarStart = containerSize + 27;

    } else if (menu instanceof DispenserMenu) {
      // Dispenser/Dropper: 9 slots in 3x3 grid
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("container")
        .setLabel(title)
        .setStartIndex(0)
        .setSlotCount(9)
        .setColumns(3)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 9;
      hotbarStart = 36;

    } else if (menu instanceof HopperMenu) {
      // Hopper: 5 slots in a row
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("container")
        .setLabel(title)
        .setStartIndex(0)
        .setSlotCount(5)
        .setColumns(5)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 5;
      hotbarStart = 32;

    } else if (menu instanceof AbstractFurnaceMenu) {
      // Furnace/Blast Furnace/Smoker: input, fuel, output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("input")
        .setLabel("Input")
        .setStartIndex(0)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("fuel")
        .setLabel("Fuel")
        .setStartIndex(1)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("output")
        .setLabel("Output")
        .setStartIndex(2)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 3;
      hotbarStart = 30;

    } else if (menu instanceof CraftingMenu) {
      // Crafting table: output + 3x3 grid
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("crafting_output")
        .setLabel("Result")
        .setStartIndex(0)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("crafting_grid")
        .setLabel("Crafting Grid")
        .setStartIndex(1)
        .setSlotCount(9)
        .setColumns(3)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 10;
      hotbarStart = 37;

    } else if (menu instanceof AnvilMenu) {
      // Anvil: 2 inputs + 1 output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("input")
        .setLabel("Items")
        .setStartIndex(0)
        .setSlotCount(2)
        .setColumns(2)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("output")
        .setLabel("Result")
        .setStartIndex(2)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 3;
      hotbarStart = 30;

    } else if (menu instanceof EnchantmentMenu) {
      // Enchanting table: item + lapis
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("enchant")
        .setLabel("Enchant")
        .setStartIndex(0)
        .setSlotCount(2)
        .setColumns(2)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 2;
      hotbarStart = 29;

    } else if (menu instanceof BrewingStandMenu) {
      // Brewing stand: 3 potions + ingredient + fuel
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("potions")
        .setLabel("Potions")
        .setStartIndex(0)
        .setSlotCount(3)
        .setColumns(3)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("ingredient")
        .setLabel("Ingredient")
        .setStartIndex(3)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("fuel")
        .setLabel("Blaze Powder")
        .setStartIndex(4)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 5;
      hotbarStart = 32;

    } else if (menu instanceof BeaconMenu) {
      // Beacon: 1 payment slot
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("payment")
        .setLabel("Payment")
        .setStartIndex(0)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 1;
      hotbarStart = 28;

    } else if (menu instanceof ShulkerBoxMenu) {
      // Shulker box: 27 slots like a small chest
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("container")
        .setLabel(title)
        .setStartIndex(0)
        .setSlotCount(27)
        .setColumns(9)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      playerInvStart = 27;
      hotbarStart = 54;

    } else if (menu instanceof GrindstoneMenu) {
      // Grindstone: 2 inputs + 1 output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("input")
        .setLabel("Items")
        .setStartIndex(0)
        .setSlotCount(2)
        .setColumns(2)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("output")
        .setLabel("Result")
        .setStartIndex(2)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 3;
      hotbarStart = 30;

    } else if (menu instanceof StonecutterMenu) {
      // Stonecutter: 1 input + 1 output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("input")
        .setLabel("Input")
        .setStartIndex(0)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("output")
        .setLabel("Result")
        .setStartIndex(1)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 2;
      hotbarStart = 29;

    } else if (menu instanceof LoomMenu) {
      // Loom: banner + dye + pattern + output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("input")
        .setLabel("Materials")
        .setStartIndex(0)
        .setSlotCount(3)
        .setColumns(3)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("output")
        .setLabel("Result")
        .setStartIndex(3)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 4;
      hotbarStart = 31;

    } else if (menu instanceof CartographyTableMenu) {
      // Cartography table: map + material + output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("input")
        .setLabel("Materials")
        .setStartIndex(0)
        .setSlotCount(2)
        .setColumns(2)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("output")
        .setLabel("Result")
        .setStartIndex(2)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 3;
      hotbarStart = 30;

    } else if (menu instanceof SmithingMenu) {
      // Smithing table: template + base + addition + output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("input")
        .setLabel("Materials")
        .setStartIndex(0)
        .setSlotCount(3)
        .setColumns(3)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("output")
        .setLabel("Result")
        .setStartIndex(3)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 4;
      hotbarStart = 31;

    } else if (menu instanceof MerchantMenu) {
      // Villager trading: 2 inputs + 1 output
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("trade_input")
        .setLabel("Trade")
        .setStartIndex(0)
        .setSlotCount(2)
        .setColumns(2)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("trade_output")
        .setLabel("Result")
        .setStartIndex(2)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_OUTPUT)
        .build());

      playerInvStart = 3;
      hotbarStart = 30;

    } else if (menu instanceof CrafterMenu) {
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("crafting_input")
        .setLabel("Crafting Grid")
        .setStartIndex(0)
        .setSlotCount(9)
        .setColumns(3)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("recipe_preview")
        .setLabel("Recipe Preview")
        .setStartIndex(45)
        .setSlotCount(1)
        .setColumns(1)
        .setType(SlotRegionType.SLOT_REGION_DISPLAY)
        .build());
      playerInvStart = 9;
      hotbarStart = 36;

    } else if (menu instanceof LecternMenu) {
      // Lecterns expose their book through page controls, without player inventory slots.
      playerInvStart = -1;
      hotbarStart = -1;

    } else {
      // Generic fallback for unknown containers
      // Just show all container slots, then player inventory
      int containerSlots = menu.slots.size() - 36; // Assume 36 player slots at end
      if (containerSlots > 0) {
        layoutBuilder.addRegions(SlotRegion.newBuilder()
          .setId("container")
          .setLabel(title)
          .setStartIndex(0)
          .setSlotCount(containerSlots)
          .setColumns(9) // Default to 9 columns
          .setType(SlotRegionType.SLOT_REGION_NORMAL)
          .build());
      }
      playerInvStart = Math.max(0, containerSlots);
      hotbarStart = playerInvStart + 27;
    }

    if (playerInvStart >= 0) {
      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("player_inventory")
        .setLabel("Inventory")
        .setStartIndex(playerInvStart)
        .setSlotCount(27)
        .setColumns(9)
        .setType(SlotRegionType.SLOT_REGION_NORMAL)
        .build());

      layoutBuilder.addRegions(SlotRegion.newBuilder()
        .setId("player_hotbar")
        .setLabel("Hotbar")
        .setStartIndex(hotbarStart)
        .setSlotCount(9)
        .setColumns(9)
        .setType(SlotRegionType.SLOT_REGION_HOTBAR)
        .build());
    }

    // Set container type for client-specific rendering
    layoutBuilder.setContainerType(getContainerTypeId(menu));

    // Add container buttons (for stonecutter, enchanting, loom, etc.)
    for (var button : buildContainerButtons(menu, minecraft)) {
      layoutBuilder.addButtons(button);
    }

    // Add text inputs for containers that support them
    if (menu instanceof AnvilMenu anvilMenu) {
      var inputItem = anvilMenu.getSlot(0).getItem();
      var currentName = inputItem.has(DataComponents.CUSTOM_NAME) ? inputItem.getHoverName().getString() : "";
      if (minecraft.gui.screen() instanceof AnvilScreen screen && screen.getMenu() == anvilMenu) {
        currentName = ((AnvilScreenAccessor) screen).soulfire$getNameInput().getValue();
      }

      layoutBuilder.addTextInputs(ContainerTextInput.newBuilder()
        .setId("item_name")
        .setLabel("Item Name")
        .setCurrentValue(currentName)
        .setMaxLength(50) // Minecraft's anvil name limit
        .setPlaceholder("Enter new name...")
        .build());
    }

    // Add book pages for lectern
    if (menu instanceof LecternMenu lecternMenu) {
      var bookSlot = lecternMenu.getSlot(0);
      var bookItem = bookSlot.getItem();
      if (!bookItem.isEmpty()) {
        var currentPage = lecternMenu.getPage();
        layoutBuilder.setCurrentBookPage(currentPage);

        // Try to extract book content
        var bookContent = bookItem.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (bookContent != null) {
          var pages = bookContent.pages();
          for (int i = 0; i < pages.size(); i++) {
            var pageText = pages.get(i).get(false); // false = get raw text, not filtered
            layoutBuilder.addBookPages(BookPage.newBuilder()
              .setPageNumber(i)
              .setContent(pageText != null ? pageText : "")
              .build());
          }
        } else {
          // Try written book content
          var writtenContent = bookItem.get(DataComponents.WRITTEN_BOOK_CONTENT);
          if (writtenContent != null) {
            var pages = writtenContent.pages();
            for (int i = 0; i < pages.size(); i++) {
              var pageComponent = pages.get(i).get(false);
              var pageText = pageComponent != null ? pageComponent.getString() : "";
              layoutBuilder.addBookPages(BookPage.newBuilder()
                .setPageNumber(i)
                .setContent(pageText)
                .build());
            }
          }
        }
      }
    }

    return layoutBuilder.build();
  }

  /**
   * Builds the available buttons for a container menu.
   * Different container types have different interactive buttons.
   * Note: Button details may be limited since some menu fields are private.
   */
  private static List<ContainerButton> buildContainerButtons(AbstractContainerMenu menu, Minecraft minecraft) {
    var level = minecraft.level;
    var buttons = new ArrayList<ContainerButton>();

    switch (menu) {
      case StonecutterMenu stonecutterMenu -> {
        var recipes = stonecutterMenu.getVisibleRecipes().entries();
        var context = SlotDisplayContext.fromLevel(level);
        for (var i = 0; i < recipes.size(); i++) {
          var result = recipes.get(i).recipe().optionDisplay().resolveForFirstStack(context);
          buttons.add(ContainerButton.newBuilder()
            .setButtonId(i)
            .setLabel(result.getCount() + "x " + result.getHoverName().getString())
            .setIconItemId(result.typeHolder().getRegisteredName())
            .setSelected(i == stonecutterMenu.getSelectedRecipeIndex())
            .build());
        }
      }
      case EnchantmentMenu enchantmentMenu -> {
        var player = minecraft.player;
        var hasItem = enchantmentMenu.getSlot(0).hasItem();
        var currency = enchantmentMenu.getGoldCount();
        var creative = player.hasInfiniteMaterials();
        for (var i = 0; i < enchantmentMenu.costs.length; i++) {
          var requiredLevel = enchantmentMenu.costs[i];
          var cost = i + 1;
          var available = hasItem && requiredLevel > 0;
          var disabled = !available || !creative && (currency < cost || player.experienceLevel < Math.max(requiredLevel, cost));
          var label = Component.literal("Enchant Slot " + (i + 1));
          var clue = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(enchantmentMenu.enchantClue[i]);
          if (available && enchantmentMenu.levelClue[i] >= 0 && clue.isPresent()) {
            label = Enchantment.getFullname(clue.get(), enchantmentMenu.levelClue[i]).copy();
          }
          var description = !available ? "No enchantment available"
            : creative ? "No experience or lapis required in creative mode"
            : "Requires level %d and %d lapis; costs %d levels".formatted(requiredLevel, cost, cost);
          buttons.add(ContainerButton.newBuilder()
            .setButtonId(i)
            .setLabel(label.getString())
            .setDescription(description)
            .setIconItemId("minecraft:enchanted_book")
            .setDisabled(disabled)
            .build());
        }
      }
      case LoomMenu loomMenu -> {
        var patterns = loomMenu.getSelectablePatterns();
        var color = loomMenu.getSlot(1).getItem().getOrDefault(DataComponents.DYE, DyeColor.WHITE);
        for (var i = 0; i < patterns.size(); i++) {
          var pattern = patterns.get(i).value();
          buttons.add(ContainerButton.newBuilder()
            .setButtonId(i)
            .setLabel(Component.translatable(pattern.translationKey() + "." + color.getName()).getString())
            .setIconItemId(loomMenu.getSlot(0).getItem().typeHolder().getRegisteredName())
            .setSelected(i == loomMenu.getSelectedBannerPatternIndex())
            .build());
        }
      }
      case MerchantMenu merchantMenu -> {
        // Villager trading: each trade offer is a button
        var offers = merchantMenu.getOffers();
        var selectedTrade = minecraft.gui.screen() instanceof MerchantScreen screen && screen.getMenu() == merchantMenu
          ? ((MerchantScreenAccessor) screen).soulfire$getSelectedTrade() : -1;
        for (int i = 0; i < offers.size(); i++) {
          var offer = offers.get(i);
          var result = offer.getResult();
          var costA = offer.getCostA();
          var costB = offer.getCostB();

          var costDesc = costA.getCount() + "x " + costA.getHoverName().getString();
          if (!costB.isEmpty()) {
            costDesc += " + " + costB.getCount() + "x " + costB.getHoverName().getString();
          }

          buttons.add(ContainerButton.newBuilder()
            .setButtonId(i)
            .setLabel(result.getCount() + "x " + result.getHoverName().getString())
            .setDescription(costDesc)
            .setIconItemId(result.typeHolder().getRegisteredName())
            .setSelected(i == selectedTrade)
            .setDisabled(offer.isOutOfStock())
            .build());
        }
      }
      case BeaconMenu beaconMenu -> {
        if (minecraft.gui.screen() instanceof BeaconScreen screen && screen.getMenu() == beaconMenu) {
          for (var control : BeaconControls.describe(screen)) {
            buttons.add(ContainerButton.newBuilder()
              .setButtonId(control.id())
              .setLabel(control.label().getString())
              .setSelected(control.selected())
              .setDisabled(!control.button().active)
              .build());
          }
        }
      }
      case CrafterMenu crafterMenu -> {
        for (var i = 0; i < 9; i++) {
          var disabled = crafterMenu.isSlotDisabled(i);
          buttons.add(ContainerButton.newBuilder()
            .setButtonId(i)
            .setLabel((disabled ? "Enable Slot " : "Disable Slot ") + (i + 1))
            .setDescription(disabled ? "Slot is disabled" : "Slot is enabled")
            .setSelected(disabled)
            .setDisabled(crafterMenu.getSlot(i).hasItem() || !crafterMenu.getCarried().isEmpty())
            .build());
        }
      }
      case LecternMenu lecternMenu -> {
        // Lectern: page navigation buttons
        // Button 1 = previous page, Button 2 = next page, Button 3 = take book
        // Data slot 0 = current page
        var bookSlot = lecternMenu.getSlot(0);
        var book = bookSlot.getItem();
        var hasBook = !book.isEmpty();
        var writtenContent = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        var writableContent = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
        var pageCount = writtenContent != null ? writtenContent.pages().size()
          : writableContent != null ? writableContent.pages().size() : 0;
        var currentPage = lecternMenu.getPage();

        buttons.add(ContainerButton.newBuilder()
          .setButtonId(1)
          .setLabel("Previous Page")
          .setDescription(hasBook ? "Go to previous page" : "No book in lectern")
          .setIconItemId("minecraft:writable_book")
          .setDisabled(!hasBook || currentPage <= 0)
          .build());
        buttons.add(ContainerButton.newBuilder()
          .setButtonId(2)
          .setLabel("Next Page")
          .setDescription(hasBook ? "Go to next page" : "No book in lectern")
          .setIconItemId("minecraft:writable_book")
          .setDisabled(!hasBook || currentPage >= pageCount - 1)
          .build());
        buttons.add(ContainerButton.newBuilder()
          .setButtonId(3)
          .setLabel("Take Book")
          .setDescription(hasBook ? "Remove book from lectern" : "No book to take")
          .setIconItemId("minecraft:book")
          .setDisabled(!hasBook || minecraft.player == null || !minecraft.player.mayBuild())
          .build());
      }
      case null, default -> {}
    }

    return buttons;
  }

  /**
   * Gets a container type identifier for client rendering.
   */
  private static String getContainerTypeId(AbstractContainerMenu menu) {
    return switch (menu) {
      case InventoryMenu ignored -> "inventory";
      case AbstractMountInventoryMenu ignored -> "mount";
      case ChestMenu ignored -> "chest";
      case DispenserMenu ignored -> "dispenser";
      case HopperMenu ignored -> "hopper";
      case AbstractFurnaceMenu ignored -> "furnace";
      case CraftingMenu ignored -> "crafting";
      case AnvilMenu ignored -> "anvil";
      case EnchantmentMenu ignored -> "enchanting";
      case BrewingStandMenu ignored -> "brewing";
      case BeaconMenu ignored -> "beacon";
      case ShulkerBoxMenu ignored -> "shulker";
      case GrindstoneMenu ignored -> "grindstone";
      case StonecutterMenu ignored -> "stonecutter";
      case LoomMenu ignored -> "loom";
      case CartographyTableMenu ignored -> "cartography";
      case SmithingMenu ignored -> "smithing";
      case MerchantMenu ignored -> "merchant";
      case CrafterMenu ignored -> "crafter";
      case LecternMenu ignored -> "lectern";
      default -> "generic";
    };
  }


  /**
   * Gets the display title for a container menu.
   */
  static String getContainerTitle(AbstractContainerMenu menu, Minecraft minecraft) {
    // For player inventory, use a standard title
    if (menu instanceof InventoryMenu) {
      return "Inventory";
    }

    // Try to get the title from the screen if available
    if (minecraft.gui.screen() instanceof AbstractContainerScreen<?> containerScreen) {
      var title = containerScreen.getTitle();
      if (title != null) {
        return title.getString();
      }
    }

    // Fallback to class name
    return menu.getClass().getSimpleName().replace("Menu", "");
  }

  private static void requireMenu(Minecraft minecraft, String expectedMenuId) {
    if (expectedMenuId != null && (minecraft.player == null
      || !expectedMenuId.equals(((IMenuIdentity) minecraft.player.containerMenu).soulfire$getMenuId()))) {
      throw Status.ABORTED.withDescription("The container changed. Review the current inventory and try again.").asRuntimeException();
    }
  }

  @Override
  public void clickInventorySlot(BotInventoryClickRequest request, StreamObserver<BotInventoryClickResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      // Map proto ClickType to Minecraft container input and mouse button
      var slotId = request.getSlot();
      int mouseButton;
      ContainerInput clickType;

      switch (request.getClickType()) {
        case LEFT_CLICK -> {
          mouseButton = 0;
          clickType = ContainerInput.PICKUP;
        }
        case RIGHT_CLICK -> {
          mouseButton = 1;
          clickType = ContainerInput.PICKUP;
        }
        case SHIFT_LEFT_CLICK -> {
          mouseButton = 0;
          clickType = ContainerInput.QUICK_MOVE;
        }
        case DROP_ONE -> {
          mouseButton = 0;
          clickType = ContainerInput.THROW;
        }
        case DROP_ALL -> {
          mouseButton = 1;
          clickType = ContainerInput.THROW;
        }
        case SWAP_HOTBAR -> {
          if (request.getHotbarSlot() < 0 || request.getHotbarSlot() > 8) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid hotbar slot index").asRuntimeException();
          }
          mouseButton = request.getHotbarSlot(); // 0-8 for hotbar slots
          clickType = ContainerInput.SWAP;
        }
        case MIDDLE_CLICK -> {
          mouseButton = 2;
          clickType = ContainerInput.CLONE;
        }
        default -> {
          responseObserver.onNext(BotInventoryClickResponse.newBuilder()
            .setSuccess(false)
            .setError("Invalid click type")
            .build());
          responseObserver.onCompleted();
          return;
        }
      }

      var response = callInBotContext(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player or gameMode is not available").asRuntimeException();
        }

        executeAction(activeBot, () -> {
          requireMenu(minecraft, request.hasExpectedMenuId() ? request.getExpectedMenuId() : null);
          ContainerClicks.click(minecraft, slotId, mouseButton, clickType);
        });

        return BotInventoryClickResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error clicking inventory slot", t);
    }
  }

  @Override
  public void closeContainer(BotCloseContainerRequest request, StreamObserver<BotCloseContainerResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var response = callInBotContext(activeBot, () -> {
        var player = activeBot.minecraft().player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player is not available").asRuntimeException();
        }

        executeAction(activeBot, () -> {
          var minecraft = activeBot.minecraft();
          requireMenu(minecraft, request.hasExpectedMenuId() ? request.getExpectedMenuId() : null);
          minecraft.player.closeContainer();
        });
        return BotCloseContainerResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error closing container", t);
    }
  }

  @Override
  public void openInventory(BotOpenInventoryRequest request, StreamObserver<BotOpenInventoryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var response = callInBotContext(activeBot, () -> {
        var player = activeBot.minecraft().player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player is not available").asRuntimeException();
        }

        executeAction(activeBot, () -> {
          var minecraft = activeBot.minecraft();
          if (minecraft.gameMode.isServerControlledInventory()) {
            player.sendOpenInventory();
          } else {
            minecraft.tutorial.onOpenInventory();
            minecraft.gui.setScreen(new InventoryScreen(player));
          }
        });
        return BotOpenInventoryResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error opening inventory", t);
    }
  }

  @Override
  public void mouseClick(BotMouseClickRequest request, StreamObserver<BotMouseClickResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var response = callInBotContext(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        var player = minecraft.player;
        var level = minecraft.level;
        var gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player, level, or gameMode is not available").asRuntimeException();
        }

        return switch (request.getButton()) {
          case LEFT_BUTTON -> {
            executeAction(
              activeBot,
              () -> MouseClickHelper.performLeftClick(minecraft));
            yield BotMouseClickResponse.newBuilder()
              .setSuccess(true)
              .build();
          }
          case RIGHT_BUTTON -> {
            executeAction(
              activeBot,
              () -> MouseClickHelper.performRightClick(minecraft));
            yield BotMouseClickResponse.newBuilder()
              .setSuccess(true)
              .build();
          }
          default -> BotMouseClickResponse.newBuilder()
            .setSuccess(false)
            .setError("Invalid mouse button")
            .build();
        };
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error performing mouse click", t);
    }
  }

  @Override
  public void clickContainerButton(BotContainerButtonClickRequest request, StreamObserver<BotContainerButtonClickResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var success = new AtomicReference<Boolean>();
      executeAction(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        requireMenu(minecraft, request.hasExpectedMenuId() ? request.getExpectedMenuId() : null);
        success.set(activateContainerButton(minecraft, request.getButtonId()));
      });
      var response = BotContainerButtonClickResponse.newBuilder()
        .setSuccess(success.get())
        .build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error clicking container button", t);
    }
  }

  static boolean activateContainerButton(Minecraft minecraft, int id) {
    var player = minecraft.player;
    var gameMode = minecraft.gameMode;
    if (player == null || gameMode == null) {
      throw Status.FAILED_PRECONDITION.withDescription("Bot player or gameMode is not available").asRuntimeException();
    }
    var menu = player.containerMenu;
    if (menu instanceof CreativeModeInventoryScreen.ItemPickerMenu) {
      if (!(minecraft.gui.screen() instanceof CreativeModeInventoryScreen screen) || screen.getMenu() != menu) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot creative inventory screen is not available").asRuntimeException();
      }
      return CreativeInventoryControls.activate(screen, id);
    }
    if (menu instanceof BeaconMenu) {
      if (!(minecraft.gui.screen() instanceof BeaconScreen screen) || screen.getMenu() != menu) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot beacon screen is not available").asRuntimeException();
      }
      return BeaconControls.activate(screen, id);
    }
    if (menu instanceof CrafterMenu crafterMenu) {
      if (id < 0 || id >= 9 || crafterMenu.getSlot(id).hasItem() || !crafterMenu.getCarried().isEmpty() || player.isSpectator()) return false;
      if (!(minecraft.gui.screen() instanceof CrafterScreen screen) || screen.getMenu() != menu) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot crafter screen is not available").asRuntimeException();
      }
      ((CrafterScreenAccessor) screen).soulfire$updateSlotState(id, crafterMenu.isSlotDisabled(id));
      return true;
    }
    if (menu instanceof MerchantMenu merchantMenu) {
      if (id < 0 || id >= merchantMenu.getOffers().size()) return false;
      if (!(minecraft.gui.screen() instanceof MerchantScreen screen) || screen.getMenu() != menu) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot trading screen is not available").asRuntimeException();
      }
      var access = (MerchantScreenAccessor) screen;
      access.soulfire$setSelectedTrade(id);
      access.soulfire$selectTrade();
      return true;
    }
    var valid = switch (menu) {
      case StonecutterMenu stonecutter -> id >= 0 && id < stonecutter.getNumberOfVisibleRecipes();
      case LoomMenu loom -> id >= 0 && id < loom.getSelectablePatterns().size();
      case EnchantmentMenu enchanting -> id >= 0 && id < enchanting.costs.length && enchanting.clickMenuButton(player, id);
      case LecternMenu lectern -> buildContainerButtons(lectern, minecraft).stream()
        .anyMatch(button -> button.getButtonId() == id && !button.getDisabled());
      default -> false;
    };
    if (valid) gameMode.handleInventoryButtonClick(menu.containerId, id);
    return valid;
  }

  @Override
  public void getInventoryState(BotInventoryStateRequest request, StreamObserver<BotInventoryStateResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var response = callInBotContext(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        var player = minecraft.player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player is not available").asRuntimeException();
        }

        return minecraft.submit(() -> buildInventoryStatePublic(minecraft, player, true)).get(60, TimeUnit.SECONDS);
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error getting inventory state", t);
    }
  }

  // ============================================================================
  // Dialog Management (Minecraft 1.21.6+)
  // ============================================================================

  @Override
  public void setContainerText(BotSetContainerTextRequest request, StreamObserver<BotSetContainerTextResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var success = new AtomicReference<Boolean>();
      var error = new AtomicReference<String>();
      executeAction(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        requireMenu(minecraft, request.hasExpectedMenuId() ? request.getExpectedMenuId() : null);
        var player = minecraft.player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player is not available").asRuntimeException();
        }
        var container = player.containerMenu;
        var fieldId = request.getFieldId();
        var text = StringUtil.filterText(request.getText());
        if (container instanceof CreativeModeInventoryScreen.ItemPickerMenu && "search".equals(fieldId)) {
          if (!(minecraft.gui.screen() instanceof CreativeModeInventoryScreen screen) || screen.getMenu() != container) {
            throw Status.FAILED_PRECONDITION.withDescription("Bot creative inventory screen is not available").asRuntimeException();
          }
          CreativeInventoryControls.search(screen, text);
          success.set(true);
        } else if (container instanceof AnvilMenu && "item_name".equals(fieldId)) {
          if (minecraft.getConnection() == null) {
            throw Status.FAILED_PRECONDITION.withDescription("Bot network connection is not available").asRuntimeException();
          }
          if (!(minecraft.gui.screen() instanceof AnvilScreen screen) || screen.getMenu() != container) {
            throw Status.FAILED_PRECONDITION.withDescription("Bot anvil screen is not available").asRuntimeException();
          }
          // Keep the screen's text and its rename packet responder in sync.
          ((AnvilScreenAccessor) screen).soulfire$getNameInput().setValue(text);
          success.set(true);
        } else {
          success.set(false);
          error.set("Unsupported container or field ID: " + container.getClass().getSimpleName() + "/" + fieldId);
        }
      });
      var responseBuilder = BotSetContainerTextResponse.newBuilder().setSuccess(success.get());
      if (error.get() != null) responseBuilder.setError(error.get());
      var response = responseBuilder.build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error setting container text", t);
    }
  }

  @Override
  public void getDialog(BotGetDialogRequest request, StreamObserver<BotGetDialogResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      var responseBuilder = BotGetDialogResponse.newBuilder();

      if (activeBot != null) {
        var dialog = callInBotContext(activeBot, () -> activeBot.minecraft().submit(() -> {
          if (activeBot.minecraft().gui.screen() instanceof ConfirmLinkScreen link) return LinkConfirmationDialog.describe(link);
          var holder = DialogHandler.getCurrentDialog(activeBot);
          return holder == null ? null : DialogStateMapper.describe(holder,
            (IDialogScreen) activeBot.minecraft().gui.screen(), activeBot.minecraft().level);
        }).get(10, TimeUnit.SECONDS));
        if (dialog != null) responseBuilder.setDialog(dialog);
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error getting dialog", t);
    }
  }

  @Override
  public void submitDialog(BotSubmitDialogRequest request, StreamObserver<BotSubmitDialogResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var result = new AtomicReference<DialogControls.ClientActions>();
      executeAction(activeBot, () -> {
        result.set(DialogControls.act(activeBot.minecraft().gui.screen(), request.getInputValuesMap(), request.hasButtonIndex() ? request.getButtonIndex() : 0,
          request.hasExpectedScreenId() ? request.getExpectedScreenId() : null));
      });
      var builder = BotSubmitDialogResponse.newBuilder().setSuccess(true);
      result.get().clipboard().ifPresent(builder::setClipboard);
      result.get().openUrl().ifPresent(builder::setOpenUrl);
      var response = builder.build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error submitting dialog", t);
    }
  }

  @Override
  public void clickDialogButton(BotClickDialogButtonRequest request, StreamObserver<BotClickDialogButtonResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var result = new AtomicReference<DialogControls.ClientActions>();
      executeAction(activeBot, () -> {
        result.set(DialogControls.act(activeBot.minecraft().gui.screen(), Map.of(), request.getButtonIndex(),
          request.hasExpectedScreenId() ? request.getExpectedScreenId() : null));
      });
      var builder = BotClickDialogButtonResponse.newBuilder().setSuccess(true);
      result.get().clipboard().ifPresent(builder::setClipboard);
      result.get().openUrl().ifPresent(builder::setOpenUrl);
      var response = builder.build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error clicking dialog button", t);
    }
  }

  @Override
  public void closeDialog(BotCloseDialogRequest request, StreamObserver<BotCloseDialogResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var result = new AtomicReference<DialogControls.ClientActions>();
      executeAction(activeBot, () -> {
        var screen = activeBot.minecraft().gui.screen();
        if (DialogControls.supports(screen) || request.hasExpectedScreenId()) {
          result.set(DialogControls.act(screen, Map.of(), -1, request.hasExpectedScreenId() ? request.getExpectedScreenId() : null));
        }
      });
      var builder = BotCloseDialogResponse.newBuilder().setSuccess(true);
      if (result.get() != null) {
        result.get().clipboard().ifPresent(builder::setClipboard);
        result.get().openUrl().ifPresent(builder::setOpenUrl);
      }
      var response = builder.build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error closing dialog", t);
    }
  }

  @Override
  public void setHotbarSlot(BotSetHotbarSlotRequest request, StreamObserver<BotSetHotbarSlotResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var slot = request.getSlot();
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      // Validate slot range
      if (slot < 0 || slot > 8) {
        responseObserver.onNext(BotSetHotbarSlotResponse.newBuilder()
          .setSuccess(false)
          .setError("Hotbar slot must be between 0 and 8, got: " + slot)
          .build());
        responseObserver.onCompleted();
        return;
      }

      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var response = callInBotContext(activeBot, () -> {
        var player = activeBot.minecraft().player;
        if (player == null) {
          return BotSetHotbarSlotResponse.newBuilder()
            .setSuccess(false)
            .setError("Player is not available")
            .build();
        }

        executeAction(activeBot, () -> player.getInventory().setSelectedSlot(slot));
        log.info("Setting hotbar slot to {} for bot {}", slot, botId);
        return BotSetHotbarSlotResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error setting hotbar slot", t);
    }
  }

  @Override
  public void setMovementState(BotSetMovementStateRequest request, StreamObserver<BotSetMovementStateResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      // Apply movement state changes
      executeAction(activeBot, Set.of(ControlResource.MOVEMENT), () -> {
        var controlState = activeBot.controlState();

        if (request.hasForward()) {
          controlState.up(request.getForward());
        }
        if (request.hasBackward()) {
          controlState.down(request.getBackward());
        }
        if (request.hasLeft()) {
          controlState.left(request.getLeft());
        }
        if (request.hasRight()) {
          controlState.right(request.getRight());
        }
        if (request.hasJump()) {
          controlState.jump(request.getJump());
        }
        if (request.hasSneak()) {
          controlState.shift(request.getSneak());
        }
        if (request.hasSprint()) {
          controlState.sprint(request.getSprint());
        }
      });

      responseObserver.onNext(BotSetMovementStateResponse.newBuilder()
        .setSuccess(true)
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error setting movement state", t);
    }
  }

  @Override
  public void resetMovement(BotResetMovementRequest request, StreamObserver<BotResetMovementResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      // Reset all movement
      executeAction(
        activeBot,
        Set.of(ControlResource.MOVEMENT),
        () -> activeBot.controlState().resetAll());

      log.info("Reset movement for bot {}", botId);

      responseObserver.onNext(BotResetMovementResponse.newBuilder()
        .setSuccess(true)
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error resetting movement", t);
    }
  }

  @Override
  public void setRotation(BotSetRotationRequest request, StreamObserver<BotSetRotationResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    requireBotControl(soulFireServer, instanceId, botId);

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot == null) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot '%s' is not online".formatted(botId)).asRuntimeException();
      }

      var yaw = request.getYaw();
      var pitch = request.getPitch();

      if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
        throw Status.INVALID_ARGUMENT
          .withDescription("Yaw and pitch must be finite")
          .asRuntimeException();
      }

      var response = callInBotContext(activeBot, () -> {
        var player = activeBot.minecraft().player;
        if (player == null) {
          return BotSetRotationResponse.newBuilder()
            .setSuccess(false)
            .setError("Player is not available")
            .build();
        }

        executeAction(
          activeBot,
          Set.of(ControlResource.ROTATION),
          () -> activeBot.rotationControl().lookTo(yaw, pitch));
        return BotSetRotationResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      throw GrpcErrors.logAndMap(log, "Error setting rotation", t);
    }
  }
}
