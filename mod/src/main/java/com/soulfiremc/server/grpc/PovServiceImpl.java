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
import com.linecorp.armeria.server.ServiceRequestContext;
import com.soulfiremc.grpc.generated.InstancePermission;
import com.soulfiremc.grpc.generated.PovFrame;
import com.soulfiremc.grpc.generated.PovInputEvent;
import com.soulfiremc.grpc.generated.PovInputRequest;
import com.soulfiremc.grpc.generated.PovInputResponse;
import com.soulfiremc.grpc.generated.PovServiceGrpc;
import com.soulfiremc.grpc.generated.PovWatchRequest;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotControlLeaseManager;
import com.soulfiremc.server.renderer.VulkanRenderer;
import com.soulfiremc.server.user.PermissionContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.input.KeyEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// Bounded, demand-driven live frames and ordered native input over authenticated gRPC-Web.
@RequiredArgsConstructor
public final class PovServiceImpl extends PovServiceGrpc.PovServiceImplBase {
  private final SoulFireServer server;
  private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

  @Override
  public void watch(PovWatchRequest request, StreamObserver<PovFrame> response) {
    try {
      var instanceId = UUID.fromString(request.getInstanceId());
      var botId = UUID.fromString(request.getBotId());
      var sessionId = UUID.fromString(request.getSessionId());
      var user = ServerRPCConstants.USER_CONTEXT_KEY.get();
      user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId));
      user.hasPermissionOrThrow(PermissionContext.instance(InstancePermission.CONTROL_BOT_ACTIONS, instanceId));
      var instance = server.getInstance(instanceId).orElseThrow(() -> Status.NOT_FOUND.asRuntimeException());
      var bot = instance.botConnections().get(botId);
      if (bot == null) throw Status.FAILED_PRECONDITION.withDescription("Bot is offline").asRuntimeException();
      dimensions(request.getWidth(), request.getHeight());
      var leases = instance.botControlLeaseManager();
      var lease = leases.acquire(botId, user.getUniqueId(), Duration.ofSeconds(10));
      var observer = (ServerCallStreamObserver<PovFrame>) response;
      var session = new Session(sessionId, bot, user.getUniqueId(), leases, lease.token(), observer,
        request.getWidth(), request.getHeight());
      if (sessions.putIfAbsent(sessionId, session) != null) {
        leases.release(botId, user.getUniqueId(), lease.token());
        throw Status.ALREADY_EXISTS.asRuntimeException();
      }
      // The input heartbeat owns this long-lived stream's timeout.
      ServiceRequestContext.current().clearRequestTimeout();
      observer.setOnCancelHandler(session::close);
      session.schedule(0);
    } catch (Throwable error) { response.onError(rpcError(error)); }
  }

  @Override
  public void input(PovInputRequest request, StreamObserver<PovInputResponse> response) {
    try {
      var session = sessions.get(UUID.fromString(request.getSessionId()));
      if (session == null || session.closed.get()) throw Status.NOT_FOUND.withDescription("POV session ended").asRuntimeException();
      if (!session.owner.equals(ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId())) throw Status.PERMISSION_DENIED.asRuntimeException();
      ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(PermissionContext.instance(
        InstancePermission.CONTROL_BOT_ACTIONS, session.bot.instanceManager().id()));
      dimensions(request.getWidth(), request.getHeight());
      if (request.getEventsCount() > 128) throw new IllegalArgumentException("Too many input events");
      for (var event : request.getEventsList()) validate(event);
      synchronized (session) {
        if (request.getSequence() <= session.inputSequence) throw Status.ABORTED.withDescription("Stale input batch").asRuntimeException();
        session.leases.renew(session.bot.accountProfileId(), session.owner, session.token, Duration.ofSeconds(10));
        session.bot.minecraft().submit(() -> {
          if (session.closed.get()) return;
          VulkanRenderer.resize(session.bot.minecraft(), request.getWidth(), request.getHeight());
          if (request.getEscape()) {
            var minecraft = session.bot.minecraft();
            minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), 1, new KeyEvent(256, 0, 0));
            minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), 0, new KeyEvent(256, 0, 0));
          }
          session.bot.povInput().capture(request.getCaptured());
          for (var event : request.getEventsList()) session.bot.povInput().accept(event);
        }).get(5, TimeUnit.SECONDS);
        session.inputSequence = request.getSequence();
        session.width = request.getWidth();
        session.height = request.getHeight();
        session.lastInput = System.nanoTime();
      }
      response.onNext(PovInputResponse.getDefaultInstance());
      response.onCompleted();
    } catch (Throwable error) { response.onError(rpcError(error)); }
  }

  private static void dimensions(int width, int height) {
    if (width < 1 || height < 1 || width > 1920 || height > 1080) throw new IllegalArgumentException("Invalid POV dimensions");
  }

  static void validate(PovInputEvent event) {
    if (!Double.isFinite(event.getX()) || !Double.isFinite(event.getY())
      || Math.abs(event.getX()) > 10_000 || Math.abs(event.getY()) > 10_000
      || event.getAction() < 0 || event.getAction() > 2 || event.getModifiers() < 0 || event.getModifiers() > 63) {
      throw new IllegalArgumentException("Invalid input values");
    }
    switch (event.getKind()) {
      case KEY -> { if (event.getCode() < 32 || event.getCode() > 348) throw new IllegalArgumentException("Invalid key"); }
      case BUTTON -> { if (event.getCode() < 0 || event.getCode() > 7) throw new IllegalArgumentException("Invalid button"); }
      case CHARACTER -> { if (!Character.isValidCodePoint(event.getCode()) || event.getCode() < 32
        || event.getCode() >= Character.MIN_SURROGATE && event.getCode() <= Character.MAX_SURROGATE) throw new IllegalArgumentException("Invalid character"); }
      case MOVE -> {
        if (!event.getRelative() && (event.getX() < 0 || event.getX() > 1 || event.getY() < 0 || event.getY() > 1)) {
          throw new IllegalArgumentException("Invalid cursor position");
        }
      }
      case SCROLL -> { }
      case UNRECOGNIZED -> throw new IllegalArgumentException("Unknown input kind");
    }
  }

  private static RuntimeException rpcError(Throwable error) {
    if (error instanceof StatusRuntimeException status) return status;
    if (error instanceof IllegalArgumentException) return Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException();
    if (error instanceof BotControlLeaseManager.LeaseUnavailableException) return Status.RESOURCE_EXHAUSTED.withDescription(error.getMessage()).asRuntimeException();
    return Status.INTERNAL.withDescription("POV session failed").withCause(error).asRuntimeException();
  }

  private final class Session {
    private final UUID id;
    private final BotConnection bot;
    private final UUID owner;
    private final BotControlLeaseManager leases;
    private final String token;
    private final ServerCallStreamObserver<PovFrame> observer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastInput = System.nanoTime();
    private long inputSequence;
    private long frameSequence;
    private volatile int width;
    private volatile int height;

    private Session(UUID id, BotConnection bot, UUID owner, BotControlLeaseManager leases, String token,
                    ServerCallStreamObserver<PovFrame> observer, int width, int height) {
      this.id = id; this.bot = bot; this.owner = owner; this.leases = leases; this.token = token;
      this.observer = observer; this.width = width; this.height = height;
    }

    private void schedule(long delay) { server.scheduler().schedule(this::frame, delay, TimeUnit.MILLISECONDS); }

    private void frame() {
      if (closed.get()) return;
      var started = System.nanoTime();
      try {
        if (observer.isCancelled()) { close(); return; }
        if (started - lastInput > 5_000_000_000L) throw Status.DEADLINE_EXCEEDED.withDescription("POV heartbeat timed out").asRuntimeException();
        if (!observer.isReady()) return;
        var frame = bot.minecraft().submit(() -> {
          if (closed.get()) return null;
          var minecraft = bot.minecraft();
          // A respawn or dimension transfer temporarily removes the world and player.
          if (minecraft.player == null || minecraft.level == null) return null;
          var image = VulkanRenderer.renderInteractive(minecraft, width, height);
          return new Captured(image, minecraft.gui.screen() != null);
        }).get(5, TimeUnit.SECONDS);
        if (frame == null || closed.get()) return;
        var rgb = new BufferedImage(frame.image.getWidth(), frame.image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try { graphics.drawImage(frame.image, 0, 0, null); } finally { graphics.dispose(); }
        try (var bytes = new ByteArrayOutputStream()) {
          ImageIO.write(rgb, "jpeg", bytes);
          if (!closed.get() && !observer.isCancelled()) observer.onNext(PovFrame.newBuilder()
            .setImage(ByteString.copyFrom(bytes.toByteArray())).setMimeType("image/jpeg")
            .setWidth(rgb.getWidth()).setHeight(rgb.getHeight()).setScreenOpen(frame.screenOpen)
            .setSequence(++frameSequence).build());
        }
      } catch (Throwable error) {
        if (!closed.get() && !observer.isCancelled()) observer.onError(rpcError(error));
        close();
      } finally {
        if (!closed.get()) schedule(Math.max(0, 33 - (System.nanoTime() - started) / 1_000_000));
      }
    }

    private void close() {
      if (!closed.compareAndSet(false, true)) return;
      sessions.remove(id, this);
      bot.minecraft().execute(() -> bot.povInput().capture(false));
      try { leases.release(bot.accountProfileId(), owner, token); }
      catch (BotControlLeaseManager.InvalidLeaseException ignored) { /* Expired leases already release ownership. */ }
    }
  }

  private record Captured(BufferedImage image, boolean screenOpen) {}
}
