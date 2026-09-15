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
package com.soulfiremc.server.renderer;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

final class InventoryComparisonScene {
  private InventoryComparisonScene() {}

  static void freezeEnvironment(Minecraft minecraft) {
    if (minecraft.level == null) {
      return;
    }
    var level = minecraft.level;
    level.setTimeFromServer(6000);
    level.clockManager().handleUpdates(6000, Map.of(level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow(), new ClockNetworkState(6000, 0, 0)));
    level.setRainLevel(0);
    level.setThunderLevel(0);
    for (var entity : StreamSupport.stream(level.entitiesForRendering().spliterator(), false).toList()) {
      if (entity != minecraft.player && entity.getId() != -1000) {
        entity.discard();
        continue;
      }
      entity.tickCount = 60;
    }
  }

  static void prepare(Minecraft minecraft) {
    var player = minecraft.player;
    player.setYRot(0);
    player.setXRot(0);
    player.setOldPosAndRot();
    player.getInventory().clearContent();
    var items = List.of(
      Items.DIAMOND_SWORD, Items.CHEST, Items.OAK_LOG, Items.STONE, Items.COMPASS, Items.DIAMOND,
      Items.SHIELD, Items.BANNER.pick(DyeColor.WHITE), Items.TRIDENT,
      Items.SPYGLASS, Items.BOW, Items.CROSSBOW, Items.FISHING_ROD, Items.IRON_AXE, Items.BUCKET,
      Items.WATER_BUCKET, Items.POTION, Items.SPLASH_POTION,
      Items.TIPPED_ARROW, Items.FIREWORK_ROCKET, Items.ENDER_PEARL, Items.AIR, Items.AIR,
      Items.PLAYER_HEAD, Items.SKELETON_SKULL, Items.AIR, Items.DECORATED_POT,
      Items.CONDUIT, Items.SHULKER_BOX, Items.OAK_BOAT, Items.MINECART, Items.ARMOR_STAND,
      Items.CAMPFIRE, Items.LANTERN, Items.BELL
    );
    for (var slot = 0; slot < items.size(); slot++) {
      player.getInventory().setItem(slot, new ItemStack(items.get(slot)));
    }
    player.getInventory().setSelectedSlot(0);
    player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
    player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
    player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
    var other = new RemotePlayer(minecraft.level,
      new GameProfile(UUID.nameUUIDFromBytes("OfflinePlayer:ProbeB".getBytes(StandardCharsets.UTF_8)), "ProbeB"));
    other.setId(-1000);
    other.setPos(player.getX() + 2.0, player.getY(), player.getZ() + 4.0);
    other.setYRot(180);
    other.setYHeadRot(180);
    other.setOldPosAndRot();
    other.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
    minecraft.level.addEntity(other);
    var boss = new LerpingBossEvent(UUID.nameUUIDFromBytes("Alpha HUD".getBytes(StandardCharsets.UTF_8)),
      Component.literal("Alpha HUD"), 0, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS, false, false, false);
    minecraft.gui.hud.getBossOverlay().reset();
    minecraft.gui.hud.getBossOverlay().update(ClientboundBossEventPacket.createAddPacket(boss));
    minecraft.gui.setScreen(new FixedInventoryScreen(minecraft));
  }

  static BufferedImage renderSoftware(Minecraft minecraft, int width, int height, Path output) {
    var camera = minecraft.gameRenderer.mainCamera();
    var position = camera.position();
    var options = new SoftwareRenderer.Options(position, camera.yRot(), camera.xRot(), width, height,
      camera.getFov(), 32, true, true, true);
    var metadata = Map.of(
      "scene", "inventory",
      "camera", Map.of("x", position.x, "y", position.y, "z", position.z,
        "yaw", camera.yRot(), "pitch", camera.xRot(), "fov", camera.getFov()),
      "gameTime", minecraft.level.getGameTime(),
      "dayTime", minecraft.level.getOverworldClockTime(),
      "partialTick", minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false),
      "guiScale", minecraft.getWindow().getGuiScale(),
      "softwareMaxDistance", 32,
      "nativeRenderDistanceChunks", minecraft.options.renderDistance().get()
    );
    try {
      Files.writeString(output.resolve("scene.json"), new GsonBuilder().setPrettyPrinting().create().toJson(metadata));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var result = SoftwareRenderer.renderWithResult(minecraft.level, minecraft.player, options);
    try {
      Files.writeString(output.resolve("software-trace.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result.debugTrace()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return result.image();
  }

  private static final class FixedInventoryScreen extends InventoryScreen {
    private FixedInventoryScreen(Minecraft minecraft) {
      super(minecraft.player);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      Minecraft.getInstance().gui.toastManager().clear();
      super.extractRenderState(graphics, 0, 0, partialTick);
    }
  }
}
