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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PovFrameTasksTest {
  @Test
  void captureReadsSimulationStateAtRenderPhase() {
    var state = new AtomicInteger(0);
    try (var tasks = new PovFrameTasks()) {
      var capture = tasks.submit(state::get);
      assertFalse(capture.isDone());
      state.set(1);
      tasks.drain();
      assertEquals(1, capture.join());
    }
  }

  @Test
  void deferReentrantTasksAndCancelPendingCapturesOnClose() {
    var tasks = new PovFrameTasks();
    var nested = tasks.submit(() -> tasks.submit(() -> 42));
    tasks.drain();
    assertFalse(nested.join().isDone());
    tasks.close();
    assertTrue(nested.join().isCancelled());
    assertTrue(tasks.submit(() -> 1).isCancelled());
  }

  @Test
  void failedCaptureDoesNotPreventTheNextCapture() {
    try (var tasks = new PovFrameTasks()) {
      var failure = tasks.submit(() -> { throw new IllegalStateException(); });
      var next = tasks.submit(() -> 42);
      tasks.drain();
      assertTrue(failure.isCompletedExceptionally());
      assertEquals(42, next.join());
    }
  }
}
