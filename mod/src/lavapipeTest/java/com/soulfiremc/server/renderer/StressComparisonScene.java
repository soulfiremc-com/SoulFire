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
import com.soulfiremc.manual.mixin.StressEntityAccess;
import com.soulfiremc.manual.mixin.StressGuardianAccess;
import com.soulfiremc.manual.mixin.StressItemAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.BossEvent;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.waypoints.Waypoint;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

/// Shared, client-only stage. Native rendering and POV rendering consume the same frozen objects.
final class StressComparisonScene {
  private static final long SEED = 0x50_5546_4652L;
  private static final Set<Entity> ENTITIES = new LinkedHashSet<>();
  private static final Map<String, Integer> ENTITY_COUNTS = new LinkedHashMap<>();
  private static final Map<BlockPos, String> PLACED_BLOCKS = new LinkedHashMap<>();
  private static final Map<String, Integer> BLOCK_COUNTS = new LinkedHashMap<>();
  private static final Map<String, Integer> PARTICLE_COUNTS = new LinkedHashMap<>();
  private static final List<String> EXCLUSIONS = new ArrayList<>();
  private static BlockPos origin;
  private static Vec3 eyeBase;
  private static float yaw;
  private static float pitch;
  private static boolean hudView;

  private StressComparisonScene() {}

  static boolean ready(Minecraft minecraft) {
    var pos = minecraft.player.blockPosition();
    for (var x = -2; x <= 2; x++) {
      for (var z = -2; z <= 2; z++) {
        if (!minecraft.level.getChunkSource().hasChunk((pos.getX() >> 4) + x, (pos.getZ() >> 4) + z)) {
          return false;
        }
      }
    }
    return true;
  }

