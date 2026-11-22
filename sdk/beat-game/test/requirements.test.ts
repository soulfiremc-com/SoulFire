import { describe, expect, it } from "vitest";

import {
  BeatGamePhase,
  defaultBeatGameStrategy,
  fastBeatGameStrategy,
  PortalStrategy,
  requirementsForPhase,
} from "../src/index.js";
import { observation } from "./fixtures.js";
import { arrayContaining } from "./matchers.js";

const goldBootsSatisfied = (remainingDurability: number, count = 1) =>
  requirementsForPhase(
    BeatGamePhase.COLLECT_NETHER_RESOURCES,
    {
      ...observation({ counts: { "minecraft:golden_boots": count } }).inventory,
      remainingDurability: { "minecraft:golden_boots": remainingDurability },
    },
    fastBeatGameStrategy,
  ).find(({ key }) => key === "gold-boots")?.satisfied;
const missingStrongholdKeys = (counts: Record<string, number>) =>
  requirementsForPhase(
    BeatGamePhase.LOCATE_STRONGHOLD,
    observation({ counts }).inventory,
    fastBeatGameStrategy,
  )
    .filter(({ satisfied }) => !satisfied)
    .map(({ key }) => key);
const reentryArmorKeys = (blazeRods: number) =>
  requirementsForPhase(
    BeatGamePhase.ENTER_NETHER,
    observation({ counts: { "minecraft:blaze_rod": blazeRods } }).inventory,
    fastBeatGameStrategy,
    { reusablePortalAvailable: true },
  )
    .map(({ key }) => key)
    .filter((key) => key === "chestplate" || key === "helmet");
const keys = (strategy: typeof defaultBeatGameStrategy, bedUnavailable = false) =>
  requirementsForPhase(
    BeatGamePhase.LOCATE_STRONGHOLD,
    observation({ counts: { "minecraft:white_bed": 1 } }).inventory,
    strategy,
    { bedUnavailable },
  ).map(({ key, targetCount, currentCount }) => ({ key, targetCount, currentCount }));
const order = (strategy: typeof defaultBeatGameStrategy) =>
  requirementsForPhase(BeatGamePhase.PREPARE_OVERWORLD, observation().inventory, strategy).map(
    ({ key }) => key,
  );
const orderWithIron = (strategy: typeof defaultBeatGameStrategy, iron: number) =>
  requirementsForPhase(
    BeatGamePhase.PREPARE_OVERWORLD,
    observation({ counts: { "minecraft:iron_ingot": iron } }).inventory,
    strategy,
  ).map(({ key }) => key);
const prepareKeys = (bedUnavailable: boolean) =>
  requirementsForPhase(
    BeatGamePhase.PREPARE_OVERWORLD,
    observation().inventory,
    defaultBeatGameStrategy,
    { bedUnavailable },
  ).map(({ key }) => key);
const logRequirement = (counts: Readonly<Record<string, number>>) =>
  requirementsForPhase(
    BeatGamePhase.PREPARE_OVERWORLD,
    observation({ counts }).inventory,
    defaultBeatGameStrategy,
  ).find(({ key }) => key === "logs");
const ironRequirement = (counts: Readonly<Record<string, number>>) =>
  requirementsForPhase(
    BeatGamePhase.PREPARE_OVERWORLD,
    observation({ counts }).inventory,
    defaultBeatGameStrategy,
  ).find(({ key }) => key === "iron");

const missingNetherKeys = (counts: Readonly<Record<string, number>>) =>
  requirementsForPhase(
    BeatGamePhase.COLLECT_NETHER_RESOURCES,
    observation({ counts }).inventory,
    fastBeatGameStrategy,
  )
    .filter(({ currentCount, targetCount }) => currentCount < targetCount)
    .map(({ key }) => key);

