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

import com.soulfiremc.builddata.BuildData;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.api.PluginApiDefinition;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.settings.server.ServerSettings;
import com.soulfiremc.server.user.PermissionContext;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/// Negotiates SDK compatibility and server capabilities before a client is ready.
public final class SdkServiceImpl extends SdkServiceGrpc.SdkServiceImplBase {
  public static final int API_MAJOR = 1;
  public static final int API_MINOR = 0;
  public static final int API_PATCH = 0;

  private static final List<SdkCapability> CAPABILITIES = List.of(
    capability("core.api.v1"),
    capability("bot.events.v1"),
    capability("bot.actions.v1"),
    capability("bot.actions.interact-block.v1"),
    capability("bot.actions.sleep.v1"),
    capability("bot.pathfinding.v1"),
    capability("bot.pathfinding.v2"),
    capability("bot.pathfinder-plan.v1"),
    capability("bot.protocol.observe.v1"),
    capability("bot.protocol.send.v1"),
    capability("bot.tasks.v1"),
    capability("bot.tasks.follow-entity.v1"),
    capability("bot.tasks.attack-entity.v1"),
    capability("bot.tasks.attack-nearest.v1"),
    capability("bot.tasks.ranged-attack.v1"),
    capability("bot.tasks.flee.v1"),
    capability("bot.tasks.guard.v1"),
    capability("bot.tasks.sleep.v1"),
    capability("bot.tasks.fish.v1"),
    capability("bot.tasks.farm.v1"),
    capability("bot.tasks.breed.v1"),
    capability("bot.tasks.explore.v1"),
    capability("bot.tasks.container-transfer.v1"),
    capability("bot.tasks.maintain-loadout.v1"),
    capability("bot.tasks.auto-eat.v1"),
    capability("bot.tasks.auto-respawn.v1"),
    capability("bot.tasks.auto-totem.v1"),
    capability("bot.tasks.auto-armor.v1"),
    capability("bot.tasks.collect-blocks.v1"),
    capability("bot.tasks.excavate.v1"),
    capability("bot.tasks.build.v1"),
    capability("bot.tasks.craft.v1"),
    capability("bot.tasks.smelt.v1"),
    capability("bot.tasks.brew.v1"),
    capability("bot.tasks.villager-trade.v1"),
    capability("bot.world-queries.v1"),
    capability("bot.camera.capture.v1"),
    capability("bot.camera.stream.v1"),
    capability("bot.camera.world-map.v1"),
    capability("bot.inventory.v1"),
    capability("bot.inventory-recommendations.v1"),
    capability("plugin.rpc.v1"),
    capability("plugin.discovery.v1"),
    capability("plugin.events.v1"),
    capability("plugin.tasks.v1")
  );

  private final SoulFireServer soulFireServer;

  public SdkServiceImpl(SoulFireServer soulFireServer) {
    this.soulFireServer = soulFireServer;
  }

