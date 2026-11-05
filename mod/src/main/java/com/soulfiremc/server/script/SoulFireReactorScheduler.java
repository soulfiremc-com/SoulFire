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

import com.soulfiremc.server.SoulFireScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// Adapter that wraps SoulFireScheduler to implement Reactor's Scheduler interface.
/// This allows reactive pipelines to execute on Soulfire's virtual thread executor.
/// Supports an optional additional RunnableWrapper for per-invocation context
/// (e.g., bot context for trigger executions).
public final class SoulFireReactorScheduler implements Scheduler {
  private final SoulFireScheduler delegate;
  private final SoulFireScheduler.@Nullable RunnableWrapper additionalWrapper;

  public SoulFireReactorScheduler(SoulFireScheduler delegate) {
    this(delegate, null);
  }

  public SoulFireReactorScheduler(SoulFireScheduler delegate, SoulFireScheduler.@Nullable RunnableWrapper additionalWrapper) {
    this.delegate = delegate;
    this.additionalWrapper = additionalWrapper;
  }

  /// Creates a new scheduler with an additional RunnableWrapper composed on top.
  /// The wrapper is applied to each task before delegation, binding the bot and
  /// Minecraft context for each async operation.
  public SoulFireReactorScheduler withAdditionalWrapper(SoulFireScheduler.RunnableWrapper wrapper) {
    var composed = additionalWrapper != null ? additionalWrapper.with(wrapper) : wrapper;
    return new SoulFireReactorScheduler(delegate, composed);
  }

  private Runnable wrapTask(Runnable task) {
    return additionalWrapper != null ? additionalWrapper.wrap(task) : task;
  }

  @Override
  public Disposable schedule(Runnable task) {
    var wrapped = wrapTask(task);
    var cancelled = new AtomicBoolean(false);
    delegate.schedule(() -> {
      if (!cancelled.get()) {
        wrapped.run();
      }
    });
    return () -> cancelled.set(true);
  }

  @Override
  public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
    var wrapped = wrapTask(task);
    var cancelled = new AtomicBoolean(false);
    delegate.schedule(() -> {
      if (!cancelled.get()) {
        wrapped.run();
      }
    }, delay, unit);
    return () -> cancelled.set(true);
  }

  @Override
  public Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
    var wrapped = wrapTask(task);
    var cancelled = new AtomicBoolean(false);
    delegate.scheduleAtFixedRate(() -> {
      if (!cancelled.get()) {
        wrapped.run();
      }
    }, initialDelay, period, unit);
    return () -> cancelled.set(true);
  }

  @Override
  public Worker createWorker() {
    return new SoulFireWorker();
  }

  private class SoulFireWorker implements Worker {
    private volatile boolean disposed;

    @Override
    public Disposable schedule(Runnable task) {
      if (disposed) {
        return () -> {}; // No-op disposable
      }
      return SoulFireReactorScheduler.this.schedule(task);
    }

    @Override
    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
      if (disposed) {
        return () -> {}; // No-op disposable
      }
      return SoulFireReactorScheduler.this.schedule(task, delay, unit);
    }

    @Override
    public void dispose() {
      disposed = true;
    }

    @Override
    public boolean isDisposed() {
      return disposed;
    }
  }
}
