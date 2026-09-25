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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class BotControlAPITest {
  @Test
  void runsTasksWithDisjointResourcesConcurrently() {
    var control = new BotControlAPI();
    var movement = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT);
    var chat = new RecordingTask(ControlPriority.NORMAL, ControlResource.CHAT);

    assertTrue(control.tryStart(movement));
    assertTrue(control.tryStart(chat));

    control.tick();

    assertEquals(1, movement.ticks);
    assertEquals(1, chat.ticks);
    assertEquals(ControlStopReason.COMPLETED, movement.stopReason);
    assertEquals(ControlStopReason.COMPLETED, chat.stopReason);
  }

  @Test
  void suspendsAndResumesOnlyConflictingTasks() {
    var control = new BotControlAPI();
    var movement = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT, 2);
    var chat = new RecordingTask(ControlPriority.NORMAL, ControlResource.CHAT, 2);
    var urgentMovement = new RecordingTask(ControlPriority.HIGH, ControlResource.MOVEMENT);
    control.tryStart(movement);
    control.tryStart(chat);

    assertTrue(control.submit(urgentMovement));
    assertEquals(1, movement.suspensions);
    assertEquals(0, chat.suspensions);

    control.tick();
    control.tick();

    assertEquals(1, movement.resumptions);
    assertEquals(1, movement.ticks);
    assertEquals(2, chat.ticks);
  }

  @Test
  void replacementDoesNotCancelIndependentResources() {
    var control = new BotControlAPI();
    var movement = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT, 2);
    var chat = new RecordingTask(ControlPriority.NORMAL, ControlResource.CHAT, 2);
    var replacement = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT);
    control.tryStart(movement);
    control.tryStart(chat);

    control.replace(replacement);
    control.tick();

    assertEquals(ControlStopReason.REPLACED, movement.stopReason);
    assertNull(chat.stopReason);
    assertEquals(1, chat.ticks);
    assertEquals(ControlStopReason.COMPLETED, replacement.stopReason);
  }

  @Test
  void doesNotSuspendAConflictingTaskAtTheSamePriority() {
    var control = new BotControlAPI();
    var active = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT, 2);
    var requested = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT);
    control.tryStart(active);

    assertFalse(control.submit(requested));

    assertEquals(0, active.suspensions);
    assertNull(requested.stopReason);
  }

  @Test
  void startsQueuedTaskWhenConflictingInternalControlFinishes() {
    var control = new BotControlAPI();
    var internal = new RecordingTask(
      ControlPriority.LOW,
      ControlResource.MAIN_HAND
    );
    var queued = new RecordingTask(
      ControlPriority.NORMAL,
      ControlResource.MAIN_HAND
    );
    control.tryStart(internal);

    control.enqueue(queued);
    control.tick();

    assertEquals(ControlStopReason.COMPLETED, internal.stopReason);
    assertEquals(0, queued.ticks);

    control.tick();

    assertEquals(1, queued.ticks);
    assertEquals(ControlStopReason.COMPLETED, queued.stopReason);
  }

  @Test
  void cancellationRemovesQueuedTask() {
    var control = new BotControlAPI();
    var active = new RecordingTask(
      ControlPriority.NORMAL,
      ControlResource.INVENTORY,
      2
    );
    var queued = new RecordingTask(
      ControlPriority.NORMAL,
      ControlResource.INVENTORY
    );
    control.tryStart(active);
    control.enqueue(queued);

    assertTrue(control.cancel(queued));
    control.tick();
    control.tick();

    assertEquals(ControlStopReason.CANCELLED, queued.stopReason);
    assertEquals(0, queued.ticks);
  }

  @Test
  void combatOverlayRequiresEveryActiveTaskToCooperate() {
    var control = new BotControlAPI();
    var movement = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT, 2) {
      @Override
      public boolean allowsCombatOverlay() {
        return true;
      }
    };
    var inventory = new RecordingTask(ControlPriority.NORMAL, ControlResource.INVENTORY, 2);

    assertTrue(control.allowsCombatOverlay());
    assertTrue(control.tryStart(movement));
    assertTrue(control.allowsCombatOverlay());
    assertTrue(control.tryStart(inventory));
    assertFalse(control.allowsCombatOverlay());
    assertTrue(control.cancel(inventory));
    assertTrue(control.allowsCombatOverlay());
  }

  @Test
  void resourceFreeCombatMarkerDoesNotInterruptMovement() {
    var control = new BotControlAPI();
    var movement = new RecordingTask(ControlPriority.NORMAL, ControlResource.MOVEMENT, 2);
    var target = new Object();
    var marker = ControlTask.marker(null, ControlPriority.LOW, Set.of(), target);

    assertTrue(control.tryStart(movement));
    assertTrue(control.tryStart(marker));
    assertSame(target, control.claimMarker(Object.class));
    control.tick();
    assertEquals(1, movement.ticks);
  }

  private static class RecordingTask implements ControlTask {
    private final ControlPriority priority;
    private final Set<ControlResource> resources;
    private final int requiredTicks;
    private int ticks;
    private int suspensions;
    private int resumptions;
    private ControlStopReason stopReason;

    private RecordingTask(ControlPriority priority, ControlResource resource) {
      this(priority, resource, 1);
    }

    private RecordingTask(
      ControlPriority priority,
      ControlResource resource,
      int requiredTicks
    ) {
      this.priority = priority;
      this.resources = Set.of(resource);
      this.requiredTicks = requiredTicks;
    }

    @Override
    public void tick() {
      ticks++;
    }

    @Override
    public boolean isDone() {
      return ticks >= requiredTicks;
    }

    @Override
    public ControlPriority priority() {
      return priority;
    }

    @Override
    public Set<ControlResource> resources() {
      return resources;
    }

    @Override
    public void onSuspended() {
      suspensions++;
    }

    @Override
    public void onResumed() {
      resumptions++;
    }

    @Override
    public void onStopped(ControlStopReason reason, Throwable cause) {
      stopReason = reason;
    }
  }
}