  @Override
  public void handshake(
    SdkHandshakeRequest request,
    StreamObserver<SdkHandshakeResponse> responseObserver
  ) {
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(PermissionContext.global(GlobalPermission.READ_CLIENT_DATA));
    validateCompatibility(request);

    var publicAddress = soulFireServer.settingsSource().get(ServerSettings.PUBLIC_ADDRESS);
    var grantedPermissions = Arrays.stream(GlobalPermission.values())
      .filter(permission -> permission != GlobalPermission.UNRECOGNIZED)
      .filter(permission -> user.hasPermission(PermissionContext.global(permission)))
      .map(GlobalPermission::name)
      .toList();
    var pluginApis = SoulFireAPI.pluginApis().catalog();

    responseObserver.onNext(SdkHandshakeResponse.newBuilder()
      .setServerId(UUID.nameUUIDFromBytes(publicAddress.getBytes(StandardCharsets.UTF_8)).toString())
      .setSoulfireVersion(BuildData.VERSION)
      .setCommitHash(BuildData.COMMIT)
      .setBranchName(BuildData.BRANCH)
      .setApiVersion(currentApiVersion())
      .setNativeMinecraftVersion(ProtocolTranslationImpl.NATIVE_VERSION.getName())
      .addSupportedMinecraftVersions(ProtocolTranslationImpl.NATIVE_VERSION.getName())
      .addAllTransports(List.of(
        SdkTransport.SDK_TRANSPORT_GRPC,
        SdkTransport.SDK_TRANSPORT_GRPC_WEB,
        SdkTransport.SDK_TRANSPORT_UNFRAMED_JSON,
        SdkTransport.SDK_TRANSPORT_HTTP_JSON_TRANSCODING
      ))
      .addAllCapabilities(CAPABILITIES)
      .addAllPlugins(pluginApis.stream().map(PluginApiDefinition::toProto).toList())
      .addAllLimits(List.of(
        limit("grpc.request_bytes", Integer.MAX_VALUE),
        limit("grpc.response_bytes", Integer.MAX_VALUE),
        limit("bot.entity_event_radius", 256),
        limit("bot.block_event_radius", 128),
        limit("bot.camera.maximum_fps", 10),
        limit("bot.camera.world_map_radius", 256),
        limit("bot.task_input_bytes", 4L * 1024 * 1024),
        limit("bot.task_event_retention", 4096)
      ))
      .setIdentity(SdkIdentity.newBuilder()
        .setId(user.getUniqueId().toString())
        .setUsername(user.getUsername())
        .setEmail(user.getEmail())
        .setRole(switch (user.getRole()) {
          case ADMIN -> UserRole.ADMIN;
          case USER -> UserRole.USER;
        })
        .addAllGrantedGlobalPermissions(grantedPermissions))
      .build());
    responseObserver.onCompleted();
  }

  private static void validateCompatibility(SdkHandshakeRequest request) {
    var current = currentApiVersion();
    if (!request.hasMinimumApiVersion() || !request.hasMaximumApiVersion()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("SDK minimum and maximum API versions are required")
        .asRuntimeException();
    }
    if (compare(current, request.getMinimumApiVersion()) < 0
      || compare(current, request.getMaximumApiVersion()) > 0) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "SoulFire API %s is outside the SDK-supported range %s to %s".formatted(
            format(current),
            format(request.getMinimumApiVersion()),
            format(request.getMaximumApiVersion())
          )
        )
        .asRuntimeException();
    }

    var availableCapabilities = CAPABILITIES.stream()
      .map(SdkCapability::getId)
      .collect(Collectors.toUnmodifiableSet());
    var missingCapabilities = request.getRequiredCapabilitiesList().stream()
      .filter(capability -> !availableCapabilities.contains(capability))
      .distinct()
      .sorted()
      .toList();
    if (!missingCapabilities.isEmpty()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Required capabilities are unavailable: " + String.join(", ", missingCapabilities))
        .asRuntimeException();
    }

    var installedPlugins = SoulFireAPI.pluginApis().catalog().stream()
      .map(plugin -> plugin.pluginInfo().id())
      .collect(Collectors.toUnmodifiableSet());
    var missingPlugins = request.getRequiredPluginsList().stream()
      .map(RequiredPlugin::getPluginId)
      .filter(pluginId -> !installedPlugins.contains(pluginId))
      .distinct()
      .sorted()
      .toList();
    if (!missingPlugins.isEmpty()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Required plugins are unavailable: " + String.join(", ", missingPlugins))
        .asRuntimeException();
    }
  }

  private static SdkApiVersion currentApiVersion() {
    return SdkApiVersion.newBuilder()
      .setMajor(API_MAJOR)
      .setMinor(API_MINOR)
      .setPatch(API_PATCH)
      .build();
  }

  private static int compare(SdkApiVersion left, SdkApiVersion right) {
    var major = Integer.compare(left.getMajor(), right.getMajor());
    if (major != 0) {
      return major;
    }
    var minor = Integer.compare(left.getMinor(), right.getMinor());
    return minor != 0 ? minor : Integer.compare(left.getPatch(), right.getPatch());
  }

  private static String format(SdkApiVersion version) {
    return "%d.%d.%d".formatted(version.getMajor(), version.getMinor(), version.getPatch());
  }

  private static SdkCapability capability(String id) {
    return SdkCapability.newBuilder().setId(id).setRevision(1).build();
  }

  private static SdkLimit limit(String id, long value) {
    return SdkLimit.newBuilder().setId(id).setValue(value).build();
  }
}
