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
package com.soulfiremc.server.util;

import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.util.structs.CancellationCollector;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SFHelpersMaxFuturesTest {
  @Test
  void maxFuturesStopsWaitingForPermitAfterCancellation() throws Exception {
    var scheduler = new SoulFireScheduler(runnable -> runnable);
    var observer = new TestServerCallStreamObserver<>();
    var cancellationCollector = new CancellationCollector(observer);
    var taskStarted = new CountDownLatch(1);
    var releaseTask = new CountDownLatch(1);
    var returned = new CountDownLatch(1);

    var worker = Thread.ofVirtual().start(() -> {
      SFHelpers.maxFutures(scheduler, 1, List.of(1, 2), _ -> {
        taskStarted.countDown();
        await(releaseTask);
      }, cancellationCollector);
      returned.countDown();
    });

    assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
    observer.cancel();
    assertTrue(returned.await(2, TimeUnit.SECONDS));

    releaseTask.countDown();
    worker.join(Duration.ofSeconds(5));
    scheduler.shutdown();
  }

  @Test
  void maxFuturesReleasesPermitWhenSchedulerSkipsTask() {
    var scheduler = new SoulFireScheduler(runnable -> runnable);
    scheduler.shutdown();

    var observer = new TestServerCallStreamObserver<>();
    var cancellationCollector = new CancellationCollector(observer);

    assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
      SFHelpers.maxFutures(scheduler, 1, List.of(1, 2, 3), _ -> fail("Task should not run"), cancellationCollector));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class TestServerCallStreamObserver<T> extends ServerCallStreamObserver<T> {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Runnable onCancelHandler;

    void cancel() {
      if (cancelled.compareAndSet(false, true) && onCancelHandler != null) {
        onCancelHandler.run();
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public void setOnCancelHandler(Runnable onCancelHandler) {
      this.onCancelHandler = onCancelHandler;
    }

    @Override
    public void setCompression(String compression) {
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setOnReadyHandler(Runnable onReadyHandler) {
    }

    @Override
    public void disableAutoInboundFlowControl() {
    }

    @Override
    public void request(int count) {
    }

    @Override
    public void setMessageCompression(boolean enable) {
    }

    @Override
    public void onNext(T value) {
    }

    @Override
    public void onError(Throwable throwable) {
    }

    @Override
    public void onCompleted() {
    }
  }
}
