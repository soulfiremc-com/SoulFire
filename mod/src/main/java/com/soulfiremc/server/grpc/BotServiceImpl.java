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
import com.mojang.authlib.GameProfile;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControllingTask;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.plugins.DialogHandler;
import com.soulfiremc.server.renderer.RenderConstants;
import com.soulfiremc.server.renderer.SoftwareRenderer;
import com.soulfiremc.server.settings.lib.InstanceSettingsImpl;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.util.MouseClickHelper;
import com.soulfiremc.server.util.structs.GsonInstance;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.dialog.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.inventory.ContainerInput;
import org.jooq.impl.DSL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Callable;

@Slf4j
@RequiredArgsConstructor
public final class BotServiceImpl extends BotServiceGrpc.BotServiceImplBase {
  private final SoulFireServer soulFireServer;

  /**
   * Builds a BotLiveState from a LocalPlayer.
   *
   * @param player           The player to extract data from
   * @param includeInventory Whether to include full inventory data (more expensive)
   * @return The built BotLiveState
   */
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
    var dimension = player.level().dimension().identifier().toString();
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
          var slotBuilder = InventorySlot.newBuilder()
            .setSlot(slot.index)
            .setItemId(item.typeHolder().getRegisteredName())
            .setCount(item.getCount());

          // Check for custom display name
          var displayName = item.getHoverName();
          if (item.has(DataComponents.CUSTOM_NAME)) {
            slotBuilder.setDisplayName(displayName.getString());
          }

