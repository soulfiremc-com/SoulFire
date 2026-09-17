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
package com.soulfiremc.server.renderer;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/// Captures run at the vanilla render phase, after simulation and mouse updates.
public final class PovFrameTasks implements AutoCloseable {
  private final ArrayDeque<Task<?>> pending = new ArrayDeque<>();
  private boolean closed;

  public synchronized <T> CompletableFuture<T> submit(Supplier<T> action) {
    var result = new CompletableFuture<T>();
    if (closed) result.cancel(false);
    else pending.add(new Task<>(action, result));
    return result;
  }

  public void drain() {
    final Task<?>[] tasks;
    synchronized (this) {
      if (pending.isEmpty()) return;
      tasks = pending.toArray(Task<?>[]::new);
      pending.clear();
    }
    for (var task : tasks) task.run();
  }

  @Override
  public synchronized void close() {
    closed = true;
    for (var task : pending) task.result.cancel(false);
    pending.clear();
  }

  private record Task<T>(Supplier<T> action, CompletableFuture<T> result) {
    void run() {
      if (result.isDone()) return;
      try { result.complete(action.get()); }
      catch (Throwable error) { result.completeExceptionally(error); }
    }
  }
}
