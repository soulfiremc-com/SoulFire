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

import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.junit.jupiter.api.Test;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PovVideoEncoderTest {
  @Test
  void highProfileEncodesMappedMemoryWithoutTakingOwnership() {
    var pixels = java.nio.ByteBuffer.allocateDirect(320 * 180 * 4);
    try (var encoder = new PovVideoEncoder(320, 180, 120, PovVideoEncoder.Format.HIGH, "libx264")) {
      for (var i = 0; i < 150; i++) {
        pixels.put(0, (byte) i);
        var encoded = encoder.encode(pixels, i * 8_333L, false);
        assertEquals(i * 8_333L, encoded.timestampUs());
        assertEquals(i == 0 || i == 120, encoded.keyFrame());
        assertEquals(0, pixels.position());
        assertTrue(encoded.data().length > 0);
      }
    }
    pixels.put(0, (byte) 42);
    assertEquals(42, pixels.get(0));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sf.pov.test.hardware", matches = "true")
  void hardwareFormatsReturnEveryFrameWithoutReordering() {
    var pixels = java.nio.ByteBuffer.allocateDirect(1280 * 720 * 4);
    for (var format : new PovVideoEncoder.Format[]{PovVideoEncoder.Format.HIGH, PovVideoEncoder.Format.AV1}) {
      var name = format == PovVideoEncoder.Format.AV1 ? "av1_nvenc" : "h264_nvenc";
      try (var encoder = new PovVideoEncoder(1280, 720, 120, format, name)) {
        for (var i = 0; i < 30; i++) {
          var encoded = encoder.encode(pixels, i * 8_333L, false);
          assertEquals(i * 8_333L, encoded.timestampUs());
          assertTrue(encoded.data().length > 0);
        }
      }
    }
  }

  @Test
  void bitrateChangesProduceImmediateKeyframesWithContinuousTimestamps() {
    try (var encoder = new PovVideoEncoder(320, 180, "libx264")) {
      var pixels = new byte[320 * 180 * 4];
      encoder.encode(pixels, 0, false);
      encoder.bitrate(1_000_000);
      var increased = encoder.encode(pixels, 16_667, false);
      assertTrue(increased.keyFrame());
      assertEquals(16_667, increased.timestampUs());
      assertEquals(1_000_000, encoder.bitrate());
      encoder.bitrate(300_000);
      assertTrue(encoder.encode(pixels, 33_334, false).keyFrame());
      assertEquals(300_000, encoder.bitrate());
    }
  }

  @Test
  void encodesImmediateAccessUnitsWithRecoverableKeyframesAndCorrectOrientation() {
    var width = 320;
    var height = 180;
    var rgba = new byte[width * height * 4];
    // Readback is bottom-up: white at the top, black at the bottom.
    for (var y = height / 2; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var index = (y * width + x) * 4;
        rgba[index] = rgba[index + 1] = rgba[index + 2] = (byte) 255;
      }
    }
    var decoder = avcodec_alloc_context3(avcodec_find_decoder(AV_CODEC_ID_H264));
    var frame = av_frame_alloc();
    var packet = av_packet_alloc();
    try (var encoder = new PovVideoEncoder(width, height, "libx264")) {
      assertEquals(0, avcodec_open2(decoder, avcodec_find_decoder(AV_CODEC_ID_H264), (AVDictionary) null));
      var total = 0;
      for (var index = 0; index < 75; index++) {
        var encoded = encoder.encode(rgba, index * 16_667L, index == 17);
        assertEquals(index * 16_667L, encoded.timestampUs());
        assertEquals(index == 0 || index == 17 || index == 60, encoded.keyFrame());
        assertNotNull(encoded.codec());
        if (index == 17) avcodec_flush_buffers(decoder);
        assertEquals(0, av_new_packet(packet, encoded.data().length));
        packet.data().put(encoded.data());
        assertEquals(0, avcodec_send_packet(decoder, packet));
        av_packet_unref(packet);
        assertEquals(0, avcodec_receive_frame(decoder, frame), "Every access unit must decode without buffering");
        assertEquals(width, frame.width());
        assertEquals(height, frame.height());
        assertTrue((frame.data(0).get(20L * frame.linesize(0) + 20) & 255) > 220);
        assertTrue((frame.data(0).get(160L * frame.linesize(0) + 20) & 255) < 25);
        av_frame_unref(frame);
        total += encoded.data().length;
      }
      assertTrue(total < rgba.length, "Temporal compression must avoid resending independent images");
    } finally {
      av_packet_free(packet);
      av_frame_free(frame);
      avcodec_free_context(decoder);
    }
  }

  @Test
  void rejectsDimensionsThatCannotRepresent420Chroma() {
    assertThrows(IllegalArgumentException.class, () -> new PovVideoEncoder(321, 180, "libx264"));
    assertThrows(IllegalArgumentException.class, () -> new PovVideoEncoder(0, 180, "libx264"));
  }
}
