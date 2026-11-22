import {
  BeatGamePhase,
  BeatGameRiskProfile,
  PortalStrategy,
  type BeatGameInventory,
  type BeatGameItemRequirement,
  type BeatGameStrategy,
} from "./model.js";

export interface BeatGameRequirementDefinition {
  readonly key: string;
  readonly itemIds?: readonly string[];
  readonly tags?: readonly string[];
  readonly target: (strategy: BeatGameStrategy) => number;
  readonly priority: number;
}

export interface BeatGameRequirementContext {
  readonly reusablePortalAvailable?: boolean;
  /// True once the bot searched long enough for sheep without finding enough wool for a bed.
  readonly bedUnavailable?: boolean;
}

export const BED_COLORS = [
  "white",
  "orange",
  "magenta",
  "light_blue",
  "yellow",
  "lime",
  "pink",
  "gray",
  "light_gray",
  "cyan",
  "purple",
  "blue",
  "brown",
  "green",
  "red",
  "black",
] as const;

export const BED_ITEM_IDS = BED_COLORS.map((color) => `minecraft:${color}_bed`);

export const WOOL_ITEM_IDS = BED_COLORS.map((color) => `minecraft:${color}_wool`);

/// Exploration legs spent looking for sheep before the run gives up on a bed.
export const BED_SHEEP_SEARCH_LEGS = 6;

/// Exploration purpose for the sheep search, which keys its saved progress.
export const BED_SHEEP_SEARCH_PURPOSE = "find-sheep";

/// Moving this far from where a search left off reaches new terrain, so an
/// exhausted search there no longer says anything about the new area.
export const EXPLORATION_REGION_RESET_DISTANCE = 128;

export const COOKED_FOOD_ITEM_IDS = [
  "minecraft:cooked_beef",
  "minecraft:cooked_porkchop",
  "minecraft:cooked_mutton",
  "minecraft:cooked_chicken",
  "minecraft:cooked_rabbit",
  "minecraft:cooked_cod",
  "minecraft:cooked_salmon",
  "minecraft:bread",
  "minecraft:baked_potato",
] as const;

export const RAW_FOOD_TO_COOKED = {
  "minecraft:beef": "minecraft:cooked_beef",
  "minecraft:porkchop": "minecraft:cooked_porkchop",
  "minecraft:mutton": "minecraft:cooked_mutton",
  "minecraft:chicken": "minecraft:cooked_chicken",
  "minecraft:rabbit": "minecraft:cooked_rabbit",
  "minecraft:cod": "minecraft:cooked_cod",
  "minecraft:salmon": "minecraft:cooked_salmon",
  "minecraft:potato": "minecraft:baked_potato",
} as const;

export const EDIBLE_FOOD_ITEM_IDS = [
  ...COOKED_FOOD_ITEM_IDS,
  ...Object.keys(RAW_FOOD_TO_COOKED),
  "minecraft:carrot",
  "minecraft:apple",
  "minecraft:beetroot",
  "minecraft:beetroot_soup",
  "minecraft:cookie",
  "minecraft:dried_kelp",
  "minecraft:glow_berries",
  "minecraft:golden_apple",
  "minecraft:golden_carrot",
  "minecraft:honey_bottle",
  "minecraft:melon_slice",
  "minecraft:mushroom_stew",
  "minecraft:pumpkin_pie",
  "minecraft:rabbit_stew",
  "minecraft:sweet_berries",
  "minecraft:tropical_fish",
] as const;

export const EMERGENCY_FOOD_ITEM_IDS = ["minecraft:rotten_flesh"] as const;

export const CRITICAL_HUNGER_FOOD_LEVEL = 6;
export const URGENT_HUNGER_FOOD_LEVEL = 10;
const PORTAL_LOG_RESERVE = 4;
const PORTAL_PICKAXE_PRIORITY = 105;
const FAST_BED_PRIORITY = 114;
// Below iron (103) and the shield (102).
const FAST_COOKED_FOOD_PRIORITY = 101;
// Above the small food supply (112).
const FAST_READY_SHIELD_PRIORITY = 113;
// Right after cobblestone (110): a live fast run kept dying in fights
// before it reached armor at the end of preparation.
const FAST_CHESTPLATE_PRIORITY = 109;
const FAST_HELMET_PRIORITY = 108;
const DRAGON_BED_COUNT = 4;

