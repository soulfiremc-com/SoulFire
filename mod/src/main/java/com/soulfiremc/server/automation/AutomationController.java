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

import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.BlockPlaceAgainstData;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.*;
import com.soulfiremc.server.pathfinding.graph.BlockFace;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import com.soulfiremc.server.plugins.KillAura;
import com.soulfiremc.server.settings.instance.AutomationSettings;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public final class AutomationController {
  private static final int REQUIRED_FOOD = 12;
  private static final int REQUIRED_BLAZE_RODS = 6;
  private static final int REQUIRED_ENDER_PEARLS = 12;
  private static final int REQUIRED_EYES = 12;
  private static final int REQUIRED_ARROWS = 24;
  private static final int REQUIRED_PORTAL_OBSIDIAN = 10;
  private static final long ACTION_STALL_TICKS = 20L * 15L;
  private static final long ACTION_TIMEOUT_TICKS = 20L * 45L;
  private static final List<String> TRACKED_TEAM_REQUIREMENTS = List.of(
    AutomationRequirements.FOOD,
    AutomationRequirements.BLAZE_ROD,
    AutomationRequirements.ENDER_PEARL,
    AutomationRequirements.ENDER_EYE,
    AutomationRequirements.BOW,
    AutomationRequirements.ARROW,
    AutomationRequirements.SHIELD,
    AutomationRequirements.BUCKET,
    AutomationRequirements.WATER_BUCKET,
    AutomationRequirements.LAVA_BUCKET,
    AutomationRequirements.FLINT_AND_STEEL,
    AutomationRequirements.OBSIDIAN,
    AutomationRequirements.ANY_BED
  );

  private final BotConnection bot;
  private final AutomationWorldMemory worldMemory = new AutomationWorldMemory();
  private final Deque<RequirementGoal> requirements = new ArrayDeque<>();
  private @Nullable AutomationAction currentAction;
  private GoalMode mode = GoalMode.IDLE;
  private BeatPhase beatPhase = BeatPhase.PREPARE_OVERWORLD;
  private long lastProgressTick;
  private long lastEyeThrowTick;
  private long currentActionStartedTick;
  private long currentActionMovedTick;
  private @Nullable Vec3 lastEyeDirectionTarget;
  private @Nullable Vec3 currentActionLastPos;
  private @Nullable GoalMode pausedMode;
  private @Nullable BeatPhase pausedBeatPhase;
  private String status = "idle";
  private boolean awaitingRespawn;
  private boolean paused;
  private int consecutiveFailures;
  private int knownDeaths;

  public AutomationController(BotConnection bot) {
    this.bot = bot;
  }

  public void tick() {
    worldMemory.observe(bot);

    var player = bot.minecraft().player;
    var level = bot.minecraft().level;
    if (player == null || level == null) {
      return;
    }

    if (!bot.settingsSource().get(AutomationSettings.ENABLED)) {
      disableAutomation();
      team().observe(bot, worldMemory, status, null, inventorySnapshot());
      return;
    }

    team().observe(bot, worldMemory, status, effectivePhaseName(), inventorySnapshot());

    if (paused) {
      status = "automation paused";
      return;
    }

    if (!player.isAlive()) {
      awaitingRespawn = true;
      bot.botControl().stopAll();
      clearCurrentAction();
      status = "waiting for respawn";
      return;
    }

    if (awaitingRespawn) {
      awaitingRespawn = false;
      bot.botControl().stopAll();
      clearCurrentAction();
      lastEyeDirectionTarget = null;
      recoverAfterDeath(level, player);
      noteProgress();
    }

    if (rememberOpenContainer(player)) {
      status = "inspecting container";
    }

    updateActionMovement(player);
    if (handleSurvival(player)) {
      return;
    }

    if (currentAction != null) {
      if (isCurrentActionStalled()) {
        timeoutCurrentAction();
      }
    }

    if (currentAction != null) {
      var result = currentAction.poll(this);
      if (result == ActionResult.RUNNING) {
        status = currentAction.description();
        return;
      }

      if (result == ActionResult.SUCCEEDED) {
        noteProgress();
      } else {
        consecutiveFailures++;
      }

      clearCurrentAction();
    }

    if (mode == GoalMode.IDLE) {
      status = "idle";
      return;
    }

    if (mode == GoalMode.BEAT) {
      updateBeatPlan(level);
    }

    while (!requirements.isEmpty()) {
      var requirement = requirements.peek();
      if (AutomationInventory.countInventory(bot, requirement.requirementKey()) >= requirement.count()) {
        requirements.pop();
        lastProgressTick = worldMemory.ticks();
        continue;
      }

      var plan = plan(requirement, level);
      if (!plan.dependencies().isEmpty()) {
        pushRequirements(plan.dependencies());
        continue;
      }

      if (plan.action() != null) {
        startAction(plan.action());
      } else if (currentAction == null) {
        startAction(new ExploreAction(plan.reason()));
      }
      return;
    }

    if (mode == GoalMode.BEAT && currentAction == null) {
      switch (beatPhase) {
        case ENTER_NETHER, RETURN_TO_OVERWORLD, STRONGHOLD_SEARCH, ACTIVATE_PORTAL, END_FIGHT ->
          driveBeatPhase(level, player);
        case COMPLETE -> status = "beat complete";
        default -> status = "ready";
      }
      return;
    }

    if (mode == GoalMode.ACQUIRE) {
      mode = GoalMode.IDLE;
      status = "automation goal completed";
    }
  }

  public boolean startAcquire(String requirement, int count) {
    if (!bot.settingsSource().get(AutomationSettings.ENABLED)) {
      status = "automation disabled";
      return false;
    }

    stop();
    mode = GoalMode.ACQUIRE;
    pushRequirement(new RequirementGoal(AutomationRequirements.normalize(requirement), count, "requested"));
    status = "acquiring " + requirement;
    return true;
  }

  public boolean startBeatMinecraft() {
    if (!bot.settingsSource().get(AutomationSettings.ENABLED)) {
      status = "automation disabled";
      return false;
    }

    stop();
    mode = GoalMode.BEAT;
    beatPhase = BeatPhase.PREPARE_OVERWORLD;
    knownDeaths = team().deathCount(bot);
    status = "preparing overworld";
    return true;
  }

  public boolean pause() {
    if (paused || mode == GoalMode.IDLE) {
      return false;
    }

    paused = true;
    pausedMode = mode;
    pausedBeatPhase = beatPhase;
    bot.botControl().stopAll();
    clearCurrentAction();
    status = "automation paused";
    return true;
  }

  public boolean resume() {
    if (!paused || pausedMode == null) {
      return false;
    }

    paused = false;
    mode = pausedMode;
    beatPhase = pausedBeatPhase == null ? beatPhase : pausedBeatPhase;
    pausedMode = null;
    pausedBeatPhase = null;
    status = mode == GoalMode.BEAT ? "resumed beat automation" : "resumed automation";
    noteProgress();
    return true;
  }

  public void stop() {
    requirements.clear();
    clearCurrentAction();
    lastEyeDirectionTarget = null;
    mode = GoalMode.IDLE;
    beatPhase = BeatPhase.PREPARE_OVERWORLD;
    paused = false;
    pausedMode = null;
    pausedBeatPhase = null;
    consecutiveFailures = 0;
    awaitingRespawn = false;
    knownDeaths = team().deathCount(bot);
    bot.botControl().stopAll();
    team().releaseClaims(bot);
    status = "idle";
  }

  public String status() {
    var parts = new ArrayList<String>();
    parts.add(status);
    if (!requirements.isEmpty()) {
      var top = requirements.peek();
      parts.add("target=%s x%d".formatted(AutomationRequirements.describe(top.requirementKey()), top.count()));
    }
    if (mode == GoalMode.BEAT) {
      parts.add("phase=" + beatPhase.name().toLowerCase());
    }
    if (paused) {
      parts.add("paused=true");
    }
    if (currentAction != null) {
      parts.add("action=" + currentAction.description());
    }
    return String.join(", ", parts);
  }

  public StatusSnapshot snapshot() {
    var target = requirements.peek();
    return new StatusSnapshot(
      status,
      mode.name(),
      paused,
      effectivePhaseName(),
      currentAction != null ? currentAction.description() : null,
      target != null ? target.requirementKey() : null,
      target != null ? target.count() : 0,
      requirements.stream()
        .map(requirement -> new QueuedRequirementSnapshot(
          requirement.requirementKey(),
          requirement.count(),
          requirement.reason()))
        .toList());
  }

  public AutomationWorldMemory.MemorySnapshot memorySnapshot(int maxEntries) {
    var player = bot.minecraft().player;
    return worldMemory.snapshot(player != null ? player.position() : null, maxEntries);
  }

  public void resetMemory() {
    worldMemory.reset();
    clearCurrentAction();
    lastEyeDirectionTarget = null;
    bot.botControl().stopAll();
    status = mode == GoalMode.IDLE ? "automation memory reset" : "automation memory reset, replanning";
  }

  private boolean rememberOpenContainer(LocalPlayer player) {
    if (player.containerMenu instanceof InventoryMenu) {
      return false;
    }

    if (currentAction instanceof PositionedAction positionedAction) {
      worldMemory.rememberContainerContents(positionedAction.position(), player.containerMenu);
      return true;
    }

    return false;
  }

  private boolean handleSurvival(LocalPlayer player) {
    if (mode == GoalMode.IDLE) {
      return false;
    }

    var retreatHealthThreshold = bot.settingsSource().get(AutomationSettings.RETREAT_HEALTH_THRESHOLD);
    var retreatFoodThreshold = bot.settingsSource().get(AutomationSettings.RETREAT_FOOD_THRESHOLD);
    var hostile = worldMemory.findNearestHostile(bot);
    if (hostile.isPresent()
      && hostile.get().position().distanceTo(player.position()) < 8
      && player.getHealth() <= retreatHealthThreshold) {
      interruptFor(new FleeAction(hostile.get().pos()));
      return true;
    }

    if (player.getFoodData().getFoodLevel() <= retreatFoodThreshold && AutomationInventory.isFoodInInventory(bot)) {
      interruptFor(new EatAction());
      return true;
    }

    if ((player.isInLava() || player.isOnFire() || player.getAirSupply() < 60) && hostile.isPresent()) {
      interruptFor(new FleeAction(hostile.get().pos()));
      return true;
    }

    if (player.level().dimension() == Level.END
      && (player.getHealth() <= 10.0F || player.getY() < 55.0 || player.position().distanceToSqr(new Vec3(0.0, player.getY(), 0.0)) > 140 * 140)) {
      interruptFor(new RetreatToCenterAction());
      return true;
    }

    return false;
  }

  private void interruptFor(AutomationAction action) {
    if (currentAction != null && currentAction.priority() == ControlPriority.CRITICAL) {
      return;
    }

    bot.botControl().stopAll();
    clearCurrentAction();
    startAction(action);
  }

  private AutomationTeamCoordinator team() {
    return bot.instanceManager().automationCoordinator();
  }

  private void noteProgress() {
    lastProgressTick = worldMemory.ticks();
    consecutiveFailures = 0;
    team().noteProgress(bot);
  }

  private void clearCurrentAction() {
    currentAction = null;
    currentActionStartedTick = 0L;
    currentActionMovedTick = 0L;
    currentActionLastPos = null;
  }

  private void updateActionMovement(LocalPlayer player) {
    if (currentAction == null) {
      return;
    }

    if (currentActionLastPos == null || currentActionLastPos.distanceToSqr(player.position()) >= 4.0) {
      currentActionLastPos = player.position();
      currentActionMovedTick = worldMemory.ticks();
    }
  }

  private boolean isCurrentActionStalled() {
    if (currentAction == null) {
      return false;
    }

    return worldMemory.ticks() - currentActionStartedTick > ACTION_TIMEOUT_TICKS
      || worldMemory.ticks() - currentActionMovedTick > ACTION_STALL_TICKS;
  }

  private void timeoutCurrentAction() {
    if (currentAction instanceof PositionedAction positionedAction) {
      worldMemory.markUnreachable(positionedAction.position());
    }

    team().noteTimeout(bot, currentAction != null ? currentAction.description() : null);
    team().noteRecovery(bot, "recovering from stalled action");
    bot.botControl().stopAll();
    clearCurrentAction();
    consecutiveFailures++;
    if (consecutiveFailures >= 3) {
      lastEyeDirectionTarget = null;
    }
    status = "recovering from stalled action";
  }

  private void disableAutomation() {
    requirements.clear();
    clearCurrentAction();
    lastEyeDirectionTarget = null;
    paused = false;
    pausedMode = null;
    pausedBeatPhase = null;
    mode = GoalMode.IDLE;
    beatPhase = BeatPhase.PREPARE_OVERWORLD;
    awaitingRespawn = false;
    bot.botControl().stopAll();
    team().releaseClaims(bot);
    status = "automation disabled";
  }

  private @Nullable String effectivePhaseName() {
    if (paused && pausedMode == GoalMode.BEAT && pausedBeatPhase != null) {
      return pausedBeatPhase.name();
    }
    return mode == GoalMode.BEAT ? beatPhase.name() : null;
  }

  private void updateBeatPlan(Level level) {
    var role = team().roleFor(bot);
    var objective = team().objectiveFor(bot);
    switch (beatPhase) {
      case PREPARE_OVERWORLD -> {
        if (requirements.isEmpty()) {
          queuePreparationRequirements(role);
        }
        if (requirements.isEmpty()) {
          if (objective.ordinal() >= AutomationTeamCoordinator.TeamObjective.STRONGHOLD_HUNT.ordinal()) {
            beatPhase = BeatPhase.STRONGHOLD_SEARCH;
          } else if (team().shouldTravelToNether(bot)) {
            beatPhase = BeatPhase.ENTER_NETHER;
          } else {
            beatPhase = BeatPhase.RETURN_TO_OVERWORLD;
          }
        }
      }
      case ENTER_NETHER -> {
        if (level.dimension() == Level.NETHER) {
          beatPhase = BeatPhase.NETHER_COLLECTION;
        } else if (!team().shouldTravelToNether(bot) && objective.ordinal() >= AutomationTeamCoordinator.TeamObjective.STRONGHOLD_HUNT.ordinal()) {
          beatPhase = BeatPhase.STRONGHOLD_SEARCH;
        }
      }
      case NETHER_COLLECTION -> {
        if (level.dimension() != Level.NETHER) {
          beatPhase = team().shouldTravelToNether(bot) ? BeatPhase.ENTER_NETHER : BeatPhase.RETURN_TO_OVERWORLD;
          return;
        }

        if (requirements.isEmpty()) {
          if (teamNeeds(AutomationRequirements.BLAZE_ROD)) {
            pushRequirement(new RequirementGoal(AutomationRequirements.BLAZE_ROD, role.isNetherSpecialist() ? REQUIRED_BLAZE_RODS : 1, "blaze rods"));
          } else if (teamNeeds(AutomationRequirements.ENDER_PEARL)) {
            pushRequirement(new RequirementGoal(AutomationRequirements.ENDER_PEARL, role.isNetherSpecialist() ? REQUIRED_ENDER_PEARLS : 1, "ender pearls"));
          } else if (teamNeeds(AutomationRequirements.ENDER_EYE)) {
            pushRequirement(new RequirementGoal(
              AutomationRequirements.ENDER_EYE,
              Math.min(REQUIRED_EYES, team().targetFor(bot, AutomationRequirements.ENDER_EYE)),
              "eyes of ender"));
          } else {
            beatPhase = BeatPhase.RETURN_TO_OVERWORLD;
          }
        }
      }
      case RETURN_TO_OVERWORLD -> {
        if (level.dimension() == Level.OVERWORLD) {
          if (requirements.isEmpty()) {
            queueReturnGear(role);
            if (requirements.isEmpty()) {
              beatPhase = BeatPhase.STRONGHOLD_SEARCH;
            }
          }
        } else if (team().shouldTravelToNether(bot)) {
          beatPhase = BeatPhase.ENTER_NETHER;
        }
      }
      case STRONGHOLD_SEARCH -> {
        if (level.dimension() == Level.END) {
          beatPhase = BeatPhase.END_FIGHT;
        } else if (level.dimension() != Level.OVERWORLD) {
          beatPhase = BeatPhase.RETURN_TO_OVERWORLD;
        } else if (hasRememberedBlock(state -> state.getBlock() == Blocks.END_PORTAL_FRAME || state.getBlock() == Blocks.END_PORTAL)) {
          beatPhase = BeatPhase.ACTIVATE_PORTAL;
        } else if (!team().shouldSearchStronghold(bot) && team().shouldTravelToNether(bot)) {
          beatPhase = BeatPhase.ENTER_NETHER;
        }
      }
      case ACTIVATE_PORTAL -> {
        if (level.dimension() == Level.END) {
          beatPhase = BeatPhase.END_FIGHT;
        } else if (level.dimension() != Level.OVERWORLD) {
          beatPhase = BeatPhase.RETURN_TO_OVERWORLD;
        }
      }
      case END_FIGHT -> {
        if (level.dimension() != Level.END) {
          beatPhase = hasRememberedBlock(state -> state.getBlock() == Blocks.END_PORTAL_FRAME || state.getBlock() == Blocks.END_PORTAL)
            ? BeatPhase.ACTIVATE_PORTAL
            : BeatPhase.STRONGHOLD_SEARCH;
          return;
        }

        var dragonVisible = worldMemory.findNearestEntity(bot, entity -> entity.type() == EntityType.ENDER_DRAGON);
        var crystalVisible = worldMemory.findNearestEntity(bot, entity -> entity.type() == EntityType.END_CRYSTAL);
        if (dragonVisible.isEmpty() && crystalVisible.isEmpty() && worldMemory.ticks() - lastProgressTick > 400) {
          beatPhase = BeatPhase.COMPLETE;
        }
      }
      case COMPLETE -> {}
    }
  }

  private void driveBeatPhase(Level level, LocalPlayer player) {
    var role = team().roleFor(bot);
    switch (beatPhase) {
      case ENTER_NETHER -> {
        if (!team().shouldTravelToNether(bot) && team().objectiveFor(bot).ordinal() >= AutomationTeamCoordinator.TeamObjective.STRONGHOLD_HUNT.ordinal()) {
          beatPhase = BeatPhase.STRONGHOLD_SEARCH;
          return;
        }
        if (findAnyPortal(Level.OVERWORLD).isPresent()) {
          startAction(new UsePortalAction(Blocks.NETHER_PORTAL, Level.NETHER));
        } else if (canCastPortalHere()) {
          startAction(new CastPortalAction("casting nether portal"));
        } else if (canBuildPortalHere()) {
          startAction(new BuildPortalAction("constructing nether portal"));
        } else if (needsPortalEngineeringSupplies()) {
          queuePortalEngineeringRequirements();
        } else {
          var ruinedPortal = worldMemory.findNearestBlock(bot, state ->
            state.getBlock() == Blocks.CRYING_OBSIDIAN
              || state.getBlock() == Blocks.GOLD_BLOCK
              || state.getBlock() == Blocks.NETHERRACK
              || state.getBlock() == Blocks.MAGMA_BLOCK
              || state.getBlock() == Blocks.OBSIDIAN);
          if (ruinedPortal.isPresent()) {
            startAction(new MoveToPositionAction(ruinedPortal.get().pos().getCenter(), "approaching ruined portal"));
          } else {
            var sharedRuinedPortal = team().findNearestRuinedPortalHint(bot, level.dimension());
            if (sharedRuinedPortal.isPresent()) {
              startAction(new MoveToPositionAction(sharedRuinedPortal.get().pos().getCenter(), "approaching ruined portal"));
            } else {
              var focus = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.WATER)
                .map(AutomationWorldMemory.RememberedBlock::pos)
                .map(BlockPos::getCenter)
                .orElse(player.position());
              startAction(new ExploreAction("searching for portal site", "nether-entry", focus, 96));
            }
          }
        }
      }
      case RETURN_TO_OVERWORLD -> {
        if (findAnyPortal(Level.NETHER).isPresent()) {
          startAction(new UsePortalAction(Blocks.NETHER_PORTAL, Level.OVERWORLD));
        } else if (canBuildPortalHere()) {
          startAction(new BuildPortalAction("constructing return portal"));
        } else {
          var portalHint = team().findNearestPortal(bot, Level.OVERWORLD)
            .map(AutomationTeamCoordinator.SharedBlock::pos)
            .map(BlockPos::getCenter)
            .orElse(player.position());
          startAction(new ExploreAction("searching for return portal", "return-portal", portalHint, 96));
        }
      }
      case STRONGHOLD_SEARCH -> {
        if (AutomationInventory.countInventory(bot, AutomationRequirements.ENDER_EYE) <= 0) {
          pushRequirement(new RequirementGoal(AutomationRequirements.ENDER_EYE, 1, "stronghold search"));
          return;
        }

        var endPortal = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.END_PORTAL);
        if (endPortal.isPresent()) {
          beatPhase = BeatPhase.ACTIVATE_PORTAL;
          return;
        }

        var strongholdEstimate = team().strongholdEstimate(bot);
        if (strongholdEstimate.isPresent() && player.position().distanceTo(strongholdEstimate.get()) > 64) {
          startAction(new MoveToPositionAction(strongholdEstimate.get(), "moving to stronghold estimate"));
        } else if (team().portalRoomEstimate(bot).isPresent()) {
          startAction(new MoveToPositionAction(
            team().assignLayeredExplorationTarget(
              bot,
              level.dimension(),
              "stronghold-dig",
              team().portalRoomEstimate(bot).get(),
              20,
              0, -8, 8, -16, 16),
            "probing stronghold tunnels"));
        } else if (lastEyeDirectionTarget != null && player.position().distanceTo(lastEyeDirectionTarget) > 16) {
          startAction(new MoveToPositionAction(lastEyeDirectionTarget, "following eye of ender"));
        } else if (worldMemory.ticks() - lastEyeThrowTick > 120 && (role.isStrongholdSpecialist() || role == AutomationTeamCoordinator.TeamRole.END_SUPPORT)) {
          startAction(new ThrowEyeAction());
        } else {
          startAction(new MoveToPositionAction(
            team().assignLayeredExplorationTarget(
              bot,
              level.dimension(),
              "stronghold-search",
              strongholdEstimate.orElse(player.position()),
              strongholdEstimate.isPresent() ? 36 : 144,
              0, -12, 12, -24, 24),
            "searching for stronghold"));
        }
      }
      case ACTIVATE_PORTAL -> {
        var activatedPortal = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.END_PORTAL);
        if (activatedPortal.isPresent()) {
          if (team().shouldEnterEnd(bot)) {
            startAction(new UsePortalAction(Blocks.END_PORTAL, Level.END));
          } else {
            startAction(new HoldPositionAction(activatedPortal.get().pos().getCenter(), "guarding portal room"));
          }
          return;
        }

        if (AutomationInventory.countInventory(bot, AutomationRequirements.ENDER_EYE) <= 0) {
          pushRequirement(new RequirementGoal(AutomationRequirements.ENDER_EYE, 1, "activate portal"));
          return;
        }

        var frame = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.END_PORTAL_FRAME && !hasEye(state));
        if (frame.isPresent() && team().claimBlock(bot, "portal-frame", level.dimension(), frame.get().pos(), 8_000L)) {
          startAction(new FillPortalFrameAction(frame.get().pos()));
        } else {
          var focus = team().portalRoomEstimate(bot).orElse(team().strongholdEstimate(bot).orElse(player.position()));
          startAction(new MoveToPositionAction(
            team().assignLayeredExplorationTarget(bot, level.dimension(), "portal-room", focus, 18, 0, -6, 6, -12, 12),
            "searching for portal room"));
        }
      }
      case END_FIGHT -> {
        var crystal = worldMemory.rememberedEntities().stream()
          .filter(entity -> entity.type() == EntityType.END_CRYSTAL)
          .min(Comparator.comparingDouble(entity ->
            entity.position().distanceToSqr(player.position()) - entity.position().y * 2.0));
        if (crystal.isPresent() && team().claimEntity(bot, "end-crystal", crystal.get().uuid(), 15_000L)) {
          startAction(new KillEntityAction(crystal.get().uuid(), "destroying end crystal"));
          return;
        }

        var dragon = worldMemory.findNearestEntity(bot, entity -> entity.type() == EntityType.ENDER_DRAGON);
        if (dragon.isPresent() && team().shouldEnterEnd(bot)) {
          startAction(new KillEntityAction(dragon.get().uuid(), "attacking dragon"));
        } else if (player.position().distanceToSqr(new Vec3(0.0, player.getY(), 0.0)) > 48 * 48) {
          startAction(new RetreatToCenterAction());
        } else {
          startAction(new ExploreAction("searching for dragon", "end-fight", new Vec3(0.0, Math.max(65.0, player.getY()), 0.0), 32));
        }
      }
      default -> {}
    }
  }

  private void queuePreparationRequirements(AutomationTeamCoordinator.TeamRole role) {
    var goals = new ArrayList<RequirementGoal>();
    goals.add(new RequirementGoal(AutomationRequirements.FOOD, role.isNetherSpecialist() ? REQUIRED_FOOD : 8, "survival food"));
    goals.add(new RequirementGoal(AutomationRequirements.STONE_PICKAXE, 1, "tools"));
    goals.add(new RequirementGoal(AutomationRequirements.SHIELD, 1, "survival"));

    if (role.isNetherSpecialist()) {
      goals.add(new RequirementGoal(AutomationRequirements.CRAFTING_TABLE, 1, "crafting"));
      goals.add(new RequirementGoal(AutomationRequirements.FURNACE, 1, "smelting"));
      goals.add(new RequirementGoal(AutomationRequirements.IRON_INGOT, role == AutomationTeamCoordinator.TeamRole.PORTAL_ENGINEER ? 10 : 7, "progression"));
      goals.add(new RequirementGoal(AutomationRequirements.BUCKET, role == AutomationTeamCoordinator.TeamRole.PORTAL_ENGINEER ? 2 : 1, "fluids"));
      goals.add(new RequirementGoal(AutomationRequirements.FLINT_AND_STEEL, 1, "portal ignition"));
    }

    if (role.isEndSpecialist() && team().objectiveFor(bot).ordinal() >= AutomationTeamCoordinator.TeamObjective.STRONGHOLD_HUNT.ordinal()) {
      goals.add(new RequirementGoal(AutomationRequirements.BOW, 1, "end fight"));
      goals.add(new RequirementGoal(AutomationRequirements.ARROW, REQUIRED_ARROWS, "end fight"));
    }

    pushRequirements(goals);
  }

  private void queueReturnGear(AutomationTeamCoordinator.TeamRole role) {
    if (role.isEndSpecialist() && AutomationInventory.countInventory(bot, AutomationRequirements.BOW) < 1) {
      pushRequirement(new RequirementGoal(AutomationRequirements.BOW, 1, "end fight"));
    } else if (role.isEndSpecialist() && AutomationInventory.countInventory(bot, AutomationRequirements.ARROW) < REQUIRED_ARROWS) {
      pushRequirement(new RequirementGoal(AutomationRequirements.ARROW, REQUIRED_ARROWS, "end fight"));
    } else if (AutomationInventory.countInventory(bot, AutomationRequirements.SHIELD) < 1) {
      pushRequirement(new RequirementGoal(AutomationRequirements.SHIELD, 1, "survival"));
    } else if (teamNeeds(AutomationRequirements.ANY_BED) && role.isEndSpecialist()) {
      pushRequirement(new RequirementGoal(AutomationRequirements.ANY_BED, 1, "dragon finish"));
    }
  }

  private boolean teamNeeds(String requirementKey) {
    return team().countFor(bot, requirementKey) < team().targetFor(bot, requirementKey);
  }

  private boolean needsPortalEngineeringSupplies() {
    var role = team().roleFor(bot);
    if (role != AutomationTeamCoordinator.TeamRole.LEAD
      && role != AutomationTeamCoordinator.TeamRole.PORTAL_ENGINEER
      && role != AutomationTeamCoordinator.TeamRole.NETHER_RUNNER) {
      return false;
    }

    var totalBuckets = AutomationInventory.countInventory(bot, AutomationRequirements.BUCKET)
      + AutomationInventory.countInventory(bot, AutomationRequirements.WATER_BUCKET)
      + AutomationInventory.countInventory(bot, AutomationRequirements.LAVA_BUCKET);
    return AutomationInventory.countInventory(bot, AutomationRequirements.FLINT_AND_STEEL) < 1
      || totalBuckets < 2
      || AutomationInventory.countInventory(bot, AutomationRequirements.WATER_BUCKET) < 1
      || AutomationInventory.countInventory(bot, AutomationRequirements.LAVA_BUCKET) < 1;
  }

  private void queuePortalEngineeringRequirements() {
    var totalBuckets = AutomationInventory.countInventory(bot, AutomationRequirements.BUCKET)
      + AutomationInventory.countInventory(bot, AutomationRequirements.WATER_BUCKET)
      + AutomationInventory.countInventory(bot, AutomationRequirements.LAVA_BUCKET);
    if (totalBuckets < 2) {
      pushRequirement(new RequirementGoal(AutomationRequirements.BUCKET, 2 - totalBuckets, "portal engineering"));
    } else if (AutomationInventory.countInventory(bot, AutomationRequirements.WATER_BUCKET) < 1) {
      pushRequirement(new RequirementGoal(AutomationRequirements.WATER_BUCKET, 1, "portal casting"));
    } else if (AutomationInventory.countInventory(bot, AutomationRequirements.LAVA_BUCKET) < 1) {
      pushRequirement(new RequirementGoal(AutomationRequirements.LAVA_BUCKET, 1, "portal casting"));
    } else if (AutomationInventory.countInventory(bot, AutomationRequirements.FLINT_AND_STEEL) < 1) {
      pushRequirement(new RequirementGoal(AutomationRequirements.FLINT_AND_STEEL, 1, "portal ignition"));
    }
  }

  private Map<String, Integer> inventorySnapshot() {
    var snapshot = new HashMap<String, Integer>();
    for (var requirement : TRACKED_TEAM_REQUIREMENTS) {
      snapshot.put(requirement, AutomationInventory.countInventory(bot, requirement));
    }
    return Map.copyOf(snapshot);
  }

  private void recoverAfterDeath(Level level, LocalPlayer player) {
    var deaths = team().deathCount(bot);
    if (deaths <= knownDeaths) {
      return;
    }

    knownDeaths = deaths;
    var deathPosition = team().lastKnownDeathPosition(bot);
    var deathDimension = team().lastKnownDeathDimension(bot);
    if (bot.settingsSource().get(AutomationSettings.ALLOW_DEATH_RECOVERY)
      && deathPosition.isPresent()
      && deathDimension.isPresent()
      && deathDimension.get() == level.dimension()) {
      team().noteRecovery(bot, "recovering death drops");
      startAction(new MoveToPositionAction(deathPosition.get(), "recovering dropped items"));
      status = "recovering dropped items";
      return;
    }

    team().noteRecovery(bot, "resetting after death");
    lastEyeDirectionTarget = null;
    if (mode == GoalMode.BEAT) {
      beatPhase = level.dimension() == Level.NETHER ? BeatPhase.RETURN_TO_OVERWORLD : BeatPhase.PREPARE_OVERWORLD;
    }
  }

  private Plan plan(RequirementGoal requirement, Level level) {
    var requirementKey = requirement.requirementKey();
    var missing = requirement.count() - AutomationInventory.countInventory(bot, requirementKey);

    var dropped = worldMemory.findNearestDroppedItem(bot, requirementKey);
    if (dropped.isPresent()) {
      return Plan.action(new MoveToPositionAction(dropped.get().position(), "collecting dropped " + AutomationRequirements.describe(requirementKey)));
    }

    var knownContainer = worldMemory.findNearestContainerWithItem(bot, requirementKey);
    if (knownContainer.isPresent()) {
      return Plan.action(new LootContainerAction(knownContainer.get().pos(), requirementKey, missing));
    }

    var uninspectedContainer = worldMemory.findNearestUninspectedContainer(bot);
    if (uninspectedContainer.isPresent()) {
      return Plan.action(new InspectContainerAction(uninspectedContainer.get().pos()));
    }

    if (AutomationRequirements.WATER_BUCKET.equals(requirementKey)) {
      if (AutomationInventory.countInventory(bot, AutomationRequirements.BUCKET) < 1) {
        return Plan.dependencies(List.of(new RequirementGoal(AutomationRequirements.BUCKET, 1, "water bucket")), "bucket dependency");
      }

      var water = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.WATER);
      if (water.isPresent()) {
        return Plan.action(new FillBucketAction(water.get().pos(), AutomationRequirements.WATER_BUCKET, "collecting water"));
      }

      return Plan.action(new ExploreAction("searching for water", "water-source", playerPosition(), 96));
    }

    if (AutomationRequirements.LAVA_BUCKET.equals(requirementKey)) {
      if (AutomationInventory.countInventory(bot, AutomationRequirements.BUCKET) < 1) {
        return Plan.dependencies(List.of(new RequirementGoal(AutomationRequirements.BUCKET, 1, "lava bucket")), "bucket dependency");
      }

      var lava = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.LAVA);
      if (lava.isPresent()) {
        return Plan.action(new FillBucketAction(lava.get().pos(), AutomationRequirements.LAVA_BUCKET, "collecting lava"));
      }

      var ruinedPortal = worldMemory.findNearestBlock(bot, state ->
        state.getBlock() == Blocks.CRYING_OBSIDIAN
          || state.getBlock() == Blocks.GOLD_BLOCK
          || state.getBlock() == Blocks.NETHERRACK
          || state.getBlock() == Blocks.MAGMA_BLOCK
          || state.getBlock() == Blocks.OBSIDIAN);
      if (ruinedPortal.isPresent()) {
        return Plan.action(new MoveToPositionAction(ruinedPortal.get().pos().getCenter(), "approaching portal ruins"));
      }

      var sharedRuinedPortal = team().findNearestRuinedPortalHint(bot, level.dimension());
      if (sharedRuinedPortal.isPresent()) {
        return Plan.action(new MoveToPositionAction(sharedRuinedPortal.get().pos().getCenter(), "approaching portal ruins"));
      }

      return Plan.action(new ExploreAction("searching for lava", "lava-source", playerPosition(), 96));
    }

    if (AutomationRequirements.ENDER_PEARL.equals(requirementKey)) {
      var piglin = worldMemory.findNearestPiglin(bot);
      if (piglin.isPresent() && AutomationInventory.countInventory(bot, AutomationRequirements.GOLD_INGOT) > 0) {
        return Plan.action(new BarterAction(piglin.get().uuid()));
      }
    }

    var craftingRecipe = AutomationRecipes.craftingRecipe(requirementKey);
    if (craftingRecipe.isPresent()) {
      var recipe = craftingRecipe.get();
      var dependencies = missingDependencies(recipe, missing);
      if (!dependencies.isEmpty()) {
        return Plan.dependencies(dependencies, "craft dependencies");
      }

      return Plan.action(new CraftAction(recipe, requirement.count()));
    }

    var smeltingRecipe = AutomationRecipes.smeltingRecipe(requirementKey);
    if (smeltingRecipe.isPresent()) {
      var recipe = smeltingRecipe.get();
      var dependencies = new ArrayList<RequirementGoal>();
      var missingOutput = requirement.count() - AutomationInventory.countInventory(bot, requirementKey);
      if (AutomationInventory.countInventory(bot, AutomationRequirements.FUEL) <= 0) {
        dependencies.add(new RequirementGoal(AutomationRequirements.FUEL, 1, "smelting fuel"));
      }
      if (recipe.inputKeys().stream().noneMatch(input -> AutomationInventory.countInventory(bot, input) >= missingOutput)) {
        dependencies.add(new RequirementGoal(recipe.inputKeys().getFirst(), missingOutput, "smelting input"));
      }
      if (!dependencies.isEmpty()) {
        return Plan.dependencies(dependencies, "smelting dependencies");
      }

      return Plan.action(new SmeltAction(recipe, requirement.count()));
    }

    var mineable = AutomationRecipes.mineableSource(requirementKey);
    if (mineable.isPresent()) {
      if (!AutomationInventory.satisfiesToolRequirement(bot, mineable.get().requiredTool())) {
        return Plan.dependencies(List.of(new RequirementGoal(requiredToolKey(mineable.get().requiredTool()), 1, "required tool")), "tool dependency");
      }

      var block = worldMemory.findNearestBlock(bot, mineable.get().predicate());
      if (block.isPresent()) {
        return Plan.action(new BreakBlockAction(block.get().pos(), AutomationRequirements.describe(requirementKey)));
      }

      return Plan.explore("searching for " + AutomationRequirements.describe(requirementKey));
    }

    var entitySource = AutomationRecipes.entitySource(requirementKey);
    if (entitySource.isPresent()) {
      var entity = worldMemory.findNearestEntity(bot, entitySource.get().predicate());
      if (entity.isPresent()) {
        return Plan.action(new KillEntityAction(entity.get().uuid(), "hunting " + AutomationRequirements.describe(requirementKey)));
      }

      if (AutomationRequirements.BLAZE_ROD.equals(requirementKey) && level.dimension() == Level.NETHER) {
        var fortress = worldMemory.findNearestBlock(bot, state ->
          state.getBlock() == Blocks.SPAWNER
            || state.getBlock() == Blocks.NETHER_BRICKS
            || state.getBlock() == Blocks.NETHER_BRICK_FENCE
            || state.getBlock() == Blocks.NETHER_BRICK_STAIRS);
        if (fortress.isPresent()) {
          return Plan.action(new MoveToPositionAction(fortress.get().pos().getCenter(), "approaching fortress"));
        }
        var sharedFortress = team().findNearestFortressHint(bot);
        if (sharedFortress.isPresent()) {
          return Plan.action(new MoveToPositionAction(sharedFortress.get().pos().getCenter(), "approaching fortress"));
        }
        var fortressEstimate = team().fortressEstimate(bot);
        if (fortressEstimate.isPresent()) {
          return Plan.action(new MoveToPositionAction(
            team().assignLayeredExplorationTarget(bot, level.dimension(), "nether-fortress", fortressEstimate.get(), 96, 0, 12, -12),
            "probing fortress perimeter"));
        }
        return Plan.action(new ExploreAction("searching for fortress", "nether-fortress", playerPosition(), 160));
      }

      if (AutomationRequirements.ENDER_PEARL.equals(requirementKey) && level.dimension() == Level.NETHER) {
        if (AutomationInventory.countInventory(bot, AutomationRequirements.GOLD_INGOT) < 1) {
          return Plan.dependencies(List.of(new RequirementGoal(AutomationRequirements.GOLD_INGOT, 1, "piglin barter")), "barter gold");
        }
      }

      return Plan.explore("searching for " + AutomationRequirements.describe(requirementKey));
    }

    if (AutomationRequirements.FOOD.equals(requirementKey)) {
      return Plan.action(new ExploreAction("searching for food", "food", playerPosition(), 80));
    }

    return Plan.explore("searching for " + AutomationRequirements.describe(requirementKey));
  }

  private List<RequirementGoal> missingDependencies(AutomationRecipes.CraftingRecipeDefinition recipe, int missingOutput) {
    var batches = Math.max(1, (int) Math.ceil(missingOutput / (double) recipe.outputCount()));
    var dependencies = new ArrayList<RequirementGoal>();
    if (recipe.station() == AutomationRecipes.CraftingStation.CRAFTING_TABLE
      && !hasRememberedBlock(state -> state.getBlock() == Blocks.CRAFTING_TABLE)
      && AutomationInventory.countInventory(bot, AutomationRequirements.CRAFTING_TABLE) <= 0) {
      dependencies.add(new RequirementGoal(AutomationRequirements.CRAFTING_TABLE, 1, "crafting station"));
    }
    if (recipe.station() == AutomationRecipes.CraftingStation.FURNACE
      && !hasRememberedBlock(state -> state.getBlock() == Blocks.FURNACE)
      && AutomationInventory.countInventory(bot, AutomationRequirements.FURNACE) <= 0) {
      dependencies.add(new RequirementGoal(AutomationRequirements.FURNACE, 1, "furnace"));
    }

    recipe.ingredients().stream()
      .collect(Collectors.groupingBy(AutomationRecipes.IngredientPlacement::requirementKey, Collectors.counting()))
      .forEach((key, amount) -> {
        var required = Math.toIntExact(amount) * batches;
        var current = AutomationInventory.countInventory(bot, key);
        if (current < required) {
          dependencies.add(new RequirementGoal(key, required, "recipe input"));
        }
      });
    dependencies.sort(Comparator.comparing(RequirementGoal::requirementKey));
    return dependencies;
  }

  private boolean hasRememberedBlock(Predicate<BlockState> predicate) {
    return worldMemory.findNearestBlock(bot, predicate).isPresent();
  }

  private String requiredToolKey(AutomationRecipes.ToolRequirement requirement) {
    return switch (requirement) {
      case NONE -> AutomationRequirements.ANY_LOG;
      case WOOD_PICKAXE -> AutomationRequirements.WOODEN_PICKAXE;
      case STONE_PICKAXE -> AutomationRequirements.STONE_PICKAXE;
      case IRON_PICKAXE -> AutomationRequirements.IRON_PICKAXE;
    };
  }

  private void pushRequirements(List<RequirementGoal> goals) {
    for (int i = goals.size() - 1; i >= 0; i--) {
      pushRequirement(goals.get(i));
    }
  }

  private void pushRequirement(RequirementGoal goal) {
    if (goal.count() <= 0) {
      return;
    }

    var existingTop = requirements.peek();
    if (existingTop != null
      && existingTop.requirementKey().equals(goal.requirementKey())
      && existingTop.count() >= goal.count()) {
      return;
    }

    requirements.push(goal);
  }

  private void startAction(AutomationAction action) {
    currentAction = action;
    currentActionStartedTick = worldMemory.ticks();
    currentActionMovedTick = worldMemory.ticks();
    currentActionLastPos = bot.minecraft().player != null ? bot.minecraft().player.position() : null;
    action.start(this);
    status = action.description();
  }

  private Vec3 playerPosition() {
    var player = bot.minecraft().player;
    return player != null ? player.position() : Vec3.ZERO;
  }

  private BlockPos findPlacementPos(Block block) {
    var player = bot.minecraft().player;
    var level = bot.minecraft().level;
    if (player == null || level == null) {
      return player == null ? BlockPos.ZERO : player.blockPosition();
    }

    var origin = player.blockPosition();
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -2; dx <= 2; dx++) {
        for (int dz = -2; dz <= 2; dz++) {
          var pos = origin.offset(dx, dy, dz);
          if (!level.getBlockState(pos).canBeReplaced()) {
            continue;
          }
          if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
            continue;
          }
          return pos.immutable();
        }
      }
    }

    return origin.above();
  }

  private Optional<Entity> findEntity(UUID uuid) {
    var level = bot.minecraft().level;
    if (level == null) {
      return Optional.empty();
    }

    for (var entity : level.entitiesForRendering()) {
      if (entity.getUUID().equals(uuid)) {
        return Optional.of(entity);
      }
    }
    return Optional.empty();
  }

  private Optional<Vec3> findEyeDirectionTarget(LocalPlayer player) {
    var eyeEntity = worldMemory.findNearestEntity(bot, entity -> entity.type() == EntityType.EYE_OF_ENDER);
    if (eyeEntity.isEmpty()) {
      return Optional.empty();
    }

    var direction = eyeEntity.get().position().subtract(player.position());
    if (direction.lengthSqr() < 1.0e-6) {
      return Optional.empty();
    }

    return Optional.of(player.position().add(direction.normalize().scale(64)));
  }

  private Optional<BlockPos> findAnyPortal(ResourceKey<Level> dimension) {
    var level = bot.minecraft().level;
    if (level != null && level.dimension() == dimension) {
      var localPortal = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.NETHER_PORTAL);
      if (localPortal.isPresent()) {
        return Optional.of(localPortal.get().pos());
      }
    }

    return team().findNearestPortal(bot, dimension).map(AutomationTeamCoordinator.SharedBlock::pos);
  }

  private boolean canBuildPortalHere() {
    return findPortalBlueprint().isPresent();
  }

  private Optional<PortalBlueprint> findPortalBlueprint() {
    var level = bot.minecraft().level;
    var player = bot.minecraft().player;
    if (level == null || player == null) {
      return Optional.empty();
    }

    var existing = findExistingPortalBlueprint(level);
    if (existing.isPresent()) {
      return existing;
    }

    if (AutomationInventory.countInventory(bot, AutomationRequirements.OBSIDIAN) < REQUIRED_PORTAL_OBSIDIAN) {
      return Optional.empty();
    }

    return findFreshPortalBlueprint(player, level);
  }

  private Optional<PortalBlueprint> findExistingPortalBlueprint(Level level) {
    PortalBlueprint best = null;
    for (var remembered : worldMemory.rememberedBlocks()) {
      var block = remembered.state().getBlock();
      if (block != Blocks.OBSIDIAN && block != Blocks.NETHER_PORTAL) {
        continue;
      }

      for (var axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
        for (int xOffset = -3; xOffset <= 0; xOffset++) {
          for (int yOffset = -4; yOffset <= 0; yOffset++) {
            for (int zOffset = -3; zOffset <= 0; zOffset++) {
              var base = remembered.pos().offset(
                axis == Direction.Axis.X ? xOffset : 0,
                yOffset,
                axis == Direction.Axis.Z ? zOffset : 0);
              var candidate = evaluatePortalBlueprint(level, base, axis, true);
              if (candidate.isPresent() && (best == null || candidate.get().score() > best.score())) {
                best = candidate.get();
              }
            }
          }
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private Optional<PortalBlueprint> findFreshPortalBlueprint(LocalPlayer player, Level level) {
    PortalBlueprint best = null;
    for (var axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
      for (int dx = -6; dx <= 6; dx++) {
        for (int dz = -6; dz <= 6; dz++) {
          for (int dy = -1; dy <= 1; dy++) {
            var base = player.blockPosition().offset(dx, dy, dz);
            var candidate = evaluatePortalBlueprint(level, base, axis, false);
            if (candidate.isPresent() && (best == null || candidate.get().score() > best.score())) {
              best = candidate.get();
            }
          }
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private Optional<PortalBlueprint> evaluatePortalBlueprint(Level level,
                                                            BlockPos base,
                                                            Direction.Axis axis,
                                                            boolean requireExisting) {
    var requiredFramePositions = new ArrayList<BlockPos>(10);
    var optionalFramePositions = new ArrayList<BlockPos>(4);
    var interiorPositions = new ArrayList<BlockPos>(6);
    var existingRequiredFrameBlocks = 0;
    var missingRequiredFrameBlocks = 0;

    for (int height = 0; height <= 4; height++) {
      for (int width = 0; width <= 3; width++) {
        var pos = axis == Direction.Axis.X ? base.offset(width, height, 0) : base.offset(0, height, width);
        var state = level.getBlockState(pos);
        var isFrame = width == 0 || width == 3 || height == 0 || height == 4;
        if (isFrame) {
          var immutable = pos.immutable();
          var optionalCorner = (width == 0 || width == 3) && (height == 0 || height == 4);
          if (optionalCorner) {
            optionalFramePositions.add(immutable);
          } else {
            requiredFramePositions.add(immutable);
          }
          if (state.getBlock() == Blocks.OBSIDIAN || state.getBlock() == Blocks.NETHER_PORTAL) {
            if (!optionalCorner) {
              existingRequiredFrameBlocks++;
            }
            continue;
          }
          if (optionalCorner && (state.canBeReplaced() || state.getBlock() == Blocks.FIRE)) {
            continue;
          }
          if (state.canBeReplaced() || state.getBlock() == Blocks.FIRE) {
            missingRequiredFrameBlocks++;
            continue;
          }
          return Optional.empty();
        }

        interiorPositions.add(pos.immutable());
        if (!(state.canBeReplaced() || state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.NETHER_PORTAL)) {
          return Optional.empty();
        }
      }
    }

    if (requireExisting && existingRequiredFrameBlocks < 4) {
      return Optional.empty();
    }
    if (missingRequiredFrameBlocks > AutomationInventory.countInventory(bot, AutomationRequirements.OBSIDIAN)) {
      return Optional.empty();
    }

    if (!requireExisting) {
      for (int width = 0; width <= 3; width++) {
        var supportPos = axis == Direction.Axis.X ? base.offset(width, -1, 0) : base.offset(0, -1, width);
        if (!level.getBlockState(supportPos).isFaceSturdy(level, supportPos, Direction.UP)) {
          return Optional.empty();
        }
      }
    }

    return Optional.of(new PortalBlueprint(
      base.immutable(),
      axis,
      List.copyOf(requiredFramePositions),
      List.copyOf(optionalFramePositions),
      List.copyOf(interiorPositions),
      existingRequiredFrameBlocks,
      missingRequiredFrameBlocks));
  }

  private Optional<BlockPlaceAgainstData> findPlaceAgainst(BlockPos target) {
    var level = bot.minecraft().level;
    if (level == null) {
      return Optional.empty();
    }

    for (var face : BlockFace.VALUES) {
      var supportPos = face.offset(target);
      var supportState = level.getBlockState(supportPos);
      if (supportState.canBeReplaced()) {
        continue;
      }

      return Optional.of(new BlockPlaceAgainstData(SFVec3i.fromInt(supportPos), oppositeFace(face)));
    }

    return Optional.empty();
  }

  private boolean useHeldItemAgainst(String requirementKey, BlockPlaceAgainstData against, ClipContext.Fluid fluidMode) {
    var player = bot.minecraft().player;
    var gameMode = bot.minecraft().gameMode;
    if (player == null || gameMode == null || !AutomationInventory.ensureHolding(bot, requirementKey)) {
      return false;
    }

    var interactPoint = against.blockFace().getMiddleOfFace(against.againstPos());
    player.lookAt(EntityAnchorArgument.Anchor.EYES, interactPoint);
    if (gameMode.useItemOn(player, InteractionHand.MAIN_HAND, player.level().clipIncludingBorder(new ClipContext(
      player.getEyePosition(),
      interactPoint,
      ClipContext.Block.COLLIDER,
      fluidMode,
      player
    ))) instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
      player.swing(InteractionHand.MAIN_HAND);
    }
    return true;
  }

  private Optional<String> temporarySupportRequirement() {
    for (var requirement : List.of(AutomationRequirements.COBBLESTONE, AutomationRequirements.ANY_PLANKS, AutomationRequirements.ANY_LOG)) {
      if (AutomationInventory.countInventory(bot, requirement) > 0) {
        return Optional.of(requirement);
      }
    }
    return Optional.empty();
  }

  private BlockFace oppositeFace(BlockFace face) {
    return switch (face) {
      case NORTH -> BlockFace.SOUTH;
      case SOUTH -> BlockFace.NORTH;
      case EAST -> BlockFace.WEST;
      case WEST -> BlockFace.EAST;
      case TOP -> BlockFace.BOTTOM;
      case BOTTOM -> BlockFace.TOP;
    };
  }

  private boolean placeBlockAt(String requirementKey, BlockPos target) {
    var player = bot.minecraft().player;
    var gameMode = bot.minecraft().gameMode;
    if (player == null || gameMode == null) {
      return false;
    }

    if (!AutomationInventory.ensureHolding(bot, requirementKey)) {
      return false;
    }

    var against = findPlaceAgainst(target);
    if (against.isEmpty()) {
      return false;
    }

    var interactPoint = against.get().blockFace().getMiddleOfFace(against.get().againstPos());
    player.lookAt(EntityAnchorArgument.Anchor.EYES, interactPoint);
    if (gameMode.useItemOn(player, InteractionHand.MAIN_HAND, player.level().clipIncludingBorder(new ClipContext(
      player.getEyePosition(),
      interactPoint,
      ClipContext.Block.COLLIDER,
      ClipContext.Fluid.NONE,
      player
    ))) instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
      player.swing(InteractionHand.MAIN_HAND);
    }
    return true;
  }

  private boolean canCastPortalHere() {
    var level = bot.minecraft().level;
    if (level == null || level.dimension() != Level.OVERWORLD) {
      return false;
    }

    var totalBuckets = AutomationInventory.countInventory(bot, AutomationRequirements.BUCKET)
      + AutomationInventory.countInventory(bot, AutomationRequirements.WATER_BUCKET)
      + AutomationInventory.countInventory(bot, AutomationRequirements.LAVA_BUCKET);
    if (totalBuckets < 2 || AutomationInventory.countInventory(bot, AutomationRequirements.WATER_BUCKET) < 1) {
      return false;
    }

    return worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.LAVA).isPresent()
      && findFreshPortalBlueprint(bot.minecraft().player, level).isPresent();
  }

  private enum GoalMode {
    IDLE,
    ACQUIRE,
    BEAT
  }

  private enum BeatPhase {
    PREPARE_OVERWORLD,
    ENTER_NETHER,
    NETHER_COLLECTION,
    RETURN_TO_OVERWORLD,
    STRONGHOLD_SEARCH,
    ACTIVATE_PORTAL,
    END_FIGHT,
    COMPLETE
  }

  private enum ActionResult {
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  private record RequirementGoal(String requirementKey, int count, String reason) {
  }

  public record StatusSnapshot(String status,
                               String mode,
                               boolean paused,
                               @Nullable String beatPhase,
                               @Nullable String currentAction,
                               @Nullable String targetRequirementKey,
                               int targetRequirementCount,
                               List<QueuedRequirementSnapshot> queuedRequirements) {
  }

  public record QueuedRequirementSnapshot(String requirementKey, int count, String reason) {
  }

  private record Plan(@Nullable AutomationAction action, List<RequirementGoal> dependencies, String reason) {
    private static Plan action(AutomationAction action) {
      return new Plan(action, List.of(), action.description());
    }

    private static Plan dependencies(List<RequirementGoal> dependencies, String reason) {
      return new Plan(null, List.copyOf(dependencies), reason);
    }

    private static Plan explore(String reason) {
      return new Plan(null, List.of(), reason);
    }
  }

  private interface AutomationAction {
    void start(AutomationController controller);

    ActionResult poll(AutomationController controller);

    String description();

    default ControlPriority priority() {
      return ControlPriority.HIGH;
    }
  }

  private interface PositionedAction {
    BlockPos position();
  }

  private final class ExploreAction implements AutomationAction {
    private final String reason;
    private final String purpose;
    private final @Nullable Vec3 focus;
    private final int spacing;
    private @Nullable CompletableFuture<Void> future;
    private @Nullable Vec3 target;

    private ExploreAction(String reason) {
      this(reason, reason, null, 96);
    }

    private ExploreAction(String reason, String purpose, @Nullable Vec3 focus, int spacing) {
      this.reason = reason;
      this.purpose = purpose;
      this.focus = focus;
      this.spacing = spacing;
    }

    @Override
    public void start(AutomationController controller) {
      var player = bot.minecraft().player;
      var level = bot.minecraft().level;
      if (player == null || level == null) {
        return;
      }

      target = team().assignExplorationTarget(bot, level.dimension(), purpose, focus != null ? focus : player.position(), spacing);
      future = PathExecutor.executePathfinding(bot, new XZGoal((int) Math.floor(target.x), (int) Math.floor(target.z)), new PathConstraintImpl(bot));
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      return future == null || !future.isDone() ? ActionResult.RUNNING : finishFuture(future);
    }

    @Override
    public String description() {
      return reason;
    }
  }

  private final class MoveToPositionAction implements AutomationAction {
    private final Vec3 target;
    private final String description;
    private @Nullable CompletableFuture<Void> future;

    private MoveToPositionAction(Vec3 target, String description) {
      this.target = target;
      this.description = description;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new PosGoal(SFVec3i.fromDouble(target)), new PathConstraintImpl(bot));
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      if (future == null || !future.isDone()) {
        return ActionResult.RUNNING;
      }
      return finishFuture(future);
    }

    @Override
    public String description() {
      return description;
    }
  }

  private final class HoldPositionAction implements AutomationAction {
    private final Vec3 target;
    private final String description;
    private @Nullable CompletableFuture<Void> future;
    private long holdUntilTick;

    private HoldPositionAction(Vec3 target, String description) {
      this.target = target;
      this.description = description;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new PosGoal(SFVec3i.fromDouble(target)), new PathConstraintImpl(bot));
      holdUntilTick = 0L;
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var player = bot.minecraft().player;
      if (player == null) {
        return ActionResult.FAILED;
      }

      if (future != null && !future.isDone()) {
        return ActionResult.RUNNING;
      }
      if (future != null && finishFuture(future) == ActionResult.FAILED) {
        return ActionResult.FAILED;
      }

      if (player.position().distanceToSqr(target) > 9.0) {
        future = PathExecutor.executePathfinding(bot, new PosGoal(SFVec3i.fromDouble(target)), new PathConstraintImpl(bot));
        return ActionResult.RUNNING;
      }

      if (holdUntilTick == 0L) {
        holdUntilTick = worldMemory.ticks() + 80L;
      }

      return worldMemory.ticks() >= holdUntilTick ? ActionResult.SUCCEEDED : ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return description;
    }
  }

  private final class BreakBlockAction implements AutomationAction, PositionedAction {
    private final BlockPos position;
    private final String label;
    private @Nullable CompletableFuture<Void> future;

    private BreakBlockAction(BlockPos position, String label) {
      this.position = position;
      this.label = label;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new BreakBlockPosGoal(SFVec3i.fromInt(position)), new PathConstraintImpl(bot));
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      if (future == null || !future.isDone()) {
        return ActionResult.RUNNING;
      }
      var result = finishFuture(future);
      if (result == ActionResult.FAILED) {
        worldMemory.markUnreachable(position);
      }
      return result;
    }

    @Override
    public String description() {
      return "mining " + label;
    }

    @Override
    public BlockPos position() {
      return position;
    }
  }

  private final class FleeAction implements AutomationAction {
    private final BlockPos dangerPos;
    private @Nullable CompletableFuture<Void> future;

    private FleeAction(BlockPos dangerPos) {
      this.dangerPos = dangerPos;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new AwayFromPosGoal(SFVec3i.fromInt(dangerPos), 12), new PathConstraintImpl(bot));
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      if (future == null || !future.isDone()) {
        return ActionResult.RUNNING;
      }
      return finishFuture(future);
    }

    @Override
    public String description() {
      return "fleeing danger";
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.CRITICAL;
    }
  }

  private final class RetreatToCenterAction implements AutomationAction {
    private @Nullable CompletableFuture<Void> future;

    @Override
    public void start(AutomationController controller) {
      var player = bot.minecraft().player;
      if (player == null) {
        return;
      }

      var target = new Vec3(0.5, Math.max(65.0, player.getY()), 0.5);
      future = PathExecutor.executePathfinding(bot, new PosGoal(SFVec3i.fromDouble(target)), new PathConstraintImpl(bot));
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      if (future == null || !future.isDone()) {
        return ActionResult.RUNNING;
      }
      return finishFuture(future);
    }

    @Override
    public String description() {
      return "retreating to end center";
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.CRITICAL;
    }
  }

  private final class EatAction implements AutomationAction {
    private @Nullable ControlTask task;

    @Override
    public void start(AutomationController controller) {
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player == null || gameMode == null) {
        return;
      }

      var edibleSlot = AutomationInventory.findInventorySlot(bot, AutomationRequirements.FOOD);
      if (edibleSlot.isEmpty()) {
        return;
      }

      var slot = edibleSlot.getAsInt();
      if (slot == AutomationInventory.findInventorySlot(bot, AutomationRequirements.FOOD).orElse(-1)
        && AutomationRequirements.matches(AutomationRequirements.FOOD, player.getMainHandItem())) {
        task = ControlTask.sequence(
          "Automation eat",
          ControlPriority.CRITICAL,
          ControlTask.action(() -> gameMode.useItem(player, InteractionHand.MAIN_HAND))
        );
      } else {
        task = ControlTask.sequence(
          "Automation eat",
          ControlPriority.CRITICAL,
          ControlTask.action(() -> AutomationInventory.ensureHolding(bot, AutomationRequirements.FOOD)),
          ControlTask.waitMillis(50L),
          ControlTask.action(() -> gameMode.useItem(player, InteractionHand.MAIN_HAND))
        );
      }

      bot.botControl().replace(task);
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      return bot.botControl().hasActiveTask() ? ActionResult.RUNNING : ActionResult.SUCCEEDED;
    }

    @Override
    public String description() {
      return "eating";
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.CRITICAL;
    }
  }

  private final class FillBucketAction implements AutomationAction, PositionedAction {
    private final BlockPos position;
    private final String resultRequirementKey;
    private final String label;
    private @Nullable CompletableFuture<Void> future;
    private int stage;
    private long stageTick;

    private FillBucketAction(BlockPos position, String resultRequirementKey, String label) {
      this.position = position;
      this.resultRequirementKey = resultRequirementKey;
      this.label = label;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(position), 2), new PathConstraintImpl(bot));
      stage = 0;
      stageTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      if (AutomationInventory.countInventory(bot, resultRequirementKey) > 0) {
        return ActionResult.SUCCEEDED;
      }

      if (stage == 0) {
        if (future == null || !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (finishFuture(future) == ActionResult.FAILED) {
          worldMemory.markUnreachable(position);
          return ActionResult.FAILED;
        }
        if (!AutomationInventory.ensureHolding(bot, AutomationRequirements.BUCKET)) {
          return ActionResult.FAILED;
        }
        interactFluid(position);
        stage = 1;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (AutomationInventory.countInventory(bot, resultRequirementKey) > 0) {
        return ActionResult.SUCCEEDED;
      }

      return worldMemory.ticks() - stageTick > 40 ? ActionResult.FAILED : ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return label;
    }

    @Override
    public BlockPos position() {
      return position;
    }
  }

  private final class CastPortalAction implements AutomationAction, PositionedAction {
    private final String label;
    private @Nullable PortalBlueprint blueprint;
    private @Nullable BlockPos lavaSource;
    private @Nullable BlockPos waterSupport;
    private @Nullable BlockPos waterSource;
    private @Nullable BlockPos currentTarget;
    private @Nullable CompletableFuture<Void> future;
    private List<BlockPos> castTargets = List.of();
    private int stage;
    private int index;
    private long stageTick;

    private CastPortalAction(String label) {
      this.label = label;
    }

    @Override
    public void start(AutomationController controller) {
      var level = bot.minecraft().level;
      var player = bot.minecraft().player;
      blueprint = level != null && player != null ? findFreshPortalBlueprint(player, level).orElse(null) : null;
      lavaSource = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.LAVA)
        .map(AutomationWorldMemory.RememberedBlock::pos)
        .orElse(null);
      castTargets = blueprint != null ? blueprint.castTargets() : List.of();
      waterSupport = blueprint != null ? blueprint.waterSupport() : null;
      waterSource = waterSupport != null ? waterSupport.above() : null;
      stage = 0;
      index = 0;
      stageTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var level = bot.minecraft().level;
      if (level == null || blueprint == null || lavaSource == null || waterSupport == null || waterSource == null) {
        return ActionResult.FAILED;
      }

      if (findAnyPortal(level.dimension()).isPresent()) {
        return ActionResult.SUCCEEDED;
      }

      switch (stage) {
        case 0 -> {
          if (!level.getBlockState(waterSupport).isFaceSturdy(level, waterSupport, Direction.UP)) {
            var supportRequirement = temporarySupportRequirement();
            if (supportRequirement.isEmpty() || !placeBlockAt(supportRequirement.get(), waterSupport)) {
              return ActionResult.FAILED;
            }
            stageTick = worldMemory.ticks();
            stage = 1;
            return ActionResult.RUNNING;
          }
          stage = 2;
          return ActionResult.RUNNING;
        }
        case 1 -> {
          if (level.getBlockState(waterSupport).isFaceSturdy(level, waterSupport, Direction.UP)) {
            stage = 2;
            return ActionResult.RUNNING;
          }
          return worldMemory.ticks() - stageTick > 20 ? ActionResult.FAILED : ActionResult.RUNNING;
        }
        case 2 -> {
          future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(waterSupport), 2), new PathConstraintImpl(bot));
          stage = 3;
          return ActionResult.RUNNING;
        }
        case 3 -> {
          if (future == null || !future.isDone()) {
            return ActionResult.RUNNING;
          }
          if (finishFuture(future) == ActionResult.FAILED) {
            return ActionResult.FAILED;
          }
          var against = new BlockPlaceAgainstData(SFVec3i.fromInt(waterSupport), BlockFace.TOP);
          if (!useHeldItemAgainst(AutomationRequirements.WATER_BUCKET, against, ClipContext.Fluid.NONE)) {
            return ActionResult.FAILED;
          }
          stageTick = worldMemory.ticks();
          stage = 4;
          return ActionResult.RUNNING;
        }
        case 4 -> {
          if (level.getBlockState(waterSource).getBlock() == Blocks.WATER || worldMemory.ticks() - stageTick > 10) {
            stage = 5;
            return ActionResult.RUNNING;
          }
          return ActionResult.RUNNING;
        }
        case 5 -> {
          if (index >= castTargets.size()) {
            stage = 10;
            return ActionResult.RUNNING;
          }

          currentTarget = castTargets.get(index);
          if (level.getBlockState(currentTarget).getBlock() == Blocks.OBSIDIAN) {
            index++;
            return ActionResult.RUNNING;
          }

          if (AutomationInventory.countInventory(bot, AutomationRequirements.LAVA_BUCKET) <= 0) {
            future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(lavaSource), 2), new PathConstraintImpl(bot));
            stage = 6;
            return ActionResult.RUNNING;
          }

          future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(currentTarget), 2), new PathConstraintImpl(bot));
          stage = 8;
          return ActionResult.RUNNING;
        }
        case 6 -> {
          if (future == null || !future.isDone()) {
            return ActionResult.RUNNING;
          }
          if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, AutomationRequirements.BUCKET)) {
            return ActionResult.FAILED;
          }
          interactFluid(lavaSource);
          stageTick = worldMemory.ticks();
          stage = 7;
          return ActionResult.RUNNING;
        }
        case 7 -> {
          if (AutomationInventory.countInventory(bot, AutomationRequirements.LAVA_BUCKET) > 0) {
            future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(currentTarget), 2), new PathConstraintImpl(bot));
            stage = 8;
            return ActionResult.RUNNING;
          }
          return worldMemory.ticks() - stageTick > 20 ? ActionResult.FAILED : ActionResult.RUNNING;
        }
        case 8 -> {
          if (future == null || !future.isDone()) {
            return ActionResult.RUNNING;
          }
          if (finishFuture(future) == ActionResult.FAILED || currentTarget == null) {
            return ActionResult.FAILED;
          }
          var against = findPlaceAgainst(currentTarget);
          if (against.isEmpty() || !useHeldItemAgainst(AutomationRequirements.LAVA_BUCKET, against.get(), ClipContext.Fluid.NONE)) {
            return ActionResult.FAILED;
          }
          stageTick = worldMemory.ticks();
          stage = 9;
          return ActionResult.RUNNING;
        }
        case 9 -> {
          if (currentTarget == null) {
            return ActionResult.FAILED;
          }
          var block = level.getBlockState(currentTarget).getBlock();
          if (block == Blocks.OBSIDIAN) {
            index++;
            stage = 5;
            return ActionResult.RUNNING;
          }
          return worldMemory.ticks() - stageTick > 30 ? ActionResult.FAILED : ActionResult.RUNNING;
        }
        case 10 -> {
          future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(waterSource), 2), new PathConstraintImpl(bot));
          stage = 11;
          return ActionResult.RUNNING;
        }
        case 11 -> {
          if (future == null || !future.isDone()) {
            return ActionResult.RUNNING;
          }
          if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, AutomationRequirements.BUCKET)) {
            return ActionResult.FAILED;
          }
          interactFluid(waterSource);
          stageTick = worldMemory.ticks();
          stage = 12;
          return ActionResult.RUNNING;
        }
        case 12 -> {
          if (level.getBlockState(waterSource).getBlock() != Blocks.WATER && worldMemory.ticks() - stageTick > 10) {
            future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(blueprint.interior().getFirst()), 2), new PathConstraintImpl(bot));
            stage = 13;
            return ActionResult.RUNNING;
          }
          return ActionResult.RUNNING;
        }
        case 13 -> {
          if (future == null || !future.isDone()) {
            return ActionResult.RUNNING;
          }
          if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, AutomationRequirements.FLINT_AND_STEEL)) {
            return ActionResult.FAILED;
          }
          interactBlock(blueprint.interior().getFirst());
          stageTick = worldMemory.ticks();
          stage = 14;
          return ActionResult.RUNNING;
        }
        case 14 -> {
          return blueprint.hasActivePortal(level) || findAnyPortal(level.dimension()).isPresent()
            ? ActionResult.SUCCEEDED
            : worldMemory.ticks() - stageTick > 40 ? ActionResult.FAILED : ActionResult.RUNNING;
        }
        default -> {
          return ActionResult.FAILED;
        }
      }
    }

    @Override
    public String description() {
      return label;
    }

    @Override
    public BlockPos position() {
      return blueprint != null ? blueprint.base() : BlockPos.ZERO;
    }
  }

  private final class BuildPortalAction implements AutomationAction, PositionedAction {
    private final String label;
    private @Nullable PortalBlueprint blueprint;
    private @Nullable CompletableFuture<Void> future;
    private @Nullable BlockPos currentTarget;
    private int index;
    private int stage;
    private long stageTick;

    private BuildPortalAction(String label) {
      this.label = label;
    }

    @Override
    public void start(AutomationController controller) {
      blueprint = findPortalBlueprint().orElse(null);
      index = 0;
      stage = 0;
      stageTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var level = bot.minecraft().level;
      if (level == null || blueprint == null) {
        return ActionResult.FAILED;
      }

      if (findAnyPortal(level.dimension()).isPresent()) {
        return ActionResult.SUCCEEDED;
      }

      var missingFrameBlocks = blueprint.missingFrameBlocks(level);
      if (index >= missingFrameBlocks.size()) {
        return ignitePortal(level);
      }

      if (stage == 0) {
        currentTarget = missingFrameBlocks.get(index);
        future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(currentTarget), 2), new PathConstraintImpl(bot));
        stage = 1;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (stage == 1) {
        if (future == null || !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (finishFuture(future) == ActionResult.FAILED || currentTarget == null) {
          worldMemory.markUnreachable(blueprint.base());
          return ActionResult.FAILED;
        }
        if (level.getBlockState(currentTarget).getBlock() == Blocks.OBSIDIAN) {
          index++;
          stage = 0;
          return ActionResult.RUNNING;
        }
        if (!placeBlockAt(AutomationRequirements.OBSIDIAN, currentTarget)) {
          return ActionResult.FAILED;
        }
        stage = 2;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (stage == 2) {
        if (currentTarget != null && level.getBlockState(currentTarget).getBlock() == Blocks.OBSIDIAN) {
          index++;
          stage = 0;
          return ActionResult.RUNNING;
        }
        if (worldMemory.ticks() - stageTick > 20) {
          return ActionResult.FAILED;
        }
      }

      return ActionResult.RUNNING;
    }

    private ActionResult ignitePortal(Level level) {
      if (blueprint == null) {
        return ActionResult.FAILED;
      }

      var ignitionTarget = blueprint.interior().getFirst();
      if (stage < 3) {
        future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(ignitionTarget), 2), new PathConstraintImpl(bot));
        stage = 3;
        return ActionResult.RUNNING;
      }

      if (stage == 3) {
        if (future == null || !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, AutomationRequirements.FLINT_AND_STEEL)) {
          return ActionResult.FAILED;
        }
        interactBlock(ignitionTarget);
        stage = 4;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (blueprint.hasActivePortal(level)) {
        return ActionResult.SUCCEEDED;
      }

      return worldMemory.ticks() - stageTick > 40 ? ActionResult.FAILED : ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return label;
    }

    @Override
    public BlockPos position() {
      return blueprint != null ? blueprint.base() : BlockPos.ZERO;
    }
  }

  private final class InspectContainerAction implements AutomationAction, PositionedAction {
    private final BlockPos position;
    private int stage;
    private @Nullable CompletableFuture<Void> future;
    private long stageTick;

    private InspectContainerAction(BlockPos position) {
      this.position = position;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(position), 2), new PathConstraintImpl(bot));
      stage = 0;
      stageTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var player = bot.minecraft().player;
      if (player == null) {
        return ActionResult.FAILED;
      }

      if (stage == 0) {
        if (future == null || !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (finishFuture(future) == ActionResult.FAILED) {
          worldMemory.markUnreachable(position);
          return ActionResult.FAILED;
        }
        interactBlock(position);
        stage = 1;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (stage == 1) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
          worldMemory.rememberContainerContents(position, player.containerMenu);
          player.closeContainer();
          return ActionResult.SUCCEEDED;
        }
        if (worldMemory.ticks() - stageTick > 40) {
          worldMemory.markUnreachable(position);
          return ActionResult.FAILED;
        }
      }

      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "inspecting container";
    }

    @Override
    public BlockPos position() {
      return position;
    }
  }

  private final class LootContainerAction implements AutomationAction, PositionedAction {
    private final BlockPos position;
    private final String requirementKey;
    private final int neededCount;
    private int stage;
    private @Nullable CompletableFuture<Void> future;
    private long stageTick;

    private LootContainerAction(BlockPos position, String requirementKey, int neededCount) {
      this.position = position;
      this.requirementKey = requirementKey;
      this.neededCount = neededCount;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(position), 2), new PathConstraintImpl(bot));
      stage = 0;
      stageTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var player = bot.minecraft().player;
      if (player == null) {
        return ActionResult.FAILED;
      }

      if (AutomationInventory.countInventory(bot, requirementKey) >= neededCount) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
          worldMemory.rememberContainerContents(position, player.containerMenu);
          player.closeContainer();
        }
        return ActionResult.SUCCEEDED;
      }

      if (stage == 0) {
        if (future == null || !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (finishFuture(future) == ActionResult.FAILED) {
          worldMemory.markUnreachable(position);
          return ActionResult.FAILED;
        }
        interactBlock(position);
        stage = 1;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (stage == 1) {
        if (player.containerMenu instanceof InventoryMenu) {
          if (worldMemory.ticks() - stageTick > 40) {
            worldMemory.markUnreachable(position);
            return ActionResult.FAILED;
          }
          return ActionResult.RUNNING;
        }

        var menu = player.containerMenu;
        for (int slot = 0; slot < AutomationWorldMemory.containerSlotCount(menu); slot++) {
          if (AutomationRequirements.matches(requirementKey, menu.getSlot(slot).getItem())) {
            AutomationInventory.click(menu, slot, 0, ContainerInput.QUICK_MOVE, player, bot.minecraft().gameMode);
          }
        }
        worldMemory.rememberContainerContents(position, menu);
        player.closeContainer();
        return AutomationInventory.countInventory(bot, requirementKey) >= neededCount ? ActionResult.SUCCEEDED : ActionResult.FAILED;
      }

      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "looting " + AutomationRequirements.describe(requirementKey);
    }

    @Override
    public BlockPos position() {
      return position;
    }
  }

  private final class CraftAction implements AutomationAction {
    private final AutomationRecipes.CraftingRecipeDefinition recipe;
    private final int targetCount;
    private int stage;
    private @Nullable BlockPos stationPos;
    private long stageTick;

    private CraftAction(AutomationRecipes.CraftingRecipeDefinition recipe, int targetCount) {
      this.recipe = recipe;
      this.targetCount = targetCount;
    }

    @Override
    public void start(AutomationController controller) {
      stage = 0;
      stageTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var player = bot.minecraft().player;
      if (player == null) {
        return ActionResult.FAILED;
      }

      if (AutomationInventory.countInventory(bot, recipe.outputKey()) >= targetCount) {
        if (!(player.containerMenu instanceof InventoryMenu) && recipe.station() != AutomationRecipes.CraftingStation.FURNACE) {
          player.closeContainer();
        }
        return ActionResult.SUCCEEDED;
      }

      if (bot.botControl().hasActiveTask()) {
        return ActionResult.RUNNING;
      }

      var menu = ensureRecipeMenu(player);
      if (menu == null) {
        return ActionResult.RUNNING;
      }

      var resultSlot = 0;
      var recipeSlots = recipe.station() == AutomationRecipes.CraftingStation.CRAFTING_TABLE
        ? IntStream.rangeClosed(1, 9)
        : IntStream.rangeClosed(1, 4);
      AutomationInventory.clearCraftingSlots(menu, recipeSlots, player);
      for (var ingredient : recipe.ingredients()) {
        if (!AutomationInventory.moveOneIngredient(menu, ingredient.slot(), ingredient.requirementKey(), player)) {
          return ActionResult.FAILED;
        }
      }

      if (!AutomationRequirements.matches(recipe.outputKey(), menu.getSlot(resultSlot).getItem())) {
        return ActionResult.FAILED;
      }

      AutomationInventory.click(menu, resultSlot, 0, ContainerInput.QUICK_MOVE, player, bot.minecraft().gameMode);
      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "crafting " + AutomationRequirements.describe(recipe.outputKey());
    }

    private @Nullable AbstractContainerMenu ensureRecipeMenu(LocalPlayer player) {
      if (recipe.station() == AutomationRecipes.CraftingStation.INVENTORY) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
          player.closeContainer();
        }
        player.sendOpenInventory();
        return player.inventoryMenu;
      }

      if (recipe.station() == AutomationRecipes.CraftingStation.CRAFTING_TABLE && player.containerMenu instanceof CraftingMenu) {
        return player.containerMenu;
      }

      if (stationPos == null) {
        var remembered = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.CRAFTING_TABLE);
        if (remembered.isPresent()) {
          stationPos = remembered.get().pos();
        } else if (AutomationInventory.countInventory(bot, AutomationRequirements.CRAFTING_TABLE) > 0) {
          stationPos = findPlacementPos(Blocks.CRAFTING_TABLE);
          placeBlock(AutomationRequirements.CRAFTING_TABLE, stationPos);
          return null;
        } else {
          return null;
        }
      }

      interactBlock(stationPos);
      if (player.containerMenu instanceof CraftingMenu) {
        return player.containerMenu;
      }

      if (worldMemory.ticks() - stageTick > 80) {
        worldMemory.markUnreachable(stationPos);
        return null;
      }
      return null;
    }
  }

  private final class SmeltAction implements AutomationAction {
    private final AutomationRecipes.SmeltingRecipeDefinition recipe;
    private final int targetCount;
    private @Nullable BlockPos furnacePos;
    private long lastAttemptTick;

    private SmeltAction(AutomationRecipes.SmeltingRecipeDefinition recipe, int targetCount) {
      this.recipe = recipe;
      this.targetCount = targetCount;
    }

    @Override
    public void start(AutomationController controller) {
      lastAttemptTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var player = bot.minecraft().player;
      if (player == null) {
        return ActionResult.FAILED;
      }

      if (AutomationInventory.countInventory(bot, recipe.outputKey()) >= targetCount) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
          player.closeContainer();
        }
        return ActionResult.SUCCEEDED;
      }

      if (!(player.containerMenu instanceof AbstractFurnaceMenu furnaceMenu)) {
        if (furnacePos == null) {
          var remembered = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.FURNACE);
          if (remembered.isPresent()) {
            furnacePos = remembered.get().pos();
          } else if (AutomationInventory.countInventory(bot, AutomationRequirements.FURNACE) > 0) {
            furnacePos = findPlacementPos(Blocks.FURNACE);
            placeBlock(AutomationRequirements.FURNACE, furnacePos);
            return ActionResult.RUNNING;
          } else {
            return ActionResult.FAILED;
          }
        }

        interactBlock(furnacePos);
        return ActionResult.RUNNING;
      }

      if (!furnaceMenu.getSlot(2).getItem().isEmpty()
        && AutomationRequirements.matches(recipe.outputKey(), furnaceMenu.getSlot(2).getItem())) {
        AutomationInventory.click(furnaceMenu, 2, 0, ContainerInput.QUICK_MOVE, player, bot.minecraft().gameMode);
        lastAttemptTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (furnaceMenu.getSlot(0).getItem().isEmpty()) {
        var inputKey = recipe.inputKeys().stream()
          .filter(key -> AutomationInventory.countInventory(bot, key) > 0)
          .findFirst()
          .orElse(null);
        if (inputKey == null) {
          return ActionResult.FAILED;
        }
        if (!AutomationInventory.moveOneIngredient(furnaceMenu, 0, inputKey, player)) {
          return ActionResult.FAILED;
        }
        lastAttemptTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (furnaceMenu.getSlot(1).getItem().isEmpty()) {
        if (!AutomationInventory.moveOneIngredient(furnaceMenu, 1, AutomationRequirements.FUEL, player)) {
          return ActionResult.FAILED;
        }
        lastAttemptTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (worldMemory.ticks() - lastAttemptTick > 200) {
        return ActionResult.FAILED;
      }

      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "smelting " + AutomationRequirements.describe(recipe.outputKey());
    }
  }

  private final class BarterAction implements AutomationAction {
    private final UUID piglinId;
    private int stage;
    private @Nullable CompletableFuture<Void> future;
    private long stageTick;

    private BarterAction(UUID piglinId) {
      this.piglinId = piglinId;
    }

    @Override
    public void start(AutomationController controller) {
      stage = 0;
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var player = bot.minecraft().player;
      var entity = findEntity(piglinId);
      if (player == null || entity.isEmpty()) {
        return ActionResult.FAILED;
      }

      if (stage == 0) {
        future = PathExecutor.executePathfinding(
          bot,
          (DynamicGoalScorer) () -> new CloseToPosGoal(SFVec3i.fromInt(entity.get().blockPosition()), 3),
          new PathConstraintImpl(bot)
        );
        stage = 1;
        return ActionResult.RUNNING;
      }

      if (stage == 1) {
        if (future == null || !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, AutomationRequirements.GOLD_INGOT)) {
          return ActionResult.FAILED;
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, entity.get().getEyePosition());
        if (bot.minecraft().gameMode.interact(player, entity.get(), new EntityHitResult(entity.get()), InteractionHand.MAIN_HAND)
          instanceof InteractionResult.Success success
          && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
          player.swing(InteractionHand.MAIN_HAND);
        }
        stage = 2;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      if (stage == 2 && worldMemory.ticks() - stageTick > 80) {
        return ActionResult.SUCCEEDED;
      }

      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "bartering with piglin";
    }
  }

  private final class KillEntityAction implements AutomationAction {
    private final UUID entityId;
    private final String label;
    private int stage;
    private @Nullable CompletableFuture<Void> future;

    private KillEntityAction(UUID entityId, String label) {
      this.entityId = entityId;
      this.label = label;
    }

    @Override
    public void start(AutomationController controller) {
      stage = 0;
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var entity = findEntity(entityId);
      if (entity.isEmpty() || !entity.get().isAlive()) {
        return ActionResult.SUCCEEDED;
      }

      if (stage == 0) {
        var followDistance = shouldUseRanged(entity.get()) ? 14 : 2;
        future = PathExecutor.executePathfinding(
          bot,
          (DynamicGoalScorer) () -> new CloseToPosGoal(SFVec3i.fromInt(entity.get().blockPosition()), followDistance),
          new PathConstraintImpl(bot)
        );
        stage = 1;
        return ActionResult.RUNNING;
      }

      if (stage == 1) {
        if (future != null && !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (future != null && finishFuture(future) == ActionResult.FAILED) {
          stage = 0;
          return ActionResult.RUNNING;
        }
        bot.botControl().replace(shouldUseRanged(entity.get())
          ? new RangedAttackTask(entityId, entity.get().getType() == EntityType.ENDER_DRAGON ? 240 : 160)
          : new AttackEntityTask(entityId, 120));
        stage = 2;
        return ActionResult.RUNNING;
      }

      if (stage == 2) {
        if (entity.isEmpty() || !entity.get().isAlive()) {
          return ActionResult.SUCCEEDED;
        }
        if (!bot.botControl().hasActiveTask()) {
          stage = 0;
        }
      }

      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return label;
    }

    private boolean shouldUseRanged(Entity entity) {
      if (!AutomationInventory.hasRangedWeapon(bot)) {
        return false;
      }

      return entity.getType() == EntityType.ENDER_DRAGON
        || entity.getType() == EntityType.END_CRYSTAL
        || entity.position().distanceTo(bot.minecraft().player.position()) > 4.0
        || Math.abs(entity.getY() - bot.minecraft().player.getY()) > 2.0;
    }
  }

  private final class ThrowEyeAction implements AutomationAction {
    private boolean used;

    @Override
    public void start(AutomationController controller) {
      used = false;
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player == null || gameMode == null || !AutomationInventory.ensureHolding(bot, AutomationRequirements.ENDER_EYE)) {
        return ActionResult.FAILED;
      }

      if (!used) {
        gameMode.useItem(player, InteractionHand.MAIN_HAND);
        used = true;
        lastEyeThrowTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      var directionTarget = findEyeDirectionTarget(player);
      if (directionTarget.isPresent()) {
        lastEyeDirectionTarget = directionTarget.get();
        team().reportEyeSample(bot, player.position(), directionTarget.get().subtract(player.position()));
        return ActionResult.SUCCEEDED;
      }

      if (worldMemory.ticks() - lastEyeThrowTick > 20) {
        return ActionResult.SUCCEEDED;
      }

      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "throwing eye of ender";
    }
  }

  private final class FillPortalFrameAction implements AutomationAction, PositionedAction {
    private final BlockPos framePos;
    private int stage;
    private @Nullable CompletableFuture<Void> future;
    private long stageTick;

    private FillPortalFrameAction(BlockPos framePos) {
      this.framePos = framePos;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(framePos), 2), new PathConstraintImpl(bot));
      stage = 0;
      stageTick = worldMemory.ticks();
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      if (future == null || !future.isDone()) {
        return ActionResult.RUNNING;
      }

      if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, AutomationRequirements.ENDER_EYE)) {
        return ActionResult.FAILED;
      }

      if (stage == 0) {
        interactBlock(framePos);
        stage = 1;
        stageTick = worldMemory.ticks();
        return ActionResult.RUNNING;
      }

      var state = bot.minecraft().level.getBlockState(framePos);
      if (state.getBlock() == Blocks.END_PORTAL_FRAME && hasEye(state)) {
        return ActionResult.SUCCEEDED;
      }
      if (bot.minecraft().level.getBlockState(framePos.below()).getBlock() == Blocks.END_PORTAL) {
        return ActionResult.SUCCEEDED;
      }
      return worldMemory.ticks() - stageTick > 40 ? ActionResult.FAILED : ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "filling portal frame";
    }

    @Override
    public BlockPos position() {
      return framePos;
    }
  }

  private final class UsePortalAction implements AutomationAction {
    private final Block portalBlock;
    private final ResourceKey<Level> targetDimension;
    private int stage;
    private @Nullable CompletableFuture<Void> future;
    private @Nullable BlockPos portalPos;

    private UsePortalAction(Block portalBlock, ResourceKey<Level> targetDimension) {
      this.portalBlock = portalBlock;
      this.targetDimension = targetDimension;
    }

    @Override
    public void start(AutomationController controller) {
      stage = 0;
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      var level = bot.minecraft().level;
      if (level.dimension() == targetDimension) {
        return ActionResult.SUCCEEDED;
      }

      if (portalPos == null) {
        portalPos = (portalBlock == Blocks.NETHER_PORTAL ? findAnyPortal(level.dimension()) : worldMemory.findNearestBlock(bot, state -> state.getBlock() == portalBlock)
          .map(AutomationWorldMemory.RememberedBlock::pos))
          .orElse(null);
        if (portalPos == null) {
          return ActionResult.FAILED;
        }
      }

      if (stage == 0) {
        future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(portalPos), 1), new PathConstraintImpl(bot));
        stage = 1;
        return ActionResult.RUNNING;
      }

      if (stage == 1) {
        if (future == null || !future.isDone()) {
          return ActionResult.RUNNING;
        }
        if (finishFuture(future) == ActionResult.FAILED) {
          return ActionResult.FAILED;
        }
        bot.botControl().replace(new WalkToPointTask(portalPos.getCenter(), 120));
        stage = 2;
        return ActionResult.RUNNING;
      }

      if (stage == 2 && level.dimension() == targetDimension) {
        return ActionResult.SUCCEEDED;
      }

      return ActionResult.RUNNING;
    }

    @Override
    public String description() {
      return "using portal";
    }
  }

  private ActionResult finishFuture(CompletableFuture<Void> future) {
    try {
      future.join();
      return ActionResult.SUCCEEDED;
    } catch (Throwable t) {
      log.debug("Automation path/action future failed", t);
      return ActionResult.FAILED;
    }
  }

  private void interactBlock(BlockPos pos) {
    interactBlock(pos, ClipContext.Fluid.NONE);
  }

  private void interactFluid(BlockPos pos) {
    interactBlock(pos, ClipContext.Fluid.SOURCE_ONLY);
  }

  private void interactBlock(BlockPos pos, ClipContext.Fluid fluidMode) {
    var player = bot.minecraft().player;
    var gameMode = bot.minecraft().gameMode;
    if (player == null || gameMode == null) {
      return;
    }

    var target = pos.getCenter();
    player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
    if (gameMode.useItemOn(player, InteractionHand.MAIN_HAND, player.level().clipIncludingBorder(new ClipContext(
      player.getEyePosition(),
      target,
      ClipContext.Block.COLLIDER,
      fluidMode,
      player
    ))) instanceof InteractionResult.Success success
      && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
      player.swing(InteractionHand.MAIN_HAND);
    }
  }

  private void placeBlock(String requirementKey, BlockPos placePos) {
    placeBlockAt(requirementKey, placePos);
  }

  private boolean hasEye(BlockState state) {
    return state.hasProperty(EndPortalFrameBlock.HAS_EYE) && state.getValue(EndPortalFrameBlock.HAS_EYE);
  }

  private record PortalBlueprint(BlockPos base,
                                 Direction.Axis axis,
                                 List<BlockPos> requiredFrame,
                                 List<BlockPos> optionalCorners,
                                 List<BlockPos> interior,
                                 int existingRequiredFrameBlocks,
                                 int missingRequiredFrameBlocks) {
    private List<BlockPos> missingFrameBlocks(Level level) {
      return requiredFrame.stream()
        .filter(pos -> level.getBlockState(pos).getBlock() != Blocks.OBSIDIAN)
        .sorted(Comparator.comparingInt(Vec3i::getY)
          .thenComparingInt(Vec3i::getX)
          .thenComparingInt(pos -> pos.getZ()))
        .toList();
    }

    private boolean hasActivePortal(Level level) {
      return interior.stream().anyMatch(pos -> level.getBlockState(pos).getBlock() == Blocks.NETHER_PORTAL);
    }

    private List<BlockPos> castTargets() {
      var targets = new ArrayList<BlockPos>(12);
      for (int width = 0; width <= 3; width++) {
        targets.add(axis == Direction.Axis.X ? base.offset(width, 0, 0).immutable() : base.offset(0, 0, width).immutable());
      }
      for (int height = 1; height <= 3; height++) {
        targets.add(axis == Direction.Axis.X ? base.offset(0, height, 0).immutable() : base.offset(0, height, 0).immutable());
        targets.add(axis == Direction.Axis.X ? base.offset(3, height, 0).immutable() : base.offset(0, height, 3).immutable());
      }
      for (int width = 1; width <= 2; width++) {
        targets.add(axis == Direction.Axis.X ? base.offset(width, 4, 0).immutable() : base.offset(0, 4, width).immutable());
      }
      return List.copyOf(targets);
    }

    private BlockPos waterSupport() {
      return base.offset(1, 3, 1).immutable();
    }

    private int score() {
      return existingRequiredFrameBlocks * 10 - missingRequiredFrameBlocks;
    }
  }

  private static final class AttackEntityTask implements ControlTask {
    private final UUID entityId;
    private final int maxTicks;
    private int ticks;
    private boolean done;

    private AttackEntityTask(UUID entityId, int maxTicks) {
      this.entityId = entityId;
      this.maxTicks = maxTicks;
    }

    @Override
    public void tick() {
      var bot = BotConnection.current();
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player == null || gameMode == null) {
        done = true;
        return;
      }

      Entity target = null;
      for (var entity : bot.minecraft().level.entitiesForRendering()) {
        if (entity.getUUID().equals(entityId)) {
          target = entity;
          break;
        }
      }

      if (target == null || !target.isAlive()) {
        done = true;
        return;
      }

      var visiblePoint = Optional.ofNullable(KillAura.getEntityVisiblePoint(bot, target)).orElse(target.getEyePosition());
      player.lookAt(EntityAnchorArgument.Anchor.EYES, visiblePoint);
      var distance = visiblePoint.distanceTo(player.getEyePosition());
      if (distance > 5.0) {
        done = true;
        return;
      }

      if (player.getAttackStrengthScale(0) >= 1F && distance <= 3.25) {
        gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
      }

      if (++ticks >= maxTicks) {
        done = true;
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.HIGH;
    }

    @Override
    public String description() {
      return "Automation attack";
    }
  }

  private static final class RangedAttackTask implements ControlTask {
    private final UUID entityId;
    private final int maxTicks;
    private int ticks;
    private int chargeTicks;
    private boolean strafeRight = true;
    private boolean done;

    private RangedAttackTask(UUID entityId, int maxTicks) {
      this.entityId = entityId;
      this.maxTicks = maxTicks;
    }

    @Override
    public void tick() {
      var bot = BotConnection.current();
      var player = bot.minecraft().player;
      var gameMode = bot.minecraft().gameMode;
      if (player == null || gameMode == null || !AutomationInventory.ensureHolding(bot, AutomationRequirements.BOW)) {
        done = true;
        return;
      }

      Entity target = null;
      for (var entity : bot.minecraft().level.entitiesForRendering()) {
        if (entity.getUUID().equals(entityId)) {
          target = entity;
          break;
        }
      }

      if (target == null || !target.isAlive() || AutomationInventory.countInventory(bot, AutomationRequirements.ARROW) <= 0) {
        if (player.isUsingItem()) {
          player.releaseUsingItem();
        }
        done = true;
        return;
      }

      var visiblePoint = Optional.ofNullable(KillAura.getEntityVisiblePoint(bot, target)).orElse(target.getEyePosition());
      var distance = visiblePoint.distanceTo(player.getEyePosition());
      player.lookAt(EntityAnchorArgument.Anchor.EYES, visiblePoint);

      bot.controlState().resetAll();
      if (distance > 18.0) {
        bot.controlState().up(true);
        bot.controlState().sprint(true);
      } else if (distance < 8.0) {
        bot.controlState().down(true);
      } else {
        if ((ticks / 20) % 2 == 0) {
          strafeRight = !strafeRight;
        }
        if (strafeRight) {
          bot.controlState().right(true);
        } else {
          bot.controlState().left(true);
        }
      }

      if (!player.isUsingItem()) {
        gameMode.useItem(player, InteractionHand.MAIN_HAND);
        chargeTicks = 0;
      } else if (++chargeTicks >= (target.getType() == EntityType.END_CRYSTAL ? 10 : 20)) {
        player.releaseUsingItem();
        chargeTicks = 0;
      }

      if (++ticks >= maxTicks) {
        if (player.isUsingItem()) {
          player.releaseUsingItem();
        }
        bot.controlState().resetAll();
        done = true;
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.HIGH;
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      BotConnection.currentOptional().ifPresent(bot -> {
        var player = bot.minecraft().player;
        if (player != null && player.isUsingItem()) {
          player.releaseUsingItem();
        }
        bot.controlState().resetAll();
      });
    }
  }

  private static final class WalkToPointTask implements ControlTask {
    private final Vec3 target;
    private final int maxTicks;
    private int ticks;
    private boolean done;

    private WalkToPointTask(Vec3 target, int maxTicks) {
      this.target = target;
      this.maxTicks = maxTicks;
    }

    @Override
    public void tick() {
      var bot = BotConnection.current();
      var player = bot.minecraft().player;
      if (player == null) {
        done = true;
        return;
      }

      var delta = target.subtract(player.position());
      if (delta.lengthSqr() < 1.0) {
        done = true;
        bot.controlState().resetAll();
        return;
      }

      player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
      bot.controlState().resetAll();
      bot.controlState().up(true);
      bot.controlState().sprint(true);
      if (++ticks >= maxTicks) {
        done = true;
        bot.controlState().resetAll();
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.HIGH;
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      BotConnection.currentOptional().ifPresent(bot -> bot.controlState().resetAll());
    }
  }
}
