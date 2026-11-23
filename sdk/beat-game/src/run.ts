import type { SoulFireBot } from "@soulfiremc/sdk";
import {
  DateTime,
  Clock,
  Deferred,
  Effect,
  Fiber,
  Ref,
  Semaphore,
  Stream,
  type Scope,
} from "effect";

import {
  acquire,
  activateEndPortal,
  attackEntity,
  buildNetherPortal,
  buildStructure,
  castNetherPortal,
  collectBlocks,
  collectNearbyDrops,
  craftItem,
  eatWhenNeeded,
  enterEndPortal,
  enterPortal,
  equipBestArmor,
  exitEnd,
  excavateStaircase,
  explore,
  fish,
  flee,
  fightEnderDragon,
  respawnAndRecover,
  smelt,
  staircaseStartPosition,
  throwEyeOfEnder,
  transferContainerItems,
} from "./behaviors.js";
import { ReplayBroadcast } from "./broadcast.js";
import { InMemoryBeatGameCoordinator, type BeatGameCoordinator } from "./coordinator.js";
import {
  makeSoulFireBeatGameDriver,
  type BeatGameBlockFace,
  type BeatGameDriver,
  type BeatGameDriverEvent,
  type BeatGameEntitySelector,
  type BeatGameEnvironmentObservation,
  type BeatGameSurfaceColumn,
} from "./driver.js";
import {
  BeatGameActionError,
  BeatGameCancelled,
  BeatGameDriverError,
  BeatGameObservationError,
  BeatGamePathfindingError,
  BeatGameProtocolError,
  BeatGameRequirementError,
  type BeatGameError,
} from "./errors.js";
import {
  createNetherPortalFrame,
  distanceSquared,
  inferNetherPortalFrames,
  NETHER_PORTAL_FRAME_OBSIDIAN_COUNT,
  rotationToward,
  triangulateStronghold,
  type PortalFrame,
} from "./geometry.js";
import { approachLiquidSourceFromSide, LIQUID_INTERACTION_REACH } from "./liquids.js";
import {
  BEAT_GAME_CHECKPOINT_SCHEMA_VERSION,
  BeatGameDurableSkillKind,
  BeatGameDurableSkillStatus,
  BeatGamePathSearchMode,
  BeatGamePhase,
  BeatGameRiskProfile,
  BeatGameRunStatus,
  BeatGameTeamRole,
  PortalStrategy,
  defaultBeatGameStrategy,
  emptyBeatGameWorldMemory,
  type BeatGameBlockPosition,
  type BeatGameBlockObservation,
  type BeatGameCheckpoint,
  type BeatGameClaim,
  type BeatGameEntityObservation,
  type BeatGameEvent,
  type BeatGameExplorationFrontier,
  type BeatGameItemRequirement,
  type BeatGameMemoryEntry,
  type BeatGameObservation,
  type BeatGameOptions,
  type BeatGamePlannerState,
  type BeatGamePortalWorkspace,
  type BeatGameResult,
  type BeatGamePosition,
  type BeatGameSnapshot,
  type BeatGameStrategy,
  type BeatGameStrategyOptions,
  type BeatGameTeamRunOptions,
  type BeatGameWorldPlace,
} from "./model.js";
import {
  decideBeatGameAction,
  isEnd,
  isNether,
  objectiveForPhase,
  plannerWithObservation,
  type BeatGamePlannerDecision,
} from "./planner.js";
import type { BeatGamePolicyContext, BeatGameStrategyHooks } from "./policy.js";
import {
  BED_COLORS,
  BED_ITEM_IDS,
  BED_SHEEP_SEARCH_PURPOSE,
  CHESTPLATE_ITEM_IDS,
  HELMET_ITEM_IDS,
  BOOTS_ITEM_IDS,
  COOKED_FOOD_ITEM_IDS,
  CRITICAL_HUNGER_FOOD_LEVEL,
  EDIBLE_FOOD_ITEM_IDS,
  EMERGENCY_FOOD_ITEM_IDS,
  LOG_ITEM_IDS,
  PLANK_ITEM_IDS,
  RAW_FOOD_TO_COOKED,
  URGENT_HUNGER_FOOD_LEVEL,
  WOOL_ITEM_IDS,
  requirementCount,
  usableRequirementCount,
} from "./requirements.js";
import {
  abandonActiveDurableSkill,
  advanceDurableSkill,
  completeDurableSkill,
  latestResumablePortalWorkspace,
  startOrResumeDurableSkill,
  startDurableSkillIfIdle,
  suspendDurableSkill,
} from "./skills.js";
import {
  assertValidCheckpoint,
  InMemoryBeatGameCheckpointStore,
  type BeatGameCheckpointStore,
} from "./stores.js";

type EventInput<T extends BeatGameEvent = BeatGameEvent> = T extends BeatGameEvent
  ? Omit<T, "sequence" | "timestamp" | "runId" | "instanceId" | "botId" | "phase">
  : never;

interface PreparedWorkstation {
  readonly position: BeatGameBlockPosition;
  readonly placed: boolean;
}

const WORKSTATION_REUSE_RADIUS = 32;
const WORKSTATION_APPROACH_RADIUS = 1.5;
// Vanilla closes any open menu while the player stands in a nether portal.
// A workstation this close to one can put its user inside the portal.
const WORKSTATION_PORTAL_CLEARANCE = 2;
const WORKSTATION_PORTAL_RETREAT_DISTANCE = 6;
const WORKSTATION_REUSE_MAX_VERTICAL_DISTANCE = 8;
const WORKSTATION_REUSE_MAX_SEARCH_TIME_MS = 5000;
const WORKSTATION_REUSE_TIMEOUT_MS = 12_000;
const FURNACE_RECLAIM_TIMEOUT = "15 seconds";
const SHORE_PATH_MAX_SEARCH_TIME_MS = 5000;
const SHORE_PATH_TIMEOUT_MS = 10_000;
const DRY_SURFACE_APPROACH_RADIUS = 0.75;
const FURNACE_RECOVERY_RADIUS = 12;
const NEARBY_REQUIREMENT_DROP_RADIUS = 12;
const NEARBY_REQUIREMENT_DROP_MAXIMUM_VERTICAL_DISTANCE = 6;
const URGENT_FOOD_DROP_MAXIMUM_VERTICAL_DISTANCE = NEARBY_REQUIREMENT_DROP_RADIUS;
const RESOURCE_COLLECTION_RESERVED_SLOTS = 3;
const REQUIREMENT_NO_PROGRESS_REPLAN_DELAY_MS = 1000;
const LOCAL_NAVIGATION_RECOVERY_MINIMUM_DISTANCE = 3;
const LOCAL_NAVIGATION_RECOVERY_MAX_SEARCH_TIME_MS = 5000;
const LOCAL_NAVIGATION_RECOVERY_TIMEOUT_MS = 15_000;
const DRY_SHAFT_RECOVERY_MAXIMUM_RISE = 16;
const SURFACE_RECOVERY_MAXIMUM_STAIR_SEGMENTS = 384;
const SURFACE_RECOVERY_DIRECT_PATH_SEARCH_TIMEOUT_MS = 2000;
const SURFACE_RECOVERY_DIRECT_PATH_EXECUTION_TIMEOUT_MS = 60_000;
const SURFACE_RECOVERY_DIRECT_PATH_CANDIDATES = 2;
const DRY_SHAFT_RECOVERY_STEP_RADIUS = 0.35;
const DRY_SHAFT_RECOVERY_STEP_TIMEOUT_MS = 10_000;
const DRY_SHAFT_RECOVERY_SUPPORT_ITEM_IDS = [
  "minecraft:cobblestone",
  "minecraft:cobbled_deepslate",
  "minecraft:dirt",
  "minecraft:coarse_dirt",
  "minecraft:netherrack",
  ...PLANK_ITEM_IDS,
  ...LOG_ITEM_IDS,
] as const;
const NETHER_EXPLORATION_BUILDING_BLOCK_IDS = ["minecraft:netherrack"] as const;
const NETHER_EXPLORATION_MINIMUM_BUILDING_BLOCKS = 16;
const NETHER_EXPLORATION_TARGET_BUILDING_BLOCKS = 64;
const NETHER_EXPLORATION_PICKAXE_DURABILITY_RESERVE = 64;
const NETHER_STONE_PICKAXE_BATCH = 3;
const NETHER_STEM_RESERVE = 4;
// The farthest a block search reaches, and within the Nether view distance.
// Fortresses and Nether forests are far apart, so their searches use it.
const NETHER_WIDE_SEARCH_RADIUS = 128;
const NETHER_LAVA_SEA_LEVEL = 32;
const NETHER_EXPLORATION_CAVERN_Y = 50;
// The goal only needs the bot up in open ground near the leg's end.
const NETHER_CAVERN_LEG_RADIUS = 16;
const OUTWARD_SEARCH_DISTANCE = 1024;
const ENDERMAN_HUNT_MINIMUM_HEALTH = 18;
const ENDERMAN_HUNT_HEAL_WAIT_MS = 3000;
const OUTWARD_SEARCH_TURN_INTERVAL_MS = 10 * 60_000;
// Entity interaction reaches about three blocks, so the bot closes in nearer.
const PIGLIN_BARTER_APPROACH_RADIUS = 2;
const PIGLIN_REFUSAL_WAIT_MS = 8000;
const PIGLIN_OUT_OF_REACH_RETRIES = 3;
const GOLD_GUARDING_PIGLIN_ENTITY_TYPES = ["minecraft:piglin", "minecraft:piglin_brute"] as const;
// Piglins guard gold within 16 blocks. The margin covers ore a little away
// from the bot and piglins that wander closer while it digs.
const PIGLIN_GOLD_GUARD_RADIUS = 24;
const PIGLIN_GOLD_LEAVE_DISTANCE = 32;
const UNSTACKABLE_CRAFT_RESULT_PATTERN =
  /_(?:pickaxe|axe|shovel|hoe|sword|helmet|chestplate|leggings|boots|bed|bucket)$|:(?:bow|crossbow|shield|flint_and_steel)$/u;
const STRUCTURE_SPAWNER_ANCHOR_RADIUS = 8;
const DISCOVERY_MAXIMUM_HOP = 64;
const WOODEN_PICKAXE_MAXIMUM_DURABILITY = 59;
const EXPLORATION_MAXIMUM_LEG_DISTANCE = 32;
const EXPLORATION_MAXIMUM_SURFACE_ELEVATION_CHANGE = 12;
const REMEMBERED_PORTAL_MAXIMUM_LEG_DISTANCE = 16;
const REMEMBERED_PORTAL_LEG_RADIUS = 8;
const REMEMBERED_PORTAL_FINAL_APPROACH_DISTANCE = 20;
const REMEMBERED_PORTAL_MAXIMUM_APPROACH_LEGS = 32;
const REMEMBERED_PORTAL_MINIMUM_LEG_PROGRESS = 4;
const REMEMBERED_PORTAL_PREFERRED_QUALITY_BOUND = 1.5;
const REMEMBERED_PORTAL_FALLBACK_QUALITY_BOUND = 3;
const PORTAL_CAREFUL_QUALITY_BOUND = 1.2;
const REMEMBERED_PORTAL_MAXIMUM_EXPANDED_STATES = 100_000;
const REMEMBERED_PORTAL_VERTICAL_LEG_HEIGHT = 24;
const REMEMBERED_PORTAL_VERTICAL_LEG_RADIUS = 6;
const REMEMBERED_PORTAL_MAXIMUM_VERTICAL_LEGS = 16;
const REMEMBERED_PORTAL_MINIMUM_VERTICAL_PROGRESS = 4;
const REMEMBERED_PORTAL_ROUTE_COOLDOWN_MS = 10_000;
// A failed route that still brought the bot this much closer counts as
// progress. Every failure costs confidence, and one without progress costs
// more, so a portal the bot keeps failing to reach is eventually forgotten.
const REMEMBERED_PORTAL_ROUTE_MINIMUM_PROGRESS = 16;
const REMEMBERED_PORTAL_ROUTE_FAILURE_PENALTY = 0.25;
const REMEMBERED_PORTAL_ROUTE_STALL_PENALTY = 0.5;
const REMEMBERED_PORTAL_REPLAN_DELAY_MS = 1000;
const MAX_SAFE_DEATH_RECOVERY_FAILURES = 3;
// A fast run gives up on a corpse after one failed preparation: rebuilding
// the kit is faster than the hours a distant recovery can take.
const MAX_FAST_DEATH_RECOVERY_FAILURES = 1;

function deathRecoveryFailureLimit(strategy: BeatGameStrategy): number {
  return strategy.riskProfile === BeatGameRiskProfile.FAST
    ? MAX_FAST_DEATH_RECOVERY_FAILURES
    : MAX_SAFE_DEATH_RECOVERY_FAILURES;
}
const MAXIMUM_DEATH_RECOVERY_SAFETY_INTERRUPTS = 6;
const DEATH_RECOVERY_PREPARATION_PROGRESS_DISTANCE = 8;
const DISTANT_DEATH_RECOVERY_BOOTSTRAP_DISTANCE = 128;
const ACTIVE_CORPSE_RECOVERY_DISTANCE = 512;
const ACTIVE_CORPSE_RECOVERY_MAX_AGE_MS = 4 * 60_000;
const DEATH_RECOVERY_MAXIMUM_QUALITY_BOUND = 2;
const IMMEDIATE_CORPSE_RECOVERY_DISTANCE = 12;
const STALE_CORPSE_SCOUT_MAXIMUM_DISTANCE = 96;
const STALE_CORPSE_SCOUT_MAXIMUM_VERTICAL_DISTANCE = 12;
const STALE_CORPSE_SCOUT_GOAL_RADIUS = 24;
const DEEP_CORPSE_EXCAVATION_MINIMUM_DEPTH = 32;
const DISTANT_CORPSE_EXCAVATION_MINIMUM_HORIZONTAL_DISTANCE = 64;
const DEATH_RECOVERY_PICKAXE_DURABILITY_BUFFER = 24;
const DEATH_RECOVERY_PICKAXE_DURABILITY_PER_DESCENT_LEVEL = 1;
const CORPSE_DROP_INSPECTION_DISTANCE = 32;
const CORPSE_DROP_MATCH_RADIUS = 16;
const CORPSE_GUARD_RADIUS = 16;
const DEATH_RECOVERY_PREPARATION_STAGING_DISTANCE = 256;
const EMERGENCY_ARMAMENT_LOG_COUNT = 2;
const DEATH_RECOVERY_BOOTSTRAP_LOG_COUNT = 12;
const DEATH_RECOVERY_BOOTSTRAP_BLOCK_COUNT = 16;
const DEATH_RECOVERY_FOOD_RESERVE_COUNT = 8;
const DEATH_RECOVERY_MINIMUM_STAGING_FOOD_COUNT = 4;
const DEATH_RECOVERY_PICKUP_RESERVED_SLOTS = 8;
const DEATH_RECOVERY_AQUATIC_FALLBACK_EXPLORATION_LEGS = 2;
const DEATH_RECOVERY_FOOD_SEARCH_TIMEOUT_MS = 60_000;
const DEATH_RECOVERY_FOOD_SEARCH_PENDING =
  "still searching for enough travel food for distant corpse recovery";
const DISPOSABLE_DEATH_RECOVERY_ITEM_IDS = new Set([
  "minecraft:coarse_dirt",
  "minecraft:dirt",
  "minecraft:grass_block",
  "minecraft:mud",
  "minecraft:mycelium",
  "minecraft:podzol",
  "minecraft:rooted_dirt",
]);
const RENEWABLE_DEATH_RECOVERY_ITEM_IDS = new Set([
  ...DISPOSABLE_DEATH_RECOVERY_ITEM_IDS,
  ...LOG_ITEM_IDS,
  ...PLANK_ITEM_IDS,
  ...EDIBLE_FOOD_ITEM_IDS,
  ...EMERGENCY_FOOD_ITEM_IDS,
  "minecraft:acacia_sapling",
  "minecraft:andesite",
  "minecraft:arrow",
  "minecraft:birch_sapling",
  "minecraft:beetroot_seeds",
  "minecraft:black_wool",
  "minecraft:blue_wool",
  "minecraft:bone",
  "minecraft:brown_mushroom",
  "minecraft:brown_wool",
  "minecraft:charcoal",
  "minecraft:cherry_sapling",
  "minecraft:coal",
  "minecraft:cobbled_deepslate",
  "minecraft:cobblestone",
  "minecraft:crafting_table",
  "minecraft:cyan_wool",
  "minecraft:dark_oak_sapling",
  "minecraft:diorite",
  "minecraft:egg",
  "minecraft:feather",
  "minecraft:flint",
  "minecraft:furnace",
  "minecraft:granite",
  "minecraft:gray_wool",
  "minecraft:gravel",
  "minecraft:green_wool",
  "minecraft:gunpowder",
  "minecraft:ink_sac",
  "minecraft:jungle_sapling",
  "minecraft:leaf_litter",
  "minecraft:leather",
  "minecraft:light_blue_wool",
  "minecraft:light_gray_wool",
  "minecraft:lime_wool",
  "minecraft:magenta_wool",
  "minecraft:mangrove_propagule",
  "minecraft:melon_seeds",
  "minecraft:oak_sapling",
  "minecraft:orange_wool",
  "minecraft:pale_oak_sapling",
  "minecraft:pink_wool",
  "minecraft:pitcher_pod",
  "minecraft:pumpkin_seeds",
  "minecraft:purple_wool",
  "minecraft:raw_copper",
  "minecraft:rabbit_hide",
  "minecraft:red_mushroom",
  "minecraft:red_wool",
  "minecraft:sand",
  "minecraft:slime_ball",
  "minecraft:spider_eye",
  "minecraft:spruce_sapling",
  "minecraft:stick",
  "minecraft:stone",
  "minecraft:stone_axe",
  "minecraft:stone_hoe",
  "minecraft:stone_pickaxe",
  "minecraft:stone_shovel",
  "minecraft:stone_sword",
  "minecraft:string",
  "minecraft:torch",
  "minecraft:torchflower_seeds",
  "minecraft:wheat_seeds",
  "minecraft:white_wool",
  "minecraft:wooden_axe",
  "minecraft:wooden_hoe",
  "minecraft:wooden_pickaxe",
  "minecraft:wooden_shovel",
  "minecraft:wooden_sword",
  "minecraft:yellow_wool",
]);
const COOKABLE_RAW_FOOD_ITEM_IDS = new Set(Object.keys(RAW_FOOD_TO_COOKED));
const INVENTORY_DISCARD_PRIORITY = [
  "minecraft:leaf_litter",
  "minecraft:tripwire_hook",
  "minecraft:spider_eye",
  "minecraft:pufferfish",
  "minecraft:lily_pad",
  "minecraft:beetroot_seeds",
  "minecraft:melon_seeds",
  "minecraft:pitcher_pod",
  "minecraft:pumpkin_seeds",
  "minecraft:torchflower_seeds",
  "minecraft:wheat_seeds",
  "minecraft:oak_sapling",
  "minecraft:birch_sapling",
  "minecraft:spruce_sapling",
  "minecraft:acacia_sapling",
  "minecraft:cherry_sapling",
  "minecraft:jungle_sapling",
  "minecraft:dark_oak_sapling",
  "minecraft:pale_oak_sapling",
  "minecraft:mangrove_propagule",
  "minecraft:feather",
  "minecraft:sand",
  "minecraft:gravel",
  "minecraft:dirt",
  "minecraft:coarse_dirt",
  "minecraft:grass_block",
  "minecraft:mud",
  "minecraft:mycelium",
  "minecraft:podzol",
  "minecraft:rooted_dirt",
  "minecraft:raw_copper",
  "minecraft:andesite",
  "minecraft:diorite",
  "minecraft:granite",
  "minecraft:tuff",
  // Nether exploration picks these up and nothing uses them. A live bot held
  // so many that it could not free slots for food.
  "minecraft:crimson_roots",
  "minecraft:warped_roots",
  "minecraft:nether_sprouts",
  "minecraft:weeping_vines",
  "minecraft:twisting_vines",
  "minecraft:crimson_fungus",
  "minecraft:warped_fungus",
  "minecraft:nether_wart_block",
  "minecraft:warped_wart_block",
  "minecraft:shroomlight",
  "minecraft:quartz",
  "minecraft:soul_sand",
  "minecraft:soul_soil",
  "minecraft:egg",
  "minecraft:red_mushroom",
  "minecraft:brown_mushroom",
  // Magma burns whoever stands on it, so it makes a poor building block.
  "minecraft:magma_block",
  "minecraft:calcite",
  "minecraft:smooth_basalt",
  "minecraft:lapis_lazuli",
  "minecraft:bone",
  // Overworld pickups that nothing crafts or uses. A live run reached the
  // Nether full of them and could not free the slots to craft gold boots.
  "minecraft:wildflowers",
  "minecraft:rose_bush",
  "minecraft:lilac",
  "minecraft:peony",
  "minecraft:sunflower",
  "minecraft:dandelion",
  "minecraft:poppy",
  "minecraft:short_grass",
  "minecraft:tall_grass",
  "minecraft:fern",
  "minecraft:fishing_rod",
  "minecraft:redstone",
  "minecraft:gunpowder",
  "minecraft:string",
  "minecraft:leather",
  "minecraft:potion",
  // A live bot back in the Nether held all of these and failed its gold
  // boots craft 54 times for want of three empty slots.
  "minecraft:glowstone_dust",
  "minecraft:nautilus_shell",
  "minecraft:phantom_membrane",
  "minecraft:firefly_bush",
  "minecraft:wither_skeleton_skull",
  "minecraft:nether_brick_fence",
  // A live bot bartering for pearls held these and could not free three
  // slots. Nether bricks are not among its building blocks.
  "minecraft:nether_bricks",
  "minecraft:nether_brick",
  "minecraft:copper_ingot",
  "minecraft:splash_potion",
  // A live bot back in the Overworld held these and failed its food search
  // 89 times for want of three empty slots.
  "minecraft:allium",
  "minecraft:azure_bluet",
  "minecraft:blue_orchid",
  "minecraft:cornflower",
  "minecraft:lily_of_the_valley",
  "minecraft:orange_tulip",
  "minecraft:oxeye_daisy",
  "minecraft:pink_petals",
  "minecraft:pink_tulip",
  "minecraft:red_tulip",
  "minecraft:torchflower",
  "minecraft:white_tulip",
  "minecraft:rail",
  "minecraft:warped_nylium",
  "minecraft:crimson_nylium",
  // Nothing crafts with iron nuggets. A live bot looking for its last eye of
  // ender carried 36 of them and could not free three slots for food.
  "minecraft:iron_nugget",
] as const;
// Each keeps a reserve. Cobblestone is also what Nether pickaxes are made of:
// a live run threw all of it away to make room and then had no stone left
// for pickaxes in the Nether.
const BUILDING_BLOCK_DISCARD_IDS = [
  "minecraft:netherrack",
  "minecraft:basalt",
  "minecraft:blackstone",
  "minecraft:cobblestone",
] as const;
const REDUNDANT_EQUIPMENT_FAMILIES = [
  [
    "minecraft:wooden_pickaxe",
    "minecraft:stone_pickaxe",
    "minecraft:iron_pickaxe",
    "minecraft:diamond_pickaxe",
    "minecraft:netherite_pickaxe",
  ],
  [
    "minecraft:wooden_sword",
    "minecraft:stone_sword",
    "minecraft:iron_sword",
    "minecraft:diamond_sword",
    "minecraft:netherite_sword",
  ],
  [
    "minecraft:wooden_axe",
    "minecraft:stone_axe",
    "minecraft:iron_axe",
    "minecraft:diamond_axe",
    "minecraft:netherite_axe",
  ],
  [
    "minecraft:wooden_shovel",
    "minecraft:stone_shovel",
    "minecraft:iron_shovel",
    "minecraft:diamond_shovel",
    "minecraft:netherite_shovel",
  ],
  [
    "minecraft:wooden_hoe",
    "minecraft:stone_hoe",
    "minecraft:iron_hoe",
    "minecraft:diamond_hoe",
    "minecraft:netherite_hoe",
  ],
] as const;
const SINGLETON_EQUIPMENT_ITEM_IDS = [
  "minecraft:shield",
  "minecraft:flint_and_steel",
  "minecraft:shears",
  "minecraft:bow",
  "minecraft:crossbow",
] as const;
const INVENTORY_BUILDING_BLOCK_RESERVE = 64;
const INVENTORY_EMPTY_SLOT_BUFFER = 2;
const INVENTORY_EMPTY_SLOT_STABILITY_OBSERVATIONS = 12;
const INVENTORY_DISCARD_ESCAPE_DISTANCE = 3;
const INVENTORY_DISCARD_POCKET_DEPTH = 2;
// Reaches every block that touches the pocket, including the ones behind it.
const INVENTORY_DISCARD_POCKET_LAVA_CLEARANCE = 2.5;
const INVENTORY_DISCARD_SITE_DISTANCE = 6;
const INVENTORY_DISCARD_ESCAPE_MAX_SEARCH_TIME_MS = 3000;
const LOW_VALUE_DEATH_RECOVERY_ITEM_IDS = new Set([
  ...DISPOSABLE_DEATH_RECOVERY_ITEM_IDS,
  "minecraft:acacia_sapling",
  "minecraft:arrow",
  "minecraft:birch_sapling",
  "minecraft:beetroot_seeds",
  "minecraft:bone",
  "minecraft:cherry_sapling",
  "minecraft:dark_oak_sapling",
  "minecraft:egg",
  "minecraft:feather",
  "minecraft:jungle_sapling",
  "minecraft:ink_sac",
  "minecraft:leaf_litter",
  "minecraft:mangrove_propagule",
  "minecraft:melon_seeds",
  "minecraft:oak_sapling",
  "minecraft:pale_oak_sapling",
  "minecraft:pitcher_pod",
  "minecraft:pumpkin_seeds",
  "minecraft:sand",
  "minecraft:spruce_sapling",
  "minecraft:torchflower_seeds",
  "minecraft:wheat_seeds",
]);
const SUBSTANTIAL_RENEWABLE_DEATH_RECOVERY_ITEM_COUNT = 8;
const SUBSTANTIAL_RENEWABLE_DEATH_RECOVERY_MAX_DISTANCE = ACTIVE_CORPSE_RECOVERY_DISTANCE;
const FURNACE_FUEL_SEARCH_RADIUS = 16;
const HUNT_ATTACK_APPROACH_RADIUS = 24;
const AQUATIC_HUNT_ATTACK_APPROACH_RADIUS = 4;
const HUNT_UNREACHABLE_TARGET_RETRY_DISTANCE = 4;
const HUNT_UNREACHABLE_GIVE_UP_COUNT = 3;
const HUNT_NEARBY_UNREACHABLE_RETRY_DELAY_MS = 5000;
const HUNT_DISTANT_UNREACHABLE_RETRY_DELAY_MS = 15_000;
const AQUATIC_HUNT_VERTICAL_ROUTE_COST = 4;
const AQUATIC_HUNT_HEALTH_ROUTE_COST = 16;
const AQUATIC_HUNT_DEFAULT_HEALTH = 3;
const HUNT_APPROACH_BUFFER = 4;
const HUNT_APPROACH_GOAL_RADIUS = 2;
const HUNT_MAXIMUM_APPROACH_DISTANCE = 48;
const LAND_HUNT_MAXIMUM_VERTICAL_DISTANCE = 12;
const FOOD_LAND_HUNT_MAXIMUM_VERTICAL_RISE = 40;
const DIRECTED_HUNT_MAXIMUM_DETOUR = 32;
const DIRECTED_HUNT_DESTINATION_REACHED_RADIUS = 3;
// Loaded terrain reaches about 160 blocks, and a forest canopy tops its
// columns with leaves, so a coarse surface survey finds trees beyond the
// block search radius.
const TREE_SURVEY_RADIUS = 160;
const TREE_SURVEY_SAMPLE_STEP = 4;
const URGENT_AQUATIC_HUNT_MAXIMUM_HORIZONTAL_DISTANCE = 64;
const URGENT_AQUATIC_HUNT_MAXIMUM_VERTICAL_DISTANCE = 4;
const LAND_HUNT_CHASE_TIMEOUT_MS = 20_000;
const AQUATIC_HUNT_CHASE_TIMEOUT_MS = 30_000;
const AQUATIC_HUNT_MINIMUM_AIR_TICKS = 120;
const HUNT_DROP_RECOVERY_RADIUS = 48;
const HUNT_DROP_RECOVERY_MAXIMUM_VERTICAL_DISTANCE = 4;
const AQUATIC_HUNT_MAXIMUM_CHASE_ATTEMPTS = 3;
const WOUNDED_AQUATIC_FALLBACK_FOOD_LEVEL = 18;
const MAXIMUM_DAMAGE_FREE_FALL_DISTANCE = 3;
const WOUNDED_LAND_HUNT_MAXIMUM_FALL_DISTANCE = 2;
const HUNT_DROP_ITEM_IDS_BY_ENTITY_TYPE: Readonly<Record<string, readonly string[]>> = {
  "minecraft:blaze": ["minecraft:blaze_rod"],
  "minecraft:cave_spider": ["minecraft:string", "minecraft:spider_eye"],
  "minecraft:chicken": ["minecraft:chicken", "minecraft:feather"],
  "minecraft:cod": ["minecraft:cod"],
  "minecraft:cow": ["minecraft:beef", "minecraft:leather"],
  "minecraft:enderman": ["minecraft:ender_pearl"],
  "minecraft:husk": ["minecraft:rotten_flesh"],
  "minecraft:mooshroom": ["minecraft:beef", "minecraft:leather"],
  "minecraft:pig": ["minecraft:porkchop"],
  "minecraft:rabbit": ["minecraft:rabbit", "minecraft:rabbit_hide"],
  "minecraft:salmon": ["minecraft:salmon"],
  "minecraft:sheep": ["minecraft:mutton"],
  "minecraft:spider": ["minecraft:string", "minecraft:spider_eye"],
  "minecraft:zombie": ["minecraft:rotten_flesh"],
  "minecraft:zombie_villager": ["minecraft:rotten_flesh"],
};
const LIQUID_INTERACTION_APPROACH_RADIUS = 3;
const LIQUID_SOURCE_MAXIMUM_PATH_ATTEMPTS = 8;
const LIQUID_SOURCE_UNREACHABLE_TTL_MS = 10 * 60_000;
// Keep a margin below the protocol interaction limit. A candidate at the
// exact limit can become unreachable after pathfinding settles within radius.
const FISHING_SHORE_SEARCH_RADIUS = Math.ceil(LIQUID_INTERACTION_REACH);
const FISHING_COLLECTION_BATCH_SIZE = 3;
const FISHING_MAXIMUM_FAILED_CASTS = 4;
const FISHING_MINIMUM_CAST_HORIZONTAL_DISTANCE = 2;
const FISHING_PREFERRED_CAST_HORIZONTAL_DISTANCE = 3;
const FISHING_MAXIMUM_DOWNWARD_CAST_PITCH = 30;
const MAXIMUM_LIQUID_SIGHT_CLEARING_BLOCKS = 4;
const LAVA_RETREAT_DISTANCE = 8;
const LAVA_EMERGENCY_SPRINT_MS = 1500;
const LAVA_ESCAPE_LEDGE_SEARCH_RADIUS = 12;
const LAVA_ESCAPE_MAXIMUM_SWIMMABLE_RISE = 8;
const LAVA_ESCAPE_MAXIMUM_LEDGE_DROP = 1;
const LAVA_ESCAPE_MAXIMUM_APPROACH_ATTEMPTS = 50;
const IRON_SEARCH_Y = 16;
const IRON_SEARCH_MAX_Y = 24;
const IRON_SEARCH_DESCENT_STEP = 12;
const COBBLESTONE_DIG_DESCENT_STEP = 8;
const IRON_ORE_BLOCK_IDS = ["minecraft:iron_ore", "minecraft:deepslate_iron_ore"] as const;
const RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE = 64;
const RESOURCE_DESCENT_PICKAXE_DURABILITY_RESERVE = 48;
const SURFACE_ESCAPE_PICKAXE_DURABILITY_PER_LEVEL = 3;
const STONE_PICKAXE_MAXIMUM_DURABILITY = 131;
const IRON_PICKAXE_MAXIMUM_DURABILITY = 250;
const DEEP_LAVA_SEARCH_Y = -52;
const DEEP_LAVA_SEARCH_MAX_Y = -48;
const DEEP_LAVA_DESCENT_STEP = 12;
const PORTAL_CASTING_ADDITIONAL_LAVA_SOURCE_COUNT = NETHER_PORTAL_FRAME_OBSIDIAN_COUNT - 1;
const EXPLORATION_REANCHOR_DISTANCE = 16;
const STRUCTURE_SEARCH_HOP_FACTOR = 1.5;
const EXPLORATION_FRONTIER_LIMIT = 64;
const EXPLORATION_DEATH_DISPLACEMENT_WINDOW_MS = 10 * 60 * 1000;
const EXPLORATION_MAXIMUM_SEARCH_TIME_MS = 5000;
const EXPLORATION_MAXIMUM_EXPANDED_STATES = 12_000;
const EXPLORATION_MINIMUM_PROGRESS_DISTANCE = 4;
const EXPLORATION_TARGET_REACHED_RADIUS = 6;
const EXPLORATION_RETRY_DIRECTIONS = 4;
const EXPLORATION_RETRY_DISTANCE_SCALES = [1, 2 / 3, 1 / 3] as const;
const EYE_TRIANGULATION_BASELINE = 32;
const AIR_ESCAPE_SURFACE_SEARCH_RADIUS = 16;
const AIR_ESCAPE_EXTENDED_SURFACE_SEARCH_RADIUS = 96;
const AIR_ESCAPE_EXTENDED_SURFACE_SAMPLE_STEP = 4;
const AIR_ESCAPE_LOCAL_LEDGE_SEARCH_RADIUS = 6;
const AIR_ESCAPE_LOCAL_LEDGE_QUERY_RESULTS = 512;
const AIR_ESCAPE_LOCAL_LEDGE_CANDIDATES = 64;
const AIR_ESCAPE_SURFACE_APPROACH_ATTEMPTS = 180;
const AIR_ESCAPE_STAGNANT_OBSERVATIONS = 30;
const AIR_ESCAPE_MAXIMUM_RECOVERY_ATTEMPTS = 3;
const AIR_ESCAPE_DIRECTION_SECTORS = 8;
const AIR_ESCAPE_MAXIMUM_SWIMMABLE_RISE = 2;
const AIR_ESCAPE_MAXIMUM_SHAFT_BLOCKS = 64;
const AIR_ESCAPE_VERTICAL_PROGRESS_ATTEMPTS = 20;
const AIR_ESCAPE_VERTICAL_PROGRESS = 0.75;
const OVERWORLD_LOW_GROUND_MAX_Y = 62;
const SURFACE_RESOURCE_MINIMUM_Y_MARGIN = 4;
const SURFACE_RESOURCE_MAXIMUM_Y_MARGIN = 2;
const SURFACE_RESOURCE_PATH_MINIMUM_Y_MARGIN = 1;
const SURFACE_NEIGHBOR_MAX_HEIGHT_DELTA = 1;
const MINIMUM_STABLE_SURFACE_NEIGHBORS = 2;
const ELEVATED_SURFACE_PATH_TIMEOUT_MS = 15_000;
const COAL_ORE_BLOCK_IDS = ["minecraft:coal_ore", "minecraft:deepslate_coal_ore"] as const;
const DEATH_RECOVERY_BUILDING_BLOCK_IDS = [
  "minecraft:dirt",
  "minecraft:grass_block",
  "minecraft:coarse_dirt",
  "minecraft:rooted_dirt",
] as const;
const DEATH_RECOVERY_BUILDING_ITEM_IDS = ["minecraft:dirt", "minecraft:coarse_dirt"] as const;
const DEATH_RECOVERY_ADDITIONAL_PLACE_ITEM_IDS = [...LOG_ITEM_IDS, ...PLANK_ITEM_IDS] as const;
const FURNACE_FUEL_ITEM_IDS = ["minecraft:coal", "minecraft:charcoal"] as const;
// Faster than a step down, slower than a jump's fall: the bot is dropping.
const EAT_FALLING_VELOCITY = -0.5;
// Crimson and warped wood neither burns nor smelts into charcoal. A live bot
// offered its crimson planks as charcoal fuel and the furnace rejected them,
// which ended the run.
const isBurnableWood = (itemId: string): boolean =>
  !itemId.startsWith("minecraft:crimson_") && !itemId.startsWith("minecraft:warped_");
const BURNABLE_PLANK_ITEM_IDS: readonly string[] = PLANK_ITEM_IDS.filter((itemId) =>
  isBurnableWood(itemId),
);
const BURNABLE_LOG_ITEM_IDS: readonly string[] = LOG_ITEM_IDS.filter((itemId) =>
  isBurnableWood(itemId),
);
const MINING_PICKAXE_ITEM_IDS = [
  "minecraft:netherite_pickaxe",
  "minecraft:diamond_pickaxe",
  "minecraft:iron_pickaxe",
  "minecraft:stone_pickaxe",
  "minecraft:wooden_pickaxe",
  "minecraft:golden_pickaxe",
] as const;
const MINING_AXE_ITEM_IDS = [
  "minecraft:netherite_axe",
  "minecraft:diamond_axe",
  "minecraft:iron_axe",
  "minecraft:stone_axe",
  "minecraft:wooden_axe",
  "minecraft:golden_axe",
] as const;
const MINING_SHOVEL_ITEM_IDS = [
  "minecraft:netherite_shovel",
  "minecraft:diamond_shovel",
  "minecraft:iron_shovel",
  "minecraft:stone_shovel",
  "minecraft:wooden_shovel",
  "minecraft:golden_shovel",
] as const;
const MINING_HOE_ITEM_IDS = [
  "minecraft:netherite_hoe",
  "minecraft:diamond_hoe",
  "minecraft:iron_hoe",
  "minecraft:stone_hoe",
  "minecraft:wooden_hoe",
  "minecraft:golden_hoe",
] as const;
const MELEE_WEAPON_ITEM_IDS = [
  "minecraft:netherite_sword",
  "minecraft:diamond_sword",
  "minecraft:iron_sword",
  "minecraft:stone_sword",
  "minecraft:wooden_sword",
] as const;
const STONE_OR_BETTER_MINING_PICKAXE_ITEM_IDS = [
  "minecraft:netherite_pickaxe",
  "minecraft:diamond_pickaxe",
  "minecraft:iron_pickaxe",
  "minecraft:stone_pickaxe",
] as const;
const STONE_TOOL_MATERIAL_ITEM_IDS = [
  "minecraft:blackstone",
  "minecraft:cobbled_deepslate",
  "minecraft:cobblestone",
] as const;
const PLAYER_FLUID_BLOCK_IDS = [
  "minecraft:water",
  "minecraft:bubble_column",
  "minecraft:kelp",
  "minecraft:kelp_plant",
  "minecraft:seagrass",
  "minecraft:tall_seagrass",
  "minecraft:lava",
] as const;
const NETHER_FORTRESS_BLOCK_IDS = [
  "minecraft:nether_bricks",
  "minecraft:nether_brick_fence",
  "minecraft:nether_brick_stairs",
] as const;
const DURABLE_MINING_PICKAXE_ITEM_IDS = [
  "minecraft:netherite_pickaxe",
  "minecraft:diamond_pickaxe",
  "minecraft:iron_pickaxe",
] as const;
const RESOURCE_COLLECTION_BUFFERS = {
  "blaze-rods": 2,
  cobblestone: 12,
  diamond: 2,
  "ender-pearls": 2,
  food: 4,
  fuel: 2,
  gold: 8,
  iron: 3,
  logs: 4,
} as const;
type BufferedResource = keyof typeof RESOURCE_COLLECTION_BUFFERS;

const FOOD_ANIMAL_ENTITY_TYPES = [
  "minecraft:cow",
  "minecraft:mooshroom",
  "minecraft:pig",
  "minecraft:rabbit",
  "minecraft:sheep",
  "minecraft:chicken",
  "minecraft:cod",
  "minecraft:salmon",
] as const;
const AQUATIC_FOOD_ENTITY_TYPES = new Set(["minecraft:cod", "minecraft:salmon"]);
const EMERGENCY_FOOD_ENTITY_TYPES = [
  "minecraft:zombie",
  "minecraft:zombie_villager",
  "minecraft:husk",
] as const;
const EMERGENCY_FOOD_ENTITY_TYPE_SET = new Set<string>(EMERGENCY_FOOD_ENTITY_TYPES);
const EMERGENCY_FOOD_MAXIMUM_HORIZONTAL_DISTANCE = 16;
const EMERGENCY_FOOD_MAXIMUM_VERTICAL_DISTANCE = 6;
const HIGH_YIELD_FOOD_ANIMAL_TYPES = new Set([
  "minecraft:cow",
  "minecraft:mooshroom",
  "minecraft:pig",
  "minecraft:sheep",
]);
const HIGH_YIELD_FOOD_PREFERENCE_RADIUS = 32;
const SAFE_AQUATIC_FALLBACK_EXPLORATION_LEGS = 12;

const DANGEROUS_NEUTRAL_ENTITY_TYPES = [
  "minecraft:bee",
  "minecraft:cave_spider",
  "minecraft:dolphin",
  "minecraft:enderman",
  "minecraft:goat",
  "minecraft:iron_golem",
  "minecraft:llama",
  "minecraft:panda",
  "minecraft:piglin",
  "minecraft:polar_bear",
  "minecraft:spider",
  "minecraft:trader_llama",
  "minecraft:wolf",
  "minecraft:zombified_piglin",
] as const;
const CONDITIONALLY_HOSTILE_ENTITY_TYPES = new Set([
  "minecraft:cave_spider",
  "minecraft:enderman",
  "minecraft:piglin",
  "minecraft:spider",
  "minecraft:zombified_piglin",
]);
const GROUP_AGGRO_ENTITY_TYPES = new Set([
  "minecraft:piglin",
  "minecraft:piglin_brute",
  "minecraft:zombified_piglin",
]);
const PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES = new Set([
  "minecraft:blaze",
  "minecraft:bogged",
  "minecraft:breeze",
  "minecraft:cave_spider",
  "minecraft:drowned",
  "minecraft:elder_guardian",
  "minecraft:endermite",
  "minecraft:evoker",
  "minecraft:guardian",
  "minecraft:hoglin",
  "minecraft:husk",
  "minecraft:magma_cube",
  "minecraft:phantom",
  "minecraft:piglin_brute",
  "minecraft:pillager",
  "minecraft:ravager",
  "minecraft:shulker",
  "minecraft:silverfish",
  "minecraft:slime",
  "minecraft:spider",
  "minecraft:stray",
  "minecraft:vex",
  "minecraft:vindicator",
  "minecraft:wither_skeleton",
  "minecraft:zoglin",
  "minecraft:zombie",
  "minecraft:zombie_villager",
]);
// An enderman hit is a melee hit a raised shield blocks. A live bot hunting
// pearls fought one with a stone sword and no shield up and died to four hits
// in three seconds.
const SHIELD_BLOCKING_HOSTILE_ENTITY_TYPES = new Set([
  "minecraft:bogged",
  "minecraft:drowned",
  "minecraft:enderman",
  "minecraft:hoglin",
  "minecraft:piglin",
  "minecraft:pillager",
  "minecraft:skeleton",
  "minecraft:stray",
  "minecraft:zoglin",
  "minecraft:zombified_piglin",
]);
const PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES = new Set([
  "minecraft:bogged",
  "minecraft:pillager",
  "minecraft:skeleton",
  "minecraft:stray",
  "minecraft:witch",
]);
const COMMITTABLE_CLOSE_MELEE_ENTITY_TYPES = new Set([
  "minecraft:cave_spider",
  "minecraft:drowned",
  "minecraft:endermite",
  "minecraft:husk",
  "minecraft:magma_cube",
  "minecraft:piglin",
  "minecraft:silverfish",
  "minecraft:slime",
  "minecraft:spider",
  "minecraft:zombified_piglin",
  "minecraft:zombie",
  "minecraft:zombie_villager",
]);
const FAST_MELEE_PURSUER_ENTITY_TYPES = new Set([
  "minecraft:cave_spider",
  "minecraft:piglin",
  "minecraft:spider",
  "minecraft:zombified_piglin",
]);
const ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES = new Set(["minecraft:creeper", "minecraft:witch"]);
const FAILED_ESCAPE_FIGHT_THRESHOLD = 3;
const FAILED_ESCAPE_STREAK_WINDOW_MS = 30_000;
const ESCAPE_BURNING_DAMAGE_TYPES: ReadonlySet<string> = new Set([
  "minecraft:in_fire",
  "minecraft:hot_floor",
  "minecraft:lava",
]);
const FAILED_ESCAPE_RECENT_HIT_MS = 5000;
const FAILED_ESCAPE_HEAL_WAIT_MS = 3000;
const PROACTIVE_ESCAPE_ONLY_EVASION_RADIUS = 12;
const PROACTIVE_MELEE_DISENGAGEMENT_RADIUS = 12;
const MELEE_ENGAGEMENT_RADIUS = 4;
const CREEPER_PROACTIVE_EVASION_RADIUS = 8;
const CREEPER_EMERGENCY_REEVASION_RADIUS = 6;
const CREEPER_CRITICAL_REEVASION_RADIUS = 4;
const PROACTIVE_RANGED_ENGAGEMENT_RADIUS = 16;
const SHIELDED_AMBUSH_ESCAPE_THRESHOLD = 3;
const PROACTIVE_THREAT_MAXIMUM_VERTICAL_DISTANCE = 6;
// A live bot on a pillar saw an angry enderman five blocks below that could
// not reach it, and that it could not reach. It turned to fight it every seven
// seconds for an hour, and each attack failed at once. A threat the bot
// could not reach is left alone for a while unless it comes close.
const UNREACHABLE_THREAT_IGNORE_MS = 30_000;
const UNREACHABLE_THREAT_IGNORED_BEYOND = 3;
const RANGED_THREAT_ESCAPE_TRIGGER_RADIUS = 24;
const RANGED_THREAT_ESCAPE_SAFE_DISTANCE = 32;
const ENDERMAN_WATER_ESCAPE_RADIUS = 16;
const DEFENSIVE_PURSUIT_MAX_DISTANCE = 12;
const STALLED_RANGED_PURSUIT_MAXIMUM_PROGRESS = 2;
const BAREHANDED_RANGED_DEFENSE_MAX_DISTANCE = 8;
const EMERGENCY_KNOCKBACK_RANGE = 3.5;
const EMERGENCY_ESCAPE_SPRINT_MS = 1250;
const RANGED_ESCAPE_SPRINT_MS = 2500;
const RANGED_ESCAPE_MINIMUM_AWAY_ALIGNMENT = 0;
const FAST_PURSUER_ADDITIONAL_DIRECT_ESCAPE_ATTEMPTS = 2;
const EMERGENCY_ESCAPE_FLUID_PROJECTION_DISTANCE = 2;
const EMERGENCY_ESCAPE_SURFACE_PROJECTION_DISTANCE = 10;
const RANGED_ESCAPE_SURFACE_PROJECTION_DISTANCE = 18;
// A sprint-jump drifts sideways on a slope. A live run escaping a zombie
// along a ledge slid down the side and fell nine blocks, so even a melee
// escape needs safe ground one block to each side.
export const DIRECT_ESCAPE_MINIMUM_LATERAL_HALF_WIDTH = 1;
// Long enough for a skeleton without line of sight to lose interest and for
// the bot to regain a few hearts.
const RANGED_COVER_WAIT_MS = 15_000;
const COVER_MELEE_EXCLUSION_RADIUS = 6;
const COVER_WALL_RING_RADIUS = 1.5;
const ENDERMAN_COVER_WAIT_MS = 30_000;
const RANGED_COVER_HEALTH = 8;
const RECENT_ARROW_HIT_MS = 5000;
const PROJECTILE_DAMAGE_TYPES = new Set(["minecraft:arrow", "minecraft:trident"]);
const CREEPER_ESCAPE_SPRINT_MS = 2500;
const CREEPER_ESCAPE_SPRINT_SEGMENT_MS = 350;
const CREEPER_ESCAPE_MINIMUM_SEGMENT_DISTANCE = 0.5;
const CREEPER_ESCAPE_MINIMUM_AWAY_ALIGNMENT = 0.25;
const CREEPER_ESCAPE_SURFACE_PROJECTION_DISTANCE = 18;
const CREEPER_FUSE_DURATION_MS = 1500;
const CREEPER_ESCAPE_REACTION_MARGIN_MS = 300;
const CREEPER_SPRINT_BLOCKS_PER_SECOND = 5.6;
const CREEPER_BLAST_SAFE_DISTANCE = 6;
const CHARGED_CREEPER_BLAST_SAFE_DISTANCE = 12;
const CREEPER_SHIELD_POLL_MS = 50;
const CREEPER_SHIELD_MAXIMUM_POLLS = 40;
const EMERGENCY_ESCAPE_LAVA_CHECK_RADIUS = 4;
const DRIVER_EVENTS_RESUBSCRIBE_DELAY_MS = 1000;
const NIGHT_SHELTER_START_TICK = 13_000n;
const NIGHT_SHELTER_END_TICK = 1000n;
const NIGHT_SHELTER_DEPTH = 3;
const SHAFT_CENTER_TOLERANCE = 0.2;
const SHAFT_CENTER_TIMEOUT_MS = 2000;
const NIGHT_SHELTER_MINIMUM_SURFACE_COVER = 8;
const NIGHT_SHELTER_DEEP_SURFACE_COVER = 32;
const NIGHT_SHELTER_POLL_MS = 1000;
const NIGHT_SHELTER_DESCENT_ATTEMPTS = 30;
const NIGHT_SHELTER_DAYLIGHT_CONFIRMATIONS = 3;
// A hostile night lasts 12,000 ticks, which is 10 minutes at 20 ticks per second.
// Waiting longer means the reported time is stale or the server stopped its daylight cycle.
const NIGHT_SHELTER_MAXIMUM_WAIT_MS = 12 * 60_000;
const NIGHT_SHELTER_RELOCATION_SCAN_RADIUS = 32;
const NIGHT_SHELTER_RELOCATION_MINIMUM_DISTANCE = 4;
const NIGHT_SHELTER_ACTION = "survive:night-shelter";
const BED_WOOL_COUNT = 3;
const NIGHT_BED_SEARCH_RADIUS = 32;
const NIGHT_BED_PLACEMENT_SEARCH_RADIUS = 5;
const NIGHT_BED_PLACEMENT_CONFIRMATION_ATTEMPTS = 20;
const NIGHT_BED_MORNING_TIMEOUT_MS = 20_000;
const NIGHT_SHELTER_BLOCK_ITEM_IDS = [
  "minecraft:dirt",
  "minecraft:coarse_dirt",
  "minecraft:cobblestone",
  "minecraft:cobbled_deepslate",
  ...PLANK_ITEM_IDS,
  ...LOG_ITEM_IDS,
] as const;
const DEATH_OBSERVATION_DEDUPLICATION_WINDOW_MS = 5000;
const BAREHANDED_DEFENSE_MINIMUM_HEALTH = 18;
const MELEE_DISENGAGE_HEALTH = 16;
const LETHAL_MELEE_DISENGAGE_HEALTH = 7;
const SHIELDED_RANGED_FIGHT_MINIMUM_HEALTH = MELEE_DISENGAGE_HEALTH;
const UNSHIELDED_RANGED_FIGHT_MINIMUM_HEALTH = 14;
const CAUGHT_MELEE_COMMIT_MINIMUM_HEALTH = MELEE_DISENGAGE_HEALTH;
const MAXIMUM_KNOCKBACK_ESCAPES = 2;
const THREAT_ESCAPE_SAFE_DISTANCE = 24;
const SINGLE_THREAT_MAXIMUM_ESCAPES = 4;
const DURABLE_DEATH_RECOVERY_WINDOW_MS = 8 * 60 * 60 * 1000;
const KEPT_INVENTORY_MINIMUM_SHARE = 0.8;
const DROPPED_ITEM_LOADED_LIFETIME_MS = 5 * 60 * 1000;
const CHAINED_DEATH_RESPAWN_BASE_COOLDOWN_MS = 60_000;
const CHAINED_DEATH_RESPAWN_MAXIMUM_COOLDOWN_MS = 8 * 60_000;
const RENEWABLE_DEATH_RECOVERY_MAX_DISTANCE = 64;
const UNKNOWN_DEATH_RECOVERY_MAX_DISTANCE = 64;
const RECOVERY_DURATION_MS = 20_000;
const POST_DEFENSE_RECOVERY_DURATION_MS = 90_000;
const MAXIMUM_RECOVERY_POLL_MS = 500;
const MINIMUM_RECOVERY_POLL_MS = 100;
const MINIMUM_SAFE_AIR_TICKS = 260;
// Above the breathing pocket escape's own reserve of 180.
const REQUIREMENT_MINIMUM_SAFE_AIR_TICKS = 200;
const AIR_ESCAPE_CRITICAL_AIR_TICKS = 100;
const AIR_ESCAPE_DIG_AIR_RESERVE_TICKS = 60;
const AIR_ESCAPE_DIG_SETTLE_ATTEMPTS = 20;
const AIR_ESCAPE_DIG_TIMEOUT_BUFFER_TICKS = 20;
const AIR_ESCAPE_ASCENT_STAGNATION_OBSERVATIONS = 10;
const AIR_ESCAPE_ASCENT_PROGRESS_EPSILON = 0.05;
const AIR_ESCAPE_OPEN_COLUMN_SEARCH_RADIUS = 4;
const AIR_ESCAPE_BREATHING_POCKET_SEARCH_RADIUS = 8;
const AIR_ESCAPE_BREATHING_POCKET_CANDIDATES = 16;
const AIR_ESCAPE_BREATHING_POCKET_PATH_TIMEOUT_MS = 4000;
const AIR_ESCAPE_BREATHING_POCKET_PATH_RESERVE_TICKS =
  AIR_ESCAPE_CRITICAL_AIR_TICKS + Math.ceil(AIR_ESCAPE_BREATHING_POCKET_PATH_TIMEOUT_MS / 50);
const FALL_CLUTCH_TRIGGER_VELOCITY = -0.7;
const FALL_CLUTCH_SAFE_VELOCITY = -0.2;
const FALL_CLUTCH_RAYCAST_DISTANCE = 96;
// The bucket reaches from the eyes, but the landing raycast starts at the feet.
const FALL_CLUTCH_PLACEMENT_REACH = Math.min(LIQUID_INTERACTION_REACH, 4.5) - 1.62;
const FALL_CLUTCH_POLL_MS = 50;
const FALL_CLUTCH_MAXIMUM_ATTEMPTS = 200;
// A poll takes about two ticks, and a full-speed fall covers almost four
// blocks in that time, more than the window in which the water can land.
const FALL_CLUTCH_BURST_LOOKAHEAD_TICKS = 3;
const FALL_CLUTCH_MAXIMUM_BURST_USES = 6;
const FALL_CLUTCH_PICKUP_ATTEMPTS = 4;
const FALL_CLUTCH_USE_RETRY_TICKS = 6;

export interface BeatGameRun {
  readonly id: string;
  readonly teamId: string;
  readonly instanceId: string;
  readonly botId: string;
  readonly events: Stream.Stream<BeatGameEvent, BeatGameError>;
  readonly snapshots: Stream.Stream<BeatGameSnapshot, BeatGameError>;
  readonly awaitCompletion: Effect.Effect<BeatGameResult, BeatGameError>;
  readonly pause: Effect.Effect<void, BeatGameError>;
  readonly resume: Effect.Effect<void, BeatGameError>;
  readonly stop: Effect.Effect<void, BeatGameError>;
  readonly snapshot: Effect.Effect<BeatGameSnapshot, BeatGameError>;
}

export interface BeatGameTeamRun {
  readonly teamId: string;
  readonly runs: readonly BeatGameRun[];
  readonly awaitCompletion: Effect.Effect<readonly BeatGameResult[], BeatGameError>;
  readonly pause: Effect.Effect<void, BeatGameError>;
  readonly resume: Effect.Effect<void, BeatGameError>;
  readonly stop: Effect.Effect<void, BeatGameError>;
}

interface FailedCraft {
  readonly itemIds: readonly string[] | undefined;
  readonly signature: string;
}

interface RunState {
  readonly driver: BeatGameDriver;
  readonly store: BeatGameCheckpointStore;
  readonly coordinator: BeatGameCoordinator;
  readonly strategy: BeatGameStrategy;
  readonly hooks: BeatGameStrategyHooks;
  readonly checkpoint: Ref.Ref<BeatGameCheckpoint>;
  readonly observation: Ref.Ref<BeatGameObservation>;
  readonly lastLivingObservation: Ref.Ref<BeatGameObservation>;
  readonly portalScanDimension: Ref.Ref<string | undefined>;
  // Abandoned rounds of actions that have no durable skill to record them,
  // reset when the action succeeds.
  readonly abandonedActionRounds: Ref.Ref<ReadonlyMap<string, number>>;
  readonly lastDamage: Ref.Ref<Extract<BeatGameDriverEvent, { type: "bot-damaged" }> | undefined>;
  // Escapes whose route failed in a row, reset by one that gets away.
  readonly failedEscapes: Ref.Ref<{ readonly count: number; readonly atMs: number }>;
  /// Threats the bot found it could not reach, by entity key, with when to
  /// look at them again.
  readonly unreachableThreats: Ref.Ref<ReadonlyMap<string, number>>;
  /// What each item last failed to craft from, for lack of materials.
  readonly failedCrafts: Ref.Ref<ReadonlyMap<string, FailedCraft>>;
  /// Set while the bot fetches logs for a craft.
  readonly fetchingTableWood: Ref.Ref<boolean>;
  readonly pendingDeaths: Ref.Ref<readonly PendingDeath[]>;
  readonly explorationFrontiers: Ref.Ref<Readonly<Record<string, BeatGameExplorationFrontier>>>;
  readonly checkedRecoveryContainers: Ref.Ref<ReadonlySet<string>>;
  readonly paused: Ref.Ref<boolean>;
  readonly stopped: Deferred.Deferred<undefined>;
  readonly checkpointMutex: Semaphore.Semaphore;
  readonly eventMutex: Semaphore.Semaphore;
  readonly events: ReplayBroadcast<BeatGameEvent>;
  readonly snapshots: ReplayBroadcast<BeatGameSnapshot>;
  readonly sequence: Ref.Ref<bigint>;
  readonly startedAtMs: number;
}

interface PendingDeath {
  readonly observedAt: string;
  readonly position: BeatGamePosition;
  readonly recoverItems: boolean;
  readonly observedLive?: boolean;
  readonly inventoryCounts?: Readonly<Record<string, number>>;
  readonly message?: string;
}

interface CrossDimensionDeathRecoveryResult {
  readonly status: "entered" | "pending";
  readonly observation: BeatGameObservation;
  readonly portalTarget?: BeatGamePosition;
  readonly reason?: string;
}

interface ActionResult {
  readonly checkpoint?: (checkpoint: BeatGameCheckpoint) => BeatGameCheckpoint;
  readonly phase?: BeatGamePhase;
  readonly replanReason?: string;
  readonly replanDelayMs?: number;
  readonly continueReason?: string;
  readonly continueDelayMs?: number;
  readonly defenseTarget?: BeatGameEntityObservation;
  readonly escapeTarget?: BeatGameEntityObservation;
  readonly airEscapePosition?: BeatGamePosition;
  readonly fallClutchPosition?: BeatGamePosition;
  readonly environmentalEscapePosition?: BeatGamePosition;
  readonly travelMealRequested?: boolean;
  readonly nightShelterRequested?: boolean;
  readonly completedPendingDeath?: string;
}

interface ImmediateThreat {
  readonly target: BeatGameEntityObservation;
  readonly response: "attack" | "flee";
}

export function beatGame(
  bot: SoulFireBot,
  options: BeatGameOptions = {},
): Effect.Effect<BeatGameRun, BeatGameError, Scope.Scope> {
  return liveEnvironmentDriver(bot).pipe(
    Effect.flatMap((driver) => beatGameWithDriver(driver, options)),
  );
}

export function beatGameWithDriver(
  driver: BeatGameDriver,
  options: BeatGameOptions = {},
): Effect.Effect<BeatGameRun, BeatGameError, Scope.Scope> {
  return Effect.gen(function* () {
    // oxlint-disable-next-line effecttsgo/crypto-random-uuid-in-effect -- Keep cryptographically generated identifiers across supported runtimes.
    const runId = options.runId ?? crypto.randomUUID();
    const teamId = options.team?.teamId ?? runId;
    const store = options.checkpointStore ?? new InMemoryBeatGameCheckpointStore();
    const coordinator = options.coordinator ?? new InMemoryBeatGameCoordinator();
    const strategy = yield* Effect.try({
      try: () => mergeStrategy(options.strategy),
      catch: (cause) =>
        new BeatGameProtocolError({
          runId,
          instanceId: driver.instanceId,
          botId: driver.botId,
          phase: BeatGamePhase.PREPARE_OVERWORLD,
          action: "configure",
          retryable: false,
          message:
            cause instanceof Error ? cause.message : "Beat-game strategy configuration is invalid",
          cause,
        }),
    });
    const observation = yield* driver.observe.pipe(
      Effect.mapError((cause) =>
        observationError(runId, driver, BeatGamePhase.PREPARE_OVERWORLD, cause),
      ),
    );
    const restored = yield* store.load(runId);
    yield* Effect.try({
      try: () => {
        validateRestoredCheckpoint(restored, driver, teamId);
      },
      catch: (cause) =>
        new BeatGameProtocolError({
          runId,
          instanceId: driver.instanceId,
          botId: driver.botId,
          phase: BeatGamePhase.PREPARE_OVERWORLD,
          action: "restore-checkpoint",
          retryable: false,
          message: cause instanceof Error ? cause.message : "The restored checkpoint is invalid",
          cause,
        }),
    });
    const member = yield* coordinator.register({
      teamId,
      instanceId: driver.instanceId,
      botId: driver.botId,
      ...(options.team?.role === undefined ? {} : { requestedRole: options.team.role }),
    });
    const initial =
      restored === undefined
        ? createInitialCheckpoint(runId, teamId, driver, member.role, observation, strategy)
        : adoptRestoredCheckpoint(restored, driver, member.role, observation);
    const stored = yield* store.save(initial, restored?.revision);
    const checkpointRef = yield* Ref.make(stored);
    const observationRef = yield* Ref.make(observation);
    const lastLivingObservation = yield* Ref.make(observation);
    const pendingDeaths = yield* Ref.make<readonly PendingDeath[]>(
      restored === undefined ? [] : restorePendingDeaths(stored, observation),
    );
    const explorationFrontiers = yield* Ref.make<
      Readonly<Record<string, BeatGameExplorationFrontier>>
    >(stored.memory.explorationFrontiers ?? {});
    const checkedRecoveryContainers = yield* Ref.make<ReadonlySet<string>>(new Set());
    const paused = yield* Ref.make(false);
    const stopped = yield* Deferred.make<undefined>();
    const checkpointMutex = yield* Semaphore.make(1);
    const eventMutex = yield* Semaphore.make(1);
    const events = new ReplayBroadcast<BeatGameEvent>(128);
    const snapshots = new ReplayBroadcast<BeatGameSnapshot>(1);
    const sequence = yield* Ref.make(0n);
    const lastDamage = yield* Ref.make<
      Extract<BeatGameDriverEvent, { type: "bot-damaged" }> | undefined
    >(undefined);
    const state: RunState = {
      driver: withGoldCalmedPiglins(driver, observationRef, lastDamage),
      store,
      coordinator,
      strategy,
      hooks: options.hooks ?? {},
      checkpoint: checkpointRef,
      observation: observationRef,
      lastLivingObservation,
      portalScanDimension: yield* Ref.make<string | undefined>(undefined),
      abandonedActionRounds: yield* Ref.make<ReadonlyMap<string, number>>(new Map()),
      lastDamage,
      failedEscapes: yield* Ref.make({ count: 0, atMs: 0 }),
      unreachableThreats: yield* Ref.make<ReadonlyMap<string, number>>(new Map()),
      failedCrafts: yield* Ref.make<ReadonlyMap<string, FailedCraft>>(new Map()),
      fetchingTableWood: yield* Ref.make(false),
      pendingDeaths,
      explorationFrontiers,
      checkedRecoveryContainers,
      paused,
      stopped,
      checkpointMutex,
      eventMutex,
      events,
      snapshots,
      sequence,
      startedAtMs: yield* Clock.currentTimeMillis,
    };
    yield* publishSnapshot(state);
    yield* emit(
      state,
      restored === undefined
        ? { type: "run-started" }
        : { type: "checkpoint-restored", revision: stored.revision },
    );
    if (restored !== undefined) {
      yield* emit(state, { type: "run-started" });
    }
    yield* emit(state, {
      type: "team-role-changed",
      role: member.role,
    });
    for (const requirement of stored.planner.requirements) {
      yield* emit(state, {
        type: "requirement-discovered",
        requirement,
      });
    }

    yield* Effect.forkScoped(monitorDriverEvents(state));
    const runtime = runLoop(state).pipe(
      Effect.ensuring(
        Effect.all(
          [
            coordinator.unregister(teamId, driver.botId).pipe(Effect.ignore),
            events.end(),
            snapshots.end(),
          ],
          { discard: true, concurrency: 1 },
        ),
      ),
    );
    const fiber = yield* Effect.forkScoped(runtime);

    const changeStatus = (
      status: BeatGameRunStatus,
      event: EventInput,
    ): Effect.Effect<void, BeatGameError> =>
      Effect.gen(function* () {
        const checkpoint = yield* persist(state, (current) => ({
          ...current,
          planner: {
            ...current.planner,
            status,
            updatedAt: new Date().toISOString(),
          },
        }));
        yield* coordinator.updateMember(teamId, driver.botId, checkpoint.planner.phase, status);
        yield* emit(state, event);
      });

    return {
      id: runId,
      teamId,
      instanceId: driver.instanceId,
      botId: driver.botId,
      events: events.stream,
      snapshots: snapshots.stream,
      awaitCompletion: Fiber.join(fiber),
      pause: Ref.get(paused).pipe(
        Effect.flatMap((isPaused) =>
          isPaused
            ? Effect.void
            : Ref.set(paused, true).pipe(
                Effect.andThen(changeStatus(BeatGameRunStatus.PAUSED, { type: "run-paused" })),
              ),
        ),
      ),
      resume: Ref.get(paused).pipe(
        Effect.flatMap((isPaused) =>
          !isPaused
            ? Effect.void
            : Ref.set(paused, false).pipe(
                Effect.andThen(changeStatus(BeatGameRunStatus.RUNNING, { type: "run-resumed" })),
              ),
        ),
      ),
      stop: Deferred.isDone(stopped).pipe(
        Effect.flatMap((isStopped) =>
          isStopped
            ? Effect.void
            : Ref.set(paused, false).pipe(
                Effect.andThen(changeStatus(BeatGameRunStatus.STOPPED, { type: "run-stopped" })),
                Effect.andThen(Deferred.succeed(stopped, undefined)),
                Effect.asVoid,
              ),
        ),
      ),
      snapshot: currentSnapshot(state),
    };
  });
}

export function beatGameTeam(
  bots: readonly SoulFireBot[],
  options: BeatGameTeamRunOptions = {},
): Effect.Effect<BeatGameTeamRun, BeatGameError, Scope.Scope> {
  return Effect.forEach(bots, liveEnvironmentDriver, { concurrency: "unbounded" }).pipe(
    Effect.flatMap((drivers) => beatGameTeamWithDrivers(drivers, options)),
  );
}

function liveEnvironmentDriver(
  bot: SoulFireBot,
): Effect.Effect<BeatGameDriver, never, Scope.Scope> {
  const fallback = makeSoulFireBeatGameDriver(bot);
  return bot.observe({ filter: { includeEnvironment: true } }).pipe(
    Effect.map((session) =>
      makeSoulFireBeatGameDriver(bot, {
        environment: Effect.sync(() => {
          const environment = session.state.environment;
          return {
            ...(environment.gameTime === undefined ? {} : { gameTime: environment.gameTime }),
            ...(environment.raining === undefined ? {} : { raining: environment.raining }),
          };
        }),
      }),
    ),
    Effect.orElseSucceed(() => fallback),
  );
}

export function beatGameTeamWithDrivers(
  drivers: readonly BeatGameDriver[],
  options: BeatGameTeamRunOptions = {},
): Effect.Effect<BeatGameTeamRun, BeatGameError, Scope.Scope> {
  if (drivers.length === 0) {
    return Effect.die(new RangeError("beatGameTeamWithDrivers needs at least one driver"));
  }
  return Effect.gen(function* () {
    // oxlint-disable-next-line effecttsgo/crypto-random-uuid-in-effect -- Keep cryptographically generated identifiers across supported runtimes.
    const teamId = options.teamId ?? crypto.randomUUID();
    const store = options.checkpointStore ?? new InMemoryBeatGameCheckpointStore();
    const coordinator = options.coordinator ?? new InMemoryBeatGameCoordinator();
    const runs = yield* Effect.forEach(
      drivers,
      (driver, index) =>
        beatGameWithDriver(driver, {
          runId: `${teamId}-${driver.botId}`,
          team: {
            teamId,
            role: roleForIndex(index),
          },
          ...(options.strategy === undefined ? {} : { strategy: options.strategy }),
          checkpointStore: store,
          coordinator,
          ...(options.hooks === undefined ? {} : { hooks: options.hooks }),
        }),
      { concurrency: "unbounded" },
    );
    return {
      teamId,
      runs,
      awaitCompletion: Effect.forEach(runs, ({ awaitCompletion }) => awaitCompletion, {
        concurrency: "unbounded",
      }),
      pause: Effect.forEach(runs, ({ pause }) => pause, {
        concurrency: "unbounded",
        discard: true,
      }),
      resume: Effect.forEach(runs, ({ resume }) => resume, {
        concurrency: "unbounded",
        discard: true,
      }),
      stop: Effect.forEach(runs, ({ stop }) => stop, {
        concurrency: "unbounded",
        discard: true,
      }),
    };
  });
}

function runLoop(state: RunState): Effect.Effect<BeatGameResult, BeatGameError> {
  return Effect.gen(function* () {
    yield* persist(state, (checkpoint) => ({
      ...checkpoint,
      planner: {
        ...checkpoint.planner,
        status: BeatGameRunStatus.RUNNING,
        updatedAt: new Date().toISOString(),
      },
    }));
    for (;;) {
      yield* awaitRunnable(state);
      const observation = yield* cancellable(state, observeWithRecovery(state));
      let checkpoint = yield* Ref.get(state.checkpoint);
      const previousPhase = checkpoint.planner.phase;
      const previousRequirements = new Map(
        checkpoint.planner.requirements.map((requirement) => [requirement.key, requirement]),
      );
      checkpoint = yield* persist(state, (current) => {
        const recovered = shouldRepairCatastrophicInventoryLoss(current, observation)
          ? resetAfterCatastrophicInventoryLoss(current, observation)
          : current;
        return {
          ...recovered,
          connectionEpoch: observation.player.connectionEpoch,
          planner: plannerWithObservation(
            recovered.planner,
            observation,
            state.strategy,
            recovered,
          ),
        };
      });
      yield* emit(state, {
        type: "observation-recorded",
        observedAt: observation.observedAt,
        connectionEpoch: observation.player.connectionEpoch,
        playerRevision: observation.player.revision.toString(),
        inventoryRevision: observation.inventory.revision.toString(),
      });
      yield* rememberArrivalPortal(state, observation);
      if (checkpoint.planner.phase !== previousPhase) {
        yield* state.coordinator.updateMember(
          checkpoint.teamId,
          checkpoint.botId,
          checkpoint.planner.phase,
          BeatGameRunStatus.RUNNING,
        );
        yield* emit(state, {
          type: "phase-changed",
          previous: previousPhase,
          current: checkpoint.planner.phase,
        });
        yield* emit(state, {
          type: "objective-changed",
          objective: checkpoint.planner.objective,
        });
        yield* emit(state, {
          type: "diagnostic",
          message: "Restarted Overworld preparation after catastrophic inventory loss",
        });
      }
      checkpoint = yield* mergeSharedDiscoveries(state, checkpoint);
      for (const requirement of checkpoint.planner.requirements) {
        const previous = previousRequirements.get(requirement.key);
        if (
          previous === undefined ||
          previous.currentCount !== requirement.currentCount ||
          previous.targetCount !== requirement.targetCount ||
          previous.satisfied !== requirement.satisfied
        ) {
          yield* emit(state, {
            type:
              previous === undefined
                ? "requirement-discovered"
                : requirement.satisfied && !previous.satisfied
                  ? "requirement-satisfied"
                  : "requirement-updated",
            requirement,
          });
        }
        yield* state.coordinator.publishRequirement(
          checkpoint.teamId,
          checkpoint.botId,
          requirement.key,
          Math.max(0, requirement.targetCount - requirement.currentCount),
        );
      }
      if (checkpoint.planner.phase === BeatGamePhase.COMPLETE) {
        return yield* completeRun(state);
      }
      if (!observation.player.dead) {
        yield* wearCarriedArmor(state, observation).pipe(
          Effect.catchTag("BeatGameDriverError", () => Effect.void),
        );
      }
      const decision = decideBeatGameAction({
        checkpoint,
        observation,
        strategy: state.strategy,
      });
      if (decision.type === "advance-phase") {
        yield* advancePhase(state, decision.to);
        continue;
      }
      const liveObservation = yield* Ref.get(state.observation);
      const urgentCorpseRecovery =
        observation.player.dead &&
        !liveObservation.player.dead &&
        isRecentDeathObservation(observation.observedAt) &&
        !(yield* hasGuardedCorpseAtNight(state, liveObservation).pipe(
          Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
        ));
      const nightShelterNeeded = urgentCorpseRecovery
        ? false
        : yield* shouldTakeNightShelter(state, liveObservation, {
            leavesCover: decision.type === "recover-death",
          }).pipe(
            Effect.catch((error) =>
              emit(state, {
                type: "diagnostic",
                message: "Could not evaluate night shelter conditions",
                data: { error: error.message },
              }).pipe(Effect.as(false)),
            ),
          );
      if (nightShelterNeeded) {
        yield* cancellable(
          state,
          runNightShelterAction(state, liveObservation).pipe(
            Effect.catch((error) =>
              emit(state, {
                type: "diagnostic",
                message: "Night shelter attempt failed",
                data: { error: error.message },
              }),
            ),
          ),
        );
        continue;
      }
      const claim = yield* claimAction(state, decision);
      if (claim === undefined) {
        yield* Effect.sleep(state.strategy.observationPollMs);
        continue;
      }
      yield* runDecisionWithRetry(state, decision, observation).pipe(
        Effect.ensuring(releaseActionClaim(state, claim)),
      );
      yield* Effect.sleep(
        Math.max(1, Math.min(MINIMUM_RECOVERY_POLL_MS, state.strategy.observationPollMs)),
      );
    }
  }).pipe(
    Effect.catchIf(
      (error) => !(error instanceof BeatGameCancelled),
      (error) => markFailed(state, error).pipe(Effect.andThen(Effect.fail(error))),
    ),
  );
}

function runNightShelterAction(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    yield* persist(state, (checkpoint) => {
      const now = new Date().toISOString();
      const withSkill = startDurableSkillIfIdle(
        checkpoint,
        BeatGameDurableSkillKind.PROTECTED_STRUCTURE,
        NIGHT_SHELTER_ACTION,
        now,
      );
      return {
        ...withSkill,
        planner: {
          ...withSkill.planner,
          currentAction: NIGHT_SHELTER_ACTION,
          currentActionId: crypto.randomUUID(),
          retryCount: 0,
          updatedAt: now,
        },
      };
    });
    yield* emit(state, {
      type: "action-started",
      action: NIGHT_SHELTER_ACTION,
      attempt: 1,
    });
    const reachedMorning = yield* shelterUntilMorning(state, observation);
    yield* persist(state, (checkpoint) =>
      reachedMorning
        ? completeDurableSkill(
            checkpoint,
            NIGHT_SHELTER_ACTION,
            "morning reached from a protected shelter",
            new Date().toISOString(),
          )
        : suspendDurableSkill(checkpoint, "shelter ended before morning", new Date().toISOString()),
    );
    yield* emit(state, {
      type: reachedMorning ? "action-succeeded" : "action-failed",
      action: NIGHT_SHELTER_ACTION,
      attempt: 1,
      ...(reachedMorning ? {} : { detail: "The shelter attempt ended before morning" }),
    });
  }).pipe(
    Effect.ensuring(
      persist(state, (checkpoint) =>
        checkpoint.planner.currentAction !== NIGHT_SHELTER_ACTION
          ? checkpoint
          : {
              ...checkpoint,
              planner: withoutCurrentAction({
                ...checkpoint.planner,
                retryCount: 0,
                updatedAt: new Date().toISOString(),
              }),
            },
      ).pipe(Effect.ignore),
    ),
  );
}

function shouldTakeNightShelter(
  state: RunState,
  observation: BeatGameObservation,
  options: { readonly leavesCover?: boolean } = {},
): Effect.Effect<boolean, BeatGameDriverError> {
  if (
    state.driver.environment === undefined ||
    observation.player.position.dimension !== "minecraft:overworld" ||
    observation.player.dead
  ) {
    return Effect.succeed(false);
  }
  return Effect.gen(function* () {
    const environment = yield* observeEnvironment(state);
    if (!isHostileNight(environment.gameTime)) {
      return false;
    }
    if (
      !(yield* hasGuardedCorpseAtNight(state, observation)) &&
      (yield* hasVisibleValuableCorpseDrops(state, observation))
    ) {
      return false;
    }
    // Cover only protects work that stays under it. A restarted run sat in
    // its own sealed shelter, took the roof as cover, and walked out to an
    // old corpse at night with no weapon.
    if (
      options.leavesCover !== true &&
      ((yield* hasSolidNightTravelCover(state.driver, observation)) ||
        (yield* isSafelyBelowOverworldSurface(state.driver, observation)))
    ) {
      return false;
    }
    // A fast run keeps working through the night once it can fight back and
    // eat, instead of losing ten minutes in a shelter. A sword alone was not
    // enough: a live run died to a night mob group, which cost far more time
    // than the shelter would have.
    const protectedForNightTravel =
      state.strategy.riskProfile === BeatGameRiskProfile.FAST
        ? hasItemInInventoryOrEquipment(observation, "minecraft:shield") &&
          hasMeleeWeapon(observation) &&
          observation.player.health >= state.strategy.minimumHealth &&
          observation.player.food > URGENT_HUNGER_FOOD_LEVEL &&
          countNightShelterItems(observation, EDIBLE_FOOD_ITEM_IDS) >= 1
        : hasItemInInventoryOrEquipment(observation, "minecraft:shield") &&
          hasMeleeWeapon(observation) &&
          observation.player.health >= state.strategy.minimumHealth &&
          observation.player.food > URGENT_HUNGER_FOOD_LEVEL &&
          countNightShelterItems(observation, EDIBLE_FOOD_ITEM_IDS) >= 4;
    if (protectedForNightTravel) {
      return false;
    }
    return true;
  });
}

function hasSolidNightTravelCover(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  const player = observation.player.position;
  return queryExactBlock(driver, {
    x: Math.floor(player.x),
    y: Math.floor(player.y) + 2,
    z: Math.floor(player.z),
    dimension: player.dimension,
  }).pipe(
    Effect.map(
      (overhead) =>
        overhead !== undefined && !overhead.replaceable && !isTreeCanopySurface(overhead.blockId),
    ),
  );
}

function isSafelyBelowOverworldSurface(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  const player = observation.player.position;
  return driver.sampleSurface(player, 0, 1).pipe(
    Effect.map((columns) => {
      const playerX = Math.floor(player.x);
      const playerZ = Math.floor(player.z);
      const column = columns.find(
        (candidate) =>
          candidate.loaded &&
          candidate.x === playerX &&
          candidate.z === playerZ &&
          candidate.surfaceY !== undefined,
      );
      if (column?.surfaceY === undefined) {
        return false;
      }
      const surfaceCover = column.surfaceY - Math.floor(player.y);
      return (
        surfaceCover >= NIGHT_SHELTER_MINIMUM_SURFACE_COVER &&
        (!isTreeCanopySurface(column.blockId) || surfaceCover >= NIGHT_SHELTER_DEEP_SURFACE_COVER)
      );
    }),
  );
}

function isTreeCanopySurface(blockId: string | undefined): boolean {
  return (
    blockId?.endsWith("_leaves") === true ||
    blockId?.endsWith("_log") === true ||
    blockId?.endsWith("_stem") === true
  );
}

interface NightBedPlacementSite {
  readonly stand: BeatGameBlockPosition;
  readonly foot: BeatGameBlockPosition;
  readonly support: BeatGameBlockPosition;
}

type SleepOutcome = "slept" | "attacked" | "unavailable";

function trySleepThroughNight(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<SleepOutcome, BeatGameError | BeatGameDriverError> {
  if (state.driver.environment === undefined) {
    return Effect.succeed("unavailable" as const);
  }
  return Effect.gen(function* () {
    const environment = yield* observeEnvironment(state);
    if (!isHostileNight(environment.gameTime)) {
      return "slept";
    }
    let current = observation;
    let beds = yield* findNearbyNightBeds(state.driver, current);
    if (beds.length === 0) {
      let bedItemId = carriedBedItemId(current);
      if (bedItemId === undefined) {
        const craftableBed = craftableBedItemId(current);
        if (craftableBed !== undefined) {
          const crafted = yield* craftWithTable(state, current, craftableBed, 1).pipe(
            Effect.result,
          );
          if (crafted._tag === "Success") {
            current = yield* state.driver.observe;
            bedItemId = carriedBedItemId(current);
          }
        }
      }
      if (bedItemId !== undefined) {
        const placedBed = yield* placeNightBed(state, current, bedItemId);
        if (placedBed !== undefined) {
          beds = [placedBed];
        }
      }
    }
    for (const bed of beds) {
      const healthBeforeBed = (yield* state.driver.observe).player.health;
      const sleepAttempt = yield* state.driver
        .runTask(
          {
            type: "sleep",
            bed: bed.position,
            searchRadius: NIGHT_BED_SEARCH_RADIUS,
            waitUntilPossible: false,
            retryIntervalTicks: 20,
          },
          {
            ...state.strategy.path,
            allowMining: false,
            allowPlacing: false,
            avoidFluids: true,
            sprint: true,
            maxFallDistance: Math.min(
              state.strategy.path.maxFallDistance,
              MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
            ),
            maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 30_000),
          },
        )
        .pipe(Effect.result);
      if (sleepAttempt._tag === "Failure") {
        continue;
      }
      const outcome = yield* waitForMorningAfterBed(state, healthBeforeBed);
      if (outcome === "attacked") {
        return "attacked";
      }
      if (outcome === "morning") {
        yield* persist(state, (checkpoint) =>
          rememberWorldPlace(checkpoint, "SHELTER", bed.position, 1),
        );
        yield* emit(state, {
          type: "diagnostic",
          message: "Slept through the hostile night in a bed",
          data: { position: bed.position },
        });
        yield* pickUpBed(state, bed);
        return "slept";
      }
    }
    return "unavailable";
  });
}

/// Takes the bed along, so the next night does not need new wool.
function pickUpBed(
  state: RunState,
  bed: BeatGameBlockObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    yield* state.driver.withControl(
      state.driver.act({ type: "dig-block", position: bed.position }),
    );
    yield* collectNearbyDrops(state.driver, {
      itemIds: BED_ITEM_IDS,
      radius: 8,
      maximumDrops: 1,
      settleDelayMs: 250,
      path: { ...state.strategy.path, allowPlacing: false, avoidFluids: true },
    });
  }).pipe(
    Effect.catchTag("BeatGameDriverError", (error) =>
      emit(state, {
        type: "diagnostic",
        message: "Could not pick up the bed after sleeping",
        data: { position: bed.position, error: error.message },
      }),
    ),
  );
}

function findNearbyNightBeds(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
): Effect.Effect<readonly BeatGameBlockObservation[], BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: observation.player.position,
      radius: NIGHT_BED_SEARCH_RADIUS,
      selector: { blockIds: BED_ITEM_IDS },
      maximumResults: 32,
    })
    .pipe(
      Effect.map((beds) =>
        beds
          .filter(({ blockId }) => BED_ITEM_IDS.includes(blockId))
          .toSorted(
            (left, right) =>
              distanceSquared(observation.player.position, left.position) -
              distanceSquared(observation.player.position, right.position),
          ),
      ),
    );
}

function carriedBedItemId(observation: BeatGameObservation): string | undefined {
  return BED_ITEM_IDS.find((itemId) => (observation.inventory.counts[itemId] ?? 0) > 0);
}

function craftableBedItemId(observation: BeatGameObservation): string | undefined {
  const plankCount = PLANK_ITEM_IDS.reduce(
    (count, itemId) => count + (observation.inventory.counts[itemId] ?? 0),
    0,
  );
  const logCount = LOG_ITEM_IDS.reduce(
    (count, itemId) => count + (observation.inventory.counts[itemId] ?? 0),
    0,
  );
  if (plankCount + logCount * 4 < 3) {
    return undefined;
  }
  const color = BED_COLORS.find(
    (candidate) =>
      (observation.inventory.counts[`minecraft:${candidate}_wool`] ?? 0) >= BED_WOOL_COUNT,
  );
  return color === undefined ? undefined : `minecraft:${color}_bed`;
}

function placeNightBed(
  state: RunState,
  observation: BeatGameObservation,
  bedItemId: string,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const sites = yield* findNightBedPlacementSites(state.driver, observation);
    for (const site of sites) {
      const attempt = yield* Effect.gen(function* () {
        yield* state.driver.pathfind(
          {
            x: site.stand.x + 0.5,
            y: site.stand.y,
            z: site.stand.z + 0.5,
            dimension: site.stand.dimension,
          },
          0.5,
          {
            ...state.strategy.path,
            allowMining: false,
            allowPlacing: false,
            avoidFluids: true,
            maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 30_000),
          },
        );
        yield* state.driver.act({
          type: "select-item",
          selector: { itemIds: [bedItemId] },
        });
        yield* state.driver.act({
          type: "place-block",
          against: site.support,
          face: "up",
          hand: "main",
        });
        for (
          let confirmation = 0;
          confirmation < NIGHT_BED_PLACEMENT_CONFIRMATION_ATTEMPTS;
          confirmation += 1
        ) {
          const placed = yield* queryExactBlock(state.driver, site.foot);
          if (placed !== undefined && BED_ITEM_IDS.includes(placed.blockId)) {
            return placed;
          }
          yield* Effect.sleep(100);
        }
        return undefined;
      }).pipe(Effect.result);
      if (attempt._tag === "Success" && attempt.success !== undefined) {
        return attempt.success;
      }
      const current = yield* state.driver.observe;
      if (current.player.dead || carriedBedItemId(current) === undefined) {
        return undefined;
      }
    }
    return undefined;
  });
}

function findNightBedPlacementSites(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
): Effect.Effect<readonly NightBedPlacementSite[], BeatGameDriverError> {
  const origin = {
    x: Math.floor(observation.player.position.x),
    y: Math.floor(observation.player.position.y),
    z: Math.floor(observation.player.position.z),
    dimension: observation.player.position.dimension,
  };
  return driver
    .queryBlocks({
      center: blockCenter(origin),
      radius: NIGHT_BED_PLACEMENT_SEARCH_RADIUS + 4,
      selector: {},
      maximumResults: 4096,
    })
    .pipe(
      Effect.map((blocks) => {
        const byPosition = new Map(
          blocks.map((block) => [
            `${block.position.x}:${block.position.y}:${block.position.z}`,
            block,
          ]),
        );
        const blockAt = (position: BeatGameBlockPosition) =>
          byPosition.get(`${position.x}:${position.y}:${position.z}`);
        const directions = [
          { x: 0, z: 1 },
          { x: 1, z: 0 },
          { x: 0, z: -1 },
          { x: -1, z: 0 },
        ] as const;
        const candidates: NightBedPlacementSite[] = [];
        for (let radius = 0; radius <= NIGHT_BED_PLACEMENT_SEARCH_RADIUS; radius += 1) {
          for (let xOffset = -radius; xOffset <= radius; xOffset += 1) {
            for (let zOffset = -radius; zOffset <= radius; zOffset += 1) {
              if (radius > 0 && Math.max(Math.abs(xOffset), Math.abs(zOffset)) !== radius) {
                continue;
              }
              for (const direction of directions) {
                const stand = {
                  ...origin,
                  x: origin.x + xOffset,
                  z: origin.z + zOffset,
                };
                const foot = {
                  ...origin,
                  x: stand.x + direction.x,
                  z: stand.z + direction.z,
                };
                const head = {
                  ...origin,
                  x: foot.x + direction.x,
                  z: foot.z + direction.z,
                };
                const support = { ...foot, y: foot.y - 1 };
                const headSupport = { ...head, y: head.y - 1 };
                const standSupport = { ...stand, y: stand.y - 1 };
                const standBlock = blockAt(stand);
                const standAbove = blockAt({ ...stand, y: stand.y + 1 });
                const footBlock = blockAt(foot);
                const footAbove = blockAt({ ...foot, y: foot.y + 1 });
                const headBlock = blockAt(head);
                const headAbove = blockAt({ ...head, y: head.y + 1 });
                const standFloor = blockAt(standSupport);
                const footFloor = blockAt(support);
                const headFloor = blockAt(headSupport);
                if (
                  standBlock?.replaceable === true &&
                  standAbove?.replaceable === true &&
                  footBlock?.replaceable === true &&
                  footAbove?.replaceable === true &&
                  headBlock?.replaceable === true &&
                  headAbove?.replaceable === true &&
                  standFloor?.solid === true &&
                  footFloor?.solid === true &&
                  headFloor?.solid === true
                ) {
                  candidates.push({ stand, foot, support });
                }
              }
            }
          }
        }
        return candidates;
      }),
    );
}

type BedWaitOutcome = "morning" | "attacked" | "timeout";

function waitForMorningAfterBed(
  state: RunState,
  healthBeforeBed: number,
): Effect.Effect<BedWaitOutcome, BeatGameDriverError> {
  return Effect.gen(function* () {
    const deadline = (yield* Clock.currentTimeMillis) + NIGHT_BED_MORNING_TIMEOUT_MS;
    let daylightConfirmations = 0;
    let previousHealth = healthBeforeBed;
    while ((yield* Clock.currentTimeMillis) < deadline) {
      const environment = yield* observeEnvironment(state);
      daylightConfirmations =
        environment.gameTime !== undefined && !isHostileNight(environment.gameTime)
          ? daylightConfirmations + 1
          : 0;
      if (daylightConfirmations >= NIGHT_SHELTER_DAYLIGHT_CONFIRMATIONS) {
        return "morning";
      }
      const current = yield* state.driver.observe;
      // A hit wakes the bot. A live run lay waiting for morning beside its
      // bed while a zombie hit it to death, so damage ends the wait.
      if (current.player.dead || current.player.health < previousHealth) {
        return "attacked";
      }
      previousHealth = current.player.health;
      yield* Effect.sleep(250);
    }
    return "timeout";
  });
}

function waitForImmediateThreat(state: RunState): Effect.Effect<void> {
  return Effect.gen(function* () {
    for (;;) {
      yield* Effect.sleep(Math.max(100, state.strategy.observationPollMs));
      const threat = yield* state.driver.observe.pipe(
        Effect.flatMap((current) =>
          current.player.dead ? Effect.undefined : findImmediateThreat(state, current),
        ),
        Effect.catchTag("BeatGameDriverError", () => Effect.undefined),
      );
      if (threat !== undefined) {
        return;
      }
    }
  });
}

function shelterUntilMorning(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    let shelterObservation = observation;
    const nearbyThreat = yield* findImmediateThreat(state, shelterObservation);
    if (nearbyThreat !== undefined) {
      yield* emit(state, {
        type: "diagnostic",
        message: "Creating distance from nearby hostiles before sheltering",
        data: {
          position: shelterObservation.player.position,
          threat: nearbyThreat.target,
          response: nearbyThreat.response,
        },
      });
      yield* respondToNightShelterTravelThreat(state, nearbyThreat);
      shelterObservation = yield* state.driver.observe;
      if (shelterObservation.player.dead) {
        return false;
      }
      const remainingThreat = yield* findImmediateThreat(state, shelterObservation);
      if (remainingThreat !== undefined) {
        yield* emit(state, {
          type: "diagnostic",
          message: "A nearby hostile still prevents safe shelter construction",
          data: {
            position: shelterObservation.player.position,
            threat: remainingThreat.target,
            response: remainingThreat.response,
          },
        });
        return false;
      }
    }
    // A live run ate here while a zombie hit it from 7 health to 1, so
    // nearby hostiles come first.
    const needsRecoveryMeal =
      shelterObservation.player.health < state.strategy.minimumHealth &&
      shelterObservation.player.food < 18;
    if (
      (shelterObservation.player.food <= state.strategy.eatBelowFood || needsRecoveryMeal) &&
      hasUsableFood(shelterObservation)
    ) {
      yield* eatUnlessEndangered(state, {
        foodItemIds: preferredUsableFoodItemIds(shelterObservation),
        foodLevel: 20,
        maximumMeals: 1,
        completeWhenNoFood: true,
        path: state.strategy.path,
      });
      shelterObservation = yield* state.driver.observe;
      if (shelterObservation.player.dead) {
        return false;
      }
    }
    if (yield* isPlayerInFluid(state.driver, shelterObservation.player.position)) {
      const reachedDrySurface = yield* swimToNearbyDrySurface(state);
      if (!reachedDrySurface) {
        shelterObservation = yield* state.driver.observe;
        yield* emit(state, {
          type: "diagnostic",
          message:
            "Could not reach dry ground for a night shelter; recovering to the water surface",
          data: { position: shelterObservation.player.position },
        });
        yield* escapeToOverworldSurface(state, shelterObservation.player.position).pipe(
          Effect.catchTag("BeatGameDriverError", (error) =>
            emit(state, {
              type: "diagnostic",
              message: "Could not recover to the water surface",
              data: {
                position: shelterObservation.player.position,
                error: error.message,
              },
            }),
          ),
        );
        yield* Effect.sleep(NIGHT_SHELTER_POLL_MS);
        return false;
      }
      shelterObservation = yield* state.driver.observe;
    }
    // A live bot placed its bed with a creeper nine blocks off, just
    // outside the check above. Three placements failed while it walked up,
    // and its blast killed the bot, so a threat that closes in ends the bed.
    const bedOutcome = yield* Effect.raceFirst(
      trySleepThroughNight(state, shelterObservation).pipe(
        Effect.catch((error) =>
          emit(state, {
            type: "diagnostic",
            message: "Could not use a bed before constructing a night shelter",
            data: {
              position: shelterObservation.player.position,
              error: error.message,
            },
          }).pipe(Effect.as<SleepOutcome>("unavailable")),
        ),
      ),
      waitForImmediateThreat(state).pipe(Effect.as<SleepOutcome>("attacked")),
    );
    if (bedOutcome === "slept") {
      return true;
    }
    // An attacker beside the bed would keep hitting the bot while it builds
    // walls, so the shelter ends and the main loop answers the attack.
    if (bedOutcome === "attacked") {
      return false;
    }
    shelterObservation = yield* state.driver.observe;
    const initialPlayerBlock = {
      x: Math.floor(shelterObservation.player.position.x),
      y: Math.floor(shelterObservation.player.position.y),
      z: Math.floor(shelterObservation.player.position.z),
      dimension: shelterObservation.player.position.dimension,
    };
    const initialOverhead = yield* queryExactBlock(state.driver, {
      ...initialPlayerBlock,
      y: initialPlayerBlock.y + 2,
    });
    let stableShelterObservation =
      initialOverhead !== undefined && !initialOverhead.replaceable
        ? shelterObservation
        : yield* prepareStableNightShelterSite(state, shelterObservation);
    let enclosure: NightShelterEnclosure | undefined;
    if (stableShelterObservation === undefined) {
      const current = yield* state.driver.observe;
      enclosure = current.player.dead
        ? undefined
        : yield* encloseNightShelterInPlace(state, current);
      if (enclosure === undefined) {
        yield* emit(state, {
          type: "diagnostic",
          message: "Could not find a stable column for a night shelter",
          data: { position: current.player.position },
        });
        yield* Effect.sleep(NIGHT_SHELTER_POLL_MS);
        return false;
      }
      yield* emit(state, {
        type: "diagnostic",
        message: "Enclosed the bot in place because no shelter column could be dug",
        data: { position: current.player.position, placed: enclosure.placed },
      });
      stableShelterObservation = yield* state.driver.observe;
    }
    shelterObservation = stableShelterObservation;
    const surfaceOrigin = shelterObservation.player.position;
    const playerBlock = {
      x: Math.floor(surfaceOrigin.x),
      y: Math.floor(surfaceOrigin.y),
      z: Math.floor(surfaceOrigin.z),
      dimension: surfaceOrigin.dimension,
    };
    yield* persist(state, (checkpoint) =>
      checkpoint.activeSkill?.action === NIGHT_SHELTER_ACTION
        ? advanceDurableSkill(checkpoint, "construct-shelter", new Date().toISOString(), {
            targets: [playerBlock],
            protectedItemIds: [...NIGHT_SHELTER_BLOCK_ITEM_IDS],
            requiredResources: { shelterBlocks: 1 },
          })
        : checkpoint,
    );
    const overhead = yield* queryExactBlock(state.driver, {
      ...playerBlock,
      y: playerBlock.y + 2,
    });
    const alreadyCovered = overhead !== undefined && !overhead.replaceable;
    // The night shelter runs outside the action safety monitor, and digging a
    // shaft by hand takes seconds per block. A live bot was hit by a zombie
    // every second of its dig and died without a single threat check.
    const construction = alreadyCovered
      ? ({ type: "sealed", sealPosition: undefined } as const)
      : yield* Effect.raceFirst(
          digAndSealNightShelter(state, shelterObservation).pipe(
            Effect.map((sealPosition) => ({ type: "sealed", sealPosition }) as const),
          ),
          waitForNightShelterTravelThreat(state).pipe(
            Effect.map((threat) => ({ type: "threat", threat }) as const),
          ),
        );
    if (construction.type === "threat") {
      yield* respondToNightShelterTravelThreat(state, construction.threat);
      return false;
    }
    const sealPosition = construction.sealPosition;
    if (!alreadyCovered && sealPosition === undefined) {
      yield* emit(state, {
        type: "diagnostic",
        message: "Could not construct a sealed night shelter",
        data: { position: surfaceOrigin },
      });
      yield* state.driver
        .pathfind(surfaceOrigin, 1, {
          ...state.strategy.path,
          allowMining: true,
          allowPlacing: true,
          avoidFluids: true,
          maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 30_000),
        })
        .pipe(Effect.ignore);
      yield* Effect.sleep(NIGHT_SHELTER_POLL_MS);
      return false;
    }
    yield* emit(state, {
      type: "diagnostic",
      message: alreadyCovered
        ? "Waiting under existing cover until morning"
        : "Waiting in a sealed shelter until morning",
      data: { position: surfaceOrigin, sealPosition },
    });
    yield* persist(state, (checkpoint) =>
      checkpoint.activeSkill?.action === NIGHT_SHELTER_ACTION
        ? advanceDurableSkill(
            rememberWorldPlace(checkpoint, "SHELTER", surfaceOrigin, 1),
            "wait-for-morning",
            new Date().toISOString(),
            {
              targets: [playerBlock],
              protectedBlocks: sealPosition === undefined ? [] : [sealPosition],
              completedWorldChanges: sealPosition === undefined ? [] : [sealPosition],
            },
          )
        : rememberWorldPlace(checkpoint, "SHELTER", surfaceOrigin, 1),
    );
    let daylightConfirmations = 0;
    const waitDeadline = (yield* Clock.currentTimeMillis) + NIGHT_SHELTER_MAXIMUM_WAIT_MS;
    while (daylightConfirmations < NIGHT_SHELTER_DAYLIGHT_CONFIRMATIONS) {
      if ((yield* Clock.currentTimeMillis) >= waitDeadline) {
        yield* emit(state, {
          type: "diagnostic",
          message: "Leaving the night shelter because the night outlasted its maximum length",
          data: { position: surfaceOrigin, maximumWaitMs: NIGHT_SHELTER_MAXIMUM_WAIT_MS },
        });
        break;
      }
      const environment = yield* observeEnvironment(state);
      daylightConfirmations =
        environment.gameTime !== undefined && !isHostileNight(environment.gameTime)
          ? daylightConfirmations + 1
          : 0;
      const current = yield* state.driver.observe;
      if (current.player.dead) {
        return false;
      }
      if (
        current.player.health < state.strategy.minimumHealth &&
        current.player.food < 18 &&
        hasUsableFood(current)
      ) {
        yield* eatUnlessEndangered(state, {
          foodItemIds: preferredUsableFoodItemIds(current),
          foodLevel: 20,
          maximumMeals: 1,
          completeWhenNoFood: true,
          path: state.strategy.path,
        });
      }
      if (daylightConfirmations < NIGHT_SHELTER_DAYLIGHT_CONFIRMATIONS) {
        yield* Effect.sleep(NIGHT_SHELTER_POLL_MS);
      }
    }
    if (enclosure !== undefined && enclosure.exit.length > 0) {
      yield* openNightShelterEnclosure(state, enclosure);
    } else if (sealPosition !== undefined) {
      yield* state.driver.withControl(
        state.driver.act({
          type: "dig-block",
          position: sealPosition,
        }),
      );
      yield* state.driver
        .pathfind(surfaceOrigin, 1, {
          ...state.strategy.path,
          allowMining: true,
          allowPlacing: true,
          avoidFluids: true,
          minimumY: Math.floor(surfaceOrigin.y) - NIGHT_SHELTER_DEPTH - 2,
          maximumY: Math.floor(surfaceOrigin.y) + 2,
          maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 30_000),
        })
        .pipe(Effect.ignore);
    } else if (alreadyCovered) {
      const current = yield* state.driver.observe;
      yield* emit(state, {
        type: "diagnostic",
        message: "Leaving a resumed night shelter after morning",
        data: { position: current.player.position },
      });
      yield* leaveCoveredVerticalShaft(state, current, overhead);
    }
    yield* recoverIfLocallyEnclosed(
      state,
      "The morning shelter exit remained enclosed by surrounding terrain",
    );
    return true;
  });
}

function leaveCoveredVerticalShaft(
  state: RunState,
  observation: BeatGameObservation,
  overhead: BeatGameBlockObservation | undefined,
): Effect.Effect<void, BeatGameDriverError> {
  if (overhead === undefined || overhead.replaceable) {
    return returnToOverworldSurface(state, observation.player.position);
  }
  if (!overhead.diggable) {
    return Effect.fail(
      new BeatGameDriverError({
        operation: "leave-covered-shaft",
        code: "unreachable",
        retryable: true,
        message: `The covered shaft exit is blocked by ${overhead.blockId} at ${positionKey(
          overhead.position,
        )}`,
      }),
    );
  }
  return state.driver.withControl(
    Effect.gen(function* () {
      if (hasMiningPickaxe(observation)) {
        yield* state.driver.act({
          type: "select-item",
          selector: { itemIds: MINING_PICKAXE_ITEM_IDS },
        });
      }
      yield* state.driver.act({
        type: "dig-block",
        position: overhead.position,
      });
      yield* state.driver.pathfind(
        {
          x: overhead.position.x + 0.5,
          y: overhead.position.y + 1,
          z: overhead.position.z + 0.5,
          dimension: overhead.position.dimension,
        },
        1,
        {
          ...state.strategy.path,
          allowMining: true,
          allowPlacing: true,
          avoidFluids: true,
          minimumY: Math.floor(observation.player.position.y) - 1,
          maximumY: overhead.position.y + 2,
          maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 30_000),
        },
      );
    }),
  );
}

function prepareStableNightShelterSite(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<BeatGameObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    if (yield* isViableNightShelterColumn(state.driver, observation.player.position)) {
      return observation;
    }
    const columns = yield* state.driver.sampleSurface(
      observation.player.position,
      AIR_ESCAPE_SURFACE_SEARCH_RADIUS,
      1,
    );
    const candidates = selectStableSurfaceEscapeColumns(columns, observation.player.position);
    for (const candidate of candidates) {
      const target = {
        x: candidate.x + 0.5,
        y: candidate.surfaceY + 1,
        z: candidate.z + 0.5,
        dimension: observation.player.position.dimension,
      };
      if (!(yield* isViableNightShelterColumn(state.driver, target))) {
        continue;
      }
      const pathOutcome = yield* Effect.raceFirst(
        state.driver
          .pathfind(target, 0.25, {
            ...survivalPathPolicy(
              state.strategy.path,
              observation.player.health,
              state.strategy.minimumHealth,
            ),
            allowMining: false,
            allowPlacing: false,
            avoidFluids: true,
            sprint: observation.player.food > CRITICAL_HUNGER_FOOD_LEVEL,
            maxFallDistance: Math.min(
              state.strategy.path.maxFallDistance,
              MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
            ),
            maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 15_000),
          })
          .pipe(
            Effect.result,
            Effect.map((result) => ({ type: "path" as const, result })),
          ),
        waitForNightShelterTravelThreat(state).pipe(
          Effect.map((threat) => ({ type: "threat" as const, threat })),
        ),
      );
      if (pathOutcome.type === "threat") {
        yield* respondToNightShelterTravelThreat(state, pathOutcome.threat);
        return undefined;
      }
      if (pathOutcome.result._tag === "Failure") {
        continue;
      }
      const current = yield* state.driver.observe;
      if (
        current.player.dead ||
        Math.floor(current.player.position.x) !== candidate.x ||
        Math.floor(current.player.position.z) !== candidate.z ||
        (yield* isPlayerInFluid(state.driver, current.player.position)) ||
        !(yield* isViableNightShelterColumn(state.driver, current.player.position))
      ) {
        continue;
      }
      return current;
    }
    const relocated = yield* relocateFromRejectedNightShelterSite(state, observation);
    return relocated !== undefined &&
      (yield* isViableNightShelterColumn(state.driver, relocated.player.position))
      ? relocated
      : undefined;
  });
}

function relocateFromRejectedNightShelterSite(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<BeatGameObservation | undefined, BeatGameDriverError> {
  const origin = observation.player.position;
  const path = {
    ...survivalPathPolicy(
      state.strategy.path,
      observation.player.health,
      state.strategy.minimumHealth,
    ),
    allowMining: false,
    allowPlacing: false,
    avoidFluids: true,
    sprint: observation.player.food > CRITICAL_HUNGER_FOOD_LEVEL,
    maxFallDistance: Math.min(
      state.strategy.path.maxFallDistance,
      MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
    ),
    maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 15_000),
  };
  return Effect.gen(function* () {
    yield* emit(state, {
      type: "diagnostic",
      message: "Relocating after nearby night shelter columns were rejected",
      data: { position: origin },
    });
    const relocationOutcome = yield* Effect.raceFirst(
      advanceExplorationFrontier(
        state,
        origin,
        explorationPurpose("night-shelter-relocation", origin),
        NIGHT_SHELTER_RELOCATION_SCAN_RADIUS,
        path,
        true,
        false,
      ).pipe(
        Effect.result,
        Effect.map((result) => ({ type: "path" as const, result })),
      ),
      waitForNightShelterTravelThreat(state).pipe(
        Effect.map((threat) => ({ type: "threat" as const, threat })),
      ),
    );
    if (relocationOutcome.type === "threat") {
      yield* respondToNightShelterTravelThreat(state, relocationOutcome.threat);
      return undefined;
    }
    if (relocationOutcome.result._tag === "Failure") {
      yield* emit(state, {
        type: "diagnostic",
        message: "Could not relocate from the rejected night shelter site",
        data: {
          position: origin,
          error: relocationOutcome.result.failure.message,
        },
      });
      return undefined;
    }
    const current = yield* state.driver.observe;
    if (
      current.player.dead ||
      horizontalDistanceSquared(current.player.position, origin) <
        NIGHT_SHELTER_RELOCATION_MINIMUM_DISTANCE ** 2 ||
      (yield* isPlayerInFluid(state.driver, current.player.position))
    ) {
      return undefined;
    }
    return current;
  });
}

function waitForNightShelterTravelThreat(
  state: RunState,
): Effect.Effect<ImmediateThreat, BeatGameDriverError> {
  const poll = (): Effect.Effect<ImmediateThreat, BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) =>
        observation.player.dead
          ? Effect.fail(
              new BeatGameDriverError({
                operation: "night-shelter-travel",
                code: "bot-dead",
                retryable: true,
                message: "The bot died while relocating to a night shelter",
              }),
            )
          : findImmediateThreat(state, observation).pipe(
              Effect.filterOrElse(
                (threat) => threat !== undefined,
                () => Effect.suspend(poll),
              ),
            ),
      ),
    );
  return Effect.suspend(poll);
}

function respondToNightShelterTravelThreat(
  state: RunState,
  threat: ImmediateThreat,
): Effect.Effect<void, BeatGameDriverError> {
  return emit(state, {
    type: "diagnostic",
    message: "Interrupted night shelter work for a nearby hostile",
    data: {
      response: threat.response,
      target: threat.target,
    },
  }).pipe(
    Effect.andThen(
      threat.response === "flee"
        ? escapeFromTarget(state, threat.target, {
            continueEscapingWhenHit: true,
          })
        : defendAndRecover(state, threat.target),
    ),
  );
}

function isViableNightShelterColumn(
  driver: BeatGameDriver,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  const startingY = Math.floor(position.y);
  const shaft = {
    x: Math.floor(position.x),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
  return Effect.gen(function* () {
    for (let depth = 1; depth <= NIGHT_SHELTER_DEPTH; depth += 1) {
      const excavationPosition = {
        ...shaft,
        y: startingY - depth,
      };
      const block = yield* queryExactBlock(driver, excavationPosition);
      if (
        block === undefined ||
        block.replaceable ||
        !block.diggable ||
        block.properties["waterlogged"] === "true"
      ) {
        return false;
      }
      for (const neighbor of [
        { ...excavationPosition, x: excavationPosition.x + 1 },
        { ...excavationPosition, x: excavationPosition.x - 1 },
        { ...excavationPosition, z: excavationPosition.z + 1 },
        { ...excavationPosition, z: excavationPosition.z - 1 },
      ]) {
        const neighboringBlock = yield* queryExactBlock(driver, neighbor);
        if (neighboringBlock !== undefined && isPlayerFluidBlock(neighboringBlock.blockId)) {
          return false;
        }
      }
    }
    // The dig stops above a floor with nothing solid under it, so a column
    // over a cave or through a tree canopy never becomes a shelter. A live
    // run respawned on a dark oak canopy and retried such a column all night.
    const shaftBottom = yield* queryExactBlock(driver, {
      ...shaft,
      y: startingY - NIGHT_SHELTER_DEPTH - 1,
    });
    if (!isSolid(shaftBottom)) {
      return false;
    }
    const sealY = startingY - 1;
    for (const support of [
      { ...shaft, x: shaft.x + 1, y: sealY },
      { ...shaft, x: shaft.x - 1, y: sealY },
      { ...shaft, z: shaft.z + 1, y: sealY },
      { ...shaft, z: shaft.z - 1, y: sealY },
    ]) {
      const block = yield* queryExactBlock(driver, support);
      if (block !== undefined && !block.replaceable && block.properties["waterlogged"] !== "true") {
        return true;
      }
    }
    return false;
  });
}

function digAndSealNightShelter(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<BeatGameBlockPosition | undefined, BeatGameDriverError> {
  const startingY = Math.floor(observation.player.position.y);
  const shaft = {
    x: Math.floor(observation.player.position.x),
    z: Math.floor(observation.player.position.z),
    dimension: observation.player.position.dimension,
  };
  // A player whose hitbox still overlaps a neighboring block stands on it
  // and does not drop into the hole. A live bot taking cover mid-escape dug
  // its first block and stayed beside it under skeleton fire.
  const center = { ...shaft, x: shaft.x + 0.5, y: observation.player.position.y, z: shaft.z + 0.5 };
  const centered =
    Math.abs(observation.player.position.x - center.x) <= SHAFT_CENTER_TOLERANCE &&
    Math.abs(observation.player.position.z - center.z) <= SHAFT_CENTER_TOLERANCE;
  const moveToCenter = centered
    ? Effect.void
    : state.driver
        .pathfind(center, SHAFT_CENTER_TOLERANCE, {
          ...state.strategy.path,
          allowMining: false,
          allowPlacing: false,
          maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 1000),
        })
        .pipe(
          Effect.timeoutOrElse({ duration: SHAFT_CENTER_TIMEOUT_MS, orElse: () => Effect.void }),
          Effect.catchTag("BeatGameDriverError", () => Effect.void),
        );
  return moveToCenter.pipe(
    Effect.andThen(
      state.driver.withControl(
        Effect.gen(function* () {
          if (hasMiningPickaxe(observation)) {
            yield* state.driver.act({
              type: "select-item",
              selector: { itemIds: MINING_PICKAXE_ITEM_IDS },
            });
          }
          for (let depth = 1; depth <= NIGHT_SHELTER_DEPTH; depth += 1) {
            const current = yield* state.driver.observe;
            const floor = {
              ...shaft,
              y: Math.floor(current.player.position.y) - 1,
            };
            const floorBlock = yield* queryExactBlock(state.driver, floor);
            if (floorBlock === undefined || !floorBlock.diggable || floorBlock.replaceable) {
              return undefined;
            }
            // A live bot taking cover dug into a cave ceiling and died to the
            // fall. Stop above any floor with nothing solid under it.
            const support = yield* queryExactBlock(state.driver, { ...floor, y: floor.y - 1 });
            if (!isSolid(support)) {
              return undefined;
            }
            yield* state.driver.act({ type: "dig-block", position: floor });
            const expectedY = startingY - depth;
            let descended = false;
            for (let attempt = 0; attempt < NIGHT_SHELTER_DESCENT_ATTEMPTS; attempt += 1) {
              const latest = yield* state.driver.observe;
              if (latest.player.position.y <= expectedY + 0.35) {
                descended = true;
                break;
              }
              yield* Effect.sleep(100);
            }
            if (!descended) {
              return undefined;
            }
          }
          const sealPosition = {
            ...shaft,
            y: startingY - 1,
          };
          const placementFaces = [
            { against: { ...sealPosition, x: sealPosition.x + 1 }, face: "west" },
            { against: { ...sealPosition, x: sealPosition.x - 1 }, face: "east" },
            { against: { ...sealPosition, z: sealPosition.z + 1 }, face: "north" },
            { against: { ...sealPosition, z: sealPosition.z - 1 }, face: "south" },
          ] as const;
          let placement: (typeof placementFaces)[number] | undefined;
          for (const candidate of placementFaces) {
            const support = yield* queryExactBlock(state.driver, candidate.against);
            if (support !== undefined && !support.replaceable && support.solid !== false) {
              placement = candidate;
              break;
            }
          }
          if (placement === undefined) {
            return undefined;
          }
          const current = yield* state.driver.observe;
          if (countNightShelterItems(current, NIGHT_SHELTER_BLOCK_ITEM_IDS) === 0) {
            return undefined;
          }
          yield* state.driver.act({
            type: "select-item",
            selector: { itemIds: NIGHT_SHELTER_BLOCK_ITEM_IDS },
          });
          yield* state.driver.act({
            type: "place-block",
            against: placement.against,
            face: placement.face,
            hand: "main",
          });
          for (let attempt = 0; attempt < 20; attempt += 1) {
            const seal = yield* queryExactBlock(state.driver, sealPosition);
            if (seal !== undefined && !seal.replaceable) {
              return sealPosition;
            }
            yield* Effect.sleep(100);
          }
          return undefined;
        }),
      ),
    ),
  );
}

interface NightShelterEnclosure {
  readonly placed: readonly BeatGameBlockPosition[];
  // Blocks placed on one side, dug in the morning. Empty when only the roof
  // was placed, so the bot leaves through it.
  readonly exit: readonly BeatGameBlockPosition[];
}

const ENCLOSURE_SIDES = [
  { x: 1, z: 0 },
  { x: -1, z: 0 },
  { x: 0, z: 1 },
  { x: 0, z: -1 },
] as const;

// Powder snow is not replaceable but has no collision, so it neither walls
// the bot in nor supports a placed block.
const isSolid = (block: BeatGameBlockObservation | undefined): boolean =>
  block !== undefined &&
  !block.replaceable &&
  block.solid !== false &&
  !isPlayerFluidBlock(block.blockId);
const foodCount = (current: BeatGameObservation): number =>
  EDIBLE_FOOD_ITEM_IDS.reduce(
    (total, itemId) => total + (current.inventory.counts[itemId] ?? 0),
    0,
  );

// Places blocks around the bot's feet, head and roof where it stands. This
// works on open ground and in shafts whose floor cannot be dug.
// The roof of a pit one block too shallow sits on top of its walls, a face
// the eyes cannot see from inside. A live bot stood in such a pit all night,
// refused a roof every few seconds. One block deeper, the roof goes up
// against the sides of the walls instead.
function encloseNightShelterInPlace(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<NightShelterEnclosure | undefined, BeatGameDriverError> {
  return encloseNightShelterInPlaceOnce(state, observation).pipe(
    Effect.filterOrElse(
      (enclosure) => enclosure !== undefined,
      () =>
        sinkIntoShallowPit(state, observation).pipe(
          Effect.flatMap((sunk) =>
            sunk === undefined ? Effect.undefined : encloseNightShelterInPlaceOnce(state, sunk),
          ),
        ),
    ),
  );
}

function sinkIntoShallowPit(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<BeatGameObservation | undefined, BeatGameDriverError> {
  const feet = {
    x: Math.floor(observation.player.position.x),
    y: Math.floor(observation.player.position.y),
    z: Math.floor(observation.player.position.z),
    dimension: observation.player.position.dimension,
  };
  return Effect.gen(function* () {
    for (const y of [feet.y, feet.y + 1]) {
      for (const side of ENCLOSURE_SIDES) {
        const wall = yield* queryExactBlock(state.driver, {
          ...feet,
          x: feet.x + side.x,
          y,
          z: feet.z + side.z,
        });
        if (!isSolid(wall)) {
          return undefined;
        }
      }
    }
    const floorPosition = { ...feet, y: feet.y - 1 };
    const floor = yield* queryExactBlock(state.driver, floorPosition);
    const below = yield* queryExactBlock(state.driver, { ...feet, y: feet.y - 2 });
    if (floor === undefined || !floor.diggable || !isSolid(floor) || !isSolid(below)) {
      return undefined;
    }
    yield* state.driver.withControl(
      Effect.gen(function* () {
        if (hasMiningPickaxe(observation)) {
          yield* state.driver.act({
            type: "select-item",
            selector: { itemIds: MINING_PICKAXE_ITEM_IDS },
          });
        }
        yield* state.driver.act({ type: "dig-block", position: floorPosition });
      }),
    );
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const current = yield* state.driver.observe;
      if (current.player.position.y < feet.y - 0.5) {
        return current;
      }
      yield* Effect.sleep(100);
    }
    return undefined;
  });
}

function encloseNightShelterInPlaceOnce(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<NightShelterEnclosure | undefined, BeatGameDriverError> {
  const feet = {
    x: Math.floor(observation.player.position.x),
    y: Math.floor(observation.player.position.y),
    z: Math.floor(observation.player.position.z),
    dimension: observation.player.position.dimension,
  };
  const head = { ...feet, y: feet.y + 1 };
  const body = [feet, head];
  const roof = { ...head, y: head.y + 1 };
  const roofRing = ENCLOSURE_SIDES.map((side) => ({
    ...roof,
    x: roof.x + side.x,
    z: roof.z + side.z,
  }));
  const roofCorner = { ...roof, x: roof.x + 1 };
  return Effect.gen(function* () {
    const floorBlock = yield* queryExactBlock(state.driver, { ...feet, y: feet.y - 1 });
    if (!isSolid(floorBlock)) {
      return undefined;
    }
    // The roof can only be placed against a neighbor, so open ground needs
    // one block beside it first.
    let roofSupported = false;
    for (const position of roofRing) {
      const ringBlock = yield* queryExactBlock(state.driver, position);
      if (isSolid(ringBlock)) {
        roofSupported = true;
        break;
      }
    }
    const targets = [
      ...ENCLOSURE_SIDES.map((side) => ({ ...feet, x: feet.x + side.x, z: feet.z + side.z })),
      ...ENCLOSURE_SIDES.map((side) => ({ ...head, x: head.x + side.x, z: head.z + side.z })),
      ...(roofSupported ? [] : [roofCorner]),
      roof,
    ];
    const open: BeatGameBlockPosition[] = [];
    for (const target of targets) {
      const block = yield* queryExactBlock(state.driver, target);
      if (block === undefined) {
        return undefined;
      }
      if (!isSolid(block)) {
        open.push(target);
      }
    }
    if (countNightShelterItems(observation, NIGHT_SHELTER_BLOCK_ITEM_IDS) < open.length) {
      return undefined;
    }
    const placed: BeatGameBlockPosition[] = [];
    const placeAt = (target: BeatGameBlockPosition) =>
      Effect.gen(function* () {
        const supports = [
          { against: { ...target, y: target.y - 1 }, face: "up" },
          { against: { ...target, x: target.x + 1 }, face: "west" },
          { against: { ...target, x: target.x - 1 }, face: "east" },
          { against: { ...target, z: target.z + 1 }, face: "north" },
          { against: { ...target, z: target.z - 1 }, face: "south" },
          { against: { ...target, y: target.y + 1 }, face: "down" },
        ] as const;
        // The server can still refuse a support it cannot see, so each solid
        // neighbor is tried in turn.
        for (const support of supports) {
          if (body.some((cell) => samePosition(cell, support.against))) {
            continue;
          }
          const supportBlock = yield* queryExactBlock(state.driver, support.against);
          if (!isSolid(supportBlock)) {
            continue;
          }
          const placement = yield* state.driver
            .act({
              type: "select-item",
              selector: { itemIds: NIGHT_SHELTER_BLOCK_ITEM_IDS },
            })
            .pipe(
              Effect.andThen(
                state.driver.act({
                  type: "place-block",
                  against: support.against,
                  face: support.face,
                  hand: "main",
                }),
              ),
              Effect.result,
            );
          if (placement._tag === "Failure") {
            continue;
          }
          for (let attempt = 0; attempt < 20; attempt += 1) {
            const block = yield* queryExactBlock(state.driver, target);
            if (isSolid(block)) {
              return true;
            }
            yield* Effect.sleep(100);
          }
        }
        return false;
      });
    const placedAll = yield* state.driver.withControl(
      Effect.gen(function* () {
        for (const target of open) {
          if (!(yield* placeAt(target))) {
            // A wall over water or a drop has nothing to hold it, so the bot
            // first fills the open space below it.
            const below = { ...target, y: target.y - 1 };
            if (body.some((cell) => samePosition(cell, below))) {
              return false;
            }
            const blockBelow = yield* queryExactBlock(state.driver, below);
            if (isSolid(blockBelow) || !(yield* placeAt(below)) || !(yield* placeAt(target))) {
              return false;
            }
          }
          placed.push(target);
        }
        return true;
      }),
    );
    if (!placedAll) {
      return undefined;
    }
    const onSide = (position: BeatGameBlockPosition, side: (typeof ENCLOSURE_SIDES)[number]) =>
      position.x === feet.x + side.x && position.z === feet.z + side.z && position.y <= head.y;
    const exitSide = ENCLOSURE_SIDES.find((side) =>
      placed.some((position) => onSide(position, side)),
    );
    return {
      placed,
      exit: exitSide === undefined ? [] : placed.filter((position) => onSide(position, exitSide)),
    };
  });
}

function openNightShelterEnclosure(
  state: RunState,
  enclosure: NightShelterEnclosure,
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.withControl(
    Effect.forEach(
      enclosure.exit,
      (position) =>
        queryExactBlock(state.driver, position).pipe(
          Effect.flatMap((block) =>
            block !== undefined && !block.replaceable && block.diggable
              ? state.driver.act({ type: "dig-block", position })
              : Effect.void,
          ),
        ),
      { discard: true, concurrency: 1 },
    ),
  );
}

// Callers check `state.driver.environment` first, so a missing observer is a programming error.
function observeEnvironment(
  state: RunState,
): Effect.Effect<BeatGameEnvironmentObservation, BeatGameDriverError> {
  return (
    state.driver.environment ??
    Effect.die(new Error("The beat-game driver does not observe the environment"))
  );
}

function isHostileNight(gameTime: bigint | undefined): boolean {
  if (gameTime === undefined) {
    return false;
  }
  const dayTime = ((gameTime % 24_000n) + 24_000n) % 24_000n;
  // The shelter window wraps past day time 0, so either side of midnight counts.
  return dayTime >= NIGHT_SHELTER_START_TICK || dayTime < NIGHT_SHELTER_END_TICK;
}

function countNightShelterItems(
  observation: BeatGameObservation,
  itemIds: readonly string[],
): number {
  return itemIds.reduce((total, itemId) => total + (observation.inventory.counts[itemId] ?? 0), 0);
}

// Abandons an action whose retries all failed, or that the world refused,
// so the planner can try it again from a clean start, such as a new portal
// site. The run fails only after the same action is abandoned this many
// times.
const MAXIMUM_EXHAUSTED_ACTION_ROUNDS = 3;
const EXHAUSTED_ACTION_EVIDENCE_PREFIX = "exhausted retries: ";

function exhaustedActionRounds(checkpoint: BeatGameCheckpoint, action: string): number {
  return checkpoint.memory.skillHistory.filter(
    (skill) =>
      skill.action === action &&
      skill.status === BeatGameDurableSkillStatus.ABANDONED &&
      skill.completionEvidence?.startsWith(EXHAUSTED_ACTION_EVIDENCE_PREFIX) === true,
  ).length;
}

function abandonExhaustedAction(
  state: RunState,
  action: string,
  attempt: number,
  error: BeatGameError,
): Effect.Effect<undefined, BeatGameError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const durable = checkpoint.activeSkill?.action === action;
    const rounds = durable
      ? exhaustedActionRounds(checkpoint, action)
      : ((yield* Ref.get(state.abandonedActionRounds)).get(action) ?? 0);
    if (rounds + 1 >= MAXIMUM_EXHAUSTED_ACTION_ROUNDS) {
      yield* persist(state, (current) =>
        suspendDurableSkill(current, `failed:${error.message}`, new Date().toISOString()),
      );
      return yield* error;
    }
    if (!durable) {
      yield* Ref.update(state.abandonedActionRounds, (current) =>
        new Map(current).set(action, rounds + 1),
      );
    }
    yield* persist(state, (current) => {
      const now = new Date().toISOString();
      const abandoned = durable
        ? abandonActiveDurableSkill(
            current,
            `${EXHAUSTED_ACTION_EVIDENCE_PREFIX}${error.message}`,
            now,
            { abandonPortalWorkspace: true },
          )
        : current;
      return {
        ...abandoned,
        planner: withoutCurrentAction({
          ...abandoned.planner,
          retryCount: 0,
          updatedAt: now,
        }),
      };
    });
    yield* emit(state, {
      type: "action-failed",
      action,
      attempt,
      detail: `Abandoned after exhausting retries, replanning: ${error.message}`,
    });
    // The world rarely changes within a moment, so give it time before the
    // planner picks the same action again.
    yield* Effect.sleep(backoffDuration(rounds + 1));
    return undefined;
  });
}

// Codes the server uses when the world did not allow a step, such as a hidden
// placement face, a missing item or a block out of reach. They are final for
// that request, but a new plan can avoid them.
const WORLD_STATE_FAILURE_CODES: ReadonlySet<string> = new Set([
  "failed_precondition",
  "not_found",
  "out_of_range",
]);

function isWorldStateFailure(error: unknown): boolean {
  for (
    let current = error, depth = 0;
    depth < 8 && typeof current === "object" && current !== null;
    depth += 1
  ) {
    if (
      current instanceof BeatGameDriverError &&
      current.code !== undefined &&
      WORLD_STATE_FAILURE_CODES.has(current.code)
    ) {
      return true;
    }
    current = "cause" in current ? current.cause : undefined;
  }
  return false;
}

function runDecisionWithRetry(
  state: RunState,
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  firstObservation: BeatGameObservation,
): Effect.Effect<void, BeatGameError> {
  const action = decision.action;
  const maximumAttempts = state.strategy.maximumActionRetries + 1;
  const attempt = (
    number: number,
    observation: BeatGameObservation,
  ): Effect.Effect<void, BeatGameError> =>
    Effect.gen(function* () {
      yield* emit(state, {
        type: number === 1 ? "action-started" : "action-retried",
        action,
        attempt: number,
      });
      const actionCheckpoint = yield* persist(state, (checkpoint) => {
        const now = new Date().toISOString();
        const withSkill = startOrResumeDurableSkill(checkpoint, decision, now);
        return {
          ...withSkill,
          planner: {
            ...withSkill.planner,
            currentAction: action,
            currentActionId:
              number === 1 &&
              checkpoint.planner.currentAction === action &&
              checkpoint.planner.currentActionId !== undefined
                ? checkpoint.planner.currentActionId
                : crypto.randomUUID(),
            retryCount: number - 1,
            updatedAt: now,
          },
        };
      });
      const result = yield* cancellable(
        state,
        executeDecision(state, decision, observation, actionCheckpoint),
      ).pipe(
        Effect.timeoutOrElse({
          duration: state.strategy.actionTimeoutMs,
          orElse: () =>
            Effect.fail(actionError(actionCheckpoint, `Action ${action} timed out`, true)),
        }),
        Effect.catch((error) =>
          number < maximumAttempts && retryable(error)
            ? Effect.gen(function* () {
                yield* emit(state, {
                  type: "action-failed",
                  action,
                  attempt: number,
                  detail: error.message,
                });
                yield* persist(state, (checkpoint) =>
                  suspendDurableSkill(
                    checkpoint,
                    `retry:${error.message}`,
                    new Date().toISOString(),
                  ),
                );
                yield* Effect.sleep(backoffDuration(number));
                // The bot can still be offline here. A live run ended when its
                // server restarted and this observation failed.
                const fresh = yield* observeWithRecovery(state);
                if (actionObservedComplete(decision, fresh, state.strategy)) {
                  yield* persist(state, (checkpoint) => {
                    const completed = completeDurableSkill(
                      checkpoint,
                      action,
                      "fresh observation confirmed completion before retry",
                      new Date().toISOString(),
                    );
                    return {
                      ...completed,
                      connectionEpoch: fresh.player.connectionEpoch,
                      lastStableAction: stableActionResult(
                        action,
                        actionCheckpoint,
                        fresh,
                        "OBSERVATION_AFTER_UNCERTAIN_RESULT",
                      ),
                      planner: withoutCurrentAction({
                        ...plannerWithObservation(
                          completed.planner,
                          fresh,
                          state.strategy,
                          completed,
                        ),
                        retryCount: 0,
                        completedActions: [...completed.planner.completedActions, action].slice(
                          -128,
                        ),
                      }),
                    };
                  });
                  yield* emit(state, {
                    type: "action-succeeded",
                    action,
                    attempt: number,
                    detail: "A fresh observation confirmed the action before retry",
                  });
                  return undefined;
                }
                return yield* attempt(number + 1, fresh);
              })
            : retryable(error) || isWorldStateFailure(error)
              ? abandonExhaustedAction(state, action, number, error)
              : persist(state, (checkpoint) =>
                  suspendDurableSkill(
                    checkpoint,
                    `failed:${error.message}`,
                    new Date().toISOString(),
                  ),
                ).pipe(Effect.andThen(Effect.fail(error))),
        ),
      );
      if (result === undefined) {
        return;
      }
      if (result.continueReason !== undefined) {
        yield* persist(state, (checkpoint) => ({
          ...checkpoint,
          planner: withoutCurrentAction({
            ...checkpoint.planner,
            retryCount: 0,
            updatedAt: new Date().toISOString(),
          }),
        }));
        yield* emit(state, {
          type: "action-progressed",
          action,
          attempt: number,
          detail: result.continueReason,
        });
        if (result.continueDelayMs !== undefined) {
          yield* Effect.sleep(result.continueDelayMs);
        }
        return;
      }
      if (result.replanReason !== undefined) {
        if (result.completedPendingDeath !== undefined) {
          yield* completePendingDeath(state, result.completedPendingDeath);
        }
        yield* persist(state, (checkpoint) => {
          const current =
            result.completedPendingDeath === undefined
              ? checkpoint
              : forgetDeathPosition(checkpoint, result.completedPendingDeath);
          const suspended = suspendDurableSkill(
            current,
            result.replanReason ?? "replan",
            new Date().toISOString(),
          );
          return {
            ...suspended,
            planner: withoutCurrentAction({
              ...suspended.planner,
              retryCount: 0,
              updatedAt: new Date().toISOString(),
            }),
          };
        });
        yield* emit(state, {
          type: "action-failed",
          action,
          attempt: number,
          detail: `Interrupted for replanning: ${result.replanReason}`,
        });
        if (result.replanDelayMs !== undefined) {
          yield* Effect.sleep(result.replanDelayMs);
        }
        return;
      }
      if (result.phase !== undefined) {
        yield* advancePhase(state, result.phase);
      }
      if (decision.type === "recover-death") {
        yield* completePendingDeath(state, observation.observedAt);
      }
      const latestObservation = yield* Ref.get(state.observation);
      yield* persist(state, (checkpoint) => {
        const transformed = result.checkpoint?.(checkpoint) ?? checkpoint;
        const completed = completeDurableSkill(
          transformed,
          action,
          result.phase === undefined
            ? "task result confirmed completion"
            : "observed phase transition confirmed completion",
          new Date().toISOString(),
        );
        return {
          ...completed,
          lastStableAction: stableActionResult(
            action,
            actionCheckpoint,
            latestObservation,
            result.phase === undefined ? "TASK_RESULT" : "OBSERVED_STATE",
          ),
          planner: withoutCurrentAction({
            ...completed.planner,
            retryCount: 0,
            completedActions: [...completed.planner.completedActions, action].slice(-128),
            updatedAt: new Date().toISOString(),
          }),
        };
      });
      yield* Ref.update(state.abandonedActionRounds, (current) => {
        if (!current.has(action)) {
          return current;
        }
        const next = new Map(current);
        next.delete(action);
        return next;
      });
      yield* emit(state, {
        type: "action-succeeded",
        action,
        attempt: number,
      });
    });
  return attempt(1, firstObservation);
}

function executeDecision(
  originalState: RunState,
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
  actionCheckpoint: BeatGameCheckpoint,
): Effect.Effect<ActionResult, BeatGameError> {
  const state = {
    ...originalState,
    driver: withTaskIdempotency(
      originalState.driver,
      actionCheckpoint.planner.currentActionId ?? crypto.randomUUID(),
      new Date(Date.now() + originalState.strategy.actionTimeoutMs),
      stableFingerprint({
        connectionEpoch: observation.player.connectionEpoch,
        playerRevision: observation.player.revision.toString(),
        inventoryRevision: observation.inventory.revision.toString(),
      }),
    ),
  };
  const policyContext = policyContextFor(state, observation, actionCheckpoint);
  const execute: Effect.Effect<ActionResult, unknown> = (() => {
    switch (decision.type) {
      case "recover-death": {
        return Effect.gen(function* () {
          const [pendingDeaths, lastLivingObservation] = yield* Effect.all(
            [Ref.get(state.pendingDeaths), Ref.get(state.lastLivingObservation)],
            { concurrency: 1 },
          );
          const pendingDeath = pendingDeaths.find(
            (candidate) => candidate.observedAt === observation.observedAt,
          ) ?? {
            observedAt: observation.observedAt,
            position: observation.player.position,
            recoverItems: hasMeaningfulRecoveryInventory(lastLivingObservation),
            observedLive: true,
            inventoryCounts: lastLivingObservation.inventory.counts,
          };
          yield* enqueuePendingDeath(state, pendingDeath);
          const rememberedDeathKey = `death:${pendingDeath.observedAt}`;
          const deathWasAlreadyRemembered = actionCheckpoint.memory.deathPositions.some(
            ({ key }) => key === rememberedDeathKey,
          );
          if (!deathWasAlreadyRemembered) {
            yield* persist(state, (checkpoint) => rememberDeathPosition(checkpoint, pendingDeath));
            yield* Effect.all(
              [
                emit(state, {
                  type: "death-observed",
                  detail: positionKey(pendingDeath.position),
                }),
                state.coordinator.publishDiscovery(actionCheckpoint.teamId, {
                  key: `death:${actionCheckpoint.botId}:${pendingDeath.observedAt}`,
                  kind: "death",
                  botId: actionCheckpoint.botId,
                  position: pendingDeath.position,
                  observedAt: pendingDeath.observedAt,
                  confidence: 1,
                }),
              ],
              { discard: true, concurrency: 1 },
            );
          }
          const customRecovery = state.hooks.recoverDeath;
          let recoveryAttempted = pendingDeath.recoverItems;
          let recoveryOrigin: BeatGamePosition | undefined;
          if (customRecovery !== undefined) {
            yield* customRecovery(policyContext);
          } else {
            const beforeRespawn = yield* observeDriverFresh(state);
            const recoverableDeaths = (yield* Ref.get(state.pendingDeaths)).filter(
              (candidate) =>
                isPendingDeathRecoverable(candidate) &&
                candidate.recoverItems &&
                classifyDeathRecoveryInventory(candidate.inventoryCounts) !== "trivial",
            );
            if (beforeRespawn.player.dead && recoverableDeaths.length > 1) {
              const cooldownMs = chainedDeathRespawnCooldown(recoverableDeaths.length);
              yield* emit(state, {
                type: "diagnostic",
                message: "Delaying respawn after a chained corpse-recovery death",
                data: {
                  cooldownMs,
                  pendingDeaths: recoverableDeaths.length,
                },
              });
              yield* Effect.sleep(cooldownMs);
            }
            yield* respawnAndRecover(state.driver, {
              path: state.strategy.path,
            });
            let respawned = yield* observeDriverFresh(state);
            // With keepInventory nothing drops, so a corpse walk would only
            // search for items the bot already carries.
            // Once one death kept the inventory, every death in this world
            // does. A live bot that logged back in dead recorded its death a
            // second time, and the older record then sent it after items it
            // had used up since.
            const pendingDeathsAfterRespawn = yield* Ref.get(state.pendingDeaths);
            if (
              pendingDeath.recoverItems &&
              !respawned.player.dead &&
              (actionCheckpoint.memory.keepsInventoryOnDeath === true ||
                [pendingDeath, ...pendingDeathsAfterRespawn].some((death) =>
                  keptInventoryThroughDeath(death, respawned),
                ))
            ) {
              // Nothing was lost, so this must not count as an inventory loss
              // that restarts Overworld preparation. A live run carrying worn
              // out tools was sent back to the first phase this way.
              yield* emit(state, {
                type: "items-recovered",
                detail: "The bot kept its inventory through death",
              });
              const forgottenDeaths = new Set(
                pendingDeathsAfterRespawn.map(({ observedAt }) => observedAt),
              );
              yield* Ref.set(state.pendingDeaths, []);
              return {
                checkpoint: (checkpoint) => {
                  const forgotten = [
                    ...forgottenDeaths,
                    ...checkpoint.memory.deathPositions.map(({ observedAt }) => observedAt),
                  ].reduce(
                    (current, observedAt) => forgetDeathPosition(current, observedAt),
                    checkpoint,
                  );
                  return {
                    ...forgotten,
                    memory: { ...forgotten.memory, keepsInventoryOnDeath: true },
                  };
                },
                completedPendingDeath: pendingDeath.observedAt,
              } satisfies ActionResult;
            }
            const safetyInterruptions = deathRecoverySafetyInterruptionCount(actionCheckpoint);
            if (
              pendingDeath.recoverItems &&
              safetyInterruptions >= MAXIMUM_DEATH_RECOVERY_SAFETY_INTERRUPTS
            ) {
              return yield* abandonPendingDeath(
                state,
                pendingDeath,
                respawned,
                "Abandoned corpse recovery after six safety interruptions",
              );
            }
            const exhaustedPreparationAttempts =
              deathRecoveryFailureCount(actionCheckpoint, pendingDeath.observedAt, "preparation") >=
              deathRecoveryFailureLimit(state.strategy);
            if (pendingDeath.recoverItems && exhaustedPreparationAttempts) {
              return yield* abandonPendingDeath(
                state,
                pendingDeath,
                respawned,
                "Abandoned a distant corpse after three bounded preparation attempts",
              );
            }
            const exhaustedPickupAttempts =
              deathRecoveryFailureCount(actionCheckpoint, pendingDeath.observedAt, "pickup") >=
              deathRecoveryFailureLimit(state.strategy);
            if (pendingDeath.recoverItems && exhaustedPickupAttempts) {
              return yield* abandonPendingDeath(
                state,
                pendingDeath,
                respawned,
                "Abandoned an unrecoverable corpse after three safe recovery attempts",
              );
            }
            if (respawned.player.health < state.strategy.minimumHealth) {
              yield* retreatAndRecover(state, POST_DEFENSE_RECOVERY_DURATION_MS, {
                preserveFoodBelowCount: DEATH_RECOVERY_FOOD_RESERVE_COUNT,
              });
              respawned = yield* observeDriverFresh(state);
            }
            if (
              yield* recoverIfLocallyEnclosed(
                state,
                "Corpse recovery resumed from an enclosed navigation pocket",
              )
            ) {
              respawned = yield* observeDriverFresh(state);
            }
            if (
              respawned.player.food <= state.strategy.eatBelowFood &&
              hasUsableFood(respawned) &&
              (respawned.player.health <= LETHAL_MELEE_DISENGAGE_HEALTH ||
                deathRecoveryTravelFoodCount(respawned) >= DEATH_RECOVERY_FOOD_RESERVE_COUNT)
            ) {
              yield* eatUnlessEndangered(state, {
                foodItemIds: preferredUsableFoodItemIds(respawned),
                foodLevel: Math.min(20, Math.max(18, state.strategy.eatBelowFood + 2)),
                maximumMeals: 1,
                completeWhenNoFood: true,
                path: state.strategy.path,
              });
              respawned = yield* observeDriverFresh(state);
            }
            if (
              pendingDeath.recoverItems &&
              shouldAttemptCrossDimensionDeathRecovery(pendingDeath, respawned.player.position)
            ) {
              const transitOrigin = respawned;
              const transit = yield* enterDeathRecoveryDimension(state, pendingDeath, respawned);
              if (transit.status === "pending") {
                const madePreparationProgress = madeCrossDimensionDeathRecoveryProgress(
                  transitOrigin,
                  transit.observation,
                  transit.portalTarget,
                );
                const preparationFailures = madePreparationProgress
                  ? yield* clearDeathRecoveryFailure(
                      state,
                      pendingDeath.observedAt,
                      "preparation",
                    ).pipe(Effect.as(0))
                  : yield* recordDeathRecoveryFailure(
                      state,
                      pendingDeath.observedAt,
                      "preparation",
                    );
                if (preparationFailures >= deathRecoveryFailureLimit(state.strategy)) {
                  return yield* abandonPendingDeath(
                    state,
                    pendingDeath,
                    transit.observation,
                    "Abandoned a cross-dimension corpse after three bounded portal recovery attempts",
                  );
                }
                yield* emit(state, {
                  type: "diagnostic",
                  message: "Continuing portal preparation for a cross-dimension corpse",
                  data: {
                    deathPosition: pendingDeath.position,
                    portalTarget: transit.portalTarget,
                    preparationFailures,
                    madePreparationProgress,
                    reason: transit.reason,
                  },
                });
                return {
                  replanReason: transit.reason ?? "cross-dimension corpse recovery remains pending",
                } satisfies ActionResult;
              }
              yield* clearDeathRecoveryFailure(state, pendingDeath.observedAt, "preparation");
              respawned = transit.observation;
            }
            recoveryAttempted =
              pendingDeath.recoverItems &&
              shouldAttemptDeathRecovery(pendingDeath, respawned.player.position);
            if (recoveryAttempted) {
              let nearbyCorpseDrops = yield* inspectNearbyCorpseDrops(
                state,
                pendingDeath,
                respawned,
              );
              if (
                nearbyCorpseDrops === undefined &&
                shouldScoutStaleCorpse(pendingDeath, respawned)
              ) {
                yield* emit(state, {
                  type: "diagnostic",
                  message: "Scouting a nearby shallow corpse before gathering a full recovery kit",
                  data: {
                    deathPosition: pendingDeath.position,
                    playerPosition: respawned.player.position,
                  },
                });
                yield* state.driver
                  .pathfind(pendingDeath.position, STALE_CORPSE_SCOUT_GOAL_RADIUS, {
                    ...state.strategy.path,
                    allowMining: false,
                    allowPlacing: false,
                    avoidFluids: true,
                    maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 30_000),
                  })
                  .pipe(
                    Effect.catchTag("BeatGameDriverError", (error) =>
                      emit(state, {
                        type: "diagnostic",
                        message: "Could not reach corpse scouting range",
                        data: {
                          deathPosition: pendingDeath.position,
                          error: error.message,
                        },
                      }),
                    ),
                  );
                respawned = yield* observeDriverFresh(state);
                nearbyCorpseDrops = yield* inspectNearbyCorpseDrops(state, pendingDeath, respawned);
              }
              if (nearbyCorpseDrops?.length === 0 && pendingDeath.observedLive !== true) {
                return yield* abandonPendingDeath(
                  state,
                  pendingDeath,
                  respawned,
                  "Abandoned a stale corpse after confirming no drops remain nearby",
                );
              }
              if (nearbyCorpseDrops !== undefined) {
                yield* emit(state, {
                  type: "diagnostic",
                  message:
                    "Attempting nearby corpse recovery before gathering more travel supplies",
                  data: { drops: nearbyCorpseDrops.length },
                });
              }
              const preparationPending =
                nearbyCorpseDrops !== undefined
                  ? undefined
                  : yield* prepareForDistantDeathRecovery(state, pendingDeath, respawned);
              if (preparationPending !== undefined) {
                const current = yield* state.driver.observe;
                const foodReserveStillMissing =
                  preparationPending === DEATH_RECOVERY_FOOD_SEARCH_PENDING &&
                  deathRecoveryTravelFoodCount(current) < DEATH_RECOVERY_FOOD_RESERVE_COUNT;
                const madeMeaningfulApproach = madeMeaningfulDeathRecoveryApproach(
                  pendingDeath.position,
                  respawned.player.position,
                  current.player.position,
                );
                const preparationFailures =
                  madeMeaningfulApproach && !foodReserveStillMissing
                    ? yield* clearDeathRecoveryFailure(
                        state,
                        pendingDeath.observedAt,
                        "preparation",
                      ).pipe(Effect.as(0))
                    : yield* recordDeathRecoveryFailure(
                        state,
                        pendingDeath.observedAt,
                        "preparation",
                      );
                const recoveryClass = classifyDeathRecoveryInventory(pendingDeath.inventoryCounts);
                if (preparationFailures >= deathRecoveryFailureLimit(state.strategy)) {
                  return yield* abandonPendingDeath(
                    state,
                    pendingDeath,
                    current,
                    "Abandoned a distant corpse after three bounded preparation attempts",
                  );
                }
                if (recoveryClass === "valuable") {
                  yield* emit(state, {
                    type: "diagnostic",
                    message: "Continuing preparation for a valuable distant corpse",
                    data: {
                      preparationFailures,
                      reason: preparationPending,
                      foodReserveStillMissing,
                      madeMeaningfulApproach,
                    },
                  });
                }
                return {
                  replanReason: preparationPending,
                } satisfies ActionResult;
              }
              yield* observeDriverFresh(state);
              yield* retreatAndRecover(state, POST_DEFENSE_RECOVERY_DURATION_MS, {
                preserveFoodBelowCount: DEATH_RECOVERY_FOOD_RESERVE_COUNT,
              });
              respawned = yield* state.driver.observe;
              respawned = yield* prepareDeathRecoveryInventorySpace(
                state,
                respawned,
                pendingDeath.inventoryCounts,
              );
              recoveryOrigin = respawned.player.position;
              yield* respawnAndRecover(state.driver, {
                deathPosition: pendingDeath.position,
                retryThroughFluids: true,
                path: {
                  ...state.strategy.path,
                  allowMining: hasMiningPickaxe(respawned),
                  avoidFluids: true,
                  additionalPlaceItemIds: DEATH_RECOVERY_ADDITIONAL_PLACE_ITEM_IDS,
                  sprint: false,
                  maxFallDistance: Math.min(1, state.strategy.path.maxFallDistance),
                  searchMode: BeatGamePathSearchMode.URGENT,
                  maximumQualityBound: DEATH_RECOVERY_MAXIMUM_QUALITY_BOUND,
                },
              });
            } else if (pendingDeath.recoverItems) {
              const crossedDimension =
                pendingDeath.position.dimension !== respawned.player.position.dimension;
              return yield* abandonPendingDeath(
                state,
                pendingDeath,
                respawned,
                crossedDimension
                  ? "Abandoned a cross-dimension corpse and restarted progression from the surviving inventory"
                  : "Skipped a distant corpse containing only renewable or unknown items",
              );
            }
          }
          if (recoveryAttempted) {
            yield* sweepRemainingDeathDrops(state, pendingDeath.inventoryCounts).pipe(
              Effect.catchTag("BeatGameDriverError", () => Effect.void),
            );
          }
          if (
            recoveryAttempted &&
            !(yield* waitForDeathRecoveryInventory(state, pendingDeath.inventoryCounts, 5))
          ) {
            const current = yield* state.driver.observe;
            const inspectedCorpseDrops = yield* inspectNearbyCorpseDrops(
              state,
              pendingDeath,
              current,
            );
            const closeEnoughToInspectDrops = inspectedCorpseDrops !== undefined;
            const remainingCorpseDrops = inspectedCorpseDrops ?? [];
            if (closeEnoughToInspectDrops && remainingCorpseDrops.length === 0) {
              yield* emit(state, {
                type: "items-recovered",
                detail: "No corpse drops remain after the safe recovery attempt",
              });
              return {
                checkpoint: (checkpoint) =>
                  resetAfterCatastrophicInventoryLoss(
                    forgetDeathPosition(checkpoint, pendingDeath.observedAt),
                    current,
                  ),
                completedPendingDeath: pendingDeath.observedAt,
              } satisfies ActionResult;
            }
            const madeMeaningfulApproach =
              recoveryOrigin !== undefined &&
              madeMeaningfulDeathRecoveryApproach(
                pendingDeath.position,
                recoveryOrigin,
                current.player.position,
              );
            const recoveryFailures = madeMeaningfulApproach
              ? yield* clearDeathRecoveryFailure(state, pendingDeath.observedAt, "pickup").pipe(
                  Effect.as(0),
                )
              : yield* recordDeathRecoveryFailure(state, pendingDeath.observedAt, "pickup");
            if (recoveryFailures >= deathRecoveryFailureLimit(state.strategy)) {
              return yield* abandonPendingDeath(
                state,
                pendingDeath,
                current,
                "Abandoned an unrecoverable corpse after three safe recovery attempts",
              );
            }
            if (
              (yield* needsOverworldSurfaceRecovery(state, current.player.position)) &&
              remainingCorpseDrops.length === 0
            ) {
              yield* escapeToOverworldSurface(state, current.player.position).pipe(
                Effect.catchTag("BeatGameDriverError", () => Effect.void),
              );
            }
            return {
              replanReason: "the corpse inventory was not recovered and remains pending",
            } satisfies ActionResult;
          }
          yield* emit(state, {
            type: "items-recovered",
            detail: pendingDeath.recoverItems
              ? "Death recovery completed"
              : "Respawn completed without a risky corpse recovery",
          });
          const recoveryObservation = yield* state.driver.observe;
          return {
            checkpoint: (checkpoint) =>
              resetAfterCatastrophicInventoryLoss(
                forgetDeathPosition(checkpoint, pendingDeath.observedAt),
                recoveryObservation,
              ),
            completedPendingDeath: pendingDeath.observedAt,
          } satisfies ActionResult;
        });
      }
      case "eat": {
        return (
          state.hooks.eat?.(policyContext) ??
          eatUnlessEndangered(state, {
            foodItemIds: preferredUsableFoodItemIds(observation),
            foodLevel: state.strategy.eatBelowFood,
            maximumMeals: 1,
            path: state.strategy.path,
          })
        ).pipe(Effect.as({} satisfies ActionResult));
      }
      case "retreat": {
        return (state.hooks.retreat?.(policyContext) ?? retreatAndRecover(state)).pipe(
          Effect.as({} satisfies ActionResult),
        );
      }
      case "prepare-equipment": {
        return (
          state.hooks.prepareEquipment?.(policyContext) ??
          equipBestArmor(state.driver, {
            path: state.strategy.path,
          })
        ).pipe(Effect.as({} satisfies ActionResult));
      }
      case "satisfy-requirement": {
        const satisfy =
          state.hooks.satisfyRequirement === undefined
            ? satisfyRequirement(state, decision.requirement, observation)
            : state.hooks.satisfyRequirement({
                ...policyContext,
                requirement: decision.requirement,
              });
        return satisfy.pipe(
          Effect.andThen(state.driver.observe),
          Effect.map((current) =>
            requirementActionResult(decision.requirement, observation, current),
          ),
          Effect.catchIf(
            (error): error is BeatGameDriverError =>
              error instanceof BeatGameDriverError && error.code === "resource-exhausted",
            (error) =>
              Effect.succeed({
                replanReason: `resource acquisition remains incomplete while satisfying ${
                  decision.requirement.key
                }: ${error.message}`,
                replanDelayMs: REQUIREMENT_NO_PROGRESS_REPLAN_DELAY_MS,
              } satisfies ActionResult),
          ),
        );
      }
      case "build-and-enter-nether": {
        const hadReusablePortal = hasReusablePortalEvidence(
          actionCheckpoint,
          observation.player.position.dimension,
        );
        const buildAndEnter =
          state.hooks.buildAndEnterNether !== undefined
            ? state.hooks
                .buildAndEnterNether(policyContext)
                .pipe(Effect.as({} satisfies ActionResult))
            : enterKnownPortal(state, actionCheckpoint, observation).pipe(
                Effect.flatMap(
                  (
                    knownPortal,
                  ): Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError> => {
                    const useCastPortal =
                      state.strategy.portalStrategy === PortalStrategy.CAST ||
                      (state.strategy.portalStrategy === PortalStrategy.AUTO &&
                        (observation.inventory.counts["minecraft:obsidian"] ?? 0) <
                          state.strategy.targetObsidianCount);
                    return knownPortal
                      ? Effect.succeed({})
                      : hadReusablePortal
                        ? Effect.succeed({
                            replanReason:
                              "revalidated remembered portal evidence before considering construction",
                            replanDelayMs: REMEMBERED_PORTAL_REPLAN_DELAY_MS,
                          })
                        : buildAndEnterDurablePortal(
                            state,
                            actionCheckpoint,
                            observation,
                            useCastPortal,
                          );
                  },
                ),
              );
        return buildAndEnter.pipe(
          Effect.catchIf(
            (error): error is BeatGameDriverError =>
              error instanceof BeatGameDriverError && isRouteUnavailable(error),
            (error) =>
              Effect.succeed({
                replanReason: `retained the portal workspace after a local route failure: ${
                  error.message
                }`,
                replanDelayMs: MINIMUM_RECOVERY_POLL_MS,
              } satisfies ActionResult),
          ),
        );
      }
      case "return-through-portal": {
        if (state.hooks.returnThroughPortal !== undefined) {
          return state.hooks
            .returnThroughPortal(policyContext)
            .pipe(Effect.as({} satisfies ActionResult));
        }
        return enterKnownPortal(state, actionCheckpoint, observation).pipe(
          Effect.flatMap((entered) =>
            entered
              ? Effect.succeed(true)
              : approachLinkedNetherPortal(state, actionCheckpoint, observation).pipe(
                  Effect.flatMap((approached) =>
                    approached
                      ? Effect.all([Ref.get(state.checkpoint), state.driver.observe], {
                          concurrency: 1,
                        }).pipe(
                          Effect.flatMap(([checkpoint, current]) =>
                            enterKnownPortal(state, checkpoint, current),
                          ),
                        )
                      : Effect.succeed(false),
                  ),
                ),
          ),
          Effect.flatMap((entered) =>
            entered
              ? Effect.void
              : enterPortal(state.driver, {
                  path: state.strategy.path,
                }),
          ),
          Effect.as({} satisfies ActionResult),
        );
      }
      case "throw-eye": {
        return moveToEyeBaseline(state).pipe(
          Effect.andThen(state.hooks.throwEye?.(policyContext) ?? throwEyeOfEnder(state.driver)),
          Effect.flatMap((sample) => {
            const eyeSamples = [...actionCheckpoint.memory.eyeSamples, sample].slice(-16);
            const estimate = triangulateStronghold(eyeSamples);
            return Effect.all(
              [
                state.coordinator.publishDiscovery(actionCheckpoint.teamId, {
                  key: `eye:${actionCheckpoint.botId}:${sample.observedAt}`,
                  kind: "eye-sample",
                  botId: actionCheckpoint.botId,
                  position: sample.origin,
                  observedAt: sample.observedAt,
                  confidence: sample.confidence,
                  metadata: {
                    directionX: sample.direction.x,
                    directionZ: sample.direction.z,
                  },
                }),
                estimate === undefined
                  ? Effect.void
                  : state.coordinator.publishDiscovery(actionCheckpoint.teamId, {
                      key: "stronghold:estimate",
                      kind: "stronghold",
                      botId: actionCheckpoint.botId,
                      position: estimate.position,
                      observedAt: sample.observedAt,
                      confidence: estimate.confidence,
                      metadata: {
                        baseline: estimate.baseline,
                        angleDegrees: estimate.angleDegrees,
                      },
                    }),
              ],
              { discard: true, concurrency: 1 },
            ).pipe(
              Effect.as({
                checkpoint: (checkpoint: BeatGameCheckpoint): BeatGameCheckpoint => ({
                  ...checkpoint,
                  memory: {
                    ...checkpoint.memory,
                    eyeSamples,
                    ...(estimate === undefined ? {} : { strongholdEstimate: estimate.position }),
                  },
                }),
              } satisfies ActionResult),
            );
          }),
        );
      }
      case "search-stronghold": {
        return (state.hooks.searchStronghold?.(policyContext) ?? searchStronghold(state)).pipe(
          Effect.tap((found) =>
            !found
              ? Effect.void
              : state.coordinator.publishDiscovery(actionCheckpoint.teamId, {
                  key: "stronghold:portal-room",
                  kind: "stronghold",
                  botId: actionCheckpoint.botId,
                  position:
                    actionCheckpoint.memory.strongholdEstimate ?? observation.player.position,
                  observedAt: new Date().toISOString(),
                  confidence: 1,
                }),
          ),
          Effect.map((found): ActionResult =>
            found
              ? {
                  phase: BeatGamePhase.ACTIVATE_END_PORTAL,
                  checkpoint: (checkpoint) =>
                    rememberWorldPlace(
                      checkpoint,
                      "END_ENTRY",
                      actionCheckpoint.memory.strongholdEstimate ?? observation.player.position,
                      1,
                    ),
                }
              : {},
          ),
          // A live bot's last pickaxe broke on the way down. The search was
          // retried with no tool until the run gave up, while the planner
          // would have crafted a new one from the logs the bot carried.
          Effect.catchIf(
            (error): error is BeatGameDriverError =>
              error instanceof BeatGameDriverError && error.operation === "select-staircase-tool",
            (error) =>
              Effect.succeed({
                replanReason: `the stronghold search ran out of tools: ${error.message}`,
              } satisfies ActionResult),
          ),
        );
      }
      case "activate-end-portal": {
        if (state.hooks.activateEndPortal !== undefined) {
          return state.hooks
            .activateEndPortal(policyContext)
            .pipe(Effect.as({} satisfies ActionResult));
        }
        return activateEndPortal(state.driver, {
          path: state.strategy.path,
        }).pipe(
          Effect.andThen(
            enterEndPortal(state.driver, {
              path: state.strategy.path,
            }),
          ),
          Effect.as({
            phase: BeatGamePhase.FIGHT_ENDER_DRAGON,
            checkpoint: (checkpoint: BeatGameCheckpoint) =>
              rememberWorldPlace(checkpoint, "END_ENTRY", observation.player.position, 1),
          } satisfies ActionResult),
        );
      }
      case "fight-ender-dragon": {
        if (state.hooks.fightEnderDragon !== undefined) {
          return state.hooks
            .fightEnderDragon(policyContext)
            .pipe(
              Effect.map((defeated): ActionResult =>
                defeated ? { phase: BeatGamePhase.EXIT_END } : {},
              ),
            );
        }
        return fightDragon(state);
      }
      case "exit-end": {
        return (
          state.hooks.exitEnd?.(policyContext) ??
          exitEnd(state.driver, {
            path: state.strategy.path,
          })
        ).pipe(Effect.as({} satisfies ActionResult));
      }
      default: {
        return decision satisfies never;
      }
    }
  })();
  const executeWithSafety = (
    safetyObservation: BeatGameObservation,
  ): Effect.Effect<ActionResult, unknown> =>
    findImmediateActionThreat(state, decision, safetyObservation).pipe(
      Effect.filterOrElse(
        (initialThreat) => initialThreat !== undefined,
        () =>
          Effect.raceFirst(
            execute,
            monitorActionSafety(state, decision, observation, safetyObservation),
          ),
      ),
      Effect.flatMap((result) => {
        const response =
          result.fallClutchPosition !== undefined
            ? performWaterBucketFallClutch(state)
            : result.airEscapePosition !== undefined
              ? emergencyAirAscent(state, result.airEscapePosition, {
                  seekDrySurfaceAfterRecovery: !shouldResumeUrgentAquaticFoodHunt(
                    decision,
                    safetyObservation,
                  ),
                })
              : result.environmentalEscapePosition !== undefined
                ? escapeEnvironmentalDamage(state, result.environmentalEscapePosition)
                : result.escapeTarget !== undefined
                  ? decision.type === "recover-death"
                    ? escapeFromTarget(state, result.escapeTarget, {
                        continueEscapingWhenHit: true,
                      })
                    : escapeFromTarget(state, result.escapeTarget).pipe(
                        Effect.andThen(retreatAndRecover(state, POST_DEFENSE_RECOVERY_DURATION_MS)),
                      )
                  : result.defenseTarget !== undefined
                    ? defendAndRecover(state, result.defenseTarget)
                    : result.nightShelterRequested === true
                      ? state.driver.observe.pipe(
                          Effect.flatMap((latest) =>
                            runNightShelterAction(state, latest).pipe(
                              Effect.catch((error) =>
                                emit(state, {
                                  type: "diagnostic",
                                  message: "Night shelter attempt failed",
                                  data: { error: error.message },
                                }),
                              ),
                            ),
                          ),
                        )
                      : result.travelMealRequested === true
                        ? state.driver.observe.pipe(
                            Effect.flatMap((latest) =>
                              eatUnlessEndangered(state, {
                                foodItemIds: preferredUsableFoodItemIds(latest),
                                foodLevel: Math.min(
                                  20,
                                  Math.max(18, state.strategy.eatBelowFood + 2),
                                ),
                                maximumMeals: 1,
                                completeWhenNoFood: true,
                                path: state.strategy.path,
                              }),
                            ),
                          )
                        : Effect.void;
        const interruptedForSafety =
          result.fallClutchPosition !== undefined ||
          result.airEscapePosition !== undefined ||
          result.environmentalEscapePosition !== undefined ||
          result.escapeTarget !== undefined ||
          result.defenseTarget !== undefined ||
          result.nightShelterRequested === true ||
          result.travelMealRequested === true;
        const handledResponse =
          result.airEscapePosition === undefined && result.fallClutchPosition === undefined
            ? response.pipe(
                Effect.catchTag("BeatGameDriverError", (error) =>
                  rememberUnreachableThreat(
                    state,
                    result.escapeTarget ?? result.defenseTarget,
                    error,
                  ),
                ),
              )
            : response;
        const fallProtectedResponse =
          interruptedForSafety && result.fallClutchPosition === undefined
            ? protectFromFatalFall(state, handledResponse)
            : handledResponse;
        const protectedResponse =
          interruptedForSafety &&
          result.environmentalEscapePosition === undefined &&
          result.airEscapePosition === undefined
            ? protectFromLava(state, fallProtectedResponse)
            : fallProtectedResponse;
        return protectedResponse.pipe(
          Effect.flatMap(() =>
            decision.type === "recover-death" && interruptedForSafety
              ? Effect.all([observeDriverFresh(state), Ref.get(state.pendingDeaths)], {
                  concurrency: 1,
                }).pipe(
                  Effect.flatMap(([latest, pendingDeaths]) => {
                    const latestDeath = pendingDeaths.at(-1);
                    return latest.player.dead ||
                      (latestDeath !== undefined &&
                        latestDeath.observedAt !== observation.observedAt)
                      ? Effect.succeed({
                          replanReason: "bot died again during recovery",
                        } satisfies ActionResult)
                      : Effect.succeed(result);
                  }),
                )
              : Effect.succeed(result),
          ),
        );
      }),
    );
  return executeWithSafety(observation).pipe(
    // A command sent in the tick the bot dies fails like any other. A live
    // fall clutch used its water bucket as the bot hit the ground, and that
    // failure ended the whole run instead of starting death recovery. A
    // portal does the same: a live bot crafting beside one was carried to
    // the Overworld, and its next craft at the Nether table ended the run.
    Effect.catchIf(
      (error) => error instanceof BeatGameDriverError,
      (error) =>
        observeDriverFresh(state).pipe(
          Effect.flatMap((latest) =>
            latest.player.dead
              ? Effect.succeed({ replanReason: "bot died" } satisfies ActionResult)
              : latest.player.position.dimension !== observation.player.position.dimension
                ? Effect.succeed({
                    replanReason: "bot changed dimension during the action",
                  } satisfies ActionResult)
                : Effect.fail(error),
          ),
          Effect.catchTag("BeatGameObservationError", () => Effect.fail(error)),
        ),
    ),
    Effect.mapError((error) =>
      error instanceof BeatGameDriverError
        ? error.operation === "pathfind"
          ? pathfindingError(actionCheckpoint, error)
          : actionError(actionCheckpoint, error.message, error.retryable, error)
        : isBeatGameError(error)
          ? error
          : actionError(
              actionCheckpoint,
              error instanceof Error ? error.message : `Action ${decision.action} failed`,
              false,
              error,
            ),
    ),
  );
}

function findImmediateActionThreat(
  state: RunState,
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
): Effect.Effect<ActionResult | undefined, BeatGameDriverError> {
  if (hasDangerousFall(observation)) {
    return Effect.succeed({
      replanReason: "interrupted an action to clutch a dangerous fall",
      fallClutchPosition: observation.player.position,
    });
  }
  return findAirSafetyInterruption(state, decision, observation).pipe(
    Effect.flatMap((airSafety) => {
      if (airSafety !== undefined) {
        return Effect.succeed(airSafety);
      }
      if (
        decision.type === "retreat" ||
        decision.type === "fight-ender-dragon" ||
        observation.player.dead
      ) {
        return Effect.undefined;
      }
      return findImmediateThreat(state, observation).pipe(
        Effect.map((threat) => {
          if (threat === undefined) {
            return undefined;
          }
          return {
            replanReason:
              decision.type === "recover-death"
                ? threat.response === "flee"
                  ? "paused item recovery to evade an immediate hostile"
                  : "paused item recovery to defend against an immediate hostile"
                : threat.response === "flee"
                  ? "delayed an action to evade an immediate threat"
                  : "delayed an action to preempt a nearby hostile",
            ...(threat.response === "flee"
              ? { escapeTarget: threat.target }
              : { defenseTarget: threat.target }),
            replanDelayMs: MINIMUM_RECOVERY_POLL_MS,
          } satisfies ActionResult;
        }),
      );
    }),
  );
}

function findAirSafetyInterruption(
  state: RunState,
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
): Effect.Effect<ActionResult | undefined, BeatGameDriverError> {
  if (observation.player.dead || !hasUnsafeAirDuringAction(decision, observation)) {
    return Effect.undefined;
  }
  return isPlayerInFluid(state.driver, observation.player.position).pipe(
    Effect.map((inFluid) =>
      inFluid
        ? ({
            replanReason: "air fell below the safety threshold",
            airEscapePosition: observation.player.position,
          } satisfies ActionResult)
        : undefined,
    ),
  );
}

function monitorActionSafety(
  state: RunState,
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  actionObservation: BeatGameObservation,
  initialSafetyObservation: BeatGameObservation = actionObservation,
): Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError> {
  const monitor = (
    previousObservation: BeatGameObservation,
  ): Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(Ref.get(state.paused)),
      Effect.flatMap((paused) =>
        paused
          ? Effect.succeed({
              replanReason: "run paused",
            } satisfies ActionResult)
          : Ref.get(state.pendingDeaths).pipe(
              Effect.flatMap((pendingDeath) =>
                decision.type === "recover-death" &&
                pendingDeath.at(-1) !== undefined &&
                pendingDeath.at(-1)?.observedAt !== actionObservation.observedAt
                  ? Effect.succeed({
                      replanReason: "bot died again during recovery",
                    } satisfies ActionResult)
                  : observeDriverFresh(state).pipe(
                      Effect.flatMap((observation) => {
                        if (decision.type !== "recover-death" && observation.player.dead) {
                          return Effect.succeed({
                            replanReason: "bot died",
                          } satisfies ActionResult);
                        }
                        if (
                          observation.player.position.dimension !==
                          previousObservation.player.position.dimension
                        ) {
                          return Effect.suspend(() => monitor(observation));
                        }
                        if (hasDangerousFall(observation)) {
                          return Effect.succeed({
                            replanReason: "interrupted an action to clutch a dangerous fall",
                            fallClutchPosition: observation.player.position,
                          } satisfies ActionResult);
                        }
                        return findAirSafetyInterruption(state, decision, observation).pipe(
                          Effect.flatMap((airSafety) => {
                            if (airSafety !== undefined) {
                              return Effect.succeed(airSafety);
                            }
                            if (
                              decision.type !== "fight-ender-dragon" &&
                              !observation.player.dead
                            ) {
                              // Lava kills in seconds, so it comes before any
                              // threat. A live bot was shot into lava by a
                              // piglin and burned while it kept turning to fight.
                              // Another ate through a retreat in lava, and its
                              // hitbox check missed the lava that kept burning
                              // it, so fresh lava damage counts too.
                              const inLava =
                                observation.player.fireTicks > 0
                                  ? isBurningInLava(state, observation)
                                  : Effect.succeed(false);
                              return inLava.pipe(
                                Effect.flatMap((burningInLava) =>
                                  burningInLava
                                    ? Effect.succeed({
                                        replanReason: "interrupted an action to escape lava",
                                        environmentalEscapePosition: observation.player.position,
                                      } satisfies ActionResult)
                                    : decision.type === "retreat"
                                      ? monitorObservedSafety(
                                          state,
                                          decision,
                                          previousObservation,
                                          observation,
                                          monitor,
                                          actionObservation.observedAt,
                                        )
                                      : findImmediateThreat(state, observation).pipe(
                                          Effect.flatMap((threat) => {
                                            if (threat === undefined) {
                                              return monitorObservedSafety(
                                                state,
                                                decision,
                                                previousObservation,
                                                observation,
                                                monitor,
                                                actionObservation.observedAt,
                                              );
                                            }
                                            const response = threat.response;
                                            return Effect.succeed({
                                              replanReason:
                                                decision.type === "recover-death"
                                                  ? response === "flee"
                                                    ? "interrupted item recovery to evade a hostile"
                                                    : "interrupted item recovery to defend against a hostile"
                                                  : response === "flee"
                                                    ? "interrupted an action to evade an immediate threat"
                                                    : "interrupted an action to preempt a nearby hostile",
                                              ...(response === "flee"
                                                ? { escapeTarget: threat.target }
                                                : { defenseTarget: threat.target }),
                                              replanDelayMs: MINIMUM_RECOVERY_POLL_MS,
                                            } satisfies ActionResult);
                                          }),
                                        ),
                                ),
                              );
                            }
                            return monitorObservedSafety(
                              state,
                              decision,
                              previousObservation,
                              observation,
                              monitor,
                              actionObservation.observedAt,
                            );
                          }),
                          Effect.catchTag("BeatGameDriverError", (error) =>
                            isPositionDimensionTransitionRace(error)
                              ? Effect.suspend(() => monitor(observation))
                              : Effect.fail(error),
                          ),
                        );
                      }),
                    ),
              ),
            ),
      ),
    );
  return Effect.suspend(() => monitor(initialSafetyObservation));
}

function isPositionDimensionTransitionRace(error: BeatGameDriverError): boolean {
  return (
    error.code === "invalid_argument" &&
    /Position dimension does not match the bot's dimension/iu.test(error.message)
  );
}

/// Blazes and archers shoot from well beyond the nearby attacker search. A
/// live run took blaze fireballs as environmental damage, retreated in the
/// open to eat and burned to death, so a fresh projectile hit looks for a
/// visible ranged shooter further out.
function findDistantRangedAttacker(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<ImmediateThreat | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const damage = yield* Ref.get(state.lastDamage);
    if (
      damage?.damageType === undefined ||
      !RANGED_ATTACK_DAMAGE_TYPES.has(damage.damageType) ||
      Date.parse(observation.observedAt) - Date.parse(damage.observedAt) > RECENT_ATTACK_MS
    ) {
      return undefined;
    }
    const shooters = yield* state.driver.queryEntities({
      origin: { ...observation.player.position, y: observation.player.position.y + 1.62 },
      radius: DISTANT_RANGED_ATTACKER_RADIUS,
      selector: {
        entityTypes: [...DISTANT_RANGED_ATTACKER_ENTITY_TYPES],
        alive: true,
        requireLineOfSight: true,
      },
      maximumResults: 8,
    });
    const shooter = shooters
      .filter(({ entityType }) => DISTANT_RANGED_ATTACKER_ENTITY_TYPES.has(entityType))
      .toSorted(
        (left, right) =>
          distanceSquared(observation.player.position, left.position) -
          distanceSquared(observation.player.position, right.position),
      )[0];
    if (shooter === undefined) {
      return undefined;
    }
    return {
      target: shooter,
      response: shooter.entityType === "minecraft:ghast" ? "flee" : "attack",
    } satisfies ImmediateThreat;
  });
}

function monitorObservedSafety(
  state: RunState,
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  previousObservation: BeatGameObservation,
  observation: BeatGameObservation,
  monitor: (
    previousObservation: BeatGameObservation,
  ) => Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError>,
  actionObservedAt: string,
): Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError> {
  if (
    decision.type === "recover-death" &&
    !previousObservation.player.dead &&
    observation.player.dead
  ) {
    return Effect.succeed({
      replanReason: "bot died again during recovery",
    } satisfies ActionResult);
  }
  if (hasDangerousFall(observation)) {
    return Effect.succeed({
      replanReason: "interrupted an action to clutch a dangerous fall",
      fallClutchPosition: observation.player.position,
    } satisfies ActionResult);
  }
  // A live bot broke its last pickaxe three minutes into a fortress search
  // and dug netherrack by hand for seven more minutes before it replanned.
  // Another broke one climbing a staircase toward portal lava and dug stone
  // by hand until the portal build timed out.
  if (
    (decision.type === "satisfy-requirement" || decision.type === "build-and-enter-nether") &&
    hasMiningPickaxe(previousObservation) &&
    !hasMiningPickaxe(observation)
  ) {
    return Effect.succeed({
      replanReason: "interrupted an action to replace a broken pickaxe",
    } satisfies ActionResult);
  }
  // Worn gold keeps piglins calm, and spare boots are put on between
  // actions. A live bot's boots broke in a fight, it fought on barefoot with
  // the spare pair in its pack, and piglins shot it dead within 30 seconds.
  if (
    isNether(observation.player.position.dimension) &&
    previousObservation.player.equipment["feet"] !== undefined &&
    observation.player.equipment["feet"] === undefined &&
    BOOTS_ITEM_IDS.some((itemId) => (observation.inventory.counts[itemId] ?? 0) > 0)
  ) {
    return Effect.succeed({
      replanReason: "interrupted an action to put on spare gold boots",
    } satisfies ActionResult);
  }
  if (
    decision.type !== "retreat" &&
    decision.type !== "fight-ender-dragon" &&
    observation.player.health < previousObservation.player.health
  ) {
    return findNearbyAttackThreat(state, observation).pipe(
      Effect.filterOrElse(
        (nearbyThreat): nearbyThreat is ImmediateThreat => nearbyThreat !== undefined,
        () => findDistantRangedAttacker(state, observation),
      ),
      Effect.flatMap((threat): Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError> => {
        if (threat === undefined) {
          if (decision.type === "eat") {
            return Effect.suspend(() => monitor(observation));
          }
          return Ref.get(state.lastDamage).pipe(
            Effect.flatMap((damage) =>
              emit(state, {
                type: "diagnostic",
                message: "Environmental damage interrupted the action",
                data: {
                  action: decision.action,
                  position: observation.player.position,
                  health: observation.player.health,
                  ...(damage?.damageType === undefined
                    ? {}
                    : { damageType: damage.damageType, damagedAt: damage.observedAt }),
                },
              }),
            ),
            Effect.as({
              replanReason: "environmental damage was observed without a nearby attacker",
              environmentalEscapePosition: observation.player.position,
            } satisfies ActionResult),
          );
        }
        const response = threat.response;
        return Effect.succeed({
          replanReason:
            decision.type === "recover-death"
              ? response === "flee"
                ? "interrupted item recovery to escape an attacker"
                : "interrupted item recovery to fight an attacker"
              : response === "flee"
                ? "interrupted an action to escape a dangerous attacker"
                : "interrupted an action to defend against an attacker",
          ...(response === "flee"
            ? { escapeTarget: threat.target }
            : { defenseTarget: threat.target }),
        } satisfies ActionResult);
      }),
    );
  }
  if (
    decision.type === "satisfy-requirement" &&
    actionObservedComplete(
      decision,
      observation,
      state.strategy,
      state.hooks.satisfyRequirement === undefined,
    )
  ) {
    return Effect.succeed({});
  }
  if (
    decision.type !== "recover-death" &&
    decision.type !== "retreat" &&
    decision.type !== "eat" &&
    observation.player.health < state.strategy.minimumHealth &&
    !(
      decision.type === "satisfy-requirement" &&
      (((decision.requirement.key === "food" || decision.requirement.key === "food-supply") &&
        !hasRecoveryFood(observation)) ||
        decision.requirement.key === "lava-bucket")
    ) &&
    // Without food the bot cannot heal by retreating, so it keeps heading for
    // the Overworld, where it can find some.
    !(decision.type === "return-through-portal" && !hasRecoveryFood(observation))
  ) {
    return Effect.succeed({
      replanReason: "health fell below the safety threshold",
    } satisfies ActionResult);
  }
  if (
    !observation.player.dead &&
    decision.type !== "eat" &&
    (decision.type !== "recover-death" || observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL) &&
    observation.player.food <= URGENT_HUNGER_FOOD_LEVEL &&
    !hasUsableFood(observation) &&
    // Going back to the Overworld is how a starving Nether bot finds food. A
    // live trip back was interrupted 132 times for this very hunger.
    decision.type !== "return-through-portal" &&
    !(
      decision.type === "satisfy-requirement" &&
      (decision.requirement.key === "food" || decision.requirement.key === "food-supply")
    )
  ) {
    return Effect.succeed({
      replanReason: "hunger became urgent without available food",
    } satisfies ActionResult);
  }
  if (
    decision.type !== "recover-death" &&
    decision.type !== "eat" &&
    observation.player.food <= state.strategy.eatBelowFood &&
    shouldInterruptForMeal(decision, observation)
  ) {
    return Effect.succeed({
      replanReason: "hunger fell below the eating threshold",
    } satisfies ActionResult);
  }
  if (
    decision.type === "recover-death" &&
    observation.player.food <= state.strategy.eatBelowFood &&
    hasUsableFood(observation) &&
    (observation.player.health <= LETHAL_MELEE_DISENGAGE_HEALTH ||
      observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL ||
      deathRecoveryTravelFoodCount(observation) >= DEATH_RECOVERY_FOOD_RESERVE_COUNT) &&
    (previousObservation.player.food > state.strategy.eatBelowFood ||
      !hasUsableFood(previousObservation))
  ) {
    return Effect.succeed({
      replanReason: "paused corpse recovery for a travel meal",
      travelMealRequested: true,
    } satisfies ActionResult);
  }
  if (
    decision.type !== "retreat" &&
    decision.type !== "eat" &&
    (decision.type !== "recover-death" || !isRecentDeathObservation(actionObservedAt))
  ) {
    return Effect.all(
      [
        shouldTakeNightShelter(state, previousObservation, {
          leavesCover: decision.type === "recover-death",
        }),
        shouldTakeNightShelter(state, observation, {
          leavesCover: decision.type === "recover-death",
        }),
      ],
      { concurrency: 1 },
    ).pipe(
      Effect.flatMap(([previousShelterNeeded, shelterNeeded]) =>
        previousShelterNeeded && shelterNeeded
          ? Effect.succeed({
              replanReason: "night fell while the bot was under-equipped",
              // The main loop rechecks the night with a different snapshot.
              // A live run bounced between this interrupt and resuming its
              // food search fifteen times, never sheltering, so the shelter
              // runs here.
              nightShelterRequested: true,
            } satisfies ActionResult)
          : Effect.suspend(() => monitor(observation)),
      ),
    );
  }
  return Effect.suspend(() => monitor(observation));
}

function hasDangerousFall(observation: BeatGameObservation): boolean {
  return (
    !observation.player.dead &&
    !observation.player.onGround &&
    observation.player.velocity.y <= FALL_CLUTCH_TRIGGER_VELOCITY &&
    !isNether(observation.player.position.dimension) &&
    (observation.inventory.counts["minecraft:water_bucket"] ?? 0) > 0
  );
}

function protectFromFatalFall(
  state: RunState,
  response: Effect.Effect<void, BeatGameDriverError>,
): Effect.Effect<void, BeatGameDriverError> {
  // The response stops before the clutch, so it cannot change the held item
  // or the view while the bot falls.
  return Effect.raceFirst(
    response.pipe(Effect.as(false)),
    monitorDangerousFall(state).pipe(Effect.as(true)),
  ).pipe(
    Effect.flatMap((falling) => (falling ? performWaterBucketFallClutch(state) : Effect.void)),
  );
}

// A live bot fleeing a hoglin ran into lava and kept fleeing and fighting
// while it burned, because nothing watched for lava during the response.
function protectFromLava(
  state: RunState,
  response: Effect.Effect<void, BeatGameDriverError>,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.raceFirst(response.pipe(Effect.as(undefined)), monitorBurningInLava(state)).pipe(
    Effect.flatMap((burning) => (burning === undefined ? Effect.void : escapeLava(state, burning))),
  );
}

function monitorBurningInLava(
  state: RunState,
): Effect.Effect<BeatGameObservation, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (;;) {
      const observed = yield* state.driver.observe.pipe(Effect.result);
      if (
        observed._tag === "Success" &&
        !observed.success.player.dead &&
        observed.success.player.fireTicks > 0
      ) {
        const inLava = yield* isPlayerInLava(state.driver, observed.success.player.position).pipe(
          Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
        );
        if (inLava) {
          return observed.success;
        }
      }
      yield* Effect.sleep(FALL_CLUTCH_POLL_MS);
    }
  });
}

function monitorDangerousFall(
  state: RunState,
): Effect.Effect<BeatGamePosition, BeatGameDriverError> {
  const poll = (): Effect.Effect<BeatGamePosition, BeatGameDriverError> =>
    state.driver.observe.pipe(
      Effect.flatMap((observation) =>
        hasDangerousFall(observation)
          ? Effect.succeed(observation.player.position)
          : Effect.sleep(FALL_CLUTCH_POLL_MS).pipe(Effect.andThen(Effect.suspend(poll))),
      ),
      Effect.catchTag("BeatGameDriverError", () =>
        Effect.sleep(FALL_CLUTCH_POLL_MS).pipe(Effect.andThen(Effect.suspend(poll))),
      ),
    );
  return Effect.suspend(poll);
}

function performWaterBucketFallClutch(state: RunState): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.withControl(
    Effect.gen(function* () {
      let observation = yield* state.driver.observe;
      if (
        observation.player.dead ||
        observation.player.onGround ||
        observation.player.velocity.y >= FALL_CLUTCH_SAFE_VELOCITY
      ) {
        return undefined;
      }
      if (
        isNether(observation.player.position.dimension) ||
        (observation.inventory.counts["minecraft:water_bucket"] ?? 0) === 0
      ) {
        return yield* new BeatGameDriverError({
          operation: "fall-clutch",
          code: "missing_water_bucket",
          retryable: true,
          message: "A dangerous fall started without a usable water bucket",
        });
      }

      yield* state.driver.act({ type: "reset-movement" });
      yield* state.driver.act({
        type: "select-item",
        selector: { itemIds: ["minecraft:water_bucket"] },
      });
      yield* state.driver.act({
        type: "look",
        yaw: observation.player.rotation.yaw,
        pitch: 90,
      });

      let placementAttempted = false;
      let placed = false;
      for (let attempt = 0; attempt < FALL_CLUTCH_MAXIMUM_ATTEMPTS; attempt += 1) {
        observation = yield* state.driver.observe;
        if (
          observation.player.dead ||
          observation.player.onGround ||
          observation.player.velocity.y >= FALL_CLUTCH_SAFE_VELOCITY
        ) {
          if (placed && !observation.player.dead) {
            yield* pickUpClutchWater(state);
          }
          return undefined;
        }

        const landing = yield* state.driver.raycast({
          direction: { x: 0, y: -1, z: 0 },
          maximumDistance: FALL_CLUTCH_RAYCAST_DISTANCE,
          includeFluids: true,
        });
        const landingInSafeFluid =
          landing.block !== undefined && isSafeFallFluidBlock(landing.block.blockId);
        const fallPerTick = Math.max(0, -observation.player.velocity.y);
        if (
          !landingInSafeFluid &&
          landing.block !== undefined &&
          landing.distance <=
            FALL_CLUTCH_PLACEMENT_REACH + fallPerTick * FALL_CLUTCH_BURST_LOOKAHEAD_TICKS &&
          (observation.inventory.counts["minecraft:water_bucket"] ?? 0) > 0
        ) {
          // A live bot polled at 4.6 blocks, just out of reach, and hit the
          // ground before the next poll. A use out of reach fails without
          // effect, so the bot uses the bucket every tick until one lands.
          // Another bot's use still came a tick late across the round trip,
          // so the server retries each use on every tick by itself.
          placementAttempted = true;
          for (let use = 0; use < FALL_CLUTCH_MAXIMUM_BURST_USES; use += 1) {
            const used = yield* state.driver
              .act({ type: "use-item", hand: "main", retryTicks: FALL_CLUTCH_USE_RETRY_TICKS })
              .pipe(
                Effect.as(true),
                Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
              );
            if (used) {
              placed = true;
              break;
            }
          }
        }
        yield* Effect.sleep(FALL_CLUTCH_POLL_MS);
      }

      return yield* new BeatGameDriverError({
        operation: "fall-clutch",
        code: placementAttempted ? "clutch_not_confirmed" : "no_landing_block",
        retryable: true,
        message: placementAttempted
          ? "Water placement did not stop the dangerous fall"
          : "No landing block entered water-bucket reach during the fall",
      });
    }).pipe(Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
  );
}

// A live bot clutched one fall, left the water behind, and died to the
// next fall with an empty bucket. The water is still below the bot, which
// still holds the bucket and looks down, so one use takes it back.
function pickUpClutchWater(state: RunState): Effect.Effect<void> {
  return Effect.gen(function* () {
    for (let attempt = 0; attempt < FALL_CLUTCH_PICKUP_ATTEMPTS; attempt += 1) {
      const pickedUp = yield* state.driver.act({ type: "use-item", hand: "main" }).pipe(
        Effect.as(true),
        Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
      );
      if (pickedUp) {
        return;
      }
      yield* Effect.sleep(FALL_CLUTCH_POLL_MS);
    }
  });
}

function isSafeFallFluidBlock(blockId: string): boolean {
  return (
    blockId !== "minecraft:lava" && (PLAYER_FLUID_BLOCK_IDS as readonly string[]).includes(blockId)
  );
}

function rememberUnreachableThreat(
  state: RunState,
  target: BeatGameEntityObservation | undefined,
  error: BeatGameDriverError,
): Effect.Effect<void> {
  if (
    target === undefined ||
    error.operation !== "task.attack-entity" ||
    !error.message.includes("Unable to reach the target entity")
  ) {
    return Effect.void;
  }
  return Clock.currentTimeMillis.pipe(
    Effect.flatMap((now) =>
      Ref.update(
        state.unreachableThreats,
        (current) =>
          new Map([
            ...[...current].filter(([, until]) => until > now),
            [`${target.connectionEpoch}:${target.networkId}`, now + UNREACHABLE_THREAT_IGNORE_MS],
          ]),
      ),
    ),
  );
}

function findImmediateThreat(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<ImmediateThreat | undefined, BeatGameDriverError> {
  const origin = {
    ...observation.player.position,
    y: observation.player.position.y + 1.62,
  };
  return Effect.all(
    [
      state.driver.queryEntities({
        origin,
        radius: 24,
        selector: {
          categories: [2],
          alive: true,
          requireLineOfSight: true,
        },
        maximumResults: 32,
      }),
      state.driver.queryEntities({
        origin,
        radius: PROACTIVE_ESCAPE_ONLY_EVASION_RADIUS,
        selector: {
          entityTypes: [...ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES],
          alive: true,
        },
        maximumResults: 8,
      }),
      state.driver.queryEntities({
        origin,
        radius: PROACTIVE_RANGED_ENGAGEMENT_RADIUS,
        selector: {
          entityTypes: DANGEROUS_NEUTRAL_ENTITY_TYPES,
          alive: true,
        },
        maximumResults: 8,
      }),
    ],
    { concurrency: 1 },
  ).pipe(
    Effect.flatMap((queried) =>
      Effect.all([Ref.get(state.unreachableThreats), Clock.currentTimeMillis], {
        concurrency: 1,
      }).pipe(Effect.map(([unreachable, now]) => [queried, unreachable, now] as const)),
    ),
    Effect.map(
      ([
        [visibleHostiles, nearbyEscapeOnlyThreats, nearbyDangerousNeutralMobs],
        unreachable,
        now,
      ]) => {
        const hostiles = new Map(
          [
            ...visibleHostiles.filter((entity) => isCurrentlyHostileMob(entity)),
            ...nearbyEscapeOnlyThreats,
            ...nearbyDangerousNeutralMobs.filter((entity) => isAggressiveNeutralMob(entity)),
          ].map((entity) => [`${entity.connectionEpoch}:${entity.networkId}`, entity]),
        );
        const candidates = [...hostiles.entries()]
          .filter(
            ([key, target]) =>
              !(
                (unreachable.get(key) ?? 0) > now &&
                distanceSquared(observation.player.position, target.position) >
                  UNREACHABLE_THREAT_IGNORED_BEYOND ** 2
              ),
          )
          .map(([, target]) => target)
          .filter(
            (target) =>
              Math.abs(target.position.y - observation.player.position.y) <=
              PROACTIVE_THREAT_MAXIMUM_VERTICAL_DISTANCE,
          )
          .map((target) => ({
            target,
            distanceSquared: distanceSquared(observation.player.position, target.position),
          }))
          .toSorted(
            (left, right) =>
              threatPriorityScore(left.target, left.distanceSquared) -
              threatPriorityScore(right.target, right.distanceSquared),
          );
        const nearbyGroupCandidates = deduplicateEntityTargets([
          ...visibleHostiles,
          ...nearbyDangerousNeutralMobs,
        ]);
        const escapeOnlyThreat = candidates.find(
          ({ target, distanceSquared: targetDistanceSquared }) =>
            ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType) &&
            targetDistanceSquared <= proactiveEscapeOnlyEvasionRadius(target) ** 2,
        );
        if (escapeOnlyThreat !== undefined) {
          return { target: escapeOnlyThreat.target, response: "flee" };
        }
        const aggressiveEnderman = candidates.find(
          ({ target }) =>
            target.entityType === "minecraft:enderman" && isAggressiveNeutralMob(target),
        );
        // An enderman teleports after a fleeing bot, so running rarely helps.
        // A live pearl hunt struck an enderman first, then ran from it when it
        // turned, and took five hits in six seconds while the enderman lost
        // only two more health. An armed bot with health to spare fights; the
        // fight still breaks off below the minimum.
        if (aggressiveEnderman !== undefined) {
          return {
            target: aggressiveEnderman.target,
            response:
              hasMeleeWeapon(observation) &&
              observation.player.health >= state.strategy.minimumHealth
                ? "attack"
                : "flee",
          };
        }
        const melee = candidates.find(({ target, distanceSquared: targetDistanceSquared }) => {
          if (
            !isActiveMeleeHostile(target) ||
            PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType)
          ) {
            return false;
          }
          const responseRadius = shouldDisengageFromThreat(state, observation, target)
            ? PROACTIVE_MELEE_DISENGAGEMENT_RADIUS
            : MELEE_ENGAGEMENT_RADIUS;
          return targetDistanceSquared <= responseRadius ** 2;
        });
        if (melee !== undefined) {
          if (hasNearbyGroupAggroMember(melee.target, nearbyGroupCandidates)) {
            return { target: melee.target, response: "flee" };
          }
          const nearbyThreats = candidates.filter(
            ({ distanceSquared: targetDistanceSquared }) =>
              targetDistanceSquared <=
              PROACTIVE_RANGED_ENGAGEMENT_RADIUS * PROACTIVE_RANGED_ENGAGEMENT_RADIUS,
          );
          const hasShield = (observation.inventory.counts["minecraft:shield"] ?? 0) > 0;
          const unshieldedAmbush = !hasShield && nearbyThreats.length > 1;
          const shieldedAmbush =
            hasShield && nearbyThreats.length >= SHIELDED_AMBUSH_ESCAPE_THRESHOLD;
          const armedClosePursuer = shouldCommitToCaughtMeleePursuerFight(
            observation,
            melee.target,
          );
          const shouldFleeMelee =
            shieldedAmbush ||
            (unshieldedAmbush && !armedClosePursuer) ||
            shouldDisengageFromThreat(state, observation, melee.target);
          return {
            target: melee.target,
            response: shouldFleeMelee ? "flee" : "attack",
          };
        }
        const ranged = candidates.find(
          ({ target, distanceSquared: targetDistanceSquared }) =>
            PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
            targetDistanceSquared <=
              PROACTIVE_RANGED_ENGAGEMENT_RADIUS * PROACTIVE_RANGED_ENGAGEMENT_RADIUS,
        );
        if (ranged !== undefined) {
          const nearbyThreats = candidates.filter(
            ({ distanceSquared: targetDistanceSquared }) =>
              targetDistanceSquared <=
              PROACTIVE_RANGED_ENGAGEMENT_RADIUS * PROACTIVE_RANGED_ENGAGEMENT_RADIUS,
          );
          const overwhelmingAmbush = isOverwhelmingAmbush(
            observation,
            nearbyThreats.map(({ target }) => target),
          );
          return {
            target: ranged.target,
            response:
              overwhelmingAmbush || shouldDisengageFromThreat(state, observation, ranged.target)
                ? "flee"
                : "attack",
          };
        }
        return undefined;
      },
    ),
  );
}

function shouldDisengageFromThreat(
  state: RunState,
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  if (ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType)) {
    return true;
  }
  // Running from an enderman fails: it teleports after the bot. A live bot
  // hit by one it had struck dug in for cover and took six hits while
  // digging. An armed bot with health to spare stands and fights.
  if (target.entityType === "minecraft:enderman" && isAggressiveNeutralMob(target)) {
    return !hasMeleeWeapon(observation) || observation.player.health < state.strategy.minimumHealth;
  }
  if (observation.player.health <= LETHAL_MELEE_DISENGAGE_HEALTH) {
    return true;
  }
  if (shouldCommitToCloseRangedFight(observation, target)) {
    return false;
  }
  if (shouldCommitToRangedFight(observation, target)) {
    return false;
  }
  if (shouldCommitToFastMeleePursuerFight(observation, target)) {
    return false;
  }
  if (shouldCommitToMeleeFight(observation, target)) {
    return false;
  }
  if (FAST_MELEE_PURSUER_ENTITY_TYPES.has(target.entityType) && !hasMeleeWeapon(observation)) {
    return true;
  }
  if (PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType)) {
    return !shouldEngageRangedFight(state, observation, target);
  }
  if (PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(target.entityType)) {
    return observation.player.health < state.strategy.minimumHealth;
  }
  if (observation.player.health <= Math.min(state.strategy.minimumHealth, MELEE_DISENGAGE_HEALTH)) {
    return true;
  }
  return false;
}

function proactiveEscapeOnlyEvasionRadius(target: BeatGameEntityObservation): number {
  return target.entityType === "minecraft:creeper"
    ? CREEPER_PROACTIVE_EVASION_RADIUS
    : PROACTIVE_ESCAPE_ONLY_EVASION_RADIUS;
}

function threatPriorityScore(
  target: BeatGameEntityObservation,
  targetDistanceSquared: number,
): number {
  return isFusingCreeper(target) ? targetDistanceSquared * 0.2 : targetDistanceSquared;
}

function isFusingCreeper(target: BeatGameEntityObservation): boolean {
  return (
    target.entityType === "minecraft:creeper" &&
    (target.creeper?.ignited === true ||
      (target.creeper?.swellDirection ?? -1) > 0 ||
      (target.creeper?.fuseProgress ?? 0) > 0)
  );
}

function shouldShieldCreeperExplosion(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  if (!isFusingCreeper(target) || (observation.inventory.counts["minecraft:shield"] ?? 0) <= 0) {
    return false;
  }
  const fuseProgress = Math.min(1, target.creeper?.fuseProgress ?? 0);
  const remainingFuseMs = (1 - fuseProgress) * CREEPER_FUSE_DURATION_MS;
  const currentDistance = Math.sqrt(distanceSquared(observation.player.position, target.position));
  const safeDistance =
    target.creeper?.powered === true
      ? CHARGED_CREEPER_BLAST_SAFE_DISTANCE
      : CREEPER_BLAST_SAFE_DISTANCE;
  const requiredSprintMs =
    (Math.max(0, safeDistance - currentDistance) / CREEPER_SPRINT_BLOCKS_PER_SECOND) * 1000;
  return remainingFuseMs <= requiredSprintMs + CREEPER_ESCAPE_REACTION_MARGIN_MS;
}

function escapeThreatSelector(
  target: BeatGameEntityObservation,
  includeHostileGroup = false,
): BeatGameEntitySelector {
  if (!includeHostileGroup) {
    return { networkId: target.networkId, alive: true };
  }
  const escapeFromHostileGroup =
    ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType) ||
    PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(target.entityType) ||
    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) ||
    isActiveConditionalHostile(target);
  return escapeFromHostileGroup
    ? { categories: [2], alive: true }
    : { networkId: target.networkId, alive: true };
}

function shouldEngageRangedFight(
  state: RunState,
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    shouldCommitToRangedFight(observation, target) ||
    shouldCommitToBarehandedRangedFight(observation, target) ||
    (hasMeleeWeapon(observation) && observation.player.health >= state.strategy.minimumHealth)
  );
}

function shouldCommitToRangedFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  const hasShield = (observation.inventory.counts["minecraft:shield"] ?? 0) > 0;
  return (
    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
    hasMeleeWeapon(observation) &&
    observation.player.health >=
      (hasShield ? SHIELDED_RANGED_FIGHT_MINIMUM_HEALTH : UNSHIELDED_RANGED_FIGHT_MINIMUM_HEALTH) &&
    observation.player.food > URGENT_HUNGER_FOOD_LEVEL
  );
}

function shouldCommitToUnshieldedRangedFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
    hasMeleeWeapon(observation) &&
    (observation.inventory.counts["minecraft:shield"] ?? 0) === 0 &&
    observation.player.health >= UNSHIELDED_RANGED_FIGHT_MINIMUM_HEALTH &&
    observation.player.food > URGENT_HUNGER_FOOD_LEVEL
  );
}

function shouldCommitToCloseRangedFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
    distanceSquared(observation.player.position, target.position) <=
      EMERGENCY_KNOCKBACK_RANGE ** 2 &&
    ((observation.inventory.counts["minecraft:shield"] ?? 0) > 0 ||
      isReadyForBarehandedDefense(observation))
  );
}

function shouldCommitToBarehandedRangedFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
    !hasMeleeWeapon(observation) &&
    isReadyForBarehandedDefense(observation) &&
    distanceSquared(observation.player.position, target.position) <=
      BAREHANDED_RANGED_DEFENSE_MAX_DISTANCE ** 2
  );
}

function shouldCommitToCaughtRangedFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
    hasMeleeWeapon(observation) &&
    distanceSquared(observation.player.position, target.position) <= EMERGENCY_KNOCKBACK_RANGE ** 2
  );
}

function shouldCommitToCloseMeleeFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    COMMITTABLE_CLOSE_MELEE_ENTITY_TYPES.has(target.entityType) &&
    distanceSquared(observation.player.position, target.position) <=
      EMERGENCY_KNOCKBACK_RANGE ** 2 &&
    ((hasMeleeWeapon(observation) && observation.player.health >= MELEE_DISENGAGE_HEALTH) ||
      observation.player.health >= BAREHANDED_DEFENSE_MINIMUM_HEALTH)
  );
}

function shouldCommitToUndergroundMeleeFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    observation.player.position.y <= OVERWORLD_LOW_GROUND_MAX_Y &&
    target.entityType !== "minecraft:drowned" &&
    shouldCommitToCloseMeleeFight(observation, target)
  );
}

function shouldCommitToFastMeleePursuerFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    FAST_MELEE_PURSUER_ENTITY_TYPES.has(target.entityType) &&
    ((hasMeleeWeapon(observation) && observation.player.health > LETHAL_MELEE_DISENGAGE_HEALTH) ||
      (observation.player.health >= BAREHANDED_DEFENSE_MINIMUM_HEALTH &&
        observation.player.food >= 18))
  );
}

function shouldCommitToCaughtMeleePursuerFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    COMMITTABLE_CLOSE_MELEE_ENTITY_TYPES.has(target.entityType) &&
    hasMeleeWeapon(observation) &&
    observation.player.health >= CAUGHT_MELEE_COMMIT_MINIMUM_HEALTH &&
    distanceSquared(observation.player.position, target.position) <= EMERGENCY_KNOCKBACK_RANGE ** 2
  );
}

function shouldCommitToBarehandedCaughtFastPursuerFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    FAST_MELEE_PURSUER_ENTITY_TYPES.has(target.entityType) &&
    !hasMeleeWeapon(observation) &&
    observation.player.health > LETHAL_MELEE_DISENGAGE_HEALTH &&
    observation.player.food > CRITICAL_HUNGER_FOOD_LEVEL &&
    distanceSquared(observation.player.position, target.position) <= EMERGENCY_KNOCKBACK_RANGE ** 2
  );
}

function isReadyForBarehandedDefense(observation: BeatGameObservation): boolean {
  return (
    observation.player.health >= BAREHANDED_DEFENSE_MINIMUM_HEALTH &&
    observation.player.food > URGENT_HUNGER_FOOD_LEVEL
  );
}

function shouldCommitToMeleeFight(
  observation: BeatGameObservation,
  target: BeatGameEntityObservation,
): boolean {
  return (
    shouldCommitToCloseMeleeFight(observation, target) ||
    shouldCommitToUndergroundMeleeFight(observation, target) ||
    shouldCommitToFastMeleePursuerFight(observation, target) ||
    shouldCommitToCaughtMeleePursuerFight(observation, target) ||
    shouldCommitToBarehandedCaughtFastPursuerFight(observation, target)
  );
}

function escapeFromTarget(
  state: RunState,
  target: BeatGameEntityObservation,
  options: {
    readonly continueEscapingWhenHit?: boolean;
    readonly knockbackStrikes?: number;
  } = {},
): Effect.Effect<void, BeatGameDriverError> {
  const escapePath = {
    ...state.strategy.path,
    sprint: true,
    maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 3000),
  };
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    if (observation.player.dead) {
      return;
    }
    // Running from a hoglin is what threw a live run off ledges and into
    // lava, so it stands on a pillar instead when it can.
    if (PILLAR_DEFENSE_ENTITY_TYPES.has(target.entityType)) {
      if (yield* fightFromPillar(state, target)) {
        return;
      }
      // A blocked hoglin hit does not throw the bot, so with a shield it
      // stands and fights rather than run along a ledge.
      // A shield does not stop every enderman hit. A live bot stood its
      // ground against one from 13 health to 4 and died, so a wounded bot
      // takes cover from an enderman instead.
      const endermanTarget = target.entityType === "minecraft:enderman";
      if (
        SHIELD_BLOCKING_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
        hasItemInInventoryOrEquipment(observation, "minecraft:shield") &&
        !(endermanTarget && observation.player.health < state.strategy.minimumHealth)
      ) {
        // This fight is the escape, so a hit must not break it off. A live
        // bot left and restarted it every 0.4 seconds, never struck, and
        // died to four hoglin hits in three seconds. An enderman fight
        // breaks off below the minimum health.
        yield* defendAgainstSingleTarget(state, target, { commitThroughWound: !endermanTarget });
        return;
      }
    }
    if (shouldShieldCreeperExplosion(observation, target)) {
      yield* blockCreeperExplosion(state, observation, target);
      return;
    }
    const endermanWaterEscapeTarget =
      target.entityType === "minecraft:enderman"
        ? yield* findEndermanWaterEscapeTarget(state, observation.player.position)
        : undefined;
    // An enderman teleports after a fleeing bot, and a live run died running
    // from one. Away from water, cover it cannot reach into is the escape.
    if (target.entityType === "minecraft:enderman" && endermanWaterEscapeTarget === undefined) {
      const cover = yield* takeCoverInPlace(state);
      if (cover !== undefined) {
        yield* emit(state, {
          type: "diagnostic",
          message: "Took cover from an angry enderman",
          data: {
            threatNetworkId: target.networkId,
            position: observation.player.position,
            method: cover.method,
            placedBlocks: cover.placedBlocks,
          },
        });
        // Health only comes back on a nearly full stomach. A live bot sat in
        // its cover at 2 health and 17 hunger for half an hour, with a meal
        // in its pack.
        const sheltered = yield* state.driver.observe;
        if (sheltered.player.food < 20 && hasUsableFood(sheltered)) {
          yield* eatUnlessEndangered(state, {
            foodItemIds: preferredUsableFoodItemIds(sheltered),
            foodLevel: 20,
            maximumMeals: 1,
            completeWhenNoFood: true,
            path: state.strategy.path,
          }).pipe(Effect.catchTag("BeatGameDriverError", () => Effect.void));
        }
        yield* Effect.sleep(ENDERMAN_COVER_WAIT_MS);
        return;
      }
    }
    let directEscapeSucceeded = false;
    if (
      target.entityType === "minecraft:creeper" ||
      PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) ||
      PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(target.entityType) ||
      isActiveConditionalHostile(target) ||
      shouldDisengageFromThreat(state, observation, target)
    ) {
      directEscapeSucceeded = yield* knockBackAndSprintAway(state, observation, target);
    }
    const latest = yield* state.driver.observe;
    if (latest.player.dead) {
      return;
    }
    // A live run fled skeletons for ninety seconds, starting a fresh escape
    // after every arrow, and died before it took cover. Once worn down,
    // walls beat running.
    if (
      latest.player.health <= RANGED_COVER_HEALTH &&
      (yield* takeCoverFromRangedFire(state, latest, target))
    ) {
      return;
    }
    const nearbyEscapeCandidates = yield* state.driver.queryEntities({
      origin: latest.player.position,
      radius: RANGED_THREAT_ESCAPE_SAFE_DISTANCE,
      selector: { categories: [2], alive: true },
      maximumResults: 32,
    });
    const nearbyEscapeThreats = nearbyEscapeCandidates.filter((entity) =>
      isCurrentlyHostileMob(entity),
    );
    const groupAggroRisk = hasNearbyGroupAggroMember(target, nearbyEscapeCandidates);
    const rangedThreat = deduplicateEntityTargets([target, ...nearbyEscapeThreats]).some(
      (candidate) =>
        candidate.position.dimension === latest.player.position.dimension &&
        (PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(candidate.entityType) ||
          isDistantDrownedThreat(latest.player.position, candidate)),
    );
    if (
      directEscapeSucceeded &&
      FAST_MELEE_PURSUER_ENTITY_TYPES.has(target.entityType) &&
      !hasMeleeWeapon(latest) &&
      (yield* continueDirectFastPursuerEscape(state, latest, target))
    ) {
      return;
    }
    const needsRecovery = yield* needsOverworldSurfaceRecovery(state, latest.player.position);
    const currentlyInFluid = yield* isPlayerInFluid(state.driver, latest.player.position);
    const dryEscapeTarget =
      target.entityType === "minecraft:creeper"
        ? undefined
        : yield* findDryThreatEscapeTarget(
            state,
            latest.player.position,
            target.position,
            currentlyInFluid ? AIR_ESCAPE_SURFACE_SEARCH_RADIUS : MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
          );
    const escapeTarget =
      dryEscapeTarget ?? surfaceEscapeTarget(latest.player.position, target.position);
    const fluidFallbackAllowed =
      target.entityType === "minecraft:creeper" &&
      !directEscapeSucceeded &&
      (yield* state.driver.queryBlocks({
        center: latest.player.position,
        radius: EMERGENCY_ESCAPE_LAVA_CHECK_RADIUS,
        selector: { blockIds: ["minecraft:lava"] },
        maximumResults: 1,
      })).length === 0;
    const dynamicEscape = flee(state.driver, {
      selector: escapeThreatSelector(target, true),
      triggerRadius: rangedThreat
        ? RANGED_THREAT_ESCAPE_TRIGGER_RADIUS
        : PROACTIVE_ESCAPE_ONLY_EVASION_RADIUS,
      safeDistance: rangedThreat ? RANGED_THREAT_ESCAPE_SAFE_DISTANCE : THREAT_ESCAPE_SAFE_DISTANCE,
      completeWhenSafe: true,
      maximumEscapes: SINGLE_THREAT_MAXIMUM_ESCAPES,
      path: {
        ...escapePath,
        allowMining: needsRecovery && !rangedThreat,
        allowPlacing:
          !rangedThreat && isActiveMeleeHostile(target) && pathBuildingBlockCount(latest) > 0,
        // Crossing water outruns arrows, but the only fluid in the Nether is
        // lava. A live bot fled a skeleton there straight into the lava sea.
        avoidFluids: !(
          currentlyInFluid ||
          ((fluidFallbackAllowed || rangedThreat) && !isNether(latest.player.position.dimension))
        ),
        maxFallDistance: Math.min(escapePath.maxFallDistance, MAXIMUM_DAMAGE_FREE_FALL_DISTANCE),
      },
    });
    const requiresDynamicEscape =
      ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType) ||
      rangedThreat ||
      PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(target.entityType) ||
      isActiveConditionalHostile(target);
    const shouldPreferDryEscape =
      dryEscapeTarget !== undefined && (currentlyInFluid || !requiresDynamicEscape);
    const navigation =
      endermanWaterEscapeTarget !== undefined
        ? state.driver.pathfind(endermanWaterEscapeTarget, 0.75, {
            ...escapePath,
            allowMining: false,
            allowPlacing: false,
            avoidFluids: false,
            maxFallDistance: Math.min(
              escapePath.maxFallDistance,
              MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
            ),
          })
        : shouldPreferDryEscape
          ? state.driver.pathfind(dryEscapeTarget, 1.5, {
              ...escapePath,
              allowMining: false,
              allowPlacing: false,
              avoidFluids: !currentlyInFluid,
              maxFallDistance: Math.min(
                escapePath.maxFallDistance,
                MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
              ),
            })
          : requiresDynamicEscape
            ? dynamicEscape
            : needsRecovery
              ? state.driver.pathfind(escapeTarget, 17, {
                  ...escapePath,
                  allowMining: true,
                  allowPlacing: false,
                  avoidFluids: true,
                  maxFallDistance: Math.min(
                    escapePath.maxFallDistance,
                    MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
                  ),
                })
              : latest.player.position.dimension === "minecraft:overworld" &&
                  latest.player.position.y > OVERWORLD_LOW_GROUND_MAX_Y
                ? state.driver.pathfindXZ(
                    escapeTarget.x,
                    escapeTarget.z,
                    latest.player.position.dimension,
                    4,
                    {
                      ...escapePath,
                      allowMining: false,
                      allowPlacing: false,
                      avoidFluids: true,
                      maxFallDistance: Math.min(
                        escapePath.maxFallDistance,
                        MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
                      ),
                    },
                  )
                : dynamicEscape;
    type EscapeNavigationFallback =
      | { readonly type: "escape-route-failed" }
      | { readonly type: "defended" }
      | { readonly type: "covered" };
    const mustContinueEscaping =
      ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType) ||
      groupAggroRisk ||
      (PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
        !shouldCommitToRangedFight(latest, target)) ||
      (PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
        !shouldCommitToMeleeFight(latest, target)) ||
      (isActiveConditionalHostile(target) && !shouldCommitToMeleeFight(latest, target)) ||
      shouldDisengageFromThreat(state, latest, target);
    const safeNavigation = navigation.pipe(
      Effect.as({ type: "escaped" } as const),
      Effect.catch((): Effect.Effect<EscapeNavigationFallback, BeatGameDriverError> =>
        Effect.gen(function* () {
          const current = yield* state.driver.observe;
          if (current.player.dead) {
            return {
              type: "escape-route-failed",
            } satisfies EscapeNavigationFallback;
          }
          if (mustContinueEscaping) {
            const [currentTarget] = yield* state.driver.queryEntities({
              origin: current.player.position,
              radius: THREAT_ESCAPE_SAFE_DISTANCE,
              selector: {
                networkId: target.networkId,
                alive: true,
              },
              maximumResults: 1,
            });
            yield* knockBackAndSprintAway(state, current, currentTarget ?? target);
            return {
              type: "escape-route-failed",
            } satisfies EscapeNavigationFallback;
          }
          // Fighting one of several archers only trades the failed escape for
          // another escape once the ambush check sees the rest.
          if (yield* takeCoverFromRangedFire(state, current, target)) {
            return { type: "covered" } satisfies EscapeNavigationFallback;
          }
          yield* defendAgainstTarget(state, target);
          return { type: "defended" } satisfies EscapeNavigationFallback;
        }),
      ),
    );
    const outcome = yield* Effect.raceFirst(
      safeNavigation,
      monitorEscapeSafety(
        state,
        target,
        latest,
        options.continueEscapingWhenHit ?? false,
        rangedThreat,
      ),
    );
    if (outcome.type === "unsafe-air") {
      const current = yield* state.driver.observe;
      yield* emergencyAirAscent(state, current.player.position);
      return;
    }
    if (outcome.type === "burning") {
      const current = yield* state.driver.observe;
      yield* escapeEnvironmentalDamage(state, current.player.position);
      return;
    }
    if (outcome.type === "escaped") {
      yield* Ref.set(state.failedEscapes, { count: 0, atMs: 0 });
    }
    if (outcome.type === "escape-route-failed") {
      if (yield* fightAfterRepeatedFailedEscapes(state, target)) {
        return;
      }
      yield* recoverAfterFailedEscape(state, target);
      return;
    }
    if (outcome.type === "defend") {
      yield* defendAndRecover(state, outcome.target);
      return;
    }
    if (outcome.type === "knockback") {
      const current = yield* state.driver.observe;
      if (current.player.dead) {
        return;
      }
      // A zombie keeps pace with a fleeing player. A live run struck and
      // fled from one about every half second while it and a second zombie
      // kept hitting. Once running has failed twice, an armed bot fights.
      const knockbackStrikes = options.knockbackStrikes ?? 0;
      if (knockbackStrikes >= MAXIMUM_KNOCKBACK_ESCAPES && hasMeleeWeapon(current)) {
        yield* emit(state, {
          type: "diagnostic",
          message: "Fighting a melee attacker that kept pace with the escape",
          data: {
            attackerNetworkId: outcome.target.networkId,
            attackerType: outcome.target.entityType,
            health: current.player.health,
          },
        });
        yield* defendAndRecover(state, outcome.target, { commitThroughWound: true });
        return;
      }
      yield* state.driver.withControl(performKnockbackStrike(state, current, outcome.target));
      yield* escapeFromTarget(state, target, {
        ...options,
        knockbackStrikes: knockbackStrikes + 1,
      });
      return;
    }
    if (outcome.type === "escape") {
      yield* escapeFromTarget(state, outcome.target, options);
    }
  });
}

// Two skeletons shot a live run to death on a mountain top while it had no
// escape route and 66 cobblestone. Walls stop arrows, so a bot that cannot
// run walls itself in and waits behind them.
interface RangedCover {
  readonly method: "sealed-shaft" | "walls";
  readonly placedBlocks: number;
}

function takeCoverFromRangedFire(
  state: RunState,
  observation: BeatGameObservation,
  threat: BeatGameEntityObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    // A live run fled a zombie with two skeletons behind it and died to
    // their arrows, so any archer nearby counts, not only the one escaped.
    // Arrows also came from skeletons beyond the entity query range, and
    // tridents from drowned the bot never saw, so a recent projectile hit
    // counts as an archer too.
    const lastDamage = yield* Ref.get(state.lastDamage);
    const recentArrowHit =
      lastDamage?.damageType !== undefined &&
      RANGED_ATTACK_DAMAGE_TYPES.has(lastDamage.damageType) &&
      (yield* Clock.currentTimeMillis) - Date.parse(lastDamage.observedAt) <= RECENT_ARROW_HIT_MS;
    const archerNearby =
      recentArrowHit ||
      PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(threat.entityType) ||
      (yield* state.driver.queryEntities({
        origin: observation.player.position,
        radius: RANGED_THREAT_ESCAPE_TRIGGER_RADIUS,
        selector: { entityTypes: [...PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES], alive: true },
        maximumResults: 4,
      })).some((entity) => PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(entity.entityType));
    if (!archerNearby) {
      return false;
    }
    // Digging in takes seconds, and a melee mob that close keeps hitting the
    // bot down the shaft. A live run dug in beside a zombie and was hit every
    // second until it died. Walls go up in place without digging.
    const closeMeleeHostile = (yield* state.driver.queryEntities({
      origin: observation.player.position,
      radius: COVER_MELEE_EXCLUSION_RADIUS,
      selector: { categories: [2], alive: true },
      maximumResults: 8,
    })).some(
      (entity) =>
        isActiveMeleeHostile(entity) &&
        !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(entity.entityType) &&
        distanceSquared(observation.player.position, entity.position) <=
          COVER_MELEE_EXCLUSION_RADIUS ** 2,
    );
    // Walls go up one block around the bot, so a melee mob already inside
    // that ring stays in. A live run walled itself in with a zombie half a
    // block away and was hit to death while the walls went up. An armed bot
    // fights that mob instead.
    const meleeHostileInsideWalls = (yield* state.driver.queryEntities({
      origin: observation.player.position,
      radius: COVER_WALL_RING_RADIUS,
      selector: { categories: [2], alive: true },
      maximumResults: 4,
    })).some(
      (entity) =>
        isActiveMeleeHostile(entity) &&
        !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(entity.entityType) &&
        Math.hypot(
          entity.position.x - observation.player.position.x,
          entity.position.z - observation.player.position.z,
        ) <= COVER_WALL_RING_RADIUS,
    );
    if (meleeHostileInsideWalls && hasMeleeWeapon(observation)) {
      return false;
    }
    // The supervisors that started this escape cancelled a live wall-in
    // halfway when the next arrow hit. Stop moving first, and let the few
    // placements finish.
    //
    // A wall-in on open ground needs a roof placed on top of the walls,
    // which the eyes cannot see from below, and a live run died while every
    // roof placement was refused. Digging down and sealing the shaft only
    // places against a shaft wall the eyes face, so it goes first.
    const coverStartedAt = yield* DateTime.now.pipe(Effect.map(DateTime.formatIso));
    const cover = yield* takeCoverInPlace(state, { allowShaft: !closeMeleeHostile });
    if (cover === undefined) {
      const coverDamage = yield* Ref.get(state.lastDamage);
      if (
        coverDamage?.damageType !== undefined &&
        ESCAPE_BURNING_DAMAGE_TYPES.has(coverDamage.damageType) &&
        Date.parse(coverDamage.observedAt) > Date.parse(coverStartedAt)
      ) {
        const burning = yield* state.driver.observe;
        yield* stepOffBurningGround(state, burning);
      }
      return false;
    }
    // Walls that were already up keep a cave full of mobs out, but they
    // never leave. A live run hid behind finished walls for half an hour
    // while three zombies and two skeletons waited outside. Once healed, an
    // armed bot fights its way out instead.
    const sheltered = yield* state.driver.observe;
    if (
      cover.placedBlocks === 0 &&
      sheltered.player.health >= state.strategy.minimumHealth &&
      hasMeleeWeapon(sheltered)
    ) {
      yield* emit(state, {
        type: "diagnostic",
        message: "Fighting out of finished cover at full health",
        data: {
          threatNetworkId: threat.networkId,
          threatType: threat.entityType,
          health: sheltered.player.health,
        },
      });
      return false;
    }
    yield* emit(state, {
      type: "diagnostic",
      message: "Took cover from a ranged attacker after the escape route failed",
      data: {
        threatNetworkId: threat.networkId,
        threatType: threat.entityType,
        position: observation.player.position,
        method: cover.method,
        placedBlocks: cover.placedBlocks,
      },
    });
    // Health only regenerates on a nearly full stomach, and a live run sat
    // in cover at 13 health and 15 food for eleven minutes without healing.
    if (sheltered.player.food < 20 && hasUsableFood(sheltered)) {
      yield* eatUnlessEndangered(state, {
        foodItemIds: preferredUsableFoodItemIds(sheltered),
        foodLevel: 20,
        maximumMeals: 1,
        completeWhenNoFood: true,
        path: state.strategy.path,
      }).pipe(Effect.catchTag("BeatGameDriverError", () => Effect.void));
    }
    // A fireball can set the cell inside the cover alight. A live bot sat in
    // fire inside its own walls for six seconds and burned to death.
    const waitStartedAt = yield* DateTime.now.pipe(Effect.map(DateTime.formatIso));
    const burning = yield* waitForBurningDamageSince(state, waitStartedAt).pipe(
      Effect.as(true),
      Effect.timeoutOrElse({
        duration: RANGED_COVER_WAIT_MS,
        orElse: () => Effect.succeed(false),
      }),
    );
    if (burning) {
      const current = yield* state.driver.observe;
      yield* stepOffBurningGround(state, current);
    }
    return true;
  });
}

function waitForBurningDamageSince(state: RunState, since: string): Effect.Effect<void> {
  const sinceMs = Date.parse(since);
  return Effect.gen(function* () {
    for (;;) {
      const damage = yield* Ref.get(state.lastDamage);
      if (
        damage?.damageType !== undefined &&
        ESCAPE_BURNING_DAMAGE_TYPES.has(damage.damageType) &&
        Date.parse(damage.observedAt) > sinceMs
      ) {
        return;
      }
      yield* Effect.sleep(MINIMUM_RECOVERY_POLL_MS);
    }
  });
}

// Digs down and seals the shaft, or walls the bot in where it stands. Both
// keep out arrows, and a three-block-tall enderman cannot reach into either.
//
// The cover goes up even when a supervisor asks it to stop, but fire stops
// it. A live bot dug a shaft by hand where a fireball had lit the ground,
// and burned from 17 health to 0 before the shaft was done.
function takeCoverInPlace(
  state: RunState,
  options: { readonly allowShaft?: boolean } = {},
): Effect.Effect<RangedCover | undefined> {
  return DateTime.now.pipe(
    Effect.map(DateTime.formatIso),
    Effect.flatMap((startedAt) =>
      Effect.uninterruptibleMask((restore) =>
        Effect.raceFirst(
          restore(buildCoverInPlace(state, options)),
          waitForBurningDamageSince(state, startedAt).pipe(Effect.as(undefined)),
        ),
      ),
    ),
  );
}

function buildCoverInPlace(
  state: RunState,
  options: { readonly allowShaft?: boolean },
): Effect.Effect<RangedCover | undefined> {
  return state.driver.act({ type: "reset-movement" }).pipe(
    Effect.andThen(state.driver.observe),
    Effect.flatMap((current) =>
      (options.allowShaft === false
        ? Effect.undefined
        : digAndSealNightShelter(state, current)
      ).pipe(
        Effect.flatMap((seal): Effect.Effect<RangedCover | undefined, BeatGameDriverError> =>
          seal !== undefined
            ? Effect.succeed({ method: "sealed-shaft", placedBlocks: 1 } as const)
            : state.driver.observe.pipe(
                Effect.flatMap((latest) => encloseNightShelterInPlace(state, latest)),
                Effect.map((enclosure) =>
                  enclosure === undefined
                    ? undefined
                    : ({ method: "walls", placedBlocks: enclosure.placed.length } as const),
                ),
              ),
        ),
      ),
    ),
    Effect.catchTag("BeatGameDriverError", () => Effect.undefined),
  );
}

/// A live bot at full health with a sword stood in a blaze spawner room and
/// tried to flee its three blazes 557 times in fifteen minutes. Each flee
/// found no route and the next began a second later. Later it sat walled in
/// at 11 health and 17 food for twenty minutes, untouched, and never ate
/// because every pass of its recovery fled again. After a few failed escapes
/// in a row, a bot that nothing is hitting eats or waits to heal, and one
/// that is being hit fights back.
function fightAfterRepeatedFailedEscapes(
  state: RunState,
  target: BeatGameEntityObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const now = yield* Clock.currentTimeMillis;
    const previous = yield* Ref.get(state.failedEscapes);
    const count = now - previous.atMs <= FAILED_ESCAPE_STREAK_WINDOW_MS ? previous.count + 1 : 1;
    yield* Ref.set(state.failedEscapes, { count, atMs: now });
    const current = yield* state.driver.observe;
    if (
      count < FAILED_ESCAPE_FIGHT_THRESHOLD ||
      current.player.dead ||
      ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType) ||
      target.entityType === "minecraft:enderman"
    ) {
      return false;
    }
    const lastDamage = yield* Ref.get(state.lastDamage);
    const recentlyHit =
      lastDamage !== undefined &&
      now - Date.parse(lastDamage.observedAt) <= FAILED_ESCAPE_RECENT_HIT_MS;
    if (!recentlyHit) {
      if (current.player.food < 20 && hasUsableFood(current)) {
        yield* Ref.set(state.failedEscapes, { count: 0, atMs: 0 });
        yield* emit(state, {
          type: "diagnostic",
          message: "Eating where it stands after repeated failed escapes",
          data: { threatNetworkId: target.networkId, failedEscapes: count },
        });
        yield* eatUnlessEndangered(state, {
          foodItemIds: preferredUsableFoodItemIds(current),
          foodLevel: 20,
          maximumMeals: 1,
          completeWhenNoFood: true,
          path: state.strategy.path,
        }).pipe(Effect.catchTag("BeatGameDriverError", () => Effect.void));
        return true;
      }
      if (current.player.health < state.strategy.minimumHealth) {
        yield* Ref.set(state.failedEscapes, { count: 0, atMs: 0 });
        yield* Effect.sleep(FAILED_ESCAPE_HEAL_WAIT_MS);
        return true;
      }
    }
    if (!hasMeleeWeapon(current)) {
      return false;
    }
    yield* Ref.set(state.failedEscapes, { count: 0, atMs: 0 });
    yield* emit(state, {
      type: "diagnostic",
      message: "Fighting after repeated failed escapes",
      data: {
        threatNetworkId: target.networkId,
        threatType: target.entityType,
        failedEscapes: count,
        health: current.player.health,
      },
    });
    yield* defendAgainstTarget(state, target, { commitThroughWound: true }).pipe(
      Effect.catchTag("BeatGameDriverError", () => Effect.void),
    );
    return true;
  });
}

function recoverAfterFailedEscape(
  state: RunState,
  escapedTarget: BeatGameEntityObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    if (observation.player.dead) {
      return;
    }
    if (yield* takeCoverFromRangedFire(state, observation, escapedTarget)) {
      return;
    }
    const nearbyThreat = yield* findNearbyAttackThreat(state, observation);
    const closeMeleeThreat = nearbyThreat?.target;
    const canFightOut =
      closeMeleeThreat !== undefined &&
      PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(closeMeleeThreat.entityType) &&
      !GROUP_AGGRO_ENTITY_TYPES.has(closeMeleeThreat.entityType) &&
      !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(closeMeleeThreat.entityType) &&
      !ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(closeMeleeThreat.entityType) &&
      closeMeleeThreat.entityType !== "minecraft:enderman" &&
      distanceSquared(observation.player.position, closeMeleeThreat.position) <=
        EMERGENCY_KNOCKBACK_RANGE ** 2;
    if (canFightOut) {
      yield* emit(state, {
        type: "diagnostic",
        message: "Fighting a close pursuer after its escape route failed",
        data: {
          escapedThreatNetworkId: escapedTarget.networkId,
          pursuerNetworkId: closeMeleeThreat.networkId,
          pursuerType: closeMeleeThreat.entityType,
          position: observation.player.position,
        },
      });
      const defended = yield* defendAgainstTarget(state, closeMeleeThreat, {
        commitThroughWound: true,
      }).pipe(
        Effect.as(true),
        Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
      );
      if (defended) {
        return;
      }
    }
    // A mob that is neither aggressive nor targeting anything is not chasing
    // the bot. Waiting for it to leave stalled a live run for 9 minutes.
    const activeMeleePursuer =
      closeMeleeThreat !== undefined &&
      PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(closeMeleeThreat.entityType) &&
      !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(closeMeleeThreat.entityType) &&
      !ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(closeMeleeThreat.entityType) &&
      closeMeleeThreat.entityType !== "minecraft:enderman" &&
      (closeMeleeThreat.aggressive !== false || closeMeleeThreat.target !== undefined);
    if (activeMeleePursuer) {
      yield* emit(state, {
        type: "diagnostic",
        message: "Deferred local navigation recovery while a melee pursuer remained active",
        data: {
          escapedThreatNetworkId: escapedTarget.networkId,
          pursuerNetworkId: closeMeleeThreat.networkId,
          pursuerType: closeMeleeThreat.entityType,
          position: observation.player.position,
        },
      });
      return;
    }
    const current = yield* state.driver.observe;
    if (!current.player.dead) {
      // This climb can run for minutes inside a safety response, where no
      // monitor watches for attacks. A live run climbed from y -22 while a
      // zombie hit it ten times to death, so an attack ends the climb.
      yield* Effect.raceFirst(
        recoverLocalNavigationTrap(state, current.player.position).pipe(Effect.asVoid),
        waitForMobAttackSince(state, current.observedAt),
      );
    }
  });
}

function waitForMobAttackSince(state: RunState, since: string): Effect.Effect<void> {
  const sinceMs = Date.parse(since);
  return Effect.gen(function* () {
    for (;;) {
      const damage = yield* Ref.get(state.lastDamage);
      if (
        damage?.damageType !== undefined &&
        ATTACK_DAMAGE_TYPES.has(damage.damageType) &&
        Date.parse(damage.observedAt) > sinceMs
      ) {
        return;
      }
      yield* Effect.sleep(MINIMUM_RECOVERY_POLL_MS);
    }
  });
}

function continueDirectFastPursuerEscape(
  state: RunState,
  initialObservation: BeatGameObservation,
  initialTarget: BeatGameEntityObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    let observation = initialObservation;
    let target = initialTarget;
    for (let attempt = 0; attempt < FAST_PURSUER_ADDITIONAL_DIRECT_ESCAPE_ATTEMPTS; attempt += 1) {
      const [currentTarget] = yield* state.driver.queryEntities({
        origin: observation.player.position,
        radius: THREAT_ESCAPE_SAFE_DISTANCE,
        selector: { networkId: target.networkId, alive: true },
        maximumResults: 1,
      });
      if (currentTarget === undefined) {
        return true;
      }
      target = currentTarget;
      if (
        distanceSquared(observation.player.position, target.position) >=
        THREAT_ESCAPE_SAFE_DISTANCE ** 2
      ) {
        return true;
      }
      if (!(yield* knockBackAndSprintAway(state, observation, target))) {
        return false;
      }
      observation = yield* state.driver.observe;
      if (observation.player.dead) {
        return true;
      }
    }

    const [remainingTarget] = yield* state.driver.queryEntities({
      origin: observation.player.position,
      radius: THREAT_ESCAPE_SAFE_DISTANCE,
      selector: { networkId: target.networkId, alive: true },
      maximumResults: 1,
    });
    return (
      remainingTarget === undefined ||
      distanceSquared(observation.player.position, remainingTarget.position) >=
        THREAT_ESCAPE_SAFE_DISTANCE ** 2
    );
  });
}

function findEndermanWaterEscapeTarget(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<BeatGamePosition | undefined, BeatGameDriverError> {
  return state.driver
    .queryBlocks({
      center: position,
      radius: ENDERMAN_WATER_ESCAPE_RADIUS,
      selector: { blockIds: ["minecraft:water"] },
      maximumResults: 64,
    })
    .pipe(
      Effect.map(
        (water) =>
          [...water]
            .filter(
              (block) =>
                block.position.dimension === position.dimension &&
                Math.abs(block.position.y - position.y) <= 4,
            )
            .toSorted(
              (left, right) =>
                distanceSquared(left.position, position) -
                distanceSquared(right.position, position),
            )
            .map((block) => blockCenter(block.position))[0],
      ),
    );
}

function monitorEscapeSafety(
  state: RunState,
  target: BeatGameEntityObservation,
  previousObservation: BeatGameObservation,
  continueEscapingWhenHit: boolean,
  rangedGroupEscape = false,
): Effect.Effect<
  | { readonly type: "dead" | "safe" | "unsafe-air" | "burning" }
  | {
      readonly type: "defend" | "escape" | "knockback";
      readonly target: BeatGameEntityObservation;
    },
  BeatGameDriverError
> {
  const monitoringRadius =
    rangedGroupEscape || PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType)
      ? RANGED_THREAT_ESCAPE_SAFE_DISTANCE
      : THREAT_ESCAPE_SAFE_DISTANCE;
  const pollInterval =
    target.entityType === "minecraft:creeper" ||
    FAST_MELEE_PURSUER_ENTITY_TYPES.has(target.entityType)
      ? MINIMUM_RECOVERY_POLL_MS
      : Math.max(MINIMUM_RECOVERY_POLL_MS, state.strategy.observationPollMs);
  return Effect.sleep(pollInterval).pipe(
    Effect.andThen(
      Effect.all([state.driver.observe, Ref.get(state.lastDamage), Clock.currentTimeMillis], {
        concurrency: 1,
      }),
    ),
    Effect.flatMap(([observation, lastDamage, now]) => {
      if (observation.player.dead) {
        return Effect.succeed({ type: "dead" } as const);
      }
      if (hasUnsafeAir(observation)) {
        return Effect.succeed({ type: "unsafe-air" } as const);
      }
      // A live flee stood in fire left by a blaze fireball for fourteen
      // seconds and burned to death. Burning ground ends the escape.
      if (
        lastDamage?.damageType !== undefined &&
        ESCAPE_BURNING_DAMAGE_TYPES.has(lastDamage.damageType) &&
        now - Date.parse(lastDamage.observedAt) <= RECENT_LAVA_DAMAGE_MS
      ) {
        return Effect.succeed({ type: "burning" } as const);
      }
      return state.driver
        .queryEntities({
          origin: observation.player.position,
          radius: monitoringRadius,
          selector: escapeThreatSelector(target),
          maximumResults: 1,
        })
        .pipe(
          Effect.zip(findImmediateThreat(state, observation)),
          Effect.zip(
            state.driver.queryEntities({
              origin: {
                ...observation.player.position,
                y: observation.player.position.y + 1.62,
              },
              radius: CREEPER_EMERGENCY_REEVASION_RADIUS,
              selector: {
                entityTypes: ["minecraft:creeper"],
                alive: true,
                requireLineOfSight: true,
              },
              maximumResults: 8,
            }),
          ),
          Effect.zip(
            GROUP_AGGRO_ENTITY_TYPES.has(target.entityType)
              ? state.driver.queryEntities({
                  origin: observation.player.position,
                  radius: monitoringRadius,
                  selector: {
                    entityTypes: groupAggroFamilyEntityTypes(target),
                    alive: true,
                  },
                  maximumResults: 32,
                })
              : Effect.succeed<readonly BeatGameEntityObservation[]>([]),
          ),
          Effect.flatMap(
            ([[[[currentTarget], immediateThreat], visibleCreepers], nearbyGroupCandidates]) => {
              const urgentCreeper =
                immediateThreat?.response === "flee" &&
                immediateThreat.target.entityType === "minecraft:creeper" &&
                distanceSquared(observation.player.position, immediateThreat.target.position) <=
                  CREEPER_EMERGENCY_REEVASION_RADIUS ** 2
                  ? immediateThreat.target
                  : currentTarget?.entityType === "minecraft:creeper" &&
                      distanceSquared(observation.player.position, currentTarget.position) <=
                        CREEPER_EMERGENCY_REEVASION_RADIUS ** 2
                    ? currentTarget
                    : undefined;
              const visibleUrgentCreeper =
                urgentCreeper === undefined
                  ? undefined
                  : visibleCreepers.find((creeper) => isSameEntityTarget(creeper, urgentCreeper));
              const nearbyVisibleCreepers = visibleCreepers.filter(
                (creeper) =>
                  creeper.entityType === "minecraft:creeper" &&
                  distanceSquared(observation.player.position, creeper.position) <=
                    CREEPER_EMERGENCY_REEVASION_RADIUS ** 2,
              );
              const urgentCreeperDistanceSquared =
                visibleUrgentCreeper === undefined
                  ? Number.POSITIVE_INFINITY
                  : distanceSquared(observation.player.position, visibleUrgentCreeper.position);
              const previousTargetDistanceSquared = distanceSquared(
                previousObservation.player.position,
                target.position,
              );
              if (
                visibleUrgentCreeper !== undefined &&
                (!isSameEntityTarget(target, visibleUrgentCreeper) ||
                  urgentCreeperDistanceSquared <= CREEPER_CRITICAL_REEVASION_RADIUS ** 2 ||
                  urgentCreeperDistanceSquared + 1 < previousTargetDistanceSquared ||
                  nearbyVisibleCreepers.length > 1)
              ) {
                return Effect.succeed({
                  type: "escape",
                  target: visibleUrgentCreeper,
                } as const);
              }
              if (
                immediateThreat?.response === "flee" &&
                shouldPreemptEscapeTarget(target, immediateThreat.target)
              ) {
                return Effect.succeed({
                  type: "escape",
                  target: immediateThreat.target,
                } as const);
              }
              if (requiresPersistentGroupEscape(target, nearbyGroupCandidates)) {
                return monitorEscapeSafety(
                  state,
                  target,
                  observation,
                  continueEscapingWhenHit,
                  rangedGroupEscape,
                );
              }
              if (currentTarget === undefined) {
                return immediateThreat === undefined
                  ? Effect.succeed({ type: "safe" } as const)
                  : Effect.succeed({
                      type: immediateThreat.response === "flee" ? "escape" : "defend",
                      target: immediateThreat.target,
                    } as const);
              }
              const caughtMeleeAttacker =
                PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(currentTarget.entityType) &&
                !GROUP_AGGRO_ENTITY_TYPES.has(currentTarget.entityType) &&
                !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(currentTarget.entityType) &&
                distanceSquared(observation.player.position, currentTarget.position) <=
                  EMERGENCY_KNOCKBACK_RANGE ** 2;
              if (
                caughtMeleeAttacker &&
                (observation.player.health < CAUGHT_MELEE_COMMIT_MINIMUM_HEALTH ||
                  (currentTarget.entityType === "minecraft:drowned" &&
                    !shouldCommitToMeleeFight(observation, currentTarget)))
              ) {
                return Effect.succeed({
                  type: "knockback",
                  target: currentTarget,
                } as const);
              }
              if (
                shouldCommitToCaughtRangedFight(observation, currentTarget) ||
                (shouldCommitToMeleeFight(observation, currentTarget) &&
                  hasMeleeWeapon(observation) &&
                  immediateThreat?.response !== "flee")
              ) {
                return Effect.succeed({
                  type: "defend",
                  target: currentTarget,
                } as const);
              }
              if (
                FAST_MELEE_PURSUER_ENTITY_TYPES.has(currentTarget.entityType) &&
                distanceSquared(observation.player.position, currentTarget.position) <=
                  EMERGENCY_KNOCKBACK_RANGE ** 2
              ) {
                const groupEscapeRequired =
                  GROUP_AGGRO_ENTITY_TYPES.has(currentTarget.entityType) &&
                  isActiveConditionalHostile(currentTarget) &&
                  immediateThreat?.response === "flee";
                const shouldFightCaughtPursuer =
                  shouldCommitToMeleeFight(observation, currentTarget) &&
                  (hasMeleeWeapon(observation) ||
                    observation.player.health < previousObservation.player.health) &&
                  !groupEscapeRequired;
                return Effect.succeed({
                  type: groupEscapeRequired
                    ? "escape"
                    : shouldFightCaughtPursuer
                      ? "defend"
                      : "escape",
                  target: currentTarget,
                } as const);
              }
              const secondaryMeleeAttacker = immediateThreat?.target;
              if (
                ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType) &&
                secondaryMeleeAttacker !== undefined &&
                !isSameEntityTarget(target, secondaryMeleeAttacker) &&
                PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(secondaryMeleeAttacker.entityType) &&
                !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(secondaryMeleeAttacker.entityType) &&
                distanceSquared(observation.player.position, secondaryMeleeAttacker.position) <=
                  EMERGENCY_KNOCKBACK_RANGE ** 2
              ) {
                return Effect.succeed({
                  type: "knockback",
                  target: secondaryMeleeAttacker,
                } as const);
              }
              if (observation.player.health >= previousObservation.player.health) {
                return monitorEscapeSafety(
                  state,
                  target.entityType === "minecraft:creeper" ? target : currentTarget,
                  observation,
                  continueEscapingWhenHit,
                  rangedGroupEscape,
                );
              }
              return findNearbyAttackThreat(state, observation).pipe(
                Effect.flatMap((threat) => {
                  if (threat === undefined) {
                    return monitorEscapeSafety(
                      state,
                      currentTarget,
                      observation,
                      continueEscapingWhenHit,
                      rangedGroupEscape,
                    );
                  }
                  if (
                    ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(target.entityType) &&
                    !isSameEntityTarget(target, threat.target)
                  ) {
                    const closeMeleeAttacker =
                      PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(threat.target.entityType) &&
                      !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(threat.target.entityType) &&
                      distanceSquared(observation.player.position, threat.target.position) <=
                        EMERGENCY_KNOCKBACK_RANGE ** 2;
                    return closeMeleeAttacker
                      ? Effect.succeed({
                          type: "knockback",
                          target: threat.target,
                        } as const)
                      : monitorEscapeSafety(
                          state,
                          target,
                          observation,
                          continueEscapingWhenHit,
                          rangedGroupEscape,
                        );
                  }
                  const caughtByCloseMeleePursuer =
                    PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(threat.target.entityType) &&
                    !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(threat.target.entityType) &&
                    distanceSquared(observation.player.position, threat.target.position) <=
                      EMERGENCY_KNOCKBACK_RANGE ** 2;
                  if (caughtByCloseMeleePursuer && isSameEntityTarget(target, threat.target)) {
                    const shouldFightCaughtPursuer = shouldCommitToMeleeFight(
                      observation,
                      threat.target,
                    );
                    if (!shouldFightCaughtPursuer && continueEscapingWhenHit) {
                      return monitorEscapeSafety(
                        state,
                        currentTarget,
                        observation,
                        continueEscapingWhenHit,
                        rangedGroupEscape,
                      );
                    }
                    return Effect.succeed({
                      type: shouldFightCaughtPursuer ? "defend" : "escape",
                      target: threat.target,
                    } as const);
                  }
                  const shouldKeepEscaping =
                    continueEscapingWhenHit &&
                    !shouldCommitToMeleeFight(observation, threat.target);
                  if (
                    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(threat.target.entityType) &&
                    threat.response === "flee" &&
                    isSameEntityTarget(target, threat.target)
                  ) {
                    return Effect.succeed({
                      type: "escape",
                      target: threat.target,
                    } as const);
                  }
                  if (
                    (shouldKeepEscaping || threat.response === "flee") &&
                    isSameEntityTarget(target, threat.target)
                  ) {
                    return monitorEscapeSafety(
                      state,
                      currentTarget,
                      observation,
                      continueEscapingWhenHit,
                      rangedGroupEscape,
                    );
                  }
                  return Effect.succeed({
                    type: shouldKeepEscaping || threat.response === "flee" ? "escape" : "defend",
                    target: threat.target,
                  } as const);
                }),
              );
            },
          ),
        );
    }),
  );
}

function shouldPreemptEscapeTarget(
  currentTarget: BeatGameEntityObservation,
  candidate: BeatGameEntityObservation,
): boolean {
  if (isSameEntityTarget(currentTarget, candidate)) {
    return false;
  }
  if (candidate.entityType === "minecraft:creeper") {
    return currentTarget.entityType !== "minecraft:creeper";
  }
  return (
    ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(candidate.entityType) &&
    !ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(currentTarget.entityType)
  );
}

function isSameEntityTarget(
  left: BeatGameEntityObservation,
  right: BeatGameEntityObservation,
): boolean {
  return left.connectionEpoch === right.connectionEpoch && left.networkId === right.networkId;
}

function knockBackAndSprintAway(
  state: RunState,
  observation: BeatGameObservation,
  threat: BeatGameEntityObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  const playerPosition = observation.player.position;
  const distance = Math.sqrt(distanceSquared(playerPosition, threat.position));
  return Effect.gen(function* () {
    const nearbyLava = yield* state.driver.queryBlocks({
      center: playerPosition,
      radius: EMERGENCY_ESCAPE_LAVA_CHECK_RADIUS,
      selector: { blockIds: ["minecraft:lava"] },
      maximumResults: 1,
    });
    const currentlyInFluid = yield* isPlayerInFluid(state.driver, playerPosition);
    const nearbyEntityCandidates = yield* state.driver.queryEntities({
      origin: playerPosition,
      radius: RANGED_THREAT_ESCAPE_SAFE_DISTANCE,
      selector: { categories: [2], alive: true },
      maximumResults: 32,
    });
    const nearbyThreats = nearbyEntityCandidates.filter((entity) => isCurrentlyHostileMob(entity));
    const groupAggroRisk = hasNearbyGroupAggroMember(threat, nearbyEntityCandidates);
    const nearbyGroupMembers = groupAggroRisk
      ? nearbyEntityCandidates.filter((candidate) => sharesGroupAggroFamily(threat, candidate))
      : [];
    const escapeThreats = deduplicateEntityTargets([
      threat,
      ...nearbyThreats,
      ...nearbyGroupMembers,
    ]).filter((candidate) => candidate.position.dimension === playerPosition.dimension);
    const rangedThreat = escapeThreats.some(
      (candidate) =>
        PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(candidate.entityType) ||
        isDistantDrownedThreat(playerPosition, candidate),
    );
    const directAquaticEvasion =
      currentlyInFluid && (rangedThreat || threat.entityType === "minecraft:drowned");
    const directSprintDistance =
      threat.entityType === "minecraft:creeper"
        ? CREEPER_ESCAPE_SURFACE_PROJECTION_DISTANCE
        : rangedThreat
          ? RANGED_ESCAPE_SURFACE_PROJECTION_DISTANCE
          : EMERGENCY_ESCAPE_SURFACE_PROJECTION_DISTANCE;
    const escapeSurface = yield* state.driver.sampleSurface(
      playerPosition,
      directSprintDistance,
      1,
    );
    const preferredTarget = surfaceEscapeTarget(playerPosition, threat.position);
    const preferredDirection = {
      x: preferredTarget.x - playerPosition.x,
      z: preferredTarget.z - playerPosition.z,
    };
    const dryDirection = selectSafeDirectEscapeDirection(
      escapeSurface,
      playerPosition,
      preferredDirection,
      escapeThreats,
      directSprintDistance,
      rangedThreat ? 2 : DIRECT_ESCAPE_MINIMUM_LATERAL_HALF_WIDTH,
      {
        minimumPreferredAlignment:
          threat.entityType === "minecraft:creeper"
            ? CREEPER_ESCAPE_MINIMUM_AWAY_ALIGNMENT
            : rangedThreat
              ? RANGED_ESCAPE_MINIMUM_AWAY_ALIGNMENT
              : -1,
      },
    );
    const alignedSwimmableDirection =
      threat.entityType === "minecraft:creeper" && dryDirection === undefined
        ? selectSafeDirectEscapeDirection(
            escapeSurface,
            playerPosition,
            preferredDirection,
            escapeThreats,
            directSprintDistance,
            0,
            {
              allowSwimmableSurface: true,
              minimumPreferredAlignment: CREEPER_ESCAPE_MINIMUM_AWAY_ALIGNMENT,
            },
          )
        : undefined;
    const groupSafeDryDirection =
      threat.entityType === "minecraft:creeper" &&
      dryDirection === undefined &&
      alignedSwimmableDirection === undefined &&
      escapeThreats.length > 1
        ? selectSafeDirectEscapeDirection(
            escapeSurface,
            playerPosition,
            preferredDirection,
            escapeThreats,
            directSprintDistance,
            rangedThreat ? 2 : DIRECT_ESCAPE_MINIMUM_LATERAL_HALF_WIDTH,
          )
        : undefined;
    const groupSafeSwimmableDirection =
      threat.entityType === "minecraft:creeper" &&
      dryDirection === undefined &&
      alignedSwimmableDirection === undefined &&
      groupSafeDryDirection === undefined &&
      escapeThreats.length > 1
        ? selectSafeDirectEscapeDirection(
            escapeSurface,
            playerPosition,
            preferredDirection,
            escapeThreats,
            directSprintDistance,
            0,
            { allowSwimmableSurface: true },
          )
        : undefined;
    const direction =
      dryDirection ??
      alignedSwimmableDirection ??
      groupSafeDryDirection ??
      groupSafeSwimmableDirection;
    const projectedPosition =
      direction === undefined
        ? playerPosition
        : {
            x: playerPosition.x + direction.x * EMERGENCY_ESCAPE_FLUID_PROJECTION_DISTANCE,
            y: playerPosition.y,
            z: playerPosition.z + direction.z * EMERGENCY_ESCAPE_FLUID_PROJECTION_DISTANCE,
            dimension: playerPosition.dimension,
          };
    const projectedIntoFluid =
      threat.entityType !== "minecraft:creeper" &&
      (yield* isPlayerInFluid(state.driver, projectedPosition));
    const emergencyBlindCreeperSprint =
      threat.entityType === "minecraft:creeper" &&
      distance <= CREEPER_EMERGENCY_REEVASION_RADIUS &&
      escapeThreats.length === 1 &&
      nearbyLava.length === 0;
    // A blind sprint takes no account of drops. A live bot ran off a
    // 46-block cave drop this way, so a raised shield takes the blast instead.
    if (
      direction === undefined &&
      emergencyBlindCreeperSprint &&
      (observation.inventory.counts["minecraft:shield"] ?? 0) > 0
    ) {
      yield* blockCreeperExplosion(state, observation, threat);
      return true;
    }
    return yield* state.driver.withControl(
      Effect.gen(function* () {
        if (
          distance <= EMERGENCY_KNOCKBACK_RANGE &&
          !isFusingCreeper(threat) &&
          (!groupAggroRisk || isAggressiveNeutralMob(threat))
        ) {
          yield* performKnockbackStrike(state, observation, threat);
        }
        if (
          nearbyLava.length > 0 ||
          (projectedIntoFluid && !directAquaticEvasion) ||
          (direction === undefined && !directAquaticEvasion && !emergencyBlindCreeperSprint)
        ) {
          return false;
        }
        const escapeDirection = direction ?? normalizeHorizontalDirection(preferredDirection);
        let currentEscapeDirection = escapeDirection;
        const lookAlongEscapeDirection = (
          position: BeatGamePosition,
        ): Effect.Effect<unknown, BeatGameDriverError> => {
          const rotation = rotationToward(position, {
            x: position.x + currentEscapeDirection.x,
            y: position.y,
            z: position.z + currentEscapeDirection.z,
          });
          return state.driver.act({
            type: "look",
            yaw: rotation.yaw,
            pitch: 0,
          });
        };
        yield* lookAlongEscapeDirection(playerPosition);
        yield* state.driver.act({
          type: "set-movement",
          forward: true,
          jump: true,
          sprint: true,
        });
        const sprintDuration =
          threat.entityType === "minecraft:creeper"
            ? CREEPER_ESCAPE_SPRINT_MS
            : rangedThreat
              ? RANGED_ESCAPE_SPRINT_MS
              : EMERGENCY_ESCAPE_SPRINT_MS;
        if (rangedThreat) {
          const strafeDuration = Math.floor(sprintDuration / 5);
          for (let step = 0; step < 5; step += 1) {
            const left = step % 2 === 0;
            yield* state.driver.act({
              type: "set-movement",
              left,
              right: !left,
            });
            yield* Effect.sleep(strafeDuration);
          }
        } else if (threat.entityType === "minecraft:creeper") {
          let remainingSprintMs = sprintDuration;
          let segmentOrigin = playerPosition;
          const blockedDirections: Readonly<{ x: number; z: number }>[] = [];
          while (remainingSprintMs > 0) {
            const segmentDuration = Math.min(CREEPER_ESCAPE_SPRINT_SEGMENT_MS, remainingSprintMs);
            yield* Effect.sleep(segmentDuration);
            remainingSprintMs -= segmentDuration;
            const current = yield* state.driver.observe;
            if (current.player.dead) {
              return true;
            }
            const segmentDistanceSquared = horizontalDistanceSquared(
              segmentOrigin,
              current.player.position,
            );
            if (segmentDistanceSquared >= CREEPER_ESCAPE_MINIMUM_SEGMENT_DISTANCE ** 2) {
              segmentOrigin = current.player.position;
              continue;
            }
            if (remainingSprintMs === 0) {
              break;
            }

            blockedDirections.push(currentEscapeDirection);
            const refreshedSurface = yield* state.driver.sampleSurface(
              current.player.position,
              directSprintDistance,
              1,
            );
            const refreshedThreats = (yield* state.driver.queryEntities({
              origin: current.player.position,
              radius: THREAT_ESCAPE_SAFE_DISTANCE,
              selector: { categories: [2], alive: true },
              maximumResults: 32,
            })).filter((entity) => isCurrentlyHostileMob(entity));
            const refreshedThreat =
              refreshedThreats.find((candidate) => isSameEntityTarget(candidate, threat)) ?? threat;
            const refreshedPreferredTarget = surfaceEscapeTarget(
              current.player.position,
              refreshedThreat.position,
            );
            const refreshedPreferredDirection = {
              x: refreshedPreferredTarget.x - current.player.position.x,
              z: refreshedPreferredTarget.z - current.player.position.z,
            };
            const alternateDirection = selectSafeDirectEscapeDirection(
              refreshedSurface,
              current.player.position,
              refreshedPreferredDirection,
              deduplicateEntityTargets([refreshedThreat, ...refreshedThreats]),
              directSprintDistance,
              0,
              {
                allowSwimmableSurface: true,
                excludedDirections: blockedDirections,
                minimumPreferredAlignment: CREEPER_ESCAPE_MINIMUM_AWAY_ALIGNMENT,
              },
            );
            yield* emit(state, {
              type: "diagnostic",
              message:
                alternateDirection === undefined
                  ? "Emergency creeper sprint stalled with no direct alternate"
                  : "Emergency creeper sprint stalled; changing direction",
              data: {
                blockedDirections: blockedDirections.length,
                position: current.player.position,
                remainingSprintMs,
                segmentDistance: Math.sqrt(segmentDistanceSquared),
                threatNetworkId: threat.networkId,
              },
            });
            if (alternateDirection === undefined) {
              return false;
            }
            currentEscapeDirection = alternateDirection;
            segmentOrigin = current.player.position;
            yield* lookAlongEscapeDirection(current.player.position);
          }
        } else {
          yield* Effect.sleep(sprintDuration);
        }
        return true;
      }).pipe(Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
    );
  });
}

function blockCreeperExplosion(
  state: RunState,
  observation: BeatGameObservation,
  threat: BeatGameEntityObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.withControl(
    Effect.gen(function* () {
      yield* state.driver.act({ type: "reset-movement" });
      if (observation.player.equipment["offhand"] !== "minecraft:shield") {
        yield* state.driver.act({
          type: "equip-item",
          selector: { itemIds: ["minecraft:shield"] },
          equipmentSlot: "offhand",
        });
      }
      const lookAtCreeper = (
        player: BeatGamePosition,
        creeper: BeatGameEntityObservation,
      ): Effect.Effect<unknown, BeatGameDriverError> => {
        const rotation = rotationToward(player, {
          ...creeper.position,
          y: creeper.position.y + 0.85,
        });
        return state.driver.act({
          type: "look",
          yaw: rotation.yaw,
          pitch: rotation.pitch,
        });
      };
      yield* lookAtCreeper(observation.player.position, threat);
      yield* state.driver.act({ type: "use-item", hand: "off" });
      for (let poll = 0; poll < CREEPER_SHIELD_MAXIMUM_POLLS; poll += 1) {
        yield* Effect.sleep(CREEPER_SHIELD_POLL_MS);
        const current = yield* state.driver.observe;
        if (current.player.dead) {
          return;
        }
        const [currentThreat] = yield* state.driver.queryEntities({
          origin: current.player.position,
          radius: CHARGED_CREEPER_BLAST_SAFE_DISTANCE,
          selector: { networkId: threat.networkId, alive: true },
          maximumResults: 1,
        });
        if (currentThreat === undefined || !isFusingCreeper(currentThreat)) {
          return;
        }
        if (poll % 4 === 3) {
          yield* lookAtCreeper(current.player.position, currentThreat);
        }
      }
    }).pipe(
      Effect.ensuring(
        state.driver
          .act({ type: "release-item" })
          .pipe(
            Effect.ignore,
            Effect.andThen(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
          ),
      ),
    ),
  );
}

function performKnockbackStrike(
  state: RunState,
  observation: BeatGameObservation,
  threat: BeatGameEntityObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    if (hasMeleeWeapon(observation)) {
      yield* state.driver.act({
        type: "select-item",
        selector: { itemIds: MELEE_WEAPON_ITEM_IDS },
      });
    }
    const toward = rotationToward(observation.player.position, threat.position);
    yield* state.driver.act({
      type: "look",
      yaw: toward.yaw,
      pitch: toward.pitch,
    });
    yield* state.driver.act({
      type: "attack-entity",
      connectionEpoch: threat.connectionEpoch,
      networkId: threat.networkId,
      sprinting: true,
    });
  });
}

function deduplicateEntityTargets(
  entities: readonly BeatGameEntityObservation[],
): readonly BeatGameEntityObservation[] {
  return [
    ...new Map(
      entities.map((entity) => [`${entity.connectionEpoch}:${entity.networkId}`, entity]),
    ).values(),
  ];
}

function hasNearbyGroupAggroMember(
  target: BeatGameEntityObservation,
  candidates: readonly BeatGameEntityObservation[],
): boolean {
  return (
    GROUP_AGGRO_ENTITY_TYPES.has(target.entityType) &&
    candidates.some(
      (candidate) =>
        !isSameEntityTarget(target, candidate) &&
        candidate.position.dimension === target.position.dimension &&
        sharesGroupAggroFamily(target, candidate),
    )
  );
}

function requiresPersistentGroupEscape(
  target: BeatGameEntityObservation,
  candidates: readonly BeatGameEntityObservation[],
): boolean {
  if (!hasNearbyGroupAggroMember(target, candidates)) {
    return false;
  }
  return (
    target.entityType === "minecraft:piglin_brute" ||
    isAggressiveNeutralMob(target) ||
    candidates.some(
      (candidate) =>
        sharesGroupAggroFamily(target, candidate) &&
        (candidate.entityType === "minecraft:piglin_brute" || isAggressiveNeutralMob(candidate)),
    )
  );
}

function groupAggroFamilyEntityTypes(target: BeatGameEntityObservation): readonly string[] {
  return target.entityType === "minecraft:zombified_piglin"
    ? ["minecraft:zombified_piglin"]
    : ["minecraft:piglin", "minecraft:piglin_brute"];
}

function sharesGroupAggroFamily(
  left: BeatGameEntityObservation,
  right: BeatGameEntityObservation,
): boolean {
  if (
    !GROUP_AGGRO_ENTITY_TYPES.has(left.entityType) ||
    !GROUP_AGGRO_ENTITY_TYPES.has(right.entityType)
  ) {
    return false;
  }
  const leftIsZombified = left.entityType === "minecraft:zombified_piglin";
  const rightIsZombified = right.entityType === "minecraft:zombified_piglin";
  return leftIsZombified === rightIsZombified;
}

export function selectSafeDirectEscapeDirection(
  columns: readonly BeatGameSurfaceColumn[],
  player: BeatGamePosition,
  preferredDirection: Readonly<{ x: number; z: number }>,
  threats: readonly BeatGameEntityObservation[],
  distance: number,
  lateralHalfWidth: number,
  options: {
    readonly allowSwimmableSurface?: boolean;
    readonly excludedDirections?: readonly Readonly<{
      x: number;
      z: number;
    }>[];
    readonly minimumPreferredAlignment?: number;
  } = {},
): Readonly<{ x: number; z: number }> | undefined {
  const preferred = normalizeHorizontalDirection(preferredDirection);
  const allowSwimmableSurface = options.allowSwimmableSurface ?? false;
  const excludedDirections = options.excludedDirections ?? [];
  const minimumPreferredAlignment = options.minimumPreferredAlignment ?? -1;
  const directions = [
    preferred,
    ...Array.from({ length: 32 }, (_, index) => {
      const angle = (index / 32) * Math.PI * 2;
      return { x: Math.cos(angle), z: Math.sin(angle) };
    }),
  ];
  let best:
    | {
        readonly direction: Readonly<{ x: number; z: number }>;
        readonly minimumThreatDistanceSquared: number;
        readonly preferredAlignment: number;
      }
    | undefined;
  for (const direction of directions) {
    const preferredAlignment = direction.x * preferred.x + direction.z * preferred.z;
    if (preferredAlignment < minimumPreferredAlignment) {
      continue;
    }
    if (
      excludedDirections.some((excludedDirection) => {
        const excluded = normalizeHorizontalDirection(excludedDirection);
        return direction.x * excluded.x + direction.z * excluded.z >= Math.SQRT1_2;
      })
    ) {
      continue;
    }
    if (
      !hasSafeDirectEscapeCorridor(
        columns,
        player,
        direction.x,
        direction.z,
        distance,
        lateralHalfWidth,
        allowSwimmableSurface,
      )
    ) {
      continue;
    }
    if (!hasNonRegressingThreatEscapeCorridor(player, direction, threats, distance)) {
      continue;
    }
    const endpoint = {
      ...player,
      x: player.x + direction.x * distance,
      z: player.z + direction.z * distance,
    };
    const minimumThreatDistanceSquared =
      threats.length === 0
        ? Number.POSITIVE_INFINITY
        : Math.min(...threats.map((candidate) => distanceSquared(endpoint, candidate.position)));
    if (
      best === undefined ||
      minimumThreatDistanceSquared > best.minimumThreatDistanceSquared + 0.001 ||
      (Math.abs(minimumThreatDistanceSquared - best.minimumThreatDistanceSquared) <= 0.001 &&
        preferredAlignment > best.preferredAlignment)
    ) {
      best = {
        direction,
        minimumThreatDistanceSquared,
        preferredAlignment,
      };
    }
  }
  return best?.direction;
}

export function hasNonRegressingThreatEscapeCorridor(
  player: BeatGamePosition,
  direction: Readonly<{ x: number; z: number }>,
  threats: readonly BeatGameEntityObservation[],
  distance: number,
): boolean {
  const normalizedDirection = normalizeHorizontalDirection(direction);
  for (const threat of threats) {
    if (threat.position.dimension !== player.dimension) {
      continue;
    }
    const exclusionRadius = directEscapeThreatExclusionRadius(threat);
    const influenceRadius = directEscapeThreatInfluenceRadius(threat);
    let previousDistance = Math.sqrt(horizontalDistanceSquared(player, threat.position));
    let outsideInfluence = previousDistance >= influenceRadius;
    let outsideExclusion = previousDistance >= exclusionRadius;
    for (let offset = 0.5; offset <= distance; offset += 0.5) {
      const candidate = {
        ...player,
        x: player.x + normalizedDirection.x * offset,
        z: player.z + normalizedDirection.z * offset,
      };
      const candidateDistance = Math.sqrt(horizontalDistanceSquared(candidate, threat.position));
      if (
        candidateDistance < exclusionRadius &&
        (outsideExclusion || candidateDistance + 0.1 < previousDistance)
      ) {
        return false;
      }
      if (
        candidateDistance < influenceRadius &&
        (outsideInfluence || candidateDistance + 0.1 < previousDistance)
      ) {
        return false;
      }
      outsideExclusion ||= candidateDistance >= exclusionRadius;
      outsideInfluence ||= candidateDistance >= influenceRadius;
      previousDistance = candidateDistance;
    }
  }
  return true;
}

function directEscapeThreatExclusionRadius(threat: BeatGameEntityObservation): number {
  if (threat.entityType === "minecraft:creeper") {
    return 8;
  }
  if (
    PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(threat.entityType) ||
    threat.entityType === "minecraft:drowned"
  ) {
    return 10;
  }
  if (threat.entityType === "minecraft:enderman") {
    return 6;
  }
  return 4;
}

function directEscapeThreatInfluenceRadius(threat: BeatGameEntityObservation): number {
  return PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(threat.entityType) ||
    threat.entityType === "minecraft:drowned"
    ? RANGED_THREAT_ESCAPE_SAFE_DISTANCE
    : THREAT_ESCAPE_SAFE_DISTANCE;
}

function normalizeHorizontalDirection(
  direction: Readonly<{ x: number; z: number }>,
): Readonly<{ x: number; z: number }> {
  const length = Math.hypot(direction.x, direction.z);
  return length > 0.001 ? { x: direction.x / length, z: direction.z / length } : { x: 1, z: 0 };
}

function findDryThreatEscapeTarget(
  state: RunState,
  player: BeatGamePosition,
  threat: BeatGamePosition,
  maximumVerticalDistance = MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
): Effect.Effect<BeatGamePosition | undefined, BeatGameDriverError> {
  if (player.dimension !== "minecraft:overworld" || threat.dimension !== player.dimension) {
    return Effect.undefined;
  }
  return state.driver.sampleSurface(player, AIR_ESCAPE_SURFACE_SEARCH_RADIUS, 1).pipe(
    Effect.map((columns) =>
      selectStableThreatEscapeColumn(columns, player, threat, maximumVerticalDistance),
    ),
    Effect.map((surface) =>
      surface === undefined
        ? undefined
        : {
            x: surface.x + 0.5,
            y: surface.surfaceY + 1,
            z: surface.z + 0.5,
            dimension: player.dimension,
          },
    ),
  );
}

function hasSafeDirectEscapeCorridor(
  columns: readonly BeatGameSurfaceColumn[],
  player: BeatGamePosition,
  directionX: number,
  directionZ: number,
  distance: number,
  lateralHalfWidth: number,
  allowSwimmableSurface = false,
): boolean {
  const safeColumns = new Map(
    columns.flatMap((column) =>
      column.loaded &&
      column.surfaceY !== undefined &&
      (!isUnsafeSurfaceBlock(column.blockId) ||
        (allowSwimmableSurface && isSwimmableSurfaceBlock(column.blockId)))
        ? [[`${column.x}:${column.z}`, column.surfaceY] as const]
        : [],
    ),
  );
  let previousStandingY = player.y;
  for (let offset = 1; offset <= Math.ceil(distance); offset += 1) {
    const x = Math.floor(player.x + directionX * offset);
    const z = Math.floor(player.z + directionZ * offset);
    const surfaceY = safeColumns.get(`${x}:${z}`);
    if (surfaceY === undefined) {
      return false;
    }
    const standingY = surfaceY + 1;
    if (Math.abs(standingY - previousStandingY) > SURFACE_NEIGHBOR_MAX_HEIGHT_DELTA) {
      return false;
    }
    for (
      let lateralOffset = -lateralHalfWidth;
      lateralOffset <= lateralHalfWidth;
      lateralOffset += 1
    ) {
      const lateralX = Math.floor(player.x + directionX * offset - directionZ * lateralOffset);
      const lateralZ = Math.floor(player.z + directionZ * offset + directionX * lateralOffset);
      const lateralSurfaceY = safeColumns.get(`${lateralX}:${lateralZ}`);
      if (
        lateralSurfaceY === undefined ||
        Math.abs(lateralSurfaceY + 1 - standingY) > SURFACE_NEIGHBOR_MAX_HEIGHT_DELTA
      ) {
        return false;
      }
    }
    previousStandingY = standingY;
  }
  return true;
}

function surfaceEscapeTarget(
  player: BeatGamePosition,
  threat: BeatGamePosition,
  targetY = 80,
): BeatGamePosition {
  const deltaX = player.x - threat.x;
  const deltaZ = player.z - threat.z;
  const horizontalDistance = Math.hypot(deltaX, deltaZ);
  const directionX = horizontalDistance > 0.001 ? deltaX / horizontalDistance : 1;
  const directionZ = horizontalDistance > 0.001 ? deltaZ / horizontalDistance : 0;
  return {
    x: player.x + directionX * 24,
    y: targetY,
    z: player.z + directionZ * 24,
    dimension: player.dimension,
  };
}

function hasUsableFood(observation: BeatGameObservation): boolean {
  return preferredUsableFoodItemIds(observation).length > 0;
}

function deathRecoveryTravelFoodCount(observation: BeatGameObservation): number {
  return [...EDIBLE_FOOD_ITEM_IDS, ...EMERGENCY_FOOD_ITEM_IDS].reduce(
    (total, itemId) => total + (observation.inventory.counts[itemId] ?? 0),
    0,
  );
}

function shouldInterruptForMeal(
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
): boolean {
  if (!hasUsableFood(observation)) {
    return false;
  }
  const hasReadyFood = [...EDIBLE_FOOD_ITEM_IDS, ...EMERGENCY_FOOD_ITEM_IDS].some(
    (itemId) =>
      !COOKABLE_RAW_FOOD_ITEM_IDS.has(itemId) && (observation.inventory.counts[itemId] ?? 0) > 0,
  );
  if (hasReadyFood || observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL) {
    return true;
  }
  // Raw food needs a furnace, and the planner does not cook on the way back
  // to the Overworld or anywhere in the Nether, so interrupting would only
  // restart the action. A live Nether search restarted every half second
  // for half an hour while it carried only raw pork.
  if (
    decision.type === "return-through-portal" ||
    isNether(observation.player.position.dimension)
  ) {
    return false;
  }
  return (
    decision.type !== "satisfy-requirement" ||
    (decision.requirement.key !== "food" &&
      decision.requirement.key !== "food-supply" &&
      decision.requirement.key !== "logs")
  );
}

function hasRecoveryFood(observation: BeatGameObservation): boolean {
  return (
    observation.player.food >= 18 ||
    [...COOKED_FOOD_ITEM_IDS, ...EMERGENCY_FOOD_ITEM_IDS].some(
      (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
    )
  );
}

function preferredUsableFoodItemIds(observation: BeatGameObservation): readonly string[] {
  const readyFood = EDIBLE_FOOD_ITEM_IDS.filter(
    (itemId) =>
      !COOKABLE_RAW_FOOD_ITEM_IDS.has(itemId) && (observation.inventory.counts[itemId] ?? 0) > 0,
  );
  if (readyFood.length > 0) {
    return readyFood;
  }
  const emergencyFood = EMERGENCY_FOOD_ITEM_IDS.filter(
    (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
  );
  if (emergencyFood.length > 0) {
    return emergencyFood;
  }
  return EDIBLE_FOOD_ITEM_IDS.filter(
    (itemId) =>
      COOKABLE_RAW_FOOD_ITEM_IDS.has(itemId) && (observation.inventory.counts[itemId] ?? 0) > 0,
  );
}

function hasUnsafeAir(observation: BeatGameObservation): boolean {
  return (
    observation.player.maxAir > 0 &&
    observation.player.air <= Math.min(MINIMUM_SAFE_AIR_TICKS, observation.player.maxAir)
  );
}

// Swimming at the surface dips the head under now and then. A live run
// crossing open water to hunt lost its search to the air check eight times
// in ten minutes at about 250 air. Work on a requirement waits until about
// ten seconds of air are left; everything else keeps the wider margin.
function hasUnsafeAirDuringAction(
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
): boolean {
  if (decision.type !== "satisfy-requirement") {
    return hasUnsafeAir(observation);
  }
  return (
    observation.player.maxAir > 0 &&
    observation.player.air <=
      Math.min(REQUIREMENT_MINIMUM_SAFE_AIR_TICKS, observation.player.maxAir)
  );
}

function shouldResumeUrgentAquaticFoodHunt(
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
): boolean {
  return (
    (decision.type === "satisfy-requirement" &&
      (decision.requirement.key === "food" || decision.requirement.key === "food-supply") &&
      observation.player.food <= URGENT_HUNGER_FOOD_LEVEL) ||
    isUrgentCorpseRecoveryFoodSearch(decision, observation)
  );
}

function isUrgentCorpseRecoveryFoodSearch(
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
): boolean {
  return (
    decision.type === "recover-death" &&
    observation.player.food <= URGENT_HUNGER_FOOD_LEVEL &&
    deathRecoveryTravelFoodCount(observation) < DEATH_RECOVERY_FOOD_RESERVE_COUNT
  );
}

function waitForUnsafeAir(
  state: RunState,
  minimumAirTicks = MINIMUM_SAFE_AIR_TICKS,
): Effect.Effect<"dead" | "unsafe-air", BeatGameDriverError> {
  const poll = (): Effect.Effect<"dead" | "unsafe-air", BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) =>
        observation.player.dead
          ? Effect.succeed("dead" as const)
          : observation.player.maxAir > 0 &&
              observation.player.air <= Math.min(minimumAirTicks, observation.player.maxAir)
            ? Effect.succeed("unsafe-air" as const)
            : Effect.suspend(poll),
      ),
    );
  return Effect.suspend(poll);
}

function emergencyAirAscent(
  state: RunState,
  position: BeatGamePosition,
  options: {
    readonly attemptsRemaining?: number;
    readonly seekDrySurfaceAfterRecovery?: boolean;
  } = {},
): Effect.Effect<void, BeatGameDriverError> {
  const attemptsRemaining = options.attemptsRemaining ?? AIR_ESCAPE_MAXIMUM_RECOVERY_ATTEMPTS;
  const seekDrySurfaceAfterRecovery = options.seekDrySurfaceAfterRecovery ?? true;
  return state.driver.observe.pipe(
    Effect.flatMap((observation) =>
      observation.player.dead
        ? Effect.void
        : isPlayerInFluid(state.driver, observation.player.position).pipe(
            Effect.flatMap((inFluid) =>
              inFluid
                ? Effect.raceFirst(
                    recoverFromFluid(
                      state,
                      position,
                      attemptsRemaining,
                      observation,
                      seekDrySurfaceAfterRecovery,
                    ).pipe(Effect.as({ type: "recovered" } as const)),
                    monitorAirRecoveryThreat(state).pipe(
                      Effect.map(
                        (threat) =>
                          ({
                            type: "threat",
                            threat,
                          }) as const,
                      ),
                    ),
                  ).pipe(
                    Effect.flatMap((result) =>
                      result.type === "threat"
                        ? respondToAirRecoveryThreat(state, result.threat)
                        : Effect.void,
                    ),
                  )
                : Effect.void,
            ),
          ),
    ),
  );
}

function monitorAirRecoveryThreat(
  state: RunState,
): Effect.Effect<ImmediateThreat, BeatGameDriverError> {
  const poll = (): Effect.Effect<ImmediateThreat, BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) =>
        observation.player.dead
          ? Effect.never
          : hasUnsafeAir(observation)
            ? Effect.suspend(poll)
            : findImmediateThreat(state, observation).pipe(
                Effect.flatMap((threat) => {
                  if (threat !== undefined && isFusingCreeper(threat.target)) {
                    return Effect.succeed(threat);
                  }
                  if (threat === undefined) {
                    return Effect.suspend(poll);
                  }
                  return isPlayerInFluid(state.driver, observation.player.position).pipe(
                    Effect.flatMap((inFluid) =>
                      inFluid
                        ? observation.player.maxAir <= 0 ||
                          observation.player.air >= observation.player.maxAir
                          ? Effect.succeed(threat)
                          : Effect.suspend(poll)
                        : observation.player.onGround
                          ? Effect.succeed(threat)
                          : Effect.suspend(poll),
                    ),
                  );
                }),
              ),
      ),
    );
  return Effect.suspend(poll);
}

function respondToAirRecoveryThreat(
  state: RunState,
  threat: ImmediateThreat,
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.observe.pipe(
    Effect.flatMap((observation) =>
      shouldShieldCreeperExplosion(observation, threat.target)
        ? blockCreeperExplosion(state, observation, threat.target).pipe(
            Effect.andThen(state.driver.observe),
            Effect.flatMap((latest) =>
              latest.player.dead
                ? Effect.void
                : isPlayerInFluid(state.driver, latest.player.position).pipe(
                    Effect.flatMap((inFluid) =>
                      inFluid ? emergencyAirAscent(state, latest.player.position) : Effect.void,
                    ),
                  ),
            ),
          )
        : threat.response === "flee"
          ? escapeFromTarget(state, threat.target)
          : defendAgainstTarget(state, threat.target).pipe(
              Effect.catchTag("BeatGameDriverError", (error) =>
                error.operation === "task.attack-entity" ||
                error.operation === "task.attack-nearest" ||
                error.code === "not_found" ||
                error.code === "unreachable"
                  ? recoverFromFailedDefense(state, threat.target)
                  : Effect.fail(error),
              ),
            ),
    ),
  );
}

function recoverFromFailedDefense(
  state: RunState,
  target: BeatGameEntityObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.observe.pipe(
    Effect.flatMap((observation) => {
      if (PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType)) {
        if (observation.player.health < state.strategy.minimumHealth) {
          return escapeFromTarget(state, target, {
            continueEscapingWhenHit: true,
          });
        }
        return needsOverworldSurfaceRecovery(state, observation.player.position).pipe(
          Effect.flatMap((needsRecovery) =>
            needsRecovery
              ? recoverLocalNavigationTrap(state, observation.player.position).pipe(
                  Effect.flatMap((recovered) =>
                    recovered ? Effect.void : escapeFromTarget(state, target),
                  ),
                )
              : escapeFromTarget(state, target),
          ),
        );
      }
      if (shouldCommitToMeleeFight(observation, target)) {
        return knockBackAndSprintAway(state, observation, target);
      }
      return escapeFromTarget(state, target);
    }),
  );
}

function recoverFromFluid(
  state: RunState,
  originalPosition: BeatGamePosition,
  attemptsRemaining: number,
  observation: BeatGameObservation,
  seekDrySurfaceAfterRecovery: boolean,
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver
    .withControl(
      state.driver
        .act({
          type: "look",
          yaw: observation.player.rotation.yaw,
          pitch: -90,
        })
        .pipe(
          Effect.andThen(
            state.driver.act({
              type: "set-movement",
              jump: true,
            }),
          ),
          Effect.andThen(
            waitForAirRecovery(state, 60, observation.player.position.y, observation.player.air),
          ),
          Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
        ),
    )
    .pipe(
      Effect.flatMap((recovered) =>
        recovered && !seekDrySurfaceAfterRecovery
          ? Effect.succeed(true)
          : (recovered
              ? Effect.succeed(false)
              : state.driver.observe.pipe(
                  Effect.flatMap((latest) =>
                    hasAirForBreathingPocketPath(latest)
                      ? escapeNearbyBreathingPocket(state, latest)
                      : Effect.succeed(false),
                  ),
                )
            ).pipe(
              Effect.flatMap((reachedBreathingPocket) =>
                swimToNearbyDrySurface(state, true).pipe(
                  Effect.map((reachedDrySurface) => reachedDrySurface || reachedBreathingPocket),
                ),
              ),
            ),
      ),
      Effect.flatMap((reachedDrySurface) =>
        reachedDrySurface
          ? Effect.void
          : state.driver.observe.pipe(
              Effect.flatMap((latest) =>
                hasUnsafeAir(latest)
                  ? escapeNearbyOpenAirColumn(state, latest).pipe(
                      Effect.flatMap((escaped) =>
                        escaped ? Effect.void : excavateAirEscapeShaft(state),
                      ),
                    )
                  : hasBreathableHeadSpace(state.driver, latest.player.position).pipe(
                      Effect.flatMap((breathingAtSurface) =>
                        breathingAtSurface
                          ? Effect.void
                          : escapeToOverworldSurface(state, originalPosition),
                      ),
                    ),
              ),
              Effect.result,
              Effect.flatMap(() => state.driver.observe),
              Effect.flatMap((latest) =>
                latest.player.dead
                  ? Effect.void
                  : isPlayerInFluid(state.driver, latest.player.position).pipe(
                      Effect.flatMap((inFluid) =>
                        !inFluid
                          ? Effect.void
                          : attemptsRemaining > 1
                            ? emergencyAirAscent(state, latest.player.position, {
                                attemptsRemaining: attemptsRemaining - 1,
                                seekDrySurfaceAfterRecovery,
                              })
                            : Effect.fail(
                                new BeatGameDriverError({
                                  operation: "recover-air",
                                  code: "unreachable",
                                  retryable: true,
                                  message:
                                    "The bot could not reach a stable dry surface before exhausting its air-recovery attempts",
                                }),
                              ),
                      ),
                    ),
              ),
            ),
      ),
    );
}

function escapeNearbyBreathingPocket(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  if (
    observation.player.dead ||
    observation.player.maxAir <= 0 ||
    observation.player.air >= observation.player.maxAir
  ) {
    return Effect.succeed(
      !observation.player.dead && observation.player.air >= observation.player.maxAir,
    );
  }
  const origin = observation.player.position;
  return state.driver
    .queryBlocks({
      center: origin,
      radius: AIR_ESCAPE_BREATHING_POCKET_SEARCH_RADIUS,
      selector: {
        blockIds: ["minecraft:air", "minecraft:cave_air", "minecraft:void_air"],
      },
      maximumResults: 128,
    })
    .pipe(
      Effect.map((blocks) =>
        blocks
          .map((block) => ({
            target: {
              x: block.position.x + 0.5,
              y: block.position.y - 1,
              z: block.position.z + 0.5,
              dimension: block.position.dimension,
            } satisfies BeatGamePosition,
          }))
          .filter(
            ({ target }) =>
              target.dimension === origin.dimension &&
              target.y <= origin.y + AIR_ESCAPE_MAXIMUM_SWIMMABLE_RISE,
          )
          .toSorted(
            (left, right) =>
              distanceSquared(left.target, origin) - distanceSquared(right.target, origin),
          )
          .slice(0, AIR_ESCAPE_BREATHING_POCKET_CANDIDATES),
      ),
      Effect.flatMap((candidates) => tryBreathingPocketCandidates(state, candidates, 0)),
    );
}

function tryBreathingPocketCandidates(
  state: RunState,
  candidates: readonly Readonly<{
    target: BeatGamePosition;
  }>[],
  index: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  const candidate = candidates[index];
  if (candidate === undefined) {
    return Effect.succeed(false);
  }
  return state.driver.observe.pipe(
    Effect.flatMap((observation) => {
      if (observation.player.dead || !hasAirForBreathingPocketPath(observation)) {
        return Effect.succeed(false);
      }
      const feet = {
        x: Math.floor(candidate.target.x),
        y: Math.floor(candidate.target.y),
        z: Math.floor(candidate.target.z),
        dimension: candidate.target.dimension,
      } satisfies BeatGameBlockPosition;
      return queryExactBlock(state.driver, feet).pipe(
        Effect.flatMap((feetBlock) =>
          feetBlock !== undefined && !feetBlock.replaceable
            ? Effect.succeed(false)
            : state.driver
                .pathfind(candidate.target, 0.75, {
                  ...state.strategy.path,
                  allowMining: false,
                  allowPlacing: false,
                  avoidFluids: false,
                  maxSearchTimeMs: Math.min(
                    state.strategy.path.maxSearchTimeMs,
                    AIR_ESCAPE_BREATHING_POCKET_PATH_TIMEOUT_MS,
                  ),
                })
                .pipe(
                  Effect.timeoutOrElse({
                    duration: AIR_ESCAPE_BREATHING_POCKET_PATH_TIMEOUT_MS,
                    orElse: () =>
                      Effect.fail(
                        new BeatGameDriverError({
                          operation: "pathfind",
                          code: "unreachable",
                          retryable: true,
                          message: `Timed out reaching breathing pocket at ${positionKey(
                            candidate.target,
                          )}`,
                        }),
                      ),
                  }),
                  Effect.andThen(state.driver.observe),
                  Effect.flatMap((current) =>
                    hasBreathableHeadSpace(state.driver, current.player.position).pipe(
                      Effect.flatMap((breathable) =>
                        breathable ? waitForBreathingPocketRecovery(state) : Effect.succeed(false),
                      ),
                    ),
                  ),
                  Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
                ),
        ),
      );
    }),
    Effect.flatMap((reached) =>
      reached ? Effect.succeed(true) : tryBreathingPocketCandidates(state, candidates, index + 1),
    ),
  );
}

function waitForBreathingPocketRecovery(
  state: RunState,
): Effect.Effect<boolean, BeatGameDriverError> {
  const wait = (
    previousAir: number,
    attemptsRemaining: number,
    stagnantObservations = 0,
  ): Effect.Effect<boolean, BeatGameDriverError> =>
    Effect.sleep(100).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) => {
        if (observation.player.dead) {
          return Effect.succeed(false);
        }
        if (observation.player.maxAir <= 0 || observation.player.air >= observation.player.maxAir) {
          return Effect.succeed(true);
        }
        const recovering = observation.player.air > previousAir;
        const nextStagnantObservations = recovering ? 0 : stagnantObservations + 1;
        return attemptsRemaining <= 1 ||
          nextStagnantObservations >= AIR_ESCAPE_ASCENT_STAGNATION_OBSERVATIONS
          ? Effect.succeed(false)
          : wait(observation.player.air, attemptsRemaining - 1, nextStagnantObservations);
      }),
    );
  return state.driver.observe.pipe(
    Effect.flatMap((observation) =>
      state.driver.withControl(
        state.driver
          .act({
            type: "set-movement",
            jump: true,
          })
          .pipe(
            Effect.andThen(wait(observation.player.air, AIR_ESCAPE_SURFACE_APPROACH_ATTEMPTS)),
            Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
          ),
      ),
    ),
  );
}

function hasBreathableHeadSpace(
  driver: BeatGameDriver,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  return queryExactBlock(driver, {
    x: Math.floor(position.x),
    y: Math.floor(position.y + 1.62),
    z: Math.floor(position.z),
    dimension: position.dimension,
  }).pipe(Effect.map((block) => block !== undefined && isAirBlock(block.blockId)));
}

function waitForAirRecovery(
  state: RunState,
  attemptsRemaining: number,
  highestY: number,
  highestAir: number,
  stagnantObservations = 0,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.sleep(100).pipe(
    Effect.andThen(state.driver.observe),
    Effect.flatMap((observation) => {
      if (
        observation.player.dead ||
        observation.player.maxAir <= 0 ||
        observation.player.air >= observation.player.maxAir
      ) {
        return Effect.succeed(true);
      }
      const nextY = observation.player.position.y;
      const madeProgress =
        nextY > highestY + AIR_ESCAPE_ASCENT_PROGRESS_EPSILON ||
        observation.player.air > highestAir;
      const nextStagnantObservations = madeProgress ? 0 : stagnantObservations + 1;
      if (
        attemptsRemaining <= 1 ||
        nextStagnantObservations >= AIR_ESCAPE_ASCENT_STAGNATION_OBSERVATIONS
      ) {
        return Effect.succeed(false);
      }
      return waitForAirRecovery(
        state,
        attemptsRemaining - 1,
        Math.max(highestY, nextY),
        Math.max(highestAir, observation.player.air),
        nextStagnantObservations,
      );
    }),
  );
}

function swimToNearbyDrySurface(
  state: RunState,
  swimDirectly = false,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    if (observation.player.dead) {
      return true;
    }
    const surfaces = yield* findDrySurfaceEscapeColumns(state, observation.player.position);
    if (surfaces.length === 0) {
      return false;
    }
    const directlyReachableSurfaces = new Set(
      surfaces.filter(
        (surface) =>
          surface.surfaceY - observation.player.position.y <= AIR_ESCAPE_MAXIMUM_SWIMMABLE_RISE,
      ),
    );
    const usableSurfaces: Readonly<{ x: number; z: number; surfaceY: number }>[] = [];
    for (const surface of surfaces) {
      const support = yield* queryExactBlock(state.driver, {
        x: surface.x,
        y: surface.surfaceY,
        z: surface.z,
        dimension: observation.player.position.dimension,
      });
      if (support !== undefined && (support.replaceable || isUnsafeSurfaceBlock(support.blockId))) {
        continue;
      }
      usableSurfaces.push(surface);
    }
    const directlyUsableSurfaces = usableSurfaces.filter((surface) =>
      directlyReachableSurfaces.has(surface),
    );
    if (swimDirectly) {
      const reachedDirectly = yield* swimDirectlyToDrySurfaceCandidates(
        state,
        directlyUsableSurfaces,
      );
      if (reachedDirectly) {
        return true;
      }
    }
    if (!swimDirectly) {
      const firstDirectSurface = directlyUsableSurfaces[0];
      if (
        firstDirectSurface !== undefined &&
        (yield* pathfindTowardDrySurface(state, firstDirectSurface))
      ) {
        return true;
      }
      const afterApproach = yield* state.driver.observe;
      if (afterApproach.player.dead) {
        return true;
      }
      if (hasUnsafeAir(afterApproach)) {
        return false;
      }
      const reachedDirectly = yield* swimDirectlyToDrySurfaceCandidates(
        state,
        directlyUsableSurfaces,
      );
      if (reachedDirectly) {
        return true;
      }
    }
    const plannedSurfaces = swimDirectly
      ? usableSurfaces
      : usableSurfaces.filter((surface) => !directlyUsableSurfaces.includes(surface));
    for (const surface of plannedSurfaces.slice(0, SURFACE_RECOVERY_DIRECT_PATH_CANDIDATES)) {
      if (yield* pathfindTowardDrySurface(state, surface)) {
        return true;
      }
      const current = yield* state.driver.observe;
      if (current.player.dead) {
        return true;
      }
      if (hasUnsafeAir(current)) {
        return false;
      }
    }
    return false;
  });
}

function pathfindTowardDrySurface(
  state: RunState,
  surface: Readonly<{ x: number; z: number; surfaceY: number }>,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    if (observation.player.dead) {
      return true;
    }
    if (
      !hasAirForBreathingPocketPath(observation) ||
      !(yield* hasBreathableHeadSpace(state.driver, observation.player.position))
    ) {
      return false;
    }
    const target = {
      x: surface.x + 0.5,
      y: surface.surfaceY + 1,
      z: surface.z + 0.5,
      dimension: observation.player.position.dimension,
    };
    yield* Effect.raceFirst(
      state.driver
        .pathfind(target, DRY_SURFACE_APPROACH_RADIUS, {
          ...survivalPathPolicy(
            state.strategy.path,
            observation.player.health,
            state.strategy.minimumHealth,
          ),
          allowMining: false,
          allowPlacing: false,
          avoidFluids: false,
          sprint: observation.player.food > CRITICAL_HUNGER_FOOD_LEVEL,
          maxSearchTimeMs: Math.min(
            state.strategy.path.maxSearchTimeMs,
            SHORE_PATH_MAX_SEARCH_TIME_MS,
          ),
        })
        .pipe(
          Effect.timeoutOrElse({
            duration: SHORE_PATH_TIMEOUT_MS,
            orElse: () =>
              Effect.fail(
                new BeatGameDriverError({
                  operation: "pathfind",
                  code: "unreachable",
                  retryable: true,
                  message: `Timed out pathfinding toward dry surface at ${positionKey(target)}`,
                }),
              ),
          }),
          Effect.catchTag("BeatGameDriverError", () => Effect.void),
        ),
      // The swim starts with enough air for its path, and the route search
      // alone can take seconds underwater. Stopping at the usual safety margin
      // cancelled every swim in a live run before the bot moved.
      waitForUnsafeAir(state, AIR_ESCAPE_CRITICAL_AIR_TICKS).pipe(Effect.asVoid),
    );
    const current = yield* state.driver.observe;
    if (current.player.dead) {
      return true;
    }
    return (
      !(yield* isPlayerInFluid(state.driver, current.player.position)) && current.player.onGround
    );
  });
}

function swimDirectlyToDrySurfaceCandidates(
  state: RunState,
  candidates: readonly Readonly<{
    x: number;
    z: number;
    surfaceY: number;
  }>[],
  maximumApproachAttempts = AIR_ESCAPE_SURFACE_APPROACH_ATTEMPTS,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (const surface of candidates) {
      const reached = yield* swimTowardDrySurface(state, surface, maximumApproachAttempts);
      if (reached) {
        return true;
      }
      const latest = yield* state.driver.observe;
      if (!(yield* isPlayerInFluid(state.driver, latest.player.position))) {
        return latest.player.onGround;
      }
      if (hasCriticalAir(latest)) {
        return false;
      }
    }
    return false;
  });
}

function findDrySurfaceEscapeColumns(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<
  readonly { readonly x: number; readonly z: number; readonly surfaceY: number }[],
  BeatGameDriverError
> {
  return findNearbyDryEscapeLedges(state, position).pipe(
    Effect.filterOrElse(
      (nearbyLedges) => nearbyLedges.length > 0,
      () =>
        state.driver.sampleSurface(position, AIR_ESCAPE_SURFACE_SEARCH_RADIUS, 1).pipe(
          Effect.map((columns) => selectStableSurfaceEscapeColumns(columns, position)),
          Effect.flatMap((columns) => withoutFloatingSpans(state.driver, position, columns)),
          Effect.filterOrElse(
            (nearbySurfaces) => nearbySurfaces.length > 0,
            () =>
              state.driver
                .sampleSurface(
                  position,
                  AIR_ESCAPE_EXTENDED_SURFACE_SEARCH_RADIUS,
                  AIR_ESCAPE_EXTENDED_SURFACE_SAMPLE_STEP,
                )
                .pipe(Effect.map((columns) => selectSurfaceEscapeColumns(columns, position))),
          ),
        ),
    ),
  );
}

// A bridge or overhang has nothing solid under its top block, so a swimmer
// cannot climb onto it. A live bot fell off its own bridge into the ocean
// and kept swimming back to it while real shore lay 30 blocks away.
function withoutFloatingSpans(
  driver: BeatGameDriver,
  position: BeatGamePosition,
  columns: readonly { readonly x: number; readonly z: number; readonly surfaceY: number }[],
): Effect.Effect<
  readonly { readonly x: number; readonly z: number; readonly surfaceY: number }[],
  BeatGameDriverError
> {
  return Effect.filter(columns, (column) =>
    queryExactBlock(driver, {
      x: column.x,
      y: column.surfaceY - 1,
      z: column.z,
      dimension: position.dimension,
    }).pipe(Effect.map((support) => support === undefined || !support.replaceable)),
  );
}

function findNearbyDryEscapeLedges(
  state: RunState,
  position: BeatGamePosition,
  options: {
    readonly radius?: number;
    readonly maximumResults?: number;
    readonly maximumCandidates?: number;
    readonly maximumDrop?: number;
    readonly maximumRise?: number;
  } = {},
): Effect.Effect<
  readonly { readonly x: number; readonly z: number; readonly surfaceY: number }[],
  BeatGameDriverError
> {
  const radius = options.radius ?? AIR_ESCAPE_LOCAL_LEDGE_SEARCH_RADIUS;
  const maximumResults = options.maximumResults ?? AIR_ESCAPE_LOCAL_LEDGE_QUERY_RESULTS;
  const maximumCandidates = options.maximumCandidates ?? AIR_ESCAPE_LOCAL_LEDGE_CANDIDATES;
  const maximumDrop = options.maximumDrop ?? MAXIMUM_DAMAGE_FREE_FALL_DISTANCE;
  const maximumRise = options.maximumRise ?? AIR_ESCAPE_MAXIMUM_SWIMMABLE_RISE;
  return state.driver
    .queryBlocks({
      center: position,
      radius,
      selector: { solid: true },
      maximumResults,
    })
    .pipe(
      Effect.map((supports) =>
        [
          ...new Map(
            supports.flatMap((support) => {
              const targetY = support.position.y + 1;
              return isStableDryShaftSupport(support) &&
                !isUnsafeSurfaceBlock(support.blockId) &&
                targetY >= position.y - maximumDrop &&
                targetY - position.y <= maximumRise
                ? [[positionKey(support.position), support] as const]
                : [];
            }),
          ).values(),
        ]
          .toSorted(
            (left, right) =>
              distanceSquared(blockCenter({ ...left.position, y: left.position.y + 1 }), position) -
              distanceSquared(
                blockCenter({ ...right.position, y: right.position.y + 1 }),
                position,
              ),
          )
          .slice(0, maximumCandidates),
      ),
      Effect.flatMap((supports) =>
        Effect.forEach(
          supports,
          (support) => {
            const feet = { ...support.position, y: support.position.y + 1 };
            const head = { ...feet, y: feet.y + 1 };
            return Effect.all(
              [queryExactBlock(state.driver, feet), queryExactBlock(state.driver, head)],
              { concurrency: 2 },
            ).pipe(
              Effect.map(([feetBlock, headBlock]) =>
                isDryWalkingSpace(feetBlock) && isDryWalkingSpace(headBlock)
                  ? {
                      x: support.position.x,
                      z: support.position.z,
                      surfaceY: support.position.y,
                    }
                  : undefined,
              ),
            );
          },
          { concurrency: 8 },
        ),
      ),
      Effect.map((candidates) =>
        selectDirectionalSurfaceColumns(
          candidates.filter((candidate) => candidate !== undefined),
          position,
          AIR_ESCAPE_DIRECTION_SECTORS,
        ),
      ),
    );
}

function escapeToOverworldSurface(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let currentPosition = position;
    let lastPathFailure: BeatGameDriverError | undefined;
    // A climb that gains height is working. A live run climbed fifty blocks
    // from a deep portal in three attempts and was cut off at y -3, so only
    // attempts that fail to rise count against the limit.
    let stalledAttempts = 0;
    for (
      let attempt = 0;
      stalledAttempts < AIR_ESCAPE_MAXIMUM_RECOVERY_ATTEMPTS &&
      attempt < SURFACE_ESCAPE_MAXIMUM_TOTAL_ATTEMPTS;
      attempt += 1
    ) {
      const startY = currentPosition.y;
      if (!(yield* needsOverworldSurfaceRecovery(state, currentPosition))) {
        return undefined;
      }
      const recovery = yield* returnToOverworldSurface(state, currentPosition).pipe(Effect.result);
      if (recovery._tag === "Failure") {
        if (recovery.failure.operation !== "pathfind") {
          return yield* recovery.failure;
        }
        lastPathFailure = recovery.failure;
        const observation = yield* state.driver.observe;
        if (observation.player.dead) {
          return undefined;
        }
        if (yield* isPlayerInFluid(state.driver, observation.player.position)) {
          yield* excavateAirEscapeShaft(state);
        }
      }
      const current = yield* state.driver.observe;
      if (current.player.dead) {
        return undefined;
      }
      currentPosition = current.player.position;
      stalledAttempts =
        currentPosition.y >= startY + SURFACE_ESCAPE_PROGRESS_HEIGHT ? 0 : stalledAttempts + 1;
    }
    if (!(yield* needsOverworldSurfaceRecovery(state, currentPosition))) {
      return undefined;
    }
    return yield* new BeatGameDriverError({
      operation: "pathfind",
      code: "no-progress",
      retryable: true,
      message: `The bot remained below the Overworld surface at ${positionKey(
        currentPosition,
      )} after ${AIR_ESCAPE_MAXIMUM_RECOVERY_ATTEMPTS} recovery attempts without rising`,
      ...(lastPathFailure === undefined ? {} : { cause: lastPathFailure }),
    });
  });
}

function excavateAirEscapeShaft(state: RunState): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (
      let clearedBlocks = 0;
      clearedBlocks < AIR_ESCAPE_MAXIMUM_SHAFT_BLOCKS;
      clearedBlocks += 1
    ) {
      const observation = yield* state.driver.observe;
      if (observation.player.dead) {
        return undefined;
      }
      const surface = yield* nearestAirEscapeSurface(state, observation.player.position);
      if (surface !== undefined && observation.player.position.y >= surface.surfaceY + 0.75) {
        return undefined;
      }
      const overheadCandidates = overheadEscapeBlocks(observation.player.position);
      let overhead = overheadCandidates[1];
      let obstruction: BeatGameBlockObservation | undefined;
      for (const candidate of overheadCandidates) {
        const block = yield* queryExactBlock(state.driver, candidate);
        if (block !== undefined && !block.replaceable) {
          overhead = candidate;
          obstruction = block;
          break;
        }
      }
      if (obstruction !== undefined && !obstruction.replaceable) {
        if (!obstruction.diggable) {
          return yield* new BeatGameDriverError({
            operation: "escape-submerged-cavity",
            code: "unreachable",
            retryable: true,
            message: `The overhead escape route is blocked by ${
              obstruction.blockId
            } at ${positionKey(overhead)}`,
          });
        }
        const cleared = yield* digAirEscapeObstruction(state, observation, obstruction, overhead);
        if (!cleared) {
          return yield* new BeatGameDriverError({
            operation: "escape-submerged-cavity",
            code: "unreachable",
            retryable: true,
            message: `Breaking ${obstruction.blockId} at ${positionKey(
              overhead,
            )} would consume the emergency air reserve`,
          });
        }
      }
      // A swimmer cannot rise above the water line. A live bot floated
      // under open sky beside its own bridge, whose top set the surface
      // height, and failed this climb for minutes. With nothing overhead and
      // air at its head, it has already surfaced.
      if (
        obstruction === undefined &&
        (yield* hasBreathableHeadSpace(state.driver, observation.player.position))
      ) {
        return undefined;
      }
      const rose = yield* swimUpOneLevel(state, observation.player.position.y);
      if (!rose) {
        return yield* new BeatGameDriverError({
          operation: "escape-submerged-cavity",
          code: "unreachable",
          retryable: true,
          message: `The bot could not rise through the cleared block at ${positionKey(overhead)}`,
        });
      }
      const current = yield* state.driver.observe;
      if (current.player.dead) {
        return undefined;
      }
      if (!(yield* isPlayerInFluid(state.driver, current.player.position))) {
        return undefined;
      }
    }
    return yield* new BeatGameDriverError({
      operation: "escape-submerged-cavity",
      code: "unreachable",
      retryable: true,
      message: `The bot did not reach the surface after clearing ${AIR_ESCAPE_MAXIMUM_SHAFT_BLOCKS} overhead blocks`,
    });
  });
}

function digAirEscapeObstruction(
  state: RunState,
  observation: BeatGameObservation,
  block: BeatGameBlockObservation,
  obstruction: BeatGameBlockPosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const toolItemIds = miningToolItemIdsFor(block);
    const selectedTool = toolItemIds.find(
      (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
    );
    if (selectedTool !== undefined) {
      yield* state.driver.act({
        type: "select-item",
        selector: { itemIds: toolItemIds },
      });
    }
    const settled = yield* settleForSubmergedDig(state);
    const inFluid = yield* isPlayerInFluid(state.driver, settled.player.position);
    // Vanilla slows digging, and the bot spends air, only while its eyes are
    // under water. A live bot stood in a flooded tunnel with its head in air
    // and refused every dig as if it were submerged.
    const eyesSubmerged = !(yield* hasBreathableHeadSpace(state.driver, settled.player.position));
    const estimatedTicks = estimatedBlockBreakTicks(
      block,
      selectedTool,
      eyesSubmerged,
      settled.player.onGround,
    );
    const usableAirTicks =
      settled.player.maxAir <= 0 || !eyesSubmerged
        ? Number.POSITIVE_INFINITY
        : settled.player.air - AIR_ESCAPE_DIG_AIR_RESERVE_TICKS;
    if (!Number.isFinite(estimatedTicks) || estimatedTicks > usableAirTicks) {
      return false;
    }
    const timeoutMs = Math.max(250, (estimatedTicks + AIR_ESCAPE_DIG_TIMEOUT_BUFFER_TICKS) * 50);
    const dig = state.driver
      .act({
        type: "dig-block",
        position: obstruction,
      })
      .pipe(
        Effect.timeoutOrElse({
          duration: timeoutMs,
          orElse: () =>
            Effect.fail(
              new BeatGameDriverError({
                operation: "act.dig-block",
                code: "deadline_exceeded",
                retryable: true,
                message: `Timed out clearing ${block.blockId} at ${positionKey(
                  obstruction,
                )} within the available air budget`,
              }),
            ),
        }),
      );
    if (!inFluid) {
      yield* dig;
      return true;
    }
    yield* state.driver.withControl(
      state.driver
        .act({
          type: "set-movement",
          sneak: true,
        })
        .pipe(
          Effect.andThen(dig),
          Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
        ),
    );
    return true;
  });
}

function settleForSubmergedDig(
  state: RunState,
): Effect.Effect<BeatGameObservation, BeatGameDriverError> {
  const wait = (
    attemptsRemaining: number,
  ): Effect.Effect<BeatGameObservation, BeatGameDriverError> =>
    Effect.sleep(100).pipe(
      Effect.andThen(state.driver.observe),
      Effect.filterOrElse(
        (observation) =>
          observation.player.dead ||
          observation.player.onGround ||
          attemptsRemaining <= 1 ||
          hasCriticalAir(observation),
        () => wait(attemptsRemaining - 1),
      ),
    );
  return state.driver.observe.pipe(
    Effect.filterOrElse(
      (observation) => observation.player.dead || observation.player.onGround,
      () =>
        state.driver.withControl(
          state.driver
            .act({
              type: "set-movement",
              sneak: true,
            })
            .pipe(
              Effect.andThen(wait(AIR_ESCAPE_DIG_SETTLE_ATTEMPTS)),
              Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
            ),
        ),
    ),
  );
}

function miningToolItemIdsFor(block: BeatGameBlockObservation): readonly string[] {
  const toolTags = block.effectiveToolTags ?? [];
  if (toolTags.includes("minecraft:mineable/axe")) {
    return MINING_AXE_ITEM_IDS;
  }
  if (toolTags.includes("minecraft:mineable/shovel")) {
    return MINING_SHOVEL_ITEM_IDS;
  }
  if (toolTags.includes("minecraft:mineable/hoe")) {
    return MINING_HOE_ITEM_IDS;
  }
  return MINING_PICKAXE_ITEM_IDS;
}

function estimatedBlockBreakTicks(
  block: BeatGameBlockObservation,
  toolItemId: string | undefined,
  submerged: boolean,
  onGround: boolean,
): number {
  const hardness = block.hardness ?? 1.5;
  if (!block.diggable || hardness < 0) {
    return Number.POSITIVE_INFINITY;
  }
  if (hardness === 0) {
    return 1;
  }
  const effectiveTool = selectedToolMatchesBlock(block, toolItemId);
  const speed = effectiveTool ? miningToolSpeed(toolItemId) : 1;
  const correctTool = effectiveTool && selectedToolMeetsRequiredTier(block, toolItemId);
  const baseTicks = (hardness * (correctTool ? 30 : 100)) / speed;
  const environmentMultiplier = (submerged ? 5 : 1) * (onGround ? 1 : 5);
  return Math.max(1, Math.ceil(baseTicks * environmentMultiplier * 1.25) + 5);
}

function selectedToolMatchesBlock(
  block: BeatGameBlockObservation,
  toolItemId: string | undefined,
): boolean {
  if (toolItemId === undefined) {
    return false;
  }
  return (block.effectiveToolTags ?? []).some((tag) =>
    tag === "minecraft:mineable/pickaxe"
      ? toolItemId.endsWith("_pickaxe")
      : tag === "minecraft:mineable/axe"
        ? toolItemId.endsWith("_axe")
        : tag === "minecraft:mineable/shovel"
          ? toolItemId.endsWith("_shovel")
          : tag === "minecraft:mineable/hoe"
            ? toolItemId.endsWith("_hoe")
            : false,
  );
}

function selectedToolMeetsRequiredTier(
  block: BeatGameBlockObservation,
  toolItemId: string | undefined,
): boolean {
  const toolTier = miningToolTier(toolItemId);
  const toolTags = block.effectiveToolTags ?? [];
  const requiredTier = toolTags.includes("minecraft:needs_diamond_tool")
    ? 3
    : toolTags.includes("minecraft:needs_iron_tool")
      ? 2
      : toolTags.includes("minecraft:needs_stone_tool")
        ? 1
        : 0;
  return toolTier >= requiredTier;
}

function miningToolTier(toolItemId: string | undefined): number {
  if (toolItemId?.startsWith("minecraft:netherite_") === true) {
    return 4;
  }
  if (toolItemId?.startsWith("minecraft:diamond_") === true) {
    return 3;
  }
  if (toolItemId?.startsWith("minecraft:iron_") === true) {
    return 2;
  }
  if (toolItemId?.startsWith("minecraft:stone_") === true) {
    return 1;
  }
  return 0;
}

function miningToolSpeed(toolItemId: string | undefined): number {
  if (toolItemId?.startsWith("minecraft:golden_") === true) {
    return 12;
  }
  if (toolItemId?.startsWith("minecraft:netherite_") === true) {
    return 9;
  }
  if (toolItemId?.startsWith("minecraft:diamond_") === true) {
    return 8;
  }
  if (toolItemId?.startsWith("minecraft:iron_") === true) {
    return 6;
  }
  if (toolItemId?.startsWith("minecraft:stone_") === true) {
    return 4;
  }
  return 2;
}

function nearestAirEscapeSurface(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<
  Readonly<{ x: number; z: number; surfaceY: number }> | undefined,
  BeatGameDriverError
> {
  return state.driver
    .sampleSurface(position, AIR_ESCAPE_SURFACE_SEARCH_RADIUS, 1)
    .pipe(Effect.map((columns) => selectSurfaceColumn(columns, position)));
}

function escapeNearbyOpenAirColumn(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const origin = observation.player.position;
    const blockX = Math.floor(origin.x);
    const blockY = Math.floor(origin.y);
    const blockZ = Math.floor(origin.z);
    const directions = [
      { x: 1, z: 0 },
      { x: -1, z: 0 },
      { x: 0, z: 1 },
      { x: 0, z: -1 },
    ] as const;
    for (let distance = 1; distance <= AIR_ESCAPE_OPEN_COLUMN_SEARCH_RADIUS; distance += 1) {
      for (const direction of directions) {
        let corridorOpen = true;
        for (let step = 1; step <= distance; step += 1) {
          for (const y of [blockY, blockY + 1]) {
            const block = yield* queryExactBlock(state.driver, {
              x: blockX + direction.x * step,
              y,
              z: blockZ + direction.z * step,
              dimension: origin.dimension,
            });
            if (block !== undefined && !block.replaceable) {
              corridorOpen = false;
              break;
            }
          }
          if (!corridorOpen) {
            break;
          }
        }
        if (!corridorOpen) {
          continue;
        }
        const openAir = yield* queryExactBlock(state.driver, {
          x: blockX + direction.x * distance,
          y: blockY + 2,
          z: blockZ + direction.z * distance,
          dimension: origin.dimension,
        });
        if (openAir === undefined || !isAirBlock(openAir.blockId)) {
          continue;
        }
        return yield* swimTowardOpenAirColumn(state, observation, {
          x: openAir.position.x + 0.5,
          y: origin.y,
          z: openAir.position.z + 0.5,
          dimension: origin.dimension,
        });
      }
    }
    return false;
  });
}

function swimTowardOpenAirColumn(
  state: RunState,
  observation: BeatGameObservation,
  target: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  const rotation = rotationToward(observation.player.position, target);
  return state.driver.withControl(
    state.driver
      .act({
        type: "look",
        yaw: rotation.yaw,
        pitch: -20,
      })
      .pipe(
        Effect.andThen(
          state.driver.act({
            type: "set-movement",
            forward: true,
            jump: true,
            // A sprinting swimmer stays in the swimming pose with its eyes under
            // water, and a live bot drowned sprinting toward open air under a ledge.
            sprint: false,
          }),
        ),
        Effect.andThen(waitForOpenAirColumn(state, target, 30)),
        Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
      ),
  );
}

function waitForOpenAirColumn(
  state: RunState,
  target: BeatGamePosition,
  attemptsRemaining: number,
  previousDistanceSquared?: number,
  stagnantObservations = 0,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.sleep(100).pipe(
    Effect.andThen(state.driver.observe),
    Effect.flatMap((observation) => {
      if (observation.player.dead) {
        return Effect.succeed(false);
      }
      if (hasCriticalAir(observation)) {
        return Effect.succeed(false);
      }
      if (observation.player.maxAir <= 0 || observation.player.air >= observation.player.maxAir) {
        return Effect.succeed(true);
      }
      const distance = horizontalDistanceSquared(observation.player.position, target);
      const madeProgress =
        previousDistanceSquared === undefined || distance < previousDistanceSquared - 0.01;
      const nextStagnantObservations = madeProgress ? 0 : stagnantObservations + 1;
      if (
        attemptsRemaining <= 1 ||
        nextStagnantObservations >= AIR_ESCAPE_ASCENT_STAGNATION_OBSERVATIONS
      ) {
        return Effect.succeed(false);
      }
      return waitForOpenAirColumn(
        state,
        target,
        attemptsRemaining - 1,
        distance,
        nextStagnantObservations,
      );
    }),
  );
}

function isAirBlock(blockId: string): boolean {
  return (
    blockId === "minecraft:air" ||
    blockId === "minecraft:cave_air" ||
    blockId === "minecraft:void_air"
  );
}

function hasCriticalAir(observation: BeatGameObservation): boolean {
  return (
    observation.player.maxAir > 0 &&
    observation.player.air < Math.min(AIR_ESCAPE_CRITICAL_AIR_TICKS, observation.player.maxAir / 3)
  );
}

function hasAirForBreathingPocketPath(observation: BeatGameObservation): boolean {
  return (
    observation.player.maxAir <= 0 ||
    observation.player.air >
      Math.min(observation.player.maxAir, AIR_ESCAPE_BREATHING_POCKET_PATH_RESERVE_TICKS)
  );
}

function overheadEscapeBlocks(
  position: BeatGamePosition,
): readonly [BeatGameBlockPosition, BeatGameBlockPosition] {
  const base = {
    x: Math.floor(position.x),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
  return [
    { ...base, y: Math.floor(position.y) + 1 },
    { ...base, y: Math.floor(position.y) + 2 },
  ];
}

function queryExactBlock(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: blockCenter(position),
      radius: 0.25,
      selector: {},
      maximumResults: 1,
    })
    .pipe(
      Effect.map((blocks) => blocks.find((block) => sameBlockPosition(block.position, position))),
    );
}

function hasMiningPickaxe(observation: BeatGameObservation): boolean {
  return MINING_PICKAXE_ITEM_IDS.some((itemId) => (observation.inventory.counts[itemId] ?? 0) > 0);
}

function hasMiningPickaxeReserve(
  observation: BeatGameObservation,
  itemIds: readonly string[],
  minimumRemainingDurability: number,
): boolean {
  const availableItemIds = itemIds.filter(
    (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
  );
  if (availableItemIds.length === 0) {
    return false;
  }
  const reportedDurability = observation.inventory.remainingDurability;
  return (
    reportedDurability === undefined ||
    availableItemIds.reduce((total, itemId) => total + (reportedDurability[itemId] ?? 0), 0) >=
      minimumRemainingDurability
  );
}

function swimUpOneLevel(
  state: RunState,
  startingY: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  return state.driver.withControl(
    state.driver
      .act({
        type: "set-movement",
        jump: true,
      })
      .pipe(
        Effect.andThen(
          waitForVerticalProgress(state, startingY, AIR_ESCAPE_VERTICAL_PROGRESS_ATTEMPTS),
        ),
        Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
      ),
  );
}

function waitForVerticalProgress(
  state: RunState,
  startingY: number,
  attemptsRemaining: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.sleep(100).pipe(
    Effect.andThen(state.driver.observe),
    Effect.flatMap((observation) =>
      observation.player.dead ||
      observation.player.position.y >= startingY + AIR_ESCAPE_VERTICAL_PROGRESS
        ? Effect.succeed(true)
        : attemptsRemaining <= 1
          ? Effect.succeed(false)
          : waitForVerticalProgress(state, startingY, attemptsRemaining - 1),
    ),
  );
}

function isPlayerInFluid(
  driver: BeatGameDriver,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  return isPlayerInBlocks(driver, position, PLAYER_FLUID_BLOCK_IDS);
}

// A live run stood on dry ground with lava touching the edge of its hitbox
// and burned to death while a feet and head check saw no lava. Lava hurts on
// any overlap, so check every block the player's box reaches into.
const PLAYER_HALF_WIDTH = 0.3;
const PLAYER_HEIGHT = 1.8;
const HITBOX_EPSILON = 0.001;

/// A live bot started a meal three times while it fell into the lava sea or
/// burned in it, and died each time with food in its mouth. A meal waits
/// until the bot has landed and is out of lava.
function eatUnlessEndangered(
  state: RunState,
  options: Parameters<typeof eatWhenNeeded>[1],
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const current = yield* state.driver.observe;
    if (
      (!current.player.onGround && current.player.velocity.y <= EAT_FALLING_VELOCITY) ||
      (yield* isPlayerInLava(state.driver, current.player.position))
    ) {
      return;
    }
    yield* eatWhenNeeded(state.driver, options);
  });
}

function isPlayerInLava(
  driver: BeatGameDriver,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  const minimum = {
    x: Math.floor(position.x - PLAYER_HALF_WIDTH + HITBOX_EPSILON),
    y: Math.floor(position.y + HITBOX_EPSILON),
    z: Math.floor(position.z - PLAYER_HALF_WIDTH + HITBOX_EPSILON),
  };
  const maximum = {
    x: Math.floor(position.x + PLAYER_HALF_WIDTH - HITBOX_EPSILON),
    y: Math.floor(position.y + PLAYER_HEIGHT - HITBOX_EPSILON),
    z: Math.floor(position.z + PLAYER_HALF_WIDTH - HITBOX_EPSILON),
  };
  return driver
    .queryBlocks({
      center: { ...position, y: position.y + PLAYER_HEIGHT / 2 },
      radius: 2,
      selector: { blockIds: ["minecraft:lava"] },
      maximumResults: 32,
    })
    .pipe(
      Effect.map((blocks) =>
        blocks.some(
          ({ blockId, position: block }) =>
            blockId === "minecraft:lava" &&
            block.dimension === position.dimension &&
            block.x >= minimum.x &&
            block.x <= maximum.x &&
            block.y >= minimum.y &&
            block.y <= maximum.y &&
            block.z >= minimum.z &&
            block.z <= maximum.z,
        ),
      ),
    );
}

function isPlayerInBlocks(
  driver: BeatGameDriver,
  position: BeatGamePosition,
  blockIds: readonly string[],
): Effect.Effect<boolean, BeatGameDriverError> {
  const playerBlock = {
    x: Math.floor(position.x),
    y: Math.floor(position.y),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
  const headBlock = {
    ...playerBlock,
    y: Math.floor(position.y + 1.62),
  };
  return Effect.forEach(
    [playerBlock, headBlock],
    (block) =>
      driver
        .queryBlocks({
          center: blockCenter(block),
          radius: 0.25,
          selector: { blockIds },
          maximumResults: 1,
        })
        .pipe(
          Effect.map((blocks) =>
            blocks.some(
              ({ blockId, position: observed }) =>
                blockIds.includes(blockId) && sameBlockPosition(observed, block),
            ),
          ),
        ),
    { concurrency: 1 },
  ).pipe(Effect.map((results) => results.some(Boolean)));
}

function swimTowardDrySurface(
  state: RunState,
  surface: Readonly<{ x: number; z: number; surfaceY: number }>,
  maximumApproachAttempts = AIR_ESCAPE_SURFACE_APPROACH_ATTEMPTS,
): Effect.Effect<boolean, BeatGameDriverError> {
  return state.driver.observe.pipe(
    Effect.flatMap((observation) => {
      if (observation.player.dead) {
        return Effect.succeed(true);
      }
      const target = {
        x: surface.x + 0.5,
        y: surface.surfaceY + 1,
        z: surface.z + 0.5,
        dimension: observation.player.position.dimension,
      };
      const rotation = rotationToward(observation.player.position, {
        ...target,
        y: observation.player.position.y,
      });
      return state.driver.withControl(
        state.driver
          .act({
            type: "look",
            yaw: rotation.yaw,
            pitch: -20,
          })
          .pipe(
            Effect.andThen(
              state.driver.act({
                type: "set-movement",
                forward: true,
                jump: true,
                // Sprint-swimming keeps the eyes under water, which costs air the bot
                // may not have left.
                sprint:
                  observation.player.food > CRITICAL_HUNGER_FOOD_LEVEL &&
                  !hasUnsafeAir(observation),
              }),
            ),
            Effect.andThen(
              waitForDrySurfaceApproach(state, target, maximumApproachAttempts, rotation.yaw),
            ),
            Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
          ),
      );
    }),
  );
}

function waitForDrySurfaceApproach(
  state: RunState,
  target: BeatGamePosition,
  attemptsRemaining: number,
  targetYaw: number,
  recoveringAir = false,
  previousDistanceSquared?: number,
  stagnantObservations = 0,
  highestRecoveryY?: number,
  highestRecoveryAir?: number,
  recoveryStagnantObservations = 0,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    yield* Effect.sleep(100);
    const observation = yield* state.driver.observe;
    const position = observation.player.position;
    const distanceToTarget = horizontalDistanceSquared(position, target);
    const reachedSurface =
      distanceToTarget <= 1.5 * 1.5 && position.y >= target.y - 0.25 && observation.player.onGround;
    if (observation.player.dead) {
      return true;
    }
    if (!(yield* isPlayerInFluid(state.driver, position))) {
      return observation.player.onGround;
    }
    if (reachedSurface) {
      return attemptsRemaining <= 1
        ? false
        : yield* waitForDrySurfaceApproach(
            state,
            target,
            attemptsRemaining - 1,
            targetYaw,
            recoveringAir,
            distanceToTarget,
            0,
            highestRecoveryY,
            highestRecoveryAir,
            recoveryStagnantObservations,
          );
    }
    const hasRecoveredAir =
      observation.player.maxAir <= 0 || observation.player.air >= observation.player.maxAir;
    if (recoveringAir && hasRecoveredAir) {
      yield* state.driver.act({
        type: "look",
        yaw: targetYaw,
        pitch: -20,
      });
      return yield* waitForDrySurfaceApproach(
        state,
        target,
        attemptsRemaining - 1,
        targetYaw,
        false,
        distanceToTarget,
        0,
      );
    }
    if (!recoveringAir && hasUnsafeAir(observation)) {
      yield* state.driver.act({
        type: "look",
        yaw: observation.player.rotation.yaw,
        pitch: -90,
      });
      return yield* waitForDrySurfaceApproach(
        state,
        target,
        attemptsRemaining - 1,
        targetYaw,
        true,
        distanceToTarget,
        0,
        position.y,
        observation.player.air,
      );
    }
    if (recoveringAir) {
      const madeRecoveryProgress =
        highestRecoveryY === undefined ||
        highestRecoveryAir === undefined ||
        position.y > highestRecoveryY + AIR_ESCAPE_ASCENT_PROGRESS_EPSILON ||
        observation.player.air > highestRecoveryAir;
      const nextRecoveryStagnantObservations = madeRecoveryProgress
        ? 0
        : recoveryStagnantObservations + 1;
      if (
        hasCriticalAir(observation) ||
        nextRecoveryStagnantObservations >= AIR_ESCAPE_ASCENT_STAGNATION_OBSERVATIONS
      ) {
        return false;
      }
      return attemptsRemaining <= 1
        ? false
        : yield* waitForDrySurfaceApproach(
            state,
            target,
            attemptsRemaining - 1,
            targetYaw,
            true,
            distanceToTarget,
            0,
            Math.max(highestRecoveryY ?? position.y, position.y),
            Math.max(highestRecoveryAir ?? observation.player.air, observation.player.air),
            nextRecoveryStagnantObservations,
          );
    }
    const madeProgress =
      previousDistanceSquared === undefined || distanceToTarget < previousDistanceSquared - 0.05;
    const nextStagnantObservations = madeProgress ? 0 : stagnantObservations + 1;
    return attemptsRemaining <= 1 || nextStagnantObservations >= AIR_ESCAPE_STAGNANT_OBSERVATIONS
      ? false
      : yield* waitForDrySurfaceApproach(
          state,
          target,
          attemptsRemaining - 1,
          targetYaw,
          recoveringAir,
          distanceToTarget,
          nextStagnantObservations,
          highestRecoveryY,
          highestRecoveryAir,
          recoveryStagnantObservations,
        );
  });
}

const GOLD_ARMOR_ITEM_IDS = new Set([
  "minecraft:golden_helmet",
  "minecraft:golden_chestplate",
  "minecraft:golden_leggings",
  "minecraft:golden_boots",
]);

const ATTACK_DAMAGE_TYPES = new Set([
  "minecraft:arrow",
  "minecraft:mob_attack",
  "minecraft:mob_projectile",
]);
const RECENT_ATTACK_MS = 3000;
// Hoglins cannot climb to a bot three blocks up. Endermen are not on this
// list: they teleport, and a live bot was hit 0.7 seconds after it reached
// the top of a three-block pillar. Another was hit while it climbed. An
// enderman is fought on the ground.
const PILLAR_DEFENSE_ENTITY_TYPES: ReadonlySet<string> = new Set([
  "minecraft:hoglin",
  "minecraft:zoglin",
]);
// A hoglin reaches about 2.9 blocks from its feet to its target's feet, so
// a live bot on a two-block pillar was struck and thrown off. Three blocks
// is out of its reach. The bot strikes when the mob comes within its own
// reach and otherwise waits for it to leave.
const PILLAR_DEFENSE_HEIGHT = 3;
const PILLAR_DEFENSE_SEARCH_MS = 2000;
const NARROW_FOOTING_FILL_BLOCK_IDS = [
  "minecraft:netherrack",
  "minecraft:cobblestone",
  "minecraft:cobbled_deepslate",
  "minecraft:blackstone",
  "minecraft:dirt",
] as const;
// These never stand on the bot's floor, so widening it only spends time under
// their fire. A live bot spent 3.4 seconds filling its footing while blazes
// shot it from 10.8 to 6.8 health, then died in the same fight.
const FLYING_HOSTILE_ENTITY_TYPES: ReadonlySet<string> = new Set([
  "minecraft:blaze",
  "minecraft:breeze",
  "minecraft:ghast",
  "minecraft:phantom",
  "minecraft:vex",
]);
// A bridge leaves six of the eight cells around the bot open.
const NARROW_FOOTING_MINIMUM_OPEN_CELLS = 5;
const NARROW_FOOTING_FILL_TIMEOUT_MS = 4000;
const NARROW_FOOTING_RECENT_HIT_MS = 2000;
// A live run spent 35 and then 48 seconds trying to climb a pillar beside a
// hoglin, which hit it the whole time until it died.
const PILLAR_CLIMB_TIMEOUT_MS = 8000;
// A hoglin closer than this reaches the bot before the pillar is built. A
// live run was struck one block up and thrown off a cliff into lava. A hit
// the shield blocks does not throw, so a bot with a shield fights it there.
const PILLAR_BUILD_LEAD_DISTANCE = 5;
const PILLAR_DEFENSE_DURATION_MS = 30_000;
const PILLAR_DEFENSE_WATCH_RADIUS = 12;
const PILLAR_STRIKE_REACH = 3.5;
const PILLAR_STRIKE_INTERVAL_MS = 650;
const RANGED_ATTACK_DAMAGE_TYPES = new Set([
  "minecraft:arrow",
  "minecraft:trident",
  "minecraft:fireball",
  "minecraft:mob_projectile",
]);
const DISTANT_RANGED_ATTACKER_ENTITY_TYPES: ReadonlySet<string> = new Set([
  "minecraft:blaze",
  "minecraft:ghast",
  "minecraft:skeleton",
  "minecraft:stray",
  "minecraft:bogged",
  "minecraft:pillager",
]);
// A ghast fires from up to 64 blocks. A live bot was hit by one it did not
// look far enough to see, took the hit for environmental damage, and was
// blown off a Nether ledge into the lava sea.
const DISTANT_RANGED_ATTACKER_RADIUS = 64;
const SURFACE_ESCAPE_PROGRESS_HEIGHT = 1;
const SURFACE_ESCAPE_MAXIMUM_TOTAL_ATTEMPTS = 20;
const WORKSTATION_SETTLE_POLLS = 10;
const WORKSTATION_SETTLE_POLL_MS = 100;
const RECENT_LAVA_DAMAGE_MS = 1000;

// Piglins leave a player who wears gold alone, but they still report
// themselves aggressive while they hunt hoglins. A live bot in gold boots
// read that as a threat, attacked a calm piglin, and a hoglin knocked it
// into lava during the fight. Once something hits the bot, piglins count as
// threats again, since an angered piglin attacks even a player in gold.
function withGoldCalmedPiglins(
  driver: BeatGameDriver,
  observation: Ref.Ref<BeatGameObservation>,
  lastDamage: Ref.Ref<Extract<BeatGameDriverEvent, { type: "bot-damaged" }> | undefined>,
): BeatGameDriver {
  const queryEntities: BeatGameDriver["queryEntities"] = (query) =>
    driver
      .queryEntities(query)
      .pipe(
        Effect.flatMap((entities) =>
          Effect.all([Ref.get(observation), Ref.get(lastDamage)], { concurrency: 1 }).pipe(
            Effect.map(([current, damage]) =>
              Object.values(current.player.equipment).some((itemId) =>
                GOLD_ARMOR_ITEM_IDS.has(itemId),
              ) &&
              !(
                damage?.damageType !== undefined &&
                ATTACK_DAMAGE_TYPES.has(damage.damageType) &&
                Date.now() - Date.parse(damage.observedAt) <= RECENT_ATTACK_MS
              )
                ? entities.map((entity) =>
                    entity.entityType === "minecraft:piglin" && entity.aggressive === true
                      ? { ...entity, aggressive: false }
                      : entity,
                  )
                : entities,
            ),
          ),
        ),
      );
  return {
    instanceId: driver.instanceId,
    botId: driver.botId,
    observe: driver.observe,
    events: driver.events,
    ...(driver.environment === undefined ? {} : { environment: driver.environment }),
    queryBlocks: driver.queryBlocks,
    queryEntities,
    raycast: driver.raycast,
    sampleSurface: driver.sampleSurface,
    recipesFor: driver.recipesFor,
    canCraft: driver.canCraft,
    waitForChunks: driver.waitForChunks,
    pathfind: driver.pathfind,
    pathfindXZ: driver.pathfindXZ,
    runTask: driver.runTask,
    act: driver.act,
    withControl: driver.withControl,
  };
}

function isAggressiveNeutralMob(entity: BeatGameEntityObservation): boolean {
  return entity.aggressive === true || entity.target !== undefined;
}

function isCurrentlyHostileMob(entity: BeatGameEntityObservation): boolean {
  return (
    !CONDITIONALLY_HOSTILE_ENTITY_TYPES.has(entity.entityType) || isAggressiveNeutralMob(entity)
  );
}

function isActiveConditionalHostile(entity: BeatGameEntityObservation): boolean {
  return (
    CONDITIONALLY_HOSTILE_ENTITY_TYPES.has(entity.entityType) && isAggressiveNeutralMob(entity)
  );
}

function isActiveMeleeHostile(entity: BeatGameEntityObservation): boolean {
  return (
    PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(entity.entityType) ||
    isActiveConditionalHostile(entity)
  );
}

function isDistantDrownedThreat(
  playerPosition: BeatGamePosition,
  target: BeatGameEntityObservation,
): boolean {
  return (
    target.entityType === "minecraft:drowned" &&
    distanceSquared(playerPosition, target.position) > MELEE_ENGAGEMENT_RADIUS ** 2
  );
}

function isOverwhelmingAmbush(
  observation: BeatGameObservation,
  nearbyThreats: readonly BeatGameEntityObservation[],
): boolean {
  const threshold =
    (observation.inventory.counts["minecraft:shield"] ?? 0) > 0
      ? SHIELDED_AMBUSH_ESCAPE_THRESHOLD
      : 2;
  return nearbyThreats.length >= threshold;
}

function findNearbyAttackThreat(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<ImmediateThreat | undefined, BeatGameDriverError> {
  const radius = 16;
  return Effect.all(
    [
      state.driver.queryEntities({
        origin: observation.player.position,
        radius,
        selector: {
          categories: [2],
          alive: true,
        },
        maximumResults: 8,
      }),
      state.driver.queryEntities({
        origin: observation.player.position,
        radius,
        selector: {
          entityTypes: DANGEROUS_NEUTRAL_ENTITY_TYPES,
          alive: true,
        },
        maximumResults: 8,
      }),
    ],
    { concurrency: 1 },
  ).pipe(
    Effect.map(([hostiles, dangerousNeutralMobs]) => {
      const nearbyGroupCandidates = deduplicateEntityTargets([
        ...hostiles,
        ...dangerousNeutralMobs,
      ]);
      const threats = new Map(
        [
          ...hostiles.filter((entity) => isCurrentlyHostileMob(entity)),
          ...dangerousNeutralMobs.filter((entity) => isAggressiveNeutralMob(entity)),
        ].map((entity) => [`${entity.connectionEpoch}:${entity.networkId}`, entity]),
      );
      const candidates = [...threats.values()].toSorted((left, right) => {
        const leftDistanceSquared = distanceSquared(observation.player.position, left.position);
        const rightDistanceSquared = distanceSquared(observation.player.position, right.position);
        return (
          threatPriorityScore(left, leftDistanceSquared) -
          threatPriorityScore(right, rightDistanceSquared)
        );
      });
      const escapeOnlyThreat = candidates.find(
        (candidate) =>
          ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(candidate.entityType) &&
          distanceSquared(observation.player.position, candidate.position) <=
            proactiveEscapeOnlyEvasionRadius(candidate) ** 2,
      );
      const rangedAttacker = candidates.find(
        (candidate) =>
          PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(candidate.entityType) ||
          isDistantDrownedThreat(observation.player.position, candidate),
      );
      const closeMeleeAttacker = candidates.find(
        (candidate) =>
          PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(candidate.entityType) &&
          !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(candidate.entityType) &&
          distanceSquared(observation.player.position, candidate.position) <= 4 ** 2,
      );
      const nearbyPursuer = candidates.find(
        (candidate) =>
          distanceSquared(observation.player.position, candidate.position) <=
          (ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(candidate.entityType)
            ? proactiveEscapeOnlyEvasionRadius(candidate)
            : PROACTIVE_ESCAPE_ONLY_EVASION_RADIUS) **
            2,
      );
      const nearest = escapeOnlyThreat ?? closeMeleeAttacker ?? rangedAttacker ?? nearbyPursuer;
      if (nearest === undefined) {
        return undefined;
      }
      const nearbyThreats = candidates.filter(
        (candidate) =>
          distanceSquared(observation.player.position, candidate.position) <=
          PROACTIVE_RANGED_ENGAGEMENT_RADIUS ** 2,
      );
      const overwhelmingAmbush = isOverwhelmingAmbush(observation, nearbyThreats);
      const shouldEscape =
        ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(nearest.entityType) ||
        hasNearbyGroupAggroMember(nearest, nearbyGroupCandidates) ||
        isDistantDrownedThreat(observation.player.position, nearest) ||
        overwhelmingAmbush ||
        shouldDisengageFromThreat(state, observation, nearest);
      return {
        target: nearest,
        response: shouldEscape ? ("flee" as const) : ("attack" as const),
      };
    }),
  );
}

// A live run fought a skeleton while a zombie beside it hit it every second
// and killed it in seven seconds without taking a hit back. While fighting
// an archer, a melee attacker that comes closer takes over as the target.
function defendAgainstTarget(
  state: RunState,
  target: BeatGameEntityObservation,
  options: DefenseOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  if (PILLAR_DEFENSE_ENTITY_TYPES.has(target.entityType)) {
    return fightFromPillar(state, target).pipe(
      Effect.flatMap((handled) =>
        handled ? Effect.void : defendAgainstSingleTarget(state, target, options),
      ),
    );
  }
  if (!PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType)) {
    return defendAgainstSingleTarget(state, target, options);
  }
  return Effect.raceFirst(
    defendAgainstSingleTarget(state, target, options).pipe(Effect.as(undefined)),
    waitForCloserMeleeAttacker(state, target),
  ).pipe(
    Effect.flatMap((closer) =>
      closer === undefined ? Effect.void : defendAgainstTarget(state, closer, options),
    ),
  );
}

/// Hoglins hit hard, take many hits and throw the bot back. A live run died
/// to three of them in a Nether fortress: knocked off a ledge, knocked into
/// lava while fleeing, and beaten in a straight fight. They cannot climb, so
/// the bot stands on a pillar out of their reach until they leave.
function fightFromPillar(
  state: RunState,
  target: BeatGameEntityObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    if (
      observation.player.dead ||
      pathBuildingBlockCount(observation) < PILLAR_DEFENSE_HEIGHT + 1
    ) {
      return false;
    }
    const base = observation.player.position;
    if (
      SHIELD_BLOCKING_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
      hasItemInInventoryOrEquipment(observation, "minecraft:shield") &&
      distanceSquared(base, target.position) < PILLAR_BUILD_LEAD_DISTANCE ** 2
    ) {
      return false;
    }
    const top = {
      x: Math.floor(base.x) + 0.5,
      y: Math.floor(base.y) + PILLAR_DEFENSE_HEIGHT,
      z: Math.floor(base.z) + 0.5,
      dimension: base.dimension,
    };
    // A live climb stalled for eight seconds beside a hoglin, which hit the
    // bot twice for five each and killed it. A hit during the climb means the
    // pillar is not going up in time, so the bot fights instead.
    const climbStartedAt = yield* DateTime.now.pipe(Effect.map(DateTime.formatIso));
    const climbed = yield* Effect.raceFirst(
      state.driver
        .pathfind(top, 0, {
          ...state.strategy.path,
          allowMining: false,
          allowPlacing: true,
          maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, PILLAR_DEFENSE_SEARCH_MS),
        })
        .pipe(
          Effect.as(true),
          Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
          Effect.timeoutOrElse({
            duration: PILLAR_CLIMB_TIMEOUT_MS,
            orElse: () => Effect.succeed(false),
          }),
        ),
      waitForMobAttackSince(state, climbStartedAt).pipe(Effect.as(false)),
    );
    if (!climbed) {
      return false;
    }
    yield* emit(state, {
      type: "diagnostic",
      message: "Fighting a ground attacker from a pillar",
      data: { attackerNetworkId: target.networkId, attackerType: target.entityType, top },
    });
    const deadline = (yield* Clock.currentTimeMillis) + PILLAR_DEFENSE_DURATION_MS;
    while ((yield* Clock.currentTimeMillis) < deadline) {
      const current = yield* state.driver.observe;
      if (current.player.dead) {
        return true;
      }
      if (current.player.position.y < top.y - 0.5) {
        return false;
      }
      const [threat] = yield* state.driver.queryEntities({
        origin: current.player.position,
        radius: PILLAR_DEFENSE_WATCH_RADIUS,
        selector: { networkId: target.networkId, alive: true },
        maximumResults: 1,
      });
      if (threat === undefined) {
        return true;
      }
      // Reach is measured to the middle of the mob's body, not its feet,
      // since the bot strikes down at it from above.
      const eye = { ...current.player.position, y: current.player.position.y + 1.62 };
      const body = { ...threat.position, y: threat.position.y + 1 };
      if (distanceSquared(eye, body) <= PILLAR_STRIKE_REACH ** 2) {
        yield* state.driver.withControl(performKnockbackStrike(state, current, threat));
      }
      yield* Effect.sleep(PILLAR_STRIKE_INTERVAL_MS);
    }
    return true;
  });
}

function waitForCloserMeleeAttacker(
  state: RunState,
  target: BeatGameEntityObservation,
): Effect.Effect<BeatGameEntityObservation, BeatGameDriverError> {
  const poll = (): Effect.Effect<BeatGameEntityObservation, BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) =>
        state.driver
          .queryEntities({
            origin: observation.player.position,
            radius: EMERGENCY_KNOCKBACK_RANGE,
            selector: { categories: [2], alive: true },
            maximumResults: 8,
          })
          .pipe(
            Effect.flatMap((entities) => {
              const currentTarget = entities.find((entity) => isSameEntityTarget(entity, target));
              const targetDistanceSquared = distanceSquared(
                observation.player.position,
                (currentTarget ?? target).position,
              );
              const closer = entities.find(
                (entity) =>
                  !isSameEntityTarget(entity, target) &&
                  PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(entity.entityType) &&
                  !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(entity.entityType) &&
                  distanceSquared(observation.player.position, entity.position) <
                    targetDistanceSquared,
              );
              return closer === undefined ? Effect.suspend(poll) : Effect.succeed(closer);
            }),
          ),
      ),
    );
  return poll();
}

function defendAgainstSingleTarget(
  state: RunState,
  target: BeatGameEntityObservation,
  options: DefenseOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  const isRangedTarget = PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType);
  const defensivePath = {
    ...state.strategy.path,
    allowMining: false,
    allowPlacing: false,
    avoidFluids: !isRangedTarget || isNether(target.position.dimension),
    maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 3000),
  };
  return state.driver.observe.pipe(
    Effect.flatMap((observation) => {
      const canBlockWithShield =
        SHIELD_BLOCKING_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
        (observation.inventory.counts["minecraft:shield"] ?? 0) > 0;
      const attack = attackEntity(state.driver, {
        target,
        sprinting: true,
        targetUnavailableTimeoutSeconds: 3,
        selectBestWeapon: true,
        useOffhandShield: canBlockWithShield,
        path: defensivePath,
      });
      const prepareShield = canBlockWithShield
        ? state.driver.withControl(
            Effect.gen(function* () {
              if (observation.player.equipment["offhand"] !== "minecraft:shield") {
                yield* state.driver.act({
                  type: "equip-item",
                  selector: { itemIds: ["minecraft:shield"] },
                  equipmentSlot: "offhand",
                });
              }
            }),
          )
        : Effect.void;
      const commitThroughWound =
        options.commitThroughWound === true ||
        shouldCommitToUnshieldedRangedFight(observation, target) ||
        shouldCommitToBarehandedRangedFight(observation, target) ||
        shouldCommitToCaughtMeleePursuerFight(observation, target) ||
        shouldCommitToBarehandedCaughtFastPursuerFight(observation, target);
      const disengageWhenWounded = !(
        (canBlockWithShield &&
          PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
          shouldCommitToRangedFight(observation, target)) ||
        shouldCommitToCloseRangedFight(observation, target) ||
        shouldCommitToFastMeleePursuerFight(observation, target) ||
        shouldCommitToCaughtMeleePursuerFight(observation, target)
      );
      const guardedAttack = Effect.raceFirst(
        attack.pipe(Effect.as("defended" as const)),
        monitorDefenseHealth(
          state,
          observation.player.health,
          observation.player.position,
          disengageWhenWounded,
          {
            commitThroughWound,
            enforcePursuitLeash: isRangedTarget,
            disengageStalledShieldedRangedPursuit: canBlockWithShield && isRangedTarget,
          },
        ),
      ).pipe(
        Effect.flatMap((outcome) =>
          outcome === "defended"
            ? Effect.void
            : outcome === "unsafe-air"
              ? state.driver.observe.pipe(
                  Effect.flatMap((latest) => emergencyAirAscent(state, latest.player.position)),
                )
              : escapeFromTarget(state, target, {
                  continueEscapingWhenHit: true,
                }),
        ),
      );
      return prepareShield.pipe(
        Effect.andThen(
          isRangedTarget || FLYING_HOSTILE_ENTITY_TYPES.has(target.entityType)
            ? Effect.void
            : widenNarrowFooting(state, observation),
        ),
        Effect.andThen(guardedAttack),
      );
    }),
  );
}

/// A live bot fought a hoglin on a one-block bridge it had built over a
/// twelve-block drop. The hoglin pressed into its cell and the bot went over
/// the edge into lava without taking a hit. Before a melee fight on footing
/// that narrow, the bot fills the floor around it into a small platform.
function widenNarrowFooting(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    // Filling the floor takes a second or more with the shield down. A live
    // bot did it while an enderman was already hitting it and died there.
    const lastDamage = yield* Ref.get(state.lastDamage);
    if (
      lastDamage?.damageType !== undefined &&
      ATTACK_DAMAGE_TYPES.has(lastDamage.damageType) &&
      (yield* Clock.currentTimeMillis) - Date.parse(lastDamage.observedAt) <=
        NARROW_FOOTING_RECENT_HIT_MS
    ) {
      return;
    }
    const blockId = NARROW_FOOTING_FILL_BLOCK_IDS.find(
      (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
    );
    // Water breaks a fall rather than causing one. A live bot fought a
    // drowned under water and spent four seconds filling the water around
    // it as if it were a drop while the drowned hit it.
    if (
      blockId === undefined ||
      (yield* isPlayerInFluid(state.driver, observation.player.position))
    ) {
      return;
    }
    const feet = {
      x: Math.floor(observation.player.position.x),
      y: Math.floor(observation.player.position.y),
      z: Math.floor(observation.player.position.z),
      dimension: observation.player.position.dimension,
    };
    const floor = yield* state.driver.queryBlocks({
      center: { x: feet.x + 0.5, y: feet.y - 0.5, z: feet.z + 0.5, dimension: feet.dimension },
      radius: 1.5,
      selector: {},
      maximumResults: 27,
    });
    const openCells: { x: number; y: number; z: number }[] = [];
    for (const x of [-1, 0, 1]) {
      for (const z of [-1, 0, 1]) {
        if (x === 0 && z === 0) {
          continue;
        }
        const cell = floor.find(
          ({ position }) =>
            position.x === feet.x + x && position.y === feet.y - 1 && position.z === feet.z + z,
        );
        if (cell?.solid !== true && cell?.blockId !== "minecraft:water") {
          openCells.push({ x, y: -1, z });
        }
      }
    }
    if (openCells.length < NARROW_FOOTING_MINIMUM_OPEN_CELLS) {
      return;
    }
    yield* emit(state, {
      type: "diagnostic",
      message: "Widening narrow footing before a melee fight",
      data: { position: observation.player.position, openCells: openCells.length },
    });
    // A live bot started the fill before a zombie reached it and kept placing
    // blocks through five of its hits. The first hit ends the fill.
    const fillStartedAt = yield* DateTime.now.pipe(Effect.map(DateTime.formatIso));
    yield* Effect.raceFirst(
      buildStructure(state.driver, {
        origin: feet,
        blocks: openCells.map((offset) => ({ offset, blockId })),
        path: { ...state.strategy.path, allowMining: false, allowPlacing: true },
      }).pipe(
        Effect.timeoutOrElse({
          duration: NARROW_FOOTING_FILL_TIMEOUT_MS,
          orElse: () => Effect.void,
        }),
        Effect.ignore,
      ),
      waitForMobAttackSince(state, fillStartedAt),
    );
  });
}

function monitorDefenseHealth(
  state: RunState,
  engagementHealth: number,
  engagementPosition: BeatGamePosition,
  disengageWhenWounded = true,
  options: {
    readonly commitThroughWound?: boolean;
    readonly enforcePursuitLeash?: boolean;
    readonly disengageStalledShieldedRangedPursuit?: boolean;
  } = {},
): Effect.Effect<"disengage" | "unsafe-air", BeatGameDriverError> {
  return Effect.sleep(Math.max(MINIMUM_RECOVERY_POLL_MS, state.strategy.observationPollMs)).pipe(
    Effect.andThen(state.driver.observe),
    Effect.flatMap((observation) => {
      if (observation.player.dead) {
        return Effect.succeed("disengage" as const);
      }
      if (hasUnsafeAir(observation)) {
        return Effect.succeed("unsafe-air" as const);
      }
      if (
        observation.player.position.dimension !== engagementPosition.dimension ||
        ((disengageWhenWounded || options.enforcePursuitLeash === true) &&
          distanceSquared(observation.player.position, engagementPosition) >
            DEFENSIVE_PURSUIT_MAX_DISTANCE ** 2)
      ) {
        return Effect.succeed("disengage" as const);
      }
      if (observation.player.health <= LETHAL_MELEE_DISENGAGE_HEALTH) {
        return Effect.succeed("disengage" as const);
      }
      if (
        options.disengageStalledShieldedRangedPursuit === true &&
        observation.player.health < engagementHealth &&
        horizontalDistanceSquared(observation.player.position, engagementPosition) <
          STALLED_RANGED_PURSUIT_MAXIMUM_PROGRESS ** 2
      ) {
        return Effect.succeed("disengage" as const);
      }
      return state.driver
        .queryEntities({
          origin: observation.player.position,
          radius: PROACTIVE_RANGED_ENGAGEMENT_RADIUS,
          selector: { categories: [2], alive: true },
          maximumResults: 32,
        })
        .pipe(
          Effect.flatMap((nearbyThreats) => {
            if (
              isOverwhelmingAmbush(
                observation,
                nearbyThreats.filter((entity) => isCurrentlyHostileMob(entity)),
              )
            ) {
              return Effect.succeed("disengage" as const);
            }
            if (
              !disengageWhenWounded ||
              options.commitThroughWound === true ||
              observation.player.health >= state.strategy.minimumHealth ||
              observation.player.health >= engagementHealth
            ) {
              return monitorDefenseHealth(
                state,
                engagementHealth,
                engagementPosition,
                disengageWhenWounded,
                options,
              );
            }
            return Effect.succeed("disengage" as const);
          }),
        );
    }),
  );
}

// A fight the bot turned to because running failed must not end in running
// again when it takes a hit. A live bot alternated between both for fifteen
// seconds against a zombie and a skeleton and died without winning either.
interface DefenseOptions {
  readonly commitThroughWound?: boolean;
}

function defendAndRecover(
  state: RunState,
  target: BeatGameEntityObservation,
  options: DefenseOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return defendAgainstTarget(state, target, options).pipe(
    Effect.andThen(retreatAndRecover(state, POST_DEFENSE_RECOVERY_DURATION_MS)),
    Effect.andThen(state.driver.observe),
    // Drop pickup runs inside the safety response, where no monitor watches
    // for attacks. A live run walked to a zombie's drops while a spider bit
    // it from 14 health to death, so an attack ends the pickup.
    Effect.flatMap((afterRecovery) =>
      Effect.raceFirst(
        collectNearbyDrops(state.driver, {
          radius: 8,
          maximumDrops: 16,
          settleDelayMs: 500,
          path: {
            ...state.strategy.path,
            allowPlacing: false,
            avoidFluids: true,
          },
        }),
        waitForMobAttackSince(state, afterRecovery.observedAt),
      ),
    ),
    Effect.catchTag("BeatGameDriverError", (error) =>
      error.operation === "task.attack-entity" || error.operation === "task.attack-nearest"
        ? recoverFromFailedDefense(state, target)
        : Effect.fail(error),
    ),
  );
}

function extinguishFire(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  if (observation.player.dead || observation.player.fireTicks <= 0) {
    return Effect.void;
  }
  return Effect.gen(function* () {
    let current = observation;
    if (
      !isNether(current.player.position.dimension) &&
      (current.inventory.counts["minecraft:water_bucket"] ?? 0) > 0
    ) {
      yield* state.driver.withControl(
        Effect.gen(function* () {
          yield* state.driver.act({
            type: "select-item",
            selector: { itemIds: ["minecraft:water_bucket"] },
          });
          yield* state.driver.act({
            type: "look",
            yaw: current.player.rotation.yaw,
            pitch: 90,
          });
          yield* waitForViewRotation(state.driver, current.player.rotation.yaw, 90, 20);
          yield* state.driver.act({ type: "use-item", hand: "main" });
          yield* Effect.sleep(100);
        }).pipe(Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
      );
      current = yield* state.driver.observe;
      if (current.player.dead || current.player.fireTicks <= 0) {
        return;
      }
    }

    const water = [
      ...(yield* state.driver.queryBlocks({
        center: current.player.position,
        radius: 8,
        selector: { blockIds: ["minecraft:water"] },
        maximumResults: 32,
      })),
    ].toSorted(
      (left, right) =>
        distanceSquared(left.position, current.player.position) -
        distanceSquared(right.position, current.player.position),
    )[0];
    if (water === undefined) {
      return;
    }
    yield* state.driver
      .pathfind(blockCenter(water.position), 0.75, {
        ...state.strategy.path,
        allowMining: false,
        allowPlacing: false,
        avoidFluids: false,
        maxFallDistance: Math.min(state.strategy.path.maxFallDistance, 1),
        maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 5000),
      })
      .pipe(Effect.catchTag("BeatGameDriverError", () => Effect.void));
    yield* Effect.sleep(100);
  });
}

const FIRE_BLOCK_IDS: ReadonlySet<string> = new Set(["minecraft:fire", "minecraft:soul_fire"]);
const HOT_FLOOR_BLOCK_IDS: ReadonlySet<string> = new Set(["minecraft:magma_block"]);
const FIRE_STEP_OFFSETS = [
  { x: 1, z: 0 },
  { x: -1, z: 0 },
  { x: 0, z: 1 },
  { x: 0, z: -1 },
  { x: 1, z: 1 },
  { x: 1, z: -1 },
  { x: -1, z: 1 },
  { x: -1, z: -1 },
] as const;

function isFireFreeOpenBlock(block: BeatGameBlockObservation | undefined): boolean {
  return (
    block === undefined ||
    (block.replaceable && !FIRE_BLOCK_IDS.has(block.blockId) && !isPlayerFluidBlock(block.blockId))
  );
}

// Standing in a fire block keeps setting the bot alight, and in the Nether
// there is no water to put it out. A live bot stood in fire left by ghast
// fireballs for nine seconds and burned to death. Magma under the feet burns
// the same way. Returns whether the bot stepped off burning ground.
function stepOffBurningGround(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const position = observation.player.position;
    // The hitbox reaches into a neighboring cell when the bot stands near a
    // block edge. A live run stood 0.3 blocks from a fire it never checked,
    // because only its feet cell was queried, and burned to death in place.
    // Punching fire puts it out, which works from any footing.
    const fireCells = (yield* Effect.forEach(
      playerHitboxCells(position),
      (cell) => queryExactBlock(state.driver, cell),
      { concurrency: 1 },
    )).filter(
      (block): block is BeatGameBlockObservation =>
        block !== undefined && FIRE_BLOCK_IDS.has(block.blockId),
    );
    if (fireCells.length > 0) {
      yield* state.driver
        .withControl(
          Effect.forEach(
            fireCells,
            (fire) => state.driver.act({ type: "dig-block", position: fire.position }),
            { discard: true, concurrency: 1 },
          ),
        )
        .pipe(Effect.ignore);
      const remaining = yield* Effect.forEach(
        fireCells,
        (fire) => queryExactBlock(state.driver, fire.position),
        { concurrency: 1 },
      );
      if (!remaining.some((block) => block !== undefined && FIRE_BLOCK_IDS.has(block.blockId))) {
        return true;
      }
    }
    const feet = {
      x: Math.floor(position.x),
      y: Math.floor(position.y),
      z: Math.floor(position.z),
      dimension: position.dimension,
    };
    const [floorBlock, ...occupied] = yield* Effect.forEach(
      [{ ...feet, y: feet.y - 1 }, feet, { ...feet, y: feet.y + 1 }],
      (cell) => queryExactBlock(state.driver, cell),
      { concurrency: 1 },
    );
    if (
      !occupied.some((block) => block !== undefined && FIRE_BLOCK_IDS.has(block.blockId)) &&
      (floorBlock === undefined || !HOT_FLOOR_BLOCK_IDS.has(floorBlock.blockId))
    ) {
      return false;
    }
    const candidates = FIRE_STEP_OFFSETS.map((offset) => ({
      x: feet.x + offset.x,
      y: feet.y,
      z: feet.z + offset.z,
      dimension: feet.dimension,
    })).toSorted(
      (left, right) =>
        horizontalDistanceSquared(blockCenter(left), position) -
        horizontalDistanceSquared(blockCenter(right), position),
    );
    for (const cell of candidates) {
      const [floor, body, head] = yield* Effect.forEach(
        [{ ...cell, y: cell.y - 1 }, cell, { ...cell, y: cell.y + 1 }],
        (block) => queryExactBlock(state.driver, block),
        { concurrency: 1 },
      );
      if (
        !isSolid(floor) ||
        (floor !== undefined && HOT_FLOOR_BLOCK_IDS.has(floor.blockId)) ||
        !isFireFreeOpenBlock(body) ||
        !isFireFreeOpenBlock(head)
      ) {
        continue;
      }
      const stepped = yield* state.driver
        .pathfind({ x: cell.x + 0.5, y: cell.y, z: cell.z + 0.5, dimension: cell.dimension }, 0.3, {
          ...state.strategy.path,
          allowMining: false,
          allowPlacing: false,
          maxFallDistance: 1,
          maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 1000),
        })
        .pipe(
          Effect.as(true),
          Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
        );
      if (stepped) {
        return true;
      }
    }
    // Magma does not burn a sneaking player. A live bot in a fortress
    // basement found no cell to step to and stood on magma until it died.
    if (floorBlock !== undefined && HOT_FLOOR_BLOCK_IDS.has(floorBlock.blockId)) {
      yield* emit(state, {
        type: "diagnostic",
        message: "Sneaking on hot floor with no safe cell to step to",
        data: { position },
      });
      yield* state.driver.act({ type: "set-movement", sneak: true });
      return true;
    }
    return false;
  });
}

function playerHitboxCells(position: BeatGamePosition): readonly BeatGameBlockPosition[] {
  const cells: BeatGameBlockPosition[] = [];
  const minimumX = Math.floor(position.x - PLAYER_HALF_WIDTH + HITBOX_EPSILON);
  const maximumX = Math.floor(position.x + PLAYER_HALF_WIDTH - HITBOX_EPSILON);
  const minimumY = Math.floor(position.y + HITBOX_EPSILON);
  const maximumY = Math.floor(position.y + PLAYER_HEIGHT - HITBOX_EPSILON);
  const minimumZ = Math.floor(position.z - PLAYER_HALF_WIDTH + HITBOX_EPSILON);
  const maximumZ = Math.floor(position.z + PLAYER_HALF_WIDTH - HITBOX_EPSILON);
  for (let x = minimumX; x <= maximumX; x += 1) {
    for (let y = minimumY; y <= maximumY; y += 1) {
      for (let z = minimumZ; z <= maximumZ; z += 1) {
        cells.push({ x, y, z, dimension: position.dimension });
      }
    }
  }
  return cells;
}

/// The hitbox check can miss lava that still burns the bot, so lava damage
/// in the last second also counts while the bot is on fire.
function isBurningInLava(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.all(
    [isPlayerInLava(state.driver, observation.player.position), Ref.get(state.lastDamage)],
    { concurrency: 1 },
  ).pipe(
    Effect.map(
      ([hitboxInLava, damage]) =>
        hitboxInLava ||
        (observation.player.fireTicks > 0 &&
          damage?.damageType === "minecraft:lava" &&
          Date.parse(observation.observedAt) - Date.parse(damage.observedAt) <=
            RECENT_LAVA_DAMAGE_MS),
    ),
  );
}

function escapeLava(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    if (!(yield* isBurningInLava(state, observation))) {
      return;
    }

    if (
      !isNether(observation.player.position.dimension) &&
      (observation.inventory.counts["minecraft:water_bucket"] ?? 0) > 0
    ) {
      yield* state.driver
        .withControl(
          Effect.gen(function* () {
            yield* state.driver.act({
              type: "select-item",
              selector: { itemIds: ["minecraft:water_bucket"] },
            });
            yield* state.driver.act({
              type: "look",
              yaw: observation.player.rotation.yaw,
              pitch: 90,
            });
            yield* waitForViewRotation(state.driver, observation.player.rotation.yaw, 90, 20);
            yield* state.driver.act({ type: "use-item", hand: "main" });
            yield* Effect.sleep(100);
          }).pipe(
            Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
          ),
        )
        .pipe(
          // A bucket can be present but unusable when there is no face in
          // reach. Continue with the same direct lava escape used in ultrawarm
          // dimensions instead of turning a recoverable hazard into a fatal SDK
          // error.
          Effect.catchTag("BeatGameDriverError", () => Effect.void),
        );
    }

    let latest = yield* state.driver.observe;
    if (latest.player.dead || !(yield* isBurningInLava(state, latest))) {
      return;
    }

    // AltoClef keeps jump and sprint held while its escape goal searches for
    // a non-lava cell. Do the same toward verified dry local ledges. Ordinary
    // ARA* deliberately excludes lava states, so asking it to plan from the
    // middle of a lava column cannot express this emergency movement.
    const ledges = yield* findNearbyDryEscapeLedges(state, latest.player.position, {
      radius: LAVA_ESCAPE_LEDGE_SEARCH_RADIUS,
      maximumResults: AIR_ESCAPE_LOCAL_LEDGE_QUERY_RESULTS,
      maximumCandidates: AIR_ESCAPE_LOCAL_LEDGE_CANDIDATES,
      maximumDrop: LAVA_ESCAPE_MAXIMUM_LEDGE_DROP,
      maximumRise: LAVA_ESCAPE_MAXIMUM_SWIMMABLE_RISE,
    });
    if (ledges.length > 0) {
      const escaped = yield* swimDirectlyToDrySurfaceCandidates(
        state,
        ledges.slice(0, 2),
        LAVA_ESCAPE_MAXIMUM_APPROACH_ATTEMPTS,
      );
      latest = yield* state.driver.observe;
      if (
        escaped ||
        latest.player.dead ||
        !(yield* isPlayerInLava(state.driver, latest.player.position))
      ) {
        return;
      }
    }

    const lava = yield* state.driver.queryBlocks({
      center: latest.player.position,
      radius: 6,
      selector: { blockIds: ["minecraft:lava"] },
      maximumResults: 16,
    });
    const nearest = [...lava].toSorted(
      (left, right) =>
        distanceSquared(left.position, latest.player.position) -
        distanceSquared(right.position, latest.player.position),
    )[0];
    const away =
      nearest === undefined
        ? {
            ...latest.player.position,
            x: latest.player.position.x + LAVA_RETREAT_DISTANCE,
          }
        : positionAwayFrom(latest.player.position, nearest.position, LAVA_RETREAT_DISTANCE);
    const rotation = rotationToward(latest.player.position, away);
    yield* state.driver.withControl(
      Effect.gen(function* () {
        yield* state.driver.act({
          type: "look",
          yaw: rotation.yaw,
          pitch: -20,
        });
        yield* state.driver.act({
          type: "set-movement",
          forward: true,
          jump: true,
          sprint: true,
        });
        yield* Effect.sleep(LAVA_EMERGENCY_SPRINT_MS);
      }).pipe(Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
    );
  });
}

function positionAwayFrom(
  position: BeatGamePosition,
  hazard: BeatGamePosition,
  distance: number,
): BeatGamePosition {
  const deltaX = position.x - hazard.x;
  const deltaZ = position.z - hazard.z;
  const horizontalDistance = Math.hypot(deltaX, deltaZ);
  const directionX = horizontalDistance > 0.001 ? deltaX / horizontalDistance : 1;
  const directionZ = horizontalDistance > 0.001 ? deltaZ / horizontalDistance : 0;
  return {
    ...position,
    x: position.x + directionX * distance,
    z: position.z + directionZ * distance,
  };
}

function retreatAndRecover(
  state: RunState,
  recoveryDurationMs = RECOVERY_DURATION_MS,
  options: {
    readonly preserveFoodBelowCount?: number;
  } = {},
): Effect.Effect<void, BeatGameDriverError> {
  const escapePath = {
    ...state.strategy.path,
    sprint: true,
    allowMining: false,
    allowPlacing: false,
    avoidFluids: true,
    maxFallDistance: Math.min(
      state.strategy.path.maxFallDistance,
      MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
    ),
    maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 3000),
  };
  const fleeFromNearbyNeutralThreat = state.driver.observe.pipe(
    Effect.flatMap((observation) => {
      if (observation.player.dead || observation.player.health >= state.strategy.minimumHealth) {
        return Effect.void;
      }
      return state.driver
        .queryEntities({
          origin: observation.player.position,
          radius: 24,
          selector: {
            entityTypes: DANGEROUS_NEUTRAL_ENTITY_TYPES,
            alive: true,
          },
          maximumResults: 8,
        })
        .pipe(
          Effect.flatMap((entities) => {
            const threat = entities.find((entity) => isAggressiveNeutralMob(entity));
            if (threat === undefined) {
              return Effect.void;
            }
            // An enderman teleports after a fleeing bot, so the same escape
            // as in a fight applies: water nearby, or cover in place.
            if (threat.entityType === "minecraft:enderman") {
              return escapeFromTarget(state, threat);
            }
            const groupAggro = hasNearbyGroupAggroMember(threat, entities);
            // A spider outruns the bot. A live run fled one here with a sword
            // in hand and took seven bites without striking back, so a lone
            // fast pursuer gets the monitored escape that fights when caught.
            if (!groupAggro && FAST_MELEE_PURSUER_ENTITY_TYPES.has(threat.entityType)) {
              return escapeFromTarget(state, threat);
            }
            const selector = groupAggro
              ? ({ categories: [2], alive: true } as const)
              : ({ networkId: threat.networkId, alive: true } as const);
            return flee(state.driver, {
              selector,
              triggerRadius: 24,
              safeDistance: 32,
              completeWhenSafe: true,
              maximumEscapes: 2,
              path: escapePath,
            });
          }),
        );
    }),
    Effect.catchTag("BeatGameDriverError", () => Effect.void),
  );
  const recoveryPollMs = Math.max(
    MINIMUM_RECOVERY_POLL_MS,
    Math.min(MAXIMUM_RECOVERY_POLL_MS, state.strategy.observationPollMs),
  );
  const recoverUntilSafe = (attemptsRemaining: number): Effect.Effect<void, BeatGameDriverError> =>
    state.driver.observe.pipe(
      Effect.flatMap((observation) => {
        if (observation.player.dead || attemptsRemaining === 0) {
          return Effect.void;
        }
        if (hasUnsafeAir(observation)) {
          return emergencyAirAscent(state, observation.player.position).pipe(
            Effect.andThen(recoverUntilSafe(attemptsRemaining - 1)),
          );
        }
        return findNearbyAttackThreat(state, observation).pipe(
          Effect.flatMap((threat) => {
            if (threat === undefined) {
              return recoverWithoutNearbyThreat(observation, attemptsRemaining);
            }
            return respondToRecoveryThreatWithSupervision(state, threat).pipe(
              Effect.andThen(Effect.sleep(recoveryPollMs)),
              Effect.andThen(recoverUntilSafe(attemptsRemaining - 1)),
            );
          }),
        );
      }),
    );
  // A live run fled a blaze, then stood eating in the open while it and the
  // blazes behind it kept firing from about 21 blocks, past the nearby
  // threat check, until it burned to death. A recent hit from a shooter
  // that far sends the bot into cover, or into the fight when it cannot.
  const recoverWithoutNearbyThreat = (
    observation: BeatGameObservation,
    attemptsRemaining: number,
  ): Effect.Effect<void, BeatGameDriverError> =>
    findDistantRangedAttacker(state, observation).pipe(
      Effect.flatMap((shooter) =>
        shooter === undefined
          ? Effect.succeed(false)
          : takeCoverFromRangedFire(state, observation, shooter.target).pipe(
              Effect.flatMap((covered) =>
                covered ? Effect.void : respondToRecoveryThreatWithSupervision(state, shooter),
              ),
              Effect.as(true),
            ),
      ),
      Effect.flatMap((answered) => {
        if (answered) {
          return Effect.sleep(recoveryPollMs).pipe(
            Effect.andThen(recoverUntilSafe(attemptsRemaining - 1)),
          );
        }
        if (observation.player.health >= state.strategy.minimumHealth) {
          return Effect.void;
        }
        if (observation.player.food < 18 && !hasUsableFood(observation)) {
          return Effect.void;
        }
        if (observation.player.food < 20 && hasUsableFood(observation)) {
          if (
            options.preserveFoodBelowCount !== undefined &&
            observation.player.health > LETHAL_MELEE_DISENGAGE_HEALTH &&
            deathRecoveryTravelFoodCount(observation) < options.preserveFoodBelowCount
          ) {
            return Effect.void;
          }
          return eatUnlessEndangered(state, {
            foodItemIds: preferredUsableFoodItemIds(observation),
            foodLevel: 20,
            maximumMeals: 1,
            completeWhenNoFood: true,
            path: state.strategy.path,
          }).pipe(
            Effect.andThen(Effect.sleep(recoveryPollMs)),
            Effect.andThen(recoverUntilSafe(attemptsRemaining - 1)),
          );
        }
        return Effect.sleep(recoveryPollMs).pipe(
          Effect.andThen(recoverUntilSafe(attemptsRemaining - 1)),
        );
      }),
    );
  const escapeEnvironmentalHazard = state.driver.observe.pipe(
    Effect.flatMap((observation) => extinguishFire(state, observation)),
    Effect.andThen(state.driver.observe),
    Effect.flatMap((observation) => escapeLava(state, observation)),
  );
  return escapeEnvironmentalHazard.pipe(
    Effect.andThen(fleeFromNearbyNeutralThreat),
    Effect.andThen(recoverUntilSafe(Math.ceil(recoveryDurationMs / recoveryPollMs))),
  );
}

function respondToRecoveryThreatWithSupervision(
  state: RunState,
  threat: ImmediateThreat,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.raceFirst(
    respondToRecoveryThreat(state, threat),
    monitorCompetingRecoveryThreat(state, threat.target).pipe(
      Effect.flatMap((competingThreat) =>
        respondToRecoveryThreatWithSupervision(state, competingThreat),
      ),
    ),
  );
}

function respondToRecoveryThreat(
  state: RunState,
  threat: ImmediateThreat,
): Effect.Effect<void, BeatGameDriverError> {
  const escaping = threat.response === "flee";
  const response = escaping
    ? escapeFromTarget(state, threat.target)
    : defendAgainstTarget(state, threat.target);
  return response.pipe(
    Effect.catchTag("BeatGameDriverError", (error) => {
      if (escaping) {
        if (
          ESCAPE_ONLY_DEFENSIVE_ENTITY_TYPES.has(threat.target.entityType) ||
          GROUP_AGGRO_ENTITY_TYPES.has(threat.target.entityType)
        ) {
          return Effect.void;
        }
        return defendAgainstTarget(state, threat.target).pipe(
          Effect.catchTag("BeatGameDriverError", () => Effect.void),
        );
      }
      if (
        error.operation === "task.attack-entity" ||
        error.operation === "task.attack-nearest" ||
        error.code === "not_found" ||
        error.code === "unreachable"
      ) {
        return recoverFromFailedDefense(state, threat.target).pipe(
          Effect.catchTag("BeatGameDriverError", () => Effect.void),
        );
      }
      return Effect.fail(error);
    }),
  );
}

function monitorCompetingRecoveryThreat(
  state: RunState,
  activeTarget: BeatGameEntityObservation,
): Effect.Effect<ImmediateThreat, BeatGameDriverError> {
  const poll = (): Effect.Effect<ImmediateThreat, BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) => {
        if (observation.player.dead) {
          return Effect.never;
        }
        return findImmediateThreat(state, observation).pipe(
          Effect.flatMap((threat) => {
            if (threat === undefined || isSameEntityTarget(threat.target, activeTarget)) {
              return Effect.suspend(poll);
            }
            return state.driver
              .queryEntities({
                origin: observation.player.position,
                radius: RANGED_THREAT_ESCAPE_SAFE_DISTANCE,
                selector: {
                  networkId: activeTarget.networkId,
                  alive: true,
                },
                maximumResults: 1,
              })
              .pipe(
                Effect.flatMap(([currentActiveTarget]) => {
                  if (currentActiveTarget === undefined) {
                    return Effect.succeed(threat);
                  }
                  const competingDistanceSquared = distanceSquared(
                    observation.player.position,
                    threat.target.position,
                  );
                  const activeDistanceSquared = distanceSquared(
                    observation.player.position,
                    currentActiveTarget.position,
                  );
                  const closeMeleeThreat =
                    PROACTIVE_MELEE_HOSTILE_ENTITY_TYPES.has(threat.target.entityType) &&
                    !PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(threat.target.entityType) &&
                    competingDistanceSquared <= MELEE_ENGAGEMENT_RADIUS ** 2;
                  const shouldPreempt =
                    isFusingCreeper(threat.target) ||
                    (closeMeleeThreat &&
                      (PROACTIVE_RANGED_HOSTILE_ENTITY_TYPES.has(currentActiveTarget.entityType) ||
                        competingDistanceSquared + 1 < activeDistanceSquared));
                  return shouldPreempt ? Effect.succeed(threat) : Effect.suspend(poll);
                }),
              );
          }),
        );
      }),
    );
  return Effect.suspend(poll);
}

function policyContextFor(
  state: RunState,
  observation: BeatGameObservation,
  checkpoint: BeatGameCheckpoint,
): BeatGamePolicyContext {
  return {
    driver: state.driver,
    checkpoint,
    observation,
    strategy: state.strategy,
  };
}

function requirementActionResult(
  requirement: BeatGameItemRequirement,
  before: BeatGameObservation,
  after: BeatGameObservation,
): ActionResult {
  const afterRequirementCount = requirementCount(after.inventory, requirement);
  const requirementProgressed =
    afterRequirementCount > requirementCount(before.inventory, requirement);
  const inventoryChanged = inventoryCountsChanged(before.inventory.counts, after.inventory.counts);
  const moved =
    before.player.position.dimension !== after.player.position.dimension ||
    distanceSquared(before.player.position, after.player.position) >= 0.25;
  if (afterRequirementCount >= requirement.targetCount) {
    return {};
  }
  if (requirementProgressed || inventoryChanged || moved) {
    return {
      continueReason: requirementProgressed
        ? `collected ${requirement.key}, but the requirement remains incomplete`
        : `advanced the search for ${requirement.key}`,
    };
  }
  return {
    replanReason: `no observable progress while satisfying ${requirement.key}`,
    replanDelayMs: REQUIREMENT_NO_PROGRESS_REPLAN_DELAY_MS,
  };
}

function inventoryCountsChanged(
  before: Readonly<Record<string, number>>,
  after: Readonly<Record<string, number>>,
): boolean {
  const itemIds = new Set([...Object.keys(before), ...Object.keys(after)]);
  return [...itemIds].some((itemId) => (before[itemId] ?? 0) !== (after[itemId] ?? 0));
}

function satisfyRequirement(
  state: RunState,
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return recoverNearbyRequirementDrops(state, requirement, observation).pipe(
    Effect.flatMap((recovered) =>
      recovered ? Effect.void : satisfyRequirementFromWorld(state, requirement, observation),
    ),
  );
}

function recoverNearbyRequirementDrops(
  state: RunState,
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  const foodRequirement = requirement.key === "food" || requirement.key === "food-supply";
  const shouldCrossFluids =
    foodRequirement &&
    (observation.player.health >= state.strategy.minimumHealth ||
      (observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL &&
        observation.player.health > LETHAL_MELEE_DISENGAGE_HEALTH));
  const maximumVerticalDistance =
    foodRequirement && observation.player.food <= URGENT_HUNGER_FOOD_LEVEL
      ? URGENT_FOOD_DROP_MAXIMUM_VERTICAL_DISTANCE
      : NEARBY_REQUIREMENT_DROP_MAXIMUM_VERTICAL_DISTANCE;
  const itemIds = [
    ...new Set(
      requirement.key === "food"
        ? [...requirement.itemIds, ...Object.keys(RAW_FOOD_TO_COOKED), ...EMERGENCY_FOOD_ITEM_IDS]
        : requirement.itemIds,
    ),
  ];
  if (itemIds.length === 0) {
    return Effect.succeed(false);
  }
  const itemIdSet = new Set(itemIds);
  const count = (value: BeatGameObservation): number =>
    itemIds.reduce((total, itemId) => total + (value.inventory.counts[itemId] ?? 0), 0);
  const before = count(observation);
  return state.driver
    .queryEntities({
      origin: observation.player.position,
      radius: NEARBY_REQUIREMENT_DROP_RADIUS,
      selector: {
        entityTypes: ["minecraft:item"],
        alive: true,
      },
      maximumResults: 32,
    })
    .pipe(
      Effect.flatMap((entities) =>
        entities.some(
          (entity) =>
            entity.itemId !== undefined &&
            itemIdSet.has(entity.itemId) &&
            Math.abs(entity.position.y - observation.player.position.y) <= maximumVerticalDistance,
        )
          ? collectNearbyDrops(state.driver, {
              itemIds,
              radius: NEARBY_REQUIREMENT_DROP_RADIUS,
              maximumDrops: 32,
              settleDelayMs: 0,
              maximumVerticalDistance,
              path: {
                ...state.strategy.path,
                allowPlacing: false,
                avoidFluids: !shouldCrossFluids,
              },
            }).pipe(
              Effect.andThen(state.driver.observe),
              Effect.map((current) => count(current) > before),
            )
          : Effect.succeed(false),
      ),
    );
}

function satisfyBedRequirement(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  const craftableBed = craftableBedItemId(observation);
  if (craftableBed !== undefined) {
    return craftWithTable(state, observation, craftableBed, 1);
  }
  return Effect.gen(function* () {
    // Villages have beds, and taking one is far faster than shearing sheep.
    const placedBeds = (yield* state.driver.queryBlocks({
      center: observation.player.position,
      radius: state.strategy.blockSearchRadius,
      selector: { blockIds: BED_ITEM_IDS },
      maximumResults: 1,
    })).filter(({ blockId }) => BED_ITEM_IDS.includes(blockId));
    if (placedBeds.length > 0) {
      const taken = yield* collectBlocks(state.driver, {
        blockIds: BED_ITEM_IDS,
        count: 1,
        searchRadius: state.strategy.blockSearchRadius,
        path: state.strategy.path,
      }).pipe(Effect.result);
      if (taken._tag === "Success") {
        return;
      }
    }
    yield* huntSheepForBed(state, observation);
  });
}

function huntSheepForBed(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  // A bed needs three wool of one color. Each sheep drops one wool of its own
  // color, so the bot hunts the color it already holds most of, and only
  // unsheared sheep, which still drop wool.
  const targetWool = WOOL_ITEM_IDS.reduce<{ itemId?: string; count: number }>(
    (best, itemId) => {
      const count = observation.inventory.counts[itemId] ?? 0;
      return count > best.count ? { itemId, count } : best;
    },
    { count: 0 },
  );
  const targetColor = targetWool.itemId?.replace(/^minecraft:/u, "").replace(/_wool$/u, "");
  return huntOrExplore(
    state,
    observation,
    { entityTypes: ["minecraft:sheep"], alive: true },
    Math.max(1, BED_WOOL_COUNT - targetWool.count),
    BED_SHEEP_SEARCH_PURPOSE,
    {
      preferredEntityTypes: new Set(["minecraft:sheep"]),
      preferredRadius: state.strategy.entitySearchRadius,
      maximumExplorationHops: 1,
      dropItemIds: targetWool.itemId === undefined ? WOOL_ITEM_IDS : [targetWool.itemId],
      // A server that does not report sheep details leaves every sheep eligible.
      acceptsTarget: ({ sheep }) =>
        sheep === undefined ||
        (!sheep.sheared && (targetColor === undefined || sheep.color === targetColor)),
      path: state.strategy.path,
    },
  );
}

function satisfyRequirementFromWorld(
  state: RunState,
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  const missing = Math.max(1, requirement.targetCount - requirement.currentCount);
  switch (requirement.key) {
    case "food-supply": {
      return satisfyFoodSupplyRequirement(state, requirement, observation);
    }
    case "food": {
      return satisfyFoodRequirement(state, requirement, observation);
    }
    case "logs": {
      return findNearestTreeColumn(state, observation.player.position).pipe(
        Effect.flatMap((explorationTarget) =>
          collectBlocksOrExplore(state, observation, {
            blockIds: requirement.itemIds,
            tags: requirement.tags,
            count: bufferedCollectionCount("logs", missing),
            progressItemIds: requirement.itemIds,
            purpose: "find-logs",
            avoidSubmergedTargets: true,
            requireSurfaceTargets: true,
            ...(explorationTarget === undefined ? {} : { explorationTarget }),
          }),
        ),
      );
    }
    case "cobblestone": {
      return collectBlocksOrExplore(state, observation, {
        blockIds: ["minecraft:stone"],
        count: bufferedCollectionCount("cobblestone", missing),
        progressItemIds: ["minecraft:cobblestone"],
        purpose: "find-stone",
        avoidSubmergedTargets: true,
        avoidFluids: true,
        prepareAttempt: (current) =>
          ensureMiningPickaxe(state, current, "minecraft:wooden_pickaxe", MINING_PICKAXE_ITEM_IDS),
      }).pipe(Effect.andThen(digForCobblestoneWithoutProgress(state, observation)));
    }
    case "bed":
    case "dragon-beds": {
      return satisfyBedRequirement(state, observation);
    }
    case "basic-melee-weapon": {
      return craftWithTable(state, observation, "minecraft:wooden_sword", 1);
    }
    case "melee-weapon": {
      return ensureWoodForCraftingTable(state, observation).pipe(
        Effect.andThen(state.driver.observe),
        Effect.flatMap((current) => craftWithTable(state, current, "minecraft:stone_sword", 1)),
      );
    }
    case "obsidian": {
      return acquire(state.driver, requirement, {
        searchRadius: state.strategy.blockSearchRadius,
        path: state.strategy.path,
      });
    }
    case "iron": {
      return satisfyIronRequirement(state, observation, missing);
    }
    case "pickaxe": {
      return ensureWoodForCraftingTable(state, observation).pipe(
        Effect.andThen(state.driver.observe),
        Effect.flatMap((current) =>
          craftWithTable(
            state,
            current,
            (current.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3
              ? "minecraft:iron_pickaxe"
              : "minecraft:stone_pickaxe",
            1,
          ),
        ),
      );
    }
    case "diamond-pickaxe": {
      const diamonds = observation.inventory.counts["minecraft:diamond"] ?? 0;
      if (diamonds < 3) {
        return collectBlocks(state.driver, {
          blockIds: ["minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"],
          count: bufferedCollectionCount("diamond", 3 - diamonds),
          searchRadius: state.strategy.blockSearchRadius,
          path: state.strategy.path,
        });
      }
      return craftWithTable(state, observation, "minecraft:diamond_pickaxe", 1);
    }
    case "water-bucket": {
      return fillLiquidBucket(state, observation, "water");
    }
    case "lava-bucket": {
      return fillLiquidBucket(state, observation, "lava");
    }
    case "ignition": {
      return ensureIgnition(state, observation);
    }
    case "shield": {
      return craftWithTable(state, observation, "minecraft:shield", 1);
    }
    case "chestplate": {
      return craftIronArmor(state, observation, "minecraft:iron_chestplate", 8);
    }
    case "helmet": {
      return craftIronArmor(state, observation, "minecraft:iron_helmet", 5);
    }
    case "blaze-rods": {
      if (
        isNether(observation.player.position.dimension) &&
        !hasMiningPickaxeReserve(
          observation,
          MINING_PICKAXE_ITEM_IDS,
          NETHER_EXPLORATION_PICKAXE_DURABILITY_RESERVE,
        )
      ) {
        return ensureNetherExplorationPickaxe(state, observation);
      }
      if (
        isNether(observation.player.position.dimension) &&
        pathBuildingBlockCount(observation) < NETHER_EXPLORATION_MINIMUM_BUILDING_BLOCKS
      ) {
        return collectBlocksOrExplore(state, observation, {
          blockIds: NETHER_EXPLORATION_BUILDING_BLOCK_IDS,
          count: NETHER_EXPLORATION_TARGET_BUILDING_BLOCKS - pathBuildingBlockCount(observation),
          progressItemIds: NETHER_EXPLORATION_BUILDING_BLOCK_IDS,
          purpose: "replenish-nether-building-blocks",
          avoidFluids: true,
          preferSurfaceExploration: false,
          path: {
            ...state.strategy.path,
            allowPlacing: false,
          },
        });
      }
      return Ref.get(state.checkpoint).pipe(
        Effect.flatMap((checkpoint) => {
          const explorationTarget = rememberedFortressTarget(
            checkpoint.memory.places,
            observation.player.position,
          );
          return huntOrExplore(
            state,
            observation,
            { entityTypes: ["minecraft:blaze"], alive: true },
            bufferedCollectionCount("blaze-rods", missing),
            "find-nether-fortress",
            {
              preferredEntityTypes: new Set(["minecraft:blaze"]),
              preferredRadius: state.strategy.entitySearchRadius,
              maximumExplorationHops: 64,
              path: state.strategy.path,
              ...(explorationTarget === undefined ? {} : { explorationTarget }),
              structureSearch: {
                anchorBlockIds: new Set(NETHER_FORTRESS_BLOCK_IDS),
                stationaryBlockIds: new Set(["minecraft:spawner"]),
                placeKind: "FORTRESS",
                scanRadius: NETHER_WIDE_SEARCH_RADIUS,
                maximumResults: 2048,
              },
            },
          );
        }),
      );
    }
    case "ender-pearls": {
      return acquireEnderPearls(
        state,
        observation,
        bufferedCollectionCount("ender-pearls", missing),
      );
    }
    case "gold-boots": {
      return craftGoldenBoots(state, observation);
    }
    case "gold": {
      return leavePiglinsBeforeMiningGold(state, observation).pipe(
        Effect.flatMap((moved) =>
          moved
            ? Effect.void
            : collectBlocks(state.driver, {
                blockIds: [
                  "minecraft:nether_gold_ore",
                  "minecraft:gold_ore",
                  "minecraft:deepslate_gold_ore",
                ],
                count: bufferedCollectionCount("gold", missing),
                searchRadius: state.strategy.blockSearchRadius,
                path: state.strategy.path,
              }),
        ),
      );
    }
    case "eyes-of-ender": {
      // Thrown eyes break about one time in five. A live fast run crafted
      // exactly twelve, lost one locating the stronghold, and retried the
      // craft 1146 times with no pearl left. Missing pearls are hunted again.
      const pearls = observation.inventory.counts["minecraft:ender_pearl"] ?? 0;
      if (pearls < missing) {
        return acquireEnderPearls(state, observation, missing - pearls);
      }
      return craftItem(state.driver, {
        resultItemId: "minecraft:ender_eye",
        count: missing,
        path: state.strategy.path,
      });
    }
    case "ranged-weapon": {
      return ensureString(state, observation, 3).pipe(
        Effect.andThen(craftWithTable(state, observation, "minecraft:bow", 1)),
      );
    }
    case "arrows": {
      return ensureArrowIngredients(state, observation, missing).pipe(
        Effect.andThen(craftWithTable(state, observation, "minecraft:arrow", missing)),
      );
    }
    case "torch": {
      return craftItem(state.driver, {
        resultItemId: "minecraft:torch",
        count: missing,
        path: state.strategy.path,
      });
    }
    default: {
      return acquire(state.driver, requirement, {
        searchRadius: state.strategy.blockSearchRadius,
        path: state.strategy.path,
      }).pipe(
        Effect.catchTag("BeatGameDriverError", (cause) =>
          Effect.fail(
            new BeatGameRequirementError({
              runId: "",
              instanceId: state.driver.instanceId,
              botId: state.driver.botId,
              phase: BeatGamePhase.PREPARE_OVERWORLD,
              action: `satisfy:${requirement.key}`,
              retryable: cause.retryable,
              message: `No acquisition strategy succeeded for ${requirement.key}`,
              requirement: requirement.key,
              cause,
            }),
          ),
        ),
      );
    }
  }
}

function pathBuildingBlockCount(observation: BeatGameObservation): number {
  return (
    observation.inventory.pathBuildingBlockCount ??
    [
      "minecraft:andesite",
      "minecraft:blackstone",
      "minecraft:basalt",
      "minecraft:cobbled_deepslate",
      "minecraft:cobblestone",
      "minecraft:coarse_dirt",
      "minecraft:diorite",
      "minecraft:dirt",
      "minecraft:granite",
      "minecraft:netherrack",
    ].reduce((total, itemId) => total + (observation.inventory.counts[itemId] ?? 0), 0)
  );
}

function ensureNetherExplorationPickaxe(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  const ironIngots = observation.inventory.counts["minecraft:iron_ingot"] ?? 0;
  const stoneToolMaterials = STONE_TOOL_MATERIAL_ITEM_IDS.reduce(
    (total, itemId) => total + (observation.inventory.counts[itemId] ?? 0),
    0,
  );
  if (ironIngots >= 3) {
    return craftNetherPickaxe(state, observation, "minecraft:iron_pickaxe", 1);
  }
  // An iron pickaxe lasts about twice as long as stone. A live fortress
  // search carried seven raw iron while it broke five stone pickaxes in half
  // an hour and kept leaving the search to find stems for new handles.
  const rawIron = observation.inventory.counts["minecraft:raw_iron"] ?? 0;
  const fuel =
    (observation.inventory.counts["minecraft:coal"] ?? 0) +
    (observation.inventory.counts["minecraft:charcoal"] ?? 0);
  if (ironIngots + rawIron >= 3 && fuel > 0) {
    return satisfyIronRequirement(state, observation, 3 - ironIngots).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((current) =>
        (current.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3
          ? craftNetherPickaxe(state, current, "minecraft:iron_pickaxe", 1)
          : Effect.void,
      ),
    );
  }
  if (stoneToolMaterials >= 3) {
    // A live fortress search wore out a stone pickaxe every three minutes,
    // so spare ones save a trip back to the crafting table each time.
    return craftNetherPickaxe(
      state,
      observation,
      "minecraft:stone_pickaxe",
      Math.min(NETHER_STONE_PICKAXE_BATCH, Math.floor(stoneToolMaterials / 3)),
    );
  }
  const reportedDurability = observation.inventory.remainingDurability;
  const remainingDurability =
    reportedDurability === undefined
      ? 0
      : MINING_PICKAXE_ITEM_IDS.reduce(
          (total, itemId) => total + (reportedDurability[itemId] ?? 0),
          0,
        );
  const woodenPickaxes = Math.max(
    1,
    Math.ceil(
      (NETHER_EXPLORATION_PICKAXE_DURABILITY_RESERVE - remainingDurability) /
        WOODEN_PICKAXE_MAXIMUM_DURABILITY,
    ),
  );
  return craftNetherPickaxe(state, observation, "minecraft:wooden_pickaxe", woodenPickaxes);
}

const NETHER_STEM_ITEM_IDS = ["minecraft:crimson_stem", "minecraft:warped_stem"] as const;

// The Nether has no trees, so a pickaxe crafted there must be paid for with
// carried wood or with stems. Without enough wood the craft fails on every
// attempt, so the bot collects stems first.
function craftNetherPickaxe(
  state: RunState,
  observation: BeatGameObservation,
  resultItemId: "minecraft:wooden_pickaxe" | "minecraft:stone_pickaxe" | "minecraft:iron_pickaxe",
  count: number,
): Effect.Effect<void, BeatGameDriverError> {
  const counts = observation.inventory.counts;
  const total = (itemIds: readonly string[]) =>
    itemIds.reduce((sum, itemId) => sum + (counts[itemId] ?? 0), 0);
  const planksNeeded = (pickaxes: number) =>
    ((counts["minecraft:crafting_table"] ?? 0) > 0 ? 0 : 4) +
    2 * Math.ceil(Math.max(0, 2 * pickaxes - (counts["minecraft:stick"] ?? 0)) / 4) +
    (resultItemId === "minecraft:wooden_pickaxe" ? 3 * pickaxes : 0);
  const planksAvailable = total(PLANK_ITEM_IDS) + 4 * total(LOG_ITEM_IDS);
  // Carried wood that pays for fewer pickaxes still pays for those now.
  const affordable = Array.from({ length: count }, (_, index) => count - index).find(
    (pickaxes) => planksNeeded(pickaxes) <= planksAvailable,
  );
  if (affordable !== undefined) {
    return craftWithTable(state, observation, resultItemId, affordable);
  }
  return collectBlocksOrExplore(state, observation, {
    blockIds: NETHER_STEM_ITEM_IDS,
    // Spare stems pay for the next batch. A live bot used its last wood on
    // pickaxes and then spent an hour without any in a basalt delta.
    count: Math.ceil((planksNeeded(count) - planksAvailable) / 4) + NETHER_STEM_RESERVE,
    progressItemIds: NETHER_STEM_ITEM_IDS,
    purpose: "find-nether-stems",
    avoidFluids: true,
    // Nether forests are far apart. A live bot without wood searched the floor
    // of a basalt delta in 24-block hops for half an hour.
    searchRadius: NETHER_WIDE_SEARCH_RADIUS,
    // The Nether has no surface to walk along. A live bot in a basalt
    // delta could not leave its cave without digging and looped for an hour.
    preferSurfaceExploration: false,
  });
}

function satisfyFoodSupplyRequirement(
  state: RunState,
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return recoverNearbyFurnaceContents(state, observation).pipe(
    Effect.flatMap(({ observation: current, recovered }) => {
      if (recovered) {
        return Effect.void;
      }
      const currentFoodCount = requirementCount(current.inventory, requirement);
      const missing = Math.max(0, requirement.targetCount - currentFoodCount);
      if (missing === 0) {
        return Effect.void;
      }
      return tryVillageBread(state, current).pipe(
        Effect.flatMap((baked) =>
          baked ? Effect.succeed(true) : tryForageNearbyFood(state, current),
        ),
        Effect.flatMap((foraged) =>
          foraged
            ? Effect.void
            : tryFishForFood(state, current).pipe(
                Effect.flatMap((fished) =>
                  fished
                    ? Effect.void
                    : huntForFoodRequirement(
                        state,
                        current,
                        bufferedCollectionCount("food", missing),
                      ),
                ),
              ),
        ),
      );
    }),
  );
}

// Village hay bales give the fastest food: each one bakes into three bread.
const VILLAGE_HAY_BALE_LIMIT = 3;

function tryVillageBread(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const counts = observation.inventory.counts;
    const bread =
      3 * (counts["minecraft:hay_block"] ?? 0) + Math.floor((counts["minecraft:wheat"] ?? 0) / 3);
    if (bread > 0) {
      const baked = yield* craftWithTable(state, observation, "minecraft:bread", bread).pipe(
        Effect.result,
      );
      return baked._tag === "Success";
    }
    const bales = (yield* state.driver.queryBlocks({
      center: observation.player.position,
      radius: state.strategy.blockSearchRadius,
      selector: { blockIds: ["minecraft:hay_block"], requireLineOfSight: true },
      maximumResults: VILLAGE_HAY_BALE_LIMIT,
    })).filter(({ blockId }) => blockId === "minecraft:hay_block");
    if (bales.length === 0) {
      return false;
    }
    const collected = yield* collectBlocks(state.driver, {
      blockIds: ["minecraft:hay_block"],
      count: bales.length,
      searchRadius: state.strategy.blockSearchRadius,
      path: { ...state.strategy.path, avoidFluids: true },
    }).pipe(Effect.result);
    return collected._tag === "Success";
  });
}

const FORAGEABLE_FOOD_BLOCK_IDS = [
  "minecraft:beetroots",
  "minecraft:carrots",
  "minecraft:cave_vines",
  "minecraft:cave_vines_plant",
  "minecraft:melon",
  "minecraft:potatoes",
  "minecraft:sweet_berry_bush",
] as const;

function tryForageNearbyFood(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  const before = foodCount(observation);
  return state.driver
    .queryBlocks({
      center: observation.player.position,
      radius: state.strategy.blockSearchRadius,
      selector: {
        blockIds: FORAGEABLE_FOOD_BLOCK_IDS,
        requireLineOfSight: true,
      },
      maximumResults: 64,
    })
    .pipe(
      Effect.map(
        (blocks) =>
          blocks
            .filter((block) => isReadyForageBlock(block))
            .toSorted(
              (left, right) =>
                distanceSquared(left.position, observation.player.position) -
                distanceSquared(right.position, observation.player.position),
            )[0],
      ),
      Effect.flatMap((block) => {
        if (block === undefined) {
          return Effect.succeed(false);
        }
        const path = {
          ...survivalPathPolicy(
            state.strategy.path,
            observation.player.health,
            state.strategy.minimumHealth,
          ),
          allowPlacing: false,
          avoidFluids: true,
        };
        const harvest =
          block.blockId === "minecraft:sweet_berry_bush" ||
          block.blockId === "minecraft:cave_vines" ||
          block.blockId === "minecraft:cave_vines_plant"
            ? state.driver.act({
                type: "interact-block",
                position: block.position,
                face: "up",
              })
            : state.driver.act({
                type: "dig-block",
                position: block.position,
              });
        return state.driver.pathfind(block.position, 3, path).pipe(
          Effect.andThen(harvest),
          Effect.andThen(
            collectNearbyDrops(state.driver, {
              itemIds: EDIBLE_FOOD_ITEM_IDS,
              radius: 8,
              maximumDrops: 16,
              settleDelayMs: 250,
              path,
            }),
          ),
          Effect.andThen(state.driver.observe),
          Effect.map((current) => foodCount(current) > before),
        );
      }),
    );
}

function isReadyForageBlock(block: BeatGameBlockObservation): boolean {
  switch (block.blockId) {
    case "minecraft:beetroots": {
      return block.properties["age"] === "3";
    }
    case "minecraft:carrots":
    case "minecraft:potatoes": {
      return block.properties["age"] === "7";
    }
    case "minecraft:cave_vines":
    case "minecraft:cave_vines_plant": {
      return block.properties["berries"] === "true";
    }
    case "minecraft:melon": {
      return block.diggable;
    }
    case "minecraft:sweet_berry_bush": {
      return Number(block.properties["age"]) >= 2;
    }
    default: {
      return false;
    }
  }
}

function satisfyFoodRequirement(
  state: RunState,
  requirement: BeatGameItemRequirement,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  const rawFood = Object.entries(RAW_FOOD_TO_COOKED)
    .map(([rawItemId, cookedItemId]) => ({
      rawItemId,
      cookedItemId,
      count: observation.inventory.counts[rawItemId] ?? 0,
    }))
    .filter(({ count }) => count > 0);
  const rawFoodCount = rawFood.reduce((total, { count }) => total + count, 0);
  const rawFoodStillNeeded = Math.max(
    0,
    requirement.targetCount - requirement.currentCount - rawFoodCount,
  );
  if (
    rawFoodStillNeeded > 0 &&
    observation.player.food > state.strategy.eatBelowFood &&
    (observation.player.health >= state.strategy.minimumHealth || rawFoodCount === 0)
  ) {
    return recoverNearbyFurnaceContents(state, observation).pipe(
      Effect.flatMap(({ observation: current, recovered }) => {
        if (recovered) {
          return Effect.void;
        }
        const currentCookedFoodCount = requirement.itemIds.reduce(
          (total, itemId) => total + (current.inventory.counts[itemId] ?? 0),
          0,
        );
        const currentRawFoodCount = Object.keys(RAW_FOOD_TO_COOKED).reduce(
          (total, itemId) => total + (current.inventory.counts[itemId] ?? 0),
          0,
        );
        const remainingRawFood = Math.max(
          0,
          requirement.targetCount - currentCookedFoodCount - currentRawFoodCount,
        );
        if (remainingRawFood === 0) {
          return Effect.void;
        }
        return tryFishForFood(state, current).pipe(
          Effect.flatMap((fished) =>
            fished
              ? Effect.void
              : huntForFoodRequirement(
                  state,
                  current,
                  current.player.health >= state.strategy.minimumHealth
                    ? bufferedCollectionCount("food", remainingRawFood)
                    : 1,
                ),
          ),
        );
      }),
    );
  }
  if (rawFoodCount === 0) {
    return recoverNearbyFurnaceContents(state, observation).pipe(
      Effect.flatMap(({ observation: current, recovered }) => {
        if (recovered) {
          return Effect.void;
        }
        return tryFishForFood(state, current).pipe(
          Effect.flatMap((fished) =>
            fished ? Effect.void : huntForFoodRequirement(state, current, 1),
          ),
        );
      }),
    );
  }
  const batch = rawFood[0];
  if (batch === undefined) {
    return Effect.void;
  }
  if (observation.player.health < state.strategy.minimumHealth) {
    const eatRawFoodAndRecover = () =>
      eatUnlessEndangered(state, {
        foodItemIds: [batch.rawItemId],
        foodLevel: Math.max(18, state.strategy.eatBelowFood),
        maximumMeals: batch.count,
        completeWhenNoFood: true,
        path: state.strategy.path,
      }).pipe(Effect.andThen(retreatAndRecover(state)));
    return findReusableWorkstations(state.driver, observation, "minecraft:furnace").pipe(
      Effect.flatMap((furnaces) =>
        furnaces.length > 0 || (observation.inventory.counts["minecraft:furnace"] ?? 0) > 0
          ? cookRawFoodBatch(state, observation, batch).pipe(
              Effect.catchTag("BeatGameDriverError", (error) =>
                error.retryable && error.code !== "bot-dead"
                  ? eatRawFoodAndRecover()
                  : Effect.fail(error),
              ),
            )
          : eatRawFoodAndRecover(),
      ),
    );
  }
  return cookRawFoodBatch(state, observation, batch);
}

function huntForFoodRequirement(
  state: RunState,
  observation: BeatGameObservation,
  maximumTargets: number,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return huntOrExplore(
    state,
    observation,
    {
      entityTypes: foodHuntEntityTypes(
        observation.player.food,
        observation.player.health >= state.strategy.minimumHealth,
      ),
      alive: true,
    },
    maximumTargets,
    "find-food-animals",
    {
      preferredEntityTypes: HIGH_YIELD_FOOD_ANIMAL_TYPES,
      preferredRadius: HIGH_YIELD_FOOD_PREFERENCE_RADIUS,
      allowCriticalAquaticTargets: true,
      maximumSafeAquaticFoodLevel: Math.max(
        state.strategy.eatBelowFood,
        WOUNDED_AQUATIC_FALLBACK_FOOD_LEVEL,
      ),
      requireHealthRecoveryForSafeAquaticTargets: true,
      safeAquaticFallbackAfterExplorationLegs: SAFE_AQUATIC_FALLBACK_EXPLORATION_LEGS,
      maximumLandVerticalRise: FOOD_LAND_HUNT_MAXIMUM_VERTICAL_RISE,
      allowFluidFallback: observation.player.health >= state.strategy.minimumHealth,
      path: {
        ...state.strategy.path,
        avoidFluids: true,
      },
    },
  );
}

const caughtFoodCount = (value: BeatGameObservation): number =>
  (value.inventory.counts["minecraft:cod"] ?? 0) +
  (value.inventory.counts["minecraft:salmon"] ?? 0);
const countInventoryItems = (current: BeatGameObservation, itemIds: readonly string[]): number =>
  itemIds.reduce((count, itemId) => count + (current.inventory.counts[itemId] ?? 0), 0);
const inventoryLogCount = (value: BeatGameObservation): number =>
  LOG_ITEM_IDS.reduce((total, itemId) => total + (value.inventory.counts[itemId] ?? 0), 0);

function tryFishForFood(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    let current = observation;
    yield* ensureInventorySpace(state, current, RESOURCE_COLLECTION_RESERVED_SLOTS);
    current = yield* state.driver.observe;
    if (
      (current.inventory.counts["minecraft:fishing_rod"] ?? 0) === 0 &&
      ((current.inventory.counts["minecraft:string"] ?? 0) < 2 ||
        (current.inventory.counts["minecraft:stick"] ?? 0) < 3)
    ) {
      return false;
    }
    const caughtFoodBeforeCollection = caughtFoodCount(current);
    yield* collectNearbyDrops(state.driver, {
      itemIds: ["minecraft:cod", "minecraft:salmon"],
      radius: 8,
      maximumDrops: 16,
      settleDelayMs: 250,
      path: state.strategy.path,
    });
    current = yield* state.driver.observe;
    if (caughtFoodCount(current) > caughtFoodBeforeCollection) {
      return true;
    }
    let water = yield* queryFishingWater(state, current.player.position);
    if (water.length === 0) {
      return false;
    }
    if ((current.inventory.counts["minecraft:fishing_rod"] ?? 0) === 0) {
      const preparation = yield* Effect.gen(function* () {
        const workstation = yield* ensureWorkstation(state, current, "minecraft:crafting_table");
        yield* craftItem(state.driver, {
          resultItemId: "minecraft:fishing_rod",
          count: 1,
          station: workstation.position,
          path: state.strategy.path,
        });
      }).pipe(Effect.result);
      if (preparation._tag === "Failure") {
        if (preparation.failure.code === "resource-exhausted") {
          return false;
        }
        return yield* preparation.failure;
      }
      current = yield* state.driver.observe;
      if ((current.inventory.counts["minecraft:fishing_rod"] ?? 0) === 0) {
        return false;
      }
      water = yield* queryFishingWater(state, current.player.position);
      if (water.length === 0) {
        return false;
      }
    }
    const highestWaterByColumn = new Map<string, BeatGameBlockObservation>();
    for (const candidate of water) {
      const key = `${candidate.position.dimension}:${candidate.position.x}:${candidate.position.z}`;
      const previous = highestWaterByColumn.get(key);
      if (previous === undefined || candidate.position.y > previous.position.y) {
        highestWaterByColumn.set(key, candidate);
      }
    }
    const candidates = [...highestWaterByColumn.values()]
      .filter(
        ({ position }) =>
          position.dimension === current.player.position.dimension &&
          position.y >= current.player.position.y - 4 &&
          position.y <= current.player.position.y + 4,
      )
      .toSorted(
        (left, right) =>
          distanceSquared(left.position, current.player.position) -
          distanceSquared(right.position, current.player.position),
      )
      .slice(0, 8);
    for (const candidate of candidates) {
      const above = {
        ...candidate.position,
        y: candidate.position.y + 1,
      };
      const exposed = (yield* state.driver.queryBlocks({
        center: blockCenter(above),
        radius: 0.25,
        selector: { replaceable: true },
        maximumResults: 1,
      })).some(
        (block) =>
          sameBlockPosition(block.position, above) &&
          !(PLAYER_FLUID_BLOCK_IDS as readonly string[]).includes(block.blockId),
      );
      if (!exposed) {
        continue;
      }
      const waterTarget = blockCenter(candidate.position);
      const surfaceColumns = yield* state.driver.sampleSurface(
        waterTarget,
        FISHING_SHORE_SEARCH_RADIUS,
        1,
      );
      const playerPosition = current.player.position;
      const castingPositions = stableSurfaceColumns(surfaceColumns)
        .flatMap((surface) => {
          const position = {
            x: surface.x + 0.5,
            y: surface.surfaceY + 1,
            z: surface.z + 0.5,
            dimension: candidate.position.dimension,
          };
          const horizontalDistance = Math.hypot(
            position.x - waterTarget.x,
            position.z - waterTarget.z,
          );
          const withinCastingRange =
            horizontalDistance >= FISHING_MINIMUM_CAST_HORIZONTAL_DISTANCE &&
            distanceSquared(position, waterTarget) <= LIQUID_INTERACTION_REACH ** 2;
          return withinCastingRange ? [{ position, horizontalDistance }] : [];
        })
        .toSorted(
          (left, right) =>
            Math.abs(left.horizontalDistance - FISHING_PREFERRED_CAST_HORIZONTAL_DISTANCE) -
              Math.abs(right.horizontalDistance - FISHING_PREFERRED_CAST_HORIZONTAL_DISTANCE) ||
            distanceSquared(left.position, playerPosition) -
              distanceSquared(right.position, playerPosition),
        )
        .map(({ position }) => position);
      for (const castingPosition of castingPositions) {
        const approached = yield* state.driver
          .pathfind(castingPosition, DRY_SURFACE_APPROACH_RADIUS, {
            ...state.strategy.path,
            allowMining: false,
            avoidFluids: true,
          })
          .pipe(
            Effect.as(true),
            Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
          );
        if (!approached) {
          continue;
        }
        current = yield* state.driver.observe;
        if (yield* isPlayerInFluid(state.driver, current.player.position)) {
          yield* emergencyAirAscent(state, current.player.position, {
            attemptsRemaining: AIR_ESCAPE_MAXIMUM_RECOVERY_ATTEMPTS,
          });
          continue;
        }
        const eyePosition = {
          ...current.player.position,
          y: current.player.position.y + 1.62,
        };
        const target = {
          x: candidate.position.x + 0.5,
          y: candidate.position.y + 0.75,
          z: candidate.position.z + 0.5,
        };
        const targetRotation = rotationToward(eyePosition, target);
        const rotation = {
          ...targetRotation,
          pitch: Math.min(targetRotation.pitch, FISHING_MAXIMUM_DOWNWARD_CAST_PITCH),
        };
        yield* state.driver.act({
          type: "look",
          yaw: rotation.yaw,
          pitch: rotation.pitch,
        });
        yield* waitForViewRotation(state.driver, rotation.yaw, rotation.pitch, 40);
        yield* fish(state.driver, {
          maximumCatches: FISHING_COLLECTION_BATCH_SIZE,
          maximumFailedCasts: FISHING_MAXIMUM_FAILED_CASTS,
          path: state.strategy.path,
        });
        yield* Effect.yieldNow;
        yield* collectNearbyDrops(state.driver, {
          itemIds: ["minecraft:cod", "minecraft:salmon"],
          radius: 8,
          maximumDrops: 16,
          settleDelayMs: 500,
          path: state.strategy.path,
        });
        current = yield* state.driver.observe;
        if (caughtFoodCount(current) > caughtFoodBeforeCollection) {
          return true;
        }
        return false;
      }
    }
    return false;
  });
}

function queryFishingWater(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<readonly BeatGameBlockObservation[], BeatGameDriverError> {
  return state.driver.queryBlocks({
    center: position,
    radius: state.strategy.blockSearchRadius,
    selector: {
      blockIds: ["minecraft:water"],
      properties: { level: "0" },
    },
    maximumResults: 256,
  });
}

function foodHuntEntityTypes(
  foodLevel: number,
  allowHostileEmergencyFood: boolean,
): readonly string[] {
  return foodLevel <= 6 && allowHostileEmergencyFood
    ? [...FOOD_ANIMAL_ENTITY_TYPES, ...EMERGENCY_FOOD_ENTITY_TYPES]
    : FOOD_ANIMAL_ENTITY_TYPES;
}

function recoverNearbyFurnaceContents(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<
  {
    readonly observation: BeatGameObservation;
    readonly recovered: boolean;
  },
  BeatGameDriverError
> {
  return state.driver
    .queryBlocks({
      center: observation.player.position,
      radius: Math.min(FURNACE_RECOVERY_RADIUS, state.strategy.blockSearchRadius),
      selector: { blockIds: ["minecraft:furnace"] },
      maximumResults: 16,
    })
    .pipe(
      Effect.flatMap((furnaces) =>
        Ref.get(state.checkedRecoveryContainers).pipe(
          Effect.map(
            (checked) =>
              [...furnaces]
                .filter(
                  (furnace) =>
                    furnace.position.dimension === observation.player.position.dimension &&
                    distanceSquared(furnace.position, observation.player.position) <=
                      FURNACE_RECOVERY_RADIUS ** 2 &&
                    !checked.has(positionKey(furnace.position)),
                )
                .toSorted(
                  (left, right) =>
                    distanceSquared(left.position, observation.player.position) -
                    distanceSquared(right.position, observation.player.position),
                )[0],
          ),
        ),
      ),
      Effect.flatMap((furnace) => {
        if (furnace === undefined) {
          return Effect.succeed({ observation, recovered: false });
        }
        const initialItemCount = inventoryItemCount(observation);
        return Ref.update(
          state.checkedRecoveryContainers,
          (checked) => new Set([...checked, positionKey(furnace.position)]),
        ).pipe(
          Effect.andThen(
            transferContainerItems(state.driver, {
              direction: "withdraw",
              container: furnace.position,
              operations: [
                {
                  selector: {},
                  count: 192,
                  allowPartial: true,
                },
              ],
              path: state.strategy.path,
            }),
          ),
          Effect.andThen(state.driver.observe),
          Effect.map((current) => ({
            observation: current,
            recovered: inventoryItemCount(current) > initialItemCount,
          })),
          Effect.catchTag("BeatGameDriverError", () =>
            Effect.succeed({ observation, recovered: false }),
          ),
        );
      }),
    );
}

function inventoryItemCount(observation: BeatGameObservation): number {
  return Object.values(observation.inventory.counts).reduce((total, count) => total + count, 0);
}

function drainFurnaceContents(
  state: RunState,
  station: BeatGameBlockPosition,
): Effect.Effect<BeatGameObservation, BeatGameDriverError> {
  return transferContainerItems(state.driver, {
    direction: "withdraw",
    container: station,
    operations: [
      {
        selector: {},
        count: 192,
        allowPartial: true,
      },
    ],
    path: state.strategy.path,
  }).pipe(Effect.andThen(state.driver.observe));
}

function cookRawFoodBatch(
  state: RunState,
  observation: BeatGameObservation,
  batch: {
    readonly rawItemId: string;
    readonly cookedItemId: string;
    readonly count: number;
  },
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return ensureFurnaceForCooking(state, observation).pipe(
    Effect.flatMap((workstation) =>
      Ref.make(workstation).pipe(
        Effect.flatMap((activeWorkstation) =>
          drainFurnaceContents(state, workstation.position).pipe(
            Effect.flatMap((current) => {
              const currentRawFoodCount = current.inventory.counts[batch.rawItemId] ?? 0;
              const currentBatchCount = Math.min(currentRawFoodCount, batch.count);
              if (currentBatchCount === 0) {
                return Effect.void;
              }
              return preferDirectWoodFurnaceFuel(state, current, currentBatchCount).pipe(
                Effect.flatMap((directWoodFuel) =>
                  directWoodFuel !== undefined
                    ? smelt(state.driver, {
                        input: { itemIds: [batch.rawItemId] },
                        count: currentBatchCount,
                        fuel: { itemIds: directWoodFuel },
                        station: workstation.position,
                        path: state.strategy.path,
                      })
                    : ensureEfficientFurnaceFuel(
                        state,
                        current,
                        workstation,
                        currentBatchCount,
                      ).pipe(
                        Effect.tap((currentWorkstation) =>
                          Ref.set(activeWorkstation, currentWorkstation),
                        ),
                        Effect.flatMap((currentWorkstation) =>
                          smelt(state.driver, {
                            input: { itemIds: [batch.rawItemId] },
                            count: currentBatchCount,
                            fuel: {
                              itemIds: ["minecraft:coal", "minecraft:charcoal"],
                            },
                            station: currentWorkstation.position,
                            path: state.strategy.path,
                          }),
                        ),
                      ),
                ),
              );
            }),
            Effect.ensuring(
              Ref.get(activeWorkstation).pipe(
                Effect.flatMap((currentWorkstation) =>
                  reclaimPlacedFurnace(state, currentWorkstation),
                ),
                Effect.ignore,
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

function directWoodFurnaceFuelItemIds(
  observation: BeatGameObservation,
  outputCount: number,
): readonly string[] | undefined {
  if (furnaceFuelCount(observation) > 0) {
    return undefined;
  }
  const requiredWoodFuel = Math.ceil(outputCount / 1.5);
  if (requiredWoodFuel > 2) {
    return undefined;
  }
  const countItems = (itemIds: readonly string[]): number =>
    itemIds.reduce((count, itemId) => count + (observation.inventory.counts[itemId] ?? 0), 0);
  if (countItems(BURNABLE_PLANK_ITEM_IDS) >= requiredWoodFuel) {
    return BURNABLE_PLANK_ITEM_IDS;
  }
  return countItems(BURNABLE_LOG_ITEM_IDS) >= requiredWoodFuel ? BURNABLE_LOG_ITEM_IDS : undefined;
}

function preferDirectWoodFurnaceFuel(
  state: RunState,
  observation: BeatGameObservation,
  outputCount: number,
): Effect.Effect<readonly string[] | undefined, BeatGameDriverError> {
  const directWoodFuel = directWoodFurnaceFuelItemIds(observation, outputCount);
  return directWoodFuel === undefined
    ? Effect.undefined
    : queryNearbyCoal(state, observation, 1).pipe(
        Effect.map((coal) => (coal.length === 0 ? directWoodFuel : undefined)),
      );
}

function queryNearbyCoal(
  state: RunState,
  observation: BeatGameObservation,
  maximumResults: number,
): Effect.Effect<readonly BeatGameBlockObservation[], BeatGameDriverError> {
  return state.driver
    .queryBlocks({
      center: observation.player.position,
      radius: Math.min(FURNACE_FUEL_SEARCH_RADIUS, state.strategy.blockSearchRadius),
      selector: {
        blockIds: COAL_ORE_BLOCK_IDS,
        diggable: true,
      },
      maximumResults: Math.max(8, maximumResults * 4),
    })
    .pipe(
      Effect.flatMap((coal) =>
        Effect.forEach(
          coal,
          (block) =>
            state.driver
              .queryBlocks({
                center: blockCenter(block.position),
                radius: 1.1,
                selector: { blockIds: PLAYER_FLUID_BLOCK_IDS },
                maximumResults: 1,
              })
              .pipe(Effect.map((fluids) => ({ block, dry: fluids.length === 0 }))),
          { concurrency: 4 },
        ),
      ),
      Effect.map((coal) =>
        coal
          .filter(({ dry }) => dry)
          .map(({ block }) => block)
          .slice(0, maximumResults),
      ),
    );
}

function ensureFurnaceForCooking(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<PreparedWorkstation, BeatGameError | BeatGameDriverError> {
  return findReusableWorkstations(state.driver, observation, "minecraft:furnace").pipe(
    Effect.flatMap((furnaces) => {
      const cobblestoneCount = observation.inventory.counts["minecraft:cobblestone"] ?? 0;
      if (
        furnaces.length > 0 ||
        (observation.inventory.counts["minecraft:furnace"] ?? 0) > 0 ||
        cobblestoneCount >= 8
      ) {
        return ensureWorkstation(state, observation, "minecraft:furnace");
      }
      const missingCobblestone = 8 - cobblestoneCount;
      return collectBlocksOrExplore(state, observation, {
        blockIds: ["minecraft:stone"],
        count: bufferedCollectionCount("cobblestone", missingCobblestone),
        progressItemIds: ["minecraft:cobblestone"],
        purpose: "prepare-food-furnace",
        avoidSubmergedTargets: true,
        avoidFluids: true,
        prepareAttempt: (current) =>
          ensureMiningPickaxe(state, current, "minecraft:wooden_pickaxe", MINING_PICKAXE_ITEM_IDS),
      }).pipe(
        Effect.andThen(state.driver.observe),
        Effect.flatMap((current) => ensureWorkstation(state, current, "minecraft:furnace")),
      );
    }),
  );
}

function reclaimPlacedFurnace(
  state: RunState,
  workstation: PreparedWorkstation,
): Effect.Effect<void, BeatGameDriverError> {
  if (!workstation.placed) {
    return Effect.void;
  }
  const reclaim = (observation: BeatGameObservation) =>
    ensureMiningPickaxe(
      state,
      observation,
      "minecraft:wooden_pickaxe",
      MINING_PICKAXE_ITEM_IDS,
    ).pipe(
      Effect.andThen(
        state.driver.act({
          type: "select-item",
          selector: { itemIds: MINING_PICKAXE_ITEM_IDS },
        }),
      ),
      Effect.andThen(
        state.driver.act({
          type: "dig-block",
          position: workstation.position,
        }),
      ),
      Effect.andThen(
        collectNearbyDrops(state.driver, {
          itemIds: ["minecraft:furnace"],
          radius: 4,
          maximumDrops: 4,
          settleDelayMs: 500,
          path: state.strategy.path,
        }),
      ),
    );
  return state.driver.observe.pipe(
    Effect.flatMap((observation) =>
      // A live run stopped smelting for an approaching zombie, then dug up
      // and collected the furnace for ten seconds while the zombie hit it
      // from 17 health to 2. The furnace stays for later batches instead.
      findImmediateThreat(state, observation).pipe(
        Effect.flatMap((threat) => (threat === undefined ? reclaim(observation) : Effect.void)),
      ),
    ),
    // Reclaiming a temporary workstation is cleanup. A lost container drop or
    // control lease must not suspend the main planner indefinitely. This can
    // run as an ensuring finalizer, so restore interruptibility before racing
    // the cleanup against its deadline.
    Effect.interruptible,
    Effect.timeoutOrElse({ duration: FURNACE_RECLAIM_TIMEOUT, orElse: () => Effect.void }),
  );
}

function ensureEfficientFurnaceFuel(
  state: RunState,
  observation: BeatGameObservation,
  workstation: PreparedWorkstation,
  outputCount: number,
): Effect.Effect<PreparedWorkstation, BeatGameDriverError> {
  return Effect.gen(function* () {
    const requiredFuel = Math.ceil(outputCount / 8);
    let currentObservation = observation;
    let activeWorkstation = workstation;
    let missingFuel = Math.max(0, requiredFuel - furnaceFuelCount(currentObservation));
    if (missingFuel === 0) {
      return activeWorkstation;
    }

    currentObservation = yield* state.driver.observe;
    missingFuel = Math.max(0, requiredFuel - furnaceFuelCount(currentObservation));
    if (missingFuel === 0) {
      return activeWorkstation;
    }

    const nearbyCoal = yield* queryNearbyCoal(
      state,
      currentObservation,
      bufferedCollectionCount("fuel", missingFuel),
    );
    if (nearbyCoal.length > 0) {
      yield* collectBlocks(state.driver, {
        blockIds: COAL_ORE_BLOCK_IDS,
        count: bufferedCollectionCount("fuel", missingFuel),
        searchRadius: FURNACE_FUEL_SEARCH_RADIUS,
        avoidSubmergedTargets: true,
        path: { ...state.strategy.path, avoidFluids: true },
      });
      yield* collectNearbyDrops(state.driver, {
        itemIds: FURNACE_FUEL_ITEM_IDS,
        radius: 8,
        maximumDrops: 16,
        path: state.strategy.path,
      });
      currentObservation = yield* state.driver.observe;
      activeWorkstation = yield* ensureAccessibleFurnaceForBatch(
        state,
        currentObservation,
        activeWorkstation,
      );
      missingFuel = Math.max(0, requiredFuel - furnaceFuelCount(currentObservation));
      if (missingFuel === 0) {
        return activeWorkstation;
      }
    }

    const charcoalCount = bufferedCollectionCount("fuel", missingFuel);
    const starterFuelItems = Math.ceil(charcoalCount / 1.5);
    const hasEnoughCharcoalMaterials = (current: BeatGameObservation): boolean => {
      const plankCount = countInventoryItems(current, BURNABLE_PLANK_ITEM_IDS);
      const logCount = countInventoryItems(current, BURNABLE_LOG_ITEM_IDS);
      return plankCount >= starterFuelItems
        ? logCount >= charcoalCount
        : logCount >= charcoalCount + starterFuelItems;
    };
    if (!hasEnoughCharcoalMaterials(currentObservation)) {
      const plankCount = countInventoryItems(currentObservation, BURNABLE_PLANK_ITEM_IDS);
      const logCount = countInventoryItems(currentObservation, BURNABLE_LOG_ITEM_IDS);
      const requiredLogs = charcoalCount + (plankCount >= starterFuelItems ? 0 : starterFuelItems);
      yield* collectBlocksOrExplore(state, currentObservation, {
        blockIds: BURNABLE_LOG_ITEM_IDS,
        count: bufferedCollectionCount("logs", Math.max(1, requiredLogs - logCount)),
        progressItemIds: BURNABLE_LOG_ITEM_IDS,
        purpose: "find-furnace-fuel",
        avoidSubmergedTargets: true,
        requireSurfaceTargets: true,
      });
      currentObservation = yield* state.driver.observe;
      activeWorkstation = yield* ensureAccessibleFurnaceForBatch(
        state,
        currentObservation,
        activeWorkstation,
      );
    }
    if (!hasEnoughCharcoalMaterials(currentObservation)) {
      return yield* new BeatGameDriverError({
        operation: "task.smelt",
        code: "resource-exhausted",
        retryable: true,
        message: "Not enough distinct logs and starter fuel are available to make charcoal",
      });
    }

    const charcoalFuelItemIds =
      countInventoryItems(currentObservation, BURNABLE_PLANK_ITEM_IDS) >= starterFuelItems
        ? BURNABLE_PLANK_ITEM_IDS
        : BURNABLE_LOG_ITEM_IDS;
    yield* smelt(state.driver, {
      input: { itemIds: BURNABLE_LOG_ITEM_IDS },
      count: charcoalCount,
      fuel: { itemIds: charcoalFuelItemIds },
      station: activeWorkstation.position,
      path: state.strategy.path,
    });
    return activeWorkstation;
  });
}

function ensureAccessibleFurnaceForBatch(
  state: RunState,
  observation: BeatGameObservation,
  previous: PreparedWorkstation,
): Effect.Effect<PreparedWorkstation, BeatGameDriverError> {
  return ensureWorkstation(state, observation, "minecraft:furnace").pipe(
    Effect.map((current) =>
      sameBlockPosition(current.position, previous.position) ? previous : current,
    ),
  );
}

function furnaceFuelCount(observation: BeatGameObservation): number {
  return FURNACE_FUEL_ITEM_IDS.reduce(
    (count, itemId) => count + (observation.inventory.counts[itemId] ?? 0),
    0,
  );
}

function bufferedCollectionCount(resource: BufferedResource, missing: number): number {
  return Math.max(1, missing) + RESOURCE_COLLECTION_BUFFERS[resource];
}

// Stone lies under nearly every surface, and digging it yields cobblestone.
// On a coast where all visible stone is under water, a live bot made 48
// collection attempts in 30 minutes without breaking a block.
function digForCobblestoneWithoutProgress(
  state: RunState,
  before: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const current = yield* state.driver.observe;
    if (
      current.player.dead ||
      (current.inventory.counts["minecraft:cobblestone"] ?? 0) >
        (before.inventory.counts["minecraft:cobblestone"] ?? 0) ||
      current.player.position.dimension !== "minecraft:overworld" ||
      !hasMiningPickaxe(current)
    ) {
      return;
    }
    yield* excavateResourceSearchStaircase(
      state,
      current.player.position,
      Math.floor(current.player.position.y) - COBBLESTONE_DIG_DESCENT_STEP,
    );
  });
}

function ensureMiningPickaxe(
  state: RunState,
  observation: BeatGameObservation,
  resultItemId: "minecraft:wooden_pickaxe" | "minecraft:stone_pickaxe" | "minecraft:iron_pickaxe",
  usableItemIds: readonly string[],
  minimumRemainingDurability = 1,
): Effect.Effect<void, BeatGameDriverError> {
  return hasMiningPickaxeReserve(observation, usableItemIds, minimumRemainingDurability)
    ? Effect.void
    : craftWithTable(state, observation, resultItemId, 1);
}

function collectBlocksOrExplore(
  state: RunState,
  observation: BeatGameObservation,
  options: {
    readonly blockIds: readonly string[];
    readonly tags?: readonly string[];
    readonly count: number;
    readonly progressItemIds: readonly string[];
    readonly purpose: string;
    readonly avoidSubmergedTargets?: boolean;
    readonly requireLineOfSight?: boolean;
    readonly requireSurfaceTargets?: boolean;
    readonly preferSurfaceExploration?: boolean;
    readonly avoidFluids?: boolean;
    readonly path?: BeatGameStrategy["path"];
    readonly explorationTarget?: BeatGamePosition;
    /// How far each collection attempt looks, and how far each exploration
    /// hop moves. Defaults to the strategy's block search radius.
    readonly searchRadius?: number;
    readonly prepareAttempt?: (
      observation: BeatGameObservation,
    ) => Effect.Effect<void, BeatGameDriverError>;
  },
): Effect.Effect<void, BeatGameDriverError> {
  const countItems = (current: BeatGameObservation): number =>
    options.progressItemIds.reduce(
      (total, itemId) => total + (current.inventory.counts[itemId] ?? 0),
      0,
    );
  const searchRadius = options.searchRadius ?? state.strategy.blockSearchRadius;
  // A wider search hops as far as one collection attempt looks; the default
  // keeps the usual half-radius hops.
  const frontierScanRadius = options.searchRadius === undefined ? searchRadius : 2 * searchRadius;
  const frontierMaximumHop =
    options.searchRadius === undefined ? DISCOVERY_MAXIMUM_HOP : searchRadius;
  return Effect.gen(function* () {
    let current = observation;
    const targetCount = countItems(observation) + options.count;
    const requestedPath = options.path ?? state.strategy.path;
    const baseCollectionPath =
      options.avoidFluids === true || options.avoidSubmergedTargets === true
        ? { ...requestedPath, avoidFluids: true }
        : requestedPath;
    const collectionPathFor = (
      position: BeatGamePosition,
    ): Effect.Effect<BeatGameStrategy["path"], BeatGameDriverError> =>
      baseCollectionPath.avoidFluids !== true
        ? Effect.succeed(baseCollectionPath)
        : isPlayerInFluid(state.driver, position).pipe(
            Effect.map((inFluid) =>
              inFluid ? { ...baseCollectionPath, avoidFluids: false } : baseCollectionPath,
            ),
          );
    const explorationPath = {
      ...(options.avoidFluids === true ? baseCollectionPath : requestedPath),
      allowMining: false,
    };
    const preferSurfaceExploration = options.preferSurfaceExploration ?? true;
    if (
      options.avoidFluids === true &&
      (yield* isPlayerInFluid(state.driver, current.player.position))
    ) {
      yield* emergencyAirAscent(state, current.player.position);
      current = yield* state.driver.observe;
    }
    while (countItems(current) < targetCount) {
      yield* ensureInventorySpace(state, current, RESOURCE_COLLECTION_RESERVED_SLOTS);
      current = yield* state.driver.observe;
      if (
        options.requireSurfaceTargets === true &&
        current.player.position.dimension === "minecraft:overworld" &&
        (yield* needsOverworldSurfaceRecovery(state, current.player.position))
      ) {
        yield* escapeToOverworldSurface(state, current.player.position);
        current = yield* state.driver.observe;
      }
      const fluidAwareCollectionPath = yield* collectionPathFor(current.player.position);
      const collectionPath =
        options.requireSurfaceTargets === true &&
        current.player.position.dimension === "minecraft:overworld"
          ? {
              ...fluidAwareCollectionPath,
              minimumY:
                Math.floor(current.player.position.y) - SURFACE_RESOURCE_PATH_MINIMUM_Y_MARGIN,
            }
          : fluidAwareCollectionPath;
      const dropCollectionPath =
        options.avoidFluids === true ? collectionPath : { ...collectionPath, avoidFluids: false };
      yield* collectNearbyDrops(state.driver, {
        itemIds: options.progressItemIds,
        radius: 12,
        maximumDrops: 32,
        settleDelayMs: 100,
        maximumVerticalDistance: 3,
        path: dropCollectionPath,
      });
      current = yield* state.driver.observe;
      if (countItems(current) >= targetCount) {
        break;
      }
      if (options.prepareAttempt !== undefined) {
        yield* options.prepareAttempt(current);
        current = yield* state.driver.observe;
      }
      const beforeAttempt = countItems(current);
      const collectAttempt = collectBlocks(state.driver, {
        blockIds: options.blockIds,
        ...(options.tags === undefined ? {} : { tags: options.tags }),
        count: targetCount - beforeAttempt,
        searchRadius,
        avoidSubmergedTargets: options.avoidSubmergedTargets ?? false,
        requireLineOfSight: options.requireLineOfSight ?? false,
        ...(options.requireSurfaceTargets === true &&
        current.player.position.dimension === "minecraft:overworld"
          ? {
              targetYRange: {
                minimum: Math.floor(current.player.position.y) - SURFACE_RESOURCE_MINIMUM_Y_MARGIN,
                maximum: Math.floor(current.player.position.y) + SURFACE_RESOURCE_MAXIMUM_Y_MARGIN,
              },
            }
          : {}),
        path: collectionPath,
      });
      const waitForInventoryTarget = Effect.gen(function* () {
        while (true) {
          const observed = yield* state.driver.observe;
          if (countItems(observed) >= targetCount) {
            return;
          }
          yield* Effect.sleep(Math.max(50, state.strategy.observationPollMs));
        }
      });
      yield* Effect.raceFirst(collectAttempt, waitForInventoryTarget);
      yield* collectNearbyDrops(state.driver, {
        itemIds: options.progressItemIds,
        radius: 12,
        maximumDrops: 32,
        settleDelayMs: 500,
        maximumVerticalDistance: 3,
        path: dropCollectionPath,
      });
      current = yield* state.driver.observe;
      for (let attempt = 0; attempt < 5 && countItems(current) <= beforeAttempt; attempt += 1) {
        yield* Effect.sleep(Math.max(50, state.strategy.observationPollMs));
        current = yield* state.driver.observe;
      }
      if (countItems(current) <= beforeAttempt) {
        if (
          preferSurfaceExploration &&
          (yield* needsOverworldSurfaceRecovery(state, current.player.position))
        ) {
          yield* escapeToOverworldSurface(state, current.player.position);
          return;
        }
        if (
          preferSurfaceExploration &&
          (yield* climbToHigherOverworldGround(state, current.player.position))
        ) {
          return;
        }
        const frontierPath = preferSurfaceExploration ? explorationPath : baseCollectionPath;
        if (frontierPath.allowMining && options.prepareAttempt !== undefined) {
          yield* options.prepareAttempt(current);
          current = yield* state.driver.observe;
        }
        const playerPosition = current.player.position;
        const directedTarget =
          options.explorationTarget?.dimension === playerPosition.dimension &&
          horizontalDistanceSquared(options.explorationTarget, playerPosition) >
            DIRECTED_HUNT_DESTINATION_REACHED_RADIUS ** 2
            ? options.explorationTarget
            : undefined;
        yield* (
          directedTarget === undefined
            ? advanceExplorationFrontier(
                state,
                playerPosition,
                options.purpose,
                frontierScanRadius,
                frontierPath,
                preferSurfaceExploration,
                true,
                0,
                frontierMaximumHop,
              )
            : pathfindExplorationTarget(
                state,
                playerPosition,
                directedTarget,
                2,
                frontierPath,
                preferSurfaceExploration,
                false,
              ).pipe(
                Effect.catchTag("BeatGameDriverError", (error) =>
                  error.operation === "pathfind" || error.operation === "pathfindXZ"
                    ? advanceExplorationFrontier(
                        state,
                        playerPosition,
                        `${options.purpose}-detour`,
                        frontierScanRadius,
                        frontierPath,
                        preferSurfaceExploration,
                        false,
                        explorationDetourRotation(playerPosition, directedTarget),
                        frontierMaximumHop,
                      )
                    : Effect.fail(error),
                ),
              )
        ).pipe(
          Effect.catchTag("BeatGameDriverError", (error) =>
            error.operation === "pathfind" || error.operation === "pathfindXZ"
              ? // A walk that may not dig finds no route out of a small pocket,
                // such as a ledge on a cliff, so every later search fails the
                // same way. Digging out to nearby solid ground breaks the loop.
                !(
                  // A walk that may not dig finds no route out of a small pocket,
                  // such as a ledge on a cliff, so every later search fails the
                  // same way. Digging out to nearby solid ground breaks the loop.
                  frontierPath.allowMining
                ) && /No route found/iu.test(error.message)
                ? recoverLocalNavigationTrap(state, playerPosition).pipe(Effect.asVoid)
                : Effect.void
              : Effect.fail(error),
          ),
        );
        return;
      }
    }
  });
}

function fillLiquidBucket(
  state: RunState,
  observation: BeatGameObservation,
  liquid: "water" | "lava",
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let current = observation;
    if (liquid === "lava" && (yield* isPlayerInLava(state.driver, current.player.position))) {
      yield* escapeLava(state, current);
      const escaped = yield* state.driver.observe;
      yield* extinguishFire(state, escaped);
      return undefined;
    }
    if ((observation.inventory.counts["minecraft:bucket"] ?? 0) === 0) {
      const ironIngots = observation.inventory.counts["minecraft:iron_ingot"] ?? 0;
      if (ironIngots < 3) {
        yield* satisfyIronRequirement(state, observation, 3 - ironIngots);
        return undefined;
      }
      yield* craftWithTable(state, observation, "minecraft:bucket", 1);
      current = yield* state.driver.observe;
    }
    if (
      liquid === "water" &&
      (yield* needsOverworldSurfaceRecovery(state, current.player.position))
    ) {
      yield* returnToOverworldSurface(state, current.player.position);
      return undefined;
    }
    let liquidSources = yield* state.driver.queryBlocks({
      center: current.player.position,
      radius: state.strategy.blockSearchRadius,
      selector: {
        blockIds: [`minecraft:${liquid}`],
        properties: { level: "0" },
      },
      maximumResults: 32,
    });
    const waterloggedSources =
      liquid === "water"
        ? yield* state.driver.queryBlocks({
            center: current.player.position,
            radius: state.strategy.blockSearchRadius,
            selector: { properties: { waterlogged: "true" } },
            maximumResults: 8,
          })
        : [];
    let sources: readonly BeatGameBlockObservation[] = [...liquidSources, ...waterloggedSources];
    const useCastPortal =
      state.strategy.portalStrategy === PortalStrategy.CAST ||
      (state.strategy.portalStrategy === PortalStrategy.AUTO &&
        (current.inventory.counts["minecraft:obsidian"] ?? 0) < state.strategy.targetObsidianCount);
    if (
      liquid === "lava" &&
      useCastPortal &&
      sources.length > 0 &&
      sources.length < PORTAL_CASTING_ADDITIONAL_LAVA_SOURCE_COUNT &&
      current.player.position.dimension === "minecraft:overworld" &&
      current.player.position.y > DEEP_LAVA_SEARCH_MAX_Y
    ) {
      yield* preparePortalCastingLavaPool(state, current);
      return undefined;
    }
    let source = sources[0];
    if (source === undefined) {
      if (liquid === "lava") {
        const canCraftIronPickaxe = (current.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3;
        yield* ensureMiningPickaxe(
          state,
          current,
          canCraftIronPickaxe ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe",
          canCraftIronPickaxe ? DURABLE_MINING_PICKAXE_ITEM_IDS : MINING_PICKAXE_ITEM_IDS,
          RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE,
        );
        current = yield* state.driver.observe;
        if (
          current.player.position.dimension === "minecraft:overworld" &&
          current.player.position.y > DEEP_LAVA_SEARCH_MAX_Y
        ) {
          while (current.player.position.y > DEEP_LAVA_SEARCH_MAX_Y) {
            const canCraftReplacementIronPickaxe =
              (current.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3;
            yield* ensureMiningPickaxe(
              state,
              current,
              canCraftReplacementIronPickaxe ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe",
              canCraftReplacementIronPickaxe
                ? DURABLE_MINING_PICKAXE_ITEM_IDS
                : MINING_PICKAXE_ITEM_IDS,
              RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE,
            );
            current = yield* state.driver.observe;
            const beforeDescent = current.player.position;
            const targetY = Math.max(
              DEEP_LAVA_SEARCH_Y,
              Math.floor(beforeDescent.y) - DEEP_LAVA_DESCENT_STEP,
            );
            const descended = yield* excavateResourceSearchStaircase(state, beforeDescent, targetY);
            if (!descended) {
              return undefined;
            }
            current = yield* state.driver.observe;
            if (current.player.position.y > beforeDescent.y + 0.5) {
              return undefined;
            }
            if (current.player.position.y >= beforeDescent.y - 0.5) {
              return yield* new BeatGameDriverError({
                operation: "find-deep-lava",
                code: "unreachable",
                retryable: true,
                message: `The lava descent made no vertical progress from Y${beforeDescent.y.toFixed(
                  1,
                )}`,
              });
            }
            liquidSources = yield* state.driver.queryBlocks({
              center: current.player.position,
              radius: state.strategy.blockSearchRadius,
              selector: {
                blockIds: ["minecraft:lava"],
                properties: { level: "0" },
              },
              maximumResults: 32,
            });
            if (liquidSources.length > 0) {
              sources = liquidSources;
              source = liquidSources[0];
              break;
            }
          }
        }
        if (source === undefined) {
          const canCraftReplacementIronPickaxe =
            (current.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3;
          yield* ensureMiningPickaxe(
            state,
            current,
            canCraftReplacementIronPickaxe ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe",
            canCraftReplacementIronPickaxe
              ? DURABLE_MINING_PICKAXE_ITEM_IDS
              : MINING_PICKAXE_ITEM_IDS,
            RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE,
          );
          current = yield* state.driver.observe;
          return yield* advanceExplorationFrontier(
            state,
            current.player.position,
            "find-deep-lava",
            state.strategy.blockSearchRadius,
            state.strategy.path,
            false,
          );
        }
      }
      if (source === undefined) {
        if (
          liquid === "water" &&
          (yield* needsOverworldSurfaceRecovery(state, current.player.position))
        ) {
          yield* returnToOverworldSurface(state, current.player.position);
          return undefined;
        }
        return yield* explore(state.driver, {
          origin: current.player.position,
          radius: discoveryHopRadius(state, state.strategy.blockSearchRadius),
          maximumWaypoints: 1,
          purpose: explorationPurpose(`find-${liquid}`, observation.player.position),
          path: state.strategy.path,
        });
      }
    }
    const approachOrigin = current.player.position;
    const ensureApproachPickaxe = (
      approachObservation: BeatGameObservation,
    ): Effect.Effect<void, BeatGameDriverError> => {
      const canCraftDurablePickaxe =
        liquid === "lava" &&
        (approachObservation.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3;
      return ensureMiningPickaxe(
        state,
        approachObservation,
        canCraftDurablePickaxe ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe",
        canCraftDurablePickaxe ? DURABLE_MINING_PICKAXE_ITEM_IDS : MINING_PICKAXE_ITEM_IDS,
        liquid === "lava" ? RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE : 1,
      );
    };
    yield* ensureApproachPickaxe(current);
    current = yield* state.driver.observe;
    if (liquid === "lava") {
      const onlyVisibleSourcesAreDeep =
        sources.length > 0 &&
        sources.every(
          ({ position }) =>
            position.dimension === current.player.position.dimension &&
            position.y < current.player.position.y - 4,
        );
      if (
        onlyVisibleSourcesAreDeep &&
        current.player.position.dimension === "minecraft:overworld" &&
        current.player.position.y > DEEP_LAVA_SEARCH_MAX_Y
      ) {
        const targetY = Math.max(
          DEEP_LAVA_SEARCH_Y,
          Math.floor(current.player.position.y) - DEEP_LAVA_DESCENT_STEP,
        );
        yield* excavateResourceSearchStaircase(state, current.player.position, targetY);
        return undefined;
      }
      const approach = yield* approachLiquidSourceFromSide(state.driver, current, sources, {
        path: state.strategy.path,
        requireTargetableSource: true,
      }).pipe(Effect.result);
      if (approach._tag === "Failure") {
        const visibleSourceIsBelow = sources.some(
          ({ position }) =>
            position.dimension === current.player.position.dimension &&
            position.y < current.player.position.y - 4,
        );
        if (
          approach.failure.operation === "approach-liquid-source" &&
          visibleSourceIsBelow &&
          current.player.position.dimension === "minecraft:overworld" &&
          current.player.position.y > DEEP_LAVA_SEARCH_MAX_Y
        ) {
          const targetY = Math.max(
            DEEP_LAVA_SEARCH_Y,
            Math.floor(current.player.position.y) - DEEP_LAVA_DESCENT_STEP,
          );
          yield* excavateResourceSearchStaircase(state, current.player.position, targetY);
          return undefined;
        }
        return yield* approach.failure;
      }
      source = approach.success;
    } else {
      const reachableSource = yield* pathfindToReachableWaterSource(state, current, sources);
      if (reachableSource === undefined) {
        yield* explore(state.driver, {
          origin: current.player.position,
          radius: discoveryHopRadius(state, state.strategy.blockSearchRadius),
          maximumWaypoints: 1,
          purpose: explorationPurpose("find-water", current.player.position),
          path: state.strategy.path,
        });
        return undefined;
      }
      source = reachableSource;
    }
    current = yield* state.driver.observe;
    yield* ensureApproachPickaxe(current);
    const collection = yield* state.driver
      .withControl(
        Effect.gen(function* () {
          for (
            let clearedBlocks = 0;
            clearedBlocks <= MAXIMUM_LIQUID_SIGHT_CLEARING_BLOCKS;
            clearedBlocks += 1
          ) {
            const latest = yield* state.driver.observe;
            const eyePosition = {
              ...latest.player.position,
              y: latest.player.position.y + 1.62,
            };
            const sourceCenter = {
              x: source.position.x + 0.5,
              y: source.position.y + 0.5,
              z: source.position.z + 0.5,
            };
            const direction = {
              x: sourceCenter.x - eyePosition.x,
              y: sourceCenter.y - eyePosition.y,
              z: sourceCenter.z - eyePosition.z,
            };
            const sourceDistance = Math.sqrt(
              direction.x * direction.x + direction.y * direction.y + direction.z * direction.z,
            );
            if (sourceDistance > LIQUID_INTERACTION_REACH) {
              return yield* new BeatGameDriverError({
                operation: `fill-${liquid}-bucket`,
                retryable: true,
                message: `The ${liquid} source remained ${sourceDistance.toFixed(
                  2,
                )} blocks away after pathfinding`,
              });
            }
            const rotation = rotationToward(eyePosition, sourceCenter);
            yield* state.driver.act({
              type: "look",
              yaw: rotation.yaw,
              pitch: rotation.pitch,
            });
            yield* waitForViewRotation(state.driver, rotation.yaw, rotation.pitch, 40);
            const obstruction = (yield* state.driver.raycast({
              direction,
              maximumDistance: Math.min(LIQUID_INTERACTION_REACH, sourceDistance + 0.05),
              includeFluids: false,
            })).block;
            if (
              obstruction === undefined ||
              sameBlockPosition(obstruction.position, source.position)
            ) {
              const liveSources = yield* state.driver.queryBlocks({
                center: {
                  x: source.position.x + 0.5,
                  y: source.position.y + 0.5,
                  z: source.position.z + 0.5,
                  dimension: source.position.dimension,
                },
                radius: 0.25,
                selector:
                  liquid === "water"
                    ? {}
                    : {
                        blockIds: ["minecraft:lava"],
                        properties: { level: "0" },
                      },
                maximumResults: 1,
              });
              if (
                !liveSources.some(
                  (candidate) =>
                    sameBlockPosition(candidate.position, source.position) &&
                    ((candidate.blockId === `minecraft:${liquid}` &&
                      candidate.properties["level"] === "0") ||
                      (liquid === "water" && candidate.properties["waterlogged"] === "true")),
                )
              ) {
                return yield* new BeatGameDriverError({
                  operation: `fill-${liquid}-bucket`,
                  retryable: true,
                  message: `The selected ${liquid} source at ${positionKey(
                    source.position,
                  )} changed while the bot approached it`,
                });
              }
              yield* state.driver.act({
                type: "select-item",
                selector: { itemIds: ["minecraft:bucket"] },
              });
              yield* state.driver
                .act({
                  type: "use-item",
                  hand: "main",
                })
                .pipe(
                  Effect.mapError(
                    (cause) =>
                      new BeatGameDriverError({
                        operation: `fill-${liquid}-bucket`,
                        ...(cause.code === undefined ? {} : { code: cause.code }),
                        retryable: true,
                        message: `Could not collect the ${liquid} source at ${positionKey(
                          source.position,
                        )}: ${cause.message}`,
                        cause,
                      }),
                  ),
                );
              return undefined;
            }
            const playerStabilityBlock = isPlayerStabilityBlock(
              latest.player.position,
              obstruction.position,
            );
            const safeLavaSightlineBlock =
              liquid !== "lava" || isSafeLavaSightlineBlock(source.position, obstruction);
            if (
              !obstruction.diggable ||
              playerStabilityBlock ||
              !safeLavaSightlineBlock ||
              clearedBlocks === MAXIMUM_LIQUID_SIGHT_CLEARING_BLOCKS
            ) {
              return yield* new BeatGameDriverError({
                operation: `expose-${liquid}-source`,
                retryable: true,
                message: playerStabilityBlock
                  ? `Refused to expose the ${liquid} source by mining the player's footing at ${positionKey(
                      obstruction.position,
                    )}`
                  : `Could not expose the ${liquid} source through ${
                      obstruction.blockId
                    } at ${positionKey(obstruction.position)}`,
              });
            }
            yield* state.driver.act({
              type: "select-item",
              selector: {
                itemIds: MINING_PICKAXE_ITEM_IDS,
              },
            });
            yield* state.driver.act({
              type: "dig-block",
              position: obstruction.position,
            });
          }
          return undefined;
        }),
      )
      .pipe(Effect.result);
    if (collection._tag === "Failure") {
      if (liquid === "water") {
        yield* rememberUnreachableLiquidSources(state, liquid, [source]);
        return undefined;
      }
      return yield* collection.failure;
    }
    if (liquid === "lava") {
      yield* retreatAfterLavaCollection(state, source.position, approachOrigin);
    }
    return undefined;
  });
}

function pathfindToReachableWaterSource(
  state: RunState,
  observation: BeatGameObservation,
  sources: readonly BeatGameBlockObservation[],
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const now = yield* Clock.currentTimeMillis;
    const rememberedUnreachable = new Set(
      checkpoint.memory.unreachable
        .filter(({ expiresAt }) => expiresAt === undefined || Date.parse(expiresAt) > now)
        .map(({ key }) => key),
    );
    const candidates = [...sources]
      .filter(
        (candidate) =>
          candidate.position.dimension === observation.player.position.dimension &&
          !rememberedUnreachable.has(liquidSourceUnreachableKey("water", candidate.position)),
      )
      .toSorted(
        (left, right) =>
          Math.abs(left.position.y - observation.player.position.y) -
            Math.abs(right.position.y - observation.player.position.y) ||
          horizontalDistanceSquared(left.position, observation.player.position) -
            horizontalDistanceSquared(right.position, observation.player.position),
      )
      .slice(0, LIQUID_SOURCE_MAXIMUM_PATH_ATTEMPTS);
    const failedSources: BeatGameBlockObservation[] = [];
    const startedInFluid = yield* isPlayerInFluid(state.driver, observation.player.position);
    for (const candidate of candidates) {
      if (
        startedInFluid &&
        isLiquidSourceWithinInteractionReach(observation.player.position, candidate.position)
      ) {
        yield* rememberUnreachableLiquidSources(state, "water", failedSources);
        return candidate;
      }
      const reached = yield* state.driver
        .pathfind(candidate.position, LIQUID_INTERACTION_APPROACH_RADIUS, {
          ...state.strategy.path,
          avoidFluids: true,
          maxFallDistance: Math.min(state.strategy.path.maxFallDistance, 1),
          maxSearchTimeMs: Math.min(
            state.strategy.path.maxSearchTimeMs,
            WORKSTATION_REUSE_MAX_SEARCH_TIME_MS,
          ),
        })
        .pipe(
          Effect.as(true),
          Effect.catchTag("BeatGameDriverError", (error) =>
            error.operation === "pathfind" ? Effect.succeed(false) : Effect.fail(error),
          ),
        );
      if (reached) {
        const current = yield* state.driver.observe;
        const endedInFluid = yield* isPlayerInFluid(state.driver, current.player.position);
        if (
          !endedInFluid &&
          isLiquidSourceWithinInteractionReach(current.player.position, candidate.position)
        ) {
          yield* rememberUnreachableLiquidSources(state, "water", failedSources);
          return candidate;
        }
        if (endedInFluid) {
          yield* emergencyAirAscent(state, current.player.position).pipe(Effect.result);
        }
      }
      failedSources.push(candidate);
    }
    yield* rememberUnreachableLiquidSources(state, "water", failedSources);
    return undefined;
  });
}

function isLiquidSourceWithinInteractionReach(
  player: BeatGamePosition,
  source: BeatGameBlockPosition,
): boolean {
  if (player.dimension !== source.dimension) {
    return false;
  }
  return (
    distanceSquared({ ...player, y: player.y + 1.62 }, blockCenter(source)) <=
    LIQUID_INTERACTION_REACH ** 2
  );
}

function rememberUnreachableLiquidSources(
  state: RunState,
  liquid: "water" | "lava",
  sources: readonly BeatGameBlockObservation[],
): Effect.Effect<void> {
  if (sources.length === 0) {
    return Effect.void;
  }
  const observedAt = new Date().toISOString();
  const expiresAt = new Date(Date.now() + LIQUID_SOURCE_UNREACHABLE_TTL_MS).toISOString();
  return persist(state, (checkpoint) => ({
    ...checkpoint,
    memory: {
      ...checkpoint.memory,
      unreachable: [
        ...checkpoint.memory.unreachable,
        ...sources.map((source) => ({
          key: liquidSourceUnreachableKey(liquid, source.position),
          value: source.position,
          observedAt,
          expiresAt,
          confidence: 1,
        })),
      ].slice(-64),
    },
  })).pipe(Effect.ignore);
}

function liquidSourceUnreachableKey(
  liquid: "water" | "lava",
  position: BeatGameBlockPosition,
): string {
  return `liquid-source:${liquid}:${positionKey(position)}`;
}

function satisfyIronRequirement(
  state: RunState,
  observation: BeatGameObservation,
  missing: number,
): Effect.Effect<void, BeatGameDriverError> {
  const rawIron = observation.inventory.counts["minecraft:raw_iron"] ?? 0;
  if (rawIron >= missing) {
    const batchCount = Math.min(rawIron, bufferedCollectionCount("iron", missing));
    return ensureWorkstation(state, observation, "minecraft:furnace").pipe(
      Effect.flatMap((workstation) =>
        ensureEfficientFurnaceFuel(state, observation, workstation, batchCount).pipe(
          Effect.flatMap((activeWorkstation) =>
            smelt(state.driver, {
              input: { itemIds: ["minecraft:raw_iron"] },
              count: batchCount,
              fuel: {
                itemIds: ["minecraft:coal", "minecraft:charcoal"],
              },
              station: activeWorkstation.position,
              path: state.strategy.path,
            }).pipe(Effect.andThen(reclaimPlacedFurnace(state, activeWorkstation))),
          ),
        ),
      ),
    );
  }
  return Effect.gen(function* () {
    const nearbyIron = yield* state.driver.queryBlocks({
      center: observation.player.position,
      radius: state.strategy.blockSearchRadius,
      selector: { blockIds: IRON_ORE_BLOCK_IDS },
      maximumResults: 1,
    });
    if (
      !nearbyIron.some(
        ({ blockId }) =>
          blockId === "minecraft:iron_ore" || blockId === "minecraft:deepslate_iron_ore",
      ) &&
      observation.player.position.dimension === "minecraft:overworld" &&
      observation.player.position.y > IRON_SEARCH_MAX_Y
    ) {
      yield* ensureMiningPickaxe(
        state,
        observation,
        "minecraft:stone_pickaxe",
        STONE_OR_BETTER_MINING_PICKAXE_ITEM_IDS,
        RESOURCE_DESCENT_PICKAXE_DURABILITY_RESERVE,
      );
      const current = yield* state.driver.observe;
      if (
        current.player.position.dimension !== "minecraft:overworld" ||
        current.player.position.y <= IRON_SEARCH_MAX_Y
      ) {
        return;
      }
      const targetY = Math.max(
        IRON_SEARCH_Y,
        Math.floor(current.player.position.y) - IRON_SEARCH_DESCENT_STEP,
      );
      yield* excavateResourceSearchStaircase(state, current.player.position, targetY);
      return;
    }
    yield* collectBlocksOrExplore(state, observation, {
      blockIds: IRON_ORE_BLOCK_IDS,
      count: bufferedCollectionCount("iron", missing - rawIron),
      progressItemIds: ["minecraft:raw_iron"],
      purpose: "find-iron",
      avoidSubmergedTargets: true,
      avoidFluids: true,
      preferSurfaceExploration: false,
      prepareAttempt: (current) =>
        ensureMiningPickaxe(
          state,
          current,
          "minecraft:stone_pickaxe",
          STONE_OR_BETTER_MINING_PICKAXE_ITEM_IDS,
          RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE,
        ),
    });
  });
}

// A casting site is abandoned when its lava could not be reached, so the
// rest of that pool is likely just as unreachable.
const ABANDONED_PORTAL_LAVA_RADIUS = 12;
const ABANDONED_PORTAL_LAVA_EXTRA_RESULTS = 32;

function abandonedPortalLavaSites(
  checkpoint: BeatGameCheckpoint,
): readonly BeatGameBlockPosition[] {
  return checkpoint.memory.portalWorkspaces.flatMap((workspace) =>
    workspace.status === "ABANDONED" && workspace.method === "CAST"
      ? [workspace.origin, ...workspace.candidateLavaSources]
      : [],
  );
}

interface PortalLavaPoolPreparation {
  readonly ready: boolean;
  readonly candidateLavaSources: readonly BeatGameBlockPosition[];
}

function preparePortalCastingLavaPool(
  state: RunState,
  observation: BeatGameObservation,
  requiredLavaSources = PORTAL_CASTING_ADDITIONAL_LAVA_SOURCE_COUNT,
): Effect.Effect<PortalLavaPoolPreparation, BeatGameDriverError> {
  return Effect.gen(function* () {
    const currentCheckpoint = yield* Ref.get(state.checkpoint);
    const abandonedLava = abandonedPortalLavaSites(currentCheckpoint);
    const sources = (yield* state.driver.queryBlocks({
      center: observation.player.position,
      radius: state.strategy.blockSearchRadius,
      selector: {
        blockIds: ["minecraft:lava"],
        properties: { level: "0" },
      },
      maximumResults:
        Math.max(1, requiredLavaSources) +
        (abandonedLava.length === 0 ? 0 : ABANDONED_PORTAL_LAVA_EXTRA_RESULTS),
    }))
      .filter(
        ({ position }) =>
          !abandonedLava.some(
            (site) =>
              site.dimension === position.dimension &&
              distanceSquared(site, position) <= ABANDONED_PORTAL_LAVA_RADIUS ** 2,
          ),
      )
      .slice(0, Math.max(1, requiredLavaSources));
    if (requiredLavaSources === 0) {
      return {
        ready: true,
        candidateLavaSources: [],
      };
    }
    if (sources.length >= requiredLavaSources) {
      yield* approachLiquidSourceFromSide(state.driver, observation, sources, {
        path: state.strategy.path,
      });
      return {
        ready: true,
        candidateLavaSources: sources.map(({ position }) => position),
      };
    }

    yield* ensurePortalMiningPickaxe(
      state,
      observation,
      RESOURCE_DESCENT_PICKAXE_DURABILITY_RESERVE,
    );
    const current = yield* state.driver.observe;
    const searchPath = {
      ...state.strategy.path,
      avoidFluids: true,
    };
    if (
      current.player.position.dimension === "minecraft:overworld" &&
      current.player.position.y > DEEP_LAVA_SEARCH_MAX_Y
    ) {
      const targetY = Math.max(
        DEEP_LAVA_SEARCH_Y,
        Math.floor(current.player.position.y) - DEEP_LAVA_DESCENT_STEP,
      );
      yield* excavateResourceSearchStaircase(state, current.player.position, targetY);
      return { ready: false, candidateLavaSources: [] };
    }

    yield* advanceExplorationFrontier(
      state,
      current.player.position,
      "find-portal-lava-pool",
      state.strategy.blockSearchRadius,
      searchPath,
      false,
    );
    return { ready: false, candidateLavaSources: [] };
  });
}

function ensurePortalMiningPickaxe(
  state: RunState,
  observation: BeatGameObservation,
  minimumRemainingDurability: number,
): Effect.Effect<void, BeatGameDriverError> {
  const canCraftDurablePickaxe = (observation.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3;
  return ensureMiningPickaxe(
    state,
    observation,
    canCraftDurablePickaxe ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe",
    canCraftDurablePickaxe ? DURABLE_MINING_PICKAXE_ITEM_IDS : MINING_PICKAXE_ITEM_IDS,
    minimumRemainingDurability,
  );
}

function excavateResourceSearchStaircase(
  state: RunState,
  position: BeatGamePosition,
  targetY: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const origin = yield* findStableResourceSearchStaircaseOrigin(state, position).pipe(
      Effect.result,
    );
    if (origin._tag === "Failure") {
      if (origin.failure.operation !== "find-resource-staircase-origin") {
        return yield* origin.failure;
      }
      yield* relocateResourceSearchStaircase(state, position, "unstable");
      return false;
    }
    const from = origin.success;
    const destination = yield* selectResourceSearchStaircaseDestination(
      state.driver,
      from,
      targetY,
    ).pipe(Effect.result);
    if (destination._tag === "Failure") {
      if (destination.failure.operation !== "find-resource-staircase-destination") {
        return yield* destination.failure;
      }
      yield* relocateResourceSearchStaircase(state, position, "blocked");
      return false;
    }
    const to = destination.success;
    const excavation = yield* excavateStaircase(state.driver, {
      from,
      to,
      path: {
        ...state.strategy.path,
        avoidFluids: true,
      },
    }).pipe(Effect.result);
    if (excavation._tag === "Success") {
      return true;
    }
    if (excavation.failure.code === "unsupported_opening") {
      return false;
    }
    if (excavation.failure.code !== "fluid_exposed") {
      return yield* excavation.failure;
    }

    yield* relocateResourceSearchStaircase(state, position, "flooded");
    return false;
  });
}

function relocateResourceSearchStaircase(
  state: RunState,
  position: BeatGamePosition,
  reason: "blocked" | "flooded" | "unstable",
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const current = yield* state.driver.observe;
    if (yield* isPlayerInFluid(state.driver, current.player.position)) {
      yield* emergencyAirAscent(state, current.player.position);
    }
    const recovered = yield* state.driver.observe;
    yield* escapeToOverworldSurface(state, recovered.player.position);
    const surfaced = yield* state.driver.observe;
    yield* advanceExplorationFrontier(
      state,
      surfaced.player.position,
      explorationPurpose(`avoid-${reason}-resource-staircase`, position),
      64,
      {
        ...state.strategy.path,
        avoidFluids: true,
      },
      true,
      false,
    );
  });
}

function findStableResourceSearchStaircaseOrigin(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<BeatGameBlockPosition, BeatGameDriverError> {
  return Effect.gen(function* () {
    const current = floorBlockPosition(position);
    const currentSupport = yield* queryExactBlockAt(state.driver, {
      ...current,
      y: current.y - 1,
    });
    if (isStableStaircaseAnchor(currentSupport)) {
      return current;
    }

    const supports = (yield* state.driver.queryBlocks({
      center: position,
      radius: 12,
      selector: { solid: true, requireLineOfSight: true },
      maximumResults: 512,
    }))
      .filter(
        (block) =>
          block.position.dimension === position.dimension &&
          isStableStaircaseAnchor(block) &&
          Math.abs(block.position.y + 1 - position.y) <= 4,
      )
      .toSorted(
        (left, right) =>
          distanceSquared({ ...left.position, y: left.position.y + 1 }, position) -
          distanceSquared({ ...right.position, y: right.position.y + 1 }, position),
      )
      .slice(0, 64);
    for (const support of supports) {
      const stand = {
        ...support.position,
        y: support.position.y + 1,
      };
      const [body, head] = yield* Effect.all(
        [
          queryExactBlockAt(state.driver, stand),
          queryExactBlockAt(state.driver, { ...stand, y: stand.y + 1 }),
        ],
        { concurrency: 1 },
      );
      if (
        body?.replaceable !== true ||
        head?.replaceable !== true ||
        [body, head].some(({ blockId }) => isPlayerFluidBlock(blockId))
      ) {
        continue;
      }
      const reached = yield* state.driver
        .pathfind(
          {
            x: stand.x + 0.5,
            y: stand.y,
            z: stand.z + 0.5,
            dimension: stand.dimension,
          },
          0.75,
          {
            ...state.strategy.path,
            allowMining: false,
            allowPlacing: false,
            avoidFluids: true,
            maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 5000),
          },
        )
        .pipe(Effect.result);
      if (reached._tag === "Failure") {
        continue;
      }
      const staged = floorBlockPosition((yield* state.driver.observe).player.position);
      const stagedSupport = yield* queryExactBlockAt(state.driver, {
        ...staged,
        y: staged.y - 1,
      });
      if (isStableStaircaseAnchor(stagedSupport)) {
        return staged;
      }
    }
    return yield* new BeatGameDriverError({
      operation: "find-resource-staircase-origin",
      code: "unreachable",
      retryable: true,
      message:
        "No reachable dry ground with stable footing is available for a resource-search staircase",
    });
  });
}

function selectResourceSearchStaircaseDestination(
  driver: BeatGameDriver,
  from: BeatGameBlockPosition,
  targetY: number,
): Effect.Effect<BeatGameBlockPosition, BeatGameDriverError> {
  return Effect.gen(function* () {
    const depth = Math.max(1, from.y - targetY);
    const directions = [
      { x: 1, z: 0 },
      { x: -1, z: 0 },
      { x: 0, z: 1 },
      { x: 0, z: -1 },
    ] as const;
    for (const direction of directions) {
      const corridor = yield* Effect.all(
        Array.from({ length: depth }, (_, index) => {
          const stepDepth = index + 1;
          const tread = {
            ...from,
            x: from.x + direction.x * stepDepth,
            y: from.y - stepDepth,
            z: from.z + direction.z * stepDepth,
          };
          return Effect.all(
            [
              queryExactBlockAt(driver, { ...tread, y: tread.y + 2 }),
              queryExactBlockAt(driver, { ...tread, y: tread.y + 1 }),
              queryExactBlockAt(driver, tread),
              queryExactBlockAt(driver, { ...tread, y: tread.y - 1 }),
              queryExactBlockAt(driver, { ...tread, y: tread.y - 2 }),
            ],
            { concurrency: 1 },
          );
        }),
        { concurrency: "unbounded" },
      );
      if (
        corridor.some((section) => {
          const support = section[3];
          const lowerAnchor = section[4];
          return (
            section.some((block) => block === undefined || isPlayerFluidBlock(block.blockId)) ||
            (!isStableStaircaseAnchor(support) && !isStableStaircaseAnchor(lowerAnchor))
          );
        })
      ) {
        continue;
      }
      return {
        ...from,
        x: from.x + direction.x * depth,
        y: targetY,
        z: from.z + direction.z * depth,
      };
    }
    return yield* new BeatGameDriverError({
      operation: "find-resource-staircase-destination",
      code: "unreachable",
      retryable: true,
      message:
        "No fully loaded, fluid-free, and supportable corridor is available for a resource-search staircase",
    });
  });
}

function isStableStaircaseAnchor(
  block: BeatGameBlockObservation | undefined,
): block is BeatGameBlockObservation {
  return block?.solid === true && !block.replaceable && !isGravityAffectedBlockId(block.blockId);
}

function isGravityAffectedBlockId(blockId: string): boolean {
  return (
    blockId === "minecraft:sand" ||
    blockId === "minecraft:red_sand" ||
    blockId === "minecraft:gravel" ||
    blockId === "minecraft:dragon_egg" ||
    blockId.endsWith("_concrete_powder") ||
    blockId.endsWith("_anvil")
  );
}

function isPlayerFluidBlock(blockId: string): boolean {
  return (PLAYER_FLUID_BLOCK_IDS as readonly string[]).includes(blockId);
}

function queryExactBlockAt(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: blockCenter(position),
      radius: 0.25,
      selector: {},
      maximumResults: 1,
    })
    .pipe(
      Effect.map((blocks) =>
        blocks.find(({ position: observed }) => sameBlockPosition(observed, position)),
      ),
    );
}

function isPlayerStabilityBlock(player: BeatGamePosition, block: BeatGameBlockPosition): boolean {
  if (player.dimension !== block.dimension) {
    return false;
  }
  const playerX = Math.floor(player.x);
  const playerY = Math.floor(player.y);
  const playerZ = Math.floor(player.z);
  return (
    block.x === playerX && block.z === playerZ && block.y >= playerY - 1 && block.y <= playerY + 1
  );
}

function isSafeLavaSightlineBlock(
  source: BeatGameBlockPosition,
  obstruction: BeatGameBlockObservation,
): boolean {
  return (
    obstruction.position.dimension === source.dimension &&
    obstruction.position.y > source.y &&
    Math.abs(obstruction.position.x - source.x) <= 1 &&
    Math.abs(obstruction.position.z - source.z) <= 1 &&
    !isGravityAffectedBlockId(obstruction.blockId) &&
    obstruction.blockId !== "minecraft:obsidian" &&
    obstruction.blockId !== "minecraft:crying_obsidian" &&
    obstruction.blockId !== "minecraft:bedrock"
  );
}

function retreatAfterLavaCollection(
  state: RunState,
  source: BeatGameBlockPosition,
  approachOrigin: BeatGamePosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let current = yield* state.driver.observe;
    yield* escapeLava(state, current);
    current = yield* state.driver.observe;
    if (current.player.dead) {
      return;
    }
    const canReturnAlongApproach =
      approachOrigin.dimension === current.player.position.dimension &&
      distanceSquared(approachOrigin, current.player.position) >=
        (LAVA_RETREAT_DISTANCE * LAVA_RETREAT_DISTANCE) / 4;
    const target = canReturnAlongApproach
      ? approachOrigin
      : positionAwayFrom(current.player.position, source, LAVA_RETREAT_DISTANCE);
    yield* state.driver
      .pathfind(target, 2, {
        ...state.strategy.path,
        avoidFluids: true,
        maxSearchTimeMs: Math.min(state.strategy.path.maxSearchTimeMs, 15_000),
      })
      .pipe(Effect.catchTag("BeatGameDriverError", () => Effect.void));
    current = yield* state.driver.observe;
    yield* escapeLava(state, current);
  });
}

function waitForViewRotation(
  driver: BeatGameDriver,
  yaw: number,
  pitch: number,
  attempts: number,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      const rotation = observation.player.rotation;
      if (
        Math.abs(wrappedDegrees(rotation.yaw - yaw)) <= 4 &&
        Math.abs(rotation.pitch - pitch) <= 4
      ) {
        return Effect.void;
      }
      if (attempts <= 1) {
        return Effect.fail(
          new BeatGameDriverError({
            operation: "wait-for-view-rotation",
            retryable: true,
            message: `The bot did not finish facing yaw ${yaw.toFixed(1)}, pitch ${pitch.toFixed(
              1,
            )}`,
          }),
        );
      }
      return Effect.sleep(50).pipe(
        Effect.andThen(waitForViewRotation(driver, yaw, pitch, attempts - 1)),
      );
    }),
  );
}

function wrappedDegrees(value: number): number {
  return ((((value + 180) % 360) + 360) % 360) - 180;
}

function ensureFlint(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let current = observation;
    if ((current.inventory.counts["minecraft:flint"] ?? 0) > 0) {
      return undefined;
    }
    if ((current.inventory.counts["minecraft:gravel"] ?? 0) === 0) {
      yield* collectBlocksOrExplore(state, current, {
        blockIds: ["minecraft:gravel"],
        count: 8,
        progressItemIds: ["minecraft:gravel", "minecraft:flint"],
        purpose: "find-gravel",
        avoidSubmergedTargets: true,
        avoidFluids: true,
      });
      current = yield* state.driver.observe;
    }
    if ((current.inventory.counts["minecraft:flint"] ?? 0) > 0) {
      return undefined;
    }
    if ((current.inventory.counts["minecraft:gravel"] ?? 0) === 0) {
      return yield* new BeatGameDriverError({
        operation: "acquire-flint",
        retryable: true,
        message: "Breaking nearby gravel produced neither gravel nor flint",
      });
    }

    for (let attempt = 0; attempt < 64; attempt += 1) {
      current = yield* state.driver.observe;
      if ((current.inventory.counts["minecraft:flint"] ?? 0) > 0) {
        return undefined;
      }
      if ((current.inventory.counts["minecraft:gravel"] ?? 0) === 0) {
        yield* collectNearbyDrops(state.driver, {
          radius: 4,
          maximumDrops: 8,
          settleDelayMs: 100,
          path: state.strategy.path,
        });
        current = yield* state.driver.observe;
        if ((current.inventory.counts["minecraft:flint"] ?? 0) > 0) {
          return undefined;
        }
        if ((current.inventory.counts["minecraft:gravel"] ?? 0) === 0) {
          return yield* new BeatGameDriverError({
            operation: "acquire-flint",
            retryable: true,
            message: "The recycled gravel item could not be recovered",
          });
        }
      }

      const targets = yield* findWorkstationTargets(state.driver, current.player.position);
      let target: BeatGameBlockPosition | undefined;
      for (const candidate of targets) {
        const approached = yield* state.driver
          .pathfind(blockCenter(candidate), 3, {
            ...state.strategy.path,
            allowMining: false,
            allowPlacing: false,
            avoidFluids: true,
          })
          .pipe(Effect.result);
        if (approached._tag === "Failure") {
          continue;
        }
        const approachedObservation = yield* state.driver.observe;
        if (yield* isPlayerInFluid(state.driver, approachedObservation.player.position)) {
          yield* emergencyAirAscent(state, approachedObservation.player.position);
          continue;
        }
        if (placementIntersectsPlayer(candidate, approachedObservation.player.position)) {
          continue;
        }
        yield* state.driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:gravel"] },
        });
        const placement = yield* state.driver
          .act({
            type: "place-block",
            against: { ...candidate, y: candidate.y - 1 },
            face: "up",
            hand: "main",
          })
          .pipe(Effect.result);
        if (placement._tag === "Failure") {
          continue;
        }
        const placed = yield* waitForExactBlockId(state.driver, candidate, "minecraft:gravel");
        if (!placed) {
          continue;
        }
        target = candidate;
        break;
      }
      if (target === undefined) {
        return yield* new BeatGameDriverError({
          operation: "acquire-flint",
          retryable: true,
          message: "No dry collision-free position accepted recycled gravel",
        });
      }
      yield* state.driver.act({ type: "dig-block", position: target });
      yield* collectNearbyDrops(state.driver, {
        radius: 4,
        maximumDrops: 8,
        settleDelayMs: 100,
        path: state.strategy.path,
      });
    }

    return yield* new BeatGameDriverError({
      operation: "acquire-flint",
      retryable: true,
      message: "Recycling gravel did not produce flint after 64 attempts",
    });
  });
}

function ensureIgnition(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let current = observation;
    if ((current.inventory.counts["minecraft:iron_ingot"] ?? 0) === 0) {
      yield* satisfyIronRequirement(state, current, 1);
      current = yield* state.driver.observe;
      if ((current.inventory.counts["minecraft:iron_ingot"] ?? 0) === 0) {
        return;
      }
    }
    yield* ensureFlint(state, current);
    current = yield* state.driver.observe;
    if ((current.inventory.counts["minecraft:flint"] ?? 0) === 0) {
      return;
    }
    yield* craftItem(state.driver, {
      resultItemId: "minecraft:flint_and_steel",
      count: 1,
      path: state.strategy.path,
    });
  });
}

function waitForExactBlockId(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
  blockId: string,
  attempts = 20,
): Effect.Effect<boolean, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: blockCenter(target),
      radius: 0.25,
      selector: { blockIds: [blockId] },
      maximumResults: 1,
    })
    .pipe(
      Effect.flatMap((blocks) =>
        blocks.some(({ position }) => sameBlockPosition(position, target))
          ? Effect.succeed(true)
          : attempts <= 1
            ? Effect.succeed(false)
            : Effect.sleep(50).pipe(
                Effect.andThen(waitForExactBlockId(driver, target, blockId, attempts - 1)),
              ),
      ),
    );
}

function ensureString(
  state: RunState,
  observation: BeatGameObservation,
  count: number,
): Effect.Effect<void, BeatGameDriverError | BeatGameError> {
  const missing = Math.max(0, count - (observation.inventory.counts["minecraft:string"] ?? 0));
  return missing === 0
    ? Effect.void
    : huntOrExplore(
        state,
        observation,
        {
          entityTypes: ["minecraft:spider", "minecraft:cave_spider"],
          alive: true,
        },
        missing,
        "find-spiders",
      );
}

function ensureArrowIngredients(
  state: RunState,
  observation: BeatGameObservation,
  arrowCount: number,
): Effect.Effect<void, BeatGameDriverError | BeatGameError> {
  const operations = Math.ceil(arrowCount / 4);
  const flintMissing = Math.max(
    0,
    operations - (observation.inventory.counts["minecraft:flint"] ?? 0),
  );
  const featherMissing = Math.max(
    0,
    operations - (observation.inventory.counts["minecraft:feather"] ?? 0),
  );
  return Effect.gen(function* () {
    if (flintMissing > 0) {
      yield* collectBlocks(state.driver, {
        blockIds: ["minecraft:gravel"],
        count: flintMissing * 4,
        searchRadius: state.strategy.blockSearchRadius,
        path: state.strategy.path,
      });
    }
    if (featherMissing > 0) {
      yield* huntOrExplore(
        state,
        observation,
        {
          entityTypes: ["minecraft:chicken"],
          alive: true,
        },
        featherMissing,
        "find-chickens",
      );
    }
  });
}

interface HuntTargetPreference {
  readonly preferredEntityTypes: ReadonlySet<string>;
  readonly preferredRadius: number;
  readonly allowCriticalAquaticTargets?: boolean;
  readonly maximumSafeAquaticFoodLevel?: number;
  readonly requireHealthRecoveryForSafeAquaticTargets?: boolean;
  readonly safeAquaticFallbackAfterExplorationLegs?: number;
  readonly maximumLandVerticalRise?: number;
  readonly maximumLandVerticalDrop?: number;
  readonly maximumExplorationHops?: number;
  readonly path?: BeatGameStrategy["path"];
  readonly explorationTarget?: BeatGamePosition;
  // The drops that count toward the hunt, when they differ from the target's usual drops.
  readonly dropItemIds?: readonly string[];
  // Skips observed targets that cannot give the wanted drops.
  readonly acceptsTarget?: (target: BeatGameEntityObservation) => boolean;
  readonly allowFluidFallback?: boolean;
  readonly fallbackToLocalExploration?: boolean;
  readonly structureSearch?: Readonly<{
    anchorBlockIds: ReadonlySet<string>;
    stationaryBlockIds: ReadonlySet<string>;
    placeKind: BeatGameWorldPlace["kind"];
    scanRadius: number;
    maximumResults: number;
  }>;
}

/// A live run came back to the Nether 130 blocks from the fortress where it
/// had already killed blazes, just past its scan, and spent half an hour
/// exploring the other way. The nearest remembered fortress in this
/// dimension is where the search heads first.
function rememberedFortressTarget(
  places: readonly BeatGameWorldPlace[],
  position: BeatGamePosition,
): BeatGamePosition | undefined {
  return places
    .filter((place) => place.kind === "FORTRESS" && place.position.dimension === position.dimension)
    .toSorted(
      (left, right) =>
        horizontalDistanceSquared(left.position, position) -
        horizontalDistanceSquared(right.position, position),
    )[0]?.position;
}

function huntOrExplore(
  state: RunState,
  observation: BeatGameObservation,
  selector: Parameters<BeatGameDriver["queryEntities"]>[0]["selector"],
  maximumTargets: number,
  purpose: string,
  targetPreference?: HuntTargetPreference,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const expectedDropItemIds = [
      ...new Set(
        targetPreference?.dropItemIds ??
          selector.entityTypes?.flatMap(
            (entityType) => HUNT_DROP_ITEM_IDS_BY_ENTITY_TYPE[entityType] ?? [],
          ) ??
          [],
      ),
    ];
    const expectedDropCount = (value: BeatGameObservation): number =>
      expectedDropItemIds.reduce(
        (total, itemId) => total + (value.inventory.counts[itemId] ?? 0),
        0,
      );
    const expectedFoodDrops = expectedDropItemIds.some(
      (itemId) =>
        EDIBLE_FOOD_ITEM_IDS.includes(itemId) ||
        EMERGENCY_FOOD_ITEM_IDS.some((foodItemId) => foodItemId === itemId),
    );
    const initialExpectedDropCount = expectedDropCount(observation);
    const maximumExplorationHops = Math.max(
      0,
      Math.floor(targetPreference?.maximumExplorationHops ?? 1),
    );
    const attemptedTargets = new Set<string>();
    const attemptedStructureCells = new Set<string>();
    const aquaticChaseAttempts = new Map<string, number>();
    const locallyUnreachable = new Set<string>();
    let confirmedVisibleTarget: BeatGameEntityObservation | undefined;
    let aquaticRetryTargetId: string | undefined;
    let strandedAquaticFallback = false;
    let fluidTraversalRequired = false;
    let aquaticPursuitActive = false;
    let attacked = 0;
    let explorationHops = 0;
    let stationaryStructureTarget: BeatGameBlockObservation | undefined;
    while (true) {
      yield* Effect.sleep(1);
      let current = yield* state.driver.observe;
      const collected = expectedDropCount(current) - initialExpectedDropCount;
      if (
        expectedDropItemIds.length > 0 ? collected >= maximumTargets : attacked >= maximumTargets
      ) {
        return undefined;
      }
      yield* ensureInventorySpace(state, current, RESOURCE_COLLECTION_RESERVED_SLOTS);
      current = yield* state.driver.observe;
      const checkpoint = yield* Ref.get(state.checkpoint);
      const explorationLegs = Math.max(
        explorationHops,
        completedExplorationLegs(checkpoint, current.player.position.dimension, purpose),
      );
      const aquaticHuntAllowed =
        strandedAquaticFallback ||
        shouldAllowAquaticHunt(
          current,
          state.strategy.minimumHealth,
          targetPreference,
          explorationLegs,
        );
      const overworldHunt = current.player.position.dimension === "minecraft:overworld";
      const playerInFluid =
        overworldHunt && (yield* isPlayerInFluid(state.driver, current.player.position));
      if (!playerInFluid) {
        fluidTraversalRequired = false;
      }
      if (
        overworldHunt &&
        !aquaticHuntAllowed &&
        !fluidTraversalRequired &&
        !aquaticPursuitActive &&
        playerInFluid
      ) {
        const recovery = yield* emergencyAirAscent(state, current.player.position).pipe(
          Effect.result,
        );
        current = yield* state.driver.observe;
        if (current.player.dead) {
          return undefined;
        }
        if (!(yield* isPlayerInFluid(state.driver, current.player.position))) {
          continue;
        }
        const breathingAtSurface =
          !hasUnsafeAir(current) &&
          (yield* hasBreathableHeadSpace(state.driver, current.player.position));
        if (breathingAtSurface) {
          fluidTraversalRequired = true;
          continue;
        }
        if (recovery._tag === "Failure") {
          return yield* recovery.failure;
        }
        continue;
      }
      const survivalPath = survivalPathPolicy(
        targetPreference?.path ?? state.strategy.path,
        current.player.health,
        state.strategy.minimumHealth,
      );
      const huntingPath =
        aquaticHuntAllowed || fluidTraversalRequired
          ? { ...survivalPath, avoidFluids: false }
          : survivalPath;
      const explorationPath = {
        ...huntingPath,
        // The Nether has no open surface to walk out along. A live hunt on a
        // walled-in ledge failed 56 explorations that could not dig or build.
        ...(targetPreference?.structureSearch === undefined &&
        !isNether(current.player.position.dimension)
          ? {
              allowMining: false,
              allowPlacing: false,
            }
          : {}),
        sprint: false,
      };
      if (yield* needsOverworldSurfaceRecovery(state, current.player.position)) {
        yield* escapeToOverworldSurface(state, current.player.position);
        if (aquaticRetryTargetId !== undefined) {
          continue;
        }
        return undefined;
      }
      if (expectedDropItemIds.length > 0) {
        yield* collectNearbyDrops(state.driver, {
          itemIds: expectedDropItemIds,
          radius: Math.min(HUNT_DROP_RECOVERY_RADIUS, state.strategy.entitySearchRadius),
          maximumDrops: Math.min(32, Math.max(16, maximumTargets)),
          settleDelayMs: 0,
          maximumVerticalDistance: HUNT_DROP_RECOVERY_MAXIMUM_VERTICAL_DISTANCE,
          path:
            expectedFoodDrops && current.player.position.dimension === "minecraft:overworld"
              ? { ...huntingPath, avoidFluids: false }
              : huntingPath,
        });
        current = yield* state.driver.observe;
        if (expectedDropCount(current) - initialExpectedDropCount >= maximumTargets) {
          return undefined;
        }
      }
      let observedStructureBlocks: readonly BeatGameBlockObservation[] = [];
      if (
        targetPreference?.structureSearch !== undefined &&
        stationaryStructureTarget === undefined
      ) {
        const structureSearch = targetPreference.structureSearch;
        const anchorBlocks = yield* state.driver.queryBlocks({
          center: current.player.position,
          radius: structureSearch.scanRadius,
          selector: { blockIds: [...structureSearch.anchorBlockIds] },
          maximumResults: structureSearch.maximumResults,
        });
        const structureEvidence = anchorBlocks.find(({ blockId }) =>
          structureSearch.anchorBlockIds.has(blockId),
        );
        const stationaryBlocks =
          structureEvidence === undefined
            ? []
            : yield* state.driver.queryBlocks({
                center: current.player.position,
                radius: structureSearch.scanRadius,
                selector: {
                  blockIds: [...structureSearch.stationaryBlockIds],
                },
                maximumResults: 32,
              });
        // A fortress spans more than a hundred blocks, and the scan returns
        // the anchors nearest the bot. A live run saw two blaze spawners 62
        // blocks from the first anchor it got back and never went to them.
        // A spawner counts when the structure's blocks lie right around it.
        let stationaryEvidence: BeatGameBlockObservation | undefined;
        for (const block of stationaryBlocks.toSorted(
          (left, right) =>
            distanceSquared(left.position, current.player.position) -
            distanceSquared(right.position, current.player.position),
        )) {
          const besideStructure =
            anchorBlocks.some(
              ({ blockId, position }) =>
                structureSearch.anchorBlockIds.has(blockId) &&
                distanceSquared(position, block.position) <= 48 ** 2,
            ) ||
            (yield* state.driver.queryBlocks({
              center: block.position,
              radius: STRUCTURE_SPAWNER_ANCHOR_RADIUS,
              selector: { blockIds: [...structureSearch.anchorBlockIds] },
              maximumResults: 1,
            })).length > 0;
          if (besideStructure) {
            stationaryEvidence = block;
            break;
          }
        }
        observedStructureBlocks = [...anchorBlocks, ...stationaryBlocks];
        const rememberedEvidence = stationaryEvidence ?? structureEvidence;
        if (rememberedEvidence !== undefined) {
          const evidenceKey = worldPlaceKey(structureSearch.placeKind, rememberedEvidence.position);
          if (!checkpoint.memory.places.some(({ key }) => key === evidenceKey)) {
            yield* persist(state, (currentCheckpoint) =>
              rememberWorldPlace(
                currentCheckpoint,
                structureSearch.placeKind,
                rememberedEvidence.position,
                stationaryEvidence === undefined ? 0.9 : 1,
              ),
            );
          }
        }
        stationaryStructureTarget = stationaryEvidence;
      }
      const now = yield* Clock.currentTimeMillis;
      const currentUnreachable = checkpoint.memory.unreachable.filter(
        ({ expiresAt }) => expiresAt === undefined || Date.parse(expiresAt) > now,
      );
      const rememberedUnreachableTargets = new Map(
        currentUnreachable.map((entry) => [entry.key, entry] as const),
      );
      // A remembered target is retried after a short delay. A live bot went
      // back to the same unreachable enderman 16 more times that way, so a
      // target remembered three times is left alone until those expire.
      const unreachableCounts = new Map<string, number>();
      for (const { key } of currentUnreachable) {
        unreachableCounts.set(key, (unreachableCounts.get(key) ?? 0) + 1);
      }
      for (const [key, count] of unreachableCounts) {
        if (count >= HUNT_UNREACHABLE_GIVE_UP_COUNT) {
          locallyUnreachable.add(key);
        }
      }
      const observedTargets = (yield* state.driver.queryEntities({
        origin: current.player.position,
        radius: state.strategy.entitySearchRadius,
        selector,
        maximumResults: Math.min(256, Math.max(64, maximumTargets + attemptedTargets.size)),
      })).filter((target) => targetPreference?.acceptsTarget?.(target) ?? true);
      const observedBlaze = observedTargets.find(
        ({ entityType }) => entityType === "minecraft:blaze",
      );
      if (
        observedBlaze !== undefined &&
        !checkpoint.memory.places.some(
          ({ key }) => key === worldPlaceKey("FORTRESS", observedBlaze.position),
        )
      ) {
        yield* persist(state, (currentCheckpoint) =>
          rememberWorldPlace(currentCheckpoint, "FORTRESS", observedBlaze.position, 0.8),
        );
      }
      const confirmedTarget = confirmedVisibleTarget;
      const targets =
        confirmedTarget === undefined
          ? observedTargets
          : [
              confirmedTarget,
              ...observedTargets.filter(
                (target) =>
                  target.connectionEpoch !== confirmedTarget.connectionEpoch ||
                  target.networkId !== confirmedTarget.networkId,
              ),
            ];
      confirmedVisibleTarget = undefined;
      const candidates = targets.filter(
        (target) =>
          !isHuntingTargetUnreachable(
            target,
            current.player.position,
            locallyUnreachable,
            rememberedUnreachableTargets,
            now,
          ) &&
          !attemptedTargets.has(`${target.connectionEpoch}:${target.networkId}`) &&
          isEligibleHuntingTarget(target, current, targetPreference, aquaticHuntAllowed),
      );
      const preferredCandidates =
        targetPreference === undefined
          ? []
          : candidates.filter(
              (candidate) =>
                targetPreference.preferredEntityTypes.has(candidate.entityType) &&
                horizontalDistanceSquared(candidate.position, current.player.position) <=
                  targetPreference.preferredRadius ** 2,
            );
      const recoveringHealth = current.player.health < state.strategy.minimumHealth;
      const landCandidates = candidates.filter(
        (candidate) => !AQUATIC_FOOD_ENTITY_TYPES.has(candidate.entityType),
      );
      const safeCandidates = landCandidates.length > 0 ? landCandidates : candidates;
      const safePreferredCandidates = preferredCandidates.filter((candidate) =>
        safeCandidates.includes(candidate),
      );
      const rankedCandidates = recoveringHealth
        ? safeCandidates
        : safePreferredCandidates.length > 0
          ? safePreferredCandidates
          : safeCandidates;
      const retryTargetId = aquaticRetryTargetId;
      const retryTarget =
        retryTargetId === undefined
          ? undefined
          : rankedCandidates.find(
              (candidate) =>
                `${candidate.connectionEpoch}:${candidate.networkId}` === retryTargetId,
            );
      aquaticRetryTargetId = undefined;
      const target =
        retryTarget ??
        rankedCandidates.reduce<BeatGameEntityObservation | undefined>(
          (nearest, candidate) =>
            nearest === undefined ||
            huntingTargetRouteCost(
              candidate,
              current.player.position,
              recoveringHealth ? undefined : targetPreference?.explorationTarget,
            ) <
              huntingTargetRouteCost(
                nearest,
                current.player.position,
                recoveringHealth ? undefined : targetPreference?.explorationTarget,
              )
              ? candidate
              : nearest,
          undefined,
        );
      aquaticPursuitActive =
        target !== undefined && AQUATIC_FOOD_ENTITY_TYPES.has(target.entityType);
      if (target === undefined) {
        if (
          stationaryStructureTarget !== undefined &&
          stationaryStructureTarget.position.dimension === current.player.position.dimension
        ) {
          const horizontalDistance = Math.sqrt(
            horizontalDistanceSquared(current.player.position, stationaryStructureTarget.position),
          );
          const verticalDistance = Math.abs(
            current.player.position.y - stationaryStructureTarget.position.y,
          );
          if (horizontalDistance > 5 || verticalDistance > 5) {
            const spawnerApproach = {
              ...stationaryStructureTarget.position,
              y: stationaryStructureTarget.position.y + 1,
            };
            const approachSpawner = (allowBlockChanges: boolean) =>
              state.driver
                .pathfind(spawnerApproach, 4, {
                  ...explorationPath,
                  allowMining: allowBlockChanges,
                  allowPlacing: allowBlockChanges,
                })
                .pipe(
                  Effect.as(true),
                  Effect.catchIf(
                    (cause) => cause.operation === "pathfind",
                    () => Effect.succeed(false),
                  ),
                );
            // Fortress floors keep the spawner out of reach without
            // digging. A live run sat 19 blocks below one and retried the
            // same walking-only route every second for half an hour.
            const approached = (yield* approachSpawner(false)) || (yield* approachSpawner(true));
            if (!approached) {
              stationaryStructureTarget = undefined;
            }
            continue;
          }
          yield* Effect.sleep(Math.max(250, state.strategy.observationPollMs));
          continue;
        }
        const structureSearch = targetPreference?.structureSearch;
        if (structureSearch !== undefined && observedStructureBlocks.length > 0) {
          const structureTargets = representativeStructureTargets(
            observedStructureBlocks.filter(({ blockId }) =>
              structureSearch.anchorBlockIds.has(blockId),
            ),
            attemptedStructureCells,
            current.player.position,
          );
          const structureTarget = structureTargets[0];
          if (structureTarget !== undefined) {
            const cell = structureCellKey(structureTarget.position);
            attemptedStructureCells.add(cell);
            explorationHops += 1;
            const structureGoal = {
              ...structureTarget.position,
              y: structureTarget.position.y + 1,
            };
            const walked = yield* state.driver
              .pathfind(structureGoal, 3, {
                ...explorationPath,
                allowMining: false,
                allowPlacing: false,
              })
              .pipe(
                Effect.as(true),
                Effect.catchIf(
                  (cause) => cause.operation === "pathfind",
                  () => Effect.succeed(false),
                ),
              );
            // Warped nylium often lies on ledges no walking route reaches. A
            // live Nether hunt tried the same walks to it fifty times in an
            // hour, so a failed walk is followed by the exploration route,
            // which may dig and build.
            if (!walked && (explorationPath.allowMining || explorationPath.allowPlacing)) {
              yield* state.driver.pathfind(structureGoal, 3, explorationPath).pipe(
                Effect.catchIf(
                  (cause) => cause.operation === "pathfind",
                  () => Effect.void,
                ),
              );
            }
            continue;
          }
        }
        if (explorationHops >= maximumExplorationHops) {
          return undefined;
        }
        explorationHops += 1;
        if (yield* needsOverworldSurfaceRecovery(state, current.player.position)) {
          yield* escapeToOverworldSurface(state, current.player.position);
        } else if (yield* climbToHigherOverworldGround(state, current.player.position)) {
          continue;
        } else {
          // Exploration hops half the scan radius it is given. Animals are
          // seen from the full search radius on each side, so a healthy hunt
          // hops a whole radius: no ground is missed, and a live food search
          // no longer spent an hour within 150 blocks of spawn.
          // A structure search hops as far as its scan reaches. A live
          // fortress search scanned and hopped 48 blocks at a time and spent
          // an hour within 150 blocks of a portal 820 blocks from the fortress.
          const structureScanRadius = targetPreference?.structureSearch?.scanRadius;
          // A fortress spans a hundred blocks or more, so scans can step
          // further apart than one scan reaches and still overlap its walls.
          // A live run took 90 minutes to circle a spiral 128 blocks per hop
          // and never reached a fortress 420 blocks from its start.
          const structureHop =
            structureScanRadius === undefined
              ? undefined
              : Math.round(structureScanRadius * STRUCTURE_SEARCH_HOP_FACTOR);
          const frontierScanRadius =
            current.player.health < state.strategy.minimumHealth
              ? Math.min(32, state.strategy.entitySearchRadius)
              : 2 * Math.max(state.strategy.entitySearchRadius, structureHop ?? 0);
          const directedExplorationTarget =
            targetPreference?.explorationTarget?.dimension === current.player.position.dimension &&
            horizontalDistanceSquared(targetPreference.explorationTarget, current.player.position) >
              DIRECTED_HUNT_DESTINATION_REACHED_RADIUS ** 2
              ? targetPreference.explorationTarget
              : undefined;
          const allowFluidFallback =
            targetPreference?.allowFluidFallback ??
            (current.player.health >= state.strategy.minimumHealth &&
              current.player.food > CRITICAL_HUNGER_FOOD_LEVEL);
          const advance =
            directedExplorationTarget === undefined
              ? advanceExplorationFrontier(
                  state,
                  current.player.position,
                  purpose,
                  frontierScanRadius,
                  explorationPath,
                  true,
                  allowFluidFallback,
                  0,
                  structureHop ?? DISCOVERY_MAXIMUM_HOP,
                )
              : pathfindExplorationTarget(
                  state,
                  current.player.position,
                  directedExplorationTarget,
                  2,
                  explorationPath,
                  true,
                  allowFluidFallback,
                );
          const explorationOutcome = yield* Effect.raceFirst(
            advance.pipe(
              Effect.as({ type: "advanced" } as const),
              Effect.catchIf(
                (cause) => cause.operation === "pathfind" || cause.operation === "pathfindXZ",
                () => Effect.succeed({ type: "route-failed" } as const),
              ),
            ),
            waitForVisibleHuntingTarget(
              state,
              selector,
              attemptedTargets,
              locallyUnreachable,
              rememberedUnreachableTargets,
              targetPreference,
              aquaticHuntAllowed,
            ).pipe(
              Effect.map((huntTarget) => ({
                type: "target-visible" as const,
                target: huntTarget,
              })),
            ),
          );
          if (explorationOutcome.type === "target-visible") {
            confirmedVisibleTarget = explorationOutcome.target;
          }
          if (
            explorationOutcome.type === "route-failed" &&
            targetPreference?.fallbackToLocalExploration === true
          ) {
            yield* advanceExplorationFrontier(
              state,
              current.player.position,
              `${purpose}-local`,
              frontierScanRadius,
              explorationPath,
              true,
              true,
            ).pipe(
              Effect.catchIf(
                (cause) => cause.operation === "pathfind" || cause.operation === "pathfindXZ",
                () => Effect.void,
              ),
            );
          } else if (explorationOutcome.type === "route-failed") {
            const latest = yield* state.driver.observe;
            const recovered = yield* recoverLocalNavigationTrap(state, latest.player.position);
            if (
              !recovered &&
              !strandedAquaticFallback &&
              targetPreference?.allowCriticalAquaticTargets === true
            ) {
              strandedAquaticFallback = true;
              explorationHops = 0;
            }
          }
        }
        continue;
      }
      const targetId = `${target.connectionEpoch}:${target.networkId}`;
      const targetKey = `target:${targetId}`;
      const aquaticTarget = AQUATIC_FOOD_ENTITY_TYPES.has(target.entityType);
      const targetHuntingPath = aquaticTarget
        ? {
            ...survivalPath,
            allowMining: false,
            allowPlacing: false,
            avoidFluids: false,
            sprint: true,
          }
        : recoveringHealth
          ? {
              ...huntingPath,
              maxFallDistance: Math.min(
                huntingPath.maxFallDistance,
                WOUNDED_LAND_HUNT_MAXIMUM_FALL_DISTANCE,
              ),
            }
          : huntingPath;
      const targetExplorationPath = {
        ...targetHuntingPath,
        allowMining: false,
        allowPlacing: false,
        sprint: aquaticTarget,
      };
      const targetDistanceSquared = aquaticTarget
        ? distanceSquared(target.position, current.player.position)
        : horizontalDistanceSquared(target.position, current.player.position);
      if (!aquaticTarget && targetDistanceSquared > HUNT_ATTACK_APPROACH_RADIUS ** 2) {
        const targetDistance = Math.sqrt(targetDistanceSquared);
        const maximumApproachDistance =
          current.player.health < state.strategy.minimumHealth
            ? 12
            : HUNT_MAXIMUM_APPROACH_DISTANCE;
        const approachDistance = Math.min(
          maximumApproachDistance,
          targetDistance - (HUNT_ATTACK_APPROACH_RADIUS - HUNT_APPROACH_BUFFER),
        );
        const approachRatio = approachDistance / targetDistance;
        const approachTarget = {
          x:
            current.player.position.x +
            (target.position.x - current.player.position.x) * approachRatio,
          z:
            current.player.position.z +
            (target.position.z - current.player.position.z) * approachRatio,
        };
        const approach = state.driver.pathfindXZ(
          approachTarget.x,
          approachTarget.z,
          current.player.position.dimension,
          HUNT_APPROACH_GOAL_RADIUS,
          targetExplorationPath,
        );
        const approached = yield* approach.pipe(
          Effect.as(true),
          Effect.catchIf(
            (cause) => cause.operation === "pathfind" || cause.operation === "pathfindXZ",
            () => Effect.succeed(false),
          ),
        );
        if (!approached) {
          locallyUnreachable.add(targetKey);
          yield* persist(state, (currentCheckpoint) => ({
            ...currentCheckpoint,
            memory: {
              ...currentCheckpoint.memory,
              unreachable: [
                ...currentCheckpoint.memory.unreachable,
                {
                  key: targetKey,
                  value: target.position,
                  observedAt: new Date().toISOString(),
                  expiresAt: new Date(Date.now() + 600_000).toISOString(),
                  confidence: 1,
                },
              ].slice(-64),
            },
          }));
          continue;
        }
        continue;
      }
      attemptedTargets.add(targetId);
      const claim = yield* state.coordinator.claim({
        teamId: checkpoint.teamId,
        runId: checkpoint.runId,
        botId: checkpoint.botId,
        key: targetKey,
        purpose,
        ttlMs: Math.max(state.strategy.claimTtlMs, state.strategy.actionTimeoutMs + 5000),
      });
      if (claim === undefined) {
        continue;
      }
      yield* emit(state, {
        type: "team-claim-changed",
        claim,
        released: false,
      });
      yield* state.coordinator.publishDiscovery(checkpoint.teamId, {
        key: `resource:${target.entityType}:${target.connectionEpoch}:${target.networkId}`,
        kind: "resource",
        botId: checkpoint.botId,
        position: target.position,
        observedAt: target.observedAt,
        expiresAt: yield* DateTime.now.pipe(
          Effect.map((observedTime) =>
            DateTime.formatIso(DateTime.add(observedTime, { minutes: 2 })),
          ),
        ),
        confidence: 1,
        metadata: {
          entityType: target.entityType,
          connectionEpoch: target.connectionEpoch,
          networkId: target.networkId,
        },
      });
      // A hunted enderman fights back. A live bot hunting pearls struck one
      // with its shield down and died to three of its hits.
      const huntWithShield =
        SHIELD_BLOCKING_HOSTILE_ENTITY_TYPES.has(target.entityType) &&
        hasItemInInventoryOrEquipment(current, "minecraft:shield");
      if (huntWithShield && current.player.equipment["offhand"] !== "minecraft:shield") {
        yield* state.driver
          .withControl(
            state.driver.act({
              type: "equip-item",
              selector: { itemIds: ["minecraft:shield"] },
              equipmentSlot: "offhand",
            }),
          )
          .pipe(Effect.ignore);
      }
      const attack = attackEntity(state.driver, {
        target,
        targetUnavailableTimeoutSeconds: 3,
        selectBestWeapon: true,
        sprinting: true,
        ...(huntWithShield ? { useOffhandShield: true } : {}),
        path: targetHuntingPath,
      });
      const timedAttack = attack.pipe(
        Effect.timeoutOrElse({
          duration: aquaticTarget ? AQUATIC_HUNT_CHASE_TIMEOUT_MS : LAND_HUNT_CHASE_TIMEOUT_MS,
          orElse: () =>
            Effect.fail(
              new BeatGameDriverError({
                operation: "task.attack-entity",
                code: aquaticTarget ? "aquatic_chase_timeout" : "land_chase_timeout",
                retryable: true,
                message: aquaticTarget
                  ? `Stopped chasing moving aquatic target ${target.networkId}`
                  : `Stopped chasing moving land target ${target.networkId}`,
              }),
            ),
        }),
      );
      const boundedAttack = aquaticTarget
        ? Effect.raceFirst(timedAttack, waitForUnsafeAquaticHunt(state))
        : timedAttack;
      const defeated = yield* boundedAttack.pipe(
        // A land chase that runs out of time is remembered too: the entry
        // is ignored once the target moves away from where it was. A live
        // bot chased an enderman on an unreachable ledge for twenty seconds
        // at a time, 68 times, because no timed-out chase was remembered.
        Effect.tapError((cause) =>
          cause.code === "aquatic_chase_timeout" || cause.code === "aquatic_air_low"
            ? Effect.void
            : persist(state, (latestCheckpoint) => ({
                ...latestCheckpoint,
                memory: {
                  ...latestCheckpoint.memory,
                  unreachable: [
                    ...latestCheckpoint.memory.unreachable,
                    {
                      key: targetKey,
                      value: target.position,
                      observedAt: new Date().toISOString(),
                      expiresAt: new Date(Date.now() + 600_000).toISOString(),
                      confidence: 1,
                    },
                  ].slice(-64),
                },
              })).pipe(Effect.ignore),
        ),
        Effect.as(true),
        // oxlint-disable-next-line eslint/no-loop-func -- the attack finishes inside this iteration and records the retry target for the next one
        Effect.catchTag("BeatGameDriverError", (cause) => {
          if (cause.code === "aquatic_chase_timeout" || cause.code === "aquatic_air_low") {
            const attempts = (aquaticChaseAttempts.get(targetId) ?? 0) + 1;
            aquaticChaseAttempts.set(targetId, attempts);
            if (attempts < AQUATIC_HUNT_MAXIMUM_CHASE_ATTEMPTS) {
              const retry = Effect.sync(() => {
                attemptedTargets.delete(targetId);
                aquaticRetryTargetId = targetId;
              });
              return (
                cause.code === "aquatic_air_low"
                  ? retry.pipe(
                      Effect.andThen(state.driver.observe),
                      Effect.flatMap((latest) =>
                        emergencyAirAscent(state, latest.player.position, {
                          seekDrySurfaceAfterRecovery: false,
                        }),
                      ),
                    )
                  : retry
              ).pipe(Effect.as(false));
            }
          }
          return cause.code === "not_found" || cause.operation === "task.attack-entity"
            ? Effect.sync(() => {
                locallyUnreachable.add(targetKey);
              }).pipe(
                Effect.andThen(
                  current.player.position.dimension === "minecraft:overworld" &&
                    target.position.y - current.player.position.y > 6
                    ? escapeToOverworldSurface(state, current.player.position)
                    : Effect.void,
                ),
                Effect.as(false),
              )
            : Effect.fail(cause);
        }),
        Effect.ensuring(releaseActionClaim(state, claim)),
      );
      if (!defeated) {
        continue;
      }
      const shouldCrossFluidsForFoodDrops =
        expectedFoodDrops && current.player.position.dimension === "minecraft:overworld";
      yield* collectNearbyDrops(state.driver, {
        ...(expectedDropItemIds.length === 0 ? {} : { itemIds: expectedDropItemIds }),
        radius: 8,
        maximumDrops: 16,
        settleDelayMs: 500,
        path: shouldCrossFluidsForFoodDrops
          ? {
              ...targetHuntingPath,
              allowMining: true,
              avoidFluids: false,
            }
          : targetHuntingPath,
      });
      attacked += 1;
    }
  });
}

function waitForUnsafeAquaticHunt(state: RunState): Effect.Effect<never, BeatGameDriverError> {
  const poll = (): Effect.Effect<never, BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) => {
        if (observation.player.dead) {
          return Effect.fail(
            new BeatGameDriverError({
              operation: "task.attack-entity",
              code: "bot-dead",
              retryable: true,
              message: "The bot died while hunting an aquatic target",
            }),
          );
        }
        if (
          observation.player.maxAir > 0 &&
          observation.player.air <=
            Math.min(AQUATIC_HUNT_MINIMUM_AIR_TICKS, (observation.player.maxAir * 2) / 5)
        ) {
          return Effect.fail(
            new BeatGameDriverError({
              operation: "task.attack-entity",
              code: "aquatic_air_low",
              retryable: true,
              message: "Surfacing before continuing the aquatic hunt",
            }),
          );
        }
        return Effect.suspend(poll);
      }),
    );
  return Effect.suspend(poll);
}

function representativeStructureTargets(
  blocks: readonly BeatGameBlockObservation[],
  attemptedCells: ReadonlySet<string>,
  origin: BeatGamePosition,
): readonly BeatGameBlockObservation[] {
  const representatives = new Map<string, BeatGameBlockObservation>();
  for (const block of blocks) {
    const cell = structureCellKey(block.position);
    if (attemptedCells.has(cell)) {
      continue;
    }
    const existing = representatives.get(cell);
    if (
      existing === undefined ||
      horizontalDistanceSquared(block.position, origin) >
        horizontalDistanceSquared(existing.position, origin)
    ) {
      representatives.set(cell, block);
    }
  }
  return [...representatives.values()].toSorted(
    (left, right) =>
      horizontalDistanceSquared(right.position, origin) -
        horizontalDistanceSquared(left.position, origin) ||
      Math.abs(left.position.y - origin.y) - Math.abs(right.position.y - origin.y),
  );
}

function structureCellKey(position: BeatGamePosition): string {
  return `${position.dimension}:${Math.floor(position.x / 16)}:${Math.floor(
    position.y / 16,
  )}:${Math.floor(position.z / 16)}`;
}

function waitForVisibleHuntingTarget(
  state: RunState,
  selector: Parameters<BeatGameDriver["queryEntities"]>[0]["selector"],
  attemptedTargets: ReadonlySet<string>,
  locallyUnreachable: ReadonlySet<string>,
  rememberedUnreachableTargets: ReadonlyMap<string, BeatGameMemoryEntry<BeatGamePosition>>,
  targetPreference?: HuntTargetPreference,
  aquaticHuntAllowed = false,
): Effect.Effect<BeatGameEntityObservation, BeatGameDriverError> {
  const poll = (
    previouslyVisibleTargets: ReadonlySet<string>,
  ): Effect.Effect<BeatGameEntityObservation, BeatGameDriverError> =>
    Effect.sleep(Math.max(100, state.strategy.observationPollMs)).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((observation) =>
        state.driver
          .queryEntities({
            origin: observation.player.position,
            radius: state.strategy.entitySearchRadius,
            selector,
            maximumResults: 64,
          })
          .pipe(
            Effect.flatMap((targets) => {
              const visibleTargets = targets.filter(
                (target) =>
                  !isHuntingTargetUnreachable(
                    target,
                    observation.player.position,
                    locallyUnreachable,
                    rememberedUnreachableTargets,
                    Date.now(),
                  ) &&
                  !attemptedTargets.has(`${target.connectionEpoch}:${target.networkId}`) &&
                  isEligibleHuntingTarget(
                    target,
                    observation,
                    targetPreference,
                    aquaticHuntAllowed,
                  ),
              );
              const confirmed = visibleTargets.find((target) =>
                previouslyVisibleTargets.has(`${target.connectionEpoch}:${target.networkId}`),
              );
              return confirmed === undefined
                ? Effect.suspend(() =>
                    poll(
                      new Set(
                        visibleTargets.map(
                          (target) => `${target.connectionEpoch}:${target.networkId}`,
                        ),
                      ),
                    ),
                  )
                : Effect.succeed(confirmed);
            }),
          ),
      ),
    );
  return Effect.suspend(() => poll(new Set()));
}

function isHuntingTargetUnreachable(
  target: BeatGameEntityObservation,
  playerPosition: BeatGamePosition,
  locallyUnreachable: ReadonlySet<string>,
  rememberedUnreachableTargets: ReadonlyMap<string, BeatGameMemoryEntry<BeatGamePosition>>,
  now: number,
): boolean {
  const key = `target:${target.connectionEpoch}:${target.networkId}`;
  if (locallyUnreachable.has(key)) {
    return true;
  }
  const remembered = rememberedUnreachableTargets.get(key);
  if (remembered === undefined) {
    return false;
  }
  if (
    remembered.value.dimension !== target.position.dimension ||
    distanceSquared(remembered.value, target.position) > HUNT_UNREACHABLE_TARGET_RETRY_DISTANCE ** 2
  ) {
    return false;
  }
  const approachRadius = AQUATIC_FOOD_ENTITY_TYPES.has(target.entityType)
    ? AQUATIC_HUNT_ATTACK_APPROACH_RADIUS
    : HUNT_ATTACK_APPROACH_RADIUS;
  const observedAt = Date.parse(remembered.observedAt);
  if (!Number.isFinite(observedAt)) {
    return true;
  }
  const retryDelay =
    distanceSquared(playerPosition, target.position) > approachRadius ** 2
      ? HUNT_DISTANT_UNREACHABLE_RETRY_DELAY_MS
      : HUNT_NEARBY_UNREACHABLE_RETRY_DELAY_MS;
  return now - observedAt < retryDelay;
}

function isEligibleHuntingTarget(
  target: BeatGameEntityObservation,
  observation: BeatGameObservation,
  targetPreference?: HuntTargetPreference,
  aquaticHuntAllowed = false,
): boolean {
  const aquaticTarget = AQUATIC_FOOD_ENTITY_TYPES.has(target.entityType);
  return (
    isHuntingTargetWithinReach(
      target,
      observation,
      aquaticHuntAllowed,
      targetPreference?.maximumLandVerticalRise,
      targetPreference?.maximumLandVerticalDrop,
    ) &&
    (!aquaticTarget || aquaticHuntAllowed) &&
    (observation.player.health <= LETHAL_MELEE_DISENGAGE_HEALTH ||
      isWithinDirectedHuntDetour(
        observation.player.position,
        target.position,
        targetPreference?.explorationTarget,
      ))
  );
}

function isHuntingTargetWithinReach(
  target: BeatGameEntityObservation,
  observation: BeatGameObservation,
  aquaticHuntAllowed = false,
  maximumLandVerticalRise = LAND_HUNT_MAXIMUM_VERTICAL_DISTANCE,
  maximumLandVerticalDrop = LAND_HUNT_MAXIMUM_VERTICAL_DISTANCE,
): boolean {
  if (EMERGENCY_FOOD_ENTITY_TYPE_SET.has(target.entityType)) {
    return (
      Math.abs(target.position.y - observation.player.position.y) <=
        EMERGENCY_FOOD_MAXIMUM_VERTICAL_DISTANCE &&
      horizontalDistanceSquared(target.position, observation.player.position) <=
        EMERGENCY_FOOD_MAXIMUM_HORIZONTAL_DISTANCE ** 2
    );
  }
  if (AQUATIC_FOOD_ENTITY_TYPES.has(target.entityType)) {
    return (
      aquaticHuntAllowed &&
      Math.abs(target.position.y - observation.player.position.y) <=
        URGENT_AQUATIC_HUNT_MAXIMUM_VERTICAL_DISTANCE &&
      horizontalDistanceSquared(target.position, observation.player.position) <=
        URGENT_AQUATIC_HUNT_MAXIMUM_HORIZONTAL_DISTANCE ** 2
    );
  }
  const verticalDelta = target.position.y - observation.player.position.y;
  return verticalDelta <= maximumLandVerticalRise && verticalDelta >= -maximumLandVerticalDrop;
}

function shouldAllowAquaticHunt(
  observation: BeatGameObservation,
  minimumHealth: number,
  targetPreference?: HuntTargetPreference,
  completedDryExplorationLegs = 0,
): boolean {
  if (targetPreference?.allowCriticalAquaticTargets !== true) {
    return false;
  }
  if (observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL) {
    return observation.player.health > LETHAL_MELEE_DISENGAGE_HEALTH;
  }
  const minimumSafeHealth = Math.max(LETHAL_MELEE_DISENGAGE_HEALTH + 1, minimumHealth - 6);
  const exhaustedDrySearch =
    targetPreference.safeAquaticFallbackAfterExplorationLegs !== undefined &&
    completedDryExplorationLegs >= targetPreference.safeAquaticFallbackAfterExplorationLegs;
  if (exhaustedDrySearch) {
    const maximumFallbackFoodLevel =
      targetPreference.maximumSafeAquaticFoodLevel ?? URGENT_HUNGER_FOOD_LEVEL;
    return (
      (observation.player.health >= minimumHealth &&
        observation.player.food <= maximumFallbackFoodLevel) ||
      (observation.player.health > LETHAL_MELEE_DISENGAGE_HEALTH &&
        observation.player.health < minimumHealth)
    );
  }
  return (
    observation.player.health >= minimumSafeHealth &&
    (targetPreference.requireHealthRecoveryForSafeAquaticTargets !== true ||
      observation.player.health < minimumHealth) &&
    targetPreference.maximumSafeAquaticFoodLevel !== undefined &&
    observation.player.food <= targetPreference.maximumSafeAquaticFoodLevel
  );
}

function completedExplorationLegs(
  checkpoint: BeatGameCheckpoint,
  dimension: string,
  purpose: string,
): number {
  const frontier = checkpoint.memory.explorationFrontiers?.[`${dimension}:${purpose}`];
  return frontier?.progressVersion === 2 ? (frontier.totalAdvances ?? 0) : 0;
}

function survivalPathPolicy(
  path: BeatGameStrategy["path"],
  health: number,
  minimumHealth: number,
): BeatGameStrategy["path"] {
  return health < minimumHealth
    ? {
        ...path,
        avoidFluids: true,
        maxFallDistance: Math.min(path.maxFallDistance, MAXIMUM_DAMAGE_FREE_FALL_DISTANCE),
      }
    : path;
}

function needsOverworldSurfaceRecovery(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  if (position.dimension !== "minecraft:overworld") {
    return Effect.succeed(false);
  }
  return state.driver.sampleSurface(position, 4, 1).pipe(
    Effect.map(
      (columns) =>
        selectSurfaceColumn(columns, position) ??
        selectSwimmableSurfaceEscapeColumns(columns, position, 1)[0],
    ),
    Effect.flatMap((surface) =>
      surface === undefined || surface.surfaceY - Math.floor(position.y) < 2
        ? Effect.succeed(false)
        : isUnderCover(state.driver, position, surface.surfaceY),
    ),
  );
}

// The nearest dry column can stand higher than a swimmer without covering
// it. A live bot swam at the water line beside its own bridge, took the
// bridge top as the surface, and spent minutes trying to climb out of open
// water.
function isUnderCover(
  driver: BeatGameDriver,
  position: BeatGamePosition,
  surfaceY: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    if (
      !(yield* isPlayerInFluid(driver, position)) ||
      !(yield* hasBreathableHeadSpace(driver, position))
    ) {
      return true;
    }
    for (let y = Math.floor(position.y + 1.62) + 1; y <= surfaceY + 1; y += 1) {
      const block = yield* queryExactBlock(driver, {
        x: Math.floor(position.x),
        y,
        z: Math.floor(position.z),
        dimension: position.dimension,
      });
      if (block !== undefined && !block.replaceable) {
        return true;
      }
    }
    return false;
  });
}

function returnToOverworldSurface(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let columns = yield* state.driver.sampleSurface(position, AIR_ESCAPE_SURFACE_SEARCH_RADIUS, 1);
    let drySurfaces = selectSurfaceEscapeColumns(columns, position);
    let surfaces =
      drySurfaces.length > 0 ? drySurfaces : selectSwimmableSurfaceEscapeColumns(columns, position);
    if (surfaces.length === 0) {
      return yield* new BeatGameDriverError({
        operation: "pathfind",
        code: "unreachable",
        retryable: true,
        message: "No loaded traversable surface is available for recovery",
      });
    }
    let targets: readonly BeatGamePosition[] = surfaces.map((surface) => ({
      x: surface.x + 0.5,
      y: surface.surfaceY + 1,
      z: surface.z + 0.5,
      dimension: position.dimension,
    }));
    const startingInFluid = yield* isPlayerInFluid(state.driver, position);
    const directSurface = targets.find(
      (target) =>
        Math.floor(target.x) === Math.floor(position.x) &&
        Math.floor(target.z) === Math.floor(position.z) &&
        target.y - position.y >= 2 &&
        target.y - position.y <= NIGHT_SHELTER_DEPTH + 2,
    );
    if (!startingInFluid && directSurface !== undefined) {
      const observation = yield* state.driver.observe;
      const overhead = yield* queryExactBlock(state.driver, {
        x: Math.floor(position.x),
        y: Math.floor(position.y) + 2,
        z: Math.floor(position.z),
        dimension: position.dimension,
      });
      if (overhead !== undefined && !overhead.replaceable && overhead.diggable) {
        yield* leaveCoveredVerticalShaft(state, observation, overhead);
        return undefined;
      }
    }
    if (!startingInFluid) {
      yield* prepareSurfaceEscapePickaxe(state, position, targets);
      const directRoute = yield* pathfindToFirstReachableSurface(
        state,
        targets.slice(0, SURFACE_RECOVERY_DIRECT_PATH_CANDIDATES),
        0,
        SURFACE_RECOVERY_DIRECT_PATH_EXECUTION_TIMEOUT_MS,
        {
          ...state.strategy.path,
          allowMining: true,
          allowPlacing: true,
          avoidFluids: drySurfaces.length > 0,
          maxSearchTimeMs: Math.min(
            state.strategy.path.maxSearchTimeMs,
            SURFACE_RECOVERY_DIRECT_PATH_SEARCH_TIMEOUT_MS,
          ),
        },
      ).pipe(Effect.result);
      if (directRoute._tag === "Success") {
        const reached = yield* state.driver.observe;
        if (
          !(yield* isPlayerInFluid(state.driver, reached.player.position)) &&
          !(yield* needsOverworldSurfaceRecovery(state, reached.player.position))
        ) {
          return undefined;
        }
      }
    }
    if (!startingInFluid) {
      let stairPosition = (yield* state.driver.observe).player.position;
      for (let segment = 0; segment < SURFACE_RECOVERY_MAXIMUM_STAIR_SEGMENTS; segment += 1) {
        if (!(yield* needsOverworldSurfaceRecovery(state, stairPosition))) {
          return undefined;
        }
        const climbed = yield* excavateDryShaftRecoveryStaircase(
          state,
          targets.map(({ y }) => y),
        );
        if (!climbed) {
          break;
        }
        const latest = yield* state.driver.observe;
        if (latest.player.dead || latest.player.position.dimension !== position.dimension) {
          return undefined;
        }
        if (latest.player.position.y < stairPosition.y + 0.5) {
          break;
        }
        stairPosition = latest.player.position;
        columns = yield* state.driver.sampleSurface(
          stairPosition,
          AIR_ESCAPE_SURFACE_SEARCH_RADIUS,
          1,
        );
        drySurfaces = selectSurfaceEscapeColumns(columns, stairPosition);
        surfaces =
          drySurfaces.length > 0
            ? drySurfaces
            : selectSwimmableSurfaceEscapeColumns(columns, stairPosition);
        if (surfaces.length === 0) {
          break;
        }
        targets = surfaces.map((surface) => ({
          x: surface.x + 0.5,
          y: surface.surfaceY + 1,
          z: surface.z + 0.5,
          dimension: position.dimension,
        }));
      }
    }
    const current = yield* state.driver.observe;
    if (current.player.dead) {
      return undefined;
    }
    columns = yield* state.driver.sampleSurface(
      current.player.position,
      AIR_ESCAPE_SURFACE_SEARCH_RADIUS,
      1,
    );
    drySurfaces = selectSurfaceEscapeColumns(columns, current.player.position);
    surfaces =
      drySurfaces.length > 0
        ? drySurfaces
        : selectSwimmableSurfaceEscapeColumns(columns, current.player.position);
    targets = surfaces.map((surface) => ({
      x: surface.x + 0.5,
      y: surface.surfaceY + 1,
      z: surface.z + 0.5,
      dimension: current.player.position.dimension,
    }));
    if (targets.length === 0) {
      return yield* new BeatGameDriverError({
        operation: "pathfind",
        code: "unreachable",
        retryable: true,
        message: "No loaded traversable surface is available for recovery",
      });
    }
    yield* prepareSurfaceEscapePickaxe(state, current.player.position, targets);
    yield* pathfindToFirstReachableSurface(state, targets, 0, undefined, {
      ...state.strategy.path,
      allowMining: true,
      allowPlacing: true,
      avoidFluids: drySurfaces.length > 0 && !startingInFluid,
    });
    return undefined;
  });
}

function prepareSurfaceEscapePickaxe(
  state: RunState,
  position: BeatGamePosition,
  targets: readonly BeatGamePosition[],
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const verticalRise = targets.reduce(
      (maximum, target) => Math.max(maximum, target.y - position.y),
      0,
    );
    if (verticalRise <= 2) {
      return;
    }

    const observation = yield* state.driver.observe;
    if (
      observation.player.dead ||
      (yield* isPlayerInFluid(state.driver, observation.player.position))
    ) {
      return;
    }

    const useIronPickaxe = (observation.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3;
    const maximumDurability = useIronPickaxe
      ? IRON_PICKAXE_MAXIMUM_DURABILITY
      : STONE_PICKAXE_MAXIMUM_DURABILITY;
    yield* ensureMiningPickaxe(
      state,
      observation,
      useIronPickaxe ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe",
      useIronPickaxe ? DURABLE_MINING_PICKAXE_ITEM_IDS : STONE_OR_BETTER_MINING_PICKAXE_ITEM_IDS,
      Math.min(
        maximumDurability,
        Math.ceil(verticalRise * SURFACE_ESCAPE_PICKAXE_DURABILITY_PER_LEVEL),
      ),
    ).pipe(Effect.catchTag("BeatGameDriverError", () => Effect.void));
  });
}

function climbToHigherOverworldGround(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  if (position.dimension !== "minecraft:overworld" || position.y > OVERWORLD_LOW_GROUND_MAX_Y) {
    return Effect.succeed(false);
  }
  return state.driver.sampleSurface(position, AIR_ESCAPE_SURFACE_SEARCH_RADIUS, 1).pipe(
    Effect.map((columns) =>
      selectElevatedSurfaceColumns(columns, position).map((surface) => ({
        x: surface.x + 0.5,
        y: surface.surfaceY + 1,
        z: surface.z + 0.5,
        dimension: position.dimension,
      })),
    ),
    Effect.flatMap((targets) =>
      targets.length === 0
        ? Effect.succeed(false)
        : pathfindToFirstReachableSurface(state, targets, 0, ELEVATED_SURFACE_PATH_TIMEOUT_MS).pipe(
            Effect.as(true),
            Effect.catchIf(
              (cause) => cause.operation === "pathfind",
              () => Effect.succeed(false),
            ),
          ),
    ),
  );
}

function escapeEnvironmentalDamage(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<void, BeatGameDriverError> {
  const playerBlock = {
    x: Math.floor(position.x),
    y: Math.floor(position.y),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
  const occupiedPositions = [{ ...playerBlock, y: playerBlock.y + 1 }, playerBlock];
  return Effect.gen(function* () {
    // A live run burned to death for twelve seconds while this escape walked
    // toward the surface. Lava and fire need their own response first.
    const burning = yield* state.driver.observe;
    // Magma hurts without setting the bot alight, so burning ground is
    // checked before anything else. A live bot stood on magma in the Nether
    // through ten interrupts and burned to death.
    if (!burning.player.dead && (yield* stepOffBurningGround(state, burning))) {
      return;
    }
    if (
      !burning.player.dead &&
      (burning.player.fireTicks > 0 ||
        (yield* isPlayerInLava(state.driver, burning.player.position)))
    ) {
      yield* escapeLava(state, burning);
      const escaped = yield* state.driver.observe;
      yield* extinguishFire(state, escaped);
      return;
    }
    // Drowned throw tridents from under the water where the bot cannot see
    // them, and a live run died to three throws while it treaded water. Dry
    // land is out of their reach.
    const lastDamage = yield* Ref.get(state.lastDamage);
    if (
      !burning.player.dead &&
      lastDamage?.damageType !== undefined &&
      PROJECTILE_DAMAGE_TYPES.has(lastDamage.damageType) &&
      (yield* isPlayerInFluid(state.driver, burning.player.position))
    ) {
      yield* swimToNearbyDrySurface(state, true);
      return;
    }
    const occupiedBlocks = yield* Effect.forEach(
      occupiedPositions,
      (occupiedPosition) => queryExactBlock(state.driver, occupiedPosition),
      { concurrency: "unbounded" },
    );
    const diggableObstructions = occupiedBlocks.filter(
      (block): block is BeatGameBlockObservation =>
        block !== undefined && !block.replaceable && block.diggable,
    );
    if (diggableObstructions.length > 0) {
      yield* state.driver.withControl(
        Effect.gen(function* () {
          const observation = yield* state.driver.observe;
          if (hasMiningPickaxe(observation)) {
            yield* state.driver.act({
              type: "select-item",
              selector: { itemIds: MINING_PICKAXE_ITEM_IDS },
            });
          }
          for (const obstruction of diggableObstructions) {
            yield* state.driver.act({
              type: "dig-block",
              position: obstruction.position,
            });
          }
        }),
      );
      return;
    }
    yield* recoverLocalNavigationTrap(state, position).pipe(Effect.asVoid);
  });
}

function recoverLocalNavigationTrap(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  if (position.dimension !== "minecraft:overworld") {
    return Effect.succeed(false);
  }
  return Effect.gen(function* () {
    const columns = yield* state.driver.sampleSurface(
      position,
      AIR_ESCAPE_SURFACE_SEARCH_RADIUS,
      1,
    );
    const targets = (() => {
      const stableColumns = selectStableSurfaceEscapeColumns(columns, position);
      const safeColumns = selectSurfaceEscapeColumns(columns, position);
      const nonTrivial = (surface: (typeof safeColumns)[number]) => {
        const deltaY = surface.surfaceY + 1 - position.y;
        return surfaceHorizontalDistanceSquared(surface, position) + deltaY * deltaY > 1.5 ** 2;
      };
      const separated = (surface: (typeof safeColumns)[number]) =>
        surfaceHorizontalDistanceSquared(surface, position) >=
        LOCAL_NAVIGATION_RECOVERY_MINIMUM_DISTANCE ** 2;
      const orderedColumns = [
        ...stableColumns
          .filter((surface) => nonTrivial(surface))
          .filter((surface) => separated(surface)),
        ...safeColumns
          .filter((surface) => nonTrivial(surface))
          .filter((surface) => separated(surface)),
        ...stableColumns.filter((surface) => nonTrivial(surface)),
        ...safeColumns.filter((surface) => nonTrivial(surface)),
      ];
      const uniqueColumns = new Map(
        orderedColumns.map((surface) => [`${surface.x}:${surface.surfaceY}:${surface.z}`, surface]),
      );
      return [...uniqueColumns.values()].map((surface) => ({
        x: surface.x + 0.5,
        y: surface.surfaceY + 1,
        z: surface.z + 0.5,
        dimension: position.dimension,
      }));
    })();
    if (targets.length === 0) {
      return false;
    }
    const pathRecovered = yield* pathfindToFirstReachableSurface(
      state,
      targets,
      0,
      LOCAL_NAVIGATION_RECOVERY_TIMEOUT_MS,
      {
        ...state.strategy.path,
        allowMining: true,
        allowPlacing: true,
        avoidFluids: true,
        sprint: false,
        maxSearchTimeMs: Math.min(
          state.strategy.path.maxSearchTimeMs,
          LOCAL_NAVIGATION_RECOVERY_MAX_SEARCH_TIME_MS,
        ),
      },
      DRY_SURFACE_APPROACH_RADIUS,
    ).pipe(
      Effect.as(true),
      Effect.catchIf(
        (cause) => cause.operation === "pathfind",
        () => Effect.succeed(false),
      ),
    );
    if (pathRecovered) {
      return true;
    }
    return yield* excavateDryShaftRecoveryStaircase(
      state,
      targets.map(({ y }) => y),
    );
  });
}

function recoverIfLocallyEnclosed(
  state: RunState,
  message: string,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    if (
      observation.player.dead ||
      observation.player.position.dimension !== "minecraft:overworld" ||
      (yield* isPlayerInFluid(state.driver, observation.player.position)) ||
      !(yield* isLocallyEnclosed(state.driver, observation.player.position))
    ) {
      return false;
    }
    yield* emit(state, {
      type: "diagnostic",
      message,
      data: { position: observation.player.position },
    });
    return yield* recoverLocalNavigationTrap(state, observation.player.position);
  });
}

function isLocallyEnclosed(
  driver: BeatGameDriver,
  position: BeatGamePosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  const origin = {
    x: Math.floor(position.x),
    y: Math.floor(position.y),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
  const directions = [
    { x: 1, z: 0 },
    { x: -1, z: 0 },
    { x: 0, z: 1 },
    { x: 0, z: -1 },
  ] as const;
  return Effect.forEach(
    directions,
    ({ x, z }) =>
      Effect.all(
        [
          queryExactBlock(driver, {
            ...origin,
            x: origin.x + x,
            z: origin.z + z,
          }),
          queryExactBlock(driver, {
            ...origin,
            x: origin.x + x,
            y: origin.y + 1,
            z: origin.z + z,
          }),
        ],
        { concurrency: 2 },
      ).pipe(Effect.map(([feet, head]) => isDryWalkingSpace(feet) && isDryWalkingSpace(head))),
    { concurrency: 4 },
  ).pipe(Effect.map((exits) => exits.every((open) => !open)));
}

function isDryWalkingSpace(block: BeatGameBlockObservation | undefined): boolean {
  return (
    block !== undefined &&
    !isPlayerFluidBlock(block.blockId) &&
    block.properties["waterlogged"] !== "true" &&
    (block.replaceable || block.solid === false)
  );
}

interface DryShaftRecoveryStep {
  readonly feet: BeatGameBlockPosition;
  readonly head: BeatGameBlockPosition;
  readonly jumpClearance: BeatGameBlockPosition;
  readonly support: BeatGameBlockPosition;
  readonly lowerAnchor: BeatGameBlockPosition;
  readonly previousSupport: BeatGameBlockPosition;
  readonly placeLowerAnchor: boolean;
  readonly placeSupport: boolean;
}

interface DryShaftRecoveryRoute {
  readonly steps: readonly DryShaftRecoveryStep[];
  readonly placedBlocks: number;
}

function excavateDryShaftRecoveryStaircase(
  state: RunState,
  candidateSurfaceY: readonly number[],
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    const position = yield* standingSupportColumn(state.driver, observation.player.position);
    if (position === undefined) {
      return false;
    }
    const startingY = Math.floor(position.y);
    const surfaceY = [...candidateSurfaceY]
      .filter((y) => y - startingY >= 2)
      .toSorted((left, right) => left - right)[0];
    if (surfaceY === undefined) {
      return false;
    }
    const targetY = Math.min(surfaceY, startingY + DRY_SHAFT_RECOVERY_MAXIMUM_RISE);
    const directions = [
      { x: 1, z: 0 },
      { x: -1, z: 0 },
      { x: 0, z: 1 },
      { x: 0, z: -1 },
    ] as const;
    const supportBlocks = DRY_SHAFT_RECOVERY_SUPPORT_ITEM_IDS.reduce(
      (count, itemId) => count + (observation.inventory.counts[itemId] ?? 0),
      0,
    );
    let route: DryShaftRecoveryRoute | undefined;
    for (const direction of directions) {
      const candidate = yield* inspectDryShaftRecoveryRoute(
        state.driver,
        position,
        targetY,
        direction,
        supportBlocks,
      );
      if (
        candidate !== undefined &&
        (route === undefined ||
          candidate.steps.length > route.steps.length ||
          (candidate.steps.length === route.steps.length &&
            candidate.placedBlocks < route.placedBlocks))
      ) {
        route = candidate;
      }
    }
    if (route === undefined) {
      return false;
    }

    for (const step of route.steps) {
      yield* ensureDryShaftRecoverySupport(state, step);
      const beforeExcavation = yield* state.driver.observe;
      if (hasMiningPickaxe(beforeExcavation)) {
        yield* state.driver.act({
          type: "select-item",
          selector: { itemIds: MINING_PICKAXE_ITEM_IDS },
        });
      }
      for (const obstruction of [step.jumpClearance, step.head, step.feet]) {
        const block = yield* queryExactBlock(state.driver, obstruction);
        if (block !== undefined && !block.replaceable) {
          yield* state.driver.act({
            type: "dig-block",
            position: obstruction,
          });
        }
      }
      const target: BeatGamePosition = {
        x: step.feet.x + 0.5,
        y: step.feet.y,
        z: step.feet.z + 0.5,
        dimension: step.feet.dimension,
      };
      yield* state.driver
        .pathfind(target, DRY_SHAFT_RECOVERY_STEP_RADIUS, {
          ...state.strategy.path,
          allowMining: false,
          allowPlacing: false,
          avoidFluids: true,
          sprint: false,
          maxSearchTimeMs: Math.min(
            state.strategy.path.maxSearchTimeMs,
            LOCAL_NAVIGATION_RECOVERY_MAX_SEARCH_TIME_MS,
          ),
        })
        .pipe(
          Effect.timeoutOrElse({
            duration: DRY_SHAFT_RECOVERY_STEP_TIMEOUT_MS,
            orElse: () =>
              Effect.fail(
                new BeatGameDriverError({
                  operation: "recover-dry-shaft",
                  code: "unreachable",
                  retryable: true,
                  message: `Timed out climbing the recovery stair at ${positionKey(step.feet)}`,
                }),
              ),
          }),
        );
      const current = yield* state.driver.observe;
      if (
        Math.floor(current.player.position.x) !== step.feet.x ||
        Math.floor(current.player.position.z) !== step.feet.z ||
        current.player.position.y < step.feet.y - 0.25
      ) {
        return yield* new BeatGameDriverError({
          operation: "recover-dry-shaft",
          code: "no-progress",
          retryable: true,
          message: `The bot did not reach recovery stair ${positionKey(step.feet)}`,
        });
      }
    }
    return true;
  });
}

/// The block a player stands on is not always the one under its center: its
/// body overhangs an edge by up to 0.3 blocks. A live bot stood 0.07 past the
/// edge of a stone block over air, its staircase found no support under its
/// center, and the climb out of a mine never started. Returns the center of a
/// column the bot covers that has support under it.
function standingSupportColumn(
  driver: BeatGameDriver,
  position: BeatGamePosition,
): Effect.Effect<BeatGamePosition | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const offsets = [0, -PLAYER_HALF_WIDTH + HITBOX_EPSILON, PLAYER_HALF_WIDTH - HITBOX_EPSILON];
    const columns = new Map<string, { readonly x: number; readonly z: number }>();
    for (const dx of offsets) {
      for (const dz of offsets) {
        const x = Math.floor(position.x + dx);
        const z = Math.floor(position.z + dz);
        columns.set(`${x}:${z}`, { x, z });
      }
    }
    for (const { x, z } of columns.values()) {
      const support = yield* queryExactBlock(driver, {
        x,
        y: Math.floor(position.y) - 1,
        z,
        dimension: position.dimension,
      });
      if (isStableDryShaftSupport(support)) {
        return { ...position, x: x + 0.5, z: z + 0.5 };
      }
    }
    return undefined;
  });
}

function inspectDryShaftRecoveryRoute(
  driver: BeatGameDriver,
  position: BeatGamePosition,
  targetY: number,
  direction: Readonly<{ x: number; z: number }>,
  maximumPlacedBlocks: number,
): Effect.Effect<DryShaftRecoveryRoute | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const originX = Math.floor(position.x);
    const originZ = Math.floor(position.z);
    const startingY = Math.floor(position.y);
    const steps: DryShaftRecoveryStep[] = [];
    let placedBlocks = 0;
    let previousSupport: BeatGameBlockPosition = {
      x: originX,
      y: startingY - 1,
      z: originZ,
      dimension: position.dimension,
    };
    const startingSupport = yield* queryExactBlock(driver, previousSupport);
    if (!isStableDryShaftSupport(startingSupport)) {
      return undefined;
    }
    for (let rise = 1; startingY + rise <= targetY; rise += 1) {
      const feet: BeatGameBlockPosition = {
        x: originX + direction.x * rise,
        y: startingY + rise,
        z: originZ + direction.z * rise,
        dimension: position.dimension,
      };
      const head = { ...feet, y: feet.y + 1 };
      const support = { ...feet, y: feet.y - 1 };
      const lowerAnchor = { ...support, y: support.y - 1 };
      const previousFeet: BeatGameBlockPosition = {
        x: originX + direction.x * (rise - 1),
        y: startingY + rise - 1,
        z: originZ + direction.z * (rise - 1),
        dimension: position.dimension,
      };
      const jumpClearance = { ...previousFeet, y: previousFeet.y + 2 };
      const [supportBlock, feetBlock, headBlock, jumpClearanceBlock, lowerAnchorBlock] =
        yield* Effect.all(
          [
            queryExactBlock(driver, support),
            queryExactBlock(driver, feet),
            queryExactBlock(driver, head),
            queryExactBlock(driver, jumpClearance),
            queryExactBlock(driver, lowerAnchor),
          ],
          { concurrency: 5 },
        );
      if (
        !isSafeDryShaftSpace(feetBlock) ||
        !isSafeDryShaftSpace(headBlock) ||
        !isSafeDryShaftSpace(jumpClearanceBlock)
      ) {
        break;
      }

      const placeSupport = !isStableDryShaftSupport(supportBlock);
      if (placeSupport && !isReplaceableDryShaftSupportCell(supportBlock)) {
        break;
      }
      const placeLowerAnchor = placeSupport && !isStableDryShaftSupport(lowerAnchorBlock);
      if (placeLowerAnchor && !isReplaceableDryShaftSupportCell(lowerAnchorBlock)) {
        break;
      }
      const requiredBlocks = Number(placeSupport) + Number(placeLowerAnchor);
      if (placedBlocks + requiredBlocks > maximumPlacedBlocks) {
        break;
      }
      placedBlocks += requiredBlocks;
      steps.push({
        feet,
        head,
        jumpClearance,
        support,
        lowerAnchor,
        previousSupport,
        placeLowerAnchor,
        placeSupport,
      });
      previousSupport = support;
    }
    return steps.length === 0 ? undefined : { steps, placedBlocks };
  });
}

function ensureDryShaftRecoverySupport(
  state: RunState,
  step: DryShaftRecoveryStep,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    if (!step.placeSupport) {
      return;
    }
    if (!step.placeLowerAnchor) {
      yield* placeDryShaftRecoveryBlock(state.driver, step.lowerAnchor, "up", step.support);
      return;
    }
    // The lower anchor goes against the side of the block the bot stands on.
    // The server only accepts a face the eyes can see, so the bot sneaks to
    // the edge first, like a player bridging, and keeps sneaking so it does
    // not walk off.
    yield* Effect.gen(function* () {
      yield* sneakPastSupportEdge(state, step.previousSupport, step.lowerAnchor);
      yield* placeDryShaftRecoveryBlock(
        state.driver,
        step.previousSupport,
        horizontalPlacementFace(step.previousSupport, step.lowerAnchor),
        step.lowerAnchor,
      );
      yield* placeDryShaftRecoveryBlock(state.driver, step.lowerAnchor, "up", step.support);
    }).pipe(Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore)));
  });
}

// Sneaking stops a player at the edge with the hitbox still on the block,
// up to 0.3 blocks past the face, so 0.55 from the center puts the eyes in
// front of it.
const SUPPORT_EDGE_EYE_OFFSET = 0.55;
const SUPPORT_EDGE_POLL_MS = 50;
const SUPPORT_EDGE_MAXIMUM_POLLS = 30;
const SUPPORT_EDGE_STALL_POLLS = 6;

function sneakPastSupportEdge(
  state: RunState,
  standing: BeatGameBlockPosition,
  toward: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  const direction = { x: toward.x - standing.x, z: toward.z - standing.z };
  const offset = (position: BeatGamePosition) =>
    (position.x - (standing.x + 0.5)) * direction.x +
    (position.z - (standing.z + 0.5)) * direction.z;
  return Effect.gen(function* () {
    const start = yield* state.driver.observe;
    if (offset(start.player.position) >= SUPPORT_EDGE_EYE_OFFSET) {
      return;
    }
    const rotation = rotationToward(start.player.position, {
      ...start.player.position,
      x: standing.x + 0.5 + direction.x,
      z: standing.z + 0.5 + direction.z,
    });
    yield* state.driver.act({ type: "look", yaw: rotation.yaw, pitch: 70 });
    yield* state.driver.act({ type: "set-movement", forward: true, sneak: true });
    let best = offset(start.player.position);
    let stalled = 0;
    for (let poll = 0; poll < SUPPORT_EDGE_MAXIMUM_POLLS; poll += 1) {
      yield* Effect.sleep(SUPPORT_EDGE_POLL_MS);
      const current = offset((yield* state.driver.observe).player.position);
      if (current >= SUPPORT_EDGE_EYE_OFFSET) {
        break;
      }
      stalled = current > best + 0.01 ? 0 : stalled + 1;
      best = Math.max(best, current);
      if (stalled >= SUPPORT_EDGE_STALL_POLLS) {
        break;
      }
    }
    yield* state.driver.act({ type: "set-movement", forward: false, sneak: true });
  });
}

function dryShaftRecoveryMaterial(
  driver: BeatGameDriver,
): Effect.Effect<string, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      const material = DRY_SHAFT_RECOVERY_SUPPORT_ITEM_IDS.find(
        (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
      );
      return material === undefined
        ? Effect.fail(
            new BeatGameDriverError({
              operation: "recover-dry-shaft",
              code: "not-found",
              retryable: true,
              message: "No solid block is available to support the recovery stair",
            }),
          )
        : Effect.succeed(material);
    }),
  );
}

function placeDryShaftRecoveryBlock(
  driver: BeatGameDriver,
  against: BeatGameBlockPosition,
  face: BeatGameBlockFace,
  expected: BeatGameBlockPosition,
  attemptsRemaining = 3,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const existing = yield* queryExactBlock(driver, expected);
    if (isStableDryShaftSupport(existing)) {
      return undefined;
    }
    // An unconfirmed attempt may still have used up a block, so every attempt
    // checks the inventory again.
    const material = yield* dryShaftRecoveryMaterial(driver);
    const placement = yield* driver
      .act({
        type: "select-item",
        selector: { itemIds: [material] },
      })
      .pipe(
        Effect.andThen(
          driver.act({
            type: "place-block",
            against,
            face,
            hand: "main",
          }),
        ),
        Effect.result,
      );
    for (let confirmation = 0; confirmation < 10; confirmation += 1) {
      const placed = yield* queryExactBlock(driver, expected);
      if (isStableDryShaftSupport(placed)) {
        return undefined;
      }
      yield* Effect.sleep(50);
    }
    if (attemptsRemaining > 1) {
      return yield* placeDryShaftRecoveryBlock(
        driver,
        against,
        face,
        expected,
        attemptsRemaining - 1,
      );
    }
    // The server can refuse a placement it cannot see, which it reports as
    // final. Another stair or route may still work, so the bot replans.
    return yield* new BeatGameDriverError({
      operation: "recover-dry-shaft",
      code: "no-progress",
      retryable: true,
      message: `The recovery support was not placed at ${positionKey(expected)}${
        placement._tag === "Failure" ? `: ${placement.failure.message}` : ""
      }`,
    });
  });
}

function horizontalPlacementFace(
  from: BeatGameBlockPosition,
  to: BeatGameBlockPosition,
): BeatGameBlockFace {
  if (from.dimension !== to.dimension || from.y !== to.y) {
    throw new RangeError("Recovery support blocks must be horizontal neighbors");
  }
  if (to.x === from.x + 1 && to.z === from.z) {
    return "east";
  }
  if (to.x === from.x - 1 && to.z === from.z) {
    return "west";
  }
  if (to.z === from.z + 1 && to.x === from.x) {
    return "south";
  }
  if (to.z === from.z - 1 && to.x === from.x) {
    return "north";
  }
  throw new RangeError("Recovery support blocks must be adjacent");
}

function isStableDryShaftSupport(block: BeatGameBlockObservation | undefined): boolean {
  return (
    block !== undefined &&
    !block.replaceable &&
    block.solid !== false &&
    !isPlayerFluidBlock(block.blockId) &&
    block.properties["waterlogged"] !== "true" &&
    !isGravityAffectedBlockId(block.blockId)
  );
}

function isReplaceableDryShaftSupportCell(block: BeatGameBlockObservation | undefined): boolean {
  return block !== undefined && block.replaceable && block.blockId !== "minecraft:lava";
}

function isSafeDryShaftSpace(block: BeatGameBlockObservation | undefined): boolean {
  return (
    block !== undefined &&
    !isPlayerFluidBlock(block.blockId) &&
    block.properties["waterlogged"] !== "true" &&
    (block.replaceable || block.diggable)
  );
}

function pathfindToFirstReachableSurface(
  state: RunState,
  targets: readonly BeatGamePosition[],
  index = 0,
  attemptTimeoutMs?: number,
  pathOverride?: BeatGameStrategy["path"],
  goalRadius = 1.5,
): Effect.Effect<void, BeatGameDriverError> {
  const path = pathOverride ?? {
    ...state.strategy.path,
    allowMining: true,
    allowPlacing: true,
    avoidFluids: true,
  };
  const target = targets[index];
  if (target === undefined) {
    return Effect.void;
  }
  const pathfind = state.driver.pathfind(target, goalRadius, path);
  const boundedPathfind =
    attemptTimeoutMs === undefined
      ? pathfind
      : pathfind.pipe(
          Effect.timeoutOrElse({
            duration: attemptTimeoutMs,
            orElse: () =>
              Effect.fail(
                new BeatGameDriverError({
                  operation: "pathfind",
                  code: "unreachable",
                  retryable: true,
                  message: `Timed out climbing toward ${target.x}, ${target.y}, ${target.z}`,
                }),
              ),
          }),
        );
  const airSafePathfind = Effect.raceFirst(
    boundedPathfind,
    waitForUnsafeAir(state).pipe(
      Effect.flatMap((outcome) =>
        Effect.fail(
          new BeatGameDriverError({
            operation: "pathfind",
            code: outcome === "dead" ? "bot-dead" : "unsafe-air",
            retryable: true,
            message:
              outcome === "dead"
                ? "The bot died while pathfinding toward the surface"
                : "Pathfinding toward the surface was cancelled before the bot ran out of air",
          }),
        ),
      ),
    ),
  );
  return airSafePathfind.pipe(
    Effect.catchIf(
      (cause) =>
        cause.operation === "pathfind" &&
        cause.code === "unreachable" &&
        index + 1 < targets.length,
      () =>
        pathfindToFirstReachableSurface(
          state,
          targets,
          index + 1,
          attemptTimeoutMs,
          path,
          goalRadius,
        ),
    ),
  );
}

function selectSurfaceColumn(
  columns: readonly {
    readonly x: number;
    readonly z: number;
    readonly loaded: boolean;
    readonly surfaceY?: number;
    readonly blockId?: string;
  }[],
  position: BeatGamePosition,
): { readonly x: number; readonly z: number; readonly surfaceY: number } | undefined {
  return selectSurfaceEscapeColumns(columns, position, 1)[0];
}

function selectSurfaceEscapeColumns(
  columns: readonly {
    readonly x: number;
    readonly z: number;
    readonly loaded: boolean;
    readonly surfaceY?: number;
    readonly blockId?: string;
  }[],
  position: BeatGamePosition,
  maximumResults = AIR_ESCAPE_DIRECTION_SECTORS,
): readonly {
  readonly x: number;
  readonly z: number;
  readonly surfaceY: number;
}[] {
  const candidates = columns
    .flatMap((column) =>
      column.loaded && column.surfaceY !== undefined && !isUnsafeSurfaceBlock(column.blockId)
        ? [{ x: column.x, z: column.z, surfaceY: column.surfaceY }]
        : [],
    )
    .toSorted(
      (left, right) =>
        surfaceHorizontalDistanceSquared(left, position) -
        surfaceHorizontalDistanceSquared(right, position),
    );
  return selectDirectionalSurfaceColumns(candidates, position, maximumResults);
}

function selectSwimmableSurfaceEscapeColumns(
  columns: readonly {
    readonly x: number;
    readonly z: number;
    readonly loaded: boolean;
    readonly surfaceY?: number;
    readonly blockId?: string;
  }[],
  position: BeatGamePosition,
  maximumResults = AIR_ESCAPE_DIRECTION_SECTORS,
): readonly {
  readonly x: number;
  readonly z: number;
  readonly surfaceY: number;
}[] {
  const candidates = columns
    .flatMap((column) =>
      column.loaded && column.surfaceY !== undefined && isSwimmableSurfaceBlock(column.blockId)
        ? [{ x: column.x, z: column.z, surfaceY: column.surfaceY }]
        : [],
    )
    .toSorted(
      (left, right) =>
        surfaceHorizontalDistanceSquared(left, position) -
        surfaceHorizontalDistanceSquared(right, position),
    );
  return selectDirectionalSurfaceColumns(candidates, position, maximumResults);
}

function selectDirectionalSurfaceColumns(
  candidates: readonly {
    readonly x: number;
    readonly z: number;
    readonly surfaceY: number;
  }[],
  position: BeatGamePosition,
  maximumResults: number,
): readonly {
  readonly x: number;
  readonly z: number;
  readonly surfaceY: number;
}[] {
  const selected = new Map<number, (typeof candidates)[number]>();
  for (const candidate of candidates) {
    const angle = Math.atan2(candidate.z + 0.5 - position.z, candidate.x + 0.5 - position.x);
    const normalizedAngle = (angle + Math.PI * 2) % (Math.PI * 2);
    const sector = Math.floor(normalizedAngle / ((Math.PI * 2) / AIR_ESCAPE_DIRECTION_SECTORS));
    if (!selected.has(sector)) {
      selected.set(sector, candidate);
    }
    if (selected.size >= maximumResults) {
      break;
    }
  }
  return [...selected.values()];
}

function selectStableSurfaceEscapeColumns(
  columns: readonly BeatGameSurfaceColumn[],
  position: BeatGamePosition,
): readonly {
  readonly x: number;
  readonly z: number;
  readonly surfaceY: number;
}[] {
  return selectSurfaceEscapeColumns(stableSurfaceColumns(columns), position);
}

function selectStableThreatEscapeColumn(
  columns: readonly BeatGameSurfaceColumn[],
  player: BeatGamePosition,
  threat: BeatGamePosition,
  maximumVerticalDistance = MAXIMUM_DAMAGE_FREE_FALL_DISTANCE,
):
  | {
      readonly x: number;
      readonly z: number;
      readonly surfaceY: number;
    }
  | undefined {
  const awayX = player.x - threat.x;
  const awayZ = player.z - threat.z;
  const awayLength = Math.hypot(awayX, awayZ);
  const directionX = awayLength > 0.001 ? awayX / awayLength : 1;
  const directionZ = awayLength > 0.001 ? awayZ / awayLength : 0;
  const currentThreatDistanceSquared = distanceSquared(player, threat);
  return stableSurfaceColumns(columns)
    .flatMap((candidate) => {
      const candidateX = candidate.x + 0.5;
      const candidateY = candidate.surfaceY + 1;
      const candidateZ = candidate.z + 0.5;
      const movementX = candidateX - player.x;
      const movementZ = candidateZ - player.z;
      const movementDistance = Math.hypot(movementX, movementZ);
      const alignment =
        movementDistance > 0.001
          ? (movementX * directionX + movementZ * directionZ) / movementDistance
          : -1;
      const threatDistanceSquared = distanceSquared(
        {
          x: candidateX,
          y: candidateY,
          z: candidateZ,
        },
        threat,
      );
      return movementDistance >= 3 &&
        alignment >= 0.25 &&
        Math.abs(candidateY - player.y) <= maximumVerticalDistance &&
        threatDistanceSquared > currentThreatDistanceSquared + 4
        ? [
            {
              ...candidate,
              alignment,
              threatDistanceSquared,
              movementDistance,
            },
          ]
        : [];
    })
    .toSorted(
      (left, right) =>
        right.threatDistanceSquared - left.threatDistanceSquared ||
        right.alignment - left.alignment ||
        left.movementDistance - right.movementDistance,
    )[0];
}

function stableSurfaceColumns(
  columns: readonly BeatGameSurfaceColumn[],
): readonly (BeatGameSurfaceColumn & { readonly surfaceY: number })[] {
  const safeColumns = columns.flatMap((column) =>
    column.loaded && column.surfaceY !== undefined && !isUnsafeSurfaceBlock(column.blockId)
      ? [{ ...column, surfaceY: column.surfaceY }]
      : [],
  );
  const columnsByPosition = new Map(
    safeColumns.map((column) => [`${column.x}:${column.z}`, column]),
  );
  return safeColumns.filter((candidate) => {
    let stableNeighbors = 0;
    for (let deltaX = -1; deltaX <= 1; deltaX += 1) {
      for (let deltaZ = -1; deltaZ <= 1; deltaZ += 1) {
        if (deltaX === 0 && deltaZ === 0) {
          continue;
        }
        const neighbor = columnsByPosition.get(`${candidate.x + deltaX}:${candidate.z + deltaZ}`);
        if (
          neighbor !== undefined &&
          Math.abs(neighbor.surfaceY - candidate.surfaceY) <= SURFACE_NEIGHBOR_MAX_HEIGHT_DELTA
        ) {
          stableNeighbors += 1;
        }
      }
    }
    return stableNeighbors >= MINIMUM_STABLE_SURFACE_NEIGHBORS;
  });
}

function selectElevatedSurfaceColumns(
  columns: readonly {
    readonly x: number;
    readonly z: number;
    readonly loaded: boolean;
    readonly surfaceY?: number;
    readonly blockId?: string;
  }[],
  position: BeatGamePosition,
): readonly {
  readonly x: number;
  readonly z: number;
  readonly surfaceY: number;
}[] {
  const safeColumns = columns.flatMap((column) =>
    column.loaded && column.surfaceY !== undefined && !isUnsafeSurfaceBlock(column.blockId)
      ? [{ x: column.x, z: column.z, surfaceY: column.surfaceY }]
      : [],
  );
  return safeColumns
    .filter(
      (candidate) =>
        candidate.surfaceY - position.y > 2 &&
        safeColumns.filter(
          (neighbor) =>
            neighbor !== candidate &&
            Math.abs(neighbor.x - candidate.x) <= 1 &&
            Math.abs(neighbor.z - candidate.z) <= 1 &&
            Math.abs(neighbor.surfaceY - candidate.surfaceY) <= SURFACE_NEIGHBOR_MAX_HEIGHT_DELTA,
        ).length >= MINIMUM_STABLE_SURFACE_NEIGHBORS,
    )
    .toSorted(
      (left, right) =>
        right.surfaceY - left.surfaceY ||
        surfaceHorizontalDistanceSquared(left, position) -
          surfaceHorizontalDistanceSquared(right, position),
    )
    .slice(0, AIR_ESCAPE_DIRECTION_SECTORS);
}

// A live run spawned on treeless high ground and explored for 25 minutes
// before it found the forest 140 blocks away in loaded chunks. Heading for
// the nearest surveyed tree replaces the blind frontier search.
function findNearestTreeColumn(
  state: RunState,
  position: BeatGamePosition,
): Effect.Effect<BeatGamePosition | undefined> {
  if (position.dimension !== "minecraft:overworld") {
    return Effect.undefined;
  }
  return state.driver.sampleSurface(position, TREE_SURVEY_RADIUS, TREE_SURVEY_SAMPLE_STEP).pipe(
    Effect.map((columns) => {
      // Logs within the block search radius are already collected directly,
      // so leaves there without them (bushes, leftover canopy) are dead
      // ends. A live bot shuttled between two such patches for minutes.
      const nearest = columns
        .filter(
          (column) =>
            column.loaded &&
            column.surfaceY !== undefined &&
            surfaceHorizontalDistanceSquared(column, position) >
              state.strategy.blockSearchRadius ** 2 &&
            (column.blockId?.endsWith("_leaves") === true ||
              column.blockId?.endsWith("_log") === true),
        )
        .toSorted(
          (left, right) =>
            surfaceHorizontalDistanceSquared(left, position) -
            surfaceHorizontalDistanceSquared(right, position),
        )[0];
      return nearest?.surfaceY === undefined
        ? undefined
        : {
            x: nearest.x + 0.5,
            y: nearest.surfaceY + 1,
            z: nearest.z + 0.5,
            dimension: position.dimension,
          };
    }),
    Effect.catchTag("BeatGameDriverError", () => Effect.undefined),
  );
}

function surfaceHorizontalDistanceSquared(
  surface: { readonly x: number; readonly z: number },
  position: BeatGamePosition,
): number {
  const deltaX = surface.x + 0.5 - position.x;
  const deltaZ = surface.z + 0.5 - position.z;
  return deltaX * deltaX + deltaZ * deltaZ;
}

function isUnsafeSurfaceBlock(blockId: string | undefined): boolean {
  return (
    blockId === undefined ||
    (PLAYER_FLUID_BLOCK_IDS as readonly string[]).includes(blockId) ||
    blockId === "minecraft:lily_pad" ||
    blockId === "minecraft:powder_snow" ||
    blockId.endsWith("_leaves")
  );
}

function isSwimmableSurfaceBlock(blockId: string | undefined): boolean {
  return (
    blockId !== undefined &&
    blockId !== "minecraft:lava" &&
    (PLAYER_FLUID_BLOCK_IDS as readonly string[]).includes(blockId)
  );
}

function explorationPurpose(
  purpose: string,
  position: BeatGamePosition,
  rotation?: number,
): string {
  const cellX = Math.floor(position.x / 32);
  const cellZ = Math.floor(position.z / 32);
  const prefix = `${purpose.slice(0, 40)}:${cellX}:${cellZ}`;
  return rotation === undefined ? prefix : `${purpose.slice(0, 24)}:${cellX}:${cellZ}:${rotation}`;
}

function advanceExplorationFrontier(
  state: RunState,
  position: BeatGamePosition,
  purpose: string,
  scanRadius: number,
  path: BeatGameStrategy["path"],
  preferSurface = true,
  allowFluidFallback = true,
  rotation = 0,
  maximumHop = DISCOVERY_MAXIMUM_HOP,
): Effect.Effect<void, BeatGameDriverError> {
  const normalizedRotation = ((rotation % 4) + 4) % 4;
  const key =
    normalizedRotation === 0
      ? `${position.dimension}:${purpose}`
      : `${position.dimension}:${purpose}:${normalizedRotation}`;
  const hop = discoveryHopRadius(state, scanRadius, maximumHop);
  const explorationPath = {
    ...path,
    maxSearchTimeMs: Math.min(path.maxSearchTimeMs, EXPLORATION_MAXIMUM_SEARCH_TIME_MS),
    maximumExpandedStates: Math.min(
      path.maximumExpandedStates ?? EXPLORATION_MAXIMUM_EXPANDED_STATES,
      EXPLORATION_MAXIMUM_EXPANDED_STATES,
    ),
  };
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const attempt = yield* Ref.modify(state.explorationFrontiers, (frontiers) => {
      const existing = frontiers[key];
      const current =
        existing?.progressVersion === 2 && existing.origin.dimension === position.dimension
          ? existing
          : undefined;
      // A wide search keeps its spiral through detours. A live fortress
      // search restarted its spiral around the bot after every fight and
      // pickaxe trip, and circled one area for an hour.
      const reanchorDistance =
        maximumHop > DISCOVERY_MAXIMUM_HOP ? 2 * hop : EXPLORATION_REANCHOR_DISTANCE;
      const wasExternallyDisplaced =
        current?.lastPosition !== undefined &&
        horizontalDistanceSquared(current.lastPosition, position) > reanchorDistance ** 2;
      const shouldReanchor =
        wasExternallyDisplaced &&
        !isRecentDeathDisplacement(current.lastPosition, checkpoint.memory.latestDeath);
      const frontier: BeatGameExplorationFrontier =
        current === undefined
          ? {
              progressVersion: 2,
              origin: position,
              nextIndex: 1,
              targetAttempts: 0,
              totalAdvances: 0,
              lastPosition: position,
            }
          : shouldReanchor
            ? {
                progressVersion: 2,
                origin: position,
                nextIndex: 1,
                targetAttempts: 0,
                lastPosition: position,
                totalAdvances: current.totalAdvances ?? 0,
              }
            : current;
      const offset = rotateExplorationOffset(
        squareSpiralOffset(frontier.nextIndex),
        normalizedRotation,
      );
      const target = frontier.target ?? {
        x: frontier.origin.x + offset.x * hop,
        y: position.y,
        z: frontier.origin.z + offset.z * hop,
        dimension: position.dimension,
      };
      const retained = {
        ...frontier,
        target,
      };
      return [
        {
          key,
          start: position,
          target,
          legTarget: explorationLegTarget(position, target),
          nextIndex: frontier.nextIndex,
        },
        retainExplorationFrontier(frontiers, key, retained),
      ] as const;
    });
    yield* pathfindExplorationTarget(
      state,
      position,
      attempt.target,
      2,
      explorationPath,
      preferSurface,
      allowFluidFallback,
    ).pipe(
      Effect.catchIf(
        (cause) =>
          preferSurface && (cause.operation === "pathfind" || cause.operation === "pathfindXZ"),
        (cause) =>
          recoverSurfaceAfterExplorationFailure(state, cause).pipe(
            Effect.andThen(Effect.fail(cause)),
          ),
      ),
      Effect.ensuring(persistExplorationAttempt(state, attempt, hop)),
    );
  });
}

function persistExplorationAttempt(
  state: RunState,
  attempt: Readonly<{
    key: string;
    start: BeatGamePosition;
    target: BeatGamePosition;
    legTarget: Readonly<{ x: number; z: number }>;
    nextIndex: number;
  }>,
  hop: number,
): Effect.Effect<void> {
  return state.driver.observe.pipe(
    Effect.flatMap((observation) =>
      Ref.modify(state.explorationFrontiers, (frontiers) => {
        const frontier = frontiers[attempt.key];
        if (
          frontier?.progressVersion !== 2 ||
          frontier.nextIndex !== attempt.nextIndex ||
          frontier.target === undefined ||
          !samePosition(frontier.target, attempt.target)
        ) {
          return [
            {
              frontiers,
              position: observation.player.position,
            },
            frontiers,
          ] as const;
        }
        const end = observation.player.position;
        const startDistance = horizontalDistanceToTarget(attempt.start, attempt.legTarget);
        const endDistance = horizontalDistanceToTarget(end, attempt.legTarget);
        const targetReached =
          horizontalDistanceSquared(end, attempt.target) <= EXPLORATION_TARGET_REACHED_RADIUS ** 2;
        const legReached = endDistance <= EXPLORATION_TARGET_REACHED_RADIUS;
        const madeMaterialProgress =
          startDistance - endDistance >= EXPLORATION_MINIMUM_PROGRESS_DISTANCE;
        const totalAdvances = frontier.totalAdvances ?? 0;
        const updated: BeatGameExplorationFrontier = targetReached
          ? {
              progressVersion: 2,
              origin: frontier.origin,
              nextIndex: frontier.nextIndex + 1,
              targetAttempts: 0,
              lastPosition: end,
              totalAdvances: totalAdvances + 1,
            }
          : legReached
            ? {
                ...frontier,
                lastPosition: end,
                totalAdvances: totalAdvances + 1,
              }
            : madeMaterialProgress
              ? {
                  ...frontier,
                  lastPosition: end,
                }
              : {
                  ...frontier,
                  target: explorationRetryTarget(
                    end,
                    attempt.target,
                    hop,
                    (frontier.targetAttempts ?? 0) + 1,
                  ),
                  targetAttempts: (frontier.targetAttempts ?? 0) + 1,
                  lastPosition: end,
                };
        const retained = retainExplorationFrontier(frontiers, attempt.key, updated);
        return [{ frontiers: retained, position: end }, retained] as const;
      }),
    ),
    Effect.flatMap(({ frontiers, position }) =>
      persist(state, (checkpoint) => {
        const remembered = isNether(position.dimension)
          ? rememberWorldPlace(checkpoint, "SAFE_CORRIDOR", position, 0.75)
          : checkpoint;
        return {
          ...remembered,
          memory: {
            ...remembered.memory,
            explorationFrontiers: frontiers,
          },
        };
      }),
    ),
    Effect.ignore,
  );
}

function explorationRetryTarget(
  position: BeatGamePosition,
  previousTarget: BeatGamePosition,
  hop: number,
  targetAttempts: number,
): BeatGamePosition {
  const deltaX = previousTarget.x - position.x;
  const deltaZ = previousTarget.z - position.z;
  const length = Math.hypot(deltaX, deltaZ);
  const unitX = length > 0.001 ? deltaX / length : 1;
  const unitZ = length > 0.001 ? deltaZ / length : 0;
  const attemptIndex = Math.max(0, targetAttempts - 1);
  const scaleIndex =
    Math.floor(attemptIndex / EXPLORATION_RETRY_DIRECTIONS) %
    EXPLORATION_RETRY_DISTANCE_SCALES.length;
  const rotated = rotateExplorationOffset({ x: unitX, z: unitZ }, 1);
  const distance = Math.max(8, hop * (EXPLORATION_RETRY_DISTANCE_SCALES[scaleIndex] ?? 1));
  return {
    x: position.x + rotated.x * distance,
    y: position.y,
    z: position.z + rotated.z * distance,
    dimension: position.dimension,
  };
}

function explorationLegTarget(
  position: BeatGamePosition,
  target: Pick<BeatGamePosition, "x" | "z">,
): { readonly x: number; readonly z: number } {
  const deltaX = target.x - position.x;
  const deltaZ = target.z - position.z;
  const targetDistance = Math.hypot(deltaX, deltaZ);
  const legRatio =
    targetDistance <= EXPLORATION_MAXIMUM_LEG_DISTANCE
      ? 1
      : EXPLORATION_MAXIMUM_LEG_DISTANCE / targetDistance;
  return {
    x: position.x + deltaX * legRatio,
    z: position.z + deltaZ * legRatio,
  };
}

function horizontalDistanceToTarget(
  position: BeatGamePosition,
  target: Readonly<{ x: number; z: number }>,
): number {
  return Math.hypot(target.x - position.x, target.z - position.z);
}

function explorationDetourRotation(
  position: BeatGamePosition,
  target: Pick<BeatGamePosition, "x" | "z">,
): number {
  return Math.abs(target.x - position.x) >= Math.abs(target.z - position.z) ? 1 : 0;
}

function rotateExplorationOffset(
  offset: Readonly<{ x: number; z: number }>,
  rotation: number,
): { readonly x: number; readonly z: number } {
  switch (rotation) {
    case 1: {
      return { x: -offset.z, z: offset.x };
    }
    case 2: {
      return { x: -offset.x, z: -offset.z };
    }
    case 3: {
      return { x: offset.z, z: -offset.x };
    }
    default: {
      return offset;
    }
  }
}

function retainExplorationFrontier(
  frontiers: Readonly<Record<string, BeatGameExplorationFrontier>>,
  key: string,
  frontier: BeatGameExplorationFrontier,
): Readonly<Record<string, BeatGameExplorationFrontier>> {
  const entries = Object.entries(frontiers)
    .filter(([candidate]) => candidate !== key)
    .slice(-(EXPLORATION_FRONTIER_LIMIT - 1));
  return Object.fromEntries([...entries, [key, frontier]]);
}

function isRecentDeathDisplacement(
  previousPosition: BeatGamePosition,
  latestDeath: BeatGameMemoryEntry<BeatGamePosition> | undefined,
): boolean {
  if (
    latestDeath === undefined ||
    latestDeath.value.dimension !== previousPosition.dimension ||
    horizontalDistanceSquared(latestDeath.value, previousPosition) >
      EXPLORATION_REANCHOR_DISTANCE ** 2
  ) {
    return false;
  }
  const deathAgeMs = Date.now() - Date.parse(latestDeath.observedAt);
  return (
    Number.isFinite(deathAgeMs) &&
    deathAgeMs >= 0 &&
    deathAgeMs <= EXPLORATION_DEATH_DISPLACEMENT_WINDOW_MS
  );
}

function pathfindExplorationTarget(
  state: RunState,
  position: BeatGamePosition,
  target: { readonly x: number; readonly z: number },
  radius: number,
  path: BeatGameStrategy["path"],
  preferSurface: boolean,
  allowFluidFallback = true,
): Effect.Effect<void, BeatGameDriverError> {
  const legTarget = explorationLegTarget(position, target);
  const navigate = (policy: BeatGameStrategy["path"]): Effect.Effect<void, BeatGameDriverError> => {
    if (!preferSurface) {
      return state.driver.pathfind(
        {
          x: legTarget.x,
          y: position.y,
          z: legTarget.z,
          dimension: position.dimension,
        },
        radius,
        policy,
      );
    }
    // Below the lava sea everything is solid netherrack. A live search kept
    // tunnelling there at y 7 to 12, ran out of search on every leg, and met
    // no mobs. From down there a leg climbs to the caverns above.
    if (isNether(position.dimension) && position.y < NETHER_LAVA_SEA_LEVEL) {
      return state.driver.pathfind(
        {
          x: legTarget.x,
          y: NETHER_EXPLORATION_CAVERN_Y,
          z: legTarget.z,
          dimension: position.dimension,
        },
        Math.max(radius, NETHER_CAVERN_LEG_RADIUS),
        policy,
      );
    }
    if (position.dimension !== "minecraft:overworld") {
      return state.driver.pathfindXZ(legTarget.x, legTarget.z, position.dimension, radius, policy);
    }
    const targetCenter = {
      x: legTarget.x,
      y: position.y,
      z: legTarget.z,
      dimension: position.dimension,
    };
    return state.driver.sampleSurface(targetCenter, 4, 1).pipe(
      Effect.map((columns) =>
        selectSurfaceColumn(
          columns.filter(
            (column) =>
              Math.abs(column.x + 0.5 - legTarget.x) <= 4 &&
              Math.abs(column.z + 0.5 - legTarget.z) <= 4,
          ),
          targetCenter,
        ),
      ),
      Effect.flatMap((surface) =>
        surface === undefined ||
        Math.abs(surface.surfaceY + 1 - position.y) > EXPLORATION_MAXIMUM_SURFACE_ELEVATION_CHANGE
          ? state.driver.pathfindXZ(legTarget.x, legTarget.z, position.dimension, radius, policy)
          : state.driver.pathfind(
              {
                x: surface.x + 0.5,
                y: surface.surfaceY + 1,
                z: surface.z + 0.5,
                dimension: position.dimension,
              },
              radius,
              policy,
            ),
      ),
    );
  };
  const preferredRoute = navigate(path);
  return path.avoidFluids === true && allowFluidFallback
    ? preferredRoute.pipe(
        Effect.catchTag("BeatGameDriverError", (cause) =>
          cause.operation === "pathfind" || cause.operation === "pathfindXZ"
            ? navigate({ ...path, avoidFluids: false })
            : Effect.fail(cause),
        ),
      )
    : preferredRoute;
}

function recoverSurfaceAfterExplorationFailure(
  state: RunState,
  routeFailure: BeatGameDriverError,
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.observe.pipe(
    Effect.flatMap((observation) =>
      needsOverworldSurfaceRecovery(state, observation.player.position).pipe(
        Effect.flatMap((needsRecovery) =>
          needsRecovery
            ? escapeToOverworldSurface(state, observation.player.position)
            : isPlayerInFluid(state.driver, observation.player.position).pipe(
                Effect.flatMap((inFluid) =>
                  inFluid
                    ? swimToNearbyDrySurface(state).pipe(Effect.asVoid)
                    : Effect.fail(
                        new BeatGameDriverError({
                          operation: routeFailure.operation,
                          ...(routeFailure.code === undefined ? {} : { code: routeFailure.code }),
                          retryable: true,
                          message: routeFailure.message,
                          cause: routeFailure,
                        }),
                      ),
                ),
              ),
        ),
      ),
    ),
  );
}

function squareSpiralOffset(index: number): {
  readonly x: number;
  readonly z: number;
} {
  if (index <= 0) {
    return { x: 0, z: 0 };
  }
  let x = 0;
  let z = 0;
  let deltaX = 1;
  let deltaZ = 0;
  let segmentLength = 1;
  let segmentProgress = 0;
  let segmentsAtLength = 0;
  for (let step = 0; step < index; step += 1) {
    x += deltaX;
    z += deltaZ;
    segmentProgress += 1;
    if (segmentProgress < segmentLength) {
      continue;
    }
    segmentProgress = 0;
    [deltaX, deltaZ] = [-deltaZ, deltaX];
    segmentsAtLength += 1;
    if (segmentsAtLength === 2) {
      segmentsAtLength = 0;
      segmentLength += 1;
    }
  }
  return { x, z };
}

function discoveryHopRadius(
  state: RunState,
  scanRadius: number,
  maximumHop = DISCOVERY_MAXIMUM_HOP,
): number {
  return Math.max(
    8,
    Math.min(maximumHop, state.strategy.explorationRadius, Math.floor(scanRadius / 2)),
  );
}

/// An enderman hits for about 4.5 through iron and takes eight stone sword
/// strikes. A live bot started one at 13 health and died to three hits.
/// Returns whether the bot spent this turn healing instead of hunting.
function healBeforeEndermanHunt(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    if (observation.player.health >= ENDERMAN_HUNT_MINIMUM_HEALTH) {
      return false;
    }
    // A live bot thrown into lava started this meal there and burned to
    // death while eating. Lava and fire come first.
    if (
      observation.player.fireTicks > 0 ||
      (yield* isPlayerInLava(state.driver, observation.player.position))
    ) {
      return true;
    }
    if (observation.player.food < 20 && hasUsableFood(observation)) {
      yield* eatUnlessEndangered(state, {
        foodItemIds: preferredUsableFoodItemIds(observation),
        foodLevel: 20,
        maximumMeals: 1,
        completeWhenNoFood: true,
        path: state.strategy.path,
      });
    } else {
      yield* Effect.sleep(ENDERMAN_HUNT_HEAL_WAIT_MS);
    }
    return true;
  });
}

function acquireEnderPearls(
  state: RunState,
  observation: BeatGameObservation,
  missing: number,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const endermen = yield* state.driver.queryEntities({
      origin: observation.player.position,
      radius: state.strategy.entitySearchRadius,
      selector: {
        entityTypes: ["minecraft:enderman"],
        alive: true,
      },
      maximumResults: Math.max(1, missing),
    });
    if (endermen.length > 0) {
      // A live bot with endermen in sight started its hunt at 12 health,
      // brought one from 40 to 5, and died to its last three hits.
      if (yield* healBeforeEndermanHunt(state, observation)) {
        return;
      }
      yield* huntOrExplore(
        state,
        observation,
        {
          entityTypes: ["minecraft:enderman"],
          alive: true,
        },
        missing,
        "hunt-endermen",
      );
      return;
    }

    // Piglins live only in the Nether. An Overworld pearl hunt, for an eye
    // lost to a throw, goes straight to the endermen.
    const goldIngots = isNether(observation.player.position.dimension)
      ? (observation.inventory.counts["minecraft:gold_ingot"] ?? 0)
      : 0;
    if (goldIngots > 0) {
      const piglins = yield* state.driver.queryEntities({
        origin: observation.player.position,
        radius: state.strategy.entitySearchRadius,
        selector: {
          entityTypes: ["minecraft:piglin"],
          alive: true,
        },
        maximumResults: 1,
      });
      const piglin = piglins[0];
      if (piglin === undefined) {
        yield* explore(state.driver, {
          origin: observation.player.position,
          radius: discoveryHopRadius(state, state.strategy.entitySearchRadius),
          maximumWaypoints: 1,
          purpose: "find-bartering-piglin",
          path: state.strategy.path,
        });
        return;
      }
      yield* barterWithPiglin(state, piglin, Math.min(goldIngots, Math.max(1, missing * 2), 8));
      return;
    }

    const nuggets = observation.inventory.counts["minecraft:gold_nugget"] ?? 0;
    if (nuggets >= 9 && isNether(observation.player.position.dimension)) {
      yield* craftWithTable(state, observation, "minecraft:gold_ingot", Math.floor(nuggets / 9));
      return;
    }

    // A piglin trades a pearl about once in 46 ingots, and an ore yields
    // half an ingot. A live bot mined and traded for an hour and held three
    // pearls of twelve. An enderman drops one about half the time, so with
    // no gold in hand the bot goes looking for endermen instead.
    // Endermen in the Nether spawn mostly in warped forests. A live hunt that
    // wandered the Nether at random found two in twenty minutes, so the hunt
    // searches for warped nylium the way the blaze hunt searches for a
    // fortress, and heads for a forest it has seen before.
    const checkpoint = yield* Ref.get(state.checkpoint);
    const rememberedForest = checkpoint.memory.places
      .filter(
        (place) =>
          place.kind === "WARPED_FOREST" &&
          place.position.dimension === observation.player.position.dimension,
      )
      .toSorted(
        (left, right) =>
          horizontalDistanceSquared(left.position, observation.player.position) -
          horizontalDistanceSquared(right.position, observation.player.position),
      )[0]?.position;
    if (yield* healBeforeEndermanHunt(state, observation)) {
      return;
    }
    const searchTurns = Math.floor(
      ((yield* Clock.currentTimeMillis) - state.startedAtMs) / OUTWARD_SEARCH_TURN_INTERVAL_MS,
    );
    yield* huntOrExplore(
      state,
      observation,
      { entityTypes: ["minecraft:enderman"], alive: true },
      missing,
      "hunt-endermen",
      isNether(observation.player.position.dimension)
        ? {
            preferredEntityTypes: new Set(["minecraft:enderman"]),
            preferredRadius: state.strategy.entitySearchRadius,
            explorationTarget:
              rememberedForest ?? outwardSearchTarget(observation.player.position, searchTurns),
            structureSearch: {
              anchorBlockIds: new Set(["minecraft:warped_nylium"]),
              stationaryBlockIds: new Set<string>(),
              placeKind: "WARPED_FOREST",
              scanRadius: NETHER_WIDE_SEARCH_RADIUS,
              maximumResults: 256,
            },
          }
        : undefined,
    );
  });
}

/// A live forest search circled within 200 blocks of where it began for an
/// hour, each stop scanning ground the last had already covered. With nothing
/// remembered, the search heads straight outward from the dimension origin
/// instead, so every stop scans new ground.
///
/// A live search found its outward way blocked by the lava sea and detoured
/// around one spot for forty minutes, so the heading turns a quarter every
/// interval and the search tries each side in turn.
function outwardSearchTarget(position: BeatGamePosition, turns = 0): BeatGamePosition {
  const distance = Math.hypot(position.x, position.z);
  const angle = (distance < 1 ? 0 : Math.atan2(position.z, position.x)) + (turns * Math.PI) / 2;
  const directionX = Math.cos(angle);
  const directionZ = Math.sin(angle);
  return {
    ...position,
    x: position.x + directionX * OUTWARD_SEARCH_DISTANCE,
    z: position.z + directionZ * OUTWARD_SEARCH_DISTANCE,
  };
}

function barterWithPiglin(
  state: RunState,
  piglin: BeatGameEntityObservation,
  trades: number,
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.withControl(
    Effect.gen(function* () {
      let outOfReachOffers = 0;
      for (let trade = 0; trade < trades; trade += 1) {
        // Piglins wander. A live bot walked to where one had been, found it
        // out of reach, and abandoned the barter three times in a row. It
        // walks to where the piglin is now before each trade.
        const observed = yield* state.driver.observe;
        const [current] = yield* state.driver.queryEntities({
          origin: observed.player.position,
          // The piglin was found within the entity search radius, so it is
          // looked up again just as far. A live bot looked only 24 blocks out,
          // lost a piglin 30 blocks away, and gave up 256 times in a row.
          radius: state.strategy.entitySearchRadius,
          selector: { networkId: piglin.networkId, alive: true },
          maximumResults: 1,
        });
        if (current === undefined) {
          return;
        }
        // A route that misses its quality bound ended a live run, so a
        // failed approach ends only this barter.
        const approached = yield* state.driver
          .pathfind(current.position, PIGLIN_BARTER_APPROACH_RADIUS, state.strategy.path)
          .pipe(
            Effect.as(true),
            Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
          );
        if (!approached) {
          return;
        }
        yield* state.driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:gold_ingot"] },
        });
        // A piglin still admiring the last ingot, or a baby or angry one,
        // refuses the trade. A live refusal ended the whole run, so it ends
        // only this barter and the next plan tries again.
        const offer = yield* state.driver
          .act({
            type: "interact-entity",
            connectionEpoch: piglin.connectionEpoch,
            networkId: piglin.networkId,
            hand: "main",
          })
          .pipe(
            Effect.as("offered" as const),
            Effect.catchTag("BeatGameDriverError", (error) =>
              Effect.succeed(error.code === "out_of_range" ? "out-of-reach" : "refused"),
            ),
          );
        // A piglin that wandered off between the approach and the offer is
        // followed again. A live bot treated three such misses as refusals.
        if (offer === "out-of-reach" && outOfReachOffers < PIGLIN_OUT_OF_REACH_RETRIES) {
          outOfReachOffers += 1;
          trade -= 1;
          continue;
        }
        if (offer !== "offered") {
          // Offering again a second later was refused four more times live.
          // A piglin admires an ingot for about six seconds.
          yield* Effect.sleep(PIGLIN_REFUSAL_WAIT_MS);
          return;
        }
        yield* Effect.sleep(6500);
        const drops = yield* state.driver.queryEntities({
          origin: current.position,
          radius: 12,
          selector: {
            categories: [6],
            alive: true,
          },
          maximumResults: 64,
        });
        for (const pearl of drops.filter(({ itemId }) => itemId === "minecraft:ender_pearl")) {
          yield* state.driver.pathfind(pearl.position, 1, state.strategy.path);
        }
      }
    }).pipe(Effect.ensuring(state.driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
  );
}

function craftIronArmor(
  state: RunState,
  observation: BeatGameObservation,
  itemId: string,
  ironCost: number,
): Effect.Effect<void, BeatGameDriverError> {
  const iron = observation.inventory.counts["minecraft:iron_ingot"] ?? 0;
  return iron < ironCost
    ? satisfyIronRequirement(state, observation, ironCost - iron)
    : craftWithTable(state, observation, itemId, 1).pipe(
        Effect.andThen(state.driver.observe),
        Effect.flatMap((current) => wearCarriedArmor(state, current)),
      );
}

const ARMOR_SLOTS = [
  { slot: "head", itemIds: HELMET_ITEM_IDS },
  { slot: "chest", itemIds: CHESTPLATE_ITEM_IDS },
  { slot: "feet", itemIds: BOOTS_ITEM_IDS },
] as const;

const GOLDEN_BOOTS_GOLD_COST = 4;

// Piglins leave a player alone while it wears any gold. A live run was shot
// into lava by a piglin a minute after it entered the Nether.
function craftGoldenBoots(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  const ingots = observation.inventory.counts["minecraft:gold_ingot"] ?? 0;
  const nuggets = observation.inventory.counts["minecraft:gold_nugget"] ?? 0;
  if (ingots >= GOLDEN_BOOTS_GOLD_COST) {
    return craftWithTable(state, observation, "minecraft:golden_boots", 1).pipe(
      Effect.andThen(state.driver.observe),
      Effect.flatMap((current) => wearCarriedArmor(state, current)),
    );
  }
  const missingNuggets = (GOLDEN_BOOTS_GOLD_COST - ingots) * 9;
  // All ingots are crafted in one visit to the crafting table.
  if (nuggets >= missingNuggets) {
    return craftWithTable(
      state,
      observation,
      "minecraft:gold_ingot",
      GOLDEN_BOOTS_GOLD_COST - ingots,
    );
  }
  if (
    !hasMiningPickaxeReserve(
      observation,
      MINING_PICKAXE_ITEM_IDS,
      NETHER_EXPLORATION_PICKAXE_DURABILITY_RESERVE,
    )
  ) {
    return ensureNetherExplorationPickaxe(state, observation);
  }
  return leavePiglinsBeforeMiningGold(state, observation).pipe(
    Effect.flatMap((moved) =>
      moved
        ? Effect.void
        : collectBlocksOrExplore(state, observation, {
            blockIds: ["minecraft:nether_gold_ore"],
            // Each ore drops two to six nuggets.
            count: Math.ceil((missingNuggets - nuggets) / 4),
            progressItemIds: ["minecraft:gold_nugget"],
            purpose: "find-nether-gold-ore",
            preferSurfaceExploration: false,
            path: state.strategy.path,
          }),
    ),
  );
}

/// Breaking gold ore angers every piglin within 16 blocks, seen or not, and
/// gold armor does not calm them. A live bot mined nether gold beside the
/// piglins it had just bartered with and died to four of their hits in three
/// seconds. With piglins close, the bot moves away from them first.
function leavePiglinsBeforeMiningGold(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    if (!isNether(observation.player.position.dimension)) {
      return false;
    }
    const position = observation.player.position;
    const [piglin] = (yield* state.driver.queryEntities({
      origin: position,
      radius: PIGLIN_GOLD_GUARD_RADIUS,
      selector: { entityTypes: [...GOLD_GUARDING_PIGLIN_ENTITY_TYPES], alive: true },
      maximumResults: 8,
    })).toSorted(
      (left, right) =>
        distanceSquared(left.position, position) - distanceSquared(right.position, position),
    );
    if (piglin === undefined) {
      return false;
    }
    yield* emit(state, {
      type: "diagnostic",
      message: "Leaving piglins before mining gold",
      data: { piglinNetworkId: piglin.networkId, position },
    });
    const away = positionAwayFrom(position, piglin.position, PIGLIN_GOLD_LEAVE_DISTANCE);
    yield* state.driver
      .pathfindXZ(away.x, away.z, position.dimension, 4, {
        ...state.strategy.path,
        allowMining: true,
        allowPlacing: true,
      })
      .pipe(Effect.catchTag("BeatGameDriverError", () => Effect.void));
    return true;
  });
}

// Armor only helps while worn. It is crafted into the inventory and comes
// back from a corpse there too.
function wearCarriedArmor(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.forEach(
    ARMOR_SLOTS,
    ({ slot, itemIds }) => {
      if (observation.player.equipment[slot] !== undefined) {
        return Effect.void;
      }
      const carried = itemIds.find((itemId) => (observation.inventory.counts[itemId] ?? 0) > 0);
      return carried === undefined
        ? Effect.void
        : state.driver.act({
            type: "equip-item",
            selector: { itemIds: [carried] },
            equipmentSlot: slot,
          });
    },
    { discard: true, concurrency: 1 },
  );
}

/// Tools, armor and the like do not stack, so each one crafted takes its own
/// slot. A live run crafted three stone pickaxes with one slot free, and the
/// craft failed with no room for the result and ended the run.
function craftResultSlots(resultItemId: string, count: number): number {
  return UNSTACKABLE_CRAFT_RESULT_PATTERN.test(resultItemId) ? count : 1;
}

// The materials a failed craft lacked, read from its error, plus logs since
// they make planks. Blocks dug on the way do not count as progress then.
function missingCraftMaterials(error: BeatGameDriverError): readonly string[] | undefined {
  const missing = /Missing \d+ of (.+?) while producing/u.exec(error.message)?.[1];
  return missing === undefined ? undefined : [...missing.split(" or "), ...LOG_ITEM_IDS];
}

function inventorySignature(
  observation: BeatGameObservation,
  itemIds: readonly string[] | undefined,
): string {
  const counts = observation.inventory.counts;
  return JSON.stringify(
    itemIds === undefined
      ? Object.entries(counts).toSorted(([left], [right]) => left.localeCompare(right))
      : itemIds.map((itemId) => counts[itemId] ?? 0),
  );
}

function isResourceExhausted(error: BeatGameDriverError): boolean {
  return error.code?.toLowerCase().replace("_", "-") === "resource-exhausted";
}

// Each failed craft walks every wood recipe the server knows, about 25
// seconds of queries. A live bot without a pickaxe retried one on every
// step of a staircase and spent half an hour climbing twelve blocks. A craft
// that failed for lack of materials fails at once until the inventory changes.
function craftWithTable(
  state: RunState,
  observation: BeatGameObservation,
  resultItemId: string,
  count: number,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const failedFrom = (yield* Ref.get(state.failedCrafts)).get(resultItemId);
    if (
      failedFrom !== undefined &&
      failedFrom.signature === inventorySignature(observation, failedFrom.itemIds)
    ) {
      return yield* new BeatGameDriverError({
        operation: "craft",
        code: "resource-exhausted",
        retryable: true,
        message: `Crafting ${resultItemId} already failed with this inventory`,
      });
    }
    const craft = craftWithTableUncached(state, observation, resultItemId, count);
    // A live bot held one log and one plank, a plank short of a shield, and
    // was shot to death by a skeleton it could not block. Wood is the one
    // material a bot can always go and fetch, so it does, and tries again.
    return yield* craft.pipe(
      Effect.catchIf(
        (error) =>
          isResourceExhausted(error) &&
          (missingCraftMaterials(error) ?? []).some((itemId) =>
            (PLANK_ITEM_IDS as readonly string[]).includes(itemId),
          ),
        (error) =>
          state.driver.observe.pipe(
            Effect.flatMap((latest) => fetchLogs(state, latest)),
            Effect.flatMap((fetched) => (fetched ? craft : Effect.fail(error))),
          ),
      ),
      Effect.tapError((error) =>
        isResourceExhausted(error)
          ? state.driver.observe.pipe(
              Effect.flatMap((latest) => {
                const itemIds = missingCraftMaterials(error);
                const failed = { itemIds, signature: inventorySignature(latest, itemIds) };
                return Ref.update(
                  state.failedCrafts,
                  (current) => new Map([...current, [resultItemId, failed]]),
                );
              }),
              Effect.ignore,
            )
          : Effect.void,
      ),
    );
  });
}

function craftWithTableUncached(
  state: RunState,
  observation: BeatGameObservation,
  resultItemId: string,
  count: number,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    // Space comes first: making it walks to a discard site and may dig on
    // the way. A live bot placed its table, dug through it on that walk,
    // picked it back up, and then tried to craft at the empty spot.
    yield* ensureInventorySpace(state, observation, craftResultSlots(resultItemId, count));
    const current = yield* state.driver.observe;
    const countBefore = current.inventory.counts[resultItemId] ?? 0;
    const workstation = yield* ensureWorkstation(state, current, "minecraft:crafting_table");
    // The empty-slot count can miss a slot the craft needs. A live bot made
    // two of three pickaxes, found no room for the third, and the run ended.
    // Whatever was made is kept, and the rest is crafted after making room.
    yield* craftItem(state.driver, {
      resultItemId,
      count,
      station: workstation.position,
      path: state.strategy.path,
    }).pipe(
      Effect.catchIf(
        (error) => error.code?.toLowerCase() === "resource_exhausted",
        () =>
          Effect.gen(function* () {
            const latest = yield* state.driver.observe;
            const made = (latest.inventory.counts[resultItemId] ?? 0) - countBefore;
            const remaining = count - Math.max(0, made);
            if (remaining <= 0) {
              return;
            }
            yield* ensureInventorySpace(
              state,
              latest,
              craftResultSlots(resultItemId, remaining) + 1,
            );
            const afterCleanup = yield* state.driver.observe;
            const station = yield* ensureWorkstation(
              state,
              afterCleanup,
              "minecraft:crafting_table",
            );
            yield* craftItem(state.driver, {
              resultItemId,
              count: remaining,
              station: station.position,
              path: state.strategy.path,
            });
          }),
      ),
    );
  });
}

// A live bot needed a new pickaxe in a desert with three planks and no
// crafting table. Each craft failed one plank short, and it replanned in
// place for half an hour, so it fetches logs first. A table nearby does not
// count: another live bot could reach neither of the two it had left, and
// looped the same way.
function ensureWoodForCraftingTable(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const counts = observation.inventory.counts;
    if ((counts["minecraft:crafting_table"] ?? 0) > 0) {
      return;
    }
    const planks = PLANK_ITEM_IDS.reduce(
      (total, itemId) => total + (counts[itemId] ?? 0),
      LOG_ITEM_IDS.reduce((total, itemId) => total + (counts[itemId] ?? 0) * 4, 0),
    );
    const stickPlanks = (counts["minecraft:stick"] ?? 0) >= 2 ? 0 : 2;
    if (planks >= 4 + stickPlanks) {
      return;
    }
    yield* fetchLogs(state, observation);
  });
}

/// Collects two logs, unless a log trip is already under way. Returns
/// whether this call made the trip.
function fetchLogs(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    // The way to the logs can dig, and digging can craft a pickaxe that
    // needs a table. A live bot nested one log trip in another on every
    // staircase step, so a trip already under way is not started again.
    if (yield* Ref.getAndSet(state.fetchingTableWood, true)) {
      return false;
    }
    yield* findNearestTreeColumn(state, observation.player.position).pipe(
      Effect.flatMap((explorationTarget) =>
        collectBlocksOrExplore(state, observation, {
          blockIds: LOG_ITEM_IDS,
          count: 2,
          progressItemIds: LOG_ITEM_IDS,
          purpose: "find-logs",
          avoidSubmergedTargets: true,
          requireSurfaceTargets: true,
          ...(explorationTarget === undefined ? {} : { explorationTarget }),
        }),
      ),
      Effect.ensuring(Ref.set(state.fetchingTableWood, false)),
    );
    return true;
  });
}

function ensureWorkstation(
  state: RunState,
  observation: BeatGameObservation,
  blockId: "minecraft:crafting_table" | "minecraft:furnace",
): Effect.Effect<PreparedWorkstation, BeatGameDriverError> {
  return Effect.gen(function* () {
    // A live bot sat in a clifftop pool where every route to dry ground
    // ended back in the water, and abandoned its iron again and again
    // without ever placing a furnace. A workstation can still be placed from
    // shallow water, so the bot tries from where it is.
    const preparedObservation = yield* prepareDryWorkstationSite(state, observation, blockId).pipe(
      Effect.catchTag("BeatGameDriverError", (error) =>
        error.operation === "ensure-workstation" && error.code === "unreachable"
          ? state.driver.observe
          : Effect.fail(error),
      ),
    );
    const existing = yield* findReusableWorkstations(state.driver, preparedObservation, blockId);
    // A live bot crafted beside the portal it came through. Each craft closed
    // at once, and the run ended after the retries ran out.
    const nearbyPortals = (yield* state.driver.queryBlocks({
      center: preparedObservation.player.position,
      radius: WORKSTATION_REUSE_RADIUS + WORKSTATION_PORTAL_CLEARANCE,
      selector: { blockIds: ["minecraft:nether_portal"] },
      maximumResults: 128,
    })).filter((portal) => portal.blockId === "minecraft:nether_portal");
    const isBesidePortal = (position: BeatGamePosition) =>
      nearbyPortals.some(
        ({ position: portal }) =>
          portal.dimension === position.dimension &&
          Math.max(
            Math.abs(portal.x - Math.floor(position.x)),
            Math.abs(portal.y - Math.floor(position.y)),
            Math.abs(portal.z - Math.floor(position.z)),
          ) <= WORKSTATION_PORTAL_CLEARANCE,
      );
    for (const candidate of existing.filter(({ position }) => !isBesidePortal(position))) {
      const approached = yield* state.driver
        .pathfind(
          {
            x: candidate.position.x + 0.5,
            y: candidate.position.y,
            z: candidate.position.z + 0.5,
            dimension: candidate.position.dimension,
          },
          WORKSTATION_APPROACH_RADIUS,
          {
            ...state.strategy.path,
            allowMining: false,
            allowPlacing: false,
            avoidFluids: true,
            maxSearchTimeMs: Math.min(
              state.strategy.path.maxSearchTimeMs,
              WORKSTATION_REUSE_MAX_SEARCH_TIME_MS,
            ),
          },
        )
        .pipe(
          Effect.timeoutOrElse({
            duration: WORKSTATION_REUSE_TIMEOUT_MS,
            orElse: () =>
              Effect.fail(
                new BeatGameDriverError({
                  operation: "pathfind",
                  code: "unreachable",
                  retryable: true,
                  message: `Timed out approaching ${blockId} at ${candidate.position.x}, ${candidate.position.y}, ${candidate.position.z}`,
                }),
              ),
          }),
          Effect.result,
        );
      if (approached._tag === "Success") {
        const approachedObservation = yield* state.driver.observe;
        if (!(yield* isPlayerInFluid(state.driver, approachedObservation.player.position))) {
          return {
            position: candidate.position,
            placed: false,
          };
        }
      }
    }
    let current = yield* state.driver.observe;
    const nearestPortal = nearbyPortals
      .filter(({ position }) => position.dimension === current.player.position.dimension)
      .toSorted(
        (left, right) =>
          distanceSquared(left.position, current.player.position) -
          distanceSquared(right.position, current.player.position),
      )[0];
    if (nearestPortal !== undefined && isBesidePortal(current.player.position)) {
      yield* state.driver
        .pathfind(
          positionAwayFrom(
            current.player.position,
            blockCenter(nearestPortal.position),
            WORKSTATION_PORTAL_RETREAT_DISTANCE,
          ),
          2,
          { ...state.strategy.path, allowPlacing: false },
        )
        .pipe(Effect.ignore);
      current = yield* state.driver.observe;
    }
    // A carried furnace needs no table. A live bot with a furnace in its
    // pack went for a table to cook one chicken, had one plank, and failed
    // every few seconds for ten minutes.
    const craftingTable =
      blockId === "minecraft:furnace" && (current.inventory.counts[blockId] ?? 0) === 0
        ? yield* ensureWorkstation(state, current, "minecraft:crafting_table")
        : undefined;
    const placementObservation =
      craftingTable === undefined ? current : yield* state.driver.observe;
    const targets = yield* findWorkstationTargets(
      state.driver,
      placementObservation.player.position,
    );
    if ((placementObservation.inventory.counts[blockId] ?? 0) === 0) {
      yield* ensureInventorySpace(state, placementObservation);
      const craftWorkstation = craftItem(state.driver, {
        resultItemId: blockId,
        count: 1,
        ...(craftingTable === undefined ? {} : { station: craftingTable.position }),
        path: state.strategy.path,
      });
      // A live bot needed a furnace to cook with one plank in its pack, and
      // failed to craft the table for it every few seconds for ten minutes.
      yield* craftWorkstation.pipe(
        Effect.catchIf(
          (error) =>
            blockId === "minecraft:crafting_table" &&
            error.code?.toLowerCase().replace("_", "-") === "resource-exhausted",
          () =>
            state.driver.observe.pipe(
              Effect.flatMap((latest) => ensureWoodForCraftingTable(state, latest)),
              Effect.andThen(craftWorkstation),
            ),
        ),
      );
    }
    for (const target of targets) {
      const built = yield* buildStructure(state.driver, {
        origin: target,
        blocks: [
          {
            offset: { x: 0, y: 0, z: 0 },
            blockId,
          },
        ],
        path: {
          ...state.strategy.path,
          avoidFluids: true,
        },
      }).pipe(Effect.result);
      if (built._tag === "Failure") {
        continue;
      }
      let placed = yield* queryExactWorkstation(state.driver, target, blockId);
      if (placed === undefined) {
        yield* placeWorkstationDirectly(state.driver, target, blockId).pipe(Effect.ignore);
        placed = yield* waitForWorkstation(state.driver, target, blockId);
      }
      if (
        placed !== undefined &&
        placed.position.x === target.x &&
        placed.position.y === target.y &&
        placed.position.z === target.z &&
        placed.position.dimension === target.dimension
      ) {
        return {
          position: placed.position,
          placed: true,
        };
      }
    }
    return yield* new BeatGameDriverError({
      operation: "ensure-workstation",
      retryable: true,
      message: `${blockId} could not be placed on any nearby support`,
    });
  });
}

function prepareDryWorkstationSite(
  state: RunState,
  observation: BeatGameObservation,
  blockId: "minecraft:crafting_table" | "minecraft:furnace",
): Effect.Effect<BeatGameObservation, BeatGameDriverError> {
  return Effect.gen(function* () {
    let current = observation;
    for (let attempt = 0; attempt < AIR_ESCAPE_MAXIMUM_RECOVERY_ATTEMPTS; attempt += 1) {
      const inFluid = yield* isPlayerInFluid(state.driver, current.player.position);
      if (!inFluid && current.player.onGround) {
        return current;
      }
      // Off the ground but dry is a step or a short drop, not a reason to
      // leave. A live run read onGround false deep in a cave and dug a
      // staircase 35 blocks to the surface by hand to place a table it
      // carried. Placing works from where the bot settles.
      if (!inFluid && !current.player.dead) {
        for (let poll = 0; poll < WORKSTATION_SETTLE_POLLS && !current.player.onGround; poll += 1) {
          yield* Effect.sleep(WORKSTATION_SETTLE_POLL_MS);
          current = yield* state.driver.observe;
        }
        return current;
      }
      if (current.player.dead) {
        return current;
      }
      if (inFluid) {
        yield* emergencyAirAscent(state, current.player.position).pipe(Effect.result);
        current = yield* state.driver.observe;
        if (
          current.player.onGround &&
          !(yield* isPlayerInFluid(state.driver, current.player.position))
        ) {
          return current;
        }
        yield* swimToNearbyDrySurface(state, true).pipe(Effect.result);
        current = yield* state.driver.observe;
        if (
          current.player.onGround &&
          !(yield* isPlayerInFluid(state.driver, current.player.position))
        ) {
          return current;
        }
      }
      if (current.player.position.dimension === "minecraft:overworld") {
        yield* returnToOverworldSurface(state, current.player.position).pipe(Effect.result);
        current = yield* state.driver.observe;
        if (
          current.player.onGround &&
          !(yield* isPlayerInFluid(state.driver, current.player.position))
        ) {
          return current;
        }
      }
    }
    return yield* new BeatGameDriverError({
      operation: "ensure-workstation",
      code: "unreachable",
      retryable: true,
      message: `The bot could not reach dry, supported ground for a ${blockId}`,
    });
  });
}

function findReusableWorkstations(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
  blockId: "minecraft:crafting_table" | "minecraft:furnace",
): Effect.Effect<readonly BeatGameBlockObservation[], BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: observation.player.position,
      radius: WORKSTATION_REUSE_RADIUS,
      selector: { blockIds: [blockId] },
      maximumResults: 8,
    })
    .pipe(
      Effect.map((workstations) =>
        workstations
          .filter(
            ({ position }) =>
              position.dimension === observation.player.position.dimension &&
              Math.abs(position.y - observation.player.position.y) <=
                WORKSTATION_REUSE_MAX_VERTICAL_DISTANCE,
          )
          .toSorted(
            (left, right) =>
              distanceSquared(left.position, observation.player.position) -
              distanceSquared(right.position, observation.player.position),
          ),
      ),
    );
}

function ensureInventorySpace(
  state: RunState,
  observation: BeatGameObservation,
  minimumEmptySlots = 1,
): Effect.Effect<void, BeatGameDriverError> {
  const discardPath = (allowMining: boolean, allowPlacing = false) => ({
    ...state.strategy.path,
    allowMining,
    allowPlacing,
    avoidFluids: true,
    maxSearchTimeMs: Math.min(
      state.strategy.path.maxSearchTimeMs,
      INVENTORY_DISCARD_ESCAPE_MAX_SEARCH_TIME_MS,
    ),
  });

  const tryRelativeDiscardPath = (
    origin: BeatGameObservation,
    distance: number,
    allowMining: boolean,
    yawOffsets: readonly number[] = [0, 90, -90, 180],
    baseYaw = origin.player.rotation.yaw,
    allowPlacing = false,
  ) =>
    Effect.gen(function* () {
      for (const yawOffset of yawOffsets) {
        const yawRadians = ((baseYaw + yawOffset) * Math.PI) / 180;
        const escaped = yield* state.driver
          .pathfind(
            {
              x: origin.player.position.x - Math.sin(yawRadians) * distance,
              y: origin.player.position.y,
              z: origin.player.position.z + Math.cos(yawRadians) * distance,
              dimension: origin.player.position.dimension,
            },
            0.75,
            discardPath(allowMining, allowPlacing),
          )
          .pipe(Effect.result);
        if (escaped._tag === "Success") {
          return true;
        }
      }
      return false;
    });

  return Effect.gen(function* () {
    let current = observation;
    const targetEmptySlots = Math.min(36, minimumEmptySlots + INVENTORY_EMPTY_SLOT_BUFFER);
    let discardPocketYaw: number | undefined;
    let discardReturnTarget: BeatGamePosition | undefined;
    let cleanupFailure: BeatGameDriverError | undefined;
    let discardViewConfirmed = false;
    let discardedItems = false;
    while (
      current.inventory.emptyPlayerSlots !== undefined &&
      current.inventory.emptyPlayerSlots < targetEmptySlots
    ) {
      if (discardPocketYaw === undefined) {
        const discardSiteOrigin = current.player.position;
        // A live bot stood on a one-block bridge over a drop beside a
        // fortress, where every discard site lay off the bridge. It failed
        // the same discard six times, so it may build its way to the site.
        const relocated =
          (yield* tryRelativeDiscardPath(
            current,
            INVENTORY_DISCARD_SITE_DISTANCE,
            true,
            [90, -90, 180, 0],
          )) ||
          (yield* tryRelativeDiscardPath(
            current,
            INVENTORY_DISCARD_SITE_DISTANCE,
            true,
            [90, -90, 180, 0],
            current.player.rotation.yaw,
            true,
          ));
        if (relocated) {
          current = yield* state.driver.observe;
        }
        if (current.inventory.emptyPlayerSlots === undefined) {
          return undefined;
        }
        const discardSiteDistance = Math.hypot(
          current.player.position.x - discardSiteOrigin.x,
          current.player.position.z - discardSiteOrigin.z,
        );
        const discardSitePosition = current.player.position;
        if (relocated && discardSiteDistance > INVENTORY_DISCARD_ESCAPE_DISTANCE) {
          discardReturnTarget = discardSiteOrigin;
          const corridorYaw =
            (Math.atan2(
              -(discardSitePosition.x - discardSiteOrigin.x),
              discardSitePosition.z - discardSiteOrigin.z,
            ) *
              180) /
            Math.PI;
          const pocketOriginX = Math.floor(discardSitePosition.x);
          const pocketOriginY = Math.floor(discardSitePosition.y);
          const pocketOriginZ = Math.floor(discardSitePosition.z);
          // A live bot dug its pocket into a Nether wall with lava behind it
          // and burned in the flow. Another live bot then found lava behind
          // the same side wall every time and gave up, so the other side and
          // the wall ahead are tried too. The way back stays clear of items.
          let pocket:
            | { readonly yaw: number; readonly stepX: number; readonly stepZ: number }
            | undefined;
          for (const offset of [90, -90, 0]) {
            const yaw = wrappedDegrees(Math.round((corridorYaw + offset) / 90) * 90);
            const yawRadians = (yaw * Math.PI) / 180;
            const stepX = Math.round(-Math.sin(yawRadians));
            const stepZ = Math.round(Math.cos(yawRadians));
            const lavaBehindPocket = yield* state.driver.queryBlocks({
              center: {
                x: pocketOriginX + 0.5 + stepX * (INVENTORY_DISCARD_POCKET_DEPTH + 1) * 0.5,
                y: pocketOriginY + 1,
                z: pocketOriginZ + 0.5 + stepZ * (INVENTORY_DISCARD_POCKET_DEPTH + 1) * 0.5,
                dimension: discardSitePosition.dimension,
              },
              radius: INVENTORY_DISCARD_POCKET_LAVA_CLEARANCE,
              selector: { blockIds: ["minecraft:lava"] },
              maximumResults: 1,
            });
            if (lavaBehindPocket.length === 0) {
              pocket = { yaw, stepX, stepZ };
              break;
            }
          }
          if (pocket === undefined) {
            cleanupFailure = new BeatGameDriverError({
              operation: "ensure-inventory-space",
              code: "unreachable",
              retryable: true,
              message: "Lava lies behind the inventory discard pocket",
            });
            break;
          }
          const pocketYaw = pocket.yaw;
          const pocketStepX = pocket.stepX;
          const pocketStepZ = pocket.stepZ;
          for (let distance = 1; distance <= INVENTORY_DISCARD_POCKET_DEPTH; distance += 1) {
            for (const yOffset of [1, 0]) {
              const position = {
                x: pocketOriginX + pocketStepX * distance,
                y: pocketOriginY + yOffset,
                z: pocketOriginZ + pocketStepZ * distance,
                dimension: discardSitePosition.dimension,
              };
              const blocks = yield* state.driver.queryBlocks({
                center: blockCenter(position),
                radius: 0.25,
                selector: { diggable: true },
                maximumResults: 1,
              });
              if (blocks.some((block) => sameBlockPosition(block.position, position))) {
                yield* state.driver.act({ type: "dig-block", position });
              }
            }
          }
          discardPocketYaw = pocketYaw;
        }
      }
      if (discardPocketYaw === undefined) {
        cleanupFailure = new BeatGameDriverError({
          operation: "ensure-inventory-space",
          code: "unreachable",
          retryable: true,
          message: "Could not excavate an isolated inventory discard pocket",
        });
        break;
      }
      const discard = selectInventoryDiscard(current.inventory.counts);
      if (discard === undefined) {
        if (current.inventory.emptyPlayerSlots >= minimumEmptySlots) {
          break;
        }
        cleanupFailure = new BeatGameDriverError({
          operation: "ensure-inventory-space",
          code: "resource-exhausted",
          retryable: true,
          message: `The player inventory needs ${minimumEmptySlots} empty slots and contains no disposable items`,
        });
        break;
      }
      const { itemId, count } = discard;
      const itemCountBefore = current.inventory.counts[itemId] ?? 0;
      const emptySlotsBefore = current.inventory.emptyPlayerSlots;
      if (!discardViewConfirmed) {
        yield* state.driver.act({
          type: "look",
          yaw: discardPocketYaw,
          pitch: 0,
        });
        yield* waitForViewRotation(state.driver, discardPocketYaw, 0, 20);
        discardViewConfirmed = true;
      }
      yield* state.driver.act({
        type: "toss-items",
        selector: { itemIds: [itemId] },
        count,
      });
      discardedItems = true;
      let observedProgress = false;
      let itemCountReduced = false;
      for (let attempt = 0; attempt < 8; attempt += 1) {
        current = yield* state.driver.observe;
        itemCountReduced ||= (current.inventory.counts[itemId] ?? 0) < itemCountBefore;
        if (
          current.inventory.emptyPlayerSlots === undefined ||
          (itemCountReduced &&
            (current.inventory.emptyPlayerSlots > emptySlotsBefore || attempt >= 2))
        ) {
          observedProgress = true;
          break;
        }
        yield* Effect.sleep(Math.max(50, state.strategy.observationPollMs));
      }
      if (!observedProgress) {
        cleanupFailure = new BeatGameDriverError({
          operation: "ensure-inventory-space",
          code: "resource-exhausted",
          retryable: true,
          message: `Tossing ${itemId} did not reduce its inventory count`,
        });
        break;
      }
    }
    if (discardReturnTarget !== undefined) {
      yield* state.driver
        .pathfind(discardReturnTarget, 0.75, discardPath(false))
        .pipe(Effect.ignore);
    }
    if (discardedItems && cleanupFailure === undefined) {
      let stableObservations = 0;
      for (let attempt = 0; attempt < 20; attempt += 1) {
        current = yield* state.driver.observe;
        if (current.inventory.emptyPlayerSlots === undefined) {
          stableObservations = INVENTORY_EMPTY_SLOT_STABILITY_OBSERVATIONS;
          break;
        }
        if (current.inventory.emptyPlayerSlots >= minimumEmptySlots) {
          stableObservations += 1;
        } else {
          stableObservations = 0;
        }
        if (stableObservations >= INVENTORY_EMPTY_SLOT_STABILITY_OBSERVATIONS) {
          break;
        }
        yield* Effect.sleep(Math.max(50, state.strategy.observationPollMs));
      }
      if (
        current.inventory.emptyPlayerSlots !== undefined &&
        stableObservations < INVENTORY_EMPTY_SLOT_STABILITY_OBSERVATIONS
      ) {
        cleanupFailure = new BeatGameDriverError({
          operation: "ensure-inventory-space",
          code: "resource-exhausted",
          retryable: true,
          message: "Discarded items were picked up again after cleanup",
        });
      }
    }
    if (cleanupFailure !== undefined) {
      return yield* cleanupFailure;
    }
    return undefined;
  });
}

// Chooses what to drop when the inventory is full: junk first, then building
// blocks beyond the reserve, then redundant equipment.
export function selectInventoryDiscard(
  counts: Readonly<Record<string, number>>,
): { readonly itemId: string; readonly count: number } | undefined {
  const priorityItemId =
    INVENTORY_DISCARD_PRIORITY.find((itemId) => (counts[itemId] ?? 0) > 0) ??
    Object.keys(counts).find(
      (itemId) => itemId.startsWith("minecraft:music_disc_") && (counts[itemId] ?? 0) > 0,
    );
  if (priorityItemId !== undefined) {
    return { itemId: priorityItemId, count: counts[priorityItemId] ?? 0 };
  }
  // A bed takes three wool of one color. A live bot carried two light gray
  // and one white beside four black, and could not free slots for food.
  const mostWool = Math.max(0, ...WOOL_ITEM_IDS.map((itemId) => counts[itemId] ?? 0));
  const strayWool = WOOL_ITEM_IDS.find((itemId) => {
    const count = counts[itemId] ?? 0;
    return count > 0 && count < 3 && count < mostWool;
  });
  if (strayWool !== undefined) {
    return { itemId: strayWool, count: counts[strayWool] ?? 0 };
  }
  const excessCobbledDeepslate =
    (counts["minecraft:cobbled_deepslate"] ?? 0) - INVENTORY_BUILDING_BLOCK_RESERVE;
  if (excessCobbledDeepslate >= 64) {
    return { itemId: "minecraft:cobbled_deepslate", count: excessCobbledDeepslate };
  }
  // Nether tunnels fill the inventory with netherrack. A live run held 726 of
  // it and could not free the slots to craft a new pickaxe.
  // Basalt deltas fill it with basalt and blackstone the same way. A live run
  // held 332 basalt and could not free slots for food.
  for (const itemId of BUILDING_BLOCK_DISCARD_IDS) {
    const excess = (counts[itemId] ?? 0) - INVENTORY_BUILDING_BLOCK_RESERVE;
    if (excess > 0) {
      return { itemId, count: Math.min(64, excess) };
    }
  }
  return selectRedundantEquipmentDiscard(counts) ?? selectBackupEquipmentDiscard(counts);
}

/// The last resort: a backup tool one tier below a better one in hand. A
/// live bot back in the Nether failed its pearl hunt for want of three
/// empty slots while it kept a wooden pickaxe and sword behind stone ones.
function selectBackupEquipmentDiscard(
  counts: Readonly<Record<string, number>>,
): { readonly itemId: string; readonly count: number } | undefined {
  for (const family of REDUNDANT_EQUIPMENT_FAMILIES) {
    const bestTier = family.findLastIndex((itemId) => (counts[itemId] ?? 0) > 0);
    const backup = bestTier > 0 ? family[bestTier - 1] : undefined;
    if (backup !== undefined && (counts[backup] ?? 0) > 0) {
      return { itemId: backup, count: counts[backup] ?? 0 };
    }
  }
  return undefined;
}

export function selectRedundantEquipmentDiscard(
  counts: Readonly<Record<string, number>>,
): { readonly itemId: string; readonly count: number } | undefined {
  for (const family of REDUNDANT_EQUIPMENT_FAMILIES) {
    let bestTier = -1;
    for (let tier = family.length - 1; tier >= 0; tier -= 1) {
      const itemId = family[tier];
      if (itemId !== undefined && (counts[itemId] ?? 0) > 0) {
        bestTier = tier;
        break;
      }
    }
    if (bestTier < 0) {
      continue;
    }
    for (let tier = 0; tier < bestTier; tier += 1) {
      const itemId = family[tier];
      if (itemId === undefined) {
        continue;
      }
      const available = counts[itemId] ?? 0;
      const retainedBackup = tier === bestTier - 1 ? 1 : 0;
      if (available > retainedBackup) {
        return {
          itemId,
          count: available - retainedBackup,
        };
      }
    }
    const bestItemId = family[bestTier];
    if (bestItemId !== undefined && (counts[bestItemId] ?? 0) > 1) {
      return {
        itemId: bestItemId,
        count: (counts[bestItemId] ?? 0) - 1,
      };
    }
  }
  for (const itemId of SINGLETON_EQUIPMENT_ITEM_IDS) {
    const available = counts[itemId] ?? 0;
    if (available > 1) {
      return { itemId, count: available - 1 };
    }
  }
  return undefined;
}

function placeWorkstationDirectly(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
  blockId: "minecraft:crafting_table" | "minecraft:furnace",
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const targetBlock = (yield* driver.queryBlocks({
      center: blockCenter(target),
      radius: 0.25,
      selector: {},
      maximumResults: 1,
    }))[0];
    if (targetBlock !== undefined && !targetBlock.replaceable) {
      yield* driver.act({ type: "dig-block", position: target });
    }
    const cleared = (yield* driver.queryBlocks({
      center: blockCenter(target),
      radius: 0.25,
      selector: { replaceable: true },
      maximumResults: 1,
    })).some(({ position }) => sameBlockPosition(position, target));
    if (!cleared) {
      return yield* new BeatGameDriverError({
        operation: "place-workstation",
        retryable: true,
        message: `Could not clear a local position for ${blockId}`,
      });
    }
    yield* driver.act({
      type: "select-item",
      selector: { itemIds: [blockId] },
    });
    yield* driver.act({
      type: "place-block",
      against: {
        ...target,
        y: target.y - 1,
      },
      face: "up",
      hand: "main",
    });
    return undefined;
  });
}

function waitForWorkstation(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
  blockId: "minecraft:crafting_table" | "minecraft:furnace",
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const workstation = yield* queryExactWorkstation(driver, target, blockId);
      if (workstation !== undefined) {
        return workstation;
      }
      yield* Effect.sleep(50);
    }
    return undefined;
  });
}

function queryExactWorkstation(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
  blockId: "minecraft:crafting_table" | "minecraft:furnace",
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: blockCenter(target),
      radius: 0.25,
      selector: { blockIds: [blockId] },
      maximumResults: 1,
    })
    .pipe(
      Effect.map((blocks) => blocks.find(({ position }) => sameBlockPosition(position, target))),
    );
}

function blockCenter(position: BeatGameBlockPosition): BeatGamePosition {
  return {
    x: position.x + 0.5,
    y: position.y + 0.5,
    z: position.z + 0.5,
    dimension: position.dimension,
  };
}

function sameBlockPosition(left: BeatGameBlockPosition, right: BeatGameBlockPosition): boolean {
  return (
    left.x === right.x &&
    left.y === right.y &&
    left.z === right.z &&
    left.dimension === right.dimension
  );
}

function findWorkstationTargets(
  driver: BeatGameDriver,
  position: BeatGamePosition,
): Effect.Effect<readonly BeatGameBlockPosition[], BeatGameDriverError> {
  return Effect.gen(function* () {
    const supports = yield* driver.queryBlocks({
      center: position,
      radius: 4,
      selector: { replaceable: false },
      maximumResults: 256,
    });
    const candidates = supports
      .map(({ position: support }) => ({
        x: support.x,
        y: support.y + 1,
        z: support.z,
        dimension: support.dimension,
      }))
      .filter(
        (candidate) =>
          candidate.dimension === position.dimension &&
          !placementIntersectsPlayer(candidate, position),
      )
      .toSorted(
        (left, right) =>
          workstationDistanceSquared(left, position) - workstationDistanceSquared(right, position),
      )
      .slice(0, 64);
    const available: BeatGameBlockPosition[] = [];
    const clearable: BeatGameBlockPosition[] = [];
    for (const candidate of candidates) {
      if (yield* hasFluidAt(driver, candidate)) {
        continue;
      }
      const replaceable = yield* driver.queryBlocks({
        center: {
          x: candidate.x + 0.5,
          y: candidate.y + 0.5,
          z: candidate.z + 0.5,
          dimension: candidate.dimension,
        },
        radius: 0.25,
        selector: { replaceable: true },
        maximumResults: 1,
      });
      if (
        replaceable.some(
          ({ position: observed }) =>
            observed.x === candidate.x &&
            observed.y === candidate.y &&
            observed.z === candidate.z &&
            observed.dimension === candidate.dimension,
        )
      ) {
        available.push(candidate);
        if (available.length >= 16) {
          break;
        }
        continue;
      }
      const diggable = yield* driver.queryBlocks({
        center: {
          x: candidate.x + 0.5,
          y: candidate.y + 0.5,
          z: candidate.z + 0.5,
          dimension: candidate.dimension,
        },
        radius: 0.25,
        selector: {
          diggable: true,
          interactive: false,
        },
        maximumResults: 1,
      });
      if (
        diggable.some(
          ({ position: observed }) =>
            observed.x === candidate.x &&
            observed.y === candidate.y &&
            observed.z === candidate.z &&
            observed.dimension === candidate.dimension,
        )
      ) {
        clearable.push(candidate);
      }
    }
    return available.length > 0
      ? available
      : clearable.length > 0
        ? clearable.slice(0, 16)
        : yield* new BeatGameDriverError({
            operation: "find-workstation-targets",
            retryable: true,
            message: "No supported open or diggable block is available for a workstation",
          });
  });
}

function placementIntersectsPlayer(
  block: BeatGameBlockPosition,
  player: BeatGamePosition,
): boolean {
  const playerHalfWidth = 0.31;
  const playerHeight = 1.8;
  return (
    player.x + playerHalfWidth > block.x &&
    player.x - playerHalfWidth < block.x + 1 &&
    player.y + playerHeight > block.y &&
    player.y < block.y + 1 &&
    player.z + playerHalfWidth > block.z &&
    player.z - playerHalfWidth < block.z + 1
  );
}

function hasFluidAt(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: blockCenter(position),
      radius: 0.25,
      selector: { blockIds: PLAYER_FLUID_BLOCK_IDS },
      maximumResults: 1,
    })
    .pipe(
      Effect.map((blocks) =>
        blocks.some(({ position: observed }) => sameBlockPosition(observed, position)),
      ),
    );
}

function workstationDistanceSquared(
  target: BeatGameBlockPosition,
  player: BeatGamePosition,
): number {
  const dx = target.x + 0.5 - player.x;
  const dy = target.y - player.y;
  const dz = target.z + 0.5 - player.z;
  return dx * dx + dy * dy + dz * dz;
}

function moveToEyeBaseline(
  state: RunState,
): Effect.Effect<void, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const latest = checkpoint.memory.eyeSamples.at(-1);
    if (latest === undefined) {
      return undefined;
    }
    const baseline = EYE_TRIANGULATION_BASELINE;
    const candidates = [1, -1].flatMap((side) =>
      [1, 0.75, 0.5].map((scale) => ({
        x: latest.origin.x - latest.direction.z * baseline * scale * side,
        y: latest.origin.y,
        z: latest.origin.z + latest.direction.x * baseline * scale * side,
        dimension: latest.origin.dimension,
      })),
    );
    for (const [index, target] of candidates.entries()) {
      const reached = yield* state.driver
        .pathfind(target, 4, state.strategy.path)
        .pipe(Effect.result);
      if (reached._tag === "Success") {
        return undefined;
      }
      if (!isRouteUnavailable(reached.failure) || index === candidates.length - 1) {
        return yield* reached.failure;
      }
    }
    return undefined;
  });
}

function isRouteUnavailable(error: BeatGameDriverError): boolean {
  return (
    error.operation === "pathfind" &&
    /No route found|search limit|certified bound|no progress/iu.test(error.message)
  );
}

interface DurablePortalPlan {
  readonly frame: PortalFrame;
  readonly workspace: BeatGamePortalWorkspace;
  readonly resumed: boolean;
}

function buildAndEnterDurablePortal(
  state: RunState,
  actionCheckpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
  preferCasting: boolean,
): Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError> {
  return reservePortalWorkspace(state, observation, preferCasting).pipe(
    Effect.flatMap((plan) => {
      if (plan === undefined) {
        return Effect.succeed({} satisfies ActionResult);
      }
      const path = portalCarefulPathPolicy(state.strategy.path);
      const remainingFrameBlocks = plan.workspace.targetFrame.filter(
        (target) =>
          !plan.workspace.observedFrame.some((observed) => samePosition(target, observed)),
      ).length;
      const filledLavaBuckets = observation.inventory.counts["minecraft:lava_bucket"] ?? 0;
      const remainingLavaSources = Math.max(
        0,
        remainingFrameBlocks - Math.min(1, filledLavaBuckets),
      );
      const prepare =
        plan.workspace.method === "CAST" && plan.resumed
          ? updatePortalSkill(state, plan.workspace, "prepare-liquid", {
              status: "BUILDING",
            }).pipe(
              Effect.andThen(
                preparePortalCastingLavaPool(state, observation, remainingLavaSources),
              ),
              Effect.flatMap((preparation) =>
                preparation.ready
                  ? updatePortalSkill(state, plan.workspace, "prepare-liquid", {
                      candidateLavaSources: preparation.candidateLavaSources,
                    }).pipe(
                      Effect.andThen(state.driver.observe),
                      Effect.flatMap((current) =>
                        ensurePortalMiningPickaxe(
                          state,
                          current,
                          RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE,
                        ),
                      ),
                      Effect.as(true),
                    )
                  : Effect.succeed(false),
              ),
            )
          : Effect.succeed(true);
      return prepare.pipe(
        Effect.flatMap((ready) =>
          ready
            ? updatePortalSkill(state, plan.workspace, "construct-frame", {
                status: "BUILDING",
              }).pipe(
                Effect.andThen(constructPortalWithDurableObservation(state, plan, path)),
                Effect.tap(({ frame }) => {
                  const observedAt = new Date().toISOString();
                  return state.coordinator.publishDiscovery(actionCheckpoint.teamId, {
                    key: `portal:${positionKey(frame.origin)}`,
                    kind: "portal",
                    botId: actionCheckpoint.botId,
                    position: frame.origin,
                    observedAt,
                    confidence: 1,
                  });
                }),
                Effect.flatMap(({ frame, workspace }) => {
                  const entering = {
                    ...workspace,
                    status: "ENTERING" as const,
                    entryAttempts: workspace.entryAttempts + 1,
                    updatedAt: new Date().toISOString(),
                  };
                  return updatePortalSkill(state, entering, "enter-portal").pipe(
                    Effect.andThen(
                      enterPortal(state.driver, {
                        portal: frame.interior[0] ?? frame.origin,
                        path,
                      }),
                    ),
                    Effect.andThen(
                      updatePortalSkill(
                        state,
                        {
                          ...entering,
                          status: "ENTERED",
                          updatedAt: new Date().toISOString(),
                        },
                        "confirm-dimension",
                      ),
                    ),
                    Effect.as(frame),
                  );
                }),
                Effect.map((frame): ActionResult => ({
                  checkpoint: (checkpoint) => rememberPortal(checkpoint, frame),
                })),
              )
            : Effect.succeed({} satisfies ActionResult),
        ),
      );
    }),
  );
}

function constructPortalWithDurableObservation(
  state: RunState,
  plan: DurablePortalPlan,
  path: BeatGameStrategy["path"],
): Effect.Effect<
  Readonly<{ frame: PortalFrame; workspace: BeatGamePortalWorkspace }>,
  BeatGameError | BeatGameDriverError
> {
  return Effect.scoped(
    Effect.gen(function* () {
      const workspace = yield* Ref.make(plan.workspace);
      yield* Effect.forever(
        Effect.sleep(1000).pipe(
          Effect.andThen(Ref.get(workspace)),
          Effect.flatMap((current) => observeCompletedPortalWorkspace(state, current, plan.frame)),
          Effect.flatMap((current) => Ref.set(workspace, current)),
          Effect.ignore,
        ),
      ).pipe(Effect.forkScoped);
      const frame = yield* plan.workspace.method === "CAST"
        ? castNetherPortal(state.driver, {
            origin: plan.frame.origin,
            axis: plan.frame.axis,
            path: { ...path, avoidFluids: true },
          })
        : buildNetherPortal(state.driver, {
            origin: plan.frame.origin,
            axis: plan.frame.axis,
            path,
          });
      const latest = yield* Ref.get(workspace);
      const observed = yield* observeCompletedPortalWorkspace(state, latest, frame);
      return { frame, workspace: observed };
    }),
  );
}

function reservePortalWorkspace(
  state: RunState,
  observation: BeatGameObservation,
  preferCasting: boolean,
): Effect.Effect<DurablePortalPlan | undefined, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const resumable = latestResumablePortalWorkspace(checkpoint);
    if (
      resumable !== undefined &&
      resumable.origin.dimension === observation.player.position.dimension
    ) {
      return {
        frame: createNetherPortalFrame(resumable.origin, resumable.axis),
        workspace: resumable,
        resumed: true,
      };
    }
    let current = observation;
    let candidateLavaSources: readonly BeatGameBlockPosition[] = [];
    if (preferCasting) {
      yield* persist(state, (currentCheckpoint) =>
        advanceDurableSkill(currentCheckpoint, "find-lava-pool", new Date().toISOString(), {
          protectedItemIds: [
            "minecraft:water_bucket",
            "minecraft:lava_bucket",
            "minecraft:iron_pickaxe",
            "minecraft:flint_and_steel",
          ],
          requiredResources: {
            "minecraft:water_bucket": 1,
            "minecraft:lava_bucket": 1,
          },
        }),
      );
      const preparation = yield* preparePortalCastingLavaPool(state, observation);
      if (!preparation.ready) {
        return undefined;
      }
      candidateLavaSources = preparation.candidateLavaSources;
      current = yield* state.driver.observe;
      yield* ensurePortalMiningPickaxe(state, current, RESOURCE_SEARCH_PICKAXE_DURABILITY_RESERVE);
      current = yield* state.driver.observe;
    }
    const frame = yield* resolvePortalBuildFrame(state.driver, current);
    const now = yield* DateTime.now.pipe(Effect.map(DateTime.formatIso));
    const workspace: BeatGamePortalWorkspace = {
      // oxlint-disable-next-line effecttsgo/crypto-random-uuid-in-effect -- Keep cryptographically generated identifiers across supported runtimes.
      workspaceId: `${checkpoint.activeSkill?.skillId ?? crypto.randomUUID()}:portal`,
      origin: frame.origin,
      axis: frame.axis,
      method: preferCasting ? "CAST" : "OBSIDIAN",
      status: "RESERVED",
      targetFrame: frame.blocks,
      observedFrame: [],
      interior: frame.interior,
      protectedBlocks: frame.blocks,
      candidateLavaSources,
      rejectedLavaSources: [],
      waterFlow: [],
      bucketState:
        (current.inventory.counts["minecraft:water_bucket"] ?? 0) > 0
          ? "WATER"
          : (current.inventory.counts["minecraft:lava_bucket"] ?? 0) > 0
            ? "LAVA"
            : (current.inventory.counts["minecraft:bucket"] ?? 0) > 0
              ? "EMPTY"
              : "UNKNOWN",
      ignitionState: "NOT_ATTEMPTED",
      interiorState: "UNKNOWN",
      entryAttempts: 0,
      observedAt: now,
      updatedAt: now,
    };
    yield* updatePortalSkill(state, workspace, "reserve-workspace");
    return { frame, workspace, resumed: false };
  });
}

function updatePortalSkill(
  state: RunState,
  workspace: BeatGamePortalWorkspace,
  substep: string,
  workspaceUpdate: Partial<BeatGamePortalWorkspace> = {},
): Effect.Effect<BeatGameCheckpoint, BeatGameError> {
  const updatedWorkspace = {
    ...workspace,
    ...workspaceUpdate,
    updatedAt: new Date().toISOString(),
  };
  return persist(state, (checkpoint) =>
    advanceDurableSkill(checkpoint, substep, updatedWorkspace.updatedAt, {
      portalWorkspace: updatedWorkspace,
      targets: [updatedWorkspace.origin],
      protectedBlocks: updatedWorkspace.protectedBlocks,
      completedWorldChanges: updatedWorkspace.observedFrame,
      protectedItemIds: [
        "minecraft:obsidian",
        "minecraft:water_bucket",
        "minecraft:lava_bucket",
        "minecraft:flint_and_steel",
      ],
      requiredResources:
        updatedWorkspace.method === "CAST"
          ? { "minecraft:water_bucket": 1, "minecraft:lava_bucket": 1 }
          : { "minecraft:obsidian": NETHER_PORTAL_FRAME_OBSIDIAN_COUNT },
    }),
  );
}

function observeCompletedPortalWorkspace(
  state: RunState,
  workspace: BeatGamePortalWorkspace,
  frame: PortalFrame,
): Effect.Effect<BeatGamePortalWorkspace, BeatGameError | BeatGameDriverError> {
  const center = {
    ...frame.origin,
    x: frame.origin.x + (frame.axis === "x" ? 1.5 : 0.5),
    y: frame.origin.y + 2,
    z: frame.origin.z + (frame.axis === "z" ? 1.5 : 0.5),
  };
  return Effect.all(
    [
      state.driver.queryBlocks({
        center,
        radius: 5,
        selector: {
          blockIds: ["minecraft:obsidian", "minecraft:nether_portal"],
        },
        maximumResults: 64,
      }),
      state.driver.queryBlocks({
        center,
        radius: 5,
        selector: { blockIds: ["minecraft:water"] },
        maximumResults: 64,
      }),
    ],
    { concurrency: 2 },
  ).pipe(
    Effect.flatMap(([blocks, water]) => {
      const observedKeys = new Set(blocks.map(({ position }) => positionKey(position)));
      const observedFrame = frame.blocks.filter((position) =>
        observedKeys.has(positionKey(position)),
      );
      const portalObserved = frame.interior.some((position) =>
        observedKeys.has(positionKey(position)),
      );
      const waterSource = water.find(({ properties }) => properties["level"] === "0")?.position;
      const updated: BeatGamePortalWorkspace = {
        ...workspace,
        status: portalObserved ? "IGNITED" : "BUILDING",
        observedFrame,
        ...(waterSource === undefined ? {} : { waterSource }),
        waterFlow: water.map(({ position }) => position),
        ignitionState: portalObserved ? "IGNITED" : "NOT_ATTEMPTED",
        interiorState: portalObserved ? "PORTAL" : "CLEAR",
        updatedAt: new Date().toISOString(),
      };
      if (
        updated.status === workspace.status &&
        updated.ignitionState === workspace.ignitionState &&
        updated.interiorState === workspace.interiorState &&
        samePositionSet(updated.observedFrame, workspace.observedFrame) &&
        samePositionSet(updated.waterFlow, workspace.waterFlow) &&
        (updated.waterSource === undefined
          ? workspace.waterSource === undefined
          : workspace.waterSource !== undefined &&
            samePosition(updated.waterSource, workspace.waterSource))
      ) {
        return Effect.succeed(workspace);
      }
      return updatePortalSkill(
        state,
        updated,
        portalObserved ? "portal-ignited" : "repair-frame",
      ).pipe(Effect.as(updated));
    }),
  );
}

function samePositionSet(
  left: readonly BeatGamePosition[],
  right: readonly BeatGamePosition[],
): boolean {
  if (left.length !== right.length) {
    return false;
  }
  const rightKeys = new Set(right.map((position) => positionKey(position)));
  return left.every((position) => rightKeys.has(positionKey(position)));
}

function rememberPortal(checkpoint: BeatGameCheckpoint, frame: PortalFrame): BeatGameCheckpoint {
  const observedAt = new Date().toISOString();
  return {
    ...checkpoint,
    memory: {
      ...checkpoint.memory,
      portals: [
        ...checkpoint.memory.portals.filter(
          ({ key }) => key !== `portal:${positionKey(frame.origin)}`,
        ),
        {
          key: `portal:${positionKey(frame.origin)}`,
          value: {
            blockId: "minecraft:nether_portal",
            position: frame.interior[0] ?? frame.origin,
            properties: { axis: frame.axis },
            diggable: false,
            replaceable: false,
            interactive: false,
            observedAt,
          },
          observedAt,
          confidence: 1,
        },
      ].slice(-32),
    },
  };
}

const LINKED_NETHER_PORTAL_ARRIVAL_RADIUS = 8;
// Centered halfway up the Nether, this radius covers its full height.
const LINKED_NETHER_PORTAL_SEARCH_Y = 64;
const LINKED_NETHER_PORTAL_SEARCH_RADIUS = 72;

// Minecraft links an Overworld portal to a Nether portal near its X and Z
// divided by 8, at any height. When the bot is in the Nether without a
// remembered portal there, it searches the full height at that X and Z,
// walking there first when the search finds nothing from afar, and
// remembers the portal so the remembered-portal route can reach it.
function approachLinkedNetherPortal(
  state: RunState,
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameError | BeatGameDriverError> {
  const player = observation.player.position;
  if (!isNether(player.dimension)) {
    return Effect.succeed(false);
  }
  const target = [
    ...checkpoint.memory.portals
      .filter(({ confidence }) => confidence > 0)
      .map(({ value }) => value.position),
    ...checkpoint.memory.portalWorkspaces
      .filter((workspace) => workspace.status === "ENTERED")
      .map(({ origin }) => origin),
  ]
    .filter(({ dimension }) => dimension === "minecraft:overworld")
    .map(({ x, z }) => ({ x: Math.floor(x / 8), z: Math.floor(z / 8) }))
    .toSorted(
      (left, right) =>
        (left.x - player.x) ** 2 +
        (left.z - player.z) ** 2 -
        ((right.x - player.x) ** 2 + (right.z - player.z) ** 2),
    )[0];
  if (target === undefined) {
    return Effect.succeed(false);
  }
  const findLinkedPortal = state.driver
    .queryBlocks({
      center: {
        x: target.x + 0.5,
        y: LINKED_NETHER_PORTAL_SEARCH_Y,
        z: target.z + 0.5,
        dimension: player.dimension,
      },
      radius: LINKED_NETHER_PORTAL_SEARCH_RADIUS,
      selector: { blockIds: ["minecraft:nether_portal"] },
      maximumResults: 16,
    })
    .pipe(
      Effect.map(
        (portals) =>
          portals
            .filter(({ position }) => !isPortalRouteOnCooldown(checkpoint, position))
            .toSorted(
              (left, right) =>
                (left.position.x - target.x) ** 2 +
                (left.position.z - target.z) ** 2 -
                ((right.position.x - target.x) ** 2 + (right.position.z - target.z) ** 2),
            )[0],
      ),
    );
  return Effect.gen(function* () {
    let portal = yield* findLinkedPortal;
    if (
      portal === undefined &&
      (target.x - player.x) ** 2 + (target.z - player.z) ** 2 >
        LINKED_NETHER_PORTAL_ARRIVAL_RADIUS ** 2
    ) {
      yield* emit(state, {
        type: "diagnostic",
        message: "Walking to the Nether position linked to a remembered Overworld portal",
        data: { position: player, target },
      });
      yield* state.driver.pathfindXZ(
        target.x,
        target.z,
        player.dimension,
        LINKED_NETHER_PORTAL_ARRIVAL_RADIUS,
        state.strategy.path,
      );
      portal = yield* findLinkedPortal;
    }
    if (portal === undefined) {
      return false;
    }
    const found = portal;
    yield* persist(state, (current) => rememberObservedPortal(current, found));
    return true;
  });
}

const ARRIVAL_PORTAL_SCAN_RADIUS = 4;

// The bot otherwise remembers only portals it builds, so after walking
// through one it had no way back from the other side. It stands in the
// portal right after a dimension change, so one scan per arrival finds it.
function rememberArrivalPortal(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError> {
  return Effect.gen(function* () {
    const position = observation.player.position;
    if (
      observation.player.dead ||
      (yield* Ref.getAndSet(state.portalScanDimension, position.dimension)) === position.dimension
    ) {
      return;
    }
    const portal = (yield* state.driver
      .queryBlocks({
        center: position,
        radius: ARRIVAL_PORTAL_SCAN_RADIUS,
        selector: { blockIds: ["minecraft:nether_portal"] },
        maximumResults: 1,
      })
      .pipe(Effect.orElseSucceed(() => [])))[0];
    if (portal === undefined) {
      return;
    }
    yield* persist(state, (checkpoint) => rememberObservedPortal(checkpoint, portal));
  });
}

function rememberObservedPortal(
  checkpoint: BeatGameCheckpoint,
  portal: BeatGameBlockObservation,
): BeatGameCheckpoint {
  const key = `portal:${positionKey(portal.position)}`;
  const known = checkpoint.memory.portals.some(
    ({ confidence, value }) =>
      confidence > 0 &&
      value.position.dimension === portal.position.dimension &&
      distanceSquared(value.position, portal.position) <= 8 ** 2,
  );
  return known
    ? checkpoint
    : {
        ...checkpoint,
        memory: {
          ...checkpoint.memory,
          portals: [
            ...checkpoint.memory.portals.filter((entry) => entry.key !== key),
            { key, value: portal, observedAt: portal.observedAt, confidence: 1 },
          ].slice(-32),
        },
      };
}

function portalCarefulPathPolicy(path: BeatGameStrategy["path"]): BeatGameStrategy["path"] {
  return {
    ...path,
    searchMode: "NORMAL",
    maximumQualityBound: PORTAL_CAREFUL_QUALITY_BOUND,
    maxParkourGap: 0,
    smoothCamera: false,
    sprint: false,
  };
}

function enterKnownPortal(
  state: RunState,
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const attemptedPortalAreas: BeatGamePosition[] = [];
    const currentCheckpoint = yield* Ref.get(state.checkpoint);
    const resumableWorkspace = latestResumablePortalWorkspace(currentCheckpoint);
    const nearby = yield* state.driver.queryBlocks({
      center: observation.player.position,
      radius: 48,
      selector: { blockIds: ["minecraft:nether_portal"] },
      maximumResults: 16,
    });
    const immediate = nearby.find(({ position }) => !isPortalRouteOnCooldown(checkpoint, position));
    if (immediate !== undefined) {
      const entered = yield* tryEnterKnownPortal(
        state,
        checkpoint,
        immediate.position,
        state.strategy.path,
      );
      if (!entered) {
        return false;
      }
      if (
        resumableWorkspace !== undefined &&
        distanceSquared(immediate.position, resumableWorkspace.origin) <= 8 ** 2
      ) {
        yield* markPortalWorkspaceEntered(state, resumableWorkspace);
      }
      return true;
    }

    if (
      resumableWorkspace !== undefined &&
      resumableWorkspace.origin.dimension === observation.player.position.dimension &&
      !isPortalRouteOnCooldown(checkpoint, resumableWorkspace.origin)
    ) {
      attemptedPortalAreas.push(resumableWorkspace.origin);
      const workspaceHadPortalEvidence =
        resumableWorkspace.interiorState === "PORTAL" ||
        checkpoint.memory.portals.some(
          ({ confidence, value }) =>
            confidence > 0 && distanceSquared(value.position, resumableWorkspace.origin) <= 8 ** 2,
        );
      const approached = yield* approachRememberedPortal(state, resumableWorkspace.origin).pipe(
        Effect.result,
      );
      if (approached._tag === "Success") {
        const revalidated = yield* state.driver.queryBlocks({
          center: resumableWorkspace.origin,
          radius: 8,
          selector: { blockIds: ["minecraft:nether_portal"] },
          maximumResults: 16,
        });
        const portal = revalidated[0];
        if (portal !== undefined) {
          const entered = yield* tryEnterKnownPortal(
            state,
            checkpoint,
            portal.position,
            portalCarefulPathPolicy(state.strategy.path),
          );
          if (!entered) {
            return false;
          }
          yield* markPortalWorkspaceEntered(state, resumableWorkspace);
          return true;
        }
      }
      if (workspaceHadPortalEvidence) {
        if (approached._tag === "Failure") {
          yield* markPortalRouteUnavailable(
            state,
            resumableWorkspace.origin,
            `remembered portal route failed: ${approached.failure.message}`,
            approached.failure.code,
          );
        } else {
          yield* invalidatePortalEvidence(
            state,
            resumableWorkspace.origin,
            "remembered portal was absent after revalidation",
          );
        }
      }
    }

    // A lit workspace counts as portal evidence for the planner, so entry
    // tries it too. A live run's portal memory was invalidated while its
    // entered workspace stayed lit, and the planner asked for that portal
    // 1520 times while entry found nothing to try.
    const litWorkspacePortals = checkpoint.memory.portalWorkspaces
      .filter(
        (workspace) =>
          workspace.status !== "ABANDONED" &&
          workspace.interiorState === "PORTAL" &&
          workspace.origin.dimension === observation.player.position.dimension,
      )
      .map((workspace) => ({
        position: workspace.interior[0] ?? workspace.origin,
        confidence: 0.5,
        observedAt: workspace.updatedAt,
      }));
    const remembered = [
      ...checkpoint.memory.portals
        .filter(({ confidence }) => confidence > 0)
        .map(({ value, confidence, observedAt }) => ({
          position: value.position,
          confidence,
          observedAt,
        })),
      ...litWorkspacePortals,
    ]
      .filter(
        ({ position }) =>
          !attemptedPortalAreas.some(
            (attempted) => distanceSquared(attempted, position) <= 8 ** 2,
          ) &&
          !isPortalRouteOnCooldown(checkpoint, position) &&
          position.dimension === observation.player.position.dimension,
      )
      .toSorted(
        (left, right) =>
          right.confidence - left.confidence ||
          Date.parse(right.observedAt) - Date.parse(left.observedAt),
      );
    for (const memory of remembered) {
      const approached = yield* approachRememberedPortal(state, memory.position).pipe(
        Effect.result,
      );
      if (approached._tag === "Failure") {
        yield* markPortalRouteUnavailable(
          state,
          memory.position,
          `remembered portal route failed: ${approached.failure.message}`,
          approached.failure.code,
        );
        continue;
      }
      const revalidated = yield* state.driver.queryBlocks({
        center: memory.position,
        radius: 8,
        selector: { blockIds: ["minecraft:nether_portal"] },
        maximumResults: 16,
      });
      const portal = revalidated[0];
      if (portal === undefined) {
        yield* invalidatePortalEvidence(
          state,
          memory.position,
          "remembered portal was absent after revalidation",
        );
        continue;
      }
      const entered = yield* tryEnterKnownPortal(
        state,
        checkpoint,
        portal.position,
        state.strategy.path,
      );
      if (entered) {
        return true;
      }
    }
    return false;
  });
}

function tryEnterKnownPortal(
  state: RunState,
  checkpoint: BeatGameCheckpoint,
  portal: BeatGameBlockPosition,
  path: BeatGameStrategy["path"],
): Effect.Effect<boolean, BeatGameError | BeatGameDriverError> {
  if (isPortalRouteOnCooldown(checkpoint, portal)) {
    return Effect.succeed(false);
  }
  return Effect.gen(function* () {
    const entry = yield* enterPortal(state.driver, {
      portal,
      path,
    }).pipe(Effect.result);
    if (entry._tag === "Success") {
      yield* clearPortalRouteFailures(state, portal);
      return true;
    }
    if (!entry.failure.retryable) {
      return yield* entry.failure;
    }
    yield* markPortalRouteUnavailable(
      state,
      portal,
      `verified portal entry failed: ${entry.failure.message}`,
    );
    return false;
  });
}

/// A portal the bot just went through works, so the route failures it piled
/// up before no longer count. A live run used its portal for hours while its
/// failures kept adding up, and the first failed route after a later respawn
/// made it forget the portal.
function clearPortalRouteFailures(
  state: RunState,
  portal: BeatGamePosition,
): Effect.Effect<void, BeatGameError> {
  const matchesPortal = (position: BeatGamePosition) =>
    position.dimension === portal.dimension && distanceSquared(position, portal) <= 8 ** 2;
  return persist(state, (checkpoint) => {
    // A progress entry holds where the bot stood, so it is found by the
    // portal position in its key.
    const progressKeys = new Set(
      [portal, ...checkpoint.memory.portals.map(({ value }) => value.position)]
        .filter((position) => matchesPortal(position))
        .map((position) => `portal-route-progress:${positionKey(position)}`),
    );
    return {
      ...checkpoint,
      memory: {
        ...checkpoint.memory,
        unreachable: checkpoint.memory.unreachable.filter(
          ({ key, value }) =>
            !progressKeys.has(key) && !(key.startsWith("portal-route:") && matchesPortal(value)),
        ),
      },
    };
  }).pipe(Effect.asVoid);
}

function hasReusablePortalEvidence(checkpoint: BeatGameCheckpoint, dimension: string): boolean {
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

function approachRememberedPortal(
  state: RunState,
  portal: BeatGamePosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const routePolicy = {
      ...state.strategy.path,
      avoidFluids: true,
      maximumExpandedStates: Math.max(
        state.strategy.path.maximumExpandedStates ?? 0,
        REMEMBERED_PORTAL_MAXIMUM_EXPANDED_STATES,
      ),
    };
    let current = yield* state.driver.observe;
    if (
      current.player.position.dimension === "minecraft:overworld" &&
      portal.dimension === "minecraft:overworld" &&
      Math.sqrt(horizontalDistanceSquared(current.player.position, portal)) >
        REMEMBERED_PORTAL_FINAL_APPROACH_DISTANCE &&
      (yield* needsOverworldSurfaceRecovery(state, current.player.position))
    ) {
      yield* returnToOverworldSurface(state, current.player.position);
      current = yield* state.driver.observe;
    }
    for (let leg = 0; leg < REMEMBERED_PORTAL_MAXIMUM_APPROACH_LEGS; leg += 1) {
      if (current.player.position.dimension !== portal.dimension) {
        return yield* new BeatGameDriverError({
          operation: "pathfindXZ",
          code: "dimension_changed",
          retryable: true,
          message: "Changed dimensions while approaching a remembered portal",
        });
      }
      const distance = Math.sqrt(horizontalDistanceSquared(current.player.position, portal));
      if (distance <= REMEMBERED_PORTAL_FINAL_APPROACH_DISTANCE) {
        break;
      }
      const ratio = Math.min(1, REMEMBERED_PORTAL_MAXIMUM_LEG_DISTANCE / distance);
      const targetX = current.player.position.x + (portal.x - current.player.position.x) * ratio;
      const targetZ = current.player.position.z + (portal.z - current.player.position.z) * ratio;
      yield* pathfindRememberedPortalLeg(
        state,
        targetX,
        targetZ,
        portal.dimension,
        REMEMBERED_PORTAL_LEG_RADIUS,
        routePolicy,
      );
      const previousDistance = distance;
      current = yield* state.driver.observe;
      const remainingDistance = Math.sqrt(
        horizontalDistanceSquared(current.player.position, portal),
      );
      if (previousDistance - remainingDistance < REMEMBERED_PORTAL_MINIMUM_LEG_PROGRESS) {
        return yield* new BeatGameDriverError({
          operation: "pathfindXZ",
          code: "no_progress",
          retryable: true,
          message: "Segmented navigation made no useful progress toward the remembered portal",
        });
      }
    }
    current = yield* state.driver.observe;
    if (
      Math.sqrt(horizontalDistanceSquared(current.player.position, portal)) >
      REMEMBERED_PORTAL_FINAL_APPROACH_DISTANCE
    ) {
      return yield* new BeatGameDriverError({
        operation: "pathfindXZ",
        code: "search_limit",
        retryable: true,
        message: "Remembered portal navigation exceeded its bounded leg count",
      });
    }
    const finalApproachPolicy = {
      ...portalCarefulPathPolicy(state.strategy.path),
      maximumExpandedStates: Math.max(
        state.strategy.path.maximumExpandedStates ?? 0,
        REMEMBERED_PORTAL_MAXIMUM_EXPANDED_STATES,
      ),
    };
    // A live bot respawned 124 blocks above its portal, and a single search
    // for that descent ran out of states every time until the bot gave the
    // portal up. Shorter climbs or descents each fit in one search.
    for (
      let leg = 0;
      leg < REMEMBERED_PORTAL_MAXIMUM_VERTICAL_LEGS &&
      Math.abs(portal.y - current.player.position.y) > REMEMBERED_PORTAL_VERTICAL_LEG_HEIGHT;
      leg += 1
    ) {
      const startY = current.player.position.y;
      yield* pathfindRememberedPortalStep(
        state,
        {
          ...portal,
          y: Math.round(
            startY + Math.sign(portal.y - startY) * REMEMBERED_PORTAL_VERTICAL_LEG_HEIGHT,
          ),
        },
        REMEMBERED_PORTAL_VERTICAL_LEG_RADIUS,
        finalApproachPolicy,
      );
      current = yield* state.driver.observe;
      if (
        Math.abs(current.player.position.y - startY) < REMEMBERED_PORTAL_MINIMUM_VERTICAL_PROGRESS
      ) {
        break;
      }
    }
    yield* pathfindRememberedPortalStep(state, portal, 8, finalApproachPolicy);
    return undefined;
  });
}

// A live descent leg found a route that missed the preferred quality bound
// by a few percent. Like the horizontal legs, it accepts a rougher route.
function pathfindRememberedPortalStep(
  state: RunState,
  position: BeatGamePosition,
  radius: number,
  policy: BeatGameStrategy["path"],
): Effect.Effect<void, BeatGameDriverError> {
  return state.driver.pathfind(position, radius, policy).pipe(
    Effect.catchIf(
      (error) =>
        error.code === "path_quality_bound_not_met" ||
        error.code === "path_search_limit" ||
        error.code === "search_limit",
      () =>
        state.driver.pathfind(position, radius, {
          ...policy,
          searchMode: BeatGamePathSearchMode.URGENT,
          maximumQualityBound: REMEMBERED_PORTAL_FALLBACK_QUALITY_BOUND,
        }),
    ),
  );
}

function pathfindRememberedPortalLeg(
  state: RunState,
  x: number,
  z: number,
  dimension: string,
  radius: number,
  basePolicy: BeatGameStrategy["path"],
): Effect.Effect<void, BeatGameDriverError> {
  const preferredPolicy = {
    ...basePolicy,
    searchMode: BeatGamePathSearchMode.NORMAL,
    maximumQualityBound: REMEMBERED_PORTAL_PREFERRED_QUALITY_BOUND,
  };
  return state.driver.pathfindXZ(x, z, dimension, radius, preferredPolicy).pipe(
    Effect.catchTag("BeatGameDriverError", (error) =>
      error.code === "path_quality_bound_not_met" ||
      error.code === "path_search_limit" ||
      error.code === "search_limit"
        ? state.driver.pathfindXZ(x, z, dimension, radius, {
            ...basePolicy,
            searchMode: BeatGamePathSearchMode.URGENT,
            maximumQualityBound: REMEMBERED_PORTAL_FALLBACK_QUALITY_BOUND,
          })
        : Effect.fail(error),
    ),
  );
}

function invalidatePortalEvidence(
  state: RunState,
  portal: BeatGamePosition,
  reason: string,
): Effect.Effect<void, BeatGameError> {
  const matchesPortal = (position: BeatGamePosition) =>
    position.dimension === portal.dimension && distanceSquared(position, portal) <= 8 ** 2;

  return persist(state, (checkpoint) => {
    const now = new Date().toISOString();
    const downgradeWorkspace = (workspace: BeatGamePortalWorkspace): BeatGamePortalWorkspace =>
      matchesPortal(workspace.origin) && workspace.interiorState === "PORTAL"
        ? {
            ...workspace,
            status: "BUILDING",
            interiorState: "UNKNOWN",
            ignitionState: "NOT_ATTEMPTED",
            updatedAt: now,
          }
        : workspace;
    const activeWorkspace = checkpoint.activeSkill?.portalWorkspace;
    return {
      ...checkpoint,
      ...(checkpoint.activeSkill === undefined || activeWorkspace === undefined
        ? {}
        : {
            activeSkill: {
              ...checkpoint.activeSkill,
              portalWorkspace: downgradeWorkspace(activeWorkspace),
              updatedAt: now,
            },
          }),
      memory: {
        ...checkpoint.memory,
        portals: checkpoint.memory.portals.map((entry) =>
          matchesPortal(entry.value.position) ? { ...entry, confidence: 0 } : entry,
        ),
        portalWorkspaces: checkpoint.memory.portalWorkspaces.map(downgradeWorkspace),
        unreachable: checkpoint.memory.unreachable.filter(
          ({ key, value }) =>
            key !== `portal-route-progress:${positionKey(portal)}` &&
            (!key.startsWith("portal-route:") || !matchesPortal(value)),
        ),
      },
    };
  }).pipe(
    Effect.andThen(
      emit(state, {
        type: "diagnostic",
        message: "Invalidated reusable portal evidence after revalidation",
        data: {
          portal: positionKey(portal),
          reason,
        },
      }),
    ),
    Effect.asVoid,
  );
}

const LOCAL_PATH_STALL_RADIUS = 4;

/// Whether a route failed on a step within reach of the bot, from SoulFire's
/// "Pathfinding stalled on <action> -> [x, y, z]" message.
function isLocalPathStall(reason: string, position: BeatGamePosition): boolean {
  const match = /Pathfinding stalled on .*?\[(-?\d+), (-?\d+), (-?\d+)\]/u.exec(reason);
  if (match === null) {
    return false;
  }
  const [, x, y, z] = match.map(Number);
  return (
    x !== undefined &&
    y !== undefined &&
    z !== undefined &&
    Math.hypot(x + 0.5 - position.x, y - position.y, z + 0.5 - position.z) <=
      LOCAL_PATH_STALL_RADIUS
  );
}

function markPortalRouteUnavailable(
  state: RunState,
  portal: BeatGamePosition,
  reason: string,
  failureCode?: string,
): Effect.Effect<void, BeatGameError> {
  return Effect.gen(function* () {
    // A live run spent an hour retrying a remembered portal it could not
    // reach, mining cobblestone for bridges between tries, and never planned
    // a portal of its own. Each try still crept closer, so progress alone
    // does not keep a portal that keeps failing.
    const current = (yield* Ref.get(state.observation)).player.position;
    const progressKey = `portal-route-progress:${positionKey(portal)}`;
    let routeConfidence = 1;
    yield* persist(state, (checkpoint) => {
      const observedAt = new Date().toISOString();
      const matchesPortal = (position: BeatGamePosition) =>
        position.dimension === portal.dimension && distanceSquared(position, portal) <= 8 ** 2;
      const previous = checkpoint.memory.unreachable.find(({ key }) => key === progressKey);
      const madeProgress =
        previous === undefined ||
        current.dimension !== previous.value.dimension ||
        Math.sqrt(distanceSquared(current, portal)) <=
          Math.sqrt(distanceSquared(previous.value, portal)) -
            REMEMBERED_PORTAL_ROUTE_MINIMUM_PROGRESS;
      // A route that only missed the requested quality bound exists, so it
      // says nothing about whether the portal is reachable. A live run lost
      // most of its confidence in a portal 650 blocks away this way.
      // Neither does a route that stalls right beside the bot: the bot is
      // stuck where it stands. A live run stalled five times climbing out of
      // its own cover and forgot the portal it had come through.
      routeConfidence =
        failureCode === "path_quality_bound_not_met" || isLocalPathStall(reason, current)
          ? (previous?.confidence ?? 1)
          : Math.max(
              0,
              (previous?.confidence ?? 1) -
                (madeProgress
                  ? REMEMBERED_PORTAL_ROUTE_FAILURE_PENALTY
                  : REMEMBERED_PORTAL_ROUTE_STALL_PENALTY),
            );
      return {
        ...checkpoint,
        memory: {
          ...checkpoint.memory,
          unreachable: [
            ...checkpoint.memory.unreachable.filter(
              ({ key, value }) =>
                key !== progressKey && (!key.startsWith("portal-route:") || !matchesPortal(value)),
            ),
            {
              key: `portal-route:${positionKey(portal)}`,
              value: portal,
              observedAt,
              expiresAt: new Date(Date.now() + REMEMBERED_PORTAL_ROUTE_COOLDOWN_MS).toISOString(),
              confidence: 1,
            },
            {
              key: progressKey,
              value: madeProgress ? current : previous.value,
              observedAt,
              confidence: routeConfidence,
            },
          ].slice(-64),
        },
      };
    });
    yield* emit(state, {
      type: "diagnostic",
      message: "Remembered portal route is temporarily unavailable",
      data: {
        portal: positionKey(portal),
        reason,
        routeConfidence,
      },
    });
    if (routeConfidence <= 0) {
      yield* invalidatePortalEvidence(
        state,
        portal,
        "remembered portal route kept failing without progress",
      );
    }
  });
}

function isPortalRouteOnCooldown(
  checkpoint: BeatGameCheckpoint,
  portal: BeatGamePosition,
  now = Date.now(),
): boolean {
  return checkpoint.memory.unreachable.some(({ key, value, observedAt, expiresAt }) => {
    const recordedExpiry =
      expiresAt === undefined ? Number.NEGATIVE_INFINITY : Date.parse(expiresAt);
    const boundedExpiry = Math.min(
      recordedExpiry,
      Date.parse(observedAt) + REMEMBERED_PORTAL_ROUTE_COOLDOWN_MS,
    );
    return (
      key.startsWith("portal-route:") &&
      boundedExpiry > now &&
      value.dimension === portal.dimension &&
      distanceSquared(value, portal) <= 8 ** 2
    );
  });
}

function markPortalWorkspaceEntered(
  state: RunState,
  workspace: BeatGamePortalWorkspace,
): Effect.Effect<BeatGameCheckpoint, BeatGameError> {
  return updatePortalSkill(
    state,
    {
      ...workspace,
      status: "ENTERED",
      interiorState: "PORTAL",
      ignitionState: "IGNITED",
      entryAttempts: workspace.entryAttempts + 1,
      updatedAt: new Date().toISOString(),
    },
    "confirm-dimension",
  );
}

function searchStronghold(
  state: RunState,
): Effect.Effect<boolean, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const estimate = checkpoint.memory.strongholdEstimate;
    if (estimate === undefined) {
      return false;
    }
    let approachObservation = yield* state.driver.observe;
    const surveyPortalFrames = state.driver.queryBlocks({
      center: {
        ...estimate,
        y: 32,
      },
      radius: Math.min(128, Math.max(64, state.strategy.blockSearchRadius)),
      selector: { blockIds: ["minecraft:end_portal_frame"] },
      maximumResults: 12,
    });
    let surveyedFrames = yield* surveyPortalFrames;
    if (surveyedFrames.length === 0) {
      // A live run ended when this level walk to the estimate kept hitting
      // the search limit 33 blocks short of it, underground. The descent
      // below digs its own way if it has to, so a failed approach goes on
      // to it from where the bot stands.
      yield* state.driver
        .pathfind(
          {
            ...estimate,
            y: Math.floor(approachObservation.player.position.y),
          },
          16,
          state.strategy.path,
        )
        .pipe(
          Effect.catchIf(
            (error) => error.operation === "pathfind" && error.retryable,
            () => Effect.void,
          ),
        );
      approachObservation = yield* state.driver.observe;
      surveyedFrames = yield* surveyPortalFrames;
    }
    if (surveyedFrames.length > 0) {
      yield* approachStrongholdPortalRoom(
        state,
        surveyedFrames,
        estimate,
        approachObservation.player.position,
      );
      return true;
    }
    const undergroundTarget = {
      ...estimate,
      y: Math.min(32, estimate.y - 24),
    };
    // The route down may swim through flooded caves. A live bot followed one
    // into a water pocket under a dirt ceiling and drowned there twice, so
    // the descent digs around water instead.
    const descended = yield* state.driver
      .pathfind(undergroundTarget, 16, {
        ...state.strategy.path,
        avoidFluids: true,
      })
      .pipe(
        Effect.as(true),
        Effect.catchIf(
          (error) => error.operation === "pathfind" && error.retryable,
          () => Effect.succeed(false),
        ),
      );
    // A route that deep can outgrow the search: a live run ended after every
    // walk down hit the state limit. A staircase digs down one block per step
    // and stops at flooded steps.
    // It starts where the bot stands: a live run could not walk to a start
    // twenty blocks away either, and ended there.
    if (!descended) {
      const target = floorBlockPosition(undergroundTarget);
      const from = floorBlockPosition((yield* state.driver.observe).player.position);
      yield* excavateStaircase(state.driver, {
        from,
        to: target,
        path: state.strategy.path,
        openSpaceHandoffRadius: 16,
      });
    }
    const observation = yield* state.driver.observe;
    const frames = yield* state.driver.queryBlocks({
      center: observation.player.position,
      radius: 96,
      selector: { blockIds: ["minecraft:end_portal_frame"] },
      maximumResults: 12,
    });
    if (frames.length > 0) {
      yield* approachStrongholdPortalRoom(state, frames, estimate, observation.player.position);
      return true;
    }
    yield* explore(state.driver, {
      origin: {
        x: Math.floor(observation.player.position.x),
        y: Math.floor(observation.player.position.y),
        z: Math.floor(observation.player.position.z),
        dimension: observation.player.position.dimension,
      },
      radius: 96,
      maximumWaypoints: 2,
      purpose: "find-stronghold-portal",
      path: state.strategy.path,
    });
    return false;
  });
}

function approachStrongholdPortalRoom(
  state: RunState,
  frames: readonly BeatGameBlockObservation[],
  estimate: BeatGamePosition,
  currentPosition: BeatGamePosition,
): Effect.Effect<void, BeatGameDriverError> {
  const destination = strongholdEntryPosition(frames, estimate);
  const current = floorBlockPosition(currentPosition);
  const depth = current.y - destination.y;
  if (depth <= 0) {
    return state.driver.pathfind(destination, 4, state.strategy.path);
  }
  return excavateStaircase(state.driver, {
    from: staircaseStartPosition(destination, current),
    to: destination,
    path: state.strategy.path,
    openSpaceHandoffRadius: 1,
  });
}

function floorBlockPosition(position: BeatGamePosition): BeatGameBlockPosition {
  return {
    x: Math.floor(position.x),
    y: Math.floor(position.y),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
}

function strongholdEntryPosition(
  frames: readonly BeatGameBlockObservation[],
  origin: BeatGamePosition,
): BeatGameBlockPosition {
  const minimumX = Math.min(...frames.map(({ position }) => position.x));
  const maximumX = Math.max(...frames.map(({ position }) => position.x));
  const minimumY = Math.min(...frames.map(({ position }) => position.y));
  const minimumZ = Math.min(...frames.map(({ position }) => position.z));
  const maximumZ = Math.max(...frames.map(({ position }) => position.z));
  const centerX = Math.round((minimumX + maximumX) / 2);
  const centerZ = Math.round((minimumZ + maximumZ) / 2);
  const dimension = frames[0]?.position.dimension ?? origin.dimension;
  const candidates: readonly BeatGameBlockPosition[] = [
    {
      x: centerX,
      y: minimumY + 1,
      z: minimumZ - 2,
      dimension,
    },
    {
      x: centerX,
      y: minimumY + 1,
      z: maximumZ + 2,
      dimension,
    },
    {
      x: minimumX - 2,
      y: minimumY + 1,
      z: centerZ,
      dimension,
    },
    {
      x: maximumX + 2,
      y: minimumY + 1,
      z: centerZ,
      dimension,
    },
  ];
  return candidates.reduce((nearest, candidate) =>
    horizontalDistanceSquared(candidate, origin) < horizontalDistanceSquared(nearest, origin)
      ? candidate
      : nearest,
  );
}

function horizontalDistanceSquared(
  left: Pick<BeatGamePosition, "x" | "z">,
  right: Pick<BeatGamePosition, "x" | "z">,
): number {
  return (left.x - right.x) ** 2 + (left.z - right.z) ** 2;
}

function directedHuntRouteCost(
  candidate: BeatGamePosition,
  origin: BeatGamePosition,
  destination?: BeatGamePosition,
): number {
  const approachDistance = Math.sqrt(horizontalDistanceSquared(candidate, origin));
  if (
    destination === undefined ||
    destination.dimension !== origin.dimension ||
    candidate.dimension !== origin.dimension
  ) {
    return approachDistance;
  }
  return approachDistance + Math.sqrt(horizontalDistanceSquared(candidate, destination));
}

function huntingTargetRouteCost(
  candidate: BeatGameEntityObservation,
  origin: BeatGamePosition,
  destination?: BeatGamePosition,
): number {
  const routeCost = directedHuntRouteCost(candidate.position, origin, destination);
  return AQUATIC_FOOD_ENTITY_TYPES.has(candidate.entityType)
    ? routeCost +
        Math.abs(candidate.position.y - origin.y) * AQUATIC_HUNT_VERTICAL_ROUTE_COST +
        Math.max(0, candidate.health ?? AQUATIC_HUNT_DEFAULT_HEALTH) *
          AQUATIC_HUNT_HEALTH_ROUTE_COST
    : routeCost;
}

function isWithinDirectedHuntDetour(
  origin: BeatGamePosition,
  candidate: BeatGamePosition,
  destination?: BeatGamePosition,
): boolean {
  if (
    destination === undefined ||
    destination.dimension !== origin.dimension ||
    candidate.dimension !== origin.dimension
  ) {
    return true;
  }
  const directDistance = Math.sqrt(horizontalDistanceSquared(origin, destination));
  return (
    directedHuntRouteCost(candidate, origin, destination) <=
    directDistance + DIRECTED_HUNT_MAXIMUM_DETOUR
  );
}

function fightDragon(
  state: RunState,
): Effect.Effect<ActionResult, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* state.driver.observe;
    yield* persist(state, (checkpoint) =>
      rememberWorldPlace(checkpoint, "END_FIGHT", observation.player.position, 1),
    );
    yield* fightEnderDragon(state.driver, {
      searchRadius: 320,
      path: state.strategy.path,
    });
    return { phase: BeatGamePhase.EXIT_END };
  });
}

function rememberWorldPlace(
  checkpoint: BeatGameCheckpoint,
  kind: BeatGameWorldPlace["kind"],
  position: BeatGamePosition,
  confidence: number,
): BeatGameCheckpoint {
  const observedAt = new Date().toISOString();
  const normalized = floorBlockPosition(position);
  const key = worldPlaceKey(kind, normalized);
  return {
    ...checkpoint,
    memory: {
      ...checkpoint.memory,
      places: [
        ...checkpoint.memory.places.filter((place) => place.key !== key),
        {
          key,
          kind,
          position: normalized,
          observedAt,
          confidence,
        },
      ].slice(-128),
    },
  };
}

function worldPlaceKey(kind: BeatGameWorldPlace["kind"], position: BeatGamePosition): string {
  if (kind === "SAFE_CORRIDOR" || kind === "FORTRESS") {
    return `${kind}:${position.dimension}:${Math.floor(position.x / 32)}:${Math.floor(
      position.z / 32,
    )}`;
  }
  return `${kind}:${positionKey(position)}`;
}

function advancePhase(state: RunState, phase: BeatGamePhase): Effect.Effect<void, BeatGameError> {
  return Effect.gen(function* () {
    const current = yield* Ref.get(state.checkpoint);
    if (current.planner.phase === phase) {
      return;
    }
    yield* persist(state, (checkpoint) => ({
      ...checkpoint,
      planner: withoutCurrentAction({
        ...checkpoint.planner,
        phase,
        objective: objectiveForPhase(phase),
        requirements: [],
        retryCount: 0,
        updatedAt: new Date().toISOString(),
      }),
    }));
    yield* state.coordinator.updateMember(
      current.teamId,
      current.botId,
      phase,
      BeatGameRunStatus.RUNNING,
    );
    yield* emit(state, {
      type: "phase-changed",
      previous: current.planner.phase,
      current: phase,
    });
    yield* emit(state, {
      type: "objective-changed",
      objective: objectiveForPhase(phase),
    });
  });
}

function completeRun(state: RunState): Effect.Effect<BeatGameResult, BeatGameError> {
  return Effect.gen(function* () {
    const completedAt = yield* DateTime.now.pipe(Effect.map(DateTime.formatIso));
    const finalCheckpoint = yield* persist(state, (checkpoint) => ({
      ...checkpoint,
      planner: withoutCurrentAction({
        ...checkpoint.planner,
        status: BeatGameRunStatus.COMPLETED,
        objective: objectiveForPhase(BeatGamePhase.COMPLETE),
        updatedAt: completedAt,
      }),
    }));
    yield* state.coordinator.updateMember(
      finalCheckpoint.teamId,
      finalCheckpoint.botId,
      BeatGamePhase.COMPLETE,
      BeatGameRunStatus.COMPLETED,
    );
    yield* emit(state, { type: "run-completed" });
    return {
      runId: finalCheckpoint.runId,
      teamId: finalCheckpoint.teamId,
      instanceId: finalCheckpoint.instanceId,
      botId: finalCheckpoint.botId,
      completedAt,
      durationMs: (yield* Clock.currentTimeMillis) - state.startedAtMs,
      finalCheckpoint,
    };
  });
}

function observeFresh(state: RunState): Effect.Effect<BeatGameObservation, BeatGameError> {
  return Effect.gen(function* () {
    const observation = yield* observeDriverFresh(state);
    const pendingDeath = yield* Ref.modify(state.pendingDeaths, (pendingDeaths) => {
      const recoverableDeaths = pendingDeaths.filter((death) => isPendingDeathRecoverable(death));
      return [recoverableDeaths.at(-1), recoverableDeaths] as const;
    });
    if (pendingDeath === undefined) {
      return observation;
    }
    if (!observation.player.dead && observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL) {
      return observation;
    }
    return {
      ...observation,
      observedAt: pendingDeath.observedAt,
      player: {
        ...observation.player,
        position: pendingDeath.position,
        health: 0,
        dead: true,
      },
    };
  });
}

function hasMeaningfulRecoveryInventory(observation: BeatGameObservation): boolean {
  return classifyDeathRecoveryInventory(observation.inventory.counts) !== "trivial";
}

function hasMeleeWeapon(observation: BeatGameObservation): boolean {
  return MELEE_WEAPON_ITEM_IDS.some((itemId) => hasItemInInventoryOrEquipment(observation, itemId));
}

function hasItemInInventoryOrEquipment(observation: BeatGameObservation, itemId: string): boolean {
  return (
    (observation.inventory.counts[itemId] ?? 0) > 0 ||
    Object.values(observation.player.equipment).includes(itemId)
  );
}

function resetAfterCatastrophicInventoryLoss(
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): BeatGameCheckpoint {
  if (
    checkpoint.planner.phase === BeatGamePhase.PREPARE_OVERWORLD ||
    checkpoint.planner.phase === BeatGamePhase.EXIT_END ||
    checkpoint.planner.phase === BeatGamePhase.COMPLETE ||
    observation.player.position.dimension !== "minecraft:overworld"
  ) {
    return checkpoint;
  }
  const counts = observation.inventory.counts;
  const hasPickaxe = MINING_PICKAXE_ITEM_IDS.some((itemId) => (counts[itemId] ?? 0) > 0);
  const hasFood = [...EDIBLE_FOOD_ITEM_IDS, ...EMERGENCY_FOOD_ITEM_IDS].some(
    (itemId) => (counts[itemId] ?? 0) > 0,
  );
  if (Number(hasMeleeWeapon(observation)) + Number(hasPickaxe) + Number(hasFood) >= 2) {
    return checkpoint;
  }
  const repairedCheckpoint =
    checkpoint.memory.latestDeath === undefined
      ? checkpoint
      : forgetDeathPosition(checkpoint, checkpoint.memory.latestDeath.observedAt);
  const resetAt = new Date().toISOString();
  const resetCheckpoint = abandonActiveDurableSkill(
    repairedCheckpoint,
    "catastrophic inventory loss restarted Overworld preparation",
    resetAt,
  );
  return {
    ...resetCheckpoint,
    planner: {
      ...withoutCurrentAction(resetCheckpoint.planner),
      phase: BeatGamePhase.PREPARE_OVERWORLD,
      objective: objectiveForPhase(BeatGamePhase.PREPARE_OVERWORLD),
      requirements: [],
      retryCount: 0,
      completedActions: [],
      updatedAt: resetAt,
    },
  };
}

function shouldRepairCatastrophicInventoryLoss(
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): boolean {
  const latestDeath = checkpoint.memory.latestDeath;
  const recoverableCorpse =
    latestDeath !== undefined &&
    checkpoint.memory.deathPositions.some(
      (entry) =>
        entry.observedAt === latestDeath.observedAt &&
        isDeathRecoveryTimestampRecoverable(entry.observedAt),
    );
  const justCompletedRecovery = checkpoint.lastStableAction?.action === "recover-death";
  return (
    !observation.player.dead &&
    !recoverableCorpse &&
    (latestDeath !== undefined || justCompletedRecovery)
  );
}

function waitForDeathRecoveryInventory(
  state: RunState,
  expectedCounts: Readonly<Record<string, number>> | undefined,
  attemptsRemaining: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  return state.driver.observe.pipe(
    Effect.flatMap((observation) =>
      (
        expectedCounts === undefined
          ? hasMeaningfulRecoveryInventory(observation)
          : hasRecoveredDeathInventory(observation, expectedCounts)
      )
        ? Effect.succeed(true)
        : attemptsRemaining <= 1
          ? Effect.succeed(false)
          : Effect.sleep(Math.max(MINIMUM_RECOVERY_POLL_MS, state.strategy.observationPollMs)).pipe(
              Effect.andThen(
                waitForDeathRecoveryInventory(state, expectedCounts, attemptsRemaining - 1),
              ),
            ),
    ),
  );
}

function updateObservedState(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<void, BeatGameError> {
  return Effect.gen(function* () {
    const previousObservation = yield* Ref.modify(
      state.observation,
      (previous) => [previous, observation] as const,
    );
    if (!observation.player.dead) {
      const lastLivingObservation = yield* Ref.get(state.lastLivingObservation);
      const inventoryWasClearedBeforeDeathState =
        !previousObservation.player.dead &&
        hasMeaningfulRecoveryInventory(lastLivingObservation) &&
        !hasMeaningfulRecoveryInventory(observation) &&
        inventoryItemCount(observation) === 0;
      if (!inventoryWasClearedBeforeDeathState) {
        yield* Ref.set(state.lastLivingObservation, observation);
      }
      return;
    }
    if (previousObservation.player.dead) {
      return;
    }
    const lastLivingObservation = yield* Ref.get(state.lastLivingObservation);
    yield* recordPendingDeath(state, {
      observedAt: observation.observedAt,
      position: observation.player.position,
      recoverItems: hasMeaningfulRecoveryInventory(lastLivingObservation),
      observedLive: true,
      inventoryCounts: lastLivingObservation.inventory.counts,
    });
  });
}

function observeDriverFresh(state: RunState): Effect.Effect<BeatGameObservation, BeatGameError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    return yield* state.driver.observe.pipe(
      Effect.mapError((cause) =>
        observationError(checkpoint.runId, state.driver, checkpoint.planner.phase, cause),
      ),
      Effect.tap((observation) => updateObservedState(state, observation)),
    );
  });
}

function monitorDriverEvents(state: RunState): Effect.Effect<void> {
  // A failed or finished subscription must not end death tracking for the rest of the run.
  return state.driver.events.pipe(
    Stream.runForEach((event) => {
      if (event.type === "bot-damaged") {
        return Ref.set(state.lastDamage, event).pipe(
          Effect.andThen(
            emit(state, {
              type: "diagnostic",
              message: "Took damage",
              data: {
                amount: event.amount,
                health: event.health,
                ...(event.damageType === undefined ? {} : { damageType: event.damageType }),
              },
            }),
          ),
        );
      }
      if (event.type !== "bot-died") {
        return Effect.void;
      }
      return Ref.get(state.lastLivingObservation).pipe(
        Effect.flatMap((lastLivingObservation) =>
          state.driver.observe.pipe(
            Effect.tap((observation) => updateObservedState(state, observation)),
            Effect.catch(() => Ref.get(state.observation)),
            Effect.map((observation) => ({
              lastLivingObservation,
              observation,
            })),
          ),
        ),
        Effect.flatMap(({ lastLivingObservation, observation }) =>
          recordPendingDeath(state, {
            observedAt: event.observedAt,
            position: observation.player.dead
              ? observation.player.position
              : lastLivingObservation.player.position,
            recoverItems: hasMeaningfulRecoveryInventory(lastLivingObservation),
            observedLive: true,
            inventoryCounts: lastLivingObservation.inventory.counts,
            ...(event.message === undefined ? {} : { message: event.message }),
          }),
        ),
      );
    }),
    Effect.ignore,
    Effect.andThen(Effect.sleep(DRIVER_EVENTS_RESUBSCRIBE_DELAY_MS)),
    Effect.forever,
  );
}

function recordPendingDeath(
  state: RunState,
  pendingDeath: PendingDeath,
): Effect.Effect<void, BeatGameError> {
  return Effect.gen(function* () {
    const recordedDeath = yield* enqueuePendingDeath(state, pendingDeath);
    yield* persist(state, (checkpoint) => rememberDeathPosition(checkpoint, recordedDeath));
  });
}

function enqueuePendingDeath(
  state: RunState,
  pendingDeath: PendingDeath,
): Effect.Effect<PendingDeath> {
  return Ref.modify(state.pendingDeaths, (pendingDeaths) => {
    const duplicateIndex = pendingDeaths.findIndex(
      (candidate) =>
        candidate.observedAt === pendingDeath.observedAt ||
        (samePosition(candidate.position, pendingDeath.position) &&
          timestampsAreNear(
            candidate.observedAt,
            pendingDeath.observedAt,
            DEATH_OBSERVATION_DEDUPLICATION_WINDOW_MS,
          )),
    );
    if (duplicateIndex === -1) {
      return [pendingDeath, [...pendingDeaths, pendingDeath]] as const;
    }
    const duplicate = pendingDeaths[duplicateIndex];
    if (duplicate === undefined) {
      return [pendingDeath, pendingDeaths] as const;
    }
    const recoverItems = duplicate.recoverItems || pendingDeath.recoverItems;
    const observedLive = duplicate.observedLive === true || pendingDeath.observedLive === true;
    const inventoryCounts = pendingDeath.inventoryCounts ?? duplicate.inventoryCounts;
    const merged = {
      ...duplicate,
      recoverItems,
      ...(observedLive ? { observedLive: true } : {}),
      ...(inventoryCounts === undefined ? {} : { inventoryCounts }),
      ...(duplicate.message !== undefined
        ? {}
        : pendingDeath.message === undefined
          ? {}
          : { message: pendingDeath.message }),
    };
    if (
      recoverItems === duplicate.recoverItems &&
      observedLive === (duplicate.observedLive === true) &&
      inventoryCounts === duplicate.inventoryCounts &&
      merged.message === duplicate.message
    ) {
      return [duplicate, pendingDeaths] as const;
    }
    return [
      merged,
      pendingDeaths.map((candidate, index) => (index === duplicateIndex ? merged : candidate)),
    ] as const;
  });
}

function timestampsAreNear(left: string, right: string, maximumDistanceMs: number): boolean {
  const leftMs = Date.parse(left);
  const rightMs = Date.parse(right);
  return (
    Number.isFinite(leftMs) &&
    Number.isFinite(rightMs) &&
    Math.abs(leftMs - rightMs) <= maximumDistanceMs
  );
}

function rememberDeathPosition(
  checkpoint: BeatGameCheckpoint,
  pendingDeath: PendingDeath,
): BeatGameCheckpoint {
  const key = `death:${pendingDeath.observedAt}`;
  const observedAtMs = Date.parse(pendingDeath.observedAt);
  const itemExpiresAt = new Date(
    (Number.isFinite(observedAtMs) ? observedAtMs : Date.now()) + DROPPED_ITEM_LOADED_LIFETIME_MS,
  ).toISOString();
  const latestDeath = {
    key,
    value: pendingDeath.position,
    observedAt: pendingDeath.observedAt,
    confidence: 1,
  } satisfies BeatGameMemoryEntry<BeatGamePosition>;
  const alreadyRemembered = checkpoint.memory.deathPositions.some((entry) => entry.key === key);
  return {
    ...checkpoint,
    memory: {
      ...checkpoint.memory,
      latestDeath,
      deathPositions: alreadyRemembered
        ? checkpoint.memory.deathPositions
        : [
            ...checkpoint.memory.deathPositions,
            {
              key,
              value: {
                ...pendingDeath.position,
                itemExpiresAt,
                ...(pendingDeath.inventoryCounts === undefined
                  ? {}
                  : { inventoryCounts: pendingDeath.inventoryCounts }),
              },
              observedAt: pendingDeath.observedAt,
              expiresAt: itemExpiresAt,
              confidence: 1,
            },
          ].slice(-16),
    },
  };
}

function restorePendingDeaths(
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): readonly PendingDeath[] {
  if (checkpoint.memory.keepsInventoryOnDeath === true && !observation.player.dead) {
    return [];
  }
  return checkpoint.memory.deathPositions.flatMap((entry) => {
    const { inventoryCounts, itemExpiresAt: _, ...position } = entry.value;
    if (
      (inventoryCounts === undefined && observation.player.dead) ||
      (inventoryCounts === undefined
        ? hasMeaningfulRecoveryInventory(observation)
        : hasRecoveredDeathInventory(observation, inventoryCounts))
    ) {
      return [];
    }
    const restoredDeath = {
      observedAt: entry.observedAt,
      position,
      recoverItems: true,
      ...(inventoryCounts === undefined ? {} : { inventoryCounts }),
    } satisfies PendingDeath;
    return isPendingDeathRecoverable(restoredDeath) ? [restoredDeath] : [];
  });
}

function hasRecoveredDeathInventory(
  observation: BeatGameObservation,
  deathCounts: Readonly<Record<string, number>>,
): boolean {
  const expectedEntries = Object.entries(deathCounts).filter(([, count]) => count > 0);
  const valuableEntries = expectedEntries.filter(
    ([itemId]) => !RENEWABLE_DEATH_RECOVERY_ITEM_IDS.has(itemId),
  );
  const requiredEntries = valuableEntries.length > 0 ? valuableEntries : expectedEntries;
  return requiredEntries.every(
    ([itemId, count]) => (observation.inventory.counts[itemId] ?? 0) >= count,
  );
}

function sweepRemainingDeathDrops(
  state: RunState,
  expectedCounts: Readonly<Record<string, number>> | undefined,
): Effect.Effect<void, BeatGameDriverError> {
  if (expectedCounts === undefined) {
    return Effect.void;
  }
  return Effect.gen(function* () {
    let observation = yield* state.driver.observe;
    observation = yield* prepareDeathRecoveryInventorySpace(state, observation, expectedCounts);
    const missingItemIds = Object.entries(expectedCounts)
      .filter(([itemId, count]) => count > 0 && (observation.inventory.counts[itemId] ?? 0) < count)
      .map(([itemId]) => itemId);
    if (missingItemIds.length === 0) {
      return;
    }
    yield* collectNearbyDrops(state.driver, {
      itemIds: missingItemIds,
      radius: 24,
      maximumDrops: 64,
      settleDelayMs: 500,
      maximumVerticalDistance: 16,
      path: {
        ...state.strategy.path,
        allowPlacing: false,
        avoidFluids: true,
      },
    });
  });
}

function prepareDeathRecoveryInventorySpace(
  state: RunState,
  observation: BeatGameObservation,
  expectedCounts: Readonly<Record<string, number>> | undefined,
): Effect.Effect<BeatGameObservation, BeatGameDriverError> {
  if (expectedCounts === undefined || observation.inventory.emptyPlayerSlots === undefined) {
    return Effect.succeed(observation);
  }
  const missingItemTypeCount = Object.entries(expectedCounts).filter(
    ([itemId, count]) => count > 0 && (observation.inventory.counts[itemId] ?? 0) < count,
  ).length;
  const requiredSlots = Math.min(DEATH_RECOVERY_PICKUP_RESERVED_SLOTS, missingItemTypeCount);
  if (requiredSlots === 0 || observation.inventory.emptyPlayerSlots >= requiredSlots) {
    return Effect.succeed(observation);
  }
  return ensureInventorySpace(state, observation, requiredSlots).pipe(
    Effect.andThen(state.driver.observe),
  );
}

function chainedDeathRespawnCooldown(pendingDeathCount: number): number {
  const exponent = Math.max(0, Math.floor(pendingDeathCount) - 2);
  return Math.min(
    CHAINED_DEATH_RESPAWN_MAXIMUM_COOLDOWN_MS,
    CHAINED_DEATH_RESPAWN_BASE_COOLDOWN_MS * 2 ** exponent,
  );
}

function deathRecoverySafetyInterruptionCount(checkpoint: BeatGameCheckpoint): number {
  if (checkpoint.activeSkill?.kind !== BeatGameDurableSkillKind.DEATH_RECOVERY) {
    return 0;
  }
  return Object.entries(checkpoint.activeSkill.retries)
    .filter(([reason]) => isDeathRecoverySafetyInterruption(reason))
    .reduce((total, [, count]) => total + count, 0);
}

function isDeathRecoverySafetyInterruption(reason: string): boolean {
  return (
    reason.startsWith("interrupted item recovery to ") ||
    reason.startsWith("paused item recovery to ") ||
    reason === "air fell below the safety threshold" ||
    reason === "bot died again during recovery" ||
    reason === "environmental damage was observed without a nearby attacker" ||
    reason === "hunger became urgent without available food" ||
    reason === "interrupted an action to clutch a dangerous fall"
  );
}

function isPendingDeathRecoverable(pendingDeath: PendingDeath): boolean {
  return isDeathRecoveryTimestampRecoverable(pendingDeath.observedAt);
}

function isDeathRecoveryTimestampRecoverable(observedAt: string): boolean {
  const ageMs = Date.now() - Date.parse(observedAt);
  return Number.isFinite(ageMs) && ageMs >= 0 && ageMs <= DURABLE_DEATH_RECOVERY_WINDOW_MS;
}

function recordDeathRecoveryFailure(
  state: RunState,
  observedAt: string,
  stage: "pickup" | "preparation",
): Effect.Effect<number, BeatGameError> {
  return Effect.gen(function* () {
    const key = `${observedAt}:${stage}`;
    let nextFailureCount = 0;
    yield* persist(state, (checkpoint) => {
      const failures = checkpoint.memory.deathRecoveryFailures ?? {};
      nextFailureCount = (failures[key] ?? 0) + 1;
      return {
        ...checkpoint,
        memory: {
          ...checkpoint.memory,
          deathRecoveryFailures: {
            ...failures,
            [key]: nextFailureCount,
          },
        },
      };
    });
    return nextFailureCount;
  });
}

function deathRecoveryFailureCount(
  checkpoint: BeatGameCheckpoint,
  observedAt: string,
  stage: "pickup" | "preparation",
): number {
  return checkpoint.memory.deathRecoveryFailures?.[`${observedAt}:${stage}`] ?? 0;
}

function clearDeathRecoveryFailure(
  state: RunState,
  observedAt: string,
  stage: "pickup" | "preparation",
): Effect.Effect<void, BeatGameError> {
  return Effect.gen(function* () {
    const key = `${observedAt}:${stage}`;
    const checkpoint = yield* Ref.get(state.checkpoint);
    const failures = checkpoint.memory.deathRecoveryFailures ?? {};
    if (failures[key] === undefined) {
      return;
    }
    yield* persist(state, (current) => {
      const { [key]: _cleared, ...remainingFailures } = current.memory.deathRecoveryFailures ?? {};
      return {
        ...current,
        memory: {
          ...current.memory,
          deathRecoveryFailures: remainingFailures,
        },
      };
    });
  });
}

function madeMeaningfulDeathRecoveryApproach(
  deathPosition: BeatGamePosition,
  before: BeatGamePosition,
  after: BeatGamePosition,
): boolean {
  if (before.dimension !== deathPosition.dimension || after.dimension !== deathPosition.dimension) {
    return false;
  }
  const beforeDistance = Math.sqrt(distanceSquared(before, deathPosition));
  const afterDistance = Math.sqrt(distanceSquared(after, deathPosition));
  return beforeDistance - afterDistance >= DEATH_RECOVERY_PREPARATION_PROGRESS_DISTANCE;
}

function completePendingDeath(state: RunState, observedAt: string): Effect.Effect<void> {
  return Ref.update(state.pendingDeaths, (pendingDeaths) =>
    pendingDeaths.filter((pendingDeath) => pendingDeath.observedAt !== observedAt),
  );
}

function keptInventoryThroughDeath(
  pendingDeath: PendingDeath,
  observation: BeatGameObservation,
): boolean {
  if (observation.player.dead) {
    return false;
  }
  const carried = Object.entries(pendingDeath.inventoryCounts ?? {}).filter(
    ([, count]) => count > 0,
  );
  // A vanilla respawn starts with an empty inventory, so most item kinds
  // still being there means nothing dropped. Exact counts are too strict: a
  // live bot ate during recovery and then searched for its own food.
  const stillCarried = carried.filter(
    ([itemId]) => (observation.inventory.counts[itemId] ?? 0) > 0,
  ).length;
  return (
    carried.length > 0 && stillCarried >= Math.ceil(carried.length * KEPT_INVENTORY_MINIMUM_SHARE)
  );
}

function abandonPendingDeath(
  state: RunState,
  pendingDeath: PendingDeath,
  observation: BeatGameObservation,
  detail: string,
): Effect.Effect<ActionResult> {
  return emit(state, {
    type: "items-recovered",
    detail,
  }).pipe(
    Effect.as({
      checkpoint: (checkpoint) =>
        resetAfterCatastrophicInventoryLoss(
          forgetDeathPosition(checkpoint, pendingDeath.observedAt),
          observation,
        ),
      completedPendingDeath: pendingDeath.observedAt,
    } satisfies ActionResult),
  );
}

function inspectNearbyCorpseDrops(
  state: RunState,
  pendingDeath: PendingDeath,
  observation: BeatGameObservation,
): Effect.Effect<readonly BeatGameEntityObservation[] | undefined, BeatGameDriverError> {
  if (
    observation.player.position.dimension !== pendingDeath.position.dimension ||
    horizontalDistanceSquared(observation.player.position, pendingDeath.position) >
      CORPSE_DROP_INSPECTION_DISTANCE ** 2
  ) {
    return Effect.undefined;
  }
  const corpseItemIds = new Set(
    Object.entries(pendingDeath.inventoryCounts ?? {})
      .filter(([, count]) => count > 0)
      .map(([itemId]) => itemId),
  );
  return state.driver
    .queryEntities({
      origin: pendingDeath.position,
      radius: CORPSE_DROP_MATCH_RADIUS,
      selector: {
        alive: true,
        categories: [6],
      },
      maximumResults: 64,
    })
    .pipe(
      Effect.map((drops) =>
        drops.filter(({ itemId }) => itemId !== undefined && corpseItemIds.has(itemId)),
      ),
    );
}

// A live run respawned at night with nothing, walked back to the mobs that
// had just killed it, and died twice more. Without a weapon, a corpse with
// hostiles beside it waits for the morning.
function hasGuardedCorpseAtNight(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  if (state.driver.environment === undefined || hasMeleeWeapon(observation)) {
    return Effect.succeed(false);
  }
  return Effect.gen(function* () {
    const environment = yield* observeEnvironment(state);
    if (!isHostileNight(environment.gameTime)) {
      return false;
    }
    const pendingDeaths = yield* Ref.get(state.pendingDeaths);
    for (const pendingDeath of pendingDeaths) {
      if (pendingDeath.position.dimension !== observation.player.position.dimension) {
        continue;
      }
      const nearby = yield* state.driver.queryEntities({
        origin: pendingDeath.position,
        radius: CORPSE_GUARD_RADIUS,
        selector: { categories: [2], alive: true },
        maximumResults: 8,
      });
      if (nearby.some((entity) => isCurrentlyHostileMob(entity))) {
        return true;
      }
    }
    return false;
  });
}

function hasVisibleValuableCorpseDrops(
  state: RunState,
  observation: BeatGameObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    const pendingDeaths = yield* Ref.get(state.pendingDeaths);
    for (const pendingDeath of pendingDeaths) {
      if (
        !pendingDeath.recoverItems ||
        classifyDeathRecoveryInventory(pendingDeath.inventoryCounts) !== "valuable"
      ) {
        continue;
      }
      const drops = yield* inspectNearbyCorpseDrops(state, pendingDeath, observation);
      if (drops !== undefined && drops.length > 0) {
        return true;
      }
    }
    return false;
  });
}

function forgetDeathPosition(
  checkpoint: BeatGameCheckpoint,
  observedAt: string,
): BeatGameCheckpoint {
  const failureKeyPrefix = `${observedAt}:`;
  const { latestDeath, ...memoryWithoutLatestDeath } = checkpoint.memory;
  const memory =
    latestDeath?.observedAt === observedAt ? memoryWithoutLatestDeath : checkpoint.memory;
  return {
    ...checkpoint,
    memory: {
      ...memory,
      deathPositions: memory.deathPositions.filter((entry) => entry.observedAt !== observedAt),
      deathRecoveryFailures: Object.fromEntries(
        Object.entries(memory.deathRecoveryFailures ?? {}).filter(
          ([key]) => !key.startsWith(failureKeyPrefix),
        ),
      ),
    },
  };
}

function shouldAttemptDeathRecovery(
  pendingDeath: PendingDeath,
  currentPosition: BeatGamePosition,
): boolean {
  if (pendingDeath.position.dimension !== currentPosition.dimension) {
    return false;
  }
  const recoveryClass = classifyDeathRecoveryInventory(pendingDeath.inventoryCounts);
  if (recoveryClass === "valuable") {
    return true;
  }
  if (recoveryClass === "trivial") {
    return (
      distanceSquared(pendingDeath.position, currentPosition) <=
      IMMEDIATE_CORPSE_RECOVERY_DISTANCE ** 2
    );
  }
  const maximumDistance =
    recoveryClass === "substantial"
      ? SUBSTANTIAL_RENEWABLE_DEATH_RECOVERY_MAX_DISTANCE
      : recoveryClass === "renewable"
        ? RENEWABLE_DEATH_RECOVERY_MAX_DISTANCE
        : UNKNOWN_DEATH_RECOVERY_MAX_DISTANCE;
  return distanceSquared(pendingDeath.position, currentPosition) <= maximumDistance ** 2;
}

function shouldAttemptCrossDimensionDeathRecovery(
  pendingDeath: PendingDeath,
  currentPosition: BeatGamePosition,
): boolean {
  if (
    pendingDeath.position.dimension === currentPosition.dimension ||
    !isNetherPortalDimensionPair(currentPosition.dimension, pendingDeath.position.dimension)
  ) {
    return false;
  }
  const recoveryClass = classifyDeathRecoveryInventory(pendingDeath.inventoryCounts);
  return recoveryClass === "valuable" || recoveryClass === "substantial";
}

function isNetherPortalDimensionPair(left: string, right: string): boolean {
  return (
    (left === "minecraft:overworld" && right === "minecraft:the_nether") ||
    (left === "minecraft:the_nether" && right === "minecraft:overworld")
  );
}

function enterDeathRecoveryDimension(
  state: RunState,
  pendingDeath: PendingDeath,
  observation: BeatGameObservation,
): Effect.Effect<CrossDimensionDeathRecoveryResult, BeatGameError | BeatGameDriverError> {
  return Effect.gen(function* () {
    const currentCheckpoint = yield* Ref.get(state.checkpoint);
    const portalTarget = yield* findDeathRecoveryPortalTarget(
      state,
      currentCheckpoint,
      observation,
    );
    if (portalTarget === undefined) {
      return {
        status: "pending",
        observation,
        reason: "no reachable Nether portal is known in the respawn dimension",
      } satisfies CrossDimensionDeathRecoveryResult;
    }
    const preparationPending = yield* prepareForDistantDeathRecovery(
      state,
      { ...pendingDeath, position: portalTarget },
      observation,
      {
        requireFullRecoveryKit: true,
        travelFoodTargetCount:
          observation.player.health >= state.strategy.minimumHealth &&
          observation.player.food >= 18 &&
          deathRecoveryTravelFoodCount(observation) >= DEATH_RECOVERY_MINIMUM_STAGING_FOOD_COUNT
            ? DEATH_RECOVERY_MINIMUM_STAGING_FOOD_COUNT
            : DEATH_RECOVERY_FOOD_RESERVE_COUNT,
      },
    );
    if (preparationPending !== undefined) {
      return {
        status: "pending",
        observation: yield* state.driver.observe,
        portalTarget,
        reason: preparationPending,
      } satisfies CrossDimensionDeathRecoveryResult;
    }
    yield* emit(state, {
      type: "diagnostic",
      message: "Returning through a remembered portal for a valuable corpse",
      data: {
        deathPosition: pendingDeath.position,
        portalTarget,
      },
    });
    const refreshedCheckpoint = yield* Ref.get(state.checkpoint);
    const portalObservation = yield* state.driver.observe;
    const entry = yield* enterKnownPortal(state, refreshedCheckpoint, portalObservation).pipe(
      Effect.map((entered) =>
        entered ? { type: "entered" as const } : { type: "missing" as const },
      ),
      Effect.catchTag("BeatGameDriverError", (error) =>
        Effect.succeed({ type: "failed" as const, error }),
      ),
    );
    if (entry.type !== "entered") {
      return {
        status: "pending",
        observation: yield* state.driver.observe,
        portalTarget,
        reason:
          entry.type === "failed"
            ? `remembered portal entry failed: ${entry.error.message}`
            : "remembered portal evidence could not be revalidated",
      } satisfies CrossDimensionDeathRecoveryResult;
    }
    const enteredObservation = yield* observeDriverFresh(state);
    if (enteredObservation.player.position.dimension !== pendingDeath.position.dimension) {
      return {
        status: "pending",
        observation: enteredObservation,
        portalTarget,
        reason: `portal entered ${enteredObservation.player.position.dimension} instead of ${pendingDeath.position.dimension}`,
      } satisfies CrossDimensionDeathRecoveryResult;
    }
    return {
      status: "entered",
      observation: enteredObservation,
      portalTarget,
    } satisfies CrossDimensionDeathRecoveryResult;
  });
}

function findDeathRecoveryPortalTarget(
  state: RunState,
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
): Effect.Effect<BeatGamePosition | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const nearby = yield* state.driver.queryBlocks({
      center: observation.player.position,
      radius: 48,
      selector: { blockIds: ["minecraft:nether_portal"] },
      maximumResults: 16,
    });
    const immediate = nearby
      .filter(
        ({ position }) =>
          position.dimension === observation.player.position.dimension &&
          !isPortalRouteOnCooldown(checkpoint, position),
      )
      .toSorted(
        (left, right) =>
          distanceSquared(left.position, observation.player.position) -
          distanceSquared(right.position, observation.player.position),
      )[0];
    if (immediate !== undefined) {
      return immediate.position;
    }
    const activeWorkspace = checkpoint.activeSkill?.portalWorkspace;
    const candidates = [
      ...checkpoint.memory.portals
        .filter(({ confidence }) => confidence > 0)
        .map(({ value }) => value.position),
      ...checkpoint.memory.portalWorkspaces
        .filter(
          (workspace) => workspace.status !== "ABANDONED" && workspace.interiorState === "PORTAL",
        )
        .map((workspace) => workspace.origin),
      ...(activeWorkspace === undefined ||
      activeWorkspace.status === "ABANDONED" ||
      activeWorkspace.interiorState !== "PORTAL"
        ? []
        : [activeWorkspace.origin]),
    ].filter(
      (position) =>
        position.dimension === observation.player.position.dimension &&
        !isPortalRouteOnCooldown(checkpoint, position),
    );
    const unique = [
      ...new Map(candidates.map((position) => [positionKey(position), position])).values(),
    ];
    return unique.toSorted(
      (left, right) =>
        distanceSquared(left, observation.player.position) -
        distanceSquared(right, observation.player.position),
    )[0];
  });
}

function madeCrossDimensionDeathRecoveryProgress(
  before: BeatGameObservation,
  after: BeatGameObservation,
  portalTarget: BeatGamePosition | undefined,
): boolean {
  if (before.player.position.dimension !== after.player.position.dimension) {
    return true;
  }
  if (
    portalTarget !== undefined &&
    before.player.position.dimension === portalTarget.dimension &&
    after.player.position.dimension === portalTarget.dimension &&
    Math.sqrt(horizontalDistanceSquared(before.player.position, portalTarget)) -
      Math.sqrt(horizontalDistanceSquared(after.player.position, portalTarget)) >=
      DEATH_RECOVERY_PREPARATION_PROGRESS_DISTANCE
  ) {
    return true;
  }
  return deathRecoveryPreparationScore(after) > deathRecoveryPreparationScore(before);
}

function deathRecoveryPreparationScore(observation: BeatGameObservation): number {
  const buildingMaterials = pathBuildingBlockCount(observation);
  const logs = LOG_ITEM_IDS.reduce(
    (total, itemId) => total + (observation.inventory.counts[itemId] ?? 0),
    0,
  );
  return (
    Math.min(DEATH_RECOVERY_FOOD_RESERVE_COUNT, deathRecoveryTravelFoodCount(observation)) +
    Math.min(DEATH_RECOVERY_BOOTSTRAP_BLOCK_COUNT, buildingMaterials) +
    Math.min(DEATH_RECOVERY_BOOTSTRAP_LOG_COUNT, logs) +
    Number(hasMeleeWeapon(observation)) * 32 +
    Number(hasMiningPickaxe(observation)) * 32
  );
}

function prepareForDistantDeathRecovery(
  state: RunState,
  pendingDeath: PendingDeath,
  observation: BeatGameObservation,
  options: Readonly<{
    readonly requireFullRecoveryKit?: boolean;
    readonly travelFoodTargetCount?: number;
  }> = {},
): Effect.Effect<string | undefined, BeatGameError | BeatGameDriverError> {
  if (observation.player.position.dimension !== pendingDeath.position.dimension) {
    return Effect.undefined;
  }

  return Effect.gen(function* () {
    let current = observation;
    const recoveryPath = {
      ...state.strategy.path,
      avoidFluids: false,
      additionalPlaceItemIds: DEATH_RECOVERY_ADDITIONAL_PLACE_ITEM_IDS,
    };
    const protectedRecoveryPath = {
      ...recoveryPath,
      allowPlacing: false,
      avoidFluids: true,
    };
    const preparationTarget = (value: BeatGameObservation) =>
      deathRecoveryPreparationTarget(value.player.position, pendingDeath.position);
    const buildingMaterialCount = pathBuildingBlockCount;
    const travelFoodCount = deathRecoveryTravelFoodCount;
    const travelFoodTargetCount = Math.max(
      DEATH_RECOVERY_MINIMUM_STAGING_FOOD_COUNT,
      Math.min(
        DEATH_RECOVERY_FOOD_RESERVE_COUNT,
        options.travelFoodTargetCount ?? DEATH_RECOVERY_FOOD_RESERVE_COUNT,
      ),
    );
    const additionalLogsForWoodenPickaxe = (value: BeatGameObservation): number => {
      const plankEquivalent =
        PLANK_ITEM_IDS.reduce((total, itemId) => total + (value.inventory.counts[itemId] ?? 0), 0) +
        inventoryLogCount(value) * 4;
      const craftingTablePlanks =
        (value.inventory.counts["minecraft:crafting_table"] ?? 0) > 0 ? 0 : 4;
      const stickPlanks = (value.inventory.counts["minecraft:stick"] ?? 0) >= 2 ? 0 : 2;
      const missingPlanks = Math.max(0, 3 + craftingTablePlanks + stickPlanks - plankEquivalent);
      return Math.ceil(missingPlanks / 4);
    };
    const ensureTravelFood = (
      value: BeatGameObservation,
    ): Effect.Effect<BeatGameObservation, BeatGameError | BeatGameDriverError> => {
      const food = travelFoodCount(value);
      if (food >= travelFoodTargetCount) {
        return Effect.succeed(value);
      }
      const needsUrgentAquaticFood = value.player.food <= URGENT_HUNGER_FOOD_LEVEL;
      const foodSearchPath = needsUrgentAquaticFood
        ? { ...protectedRecoveryPath, avoidFluids: false }
        : protectedRecoveryPath;
      const search = huntOrExplore(
        state,
        value,
        {
          entityTypes: foodHuntEntityTypes(
            value.player.food,
            value.player.health >= state.strategy.minimumHealth,
          ),
          alive: true,
        },
        travelFoodTargetCount - food,
        "prepare-corpse-recovery-food",
        {
          preferredEntityTypes: HIGH_YIELD_FOOD_ANIMAL_TYPES,
          preferredRadius: HIGH_YIELD_FOOD_PREFERENCE_RADIUS,
          maximumExplorationHops: 2,
          path: foodSearchPath,
          explorationTarget: preparationTarget(value),
          allowCriticalAquaticTargets: true,
          maximumSafeAquaticFoodLevel: Math.max(
            state.strategy.eatBelowFood,
            URGENT_HUNGER_FOOD_LEVEL,
          ),
          requireHealthRecoveryForSafeAquaticTargets: true,
          safeAquaticFallbackAfterExplorationLegs:
            value.player.health < state.strategy.minimumHealth
              ? 0
              : DEATH_RECOVERY_AQUATIC_FALLBACK_EXPLORATION_LEGS,
          allowFluidFallback: true,
          fallbackToLocalExploration: true,
        },
      );
      return search.pipe(
        Effect.timeoutOrElse({
          duration: DEATH_RECOVERY_FOOD_SEARCH_TIMEOUT_MS,
          orElse: () => Effect.void,
        }),
        Effect.andThen(state.driver.observe),
      );
    };
    const recoveryDistanceSquared = distanceSquared(current.player.position, pendingDeath.position);
    const horizontalRecoveryDistanceSquared = horizontalDistanceSquared(
      current.player.position,
      pendingDeath.position,
    );
    const recoveryRequiresExcavation = pendingDeath.position.y < current.player.position.y - 8;
    const recoveryRequiresPreparedExcavation =
      recoveryRequiresExcavation &&
      (pendingDeath.position.y < current.player.position.y - DEEP_CORPSE_EXCAVATION_MINIMUM_DEPTH ||
        horizontalRecoveryDistanceSquared >
          DISTANT_CORPSE_EXCAVATION_MINIMUM_HORIZONTAL_DISTANCE ** 2);
    const requiredPickaxeDurability = Math.min(
      STONE_PICKAXE_MAXIMUM_DURABILITY,
      Math.ceil(
        Math.max(0, current.player.position.y - pendingDeath.position.y) *
          DEATH_RECOVERY_PICKAXE_DURABILITY_PER_DESCENT_LEVEL,
      ) + DEATH_RECOVERY_PICKAXE_DURABILITY_BUFFER,
    );
    const hasPreparedExcavationPickaxe = (value: BeatGameObservation): boolean =>
      hasMiningPickaxeReserve(
        value,
        STONE_OR_BETTER_MINING_PICKAXE_ITEM_IDS,
        requiredPickaxeDurability,
      );
    const hasViableStagingFood =
      horizontalRecoveryDistanceSquared <= DEATH_RECOVERY_PREPARATION_STAGING_DISTANCE ** 2 &&
      current.player.food > CRITICAL_HUNGER_FOOD_LEVEL &&
      deathRecoveryTravelFoodCount(current) >= DEATH_RECOVERY_MINIMUM_STAGING_FOOD_COUNT;
    const canRaceActiveCorpse =
      current.player.health >= state.strategy.minimumHealth &&
      (deathRecoveryTravelFoodCount(current) >= DEATH_RECOVERY_FOOD_RESERVE_COUNT ||
        hasViableStagingFood ||
        (isRecentActiveCorpse(pendingDeath) &&
          (current.player.food > URGENT_HUNGER_FOOD_LEVEL ||
            (horizontalRecoveryDistanceSquared <=
              DEATH_RECOVERY_PREPARATION_STAGING_DISTANCE ** 2 &&
              current.player.food > CRITICAL_HUNGER_FOOD_LEVEL)))) &&
      (!recoveryRequiresPreparedExcavation || hasPreparedExcavationPickaxe(current)) &&
      (hasMeleeWeapon(current) || !hasMeaningfulRecoveryInventory(current));
    if (
      options.requireFullRecoveryKit !== true &&
      (recoveryDistanceSquared <= IMMEDIATE_CORPSE_RECOVERY_DISTANCE ** 2 ||
        (recoveryDistanceSquared <= ACTIVE_CORPSE_RECOVERY_DISTANCE ** 2 && canRaceActiveCorpse))
    ) {
      return undefined;
    }
    const ensureBuildingMaterials = (
      value: BeatGameObservation,
    ): Effect.Effect<BeatGameObservation, BeatGameDriverError> => {
      const buildingMaterials = buildingMaterialCount(value);
      if (buildingMaterials >= DEATH_RECOVERY_BOOTSTRAP_BLOCK_COUNT) {
        return Effect.succeed(value);
      }
      return collectBlocksOrExplore(state, value, {
        blockIds: DEATH_RECOVERY_BUILDING_BLOCK_IDS,
        count: DEATH_RECOVERY_BOOTSTRAP_BLOCK_COUNT - buildingMaterials,
        progressItemIds: DEATH_RECOVERY_BUILDING_ITEM_IDS,
        purpose: "prepare-corpse-recovery-blocks",
        avoidSubmergedTargets: true,
        path: protectedRecoveryPath,
        explorationTarget: preparationTarget(value),
      }).pipe(Effect.andThen(state.driver.observe));
    };

    if (
      (current.player.food <= CRITICAL_HUNGER_FOOD_LEVEL ||
        current.player.health < state.strategy.minimumHealth) &&
      travelFoodCount(current) < DEATH_RECOVERY_FOOD_RESERVE_COUNT
    ) {
      current = yield* ensureTravelFood(current);
      if (travelFoodCount(current) < DEATH_RECOVERY_FOOD_RESERVE_COUNT) {
        return DEATH_RECOVERY_FOOD_SEARCH_PENDING;
      }
    }
    if (!hasMeleeWeapon(current)) {
      const logs = inventoryLogCount(current);
      if (logs < EMERGENCY_ARMAMENT_LOG_COUNT) {
        yield* collectBlocksOrExplore(state, current, {
          blockIds: LOG_ITEM_IDS,
          count: EMERGENCY_ARMAMENT_LOG_COUNT - logs,
          progressItemIds: LOG_ITEM_IDS,
          purpose: "prepare-corpse-recovery",
          avoidSubmergedTargets: true,
          requireSurfaceTargets: true,
          path: protectedRecoveryPath,
          explorationTarget: preparationTarget(current),
        });
        current = yield* state.driver.observe;
      }
      if (inventoryLogCount(current) < EMERGENCY_ARMAMENT_LOG_COUNT) {
        return "still gathering enough wood to craft a corpse recovery weapon";
      }
      yield* craftWithTable(state, current, "minecraft:wooden_sword", 1);
      current = yield* state.driver.observe;
    }
    if (
      recoveryDistanceSquared > RENEWABLE_DEATH_RECOVERY_MAX_DISTANCE ** 2 &&
      (current.player.food <= state.strategy.eatBelowFood ||
        current.player.health < state.strategy.minimumHealth) &&
      travelFoodCount(current) < DEATH_RECOVERY_FOOD_RESERVE_COUNT
    ) {
      current = yield* ensureTravelFood(current);
      if (travelFoodCount(current) < DEATH_RECOVERY_FOOD_RESERVE_COUNT) {
        return DEATH_RECOVERY_FOOD_SEARCH_PENDING;
      }
    }
    if (
      recoveryRequiresExcavation &&
      (!hasMiningPickaxe(current) ||
        (recoveryRequiresPreparedExcavation && !hasPreparedExcavationPickaxe(current)))
    ) {
      let additionalLogs = additionalLogsForWoodenPickaxe(current);
      if (
        additionalLogs > 0 &&
        (recoveryRequiresPreparedExcavation ||
          recoveryDistanceSquared > DISTANT_DEATH_RECOVERY_BOOTSTRAP_DISTANCE ** 2)
      ) {
        yield* collectBlocksOrExplore(state, current, {
          blockIds: LOG_ITEM_IDS,
          count: additionalLogs,
          progressItemIds: LOG_ITEM_IDS,
          purpose: "prepare-corpse-recovery-pickaxe",
          avoidSubmergedTargets: true,
          requireSurfaceTargets: true,
          path: protectedRecoveryPath,
          explorationTarget: preparationTarget(current),
        });
        current = yield* state.driver.observe;
        additionalLogs = additionalLogsForWoodenPickaxe(current);
      }
      if (additionalLogs === 0) {
        if (!hasMiningPickaxe(current)) {
          yield* craftWithTable(state, current, "minecraft:wooden_pickaxe", 1);
          current = yield* state.driver.observe;
        }
        if (recoveryRequiresPreparedExcavation && !hasPreparedExcavationPickaxe(current)) {
          const useIronPickaxe = (current.inventory.counts["minecraft:iron_ingot"] ?? 0) >= 3;
          const cobblestone = current.inventory.counts["minecraft:cobblestone"] ?? 0;
          if (!useIronPickaxe && cobblestone < 3) {
            yield* collectBlocksOrExplore(state, current, {
              blockIds: ["minecraft:stone"],
              count: 3 - cobblestone,
              progressItemIds: ["minecraft:cobblestone"],
              purpose: "prepare-corpse-recovery-stone-pickaxe",
              avoidSubmergedTargets: true,
              avoidFluids: true,
              path: protectedRecoveryPath,
              explorationTarget: preparationTarget(current),
              prepareAttempt: (value) =>
                ensureMiningPickaxe(
                  state,
                  value,
                  "minecraft:wooden_pickaxe",
                  MINING_PICKAXE_ITEM_IDS,
                  4,
                ),
            });
            current = yield* state.driver.observe;
          }
          yield* ensureMiningPickaxe(
            state,
            current,
            useIronPickaxe ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe",
            useIronPickaxe
              ? DURABLE_MINING_PICKAXE_ITEM_IDS
              : STONE_OR_BETTER_MINING_PICKAXE_ITEM_IDS,
            requiredPickaxeDurability,
          );
          current = yield* state.driver.observe;
        }
      }
    }
    if (
      options.requireFullRecoveryKit !== true &&
      recoveryDistanceSquared <= DISTANT_DEATH_RECOVERY_BOOTSTRAP_DISTANCE ** 2
    ) {
      return !recoveryRequiresPreparedExcavation || hasPreparedExcavationPickaxe(current)
        ? undefined
        : "still gathering enough wood to craft a deep corpse recovery pickaxe";
    }
    const bufferedLogs = inventoryLogCount(current);
    if (bufferedLogs < DEATH_RECOVERY_BOOTSTRAP_LOG_COUNT) {
      yield* collectBlocksOrExplore(state, current, {
        blockIds: LOG_ITEM_IDS,
        count: DEATH_RECOVERY_BOOTSTRAP_LOG_COUNT - bufferedLogs,
        progressItemIds: LOG_ITEM_IDS,
        purpose: "prepare-corpse-recovery-log-buffer",
        avoidSubmergedTargets: true,
        requireSurfaceTargets: true,
        path: protectedRecoveryPath,
        explorationTarget: preparationTarget(current),
      });
      current = yield* state.driver.observe;
    }

    current = yield* ensureBuildingMaterials(current);
    if (
      !hasMeleeWeapon(current) ||
      (recoveryRequiresExcavation &&
        (recoveryRequiresPreparedExcavation
          ? !hasPreparedExcavationPickaxe(current)
          : !hasMiningPickaxe(current))) ||
      buildingMaterialCount(current) < DEATH_RECOVERY_BOOTSTRAP_BLOCK_COUNT
    ) {
      return "still gathering recovery tools and disposable building blocks for distant corpse recovery";
    }

    const food = travelFoodCount(current);
    if (food < travelFoodTargetCount) {
      current = yield* ensureTravelFood(current);
    }
    if (travelFoodCount(current) < travelFoodTargetCount) {
      return DEATH_RECOVERY_FOOD_SEARCH_PENDING;
    }
    current = yield* ensureBuildingMaterials(current);
    if (buildingMaterialCount(current) < DEATH_RECOVERY_BOOTSTRAP_BLOCK_COUNT) {
      return "still replenishing the disposable building block reserve for distant corpse recovery";
    }
    return undefined;
  });
}

function deathRecoveryPreparationTarget(
  currentPosition: BeatGamePosition,
  deathPosition: BeatGamePosition,
): BeatGamePosition {
  const deltaX = currentPosition.x - deathPosition.x;
  const deltaZ = currentPosition.z - deathPosition.z;
  const distance = Math.hypot(deltaX, deltaZ);
  const directionX = distance === 0 ? 1 : deltaX / distance;
  const directionZ = distance === 0 ? 0 : deltaZ / distance;
  return {
    x: deathPosition.x + directionX * DEATH_RECOVERY_PREPARATION_STAGING_DISTANCE,
    y: currentPosition.y,
    z: deathPosition.z + directionZ * DEATH_RECOVERY_PREPARATION_STAGING_DISTANCE,
    dimension: currentPosition.dimension,
  };
}

function isRecentActiveCorpse(pendingDeath: PendingDeath): boolean {
  return isRecentDeathObservation(pendingDeath.observedAt);
}

function isRecentDeathObservation(observedAt: string): boolean {
  const ageMs = Date.now() - Date.parse(observedAt);
  return Number.isFinite(ageMs) && ageMs >= 0 && ageMs <= ACTIVE_CORPSE_RECOVERY_MAX_AGE_MS;
}

function shouldScoutStaleCorpse(
  pendingDeath: PendingDeath,
  observation: BeatGameObservation,
): boolean {
  if (
    isRecentActiveCorpse(pendingDeath) ||
    pendingDeath.position.dimension !== observation.player.position.dimension ||
    observation.player.health <= LETHAL_MELEE_DISENGAGE_HEALTH ||
    observation.player.food <= CRITICAL_HUNGER_FOOD_LEVEL ||
    Math.abs(pendingDeath.position.y - observation.player.position.y) >
      STALE_CORPSE_SCOUT_MAXIMUM_VERTICAL_DISTANCE
  ) {
    return false;
  }
  return (
    horizontalDistanceSquared(pendingDeath.position, observation.player.position) <=
    STALE_CORPSE_SCOUT_MAXIMUM_DISTANCE ** 2
  );
}

function classifyDeathRecoveryInventory(
  counts: Readonly<Record<string, number>> | undefined,
): "renewable" | "substantial" | "trivial" | "unknown" | "valuable" {
  if (counts === undefined) {
    return "unknown";
  }
  const entries = Object.entries(counts).filter(([, count]) => count > 0);
  if (entries.some(([itemId]) => !RENEWABLE_DEATH_RECOVERY_ITEM_IDS.has(itemId))) {
    return "valuable";
  }
  const usefulRenewableItems = entries.reduce(
    (total, [itemId, count]) =>
      LOW_VALUE_DEATH_RECOVERY_ITEM_IDS.has(itemId) ? total : total + count,
    0,
  );
  if (usefulRenewableItems === 0) {
    return "trivial";
  }
  return usefulRenewableItems >= SUBSTANTIAL_RENEWABLE_DEATH_RECOVERY_ITEM_COUNT
    ? "substantial"
    : "renewable";
}

function observeWithRecovery(state: RunState): Effect.Effect<BeatGameObservation, BeatGameError> {
  const attempt = (
    recovering: boolean,
    retryCount: number,
  ): Effect.Effect<BeatGameObservation, BeatGameError> =>
    observeFresh(state).pipe(
      Effect.tap((observation) =>
        !recovering
          ? Effect.void
          : Effect.gen(function* () {
              const checkpoint = yield* Ref.get(state.checkpoint);
              const paused = yield* Ref.get(state.paused);
              const status = paused ? BeatGameRunStatus.PAUSED : BeatGameRunStatus.RUNNING;
              yield* persist(state, (current) => ({
                ...current,
                connectionEpoch: observation.player.connectionEpoch,
                planner: {
                  ...current.planner,
                  status,
                  updatedAt: new Date().toISOString(),
                },
              }));
              yield* state.coordinator.updateMember(
                checkpoint.teamId,
                checkpoint.botId,
                checkpoint.planner.phase,
                status,
              );
              yield* emit(state, {
                type: "bot-recovered",
                detail: `Observation stream recovered after ${retryCount} retries`,
              });
            }),
      ),
      Effect.catch((error) => {
        if (!error.retryable) {
          return Effect.fail(error);
        }
        const enterRecovery = recovering
          ? Effect.void
          : Effect.gen(function* () {
              const checkpoint = yield* persist(state, (current) => ({
                ...current,
                planner: {
                  ...current.planner,
                  status: BeatGameRunStatus.RECOVERING,
                  updatedAt: new Date().toISOString(),
                },
              }));
              yield* state.coordinator.updateMember(
                checkpoint.teamId,
                checkpoint.botId,
                checkpoint.planner.phase,
                BeatGameRunStatus.RECOVERING,
              );
              yield* emit(state, {
                type: "bot-disconnected",
                detail: error.message,
              });
            });
        return enterRecovery.pipe(
          Effect.andThen(Effect.sleep(backoffDuration(retryCount + 1))),
          Effect.andThen(attempt(true, retryCount + 1)),
        );
      }),
    );
  return attempt(false, 0);
}

function persist(
  state: RunState,
  update: (checkpoint: BeatGameCheckpoint) => BeatGameCheckpoint,
): Effect.Effect<BeatGameCheckpoint, BeatGameError> {
  return state.checkpointMutex.withPermits(1)(
    Effect.uninterruptible(
      Effect.gen(function* () {
        const current = yield* Ref.get(state.checkpoint);
        const now = yield* DateTime.now.pipe(Effect.map(DateTime.formatIso));
        const updated = update(current);
        const next: BeatGameCheckpoint = {
          ...updated,
          revision: current.revision + 1,
          updatedAt: now,
          planner: {
            ...updated.planner,
            updatedAt: updated.planner.updatedAt || now,
          },
        };
        const stored = yield* state.store.save(next, current.revision);
        yield* Ref.set(state.checkpoint, stored);
        yield* publishSnapshot(state);
        yield* emit(state, {
          type: "checkpoint-saved",
          revision: stored.revision,
        });
        return stored;
      }),
    ),
  );
}

function currentSnapshot(state: RunState): Effect.Effect<BeatGameSnapshot, BeatGameError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const observation = yield* Ref.get(state.observation);
    const team = yield* state.coordinator.snapshot(checkpoint.teamId);
    return { checkpoint, observation, team };
  });
}

function mergeSharedDiscoveries(
  state: RunState,
  checkpoint: BeatGameCheckpoint,
): Effect.Effect<BeatGameCheckpoint, BeatGameError> {
  return Effect.gen(function* () {
    const team = yield* state.coordinator.snapshot(checkpoint.teamId);
    const knownPortals = new Set(checkpoint.memory.portals.map(({ key }) => key));
    const sharedPortals = team.discoveries
      .filter(({ kind, key }) => kind === "portal" && !knownPortals.has(key))
      // oxlint-disable-next-line oxc/no-map-spread -- the spread only adds expiresAt when it is defined
      .map((discovery) => ({
        key: discovery.key,
        value: {
          blockId: "minecraft:nether_portal",
          position: {
            x: Math.floor(discovery.position.x),
            y: Math.floor(discovery.position.y),
            z: Math.floor(discovery.position.z),
            dimension: discovery.position.dimension,
          },
          properties: {},
          diggable: false,
          replaceable: false,
          interactive: false,
          observedAt: discovery.observedAt,
        },
        observedAt: discovery.observedAt,
        ...(discovery.expiresAt === undefined ? {} : { expiresAt: discovery.expiresAt }),
        confidence: discovery.confidence,
      }));
    const knownEyeSamples = new Set(
      checkpoint.memory.eyeSamples.map(
        (sample) => `${sample.observedAt}:${positionKey(sample.origin)}`,
      ),
    );
    const sharedEyeSamples = team.discoveries.flatMap((discovery) => {
      if (discovery.kind !== "eye-sample") {
        return [];
      }
      const directionX = discovery.metadata?.["directionX"];
      const directionZ = discovery.metadata?.["directionZ"];
      const key = `${discovery.observedAt}:${positionKey(discovery.position)}`;
      if (
        knownEyeSamples.has(key) ||
        typeof directionX !== "number" ||
        typeof directionZ !== "number"
      ) {
        return [];
      }
      return [
        {
          origin: discovery.position,
          direction: { x: directionX, z: directionZ },
          observedAt: discovery.observedAt,
          confidence: discovery.confidence,
        },
      ];
    });
    const stronghold = team.discoveries
      .filter(({ kind }) => kind === "stronghold")
      .toSorted(
        (left, right) =>
          right.confidence - left.confidence ||
          Date.parse(right.observedAt) - Date.parse(left.observedAt),
      )[0]?.position;
    const changedStronghold =
      stronghold !== undefined &&
      (checkpoint.memory.strongholdEstimate === undefined ||
        !samePosition(checkpoint.memory.strongholdEstimate, stronghold));
    if (sharedPortals.length === 0 && sharedEyeSamples.length === 0 && !changedStronghold) {
      return checkpoint;
    }
    return yield* persist(state, (current) => ({
      ...current,
      memory: {
        ...current.memory,
        portals: [...current.memory.portals, ...sharedPortals].slice(-64),
        eyeSamples: [...current.memory.eyeSamples, ...sharedEyeSamples].slice(-32),
        ...(stronghold === undefined ? {} : { strongholdEstimate: stronghold }),
      },
    }));
  });
}

function publishSnapshot(state: RunState): Effect.Effect<void, BeatGameError> {
  return currentSnapshot(state).pipe(
    Effect.flatMap((snapshot) => state.snapshots.publish(snapshot)),
  );
}

function emit(state: RunState, input: EventInput): Effect.Effect<void> {
  return state.eventMutex.withPermits(1)(
    Effect.gen(function* () {
      const checkpoint = yield* Ref.get(state.checkpoint);
      const sequence = yield* Ref.updateAndGet(state.sequence, (current) => current + 1n);
      const event = {
        ...input,
        sequence,
        timestamp: yield* DateTime.now.pipe(Effect.map(DateTime.formatIso)),
        runId: checkpoint.runId,
        instanceId: checkpoint.instanceId,
        botId: checkpoint.botId,
        phase: checkpoint.planner.phase,
      } as BeatGameEvent;
      yield* state.events.publish(event);
    }),
  );
}

function awaitRunnable(state: RunState): Effect.Effect<void, BeatGameError> {
  return Effect.gen(function* () {
    if (yield* Deferred.isDone(state.stopped)) {
      const checkpoint = yield* Ref.get(state.checkpoint);
      return yield* cancelled(checkpoint, "stopped");
    }
    while (yield* Ref.get(state.paused)) {
      if (yield* Deferred.isDone(state.stopped)) {
        const checkpoint = yield* Ref.get(state.checkpoint);
        return yield* cancelled(checkpoint, "stopped");
      }
      yield* Effect.sleep(50);
    }
    return undefined;
  });
}

function cancellable<A>(
  state: RunState,
  effect: Effect.Effect<A, BeatGameError>,
): Effect.Effect<A, BeatGameError> {
  const stopped = Deferred.await(state.stopped).pipe(
    Effect.flatMap(() =>
      Ref.get(state.checkpoint).pipe(
        Effect.flatMap((checkpoint) => Effect.fail(cancelled(checkpoint, "stopped"))),
      ),
    ),
  );
  return Effect.raceFirst(effect, stopped);
}

function claimAction(
  state: RunState,
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
): Effect.Effect<BeatGameClaim | undefined, BeatGameError> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const baseKey =
      decision.type === "satisfy-requirement"
        ? `requirement:${decision.requirement.key}`
        : `${checkpoint.planner.phase}:${decision.action}`;
    const capacity =
      decision.type === "activate-end-portal" ? state.strategy.maximumConcurrentEndEntries : 1;
    let claim: BeatGameClaim | undefined;
    for (let slot = 0; slot < capacity; slot += 1) {
      claim = yield* state.coordinator.claim({
        teamId: checkpoint.teamId,
        runId: checkpoint.runId,
        botId: checkpoint.botId,
        key: capacity === 1 ? baseKey : `${baseKey}:${slot}`,
        purpose: decision.action,
        ttlMs: Math.max(state.strategy.claimTtlMs, state.strategy.actionTimeoutMs + 5000),
      });
      if (claim !== undefined) {
        break;
      }
    }
    if (claim === undefined) {
      return undefined;
    }
    yield* emit(state, {
      type: "team-claim-changed",
      claim,
      released: false,
    });
    if (decision.type === "satisfy-requirement") {
      yield* emit(state, {
        type: "requirement-claimed",
        requirement: decision.requirement,
        claim,
      });
    }
    return claim;
  });
}

function releaseActionClaim(state: RunState, claim: BeatGameClaim): Effect.Effect<void> {
  return Effect.gen(function* () {
    const checkpoint = yield* Ref.get(state.checkpoint);
    const released = yield* state.coordinator
      .release(checkpoint.teamId, claim.key, checkpoint.botId)
      .pipe(Effect.orElseSucceed(() => false));
    if (released) {
      yield* emit(state, {
        type: "team-claim-changed",
        claim,
        released: true,
      });
    }
  });
}

function markFailed(state: RunState, error: BeatGameError): Effect.Effect<void> {
  return persist(state, (checkpoint) => ({
    ...checkpoint,
    planner: {
      ...checkpoint.planner,
      status: BeatGameRunStatus.FAILED,
      updatedAt: new Date().toISOString(),
    },
  })).pipe(
    Effect.andThen(
      emit(state, {
        type: "diagnostic",
        message: error.message,
        data: { error: error._tag },
      }),
    ),
    Effect.ignore,
  );
}

function createInitialCheckpoint(
  runId: string,
  teamId: string,
  driver: BeatGameDriver,
  role: BeatGameTeamRole,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
): BeatGameCheckpoint {
  const now = new Date().toISOString();
  return {
    schemaVersion: BEAT_GAME_CHECKPOINT_SCHEMA_VERSION,
    runId,
    teamId,
    instanceId: driver.instanceId,
    botId: driver.botId,
    role,
    revision: 1,
    connectionEpoch: observation.player.connectionEpoch,
    planner: {
      phase: BeatGamePhase.PREPARE_OVERWORLD,
      status: BeatGameRunStatus.CREATED,
      objective: objectiveForPhase(BeatGamePhase.PREPARE_OVERWORLD),
      requirements: plannerWithObservation(
        {
          phase: BeatGamePhase.PREPARE_OVERWORLD,
          status: BeatGameRunStatus.CREATED,
          objective: objectiveForPhase(BeatGamePhase.PREPARE_OVERWORLD),
          requirements: [],
          retryCount: 0,
          completedActions: [],
          startedAt: now,
          updatedAt: now,
        },
        observation,
        strategy,
      ).requirements,
      retryCount: 0,
      completedActions: [],
      startedAt: now,
      updatedAt: now,
    },
    memory: emptyBeatGameWorldMemory(),
    createdAt: now,
    updatedAt: now,
  };
}

function validateRestoredCheckpoint(
  checkpoint: BeatGameCheckpoint | undefined,
  driver: BeatGameDriver,
  teamId: string,
): void {
  if (checkpoint === undefined) {
    return;
  }
  assertValidCheckpoint(checkpoint);
  if (checkpoint.botId !== driver.botId || checkpoint.teamId !== teamId) {
    throw new TypeError("The restored checkpoint belongs to another bot or team");
  }
}

function adoptRestoredCheckpoint(
  checkpoint: BeatGameCheckpoint,
  driver: BeatGameDriver,
  role: BeatGameTeamRole,
  observation: BeatGameObservation,
): BeatGameCheckpoint {
  const now = new Date().toISOString();
  return {
    ...checkpoint,
    instanceId: driver.instanceId,
    botId: driver.botId,
    role,
    connectionEpoch: observation.player.connectionEpoch,
    revision: checkpoint.revision + 1,
    updatedAt: now,
    planner: {
      ...checkpoint.planner,
      updatedAt: now,
    },
  };
}

function mergeStrategy(override: BeatGameStrategyOptions | undefined): BeatGameStrategy {
  const strategy: BeatGameStrategy = {
    ...defaultBeatGameStrategy,
    ...override,
    path: {
      ...defaultBeatGameStrategy.path,
      ...override?.path,
    },
  };
  validateStrategy(strategy);
  return strategy;
}

function validateStrategy(strategy: BeatGameStrategy): void {
  for (const [name, value] of Object.entries({
    targetFoodCount: strategy.targetFoodCount,
    targetLogCount: strategy.targetLogCount,
    targetCobblestoneCount: strategy.targetCobblestoneCount,
    targetIronCount: strategy.targetIronCount,
    targetGoldCount: strategy.targetGoldCount,
    targetBlazeRodCount: strategy.targetBlazeRodCount,
    targetEnderPearlCount: strategy.targetEnderPearlCount,
    targetEyeCount: strategy.targetEyeCount,
    targetObsidianCount: strategy.targetObsidianCount,
    maximumActionRetries: strategy.maximumActionRetries,
  })) {
    requireNonNegativeInteger(value, name);
  }
  for (const [name, value] of Object.entries({
    blockSearchRadius: strategy.blockSearchRadius,
    entitySearchRadius: strategy.entitySearchRadius,
    explorationRadius: strategy.explorationRadius,
    actionTimeoutMs: strategy.actionTimeoutMs,
    observationPollMs: strategy.observationPollMs,
    claimTtlMs: strategy.claimTtlMs,
    maximumConcurrentEndEntries: strategy.maximumConcurrentEndEntries,
    maxFallDistance: strategy.path.maxFallDistance,
    maxSearchTimeMs: strategy.path.maxSearchTimeMs,
  })) {
    requirePositiveInteger(value, name);
  }
  if (
    !Number.isFinite(strategy.minimumHealth) ||
    strategy.minimumHealth <= 0 ||
    strategy.minimumHealth > 20
  ) {
    throw new RangeError("minimumHealth must be greater than 0 and at most 20");
  }
  if (
    !Number.isFinite(strategy.eatBelowFood) ||
    strategy.eatBelowFood < 0 ||
    strategy.eatBelowFood > 20
  ) {
    throw new RangeError("eatBelowFood must be between 0 and 20");
  }
  if (
    strategy.portalStrategy !== PortalStrategy.CAST &&
    strategy.targetObsidianCount < NETHER_PORTAL_FRAME_OBSIDIAN_COUNT
  ) {
    throw new RangeError(
      `targetObsidianCount must be at least ${
        NETHER_PORTAL_FRAME_OBSIDIAN_COUNT
      } unless portalStrategy is CAST`,
    );
  }
}

function requireNonNegativeInteger(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${name} must be a non-negative safe integer`);
  }
}

function requirePositiveInteger(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError(`${name} must be a positive safe integer`);
  }
}

function roleForIndex(index: number): BeatGameTeamRole {
  const roles: readonly BeatGameTeamRole[] = [
    BeatGameTeamRole.LEAD,
    BeatGameTeamRole.PORTAL_ENGINEER,
    BeatGameTeamRole.NETHER_RUNNER,
    BeatGameTeamRole.STRONGHOLD_SCOUT,
    BeatGameTeamRole.END_SUPPORT,
  ];
  return roles[index % roles.length] ?? BeatGameTeamRole.END_SUPPORT;
}

function observationError(
  runId: string,
  driver: BeatGameDriver,
  phase: BeatGamePhase,
  cause: BeatGameDriverError,
): BeatGameObservationError {
  return new BeatGameObservationError({
    runId,
    instanceId: driver.instanceId,
    botId: driver.botId,
    phase,
    retryable: cause.retryable,
    message: cause.message,
    cause,
  });
}

function actionError(
  checkpoint: BeatGameCheckpoint | undefined,
  message: string,
  isRetryable: boolean,
  cause?: unknown,
): BeatGameActionError {
  return new BeatGameActionError({
    runId: checkpoint?.runId ?? "",
    instanceId: checkpoint?.instanceId ?? "",
    botId: checkpoint?.botId ?? "",
    phase: checkpoint?.planner.phase ?? BeatGamePhase.PREPARE_OVERWORLD,
    ...(checkpoint?.planner.currentAction === undefined
      ? {}
      : { action: checkpoint.planner.currentAction }),
    retryable: isRetryable,
    message,
    ...(cause === undefined ? {} : { cause }),
  });
}

function pathfindingError(
  checkpoint: BeatGameCheckpoint,
  cause: BeatGameDriverError,
): BeatGamePathfindingError {
  return new BeatGamePathfindingError({
    runId: checkpoint.runId,
    instanceId: checkpoint.instanceId,
    botId: checkpoint.botId,
    phase: checkpoint.planner.phase,
    ...(checkpoint.planner.currentAction === undefined
      ? {}
      : { action: checkpoint.planner.currentAction }),
    retryable: cause.retryable,
    message: cause.message,
    cause,
  });
}

function cancelled(checkpoint: BeatGameCheckpoint, reason: string): BeatGameCancelled {
  return new BeatGameCancelled({
    runId: checkpoint.runId,
    instanceId: checkpoint.instanceId,
    botId: checkpoint.botId,
    phase: checkpoint.planner.phase,
    ...(checkpoint.planner.currentAction === undefined
      ? {}
      : { action: checkpoint.planner.currentAction }),
    retryable: false,
    message: `Beat-game run ${checkpoint.runId} was ${reason}`,
    reason,
  });
}

function retryable(error: BeatGameError): boolean {
  return "retryable" in error && error.retryable;
}

function isBeatGameError(value: unknown): value is BeatGameError {
  if (!(value instanceof Error) || !("_tag" in value)) {
    return false;
  }
  return [
    "BeatGameActionError",
    "BeatGameCancelled",
    "BeatGameCheckpointError",
    "BeatGameCoordinationError",
    "BeatGameDriverError",
    "BeatGameObservationError",
    "BeatGamePathfindingError",
    "BeatGameProtocolError",
    "BeatGameRequirementError",
  ].includes(String(value._tag));
}

function actionObservedComplete(
  decision: Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
  includeCollectionBuffer = false,
): boolean {
  switch (decision.type) {
    case "recover-death": {
      return !observation.player.dead;
    }
    case "eat": {
      return observation.player.food > strategy.eatBelowFood;
    }
    case "retreat": {
      return observation.player.health >= strategy.minimumHealth;
    }
    case "satisfy-requirement": {
      return (
        usableRequirementCount(observation.inventory, decision.requirement) >=
        decision.requirement.targetCount +
          (includeCollectionBuffer ? requirementCollectionBuffer(decision.requirement.key) : 0)
      );
    }
    case "build-and-enter-nether": {
      return isNether(observation.player.position.dimension);
    }
    case "return-through-portal": {
      return !isNether(observation.player.position.dimension);
    }
    case "activate-end-portal": {
      return isEnd(observation.player.position.dimension);
    }
    case "exit-end": {
      return !isEnd(observation.player.position.dimension);
    }
    case "prepare-equipment":
    case "throw-eye":
    case "search-stronghold":
    case "fight-ender-dragon": {
      return false;
    }
    default: {
      return decision satisfies never;
    }
  }
}

function requirementCollectionBuffer(requirementKey: string): number {
  switch (requirementKey) {
    case "blaze-rods":
    case "cobblestone":
    case "ender-pearls":
    case "food":
    case "gold":
    case "iron":
    case "logs": {
      return RESOURCE_COLLECTION_BUFFERS[requirementKey];
    }
    case "food-supply": {
      return RESOURCE_COLLECTION_BUFFERS.food;
    }
    default: {
      return 0;
    }
  }
}

function backoffDuration(attempt: number): number {
  return Math.min(5000, 250 * 2 ** Math.max(0, attempt - 1));
}

function resolvePortalBuildFrame(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
): Effect.Effect<PortalFrame, BeatGameDriverError> {
  const player = observation.player.position;
  const baseX = Math.floor(player.x) - 1;
  const baseZ = Math.floor(player.z) + 2;
  const highestY = Math.floor(player.y);
  const lowestY = highestY - 12;
  const offsets = portalBuildSearchOffsets(4);
  const findFloor = (
    offsetIndex: number,
    y: number,
  ): Effect.Effect<PortalFrame, BeatGameDriverError> => {
    const offset = offsets[offsetIndex];
    if (offset === undefined) {
      return Effect.fail(
        new BeatGameDriverError({
          operation: "resolvePortalBuildFrame",
          retryable: true,
          message: `Could not find a clear portal workspace near ${baseX}, ${baseZ}`,
        }),
      );
    }
    if (y < lowestY) {
      return findFloor(offsetIndex + 1, highestY);
    }
    const x = baseX + offset.x;
    const z = baseZ + offset.z;
    const candidate: BeatGameBlockPosition = {
      x: x + 1,
      y,
      z,
      dimension: player.dimension,
    };
    return driver
      .queryBlocks({
        center: {
          ...candidate,
          x: candidate.x + 0.5,
          y: candidate.y + 0.5,
          z: candidate.z + 0.5,
        },
        radius: 0.25,
        selector: { replaceable: false },
        maximumResults: 1,
      })
      .pipe(
        Effect.flatMap((blocks) => {
          if (!blocks.some(({ position }) => samePosition(position, candidate))) {
            return findFloor(offsetIndex, y - 1);
          }
          const frame = createNetherPortalFrame({
            x,
            y: y + 1,
            z,
            dimension: player.dimension,
          });
          return portalBuildWorkspaceIsClearable(driver, frame).pipe(
            Effect.flatMap((clearable) =>
              clearable ? Effect.succeed(frame) : findFloor(offsetIndex + 1, highestY),
            ),
          );
        }),
      );
  };

  return resolveExistingPortalBuildFrame(driver, observation).pipe(
    Effect.filterOrElse(
      (frame) => frame !== undefined,
      () => findFloor(0, highestY),
    ),
  );
}

function resolveExistingPortalBuildFrame(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
): Effect.Effect<PortalFrame | undefined, BeatGameDriverError> {
  const player = observation.player.position;
  return driver
    .queryBlocks({
      center: player,
      radius: 16,
      selector: { blockIds: ["minecraft:obsidian"] },
      maximumResults: 128,
    })
    .pipe(
      Effect.flatMap((blocks) => {
        const candidates = inferNetherPortalFrames(
          blocks.map(({ position }) => position),
          player,
        );
        const findClearable = (
          index: number,
        ): Effect.Effect<PortalFrame | undefined, BeatGameDriverError> => {
          const candidate = candidates[index];
          if (candidate === undefined) {
            return Effect.undefined;
          }
          return portalBuildWorkspaceIsClearable(driver, candidate.frame).pipe(
            Effect.flatMap((clearable) =>
              clearable ? Effect.succeed(candidate.frame) : findClearable(index + 1),
            ),
          );
        };
        return findClearable(0);
      }),
    );
}

function portalBuildSearchOffsets(radius: number): readonly Readonly<{ x: number; z: number }>[] {
  const offsets: Readonly<{ x: number; z: number }>[] = [];
  for (let x = -radius; x <= radius; x += 1) {
    for (let z = -radius; z <= radius; z += 1) {
      offsets.push({ x, z });
    }
  }
  return offsets.toSorted(
    (left, right) =>
      left.x * left.x + left.z * left.z - (right.x * right.x + right.z * right.z) ||
      Math.abs(left.x) + Math.abs(left.z) - (Math.abs(right.x) + Math.abs(right.z)) ||
      left.x - right.x ||
      left.z - right.z,
  );
}

function portalBuildWorkspaceIsClearable(
  driver: BeatGameDriver,
  frame: PortalFrame,
): Effect.Effect<boolean, BeatGameDriverError> {
  const { origin } = frame;
  const frameKeys = new Set(frame.blocks.map(positionKey));
  const castingStand = {
    x: origin.x + (frame.axis === "x" ? 1 : -2),
    y: origin.y + 1,
    z: origin.z + (frame.axis === "z" ? 1 : -2),
    dimension: origin.dimension,
  };
  const positions = [
    ...new Map(
      [
        ...frame.blocks,
        ...frame.interior,
        ...frame.blocks.map((position) => ({
          ...position,
          x: position.x - (frame.axis === "z" ? 1 : 0),
          z: position.z - (frame.axis === "x" ? 1 : 0),
        })),
        castingStand,
        { ...castingStand, y: castingStand.y + 1 },
      ].map((position) => [positionKey(position), position]),
    ).values(),
  ];
  return Effect.forEach(positions, (position) => queryExactBlockAt(driver, position), {
    concurrency: 16,
  }).pipe(
    Effect.map((blocks) =>
      blocks.every((block, index) => {
        if (block === undefined || isPlayerFluidBlock(block.blockId)) {
          return false;
        }
        if (block.replaceable) {
          return true;
        }
        const position = positions[index];
        if (block.blockId === "minecraft:obsidian") {
          return position !== undefined && frameKeys.has(positionKey(position));
        }
        return block.diggable;
      }),
    ),
  );
}

function positionKey(
  position: Readonly<{
    dimension: string;
    x: number;
    y: number;
    z: number;
  }>,
): string {
  return `${position.dimension}:${position.x}:${position.y}:${position.z}`;
}

function samePosition(left: BeatGamePosition, right: BeatGamePosition): boolean {
  return (
    left.dimension === right.dimension &&
    left.x === right.x &&
    left.y === right.y &&
    left.z === right.z
  );
}

function stableActionResult(
  action: string,
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
  evidence: "TASK_RESULT" | "OBSERVED_STATE" | "OBSERVATION_AFTER_UNCERTAIN_RESULT",
) {
  return {
    action,
    phase: checkpoint.planner.phase,
    completedAt: new Date().toISOString(),
    evidence,
    connectionEpoch: observation.player.connectionEpoch,
    playerRevision: observation.player.revision.toString(),
    inventoryRevision: observation.inventory.revision.toString(),
  } as const;
}

function withoutCurrentAction(
  planner: BeatGamePlannerState & {
    readonly currentAction?: string | undefined;
    readonly currentActionId?: string | undefined;
  },
): BeatGamePlannerState {
  const { currentAction: _currentAction, currentActionId: _currentActionId, ...rest } = planner;
  return rest;
}

function withTaskIdempotency(
  driver: BeatGameDriver,
  actionId: string,
  deadline: Date,
  observationFingerprint: string,
): BeatGameDriver {
  let taskInvocation = 0;
  return {
    instanceId: driver.instanceId,
    botId: driver.botId,
    observe: driver.observe,
    events: driver.events,
    ...(driver.environment === undefined ? {} : { environment: driver.environment }),
    queryBlocks: driver.queryBlocks,
    queryEntities: driver.queryEntities,
    raycast: driver.raycast,
    sampleSurface: driver.sampleSurface,
    recipesFor: driver.recipesFor,
    canCraft: driver.canCraft,
    waitForChunks: driver.waitForChunks,
    pathfind: driver.pathfind,
    pathfindXZ: driver.pathfindXZ,
    runTask: (task, policy, execution = {}) =>
      driver.runTask(task, policy, {
        idempotencyKey:
          execution.idempotencyKey ??
          `beat-game:${actionId}:${observationFingerprint}:${++taskInvocation}:${stableFingerprint(task)}`,
        deadline: execution.deadline ?? deadline,
      }),
    act: driver.act,
    withControl: driver.withControl,
  };
}

function stableFingerprint(value: unknown): string {
  const source = JSON.stringify(value);
  let hash = 0xcb_f2_9c_e4_84_22_23_25n;
  for (let index = 0; index < source.length; index += 1) {
    // oxlint-disable-next-line unicorn/prefer-code-point -- the fingerprint hashes UTF-16 code units; code points would change existing idempotency keys
    hash ^= BigInt(source.charCodeAt(index));
    hash = BigInt.asUintN(64, hash * 0x1_00_00_00_01_b3n);
  }
  return hash.toString(16).padStart(16, "0");
}