export const CHESTPLATE_ITEM_IDS = [
  "minecraft:iron_chestplate",
  "minecraft:diamond_chestplate",
  "minecraft:netherite_chestplate",
];
export const HELMET_ITEM_IDS = [
  "minecraft:iron_helmet",
  "minecraft:diamond_helmet",
  "minecraft:netherite_helmet",
];

export const BOOTS_ITEM_IDS = ["minecraft:golden_boots"];

export const LOG_ITEM_IDS = [
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
] as const;

export const PLANK_ITEM_IDS = [
  "minecraft:oak_planks",
  "minecraft:spruce_planks",
  "minecraft:birch_planks",
  "minecraft:jungle_planks",
  "minecraft:acacia_planks",
  "minecraft:dark_oak_planks",
  "minecraft:mangrove_planks",
  "minecraft:cherry_planks",
  "minecraft:pale_oak_planks",
  "minecraft:crimson_planks",
  "minecraft:warped_planks",
] as const;

const REQUIREMENTS: Readonly<Record<BeatGamePhase, readonly BeatGameRequirementDefinition[]>> = {
  [BeatGamePhase.PREPARE_OVERWORLD]: [
    itemRequirement("logs", LOG_ITEM_IDS, ({ targetLogCount }) => targetLogCount, 120),
    itemRequirement(
      "basic-melee-weapon",
      [
        "minecraft:netherite_sword",
        "minecraft:diamond_sword",
        "minecraft:iron_sword",
        "minecraft:stone_sword",
        "minecraft:wooden_sword",
      ],
      () => 1,
      115,
    ),
    itemRequirement(
      "food-supply",
      EDIBLE_FOOD_ITEM_IDS,
      ({ targetFoodCount }) => Math.min(targetFoodCount, 4),
      112,
    ),
    itemRequirement(
      "cobblestone",
      ["minecraft:cobblestone"],
      ({ targetCobblestoneCount }) => targetCobblestoneCount,
      110,
    ),
    // A bed skips each night in seconds. Without one, the bot hides for 10 minutes.
    itemRequirement("bed", BED_ITEM_IDS, () => 1, 106),
    itemRequirement(
      "melee-weapon",
      [
        "minecraft:netherite_sword",
        "minecraft:diamond_sword",
        "minecraft:iron_sword",
        "minecraft:stone_sword",
      ],
      () => 1,
      105,
    ),
    itemRequirement(
      "food",
      COOKED_FOOD_ITEM_IDS,
      ({ targetFoodCount }) => Math.min(targetFoodCount, 8),
      104,
    ),
    itemRequirement(
      "iron",
      ["minecraft:iron_ingot"],
      ({ targetIronCount }) => targetIronCount,
      103,
    ),
    itemRequirement("shield", ["minecraft:shield"], () => 1, 102),
    // The bot lost most fights against zombies and skeletons with no armor
    // at all. A chestplate and helmet take a third off every hit for 13 iron.
    // They come last, once everything else for the Nether is ready.
    itemRequirement("chestplate", CHESTPLATE_ITEM_IDS, () => 1, 55),
    itemRequirement("helmet", HELMET_ITEM_IDS, () => 1, 54),
    itemRequirement("pickaxe", ["minecraft:iron_pickaxe", "minecraft:stone_pickaxe"], () => 1, 68),
    itemRequirement("water-bucket", ["minecraft:water_bucket"], () => 1, 65),
    itemRequirement(
      "ignition",
      ["minecraft:flint_and_steel", "minecraft:fire_charge"],
      () => 1,
      60,
    ),
  ],
  [BeatGamePhase.ENTER_NETHER]: [
    itemRequirement(
      "obsidian",
      ["minecraft:obsidian"],
      ({ targetObsidianCount }) => targetObsidianCount,
      100,
    ),
    itemRequirement(
      "ignition",
      ["minecraft:flint_and_steel", "minecraft:fire_charge"],
      () => 1,
      90,
    ),
  ],
  [BeatGamePhase.COLLECT_NETHER_RESOURCES]: [
    // Worn gold keeps piglins from attacking. Boots take the free armor slot.
    itemRequirement("gold-boots", BOOTS_ITEM_IDS, () => 1, 110),
    itemRequirement(
      "blaze-rods",
      ["minecraft:blaze_rod"],
      ({ targetBlazeRodCount }) => targetBlazeRodCount,
      100,
    ),
    itemRequirement(
      "ender-pearls",
      ["minecraft:ender_pearl"],
      ({ targetEnderPearlCount }) => targetEnderPearlCount,
      90,
    ),
  ],
  [BeatGamePhase.RETURN_TO_OVERWORLD]: [],
  [BeatGamePhase.LOCATE_STRONGHOLD]: [
    itemRequirement(
      "eyes-of-ender",
      ["minecraft:ender_eye"],
      ({ targetEyeCount }) => targetEyeCount,
      100,
    ),
  ],
  [BeatGamePhase.ACTIVATE_END_PORTAL]: [
    itemRequirement(
      "eyes-of-ender",
      ["minecraft:ender_eye"],
      ({ targetEyeCount }) => targetEyeCount,
      100,
    ),
  ],
  [BeatGamePhase.FIGHT_ENDER_DRAGON]: [
    itemRequirement("food", COOKED_FOOD_ITEM_IDS, ({ targetFoodCount }) => targetFoodCount, 100),
    itemRequirement("ranged-weapon", ["minecraft:bow", "minecraft:crossbow"], () => 1, 80),
    itemRequirement("arrows", ["minecraft:arrow"], () => 32, 75),
  ],
  [BeatGamePhase.EXIT_END]: [],
  [BeatGamePhase.COMPLETE]: [],
};

