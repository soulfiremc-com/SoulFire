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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.function.LongSupplier;

public interface ControlTask {
  static ControlTask once(Runnable runnable) {
    return once(null, ControlPriority.NORMAL, runnable);
  }

  static ControlTask once(String description, Runnable runnable) {
    return once(description, ControlPriority.NORMAL, runnable);
  }

  static ControlTask once(@Nullable String description, ControlPriority priority, Runnable runnable) {
    return new OnceTask(description, priority, runnable);
  }

  static ControlTask sequence(Step... steps) {
    return sequence(null, ControlPriority.NORMAL, steps);
  }

  static ControlTask sequence(String description, Step... steps) {
    return sequence(description, ControlPriority.NORMAL, steps);
  }

  static ControlTask sequence(@Nullable String description, ControlPriority priority, Step... steps) {
    return new SequenceTask(description, priority, List.of(steps));
  }

  static ControlTask sequence(@Nullable String description, ControlPriority priority, List<Step> steps) {
    return new SequenceTask(description, priority, steps);
  }

  static <M> MarkerTask<M> marker(M marker) {
    return marker(null, ControlPriority.NORMAL, marker);
  }

  static <M> MarkerTask<M> marker(@Nullable String description, ControlPriority priority, M marker) {
    return new MarkerTask<>(description, priority, marker);
  }

  static ActionStep action(Runnable runnable) {
    return new ActionStep(runnable);
  }

  static DelayStep waitFor(LongSupplier delaySupplier) {
    return new DelayStep(delaySupplier);
  }

  static DelayStep waitMillis(long delayMillis) {
    return new DelayStep(() -> delayMillis);
  }

  void tick();

  boolean isDone();

  default ControlPriority priority() {
    return ControlPriority.NORMAL;
  }

  default void onStarted() {
  }

  default void onSuspended() {
  }

  default void onResumed() {
  }

  default void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
  }

  /// Returns a human-readable description of this task for error logging.
  default @Nullable String description() {
    var simpleName = getClass().getSimpleName();
    return simpleName.isBlank() ? null : simpleName;
  }

  interface Step {
  }

  record ActionStep(Runnable runnable) implements Step {}

  record DelayStep(LongSupplier delaySupplier) implements Step {}

  final class OnceTask implements ControlTask {
    private final @Nullable String taskDescription;
    private final ControlPriority taskPriority;
    private final Runnable runnable;
    private boolean done;

    private OnceTask(@Nullable String taskDescription, ControlPriority taskPriority, Runnable runnable) {
      this.taskDescription = taskDescription;
      this.taskPriority = taskPriority;
      this.runnable = runnable;
    }

    @Override
    public void tick() {
      if (done) {
        return;
      }

      runnable.run();
      done = true;
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public ControlPriority priority() {
      return taskPriority;
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      done = true;
    }

    @Override
    public @Nullable String description() {
      return taskDescription;
    }
  }

  final class SequenceTask implements ControlTask {
    private final @Nullable String taskDescription;
    private final ControlPriority taskPriority;
    private final List<Step> steps;
    private long currentDelayUntil;
    private int currentStep;
    private boolean done;

    private SequenceTask(@Nullable String taskDescription, ControlPriority taskPriority, List<Step> steps) {
      this.taskDescription = taskDescription;
      this.taskPriority = taskPriority;
      this.steps = List.copyOf(steps);
      this.done = steps.isEmpty();
    }

    @Override
    public void tick() {
      if (done) {
        return;
      }

      var step = steps.get(currentStep);
      if (step instanceof ActionStep(var runnable)) {
        runnable.run();
      } else if (step instanceof DelayStep(var delaySupplier)) {
        if (currentDelayUntil == 0L) {
          currentDelayUntil = System.currentTimeMillis() + delaySupplier.getAsLong();
        }

        if (System.currentTimeMillis() < currentDelayUntil) {
          return;
        }

        currentDelayUntil = 0L;
      }

      if (currentStep >= steps.size() - 1) {
        done = true;
      } else {
        currentStep++;
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public ControlPriority priority() {
      return taskPriority;
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      done = true;
    }

    @Override
    public @Nullable String description() {
      return taskDescription;
    }
  }

  final class MarkerTask<M> implements ControlTask {
    private final @Nullable String taskDescription;
    private final ControlPriority taskPriority;
    private final M marker;
    private boolean done;

    private MarkerTask(@Nullable String taskDescription, ControlPriority taskPriority, M marker) {
      this.taskDescription = taskDescription;
      this.taskPriority = taskPriority;
      this.marker = marker;
    }

    public M marker() {
      return marker;
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public ControlPriority priority() {
      return taskPriority;
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      done = true;
    }

    @Override
    public @Nullable String description() {
      return taskDescription;
    }
  }
}
