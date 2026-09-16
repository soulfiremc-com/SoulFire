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

import com.soulfiremc.grpc.generated.PovStreamFeedback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePovBitrateTest {
  private static PovStreamFeedback feedback(long sequence, double delay, int queue, int recoveries) {
    return PovStreamFeedback.newBuilder().setReceivedSequence(sequence).setDeliveryDelayMs(delay)
      .setDecoderQueueSize(queue).setDecoderRecoveries(recoveries).build();
  }

  @Test
  void probesOnlyAfterSustainedFreshFeedbackAndBacksOffOnCongestion() {
    var rate = new AdaptivePovBitrate(1920, 1080, 0);
    var initial = rate.bitrate();
    for (var second = 1; second <= 3; second++) rate.update(feedback(second, 10, 0, 0), true, second * 1_000_000_000L);
    assertTrue(rate.bitrate() > initial);
    var raised = rate.bitrate();
    rate.update(feedback(4, 200, 0, 0), true, 4_000_000_000L);
    assertEquals(raised * 7 / 10, rate.bitrate());
    var lowered = rate.bitrate();
    rate.update(feedback(5, 200, 0, 0), true, 4_100_000_000L);
    assertEquals(lowered, rate.bitrate());
    rate.update(feedback(6, 0, 3, 0), true, 5_000_000_000L);
    assertEquals(lowered * 7 / 10, rate.bitrate());
  }

  @Test
  void holdsForOldViewersAndStaleFeedbackAndBoundsBothDirections() {
    var rate = new AdaptivePovBitrate(640, 360, 0);
    var initial = rate.bitrate();
    for (var second = 1; second <= 10; second++) rate.update(null, true, second * 1_000_000_000L);
    assertEquals(initial, rate.bitrate());
    for (var second = 11; second <= 20; second++) rate.update(feedback(1, 0, 0, 0), true, second * 1_000_000_000L);
    assertEquals(initial, rate.bitrate());
    for (var second = 21; second <= 100; second++) rate.update(feedback(second, 0, 0, 0), true, second * 1_000_000_000L);
    assertEquals(640L * 360 * 16, rate.bitrate());
    for (var second = 101; second <= 150; second++) rate.update(null, false, second * 1_000_000_000L);
    assertEquals(300_000L, rate.bitrate());
  }

  @Test
  void decoderRecoveryTriggersBackoffEvenAfterItsQueueHasDrained() {
    var rate = new AdaptivePovBitrate(1920, 1080, 0);
    rate.update(feedback(1, 0, 0, 5), true, 1_000_000_000L);
    var initial = rate.bitrate();
    rate.update(feedback(2, 0, 0, 6), true, 2_000_000_000L);
    assertEquals(initial * 7 / 10, rate.bitrate());
  }
}