export function requirementsForPhase(
  phase: BeatGamePhase,
  inventory: BeatGameInventory,
  strategy: BeatGameStrategy,
  context: BeatGameRequirementContext = {},
): readonly BeatGameItemRequirement[] {
  const definitions =
    phase === BeatGamePhase.ENTER_NETHER
      ? portalRequirements(inventory, strategy, context.reusablePortalAvailable === true)
      : phase === BeatGamePhase.PREPARE_OVERWORLD
        ? prepareOverworldRequirements(inventory, context)
        : phase === BeatGamePhase.LOCATE_STRONGHOLD &&
            strategy.riskProfile === BeatGameRiskProfile.FAST &&
            context.bedUnavailable !== true
          ? // Beds set off under the perched dragon kill it in about four
            // explosions, far faster than arrows. They must be carried in.
            [...REQUIREMENTS[phase], DRAGON_BEDS_REQUIREMENT]
          : REQUIREMENTS[phase];
  const fastPreparation =
    phase === BeatGamePhase.PREPARE_OVERWORLD && strategy.riskProfile === BeatGameRiskProfile.FAST;
  return (
    definitions
      // oxlint-disable-next-line oxc/no-map-spread -- copies keep the shared requirement definitions unchanged
      .map((definition) =>
        // A fast run takes its bed right after wood and a weapon: every night
        // without one costs ten minutes in a shelter.
        fastPreparation && definition.key === "bed"
          ? { ...definition, priority: FAST_BED_PRIORITY }
          : // A live fast run spent whole days stocking cooked food at full
            // hunger before it had any iron. The small food supply still
            // comes first; the full stock waits for the iron and the shield.
            fastPreparation && definition.key === "food"
            ? { ...definition, priority: FAST_COOKED_FOOD_PRIORITY }
            : // Once the iron is in hand the shield is a few seconds of
              // crafting, and every night without it costs a shelter. A live
              // run held four iron for a whole day while it searched for food.
              fastPreparation &&
                definition.key === "shield" &&
                (inventory.counts["minecraft:iron_ingot"] ?? 0) > 0
              ? { ...definition, priority: FAST_READY_SHIELD_PRIORITY }
              : fastPreparation && definition.key === "chestplate"
                ? { ...definition, priority: FAST_CHESTPLATE_PRIORITY }
                : fastPreparation && definition.key === "helmet"
                  ? { ...definition, priority: FAST_HELMET_PRIORITY }
                  : definition,
      )
      .map((definition) => materializeRequirement(definition, inventory, strategy))
      .toSorted(
        (left, right) => right.priority - left.priority || left.key.localeCompare(right.key),
      )
  );
}

function prepareOverworldRequirements(
  inventory: BeatGameInventory,
  context: BeatGameRequirementContext,
): readonly BeatGameRequirementDefinition[] {
  const definitions = REQUIREMENTS[BeatGamePhase.PREPARE_OVERWORLD].filter(
    ({ key }) => key !== "bed" || context.bedUnavailable !== true,
  );
  const logTarget = overworldLogTarget(inventory);
  const investedIron = preparedEquipmentIronInvestment(inventory);
  // oxlint-disable-next-line oxc/no-map-spread -- copies keep the shared requirement definitions unchanged
  return definitions.map((definition) => {
    if (definition.key === "logs") {
      return {
        ...definition,
        target: ({ targetLogCount }) => Math.min(targetLogCount, logTarget),
      };
    }
    if (definition.key === "iron") {
      return {
        ...definition,
        target: ({ targetIronCount }) => Math.max(0, targetIronCount - investedIron),
      };
    }
    return definition;
  });
}

