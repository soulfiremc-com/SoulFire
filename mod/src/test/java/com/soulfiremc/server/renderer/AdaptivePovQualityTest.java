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

class AdaptivePovQualityTest {
  private static PovStreamFeedback feedback(long sequence, double delay, int queue) {
    return PovStreamFeedback.newBuilder().setReceivedSequence(sequence).setDeliveryDelayMs(delay)
      .setDecoderQueueSize(queue).build();
  }

  @Test void sustainedCongestionReducesPixelRateAndRecoveryIsSlow() {
    var quality = new AdaptivePovQuality(3840, 2160, 60, 0);
    var initial = quality.bitrate();
    quality.update(feedback(1, 200, 0), true, 1_000_000_000L);
    assertTrue(quality.bitrate() < initial);
    assertEquals(60, quality.fps());
    for (var second = 2; second <= 6; second++) quality.update(feedback(second, 200, 0), true, second * 1_000_000_000L);
    assertEquals(30, quality.fps());
    assertEquals(2880, quality.width());
    assertEquals(1620, quality.height());
    for (var second = 7; second <= 20; second++) quality.update(feedback(second, 0, 0), true, second * 1_000_000_000L);
    assertEquals(2880, quality.width());
    quality.update(feedback(21, 0, 0), true, 21_000_000_000L);
    assertEquals(3840, quality.width());
  }

  @Test void rendererAndDecoderOverloadReduceFpsWithoutMistakingItForBandwidth() {
    var quality = new AdaptivePovQuality(2560, 1440, 120, 0);
    var bitrate = quality.bitrate();
    for (var second = 1; second <= 3; second++) {
      quality.recordFrame(18_000_000);
      quality.update(feedback(second, 0, 0), true, second * 1_000_000_000L);
    }
    assertEquals(60, quality.fps());
    assertEquals(bitrate, quality.bitrate());
    for (var second = 4; second <= 6; second++) quality.update(feedback(second, 0, 4), true, second * 1_000_000_000L);
    assertEquals(1920, quality.width());
    for (var second = 7; second <= 40; second++) quality.update(feedback(second, 0, 0), true, second * 1_000_000_000L);
    assertEquals(60, quality.fps());
  }

  @Test void staleFeedbackDoesNotProbeAndOutputStaysBounded() {
    var quality = new AdaptivePovQuality(642, 362, 60, 0);
    var bitrate = quality.bitrate();
    for (var second = 1; second <= 20; second++) quality.update(feedback(1, 0, 0), true, second * 1_000_000_000L);
    assertEquals(bitrate, quality.bitrate());
    for (var second = 21; second <= 80; second++) quality.update(null, false, second * 1_000_000_000L);
    assertEquals(0, quality.width() % 2);
    assertEquals(0, quality.height() % 2);
    assertEquals(15, quality.fps());
    assertTrue(quality.bitrate() >= 300_000);
  }
}