describe("beat-game requirements", () => {
  it("carries four beds into a fast run's dragon fight unless sheep ran out", () => {
    expect(keys(fastBeatGameStrategy)).toContainEqual({
      key: "dragon-beds",
      targetCount: 4,
      currentCount: 1,
    });
    expect(keys(fastBeatGameStrategy, true).map(({ key }) => key)).not.toContain("dragon-beds");
    expect(keys(defaultBeatGameStrategy).map(({ key }) => key)).not.toContain("dragon-beds");
  });

  it("takes the bed right after wood and a weapon in a fast run", () => {
    expect(order(fastBeatGameStrategy).slice(0, 3)).toEqual(["logs", "basic-melee-weapon", "bed"]);
    // The full cooked food stock waits for the iron and the shield.
    expect(order(fastBeatGameStrategy).indexOf("food")).toBeGreaterThan(
      order(fastBeatGameStrategy).indexOf("shield"),
    );
    expect(order(defaultBeatGameStrategy).indexOf("food")).toBeLessThan(
      order(defaultBeatGameStrategy).indexOf("iron"),
    );
    expect(order(defaultBeatGameStrategy).indexOf("bed")).toBeGreaterThan(
      order(defaultBeatGameStrategy).indexOf("cobblestone"),
    );
  });

  it("crafts the shield before the food supply once a fast run holds iron", () => {
    expect(orderWithIron(fastBeatGameStrategy, 1).indexOf("shield")).toBeLessThan(
      orderWithIron(fastBeatGameStrategy, 1).indexOf("food-supply"),
    );
    expect(orderWithIron(fastBeatGameStrategy, 0).indexOf("shield")).toBeGreaterThan(
      orderWithIron(fastBeatGameStrategy, 0).indexOf("food-supply"),
    );
    expect(orderWithIron(defaultBeatGameStrategy, 1).indexOf("shield")).toBeGreaterThan(
      orderWithIron(defaultBeatGameStrategy, 1).indexOf("food-supply"),
    );
  });

  it("gets armor right after cobblestone in a fast run", () => {
    expect(order(fastBeatGameStrategy).indexOf("chestplate")).toBe(
      order(fastBeatGameStrategy).indexOf("cobblestone") + 1,
    );
    expect(order(defaultBeatGameStrategy).slice(-2)).toEqual(["chestplate", "helmet"]);
  });

  it("gets gold boots before blaze rods in the Nether", () => {
    expect(missingNetherKeys({})).toStrictEqual([
      "gold-boots",
      "melee-weapon",
      "blaze-rods",
      "ender-pearls",
    ]);
    expect(missingNetherKeys({ "minecraft:golden_boots": 1 })).toStrictEqual([
      "melee-weapon",
      "blaze-rods",
      "ender-pearls",
    ]);
    // A sword worn out on the pearl hunt is replaced in the Nether.
    expect(
      missingNetherKeys({ "minecraft:golden_boots": 1, "minecraft:stone_sword": 1 }),
    ).toStrictEqual(["blaze-rods", "ender-pearls"]);
  });

  it("counts interchangeable item choices without double counting tags", () => {
    const inventory = observation({
      counts: {
        "minecraft:cooked_beef": 4,
        "minecraft:bread": 3,
      },
    }).inventory;

    const food = requirementsForPhase(
      BeatGamePhase.PREPARE_OVERWORLD,
      inventory,
      defaultBeatGameStrategy,
    ).find(({ key }) => key === "food");

    expect(food?.currentCount).toBe(7);
    expect(food?.satisfied).toBe(false);
  });

  it("requires a compact cooked reserve before leaving initial preparation", () => {
    const food = requirementsForPhase(
      BeatGamePhase.PREPARE_OVERWORLD,
      observation({
        counts: {
          "minecraft:cooked_mutton": 7,
          "minecraft:mutton": 64,
        },
      }).inventory,
      defaultBeatGameStrategy,
    ).find(({ key }) => key === "food");

    expect(food).toMatchObject({
      currentCount: 7,
      targetCount: 8,
      satisfied: false,
    });
  });

  it("orders missing requirements by explicit planner priority", () => {
    const requirements = requirementsForPhase(
      BeatGamePhase.PREPARE_OVERWORLD,
      observation().inventory,
      defaultBeatGameStrategy,
    );

    expect(requirements.map(({ key }) => key)).toEqual([
      "logs",
      "basic-melee-weapon",
      "food-supply",
      "cobblestone",
      "bed",
      "melee-weapon",
      "food",
      "iron",
      "shield",
      "pickaxe",
      "water-bucket",
      "ignition",
      "chestplate",
      "helmet",
    ]);
  });

  it("drops the bed once the sheep search is exhausted", () => {
    expect(prepareKeys(false)).toContain("bed");
    expect(prepareKeys(true)).not.toContain("bed");
  });

  it("secures an edible supply before spending time on stone", () => {
    const requirements = requirementsForPhase(
      BeatGamePhase.PREPARE_OVERWORLD,
      observation({
        counts: {
          "minecraft:oak_log": 8,
          "minecraft:wooden_sword": 1,
          "minecraft:beef": 4,
        },
      }).inventory,
      defaultBeatGameStrategy,
    );

    expect(requirements.find(({ key }) => key === "food-supply")).toMatchObject({
      currentCount: 4,
      targetCount: 4,
      satisfied: true,
    });
    expect(requirements.find(({ key }) => key === "food")).toMatchObject({
      currentCount: 0,
      targetCount: 8,
      satisfied: false,
    });
    expect(requirements.findIndex(({ key }) => key === "food-supply")).toBeLessThan(
      requirements.findIndex(({ key }) => key === "cobblestone"),
    );
  });

  it("counts safe forage toward the edible supply reserve", () => {
    const requirements = requirementsForPhase(
      BeatGamePhase.PREPARE_OVERWORLD,
      observation({
        counts: {
          "minecraft:sweet_berries": 4,
          "minecraft:glow_berries": 4,
        },
      }).inventory,
      defaultBeatGameStrategy,
    );

    expect(requirements.find(({ key }) => key === "food-supply")).toMatchObject({
      currentCount: 8,
      targetCount: 4,
      satisfied: true,
    });
    expect(requirements.find(({ key }) => key === "food")).toMatchObject({
      currentCount: 0,
      targetCount: 8,
      satisfied: false,
    });
  });

  it("shrinks the bootstrap log reserve as durable equipment comes online", () => {
    expect(logRequirement({})).toMatchObject({
      targetCount: 8,
      satisfied: false,
    });
    expect(
      logRequirement({
        "minecraft:wooden_pickaxe": 1,
        "minecraft:oak_log": 4,
      }),
    ).toMatchObject({
      targetCount: 4,
      satisfied: true,
    });
    expect(
      logRequirement({
        "minecraft:stone_pickaxe": 1,
        "minecraft:raw_iron": 7,
        "minecraft:oak_log": 2,
      }),
    ).toMatchObject({
      targetCount: 2,
      satisfied: true,
    });
    expect(
      logRequirement({
        "minecraft:wooden_pickaxe": 1,
        "minecraft:cooked_chicken": 8,
        "minecraft:oak_log": 3,
      }),
    ).toMatchObject({
      targetCount: 3,
      satisfied: true,
    });
    expect(
      logRequirement({
        "minecraft:shield": 1,
      }),
    ).toMatchObject({
      targetCount: 2,
      satisfied: false,
    });
  });

  it("credits crafted equipment toward the preparation iron budget", () => {
    expect(ironRequirement({})).toMatchObject({
      currentCount: 0,
      targetCount: 7,
      satisfied: false,
    });
    expect(
      ironRequirement({
        "minecraft:shield": 1,
        "minecraft:iron_ingot": 6,
      }),
    ).toMatchObject({
      currentCount: 6,
      targetCount: 6,
      satisfied: true,
    });
    expect(
      ironRequirement({
        "minecraft:shield": 1,
        "minecraft:iron_pickaxe": 1,
        "minecraft:bucket": 1,
      }),
    ).toMatchObject({
      currentCount: 0,
      targetCount: 0,
      satisfied: true,
    });
  });

  it("counts eyes of ender toward the pearls and blaze rods they were made from", () => {
    // As in the live run: eleven eyes, one lost to a throw, back in the Nether.
    const nether = requirementsForPhase(
      BeatGamePhase.COLLECT_NETHER_RESOURCES,
      observation({ counts: { "minecraft:ender_eye": 11, "minecraft:blaze_rod": 3 } }).inventory,
      fastBeatGameStrategy,
    );

    expect(nether.find(({ key }) => key === "blaze-rods")).toMatchObject({ satisfied: true });
    // Only the pearl for the lost eye is still missing.
    expect(nether.find(({ key }) => key === "ender-pearls")).toMatchObject({
      currentCount: 11,
      targetCount: 12,
      satisfied: false,
    });
  });

  it("needs a pickaxe to dig down to the stronghold", () => {
    // As in the live run: eleven eyes and no pickaxe left after the staircase.

    expect(
      missingStrongholdKeys({ "minecraft:ender_eye": 12, "minecraft:stone_sword": 1 }),
    ).toContain("pickaxe");
    expect(
      missingStrongholdKeys({
        "minecraft:ender_eye": 12,
        "minecraft:stone_sword": 1,
        "minecraft:stone_pickaxe": 1,
      }),
    ).not.toContain("pickaxe");
  });

  it("restocks food and water before the stronghold search", () => {
    // As in the live run: one raw porkchop and an empty bucket after a clutch.
    const ready = {
      "minecraft:ender_eye": 12,
      "minecraft:stone_sword": 1,
      "minecraft:stone_pickaxe": 1,
    };

    expect(
      missingStrongholdKeys({ ...ready, "minecraft:porkchop": 1, "minecraft:bucket": 1 }),
    ).toEqual(arrayContaining(["food-supply", "water-bucket"]));
    expect(
      missingStrongholdKeys({
        ...ready,
        "minecraft:cooked_beef": 4,
        "minecraft:water_bucket": 1,
      }),
    ).not.toEqual(arrayContaining(["food-supply"]));
    expect(
      missingStrongholdKeys({
        ...ready,
        "minecraft:cooked_beef": 4,
        "minecraft:water_bucket": 1,
      }),
    ).not.toContain("water-bucket");
  });

  it("replaces gold boots before they wear out", () => {
    expect(goldBootsSatisfied(91)).toBe(true);
    // Nearly broken: a new pair is due while the old one still works.
    expect(goldBootsSatisfied(12)).toBe(false);
    // The new pair is carried beside the worn one.
    expect(goldBootsSatisfied(103, 2)).toBe(true);
  });

  it("returns to the Nether for pearls only in armor", () => {
    // Before the blaze rods the armor does not hold up the trip.
    expect(reentryArmorKeys(0)).toEqual([]);
    // The pearl hunt means fighting endermen, so the armor comes first.
    expect(reentryArmorKeys(fastBeatGameStrategy.targetBlazeRodCount).toSorted()).toEqual([
      "chestplate",
      "helmet",
    ]);
  });

  it("requires a diamond pickaxe before mining an obsidian frame", () => {
    const strategy = {
      ...defaultBeatGameStrategy,
      portalStrategy: PortalStrategy.OBSIDIAN,
    };

    expect(
      requirementsForPhase(BeatGamePhase.ENTER_NETHER, observation().inventory, strategy).map(
        ({ key }) => key,
      ),
    ).toEqual([
      "logs",
      "basic-melee-weapon",
      "food-supply",
      "cobblestone",
      "diamond-pickaxe",
      "melee-weapon",
      "pickaxe",
      "food",
      "iron",
      "shield",
      "water-bucket",
      "obsidian",
      "ignition",
    ]);

    expect(
      requirementsForPhase(
        BeatGamePhase.ENTER_NETHER,
        observation({
          counts: {
            "minecraft:diamond_pickaxe": 1,
            "minecraft:obsidian": strategy.targetObsidianCount,
          },
        }).inventory,
        strategy,
      ).map(({ key }) => key),
    ).not.toContain("diamond-pickaxe");
  });

  it("revalidates survival supplies before entering the Nether", () => {
    const requirements = requirementsForPhase(
      BeatGamePhase.ENTER_NETHER,
      observation({
        counts: {
          "minecraft:oak_log": 8,
          "minecraft:wooden_sword": 1,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:iron_ingot": 7,
          "minecraft:iron_pickaxe": 1,
          "minecraft:water_bucket": 1,
          "minecraft:flint_and_steel": 1,
          "minecraft:shield": 1,
        },
      }).inventory,
      {
        ...defaultBeatGameStrategy,
        portalStrategy: PortalStrategy.CAST,
      },
    );

    expect(requirements.find(({ key }) => key === "food")).toMatchObject({
      currentCount: 0,
      targetCount: 8,
      satisfied: false,
    });
    expect(requirements.find(({ key }) => key === "logs")).toMatchObject({
      currentCount: 8,
      targetCount: 4,
      satisfied: true,
    });
    expect(requirements.filter(({ key }) => key === "water-bucket")).toHaveLength(1);
    expect(requirements.filter(({ key }) => key === "ignition")).toHaveLength(1);
    expect(requirements.find(({ key }) => key === "iron")).toMatchObject({
      currentCount: 7,
      targetCount: 3,
      satisfied: true,
    });
  });

  it("does not rebuild portal equipment while a reusable portal is known", () => {
    const requirements = requirementsForPhase(
      BeatGamePhase.ENTER_NETHER,
      observation({
        counts: {
          "minecraft:cooked_beef": 8,
          "minecraft:oak_log": 4,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
          "minecraft:shield": 1,
        },
      }).inventory,
      {
        ...defaultBeatGameStrategy,
        portalStrategy: PortalStrategy.CAST,
      },
      { reusablePortalAvailable: true },
    );

    expect(requirements.every(({ satisfied }) => satisfied)).toBe(true);
    expect(requirements.map(({ key }) => key)).not.toEqual(
      arrayContaining(["iron", "water-bucket", "lava-bucket", "ignition", "obsidian"]),
    );
  });

  it("restores the shield iron dependency before reusing a portal", () => {
    const requirements = requirementsForPhase(
      BeatGamePhase.ENTER_NETHER,
      observation({
        counts: {
          "minecraft:cooked_beef": 8,
          "minecraft:oak_log": 4,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
          "minecraft:raw_iron": 1,
        },
      }).inventory,
      {
        ...defaultBeatGameStrategy,
        portalStrategy: PortalStrategy.CAST,
      },
      { reusablePortalAvailable: true },
    );

    expect(requirements.find(({ key }) => key === "iron")).toMatchObject({
      currentCount: 0,
      targetCount: 1,
      satisfied: false,
    });
    expect(requirements.findIndex(({ key }) => key === "iron")).toBeLessThan(
      requirements.findIndex(({ key }) => key === "shield"),
    );
    expect(requirements.map(({ key }) => key)).not.toEqual(
      arrayContaining(["water-bucket", "lava-bucket", "ignition", "obsidian"]),
    );
  });

  it("replenishes only the iron needed for missing portal equipment", () => {
    const requirements = requirementsForPhase(
      BeatGamePhase.ENTER_NETHER,
      observation({
        counts: {
          "minecraft:bucket": 1,
          "minecraft:shield": 1,
        },
      }).inventory,
      {
        ...defaultBeatGameStrategy,
        portalStrategy: PortalStrategy.CAST,
      },
    );

    expect(requirements.find(({ key }) => key === "iron")).toMatchObject({
      currentCount: 0,
      targetCount: 4,
      satisfied: false,
    });
  });

  it("prices missing cast-portal buckets in ingots", () => {
    const requirements = requirementsForPhase(BeatGamePhase.ENTER_NETHER, observation().inventory, {
      ...defaultBeatGameStrategy,
      portalStrategy: PortalStrategy.CAST,
    });

    expect(requirements.find(({ key }) => key === "iron")).toMatchObject({
      currentCount: 0,
      targetCount: 8,
      satisfied: false,
    });
  });
});