          builder.addInventory(slotBuilder.build());
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
          .set(Tables.INSTANCES.SETTINGS, GsonInstance.GSON.toJson(newSettings.serializeToTree()))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      responseObserver.onNext(BotUpdateConfigEntryResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error updating bot config entry", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
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
        var entryBuilder = BotListEntry.newBuilder()
          .setProfileId(profileId.toString())
          .setIsOnline(botConnections.containsKey(profileId));

        var activeBot = botConnections.get(profileId);
        if (activeBot != null) {
          var liveState = callInBotContext(activeBot, () -> {
            var minecraft = activeBot.minecraft();
            var player = minecraft.player;
            return player != null
              ? buildLiveState(minecraft, player, false)
              : null;
          });
          if (liveState != null) {
            // Don't include inventory for list view (too expensive)
            entryBuilder.setLiveState(liveState);
          }
        }
        responseBuilder.addBots(entryBuilder.build());
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting bot list", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
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

      var botInfoResponseBuilder = BotInfoResponse.newBuilder();
      var activeBot = instance.botConnections().get(botId);
      if (activeBot != null) {
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
      log.error("Error getting instance info", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
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

      // Use provided dimensions or defaults
      var width = request.getWidth() > 0 ? request.getWidth() : RenderConstants.DEFAULT_WIDTH;
      var height = request.getHeight() > 0 ? request.getHeight() : RenderConstants.DEFAULT_HEIGHT;

      // Clamp dimensions to reasonable values
      width = Math.min(Math.max(width, 1), 1920);
      height = Math.min(Math.max(height, 1), 1080);
      final var renderWidth = width;
      final var renderHeight = height;

      var response = callInBotContext(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player or level is not available").asRuntimeException();
        }

        var renderDistanceChunks = minecraft.options.getEffectiveRenderDistance();
        var maxDistance = renderDistanceChunks * 16;
        var image = SoftwareRenderer.render(
          level,
          player,
          renderWidth,
          renderHeight,
          RenderConstants.DEFAULT_FOV,
          maxDistance
        );

        return BotRenderPovResponse.newBuilder()
          .setImageBase64(toBase64PNG(image))
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error rendering bot POV", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  /**
   * Builds a ContainerLayout describing the slot regions for the given menu.
   * This allows the frontend to render any container type dynamically.
   */
  private static ContainerLayout buildContainerLayout(AbstractContainerMenu menu, String title) {
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

    // Add player inventory regions (common to all containers except pure player inventory)
    if (!(menu instanceof InventoryMenu)) {
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
    } else {
      // For player inventory, add main and hotbar
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
    for (var button : buildContainerButtons(menu)) {
      layoutBuilder.addButtons(button);
    }

    // Add text inputs for containers that support them
    if (menu instanceof AnvilMenu anvilMenu) {
      var currentName = "";
      var inputItem = anvilMenu.getSlot(0).getItem();
      if (!inputItem.isEmpty()) {
        // Get the item's custom name if it has one, otherwise empty
        if (inputItem.has(DataComponents.CUSTOM_NAME)) {
          currentName = inputItem.getHoverName().getString();
        }
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
  private static List<ContainerButton> buildContainerButtons(AbstractContainerMenu menu) {
    var buttons = new ArrayList<ContainerButton>();

    if (menu instanceof StonecutterMenu stonecutterMenu) {
      // Stonecutter: recipes are shown in the output slot when selected
      var inputSlot = stonecutterMenu.getSlot(0);
      if (!inputSlot.getItem().isEmpty()) {
        for (int i = 0; i < 20; i++) {  // Max typical stonecutter recipes
          buttons.add(ContainerButton.newBuilder()
            .setButtonId(i)
            .setLabel("Recipe " + (i + 1))
            .setDisabled(false)
            .build());
        }
      }
    } else if (menu instanceof EnchantmentMenu enchantmentMenu) {
      // Enchanting table: 3 enchantment slots
      // Slot 0 = item to enchant, Slot 1 = lapis lazuli
      var itemSlot = enchantmentMenu.getSlot(0);
      var lapisSlot = enchantmentMenu.getSlot(1);
      var hasItem = !itemSlot.getItem().isEmpty();
      var lapisCount = lapisSlot.getItem().isEmpty() ? 0 : lapisSlot.getItem().getCount();

      // Enchantment costs increase per slot: 1, 2, 3 lapis and levels
      int[] lapisCosts = {1, 2, 3};
      int[] levelCosts = {1, 2, 3}; // Minimum levels shown, actual varies

      for (int i = 0; i < 3; i++) {
        var needsLapis = lapisCosts[i];
        var disabled = !hasItem || lapisCount < needsLapis;
        var description = "Requires %d lapis, %d+ levels".formatted(needsLapis, levelCosts[i]);
        if (!hasItem) {
          description = "Insert item to enchant";
        } else if (lapisCount < needsLapis) {
          description = "Need %d more lapis lazuli".formatted(needsLapis - lapisCount);
        }

        buttons.add(ContainerButton.newBuilder()
          .setButtonId(i)
          .setLabel("Enchant Slot " + (i + 1))
          .setDescription(description)
          .setIconItemId("minecraft:enchanted_book")
          .setDisabled(disabled)
          .build());
      }
    } else if (menu instanceof LoomMenu ignored) {
      // Loom: banner patterns
      for (int i = 0; i < 40; i++) {  // Max banner patterns
        buttons.add(ContainerButton.newBuilder()
          .setButtonId(i)
          .setLabel("Pattern " + (i + 1))
          .setDisabled(false)
          .build());
      }
    } else if (menu instanceof MerchantMenu merchantMenu) {
      // Villager trading: each trade offer is a button
      var offers = merchantMenu.getOffers();
      for (int i = 0; i < offers.size(); i++) {
        var offer = offers.get(i);
        var result = offer.getResult();
        var costA = offer.getBaseCostA();
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
          .setDisabled(offer.isOutOfStock())
          .build());
      }
    } else if (menu instanceof BeaconMenu beaconMenu) {
      // Beacon effects are unlocked based on pyramid levels:
      // Level 1: Speed, Haste
      // Level 2: Resistance, Jump Boost
      // Level 3: Strength
      // Level 4: Secondary effects (Regeneration or amplified primary)
      // Payment slot must have a valid item to confirm

      var beaconLevel = beaconMenu.getLevels();
      var hasPayment = !beaconMenu.getSlot(0).getItem().isEmpty();

      // Get actual MobEffect registry to iterate and find effects by name
      var mobEffectRegistry = BuiltInRegistries.MOB_EFFECT;

      // Primary effects with their required levels
      record BeaconEffectDef(String effectName, String displayName, int requiredLevel, boolean isPrimary) {}
      var effects = new BeaconEffectDef[]{
        new BeaconEffectDef("speed", "Speed", 1, true),
        new BeaconEffectDef("haste", "Haste", 1, true),
        new BeaconEffectDef("resistance", "Resistance", 2, true),
        new BeaconEffectDef("jump_boost", "Jump Boost", 2, true),
        new BeaconEffectDef("strength", "Strength", 3, true),
        new BeaconEffectDef("regeneration", "Regeneration", 4, false),
      };

      // Iterate through registry to find effects by name and get their IDs
      for (var entry : mobEffectRegistry.entrySet()) {
        var key = entry.getKey();
        var effectName = key.identifier().getPath(); // e.g., "speed", "haste"

        for (var effectDef : effects) {
          if (effectName.equals(effectDef.effectName)) {
            var effectRegistryId = mobEffectRegistry.getId(entry.getValue());
            var disabled = beaconLevel < effectDef.requiredLevel;

            log.debug("Beacon effect {} has registry ID {}", effectDef.effectName, effectRegistryId);

            buttons.add(ContainerButton.newBuilder()
              .setButtonId(effectRegistryId)
              .setLabel(effectDef.displayName)
              .setDescription("Level " + effectDef.requiredLevel + "+ (" + (effectDef.isPrimary ? "Primary" : "Secondary") + ")"
                + (disabled ? " - Requires more beacon levels" : ""))
              .setIconItemId("minecraft:potion")
              .setDisabled(disabled)
              .build());
            break;
          }
        }
      }

      // Confirm button - disabled if no payment item
      buttons.add(ContainerButton.newBuilder()
        .setButtonId(-1)
        .setLabel("Confirm")
        .setDescription(hasPayment ? "Apply beacon effects" : "Insert payment item (iron/gold/emerald/diamond/netherite ingot)")
        .setIconItemId("minecraft:beacon")
        .setDisabled(!hasPayment)
        .build());
    } else if (menu instanceof CrafterMenu ignored) {
      // Crafter: 9 slot toggle buttons (button ID = slot index)
      // Clicking toggles whether the slot is disabled
      for (int i = 0; i < 9; i++) {
        buttons.add(ContainerButton.newBuilder()
          .setButtonId(i)
          .setLabel("Toggle Slot " + (i + 1))
          .setDescription("Enable/disable crafter slot")
          .setDisabled(false)
          .build());
      }
    } else if (menu instanceof LecternMenu lecternMenu) {
      // Lectern: page navigation buttons
      // Button 1 = previous page, Button 2 = next page, Button 3 = take book
      // Data slot 0 = current page
      var bookSlot = lecternMenu.getSlot(0);
      var hasBook = !bookSlot.getItem().isEmpty();
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
        .setDisabled(!hasBook)  // Can't easily know max pages, let server reject
        .build());
      buttons.add(ContainerButton.newBuilder()
        .setButtonId(3)
        .setLabel("Take Book")
        .setDescription(hasBook ? "Remove book from lectern" : "No book to take")
        .setIconItemId("minecraft:book")
        .setDisabled(!hasBook)
        .build());
    }

    return buttons;
  }

  /**
   * Gets a container type identifier for client rendering.
   */
  private static String getContainerTypeId(AbstractContainerMenu menu) {
    return switch (menu) {
      case InventoryMenu ignored -> "inventory";
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
   * Converts a Minecraft Dialog to the proto ServerDialog format.
   * This is a simplified conversion that captures the essential dialog information.
   */
  private static ServerDialog convertDialogToProto(Holder<Dialog> dialogHolder) {
    var dialog = dialogHolder.value();
    var builder = ServerDialog.newBuilder();

    // Set dialog ID from the holder key if available
    dialogHolder.unwrapKey().ifPresent(key ->
      builder.setId(key.identifier().toString()));

    // Set dialog type based on the concrete class
    if (dialog instanceof NoticeDialog) {
      builder.setType(DialogType.DIALOG_TYPE_NOTICE);
    } else if (dialog instanceof ConfirmationDialog) {
      builder.setType(DialogType.DIALOG_TYPE_CONFIRMATION);
    } else if (dialog instanceof MultiActionDialog) {
      builder.setType(DialogType.DIALOG_TYPE_MULTI_ACTION);
    } else if (dialog instanceof ServerLinksDialog) {
      builder.setType(DialogType.DIALOG_TYPE_SERVER_LINKS);
    } else if (dialog instanceof DialogListDialog) {
      builder.setType(DialogType.DIALOG_TYPE_DIALOG_LIST);
    }

    // Set a basic title from the class name if we can't access the actual title
    builder.setTitle(dialog.getClass().getSimpleName().replace("Dialog", ""));

    return builder.build();
  }


  /**
   * Gets the display title for a container menu.
   */
  private static String getContainerTitle(AbstractContainerMenu menu, Minecraft minecraft) {
    // For player inventory, use a standard title
    if (menu instanceof InventoryMenu) {
      return "Inventory";
    }

    // Try to get the title from the screen if available
    if (minecraft.screen instanceof AbstractContainerScreen<?> containerScreen) {
      var title = containerScreen.getTitle();
      if (title != null) {
        return title.getString();
      }
    }

    // Fallback to class name
    return menu.getClass().getSimpleName().replace("Menu", "");
  }

  @Override
  public void clickInventorySlot(BotInventoryClickRequest request, StreamObserver<BotInventoryClickResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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

        var containerId = player.containerMenu.containerId;
        activeBot.botControl().registerControllingTask(
          ControllingTask.singleTick(() ->
            gameMode.handleContainerInput(containerId, slotId, mouseButton, clickType, player)));

        return BotInventoryClickResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error clicking inventory slot", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void closeContainer(BotCloseContainerRequest request, StreamObserver<BotCloseContainerResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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

        activeBot.botControl().registerControllingTask(ControllingTask.singleTick(player::closeContainer));
        return BotCloseContainerResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error closing container", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void openInventory(BotOpenInventoryRequest request, StreamObserver<BotOpenInventoryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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

        activeBot.botControl().registerControllingTask(ControllingTask.singleTick(player::sendOpenInventory));
        return BotOpenInventoryResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error opening inventory", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void mouseClick(BotMouseClickRequest request, StreamObserver<BotMouseClickResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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
            activeBot.botControl().registerControllingTask(ControllingTask.singleTick(() ->
              MouseClickHelper.performLeftClick(player, level, gameMode)));
            yield BotMouseClickResponse.newBuilder()
              .setSuccess(true)
              .build();
          }
          case RIGHT_BUTTON -> {
            activeBot.botControl().registerControllingTask(ControllingTask.singleTick(() ->
              MouseClickHelper.performRightClick(player, level, gameMode)));
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
      log.error("Error performing mouse click", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void clickContainerButton(BotContainerButtonClickRequest request, StreamObserver<BotContainerButtonClickResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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

      var buttonId = request.getButtonId();

      var response = callInBotContext(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player or gameMode is not available").asRuntimeException();
        }

        var container = player.containerMenu;
        var validClick = switch (container) {
          case StonecutterMenu ignored -> buttonId >= 0 && buttonId < 100;
          case EnchantmentMenu ignored -> buttonId >= 0 && buttonId < 3;
          case LoomMenu ignored -> buttonId >= 0 && buttonId < 100;
          case MerchantMenu merchantMenu -> buttonId >= 0 && buttonId < merchantMenu.getOffers().size();
          case BeaconMenu ignored -> true;
          case CrafterMenu ignored -> buttonId >= 0 && buttonId < 9;
          case LecternMenu ignored -> buttonId >= 1 && buttonId <= 3;
          default -> true;
        };

        if (!validClick) {
          return BotContainerButtonClickResponse.newBuilder()
            .setSuccess(false)
            .setError("Invalid button ID for this container type")
            .build();
        }

        log.info("Clicking container button {} on container {} (type: {})", buttonId, container.containerId, container.getClass().getSimpleName());
        activeBot.botControl().registerControllingTask(
          ControllingTask.singleTick(() -> gameMode.handleInventoryButtonClick(container.containerId, buttonId)));
        return BotContainerButtonClickResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error clicking container button", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
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

        var container = player.containerMenu;
        var title = getContainerTitle(container, minecraft);
        var layout = buildContainerLayout(container, title);
        var responseBuilder = BotInventoryStateResponse.newBuilder()
          .setLayout(layout)
          .setSelectedHotbarSlot(player.getInventory().getSelectedSlot());

        for (var slot : container.slots) {
          var item = slot.getItem();
          if (!item.isEmpty()) {
            var slotBuilder = InventorySlot.newBuilder()
              .setSlot(slot.index)
              .setItemId(item.typeHolder().getRegisteredName())
              .setCount(item.getCount());

            if (item.has(DataComponents.CUSTOM_NAME)) {
              slotBuilder.setDisplayName(item.getHoverName().getString());
            }

            responseBuilder.addSlots(slotBuilder.build());
          }
        }

        var carried = container.getCarried();
        if (!carried.isEmpty()) {
          var carriedBuilder = InventorySlot.newBuilder()
            .setSlot(-1)
            .setItemId(carried.typeHolder().getRegisteredName())
            .setCount(carried.getCount());

          if (carried.has(DataComponents.CUSTOM_NAME)) {
            carriedBuilder.setDisplayName(carried.getHoverName().getString());
          }

          responseBuilder.setCarriedItem(carriedBuilder.build());
        }

        return responseBuilder.build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting inventory state", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  // ============================================================================
  // Dialog Management (Minecraft 1.21.6+)
  // ============================================================================

  @Override
  public void setContainerText(BotSetContainerTextRequest request, StreamObserver<BotSetContainerTextResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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

      var fieldId = request.getFieldId();
      var text = request.getText();

      var response = callInBotContext(activeBot, () -> {
        var minecraft = activeBot.minecraft();
        var player = minecraft.player;
        if (player == null) {
          throw Status.FAILED_PRECONDITION.withDescription("Bot player is not available").asRuntimeException();
        }

        var container = player.containerMenu;
        if (container instanceof AnvilMenu anvilMenu && "item_name".equals(fieldId)) {
          if (minecraft.getConnection() == null) {
            throw Status.FAILED_PRECONDITION.withDescription("Bot network connection is not available").asRuntimeException();
          }

          activeBot.botControl().registerControllingTask(
            ControllingTask.singleTick(() -> anvilMenu.setItemName(text)));
          return BotSetContainerTextResponse.newBuilder()
            .setSuccess(true)
            .build();
        }

        return BotSetContainerTextResponse.newBuilder()
          .setSuccess(false)
          .setError("Unsupported container or field ID: " + container.getClass().getSimpleName() + "/" + fieldId)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting container text", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
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
        var dialogHolder = callInBotContext(activeBot, () -> DialogHandler.getCurrentDialog(activeBot));
        if (dialogHolder != null) {
          responseBuilder.setDialog(convertDialogToProto(dialogHolder));
        }
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting dialog", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void submitDialog(BotSubmitDialogRequest request, StreamObserver<BotSubmitDialogResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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
        var dialogHolder = DialogHandler.getCurrentDialog(activeBot);
        if (dialogHolder == null) {
          return BotSubmitDialogResponse.newBuilder()
            .setSuccess(false)
            .setError("No dialog is currently displayed")
            .build();
        }

        log.info("Dialog submit requested with {} input values - clearing dialog state", request.getInputValuesCount());
        DialogHandler.setCurrentDialog(activeBot, null);
        return BotSubmitDialogResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error submitting dialog", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void clickDialogButton(BotClickDialogButtonRequest request, StreamObserver<BotClickDialogButtonResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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
        var dialogHolder = DialogHandler.getCurrentDialog(activeBot);
        if (dialogHolder == null) {
          return BotClickDialogButtonResponse.newBuilder()
            .setSuccess(false)
            .setError("No dialog is currently displayed")
            .build();
        }

        log.info("Dialog button click requested (button index: {}) - clearing dialog state", request.getButtonIndex());
        DialogHandler.setCurrentDialog(activeBot, null);
        return BotClickDialogButtonResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error clicking dialog button", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void closeDialog(BotCloseDialogRequest request, StreamObserver<BotCloseDialogResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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
        DialogHandler.setCurrentDialog(activeBot, null);
        return BotCloseDialogResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error closing dialog", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void setHotbarSlot(BotSetHotbarSlotRequest request, StreamObserver<BotSetHotbarSlotResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    var slot = request.getSlot();
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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

        activeBot.botControl().registerControllingTask(
          ControllingTask.singleTick(() -> player.getInventory().setSelectedSlot(slot)));
        log.info("Setting hotbar slot to {} for bot {}", slot, botId);
        return BotSetHotbarSlotResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting hotbar slot", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void setMovementState(BotSetMovementStateRequest request, StreamObserver<BotSetMovementStateResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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
      activeBot.botControl().registerControllingTask(ControllingTask.singleTick(() -> {
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
      }));

      responseObserver.onNext(BotSetMovementStateResponse.newBuilder()
        .setSuccess(true)
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting movement state", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void resetMovement(BotResetMovementRequest request, StreamObserver<BotResetMovementResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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
      activeBot.botControl().registerControllingTask(ControllingTask.singleTick(() ->
        activeBot.controlState().resetAll()));

      log.info("Reset movement for bot {}", botId);

      responseObserver.onNext(BotResetMovementResponse.newBuilder()
        .setSuccess(true)
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error resetting movement", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void setRotation(BotSetRotationRequest request, StreamObserver<BotSetRotationResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var botId = UUID.fromString(request.getBotId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

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

      // Clamp pitch to valid range
      pitch = Math.max(-90f, Math.min(90f, pitch));

      // Normalize yaw to -180 to 180 range
      while (yaw > 180f) {
        yaw -= 360f;
      }
      while (yaw < -180f) {
        yaw += 360f;
      }

      final float finalYaw = yaw;
      final float finalPitch = pitch;

      var response = callInBotContext(activeBot, () -> {
        var player = activeBot.minecraft().player;
        if (player == null) {
          return BotSetRotationResponse.newBuilder()
            .setSuccess(false)
            .setError("Player is not available")
            .build();
        }

        activeBot.botControl().registerControllingTask(ControllingTask.singleTick(() -> {
          player.setYRot(finalYaw);
          player.setXRot(finalPitch);
        }));
        return BotSetRotationResponse.newBuilder()
          .setSuccess(true)
          .build();
      });

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting rotation", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }
}
