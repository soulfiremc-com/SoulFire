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

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.mod.access.IConnectionChannel;
import com.soulfiremc.mod.access.IProtocolInfoAccess;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.SoulFireEvent;
import com.soulfiremc.server.api.event.bot.BotPacketPreReceiveEvent;
import com.soulfiremc.server.api.event.bot.BotPacketPreSendEvent;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.user.PermissionContext;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.StatusProtocols;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/// Advanced, version-dependent access to Minecraft's native packet codec.
///
/// Packet bytes exposed here use SoulFire's native Minecraft protocol. The
/// connection pipeline translates them to the remote server version afterward.
@Slf4j
@RequiredArgsConstructor
public final class BotProtocolServiceImpl extends BotProtocolServiceGrpc.BotProtocolServiceImplBase {
  private static final int MAXIMUM_PACKET_BYTES = 1024 * 1024;
  private static final int MAXIMUM_SENDS_PER_SECOND = 20;
  private static final ConcurrentHashMap<RateLimitKey, SendWindow> SEND_WINDOWS =
    new ConcurrentHashMap<>();

  private final SoulFireServer soulFireServer;

  @Override
  public void getProtocolInfo(
    BotProtocolRequest request,
    StreamObserver<BotProtocolInfo> responseObserver
  ) {
    var target = requireReadableBot(request.getInstanceId(), request.getBotId());
    var protocols = activeProtocols(target.bot());
    var nativeVersion = ProtocolTranslationImpl.NATIVE_VERSION;
    var remoteVersion = target.bot().currentProtocolVersion();
    var rawEnabled = ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermission(
      PermissionContext.instance(InstancePermission.RAW_PROTOCOL, target.instanceId()));

    responseObserver.onNext(BotProtocolInfo.newBuilder()
      .setMinecraftProtocolVersion(nativeVersion.getVersion())
      .setMinecraftVersionName(nativeVersion.getName())
      .setRemoteProtocolVersion(remoteVersion.getVersion())
      .setRemoteVersionName(remoteVersion.getName())
      .setProtocolState(protocols.serverbound().id().id())
      .setPacketObservationSupported(true)
      .setRawPacketSendingEnabled(rawEnabled)
      .setMaximumPacketBytes(MAXIMUM_PACKET_BYTES)
      .setMaximumSendsPerSecond(MAXIMUM_SENDS_PER_SECOND)
      .build());
    responseObserver.onCompleted();
  }

