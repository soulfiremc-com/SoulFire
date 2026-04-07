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
package com.soulfiremc.server.grpc;

import com.google.protobuf.util.Timestamps;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.automation.AutomationControlSupport;
import com.soulfiremc.server.automation.AutomationRequirements;
import com.soulfiremc.server.automation.AutomationTeamCoordinator;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.database.AuditLogType;
import com.soulfiremc.server.settings.instance.AutomationSettings;
import com.soulfiremc.server.user.PermissionContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

@Slf4j
@RequiredArgsConstructor
public final class AutomationServiceImpl extends AutomationServiceGrpc.AutomationServiceImplBase {
  private final SoulFireServer soulFireServer;

  private static <T> T callInBotContext(BotConnection botConnection, Callable<T> callable) throws Exception {
    return botConnection.runnableWrapper().wrap(callable).call();
  }

  @Override
  public void getAutomationTeamState(GetAutomationTeamStateRequest request,
                                     StreamObserver<GetAutomationTeamStateResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var instance = requireInstance(instanceId);
      responseObserver.onNext(GetAutomationTeamStateResponse.newBuilder()
        .setState(buildTeamState(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting automation team state", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void getAutomationCoordinationState(GetAutomationCoordinationStateRequest request,
                                             StreamObserver<GetAutomationCoordinationStateResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var instance = requireInstance(instanceId);
      responseObserver.onNext(GetAutomationCoordinationStateResponse.newBuilder()
        .setState(buildCoordinationState(instance, resolveMaxEntries(request.getMaxEntries())))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting automation coordination state", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void getAutomationBotState(GetAutomationBotStateRequest request,
                                    StreamObserver<GetAutomationBotStateResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var botId = parseUuid(request.getBotId(), "bot_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var bot = requireConnectedBot(instance, botId);
      var statuses = indexBotStatuses(instance.automationCoordinator().botStatuses());
      responseObserver.onNext(GetAutomationBotStateResponse.newBuilder()
        .setState(buildBotState(instance, bot, statuses.get(botId)))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting automation bot state", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void getAutomationMemoryState(GetAutomationMemoryStateRequest request,
                                       StreamObserver<GetAutomationMemoryStateResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var botId = parseUuid(request.getBotId(), "bot_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var bot = requireConnectedBot(instance, botId);
      responseObserver.onNext(GetAutomationMemoryStateResponse.newBuilder()
        .setState(buildMemoryState(instance, bot, resolveMaxEntries(request.getMaxEntries())))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting automation memory state", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void startAutomationBeat(AutomationActionRequest request,
                                  StreamObserver<AutomationActionResponse> responseObserver) {
    runBotAction(
      request.getInstanceId(),
      request.getBotIdsList(),
      InstancePermission.INSTANCE_COMMAND_EXECUTION,
      bot -> {
        var started = callInBotContext(bot, () -> bot.automation().startBeatMinecraft());
        if (started) {
          bot.instanceManager().addAuditLog(ServerRPCConstants.USER_CONTEXT_KEY.get(), AuditLogType.AUTOMATION_START, "mode=beat bot=" + bot.accountName());
        }
        return started
          ? successResult(bot, "started beat automation")
          : failureResult(bot.accountProfileId().toString(), bot.accountName(), "automation disabled");
      },
      responseObserver);
  }

  @Override
  public void startAutomationAcquire(StartAutomationAcquireRequest request,
                                     StreamObserver<AutomationActionResponse> responseObserver) {
    final var normalizedTarget = normalizeTarget(request.getTarget());
    validateCount(request.getCount());

    runBotAction(
      request.getInstanceId(),
      request.getBotIdsList(),
      InstancePermission.INSTANCE_COMMAND_EXECUTION,
      bot -> {
        var started = callInBotContext(bot, () -> bot.automation().startAcquire(normalizedTarget, request.getCount()));
        if (started) {
          bot.instanceManager().addAuditLog(
            ServerRPCConstants.USER_CONTEXT_KEY.get(),
            AuditLogType.AUTOMATION_START,
            "mode=get target=%s count=%d bot=%s".formatted(normalizedTarget, request.getCount(), bot.accountName()));
        }
        return started
          ? successResult(bot, "started acquire " + normalizedTarget + " x" + request.getCount())
          : failureResult(bot.accountProfileId().toString(), bot.accountName(), "automation disabled");
      },
      responseObserver);
  }

  @Override
  public void pauseAutomation(AutomationActionRequest request,
                              StreamObserver<AutomationActionResponse> responseObserver) {
    runBotAction(
      request.getInstanceId(),
      request.getBotIdsList(),
      InstancePermission.INSTANCE_COMMAND_EXECUTION,
      bot -> {
        var paused = callInBotContext(bot, () -> bot.automation().pause());
        if (paused) {
          bot.instanceManager().addAuditLog(ServerRPCConstants.USER_CONTEXT_KEY.get(), AuditLogType.AUTOMATION_PAUSE, "bot=" + bot.accountName());
        }
        return paused
          ? successResult(bot, "paused automation")
          : failureResult(bot.accountProfileId().toString(), bot.accountName(), "automation was not running");
      },
      responseObserver);
  }

  @Override
  public void resumeAutomation(AutomationActionRequest request,
                               StreamObserver<AutomationActionResponse> responseObserver) {
    runBotAction(
      request.getInstanceId(),
      request.getBotIdsList(),
      InstancePermission.INSTANCE_COMMAND_EXECUTION,
      bot -> {
        var resumed = callInBotContext(bot, () -> bot.automation().resume());
        if (resumed) {
          bot.instanceManager().addAuditLog(ServerRPCConstants.USER_CONTEXT_KEY.get(), AuditLogType.AUTOMATION_RESUME, "bot=" + bot.accountName());
        }
        return resumed
          ? successResult(bot, "resumed automation")
          : failureResult(bot.accountProfileId().toString(), bot.accountName(), "automation was not paused");
      },
      responseObserver);
  }

  @Override
  public void stopAutomation(AutomationActionRequest request,
                             StreamObserver<AutomationActionResponse> responseObserver) {
    runBotAction(
      request.getInstanceId(),
      request.getBotIdsList(),
      InstancePermission.INSTANCE_COMMAND_EXECUTION,
      bot -> {
        callInBotContext(bot, () -> {
          bot.automation().stop();
          return null;
        });
        bot.instanceManager().addAuditLog(ServerRPCConstants.USER_CONTEXT_KEY.get(), AuditLogType.AUTOMATION_STOP, "bot=" + bot.accountName());
        return successResult(bot, "stopped automation");
      },
      responseObserver);
  }

  @Override
  public void applyAutomationPreset(ApplyAutomationPresetRequest request,
                                    StreamObserver<ApplyAutomationPresetResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var preset = fromProto(request.getPreset());
      var targetBots = targetConfiguredBots(instance, request.getBotIdsList(), "preset updated");
      AutomationControlSupport.applyPreset(instance, targetBots.validBotIds(), preset);
      instance.addAuditLog(user, AuditLogType.AUTOMATION_APPLY_PRESET, "preset=" + AutomationControlSupport.formatEnumId(preset));

      responseObserver.onNext(ApplyAutomationPresetResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .addAllResults(targetBots.results())
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error applying automation preset", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationCollaboration(SetAutomationCollaborationRequest request,
                                         StreamObserver<SetAutomationCollaborationResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      AutomationControlSupport.applyCollaborationPreset(instance, request.getEnabled());
      var preset = request.getEnabled() ? AutomationSettings.Preset.BALANCED_TEAM : AutomationSettings.Preset.INDEPENDENT_RUNNERS;
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS,
        "team-collaboration=%s preset=%s".formatted(request.getEnabled(), AutomationControlSupport.formatEnumId(preset)));

      responseObserver.onNext(SetAutomationCollaborationResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation collaboration", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationRolePolicy(SetAutomationRolePolicyRequest request,
                                      StreamObserver<SetAutomationRolePolicyResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var rolePolicy = fromProtoRolePolicy(request.getRolePolicy());
      instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, com.soulfiremc.server.util.structs.GsonInstance.GSON.toJsonTree(rolePolicy.name()));
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS, "role-policy=" + AutomationControlSupport.formatEnumId(rolePolicy));

      responseObserver.onNext(SetAutomationRolePolicyResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation role policy", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationSharedStructures(SetAutomationSharedStructuresRequest request,
                                            StreamObserver<SetAutomationSharedStructuresResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      instance.updateInstanceSetting(AutomationSettings.SHARED_STRUCTURE_INTEL, com.soulfiremc.server.util.structs.GsonInstance.GSON.toJsonTree(request.getEnabled()));
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS, "shared-structure-intel=" + request.getEnabled());

      responseObserver.onNext(SetAutomationSharedStructuresResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation shared structures", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationSharedClaims(SetAutomationSharedClaimsRequest request,
                                        StreamObserver<SetAutomationSharedClaimsResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      instance.updateInstanceSetting(AutomationSettings.SHARED_TARGET_CLAIMS, com.soulfiremc.server.util.structs.GsonInstance.GSON.toJsonTree(request.getEnabled()));
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS, "shared-target-claims=" + request.getEnabled());

      responseObserver.onNext(SetAutomationSharedClaimsResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation shared claims", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationSharedEndEntry(SetAutomationSharedEndEntryRequest request,
                                          StreamObserver<SetAutomationSharedEndEntryResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, com.soulfiremc.server.util.structs.GsonInstance.GSON.toJsonTree(request.getEnabled()));
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS, "shared-end-entry=" + request.getEnabled());

      responseObserver.onNext(SetAutomationSharedEndEntryResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation shared End entry", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationMaxEndBots(SetAutomationMaxEndBotsRequest request,
                                      StreamObserver<SetAutomationMaxEndBotsResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));
    validateMaxEndBots(request.getMaxEndBots());

    try {
      var instance = requireInstance(instanceId);
      instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, com.soulfiremc.server.util.structs.GsonInstance.GSON.toJsonTree(request.getMaxEndBots()));
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS, "max-end-bots=" + request.getMaxEndBots());

      responseObserver.onNext(SetAutomationMaxEndBotsResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation max End bots", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationObjectiveOverride(SetAutomationObjectiveOverrideRequest request,
                                             StreamObserver<SetAutomationObjectiveOverrideResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var override = fromProtoObjectiveOverride(request.getObjective());
      instance.updateInstanceSetting(AutomationSettings.OBJECTIVE_OVERRIDE, com.soulfiremc.server.util.structs.GsonInstance.GSON.toJsonTree(override.name()));
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS, "objective-override=" + AutomationControlSupport.formatEnumId(override));

      responseObserver.onNext(SetAutomationObjectiveOverrideResponse.newBuilder()
        .setSettings(buildInstanceSettings(instance))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation objective override", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void setAutomationRoleOverride(SetAutomationRoleOverrideRequest request,
                                        StreamObserver<SetAutomationRoleOverrideResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_BOT_CONFIG, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var override = fromProtoRoleOverride(request.getRole());
      var targetBots = targetConfiguredBots(instance, request.getBotIdsList(), "role override updated");
      for (var botId : targetBots.validBotIds()) {
        instance.updateBotSetting(botId, AutomationSettings.ROLE_OVERRIDE, com.soulfiremc.server.util.structs.GsonInstance.GSON.toJsonTree(override.name()));
      }
      instance.addAuditLog(user, AuditLogType.AUTOMATION_UPDATE_SETTINGS, "role-override=" + AutomationControlSupport.formatEnumId(override));

      responseObserver.onNext(SetAutomationRoleOverrideResponse.newBuilder()
        .addAllResults(targetBots.results())
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting automation role override", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void resetAutomationMemory(ResetAutomationMemoryRequest request,
                                    StreamObserver<ResetAutomationMemoryResponse> responseObserver) {
    runBotAction(
      request.getInstanceId(),
      request.getBotIdsList(),
      InstancePermission.INSTANCE_COMMAND_EXECUTION,
      bot -> {
        callInBotContext(bot, () -> {
          bot.automation().resetMemory();
          return null;
        });
        bot.instanceManager().addAuditLog(ServerRPCConstants.USER_CONTEXT_KEY.get(), AuditLogType.AUTOMATION_RESET_MEMORY, "bot=" + bot.accountName());
        return successResult(bot, "reset automation memory");
      },
      new StreamObserver<>() {
        @Override
        public void onNext(AutomationActionResponse value) {
          responseObserver.onNext(ResetAutomationMemoryResponse.newBuilder()
            .addAllResults(value.getResultsList())
            .build());
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      });
  }

  @Override
  public void resetAutomationCoordinationState(ResetAutomationCoordinationStateRequest request,
                                               StreamObserver<ResetAutomationCoordinationStateResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.INSTANCE_COMMAND_EXECUTION, instanceId));

    try {
      var instance = requireInstance(instanceId);
      instance.automationCoordinator().resetCoordinationState();
      instance.addAuditLog(user, AuditLogType.AUTOMATION_RESET_COORDINATION, "instance=" + instance.id());

      responseObserver.onNext(ResetAutomationCoordinationStateResponse.newBuilder()
        .setState(buildCoordinationState(instance, 8))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error resetting automation coordination state", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void releaseAutomationClaim(ReleaseAutomationClaimRequest request,
                                     StreamObserver<ReleaseAutomationClaimResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.INSTANCE_COMMAND_EXECUTION, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var released = instance.automationCoordinator().releaseClaim(request.getKey());
      instance.addAuditLog(user, AuditLogType.AUTOMATION_RELEASE_CLAIMS,
        "claim-key=%s released=%s".formatted(request.getKey(), released));

      responseObserver.onNext(ReleaseAutomationClaimResponse.newBuilder()
        .setReleased(released)
        .setState(buildCoordinationState(instance, 8))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error releasing automation claim", t);
      throw statusFromThrowable(t);
    }
  }

  @Override
  public void releaseAutomationBotClaims(ReleaseAutomationBotClaimsRequest request,
                                         StreamObserver<ReleaseAutomationBotClaimsResponse> responseObserver) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.INSTANCE_COMMAND_EXECUTION, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var targets = targetConnectedBots(instance, request.getBotIdsList());
      if (targets.results().isEmpty() && targets.validBots().isEmpty()) {
        throw Status.FAILED_PRECONDITION.withDescription("No connected bots matched the request").asRuntimeException();
      }

      var results = new ArrayList<AutomationBotActionResult>();
      results.addAll(targets.results());
      for (var bot : targets.validBots()) {
        var released = instance.automationCoordinator().releaseClaimsOwnedBy(bot.accountProfileId());
        instance.addAuditLog(ServerRPCConstants.USER_CONTEXT_KEY.get(), AuditLogType.AUTOMATION_RELEASE_CLAIMS,
          "bot=%s released=%d".formatted(bot.accountName(), released));
        results.add(successResult(bot, released == 1 ? "released 1 claim" : "released %d claims".formatted(released)));
      }

      responseObserver.onNext(ReleaseAutomationBotClaimsResponse.newBuilder()
        .addAllResults(results)
        .setState(buildCoordinationState(instance, 8))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error releasing automation bot claims", t);
      throw statusFromThrowable(t);
    }
  }

  private void runBotAction(String instanceIdRaw,
                            List<String> botIds,
                            InstancePermission permission,
                            BotAction action,
                            StreamObserver<AutomationActionResponse> responseObserver) {
    var instanceId = parseUuid(instanceIdRaw, "instance_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(permission, instanceId));

    try {
      var instance = requireInstance(instanceId);
      var targets = targetConnectedBots(instance, botIds);
      if (targets.results().isEmpty() && targets.validBots().isEmpty()) {
        throw Status.FAILED_PRECONDITION.withDescription("No connected bots matched the request").asRuntimeException();
      }

      var results = new ArrayList<AutomationBotActionResult>();
      results.addAll(targets.results());
      for (var bot : targets.validBots()) {
        results.add(action.run(bot));
      }

      responseObserver.onNext(AutomationActionResponse.newBuilder().addAllResults(results).build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error processing automation bot action", t);
      throw statusFromThrowable(t);
    }
  }

  private InstanceManager requireInstance(UUID instanceId) {
    return soulFireServer.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException());
  }

  private BotConnection requireConnectedBot(InstanceManager instance, UUID botId) {
    return instance.getConnectedBots().stream()
      .filter(bot -> bot.accountProfileId().equals(botId))
      .findFirst()
      .orElseThrow(() -> Status.NOT_FOUND.withDescription("Bot '%s' is not connected".formatted(botId)).asRuntimeException());
  }

  private AutomationTeamState buildTeamState(InstanceManager instance) throws Exception {
    var coordinator = instance.automationCoordinator();
    var teamSummary = coordinator.teamSummary();
    var botStatuses = indexBotStatuses(coordinator.botStatuses());
    var bots = instance.getConnectedBots().stream()
      .sorted(Comparator.comparing(BotConnection::accountName))
      .map(bot -> {
        try {
          return buildBotState(instance, bot, botStatuses.get(bot.accountProfileId()));
        } catch (Exception e) {
          throw Status.INTERNAL.withDescription("Failed to build automation state for " + bot.accountName()).withCause(e).asRuntimeException();
        }
      })
      .toList();

    return AutomationTeamState.newBuilder()
      .setInstanceId(instance.id().toString())
      .setFriendlyName(instance.friendlyNameCache().get())
      .setSettings(buildInstanceSettings(instance))
      .setObjective(toProto(teamSummary.objective()))
      .setActiveBots(teamSummary.activeBots())
      .addAllQuotas(List.of(
        quota(AutomationRequirements.BLAZE_ROD, teamSummary.blazeRods(), teamSummary.targetBlazeRods()),
        quota(AutomationRequirements.ENDER_PEARL, teamSummary.enderPearls(), teamSummary.targetEnderPearls()),
        quota(AutomationRequirements.ENDER_EYE, teamSummary.enderEyes(), teamSummary.targetEnderEyes()),
        quota(AutomationRequirements.ARROW, teamSummary.arrows(), teamSummary.targetArrows()),
        quota(AutomationRequirements.ANY_BED, teamSummary.beds(), teamSummary.targetBeds())))
      .addAllBots(bots)
      .build();
  }

  private AutomationCoordinationState buildCoordinationState(InstanceManager instance, int maxEntries) {
    var snapshot = instance.automationCoordinator().coordinationSnapshot(maxEntries);
    var builder = AutomationCoordinationState.newBuilder()
      .setInstanceId(instance.id().toString())
      .setFriendlyName(instance.friendlyNameCache().get())
      .setSettings(buildInstanceSettings(instance))
      .setObjective(toProto(snapshot.summary().objective()))
      .setActiveBots(snapshot.summary().activeBots())
      .setSharedBlockCount(snapshot.sharedBlockCount())
      .setClaimCount(snapshot.claimCount())
      .setEyeSampleCount(snapshot.eyeSampleCount());

    builder.addAllSharedCounts(snapshot.sharedCounts().stream()
      .map(count -> AutomationSharedRequirementCount.newBuilder()
        .setRequirementKey(count.requirementKey())
        .setDisplayName(AutomationRequirements.describe(count.requirementKey()))
        .setCurrentCount(count.currentCount())
        .setTargetCount(count.targetCount())
        .build())
      .toList());
    builder.addAllSharedBlocks(snapshot.sharedBlocks().stream()
      .map(block -> AutomationCoordinationSharedBlock.newBuilder()
        .setObserverBotId(block.observerBotId().toString())
        .setObserverAccountName(block.observerAccountName())
        .setDimension(block.dimension().identifier().toString())
        .setX(block.pos().getX())
        .setY(block.pos().getY())
        .setZ(block.pos().getZ())
        .setBlockId(BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString())
        .setLastSeenAt(Timestamps.fromMillis(block.lastSeenMillis()))
        .build())
      .toList());
    builder.addAllClaims(snapshot.claims().stream()
      .map(claim -> {
        var claimBuilder = AutomationCoordinationClaim.newBuilder()
          .setKey(claim.key())
          .setOwnerBotId(claim.ownerBotId().toString())
          .setOwnerAccountName(claim.ownerAccountName())
          .setExpiresAt(Timestamps.fromMillis(claim.expiresAtMillis()));
        if (claim.target() != null) {
          claimBuilder.setTarget(AutomationPosition.newBuilder()
            .setX(claim.target().x)
            .setY(claim.target().y)
            .setZ(claim.target().z)
            .build());
        }
        return claimBuilder.build();
      })
      .toList());
    builder.addAllEyeSamples(snapshot.eyeSamples().stream()
      .map(sample -> AutomationCoordinationEyeSample.newBuilder()
        .setBotId(sample.botId().toString())
        .setAccountName(sample.accountName())
        .setOrigin(AutomationPosition.newBuilder()
          .setX(sample.origin().x)
          .setY(sample.origin().y)
          .setZ(sample.origin().z)
          .build())
        .setDirection(AutomationPosition.newBuilder()
          .setX(sample.direction().x)
          .setY(sample.direction().y)
          .setZ(sample.direction().z)
          .build())
        .setRecordedAt(Timestamps.fromMillis(sample.recordedAtMillis()))
        .build())
      .toList());
    return builder.build();
  }

  private AutomationBotState buildBotState(InstanceManager instance,
                                           BotConnection bot,
                                           AutomationTeamCoordinator.BotStatus teamStatus) throws Exception {
    var runtime = callInBotContext(bot, () -> {
      var snapshot = bot.automation().snapshot();
      var player = bot.minecraft().player;
      var builder = AutomationBotState.newBuilder()
        .setInstanceId(instance.id().toString())
        .setBotId(bot.accountProfileId().toString())
        .setAccountName(bot.accountName())
        .setStatusSummary(bot.automation().status())
        .setGoalMode(toProtoGoalMode(snapshot.mode()))
        .setPaused(snapshot.paused())
        .setSettings(AutomationBotSettings.newBuilder()
          .setEnabled(bot.settingsSource().get(AutomationSettings.ENABLED))
          .setAllowDeathRecovery(bot.settingsSource().get(AutomationSettings.ALLOW_DEATH_RECOVERY))
          .setMemoryScanRadius(bot.settingsSource().get(AutomationSettings.MEMORY_SCAN_RADIUS))
          .setMemoryScanIntervalTicks(bot.settingsSource().get(AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS))
          .setRetreatHealthThreshold(bot.settingsSource().get(AutomationSettings.RETREAT_HEALTH_THRESHOLD))
          .setRetreatFoodThreshold(bot.settingsSource().get(AutomationSettings.RETREAT_FOOD_THRESHOLD))
          .setRoleOverride(toProto(bot.settingsSource().get(AutomationSettings.ROLE_OVERRIDE, AutomationSettings.RoleOverride.class)))
          .build());

      if (snapshot.beatPhase() != null) {
        builder.setBeatPhase(toProtoBeatPhase(snapshot.beatPhase()));
      }
      if (snapshot.currentAction() != null && !snapshot.currentAction().isBlank()) {
        builder.setCurrentAction(snapshot.currentAction());
      }
      if (snapshot.targetRequirementKey() != null) {
        builder.setTarget(AutomationRequirementTarget.newBuilder()
          .setRequirementKey(snapshot.targetRequirementKey())
          .setDisplayName(AutomationRequirements.describe(snapshot.targetRequirementKey()))
          .setCount(snapshot.targetRequirementCount())
          .build());
      }
      builder.addAllQueuedTargets(snapshot.queuedRequirements().stream()
        .map(requirement -> AutomationRequirementTarget.newBuilder()
          .setRequirementKey(requirement.requirementKey())
          .setDisplayName(AutomationRequirements.describe(requirement.requirementKey()))
          .setCount(requirement.count())
          .build())
        .toList());
      if (player != null) {
        builder.setDimension(player.level().dimension().identifier().toString());
        builder.setPosition(AutomationPosition.newBuilder()
          .setX(player.getX())
          .setY(player.getY())
          .setZ(player.getZ())
          .build());
      }

      return builder.build();
    });

    var builder = runtime.toBuilder();
    var resolvedStatus = teamStatus != null
      ? teamStatus
      : new AutomationTeamCoordinator.BotStatus(
      bot.accountProfileId(),
      bot.accountName(),
      null,
      null,
      instance.automationCoordinator().roleFor(bot),
      null,
      instance.automationCoordinator().objectiveFor(bot),
      runtime.getStatusSummary(),
      runtime.hasBeatPhase() ? runtime.getBeatPhase().name() : null,
      0,
      0L,
      0,
      0,
      null);

    builder
      .setTeamRole(toProto(resolvedStatus.role()))
      .setTeamObjective(toProto(resolvedStatus.objective()))
      .setDeathCount(resolvedStatus.deathCount())
      .setTimeoutCount(resolvedStatus.timeoutCount())
      .setRecoveryCount(resolvedStatus.recoveryCount());

    if (!runtime.hasDimension() && resolvedStatus.dimension() != null) {
      builder.setDimension(resolvedStatus.dimension().identifier().toString());
    }
    if (!runtime.hasPosition() && resolvedStatus.position() != null) {
      builder.setPosition(AutomationPosition.newBuilder()
        .setX(resolvedStatus.position().x)
        .setY(resolvedStatus.position().y)
        .setZ(resolvedStatus.position().z)
        .build());
    }
    if (resolvedStatus.lastRecoveryReason() != null && !resolvedStatus.lastRecoveryReason().isBlank()) {
      builder.setLastRecoveryReason(resolvedStatus.lastRecoveryReason());
    }
    if (resolvedStatus.lastProgressMillis() > 0L) {
      builder.setLastProgressAt(Timestamps.fromMillis(resolvedStatus.lastProgressMillis()));
    }
    return builder.build();
  }

  private AutomationMemoryState buildMemoryState(InstanceManager instance,
                                                 BotConnection bot,
                                                 int maxEntries) throws Exception {
    var memory = callInBotContext(bot, () -> bot.automation().memorySnapshot(maxEntries));
    var builder = AutomationMemoryState.newBuilder()
      .setInstanceId(instance.id().toString())
      .setBotId(bot.accountProfileId().toString())
      .setAccountName(bot.accountName())
      .setTick(memory.ticks())
      .setRememberedBlockCount(memory.rememberedBlockCount())
      .setRememberedContainerCount(memory.rememberedContainerCount())
      .setRememberedEntityCount(memory.rememberedEntityCount())
      .setRememberedDroppedItemCount(memory.rememberedDroppedItemCount())
      .setUnreachablePositionCount(memory.unreachablePositionCount());

    builder.addAllBlocks(memory.blocks().stream()
      .map(block -> AutomationMemoryBlock.newBuilder()
        .setX(block.pos().getX())
        .setY(block.pos().getY())
        .setZ(block.pos().getZ())
        .setBlockId(BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString())
        .setLastSeenTick(block.lastSeenTick())
        .build())
      .toList());
    builder.addAllContainers(memory.containers().stream()
      .map(container -> AutomationMemoryContainer.newBuilder()
        .setX(container.pos().getX())
        .setY(container.pos().getY())
        .setZ(container.pos().getZ())
        .setBlockId(BuiltInRegistries.BLOCK.getKey(container.state().getBlock()).toString())
        .setInspected(container.inspected())
        .setDistinctItemKinds(container.distinctItemKinds())
        .setTotalItemCount(container.totalItemCount())
        .setLastSeenTick(container.lastSeenTick())
        .build())
      .toList());
    builder.addAllEntities(memory.entities().stream()
      .map(entity -> AutomationMemoryEntity.newBuilder()
        .setEntityId(entity.uuid().toString())
        .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type()).toString())
        .setPosition(AutomationPosition.newBuilder()
          .setX(entity.position().x)
          .setY(entity.position().y)
          .setZ(entity.position().z)
          .build())
        .setLastSeenTick(entity.lastSeenTick())
        .build())
      .toList());
    builder.addAllDroppedItems(memory.droppedItems().stream()
      .map(item -> AutomationMemoryDroppedItem.newBuilder()
        .setEntityId(item.uuid().toString())
        .setItemId(BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString())
        .setCount(item.stack().getCount())
        .setPosition(AutomationPosition.newBuilder()
          .setX(item.position().x)
          .setY(item.position().y)
          .setZ(item.position().z)
          .build())
        .setLastSeenTick(item.lastSeenTick())
        .build())
      .toList());
    builder.addAllUnreachablePositions(memory.unreachablePositions().stream()
      .map(pos -> AutomationMemoryUnreachablePosition.newBuilder()
        .setX(pos.pos().getX())
        .setY(pos.pos().getY())
        .setZ(pos.pos().getZ())
        .setUntilTick(pos.untilTick())
        .build())
      .toList());
    return builder.build();
  }

  private AutomationInstanceSettings buildInstanceSettings(InstanceManager instance) {
    return AutomationInstanceSettings.newBuilder()
      .setPreset(toProto(instance.settingsSource().get(AutomationSettings.PRESET, AutomationSettings.Preset.class)))
      .setTeamCollaboration(instance.settingsSource().get(AutomationSettings.TEAM_COLLABORATION))
      .setRolePolicy(toProto(instance.settingsSource().get(AutomationSettings.ROLE_POLICY, AutomationSettings.RolePolicy.class)))
      .setSharedEndEntry(instance.settingsSource().get(AutomationSettings.SHARED_END_ENTRY))
      .setMaxEndBots(instance.settingsSource().get(AutomationSettings.MAX_END_BOTS))
      .setSharedStructureIntel(instance.settingsSource().get(AutomationSettings.SHARED_STRUCTURE_INTEL))
      .setSharedTargetClaims(instance.settingsSource().get(AutomationSettings.SHARED_TARGET_CLAIMS))
      .setObjectiveOverride(toProto(instance.settingsSource().get(AutomationSettings.OBJECTIVE_OVERRIDE, AutomationSettings.ObjectiveOverride.class)))
      .build();
  }

  private Map<UUID, AutomationTeamCoordinator.BotStatus> indexBotStatuses(Iterable<AutomationTeamCoordinator.BotStatus> statuses) {
    var indexed = new LinkedHashMap<UUID, AutomationTeamCoordinator.BotStatus>();
    for (var status : statuses) {
      indexed.put(status.botId(), status);
    }
    return indexed;
  }

  private TargetedConnectedBots targetConnectedBots(InstanceManager instance, List<String> botIds) {
    var connected = instance.getConnectedBots().stream()
      .collect(LinkedHashMap<UUID, BotConnection>::new, (map, bot) -> map.put(bot.accountProfileId(), bot), Map::putAll);
    if (botIds.isEmpty()) {
      return new TargetedConnectedBots(new ArrayList<>(connected.values()), List.of());
    }

    var validBots = new ArrayList<BotConnection>();
    var results = new ArrayList<AutomationBotActionResult>();
    for (var botIdRaw : botIds) {
      UUID botId;
      try {
        botId = parseUuid(botIdRaw, "bot_id");
      } catch (RuntimeException e) {
        results.add(failureResult(botIdRaw, "", e.getMessage() == null ? "invalid bot id" : e.getMessage()));
        continue;
      }

      var bot = connected.get(botId);
      if (bot == null) {
        results.add(failureResult(botIdRaw, "", "bot is not connected"));
        continue;
      }
      validBots.add(bot);
    }
    return new TargetedConnectedBots(validBots, results);
  }

  private TargetedConfiguredBots targetConfiguredBots(InstanceManager instance, List<String> botIds, String successMessage) {
    var accounts = instance.settingsSource().accounts();
    if (botIds.isEmpty()) {
      var results = accounts.values().stream()
        .sorted(Comparator.comparing(account -> account.lastKnownName().toLowerCase()))
        .map(account -> successResult(account.profileId().toString(), account.lastKnownName(), successMessage))
        .toList();
      return new TargetedConfiguredBots(accounts.keySet().stream().toList(), results);
    }

    var validBotIds = new ArrayList<UUID>();
    var results = new ArrayList<AutomationBotActionResult>();
    for (var botIdRaw : botIds) {
      UUID botId;
      try {
        botId = parseUuid(botIdRaw, "bot_id");
      } catch (RuntimeException e) {
        results.add(failureResult(botIdRaw, "", e.getMessage() == null ? "invalid bot id" : e.getMessage()));
        continue;
      }

      var account = accounts.get(botId);
      if (account == null) {
        results.add(failureResult(botIdRaw, "", "bot is not configured in this instance"));
        continue;
      }

      validBotIds.add(botId);
      results.add(successResult(botIdRaw, account.lastKnownName(), successMessage));
    }
    return new TargetedConfiguredBots(validBotIds, results);
  }

  private static AutomationResourceQuota quota(String requirementKey, int currentCount, int targetCount) {
    return AutomationResourceQuota.newBuilder()
      .setRequirementKey(requirementKey)
      .setDisplayName(AutomationRequirements.describe(requirementKey))
      .setCurrentCount(currentCount)
      .setTargetCount(targetCount)
      .build();
  }

  private static AutomationBotActionResult successResult(BotConnection bot, String message) {
    return successResult(bot.accountProfileId().toString(), bot.accountName(), message);
  }

  private static AutomationBotActionResult successResult(String botId, String accountName, String message) {
    return AutomationBotActionResult.newBuilder()
      .setBotId(botId)
      .setAccountName(accountName)
      .setSuccess(true)
      .setMessage(message)
      .build();
  }

  private static AutomationBotActionResult failureResult(String botId, String accountName, String message) {
    return AutomationBotActionResult.newBuilder()
      .setBotId(botId)
      .setAccountName(accountName)
      .setSuccess(false)
      .setMessage(message)
      .build();
  }

  private static UUID parseUuid(String raw, String fieldName) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT.withDescription("Invalid %s '%s'".formatted(fieldName, raw)).asRuntimeException();
    }
  }

  private static int resolveMaxEntries(int rawMaxEntries) {
    return rawMaxEntries <= 0 ? 8 : Math.min(rawMaxEntries, 64);
  }

  private static String normalizeTarget(String rawTarget) {
    try {
      return AutomationRequirements.normalize(rawTarget);
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
    }
  }

  private static void validateCount(int count) {
    if (count < 1) {
      throw Status.INVALID_ARGUMENT.withDescription("count must be >= 1").asRuntimeException();
    }
  }

  private static void validateMaxEndBots(int count) {
    if (count < 1 || count > 32) {
      throw Status.INVALID_ARGUMENT.withDescription("max_end_bots must be between 1 and 32").asRuntimeException();
    }
  }

  private static AutomationPreset toProto(AutomationSettings.Preset preset) {
    return switch (preset) {
      case BALANCED_TEAM -> AutomationPreset.AUTOMATION_PRESET_BALANCED_TEAM;
      case INDEPENDENT_RUNNERS -> AutomationPreset.AUTOMATION_PRESET_INDEPENDENT_RUNNERS;
      case CAUTIOUS_TEAM -> AutomationPreset.AUTOMATION_PRESET_CAUTIOUS_TEAM;
    };
  }

  private static AutomationSettings.Preset fromProto(AutomationPreset preset) {
    return switch (preset) {
      case AUTOMATION_PRESET_BALANCED_TEAM -> AutomationSettings.Preset.BALANCED_TEAM;
      case AUTOMATION_PRESET_INDEPENDENT_RUNNERS -> AutomationSettings.Preset.INDEPENDENT_RUNNERS;
      case AUTOMATION_PRESET_CAUTIOUS_TEAM -> AutomationSettings.Preset.CAUTIOUS_TEAM;
      default -> throw Status.INVALID_ARGUMENT.withDescription("Unsupported automation preset: " + preset).asRuntimeException();
    };
  }

  private static AutomationRolePolicy toProto(AutomationSettings.RolePolicy rolePolicy) {
    return switch (rolePolicy) {
      case STATIC_TEAM -> AutomationRolePolicy.AUTOMATION_ROLE_POLICY_STATIC_TEAM;
      case INDEPENDENT -> AutomationRolePolicy.AUTOMATION_ROLE_POLICY_INDEPENDENT;
    };
  }

  private static AutomationSettings.RolePolicy fromProtoRolePolicy(AutomationRolePolicy rolePolicy) {
    return switch (rolePolicy) {
      case AUTOMATION_ROLE_POLICY_STATIC_TEAM -> AutomationSettings.RolePolicy.STATIC_TEAM;
      case AUTOMATION_ROLE_POLICY_INDEPENDENT -> AutomationSettings.RolePolicy.INDEPENDENT;
      default -> throw Status.INVALID_ARGUMENT.withDescription("Unsupported automation role policy: " + rolePolicy).asRuntimeException();
    };
  }

  private static AutomationTeamRole toProto(AutomationSettings.RoleOverride roleOverride) {
    return switch (roleOverride) {
      case AUTO -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_UNSPECIFIED;
      case LEAD -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_LEAD;
      case PORTAL_ENGINEER -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_PORTAL_ENGINEER;
      case NETHER_RUNNER -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_NETHER_RUNNER;
      case STRONGHOLD_SCOUT -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_STRONGHOLD_SCOUT;
      case END_SUPPORT -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_END_SUPPORT;
    };
  }

  private static AutomationTeamRole toProto(AutomationTeamCoordinator.TeamRole role) {
    return switch (role) {
      case LEAD -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_LEAD;
      case PORTAL_ENGINEER -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_PORTAL_ENGINEER;
      case NETHER_RUNNER -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_NETHER_RUNNER;
      case STRONGHOLD_SCOUT -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_STRONGHOLD_SCOUT;
      case END_SUPPORT -> AutomationTeamRole.AUTOMATION_TEAM_ROLE_END_SUPPORT;
    };
  }

  private static AutomationTeamObjective toProto(AutomationTeamCoordinator.TeamObjective objective) {
    return switch (objective) {
      case BOOTSTRAP -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_BOOTSTRAP;
      case NETHER_PROGRESS -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_NETHER_PROGRESS;
      case STRONGHOLD_HUNT -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_STRONGHOLD_HUNT;
      case END_ASSAULT -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_END_ASSAULT;
      case COMPLETE -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_COMPLETE;
    };
  }

  private static AutomationTeamObjective toProto(AutomationSettings.ObjectiveOverride objectiveOverride) {
    return switch (objectiveOverride) {
      case AUTO -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_UNSPECIFIED;
      case BOOTSTRAP -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_BOOTSTRAP;
      case NETHER_PROGRESS -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_NETHER_PROGRESS;
      case STRONGHOLD_HUNT -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_STRONGHOLD_HUNT;
      case END_ASSAULT -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_END_ASSAULT;
      case COMPLETE -> AutomationTeamObjective.AUTOMATION_TEAM_OBJECTIVE_COMPLETE;
    };
  }

  private static AutomationSettings.RoleOverride fromProtoRoleOverride(AutomationTeamRole role) {
    return switch (role) {
      case AUTOMATION_TEAM_ROLE_UNSPECIFIED -> AutomationSettings.RoleOverride.AUTO;
      case AUTOMATION_TEAM_ROLE_LEAD -> AutomationSettings.RoleOverride.LEAD;
      case AUTOMATION_TEAM_ROLE_PORTAL_ENGINEER -> AutomationSettings.RoleOverride.PORTAL_ENGINEER;
      case AUTOMATION_TEAM_ROLE_NETHER_RUNNER -> AutomationSettings.RoleOverride.NETHER_RUNNER;
      case AUTOMATION_TEAM_ROLE_STRONGHOLD_SCOUT -> AutomationSettings.RoleOverride.STRONGHOLD_SCOUT;
      case AUTOMATION_TEAM_ROLE_END_SUPPORT -> AutomationSettings.RoleOverride.END_SUPPORT;
      case UNRECOGNIZED -> throw Status.INVALID_ARGUMENT.withDescription("Unsupported automation team role: " + role).asRuntimeException();
    };
  }

  private static AutomationSettings.ObjectiveOverride fromProtoObjectiveOverride(AutomationTeamObjective objective) {
    return switch (objective) {
      case AUTOMATION_TEAM_OBJECTIVE_UNSPECIFIED -> AutomationSettings.ObjectiveOverride.AUTO;
      case AUTOMATION_TEAM_OBJECTIVE_BOOTSTRAP -> AutomationSettings.ObjectiveOverride.BOOTSTRAP;
      case AUTOMATION_TEAM_OBJECTIVE_NETHER_PROGRESS -> AutomationSettings.ObjectiveOverride.NETHER_PROGRESS;
      case AUTOMATION_TEAM_OBJECTIVE_STRONGHOLD_HUNT -> AutomationSettings.ObjectiveOverride.STRONGHOLD_HUNT;
      case AUTOMATION_TEAM_OBJECTIVE_END_ASSAULT -> AutomationSettings.ObjectiveOverride.END_ASSAULT;
      case AUTOMATION_TEAM_OBJECTIVE_COMPLETE -> AutomationSettings.ObjectiveOverride.COMPLETE;
      case UNRECOGNIZED -> throw Status.INVALID_ARGUMENT.withDescription("Unsupported automation team objective: " + objective).asRuntimeException();
    };
  }

  private static AutomationGoalMode toProtoGoalMode(String mode) {
    return switch (mode) {
      case "IDLE" -> AutomationGoalMode.AUTOMATION_GOAL_MODE_IDLE;
      case "ACQUIRE" -> AutomationGoalMode.AUTOMATION_GOAL_MODE_ACQUIRE;
      case "BEAT" -> AutomationGoalMode.AUTOMATION_GOAL_MODE_BEAT;
      default -> AutomationGoalMode.AUTOMATION_GOAL_MODE_UNSPECIFIED;
    };
  }

  private static AutomationBeatPhase toProtoBeatPhase(String phase) {
    return switch (phase) {
      case "PREPARE_OVERWORLD" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_PREPARE_OVERWORLD;
      case "ENTER_NETHER" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_ENTER_NETHER;
      case "NETHER_COLLECTION" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_NETHER_COLLECTION;
      case "RETURN_TO_OVERWORLD" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_RETURN_TO_OVERWORLD;
      case "STRONGHOLD_SEARCH" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_STRONGHOLD_SEARCH;
      case "ACTIVATE_PORTAL" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_ACTIVATE_PORTAL;
      case "END_FIGHT" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_END_FIGHT;
      case "COMPLETE" -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_COMPLETE;
      default -> AutomationBeatPhase.AUTOMATION_BEAT_PHASE_UNSPECIFIED;
    };
  }

  private static StatusRuntimeException statusFromThrowable(Throwable t) {
    if (t instanceof StatusRuntimeException statusRuntimeException) {
      return statusRuntimeException;
    }
    return Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
  }

  private record TargetedConnectedBots(List<BotConnection> validBots,
                                       List<AutomationBotActionResult> results) {
  }

  private record TargetedConfiguredBots(List<UUID> validBotIds,
                                        List<AutomationBotActionResult> results) {
  }

  @FunctionalInterface
  private interface BotAction {
    AutomationBotActionResult run(BotConnection bot) throws Exception;
  }
}
