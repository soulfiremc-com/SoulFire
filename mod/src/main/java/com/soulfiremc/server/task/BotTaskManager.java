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
package com.soulfiremc.server.task;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.api.BotTaskProviderRegistration;
import com.soulfiremc.server.api.RegisteredPluginPermission;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotConnectedEvent;
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.bot.BotPostEntityTickEvent;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotThreadExecution;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.execution.UnreachableGoalException;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.user.SoulFireUser;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Owns the durable lifecycle, arbitration, observation, and cancellation of
/// core and plugin bot tasks.
@Slf4j
public final class BotTaskManager implements AutoCloseable {
  private static final int MAX_INPUT_BYTES = 4 * 1024 * 1024;
  private static final int MAX_PAGE_SIZE = 500;
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int JOURNAL_SIZE = 4096;

  private final SoulFireServer server;
  private final Map<String, ProviderEntry<?>> providers;
  private final Map<UUID, ManagedTask> tasks = new ConcurrentHashMap<>();
  private final Map<BotConnection, List<ManagedTask>> pendingRecoveries = new ConcurrentHashMap<>();
  private final Map<IdempotencyKey, UUID> idempotencyKeys = new ConcurrentHashMap<>();
  private final Set<Consumer<BotTaskEvent>> listeners = new CopyOnWriteArraySet<>();
  private final Deque<BotTaskEvent> journal = new ArrayDeque<>();
  private final AtomicLong eventSequence = new AtomicLong();
  private final Consumer<BotDisconnectedEvent> disconnectedListener =
    this::onBotDisconnected;
  private final Consumer<BotConnectedEvent> connectedListener =
    this::onBotConnected;
  private final Consumer<BotPostEntityTickEvent> readyListener =
    this::onBotReady;

  public BotTaskManager(SoulFireServer server) {
    this.server = server;
    var registered = new LinkedHashMap<String, ProviderEntry<?>>();
    coreProviders().forEach(provider -> registerCore(registered, provider));
    for (var pluginProvider : SoulFireAPI.pluginApis().taskProviders()) {
      registerPlugin(registered, pluginProvider);
    }
    this.providers = Map.copyOf(registered);
    SoulFireAPI.registerListener(BotDisconnectedEvent.class, disconnectedListener);
    SoulFireAPI.registerListener(BotConnectedEvent.class, connectedListener);
    SoulFireAPI.registerListener(BotPostEntityTickEvent.class, readyListener);
  }

  static List<BotTaskProvider<?>> coreProviders() {
    return List.of(
      new GoToTaskProvider(),
      new FollowEntityTaskProvider(),
      new AttackEntityTaskProvider(),
      new AttackNearestTaskProvider(),
      new RangedAttackTaskProvider(),
      new FleeTaskProvider(),
      new GuardTaskProvider(),
      new SleepTaskProvider(),
      new FishTaskProvider(),
      new FarmTaskProvider(),
      new BreedTaskProvider(),
      new ExploreTaskProvider(),
      new ContainerTransferTaskProvider(),
      new MaintainLoadoutTaskProvider(),
      new AutoEatTaskProvider(),
      new AutoRespawnTaskProvider(),
      new AutoTotemTaskProvider(),
      new AutoArmorTaskProvider(),
      new CollectBlocksTaskProvider(),
      new ExcavateTaskProvider(),
      new BuildTaskProvider(),
      new CraftTaskProvider(),
      new SmeltTaskProvider(),
      new BrewTaskProvider(),
      new VillagerTradeTaskProvider()
    );
  }

