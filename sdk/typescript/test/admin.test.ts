import { createRouterTransport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { describe, expect, it } from "vitest";

import { ClientService } from "../src/generated/soulfire/client_pb.js";
import { LogsService } from "../src/generated/soulfire/logs_pb.js";
import { MetricsService } from "../src/generated/soulfire/metrics_pb.js";
import { PluginPermissionScope } from "../src/generated/soulfire/plugin_api_pb.js";
import { ScriptService } from "../src/generated/soulfire/script_pb.js";
import { UserService } from "../src/generated/soulfire/user_pb.js";
import { SoulFire } from "../src/index.js";

describe("SoulFireAdmin", () => {
  it("wraps self, user, metrics, log, and script administration", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = createRouterTransport(({ service }) => {
            service(ClientService, {
              getClientData() {
                return {
                  id: "user-id",
                  username: "operator",
                  email: "operator@example.com",
                };
              },
              generateAPIToken() {
                return { token: "secret-token" };
              },
            });
            service(UserService, {
              listUsers() {
                return {
                  users: [
                    {
                      id: "other-user",
                      username: "builder",
                      email: "builder@example.com",
                    },
                  ],
                };
              },
              listUserPluginPermissionGrants(request) {
                return {
                  grants: [
                    {
                      userId: request.userId,
                      permissionId: "plugin.example.read",
                      scope: PluginPermissionScope.INSTANCE,
                      resourceId: "instance-id",
                      granted: true,
                      active: true,
                    },
                  ],
                };
              },
              setUserPluginPermissionGrant(request) {
                return {
                  userId: request.userId,
                  permissionId: request.permissionId,
                  scope: request.scope,
                  ...(request.resourceId === undefined ? {} : { resourceId: request.resourceId }),
                  granted: request.granted,
                  active: true,
                };
              },
            });
            service(MetricsService, {
              getInstanceMetrics(request) {
                return {
                  snapshots: [],
                  distributions: {
                    dimensionCounts: {
                      "minecraft:overworld": request.instanceId === "instance-id" ? 4 : 0,
                    },
                  },
                };
              },
            });
            service(LogsService, {
              async *subscribe() {
                yield { message: { message: "ready" } };
              },
            });
            service(ScriptService, {
              listScripts(request) {
                return {
                  scripts: [
                    {
                      id: "script-id",
                      instanceId: request.instanceId,
                      name: "Patrol",
                    },
                  ],
                };
              },
              async *dryRunScript(request) {
                yield {
                  event: {
                    case: "scriptStarted",
                    value: {
                      scriptId: request.scriptId,
                    },
                  },
                };
              },
            });
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const client = yield* soulfire.admin.clientData();
          const token = yield* soulfire.admin.generateApiToken();
          const users = yield* soulfire.admin.users();
          const grants = yield* soulfire.admin.userPluginPermissionGrants("other-user");
          const denied = yield* soulfire.admin.setUserPluginPermissionGrant("other-user", {
            permissionId: "plugin.example.read",
            scope: PluginPermissionScope.INSTANCE,
            resourceId: "instance-id",
            granted: false,
          });
          const metrics = yield* soulfire.admin.instanceMetrics("instance-id");
          const collected = yield* Stream.runCollect(soulfire.admin.logs({}));
          const logs = Array.from(collected);
          const scripts = yield* soulfire.admin.scripts("instance-id");
          const collected1 = yield* Stream.runCollect(
            soulfire.admin.dryRunScript("instance-id", {
              scriptId: "script-id",
              triggerNodeId: "trigger-id",
            }),
          );
          const dryRun = Array.from(collected1);
          yield* soulfire.close();
          expect(client.username).toBe("operator");
          expect(token).toBe("secret-token");
          expect(users[0]?.id).toBe("other-user");
          expect(grants[0]).toMatchObject({
            userId: "other-user",
            resourceId: "instance-id",
            granted: true,
          });
          expect(denied).toMatchObject({
            userId: "other-user",
            granted: false,
            active: true,
          });
          expect(metrics.distributions?.dimensionCounts["minecraft:overworld"]).toBe(4);
          expect(logs[0]?.message?.message).toBe("ready");
          expect(scripts[0]).toMatchObject({
            id: "script-id",
            instanceId: "instance-id",
          });
          expect(dryRun[0]?.event).toMatchObject({
            case: "scriptStarted",
            value: {
              scriptId: "script-id",
            },
          });
        }),
      ),
    ));
});
