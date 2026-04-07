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

import com.google.gson.JsonElement;
import com.google.protobuf.util.Timestamps;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.api.SessionLifecycle;
import com.soulfiremc.server.database.AuditLogType;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.settings.lib.InstanceSettingsImpl;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.util.SFHelpers;
import com.soulfiremc.server.util.SocketAddressHelper;
import com.soulfiremc.server.util.structs.GsonInstance;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public final class InstanceServiceImpl extends InstanceServiceGrpc.InstanceServiceImplBase {
  private final SoulFireServer soulFireServer;

  private Collection<InstancePermissionState> getInstancePermissions(UUID instanceId) {
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    return Arrays.stream(InstancePermission.values())
      .filter(permission -> permission != InstancePermission.UNRECOGNIZED)
      .map(permission -> InstancePermissionState.newBuilder()
        .setInstancePermission(permission)
        .setGranted(user.hasPermission(PermissionContext.instance(permission, instanceId)))
        .build())
      .toList();
  }

  private InstanceSettingsImpl.Stem parseSettings(String settingsJson) {
    return InstanceSettingsImpl.Stem.deserialize(GsonInstance.GSON.fromJson(settingsJson, JsonElement.class));
  }

  private String serializeSettings(InstanceSettingsImpl.Stem stem) {
    return GsonInstance.GSON.toJson(stem.serializeToTree());
  }

  @Override
  public void createInstance(InstanceCreateRequest request, StreamObserver<InstanceCreateResponse> responseObserver) {
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.global(GlobalPermission.CREATE_INSTANCE));

    try {
      var id = soulFireServer.createInstance(request.getFriendlyName(), ServerRPCConstants.USER_CONTEXT_KEY.get());
      responseObserver.onNext(InstanceCreateResponse.newBuilder().setId(id.toString()).build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error creating instance", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void deleteInstance(InstanceDeleteRequest request, StreamObserver<InstanceDeleteResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.DELETE_INSTANCE, instanceId));

    try {
      var optionalDeletion = soulFireServer.deleteInstance(instanceId);
      if (optionalDeletion.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      optionalDeletion.get().join();
      responseObserver.onNext(InstanceDeleteResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error deleting instance", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void listInstances(InstanceListRequest request, StreamObserver<InstanceListResponse> responseObserver) {
    try {
      var dsl = soulFireServer.dsl();
      var records = dsl.selectFrom(Tables.INSTANCES).fetch();

      responseObserver.onNext(InstanceListResponse.newBuilder()
        .addAllInstances(records.stream()
          .filter(record -> {
            var id = UUID.fromString(record.getId());
            return ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermission(PermissionContext.instance(InstancePermission.READ_INSTANCE, id));
          })
          .map(record -> {
            var id = UUID.fromString(record.getId());
            return InstanceListResponse.Instance.newBuilder()
              .setId(record.getId())
              .setFriendlyName(record.getFriendlyName())
              .setIcon(record.getIcon())
              .setState(SessionLifecycle.valueOf(record.getSessionLifecycle()).toProto())
              .addAllInstancePermissions(getInstancePermissions(id))
              .build();
          })
          .toList())
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error listing instance", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getInstanceInfo(InstanceInfoRequest request, StreamObserver<InstanceInfoResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_INSTANCE, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      var record = dsl.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
      if (record == null) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var lastModified = record.getUpdatedAt().toInstant(ZoneOffset.UTC);
      var lastModifiedProto = Timestamps.fromMillis(lastModified.toEpochMilli());

      // Check if-modified-since
      if (request.hasIfModifiedSince()) {
        var ifModifiedSince = Instant.ofEpochSecond(
          request.getIfModifiedSince().getSeconds(),
          request.getIfModifiedSince().getNanos()
        );
        if (!lastModified.isAfter(ifModifiedSince)) {
          // Not modified since the given timestamp
          responseObserver.onNext(InstanceInfoResponse.newBuilder()
            .setNotModified(InstanceNotModified.newBuilder()
              .setLastModified(lastModifiedProto)
              .build())
            .build());
          responseObserver.onCompleted();
          return;
        }
      }

      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      var registry = instance.instanceSettingsPageRegistry();
      var settings = parseSettings(record.getSettings());
      responseObserver.onNext(InstanceInfoResponse.newBuilder()
        .setInfo(InstanceInfo.newBuilder()
          .setFriendlyName(record.getFriendlyName())
          .setIcon(record.getIcon())
          .setConfig(settings.toProto())
          .setState(SessionLifecycle.valueOf(record.getSessionLifecycle()).toProto())
          .addAllInstancePermissions(getInstancePermissions(instanceId))
          .addAllSettingsDefinitions(registry.exportSettingsDefinitions())
          .addAllInstanceSettings(registry.exportSettingsPages())
          .addAllPlugins(registry.exportRegisteredPlugins())
          .setLastModified(lastModifiedProto)
          .build())
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting instance info", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void updateInstanceMeta(InstanceUpdateMetaRequest request, StreamObserver<InstanceUpdateMetaResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_META, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var updateStep = switch (request.getMetaCase()) {
          case FRIENDLY_NAME -> ctx.update(Tables.INSTANCES).set(Tables.INSTANCES.FRIENDLY_NAME, request.getFriendlyName());
          case ICON -> ctx.update(Tables.INSTANCES).set(Tables.INSTANCES.ICON, request.getIcon());
          case META_NOT_SET -> throw new IllegalStateException("Unknown meta type");
        };

        updateStep
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceUpdateMetaResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error updating instance meta", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void updateInstanceConfig(InstanceUpdateConfigRequest request, StreamObserver<InstanceUpdateConfigResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var newSettings = InstanceSettingsImpl.Stem.fromProto(request.getConfig());

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(newSettings))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceUpdateConfigResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error updating instance config", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void updateInstanceConfigEntry(InstanceUpdateConfigEntryRequest request, StreamObserver<InstanceUpdateConfigEntryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newSettings = SettingsSource.Stem.withUpdatedEntry(
          currentSettings.settings(),
          request.getNamespace(),
          request.getKey(),
          SettingsSource.Stem.valueToJsonElement(request.getValue())
        );
        var updatedStem = currentSettings.withSettings(newSettings);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceUpdateConfigEntryResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error updating instance config entry", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void addInstanceAccount(InstanceAddAccountRequest request, StreamObserver<InstanceAddAccountResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newAccounts = new ArrayList<>(currentSettings.accounts());
        newAccounts.add(MinecraftAccount.fromProto(request.getAccount()));
        var updatedStem = currentSettings.withAccounts(newAccounts);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceAddAccountResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error adding instance account", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void removeInstanceAccount(InstanceRemoveAccountRequest request, StreamObserver<InstanceRemoveAccountResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    var profileId = UUID.fromString(request.getProfileId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newAccounts = currentSettings.accounts().stream()
          .filter(account -> !account.profileId().equals(profileId))
          .toList();
        var updatedStem = currentSettings.withAccounts(newAccounts);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceRemoveAccountResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error removing instance account", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void updateInstanceAccount(InstanceUpdateAccountRequest request, StreamObserver<InstanceUpdateAccountResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var updatedAccount = MinecraftAccount.fromProto(request.getAccount());
        var currentSettings = parseSettings(record.getSettings());
        var newAccounts = currentSettings.accounts().stream()
          .map(account -> account.profileId().equals(updatedAccount.profileId()) ? updatedAccount : account)
          .toList();
        var updatedStem = currentSettings.withAccounts(newAccounts);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceUpdateAccountResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error updating instance account", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void addInstanceAccountsBatch(InstanceAddAccountsBatchRequest request, StreamObserver<InstanceAddAccountsBatchResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newAccounts = new ArrayList<>(currentSettings.accounts());
        for (var accountProto : request.getAccountsList()) {
          newAccounts.add(MinecraftAccount.fromProto(accountProto));
        }
        var updatedStem = currentSettings.withAccounts(newAccounts);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceAddAccountsBatchResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error adding instance accounts batch", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void removeInstanceAccountsBatch(InstanceRemoveAccountsBatchRequest request, StreamObserver<InstanceRemoveAccountsBatchResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var profileIds = request.getProfileIdsList().stream()
        .map(UUID::fromString)
        .collect(Collectors.toSet());

      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newAccounts = currentSettings.accounts().stream()
          .filter(account -> !profileIds.contains(account.profileId()))
          .toList();
        var updatedStem = currentSettings.withAccounts(newAccounts);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceRemoveAccountsBatchResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error removing instance accounts batch", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void addInstanceProxy(InstanceAddProxyRequest request, StreamObserver<InstanceAddProxyResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newProxies = new ArrayList<>(currentSettings.proxies());
        newProxies.add(SFProxy.fromProto(request.getProxy()));
        var updatedStem = currentSettings.withProxies(newProxies);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceAddProxyResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error adding instance proxy", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void removeInstanceProxy(InstanceRemoveProxyRequest request, StreamObserver<InstanceRemoveProxyResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    var index = request.getIndex();
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newProxies = new ArrayList<>(currentSettings.proxies());
        if (index < 0 || index >= newProxies.size()) {
          throw Status.INVALID_ARGUMENT.withDescription("Proxy index '%d' out of bounds".formatted(index)).asRuntimeException();
        }
        newProxies.remove(index);
        var updatedStem = currentSettings.withProxies(newProxies);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceRemoveProxyResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error removing instance proxy", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void updateInstanceProxy(InstanceUpdateProxyRequest request, StreamObserver<InstanceUpdateProxyResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    var index = request.getIndex();
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newProxies = new ArrayList<>(currentSettings.proxies());
        if (index < 0 || index >= newProxies.size()) {
          throw Status.INVALID_ARGUMENT.withDescription("Proxy index '%d' out of bounds".formatted(index)).asRuntimeException();
        }
        newProxies.set(index, SFProxy.fromProto(request.getProxy()));
        var updatedStem = currentSettings.withProxies(newProxies);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceUpdateProxyResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error updating instance proxy", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void addInstanceProxiesBatch(InstanceAddProxiesBatchRequest request, StreamObserver<InstanceAddProxiesBatchResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newProxies = new ArrayList<>(currentSettings.proxies());
        for (var proxyProto : request.getProxiesList()) {
          newProxies.add(SFProxy.fromProto(proxyProto));
        }
        var updatedStem = currentSettings.withProxies(newProxies);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceAddProxiesBatchResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error adding instance proxies batch", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void removeInstanceProxiesBatch(InstanceRemoveProxiesBatchRequest request, StreamObserver<InstanceRemoveProxiesBatchResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var addressesToRemove = new HashSet<>(request.getAddressesList());

      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newProxies = currentSettings.proxies().stream()
          .filter(proxy -> !addressesToRemove.contains(SocketAddressHelper.serialize(proxy.address())))
          .toList();
        var updatedStem = currentSettings.withProxies(newProxies);

        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      soulFireServer.getInstance(instanceId).ifPresent(InstanceManager::invalidateSettingsCache);
      responseObserver.onNext(InstanceRemoveProxiesBatchResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error removing instance proxies batch", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void changeInstanceState(InstanceStateChangeRequest request, StreamObserver<InstanceStateChangeResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.CHANGE_INSTANCE_STATE, instanceId));

    try {
      var optionalInstance = soulFireServer.getInstance(instanceId);
      if (optionalInstance.isEmpty()) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var instance = optionalInstance.get();
      instance.switchToState(ServerRPCConstants.USER_CONTEXT_KEY.get(), SessionLifecycle.fromProto(request.getState())).join();
      responseObserver.onNext(InstanceStateChangeResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error changing instance state", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getAuditLog(InstanceAuditLogRequest request, StreamObserver<InstanceAuditLogResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_INSTANCE_AUDIT_LOGS, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      var instanceExists = dsl.fetchExists(Tables.INSTANCES, Tables.INSTANCES.ID.eq(instanceId.toString()));
      if (!instanceExists) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var auditLogs = dsl.selectFrom(Tables.INSTANCE_AUDIT_LOGS)
        .where(Tables.INSTANCE_AUDIT_LOGS.INSTANCE_ID.eq(instanceId.toString()))
        .orderBy(Tables.INSTANCE_AUDIT_LOGS.CREATED_AT.desc())
        .fetch();

      var responseBuilder = InstanceAuditLogResponse.newBuilder();
      for (var auditLog : auditLogs) {
        var userRecord = dsl.selectFrom(Tables.USERS)
          .where(Tables.USERS.ID.eq(auditLog.getUserId()))
          .fetchOne();

        var userBuilder = InstanceUser.newBuilder()
          .setId(auditLog.getUserId());
        if (userRecord != null) {
          userBuilder.setUsername(userRecord.getUsername())
            .setEmail(userRecord.getEmail());
        }

        var auditLogType = AuditLogType.valueOf(auditLog.getType());
        responseBuilder.addEntry(InstanceAuditLogResponse.AuditLogEntry.newBuilder()
          .setId(auditLog.getId())
          .setUser(userBuilder.build())
          .setType(switch (auditLogType) {
            case EXECUTE_COMMAND -> InstanceAuditLogResponse.AuditLogEntryType.EXECUTE_COMMAND;
            case START_SESSION -> InstanceAuditLogResponse.AuditLogEntryType.START_SESSION;
            case PAUSE_SESSION -> InstanceAuditLogResponse.AuditLogEntryType.PAUSE_SESSION;
            case RESUME_SESSION -> InstanceAuditLogResponse.AuditLogEntryType.RESUME_SESSION;
            case STOP_SESSION -> InstanceAuditLogResponse.AuditLogEntryType.STOP_SESSION;
            case AUTOMATION_START -> InstanceAuditLogResponse.AuditLogEntryType.AUTOMATION_START;
            case AUTOMATION_PAUSE -> InstanceAuditLogResponse.AuditLogEntryType.AUTOMATION_PAUSE;
            case AUTOMATION_RESUME -> InstanceAuditLogResponse.AuditLogEntryType.AUTOMATION_RESUME;
            case AUTOMATION_STOP -> InstanceAuditLogResponse.AuditLogEntryType.AUTOMATION_STOP;
            case AUTOMATION_UPDATE_SETTINGS -> InstanceAuditLogResponse.AuditLogEntryType.AUTOMATION_UPDATE_SETTINGS;
            case AUTOMATION_APPLY_PRESET -> InstanceAuditLogResponse.AuditLogEntryType.AUTOMATION_APPLY_PRESET;
          })
          .setTimestamp(Timestamps.fromMillis(auditLog.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()))
          .setData(auditLog.getData() != null ? auditLog.getData() : "")
          .build());
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting audit logs", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getAccountMetadata(GetAccountMetadataRequest request, StreamObserver<GetAccountMetadataResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var accountId = UUID.fromString(request.getAccountId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      var record = dsl.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
      if (record == null) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var settings = parseSettings(record.getSettings());
      var account = settings.accounts().stream()
        .filter(a -> a.profileId().equals(accountId))
        .findFirst()
        .orElseThrow(() ->
          Status.NOT_FOUND.withDescription("Account '%s' not found in instance '%s'".formatted(accountId, instanceId)).asRuntimeException());

      responseObserver.onNext(GetAccountMetadataResponse.newBuilder()
        .addAllMetadata(SettingsSource.Stem.mapToSettingsNamespaceProto(account.persistentMetadata()))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting account metadata", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void setAccountMetadataEntry(SetAccountMetadataEntryRequest request, StreamObserver<SetAccountMetadataEntryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var accountId = UUID.fromString(request.getAccountId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var jsonValue = SettingsSource.Stem.valueToJsonElement(request.getValue());

      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newAccounts = currentSettings.accounts().stream()
          .map(account -> {
            if (account.profileId().equals(accountId)) {
              var newMetadata = SettingsSource.Stem.withUpdatedEntry(
                account.persistentMetadata(),
                request.getNamespace(),
                request.getKey(),
                jsonValue);
              return account.withPersistentMetadata(newMetadata);
            }
            return account;
          })
          .toList();

        var updatedStem = currentSettings.withAccounts(newAccounts);
        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      // Also update the live bot's persistent metadata if it's connected
      soulFireServer.getInstance(instanceId).ifPresent(instance -> {
        instance.invalidateSettingsCache();
        var bot = instance.botConnections().get(accountId);
        if (bot != null) {
          bot.persistentMetadata().set(request.getNamespace(), request.getKey(), jsonValue);
          // Mark clean since we just persisted it
          bot.persistentMetadata().markClean();
        }
      });

      responseObserver.onNext(SetAccountMetadataEntryResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting account metadata entry", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void deleteAccountMetadataEntry(DeleteAccountMetadataEntryRequest request, StreamObserver<DeleteAccountMetadataEntryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var accountId = UUID.fromString(request.getAccountId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newAccounts = currentSettings.accounts().stream()
          .map(account -> {
            if (account.profileId().equals(accountId)) {
              var newMetadata = SettingsSource.Stem.withDeletedEntry(
                account.persistentMetadata(),
                request.getNamespace(),
                request.getKey());
              return account.withPersistentMetadata(newMetadata);
            }
            return account;
          })
          .toList();

        var updatedStem = currentSettings.withAccounts(newAccounts);
        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      // Also update the live bot's persistent metadata if it's connected
      soulFireServer.getInstance(instanceId).ifPresent(instance -> {
        instance.invalidateSettingsCache();
        var bot = instance.botConnections().get(accountId);
        if (bot != null) {
          bot.persistentMetadata().remove(request.getNamespace(), request.getKey());
          // Mark clean since we just persisted it
          bot.persistentMetadata().markClean();
        }
      });

      responseObserver.onNext(DeleteAccountMetadataEntryResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error deleting account metadata entry", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getInstanceMetadata(GetInstanceMetadataRequest request, StreamObserver<GetInstanceMetadataResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_INSTANCE, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      var record = dsl.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
      if (record == null) {
        throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
      }

      var settings = parseSettings(record.getSettings());

      responseObserver.onNext(GetInstanceMetadataResponse.newBuilder()
        .addAllMetadata(SettingsSource.Stem.mapToSettingsNamespaceProto(settings.persistentMetadata()))
        .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting instance metadata", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void setInstanceMetadataEntry(SetInstanceMetadataEntryRequest request, StreamObserver<SetInstanceMetadataEntryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_META, instanceId));

    try {
      var jsonValue = SettingsSource.Stem.valueToJsonElement(request.getValue());

      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newMetadata = SettingsSource.Stem.withUpdatedEntry(
          currentSettings.persistentMetadata(),
          request.getNamespace(),
          request.getKey(),
          jsonValue);

        var updatedStem = currentSettings.withPersistentMetadata(newMetadata);
        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      // Also update the live instance's persistent metadata if loaded
      soulFireServer.getInstance(instanceId).ifPresent(instance -> {
        instance.invalidateSettingsCache();
        instance.persistentMetadata().set(request.getNamespace(), request.getKey(), jsonValue);
        instance.persistentMetadata().markClean();
      });

      responseObserver.onNext(SetInstanceMetadataEntryResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error setting instance metadata entry", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void deleteInstanceMetadataEntry(DeleteInstanceMetadataEntryRequest request, StreamObserver<DeleteInstanceMetadataEntryResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_META, instanceId));

    try {
      var dsl = soulFireServer.dsl();
      dsl.transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId.toString())).fetchOne();
        if (record == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var currentSettings = parseSettings(record.getSettings());
        var newMetadata = SettingsSource.Stem.withDeletedEntry(
          currentSettings.persistentMetadata(),
          request.getNamespace(),
          request.getKey());

        var updatedStem = currentSettings.withPersistentMetadata(newMetadata);
        ctx.update(Tables.INSTANCES)
          .set(Tables.INSTANCES.SETTINGS, serializeSettings(updatedStem))
          .set(Tables.INSTANCES.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .execute();
      });

      // Also update the live instance's persistent metadata if loaded
      soulFireServer.getInstance(instanceId).ifPresent(instance -> {
        instance.invalidateSettingsCache();
        instance.persistentMetadata().remove(request.getNamespace(), request.getKey());
        instance.persistentMetadata().markClean();
      });

      responseObserver.onNext(DeleteInstanceMetadataEntryResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error deleting instance metadata entry", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }
}
