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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/// This class is used to control the bot. The goal is to reduce friction for doing simple things.
@Slf4j
@RequiredArgsConstructor
public final class BotControlAPI {
  private final Deque<ControlTask> taskStack = new ArrayDeque<>();

  public synchronized void tick() {
    var task = activeTask();
    if (task == null) {
      return;
    }

    if (task.isDone()) {
      finishActiveTask(ControlStopReason.COMPLETED, null);
      return;
    }

    try {
      task.tick();
    } catch (Throwable t) {
      logTaskFailure("executing", task, t);
      finishActiveTask(ControlStopReason.FAILED, t);
      return;
    }

    if (task.isDone()) {
      finishActiveTask(ControlStopReason.COMPLETED, null);
    }
  }

  public synchronized boolean stopAll() {
    return clearTasks(ControlStopReason.CANCELLED, null);
  }

  public synchronized boolean hasActiveTask() {
    return activeTask() != null;
  }

  public synchronized void replace(ControlTask task) {
    clearTasks(ControlStopReason.REPLACED, null);
    startTask(task);
  }

  public synchronized boolean tryStart(ControlTask task) {
    if (hasActiveTask()) {
      return false;
    }

    startTask(task);
    return true;
  }

  public synchronized boolean submit(ControlTask task) {
    var current = activeTask();
    if (current == null) {
      startTask(task);
      return true;
    }

    if (!task.priority().canPreempt(current.priority())) {
      return false;
    }

    if (!suspendActiveTask(current)) {
      return false;
    }

    startTask(task);
    return true;
  }

  public synchronized <M> @Nullable M claimMarker(Class<M> clazz) {
    var task = activeTask();
    if (task instanceof ControlTask.MarkerTask<?> markerTask
      && clazz.isInstance(markerTask.marker())) {
      var marker = clazz.cast(markerTask.marker());
      finishActiveTask(ControlStopReason.CLAIMED, null);
      return marker;
    }

    return null;
  }

  private @Nullable ControlTask activeTask() {
    return taskStack.peekLast();
  }

  private void startTask(ControlTask task) {
    taskStack.addLast(task);
    try {
      task.onStarted();
    } catch (Throwable t) {
      logTaskFailure("starting", task, t);
      taskStack.removeLastOccurrence(task);
      stopTask(task, ControlStopReason.FAILED, t);
      resumeActiveTask();
    }
  }

  private boolean suspendActiveTask(ControlTask task) {
    try {
      task.onSuspended();
      return true;
    } catch (Throwable t) {
      logTaskFailure("suspending", task, t);
      finishActiveTask(ControlStopReason.FAILED, t);
      return false;
    }
  }

  private void finishActiveTask(ControlStopReason reason, @Nullable Throwable cause) {
    var task = taskStack.pollLast();
    if (task == null) {
      return;
    }

    stopTask(task, reason, cause);
    resumeActiveTask();
  }

  private void resumeActiveTask() {
    while (true) {
      var task = activeTask();
      if (task == null) {
        return;
      }

      try {
        task.onResumed();
        return;
      } catch (Throwable t) {
        logTaskFailure("resuming", task, t);
        taskStack.pollLast();
        stopTask(task, ControlStopReason.FAILED, t);
      }
    }
  }

  private boolean clearTasks(ControlStopReason reason, @Nullable Throwable cause) {
    var stoppedAny = false;
    ControlTask task;
    while ((task = taskStack.pollLast()) != null) {
      stoppedAny = true;
      stopTask(task, reason, cause);
    }

    return stoppedAny;
  }

  private void stopTask(ControlTask task, ControlStopReason reason, @Nullable Throwable cause) {
    try {
      task.onStopped(reason, cause);
    } catch (Throwable t) {
      logTaskFailure("stopping", task, t);
    }
  }

  private void logTaskFailure(String action, ControlTask task, Throwable t) {
    var desc = task.description();
    if (desc != null) {
      log.error("Error while {} control task ({})", action, desc, t);
    } else {
      log.error("Error while {} control task", action, t);
    }
  }
}
