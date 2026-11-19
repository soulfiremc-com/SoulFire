import { create } from "@bufbuild/protobuf";
import { anyIs, anyPack, anyUnpack } from "@bufbuild/protobuf/wkt";
import { createClient, createRouterTransport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { describe, expect, it } from "vitest";

import { SoulFireBot } from "../src/client.js";
import { BotLiveService } from "../src/generated/soulfire/bot_live_pb.js";
import { BotService } from "../src/generated/soulfire/bot_pb.js";
import { EntityCategory } from "../src/generated/soulfire/domain_pb.js";
import {
  BrewTaskSchema,
  CraftTaskSchema,
  SmeltTaskSchema,
  VillagerTradeTaskSchema,
} from "../src/generated/soulfire/recipe_pb.js";
import {
  AttackEntityTaskSchema,
  AttackNearestTaskSchema,
  AutoArmorTaskSchema,
  AutoEatTaskSchema,
  AutoRespawnTaskSchema,
  AutoTotemTaskSchema,
  BotTaskDisconnectPolicy,
  BotTaskEventSchema,
  BotTaskSchema,
  BotTaskService,
  BotTaskStatus,
  BreedTaskSchema,
  BuildMirror,
  BuildRotation,
  BuildTaskSchema,
  CollectBlocksTaskSchema,
  ContainerTransferDirection,
  ContainerTransferTaskSchema,
  ExcavateTaskSchema,
  ExploreTaskSchema,
  FarmTaskSchema,
  FishTaskSchema,
  FleeTaskSchema,
  FollowEntityTaskSchema,
  GoToTaskResultSchema,
  GoToTaskSchema,
  GuardTaskSchema,
  MaintainLoadoutTaskSchema,
  RangedAttackTaskSchema,
  SleepTaskSchema,
  type StartBotTaskRequest,
} from "../src/generated/soulfire/task_pb.js";
import { taskInput } from "./task-input.js";

describe("SoulFireTasks", () => {
  it("starts a typed task and decodes its terminal result", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const taskId = "00000000-0000-0000-0000-000000000010";
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId,
                  instanceId: request.instanceId,
                  botId: request.botId,
                  taskType: request.input?.typeUrl ?? "",
                  status: BotTaskStatus.RUNNING,
                  revision: 2n,
                });
              },
              async *watchBotTask() {
                yield create(BotTaskEventSchema, {
                  sequence: 3n,
                  task: create(BotTaskSchema, {
                    taskId,
                    instanceId: "instance-id",
                    botId: "bot-id",
                    taskType: "type.googleapis.com/soulfire.v1.GoToTask",
                    status: BotTaskStatus.COMPLETED,
                    revision: 3n,
                    result: anyPack(
                      GoToTaskResultSchema,
                      create(GoToTaskResultSchema, {
                        finalPosition: {
                          x: 12,
                          y: 64,
                          z: -4,
                          dimension: "minecraft:overworld",
                        },
                      }),
                    ),
                  }),
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          const task = yield* bot.tasks.start(GoToTaskSchema, {}, GoToTaskResultSchema);
          const result = yield* task.result();
          expect(started).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
          });
          expect(started?.input).toBeDefined();
          expect(anyIs(taskInput(started), GoToTaskSchema)).toBe(true);
          expect(anyUnpack(taskInput(started), GoToTaskSchema)).toBeDefined();
          expect(result.finalPosition).toMatchObject({
            x: 12,
            y: 64,
            z: -4,
            dimension: "minecraft:overworld",
          });
          expect(task.snapshot.status).toBe(BotTaskStatus.COMPLETED);
        }),
      ),
    ));

  it("starts a durable follow task with a connection-scoped entity", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "follow-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.followEntity(
            { networkId: 42, connectionEpoch: "connection-epoch" },
            2.5,
            { targetUnavailableTimeoutSeconds: 7 },
          );
          const input = anyUnpack(taskInput(started), FollowEntityTaskSchema);
          expect(input).toMatchObject({
            target: {
              entityId: 42,
              connectionEpoch: "connection-epoch",
              radius: 2.5,
            },
            targetUnavailableTimeoutSeconds: 7,
          });
        }),
      ),
    ));

  it("starts a durable combat task with path and attack policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "attack-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.attackEntity(
            {
              networkId: 42,
              connectionEpoch: "connection-epoch",
              uuid: "00000000-0000-0000-0000-000000000042",
            },
            {
              attackRange: 3.5,
              sprinting: true,
              maximumAttacks: 4,
              targetUnavailableTimeoutSeconds: 6,
              weapon: { tags: ["minecraft:swords"] },
              restoreSelectedSlot: true,
              useOffhandShield: true,
              path: { allowMining: false, allowPlacing: false },
            },
          );
          const input = anyUnpack(taskInput(started), AttackEntityTaskSchema);
          expect(input).toMatchObject({
            target: {
              networkId: 42,
              connectionEpoch: "connection-epoch",
              uuid: "00000000-0000-0000-0000-000000000042",
            },
            attackRange: 3.5,
            sprinting: true,
            maximumAttacks: 4,
            targetUnavailableTimeoutSeconds: 6,
            selectBestWeapon: true,
            weapon: { tags: ["minecraft:swords"] },
            restoreSelectedSlot: true,
            useOffhandShield: true,
            options: { allowMining: false, allowPlacing: false },
          });
        }),
      ),
    ));

  it("starts trajectory-aware ranged combat with spacing policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "ranged-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.rangedAttack(
            {
              networkId: 42,
              connectionEpoch: "connection-epoch",
              uuid: "00000000-0000-0000-0000-000000000042",
            },
            {
              minimumRange: 10,
              maximumRange: 32,
              maximumShots: 6,
              targetUnavailableTimeoutSeconds: 8,
              weapon: { itemIds: ["minecraft:bow"] },
              bowDrawTicks: 18,
              leadTarget: true,
              compensateGravity: true,
              strafe: true,
              restoreSelectedSlot: true,
              path: { allowMining: false, allowPlacing: false },
            },
          );
          expect(anyUnpack(taskInput(started), RangedAttackTaskSchema)).toMatchObject({
            target: {
              networkId: 42,
              connectionEpoch: "connection-epoch",
              uuid: "00000000-0000-0000-0000-000000000042",
            },
            minimumRange: 10,
            maximumRange: 32,
            maximumShots: 6,
            targetUnavailableTimeoutSeconds: 8,
            weapon: { itemIds: ["minecraft:bow"] },
            bowDrawTicks: 18,
            leadTarget: true,
            compensateGravity: true,
            strafe: true,
            restoreSelectedSlot: true,
            options: { allowMining: false, allowPlacing: false },
          });
        }),
      ),
    ));

  it("starts a server-side nearest-target hunt with a safe selector", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "attack-nearest-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.attackNearest(
            { entityTypes: ["minecraft:zombie"] },
            {
              radius: 48,
              maximumTargets: 3,
              completeWhenNoTarget: false,
              weapon: { tags: ["minecraft:swords"] },
            },
          );
          expect(anyUnpack(taskInput(started), AttackNearestTaskSchema)).toMatchObject({
            selector: { entityTypes: ["minecraft:zombie"] },
            radius: 48,
            maximumTargets: 3,
            completeWhenNoTarget: false,
            selectBestWeapon: true,
            weapon: { tags: ["minecraft:swords"] },
            restoreSelectedSlot: true,
          });
        }),
      ),
    ));

  it("starts a dynamic flee task with an explicit safety radius", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "flee-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.flee(
            { categories: [EntityCategory.HOSTILE] },
            {
              triggerRadius: 8,
              safeDistance: 20,
              safeSeconds: 3,
              completeWhenSafe: true,
              maximumEscapes: 2,
            },
          );
          expect(anyUnpack(taskInput(started), FleeTaskSchema)).toMatchObject({
            threats: { categories: [EntityCategory.HOSTILE] },
            triggerRadius: 8,
            safeDistance: 20,
            safeSeconds: 3,
            completeWhenSafe: true,
            maximumEscapes: 2,
          });
        }),
      ),
    ));

  it("guards positions and protects connection-scoped entities", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "guard-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.guard(
            { x: 10, y: 64, z: -5 },
            { categories: [EntityCategory.HOSTILE] },
            {
              guardRadius: 18,
              maximumPursuitDistance: 30,
              maximumTargets: 4,
              weapon: { tags: ["minecraft:swords"] },
            },
          );
          expect(anyUnpack(taskInput(started), GuardTaskSchema)).toMatchObject({
            subject: {
              case: "position",
              value: { x: 10, y: 64, z: -5 },
            },
            threats: { categories: [EntityCategory.HOSTILE] },
            guardRadius: 18,
            maximumPursuitDistance: 30,
            maximumTargets: 4,
            completeWhenClear: true,
            selectBestWeapon: true,
            weapon: { tags: ["minecraft:swords"] },
            restoreSelectedSlot: true,
          });
          yield* bot.tasks.protect(
            { networkId: 42, connectionEpoch: "connection-epoch" },
            { entityTypes: ["minecraft:zombie"] },
          );
          expect(anyUnpack(taskInput(started), GuardTaskSchema)).toMatchObject({
            subject: {
              case: "entity",
              value: {
                networkId: 42,
                connectionEpoch: "connection-epoch",
              },
            },
            threats: { entityTypes: ["minecraft:zombie"] },
          });
        }),
      ),
    ));

  it("starts a durable sleep task with discovery and retry policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "sleep-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.sleep({
            bed: { x: 10, y: 64, z: -5 },
            searchRadius: 30,
            waitUntilPossible: true,
            retryIntervalTicks: 40,
            path: { allowMining: false, allowPlacing: false },
          });
          expect(anyUnpack(taskInput(started), SleepTaskSchema)).toMatchObject({
            bed: { x: 10, y: 64, z: -5 },
            searchRadius: 30,
            waitUntilPossible: true,
            retryIntervalTicks: 40,
            options: { allowMining: false, allowPlacing: false },
          });
        }),
      ),
    ));

  it("starts server-timed fishing with rod and catch policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "fish-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.fish({
            maximumCatches: 3,
            maximumFailedCasts: 4,
            rod: { itemIds: ["minecraft:fishing_rod"] },
            castTimeoutTicks: 80,
            biteTimeoutTicks: 6000,
            completeWhenNoRod: true,
            restoreSelectedSlot: true,
          });
          expect(anyUnpack(taskInput(started), FishTaskSchema)).toMatchObject({
            maximumCatches: 3,
            maximumFailedCasts: 4,
            rod: { itemIds: ["minecraft:fishing_rod"] },
            castTimeoutTicks: 80,
            biteTimeoutTicks: 6000,
            completeWhenNoRod: true,
            restoreSelectedSlot: true,
          });
        }),
      ),
    ));

  it("starts a durable farm worker with crop and replant policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "farm-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.farm({
            cropIds: ["minecraft:wheat", "minecraft:carrots"],
            center: {
              x: 12,
              y: 64,
              z: -4,
              dimension: "minecraft:overworld",
            },
            radius: 18,
            maximumHarvests: 24,
            replant: true,
            completeWhenNoMatureCrops: false,
            path: { allowMining: false, allowPlacing: false },
            rescanIntervalTicks: 80,
            restoreSelectedSlot: true,
          });
          expect(anyUnpack(taskInput(started), FarmTaskSchema)).toMatchObject({
            cropIds: ["minecraft:wheat", "minecraft:carrots"],
            center: {
              x: 12,
              y: 64,
              z: -4,
              dimension: "minecraft:overworld",
            },
            radius: 18,
            maximumHarvests: 24,
            replant: true,
            completeWhenNoMatureCrops: false,
            options: { allowMining: false, allowPlacing: false },
            rescanIntervalTicks: 80,
            restoreSelectedSlot: true,
          });
        }),
      ),
    ));

  it("starts verified animal breeding with shared food policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "breed-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.breed({
            animals: { entityTypes: ["minecraft:cow"] },
            food: { itemIds: ["minecraft:wheat"] },
            center: {
              x: 20,
              y: 64,
              z: 8,
              dimension: "minecraft:overworld",
            },
            radius: 20,
            maximumPairs: 4,
            completeWhenNoPair: false,
            completeWhenNoFood: true,
            path: { allowMining: false, allowPlacing: false },
            rescanIntervalTicks: 60,
            breedingTimeoutTicks: 120,
            restoreSelectedSlot: true,
          });
          expect(anyUnpack(taskInput(started), BreedTaskSchema)).toMatchObject({
            animals: { entityTypes: ["minecraft:cow"] },
            food: { itemIds: ["minecraft:wheat"] },
            center: {
              x: 20,
              y: 64,
              z: 8,
              dimension: "minecraft:overworld",
            },
            radius: 20,
            maximumPairs: 4,
            completeWhenNoPair: false,
            completeWhenNoFood: true,
            options: { allowMining: false, allowPlacing: false },
            rescanIntervalTicks: 60,
            breedingTimeoutTicks: 120,
            restoreSelectedSlot: true,
          });
        }),
      ),
    ));

  it("starts coordinated exploration with a bounded frontier", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "explore-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.explore({
            origin: {
              x: 0,
              y: 64,
              z: 0,
              dimension: "minecraft:overworld",
            },
            radius: 512,
            waypointSpacing: 64,
            maximumWaypoints: 6,
            path: { allowMining: false, allowPlacing: false },
            returnToOrigin: true,
            purpose: "village-scouting",
          });
          expect(anyUnpack(taskInput(started), ExploreTaskSchema)).toMatchObject({
            origin: {
              x: 0,
              y: 64,
              z: 0,
              dimension: "minecraft:overworld",
            },
            radius: 512,
            waypointSpacing: 64,
            maximumWaypoints: 6,
            options: { allowMining: false, allowPlacing: false },
            returnToOrigin: true,
            purpose: "village-scouting",
          });
        }),
      ),
    ));

  it("starts a pathfinding container withdrawal with exact and partial transfers", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "withdraw-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.withdraw(
            {
              x: 30,
              y: 65,
              z: -12,
              dimension: "minecraft:overworld",
            },
            [
              {
                selector: { itemIds: ["minecraft:bread"] },
                count: 16,
              },
              {
                selector: { tags: ["minecraft:coals"] },
                count: 8,
                allowPartial: true,
              },
            ],
            {
              path: { allowMining: false, allowPlacing: false },
              closeContainer: true,
            },
          );
          expect(anyUnpack(taskInput(started), ContainerTransferTaskSchema)).toMatchObject({
            container: {
              x: 30,
              y: 65,
              z: -12,
              dimension: "minecraft:overworld",
            },
            direction: ContainerTransferDirection.WITHDRAW,
            operations: [
              {
                selector: { itemIds: ["minecraft:bread"] },
                count: 16,
                allowPartial: false,
              },
              {
                selector: { tags: ["minecraft:coals"] },
                count: 8,
                allowPartial: true,
              },
            ],
            options: { allowMining: false, allowPlacing: false },
            closeContainer: true,
          });
        }),
      ),
    ));

  it("starts semantic loadout maintenance with inventory bounds", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "maintain-loadout-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.maintainLoadout(
            { x: 14, y: 64, z: -9, dimension: "minecraft:overworld" },
            [
              {
                selector: { itemIds: ["minecraft:bread"] },
                minimumCount: 8,
                targetCount: 16,
                maximumCount: 24,
              },
              {
                selector: { tags: ["minecraft:arrows"] },
                minimumCount: 32,
                targetCount: 64,
              },
            ],
            {
              checkIntervalTicks: 80,
              maximumRebalances: 5,
              path: { allowPlacing: false },
              closeContainer: true,
            },
          );
          expect(anyUnpack(taskInput(started), MaintainLoadoutTaskSchema)).toMatchObject({
            container: {
              x: 14,
              y: 64,
              z: -9,
              dimension: "minecraft:overworld",
            },
            requirements: [
              {
                selector: { itemIds: ["minecraft:bread"] },
                minimumCount: 8,
                targetCount: 16,
                maximumCount: 24,
              },
              {
                selector: { tags: ["minecraft:arrows"] },
                minimumCount: 32,
                targetCount: 64,
                maximumCount: 0,
              },
            ],
            checkIntervalTicks: 80,
            maximumRebalances: 5,
            closeContainer: true,
          });
        }),
      ),
    ));

  it("starts resource-aware automatic eating with an explicit policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "auto-eat-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.autoEat(["minecraft:bread", "minecraft:cooked_beef"], {
            foodLevel: 12,
            checkIntervalTicks: 10,
            maximumMeals: 3,
            completeWhenNoFood: true,
            restoreSelectedSlot: false,
          });
          const input = anyUnpack(taskInput(started), AutoEatTaskSchema);
          expect(input).toMatchObject({
            foodItemIds: ["minecraft:bread", "minecraft:cooked_beef"],
            foodLevel: 12,
            checkIntervalTicks: 10,
            maximumMeals: 3,
            completeWhenNoFood: true,
            restoreSelectedSlot: false,
          });
        }),
      ),
    ));

  it("starts durable automatic respawning with a bounded policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "auto-respawn-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.autoRespawn({
            respawnDelayTicks: 15,
            maximumRespawns: 2,
          });
          const input = anyUnpack(taskInput(started), AutoRespawnTaskSchema);
          expect(input).toMatchObject({
            respawnDelayTicks: 15,
            maximumRespawns: 2,
          });
        }),
      ),
    ));

  it("starts durable equipment monitors with explicit policies", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const started: StartBotTaskRequest[] = [];
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started.push(request);
                return create(BotTaskSchema, {
                  taskId: `equipment-task-${started.length}`,
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.autoTotem({
            checkIntervalTicks: 8,
            maximumEquips: 2,
            completeWhenNoTotem: true,
            replaceOccupiedOffhand: true,
          });
          yield* bot.tasks.autoArmor({
            checkIntervalTicks: 12,
            maximumEquips: 4,
            completeWhenNoUpgrade: true,
          });
          expect(anyUnpack(taskInput(started[0]), AutoTotemTaskSchema)).toMatchObject({
            checkIntervalTicks: 8,
            maximumEquips: 2,
            completeWhenNoTotem: true,
            replaceOccupiedOffhand: true,
          });
          expect(anyUnpack(taskInput(started[1]), AutoArmorTaskSchema)).toMatchObject({
            checkIntervalTicks: 12,
            maximumEquips: 4,
            completeWhenNoUpgrade: true,
          });
        }),
      ),
    ));

  it("starts durable block collection with selectors and path policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "collect-blocks-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.collectBlocks(["oak_log", "#logs"], {
            count: 6,
            searchRadius: 48,
            avoidSubmergedTargets: true,
            requireLineOfSight: true,
            targetYRange: { minimum: 60, maximum: 96 },
            path: {
              allowMining: true,
              allowPlacing: false,
              avoidFluids: true,
              minimumY: 63,
              maximumY: 96,
            },
          });
          expect(anyUnpack(taskInput(started), CollectBlocksTaskSchema)).toMatchObject({
            blockIds: ["minecraft:oak_log"],
            tags: ["minecraft:logs"],
            count: 6,
            searchRadius: 48,
            avoidSubmergedTargets: true,
            requireLineOfSight: true,
            targetYRange: { minimum: 60, maximum: 96 },
            options: {
              allowMining: true,
              allowPlacing: false,
              avoidFluids: true,
              minimumY: 63,
              maximumY: 96,
            },
          });
        }),
      ),
    ));

  it("starts bounded cuboid excavation with path policy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "excavate-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.excavate(
            { x: 1, y: 62, z: 3, dimension: "minecraft:overworld" },
            { x: 8, y: 65, z: 10, dimension: "minecraft:overworld" },
            {
              maximumBlocks: 128,
              path: { allowPlacing: true, searchTimeoutSeconds: 15 },
            },
          );
          expect(anyUnpack(taskInput(started), ExcavateTaskSchema)).toMatchObject({
            cornerA: { x: 1, y: 62, z: 3, dimension: "minecraft:overworld" },
            cornerB: { x: 8, y: 65, z: 10, dimension: "minecraft:overworld" },
            maximumBlocks: 128,
            options: { allowPlacing: true, searchTimeoutSeconds: 15 },
          });
        }),
      ),
    ));

  it("starts a transformed and partitioned schematic build", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "build-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.build(
            { x: 100, y: 64, z: -20, dimension: "minecraft:overworld" },
            [
              {
                offset: { x: 0, y: 0, z: 0 },
                blockId: "minecraft:oak_stairs",
                properties: { facing: "north", half: "bottom" },
              },
              {
                offset: { x: 1, y: 0, z: 0 },
                blockId: "minecraft:oak_planks",
              },
            ],
            {
              rotation: BuildRotation.CLOCKWISE_90,
              mirror: BuildMirror.X,
              substitutions: {
                "minecraft:oak_planks": ["minecraft:spruce_planks"],
              },
              breakIncorrectBlocks: true,
              partitionIndex: 1,
              partitionCount: 2,
            },
          );
          expect(anyUnpack(taskInput(started), BuildTaskSchema)).toMatchObject({
            origin: {
              x: 100,
              y: 64,
              z: -20,
              dimension: "minecraft:overworld",
            },
            blocks: [
              {
                offset: { x: 0, y: 0, z: 0 },
                blockId: "minecraft:oak_stairs",
                properties: { facing: "north", half: "bottom" },
              },
              {
                offset: { x: 1, y: 0, z: 0 },
                blockId: "minecraft:oak_planks",
              },
            ],
            rotation: BuildRotation.CLOCKWISE_90,
            mirror: BuildMirror.X,
            substitutions: [
              {
                sourceBlockId: "minecraft:oak_planks",
                replacementBlockIds: ["minecraft:spruce_planks"],
              },
            ],
            breakIncorrectBlocks: true,
            partitionIndex: 1,
            partitionCount: 2,
          });
        }),
      ),
    ));

  it("starts durable crafting with an exact operation count and station", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "craft-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.craft("display:42", 3, {
            station: {
              x: 12,
              y: 64,
              z: -4,
              dimension: "minecraft:overworld",
            },
          });
          expect(anyUnpack(taskInput(started), CraftTaskSchema)).toMatchObject({
            recipeId: "display:42",
            count: 3,
            station: {
              x: 12,
              y: 64,
              z: -4,
              dimension: "minecraft:overworld",
            },
          });
        }),
      ),
    ));

  it("starts durable smelting with input, fuel, and station selectors", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "smelt-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.smelt({ itemIds: ["minecraft:raw_iron"] }, 8, {
            fuel: { tags: ["minecraft:coals"] },
            station: {
              x: 12,
              y: 64,
              z: -4,
              dimension: "minecraft:overworld",
            },
          });
          expect(anyUnpack(taskInput(started), SmeltTaskSchema)).toMatchObject({
            input: { itemIds: ["minecraft:raw_iron"] },
            count: 8,
            fuel: { tags: ["minecraft:coals"] },
            station: {
              x: 12,
              y: 64,
              z: -4,
              dimension: "minecraft:overworld",
            },
          });
        }),
      ),
    ));

  it("starts batched brewing with exact input and output policies", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "brew-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.brew(
            { fingerprint: "water-potion" },
            { itemIds: ["minecraft:nether_wart"] },
            3,
            {
              fuel: { itemIds: ["minecraft:blaze_powder"] },
              expectedResult: { fingerprint: "awkward-potion" },
              station: {
                x: 12,
                y: 64,
                z: -4,
                dimension: "minecraft:overworld",
              },
            },
          );
          expect(anyUnpack(taskInput(started), BrewTaskSchema)).toMatchObject({
            input: { fingerprint: "water-potion" },
            ingredient: { itemIds: ["minecraft:nether_wart"] },
            count: 3,
            fuel: { itemIds: ["minecraft:blaze_powder"] },
            expectedResult: { fingerprint: "awkward-potion" },
            station: {
              x: 12,
              y: 64,
              z: -4,
              dimension: "minecraft:overworld",
            },
          });
        }),
      ),
    ));

  it("starts an exact villager trade with stale-offer protection", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                started = request;
                return create(BotTaskSchema, {
                  taskId: "villager-trade-task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          yield* bot.tasks.villagerTrade(4, 3, {
            expectedResult: { itemIds: ["minecraft:ender_pearl"] },
            closeWhenDone: true,
          });
          expect(anyUnpack(taskInput(started), VillagerTradeTaskSchema)).toMatchObject({
            offerIndex: 4,
            count: 3,
            expectedResult: { itemIds: ["minecraft:ender_pearl"] },
            closeWhenDone: true,
          });
        }),
      ),
    ));

  it("attaches run streams to call cancellation by default", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let started: StartBotTaskRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              async *runBotTask(request) {
                started = request;
                yield create(BotTaskEventSchema, {
                  task: create(BotTaskSchema, {
                    taskId: "task-id",
                    instanceId: request.instanceId,
                    botId: request.botId,
                    status: BotTaskStatus.COMPLETED,
                  }),
                });
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(BotTaskService, transport),
          );
          const collected = yield* Stream.runCollect(bot.tasks.run(GoToTaskSchema, {}));
          const updates = Array.from(collected);
          expect(updates).toHaveLength(1);
          expect(started?.disconnectPolicy).toBe(BotTaskDisconnectPolicy.CANCEL_WITH_CALL);
        }),
      ),
    ));
});
