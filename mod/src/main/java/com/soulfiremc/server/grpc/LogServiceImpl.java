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

import com.google.common.base.Supplier;
import com.google.protobuf.util.Timestamps;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.user.SoulFireUser;
import com.soulfiremc.server.util.structs.CachedLazyObject;
import com.soulfiremc.shared.SFLogAppender;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

@Slf4j
@RequiredArgsConstructor
public final class LogServiceImpl extends LogsServiceGrpc.LogsServiceImplBase {
  private final SoulFireServer soulFireServer;

  private static LogString fromEvent(SFLogAppender.SFLogEvent event, boolean personal) {
    var builder = LogString.newBuilder()
      .setId(event.id())
      .setMessage(event.message())
      .setPersonal(personal)
      .setTimestamp(Timestamps.fromMillis(event.timestamp()));

    if (event.instanceId() != null) {
      builder.setInstanceId(event.instanceId().toString());
    }

    if (event.instanceName() != null) {
      builder.setInstanceName(event.instanceName());
    }

    if (event.botAccountId() != null) {
      builder.setBotAccountId(event.botAccountId().toString());
    }

    if (event.botAccountName() != null) {
      builder.setBotAccountName(event.botAccountName());
    }

    if (event.scriptId() != null) {
      builder.setScriptId(event.scriptId().toString());
    }

    if (event.loggerName() != null) {
      builder.setLoggerName(event.loggerName());
    }

    if (event.level() != null) {
      builder.setLevel(event.level());
    }

    return builder.build();
  }

