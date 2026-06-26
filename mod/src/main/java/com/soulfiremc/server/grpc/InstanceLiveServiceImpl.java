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

import com.google.protobuf.Timestamp;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.adventure.SoulFireAdventure;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotConnectionInitEvent;
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.bot.ChatMessageReceiveEvent;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.user.PermissionContext;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Instance-wide counterpart to {@link BotLiveServiceImpl}'s WatchBotEvents.
///
/// Rather than one stream per bot, this fans in over the same global event bus
/// and forwards events from every bot belonging to the instance, tagging each
/// with the originating bot. This is what backs an instance-wide live feed.
@Slf4j
@RequiredArgsConstructor
public final class InstanceLiveServiceImpl extends InstanceLiveServiceGrpc.InstanceLiveServiceImplBase {
  private final SoulFireServer soulFireServer;

  @Override
  public void watchInstanceEvents(WatchInstanceEventsRequest request, StreamObserver<InstanceEvent> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));

    // Validate the instance exists before opening the stream.
    if (soulFireServer.getInstance(instanceId).isEmpty()) {
      responseObserver.onError(Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
      return;
    }

    var filter = request.getFilter();
    var serverObserver = (ServerCallStreamObserver<InstanceEvent>) responseObserver;
    var closed = new AtomicBoolean(false);

    Consumer<ChatMessageReceiveEvent> chatListener = null;
    Consumer<BotConnectionInitEvent> connectListener = null;
    Consumer<BotDisconnectedEvent> disconnectListener = null;

    if (filter.getIncludeChat()) {
      chatListener = event -> {
        if (closed.get() || !belongsToInstance(event.connection(), instanceId)) {
          return;
        }
        var plain = event.parseToPlainText();
        var json = GsonComponentSerializer.gson().serialize(event.message());
        var nowSec = Instant.ofEpochMilli(event.timestamp()).getEpochSecond();
        var chat = BotChatEvent.newBuilder()
          .setSource(ChatSource.CHAT_SOURCE_SYSTEM)
          .setPlainText(plain)
          .setJsonComponent(json)
          .setReceivedAt(Timestamp.newBuilder().setSeconds(nowSec).build())
          .build();
        emit(serverObserver, closed, baseEvent(event.connection()).setChat(chat).build());
      };
      SoulFireAPI.registerListener(ChatMessageReceiveEvent.class, chatListener);
    }

    if (filter.getIncludeLifecycle()) {
      connectListener = event -> {
        if (closed.get() || !belongsToInstance(event.connection(), instanceId)) {
          return;
        }
        var lifecycle = BotLifecycleEvent.newBuilder()
          .setKind(BotLifecycleKind.BOT_LIFECYCLE_CONNECTING)
          .build();
        emit(serverObserver, closed, baseEvent(event.connection()).setLifecycle(lifecycle).build());
      };
      SoulFireAPI.registerListener(BotConnectionInitEvent.class, connectListener);

      disconnectListener = event -> {
        if (closed.get() || !belongsToInstance(event.connection(), instanceId)) {
          return;
        }
        var reason = event.message() == null
          ? ""
          : SoulFireAdventure.PLAIN_MESSAGE_SERIALIZER.serialize(event.message());
        var lifecycle = BotLifecycleEvent.newBuilder()
          .setKind(BotLifecycleKind.BOT_LIFECYCLE_DISCONNECTED)
          .setMessage(reason)
          .build();
        emit(serverObserver, closed, baseEvent(event.connection()).setLifecycle(lifecycle).build());
      };
      SoulFireAPI.registerListener(BotDisconnectedEvent.class, disconnectListener);
    }

    var finalChatListener = chatListener;
    var finalConnectListener = connectListener;
    var finalDisconnectListener = disconnectListener;

    serverObserver.setOnCancelHandler(() -> {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      if (finalChatListener != null) {
        SoulFireAPI.unregisterListener(ChatMessageReceiveEvent.class, finalChatListener);
      }
      if (finalConnectListener != null) {
        SoulFireAPI.unregisterListener(BotConnectionInitEvent.class, finalConnectListener);
      }
      if (finalDisconnectListener != null) {
        SoulFireAPI.unregisterListener(BotDisconnectedEvent.class, finalDisconnectListener);
      }
    });
  }

  private static boolean belongsToInstance(BotConnection connection, UUID instanceId) {
    return connection.instanceManager().id().equals(instanceId);
  }

  private static InstanceEvent.Builder baseEvent(BotConnection connection) {
    var builder = InstanceEvent.newBuilder()
      .setBotProfileId(connection.accountProfileId().toString());
    var name = connection.accountName();
    if (name != null) {
      builder.setBotName(name);
    }
    return builder;
  }

  private static void emit(ServerCallStreamObserver<InstanceEvent> observer, AtomicBoolean closed, InstanceEvent event) {
    synchronized (observer) {
      if (!closed.get() && !observer.isCancelled()) {
        observer.onNext(event);
      }
    }
  }
}
