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
const MINIMUM_LOG_RESERVE = 2;
const PRACTICAL_OVERWORLD_FOOD_REFILL_MINIMUM_Y = 50;

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
    bedUnavailable: bedSearchExhausted(checkpoint),
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
  if (needsOverworldForPreparation(phase, observation)) {
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
    case BeatGamePhase.PREPARE_OVERWORLD:
      if (missing !== undefined) {
        return requirementDecision(missing);
      }
      return checkpoint.planner.completedActions.includes("prepare-equipment")
        ? phaseTransition(phase, BeatGamePhase.ENTER_NETHER)
        : {
            type: "prepare-equipment",
            action: "prepare-equipment",
          };
    case BeatGamePhase.ENTER_NETHER:
      if (isNether(observation.player.position.dimension)) {
        return phaseTransition(phase, BeatGamePhase.COLLECT_NETHER_RESOURCES);
      }
      return missing === undefined
        ? {
            type: "build-and-enter-nether",
            action: "build-and-enter-nether",
          }
        : requirementDecision(missing);
    case BeatGamePhase.COLLECT_NETHER_RESOURCES:
      if (!isNether(observation.player.position.dimension)) {
        const reentryRequirement = requirementsForPhase(
          BeatGamePhase.ENTER_NETHER,
          observation.inventory,
          strategy,
          { reusablePortalAvailable },
        ).find(
          (requirement) =>
            !requirement.satisfied && !shouldDeferReserveRefill(requirement, observation, strategy),
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
    case BeatGamePhase.RETURN_TO_OVERWORLD:
      return isNether(observation.player.position.dimension)
        ? {
            type: "return-through-portal",
            action: "return-through-portal",
          }
        : phaseTransition(phase, BeatGamePhase.LOCATE_STRONGHOLD);
    case BeatGamePhase.LOCATE_STRONGHOLD:
      if (checkpoint.memory.strongholdEstimate !== undefined) {
        return { type: "search-stronghold", action: "search-stronghold" };
      }
      return missing === undefined
        ? { type: "throw-eye", action: "throw-eye" }
        : requirementDecision(missing);
    case BeatGamePhase.ACTIVATE_END_PORTAL:
      if (isEnd(observation.player.position.dimension)) {
        return phaseTransition(phase, BeatGamePhase.FIGHT_ENDER_DRAGON);
      }
      return missing === undefined
        ? {
            type: "activate-end-portal",
            action: "activate-end-portal",
          }
        : requirementDecision(missing);
    case BeatGamePhase.FIGHT_ENDER_DRAGON:
      return missing === undefined
        ? {
            type: "fight-ender-dragon",
            action: "fight-ender-dragon",
          }
        : requirementDecision(missing);
    case BeatGamePhase.EXIT_END:
      return isEnd(observation.player.position.dimension)
        ? { type: "exit-end", action: "exit-end" }
        : phaseTransition(phase, BeatGamePhase.COMPLETE);
    case BeatGamePhase.COMPLETE:
      return phaseTransition(phase, phase);
  }
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

function shouldDeferReserveRefill(
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
): boolean {
  if (requirement.key === "logs") {
    return requirement.currentCount >= Math.min(requirement.targetCount, MINIMUM_LOG_RESERVE);
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
      bedUnavailable: checkpoint !== undefined && bedSearchExhausted(checkpoint),
    }),
    updatedAt: now,
  };
}

function bedSearchExhausted(checkpoint: BeatGameCheckpoint): boolean {
  const frontier =
    checkpoint.memory.explorationFrontiers?.[`minecraft:overworld:${BED_SHEEP_SEARCH_PURPOSE}`];
  return frontier?.progressVersion === 2 && (frontier.totalAdvances ?? 0) >= BED_SHEEP_SEARCH_LEGS;
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
    activeWorkspace !== undefined &&
    activeWorkspace.origin.dimension === dimension &&
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
    case BeatGamePhase.PREPARE_OVERWORLD:
      return BeatGamePhase.ENTER_NETHER;
    case BeatGamePhase.ENTER_NETHER:
      return BeatGamePhase.COLLECT_NETHER_RESOURCES;
    case BeatGamePhase.COLLECT_NETHER_RESOURCES:
      return BeatGamePhase.RETURN_TO_OVERWORLD;
    case BeatGamePhase.RETURN_TO_OVERWORLD:
      return BeatGamePhase.LOCATE_STRONGHOLD;
    case BeatGamePhase.LOCATE_STRONGHOLD:
      return BeatGamePhase.ACTIVATE_END_PORTAL;
    case BeatGamePhase.ACTIVATE_END_PORTAL:
      return BeatGamePhase.FIGHT_ENDER_DRAGON;
    case BeatGamePhase.FIGHT_ENDER_DRAGON:
      return BeatGamePhase.EXIT_END;
    case BeatGamePhase.EXIT_END:
    case BeatGamePhase.COMPLETE:
      return BeatGamePhase.COMPLETE;
  }
}

export function objectiveForPhase(phase: BeatGamePhase): string {
  switch (phase) {
    case BeatGamePhase.PREPARE_OVERWORLD:
      return "Prepare food, tools, and portal materials";
    case BeatGamePhase.ENTER_NETHER:
      return "Build, ignite, and enter a Nether portal";
    case BeatGamePhase.COLLECT_NETHER_RESOURCES:
      return "Collect blaze rods, pearls, and supporting resources";
    case BeatGamePhase.RETURN_TO_OVERWORLD:
      return "Return safely to the Overworld";
    case BeatGamePhase.LOCATE_STRONGHOLD:
      return "Triangulate and locate the stronghold";
    case BeatGamePhase.ACTIVATE_END_PORTAL:
      return "Fill the End portal frames and enter the End";
    case BeatGamePhase.FIGHT_ENDER_DRAGON:
      return "Destroy the End crystals and defeat the dragon";
    case BeatGamePhase.EXIT_END:
      return "Enter the exit portal and return from the End";
    case BeatGamePhase.COMPLETE:
      return "The dragon is defeated and the bot has left the End";
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
