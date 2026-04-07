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
package com.soulfiremc.server.automation;

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class AutomationTeamCoordinator {
  private static final long BLOCK_EXPIRY_MILLIS = 15L * 60L * 1000L;
  private static final long BOT_EXPIRY_MILLIS = 5L * 60L * 1000L;
  private static final long CLAIM_EXPIRY_MILLIS = 45_000L;
  private static final long EYE_SAMPLE_EXPIRY_MILLIS = 10L * 60L * 1000L;
  private static final double MIN_STRONGHOLD_DISTANCE = 128.0;
  private static final double MAX_STRONGHOLD_DISTANCE = 30_000.0;
  private static final List<String> TEAM_SHARED_REQUIREMENTS = List.of(
    AutomationRequirements.FOOD,
    "item:minecraft:blaze_rod",
    "item:minecraft:ender_pearl",
    "item:minecraft:ender_eye",
    "item:minecraft:bow",
    "item:minecraft:arrow",
    "item:minecraft:shield",
    "item:minecraft:water_bucket",
    "item:minecraft:lava_bucket",
    "item:minecraft:flint_and_steel",
    "item:minecraft:obsidian",
    "item:minecraft:bed"
  );

  private final InstanceManager instanceManager;
  private final Map<ResourceKey<Level>, Map<Long, SharedBlock>> sharedBlocks = new HashMap<>();
  private final Map<UUID, BotSnapshot> botSnapshots = new HashMap<>();
  private final Map<String, Claim> claims = new HashMap<>();
  private final List<EyeSample> eyeSamples = new ArrayList<>();
  private final Map<UUID, Map<String, Integer>> sharedInventory = new HashMap<>();
  private final Map<UUID, TeamRole> roleAssignments = new HashMap<>();
  private final Map<UUID, BotTelemetry> telemetry = new HashMap<>();
  private TeamObjective objective = TeamObjective.BOOTSTRAP;

  public AutomationTeamCoordinator(InstanceManager instanceManager) {
    this.instanceManager = instanceManager;
  }

  public InstanceManager instanceManager() {
    return instanceManager;
  }

  public synchronized void tick() {
    prune(System.currentTimeMillis());
  }

  public synchronized void observe(BotConnection bot,
                                   AutomationWorldMemory worldMemory,
                                   String status,
                                   @Nullable String phase,
                                   Map<String, Integer> inventorySnapshot) {
    var player = bot.minecraft().player;
    var level = bot.minecraft().level;
    if (player == null || level == null) {
      return;
    }

    var now = System.currentTimeMillis();
    prune(now);

    var snapshot = botSnapshots.computeIfAbsent(bot.accountProfileId(), _ -> new BotSnapshot(bot.accountProfileId(), bot.accountName()));
    snapshot.observe(player.position(), level.dimension(), player.isAlive(), status, phase, now);
    sharedInventory.put(bot.accountProfileId(), Map.copyOf(inventorySnapshot));
    telemetry.computeIfAbsent(bot.accountProfileId(), _ -> new BotTelemetry());

    var sharedDimensionBlocks = sharedBlocks.computeIfAbsent(level.dimension(), _ -> new HashMap<>());
    for (var block : worldMemory.rememberedBlocks()) {
      if (!isSharedInteresting(block.state())) {
        continue;
      }

      sharedDimensionBlocks.put(block.pos().asLong(), new SharedBlock(block.pos(), block.state(), now));
    }

    rebalanceRoles();
    updateObjective();
  }

  public synchronized void noteProgress(BotConnection bot) {
    var snapshot = botSnapshots.get(bot.accountProfileId());
    if (snapshot != null) {
      snapshot.noteProgress(System.currentTimeMillis());
    }
  }

  public synchronized void releaseClaims(BotConnection bot) {
    var owner = bot.accountProfileId();
    claims.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
  }

  public synchronized void releaseBot(BotConnection bot) {
    releaseClaims(bot);
    botSnapshots.remove(bot.accountProfileId());
    sharedInventory.remove(bot.accountProfileId());
    roleAssignments.remove(bot.accountProfileId());
    telemetry.remove(bot.accountProfileId());
  }

  public synchronized Optional<SharedBlock> findNearestBlock(BotConnection bot,
                                                             ResourceKey<Level> dimension,
                                                             Predicate<BlockState> predicate) {
    return origin(bot).flatMap(origin -> findNearestBlock(origin, dimension, predicate));
  }

  public synchronized Optional<SharedBlock> findNearestBlock(Vec3 origin,
                                                             ResourceKey<Level> dimension,
                                                             Predicate<BlockState> predicate) {
    var now = System.currentTimeMillis();
    prune(now);

    return sharedBlocks.getOrDefault(dimension, Map.of())
      .values()
      .stream()
      .filter(block -> predicate.test(block.state()))
      .min((a, b) -> Double.compare(a.pos().distToCenterSqr(origin), b.pos().distToCenterSqr(origin)));
  }

  public synchronized Optional<SharedBlock> findNearestPortal(BotConnection bot, ResourceKey<Level> dimension) {
    return findNearestBlock(bot, dimension, state -> state.getBlock() == Blocks.NETHER_PORTAL);
  }

  public synchronized Optional<SharedBlock> findNearestRuinedPortalHint(BotConnection bot, ResourceKey<Level> dimension) {
    return findNearestBlock(bot, dimension, AutomationTeamCoordinator::isRuinedPortalMarker);
  }

  public synchronized Optional<SharedBlock> findNearestFortressHint(BotConnection bot) {
    return findNearestBlock(bot, Level.NETHER, AutomationTeamCoordinator::isFortressMarker);
  }

  public synchronized Optional<Vec3> fortressEstimate() {
    return centroid(Level.NETHER, AutomationTeamCoordinator::isFortressMarker, 70.0);
  }

  public synchronized boolean claimBlock(BotConnection bot,
                                         String purpose,
                                         ResourceKey<Level> dimension,
                                         BlockPos pos,
                                         long leaseMillis) {
    return claim(bot.accountProfileId(), "block:%s:%s:%d".formatted(purpose, dimension.identifier(), pos.asLong()), pos.getCenter(), leaseMillis);
  }

  public synchronized boolean claimEntity(BotConnection bot,
                                          String purpose,
                                          UUID entityId,
                                          long leaseMillis) {
    return claim(bot.accountProfileId(), "entity:%s:%s".formatted(purpose, entityId), null, leaseMillis);
  }

  public synchronized Vec3 assignExplorationTarget(BotConnection bot,
                                                   ResourceKey<Level> dimension,
                                                   String purpose,
                                                   Vec3 focus,
                                                   int spacing) {
    var now = System.currentTimeMillis();
    prune(now);

    var anchorX = floorToGrid(focus.x, spacing);
    var anchorZ = floorToGrid(focus.z, spacing);
    for (var offset : spiralOffsets(7)) {
      var dx = offset[0];
      var dz = offset[1];
      var target = new Vec3(anchorX + dx * spacing + 0.5, focus.y, anchorZ + dz * spacing + 0.5);
      var key = "explore:%s:%s:%d:%d:%d:%d".formatted(dimension.identifier(), purpose, anchorX, anchorZ, dx, dz);
      if (claim(bot.accountProfileId(), key, target, CLAIM_EXPIRY_MILLIS)) {
        return target;
      }
    }

    return focus;
  }

  public synchronized Vec3 assignLayeredExplorationTarget(BotConnection bot,
                                                          ResourceKey<Level> dimension,
                                                          String purpose,
                                                          Vec3 focus,
                                                          int spacing,
                                                          int... yOffsets) {
    var now = System.currentTimeMillis();
    prune(now);

    var anchorX = floorToGrid(focus.x, spacing);
    var anchorZ = floorToGrid(focus.z, spacing);
    for (var offset : spiralOffsets(6)) {
      for (var yOffset : yOffsets) {
        var dx = offset[0];
        var dz = offset[1];
        var target = new Vec3(
          anchorX + dx * spacing + 0.5,
          clampTargetY(dimension, focus.y + yOffset),
          anchorZ + dz * spacing + 0.5);
        var key = "explore3d:%s:%s:%d:%d:%d:%d:%d".formatted(
          dimension.identifier(), purpose, anchorX, anchorZ, dx, dz, yOffset);
        if (claim(bot.accountProfileId(), key, target, CLAIM_EXPIRY_MILLIS)) {
          return target;
        }
      }
    }

    return new Vec3(focus.x, clampTargetY(dimension, focus.y), focus.z);
  }

  public synchronized void reportEyeSample(BotConnection bot, Vec3 origin, Vec3 direction) {
    var flattened = new Vec3(direction.x, 0.0, direction.z);
    if (flattened.lengthSqr() < 1.0e-6) {
      return;
    }

    var now = System.currentTimeMillis();
    prune(now);

    eyeSamples.removeIf(sample ->
      sample.botId().equals(bot.accountProfileId())
        && sample.origin().distanceToSqr(origin) < 16 * 16);
    eyeSamples.add(new EyeSample(bot.accountProfileId(), origin, flattened.normalize(), now));
  }

  public synchronized Optional<Vec3> strongholdEstimate() {
    var now = System.currentTimeMillis();
    prune(now);

    var frames = sharedBlocks.getOrDefault(Level.OVERWORLD, Map.of())
      .values()
      .stream()
      .filter(block -> block.state().getBlock() == Blocks.END_PORTAL_FRAME || block.state().getBlock() == Blocks.END_PORTAL)
      .map(SharedBlock::pos)
      .toList();
    if (!frames.isEmpty()) {
      var sumX = 0.0;
      var sumY = 0.0;
      var sumZ = 0.0;
      for (var pos : frames) {
        sumX += pos.getX() + 0.5;
        sumY += pos.getY() + 0.5;
        sumZ += pos.getZ() + 0.5;
      }
      return Optional.of(new Vec3(sumX / frames.size(), sumY / frames.size(), sumZ / frames.size()));
    }

    if (eyeSamples.size() == 1) {
      var sample = eyeSamples.getFirst();
      return Optional.of(sample.origin().add(sample.direction().scale(768.0)));
    }

    var intersections = new ArrayList<Vec3>();
    for (int i = 0; i < eyeSamples.size(); i++) {
      for (int j = i + 1; j < eyeSamples.size(); j++) {
        var intersection = intersect(eyeSamples.get(i), eyeSamples.get(j));
        if (intersection != null) {
          intersections.add(intersection);
        }
      }
    }

    if (intersections.isEmpty()) {
      return Optional.empty();
    }

    var sumX = 0.0;
    var sumZ = 0.0;
    for (var intersection : intersections) {
      sumX += intersection.x;
      sumZ += intersection.z;
    }
    return Optional.of(new Vec3(sumX / intersections.size(), 32.0, sumZ / intersections.size()));
  }

  public synchronized Optional<Vec3> portalRoomEstimate() {
    var now = System.currentTimeMillis();
    prune(now);

    var estimate = centroid(Level.OVERWORLD,
      state -> state.getBlock() == Blocks.END_PORTAL_FRAME || state.getBlock() == Blocks.END_PORTAL,
      28.0);
    if (estimate.isPresent()) {
      return estimate;
    }

    return strongholdEstimate().map(pos -> new Vec3(pos.x, clampTargetY(Level.OVERWORLD, pos.y), pos.z));
  }

  public synchronized TeamRole roleFor(BotConnection bot) {
    prune(System.currentTimeMillis());
    rebalanceRoles();
    return roleAssignments.getOrDefault(bot.accountProfileId(), TeamRole.END_SUPPORT);
  }

  public synchronized TeamObjective objective() {
    prune(System.currentTimeMillis());
    updateObjective();
    return objective;
  }

  public synchronized int sharedCount(String requirementKey) {
    prune(System.currentTimeMillis());
    return sharedInventory.values().stream()
      .mapToInt(snapshot -> snapshot.getOrDefault(requirementKey, 0))
      .sum();
  }

  public synchronized int teamTarget(String requirementKey) {
    var botCount = Math.max(1, botSnapshots.size());
    return switch (requirementKey) {
      case AutomationRequirements.FOOD -> Math.max(12, botCount * 8);
      case "item:minecraft:blaze_rod" -> Math.max(8, botCount * 2);
      case "item:minecraft:ender_pearl" -> Math.max(14, botCount * 2);
      case "item:minecraft:ender_eye" -> Math.max(12, Math.min(24, botCount * 2));
      case "item:minecraft:bow" -> Math.max(3, botCount / 2);
      case "item:minecraft:arrow" -> Math.max(32, botCount * 16);
      case "item:minecraft:shield" -> Math.max(2, botCount / 2);
      case "item:minecraft:water_bucket" -> 1;
      case "item:minecraft:lava_bucket" -> 1;
      case "item:minecraft:flint_and_steel" -> 1;
      case "item:minecraft:obsidian" -> 10;
      case "item:minecraft:bed" -> Math.max(2, botCount / 3);
      default -> botCount;
    };
  }

  public synchronized boolean shouldTravelToNether(BotConnection bot) {
    return switch (roleFor(bot)) {
      case LEAD, PORTAL_ENGINEER, NETHER_RUNNER -> objective().ordinal() <= TeamObjective.NETHER_PROGRESS.ordinal();
      case STRONGHOLD_SCOUT -> objective() == TeamObjective.NETHER_PROGRESS && sharedCount("item:minecraft:ender_eye") < teamTarget("item:minecraft:ender_eye");
      case END_SUPPORT -> false;
    };
  }

  public synchronized boolean shouldSearchStronghold(BotConnection bot) {
    var role = roleFor(bot);
    if (objective().ordinal() < TeamObjective.STRONGHOLD_HUNT.ordinal()) {
      return false;
    }

    return role == TeamRole.LEAD || role == TeamRole.STRONGHOLD_SCOUT || role == TeamRole.END_SUPPORT;
  }

  public synchronized boolean shouldEnterEnd(BotConnection bot) {
    var role = roleFor(bot);
    return objective().ordinal() >= TeamObjective.END_ASSAULT.ordinal()
      && role != TeamRole.PORTAL_ENGINEER;
  }

  public synchronized void noteTimeout(BotConnection bot, @Nullable String actionDescription) {
    telemetry.computeIfAbsent(bot.accountProfileId(), _ -> new BotTelemetry())
      .noteTimeout(actionDescription, System.currentTimeMillis());
  }

  public synchronized void noteRecovery(BotConnection bot, String reason) {
    telemetry.computeIfAbsent(bot.accountProfileId(), _ -> new BotTelemetry())
      .noteRecovery(reason, System.currentTimeMillis());
  }

  public synchronized Optional<Vec3> lastKnownDeathPosition(BotConnection bot) {
    return Optional.ofNullable(botSnapshots.get(bot.accountProfileId()))
      .flatMap(BotSnapshot::lastDeathPosition);
  }

  public synchronized Optional<ResourceKey<Level>> lastKnownDeathDimension(BotConnection bot) {
    return Optional.ofNullable(botSnapshots.get(bot.accountProfileId()))
      .flatMap(BotSnapshot::lastDeathDimension);
  }

  public synchronized int deathCount(BotConnection bot) {
    return Optional.ofNullable(botSnapshots.get(bot.accountProfileId()))
      .map(BotSnapshot::deathCount)
      .orElse(0);
  }

  public synchronized Collection<BotStatus> botStatuses() {
    prune(System.currentTimeMillis());
    rebalanceRoles();
    updateObjective();
    return botSnapshots.values().stream()
      .sorted(Comparator.comparing(BotSnapshot::accountName))
      .map(snapshot -> new BotStatus(
        snapshot.botId(),
        snapshot.accountName(),
        snapshot.dimension,
        snapshot.position,
        roleAssignments.getOrDefault(snapshot.botId(), TeamRole.END_SUPPORT),
        objective,
        snapshot.status,
        snapshot.phase,
        snapshot.deathCount,
        snapshot.lastProgressMillis,
        telemetry.getOrDefault(snapshot.botId(), BotTelemetry.EMPTY).timeoutCount(),
        telemetry.getOrDefault(snapshot.botId(), BotTelemetry.EMPTY).recoveryCount(),
        telemetry.getOrDefault(snapshot.botId(), BotTelemetry.EMPTY).lastRecoveryReason()))
      .toList();
  }

  public synchronized TeamSummary teamSummary() {
    prune(System.currentTimeMillis());
    updateObjective();

    var counts = new HashMap<String, Integer>();
    for (var requirement : TEAM_SHARED_REQUIREMENTS) {
      counts.put(requirement, sharedCount(requirement));
    }

    return new TeamSummary(
      objective,
      botSnapshots.size(),
      counts.getOrDefault("item:minecraft:blaze_rod", 0),
      teamTarget("item:minecraft:blaze_rod"),
      counts.getOrDefault("item:minecraft:ender_pearl", 0),
      teamTarget("item:minecraft:ender_pearl"),
      counts.getOrDefault("item:minecraft:ender_eye", 0),
      teamTarget("item:minecraft:ender_eye"),
      counts.getOrDefault("item:minecraft:arrow", 0),
      teamTarget("item:minecraft:arrow"),
      counts.getOrDefault("item:minecraft:bed", 0),
      teamTarget("item:minecraft:bed"));
  }

  private boolean claim(UUID owner, String key, @Nullable Vec3 target, long leaseMillis) {
    var now = System.currentTimeMillis();
    prune(now);

    var existing = claims.get(key);
    if (existing != null && existing.expiresAtMillis() > now && !existing.owner().equals(owner)) {
      return false;
    }

    claims.put(key, new Claim(key, owner, target, now + Math.max(5_000L, leaseMillis)));
    return true;
  }

  private Optional<Vec3> origin(BotConnection bot) {
    var player = bot.minecraft().player;
    if (player != null) {
      return Optional.of(player.position());
    }

    return Optional.ofNullable(botSnapshots.get(bot.accountProfileId()))
      .map(BotSnapshot::position);
  }

  private void prune(long now) {
    sharedBlocks.values().forEach(dimensionBlocks ->
      dimensionBlocks.values().removeIf(block -> now - block.lastSeenMillis() > BLOCK_EXPIRY_MILLIS));
    sharedBlocks.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    botSnapshots.values().removeIf(snapshot -> now - snapshot.lastSeenMillis > BOT_EXPIRY_MILLIS);
    sharedInventory.keySet().removeIf(botId -> !botSnapshots.containsKey(botId));
    roleAssignments.keySet().removeIf(botId -> !botSnapshots.containsKey(botId));
    telemetry.keySet().removeIf(botId -> !botSnapshots.containsKey(botId));
    claims.values().removeIf(claim -> claim.expiresAtMillis() <= now);
    eyeSamples.removeIf(sample -> now - sample.recordedAtMillis() > EYE_SAMPLE_EXPIRY_MILLIS);
  }

  private static boolean isSharedInteresting(BlockState state) {
    var block = state.getBlock();
    return AutomationWorldMemory.isInterestingBlock(state)
      || block == Blocks.OBSIDIAN
      || block == Blocks.CRYING_OBSIDIAN
      || block == Blocks.GOLD_BLOCK
      || block == Blocks.NETHERRACK
      || block == Blocks.MAGMA_BLOCK
      || block == Blocks.NETHER_BRICKS
      || block == Blocks.NETHER_BRICK_FENCE
      || block == Blocks.NETHER_BRICK_STAIRS
      || block == Blocks.NETHER_WART_BLOCK
      || block == Blocks.SPAWNER
      || block == Blocks.BEDROCK
      || block == Blocks.DRAGON_EGG
      || block == Blocks.END_STONE;
  }

  private static boolean isFortressMarker(BlockState state) {
    var block = state.getBlock();
    return block == Blocks.SPAWNER
      || block == Blocks.NETHER_BRICKS
      || block == Blocks.NETHER_BRICK_FENCE
      || block == Blocks.NETHER_BRICK_STAIRS;
  }

  private static boolean isRuinedPortalMarker(BlockState state) {
    var block = state.getBlock();
    return block == Blocks.CRYING_OBSIDIAN
      || block == Blocks.GOLD_BLOCK
      || block == Blocks.NETHERRACK
      || block == Blocks.MAGMA_BLOCK
      || block == Blocks.OBSIDIAN;
  }

  private Optional<Vec3> centroid(ResourceKey<Level> dimension, Predicate<BlockState> predicate, double y) {
    var matches = sharedBlocks.getOrDefault(dimension, Map.of())
      .values()
      .stream()
      .filter(block -> predicate.test(block.state()))
      .map(SharedBlock::pos)
      .toList();
    if (matches.isEmpty()) {
      return Optional.empty();
    }

    var sumX = 0.0;
    var sumZ = 0.0;
    for (var pos : matches) {
      sumX += pos.getX() + 0.5;
      sumZ += pos.getZ() + 0.5;
    }
    return Optional.of(new Vec3(sumX / matches.size(), y, sumZ / matches.size()));
  }

  private double clampTargetY(ResourceKey<Level> dimension, double y) {
    if (dimension == Level.NETHER) {
      return Math.max(50.0, Math.min(96.0, y));
    }
    if (dimension == Level.END) {
      return Math.max(62.0, Math.min(90.0, y));
    }
    return Math.max(12.0, Math.min(48.0, y));
  }

  private void rebalanceRoles() {
    var orderedBots = botSnapshots.values().stream()
      .sorted(Comparator.comparing(BotSnapshot::accountName))
      .toList();
    for (int i = 0; i < orderedBots.size(); i++) {
      var snapshot = orderedBots.get(i);
      var role = switch (i) {
        case 0 -> TeamRole.LEAD;
        case 1 -> TeamRole.PORTAL_ENGINEER;
        case 2, 3, 4 -> TeamRole.NETHER_RUNNER;
        case 5, 6, 7 -> TeamRole.STRONGHOLD_SCOUT;
        default -> TeamRole.END_SUPPORT;
      };
      roleAssignments.put(snapshot.botId(), role);
    }
  }

  private void updateObjective() {
    if (hasCompletedRun()) {
      objective = TeamObjective.COMPLETE;
      return;
    }

    if (sharedBlocks.getOrDefault(Level.END, Map.of()).values().stream()
      .anyMatch(block -> block.state().getBlock() == Blocks.DRAGON_EGG || block.state().getBlock() == Blocks.END_PORTAL)) {
      objective = TeamObjective.END_ASSAULT;
      return;
    }

    if (sharedBlocks.getOrDefault(Level.OVERWORLD, Map.of()).values().stream()
      .anyMatch(block -> block.state().getBlock() == Blocks.END_PORTAL_FRAME || block.state().getBlock() == Blocks.END_PORTAL)
      || sharedCount("item:minecraft:ender_eye") >= teamTarget("item:minecraft:ender_eye")) {
      objective = TeamObjective.STRONGHOLD_HUNT;
      return;
    }

    if (sharedCount("item:minecraft:blaze_rod") >= teamTarget("item:minecraft:blaze_rod")
      && sharedCount("item:minecraft:ender_pearl") >= teamTarget("item:minecraft:ender_pearl")) {
      objective = TeamObjective.STRONGHOLD_HUNT;
      return;
    }

    objective = TeamObjective.NETHER_PROGRESS;
  }

  private boolean hasCompletedRun() {
    return botSnapshots.values().stream().anyMatch(snapshot -> "COMPLETE".equals(snapshot.phase))
      || sharedBlocks.getOrDefault(Level.END, Map.of()).values().stream()
      .anyMatch(block -> block.state().getBlock() == Blocks.DRAGON_EGG);
  }

  private static int floorToGrid(double value, int spacing) {
    return Math.floorDiv((int) Math.floor(value), spacing) * spacing;
  }

  private static List<int[]> spiralOffsets(int maxRadius) {
    var offsets = new ArrayList<int[]>();
    offsets.add(new int[]{0, 0});
    for (int radius = 1; radius <= maxRadius; radius++) {
      for (int dx = -radius; dx <= radius; dx++) {
        offsets.add(new int[]{dx, -radius});
        offsets.add(new int[]{dx, radius});
      }
      for (int dz = -radius + 1; dz <= radius - 1; dz++) {
        offsets.add(new int[]{-radius, dz});
        offsets.add(new int[]{radius, dz});
      }
    }
    return offsets;
  }

  private static @Nullable Vec3 intersect(EyeSample first, EyeSample second) {
    var det = first.direction().x * second.direction().z - first.direction().z * second.direction().x;
    if (Math.abs(det) < 0.15) {
      return null;
    }

    var delta = second.origin().subtract(first.origin());
    var firstDistance = (delta.x * second.direction().z - delta.z * second.direction().x) / det;
    var secondDistance = (delta.x * first.direction().z - delta.z * first.direction().x) / det;
    if (firstDistance < 0.0 || secondDistance < 0.0) {
      return null;
    }

    var intersection = first.origin().add(first.direction().scale(firstDistance));
    var distance = intersection.distanceTo(first.origin());
    if (distance < MIN_STRONGHOLD_DISTANCE || distance > MAX_STRONGHOLD_DISTANCE) {
      return null;
    }

    return new Vec3(intersection.x, 32.0, intersection.z);
  }

  public record SharedBlock(BlockPos pos, BlockState state, long lastSeenMillis) {
  }

  public record BotStatus(UUID botId,
                          String accountName,
                          @Nullable ResourceKey<Level> dimension,
                          @Nullable Vec3 position,
                          TeamRole role,
                          TeamObjective objective,
                          String status,
                          @Nullable String phase,
                          int deathCount,
                          long lastProgressMillis,
                          int timeoutCount,
                          int recoveryCount,
                          @Nullable String lastRecoveryReason) {
  }

  public record TeamSummary(TeamObjective objective,
                            int activeBots,
                            int blazeRods,
                            int targetBlazeRods,
                            int enderPearls,
                            int targetEnderPearls,
                            int enderEyes,
                            int targetEnderEyes,
                            int arrows,
                            int targetArrows,
                            int beds,
                            int targetBeds) {
  }

  private record Claim(String key, UUID owner, @Nullable Vec3 target, long expiresAtMillis) {
  }

  private record EyeSample(UUID botId, Vec3 origin, Vec3 direction, long recordedAtMillis) {
  }

  private static final class BotSnapshot {
    private final UUID botId;
    private final String accountName;
    private @Nullable ResourceKey<Level> dimension;
    private @Nullable Vec3 position;
    private @Nullable Vec3 lastDeathPosition;
    private @Nullable ResourceKey<Level> lastDeathDimension;
    private String status = "idle";
    private @Nullable String phase;
    private boolean alive = true;
    private int deathCount;
    private long lastSeenMillis;
    private long lastProgressMillis;

    private BotSnapshot(UUID botId, String accountName) {
      this.botId = botId;
      this.accountName = accountName;
    }

    private void observe(Vec3 position,
                         ResourceKey<Level> dimension,
                         boolean alive,
                         String status,
                         @Nullable String phase,
                         long now) {
      if (this.alive && !alive) {
        deathCount++;
        lastDeathPosition = position;
        lastDeathDimension = dimension;
      }

      this.position = position;
      this.dimension = dimension;
      this.alive = alive;
      this.status = status;
      this.phase = phase;
      this.lastSeenMillis = now;
      if (lastProgressMillis == 0L) {
        lastProgressMillis = now;
      }
    }

    private void noteProgress(long now) {
      lastProgressMillis = now;
    }

    private UUID botId() {
      return botId;
    }

    private String accountName() {
      return accountName;
    }

    private @Nullable Vec3 position() {
      return position;
    }

    private Optional<Vec3> lastDeathPosition() {
      return Optional.ofNullable(lastDeathPosition);
    }

    private Optional<ResourceKey<Level>> lastDeathDimension() {
      return Optional.ofNullable(lastDeathDimension);
    }

    private int deathCount() {
      return deathCount;
    }
  }

  public enum TeamRole {
    LEAD,
    PORTAL_ENGINEER,
    NETHER_RUNNER,
    STRONGHOLD_SCOUT,
    END_SUPPORT;

    public boolean isNetherSpecialist() {
      return this == LEAD || this == PORTAL_ENGINEER || this == NETHER_RUNNER;
    }

    public boolean isStrongholdSpecialist() {
      return this == LEAD || this == STRONGHOLD_SCOUT;
    }

    public boolean isEndSpecialist() {
      return this == LEAD || this == STRONGHOLD_SCOUT || this == END_SUPPORT;
    }
  }

  public enum TeamObjective {
    BOOTSTRAP,
    NETHER_PROGRESS,
    STRONGHOLD_HUNT,
    END_ASSAULT,
    COMPLETE
  }

  private static final class BotTelemetry {
    private static final BotTelemetry EMPTY = new BotTelemetry();
    private int timeoutCount;
    private int recoveryCount;
    private @Nullable String lastRecoveryReason;
    private long lastTimeoutMillis;
    private long lastRecoveryMillis;

    private void noteTimeout(@Nullable String actionDescription, long now) {
      timeoutCount++;
      lastTimeoutMillis = now;
      if (actionDescription != null && !actionDescription.isBlank()) {
        lastRecoveryReason = "timeout: " + actionDescription;
      }
    }

    private void noteRecovery(String reason, long now) {
      recoveryCount++;
      lastRecoveryMillis = now;
      lastRecoveryReason = reason;
    }

    private int timeoutCount() {
      return timeoutCount;
    }

    private int recoveryCount() {
      return recoveryCount;
    }

    private @Nullable String lastRecoveryReason() {
      return lastRecoveryReason;
    }
  }
}
