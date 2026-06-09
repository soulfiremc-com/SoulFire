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
package com.soulfiremc.server.util.structs;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class CancellationCollector {
  private static final int CLEANUP_THRESHOLD = 100;
  private final List<CompletableFuture<?>> futures = new ArrayList<>();
  private final List<Runnable> cancelHooks = new ArrayList<>();
  @Getter
  private volatile boolean cancelled;

  public CancellationCollector(StreamObserver<?> casted) {
    var observer = (ServerCallStreamObserver<?>) casted;
    observer.setOnCancelHandler(this::cancelAll);
  }

  public synchronized <T> CompletableFuture<T> add(CompletableFuture<T> future) {
    if (cancelled) {
      future.cancel(true);

      // Just return a fake future that is already cancelled
      var fakeFuture = new CompletableFuture<T>();
      fakeFuture.cancel(true);
      return fakeFuture;
    }

    futures.add(future);

    // Periodically clean up completed futures to prevent memory buildup
    // during large import operations
    if (futures.size() > CLEANUP_THRESHOLD) {
      futures.removeIf(CompletableFuture::isDone);
    }

    return future;
  }

  public void addCancelHook(Runnable hook) {
    var runNow = false;
    synchronized (this) {
      if (cancelled) {
        runNow = true;
      } else {
        cancelHooks.add(hook);
      }
    }

    if (runNow) {
      hook.run();
    }
  }

  public void cancelAll() {
    List<CompletableFuture<?>> futuresToCancel;
    List<Runnable> hooksToRun;
    synchronized (this) {
      if (cancelled) {
        return;
      }

      cancelled = true;
      futuresToCancel = List.copyOf(futures);
      hooksToRun = List.copyOf(cancelHooks);
      futures.clear();
      cancelHooks.clear();
    }

    for (var future : futuresToCancel) {
      future.cancel(true);
    }

    hooksToRun.forEach(Runnable::run);
  }
}