  @Override
  public void listPacketSchemas(
    ListPacketSchemasRequest request,
    StreamObserver<ListPacketSchemasResponse> responseObserver
  ) {
    var target = requireReadableBot(request.getInstanceId(), request.getBotId());
    var protocols = activeProtocols(target.bot());
    var info = switch (request.getDirection()) {
      case PACKET_DIRECTION_CLIENTBOUND -> protocols.clientbound();
      case PACKET_DIRECTION_SERVERBOUND -> protocols.serverbound();
      case PACKET_DIRECTION_UNSPECIFIED, UNRECOGNIZED -> throw Status.INVALID_ARGUMENT
        .withDescription("direction must be clientbound or serverbound")
        .asRuntimeException();
    };

    var response = ListPacketSchemasResponse.newBuilder();
    packetSchemas(info).stream()
      .sorted(Comparator.comparingInt(PacketSchemaEntry::networkId))
      .map(entry -> PacketSchema.newBuilder()
        .setDirection(toProtoDirection(info.flow()))
        .setName(entry.type().id().toString())
        .setNetworkId(entry.networkId())
        .setProtocolState(info.id().id())
        .build())
      .forEach(response::addPackets);
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void watchPackets(
    WatchPacketsRequest request,
    StreamObserver<RawPacketEvent> responseObserver
  ) {
    var target = requireReadableBot(request.getInstanceId(), request.getBotId());
    var directions = normalizedDirections(request.getDirectionsList());
    var names = Set.copyOf(request.getNamesList());
    var maximumEncodedBytes = request.getMaximumEncodedBytes() == 0
      ? MAXIMUM_PACKET_BYTES
      : Math.min(request.getMaximumEncodedBytes(), MAXIMUM_PACKET_BYTES);
    var observer = (ServerCallStreamObserver<RawPacketEvent>) responseObserver;
    var closed = new AtomicBoolean();
    var sequence = new AtomicLong();
    var dropped = new AtomicLong();
    var cleanupActions = new CopyOnWriteArrayList<Runnable>();
    Runnable cleanup = () -> {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      cleanupActions.forEach(action -> {
        try {
          action.run();
        } catch (Throwable throwable) {
          log.debug("Failed to clean up raw packet subscription", throwable);
        }
      });
    };
    observer.setOnCancelHandler(cleanup);

    if (directions.contains(PacketDirection.PACKET_DIRECTION_CLIENTBOUND)) {
      Consumer<BotPacketPreReceiveEvent> listener = event -> observePacket(
        event.connection(),
        event.packet(),
        target,
        PacketDirection.PACKET_DIRECTION_CLIENTBOUND,
        request.getIncludeEncodedPacket(),
        maximumEncodedBytes,
        names,
        observer,
        closed,
        sequence,
        dropped);
      register(cleanupActions, BotPacketPreReceiveEvent.class, listener);
    }
    if (directions.contains(PacketDirection.PACKET_DIRECTION_SERVERBOUND)) {
      Consumer<BotPacketPreSendEvent> listener = event -> observePacket(
        event.connection(),
        event.packet(),
        target,
        PacketDirection.PACKET_DIRECTION_SERVERBOUND,
        request.getIncludeEncodedPacket(),
        maximumEncodedBytes,
        names,
        observer,
        closed,
        sequence,
        dropped);
      register(cleanupActions, BotPacketPreSendEvent.class, listener);
    }
  }

  @Override
  public void sendRawPacket(
    SendRawPacketRequest request,
    StreamObserver<SendRawPacketResponse> responseObserver
  ) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var botId = parseUuid(request.getBotId(), "bot_id");
    var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
    user.hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.RAW_PROTOCOL, instanceId));
    var bot = requireOnlineBot(instanceId, botId).bot();
    var size = request.getEncodedPacket().size();
    if (size == 0 || size > MAXIMUM_PACKET_BYTES) {
      throw Status.INVALID_ARGUMENT
        .withDescription("encoded_packet must contain 1 to %d bytes".formatted(MAXIMUM_PACKET_BYTES))
        .asRuntimeException();
    }
    enforceSendRate(new RateLimitKey(user.getUniqueId(), instanceId, botId));

    var protocols = activeProtocols(bot);
    var input = Unpooled.wrappedBuffer(request.getEncodedPacket().asReadOnlyByteBuffer());
    Packet<?> packet;
    try {
      packet = decode(protocols.serverbound(), input);
      if (input.isReadable()) {
        throw Status.INVALID_ARGUMENT
          .withDescription("encoded_packet has %d unread trailing bytes".formatted(input.readableBytes()))
          .asRuntimeException();
      }
    } catch (io.grpc.StatusRuntimeException exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw Status.INVALID_ARGUMENT
        .withDescription("encoded_packet is not valid for the current serverbound protocol: "
          + throwable.getMessage())
        .withCause(throwable)
        .asRuntimeException();
    } finally {
      input.release();
    }

    var name = packet.type().id().toString();
    if (request.hasExpectedName() && !request.getExpectedName().equals(name)) {
      throw Status.INVALID_ARGUMENT
        .withDescription("decoded packet '%s' does not match expected_name '%s'".formatted(
          name,
          request.getExpectedName()))
        .asRuntimeException();
    }

    var connection = requireMinecraftConnection(bot);
    connection.send(packet);
    log.warn(
      "User {} sent raw Minecraft packet {} ({} bytes) through bot {} in instance {}",
      user.getUniqueId(),
      name,
      size,
      botId,
      instanceId);
    responseObserver.onNext(SendRawPacketResponse.newBuilder()
      .setName(name)
      .setEncodedBytes(size)
      .build());
    responseObserver.onCompleted();
  }

  private BotTarget requireReadableBot(String instanceIdValue, String botIdValue) {
    var instanceId = parseUuid(instanceIdValue, "instance_id");
    var botId = parseUuid(botIdValue, "bot_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));
    return requireOnlineBot(instanceId, botId);
  }

  private BotTarget requireOnlineBot(UUID instanceId, UUID botId) {
    var instance = soulFireServer.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
    if (!instance.settingsSource().accounts().containsKey(botId)) {
      throw Status.NOT_FOUND
        .withDescription("Bot '%s' is not configured".formatted(botId))
        .asRuntimeException();
    }
    var bot = instance.botConnections().get(botId);
    if (bot == null || bot.isDisconnected()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot '%s' is not online".formatted(botId))
        .asRuntimeException();
    }
    return new BotTarget(instanceId, botId, instance, bot);
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription("%s must be a UUID".formatted(field))
        .withCause(exception)
        .asRuntimeException();
    }
  }

  private static ActiveProtocols activeProtocols(BotConnection bot) {
    var connection = requireMinecraftConnection(bot);
    var channel = ((IConnectionChannel) connection).soulFire$getChannel();
    var decoder = channel.pipeline().get("decoder");
    var encoder = channel.pipeline().get("encoder");
    if (!(decoder instanceof IProtocolInfoAccess decoderAccess)
      || !(encoder instanceof IProtocolInfoAccess encoderAccess)) {
      throw Status.FAILED_PRECONDITION
        .withDescription("The bot protocol codecs are not active")
        .asRuntimeException();
    }
    return new ActiveProtocols(
      decoderAccess.soulFire$getProtocolInfo(),
      encoderAccess.soulFire$getProtocolInfo());
  }

  private static net.minecraft.network.Connection requireMinecraftConnection(BotConnection bot) {
    var listener = bot.minecraft().getConnection();
    if (listener == null) {
      throw Status.FAILED_PRECONDITION
        .withDescription("The bot Minecraft connection is not active")
        .asRuntimeException();
    }
    return listener.getConnection();
  }

  private static List<PacketSchemaEntry> packetSchemas(ProtocolInfo<?> info) {
    ProtocolInfo.Details details = switch (info.id()) {
      case HANDSHAKING -> {
        if (info.flow() != PacketFlow.SERVERBOUND) {
          throw unavailableSchema(info);
        }
        yield HandshakeProtocols.SERVERBOUND_TEMPLATE.details();
      }
      case STATUS -> info.flow() == PacketFlow.CLIENTBOUND
        ? StatusProtocols.CLIENTBOUND_TEMPLATE.details()
        : StatusProtocols.SERVERBOUND_TEMPLATE.details();
      case LOGIN -> info.flow() == PacketFlow.CLIENTBOUND
        ? LoginProtocols.CLIENTBOUND_TEMPLATE.details()
        : LoginProtocols.SERVERBOUND_TEMPLATE.details();
      case CONFIGURATION -> info.flow() == PacketFlow.CLIENTBOUND
        ? ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details()
        : ConfigurationProtocols.SERVERBOUND_TEMPLATE.details();
      case PLAY -> info.flow() == PacketFlow.CLIENTBOUND
        ? GameProtocols.CLIENTBOUND_TEMPLATE.details()
        : GameProtocols.SERVERBOUND_TEMPLATE.details();
    };
    var schemas = new ArrayList<PacketSchemaEntry>();
    details.listPackets((type, networkId) ->
      schemas.add(new PacketSchemaEntry(type, networkId)));
    return List.copyOf(schemas);
  }

  private static io.grpc.StatusRuntimeException unavailableSchema(ProtocolInfo<?> info) {
    return Status.FAILED_PRECONDITION
      .withDescription("No %s packet schema exists for protocol state %s".formatted(
        info.flow(),
        info.id().id()))
      .asRuntimeException();
  }

  private static Set<PacketDirection> normalizedDirections(List<PacketDirection> requested) {
    if (requested.isEmpty()) {
      return EnumSet.of(
        PacketDirection.PACKET_DIRECTION_CLIENTBOUND,
        PacketDirection.PACKET_DIRECTION_SERVERBOUND);
    }
    var normalized = EnumSet.noneOf(PacketDirection.class);
    for (var direction : requested) {
      switch (direction) {
        case PACKET_DIRECTION_CLIENTBOUND, PACKET_DIRECTION_SERVERBOUND ->
          normalized.add(direction);
        case PACKET_DIRECTION_UNSPECIFIED, UNRECOGNIZED -> throw Status.INVALID_ARGUMENT
          .withDescription("directions may only contain clientbound or serverbound")
          .asRuntimeException();
      }
    }
    return Set.copyOf(normalized);
  }

  private static PacketDirection toProtoDirection(PacketFlow flow) {
    return flow == PacketFlow.CLIENTBOUND
      ? PacketDirection.PACKET_DIRECTION_CLIENTBOUND
      : PacketDirection.PACKET_DIRECTION_SERVERBOUND;
  }

  private static void observePacket(
    BotConnection connection,
    Packet<?> packet,
    BotTarget target,
    PacketDirection direction,
    boolean includeEncoded,
    int maximumEncodedBytes,
    Set<String> names,
    ServerCallStreamObserver<RawPacketEvent> observer,
    AtomicBoolean closed,
    AtomicLong sequence,
    AtomicLong dropped
  ) {
    if (packet == null
      || connection.instanceManager() != target.instance()
      || !connection.accountProfileId().equals(target.botId())) {
      return;
    }
    var name = packet.type().id().toString();
    if (!names.isEmpty() && !names.contains(name)) {
      return;
    }
    try {
      var protocols = activeProtocols(connection);
      var info = direction == PacketDirection.PACKET_DIRECTION_CLIENTBOUND
        ? protocols.clientbound()
        : protocols.serverbound();
      var builder = RawPacketEvent.newBuilder()
        .setSequence(sequence.incrementAndGet())
        .setObservedAt(toTimestamp(Instant.now()))
        .setDirection(direction)
        .setName(name)
        .setNetworkId(networkId(info, packet.type()))
        .setProtocolState(info.id().id())
        .setJavaClassName(packet.getClass().getName());
      if (includeEncoded) {
        var encoded = encode(info, packet);
        var length = Math.min(encoded.length, maximumEncodedBytes);
        builder
          .setEncodedPacket(ByteString.copyFrom(encoded, 0, length))
          .setEncodedPacketTruncated(encoded.length > length);
      }
      emit(observer, closed, dropped, builder);
    } catch (Throwable throwable) {
      dropped.incrementAndGet();
      log.debug("Failed to encode observed Minecraft packet {}", name, throwable);
    }
  }

  private static void emit(
    ServerCallStreamObserver<RawPacketEvent> observer,
    AtomicBoolean closed,
    AtomicLong dropped,
    RawPacketEvent.Builder event
  ) {
    synchronized (observer) {
      if (closed.get() || observer.isCancelled()) {
        return;
      }
      if (!observer.isReady()) {
        dropped.incrementAndGet();
        return;
      }
      event.setDroppedBefore(dropped.getAndSet(0));
      observer.onNext(event.build());
    }
  }

  private static int networkId(ProtocolInfo<?> info, PacketType<?> type) {
    return packetSchemas(info).stream()
      .filter(entry -> entry.type().equals(type))
      .mapToInt(PacketSchemaEntry::networkId)
      .findFirst()
      .orElse(0);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static byte[] encode(ProtocolInfo<?> info, Packet<?> packet) {
    var output = Unpooled.buffer();
    try {
      ((StreamCodec) info.codec()).encode(output, packet);
      var bytes = new byte[output.readableBytes()];
      output.getBytes(output.readerIndex(), bytes);
      return bytes;
    } finally {
      output.release();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Packet<?> decode(ProtocolInfo<?> info, ByteBuf input) {
    return (Packet<?>) ((StreamCodec) info.codec()).decode(input);
  }

  private static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder()
      .setSeconds(instant.getEpochSecond())
      .setNanos(instant.getNano())
      .build();
  }

  private static <E extends SoulFireEvent> void register(
    List<Runnable> cleanupActions,
    Class<E> eventType,
    Consumer<E> listener
  ) {
    SoulFireAPI.registerListener(eventType, listener);
    cleanupActions.add(() -> SoulFireAPI.unregisterListener(eventType, listener));
  }

  private static void enforceSendRate(RateLimitKey key) {
    var window = SEND_WINDOWS.computeIfAbsent(key, ignored -> new SendWindow());
    if (!window.tryAcquire()) {
      throw Status.RESOURCE_EXHAUSTED
        .withDescription("Raw protocol sends are limited to %d packets per second".formatted(
          MAXIMUM_SENDS_PER_SECOND))
        .asRuntimeException();
    }
  }

  private record BotTarget(
    UUID instanceId,
    UUID botId,
    InstanceManager instance,
    BotConnection bot
  ) {
  }

  private record ActiveProtocols(
    ProtocolInfo<?> clientbound,
    ProtocolInfo<?> serverbound
  ) {
  }

  private record PacketSchemaEntry(PacketType<?> type, int networkId) {
  }

  private record RateLimitKey(UUID userId, UUID instanceId, UUID botId) {
  }

  private static final class SendWindow {
    private long second;
    private int sends;

    private synchronized boolean tryAcquire() {
      var currentSecond = Instant.now().getEpochSecond();
      if (currentSecond != second) {
        second = currentSecond;
        sends = 0;
      }
      if (sends >= MAXIMUM_SENDS_PER_SECOND) {
        return false;
      }
      sends++;
      return true;
    }
  }
}
