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

/// Bounded quality adaptation. Congestion drops bitrate first; sustained overload
/// reduces pixel rate. Recovery probes slowly to avoid repeated encoder resets.
public final class AdaptivePovQuality {
  private final int width;
  private final int height;
  private final int maximumFps;
  private long bitrate;
  private long lastSample;
  private long sequence;
  private int recoveries;
  private int healthy;
  private int overloaded;
  private int level;
  private double frameCostMs;

  public AdaptivePovQuality(int width, int height, int maximumFps, long now) {
    this.width = width;
    this.height = height;
    this.maximumFps = maximumFps;
    bitrate = Math.min(maximum(), PovVideoEncoder.initialBitrate(width, height));
    lastSample = now;
  }

  public int width() { return Math.max(2, (int) (width * scale()) & ~1); }
  public int height() { return Math.max(2, (int) (height * scale()) & ~1); }
  public int fps() { return Math.max(15, maximumFps / (level >= 3 ? 4 : level >= 1 ? 2 : 1)); }
  private double scale() { return level >= 4 ? 0.5 : level >= 2 ? 0.75 : 1; }
  private long minimum() { return Math.max(300_000L, (long) width() * height()); }
  private long maximum() { return Math.max(minimum(), Math.min(80_000_000L, (long) width() * height() * 16 * fps() / 60)); }
  public long bitrate() { return bitrate; }
  public void recordFrame(long nanos) {
    var milliseconds = nanos / 1_000_000.0;
    frameCostMs = frameCostMs == 0 ? milliseconds : frameCostMs * 0.9 + milliseconds * 0.1;
  }

  public void update(PovStreamFeedback feedback, boolean ready, long now) {
    if (now - lastSample < 1_000_000_000L) return;
    lastSample = now;
    var fresh = feedback != null && feedback.getReceivedSequence() > sequence;
    var recovery = fresh && sequence != 0 && feedback.getDecoderRecoveries() > recoveries;
    if (fresh) {
      sequence = feedback.getReceivedSequence();
      recoveries = feedback.getDecoderRecoveries();
    }
    var networkBad = !ready || fresh && feedback.getDeliveryDelayMs() > 100;
    var decoderBad = fresh && (feedback.getDecoderQueueSize() >= 3 || recovery);
    var rendererBad = frameCostMs > 1000.0 / fps() * 1.15;
    if (networkBad || decoderBad || rendererBad) {
      healthy = 0;
      if (networkBad) bitrate = Math.max(minimum(), bitrate * 7 / 10);
      if (++overloaded >= 3 && level < 4) {
        level++;
        bitrate = Math.clamp(bitrate, minimum(), maximum());
        overloaded = 0;
      }
    } else if (fresh && feedback.getDeliveryDelayMs() < 30 && feedback.getDecoderQueueSize() <= 1) {
      overloaded = 0;
      healthy++;
      if (healthy % 3 == 0) bitrate = Math.min(maximum(), bitrate + Math.max(100_000L, maximum() / 20));
      // Render time must fit the next FPS tier too, otherwise recovery would oscillate.
      if (healthy >= 15 && level > 0 && frameCostMs < 1000.0 / maximumFps * 0.8) {
        level--;
        healthy = 0;
        bitrate = Math.clamp(bitrate, minimum(), maximum());
      }
    } else {
      healthy = 0;
      overloaded = 0;
    }
  }
}
