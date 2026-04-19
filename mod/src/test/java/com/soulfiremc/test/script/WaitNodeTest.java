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
package com.soulfiremc.test.script;

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.script.NodeRuntime;
import com.soulfiremc.server.script.NodeValue;
import com.soulfiremc.server.script.ScriptStateStore;
import com.soulfiremc.server.script.nodes.action.WaitNode;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WaitNodeTest {
  @Test
  void waitNodeSchedulesDelayOnRuntimeScheduler() {
    var scheduler = new RecordingScheduler();
    var runtime = new TestRuntime(scheduler);
    var waitNode = new WaitNode();
    var inputs = Map.of(
      "baseMs", NodeValue.ofNumber(25),
      "jitterMs", NodeValue.ofNumber(0),
      "minMs", NodeValue.ofNumber(0),
      "maxMs", NodeValue.ofNumber(1000)
    );

    var result = waitNode.executeReactive(runtime, inputs).block(Duration.ofSeconds(1));

    assertNotNull(result, "Wait node should return outputs");
    assertTrue(scheduler.delayScheduled.get(),
      "Wait node should schedule delay using the runtime scheduler");
    assertEquals(25L, scheduler.lastDelayMs.get(),
      "Wait node should schedule the configured delay");
  }

  private static final class TestRuntime implements NodeRuntime {
    private final ScriptStateStore stateStore = new ScriptStateStore();
    private final Scheduler reactorScheduler;

    private TestRuntime(Scheduler reactorScheduler) {
      this.reactorScheduler = reactorScheduler;
    }

    @Override
    public ScriptStateStore stateStore() {
      return stateStore;
    }

    @Override
    public InstanceManager instance() {
      return null;
    }

    @Override
    public SoulFireScheduler scheduler() {
      return null;
    }

    @Override
    public Scheduler reactorScheduler() {
      return reactorScheduler;
    }

    @Override
    public void log(String level, String message) {}
  }

  private static final class RecordingScheduler implements Scheduler {
    private final Scheduler delegate = Schedulers.immediate();
    private final AtomicBoolean delayScheduled = new AtomicBoolean(false);
    private final AtomicLong lastDelayMs = new AtomicLong(-1);

    @Override
    public Disposable schedule(Runnable task) {
      return delegate.schedule(task);
    }

    @Override
    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
      delayScheduled.set(true);
      lastDelayMs.set(unit.toMillis(delay));
      task.run();
      return () -> {};
    }

    @Override
    public Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
      return delegate.schedulePeriodically(task, initialDelay, period, unit);
    }

    @Override
    public Worker createWorker() {
      var worker = delegate.createWorker();
      return new Worker() {
        @Override
        public Disposable schedule(Runnable task) {
          return worker.schedule(task);
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
          delayScheduled.set(true);
          lastDelayMs.set(unit.toMillis(delay));
          task.run();
          return () -> {};
        }

        @Override
        public void dispose() {
          worker.dispose();
        }

        @Override
        public boolean isDisposed() {
          return worker.isDisposed();
        }
      };
    }

    @Override
    public void dispose() {
      delegate.dispose();
    }

    @Override
    public boolean isDisposed() {
      return delegate.isDisposed();
    }
  }
}
