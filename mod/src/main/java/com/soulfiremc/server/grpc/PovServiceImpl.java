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
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.soulfiremc.grpc.generated.InstancePermission;
import com.soulfiremc.grpc.generated.PovFrame;
import com.soulfiremc.grpc.generated.PovInputEvent;
import com.soulfiremc.grpc.generated.PovInputRequest;
import com.soulfiremc.grpc.generated.PovInputResponse;
import com.soulfiremc.grpc.generated.PovServiceGrpc;
import com.soulfiremc.grpc.generated.PovStreamFeedback;
import com.soulfiremc.grpc.generated.PovWatchRequest;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotControlLeaseManager;
import com.soulfiremc.server.renderer.AdaptivePovQuality;
import com.soulfiremc.server.renderer.PovClientActions;
import com.soulfiremc.server.renderer.PovVideoEncoder;
import com.soulfiremc.server.renderer.VulkanRenderer;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.user.SoulFireUser;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.input.KeyEvent;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Authenticated live video over gRPC-Web with ordered, session-scoped WebSocket input.
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
      var session = new Session(sessionId, bot, user, leases, lease.token(), observer,
        request.getWidth(), request.getHeight(), request.getMaxFps(), request.getCodecsList());
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
      applyInput(sessions.get(UUID.fromString(request.getSessionId())), request, ServerRPCConstants.USER_CONTEXT_KEY.get());
      response.onNext(PovInputResponse.getDefaultInstance());
      response.onCompleted();
    } catch (Throwable error) { response.onError(rpcError(error)); }
  }

  private void applyInput(Session session, PovInputRequest request, SoulFireUser user) throws Exception {
    if (session == null || session.closed.get()) throw Status.NOT_FOUND.withDescription("POV session ended").asRuntimeException();
    if (!session.owner.equals(user.getUniqueId())) throw Status.PERMISSION_DENIED.asRuntimeException();
    user.hasPermissionOrThrow(PermissionContext.instance(
      InstancePermission.CONTROL_BOT_ACTIONS, session.bot.instanceManager().id()));
    dimensions(request.getWidth(), request.getHeight());
    if (request.getMaxFps() != 0 && (request.getMaxFps() < 15 || request.getMaxFps() > 120)) throw new IllegalArgumentException("Invalid target FPS");
    if (request.getEventsCount() > 128) throw new IllegalArgumentException("Too many input events");
    for (var event : request.getEventsList()) validate(event);
    if (request.hasFeedback()) {
      var feedback = request.getFeedback();
      if (!Double.isFinite(feedback.getDeliveryDelayMs()) || feedback.getDeliveryDelayMs() < 0
        || feedback.getDecoderQueueSize() < 0 || feedback.getDecoderRecoveries() < 0
        || feedback.getReceivedSequence() < 0) throw new IllegalArgumentException("Invalid stream feedback");
    }
    if (request.hasClipboard() && request.getClipboard().length() > 16_384) throw new IllegalArgumentException("Clipboard too large");
    synchronized (session) {
      if (request.getSequence() <= session.inputSequence) throw Status.ABORTED.withDescription("Stale input batch").asRuntimeException();
      session.leases.renew(session.bot.accountProfileId(), session.owner, session.token, Duration.ofSeconds(10));
      session.bot.minecraft().submit(() -> {
        if (session.closed.get()) return;

        if (request.hasClipboard()) {
          var minecraft = session.bot.minecraft();
          minecraft.keyboardHandler.setClipboard(request.getClipboard());
          if (request.getCaptured() && minecraft.gui.screen() != null) {
            minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), 1, new KeyEvent(86, 0, 2));
            minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), 0, new KeyEvent(86, 0, 2));
          }
        }
        var actions = new PovClientActions(text -> session.clipboard.set(new ClipboardUpdate(0, text)), session.openUrl::set);
        ScopedValue.where(PovClientActions.CURRENT, actions).run(() -> {
          if (request.getEscape()) {
            var minecraft = session.bot.minecraft();
            minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), 1, new KeyEvent(256, 0, 0));
            minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), 0, new KeyEvent(256, 0, 0));
          }
          session.bot.povInput().capture(request.getCaptured());
          for (var event : request.getEventsList()) session.bot.povInput().accept(event);
        });
        if (request.getReadClipboard()) {
          session.clipboard.set(new ClipboardUpdate(request.getSequence(), session.bot.minecraft().keyboardHandler.getClipboard()));
        }
      }).get(5, TimeUnit.SECONDS);
      session.inputSequence = request.getSequence();
      session.width = even(request.getWidth());
      session.height = even(request.getHeight());
      if (request.getMaxFps() != 0) session.maxFps = request.getMaxFps();
      if (request.getRequestKeyFrame()) session.keyFrameRequested.set(true);
      session.feedback = request.hasFeedback() ? request.getFeedback() : null;
      session.lastInput = System.nanoTime();
    }
  }

  /// The capability comes only from the authenticated Watch stream and expires with it.
  public WebSocketService inputChannel() {
    return WebSocketService.builder((ctx, incoming) -> {
      var outgoing = WebSocket.streaming();
      incoming.subscribe(new Subscriber<WebSocketFrame>() {
        private Subscription subscription;
        private Session bound;
        private boolean ended;
        @Override public void onSubscribe(Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(WebSocketFrame frame) {
          try {
            if (frame.type() == WebSocketFrameType.CLOSE) { onComplete(); return; }
            if (frame.type() == WebSocketFrameType.PING) {
              if (!outgoing.tryWrite(WebSocketFrame.ofPong(frame.array()))) { onComplete(); return; }
              subscription.request(1);
              return;
            }
            if (frame.type() == WebSocketFrameType.PONG) { subscription.request(1); return; }
            if (frame.type() != WebSocketFrameType.BINARY) throw new IllegalArgumentException("Binary input required");
            var request = PovInputRequest.parseFrom(frame.array());
            var session = sessions.get(UUID.fromString(request.getSessionId()));
            if (session == null || !MessageDigest.isEqual(session.inputToken.getBytes(StandardCharsets.UTF_8),
              request.getInputToken().getBytes(StandardCharsets.UTF_8))) throw Status.PERMISSION_DENIED.asRuntimeException();
            if (bound == null) {
              synchronized (session) {
                if (session.inputChannel != null || session.closed.get()) throw Status.ALREADY_EXISTS.asRuntimeException();
                session.inputChannel = outgoing;
              }
              bound = session;
            }
            if (bound != session) throw Status.PERMISSION_DENIED.asRuntimeException();
            ctx.blockingTaskExecutor().execute(() -> {
              try {
                applyInput(session, request, session.user);
                ctx.eventLoop().execute(() -> {
                  if (!outgoing.tryWrite(WebSocketFrame.ofText(Long.toString(request.getSequence())))) { onComplete(); return; }
                  subscription.request(1);
                });
              } catch (Throwable error) { ctx.eventLoop().execute(() -> onError(error)); }
            });
          } catch (Throwable error) { onError(error); }
        }
        @Override public void onError(Throwable error) {
          if (ended) return;
          ended = true;
          subscription.cancel();
          if (bound != null) { bound.close(); if (!bound.observer.isCancelled()) bound.observer.onCompleted(); }
          outgoing.close(error);
        }
        @Override public void onComplete() {
          if (ended) return;
          ended = true;
          subscription.cancel();
          if (bound != null) { bound.close(); if (!bound.observer.isCancelled()) bound.observer.onCompleted(); }
          outgoing.close();
        }
      }, ctx.eventLoop());
      return outgoing;
    }).allowedOrigins("*").aggregateContinuation(true).maxFramePayloadLength(65_536)
      .streamTimeout(Duration.ofSeconds(5)).build();
  }

  private static int even(int size) { return (size + 1) & ~1; }

  private static void dimensions(int width, int height) {
    if (width < 1 || height < 1 || width > 3840 || height > 2160) throw new IllegalArgumentException("Invalid POV dimensions");
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
    private final SoulFireUser user;
    private final String inputToken = UUID.randomUUID().toString() + UUID.randomUUID();
    private volatile WebSocketWriter inputChannel;
    private final BotControlLeaseManager leases;
    private final String token;
    private final ServerCallStreamObserver<PovFrame> observer;
    private final AtomicBoolean rendering = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object encoderLock = new Object();
    private final AtomicBoolean keyFrameRequested = new AtomicBoolean(true);
    private final long startedAt = System.nanoTime();
    private PovVideoEncoder encoder;
    private boolean previousScreenOpen;
    private PovFrame.CursorShape previousCursor = PovFrame.CursorShape.ARROW;
    private long previousTimestamp;
    private final VulkanRenderer.LiveReadback readback = new VulkanRenderer.LiveReadback();
    private AdaptivePovQuality adaptation;
    private volatile PovStreamFeedback feedback;
    private volatile long lastInput = System.nanoTime();
    private long inputSequence;
    private final AtomicReference<String> openUrl = new AtomicReference<>();
    private final AtomicReference<ClipboardUpdate> clipboard = new AtomicReference<>();
    private long frameSequence;
    private volatile int width;
    private volatile int height;
    private volatile int maxFps;
    private int targetFps;
    private final PovVideoEncoder.Format format;
    private int targetWidth;
    private int targetHeight;

    private Session(UUID id, BotConnection bot, SoulFireUser user, BotControlLeaseManager leases, String token,
                    ServerCallStreamObserver<PovFrame> observer, int width, int height, int maxFps, List<String> codecs) {
      this.id = id; this.bot = bot; this.user = user; this.owner = user.getUniqueId(); this.leases = leases; this.token = token;
      this.observer = observer; this.width = even(width); this.height = even(height);
      this.format = codecs.contains("av1") ? PovVideoEncoder.Format.AV1 : codecs.contains("h264-high") ? PovVideoEncoder.Format.HIGH : PovVideoEncoder.Format.BASELINE;
      this.maxFps = Math.clamp(maxFps == 0 ? 60 : maxFps, 15, 120);
    }

    private void schedule(long delay) { server.scheduler().schedule(this::frame, delay, TimeUnit.NANOSECONDS); }

    private void frame() {
      if (closed.get()) return;
      rendering.set(true);
      var started = System.nanoTime();
      try {
        if (observer.isCancelled()) { close(); return; }
        if (started - lastInput > 5_000_000_000L) throw Status.DEADLINE_EXCEEDED.withDescription("POV heartbeat timed out").asRuntimeException();
        if (adaptation == null || targetWidth != width || targetHeight != height || targetFps != maxFps) {
          targetWidth = width; targetHeight = height; targetFps = maxFps;
          var fps = Math.min(maxFps, (int) (530_000_000L / ((long) width * height)) / 5 * 5);
          adaptation = new AdaptivePovQuality(width, height, fps, started);
        }
        adaptation.update(feedback, observer.isReady(), started);
        if (!observer.isReady()) return;
        var captureFuture = bot.povFrameTasks().submit(() -> {
          if (closed.get()) return null;
          var minecraft = bot.minecraft();
          // A respawn or dimension transfer temporarily removes the world and player.
          if (minecraft.player == null || minecraft.level == null) return null;
          var image = VulkanRenderer.renderInteractive(minecraft, adaptation.width(), adaptation.height(), readback);
          var cursorShape = switch (minecraft.getWindow().currentCursor.toString()) {
            case "ibeam" -> PovFrame.CursorShape.TEXT;
            case "crosshair" -> PovFrame.CursorShape.CROSSHAIR;
            case "pointing_hand" -> PovFrame.CursorShape.POINTER;
            case "resize_ns" -> PovFrame.CursorShape.RESIZE_NS;
            case "resize_ew" -> PovFrame.CursorShape.RESIZE_EW;
            case "resize_all" -> PovFrame.CursorShape.RESIZE_ALL;
            case "not_allowed" -> PovFrame.CursorShape.NOT_ALLOWED;
            default -> PovFrame.CursorShape.ARROW;
          };
          var timestamp = (started - startedAt) / 1000;
          var captured = new Captured(image, readback.pipelined() ? previousScreenOpen : minecraft.gui.screen() != null,
            readback.pipelined() ? previousCursor : cursorShape, readback.pipelined() ? previousTimestamp : timestamp);
          previousScreenOpen = minecraft.gui.screen() != null;
          previousCursor = cursorShape;
          previousTimestamp = timestamp;
          return image == null ? null : captured;
        });
        Captured frame;
        try { frame = captureFuture.get(30, TimeUnit.SECONDS); }
        catch (Throwable error) {
          captureFuture.thenAccept(late -> { if (late != null) late.image.close(); });
          throw error;
        }
        if (frame == null) return;
        try (var image = frame.image) {
          synchronized (encoderLock) {
            if (closed.get()) return;
            if (encoder == null || encoder.width() != image.width() || encoder.height() != image.height() || encoder.fps() != adaptation.fps()) {
              if (encoder != null) encoder.close();
              encoder = null;
              encoder = new PovVideoEncoder(image.width(), image.height(), adaptation.fps(), format);
            }
            encoder.bitrate(adaptation.bitrate());
            var encoded = encoder.encode(image.pixels(), frame.timestampUs, keyFrameRequested.getAndSet(false));
            var builder = PovFrame.newBuilder()
              .setTargetFps(adaptation.fps()).setInputToken(inputToken).setData(ByteString.copyFrom(encoded.data())).setTimestampUs(encoded.timestampUs())
              .setKeyFrame(encoded.keyFrame()).setCodec(encoded.codec())
              .setWidth(image.width()).setHeight(image.height()).setScreenOpen(frame.screenOpen)
              .setTargetBitrate((int) encoder.bitrate()).setCursorShape(frame.cursorShape).setSequence(++frameSequence);
            var url = openUrl.getAndSet(null);
            if (url != null) builder.setOpenUrl(url);
            var clipboardUpdate = clipboard.getAndSet(null);
            if (clipboardUpdate != null) builder.setClipboard(clipboardUpdate.text).setClipboardSequence(clipboardUpdate.sequence);
            if (!closed.get() && !observer.isCancelled()) observer.onNext(builder.build());
          }
        }
      } catch (Throwable error) {
        if (!closed.get() && !observer.isCancelled()) observer.onError(rpcError(error));
        close();
      } finally {
        rendering.set(false);
        if (closed.get()) bot.minecraft().execute(readback::close);
        if (adaptation != null) adaptation.recordFrame(System.nanoTime() - started);
        if (!closed.get()) schedule(Math.max(0, 1_000_000_000L / (adaptation == null ? 60 : adaptation.fps()) - (System.nanoTime() - started)));
      }
    }

    private void close() {
      if (!closed.compareAndSet(false, true)) return;
      sessions.remove(id, this);
      if (inputChannel != null) inputChannel.close();
      synchronized (encoderLock) {
        if (encoder != null) { encoder.close(); encoder = null; }
      }
      bot.minecraft().execute(() -> {
        bot.povInput().capture(false);
        bot.minecraft().keyboardHandler.setClipboard("");
        if (!rendering.get()) readback.close();
      });
      try { leases.release(bot.accountProfileId(), owner, token); }
      catch (BotControlLeaseManager.InvalidLeaseException ignored) { /* Expired leases already release ownership. */ }
    }
  }

  private record ClipboardUpdate(long sequence, String text) {}

  private record Captured(VulkanRenderer.RgbaFrame image, boolean screenOpen, PovFrame.CursorShape cursorShape, long timestampUs) {}
}
