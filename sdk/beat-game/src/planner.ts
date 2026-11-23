import {
  BeatGamePhase,
  type BeatGameCheckpoint,
  type BeatGameItemRequirement,
  type BeatGameObservation,
  type BeatGamePlannerState,
  type BeatGameStrategy,
} from "./model.js";
import {
  BED_SHEEP_SEARCH_LEGS,
  EXPLORATION_REGION_RESET_DISTANCE,
  BED_SHEEP_SEARCH_PURPOSE,
  CRITICAL_HUNGER_FOOD_LEVEL,
  EDIBLE_FOOD_ITEM_IDS,
  EMERGENCY_FOOD_ITEM_IDS,
  LOG_ITEM_IDS,
  PLANK_ITEM_IDS,
  RAW_FOOD_TO_COOKED,
  URGENT_HUNGER_FOOD_LEVEL,
  requirementsForPhase,
} from "./requirements.js";

const FOOD_RESERVE_REFILL_TOLERANCE = 3;
const DEEP_WORK_MINIMUM_FOOD_RESERVE = 3;
const NETHER_REENTRY_MINIMUM_HUNGER = 18;
const MINIMUM_LOG_RESERVE = 2;
const DURABLE_PICKAXE_ITEM_IDS = [
  "minecraft:iron_pickaxe",
  "minecraft:diamond_pickaxe",
  "minecraft:netherite_pickaxe",
] as const;
const PRACTICAL_OVERWORLD_FOOD_REFILL_MINIMUM_Y = 50;
const MINING_PICKAXE_ITEM_IDS = [
  "minecraft:wooden_pickaxe",
  "minecraft:stone_pickaxe",
  ...DURABLE_PICKAXE_ITEM_IDS,
] as const;
const WOOD_ITEM_IDS = [
  "minecraft:oak_log",
  "minecraft:spruce_log",
  "minecraft:birch_log",
  "minecraft:jungle_log",
  "minecraft:acacia_log",
  "minecraft:dark_oak_log",
  "minecraft:mangrove_log",
  "minecraft:cherry_log",
  "minecraft:pale_oak_log",
  "minecraft:crimson_stem",
  "minecraft:warped_stem",
  "minecraft:oak_planks",
  "minecraft:spruce_planks",
  "minecraft:birch_planks",
  "minecraft:jungle_planks",
  "minecraft:acacia_planks",
  "minecraft:dark_oak_planks",
  "minecraft:mangrove_planks",
  "minecraft:cherry_planks",
  "minecraft:pale_oak_planks",
  "minecraft:bamboo_planks",
  "minecraft:crimson_planks",
  "minecraft:warped_planks",
] as const;

const COOKABLE_RAW_FOOD_ITEM_IDS = new Set(Object.keys(RAW_FOOD_TO_COOKED));

export type BeatGamePlannerDecision =
  | {
      readonly type: "advance-phase";
      readonly from: BeatGamePhase;
      readonly to: BeatGamePhase;
      readonly objective: string;
    }
  | {
      readonly type: "recover-death";
      readonly action: "recover-death";
    }
  | {
      readonly type: "eat";
      readonly action: "eat";
    }
  | {
      readonly type: "retreat";
      readonly action: "retreat";
    }
  | {
      readonly type: "prepare-equipment";
      readonly action: "prepare-equipment";
    }
  | {
      readonly type: "satisfy-requirement";
      readonly action: string;
      readonly requirement: BeatGameItemRequirement;
    }
  | {
      readonly type: "build-and-enter-nether";
      readonly action: "build-and-enter-nether";
    }
  | {
      readonly type: "return-through-portal";
      readonly action: "return-through-portal";
    }
  | {
      readonly type: "throw-eye";
      readonly action: "throw-eye";
    }
  | {
      readonly type: "search-stronghold";
      readonly action: "search-stronghold";
    }
  | {
      readonly type: "activate-end-portal";
      readonly action: "activate-end-portal";
    }
  | {
      readonly type: "fight-ender-dragon";
      readonly action: "fight-ender-dragon";
    }
  | {
      readonly type: "exit-end";
      readonly action: "exit-end";
    };

export interface BeatGamePlannerInput {
  readonly checkpoint: BeatGameCheckpoint;
  readonly observation: BeatGameObservation;
  readonly strategy: BeatGameStrategy;
}

