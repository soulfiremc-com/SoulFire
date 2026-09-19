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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Owns an isolated client world. The connection supplies registries, never fixture commands.
final class FixtureWorld {
  static final long SEED = 0x454E56524F4EL;
  final Minecraft minecraft;
  final ClientLevel level;
  final ComparisonScenario scenario;
  final BlockPos origin;
  final List<Entity> entities = new ArrayList<>();
  final Map<String, Integer> blocks = new LinkedHashMap<>();
  final Map<String, Integer> particles = new LinkedHashMap<>();
  final Map<String, Object> features = new LinkedHashMap<>();
  private Vec3 feet;
  private float yaw;
  private float pitch;
  long time = 6000;
  float rain;
  float thunder;
  int skyFlash;

  FixtureWorld(Minecraft minecraft, ComparisonScenario scenario) {
    this.minecraft = minecraft;
    this.scenario = scenario;
    origin = new BlockPos(0, scenario.variant().equals("clouds") ? 184 : 80, 0);
    var connection = minecraft.getConnection();
    var dimension = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(scenario.dimension()));
    var dimensionType = connection.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
      .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.withDefaultNamespace(scenario.dimension())));
    level = new ClientLevel(connection, new ClientLevel.ClientLevelData(minecraft.level.getDifficulty(), false, false),
      dimension, dimensionType, 6, 5, minecraft.levelExtractor, false, SEED, 63);
    ((StressEntityAccess) minecraft.player).assignFixtureLevel(level);
    minecraft.setLevel(level);
    level.addEntity(minecraft.player);
    minecraft.setCameraEntity(minecraft.player);
    if (!minecraft.gui.hud.isHidden()) minecraft.gui.hud.toggle();
    minecraft.options.glintSpeed().set(0.0);
    minecraft.player.removeAllEffects();
    minecraft.player.setNoGravity(true);
    minecraft.player.setDeltaMovement(Vec3.ZERO);
    minecraft.player.setHealth(20);
    minecraft.player.setTicksFrozen(0);
    minecraft.player.setSharedFlagOnFire(false);
    level.getRandom().setSeed(SEED);
    LavapipeComparison.resetEntitySeed(SEED);
    LavapipeComparison.resetParticleSeed(SEED);
    minecraft.particleEngine.getRandom().setSeed(SEED);
    initializeChunks();
    camera(0, 5, -18, 0, 12);
    freeze();
  }

  private void initializeChunks() {
    var biome = level.registryAccess().lookupOrThrow(Registries.BIOME)
      .getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(scenario.biome())));
    var cache = level.getChunkSource();
    cache.updateViewCenter(0, 0);
    var lighting = cache.getLightEngine();
    for (var x = -5; x <= 5; x++) {
      for (var z = -5; z <= 5; z++) {
        var chunk = new LevelChunk(level, new ChunkPos(x, z));
        for (var section : chunk.getSections()) section.fillBiomesFromNoise((_, _, _) -> biome, 0, 0, 0);
        var packet = new ClientboundLevelChunkPacketData(chunk);
        cache.replaceWithPacketData(x, z, packet);
        lighting.setLightEnabled(chunk.getPos(), true);
        for (var y = level.getMinSectionY() - 1; y <= level.getMaxSectionY(); y++) {
          var section = SectionPos.of(x, y, z);
          lighting.queueSectionData(LightLayer.BLOCK, section, new DataLayer());
          if (level.dimensionType().hasSkyLight()) lighting.queueSectionData(LightLayer.SKY, section, new DataLayer(15));
        }
      }
    }
    lighting.runLightUpdates();
  }

  Vec3 at(double x, double y, double z) {
    return Vec3.atLowerCornerOf(origin).add(x, y, z);
  }

  BlockPos pos(int x, int y, int z) {
    return origin.offset(x, y, z);
  }

  static BlockState block(String name) {
    return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name)).defaultBlockState();
  }

  void put(int x, int y, int z, String name) {
    put(x, y, z, block(name));
  }

  void put(int x, int y, int z, BlockState state) {
    level.setBlock(pos(x, y, z), state, 18);
    blocks.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
  }

  void box(int x0, int y0, int z0, int x1, int y1, int z1, String name) {
    for (var x = x0; x <= x1; x++) for (var y = y0; y <= y1; y++) for (var z = z0; z <= z1; z++) put(x, y, z, name);
  }

  <T extends BlockEntity> T blockEntity(int x, int y, int z, Class<T> type) {
    return type.cast(java.util.Objects.requireNonNull(level.getBlockEntity(pos(x, y, z)), "Missing fixture block entity"));
  }

  Entity spawn(String name, double x, double y, double z, String data) {
    var entity = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(name))
      .create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
    if (entity == null) throw new IllegalStateException("No entity factory: " + name);
    try {
      entity.setPos(at(x, y, z));
      var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
      entity.saveWithoutId(output);
      entity.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(),
        output.buildResult().merge(TagParser.parseCompoundFully(data))));
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
      throw new IllegalArgumentException("Invalid fixture entity: " + name, e);
    }
    entity.setId(-4000 - entities.size());
    entity.setUUID(UUID.nameUUIDFromBytes((scenario.name() + ":" + entities.size()).getBytes(StandardCharsets.UTF_8)));
    entity.getRandom().setSeed(SEED + entities.size());
    entity.setPos(at(x, y, z));
    entity.setYRot(180);
    entity.setOldPosAndRot();
    entity.setNoGravity(true);
    entity.tickCount = scenario.animationTick();
    if (entity instanceof LivingEntity living) {
      living.setYHeadRot(180);
      living.yHeadRotO = living.yBodyRot = living.yBodyRotO = 180;
    }
    level.addEntity(entity);
    entities.add(entity);
    return entity;
  }

  void particle(ParticleOptions type, double x, double y, double z, int count) {
    for (var i = 0; i < count; i++) {
      var point = at(x + (i % 5 - 2) * 0.28, y + i * 0.08, z + (i / 5) * 0.35);
      var particle = minecraft.particleEngine.createParticle(type, point.x, point.y, point.z, 0.025, 0.035, -0.01);
      if (particle == null) throw new IllegalStateException("No particle factory: " + type);
      particles.merge(BuiltInRegistries.PARTICLE_TYPE.getKey(type.getType()).toString(), 1, Integer::sum);
    }
  }

  void camera(double x, double y, double z, float yaw, float pitch) {
    feet = at(x, y, z);
    this.yaw = yaw;
    this.pitch = pitch;
  }

  void finish() {
    for (var i = 0; i < Math.min(6, scenario.animationTick()); i++) minecraft.particleEngine.tick();
    var lighting = level.getChunkSource().getLightEngine();
    while (lighting.hasLightWork()) lighting.runLightUpdates();
    freeze();
    minecraft.player.baseTick();
    freeze();
  }

  void freeze() {
    level.setTimeFromServer(time);
    var clocks = new LinkedHashMap<net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock>, ClockNetworkState>();
    for (var key : List.of(WorldClocks.OVERWORLD, WorldClocks.THE_END)) {
      clocks.put(level.registryAccess().get(key).orElseThrow(), new ClockNetworkState(time, 0, 0));
    }
    level.clockManager().handleUpdates(time, clocks);
    level.setRainLevel(rain);
    level.setThunderLevel(thunder);
    level.setSkyFlashTime(skyFlash);
    level.updateSkyBrightness();
    minecraft.player.setPos(feet);
    minecraft.player.setYRot(yaw);
    minecraft.player.setXRot(pitch);
    minecraft.player.setOldPosAndRot();
    minecraft.player.tickCount = scenario.animationTick();
    minecraft.player.setDeltaMovement(Vec3.ZERO);
    // Both processes sample the frozen weather, without cached values from intervening client ticks.
    var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
    probe.reset();
    probe.tick(level, minecraft.player.getEyePosition());
  }

  BufferedImage renderOffscreen(int width, int height, Path output) throws IOException {
    var camera = minecraft.gameRenderer.mainCamera();
    var distance = minecraft.options.getEffectiveRenderDistance() * 16;
    var options = new VulkanRenderer.Options(camera.position(), camera.yRot(), camera.xRot(), width, height,
      camera.getFov(), distance, false, false, true);
    var result = VulkanRenderer.renderWithResult(level, minecraft.player, options);
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("scenario", scenario);
    metadata.put("seed", SEED);
    metadata.put("dimension", level.dimension().identifier().toString());
    metadata.put("cameraBiome", level.getBiome(camera.blockPosition()).unwrapKey().orElseThrow().identifier().toString());
    metadata.put("camera", Map.of("position", camera.position(), "yaw", camera.yRot(), "pitch", camera.xRot(), "fov", camera.getFov()));
    metadata.put("environment", Map.of("time", time, "rain", rain, "thunder", thunder, "skyFlash", skyFlash,
      "partialTick", minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
    metadata.put("features", features);
    metadata.put("blockPlacements", blocks);
    metadata.put("particlesCreated", particles);
    metadata.put("entities", entities.stream().map(entity -> Map.of("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
      "position", entity.position(), "bounds", entity.getBoundingBox(), "tick", entity.tickCount)).toList());
    metadata.put("coverageNote", "Placed subjects are recorded separately from pixel visibility; inspect the reference image for occlusion.");
    var gson = new GsonBuilder().setPrettyPrinting().create();
    Files.writeString(output.resolve("scene.json"), gson.toJson(metadata));
    Files.writeString(output.resolve("vulkan-trace.json"), gson.toJson(result.debugTrace()));
    return result.image();
  }
}