  public BotTask start(StartBotTaskRequest request, SoulFireUser owner) {
    var instanceId = parseUuid(request.getInstanceId(), "instance_id");
    var botId = parseUuid(request.getBotId(), "bot_id");
    var input = request.getInput();
    if (input.getTypeUrl().isBlank()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("input.type_url must be set")
        .asRuntimeException();
    }
    if (input.getValue().size() > MAX_INPUT_BYTES) {
      throw Status.RESOURCE_EXHAUSTED
        .withDescription("Task input exceeds %d bytes".formatted(MAX_INPUT_BYTES))
        .asRuntimeException();
    }
    var canonicalInput = input.toBuilder()
      .setTypeUrl(canonicalTypeUrl(input.getTypeUrl()))
      .build();
    var provider = providers.get(canonicalInput.getTypeUrl());
    if (provider == null) {
      throw Status.INVALID_ARGUMENT
        .withDescription("No task provider is registered for '%s'".formatted(input.getTypeUrl()))
        .asRuntimeException();
    }

    var instance = server.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
    var bot = instance.botConnections().get(botId);
    if (bot == null || bot.isDisconnected() || bot.isDisconnecting()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot '%s' is not online".formatted(botId))
        .asRuntimeException();
    }

    var taskId = UUID.randomUUID();
    provider.authorize(owner, instanceId, botId, taskId);
    var prepared = provider.prepare(input);
    var deadline = request.hasDeadline()
      ? Optional.of(toInstant(request.getDeadline()))
      : Optional.<Instant>empty();
    if (deadline.isPresent() && !deadline.orElseThrow().isAfter(Instant.now())) {
      throw Status.INVALID_ARGUMENT
        .withDescription("deadline must be in the future")
        .asRuntimeException();
    }
    var idempotencyKey = normalizeIdempotencyKey(request);
    if (idempotencyKey.isPresent()) {
      var key = new IdempotencyKey(owner.getUniqueId(), instanceId, botId, idempotencyKey.orElseThrow());
      var existingId = idempotencyKeys.get(key);
      if (existingId != null) {
        var existing = tasks.get(existingId);
        if (existing != null) {
          if (!existing.input.equals(canonicalInput)) {
            throw Status.ALREADY_EXISTS
              .withDescription("Idempotency key was already used with different task input")
              .asRuntimeException();
          }
          return existing.snapshot();
        }
      }
    }

    var parentTaskId = request.hasParentTaskId()
      ? Optional.of(parseUuid(request.getParentTaskId(), "parent_task_id"))
      : Optional.<UUID>empty();
    var record = new ManagedTask(
      taskId,
      instanceId,
      botId,
      owner,
      canonicalInput.getTypeUrl(),
      canonicalInput,
      prepared.summary(),
      prepared.resources(),
      defaultConflictPolicy(request.getConflictPolicy()),
      defaultReconnectPolicy(request.getReconnectPolicy()),
      defaultDisconnectPolicy(request.getDisconnectPolicy()),
      defaultPriority(request.getPriority()),
      deadline,
      parentTaskId,
      optionalNonBlank(request.hasCausationId(), request.getCausationId()),
      idempotencyKey,
      prepared
    );

    synchronized (this) {
      if (bot.isDisconnected() || bot.isDisconnecting()) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bot connection is closing")
          .asRuntimeException();
      }
      parentTaskId.ifPresent(parentId -> attachParent(record, parentId, owner));
      tasks.put(taskId, record);
      idempotencyKey.ifPresent(value ->
        idempotencyKeys.put(
          new IdempotencyKey(owner.getUniqueId(), instanceId, botId, value),
          taskId
        ));
      publish(record);
    }

    startExecution(record, bot);

