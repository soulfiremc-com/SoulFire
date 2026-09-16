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

import com.soulfiremc.manual.mixin.StressGuardianAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

import static com.soulfiremc.server.renderer.FixtureWorld.block;

/// Dimension, environment and animation fixtures with deliberately overlapping render features.
final class EnvironmentComparisonScenes {
  private EnvironmentComparisonScenes() {}

  static FixtureWorld prepare(Minecraft minecraft, ComparisonScenario scenario) {
    var world = new FixtureWorld(minecraft, scenario);
    switch (scenario.family()) {
      case "end" -> end(world);
      case "nether" -> nether(world);
      case "submerged" -> submerged(world);
      case "machinery" -> machinery(world);
      case "weather" -> weather(world);
      default -> throw new IllegalArgumentException("Unknown scene family: " + scenario.family());
    }
    world.finish();
    return world;
  }

  private static void end(FixtureWorld w) {
    w.box(-24, -2, -12, 24, -1, 40, "end_stone");
    w.box(-4, 0, 1, 4, 0, 9, "obsidian");
    w.box(-3, 0, 2, 3, 0, 8, "end_portal");
    for (var x : new int[]{-10, 10}) {
      w.box(x - 1, 0, 15, x + 1, 5, 17, "obsidian");
      var crystal = (EndCrystal) w.spawn("end_crystal", x + 0.5, 6, 16.5, "{}");
      crystal.time = w.scenario.animationTick();
      crystal.setBeamTarget(w.pos(0, 10, 24));
    }
    var dragon = (EnderDragon) w.spawn("ender_dragon", 0, 9, 24, "{}");
    dragon.nearestCrystal = (EndCrystal) w.entities.getFirst();
    dragon.oFlapTime = w.scenario.animationTick() * 0.03F;
    dragon.flapTime = dragon.oFlapTime + 0.03F;
    for (var i = 0; i < 64; i++) dragon.flightHistory.record(w.origin.getY() + 9 + Math.sin(i * 0.12), 175 + i * 0.1F);
    if (w.scenario.variant().equals("dragon-death")) dragon.dragonDeathTime = w.scenario.animationTick();
    w.put(8, 3, 7, "end_gateway");
    var gateway = w.blockEntity(8, 3, 7, TheEndGatewayBlockEntity.class);
    for (var i = 0; i < w.scenario.animationTick(); i++) TheEndGatewayBlockEntity.beamAnimationTick(w.level, gateway.getBlockPos(), gateway.getBlockState(), gateway);
    if (!gateway.isSpawning()) throw new IllegalStateException("Gateway beam must be active");
    for (var x : new int[]{-7, 7}) {
      w.put(x, 0, 11, "purpur_block");
      w.spawn("shulker", x + 0.5, 1, 11.5, "{Peek:80b,Color:10b}");
      w.spawn("shulker_bullet", x * 0.5, 3, 7, "{}");
    }
    w.box(-6, 1, 13, -4, 4, 13, "purple_stained_glass");
    w.box(4, 1, 11, 6, 4, 11, "tinted_glass");
    w.particle(net.minecraft.core.particles.PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1), -2, 1, 7, 24);
    w.particle(ParticleTypes.REVERSE_PORTAL, 8, 2, 7, 15);
    w.particle(ParticleTypes.END_ROD, -7, 2, 10, 12);
    w.camera(0, 7, -12, 0, 15);
    switch (w.scenario.variant()) {
      case "portals" -> w.camera(1, 2, -2, -20, 18);
      case "dragon-death" -> w.camera(0, 8, 7, 0, -5);
      case "clipping" -> w.camera(7.6, 3, 7.5, -90, 0);
      default -> { }
    }
    w.time = 650 + w.scenario.animationTick();
    var flash = w.level.endFlashState();
    if (flash != null) { flash.tick(w.time - 1); flash.tick(w.time); }
    w.features.put("end", List.of("dimension sky and flash", "portal surfaces", "spawning gateway beam", "crystal beams", "dragon flight history", "opening shulkers", "dragon breath"));
    w.features.put("dragonDeathTime", dragon.dragonDeathTime);
    w.features.put("gatewaySpawnPercent", gateway.getSpawnPercent(w.scenario.partialTick()));
  }

  private static void nether(FixtureWorld w) {
    w.box(-24, -2, -16, 24, -1, 46, "blackstone");
    w.box(-24, 16, -16, 24, 17, 46, "netherrack");
    w.box(-24, 0, -16, -23, 15, 46, "basalt");
    w.box(23, 0, -16, 24, 15, 46, "basalt");
    w.box(-12, 0, 4, -7, 0, 20, "lava");
    w.box(-10, 1, 12, -10, 14, 12, "lava");
    w.box(-12, 1, 7, -7, 7, 7, "orange_stained_glass");
    w.box(-12, 1, 9, -7, 7, 9, "red_stained_glass");
    for (var z : new int[]{2, 14, 30, 44}) {
      w.box(11, 0, z, 13, 8, z + 1, "polished_basalt");
      w.put(12, 9, z, "shroomlight");
    }
    var palette = List.of("crimson_nylium", "warped_nylium", "soul_sand", "magma_block", "nether_gold_ore", "glowstone");
    for (var i = 0; i < palette.size(); i++) {
      w.put(-5 + i * 2, 0, 5, palette.get(i));
      w.put(-5 + i * 2, 1, 5, i % 2 == 0 ? "soul_fire" : "fire");
    }
    w.box(-2, 0, 18, 2, 5, 18, "obsidian");
    w.box(-1, 1, 18, 1, 4, 18, "nether_portal");
    w.spawn("blaze", -4, 3, 10, "{}");
    w.spawn("magma_cube", 4, 0, 8, "{Size:2}");
    w.spawn("strider", -9, 1, 16, "{}");
    w.spawn("wither_skeleton", 7, 0, 15, "{}");
    w.spawn("creeper", 1, 0, 8, "{powered:1b}");
    w.spawn("ghast", 0, 8, 34, "{}");
    w.particle(ParticleTypes.LARGE_SMOKE, -8, 1, 8, 30);
    w.particle(ParticleTypes.FLAME, -4, 1, 3, 18);
    w.particle(ParticleTypes.SOUL_FIRE_FLAME, 4, 1, 4, 18);
    w.particle(ParticleTypes.ASH, 0, 4, 12, 24);
    w.particle(ParticleTypes.WHITE_ASH, 5, 5, 15, 18);
    w.particle(ParticleTypes.LAVA, -9, 1, 12, 8);
    w.camera(0, 3, -13, 0, 4);
    if (w.scenario.variant().equals("lava")) {
      w.box(-2, -1, -6, 2, 3, -1, "lava");
      w.minecraft.player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200));
      w.camera(0, 0, -4, 0, 0);
    }
    w.features.put("nether", List.of("biome fog", "dimension lighting", "lava behind glass", "portal", "charged creeper", "smoke and flame overlap", "distance markers"));
  }

  private static void submerged(FixtureWorld w) {
    w.box(-20, -2, -12, 20, -1, 36, "sand");
    w.box(-20, 0, -12, 20, 7, 36, "water");
    w.box(-12, 0, 12, 12, 0, 26, "prismarine_bricks");
    for (var x : new int[]{-11, -4, 4, 11}) {
      w.box(x, 1, 17, x, 5, 17, "dark_prismarine");
      w.put(x, 6, 17, "sea_lantern");
    }
    for (var x = -8; x <= 8; x += 2) {
      w.put(x, 1, 12, block("prismarine_stairs").setValue(BlockStateProperties.WATERLOGGED, true));
      w.put(x, 1, 14, block("oak_fence").setValue(BlockStateProperties.WATERLOGGED, true));
    }
    w.put(-6, 0, 4, "soul_sand");
    w.box(-6, 1, 4, -6, 6, 4, "bubble_column");
    w.put(6, 0, 4, "magma_block");
    for (var y = 1; y < 7; y++) w.put(6, y, 4, block("bubble_column").setValue(BlockStateProperties.DRAG, true));
    w.put(-3, 1, 6, "brain_coral");
    w.put(-4, 1, 7, "tube_coral_fan");
    w.put(4, 1, 7, "seagrass");
    w.box(8, 1, 10, 8, 4, 10, "kelp_plant");
    w.put(8, 5, 10, "kelp");
    w.box(-2, 1, 9, 2, 4, 9, "cyan_stained_glass");
    w.put(0, 3, 20, "conduit");
    for (var x = -2; x <= 2; x++) for (var y = -2; y <= 2; y++) for (var z = -2; z <= 2; z++) {
      if ((x == 0 && (Math.abs(y) == 2 || Math.abs(z) == 2))
        || (y == 0 && (Math.abs(x) == 2 || Math.abs(z) == 2))
        || (z == 0 && (Math.abs(x) == 2 || Math.abs(y) == 2))) w.put(x, y + 3, z + 20, "prismarine");
    }
    var conduit = w.blockEntity(0, 3, 20, ConduitBlockEntity.class);
    for (var i = 0; i < w.scenario.animationTick(); i++) ConduitBlockEntity.clientTick(w.level, conduit.getBlockPos(), conduit.getBlockState(), conduit);
    if (!conduit.isActive() || !conduit.isHunting()) throw new IllegalStateException("Conduit frame did not activate");
    var target = w.spawn("squid", -5, 3, 7, "{}");
    var guardian = (Guardian) w.spawn("guardian", 5, 3, 14, "{}");
    ((StressGuardianAccess) guardian).setAttackTarget(target.getId());
    ((StressGuardianAccess) guardian).setAttackTime(40);
    w.spawn("dolphin", -8, 3, 17, "{}");
    w.spawn("glow_squid", 7, 3, 22, "{}");
    w.spawn("tropical_fish", -2, 3, 6, "{Variant:65536}");
    w.spawn("drowned", 4, 1, 24, "{}");
    w.particle(ParticleTypes.BUBBLE, -4, 2, 4, 24);
    w.particle(ParticleTypes.BUBBLE_POP, 3, 4, 6, 12);
    w.particle(ParticleTypes.CURRENT_DOWN, 6, 3, 4, 16);
    w.particle(ParticleTypes.NAUTILUS, 0, 3, 19, 12);
    w.particle(ParticleTypes.UNDERWATER, 1, 3, 8, 24);
    w.camera(0, 2, -7, 0, 0);
    if (w.scenario.variant().equals("surface")) w.camera(0, 10, -10, 0, 26);
    if (w.scenario.variant().equals("beam")) w.camera(-6, 2, 2, -35, 0);
    w.features.put("submerged", List.of("biome water color", "water surface", "waterlogged stairs and fences", "bubble columns", "coral", "guardian beam", "active conduit"));
    w.features.put("conduitActive", conduit.isActive());
    w.features.put("conduitHunting", conduit.isHunting());
  }

  private static void machinery(FixtureWorld w) {
    w.box(-20, -2, -12, 20, -1, 30, "smooth_quartz");
    for (var i = 0; i < 4; i++) {
      var x = -9 + i * 6;
      w.put(x, 0, 6, block("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
      var chest = w.blockEntity(x, 0, 6, ChestBlockEntity.class);
      chest.triggerEvent(1, 1);
      w.put(x, 0, 10, block("purple_shulker_box").setValue(BlockStateProperties.FACING, Direction.values()[i]));
      var shulker = w.blockEntity(x, 0, 10, ShulkerBoxBlockEntity.class);
      shulker.triggerEvent(1, 1);
      w.put(x, 3, 8, "bell");
      var bell = w.blockEntity(x, 3, 8, BellBlockEntity.class);
      bell.triggerEvent(1, Direction.NORTH.get3DDataValue());
      for (var tick = 0; tick < w.scenario.animationTick(); tick++) {
        ChestBlockEntity.lidAnimateTick(w.level, chest.getBlockPos(), chest.getBlockState(), chest);
        ShulkerBoxBlockEntity.tick(w.level, shulker.getBlockPos(), shulker.getBlockState(), shulker);
        BellBlockEntity.clientTick(w.level, bell.getBlockPos(), bell.getBlockState(), bell);
      }
      w.features.put("chest-" + i, chest.getOpenNess(w.scenario.partialTick()));
      var moving = block("moving_piston").setValue(BlockStateProperties.FACING, Direction.NORTH);
      w.put(x, 0, 2, moving);
      var piston = new PistonMovingBlockEntity(w.pos(x, 0, 2), moving,
        block(i % 2 == 0 ? "slime_block" : "honey_block"), Direction.NORTH, i % 2 == 0, false);
      w.level.setBlockEntity(piston);
      PistonMovingBlockEntity.tick(w.level, piston.getBlockPos(), moving, piston);
      w.put(x, 0, 13, "white_banner");
      w.put(x, 1, 13, "oak_hanging_sign");
      var sign = w.blockEntity(x, 1, 13, SignBlockEntity.class);
      sign.setText(sign.getFrontText().setMessage(0, Component.literal("Moving Ω " + i))
        .setColor(DyeColor.CYAN).setHasGlowingText(true), true);
      var display = (Display) w.spawn("block_display", x, 4, 14,
        "{block_state:{Name:'amethyst_block'},transformation:{scale:[1.4f,0.3f,1.4f],left_rotation:[0f,0.3826834f,0f,0.9238795f]}}");
      display.tick();
      display.setYRot(w.scenario.animationTick() * 8);
      display.yRotO = display.getYRot() - 8;
    }
    w.box(-10, 1, 17, 10, 5, 17, "red_stained_glass");
    w.box(-10, 1, 19, 10, 5, 19, "blue_stained_glass");
    w.particle(new BlockParticleOption(ParticleTypes.BLOCK, block("slime_block")), -8, 1, 1, 18);
    w.particle(new DustColorTransitionOptions(0xFF0000, 0x0000FF, 2), 4, 2, 8, 18);
    w.time = 6000 + w.scenario.animationTick();
    w.camera(0, 4, -10, 0, 12);
    if (w.scenario.variant().equals("clipping")) w.camera(-9, 0.25, 2.05, 0, 0);
    w.features.put("machinery", List.of("interpolated chest lids", "rotating shulker lids", "piston motion", "bell swing", "banner cloth", "glowing hanging signs", "display transforms", "translucent layers"));
  }

  private static void weather(FixtureWorld w) {
    w.box(-32, -2, -20, 32, -1, 44, "grass_block");
    for (var z : new int[]{0, 12, 28, 40}) {
      for (var x : new int[]{-12, 12}) {
        w.box(x, 0, z, x + 1, 5, z + 1, "oak_log");
        w.box(x - 2, 5, z - 2, x + 3, 7, z + 3, "oak_leaves");
        w.put(x, 0, z - 1, "lantern");
      }
    }
    w.box(-5, 0, 12, 5, 4, 12, "glass");
    w.box(-5, 0, 16, 5, 4, 16, "white_stained_glass");
    w.spawn("sheep", -4, 0, 5, "{Color:3b}");
    w.spawn("fox", 5, 0, 8, "{Type:'snow'}");
    w.particle(ParticleTypes.CAMPFIRE_COSY_SMOKE, -6, 0, 7, 16);
    w.particle(ParticleTypes.SNOWFLAKE, 2, 3, 5, 20);
    w.rain = 1;
    w.time = 12000;
    w.camera(0, 3, -12, 0, 4);
    switch (w.scenario.variant()) {
      case "lightning" -> {
        w.thunder = 1;
        w.skyFlash = 2;
        var bolt = (LightningBolt) w.spawn("lightning_bolt", 4, 0, 12, "{}");
        bolt.seed = FixtureWorld.SEED;
      }
      case "night" -> { w.time = 18000; w.rain = 0; w.camera(0, 3, -12, 0, -20); }
      case "clouds" -> {
        w.rain = 0;
        w.time = 6000;
        w.box(-10, 0, 12, -4, 25, 18, "stone");
        w.box(6, 0, 20, 12, 20, 26, "snow_block");
        w.camera(0, 7, -10, 0, -3);
      }
      case "blindness" -> {
        w.minecraft.player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 1200));
        w.camera(-4, 1, 2, 0, 8);
        w.put(-2, 0, 4, "lantern");
      }
      case "darkness" -> w.minecraft.player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 1200));
      case "powder-snow" -> {
        w.box(-2, 0, -10, 2, 4, -6, "powder_snow");
        w.put(-1, 1, -7, "chiseled_stone_bricks");
        w.put(0, 1, -7, "oak_log");
        w.camera(0, 0, -8, 0, 0);
        w.minecraft.player.setTicksFrozen(120);
      }
      default -> { }
    }
    w.features.put("weather", List.of("precipitation", "distance fog", "foliage", "glass overlap", "smoke"));
    w.features.put("visibilityMode", w.scenario.variant());
  }
}
