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

/// Conservative additive probing with multiplicative backoff, evaluated once per second.
/// Owned by the stream's frame loop. Old viewers without feedback do not probe upward.
public final class AdaptivePovBitrate {
  private final long minimum;
  private final long maximum;
  private long bitrate;
  private long lastSample;
  private long sequence;
  private int recoveries;
  private int healthy;

  public AdaptivePovBitrate(int width, int height, long now) {
    minimum = Math.max(300_000L, (long) width * height);
    maximum = Math.max(minimum, Math.min(32_000_000L, (long) width * height * 16));
    bitrate = H264VideoEncoder.initialBitrate(width, height);
    lastSample = now;
  }

  public long bitrate() { return bitrate; }

  public void update(PovStreamFeedback feedback, boolean ready, long now) {
    if (now - lastSample < 1_000_000_000L) return;
    lastSample = now;
    if (!ready) {
      decrease();
      return;
    }
    if (feedback == null || feedback.getReceivedSequence() <= sequence) {
      healthy = 0;
      return;
    }
    var recovery = sequence != 0 && feedback.getDecoderRecoveries() > recoveries;
    sequence = feedback.getReceivedSequence();
    recoveries = feedback.getDecoderRecoveries();
    if (feedback.getDeliveryDelayMs() > 150 || feedback.getDecoderQueueSize() >= 3 || recovery) {
      decrease();
    } else if (feedback.getDeliveryDelayMs() < 50 && feedback.getDecoderQueueSize() <= 1) {
      if (++healthy >= 3) {
        bitrate = Math.min(maximum, bitrate + Math.max(100_000L, maximum / 20));
        healthy = 0;
      }
    } else {
      healthy = 0;
    }
  }

  private void decrease() {
    bitrate = Math.max(minimum, bitrate * 7 / 10);
    healthy = 0;
  }
}