    deadline.ifPresent(value -> {
      var delayMillis = Math.max(0, value.toEpochMilli() - System.currentTimeMillis());
      server.scheduler().schedule(
        () -> timeout(record),
        delayMillis,
        TimeUnit.MILLISECONDS
      );
    });
    return record.snapshot();
  }

  public BotTask get(UUID taskId) {
    return require(taskId).snapshot();
  }

  public ListResult list(
    Optional<UUID> instanceId,
    Optional<UUID> botId,
    Set<BotTaskStatus> statuses,
    boolean includeTerminal,
    int requestedPageSize,
    String pageToken,
    SoulFireUser user
  ) {
    if (botId.isPresent() && instanceId.isEmpty()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("bot_id requires instance_id")
        .asRuntimeException();
    }
    var offset = decodePageToken(pageToken);
    var pageSize = requestedPageSize <= 0
      ? DEFAULT_PAGE_SIZE
      : Math.min(requestedPageSize, MAX_PAGE_SIZE);
    var instanceAccess = new HashMap<UUID, Boolean>();
    var matching = tasks.values().stream()
      .filter(task -> instanceId.map(task.instanceId::equals).orElse(true))
      .filter(task -> botId.map(task.botId::equals).orElse(true))
      .filter(task -> statuses.isEmpty() || statuses.contains(task.status))
      .filter(task -> includeTerminal || !isTerminal(task.status))
      .filter(task -> instanceAccess.computeIfAbsent(task.instanceId, id -> user.hasPermission(PermissionContext.instance(
        InstancePermission.READ_BOT_INFO,
        id
      ))))
      .sorted(Comparator
        .comparing((ManagedTask task) -> task.createdAt)
        .reversed()
        .thenComparing(task -> task.taskId))
      .toList();
    if (offset > matching.size()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("page_token is outside the result set")
        .asRuntimeException();
    }
    var toIndex = Math.min(matching.size(), offset + pageSize);
    var page = matching.subList(offset, toIndex).stream()
      .map(ManagedTask::snapshot)
      .toList();
    var next = toIndex < matching.size() ? encodePageToken(toIndex) : "";
    return new ListResult(page, next);
  }

  public BotTask cancel(UUID taskId, String reason) {
    var record = require(taskId);
    cancel(record, BotTaskStatus.BOT_TASK_STATUS_CANCELLED, reason);
    return record.snapshot();
  }

  public AutoCloseable subscribe(Consumer<BotTaskEvent> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  public synchronized List<BotTaskEvent> eventsAfter(long sequence) {
    return journal.stream()
      .filter(event -> event.getSequence() > sequence)
      .toList();
  }

  public long earliestRetainedSequence() {
    synchronized (this) {
      return journal.isEmpty()
        ? eventSequence.get() + 1
        : journal.getFirst().getSequence();
    }
  }

  public long latestSequence() {
    return eventSequence.get();
  }

  private void startExecution(ManagedTask record, BotConnection bot) {
    long generation;
    synchronized (this) {
      if (isTerminal(record.status) || bot.isDisconnecting()) {
        return;
      }
      generation = ++record.generation;
    }

    BotTaskExecution execution;
    try {
      var context = new BotTaskContext(
        server,
        bot,
        record.owner,
        record.taskId,
        record.instanceId,
        record.botId,
        record.deadline,
        progress -> updateProgress(
          record,
          generation,
          record.prepared.validateProgress(progress)
        )
      );
      execution = BotThreadExecution.call(
        bot,
        () -> record.prepared.start(context)
      );
    } catch (Throwable throwable) {
      // The disconnect notification applies this task's reconnect policy.
      if (!bot.isDisconnecting()) {
        fail(record, generation, "provider_start_failed", unwrap(throwable), false);
      }
      return;
    }

    var control = new ManagedControlTask(record, execution, generation);
    var discard = false;
    synchronized (this) {
      if (isTerminal(record.status) || generation != record.generation) {
        discard = true;
      } else {
        record.control = control;
        if (record.status == BotTaskStatus.BOT_TASK_STATUS_RECOVERING) {
          record.status = BotTaskStatus.BOT_TASK_STATUS_QUEUED;
          publish(record);
        }
      }
    }
    if (discard) {
      control.discard();
      return;
    }
    try {
      BotThreadExecution.call(bot, () -> {
        schedule(record, bot, control);
        return null;
      });
    } catch (Throwable throwable) {
      if (!bot.isDisconnecting()) {
        fail(record, generation, "scheduling_failed", unwrap(throwable), true);
      }
      control.discard();
    }
  }

  private void schedule(
    ManagedTask record,
    BotConnection bot,
    ManagedControlTask control
  ) {
    var accepted = switch (record.conflictPolicy) {
      case BOT_TASK_CONFLICT_POLICY_REJECT -> bot.botControl().tryStart(control);
      case BOT_TASK_CONFLICT_POLICY_QUEUE -> {
        if (bot.botControl().tryStart(control)) {
          yield true;
        }
        updateStatus(record, BotTaskStatus.BOT_TASK_STATUS_WAITING_FOR_RESOURCES);
        bot.botControl().enqueue(control);
        yield true;
      }
      case BOT_TASK_CONFLICT_POLICY_REPLACE -> {
        bot.botControl().replace(control);
        yield true;
      }
      case BOT_TASK_CONFLICT_POLICY_SUSPEND_LOWER_PRIORITY ->
        bot.botControl().submit(control);
      case BOT_TASK_CONFLICT_POLICY_UNSPECIFIED, UNRECOGNIZED ->
        throw new IllegalStateException("Conflict policy was not normalized");
    };
    if (!accepted) {
      fail(
        record,
        control.generation,
        "resource_conflict",
        new IllegalStateException("Required bot resources are busy"),
        true
      );
      control.cancelBeforeStart();
    }
  }

  private synchronized void attachParent(
    ManagedTask child,
    UUID parentId,
    SoulFireUser owner
  ) {
    var parent = require(parentId);
    if (!parent.ownerId.equals(owner.getUniqueId())
      || !parent.instanceId.equals(child.instanceId)
      || !parent.botId.equals(child.botId)) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Parent task must have the same owner, instance, and bot")
        .asRuntimeException();
    }
    if (isTerminal(parent.status)) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Parent task is already terminal")
        .asRuntimeException();
    }
    parent.childTaskIds.add(child.taskId);
    publish(parent);
  }

  private void updateProgress(
    ManagedTask task,
    long generation,
    BotTaskProgress progress
  ) {
    synchronized (this) {
      if (isTerminal(task.status) || generation != task.generation) {
        return;
      }
      task.progress = Objects.requireNonNull(progress, "progress");
      publish(task);
    }
  }

  private void updateStatus(ManagedTask task, BotTaskStatus status) {
    synchronized (this) {
      if (isTerminal(task.status)) {
        return;
      }
      task.status = status;
      if (status == BotTaskStatus.BOT_TASK_STATUS_RUNNING && task.startedAt == null) {
        task.startedAt = Instant.now();
      }
      publish(task);
    }
  }

  private void finish(
    ManagedTask task,
    ControlStopReason reason,
    @Nullable Throwable cause,
    ManagedControlTask control
  ) {
    synchronized (this) {
      // History keeps the result, not the stopped execution and its bot.
      if (task.control == control) {
        task.control = null;
      }
      if (isTerminal(task.status)
        || control.retired.get()
        || control.generation != task.generation) {
        return;
      }
      if (task.requestedTerminal != null) {
        task.status = task.requestedTerminal;
        task.failure = BotTaskFailure.newBuilder()
          .setCode(task.requestedTerminal
            == BotTaskStatus.BOT_TASK_STATUS_TIMED_OUT ? "deadline_exceeded" : "cancelled")
          .setMessage(Objects.requireNonNullElse(task.terminalReason, "Task was cancelled"))
          .build();
      } else if (cause != null || reason == ControlStopReason.FAILED) {
        var failure = cause == null
          ? new IllegalStateException("Control task failed without a cause")
          : unwrap(cause);
        task.status = BotTaskStatus.BOT_TASK_STATUS_FAILED;
        task.failure = failure("task_failed", failure, false);
      } else if (reason == ControlStopReason.COMPLETED) {
        try {
          var result = control.execution.result().join();
          task.result = Any.pack(result);
          task.progress = task.progress.toBuilder().setFraction(1.0).build();
          task.status = BotTaskStatus.BOT_TASK_STATUS_COMPLETED;
        } catch (CancellationException exception) {
          task.status = BotTaskStatus.BOT_TASK_STATUS_CANCELLED;
          task.failure = failure("cancelled", exception, true);
        } catch (CompletionException exception) {
          task.status = BotTaskStatus.BOT_TASK_STATUS_FAILED;
          task.failure = failure("task_failed", unwrap(exception), false);
        }
      } else {
        task.status = BotTaskStatus.BOT_TASK_STATUS_CANCELLED;
        task.failure = BotTaskFailure.newBuilder()
          .setCode(reason == ControlStopReason.REPLACED ? "replaced" : "cancelled")
          .setMessage(reason == ControlStopReason.REPLACED
            ? "Task was replaced by conflicting work"
            : "Task was cancelled")
          .setRetryable(reason == ControlStopReason.REPLACED)
          .build();
      }
      task.completedAt = Instant.now();
      publish(task);
    }
    if (task.status != BotTaskStatus.BOT_TASK_STATUS_COMPLETED) {
      cancelChildren(task, "Parent task did not complete successfully");
    }
  }

  private void fail(
    ManagedTask task,
    long generation,
    String code,
    Throwable cause,
    boolean retryable
  ) {
    synchronized (this) {
      if (isTerminal(task.status) || generation != task.generation) {
        return;
      }
      task.status = BotTaskStatus.BOT_TASK_STATUS_FAILED;
      task.failure = failure(code, cause, retryable);
      task.completedAt = Instant.now();
      publish(task);
    }
    cancelChildren(task, "Parent task failed");
  }

  private void timeout(ManagedTask task) {
    cancel(task, BotTaskStatus.BOT_TASK_STATUS_TIMED_OUT, "Task deadline was exceeded");
  }

  private void cancel(
    ManagedTask task,
    BotTaskStatus terminal,
    String reason
  ) {
    ManagedControlTask control;
    synchronized (this) {
      if (isTerminal(task.status)) {
        return;
      }
      task.requestedTerminal = terminal;
      task.terminalReason = reason.isBlank() ? null : reason;
      control = task.control;
    }
    if (control == null) {
      completeCancellation(task, terminal);
      cancelChildren(task, "Parent task was cancelled");
      return;
    }
    var instance = server.getInstance(task.instanceId);
    var bot = instance.map(value -> value.botConnections().get(task.botId)).orElse(null);
    if (bot == null || bot.isDisconnected()) {
      control.cancelWithoutBot();
      return;
    }
    bot.scheduler().execute(() -> {
      if (!bot.botControl().cancel(control)) {
        control.cancelWithoutBot();
      }
    });
  }

  private void completeCancellation(ManagedTask task, BotTaskStatus terminal) {
    synchronized (this) {
      if (isTerminal(task.status)) {
        return;
      }
      task.status = terminal;
      task.completedAt = Instant.now();
      task.failure = BotTaskFailure.newBuilder()
        .setCode(terminal == BotTaskStatus.BOT_TASK_STATUS_TIMED_OUT
          ? "deadline_exceeded"
          : "cancelled")
        .setMessage(Objects.requireNonNullElse(task.terminalReason, "Task was cancelled"))
        .build();
      publish(task);
    }
  }

  private void cancelChildren(ManagedTask parent, String reason) {
    List<UUID> childIds;
    synchronized (this) {
      childIds = List.copyOf(parent.childTaskIds);
    }
    for (var childId : childIds) {
      var child = tasks.get(childId);
      if (child != null) {
        cancel(child, BotTaskStatus.BOT_TASK_STATUS_CANCELLED, reason);
      }
    }
  }

  private void onBotDisconnected(BotDisconnectedEvent event) {
    var bot = event.connection();
    if (bot.instanceManager().soulFireServer() != server) {
      return;
    }
    synchronized (this) {
      pendingRecoveries.remove(bot);
    }
    var key = new BotKey(bot.instanceManager().id(), bot.accountProfileId());
    var affected = tasks.values().stream()
      .filter(task -> task.instanceId.equals(key.instanceId)
        && task.botId.equals(key.botId)
        && !isTerminal(task.status))
      .toList();
    for (var task : affected) {
      var control = task.control;
      if (task.reconnectPolicy
        == BotTaskReconnectPolicy.BOT_TASK_RECONNECT_POLICY_FAIL) {
        fail(
          task,
          task.generation,
          "connection_lost",
          new IllegalStateException("Bot disconnected while the task was active"),
          true
        );
      } else {
        synchronized (this) {
          if (isTerminal(task.status)) {
            continue;
          }
          task.generation++;
          task.control = null;
          task.status = BotTaskStatus.BOT_TASK_STATUS_RECOVERING;
          publish(task);
        }
      }
      if (control != null) {
        control.retire(bot);
      }
    }
  }

  private void onBotConnected(BotConnectedEvent event) {
    var bot = event.connection();
    if (bot.instanceManager().soulFireServer() != server) {
      return;
    }
    var instanceId = bot.instanceManager().id();
    var botId = bot.accountProfileId();
    var recoverable = tasks.values().stream()
      .filter(task -> task.instanceId.equals(instanceId)
        && task.botId.equals(botId)
        && task.status == BotTaskStatus.BOT_TASK_STATUS_RECOVERING)
      .toList();
    synchronized (this) {
      if (!recoverable.isEmpty() && !bot.isDisconnecting()) {
        pendingRecoveries.put(bot, recoverable);
      }
    }
  }

  private void onBotReady(BotPostEntityTickEvent event) {
    var bot = event.connection();
    var recoverable = pendingRecoveries.get(bot);
    if (recoverable == null || bot.isDisconnecting()) {
      return;
    }
    // Login finishes before the player, world, and initial position arrive.
    var minecraft = bot.minecraft();
    var connection = minecraft.getConnection();
    if (minecraft.player == null || minecraft.level == null
      || connection == null || !connection.hasClientLoaded()
      || !pendingRecoveries.remove(bot, recoverable)) {
      return;
    }
    for (var task : recoverable) {
      synchronized (this) {
        if (task.status != BotTaskStatus.BOT_TASK_STATUS_RECOVERING
          || task.recoveryStarting) {
          continue;
        }
        task.recoveryStarting = true;
      }
      try {
        startExecution(task, bot);
      } finally {
        task.recoveryStarting = false;
      }
    }
  }

  @Override
  public void close() {
    SoulFireAPI.unregisterListener(BotDisconnectedEvent.class, disconnectedListener);
    SoulFireAPI.unregisterListener(BotConnectedEvent.class, connectedListener);
    SoulFireAPI.unregisterListener(BotPostEntityTickEvent.class, readyListener);
    pendingRecoveries.clear();
    tasks.values().stream()
      .filter(task -> !isTerminal(task.status))
      .forEach(task -> cancel(
        task,
        BotTaskStatus.BOT_TASK_STATUS_CANCELLED,
        "Soulfire server is shutting down"
      ));
  }

  private synchronized void publish(ManagedTask task) {
    task.revision++;
    task.updatedAt = Instant.now();
    var event = BotTaskEvent.newBuilder()
      .setSequence(eventSequence.incrementAndGet())
      .setObservedAt(timestamp(task.updatedAt))
      .setTask(task.snapshot())
      .build();
    journal.addLast(event);
    while (journal.size() > JOURNAL_SIZE) {
      journal.removeFirst();
    }
    listeners.forEach(listener -> {
      try {
        listener.accept(event);
      } catch (Throwable throwable) {
        log.warn("Bot task event listener failed", throwable);
      }
    });
  }

  private ManagedTask require(UUID taskId) {
    var task = tasks.get(taskId);
    if (task == null) {
      throw Status.NOT_FOUND
        .withDescription("Task '%s' not found".formatted(taskId))
        .asRuntimeException();
    }
    return task;
  }

  private static void registerCore(
    Map<String, ProviderEntry<?>> providers,
    BotTaskProvider<?> provider
  ) {
    putProvider(providers, ProviderEntry.core(provider));
  }

  private static void registerPlugin(
    Map<String, ProviderEntry<?>> providers,
    BotTaskProviderRegistration<?, ?> registration
  ) {
    putProvider(providers, ProviderEntry.plugin(registration));
  }

  private static void putProvider(
    Map<String, ProviderEntry<?>> providers,
    ProviderEntry<?> provider
  ) {
    if (providers.putIfAbsent(provider.typeUrl, provider) != null) {
      throw new IllegalStateException("Duplicate bot task provider: " + provider.typeUrl);
    }
  }

  private static BotTaskFailure failure(
    String code,
    Throwable throwable,
    boolean retryable
  ) {
    return BotTaskFailure.newBuilder()
      .setCode(failureCode(code, throwable))
      .setMessage(Objects.requireNonNullElse(
        throwable.getMessage(),
        throwable.getClass().getSimpleName()
      ))
      .setRetryable(retryable || throwable instanceof UnreachableGoalException)
      .build();
  }

  static String failureCode(String fallback, Throwable throwable) {
    var status = Status.fromThrowable(throwable);
    if (status.getCode() != Status.Code.UNKNOWN) {
      return status.getCode().name().toLowerCase(Locale.ROOT);
    }
    return throwable instanceof UnreachableGoalException unreachable
      ? unreachable.code()
      : fallback;
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

  private static Instant toInstant(Timestamp timestamp) {
    try {
      return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    } catch (RuntimeException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription("deadline is invalid")
        .withCause(exception)
        .asRuntimeException();
    }
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
      .setSeconds(instant.getEpochSecond())
      .setNanos(instant.getNano())
      .build();
  }

  private static String canonicalTypeUrl(String typeUrl) {
    var separator = typeUrl.lastIndexOf('/');
    var typeName = separator < 0 ? typeUrl : typeUrl.substring(separator + 1);
    if (typeName.isBlank() || !typeName.contains(".")) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Invalid protobuf type URL: " + typeUrl)
        .asRuntimeException();
    }
    return "type.googleapis.com/" + typeName;
  }

  private static Optional<String> normalizeIdempotencyKey(StartBotTaskRequest request) {
    var value = optionalNonBlank(request.hasIdempotencyKey(), request.getIdempotencyKey());
    if (value.isPresent() && value.orElseThrow().length() > 128) {
      throw Status.INVALID_ARGUMENT
        .withDescription("idempotency_key must not exceed 128 characters")
        .asRuntimeException();
    }
    return value;
  }

  private static Optional<String> optionalNonBlank(boolean present, String value) {
    if (!present || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  private static BotTaskConflictPolicy defaultConflictPolicy(
    BotTaskConflictPolicy policy
  ) {
    return policy == BotTaskConflictPolicy.BOT_TASK_CONFLICT_POLICY_UNSPECIFIED
      ? BotTaskConflictPolicy.BOT_TASK_CONFLICT_POLICY_QUEUE
      : policy;
  }

  private static BotTaskReconnectPolicy defaultReconnectPolicy(
    BotTaskReconnectPolicy policy
  ) {
    return policy == BotTaskReconnectPolicy.BOT_TASK_RECONNECT_POLICY_UNSPECIFIED
      ? BotTaskReconnectPolicy.BOT_TASK_RECONNECT_POLICY_FAIL
      : policy;
  }

  private static BotTaskDisconnectPolicy defaultDisconnectPolicy(
    BotTaskDisconnectPolicy policy
  ) {
    return policy == BotTaskDisconnectPolicy.BOT_TASK_DISCONNECT_POLICY_UNSPECIFIED
      ? BotTaskDisconnectPolicy.BOT_TASK_DISCONNECT_POLICY_CONTINUE
      : policy;
  }

  private static BotTaskPriority defaultPriority(BotTaskPriority priority) {
    return priority == BotTaskPriority.BOT_TASK_PRIORITY_UNSPECIFIED
      ? BotTaskPriority.BOT_TASK_PRIORITY_NORMAL
      : priority;
  }

  private static ControlPriority controlPriority(BotTaskPriority priority) {
    return switch (priority) {
      case BOT_TASK_PRIORITY_LOW -> ControlPriority.LOW;
      case BOT_TASK_PRIORITY_NORMAL -> ControlPriority.NORMAL;
      case BOT_TASK_PRIORITY_HIGH -> ControlPriority.HIGH;
      case BOT_TASK_PRIORITY_CRITICAL -> ControlPriority.CRITICAL;
      case BOT_TASK_PRIORITY_UNSPECIFIED, UNRECOGNIZED ->
        throw new IllegalStateException("Task priority was not normalized");
    };
  }

  public static boolean isTerminal(BotTaskStatus status) {
    return switch (status) {
      case BOT_TASK_STATUS_COMPLETED,
           BOT_TASK_STATUS_CANCELLED,
           BOT_TASK_STATUS_FAILED,
           BOT_TASK_STATUS_TIMED_OUT -> true;
      default -> false;
    };
  }

  private static int decodePageToken(String token) {
    if (token.isBlank()) {
      return 0;
    }
    try {
      var decoded = new String(Base64.getUrlDecoder().decode(token));
      var offset = Integer.parseInt(decoded);
      if (offset < 0) {
        throw new IllegalArgumentException("negative offset");
      }
      return offset;
    } catch (IllegalArgumentException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Invalid page_token")
        .withCause(exception)
        .asRuntimeException();
    }
  }

  private static String encodePageToken(int offset) {
    return Base64.getUrlEncoder().withoutPadding()
      .encodeToString(Integer.toString(offset).getBytes(UTF_8));
  }

  private static Throwable unwrap(Throwable throwable) {
    var current = throwable;
    while ((current instanceof CompletionException
      || current instanceof java.util.concurrent.ExecutionException)
      && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  public record ListResult(List<BotTask> tasks, String nextPageToken) {
    public ListResult {
      tasks = List.copyOf(tasks);
    }
  }

  private final class ManagedControlTask implements ControlTask {
    private final ManagedTask record;
    private final BotTaskExecution execution;
    private final long generation;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean retired = new AtomicBoolean();

    private ManagedControlTask(
      ManagedTask record,
      BotTaskExecution execution,
      long generation
    ) {
      this.record = record;
      this.execution = execution;
      this.generation = generation;
    }

    @Override
    public void tick() {
      execution.control().tick();
      if (execution.control().isDone() && !execution.result().isDone()) {
        throw new IllegalStateException(
          "Task provider completed control before producing its result"
        );
      }
    }

    @Override
    public boolean isDone() {
      return execution.result().isDone();
    }

    @Override
    public ControlPriority priority() {
      return controlPriority(record.priority);
    }

    @Override
    public Set<ControlResource> resources() {
      return record.resources;
    }

    @Override
    public void onStarted() {
      updateStatus(record, BotTaskStatus.BOT_TASK_STATUS_RUNNING);
      execution.control().onStarted();
    }

    @Override
    public void onSuspended() {
      execution.control().onSuspended();
      updateStatus(record, BotTaskStatus.BOT_TASK_STATUS_SUSPENDED);
    }

    @Override
    public void onResumed() {
      execution.control().onResumed();
      updateStatus(record, BotTaskStatus.BOT_TASK_STATUS_RUNNING);
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      if (!stopped.compareAndSet(false, true)) {
        return;
      }
      try {
        execution.control().onStopped(reason, cause);
      } catch (Throwable stopError) {
        if (cause == null) {
          cause = stopError;
        } else {
          cause.addSuppressed(stopError);
        }
      }
      finish(record, reason, cause, this);
    }

    @Override
    public String description() {
      return record.summary;
    }

    private void cancelBeforeStart() {
      if (!stopped.compareAndSet(false, true)) {
        return;
      }
      execution.result().cancel(true);
      try {
        execution.control().onStopped(ControlStopReason.CANCELLED, null);
      } finally {
        finish(record, ControlStopReason.CANCELLED, null, this);
      }
    }

    private void cancelWithoutBot() {
      cancelBeforeStart();
    }

    private void retire(BotConnection bot) {
      retired.set(true);
      if (!bot.botControl().cancel(this)) {
        discard();
      }
    }

    private void discard() {
      retired.set(true);
      if (!stopped.compareAndSet(false, true)) {
        return;
      }
      execution.result().cancel(true);
      try {
        execution.control().onStopped(ControlStopReason.CANCELLED, null);
      } catch (Throwable throwable) {
        log.debug("Failed to stop retired task execution {}", record.taskId, throwable);
      } finally {
        synchronized (BotTaskManager.this) {
          if (record.control == this) {
            record.control = null;
          }
        }
      }
    }
  }

  private static final class ManagedTask {
    private final UUID taskId;
    private final UUID instanceId;
    private final UUID botId;
    private final UUID ownerId;
    private final String ownerName;
    private final SoulFireUser owner;
    private final String taskType;
    private final Any input;
    private final String summary;
    private final Set<ControlResource> resources;
    private final BotTaskConflictPolicy conflictPolicy;
    private final BotTaskReconnectPolicy reconnectPolicy;
    private final BotTaskDisconnectPolicy disconnectPolicy;
    private final BotTaskPriority priority;
    private final Optional<Instant> deadline;
    private final Optional<UUID> parentTaskId;
    private final Optional<String> causationId;
    private final Optional<String> idempotencyKey;
    private final PreparedTask prepared;
    private final Instant createdAt = Instant.now();
    private final List<UUID> childTaskIds = new ArrayList<>();
    private volatile BotTaskStatus status = BotTaskStatus.BOT_TASK_STATUS_QUEUED;
    private volatile BotTaskProgress progress = BotTaskProgress.getDefaultInstance();
    private volatile @Nullable BotTaskFailure failure;
    private volatile @Nullable Any result;
    private volatile @Nullable Instant startedAt;
    private volatile Instant updatedAt = createdAt;
    private volatile @Nullable Instant completedAt;
    private volatile long revision;
    private volatile @Nullable ManagedControlTask control;
    private volatile @Nullable BotTaskStatus requestedTerminal;
    private volatile @Nullable String terminalReason;
    private volatile long generation;
    private volatile boolean recoveryStarting;

    private ManagedTask(
      UUID taskId,
      UUID instanceId,
      UUID botId,
      SoulFireUser owner,
      String taskType,
      Any input,
      String summary,
      Set<ControlResource> resources,
      BotTaskConflictPolicy conflictPolicy,
      BotTaskReconnectPolicy reconnectPolicy,
      BotTaskDisconnectPolicy disconnectPolicy,
      BotTaskPriority priority,
      Optional<Instant> deadline,
      Optional<UUID> parentTaskId,
      Optional<String> causationId,
      Optional<String> idempotencyKey,
      PreparedTask prepared
    ) {
      this.taskId = taskId;
      this.instanceId = instanceId;
      this.botId = botId;
      this.ownerId = owner.getUniqueId();
      this.ownerName = owner.getUsername();
      this.owner = owner;
      this.taskType = taskType;
      this.input = input;
      this.summary = summary;
      this.resources = Set.copyOf(resources);
      this.conflictPolicy = conflictPolicy;
      this.reconnectPolicy = reconnectPolicy;
      this.disconnectPolicy = disconnectPolicy;
      this.priority = priority;
      this.deadline = deadline;
      this.parentTaskId = parentTaskId;
      this.causationId = causationId;
      this.idempotencyKey = idempotencyKey;
      this.prepared = prepared;
    }

    private synchronized BotTask snapshot() {
      var builder = BotTask.newBuilder()
        .setTaskId(taskId.toString())
        .setInstanceId(instanceId.toString())
        .setBotId(botId.toString())
        .setTaskType(taskType)
        .setOwnerId(ownerId.toString())
        .setOwnerName(ownerName)
        .setStatus(status)
        .setProgress(progress)
        .setSummary(summary)
        .setCreatedAt(timestamp(createdAt))
        .setUpdatedAt(timestamp(updatedAt))
        .addAllClaimedResources(resources.stream()
          .map(BotTaskManager::toProtoResource)
          .sorted(Comparator.comparingInt(BotTaskResource::getNumber))
          .toList())
        .addAllChildTaskIds(childTaskIds.stream().map(UUID::toString).toList())
        .setReconnectPolicy(reconnectPolicy)
        .setDisconnectPolicy(disconnectPolicy)
        .setConflictPolicy(conflictPolicy)
        .setPriority(priority)
        .setInput(input)
        .setRevision(revision);
      if (failure != null) {
        builder.setFailure(failure);
      }
      if (result != null) {
        builder.setResult(result);
      }
      if (startedAt != null) {
        builder.setStartedAt(timestamp(startedAt));
      }
      if (completedAt != null) {
        builder.setCompletedAt(timestamp(completedAt));
      }
      deadline.ifPresent(value -> builder.setDeadline(timestamp(value)));
      parentTaskId.ifPresent(value -> builder.setParentTaskId(value.toString()));
      causationId.ifPresent(builder::setCausationId);
      idempotencyKey.ifPresent(builder::setIdempotencyKey);
      return builder.build();
    }
  }

  private record PreparedTask(
    String summary,
    Set<ControlResource> resources,
    Optional<String> progressTypeUrl,
    Starter starter
  ) {
    private BotTaskExecution start(BotTaskContext context) throws Exception {
      return starter.start(context);
    }

    private BotTaskProgress validateProgress(BotTaskProgress progress) {
      if (progressTypeUrl.isEmpty() || !progress.hasDetail()) {
        return progress;
      }
      var actual = canonicalTypeUrl(progress.getDetail().getTypeUrl());
      if (!actual.equals(progressTypeUrl.orElseThrow())) {
        throw new IllegalArgumentException(
          "Plugin task progress detail must use %s, received %s"
            .formatted(progressTypeUrl.orElseThrow(), actual)
        );
      }
      return progress;
    }
  }

  @FunctionalInterface
  private interface Starter {
    BotTaskExecution start(BotTaskContext context) throws Exception;
  }

  private record ProviderEntry<I extends Message>(
    String typeUrl,
    BotTaskProvider<I> provider,
    List<RegisteredPluginPermission> permissions,
    Optional<String> resultTypeUrl,
    Optional<String> progressTypeUrl
  ) {
    private static <I extends Message> ProviderEntry<I> core(
      BotTaskProvider<I> provider
    ) {
      return new ProviderEntry<>(
        typeUrl(provider.inputPrototype()),
        provider,
        List.of(),
        Optional.empty(),
        Optional.empty()
      );
    }

    private static <I extends Message, R extends Message> ProviderEntry<I> plugin(
      BotTaskProviderRegistration<I, R> registration
    ) {
      return new ProviderEntry<>(
        registration.typeUrl(),
        registration.provider(),
        registration.permissions(),
        Optional.of(registration.resultTypeUrl()),
        registration.progressTypeUrl()
      );
    }

    private void authorize(
      SoulFireUser user,
      UUID instanceId,
      UUID botId,
      UUID taskId
    ) {
      permissions.forEach(permission ->
        user.hasPermissionOrThrow(PermissionContext.plugin(
          permission,
          Optional.of(instanceId),
          Optional.of(botId),
          Optional.of(taskId)
        )));
    }

    private PreparedTask prepare(Any input) {
      I parsed;
      try {
        @SuppressWarnings("unchecked")
        var value = (I) provider.inputPrototype()
          .getParserForType()
          .parseFrom(input.getValue());
        parsed = value;
      } catch (InvalidProtocolBufferException exception) {
        throw Status.INVALID_ARGUMENT
          .withDescription("Task input does not match " + typeUrl)
          .withCause(exception)
          .asRuntimeException();
      }
      var factory = provider.prepare(parsed);
      return new PreparedTask(
        provider.summary(parsed),
        Set.copyOf(provider.resources(parsed)),
        progressTypeUrl,
        context -> validateExecution(factory.start(context))
      );
    }

    private BotTaskExecution validateExecution(BotTaskExecution execution) {
      if (resultTypeUrl.isEmpty()) {
        return execution;
      }
      var expected = resultTypeUrl.orElseThrow();
      var result = execution.result().thenApply(value -> {
        var actual = typeUrl(value);
        if (!actual.equals(expected)) {
          throw new IllegalStateException(
            "Plugin task result must use %s, received %s".formatted(expected, actual)
          );
        }
        return value;
      });
      return new BotTaskExecution(execution.control(), result);
    }

    private static String typeUrl(Message prototype) {
      return "type.googleapis.com/" + prototype.getDescriptorForType().getFullName();
    }
  }

  private static BotTaskResource toProtoResource(ControlResource resource) {
    return switch (resource) {
      case MOVEMENT -> BotTaskResource.BOT_TASK_RESOURCE_MOVEMENT;
      case ROTATION -> BotTaskResource.BOT_TASK_RESOURCE_ROTATION;
      case MAIN_HAND -> BotTaskResource.BOT_TASK_RESOURCE_MAIN_HAND;
      case OFF_HAND -> BotTaskResource.BOT_TASK_RESOURCE_OFF_HAND;
      case INVENTORY -> BotTaskResource.BOT_TASK_RESOURCE_INVENTORY;
      case CONTAINER -> BotTaskResource.BOT_TASK_RESOURCE_CONTAINER;
      case CHAT -> BotTaskResource.BOT_TASK_RESOURCE_CHAT;
      case VEHICLE -> BotTaskResource.BOT_TASK_RESOURCE_VEHICLE;
      case CAMERA -> BotTaskResource.BOT_TASK_RESOURCE_CAMERA;
      case PROTOCOL -> BotTaskResource.BOT_TASK_RESOURCE_PROTOCOL;
    };
  }

  private record BotKey(UUID instanceId, UUID botId) {
  }

  private record IdempotencyKey(
    UUID ownerId,
    UUID instanceId,
    UUID botId,
    String value
  ) {
  }
}