  @Override
  public void getPrevious(PreviousLogRequest request, StreamObserver<PreviousLogResponse> responseObserver) {
    validateScopeAccess(request.getScope());

    try {
      var predicate = eventPredicate(request.getScope());
      responseObserver.onNext(PreviousLogResponse.newBuilder()
        .addAllMessages(SFLogAppender.INSTANCE.logs().getNewest(request.getCount(), event -> predicate.test(event, false))
          .stream()
          .map(e -> fromEvent(e, false))
          .toList())
        .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e).asRuntimeException();
    } catch (Throwable t) {
      log.error("Error getting previous logs", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void subscribe(LogRequest request, StreamObserver<LogResponse> responseObserver) {
    subscribe(soulFireServer, request.getScope(), (ServerCallStreamObserver<LogResponse>) responseObserver,
      (event, personal) -> LogResponse.newBuilder().setMessage(fromEvent(event, personal)).build());
  }

  static <T> void subscribe(SoulFireServer server, LogScope scope, ServerCallStreamObserver<T> observer,
                           BiFunction<SFLogAppender.SFLogEvent, Boolean, T> messageFactory) {
    validateScopeAccess(scope);

    try {
      var userId = ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId();
      var issuedAt = ServerRPCConstants.USER_CONTEXT_KEY.get().getIssuedAt();
      var user = new CachedLazyObject<>((Supplier<Optional<SoulFireUser>>)
        () -> server.authSystem().authenticateBySubject(userId, issuedAt), 1, TimeUnit.SECONDS);
      var predicate = eventPredicate(scope);
      new StateHolder.ConnectionMessageSender<>(
        server.logStateHolder(),
        ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId(),
        observer,
        (event, personal) -> user.get().filter(soulFireUser -> hasScopeAccess(soulFireUser, scope)
          && predicate.test(event, personal)).isPresent(),
        messageFactory
      );
    } catch (Throwable t) {
      log.error("Error subscribing to logs", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  private static void validateScopeAccess(LogScope scope) {
    if (!hasScopeAccess(ServerRPCConstants.USER_CONTEXT_KEY.get(), scope)) {
      throw
        Status.PERMISSION_DENIED.withDescription("You do not have permission to access this resource").asRuntimeException();
    }
  }

  private static boolean hasScopeAccess(SoulFireUser user, LogScope scope) {
    return switch (scope.getScopeCase()) {
      case GLOBAL -> user.hasPermission(PermissionContext.global(GlobalPermission.GLOBAL_SUBSCRIBE_LOGS));
      case INSTANCE -> {
        var instanceId = UUID.fromString(scope.getInstance().getInstanceId());
        yield user.hasPermission(PermissionContext.instance(InstancePermission.INSTANCE_SUBSCRIBE_LOGS, instanceId));
      }
      case BOT -> {
        var instanceId = UUID.fromString(scope.getBot().getInstanceId());
        yield user.hasPermission(PermissionContext.instance(InstancePermission.INSTANCE_SUBSCRIBE_LOGS, instanceId));
      }
      case INSTANCE_SCRIPT -> {
        var instanceId = UUID.fromString(scope.getInstanceScript().getInstanceId());
        yield user.hasPermission(PermissionContext.instance(InstancePermission.INSTANCE_SUBSCRIBE_LOGS, instanceId));
      }
      case PERSONAL -> true;
      case SCOPE_NOT_SET -> throw new IllegalArgumentException("Scope not set");
    };
  }

  private static EventPredicate eventPredicate(LogScope scope) {
    return switch (scope.getScopeCase()) {
      case GLOBAL -> (_, personal) -> !personal;
      case INSTANCE -> {
        var instanceId = UUID.fromString(scope.getInstance().getInstanceId());
        yield (event, personal) -> !personal && instanceId.equals(event.instanceId());
      }
      case BOT -> {
        var instanceId = UUID.fromString(scope.getBot().getInstanceId());
        var botId = UUID.fromString(scope.getBot().getBotId());
        yield (event, personal) -> !personal && instanceId.equals(event.instanceId()) && botId.equals(event.botAccountId());
      }
      case INSTANCE_SCRIPT -> {
        var instanceId = UUID.fromString(scope.getInstanceScript().getInstanceId());
        var scriptId = UUID.fromString(scope.getInstanceScript().getScriptId());
        yield (event, personal) -> !personal && instanceId.equals(event.instanceId()) && scriptId.equals(event.scriptId());
      }
      case PERSONAL -> (_, personal) -> personal;
      case SCOPE_NOT_SET -> (_, _) -> false;
    };
  }

  @FunctionalInterface
  public interface EventPredicate {
    boolean test(SFLogAppender.SFLogEvent event, boolean personal);
  }

  public static class StateHolder {
    private final Map<UUID, ConnectionMessageSender<?>> subscribers = new ConcurrentHashMap<>();

    public StateHolder() {
      SFLogAppender.INSTANCE.logConsumers().add(this::broadcastLogMessage);
    }

    public void sendPersonalMessage(UUID uuid, String message) {
      var messageEvent = new SFLogAppender.SFLogEvent(
        UUID.randomUUID().toString(),
        message,
        System.currentTimeMillis(),
        null,
        null,
        null,
        null,
        null,
        null,
        null
      );
      for (var sender : subscribers.values()) {
        if (sender.userId.equals(uuid)) sender.sendMessage(messageEvent, true);
      }
    }

    public void disconnect(UUID uuid) {
      for (var sender : subscribers.values()) {
        if (sender.userId.equals(uuid)) sender.complete();
      }
    }

    public void broadcastLogMessage(SFLogAppender.SFLogEvent message) {
      for (var sender : subscribers.values()) {
        sender.sendMessage(message, false);
      }
    }

    private static final class ConnectionMessageSender<T> {
      private static final int MAX_PENDING_MESSAGES = 300;
      private final StateHolder stateHolder;
      private final UUID responseId = UUID.randomUUID();
      private final UUID userId;
      private final ServerCallStreamObserver<T> responseObserver;
      private final BiFunction<SFLogAppender.SFLogEvent, Boolean, T> messageFactory;
      private final EventPredicate eventPredicate;
      private final ArrayDeque<PendingLog> pending = new ArrayDeque<>();
      private boolean open = true;
      private boolean draining;
      private Status terminalStatus;

      private ConnectionMessageSender(StateHolder stateHolder, UUID userId,
                                      ServerCallStreamObserver<T> responseObserver, EventPredicate eventPredicate,
                                      BiFunction<SFLogAppender.SFLogEvent, Boolean, T> messageFactory) {
        this.stateHolder = stateHolder;
        this.userId = userId;
        this.responseObserver = responseObserver;
        this.eventPredicate = eventPredicate;
        this.messageFactory = messageFactory;
        responseObserver.setOnCancelHandler(this::cancel);
        responseObserver.setOnCloseHandler(this::cancel);
        responseObserver.setOnReadyHandler(this::drain);
        synchronized (this) {
          if (open && !responseObserver.isCancelled()) stateHolder.subscribers.put(responseId, this);
          else cancel();
        }
      }

      private synchronized void sendMessage(SFLogAppender.SFLogEvent event, boolean personal) {
        if (!open) return;
        try {
          if (responseObserver.isCancelled()) { cancel(); return; }
          if (!eventPredicate.test(event, personal)) return;
          if (pending.size() >= MAX_PENDING_MESSAGES) {
            fail(Status.RESOURCE_EXHAUSTED.withDescription("Log stream fell behind. Reconnect to resume.").asRuntimeException());
            return;
          }
          pending.addLast(new PendingLog(event, personal));
          drain();
        } catch (RuntimeException error) { fail(error); }
      }

      private synchronized void drain() {
        if (!open || draining) return;
        draining = true;
        try {
          while (open && !pending.isEmpty()) {
            if (responseObserver.isCancelled()) { cancel(); return; }
            if (!responseObserver.isReady()) return;
            var next = pending.removeFirst();
            // Permissions may change while the transport cannot accept messages.
            if (eventPredicate.test(next.event, next.personal)) {
              responseObserver.onNext(messageFactory.apply(next.event, next.personal));
            }
          }
        } catch (RuntimeException error) { fail(error); }
        finally {
          draining = false;
          deliverTerminal();
        }
      }

      private synchronized void cancel() { close(); }

      private boolean close() {
        if (!open) return false;
        open = false;
        pending.clear();
        stateHolder.subscribers.remove(responseId, this);
        return true;
      }

      private synchronized void complete() {
        if (!close()) return;
        terminalStatus = Status.OK;
        deliverTerminal();
      }

      private void fail(RuntimeException error) {
        if (!close()) return;
        var status = Status.fromThrowable(error);
        terminalStatus = status.getCode() == Status.Code.UNKNOWN
          ? Status.UNAVAILABLE.withDescription("Log stream ended").withCause(error)
          : status;
        deliverTerminal();
      }

      private void deliverTerminal() {
        if (draining || terminalStatus == null) return;
        var status = terminalStatus;
        terminalStatus = null;
        try {
          if (!responseObserver.isCancelled()) {
            if (status.isOk()) responseObserver.onCompleted();
            else responseObserver.onError(status.asRuntimeException());
          }
        } catch (RuntimeException ignored) { /* A failed observer must not disrupt another viewer. */ }
      }

      private record PendingLog(SFLogAppender.SFLogEvent event, boolean personal) {}
    }
  }
}
