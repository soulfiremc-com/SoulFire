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
package com.soulfiremc.server.script;

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireScheduler;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.VisibleForTesting;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/// Reactive execution context for scripts.
/// Per-invocation state (output sinks) lives in ExecutionRun, not here.
@Getter
public final class ReactiveScriptContext {
  private final ScriptStateStore stateStore = new ScriptStateStore();
  private final InstanceManager instance;
  private final Scheduler reactorScheduler;
  private final ScriptEventListener eventListener;
  private final ScriptQuotasConfig quotas;
  @Getter(AccessLevel.NONE)
  private final LinkedHashMap<Long, String> activeNodes = new LinkedHashMap<>();
  @Getter(AccessLevel.NONE)
  private long nextExecutionId;

  /// For cancellation via Disposable.
  private final AtomicReference<Disposable> execution = new AtomicReference<>();
  private volatile boolean cancelled;

  /// Creates a new reactive script context.
  ///
  /// @param instance      the Soulfire instance
  /// @param eventListener listener for script execution events
  /// @param quotas        execution quotas for this script
  public ReactiveScriptContext(InstanceManager instance, ScriptEventListener eventListener, ScriptQuotasConfig quotas) {
    this.instance = instance;
    this.reactorScheduler = new SoulFireReactorScheduler(instance.scheduler());
    this.eventListener = eventListener;
    this.quotas = quotas;
  }

  /// Creates a lightweight context for testing without an InstanceManager.
  /// Instance and scheduler methods will return null.
  ///
  /// @param eventListener listener for script execution events
  @VisibleForTesting
  public ReactiveScriptContext(ScriptEventListener eventListener) {
    this(eventListener, ScriptQuotasConfig.DEFAULTS);
  }

  /// Creates an isolated dry-run context with the saved script's resource limits.
  public ReactiveScriptContext(ScriptEventListener eventListener, ScriptQuotasConfig quotas) {
    this.instance = null;
    this.reactorScheduler = Schedulers.parallel();
    this.eventListener = eventListener;
    this.quotas = quotas;
  }

  public SoulFireScheduler scheduler() {
    return instance.scheduler();
  }

  public void log(String level, String message) {
    eventListener.onLog(level, message);
  }

  /// Gets the Reactor scheduler for reactive operations.
  ///
  /// @return the Reactor scheduler
  public Scheduler getReactorScheduler() {
    return reactorScheduler;
  }

  /// Checks if execution has been cancelled.
  ///
  /// @return true if cancelled
  public boolean isCancelled() {
    return cancelled;
  }

  /// Returns the most recently started node that is still executing.
  public Optional<String> activeNodeId() {
    synchronized (activeNodes) {
      return Optional.ofNullable(activeNodes.lastEntry()).map(Map.Entry::getValue);
    }
  }

  /// Tracks each subscription independently, including overlapping runs of the same node.
  <T> Mono<T> trackNode(String nodeId, LongFunction<Mono<T>> operation) {
    return Mono.defer(() -> {
      final long executionId;
      synchronized (activeNodes) {
        if (cancelled) return Mono.empty();
        executionId = ++nextExecutionId;
        activeNodes.put(executionId, nodeId);
      }
      return Mono.defer(() -> operation.apply(executionId)).doFinally(_ -> {
        synchronized (activeNodes) {
          activeNodes.remove(executionId);
        }
      });
    });
  }

  /// Cancels the script execution.
  public void cancel() {
    this.cancelled = true;
    var exec = execution.getAndSet(null);
    if (exec != null) {
      exec.dispose();
    }
    stateStore.clear();
    synchronized (activeNodes) {
      activeNodes.clear();
    }
    eventListener.onScriptCancelled();
  }

  /// Sets the main execution disposable for cancellation.
  ///
  /// @param execution the disposable to set
  public void setExecution(Disposable execution) {
    if (cancelled) {
      execution.dispose();
      return;
    }
    var old = this.execution.getAndSet(execution);
    if (old != null) {
      old.dispose();
    }
    // Double-check after setting, in case cancel() raced
    if (cancelled) {
      var current = this.execution.getAndSet(null);
      if (current != null) {
        current.dispose();
      }
    }
  }
}