export function decideBeatGameAction(input: BeatGamePlannerInput): BeatGamePlannerDecision {
  const { checkpoint, observation, strategy } = input;
  const { phase } = checkpoint.planner;
  if (observation.player.dead) {
    return { type: "recover-death", action: "recover-death" };
  }
  if (
    observation.player.food <= strategy.eatBelowFood &&
    hasEdibleFood(observation) &&
    (hasReadyFood(observation) || observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL)
  ) {
    return { type: "eat", action: "eat" };
  }
  const reusablePortalAvailable = hasReusablePortalCandidate(checkpoint, observation);
  const requirements = requirementsForPhase(phase, observation.inventory, strategy, {
    reusablePortalAvailable,
    bedUnavailable: bedSearchExhausted(checkpoint, observation),
    underground: isDeepUnderground(observation),
    portalEyesNeeded: checkpoint.memory.emptyPortalFrames,
  });
  if (
    observation.player.food <= strategy.eatBelowFood &&
    hasCookableRawFood(observation) &&
    !needsOverworldForPreparation(phase, observation)
  ) {
    const food =
      requirements.find(({ key }) => key === "food") ??
      (isOverworld(observation.player.position.dimension)
        ? requirementsForPhase(
            BeatGamePhase.PREPARE_OVERWORLD,
            observation.inventory,
            strategy,
          ).find(({ key }) => key === "food")
        : undefined);
    const logs = requirements.find(({ key }) => key === "logs");
    if (
      food !== undefined &&
      !food.satisfied &&
      (observation.player.health < strategy.minimumHealth ||
        logs === undefined ||
        logs.satisfied ||
        shouldDeferReserveRefill(logs, observation, strategy))
    ) {
      return requirementDecision(food);
    }
  }
  if (
    observation.player.food <= URGENT_HUNGER_FOOD_LEVEL &&
    !needsOverworldForPreparation(phase, observation)
  ) {
    // The Nether has almost nothing to eat and nothing cooks raw food. A live
    // bot retried its food supply there hundreds of times while it carried
    // raw pork, so a hungry bot without ready food goes back to the
    // Overworld, as a wounded one does.
    if (isNether(observation.player.position.dimension) && !hasReadyFood(observation)) {
      return { type: "return-through-portal", action: "return-through-portal" };
    }
    const foodSupply = requirementsForPhase(
      BeatGamePhase.PREPARE_OVERWORLD,
      observation.inventory,
      strategy,
    ).find(({ key }) => key === "food-supply");
    if (foodSupply !== undefined && !foodSupply.satisfied) {
      return requirementDecision(foodSupply);
    }
  }
  if (observation.player.health < strategy.minimumHealth) {
    const phaseFood = requirements.find(({ key }) => key === "food");
    const food =
      phaseFood ??
      (isOverworld(observation.player.position.dimension)
        ? requirementsForPhase(
            BeatGamePhase.PREPARE_OVERWORLD,
            observation.inventory,
            strategy,
          ).find(({ key }) => key === "food")
        : undefined);
    if (!hasFood(observation, strategy)) {
      // Without food a retreat cannot heal, and food is only in the
      // Overworld, so the wounded bot heads back first.
      if (needsOverworldForPreparation(phase, observation)) {
        return { type: "return-through-portal", action: "return-through-portal" };
      }
      if (
        food !== undefined &&
        !food.satisfied &&
        (phaseFood !== undefined || hasCookableRawFood(observation))
      ) {
        return requirementDecision(food);
      }
      const emergencyFoodSupply = requirementsForPhase(
        BeatGamePhase.PREPARE_OVERWORLD,
        observation.inventory,
        strategy,
      ).find(({ key }) => key === "food-supply");
      if (
        isOverworld(observation.player.position.dimension) &&
        emergencyFoodSupply !== undefined &&
        !emergencyFoodSupply.satisfied
      ) {
        return requirementDecision(emergencyFoodSupply);
      }
    }
    return { type: "retreat", action: "retreat" };
  }
  if (
    needsOverworldForPreparation(phase, observation) ||
    (needsOverworldWoodForPickaxe(phase, observation) &&
      hasReusablePortalCandidate(checkpoint, observation))
  ) {
    return { type: "return-through-portal", action: "return-through-portal" };
  }
  if (phase === BeatGamePhase.PREPARE_OVERWORLD) {
    const basicWeapon = requirements.find(({ key }) => key === "basic-melee-weapon");
    if (basicWeapon !== undefined && !basicWeapon.satisfied && canCraftWoodenSword(observation)) {
      return requirementDecision(basicWeapon);
    }
  }
  const missingRequirements = requirements.filter(({ satisfied }) => !satisfied);
  const firstMissing = missingRequirements[0];
  const actionableMissing = missingRequirements.find(
    (requirement) => !shouldDeferReserveRefill(requirement, observation, strategy),
  );
  const missing =
    actionableMissing ?? (phase === BeatGamePhase.PREPARE_OVERWORLD ? firstMissing : undefined);
  switch (phase) {
    case BeatGamePhase.PREPARE_OVERWORLD: {
      if (missing !== undefined) {
        return requirementDecision(missing);
      }
      return checkpoint.planner.completedActions.includes("prepare-equipment")
        ? phaseTransition(phase, BeatGamePhase.ENTER_NETHER)
        : {
            type: "prepare-equipment",
            action: "prepare-equipment",
          };
    }
    case BeatGamePhase.ENTER_NETHER: {
      if (isNether(observation.player.position.dimension)) {
        return phaseTransition(phase, BeatGamePhase.COLLECT_NETHER_RESOURCES);
      }
      return missing === undefined
        ? {
            type: "build-and-enter-nether",
            action: "build-and-enter-nether",
          }
        : requirementDecision(missing);
    }
    case BeatGamePhase.COLLECT_NETHER_RESOURCES: {
      if (!isNether(observation.player.position.dimension)) {
        const reentryRequirement = requirementsForPhase(
          BeatGamePhase.ENTER_NETHER,
          observation.inventory,
          strategy,
          { reusablePortalAvailable },
        ).find(
          (requirement) =>
            !requirement.satisfied &&
            !shouldDeferReserveRefill(requirement, observation, strategy) &&
            !hasNetherReentryFood(requirement, observation),
        );
        if (reentryRequirement !== undefined) {
          return requirementDecision(reentryRequirement);
        }
        return {
          type: "build-and-enter-nether",
          action: "build-and-enter-nether",
        };
      }
      return missing === undefined
        ? phaseTransition(phase, BeatGamePhase.RETURN_TO_OVERWORLD)
        : requirementDecision(missing);
    }
    case BeatGamePhase.RETURN_TO_OVERWORLD: {
      return isNether(observation.player.position.dimension)
        ? {
            type: "return-through-portal",
            action: "return-through-portal",
          }
        : phaseTransition(phase, BeatGamePhase.LOCATE_STRONGHOLD);
    }
    case BeatGamePhase.LOCATE_STRONGHOLD: {
      // The search digs its way down and meets mobs on the way. A live run
      // resumed it with no pickaxe left and ended at once, so missing tools
      // come first. Eyes are only needed at the portal itself.
      if (checkpoint.memory.strongholdEstimate !== undefined) {
        return missing === undefined || missing.key === "eyes-of-ender"
          ? { type: "search-stronghold", action: "search-stronghold" }
          : requirementDecision(missing);
      }
      // Locating the stronghold takes one eye, not twelve. A live bot that
      // dropped a bad estimate went hunting pearls for its twelfth eye
      // instead of throwing one of the eleven it carried.
      return missing === undefined ||
        (missing.key === "eyes-of-ender" &&
          checkpoint.memory.eyeSamples.length > 0 &&
          (observation.inventory.counts["minecraft:ender_eye"] ?? 0) > 0)
        ? { type: "throw-eye", action: "throw-eye" }
        : requirementDecision(missing);
    }
    case BeatGamePhase.ACTIVATE_END_PORTAL: {
      if (isEnd(observation.player.position.dimension)) {
        return phaseTransition(phase, BeatGamePhase.FIGHT_ENDER_DRAGON);
      }
      return missing === undefined
        ? {
            type: "activate-end-portal",
            action: "activate-end-portal",
          }
        : requirementDecision(missing);
    }
    case BeatGamePhase.FIGHT_ENDER_DRAGON: {
      // A live bot died in the End and woke in the Overworld, where it
      // looked for the dragon around its bed. It goes back through the
      // portal first.
      if (observation.player.position.dimension === "minecraft:overworld") {
        return phaseTransition(phase, BeatGamePhase.ACTIVATE_END_PORTAL);
      }
      // Nothing the fight asks for can be gathered in the End. A live bot
      // stepped through the portal without cooked food and retried the food
      // requirement every second, so it fights with what it carried in.
      return missing === undefined || isEnd(observation.player.position.dimension)
        ? {
            type: "fight-ender-dragon",
            action: "fight-ender-dragon",
          }
        : requirementDecision(missing);
    }
    case BeatGamePhase.EXIT_END: {
      return isEnd(observation.player.position.dimension)
        ? { type: "exit-end", action: "exit-end" }
        : phaseTransition(phase, BeatGamePhase.COMPLETE);
    }
    case BeatGamePhase.COMPLETE: {
      return phaseTransition(phase, phase);
    }
    default: {
      return phase satisfies never;
    }
  }
}

