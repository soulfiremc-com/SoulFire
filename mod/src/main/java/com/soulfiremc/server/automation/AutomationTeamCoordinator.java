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

import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.settings.instance.AutomationSettings;
import com.soulfiremc.server.util.structs.GsonInstance;
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
    AutomationRequirements.BLAZE_ROD,
    AutomationRequirements.ENDER_PEARL,
    AutomationRequirements.ENDER_EYE,
    AutomationRequirements.BOW,
    AutomationRequirements.ARROW,
    AutomationRequirements.SHIELD,
    AutomationRequirements.WATER_BUCKET,
    AutomationRequirements.LAVA_BUCKET,
    AutomationRequirements.FLINT_AND_STEEL,
    AutomationRequirements.OBSIDIAN,
    AutomationRequirements.ANY_BED
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

      sharedDimensionBlocks.put(block.pos().asLong(), new SharedBlock(bot.accountProfileId(), block.pos(), block.state(), now));
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

  public synchronized int releaseClaimsOwnedBy(UUID ownerBotId) {
    var before = claims.size();
    claims.entrySet().removeIf(entry -> entry.getValue().owner().equals(ownerBotId));
    return before - claims.size();
  }

  public synchronized boolean releaseClaim(String key) {
    prune(System.currentTimeMillis());
    return claims.remove(key) != null;
  }

  public synchronized void releaseBot(BotConnection bot) {
    releaseClaims(bot);
    botSnapshots.remove(bot.accountProfileId());
    sharedInventory.remove(bot.accountProfileId());
    roleAssignments.remove(bot.accountProfileId());
    telemetry.remove(bot.accountProfileId());
  }

  public synchronized void resetCoordinationState() {
    sharedBlocks.clear();
    claims.clear();
    eyeSamples.clear();
    sharedInventory.clear();
    roleAssignments.clear();
    objective = TeamObjective.BOOTSTRAP;
  }

  public synchronized Optional<SharedBlock> findNearestBlock(BotConnection bot,
                                                             ResourceKey<Level> dimension,
                                                             Predicate<BlockState> predicate) {
    return origin(bot).flatMap(origin -> {
      var botId = independentMode() ? bot.accountProfileId() : null;
      return observedBlocks(botId, dimension)
        .filter(block -> predicate.test(block.state()))
        .min((a, b) -> Double.compare(a.pos().distToCenterSqr(origin), b.pos().distToCenterSqr(origin)));
    });
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
    if (!sharedStructureIntelEnabled()) {
      return Optional.empty();
    }
    return findNearestBlock(bot, dimension, state -> state.getBlock() == Blocks.NETHER_PORTAL);
  }

  public synchronized Optional<SharedBlock> findNearestRuinedPortalHint(BotConnection bot, ResourceKey<Level> dimension) {
    if (!sharedStructureIntelEnabled()) {
      return Optional.empty();
    }
    return findNearestBlock(bot, dimension, AutomationTeamCoordinator::isRuinedPortalMarker);
  }

  public synchronized Optional<SharedBlock> findNearestFortressHint(BotConnection bot) {
    if (!sharedStructureIntelEnabled()) {
      return Optional.empty();
    }
    return findNearestBlock(bot, Level.NETHER, AutomationTeamCoordinator::isFortressMarker);
  }

  public synchronized Optional<Vec3> fortressEstimate() {
    if (!sharedStructureIntelEnabled()) {
      return Optional.empty();
    }
    return centroid(Level.NETHER, AutomationTeamCoordinator::isFortressMarker, 70.0);
  }

  public synchronized Optional<Vec3> fortressEstimate(BotConnection bot) {
    if (sharedStructureIntelEnabled()) {
      return fortressEstimate();
    }

    return centroid(observedBlocks(bot.accountProfileId(), Level.NETHER)
      .filter(block -> isFortressMarker(block.state()))
      .map(SharedBlock::pos)
      .toList(), 70.0);
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
    if (!sharedStructureIntelEnabled()) {
      return Optional.empty();
    }
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

  public synchronized Optional<Vec3> strongholdEstimate(BotConnection bot) {
    if (sharedStructureIntelEnabled()) {
      return strongholdEstimate();
    }

    var botId = bot.accountProfileId();
    var frames = observedBlocks(botId, Level.OVERWORLD)
      .filter(block -> block.state().getBlock() == Blocks.END_PORTAL_FRAME || block.state().getBlock() == Blocks.END_PORTAL)
      .map(SharedBlock::pos)
      .toList();
    if (!frames.isEmpty()) {
      return centroid(frames, 28.0);
    }

    var botSamples = eyeSamples.stream()
      .filter(sample -> sample.botId().equals(botId))
      .toList();
    if (botSamples.size() == 1) {
      var sample = botSamples.getFirst();
      return Optional.of(sample.origin().add(sample.direction().scale(768.0)));
    }

    var intersections = new ArrayList<Vec3>();
    for (int i = 0; i < botSamples.size(); i++) {
      for (int j = i + 1; j < botSamples.size(); j++) {
        var intersection = intersect(botSamples.get(i), botSamples.get(j));
        if (intersection != null) {
          intersections.add(intersection);
        }
      }
    }
    return centroidVectors(intersections, 32.0);
  }

  public synchronized Optional<Vec3> portalRoomEstimate() {
    if (!sharedStructureIntelEnabled()) {
      return Optional.empty();
    }
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

  public synchronized Optional<Vec3> portalRoomEstimate(BotConnection bot) {
    if (sharedStructureIntelEnabled()) {
      return portalRoomEstimate();
    }

    var frames = observedBlocks(bot.accountProfileId(), Level.OVERWORLD)
      .filter(block -> block.state().getBlock() == Blocks.END_PORTAL_FRAME || block.state().getBlock() == Blocks.END_PORTAL)
      .map(SharedBlock::pos)
      .toList();
    if (!frames.isEmpty()) {
      return centroid(frames, 28.0);
    }

    return strongholdEstimate(bot).map(pos -> new Vec3(pos.x, clampTargetY(Level.OVERWORLD, pos.y), pos.z));
  }

  public synchronized TeamRole roleFor(BotConnection bot) {
    prune(System.currentTimeMillis());
    var override = roleOverride(bot.accountProfileId());
    if (override != null) {
      return override;
    }
    if (independentMode()) {
      return TeamRole.LEAD;
    }
    rebalanceRoles();
    return roleAssignments.getOrDefault(bot.accountProfileId(), TeamRole.END_SUPPORT);
  }

  public synchronized TeamObjective objective() {
    prune(System.currentTimeMillis());
    var override = objectiveOverride();
    if (override != null) {
      return override;
    }
    updateObjective();
    return objective;
  }

  public synchronized TeamObjective objectiveFor(BotConnection bot) {
    prune(System.currentTimeMillis());
    var override = objectiveOverride();
    if (override != null) {
      return override;
    }
    if (!independentMode()) {
      updateObjective();
      return objective;
    }

    return objectiveForBotId(bot.accountProfileId());
  }

  public synchronized int sharedCount(String requirementKey) {
    prune(System.currentTimeMillis());
    return sharedInventory.values().stream()
      .mapToInt(snapshot -> snapshot.getOrDefault(requirementKey, 0))
      .sum();
  }

  public synchronized int countFor(BotConnection bot, String requirementKey) {
    prune(System.currentTimeMillis());
    if (!independentMode()) {
      return sharedCount(requirementKey);
    }

    return sharedInventory.getOrDefault(bot.accountProfileId(), Map.of())
      .getOrDefault(requirementKey, 0);
  }

  public synchronized int teamTarget(String requirementKey) {
    var botCount = Math.max(1, botSnapshots.size());
    var dynamicTarget = switch (requirementKey) {
      case AutomationRequirements.FOOD -> Math.max(12, botCount * 8);
      case AutomationRequirements.BLAZE_ROD -> Math.max(8, botCount * 2);
      case AutomationRequirements.ENDER_PEARL -> Math.max(14, botCount * 2);
      case AutomationRequirements.ENDER_EYE -> Math.max(12, Math.min(24, botCount * 2));
      case AutomationRequirements.BOW -> Math.max(3, botCount / 2);
      case AutomationRequirements.ARROW -> Math.max(32, botCount * 16);
      case AutomationRequirements.SHIELD -> Math.max(2, botCount / 2);
      case AutomationRequirements.WATER_BUCKET -> 1;
      case AutomationRequirements.LAVA_BUCKET -> 1;
      case AutomationRequirements.FLINT_AND_STEEL -> 1;
      case AutomationRequirements.OBSIDIAN -> 10;
      case AutomationRequirements.ANY_BED -> Math.max(2, botCount / 3);
      default -> botCount;
    };

    return switch (requirementKey) {
      case AutomationRequirements.BLAZE_ROD -> AutomationControlSupport.resolveTargetOverride(
        instanceManager.settingsSource().get(AutomationSettings.TARGET_BLAZE_RODS),
        dynamicTarget);
      case AutomationRequirements.ENDER_PEARL -> AutomationControlSupport.resolveTargetOverride(
        instanceManager.settingsSource().get(AutomationSettings.TARGET_ENDER_PEARLS),
        dynamicTarget);
      case AutomationRequirements.ENDER_EYE -> AutomationControlSupport.resolveTargetOverride(
        instanceManager.settingsSource().get(AutomationSettings.TARGET_ENDER_EYES),
        dynamicTarget);
      case AutomationRequirements.ARROW -> AutomationControlSupport.resolveTargetOverride(
        instanceManager.settingsSource().get(AutomationSettings.TARGET_ARROWS),
        dynamicTarget);
      case AutomationRequirements.ANY_BED -> AutomationControlSupport.resolveTargetOverride(
        instanceManager.settingsSource().get(AutomationSettings.TARGET_BEDS),
        dynamicTarget);
      default -> dynamicTarget;
    };
  }

  public synchronized int targetFor(BotConnection bot, String requirementKey) {
    if (!independentMode()) {
      return teamTarget(requirementKey);
    }

    return soloTarget(requirementKey);
  }

  public synchronized boolean shouldTravelToNether(BotConnection bot) {
    var objective = objectiveFor(bot);
    if (independentMode()) {
      return objective.ordinal() <= TeamObjective.NETHER_PROGRESS.ordinal();
    }

    return switch (roleFor(bot)) {
      case LEAD, PORTAL_ENGINEER, NETHER_RUNNER -> objective.ordinal() <= TeamObjective.NETHER_PROGRESS.ordinal();
      case STRONGHOLD_SCOUT -> objective == TeamObjective.NETHER_PROGRESS
        && countFor(bot, AutomationRequirements.ENDER_EYE) < targetFor(bot, AutomationRequirements.ENDER_EYE);
      case END_SUPPORT -> false;
    };
  }

  public synchronized boolean shouldSearchStronghold(BotConnection bot) {
    if (independentMode()) {
      return objectiveFor(bot).ordinal() >= TeamObjective.STRONGHOLD_HUNT.ordinal();
    }

    var role = roleFor(bot);
    if (objectiveFor(bot).ordinal() < TeamObjective.STRONGHOLD_HUNT.ordinal()) {
      return false;
    }

    return role == TeamRole.LEAD || role == TeamRole.STRONGHOLD_SCOUT || role == TeamRole.END_SUPPORT;
  }

  public synchronized boolean shouldEnterEnd(BotConnection bot) {
    if (objectiveFor(bot).ordinal() < TeamObjective.END_ASSAULT.ordinal()) {
      return false;
    }

    if (independentMode()) {
      return true;
    }

    var role = roleFor(bot);
    if (role == TeamRole.PORTAL_ENGINEER) {
      return false;
    }

    if (!instanceManager.settingsSource().get(AutomationSettings.SHARED_END_ENTRY)) {
      return true;
    }

    var snapshot = botSnapshots.get(bot.accountProfileId());
    if (snapshot != null && snapshot.dimension == Level.END) {
      return true;
    }

    var currentEndBots = botSnapshots.values().stream()
      .filter(current -> current.alive && current.dimension == Level.END)
      .count();
    return currentEndBots < instanceManager.settingsSource().get(AutomationSettings.MAX_END_BOTS);
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
        roleForBotId(snapshot.botId()),
        roleOverride(snapshot.botId()),
        objectiveForBotId(snapshot.botId()),
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
      instanceManager.settingsSource().get(AutomationSettings.PRESET, AutomationSettings.Preset.class),
      collaborationEnabled(),
      instanceManager.settingsSource().get(AutomationSettings.ROLE_POLICY, AutomationSettings.RolePolicy.class),
      objectiveOverride(),
      instanceManager.settingsSource().get(AutomationSettings.SHARED_STRUCTURE_INTEL),
      instanceManager.settingsSource().get(AutomationSettings.SHARED_TARGET_CLAIMS),
      instanceManager.settingsSource().get(AutomationSettings.SHARED_END_ENTRY),
      instanceManager.settingsSource().get(AutomationSettings.MAX_END_BOTS),
      objective,
      botSnapshots.size(),
      counts.getOrDefault(AutomationRequirements.BLAZE_ROD, 0),
      teamTarget(AutomationRequirements.BLAZE_ROD),
      counts.getOrDefault(AutomationRequirements.ENDER_PEARL, 0),
      teamTarget(AutomationRequirements.ENDER_PEARL),
      counts.getOrDefault(AutomationRequirements.ENDER_EYE, 0),
      teamTarget(AutomationRequirements.ENDER_EYE),
      counts.getOrDefault(AutomationRequirements.ARROW, 0),
      teamTarget(AutomationRequirements.ARROW),
      counts.getOrDefault(AutomationRequirements.ANY_BED, 0),
      teamTarget(AutomationRequirements.ANY_BED));
  }

  public synchronized CoordinationSnapshot coordinationSnapshot(int maxEntries) {
    prune(System.currentTimeMillis());
    rebalanceRoles();
    updateObjective();

    var cappedEntries = Math.max(1, maxEntries);
    var sharedCounts = TEAM_SHARED_REQUIREMENTS.stream()
      .map(requirement -> new SharedRequirementCountSnapshot(
        requirement,
        sharedCount(requirement),
        teamTarget(requirement)))
      .toList();
    var blockSnapshots = sharedBlocks.entrySet().stream()
      .flatMap(entry -> entry.getValue().values().stream()
        .map(block -> new SharedBlockSnapshot(
          block.observerBotId(),
          accountNameFor(block.observerBotId()),
          entry.getKey(),
          block.pos(),
          block.state(),
          block.lastSeenMillis())))
      .sorted(Comparator.comparingLong(SharedBlockSnapshot::lastSeenMillis).reversed())
      .limit(cappedEntries)
      .toList();
    var claimSnapshots = claims.values().stream()
      .sorted(Comparator.comparingLong(Claim::expiresAtMillis).reversed())
      .limit(cappedEntries)
      .map(claim -> new ClaimSnapshot(
        claim.key(),
        claim.owner(),
        accountNameFor(claim.owner()),
        claim.target(),
        claim.expiresAtMillis()))
      .toList();
    var eyeSampleSnapshots = eyeSamples.stream()
      .sorted(Comparator.comparingLong(EyeSample::recordedAtMillis).reversed())
      .limit(cappedEntries)
      .map(sample -> new EyeSampleSnapshot(
        sample.botId(),
        accountNameFor(sample.botId()),
        sample.origin(),
        sample.direction(),
        sample.recordedAtMillis()))
      .toList();

    return new CoordinationSnapshot(
      teamSummary(),
      blockSnapshots,
      claimSnapshots,
      eyeSampleSnapshots,
      sharedCounts,
      sharedBlocks.values().stream().mapToInt(Map::size).sum(),
      claims.size(),
      eyeSamples.size());
  }

  private boolean claim(UUID owner, String key, @Nullable Vec3 target, long leaseMillis) {
    var now = System.currentTimeMillis();
    prune(now);
    if (independentMode() || !sharedTargetClaimsEnabled()) {
      key = owner + ":" + key;
    }

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
    return centroid(observedBlocks(null, dimension)
      .filter(block -> predicate.test(block.state()))
      .map(SharedBlock::pos)
      .toList(), y);
  }

  private Optional<Vec3> centroid(List<BlockPos> matches, double y) {
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
    objective = computeObjective(Map.of(), false);
  }

  private TeamObjective objectiveForBotId(UUID botId) {
    var override = objectiveOverride();
    if (override != null) {
      return override;
    }
    if (!independentMode()) {
      return objective;
    }

    return computeObjective(botId, sharedInventory.getOrDefault(botId, Map.of()), true);
  }

  private TeamObjective computeObjective(Map<String, Integer> inventoryCounts, boolean useSoloTargets) {
    return computeObjective(null, inventoryCounts, useSoloTargets);
  }

  private TeamObjective computeObjective(@Nullable UUID botId, Map<String, Integer> inventoryCounts, boolean useSoloTargets) {
    if (hasCompletedRun(botId)) {
      return TeamObjective.COMPLETE;
    }

    if (observedBlocks(botId, Level.END)
      .anyMatch(block -> block.state().getBlock() == Blocks.DRAGON_EGG || block.state().getBlock() == Blocks.END_PORTAL)) {
      return TeamObjective.END_ASSAULT;
    }

    if (observedBlocks(botId, Level.OVERWORLD)
      .anyMatch(block -> block.state().getBlock() == Blocks.END_PORTAL_FRAME || block.state().getBlock() == Blocks.END_PORTAL)
      || inventoryCounts.getOrDefault(AutomationRequirements.ENDER_EYE, 0) >= (useSoloTargets ? soloTarget(AutomationRequirements.ENDER_EYE) : teamTarget(AutomationRequirements.ENDER_EYE))) {
      return TeamObjective.STRONGHOLD_HUNT;
    }

    if (inventoryCounts.getOrDefault(AutomationRequirements.BLAZE_ROD, 0) >= (useSoloTargets ? soloTarget(AutomationRequirements.BLAZE_ROD) : teamTarget(AutomationRequirements.BLAZE_ROD))
      && inventoryCounts.getOrDefault(AutomationRequirements.ENDER_PEARL, 0) >= (useSoloTargets ? soloTarget(AutomationRequirements.ENDER_PEARL) : teamTarget(AutomationRequirements.ENDER_PEARL))) {
      return TeamObjective.STRONGHOLD_HUNT;
    }

    if (!useSoloTargets && sharedCount(AutomationRequirements.BLAZE_ROD) >= teamTarget(AutomationRequirements.BLAZE_ROD)
      && sharedCount(AutomationRequirements.ENDER_PEARL) >= teamTarget(AutomationRequirements.ENDER_PEARL)) {
      return TeamObjective.STRONGHOLD_HUNT;
    }

    return TeamObjective.NETHER_PROGRESS;
  }

  private TeamRole roleForBotId(UUID botId) {
    var override = roleOverride(botId);
    if (override != null) {
      return override;
    }
    if (independentMode()) {
      return TeamRole.LEAD;
    }

    return roleAssignments.getOrDefault(botId, TeamRole.END_SUPPORT);
  }

  private boolean collaborationEnabled() {
    return instanceManager.settingsSource().get(AutomationSettings.TEAM_COLLABORATION);
  }

  private @Nullable TeamObjective objectiveOverride() {
    return switch (instanceManager.settingsSource().get(AutomationSettings.OBJECTIVE_OVERRIDE, AutomationSettings.ObjectiveOverride.class)) {
      case AUTO -> null;
      case BOOTSTRAP -> TeamObjective.BOOTSTRAP;
      case NETHER_PROGRESS -> TeamObjective.NETHER_PROGRESS;
      case STRONGHOLD_HUNT -> TeamObjective.STRONGHOLD_HUNT;
      case END_ASSAULT -> TeamObjective.END_ASSAULT;
      case COMPLETE -> TeamObjective.COMPLETE;
    };
  }

  private @Nullable TeamRole roleOverride(UUID botId) {
    return configuredRoleOverride(instanceManager.settingsSource().accounts().get(botId));
  }

  private boolean sharedStructureIntelEnabled() {
    return !independentMode()
      && instanceManager.settingsSource().get(AutomationSettings.SHARED_STRUCTURE_INTEL);
  }

  private boolean sharedTargetClaimsEnabled() {
    return !independentMode()
      && instanceManager.settingsSource().get(AutomationSettings.SHARED_TARGET_CLAIMS);
  }

  private boolean independentMode() {
    return !collaborationEnabled()
      || instanceManager.settingsSource().get(AutomationSettings.ROLE_POLICY, AutomationSettings.RolePolicy.class) == AutomationSettings.RolePolicy.INDEPENDENT;
  }

  private static @Nullable TeamRole configuredRoleOverride(@Nullable MinecraftAccount account) {
    if (account == null) {
      return null;
    }

    var configured = account.get(AutomationSettings.ROLE_OVERRIDE)
      .map(json -> GsonInstance.GSON.fromJson(json, String.class))
      .map(AutomationSettings.RoleOverride::valueOf)
      .orElse(AutomationSettings.RoleOverride.AUTO);
    return switch (configured) {
      case AUTO -> null;
      case LEAD -> TeamRole.LEAD;
      case PORTAL_ENGINEER -> TeamRole.PORTAL_ENGINEER;
      case NETHER_RUNNER -> TeamRole.NETHER_RUNNER;
      case STRONGHOLD_SCOUT -> TeamRole.STRONGHOLD_SCOUT;
      case END_SUPPORT -> TeamRole.END_SUPPORT;
    };
  }

  private static int soloTarget(String requirementKey) {
    return switch (requirementKey) {
      case AutomationRequirements.FOOD -> 12;
      case AutomationRequirements.BLAZE_ROD -> 6;
      case AutomationRequirements.ENDER_PEARL -> 12;
      case AutomationRequirements.ENDER_EYE -> 12;
      case AutomationRequirements.BOW -> 1;
      case AutomationRequirements.ARROW -> 24;
      case AutomationRequirements.SHIELD -> 1;
      case AutomationRequirements.WATER_BUCKET -> 1;
      case AutomationRequirements.LAVA_BUCKET -> 1;
      case AutomationRequirements.FLINT_AND_STEEL -> 1;
      case AutomationRequirements.OBSIDIAN -> 10;
      case AutomationRequirements.ANY_BED -> 1;
      default -> 1;
    };
  }

  private boolean hasCompletedRun(@Nullable UUID botId) {
    var completedPhase = botId == null
      ? botSnapshots.values().stream().anyMatch(snapshot -> "COMPLETE".equals(snapshot.phase))
      : Optional.ofNullable(botSnapshots.get(botId)).map(snapshot -> "COMPLETE".equals(snapshot.phase)).orElse(false);
    return completedPhase
      || observedBlocks(botId, Level.END)
      .anyMatch(block -> block.state().getBlock() == Blocks.DRAGON_EGG);
  }

  private java.util.stream.Stream<SharedBlock> observedBlocks(@Nullable UUID botId, ResourceKey<Level> dimension) {
    var blocks = sharedBlocks.getOrDefault(dimension, Map.of()).values().stream();
    if (botId != null && (!sharedStructureIntelEnabled() || independentMode())) {
      return blocks.filter(block -> block.observerBotId().equals(botId));
    }
    if (botId == null && !sharedStructureIntelEnabled()) {
      return java.util.stream.Stream.empty();
    }
    return blocks;
  }

  private String accountNameFor(UUID botId) {
    return Optional.ofNullable(botSnapshots.get(botId))
      .map(BotSnapshot::accountName)
      .orElse(botId.toString());
  }

  private static Optional<Vec3> centroidVectors(List<Vec3> matches, double y) {
    if (matches.isEmpty()) {
      return Optional.empty();
    }

    var sumX = 0.0;
    var sumZ = 0.0;
    for (var pos : matches) {
      sumX += pos.x;
      sumZ += pos.z;
    }
    return Optional.of(new Vec3(sumX / matches.size(), y, sumZ / matches.size()));
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

  public record SharedBlock(UUID observerBotId, BlockPos pos, BlockState state, long lastSeenMillis) {
  }

  public record BotStatus(UUID botId,
                          String accountName,
                          @Nullable ResourceKey<Level> dimension,
                          @Nullable Vec3 position,
                          TeamRole role,
                          @Nullable TeamRole roleOverride,
                          TeamObjective objective,
                          String status,
                          @Nullable String phase,
                          int deathCount,
                          long lastProgressMillis,
                          int timeoutCount,
                          int recoveryCount,
                          @Nullable String lastRecoveryReason) {
  }

  public record TeamSummary(AutomationSettings.Preset preset,
                            boolean collaborationEnabled,
                            AutomationSettings.RolePolicy rolePolicy,
                            @Nullable TeamObjective objectiveOverride,
                            boolean sharedStructureIntel,
                            boolean sharedTargetClaims,
                            boolean sharedEndEntry,
                            int maxEndBots,
                            TeamObjective objective,
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

  public record CoordinationSnapshot(TeamSummary summary,
                                     List<SharedBlockSnapshot> sharedBlocks,
                                     List<ClaimSnapshot> claims,
                                     List<EyeSampleSnapshot> eyeSamples,
                                     List<SharedRequirementCountSnapshot> sharedCounts,
                                     int sharedBlockCount,
                                     int claimCount,
                                     int eyeSampleCount) {
  }

  public record SharedRequirementCountSnapshot(String requirementKey, int currentCount, int targetCount) {
  }

  public record SharedBlockSnapshot(UUID observerBotId,
                                    String observerAccountName,
                                    ResourceKey<Level> dimension,
                                    BlockPos pos,
                                    BlockState state,
                                    long lastSeenMillis) {
  }

  public record ClaimSnapshot(String key,
                              UUID ownerBotId,
                              String ownerAccountName,
                              @Nullable Vec3 target,
                              long expiresAtMillis) {
  }

  public record EyeSampleSnapshot(UUID botId,
                                  String accountName,
                                  Vec3 origin,
                                  Vec3 direction,
                                  long recordedAtMillis) {
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