function preparedEquipmentIronInvestment(inventory: BeatGameInventory): number {
  const hasShield = (inventory.counts["minecraft:shield"] ?? 0) > 0;
  const hasDurablePickaxe = [
    "minecraft:netherite_pickaxe",
    "minecraft:diamond_pickaxe",
    "minecraft:iron_pickaxe",
  ].some((itemId) => (inventory.counts[itemId] ?? 0) > 0);
  const hasLiquidContainer =
    (inventory.counts["minecraft:bucket"] ?? 0) +
      (inventory.counts["minecraft:water_bucket"] ?? 0) +
      (inventory.counts["minecraft:lava_bucket"] ?? 0) >
    0;
  const hasIgnition =
    (inventory.counts["minecraft:flint_and_steel"] ?? 0) > 0 ||
    (inventory.counts["minecraft:fire_charge"] ?? 0) > 0;
  return (
    Number(hasShield) +
    3 * Number(hasDurablePickaxe) +
    3 * Number(hasLiquidContainer) +
    Number(hasIgnition)
  );
}

function overworldLogTarget(inventory: BeatGameInventory): number {
  if ((inventory.counts["minecraft:shield"] ?? 0) > 0) {
    return 2;
  }
  if (
    (inventory.counts["minecraft:raw_iron"] ?? 0) > 0 ||
    (inventory.counts["minecraft:iron_ingot"] ?? 0) > 0
  ) {
    return 2;
  }
  const hasCookedFood = COOKED_FOOD_ITEM_IDS.some((itemId) => (inventory.counts[itemId] ?? 0) > 0);
  if (hasCookedFood) {
    return 3;
  }
  const hasMiningPickaxe = [
    "minecraft:wooden_pickaxe",
    "minecraft:stone_pickaxe",
    "minecraft:iron_pickaxe",
    "minecraft:diamond_pickaxe",
    "minecraft:netherite_pickaxe",
  ].some((itemId) => (inventory.counts[itemId] ?? 0) > 0);
  return hasMiningPickaxe ? 4 : Number.POSITIVE_INFINITY;
}

