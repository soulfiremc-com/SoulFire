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

import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/// Arbitrates independently claimable bot resources between control tasks.
@Slf4j
public final class BotControlAPI {
  private final List<ControlTask> activeTasks = new ArrayList<>();
  private final Deque<ControlTask> suspendedTasks = new ArrayDeque<>();
  private final Deque<ControlTask> queuedTasks = new ArrayDeque<>();

  public synchronized void tick() {
    for (var task : List.copyOf(activeTasks)) {
      if (!activeTasks.contains(task)) {
        continue;
      }
      if (task.isDone()) {
        finishTask(task, ControlStopReason.COMPLETED, null);
        continue;
      }
      try {
        task.tick();
      } catch (Throwable t) {
        logTaskFailure("executing", task, t);
        finishTask(task, ControlStopReason.FAILED, t);
        continue;
      }
      if (task.isDone()) {
        finishTask(task, ControlStopReason.COMPLETED, null);
      }
    }
  }

  public synchronized boolean stopAll() {
    var stoppedAny =
      !activeTasks.isEmpty() || !suspendedTasks.isEmpty() || !queuedTasks.isEmpty();
    for (var task : List.copyOf(activeTasks)) {
      activeTasks.remove(task);
      stopTask(task, ControlStopReason.CANCELLED, null);
    }
    ControlTask task;
    while ((task = suspendedTasks.pollLast()) != null) {
      stopTask(task, ControlStopReason.CANCELLED, null);
    }
    while ((task = queuedTasks.pollFirst()) != null) {
      stopTask(task, ControlStopReason.CANCELLED, null);
    }
    return stoppedAny;
  }

  public synchronized boolean cancel(ControlTask task) {
    if (activeTasks.remove(task)
      || suspendedTasks.remove(task)
      || queuedTasks.remove(task)) {
      stopTask(task, ControlStopReason.CANCELLED, null);
      resumeTasks();
      startQueuedTasks();
      return true;
    }
    return false;
  }

  public synchronized boolean hasActiveTask() {
    return !activeTasks.isEmpty();
  }

  public synchronized boolean hasActiveTask(ControlResource resource) {
    return activeTasks.stream().anyMatch(task -> task.resources().contains(resource));
  }

  public synchronized boolean allowsCombatOverlay() {
    return activeTasks.stream().allMatch(ControlTask::allowsCombatOverlay);
  }

  public synchronized void replace(ControlTask task) {
    for (var conflict : conflicts(task)) {
      activeTasks.remove(conflict);
      stopTask(conflict, ControlStopReason.REPLACED, null);
    }
    suspendedTasks.removeIf(conflict -> {
      if (!conflicts(task, conflict)) {
        return false;
      }
      stopTask(conflict, ControlStopReason.REPLACED, null);
      return true;
    });
    startTask(task);
  }

  public synchronized boolean tryStart(ControlTask task) {
    if (!conflicts(task).isEmpty()) {
      return false;
    }
    startTask(task);
    return activeTasks.contains(task);
  }

  public synchronized void enqueue(ControlTask task) {
    if (conflicts(task).isEmpty()) {
      startTask(task);
      return;
    }
    queuedTasks.addLast(task);
  }

  public synchronized boolean submit(ControlTask task) {
    var conflicts = conflicts(task);
    if (conflicts.isEmpty()) {
      startTask(task);
      return activeTasks.contains(task);
    }
    if (conflicts.stream().anyMatch(active ->
      !task.priority().canPreempt(active.priority()))) {
      return false;
    }
    for (var active : conflicts) {
      if (!suspendTask(active)) {
        resumeTasks();
        return false;
      }
    }
    startTask(task);
    return activeTasks.contains(task);
  }

  public synchronized <M> @Nullable M claimMarker(Class<M> clazz) {
    for (var task : List.copyOf(activeTasks)) {
      if (task instanceof ControlTask.MarkerTask<?> markerTask
        && clazz.isInstance(markerTask.marker())) {
        var marker = clazz.cast(markerTask.marker());
        finishTask(task, ControlStopReason.CLAIMED, null);
        return marker;
      }
    }
    return null;
  }

  private void startTask(ControlTask task) {
    activeTasks.add(task);
    try {
      task.onStarted();
    } catch (Throwable t) {
      logTaskFailure("starting", task, t);
      activeTasks.remove(task);
      stopTask(task, ControlStopReason.FAILED, t);
      resumeTasks();
    }
  }

  private boolean suspendTask(ControlTask task) {
    try {
      task.onSuspended();
      activeTasks.remove(task);
      suspendedTasks.addLast(task);
      return true;
    } catch (Throwable t) {
      logTaskFailure("suspending", task, t);
      activeTasks.remove(task);
      stopTask(task, ControlStopReason.FAILED, t);
      return false;
    }
  }

  private void finishTask(
    ControlTask task,
    ControlStopReason reason,
    @Nullable Throwable cause
  ) {
    if (!activeTasks.remove(task)) {
      return;
    }
    stopTask(task, reason, cause);
    resumeTasks();
    startQueuedTasks();
  }

  private void resumeTasks() {
    var candidates = new ArrayList<ControlTask>();
    while (!suspendedTasks.isEmpty()) {
      candidates.add(suspendedTasks.pollLast());
    }
    for (var task : candidates) {
      if (!conflicts(task).isEmpty()) {
        suspendedTasks.addFirst(task);
        continue;
      }
      try {
        task.onResumed();
        activeTasks.add(task);
      } catch (Throwable t) {
        logTaskFailure("resuming", task, t);
        stopTask(task, ControlStopReason.FAILED, t);
      }
    }
  }

  private void startQueuedTasks() {
    var iterator = queuedTasks.iterator();
    while (iterator.hasNext()) {
      var task = iterator.next();
      if (!conflicts(task).isEmpty()) {
        continue;
      }
      iterator.remove();
      startTask(task);
    }
  }

  private List<ControlTask> conflicts(ControlTask requested) {
    return activeTasks.stream()
      .filter(active -> conflicts(requested, active))
      .toList();
  }

  private static boolean conflicts(ControlTask left, ControlTask right) {
    return left.resources().stream().anyMatch(right.resources()::contains);
  }

  private void stopTask(
    ControlTask task,
    ControlStopReason reason,
    @Nullable Throwable cause
  ) {
    try {
      task.onStopped(reason, cause);
    } catch (Throwable t) {
      logTaskFailure("stopping", task, t);
    }
  }

  private void logTaskFailure(String action, ControlTask task, Throwable t) {
    var description = task.description();
    if (description != null) {
      log.error("Error while {} control task ({})", action, description, t);
    } else {
      log.error("Error while {} control task", action, t);
    }
  }
}
