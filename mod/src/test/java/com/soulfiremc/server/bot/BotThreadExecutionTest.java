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
package com.soulfiremc.server.bot;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.Window;
import com.soulfiremc.server.SoulFireScheduler;
import io.grpc.Status;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

final class BotThreadExecutionTest {
  @Test
  void disconnectUnblocksAQueuedCallerWithoutRunningItsAction() throws Exception {
    var queue = new LinkedBlockingQueue<Runnable>();
    var calls = new AtomicInteger();
    try (var tasks = new BotThreadTasks()) {
      var bot = bot(tasks);
      var minecraft = bot.minecraft();
      doAnswer(call -> {
        queue.add(call.getArgument(0));
        return null;
      }).when(minecraft).execute(any(Runnable.class));
      var result = new CompletableFuture<Integer>();
      var caller = Thread.ofVirtual().start(() -> {
        try {
          result.complete(BotThreadExecution.call(bot, calls::incrementAndGet));
        } catch (Throwable failure) {
          result.completeExceptionally(failure);
        }
      });
      try {
        var queued = queue.poll(2, TimeUnit.SECONDS);
        assertNotNull(queued);
        tasks.close();
        var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(failure.getCause()).getCode());
        queued.run();
        assertEquals(0, calls.get());
      } finally {
        caller.interrupt();
        caller.join();
      }
    }
  }

  @Test
  void closedQueueStillAllowsCleanupOnTheOwningGameThread() throws Exception {
    try (var tasks = new BotThreadTasks()) {
      var bot = bot(tasks);
      tasks.close();
      when(bot.minecraft().isSameThread()).thenReturn(true);
      assertEquals(42, BotThreadExecution.call(bot, () -> 42));
    }
  }

  @Test
  void apiCallsKeepAnIdleBotAtItsFullFrameRate() throws Exception {
    try (var tasks = new BotThreadTasks(); var clock = mockStatic(Util.class)) {
      var bot = bot(tasks);
      var minecraft = bot.minecraft();
      minecraft.level = mock(ClientLevel.class);
      when(minecraft.getWindow()).thenReturn(mock(Window.class));
      var options = mock(Options.class, RETURNS_DEEP_STUBS);
      when(options.framerateLimit().get()).thenReturn(120);
      when(options.inactivityFpsLimit().get()).thenReturn(InactivityFpsLimit.AFK);
      var limiter = new FramerateLimitTracker(options, minecraft);
      when(minecraft.getFramerateLimitTracker()).thenReturn(limiter);
      doAnswer(call -> {
        call.<Runnable>getArgument(0).run();
        return null;
      }).when(minecraft).execute(any(Runnable.class));

      clock.when(Util::getMillis).thenReturn(600_001L);
      assertEquals(10, limiter.getFramerateLimit());
      assertEquals(120, BotThreadExecution.call(bot, limiter::getFramerateLimit));

      clock.when(Util::getMillis).thenReturn(1_200_002L);
      assertEquals(10, limiter.getFramerateLimit());
    }
  }

  private static BotConnection bot(BotThreadTasks tasks) {
    var bot = mock(BotConnection.class);
    when(bot.minecraft()).thenReturn(mock(Minecraft.class));
    when(bot.gameThreadTasks()).thenReturn(tasks);
    when(bot.runnableWrapper()).thenReturn((SoulFireScheduler.RunnableWrapper) runnable -> runnable);
    return bot;
  }
}