// Sea level is 63, so a bot this far down is in a cave or a dug shaft.
const DEEP_UNDERGROUND_MAXIMUM_Y = 50;

function isDeepUnderground(observation: BeatGameObservation): boolean {
  return (
    isOverworld(observation.player.position.dimension) &&
    observation.player.position.y < DEEP_UNDERGROUND_MAXIMUM_Y
  );
}

function canCraftWoodenSword(observation: BeatGameObservation): boolean {
  const counts = observation.inventory.counts;
  const availablePlanks = PLANK_ITEM_IDS.reduce(
    (total, itemId) => total + (counts[itemId] ?? 0),
    LOG_ITEM_IDS.reduce((total, itemId) => total + (counts[itemId] ?? 0) * 4, 0),
  );
  const craftingTableCost = (counts["minecraft:crafting_table"] ?? 0) > 0 ? 0 : 4;
  const stickCost = (counts["minecraft:stick"] ?? 0) > 0 ? 0 : 2;
  return availablePlanks >= craftingTableCost + stickCost + 2;
}

// A death can reset the run to Overworld preparation while the bot is still
// in the Nether, for example after it goes back for its items. Its trees,
// animals and stone, including the stone for a furnace, are only in the
// Overworld.
function needsOverworldForPreparation(
  phase: BeatGamePhase,
  observation: BeatGameObservation,
): boolean {
  return (
    phase === BeatGamePhase.PREPARE_OVERWORLD && isNether(observation.player.position.dimension)
  );
}

