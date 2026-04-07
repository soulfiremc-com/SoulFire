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
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.AwayFromPosGoal;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.goals.DynamicGoalScorer;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.pathfinding.goals.PosGoal;
import com.soulfiremc.server.pathfinding.goals.XZGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import com.soulfiremc.server.plugins.KillAura;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public final class AutomationController {
  private static final int REQUIRED_FOOD = 8;
  private static final int REQUIRED_BLAZE_RODS = 6;
  private static final int REQUIRED_ENDER_PEARLS = 12;
  private static final int REQUIRED_EYES = 12;

  private final BotConnection bot;
  private final AutomationWorldMemory worldMemory = new AutomationWorldMemory();
  private final Deque<RequirementGoal> requirements = new ArrayDeque<>();
  private @Nullable AutomationAction currentAction;
  private GoalMode mode = GoalMode.IDLE;
  private BeatPhase beatPhase = BeatPhase.PREPARE_OVERWORLD;
  private long lastProgressTick;
  private long lastEyeThrowTick;
  private @Nullable Vec3 lastEyeDirectionTarget;
  private String status = "idle";

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

    if (rememberOpenContainer(player)) {
      status = "inspecting container";
    }

    if (handleSurvival(player)) {
      return;
    }

    if (currentAction != null) {
      var result = currentAction.poll(this);
      if (result == ActionResult.RUNNING) {
        status = currentAction.description();
        return;
      }

      if (result == ActionResult.SUCCEEDED) {
        lastProgressTick = worldMemory.ticks();
      }

      currentAction = null;
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

  public void startAcquire(String requirement, int count) {
    stop();
    mode = GoalMode.ACQUIRE;
    pushRequirement(new RequirementGoal(AutomationRequirements.normalize(requirement), count, "requested"));
    status = "acquiring " + requirement;
  }

  public void startBeatMinecraft() {
    stop();
    mode = GoalMode.BEAT;
    beatPhase = BeatPhase.PREPARE_OVERWORLD;
    status = "preparing overworld";
  }

  public void stop() {
    requirements.clear();
    currentAction = null;
    lastEyeDirectionTarget = null;
    mode = GoalMode.IDLE;
    beatPhase = BeatPhase.PREPARE_OVERWORLD;
    bot.botControl().stopAll();
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
    if (currentAction != null) {
      parts.add("action=" + currentAction.description());
    }
    return String.join(", ", parts);
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

    var hostile = worldMemory.findNearestHostile(bot);
    if (hostile.isPresent()
      && hostile.get().position().distanceTo(player.position()) < 8
      && player.getHealth() <= 8) {
      interruptFor(new FleeAction(hostile.get().pos()));
      return true;
    }

    if (player.getFoodData().getFoodLevel() <= 12 && AutomationInventory.isFoodInInventory(bot)) {
      interruptFor(new EatAction());
      return true;
    }

    if ((player.isInLava() || player.isOnFire() || player.getAirSupply() < 60) && hostile.isPresent()) {
      interruptFor(new FleeAction(hostile.get().pos()));
      return true;
    }

    return false;
  }

  private void interruptFor(AutomationAction action) {
    if (currentAction != null && currentAction.priority() == ControlPriority.CRITICAL) {
      return;
    }

    bot.botControl().stopAll();
    currentAction = null;
    startAction(action);
  }

  private void updateBeatPlan(Level level) {
    switch (beatPhase) {
      case PREPARE_OVERWORLD -> {
        if (requirements.isEmpty()) {
          pushRequirements(List.of(
            new RequirementGoal(AutomationRequirements.FOOD, REQUIRED_FOOD, "survival food"),
            new RequirementGoal("item:minecraft:crafting_table", 1, "crafting"),
            new RequirementGoal("item:minecraft:stone_pickaxe", 1, "tools"),
            new RequirementGoal("item:minecraft:furnace", 1, "smelting"),
            new RequirementGoal("item:minecraft:iron_ingot", 3, "progression"),
            new RequirementGoal("item:minecraft:flint_and_steel", 1, "portal ignition")
          ));
        }
        if (requirements.isEmpty()) {
          beatPhase = BeatPhase.ENTER_NETHER;
        }
      }
      case ENTER_NETHER -> {
        if (level.dimension() == Level.NETHER) {
          beatPhase = BeatPhase.NETHER_COLLECTION;
        }
      }
      case NETHER_COLLECTION -> {
        if (level.dimension() != Level.NETHER) {
          beatPhase = BeatPhase.ENTER_NETHER;
          return;
        }

        if (requirements.isEmpty()) {
          if (AutomationInventory.countInventory(bot, "item:minecraft:blaze_rod") < REQUIRED_BLAZE_RODS) {
            pushRequirement(new RequirementGoal("item:minecraft:blaze_rod", REQUIRED_BLAZE_RODS, "blaze rods"));
          } else if (AutomationInventory.countInventory(bot, "item:minecraft:ender_pearl") < REQUIRED_ENDER_PEARLS) {
            pushRequirement(new RequirementGoal("item:minecraft:ender_pearl", REQUIRED_ENDER_PEARLS, "ender pearls"));
          } else if (AutomationInventory.countInventory(bot, "item:minecraft:ender_eye") < REQUIRED_EYES) {
            pushRequirement(new RequirementGoal("item:minecraft:ender_eye", REQUIRED_EYES, "eyes of ender"));
          } else {
            beatPhase = BeatPhase.RETURN_TO_OVERWORLD;
          }
        }
      }
      case RETURN_TO_OVERWORLD -> {
        if (level.dimension() == Level.OVERWORLD) {
          beatPhase = BeatPhase.STRONGHOLD_SEARCH;
        }
      }
      case STRONGHOLD_SEARCH -> {
        if (level.dimension() == Level.END) {
          beatPhase = BeatPhase.END_FIGHT;
        } else if (hasRememberedBlock(state -> state.getBlock() == Blocks.END_PORTAL_FRAME || state.getBlock() == Blocks.END_PORTAL)) {
          beatPhase = BeatPhase.ACTIVATE_PORTAL;
        }
      }
      case ACTIVATE_PORTAL -> {
        if (level.dimension() == Level.END) {
          beatPhase = BeatPhase.END_FIGHT;
        }
      }
      case END_FIGHT -> {
        var dragonVisible = worldMemory.findNearestEntity(bot, entity -> entity.type() == EntityType.ENDER_DRAGON);
        if (level.dimension() == Level.END && dragonVisible.isEmpty() && worldMemory.ticks() - lastProgressTick > 200) {
          beatPhase = BeatPhase.COMPLETE;
        }
      }
      case COMPLETE -> {}
    }
  }

  private void driveBeatPhase(Level level, LocalPlayer player) {
    switch (beatPhase) {
      case ENTER_NETHER -> {
        if (worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.NETHER_PORTAL).isPresent()) {
          startAction(new UsePortalAction(Blocks.NETHER_PORTAL, Level.NETHER));
        } else {
          startAction(new ExploreAction("searching for nether portal"));
        }
      }
      case RETURN_TO_OVERWORLD -> {
        if (worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.NETHER_PORTAL).isPresent()) {
          startAction(new UsePortalAction(Blocks.NETHER_PORTAL, Level.OVERWORLD));
        } else {
          startAction(new ExploreAction("searching for return portal"));
        }
      }
      case STRONGHOLD_SEARCH -> {
        if (AutomationInventory.countInventory(bot, "item:minecraft:ender_eye") <= 0) {
          pushRequirement(new RequirementGoal("item:minecraft:ender_eye", 1, "stronghold search"));
          return;
        }

        var endPortal = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.END_PORTAL);
        if (endPortal.isPresent()) {
          beatPhase = BeatPhase.ACTIVATE_PORTAL;
          return;
        }

        if (lastEyeDirectionTarget != null && player.position().distanceTo(lastEyeDirectionTarget) > 10) {
          startAction(new MoveToPositionAction(lastEyeDirectionTarget, "following eye of ender"));
        } else if (worldMemory.ticks() - lastEyeThrowTick > 120) {
          startAction(new ThrowEyeAction());
        } else {
          startAction(new ExploreAction("searching for stronghold"));
        }
      }
      case ACTIVATE_PORTAL -> {
        var activatedPortal = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.END_PORTAL);
        if (activatedPortal.isPresent()) {
          startAction(new UsePortalAction(Blocks.END_PORTAL, Level.END));
          return;
        }

        if (AutomationInventory.countInventory(bot, "item:minecraft:ender_eye") <= 0) {
          pushRequirement(new RequirementGoal("item:minecraft:ender_eye", 1, "activate portal"));
          return;
        }

        var frame = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.END_PORTAL_FRAME);
        if (frame.isPresent()) {
          startAction(new FillPortalFrameAction(frame.get().pos()));
        } else {
          startAction(new ExploreAction("searching for portal room"));
        }
      }
      case END_FIGHT -> {
        var crystal = worldMemory.findNearestEntity(bot, entity -> entity.type() == EntityType.END_CRYSTAL);
        if (crystal.isPresent()) {
          startAction(new KillEntityAction(crystal.get().uuid(), "destroying end crystal"));
          return;
        }

        var dragon = worldMemory.findNearestEntity(bot, entity -> entity.type() == EntityType.ENDER_DRAGON);
        if (dragon.isPresent()) {
          startAction(new KillEntityAction(dragon.get().uuid(), "attacking dragon"));
        } else {
          startAction(new ExploreAction("searching for dragon"));
        }
      }
      default -> {}
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

    if ("item:minecraft:ender_pearl".equals(requirementKey)) {
      var piglin = worldMemory.findNearestPiglin(bot);
      if (piglin.isPresent() && AutomationInventory.countInventory(bot, "item:minecraft:gold_ingot") > 0) {
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

      if ("item:minecraft:ender_pearl".equals(requirementKey) && level.dimension() == Level.NETHER) {
        if (AutomationInventory.countInventory(bot, "item:minecraft:gold_ingot") < 1) {
          return Plan.dependencies(List.of(new RequirementGoal("item:minecraft:gold_ingot", 1, "piglin barter")), "barter gold");
        }
      }

      return Plan.explore("searching for " + AutomationRequirements.describe(requirementKey));
    }

    if (AutomationRequirements.FOOD.equals(requirementKey)) {
      return Plan.explore("searching for food");
    }

    return Plan.explore("searching for " + AutomationRequirements.describe(requirementKey));
  }

  private List<RequirementGoal> missingDependencies(AutomationRecipes.CraftingRecipeDefinition recipe, int missingOutput) {
    var batches = Math.max(1, (int) Math.ceil(missingOutput / (double) recipe.outputCount()));
    var dependencies = new ArrayList<RequirementGoal>();
    if (recipe.station() == AutomationRecipes.CraftingStation.CRAFTING_TABLE
      && !hasRememberedBlock(state -> state.getBlock() == Blocks.CRAFTING_TABLE)
      && AutomationInventory.countInventory(bot, "item:minecraft:crafting_table") <= 0) {
      dependencies.add(new RequirementGoal("item:minecraft:crafting_table", 1, "crafting station"));
    }
    if (recipe.station() == AutomationRecipes.CraftingStation.FURNACE
      && !hasRememberedBlock(state -> state.getBlock() == Blocks.FURNACE)
      && AutomationInventory.countInventory(bot, "item:minecraft:furnace") <= 0) {
      dependencies.add(new RequirementGoal("item:minecraft:furnace", 1, "furnace"));
    }

    recipe.ingredients().stream()
      .collect(java.util.stream.Collectors.groupingBy(AutomationRecipes.IngredientPlacement::requirementKey, java.util.stream.Collectors.counting()))
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

  private boolean hasRememberedBlock(java.util.function.Predicate<BlockState> predicate) {
    return worldMemory.findNearestBlock(bot, predicate).isPresent();
  }

  private String requiredToolKey(AutomationRecipes.ToolRequirement requirement) {
    return switch (requirement) {
      case NONE -> AutomationRequirements.ANY_LOG;
      case WOOD_PICKAXE -> "item:minecraft:wooden_pickaxe";
      case STONE_PICKAXE -> "item:minecraft:stone_pickaxe";
      case IRON_PICKAXE -> "item:minecraft:iron_pickaxe";
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
    action.start(this);
    status = action.description();
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

  private Optional<Entity> findEntity(java.util.UUID uuid) {
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
    private @Nullable CompletableFuture<Void> future;

    private ExploreAction(String reason) {
      this.reason = reason;
    }

    @Override
    public void start(AutomationController controller) {
      var player = bot.minecraft().player;
      var random = ThreadLocalRandom.current();
      var x = random.nextInt(player.blockPosition().getX() - 48, player.blockPosition().getX() + 49);
      var z = random.nextInt(player.blockPosition().getZ() - 48, player.blockPosition().getZ() + 49);
      future = PathExecutor.executePathfinding(bot, new XZGoal(x, z), new PathConstraintImpl(bot));
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
        return AutomationInventory.countInventory(bot, requirementKey) > 0 ? ActionResult.SUCCEEDED : ActionResult.FAILED;
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
        ? java.util.stream.IntStream.rangeClosed(1, 9)
        : java.util.stream.IntStream.rangeClosed(1, 4);
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
        } else if (AutomationInventory.countInventory(bot, "item:minecraft:crafting_table") > 0) {
          stationPos = findPlacementPos(Blocks.CRAFTING_TABLE);
          placeBlock("item:minecraft:crafting_table", stationPos);
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

      if (!(player.containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu furnaceMenu)) {
        if (furnacePos == null) {
          var remembered = worldMemory.findNearestBlock(bot, state -> state.getBlock() == Blocks.FURNACE);
          if (remembered.isPresent()) {
            furnacePos = remembered.get().pos();
          } else if (AutomationInventory.countInventory(bot, "item:minecraft:furnace") > 0) {
            furnacePos = findPlacementPos(Blocks.FURNACE);
            placeBlock("item:minecraft:furnace", furnacePos);
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
    private final java.util.UUID piglinId;
    private int stage;
    private @Nullable CompletableFuture<Void> future;
    private long stageTick;

    private BarterAction(java.util.UUID piglinId) {
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
        if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, "item:minecraft:gold_ingot")) {
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
    private final java.util.UUID entityId;
    private final String label;
    private int stage;
    private @Nullable CompletableFuture<Void> future;

    private KillEntityAction(java.util.UUID entityId, String label) {
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
        future = PathExecutor.executePathfinding(
          bot,
          (DynamicGoalScorer) () -> new CloseToPosGoal(SFVec3i.fromInt(entity.get().blockPosition()), 2),
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
        bot.botControl().replace(new AttackEntityTask(entityId, 120));
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
      if (player == null || gameMode == null || !AutomationInventory.ensureHolding(bot, "item:minecraft:ender_eye")) {
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

    private FillPortalFrameAction(BlockPos framePos) {
      this.framePos = framePos;
    }

    @Override
    public void start(AutomationController controller) {
      future = PathExecutor.executePathfinding(bot, new CloseToPosGoal(SFVec3i.fromInt(framePos), 2), new PathConstraintImpl(bot));
      stage = 0;
    }

    @Override
    public ActionResult poll(AutomationController controller) {
      if (future == null || !future.isDone()) {
        return ActionResult.RUNNING;
      }

      if (finishFuture(future) == ActionResult.FAILED || !AutomationInventory.ensureHolding(bot, "item:minecraft:ender_eye")) {
        return ActionResult.FAILED;
      }

      if (stage == 0) {
        interactBlock(framePos);
        stage = 1;
        return ActionResult.RUNNING;
      }

      var state = bot.minecraft().level.getBlockState(framePos);
      if (state.getBlock() == Blocks.END_PORTAL_FRAME && hasEye(state)) {
        return ActionResult.SUCCEEDED;
      }
      if (bot.minecraft().level.getBlockState(framePos.below()).getBlock() == Blocks.END_PORTAL) {
        return ActionResult.SUCCEEDED;
      }
      return worldMemory.ticks() - lastProgressTick > 40 ? ActionResult.FAILED : ActionResult.RUNNING;
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
    private final net.minecraft.resources.ResourceKey<Level> targetDimension;
    private int stage;
    private @Nullable CompletableFuture<Void> future;
    private @Nullable BlockPos portalPos;

    private UsePortalAction(Block portalBlock, net.minecraft.resources.ResourceKey<Level> targetDimension) {
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
        portalPos = worldMemory.findNearestBlock(bot, state -> state.getBlock() == portalBlock)
          .map(AutomationWorldMemory.RememberedBlock::pos)
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
      ClipContext.Fluid.NONE,
      player
    ))) instanceof InteractionResult.Success success
      && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
      player.swing(InteractionHand.MAIN_HAND);
    }
  }

  private void placeBlock(String requirementKey, BlockPos placePos) {
    var player = bot.minecraft().player;
    var gameMode = bot.minecraft().gameMode;
    if (player == null || gameMode == null || !AutomationInventory.ensureHolding(bot, requirementKey)) {
      return;
    }

    var support = placePos.below();
    var target = support.getCenter().add(0.0, 0.5, 0.0);
    player.lookAt(EntityAnchorArgument.Anchor.EYES, placePos.getCenter());
    gameMode.useItemOn(player, InteractionHand.MAIN_HAND, player.level().clipIncludingBorder(new ClipContext(
      player.getEyePosition(),
      target,
      ClipContext.Block.COLLIDER,
      ClipContext.Fluid.NONE,
      player
    )));
  }

  private boolean hasEye(BlockState state) {
    return state.hasProperty(EndPortalFrameBlock.HAS_EYE) && state.getValue(EndPortalFrameBlock.HAS_EYE);
  }

  private static final class AttackEntityTask implements ControlTask {
    private final java.util.UUID entityId;
    private final int maxTicks;
    private int ticks;
    private boolean done;

    private AttackEntityTask(java.util.UUID entityId, int maxTicks) {
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
    public void onStopped(com.soulfiremc.server.bot.ControlStopReason reason, @Nullable Throwable cause) {
      BotConnection.currentOptional().ifPresent(bot -> bot.controlState().resetAll());
    }
  }
}