function portalRequirements(
  inventory: BeatGameInventory,
  strategy: BeatGameStrategy,
  reusablePortalAvailable: boolean,
): readonly BeatGameRequirementDefinition[] {
  const survivalRequirements = REQUIREMENTS[BeatGamePhase.PREPARE_OVERWORLD]
    .filter(
      ({ key }) =>
        key !== "iron" &&
        key !== "water-bucket" &&
        key !== "ignition" &&
        key !== "bed" &&
        // Endermen guard the pearls, and their hits come through a shield. A
        // live bot hunting them in golden boots died to three hits after
        // breaking off the fight. With every blaze rod in hand, the bot
        // returns to the Nether only in its armor.
        ((key !== "chestplate" && key !== "helmet") ||
          (inventory.counts["minecraft:blaze_rod"] ?? 0) >= strategy.targetBlazeRodCount),
    )
    // oxlint-disable-next-line oxc/no-map-spread -- copies keep the shared requirement definitions unchanged
    .map((definition) => {
      if (definition.key === "logs") {
        return {
          ...definition,
          target: ({ targetLogCount }: BeatGameStrategy) =>
            Math.min(targetLogCount, PORTAL_LOG_RESERVE),
        };
      }
      if (definition.key === "food-supply") {
        return {
          ...definition,
          target: ({ targetFoodCount }: BeatGameStrategy) => Math.min(targetFoodCount, 8),
        };
      }
      // Water, lava and iron all take digging. A live run lost its pickaxe,
      // went for water first, and dug stone by hand for ten minutes while it
      // carried what it needed for a new stone pickaxe.
      if (definition.key === "pickaxe") {
        return { ...definition, priority: PORTAL_PICKAXE_PRIORITY };
      }
      return definition;
    });
  if (reusablePortalAvailable) {
    return [
      ...survivalRequirements,
      ...((inventory.counts["minecraft:shield"] ?? 0) === 0
        ? [itemRequirement("iron", ["minecraft:iron_ingot"], () => 1, 103)]
        : []),
    ];
  }
  const hasCompleteObsidianFrame =
    (inventory.counts["minecraft:obsidian"] ?? 0) >= strategy.targetObsidianCount;
  const useObsidian =
    strategy.portalStrategy === PortalStrategy.OBSIDIAN ||
    (strategy.portalStrategy === PortalStrategy.AUTO && hasCompleteObsidianFrame);
  const missingLiquidBuckets =
    Number((inventory.counts["minecraft:water_bucket"] ?? 0) === 0) +
    Number(!useObsidian && (inventory.counts["minecraft:lava_bucket"] ?? 0) === 0);
  const missingBucketCount = Math.max(
    0,
    missingLiquidBuckets - (inventory.counts["minecraft:bucket"] ?? 0),
  );
  const portalEquipmentIronTarget =
    3 * missingBucketCount +
    Number((inventory.counts["minecraft:shield"] ?? 0) === 0) +
    Number(
      (inventory.counts["minecraft:flint_and_steel"] ?? 0) === 0 &&
        (inventory.counts["minecraft:fire_charge"] ?? 0) === 0,
    );
  return [
    ...survivalRequirements,
    ...(useObsidian &&
    !hasCompleteObsidianFrame &&
    (inventory.counts["minecraft:diamond_pickaxe"] ?? 0) === 0
      ? [itemRequirement("diamond-pickaxe", ["minecraft:diamond_pickaxe"], () => 1, 110)]
      : []),
    ...(portalEquipmentIronTarget > 0
      ? [itemRequirement("iron", ["minecraft:iron_ingot"], () => portalEquipmentIronTarget, 103)]
      : []),
    useObsidian
      ? itemRequirement(
          "obsidian",
          ["minecraft:obsidian"],
          ({ targetObsidianCount }) => targetObsidianCount,
          100,
        )
      : itemRequirement("lava-bucket", ["minecraft:lava_bucket"], () => 1, 100),
    itemRequirement("water-bucket", ["minecraft:water_bucket"], () => 1, 101),
    itemRequirement(
      "ignition",
      ["minecraft:flint_and_steel", "minecraft:fire_charge"],
      () => 1,
      90,
    ),
  ];
}

export function unsatisfiedRequirements(
  phase: BeatGamePhase,
  inventory: BeatGameInventory,
  strategy: BeatGameStrategy,
  context: BeatGameRequirementContext = {},
): readonly BeatGameItemRequirement[] {
  return requirementsForPhase(phase, inventory, strategy, context).filter(
    ({ satisfied }) => !satisfied,
  );
}

export function requirementCount(
  inventory: BeatGameInventory,
  definition: Pick<BeatGameRequirementDefinition, "itemIds" | "tags">,
): number {
  const direct = (definition.itemIds ?? []).reduce(
    (total, itemId) => total + (inventory.counts[itemId] ?? 0),
    0,
  );
  const tagged = (definition.tags ?? []).reduce(
    (total, tag) => total + (inventory.counts[`#${tag}`] ?? 0),
    0,
  );
  return Math.max(direct, tagged);
}

function materializeRequirement(
  definition: BeatGameRequirementDefinition,
  inventory: BeatGameInventory,
  strategy: BeatGameStrategy,
): BeatGameItemRequirement {
  const targetCount = nonNegativeInteger(definition.target(strategy), `${definition.key}.target`);
  const currentCount = requirementCount(inventory, definition);
  return {
    key: definition.key,
    itemIds: definition.itemIds ?? [],
    tags: definition.tags ?? [],
    targetCount,
    currentCount,
    priority: definition.priority,
    satisfied: currentCount >= targetCount,
  };
}

function itemRequirement(
  key: string,
  itemIds: readonly string[],
  target: BeatGameRequirementDefinition["target"],
  priority: number,
): BeatGameRequirementDefinition {
  return { key, itemIds, target, priority };
}

function nonNegativeInteger(value: number, name: string): number {
  if (!Number.isFinite(value) || value < 0) {
    throw new TypeError(`${name} must be a non-negative finite number`);
  }
  return Math.floor(value);
}

const DRAGON_BEDS_REQUIREMENT = itemRequirement(
  "dragon-beds",
  BED_ITEM_IDS,
  () => DRAGON_BED_COUNT,
  60,
);
