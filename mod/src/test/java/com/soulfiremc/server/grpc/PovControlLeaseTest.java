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
package com.soulfiremc.server.grpc;

import com.soulfiremc.grpc.generated.PovInputEvent;
import com.soulfiremc.grpc.generated.PovInputRequest;
import com.soulfiremc.grpc.generated.PovStreamFeedback;
import com.soulfiremc.server.bot.BotControlLeaseManager;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PovControlLeaseTest {
  private final BotControlLeaseManager leases = new BotControlLeaseManager();
  private final UUID botId = UUID.randomUUID();
  private final PovControlLease control = new PovControlLease(leases, botId, UUID.randomUUID());

  @Test
  void onlyInputThatActsOnTheBotControlsIt() {
    var heartbeat = PovInputRequest.newBuilder().setSequence(1).setWidth(640).setHeight(360)
      .setFeedback(PovStreamFeedback.newBuilder().setReceivedSequence(3)).setRequestKeyFrame(true).setReadClipboard(true).build();
    assertFalse(PovServiceImpl.controls(heartbeat));
    assertFalse(PovServiceImpl.controls(heartbeat.toBuilder()
      .addEvents(PovInputEvent.newBuilder().setKind(PovInputEvent.Kind.KEY).setCode(87).setAction(1)).build()));
    assertTrue(PovServiceImpl.controls(heartbeat.toBuilder().setCaptured(true).build()));
    assertTrue(PovServiceImpl.controls(heartbeat.toBuilder().setEscape(true).build()));
    assertTrue(PovServiceImpl.controls(heartbeat.toBuilder().setClipboard("text").build()));
  }

  @Test
  void holdsTheLeaseFromHoldUntilRelease() {
    assertDoesNotThrow(() -> leases.authorize(botId, null));

    control.hold();
    assertThrows(BotControlLeaseManager.InvalidLeaseException.class, () -> leases.authorize(botId, null));
    control.hold();
    assertThrows(BotControlLeaseManager.InvalidLeaseException.class, () -> leases.authorize(botId, null));

    control.release();
    assertDoesNotThrow(() -> leases.authorize(botId, null));
    assertDoesNotThrow(control::release);
  }

  @Test
  void leavesAnotherClientsLeaseAlone() {
    var sdk = leases.acquire(botId, UUID.randomUUID(), Duration.ofSeconds(30));

    assertThrows(BotControlLeaseManager.LeaseUnavailableException.class, control::hold);
    control.release();
    control.close();
    assertDoesNotThrow(() -> leases.authorize(botId, sdk.token()));
    assertThrows(BotControlLeaseManager.InvalidLeaseException.class, () -> leases.authorize(botId, null));
  }

  @Test
  void stopsControllingOnceTheLeaseWasCleared() {
    control.hold();
    leases.clear(botId);

    assertThrows(BotControlLeaseManager.InvalidLeaseException.class, control::hold);
    assertDoesNotThrow(() -> leases.authorize(botId, null));
    assertDoesNotThrow(control::release);
  }

  @Test
  void releasesOnCloseAndNeverHoldsAgain() {
    control.hold();
    control.close();
    assertDoesNotThrow(() -> leases.authorize(botId, null));

    assertThrows(StatusRuntimeException.class, control::hold);
    assertDoesNotThrow(() -> leases.authorize(botId, null));
  }
}