/// A live run with the Nether half done spent over two hours near a
/// hunted-out spawn refilling its food stock to eight. It ate what it caught
/// as fast as it caught it and never kept more than four. A bot on a full
/// stomach goes back with the reserve it would take into any deep work.
function hasNetherReentryFood(
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
): boolean {
  return (
    (requirement.key === "food-supply" || requirement.key === "food") &&
    observation.player.food >= NETHER_REENTRY_MINIMUM_HUNGER &&
    requirement.currentCount >= DEEP_WORK_MINIMUM_FOOD_RESERVE
  );
}

function shouldDeferReserveRefill(
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
): boolean {
  if (requirement.key === "logs") {
    // Without a durable pickaxe, wood is the only way to replace a broken one, so refill the
    // full reserve on the surface, where trees are close. Underground work keeps going while a
    // minimal reserve remains.
    const minimumReserve =
      !hasDurablePickaxe(observation) &&
      shouldRefillDeferredFoodReserve(observation) &&
      observation.player.food > strategy.eatBelowFood
        ? requirement.targetCount
        : Math.min(requirement.targetCount, MINIMUM_LOG_RESERVE);
    return requirement.currentCount >= minimumReserve;
  }
  if (requirement.key !== "food-supply" && requirement.key !== "food") {
    return false;
  }
  const minimumReserve = shouldRefillDeferredFoodReserve(observation)
    ? Math.max(1, requirement.targetCount - FOOD_RESERVE_REFILL_TOLERANCE)
    : DEEP_WORK_MINIMUM_FOOD_RESERVE;
  return (
    observation.player.food > strategy.eatBelowFood && requirement.currentCount >= minimumReserve
  );
}