  static void prepare(Minecraft minecraft, String view) {
    hudView = view.equals("stress-hud");
    origin = minecraft.player.blockPosition();
    minecraft.level.getRandom().setSeed(SEED);
    LavapipeComparison.resetEntitySeed(SEED);
    LavapipeComparison.resetParticleSeed(SEED);
    // Clear only the local stage; no commands or changes are sent to the server.
    for (var x = -20; x <= 20; x++) {
      for (var z = -20; z <= 28; z++) {
        put(minecraft, x, -1, z, block((x + z & 1) == 0 ? "smooth_quartz" : "polished_deepslate"));
        for (var y = 0; y <= 18; y++) {
          put(minecraft, x, y, z, block("air"));
        }
      }
    }
    buildBlocks(minecraft);
    LavapipeComparison.resetEntitySeed(SEED);
    buildEntities(minecraft);
    buildDisplays(minecraft);
    buildParticles(minecraft);
    InventoryComparisonScene.prepare(minecraft);
    minecraft.gui.setScreen(null);
    var remote = minecraft.level.getEntity(-1000);
    remote.setPos(at(6, 0, 4));
    remote.setOldPosAndRot();
    ENTITIES.add(remote);
    ENTITY_COUNTS.put("minecraft:player", 1);
    minecraft.options.glintSpeed().set(0.0);
    buildHud(minecraft);
    minecraft.player.setHealth(13);
    minecraft.player.setAbsorptionAmount(4);
    minecraft.player.experienceLevel = 27;
    minecraft.player.experienceProgress = 0.65F;
    minecraft.player.getFoodData().setFoodLevel(14);
    minecraft.player.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200));
    minecraft.player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200));
    minecraft.player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 1200));
    eyeBase = at(0, 12, -18);
    yaw = 0;
    pitch = 24;
    switch (view) {
      case "stress-transparency" -> { eyeBase = at(-11, 1, -5); pitch = 0; }
      case "stress-entities" -> { eyeBase = at(9, 3, -6); yaw = 12; pitch = 8; }
      case "stress-hud" -> {
        eyeBase = at(0, 1, -7); pitch = 0;
        for (var x = -1; x <= 1; x++) {
          for (var y = 0; y <= 3; y++) {
            for (var z = -8; z <= -6; z++) put(minecraft, x, y, z, block("water"));
          }
        }
        minecraft.player.setPos(eyeBase);
        minecraft.player.baseTick();
        minecraft.player.setTicksFrozen(120);
        minecraft.player.setSharedFlagOnFire(true);
        minecraft.player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        minecraft.player.setAirSupply(120);
        minecraft.gui.hud.setTimes(0, 10000, 0);
        minecraft.gui.hud.setTitle(Component.literal("HUD / POV"));
        minecraft.gui.hud.setSubtitle(Component.literal("Layers • entities • particles"));
      }
      default -> { }
    }
    minecraft.gui.hud.setOverlayMessage(Component.literal("Fixed scene / seed " + SEED), false);
    minecraft.gui.hud.getBossOverlay().reset();
    for (var i = 0; i < 3; i++) {
      var boss = new LerpingBossEvent(uuid("boss" + i), Component.literal("Stress " + (i + 1)),
        (i + 1) / 4F, BossEvent.BossBarColor.values()[i], BossEvent.BossBarOverlay.values()[i], false, false, false);
      minecraft.gui.hud.getBossOverlay().update(ClientboundBossEventPacket.createAddPacket(boss));
    }
    freeze(minecraft);
  }

  private static void buildBlocks(Minecraft minecraft) {
    var materials = List.of("stone", "glass", "red_stained_glass", "ice", "slime_block", "honey_block",
      "oak_leaves", "oak_stairs", "oak_slab", "oak_fence", "cobblestone_wall", "red_carpet", "snow",
      "oak_trapdoor", "iron_chain", "white_banner", "skeleton_skull", "oak_sign", "chest", "barrel",
      "purple_shulker_box", "furnace", "campfire", "soul_campfire", "candle", "redstone_torch",
      "redstone_wire", "piston_head", "decorated_pot", "conduit", "bell", "enchanting_table");
    for (var i = 0; i < materials.size(); i++) {
      var x = -18 + i % 8 * 2;
      var z = 12 + i / 8 * 3;
      var state = block(materials.get(i));
      for (var property : state.getProperties()) {
        if (property.getName().equals("waterlogged") || property.getName().equals("lit")) {
          state = stateWith(state, property.getName(), "true");
        }
      }
      put(minecraft, x, 0, z, state);
      if (minecraft.level.getBlockEntity(origin.offset(x, 0, z)) instanceof SignBlockEntity sign) {
        sign.setText(sign.getText(SignTextSlot.FRONT).asMutable().setLine(0, Component.literal("Glowing text"))
          .setLine(1, Component.literal("§lPixels Ω")).setColor(DyeColor.CYAN).setTextGlowing(true).asImmutable(), SignTextSlot.FRONT);
      }
    }
    var layers = List.of("water", "glass", "blue_stained_glass", "ice", "slime_block", "honey_block", "oak_leaves", "nether_portal");
    for (var i = 0; i < layers.size(); i++) {
      for (var x = -13; x <= -9; x++) {
        for (var y = 0; y < 4; y++) {
          put(minecraft, x, y, i * 2, block(layers.get(i)));
        }
      }
    }
    for (var x = -2; x <= 2; x++) {
      for (var z = 16; z <= 20; z++) put(minecraft, x, -1, z, block("iron_block"));
    }
    put(minecraft, 0, 0, 18, block("beacon"));
    put(minecraft, 0, 5, 18, block("red_stained_glass"));
    put(minecraft, 0, 8, 18, block("blue_stained_glass"));
    var beaconPos = origin.offset(0, 0, 18);
    var beacon = (BeaconBlockEntity) minecraft.level.getBlockEntity(beaconPos);
    for (var i = 0; i < 100; i++) BeaconBlockEntity.tick(minecraft.level, beaconPos, block("beacon"), beacon);
    for (var x = 3; x < 7; x++) {
      for (var z = 19; z < 23; z++) put(minecraft, x, 0, z, block("end_portal"));
    }
    put(minecraft, 8, 2, 22, block("end_gateway"));
    put(minecraft, -7, 0, 10, stateWith(block("water"), "level", "3"));
    put(minecraft, -6, 0, 10, stateWith(block("lava"), "level", "4"));
    put(minecraft, -7, 0, 12, block("bubble_column"));
    put(minecraft, -6, 0, 12, block("soul_fire"));
  }

  private static void setClientGlowing(Entity entity, boolean glowing) {
    // Entity.FLAG_GLOWING is synchronized metadata; setGlowingTag only works on the server.
    ((StressEntityAccess) entity).setClientFlag(6, glowing);
  }

  private static void buildEntities(Minecraft minecraft) {
    // Every registered type with a client factory gets a slot. Exceptions are recorded, never silently omitted.
    var index = 0;
    for (var type : BuiltInRegistries.ENTITY_TYPE) {
      var name = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
      if (List.of("player", "fishing_bobber").contains(name)) continue;
      var entity = type.create(minecraft.level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
      if (entity == null) {
        EXCLUSIONS.add(name + ": no client factory");
        continue;
      }
      var x = -4 + index % 10 * 2.3;
      var z = 1 + index / 10 * 1.7;
      if (name.equals("ender_dragon") || name.equals("giant") || name.equals("ghast")) {
        x = 12; z = 25;
      }
      add(minecraft, entity, x, name.equals("ender_dragon") ? 10 : 0, z);
      index++;
    }
    add(minecraft, new FishingHook(minecraft.player, minecraft.level, 0, 0), 2, 1, 3);
    var holder = spawn(minecraft, "camel", 4, 0, 1, "{}");
    var boat = spawn(minecraft, "oak_boat", 4, 0, 1, "{}");
    holder.startRiding(boat, true, false);
    var vehicle = holder;
    for (var name : List.of("villager", "chicken", "armor_stand")) {
      var rider = spawn(minecraft, name, 4, 2, 1, "{}");
      rider.startRiding(vehicle, true, false);
      vehicle.positionRider(rider);
      vehicle = rider;
    }
    for (var i = 0; i < 20; i++) {
      var stand = (ArmorStand) spawn(minecraft, "armor_stand", -3 + i % 10 * 1.6, 0, -2 + i / 10 * 1.2,
        "{ShowArms:1b,Small:" + (i % 3 == 0 ? "1b" : "0b") + ",NoBasePlate:1b}");
      stand.setHeadPose(new Rotations(i * 7, i * 11, i * 3));
      stand.setLeftArmPose(new Rotations(-90 + i * 8, 0, -30));
      stand.setRightArmPose(new Rotations(-90, i * 9, 30));
      stand.setLeftLegPose(new Rotations(i * 4, 0, 10));
      stand.setRightLegPose(new Rotations(-i * 4, 0, -10));
      equip(stand);
      setClientGlowing(stand, i % 4 == 0);
      stand.setInvisible(i % 5 == 0);
    }
    for (var i = 0; i < 8; i++) {
      var sheep = spawn(minecraft, "sheep", -7 + i, 0, 6, "{Color:" + i + "b}");
      ((Leashable) sheep).setLeashedTo(holder, false);
      setClientGlowing(sheep, i % 2 == 0);
    }
    var zombie = (LivingEntity) spawn(minecraft, "zombie", 7, 0, -1, "{IsBaby:1b}");
    equip(zombie);
    zombie.setInvisible(true);
    setClientGlowing(zombie, true);
    spawn(minecraft, "creeper", 8, 0, 2, "{powered:1b}");
    spawn(minecraft, "enderman", 10, 0, 2, "{carriedBlockState:{Name:'minecraft:grass_block'}}");
    var dinnerbone = spawn(minecraft, "cow", 12, 0, 2, "{}");
    dinnerbone.setCustomName(Component.literal("Dinnerbone"));
    spawn(minecraft, "shulker", 14, 0, 2, "{Peek:80b}");
    spawn(minecraft, "slime", 16, 0, 6, "{Size:2}");
    spawn(minecraft, "magma_cube", 16, 0, 10, "{Size:1}");
    spawn(minecraft, "bee", 7, 3, 2, "{HasNectar:1b}");
    spawn(minecraft, "item", 0, 1, 0, "{Item:{id:'minecraft:diamond',count:32}}");
    spawn(minecraft, "falling_block", 1, 2, 0, "{BlockState:{Name:'minecraft:anvil'},Time:1}");
    var burning = spawn(minecraft, "zombie", -10, 0, 10, "{}");
    burning.setSharedFlagOnFire(true);
    burning.setTicksFrozen(120);
    var guardian = spawn(minecraft, "guardian", 2, 1, 6, "{}");
    ((StressGuardianAccess) guardian).setAttackTarget(holder.getId());
    ((StressGuardianAccess) guardian).setAttackTime(40);
    spawn(minecraft, "item_frame", 0, 2, 9, "{Facing:2b,Item:{id:'minecraft:filled_map',count:1}}");
    spawn(minecraft, "glow_item_frame", 2, 2, 9, "{Facing:2b,Item:{id:'minecraft:clock',count:1}}");
    spawn(minecraft, "wolf", 10, 0, 4, "{Owner:[I;0,0,0,1],CollarColor:11b}");
    var drowned = (LivingEntity) spawn(minecraft, "drowned", 12, 0, 4, "{}");
    drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
    var allay = (LivingEntity) spawn(minecraft, "allay", 4, 3, 4, "{}");
    allay.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.AMETHYST_SHARD));
  }

  private static void buildDisplays(Minecraft minecraft) {
    for (var i = 0; i < 32; i++) {
      var scale = i % 3 == 0 ? "[-1.2f,0.1f,2f]" : "[0.6f,0.6f,0.6f]";
      spawn(minecraft, "block_display", -5 + i % 8 * 1.5, 5 + i / 8 * 1.2, 12,
        "{block_state:{Name:'minecraft:amethyst_block'},brightness:{block:15,sky:15},transformation:{translation:[0f,0f,0f],scale:"
          + scale + ",left_rotation:[0f,0.3826834f,0f,0.9238795f],right_rotation:[0f,0f,0f,1f]}}");
    }
    for (var i = 0; i < 4; i++) {
      var billboard = List.of("fixed", "vertical", "horizontal", "center").get(i);
      spawn(minecraft, "text_display", -6 + i * 4, 4, 5,
        "{text:{text:'§lRenderer Ω\\n§oUnicode ♦',color:'aqua'},billboard:'" + billboard
          + "',background:1073742079,shadow:1b,line_width:100,alignment:'left'}");
      spawn(minecraft, "item_display", -5 + i * 4, 3, 6,
        "{item:{id:'minecraft:diamond_sword',count:1,components:{'minecraft:enchantment_glint_override':true}},item_display:'gui',billboard:'"
          + billboard + "'}");
    }
  }

  private static void buildParticles(Minecraft minecraft) {
    var engine = minecraft.particleEngine;
    engine.clearParticles();
    engine.getRandom().setSeed(SEED);
    LavapipeComparison.resetParticleSeed(SEED);
    var types = new ArrayList<ParticleOptions>(List.of(ParticleTypes.EXPLOSION, ParticleTypes.SMOKE,
      ParticleTypes.FLAME, ParticleTypes.SOUL, ParticleTypes.PORTAL, ParticleTypes.REVERSE_PORTAL,
      ParticleTypes.ENCHANT, ParticleTypes.CRIT, ParticleTypes.ENCHANTED_HIT, ParticleTypes.DRIPPING_WATER,
      ParticleTypes.DRIPPING_LAVA, ParticleTypes.CHERRY_LEAVES, ParticleTypes.SONIC_BOOM,
      ParticleTypes.SCULK_SOUL, ParticleTypes.CAMPFIRE_COSY_SMOKE,
      new DustParticleOptions(0xFF3300, 1.5F), new DustColorTransitionOptions(0x00FFFF, 0xFF00FF, 2),
      new BlockParticleOption(ParticleTypes.BLOCK, block("amethyst_block")),
      new ItemParticleOption(ParticleTypes.ITEM, Items.DIAMOND)));
    for (var i = 0; i < types.size(); i++) {
      var type = types.get(i);
      for (var j = 0; j < 6; j++) {
        var pos = at(-12 + i % 8 * 3, 1 + j * 0.3, 2 + i / 8 * 4);
        if (engine.createParticle(type, pos.x, pos.y, pos.z, 0.01, 0.02, 0.01) != null) {
          PARTICLE_COUNTS.merge(BuiltInRegistries.PARTICLE_TYPE.getKey(type.getType()).toString(), 1, Integer::sum);
        }
      }
    }
    engine.tick();
    engine.tick();
  }

  private static void buildHud(Minecraft minecraft) {
    var scoreboard = minecraft.level.getScoreboard();
    var objective = scoreboard.addObjective("render_stress", ObjectiveCriteria.DUMMY, Component.literal("Render coverage"),
      ObjectiveCriteria.RenderType.INTEGER, false, null);
    scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
    scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Entity types"), objective).set(ENTITY_COUNTS.size());
    scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Particles"), objective).set(PARTICLE_COUNTS.size());
    scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Block types"), objective).set(BLOCK_COUNTS.size());
    for (var i = 0; i < 4; i++) {
      var icon = new Waypoint.Icon();
      icon.color = Optional.of(List.of(0xFF00FF, 0x00FFFF, 0xFFFF00, 0x00FF00).get(i));
      ClientboundTrackedWaypointPacket.addWaypointPosition(uuid("marker" + i), icon, origin.offset(-15 + i * 10, 0, 24))
        .apply(minecraft.getConnection().getWaypointManager());
    }
    minecraft.gui.hud.getChat().clearMessages(true);
    minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal("§aFrozen renderer fixture ready"));
  }

  private static void equip(LivingEntity entity) {
    for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND)) {
      var item = switch (slot) {
        case HEAD -> Items.DIAMOND_HELMET;
        case CHEST -> Items.DIAMOND_CHESTPLATE;
        case LEGS -> Items.DIAMOND_LEGGINGS;
        case FEET -> Items.DIAMOND_BOOTS;
        default -> Items.DIAMOND_SWORD;
      };
      var stack = new ItemStack(item);
      stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      entity.setItemSlot(slot, stack);
    }
  }

  private static Entity spawn(Minecraft minecraft, String name, double x, double y, double z, String data) {
    var entity = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(name)).create(minecraft.level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
    if (entity == null) throw new IllegalStateException("No factory for " + name);
    try {
      entity.setPos(at(x, y, z));
      var initial = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, minecraft.level.registryAccess());
      entity.saveWithoutId(initial);
      var tag = initial.buildResult().merge(TagParser.parseCompoundFully(data));
      entity.load(TagValueInput.create(ProblemReporter.DISCARDING, minecraft.level.registryAccess(), tag));
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
      throw new IllegalArgumentException("Invalid fixture for " + name, e);
    }
    return add(minecraft, entity, x, y, z);
  }

  private static Entity add(Minecraft minecraft, Entity entity, double x, double y, double z) {
    entity.setId(-2000 - ENTITIES.size());
    entity.setUUID(uuid("entity" + ENTITIES.size()));
    entity.getRandom().setSeed(SEED + ENTITIES.size());
    if (entity instanceof LightningBolt lightning) lightning.seed = SEED;
    if (entity instanceof ItemEntity item) ((StressItemAccess) item).setBobOffset(0.5F);
    entity.setPos(at(x, y, z));
    entity.setYRot(180);
    entity.setOldPosAndRot();
    entity.setNoGravity(true);
    entity.tickCount = 60;
    if (entity instanceof LivingEntity living) {
      living.setYHeadRot(180);
      living.yHeadRotO = 180;
      living.yBodyRot = 180;
      living.yBodyRotO = 180;
    }
    if (entity instanceof Display display) display.tick();
    minecraft.level.addEntity(entity);
    ENTITIES.add(entity);
    ENTITY_COUNTS.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), 1, Integer::sum);
    return entity;
  }

  static void freeze(Minecraft minecraft) {
    if (origin == null || minecraft.level == null || minecraft.player == null) return;
    var level = minecraft.level;
    var time = hudView ? 12000 : 6000;
    level.setTimeFromServer(time);
    level.clockManager().handleUpdates(time, Map.of(level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow(), new ClockNetworkState(time, 0, 0)));
    level.setRainLevel(hudView ? 0.6F : 0);
    level.setThunderLevel(hudView ? 0.4F : 0);
    for (var entity : StreamSupport.stream(level.entitiesForRendering().spliterator(), false).toList()) {
      if (entity != minecraft.player && !ENTITIES.contains(entity)) entity.discard();
    }
    minecraft.player.setPos(eyeBase);
    minecraft.player.setYRot(yaw);
    minecraft.player.setXRot(pitch);
    minecraft.player.setOldPosAndRot();
    minecraft.player.xBob = minecraft.player.xBobO = pitch;
    minecraft.player.yBob = minecraft.player.yBobO = yaw;
    minecraft.player.tickCount = 60;
    minecraft.player.attackStrengthTicker = 100;
    minecraft.player.itemSwapTicker = 100;
    minecraft.gui.hud.overlayMessageTime = 60;
    minecraft.gui.hud.toolHighlightTimer = 40;
    minecraft.gui.hud.titleTime = 60;
    minecraft.gui.hud.vignetteBrightness = 0.25F;
  }

  static BufferedImage renderOffscreen(Minecraft minecraft, int width, int height, Path output, String view) throws IOException {
    var camera = minecraft.gameRenderer.mainCamera();
    var distance = minecraft.options.getEffectiveRenderDistance() * 16;
    var options = new VulkanRenderer.Options(camera.position(), camera.yRot(), camera.xRot(), width, height, camera.getFov(), distance, true, true, true);
    var result = VulkanRenderer.renderWithResult(minecraft.level, minecraft.player, options);
    var gson = new GsonBuilder().setPrettyPrinting().create();
    Files.writeString(output.resolve("scene.json"), gson.toJson(Map.of(
      "scene", view, "seed", SEED, "camera", Map.of("position", camera.position(), "yaw", camera.yRot(), "pitch", camera.xRot(), "fov", camera.getFov()),
      "entities", ENTITY_COUNTS, "blocks", BLOCK_COUNTS, "particles", PARTICLE_COUNTS,
      "excludedEntities", EXCLUSIONS, "headlessMaxDistance", distance,
      "glowingEntities", ENTITIES.stream().filter(Entity::isCurrentlyGlowing).count(),
      "coverageNote", "Counts describe stage contents, not visibility. Views deliberately overlap features; inspect the native image for occlusion.")));
    Files.writeString(output.resolve("vulkan-trace.json"), gson.toJson(result.debugTrace()));
    return result.image();
  }

  private static Vec3 at(double x, double y, double z) { return Vec3.atLowerCornerOf(origin).add(x, y, z); }
  private static UUID uuid(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)); }
  private static BlockState block(String name) { return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name)).defaultBlockState(); }
  private static <T extends Comparable<T>> BlockState withValue(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
    return state.setValue(property, property.getValue(value).orElseThrow());
  }
  private static BlockState stateWith(BlockState state, String property, String value) {
    return withValue(state, state.getBlock().getStateDefinition().getProperty(property), value);
  }
  private static void put(Minecraft minecraft, int x, int y, int z, BlockState state) {
    var position = origin.offset(x, y, z);
    minecraft.level.setBlock(position, state, 18);
    var previous = PLACED_BLOCKS.remove(position);
    if (previous != null) BLOCK_COUNTS.computeIfPresent(previous, (_, count) -> count == 1 ? null : count - 1);
    if (!state.isAir()) {
      var name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      PLACED_BLOCKS.put(position, name);
      BLOCK_COUNTS.merge(name, 1, Integer::sum);
    }
  }
}
