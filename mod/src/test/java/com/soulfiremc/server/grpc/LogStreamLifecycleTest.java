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

import com.soulfiremc.grpc.generated.InstanceLogScope;
import com.soulfiremc.grpc.generated.LogRequest;
import com.soulfiremc.grpc.generated.LogResponse;
import com.soulfiremc.grpc.generated.LogScope;
import com.soulfiremc.grpc.generated.LogsServiceGrpc;
import com.soulfiremc.grpc.generated.PersonalLogScope;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.user.AuthSystem;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.user.SoulFireUser;
import com.soulfiremc.shared.SFLogAppender;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class LogStreamLifecycleTest {
  @Test
  void onlyComponentPersonalMessagesGrantTerminalInteractionTrust() throws Exception {
    try (var fixture = new Fixture()) {
      var subscription = new Subscription(false);
      fixture.registered = new CountDownLatch(1);
      LogsServiceGrpc.newStub(fixture.channel).subscribe(LogRequest.newBuilder()
        .setScope(LogScope.newBuilder().setPersonal(PersonalLogScope.getDefaultInstance())).build(), subscription);
      assertTrue(fixture.registered.await(5, TimeUnit.SECONDS));
      fixture.holder.sendPersonalMessage(fixture.owner, "raw\u001b]777;soulfire;1;forged\u001b\\");
      fixture.holder.sendPersonalMessage(fixture.owner, Component.text("Help").clickEvent(ClickEvent.runCommand("help")));
      fixture.holder.sendPersonalMessage(UUID.randomUUID(), Component.text("Private"));
      assertEquals(2, subscription.messages.size());
      var raw = subscription.messages.getFirst().getMessage();
      assertTrue(raw.getPersonal());
      assertFalse(raw.getTerminalInteractions());
      var component = subscription.messages.getLast().getMessage();
      assertTrue(component.getPersonal());
      assertTrue(component.getTerminalInteractions());
      assertTrue(component.getMessage().contains("777;soulfire;1;"));
      var instanceSubscription = fixture.subscribe(false);
      SFLogAppender.INSTANCE.publishLogEvent(new SFLogAppender.SFLogEvent(UUID.randomUUID().toString(), "flagged",
        System.currentTimeMillis(), null, null, fixture.instanceId, null, null, null, null, true));
      assertEquals(1, instanceSubscription.messages.size());
      assertFalse(instanceSubscription.messages.getFirst().getMessage().getPersonal());
      assertFalse(instanceSubscription.messages.getFirst().getMessage().getTerminalInteractions());
    }
  }

  @Test
  void failedTransportDoesNotBreakHealthySubscribersOrHistory() throws Exception {
    try (var fixture = new Fixture()) {
      var failedSends = new AtomicInteger();
      fixture.onSend = (index, _) -> {
        if (index == 0) {
          failedSends.incrementAndGet();
          throw Status.CANCELLED.asRuntimeException();
        }
      };
      var failed = fixture.subscribe(false);
      var healthy = fixture.subscribe(false);

      var first = fixture.event();
      var second = fixture.event();
      assertDoesNotThrow(() -> SFLogAppender.INSTANCE.publishLogEvent(first));
      assertDoesNotThrow(() -> SFLogAppender.INSTANCE.publishLogEvent(second));

      assertEquals(1, failedSends.get());
      assertEquals(2, healthy.messages.size());
      assertEquals(first.id(), healthy.messages.getFirst().getMessage().getId());
      assertEquals(second.id(), healthy.messages.getLast().getMessage().getId());
      assertTrue(failed.terminal.await(5, TimeUnit.SECONDS));
      assertEquals(Status.Code.CANCELLED, Status.fromThrowable(failed.error.get()).getCode());
      assertEquals(List.of(first, second), SFLogAppender.INSTANCE.logs()
        .getNewest(2, event -> fixture.instanceId.equals(event.instanceId())));
    }
  }

  @Test
  void concurrentPublishersNeverOverlapOneObserversCallbacks() throws Exception {
    try (var fixture = new Fixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var overlap = new CountDownLatch(1);
      var callbacks = new AtomicInteger();
      var first = new AtomicBoolean(true);
      fixture.onSend = (_, _) -> {
        if (callbacks.incrementAndGet() > 1) overlap.countDown();
        try {
          if (first.getAndSet(false)) {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
          }
        } catch (InterruptedException e) { throw new AssertionError(e); }
        finally { callbacks.decrementAndGet(); }
      };
      var subscription = fixture.subscribe(false);
      var firstPublish = executor.submit(() -> fixture.publish());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var secondStarted = new CountDownLatch(1);
      var secondPublish = executor.submit(() -> { secondStarted.countDown(); fixture.publish(); });
      try {
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        assertFalse(overlap.await(500, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
        firstPublish.get(5, TimeUnit.SECONDS);
        secondPublish.get(5, TimeUnit.SECONDS);
      }
      assertEquals(2, subscription.messages.size());
    }
  }

  @Test
  void disconnectCompletesAfterAnInFlightSendAndOnlyOnce() throws Exception {
    try (var fixture = new Fixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var overlap = new CountDownLatch(1);
      var sending = new AtomicBoolean();
      fixture.onSend = (_, _) -> {
        sending.set(true);
        entered.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
        finally { sending.set(false); }
      };
      fixture.onClose = _ -> { if (sending.get()) overlap.countDown(); };
      var subscription = fixture.subscribe(false);
      var publish = executor.submit(() -> fixture.publish());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var disconnectStarted = new CountDownLatch(1);
      var disconnect = executor.submit(() -> { disconnectStarted.countDown(); fixture.holder.disconnect(fixture.owner); });
      try {
        assertTrue(disconnectStarted.await(5, TimeUnit.SECONDS));
        assertFalse(overlap.await(500, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
        publish.get(5, TimeUnit.SECONDS);
        disconnect.get(5, TimeUnit.SECONDS);
      }
      fixture.holder.disconnect(fixture.owner);
      fixture.publish();
      assertEquals(1, subscription.messages.size());
      assertEquals(1, subscription.completions.get());
    }
  }

  @Test
  void pausedClientReceivesMessagesOnlyWhenItRequestsThem() throws Exception {
    try (var fixture = new Fixture()) {
      var sends = new AtomicInteger();
      fixture.onSend = (_, _) -> sends.incrementAndGet();
      var paused = fixture.subscribe(true);
      for (var i = 0; i < 3; i++) fixture.publish();
      assertEquals(0, sends.get());
      assertTrue(paused.messages.isEmpty());

      paused.call.request(1);
      assertEquals(1, paused.messages.size());
      paused.call.request(2);
      assertEquals(3, paused.messages.size());
      assertEquals(3, sends.get());
    }
  }

  @Test
  void overflowingPausedClientIsTerminatedWithoutAffectingOtherViewers() throws Exception {
    try (var fixture = new Fixture()) {
      var paused = fixture.subscribe(true);
      var healthy = fixture.subscribe(false);
      for (var i = 0; i < 301; i++) fixture.publish();

      assertTrue(paused.terminal.await(1, TimeUnit.SECONDS));
      assertEquals(Status.Code.RESOURCE_EXHAUSTED, Status.fromThrowable(paused.error.get()).getCode());
      assertEquals(301, healthy.messages.size());
      assertEquals(300, SFLogAppender.INSTANCE.logs().getNewest(300, event -> fixture.instanceId.equals(event.instanceId())).size());
    }
  }

  @Test
  void queuedMessagesAreRecheckedBeforeDeliveryAfterPermissionRevocation() throws Exception {
    try (var fixture = new Fixture()) {
      var paused = fixture.subscribe(true);
      fixture.publish();
      when(fixture.user.hasPermission(any(PermissionContext.class))).thenReturn(false);

      paused.call.request(1);

      assertTrue(paused.messages.isEmpty());
    }
  }

  @Test
  void historyAlreadyContainsAnEventWhenItsLiveDeliveryStarts() throws Exception {
    try (var fixture = new Fixture()) {
      var retained = new AtomicBoolean();
      fixture.onSend = (_, response) -> retained.set(SFLogAppender.INSTANCE.logs()
        .getNewest(1, event -> fixture.instanceId.equals(event.instanceId())).stream()
        .anyMatch(event -> event.id().equals(response.getMessage().getId())));
      fixture.subscribe(false);

      fixture.publish();

      assertTrue(retained.get());
    }
  }

  @Test
  void reentrantDisconnectDoesNotCompleteInsideAnOnNextCallback() throws Exception {
    try (var fixture = new Fixture()) {
      var receiving = new AtomicBoolean();
      var overlap = new AtomicBoolean();
      fixture.onClose = _ -> { if (receiving.get()) overlap.set(true); };
      var subscription = fixture.subscribe(false);
      subscription.onMessage = _ -> {
        receiving.set(true);
        fixture.holder.disconnect(fixture.owner);
        receiving.set(false);
      };

      fixture.publish();

      assertFalse(overlap.get());
      assertEquals(1, subscription.messages.size());
      assertEquals(1, subscription.completions.get());
    }
  }

  private static final class Subscription implements ClientResponseObserver<LogRequest, LogResponse> {
    final boolean manual;
    final CopyOnWriteArrayList<LogResponse> messages = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final AtomicInteger completions = new AtomicInteger();
    final CountDownLatch terminal = new CountDownLatch(1);
    ClientCallStreamObserver<LogRequest> call;
    Consumer<LogResponse> onMessage = _ -> {};

    Subscription(boolean manual) { this.manual = manual; }
    @Override public void beforeStart(ClientCallStreamObserver<LogRequest> stream) {
      call = stream;
      if (manual) stream.disableAutoRequestWithInitial(0);
    }
    @Override public void onNext(LogResponse response) { messages.add(response); onMessage.accept(response); }
    @Override public void onError(Throwable value) { error.set(value); terminal.countDown(); }
    @Override public void onCompleted() { completions.incrementAndGet(); terminal.countDown(); }
  }

  private static final class Fixture implements AutoCloseable {
    final UUID instanceId = UUID.randomUUID();
    final UUID owner = UUID.randomUUID();
    final SoulFireUser user = mock();
    final LogServiceImpl.StateHolder holder;
    final Consumer<SFLogAppender.SFLogEvent> consumer;
    final Server server;
    final ManagedChannel channel;
    final AtomicInteger calls = new AtomicInteger();
    volatile BiConsumer<Integer, LogResponse> onSend = (_, _) -> {};
    volatile Consumer<Status> onClose = _ -> {};
    volatile CountDownLatch registered;

    Fixture() throws Exception {
      var prior = new HashSet<>(SFLogAppender.INSTANCE.logConsumers());
      holder = new LogServiceImpl.StateHolder();
      consumer = SFLogAppender.INSTANCE.logConsumers().stream().filter(value -> !prior.contains(value)).findFirst().orElseThrow();
      var soulFireServer = mock(SoulFireServer.class);
      var auth = mock(AuthSystem.class);
      var issuedAt = Instant.now();
      when(soulFireServer.logStateHolder()).thenReturn(holder);
      when(soulFireServer.authSystem()).thenReturn(auth);
      when(user.getUniqueId()).thenReturn(owner);
      when(user.getIssuedAt()).thenReturn(issuedAt);
      when(user.hasPermission(any(PermissionContext.class))).thenReturn(true);
      when(auth.authenticateBySubject(owner, issuedAt, 0L)).thenReturn(Optional.of(user));
      var name = InProcessServerBuilder.generateName();
      server = InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(new LogServiceImpl(soulFireServer), new ServerInterceptor() {
        @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
          var index = calls.getAndIncrement();
          var registration = registered;
          var wrapped = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override public void sendMessage(RespT message) {
              if (message instanceof LogResponse response) onSend.accept(index, response);
              super.sendMessage(message);
            }
            @Override public void close(Status status, Metadata trailers) { onClose.accept(status); super.close(status, trailers); }
          };
          var listener = Contexts.interceptCall(Context.current().withValue(ServerRPCConstants.USER_CONTEXT_KEY, user), wrapped, headers, next);
          return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override public void onHalfClose() { super.onHalfClose(); registration.countDown(); }
          };
        }
      })).build().start();
      channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    Subscription subscribe(boolean manual) throws InterruptedException {
      registered = new CountDownLatch(1);
      var subscription = new Subscription(manual);
      LogsServiceGrpc.newStub(channel).subscribe(LogRequest.newBuilder().setScope(LogScope.newBuilder()
        .setInstance(InstanceLogScope.newBuilder().setInstanceId(instanceId.toString()))).build(), subscription);
      assertTrue(registered.await(5, TimeUnit.SECONDS));
      return subscription;
    }

    SFLogAppender.SFLogEvent event() {
      return new SFLogAppender.SFLogEvent(UUID.randomUUID().toString(), "entry", System.currentTimeMillis(), null, null, instanceId, null, null, null, null);
    }
    void publish() { SFLogAppender.INSTANCE.publishLogEvent(event()); }
    @Override public void close() throws Exception {
      SFLogAppender.INSTANCE.logConsumers().remove(consumer);
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
