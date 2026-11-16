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

import io.grpc.Status;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Runs access to mutable Minecraft client state on its owning game thread.
public final class BotThreadExecution {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private BotThreadExecution() {}

  public static <T> T call(
    BotConnection bot,
    Callable<T> action
  ) throws Exception {
    var minecraft = bot.minecraft();
    if (minecraft.isSameThread()) {
      return bot.runnableWrapper().wrap(action).call();
    }

    var result = bot.gameThreadTasks().submit(minecraft::execute, bot.runnableWrapper().wrap(() -> {
      // API callers drive the bot, so vanilla must not throttle it as idle while they do.
      minecraft.getFramerateLimitTracker().onInputReceived();
      return action.call();
    }));
    try {
      return result.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (CancellationException exception) {
      throw Status.UNAVAILABLE
        .withDescription("Bot connection is closing")
        .withCause(exception)
        .asRuntimeException();
    } catch (TimeoutException exception) {
      throw Status.DEADLINE_EXCEEDED
        .withDescription("Timed out waiting for the bot game thread")
        .withCause(exception)
        .asRuntimeException();
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw exception;
    } finally {
      // Timed-out or interrupted callers must not leave mutations queued for a later tick.
      result.cancel(false);
    }
  }
}
