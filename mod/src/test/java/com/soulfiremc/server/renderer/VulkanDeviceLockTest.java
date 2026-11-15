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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.soulfiremc.server.renderer.VulkanRenderer.DEVICE_LOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkanDeviceLockTest {
  @AfterEach
  void releaseLock() {
    while (DEVICE_LOCK.isHeldByCurrentThread()) DEVICE_LOCK.unlock();
  }

  @Test
  void anotherBotCanUseTheDeviceDuringATick() {
    DEVICE_LOCK.lock();

    VulkanRenderer.runWithoutDeviceLock(() -> {
      assertFalse(DEVICE_LOCK.isHeldByCurrentThread());
      assertTrue(otherThreadAcquires(1_000));
    });

    assertEquals(1, DEVICE_LOCK.getHoldCount());
  }

  @Test
  void deviceUseKeepsTheLockUntilTheFrameEnds() {
    DEVICE_LOCK.lock();

    VulkanRenderer.runWithoutDeviceLock(() -> {
      assertTrue(VulkanRenderer.claimDeviceAccess());
      assertTrue(VulkanRenderer.claimDeviceAccess());
      assertFalse(otherThreadAcquires(50));
    });

    assertEquals(1, DEVICE_LOCK.getHoldCount());
    assertFalse(otherThreadAcquires(50));
  }

  @Test
  void explicitLockingInsideATickStaysBalanced() {
    DEVICE_LOCK.lock();

    VulkanRenderer.runWithoutDeviceLock(() -> {
      DEVICE_LOCK.lock();
      try {
        assertTrue(VulkanRenderer.claimDeviceAccess());
      } finally {
        DEVICE_LOCK.unlock();
      }
      assertTrue(otherThreadAcquires(1_000));
    });

    assertEquals(1, DEVICE_LOCK.getHoldCount());
  }

  @Test
  void aFailedTickStillReturnsTheLock() {
    DEVICE_LOCK.lock();
    var failure = new IllegalStateException("tick failed");

    var thrown = assertThrows(IllegalStateException.class, () -> VulkanRenderer.runWithoutDeviceLock(() -> {
      throw failure;
    }));

    assertSame(failure, thrown);
    assertEquals(1, DEVICE_LOCK.getHoldCount());
  }

  @Test
  void nestedHoldersKeepTheLockDuringATick() {
    DEVICE_LOCK.lock();
    DEVICE_LOCK.lock();

    VulkanRenderer.runWithoutDeviceLock(() -> assertTrue(DEVICE_LOCK.isHeldByCurrentThread()));

    assertEquals(2, DEVICE_LOCK.getHoldCount());
  }

  @Test
  void claimsOutsideATickDoNotTakeTheLock() {
    assertFalse(VulkanRenderer.claimDeviceAccess());
    assertFalse(DEVICE_LOCK.isHeldByCurrentThread());
  }

  private static boolean otherThreadAcquires(long timeoutMillis) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (!DEVICE_LOCK.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false;
        DEVICE_LOCK.unlock();
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }).join();
  }
}
