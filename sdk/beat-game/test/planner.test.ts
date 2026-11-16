import { describe, expect, it } from "vitest";

import { BeatGamePhase, decideBeatGameAction, defaultBeatGameStrategy } from "../src/index.js";
import { blockObservation, checkpoint, observation } from "./fixtures.js";

describe("beat-game planner", () => {
  it("recovers a death before considering phase requirements", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({ dead: true }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toEqual({
      type: "recover-death",
      action: "recover-death",
    });
  });

  it.each([
    [20, {}],
    [4, {}],
    // At the eating threshold with raw beef, cooking would need a furnace.
    [14, { "minecraft:beef": 1, "minecraft:dark_oak_log": 12 }],
  ])(
    "returns to the Overworld to prepare gear after a reset in the Nether at food %i",
    (food, extraItems) => {
      // A death reset the run to Overworld preparation while the bot went back
      // into the Nether for its items, as in a live run.
      const inventory = {
        "minecraft:shield": 1,
        "minecraft:stone_pickaxe": 1,
        "minecraft:wooden_sword": 1,
        ...extraItems,
      };
      const nether = decideBeatGameAction({
        checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
        observation: observation({ dimension: "minecraft:the_nether", food, counts: inventory }),
        strategy: defaultBeatGameStrategy,
      });
      const overworld = decideBeatGameAction({
        checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
        observation: observation({ food, counts: inventory }),
        strategy: defaultBeatGameStrategy,
      });

      expect(nether).toEqual({ type: "return-through-portal", action: "return-through-portal" });
      expect(overworld.type).toBe("satisfy-requirement");
    },
  );

  it("returns to the Overworld while wounded without food after a reset in the Nether", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        dimension: "minecraft:the_nether",
        food: 17,
        health: 13,
        counts: { "minecraft:red_mushroom": 3, "minecraft:wooden_sword": 1 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toEqual({ type: "return-through-portal", action: "return-through-portal" });
  });

  it("acquires emergency food instead of retreating without regeneration", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        food: 17,
        health: 12,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food",
      requirement: { key: "food" },
    });
  });

  it("acquires emergency food after a Nether death leaves it wounded", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.COLLECT_NETHER_RESOURCES),
      observation: observation({
        food: 11,
        health: 13.5,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food-supply",
      requirement: { key: "food-supply" },
    });
  });

  it("prepares retained raw food after a Nether death", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.COLLECT_NETHER_RESOURCES),
      observation: observation({
        counts: { "minecraft:porkchop": 3 },
        food: 11,
        health: 13.5,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food",
      requirement: { key: "food" },
    });
  });

  it("cooks raw food before trying to recover at low health", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        counts: { "minecraft:porkchop": 3 },
        food: 17,
        health: 12,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food",
      requirement: { key: "food" },
    });
  });

  it("cooks raw food instead of consuming it at the normal hunger threshold", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        counts: {
          "minecraft:oak_log": defaultBeatGameStrategy.targetLogCount,
          "minecraft:porkchop": 3,
        },
        food: defaultBeatGameStrategy.eatBelowFood,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food",
      requirement: { key: "food" },
    });
  });

  it("cooks a partial raw-food reserve before portal work", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:oak_log": defaultBeatGameStrategy.targetLogCount,
          "minecraft:porkchop": 3,
        },
        food: defaultBeatGameStrategy.eatBelowFood,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food",
      requirement: { key: "food" },
    });
  });

  it("cooks an existing raw-food reserve before an urgent hunt", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:oak_log": defaultBeatGameStrategy.targetLogCount,
          "minecraft:porkchop": 3,
        },
        food: 10,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food",
      requirement: { key: "food" },
    });
  });

  it("gathers cooking prerequisites before preparing raw food while healthy", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        counts: { "minecraft:porkchop": 3 },
        food: defaultBeatGameStrategy.eatBelowFood,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:logs",
      requirement: { key: "logs" },
    });
  });

  it("prepares urgent raw food once the usable wood reserve is ready", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        counts: {
          "minecraft:oak_log": 4,
          "minecraft:mutton": 4,
          "minecraft:wooden_sword": 1,
        },
        food: 9,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food",
      requirement: { key: "food" },
    });
  });

  it("eats raw food when hunger becomes critical", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        counts: { "minecraft:porkchop": 3 },
        food: 6,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "eat",
      action: "eat",
    });
  });

  it("secures emergency food before phase work when hunger is urgent", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({ food: 10 }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:food-supply",
      requirement: { key: "food-supply" },
    });
  });

  it("crafts a wooden sword as soon as enough wood is available", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        counts: { "minecraft:oak_log": 2 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:basic-melee-weapon",
      requirement: { key: "basic-melee-weapon" },
    });
  });

  it("defers a small healthy food top-up while required work remains", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:cooked_cod": 7,
          "minecraft:oak_log": 8,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
        },
        food: 17,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:iron",
      requirement: { key: "iron" },
    });
  });

  it("secures iron before topping up a useful surface food reserve", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.PREPARE_OVERWORLD),
      observation: observation({
        counts: {
          "minecraft:white_bed": 1,
          "minecraft:cooked_beef": 5,
          "minecraft:spruce_log": 8,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
        },
        food: 20,
        position: { y: 76 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:iron",
      requirement: { key: "iron" },
    });
  });

  it("prepares a bed before iron until the sheep search is exhausted", () => {
    const prepared = observation({
      counts: {
        "minecraft:cooked_beef": 5,
        "minecraft:spruce_log": 8,
        "minecraft:cobblestone": 20,
        "minecraft:stone_sword": 1,
        "minecraft:stone_pickaxe": 1,
      },
      food: 20,
      position: { y: 76 },
    });
    const base = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);
    const exhausted = {
      ...base,
      memory: {
        ...base.memory,
        explorationFrontiers: {
          "minecraft:overworld:find-sheep": {
            progressVersion: 2 as const,
            origin: prepared.player.position,
            nextIndex: 7,
            totalAdvances: 6,
          },
        },
      },
    };

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: prepared,
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ action: "satisfy:bed" });
    expect(
      decideBeatGameAction({
        checkpoint: exhausted,
        observation: prepared,
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ action: "satisfy:iron" });
  });

  it("does not abandon portal travel after consuming one reserve meal", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:cooked_cod": 7,
          "minecraft:oak_log": 8,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:iron_ingot": 7,
          "minecraft:shield": 1,
          "minecraft:iron_pickaxe": 1,
          "minecraft:lava_bucket": 1,
          "minecraft:water_bucket": 1,
          "minecraft:flint_and_steel": 1,
        },
        food: 20,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    });
  });

  it("does not abandon a reusable portal route to top up logs", () => {
    const base = checkpoint(BeatGamePhase.ENTER_NETHER);
    const observedAt = "2026-01-01T00:00:00.000Z";
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        memory: {
          ...base.memory,
          portals: [
            {
              key: "portal:minecraft:overworld:0:64:0",
              value: blockObservation(
                {
                  x: 0,
                  y: 64,
                  z: 0,
                  dimension: "minecraft:overworld",
                },
                {
                  blockId: "minecraft:nether_portal",
                  diggable: false,
                  solid: false,
                  observedAt,
                },
              ),
              observedAt,
              confidence: 0.25,
            },
          ],
        },
      },
      observation: observation({
        counts: {
          "minecraft:cooked_cod": 8,
          "minecraft:oak_log": 2,
          "minecraft:cobblestone": 89,
          "minecraft:stone_sword": 1,
          "minecraft:iron_ingot": 2,
          "minecraft:shield": 1,
          "minecraft:iron_pickaxe": 1,
          "minecraft:water_bucket": 1,
          "minecraft:flint_and_steel": 1,
        },
        food: 20,
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    });
  });

  it("finishes deep portal work before topping up a healthy food reserve", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:cooked_cod": 6,
          "minecraft:oak_log": 8,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:iron_ingot": 7,
          "minecraft:shield": 1,
          "minecraft:iron_pickaxe": 1,
          "minecraft:lava_bucket": 1,
          "minecraft:water_bucket": 1,
          "minecraft:flint_and_steel": 1,
        },
        food: 20,
        position: { y: -53 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    });
  });

  it("keeps a useful deep-work food reserve without abandoning the portal", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:cooked_cod": 4,
          "minecraft:oak_log": 8,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:iron_ingot": 7,
          "minecraft:shield": 1,
          "minecraft:iron_pickaxe": 1,
          "minecraft:lava_bucket": 1,
          "minecraft:water_bucket": 1,
          "minecraft:flint_and_steel": 1,
        },
        food: 20,
        position: { y: -53 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    });
  });

  it("recovers in place when current hunger or emergency food can heal", () => {
    const base = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: observation({
          food: 18,
          health: 12,
        }),
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ type: "retreat" });

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: observation({
          counts: { "minecraft:rotten_flesh": 1 },
          food: 17,
          health: 12,
        }),
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ type: "retreat" });
  });

  it("advances only after a fresh observation satisfies preparation", () => {
    const base = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        planner: {
          ...base.planner,
          completedActions: ["prepare-equipment"],
        },
      },
      observation: observation({
        counts: {
          "minecraft:white_bed": 1,
          "minecraft:cooked_beef": 16,
          "minecraft:oak_log": 8,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:iron_ingot": 7,
          "minecraft:iron_pickaxe": 1,
          "minecraft:water_bucket": 1,
          "minecraft:flint_and_steel": 1,
          "minecraft:shield": 1,
        },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "advance-phase",
      from: BeatGamePhase.PREPARE_OVERWORLD,
      to: BeatGamePhase.ENTER_NETHER,
    });
  });

  it("handles hunger, low health, and equipment preparation explicitly", () => {
    const counts = {
      "minecraft:white_bed": 1,
      "minecraft:cooked_beef": 16,
      "minecraft:oak_log": 8,
      "minecraft:cobblestone": 20,
      "minecraft:stone_sword": 1,
      "minecraft:iron_ingot": 7,
      "minecraft:iron_pickaxe": 1,
      "minecraft:water_bucket": 1,
      "minecraft:flint_and_steel": 1,
      "minecraft:shield": 1,
    };
    const base = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: observation({ counts, food: 10 }),
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ type: "eat" });

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: observation({ counts, health: 4 }),
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ type: "retreat" });

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: observation({ counts }),
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ type: "prepare-equipment" });
  });

  it("uses dimension evidence before advancing through a portal phase", () => {
    const base = checkpoint(BeatGamePhase.ENTER_NETHER);
    const counts = {
      "minecraft:cooked_cod": 8,
      "minecraft:oak_log": 8,
      "minecraft:cobblestone": 20,
      "minecraft:stone_sword": 1,
      "minecraft:iron_ingot": 7,
      "minecraft:shield": 1,
      "minecraft:iron_pickaxe": 1,
      "minecraft:obsidian": 10,
      "minecraft:water_bucket": 1,
      "minecraft:flint_and_steel": 1,
    };

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: observation({ counts }),
        strategy: defaultBeatGameStrategy,
      }).type,
    ).toBe("build-and-enter-nether");

    expect(
      decideBeatGameAction({
        checkpoint: base,
        observation: observation({
          counts,
          dimension: "minecraft:the_nether",
        }),
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({
      type: "advance-phase",
      to: BeatGamePhase.COLLECT_NETHER_RESOURCES,
    });
  });

  it("tries a remembered portal before collecting construction supplies", () => {
    const base = checkpoint(BeatGamePhase.ENTER_NETHER);
    const observedAt = "2026-01-01T00:00:00.000Z";
    const portal = blockObservation(
      {
        x: 0,
        y: 64,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:nether_portal",
        diggable: false,
        solid: false,
        observedAt,
      },
    );
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        memory: {
          ...base.memory,
          portals: [
            {
              key: "portal:minecraft:overworld:0:64:0",
              value: portal,
              observedAt,
              confidence: 0.25,
            },
          ],
        },
      },
      observation: observation({
        counts: {
          "minecraft:cooked_beef": 8,
          "minecraft:oak_log": 4,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
          "minecraft:shield": 1,
        },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toEqual({
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    });
  });

  it("rebuilds survival supplies before returning to the Nether", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.COLLECT_NETHER_RESOURCES),
      observation: observation({ dimension: "minecraft:overworld" }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:logs",
      requirement: { key: "logs" },
    });
  });

  it("returns to a remembered Nether portal after restoring survival gear", () => {
    const base = checkpoint(BeatGamePhase.COLLECT_NETHER_RESOURCES);
    const observedAt = "2026-01-01T00:00:00.000Z";
    const portal = blockObservation(
      {
        x: 0,
        y: 64,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:nether_portal",
        diggable: false,
        solid: false,
        observedAt,
      },
    );
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        memory: {
          ...base.memory,
          portals: [
            {
              key: "portal:minecraft:overworld:0:64:0",
              value: portal,
              observedAt,
              confidence: 0.25,
            },
          ],
        },
      },
      observation: observation({
        counts: {
          "minecraft:cooked_beef": 8,
          "minecraft:oak_log": 4,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
          "minecraft:shield": 1,
        },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    });
  });

  it("uses a shared stronghold estimate without making every bot throw eyes", () => {
    const base = checkpoint(BeatGamePhase.LOCATE_STRONGHOLD);
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        memory: {
          ...base.memory,
          strongholdEstimate: {
            x: 1200,
            y: 32,
            z: -800,
            dimension: "minecraft:overworld",
          },
        },
      },
      observation: observation(),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({ type: "search-stronghold" });
  });

  it("requires an observed End exit before completion", () => {
    const exit = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.EXIT_END),
      observation: observation({
        dimension: "minecraft:the_end",
      }),
      strategy: defaultBeatGameStrategy,
    });
    expect(exit).toMatchObject({ type: "exit-end" });

    const complete = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.EXIT_END),
      observation: observation({
        dimension: "minecraft:overworld",
      }),
      strategy: defaultBeatGameStrategy,
    });
    expect(complete).toMatchObject({
      type: "advance-phase",
      to: BeatGamePhase.COMPLETE,
    });
  });
});
