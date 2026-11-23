import { describe, expect, it } from "vitest";

import {
  BeatGamePhase,
  decideBeatGameAction,
  defaultBeatGameStrategy,
  fastBeatGameStrategy,
  PortalStrategy,
} from "../src/index.js";
import { blockObservation, checkpoint, observation } from "./fixtures.js";

describe("beat-game planner", () => {
  it("goes back through the portal after waking in the Overworld mid-fight", () => {
    // As in a live run: an enderman killed the bot in the End and it woke at
    // its bed in the Overworld.
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.FIGHT_ENDER_DRAGON),
      observation: observation({
        position: { x: -225, y: 80, z: -1263, dimension: "minecraft:overworld" },
        counts: {
          "minecraft:bow": 1,
          "minecraft:arrow": 64,
          "minecraft:cooked_beef": 16,
        },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "advance-phase",
      to: BeatGamePhase.ACTIVATE_END_PORTAL,
    });
  });

  it("stocks food before stepping through the End portal", () => {
    const portalCheckpoint = checkpoint(BeatGamePhase.ACTIVATE_END_PORTAL);
    const decision = decideBeatGameAction({
      checkpoint: {
        ...portalCheckpoint,
        memory: { ...portalCheckpoint.memory, emptyPortalFrames: 10 },
      },
      observation: observation({
        position: { x: -372, y: 14, z: -1772, dimension: "minecraft:overworld" },
        counts: { "minecraft:ender_eye": 11 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({ type: "satisfy-requirement", action: "satisfy:food" });
  });

  it("goes back through an open portal without more eyes", () => {
    const portalCheckpoint = checkpoint(BeatGamePhase.ACTIVATE_END_PORTAL);
    const decision = decideBeatGameAction({
      checkpoint: {
        ...portalCheckpoint,
        memory: { ...portalCheckpoint.memory, emptyPortalFrames: 0 },
      },
      observation: observation({
        position: { x: -372, y: 14, z: -1772, dimension: "minecraft:overworld" },
        counts: { "minecraft:ender_eye": 1, "minecraft:cooked_beef": 16 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toEqual({ type: "activate-end-portal", action: "activate-end-portal" });
  });

  it("fights the dragon with what it carried into the End", () => {
    // As in a live run: no cooked food made it through the portal.
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.FIGHT_ENDER_DRAGON),
      observation: observation({
        dimension: "minecraft:the_end",
        counts: { "minecraft:bow": 1, "minecraft:arrow": 64, "minecraft:stone_sword": 1 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toEqual({
      type: "fight-ender-dragon",
      action: "fight-ender-dragon",
    });
  });

  it("activates a portal whose remaining empty frames its eyes can fill", () => {
    // As in a live run: two frames already held eyes and the bot carried
    // eleven, one more than the portal still needed.
    const portalCheckpoint = checkpoint(BeatGamePhase.ACTIVATE_END_PORTAL);
    const decision = decideBeatGameAction({
      checkpoint: {
        ...portalCheckpoint,
        memory: { ...portalCheckpoint.memory, emptyPortalFrames: 10 },
      },
      observation: observation({
        position: { x: -372, y: 14, z: -1772, dimension: "minecraft:overworld" },
        counts: { "minecraft:ender_eye": 11, "minecraft:cooked_beef": 16 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toEqual({
      type: "activate-end-portal",
      action: "activate-end-portal",
    });
  });

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

  it("goes back to the Overworld for food when hunger is urgent in the Nether", () => {
    // As in a live run whose bot carried only raw pork it could not cook.
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.COLLECT_NETHER_RESOURCES),
      observation: observation({
        food: 10,
        dimension: "minecraft:the_nether",
        counts: { "minecraft:porkchop": 3 },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({ type: "return-through-portal" });
    expect(
      decideBeatGameAction({
        checkpoint: checkpoint(BeatGamePhase.COLLECT_NETHER_RESOURCES),
        observation: observation({
          food: 10,
          dimension: "minecraft:the_nether",
          counts: { "minecraft:cooked_porkchop": 3 },
        }),
        strategy: defaultBeatGameStrategy,
      }),
    ).not.toMatchObject({ type: "return-through-portal" });
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
          "minecraft:stone_pickaxe": 1,
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

  it.each([
    { y: 64, action: "satisfy:logs" },
    { y: 20, action: "satisfy:iron" },
  ])("refills the wood reserve only near the surface (y $y)", ({ y, action }) => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:cooked_cod": 16,
          "minecraft:oak_log": 3,
          "minecraft:cobblestone": 20,
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
        },
        position: { y },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({ type: "satisfy-requirement", action });
  });

  it("replaces a lost pickaxe before digging for portal water", () => {
    const decision = decideBeatGameAction({
      checkpoint: checkpoint(BeatGamePhase.ENTER_NETHER),
      observation: observation({
        counts: {
          "minecraft:cooked_beef": 8,
          "minecraft:oak_log": 5,
          "minecraft:stick": 3,
          "minecraft:cobblestone": 120,
          "minecraft:stone_sword": 1,
          "minecraft:shield": 1,
          "minecraft:bucket": 1,
          "minecraft:lava_bucket": 1,
          "minecraft:flint_and_steel": 1,
        },
      }),
      strategy: { ...defaultBeatGameStrategy, portalStrategy: PortalStrategy.CAST },
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      action: "satisfy:pickaxe",
    });
  });

  it("goes back for wood when the Nether leaves it without a pickaxe or a handle", () => {
    const base = checkpoint(BeatGamePhase.COLLECT_NETHER_RESOURCES);
    const observedAt = "2026-01-01T00:00:00.000Z";
    const portal = blockObservation(
      { x: 0, y: 64, z: 0, dimension: "minecraft:the_nether" },
      { blockId: "minecraft:nether_portal", diggable: false, solid: false, observedAt },
    );
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        memory: {
          ...base.memory,
          portals: [
            {
              key: "portal:minecraft:the_nether:0:64:0",
              value: portal,
              observedAt,
              confidence: 0.25,
            },
          ],
        },
      },
      observation: observation({
        dimension: "minecraft:the_nether",
        counts: {
          "minecraft:golden_boots": 1,
          "minecraft:cooked_beef": 8,
          "minecraft:stone_sword": 1,
          "minecraft:iron_ingot": 6,
          "minecraft:stick": 1,
        },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({ type: "return-through-portal" });
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
    // The search only ruled out the area it covered.
    expect(
      decideBeatGameAction({
        checkpoint: exhausted,
        observation: {
          ...prepared,
          player: {
            ...prepared.player,
            position: { ...prepared.player.position, x: prepared.player.position.x + 200 },
          },
        },
        strategy: defaultBeatGameStrategy,
      }),
    ).toMatchObject({ action: "satisfy:bed" });
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
          "minecraft:iron_chestplate": 1,
          "minecraft:iron_helmet": 1,
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
      "minecraft:iron_chestplate": 1,
      "minecraft:iron_helmet": 1,
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

  it("goes back to the Nether on a full stomach with a smaller food stock", () => {
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
      // As in a live run: animals near spawn were gone, and over two hours
      // the bot ate what it caught and never kept more than four.
      observation: observation({
        food: 20,
        counts: {
          "minecraft:cooked_beef": 3,
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
      observation: observation({
        counts: {
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
          "minecraft:cooked_beef": 4,
          "minecraft:water_bucket": 1,
          "minecraft:shield": 1,
        },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({ type: "search-stronghold" });
  });

  it("hunts for food on the surface but not at the stronghold depth", () => {
    const base = checkpoint(BeatGamePhase.LOCATE_STRONGHOLD);
    const decideAt = (y: number, food: Readonly<Record<string, number>>) =>
      decideBeatGameAction({
        checkpoint: {
          ...base,
          memory: {
            ...base.memory,
            strongholdEstimate: { x: 232, y: 30, z: -501, dimension: "minecraft:overworld" },
          },
        },
        observation: observation({
          counts: {
            "minecraft:stone_sword": 1,
            "minecraft:stone_pickaxe": 1,
            "minecraft:water_bucket": 1,
            "minecraft:shield": 1,
            "minecraft:ender_eye": 11,
            "minecraft:ender_pearl": 1,
            "minecraft:blaze_powder": 1,
            ...food,
          },
          food: 16,
          position: { x: 235, y, z: -497, dimension: "minecraft:overworld" },
        }),
        strategy: fastBeatGameStrategy,
      });

    expect(decideAt(80, {})).toMatchObject({
      type: "satisfy-requirement",
      requirement: { key: "food-supply" },
    });
    // As in the live run: two meals left at the bottom of the dig, where
    // skeletons shot the bot while it looked for animals in the caves.
    expect(decideAt(38, { "minecraft:cooked_beef": 1, "minecraft:chicken": 1 })).toMatchObject({
      type: "search-stronghold",
    });
  });

  it("throws one of its eyes to relocate a dropped stronghold estimate", () => {
    const base = checkpoint(BeatGamePhase.LOCATE_STRONGHOLD);
    // As in the live run: eleven eyes, throws on record, and no estimate
    // after the old one was dropped at an empty spot.
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        memory: {
          ...base.memory,
          eyeSamples: [
            {
              origin: { x: 465, y: 52, z: -207, dimension: "minecraft:overworld" },
              direction: { x: -0.484, z: -0.875 },
              observedAt: "2026-01-01T00:00:00.000Z",
              confidence: 1,
            },
          ],
        },
      },
      observation: observation({
        counts: {
          "minecraft:stone_sword": 1,
          "minecraft:stone_pickaxe": 1,
          "minecraft:water_bucket": 1,
          "minecraft:shield": 1,
          "minecraft:cooked_beef": 8,
          "minecraft:ender_eye": 11,
        },
        position: { x: -232, y: 83, z: -1482, dimension: "minecraft:overworld" },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({ type: "throw-eye" });
  });

  it("replaces a worn-out pickaxe before digging down to the stronghold", () => {
    // As in the live run: the search resumed with no pickaxe and ended the run.
    const base = checkpoint(BeatGamePhase.LOCATE_STRONGHOLD);
    const decision = decideBeatGameAction({
      checkpoint: {
        ...base,
        memory: {
          ...base.memory,
          strongholdEstimate: { x: 232, y: 53, z: -501, dimension: "minecraft:overworld" },
        },
      },
      observation: observation({
        counts: {
          "minecraft:stone_sword": 1,
          "minecraft:ender_eye": 11,
          "minecraft:cobblestone": 64,
          "minecraft:oak_log": 6,
        },
      }),
      strategy: defaultBeatGameStrategy,
    });

    expect(decision).toMatchObject({
      type: "satisfy-requirement",
      requirement: { key: "pickaxe" },
    });
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