/// Nether forests are far apart, and nothing else in the Nether makes a
/// pickaxe handle. A live run searched an hour for stems with six iron
/// ingots, one stick and no pickaxe while its portal stood nearby.
function needsOverworldWoodForPickaxe(
  phase: BeatGamePhase,
  observation: BeatGameObservation,
): boolean {
  const counts = observation.inventory.counts;
  const count = (itemIds: readonly string[]) =>
    itemIds.reduce((total, itemId) => total + (counts[itemId] ?? 0), 0);
  return (
    phase === BeatGamePhase.COLLECT_NETHER_RESOURCES &&
    isNether(observation.player.position.dimension) &&
    count(MINING_PICKAXE_ITEM_IDS) === 0 &&
    (counts["minecraft:stick"] ?? 0) < 2 &&
    count(WOOD_ITEM_IDS) === 0
  );
}

function hasDurablePickaxe(observation: BeatGameObservation): boolean {
  return DURABLE_PICKAXE_ITEM_IDS.some((itemId) => (observation.inventory.counts[itemId] ?? 0) > 0);
}

function shouldRefillDeferredFoodReserve(observation: BeatGameObservation): boolean {
  return (
    observation.player.position.dimension === "minecraft:overworld" &&
    observation.player.position.y >= PRACTICAL_OVERWORLD_FOOD_REFILL_MINIMUM_Y
  );
}

