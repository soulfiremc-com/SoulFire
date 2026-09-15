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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.soulfiremc.builddata.BuildData;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotThreadExecution;
import com.soulfiremc.server.user.PermissionContext;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/// Read-only access to versioned Minecraft registries and tags.
@RequiredArgsConstructor
public final class RegistryServiceImpl
  extends RegistryServiceGrpc.RegistryServiceImplBase {
  private static final int MAX_PAGE_SIZE = 1_000;
  private static final List<RegistryKind> SUPPORTED_KINDS = List.of(
    RegistryKind.REGISTRY_KIND_BLOCK,
    RegistryKind.REGISTRY_KIND_ITEM,
    RegistryKind.REGISTRY_KIND_ENTITY_TYPE,
    RegistryKind.REGISTRY_KIND_BIOME,
    RegistryKind.REGISTRY_KIND_DIMENSION,
    RegistryKind.REGISTRY_KIND_RECIPE,
    RegistryKind.REGISTRY_KIND_ENCHANTMENT,
    RegistryKind.REGISTRY_KIND_EFFECT,
    RegistryKind.REGISTRY_KIND_ATTRIBUTE,
    RegistryKind.REGISTRY_KIND_GAME_EVENT,
    RegistryKind.REGISTRY_KIND_SOUND,
    RegistryKind.REGISTRY_KIND_PARTICLE,
    RegistryKind.REGISTRY_KIND_CONTAINER
  );

  private final SoulFireServer server;
  private final Cache<@NonNull String, String> registryHashes =
    Caffeine.newBuilder()
      .maximumSize(10_000)
      .expireAfterAccess(Duration.ofHours(1))
      .build();

  @Override
  public void getRegistryIdentity(
    GetRegistryIdentityRequest request,
    StreamObserver<GetRegistryIdentityResponse> responseObserver
  ) {
    unary(responseObserver, () -> {
      requireGlobalRead();
      var bot = optionalBot(
        request.hasInstanceId() ? request.getInstanceId() : null,
        request.hasBotId() ? request.getBotId() : null
      );
      return onGameThread(bot, () -> {
        var protocol = bot
          .map(value -> value.currentProtocolVersion().getVersion())
          .orElse(ProtocolTranslationImpl.NATIVE_VERSION.getVersion());
        return GetRegistryIdentityResponse.newBuilder()
          .setIdentity(RegistryIdentity.newBuilder()
            .setSoulfireVersion(BuildData.VERSION)
            .setMinecraftVersion(ProtocolTranslationImpl.NATIVE_VERSION.getName())
            .setProtocolVersion(protocol)
            .setRegistryHash(registryHash(bot)))
          .addAllSupportedKinds(SUPPORTED_KINDS)
          .addAllProtocolFeatures(List.of(
            "data-components",
            "dynamic-registries",
            "registry-tags",
            "semantic-items"
          ))
          .build();
      });
    });
  }

  @Override
  public void listRegistryEntries(
    ListRegistryEntriesRequest request,
    StreamObserver<ListRegistryEntriesResponse> responseObserver
  ) {
    unary(responseObserver, () -> {
      requireGlobalRead();
      var bot = optionalBot(
        request.hasInstanceId() ? request.getInstanceId() : null,
        request.hasBotId() ? request.getBotId() : null
      );
      return onGameThread(bot, () -> {
        var registry = registry(request.getKind(), bot);
        var entries = registry.entrySet().stream()
          .map(entry -> entry(
            request.getKind(),
            registry,
            entry.getKey().identifier().toString(),
            entry.getValue()
          ))
          .filter(entry -> request.getIdPrefix().isBlank()
            || entry.getId().startsWith(request.getIdPrefix()))
          .filter(entry -> request.getTagsList().isEmpty()
            || entry.getTagsList().containsAll(request.getTagsList()))
          .sorted(Comparator.comparing(MinecraftRegistryEntry::getId))
          .toList();
        var page = page(entries, request.getPageSize(), request.getPageToken());
        return ListRegistryEntriesResponse.newBuilder()
          .addAllEntries(page.values)
          .setNextPageToken(page.nextToken)
          .setRegistryHash(registryHash(bot))
          .build();
      });
    });
  }

  @Override
  public void getRegistryEntry(
    GetRegistryEntryRequest request,
    StreamObserver<GetRegistryEntryResponse> responseObserver
  ) {
    unary(responseObserver, () -> {
      requireGlobalRead();
      var bot = optionalBot(
        request.hasInstanceId() ? request.getInstanceId() : null,
        request.hasBotId() ? request.getBotId() : null
      );
      return onGameThread(bot, () -> {
        var registry = registry(request.getKind(), bot);
        var value = registry.entrySet().stream()
          .filter(entry -> entry.getKey().identifier().toString().equals(request.getId()))
          .findFirst()
          .orElseThrow(() -> Status.NOT_FOUND
            .withDescription("Registry entry not found: " + request.getId())
            .asRuntimeException());
        return GetRegistryEntryResponse.newBuilder()
          .setEntry(entry(
            request.getKind(),
            registry,
            value.getKey().identifier().toString(),
            value.getValue()
          ))
          .setRegistryHash(registryHash(bot))
          .build();
      });
    });
  }

  @Override
  public void listRegistryTags(
    ListRegistryTagsRequest request,
    StreamObserver<ListRegistryTagsResponse> responseObserver
  ) {
    unary(responseObserver, () -> {
      requireGlobalRead();
      var bot = optionalBot(
        request.hasInstanceId() ? request.getInstanceId() : null,
        request.hasBotId() ? request.getBotId() : null
      );
      return onGameThread(bot, () -> {
        var registry = registry(request.getKind(), bot);
        var tags = tags(request.getKind(), registry).stream()
          .filter(tag -> request.getPrefix().isBlank()
            || tag.getId().startsWith(request.getPrefix()))
          .sorted(Comparator.comparing(RegistryTag::getId))
          .toList();
        var page = page(tags, request.getPageSize(), request.getPageToken());
        return ListRegistryTagsResponse.newBuilder()
          .addAllTags(page.values)
          .setNextPageToken(page.nextToken)
          .setRegistryHash(registryHash(bot))
          .build();
      });
    });
  }

  private Optional<BotConnection> optionalBot(
    String instanceValue,
    String botValue
  ) {
    if (instanceValue == null && botValue == null) {
      return Optional.empty();
    }
    if (instanceValue == null || botValue == null) {
      throw Status.INVALID_ARGUMENT
        .withDescription("instance_id and bot_id must be provided together")
        .asRuntimeException();
    }
    var instanceId = parseUuid(instanceValue, "instance_id");
    var botId = parseUuid(botValue, "bot_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId)
    );
    var instance = server.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance not found: " + instanceId)
        .asRuntimeException());
    var bot = instance.botConnections().get(botId);
    if (bot == null || bot.isDisconnected()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot must be online for dynamic registry access")
        .asRuntimeException();
    }
    return Optional.of(bot);
  }

  private static Registry<?> registry(
    RegistryKind kind,
    Optional<BotConnection> bot
  ) {
    return switch (kind) {
      case REGISTRY_KIND_BLOCK -> BuiltInRegistries.BLOCK;
      case REGISTRY_KIND_ITEM -> BuiltInRegistries.ITEM;
      case REGISTRY_KIND_ENTITY_TYPE -> BuiltInRegistries.ENTITY_TYPE;
      case REGISTRY_KIND_EFFECT -> BuiltInRegistries.MOB_EFFECT;
      case REGISTRY_KIND_ATTRIBUTE -> BuiltInRegistries.ATTRIBUTE;
      case REGISTRY_KIND_GAME_EVENT -> BuiltInRegistries.GAME_EVENT;
      case REGISTRY_KIND_SOUND -> BuiltInRegistries.SOUND_EVENT;
      case REGISTRY_KIND_PARTICLE -> BuiltInRegistries.PARTICLE_TYPE;
      case REGISTRY_KIND_CONTAINER -> BuiltInRegistries.MENU;
      case REGISTRY_KIND_BIOME -> dynamic(bot, Registries.BIOME);
      case REGISTRY_KIND_DIMENSION -> dynamic(bot, Registries.DIMENSION_TYPE);
      case REGISTRY_KIND_ENCHANTMENT -> dynamic(bot, Registries.ENCHANTMENT);
      case REGISTRY_KIND_RECIPE -> BuiltInRegistries.RECIPE_TYPE;
      case REGISTRY_KIND_UNSPECIFIED, UNRECOGNIZED ->
        throw Status.INVALID_ARGUMENT
          .withDescription("A supported registry kind is required")
          .asRuntimeException();
    };
  }

  private static Registry<?> dynamic(
    Optional<BotConnection> bot,
    net.minecraft.resources.ResourceKey<? extends Registry<?>> key
  ) {
    var connection = bot.orElseThrow(() -> Status.FAILED_PRECONDITION
      .withDescription("This registry requires an online bot scope")
      .asRuntimeException());
    if (connection.minecraft().level == null) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot must be in a world for dynamic registry access")
        .asRuntimeException();
    }
    return connection.minecraft().level.registryAccess().lookupOrThrow(key);
  }

  private static MinecraftRegistryEntry entry(
    RegistryKind kind,
    Registry<?> registry,
    String id,
    Object value
  ) {
    var builder = MinecraftRegistryEntry.newBuilder()
      .setKind(kind)
      .setId(id)
      .setNumericId(rawId(registry, value))
      .setDisplayName(displayName(value))
      .setProperties(properties(value));
    @SuppressWarnings("unchecked")
    var rawRegistry = (Registry<Object>) registry;
    builder.addAllTags(rawRegistry.wrapAsHolder(value).tags()
      .map(tag -> tag.location().toString())
      .sorted()
      .toList());
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static int rawId(Registry<?> registry, Object value) {
    return ((Registry<Object>) registry).getId(value);
  }

  private static String displayName(Object value) {
    return switch (value) {
      case Item item -> item.getDefaultInstance().getHoverName().getString();
      case Block block -> block.getName().getString();
      case EntityType<?> entityType -> entityType.getDescription().getString();
      default -> value.toString();
    };
  }

  @SuppressWarnings("unchecked")
  private static List<RegistryTag> tags(
    RegistryKind kind,
    Registry<?> registry
  ) {
    var rawRegistry = (Registry<Object>) registry;
    return rawRegistry.getTags()
      .map(tag -> RegistryTag.newBuilder()
        .setKind(kind)
        .setId(tag.key().location().toString())
        .addAllValues(tag.stream()
          .map(holder -> rawRegistry.getKey(holder.value()))
          .filter(Objects::nonNull)
          .map(Object::toString)
          .sorted()
          .toList())
        .build())
      .toList();
  }

  private static Struct properties(Object value) {
    var builder = Struct.newBuilder()
      .putFields("implementation", string(value.getClass().getName()));
    switch (value) {
      case Item item -> {
        var stack = item.getDefaultInstance();
        builder
          .putFields("maxStackSize", number(stack.getMaxStackSize()))
          .putFields("maxDamage", number(stack.getMaxDamage()));
      }
      case Block block -> builder.putFields(
        "stateCount",
        number(block.getStateDefinition().getPossibleStates().size())
      );
      case EntityType<?> entityType -> builder
        .putFields("width", number(entityType.getWidth()))
        .putFields("height", number(entityType.getHeight()));
      default -> {
      }
    }
    return builder.build();
  }

  private static Value string(String value) {
    return Value.newBuilder().setStringValue(value).build();
  }

  private static Value number(double value) {
    return Value.newBuilder().setNumberValue(value).build();
  }

  private String registryHash(Optional<BotConnection> bot) {
    var cacheKey = bot
      .map(value -> value.connectionEpoch().toString())
      .orElse("builtin");
    return registryHashes.get(cacheKey, _ -> computeRegistryHash(bot));
  }

  private static String computeRegistryHash(Optional<BotConnection> bot) {
    var material = new StringBuilder()
      .append(BuildData.VERSION)
      .append('|')
      .append(ProtocolTranslationImpl.NATIVE_VERSION.getName());
    for (var kind : SUPPORTED_KINDS) {
      try {
        registry(kind, bot).keySet().stream()
          .map(Object::toString)
          .sorted()
          .forEach(id -> material.append('|').append(kind.name()).append(':').append(id));
      } catch (io.grpc.StatusRuntimeException ignored) {
        // Dynamic registries are absent from an unscoped identity.
      }
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 must be available", exception);
    }
  }

  private static <T> T onGameThread(
    Optional<BotConnection> bot,
    Callable<T> call
  ) throws Exception {
    return bot.isPresent()
      ? BotThreadExecution.call(bot.orElseThrow(), call)
      : call.call();
  }

  private static <T> Page<T> page(
    List<T> values,
    int requestedSize,
    String pageToken
  ) {
    var offset = decodeOffset(pageToken);
    if (offset > values.size()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("page_token is outside the result set")
        .asRuntimeException();
    }
    var size = requestedSize <= 0 ? 100 : Math.min(requestedSize, MAX_PAGE_SIZE);
    var end = Math.min(values.size(), offset + size);
    return new Page<>(
      List.copyOf(values.subList(offset, end)),
      end < values.size() ? encodeOffset(end) : ""
    );
  }

  private static int decodeOffset(String token) {
    if (token.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(new String(
        Base64.getUrlDecoder().decode(token),
        StandardCharsets.UTF_8
      ));
    } catch (RuntimeException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Invalid page_token")
        .withCause(exception)
        .asRuntimeException();
    }
  }

  private static String encodeOffset(int offset) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
      Integer.toString(offset).getBytes(StandardCharsets.UTF_8)
    );
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must be a UUID")
        .withCause(exception)
        .asRuntimeException();
    }
  }

  private static void requireGlobalRead() {
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.global(GlobalPermission.READ_CLIENT_DATA)
    );
  }

  private static <T> void unary(
    StreamObserver<T> observer,
    Callable<T> call
  ) {
    try {
      observer.onNext(call.call());
      observer.onCompleted();
    } catch (Throwable throwable) {
      if (throwable instanceof io.grpc.StatusRuntimeException) {
        observer.onError(throwable);
      } else {
        observer.onError(Status.INTERNAL
          .withDescription(Objects.requireNonNullElse(
            throwable.getMessage(),
            throwable.getClass().getSimpleName()
          ))
          .withCause(throwable)
          .asRuntimeException());
      }
    }
  }

  private record Page<T>(List<T> values, String nextToken) {}
}