function hasFood(observation: BeatGameObservation, strategy: BeatGameStrategy): boolean {
  if (observation.player.food >= 18) {
    return true;
  }
  const foodIds =
    requirementsForPhase(BeatGamePhase.PREPARE_OVERWORLD, observation.inventory, strategy).find(
      ({ key }) => key === "food",
    )?.itemIds ?? [];
  return [...foodIds, ...EMERGENCY_FOOD_ITEM_IDS].some(
    (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
  );
}

function hasEdibleFood(observation: BeatGameObservation): boolean {
  return [...EDIBLE_FOOD_ITEM_IDS, ...EMERGENCY_FOOD_ITEM_IDS].some(
    (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
  );
}

function hasReadyFood(observation: BeatGameObservation): boolean {
  return [...EDIBLE_FOOD_ITEM_IDS, ...EMERGENCY_FOOD_ITEM_IDS].some(
    (itemId) =>
      !COOKABLE_RAW_FOOD_ITEM_IDS.has(itemId) && (observation.inventory.counts[itemId] ?? 0) > 0,
  );
}

function hasCookableRawFood(observation: BeatGameObservation): boolean {
  return Object.keys(RAW_FOOD_TO_COOKED).some(
    (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
  );
}

export function plannerWithObservation(
  planner: BeatGamePlannerState,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
  checkpoint?: BeatGameCheckpoint,
): BeatGamePlannerState {
  const now = new Date().toISOString();
  return {
    ...planner,
    requirements: requirementsForPhase(planner.phase, observation.inventory, strategy, {
      reusablePortalAvailable:
        checkpoint !== undefined && hasReusablePortalCandidate(checkpoint, observation),
      bedUnavailable: checkpoint !== undefined && bedSearchExhausted(checkpoint, observation),
      underground: isDeepUnderground(observation),
      portalEyesNeeded: checkpoint?.memory.emptyPortalFrames,
    }),
    updatedAt: now,
  };
}

// A live run found no sheep near spawn and then went without a bed for hours
// of play far away. The search only rules out the area it covered.
function bedSearchExhausted(
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): boolean {
  const frontier =
    checkpoint.memory.explorationFrontiers?.[`minecraft:overworld:${BED_SHEEP_SEARCH_PURPOSE}`];
  if (frontier?.progressVersion !== 2 || (frontier.totalAdvances ?? 0) < BED_SHEEP_SEARCH_LEGS) {
    return false;
  }
  const searched = frontier.lastPosition ?? frontier.origin;
  const player = observation.player.position;
  return (
    player.dimension !== searched.dimension ||
    Math.hypot(player.x - searched.x, player.z - searched.z) < EXPLORATION_REGION_RESET_DISTANCE
  );
}

function hasReusablePortalCandidate(
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): boolean {
  if (
    checkpoint.planner.phase !== BeatGamePhase.ENTER_NETHER &&
    checkpoint.planner.phase !== BeatGamePhase.COLLECT_NETHER_RESOURCES
  ) {
    return false;
  }
  const dimension = observation.player.position.dimension;
  if (
    checkpoint.memory.portals.some(
      ({ confidence, value }) => confidence > 0 && value.position.dimension === dimension,
    )
  ) {
    return true;
  }
  const activeWorkspace = checkpoint.activeSkill?.portalWorkspace;
  if (
    activeWorkspace?.origin.dimension === dimension &&
    activeWorkspace.status !== "ABANDONED" &&
    activeWorkspace.interiorState === "PORTAL"
  ) {
    return true;
  }
  return checkpoint.memory.portalWorkspaces.some(
    (workspace) =>
      workspace.origin.dimension === dimension &&
      workspace.status !== "ABANDONED" &&
      workspace.interiorState === "PORTAL",
  );
}

export function nextPhase(phase: BeatGamePhase): BeatGamePhase {
  switch (phase) {
    case BeatGamePhase.PREPARE_OVERWORLD: {
      return BeatGamePhase.ENTER_NETHER;
    }
    case BeatGamePhase.ENTER_NETHER: {
      return BeatGamePhase.COLLECT_NETHER_RESOURCES;
    }
    case BeatGamePhase.COLLECT_NETHER_RESOURCES: {
      return BeatGamePhase.RETURN_TO_OVERWORLD;
    }
    case BeatGamePhase.RETURN_TO_OVERWORLD: {
      return BeatGamePhase.LOCATE_STRONGHOLD;
    }
    case BeatGamePhase.LOCATE_STRONGHOLD: {
      return BeatGamePhase.ACTIVATE_END_PORTAL;
    }
    case BeatGamePhase.ACTIVATE_END_PORTAL: {
      return BeatGamePhase.FIGHT_ENDER_DRAGON;
    }
    case BeatGamePhase.FIGHT_ENDER_DRAGON: {
      return BeatGamePhase.EXIT_END;
    }
    case BeatGamePhase.EXIT_END:
    case BeatGamePhase.COMPLETE: {
      return BeatGamePhase.COMPLETE;
    }
    default: {
      return phase satisfies never;
    }
  }
}

export function objectiveForPhase(phase: BeatGamePhase): string {
  switch (phase) {
    case BeatGamePhase.PREPARE_OVERWORLD: {
      return "Prepare food, tools, and portal materials";
    }
    case BeatGamePhase.ENTER_NETHER: {
      return "Build, ignite, and enter a Nether portal";
    }
    case BeatGamePhase.COLLECT_NETHER_RESOURCES: {
      return "Collect blaze rods, pearls, and supporting resources";
    }
    case BeatGamePhase.RETURN_TO_OVERWORLD: {
      return "Return safely to the Overworld";
    }
    case BeatGamePhase.LOCATE_STRONGHOLD: {
      return "Triangulate and locate the stronghold";
    }
    case BeatGamePhase.ACTIVATE_END_PORTAL: {
      return "Fill the End portal frames and enter the End";
    }
    case BeatGamePhase.FIGHT_ENDER_DRAGON: {
      return "Destroy the End crystals and defeat the dragon";
    }
    case BeatGamePhase.EXIT_END: {
      return "Enter the exit portal and return from the End";
    }
    case BeatGamePhase.COMPLETE: {
      return "The dragon is defeated and the bot has left the End";
    }
    default: {
      return phase satisfies never;
    }
  }
}

export function isNether(dimension: string): boolean {
  return dimension === "minecraft:the_nether" || dimension.endsWith(":the_nether");
}

function isOverworld(dimension: string): boolean {
  return dimension === "minecraft:overworld" || dimension.endsWith(":overworld");
}

export function isEnd(dimension: string): boolean {
  return dimension === "minecraft:the_end" || dimension.endsWith(":the_end");
}

function phaseTransition(from: BeatGamePhase, to: BeatGamePhase): BeatGamePlannerDecision {
  return {
    type: "advance-phase",
    from,
    to,
    objective: objectiveForPhase(to),
  };
}

function requirementDecision(requirement: BeatGameItemRequirement): BeatGamePlannerDecision {
  return {
    type: "satisfy-requirement",
    action: `satisfy:${requirement.key}`,
    requirement,
  };
}
