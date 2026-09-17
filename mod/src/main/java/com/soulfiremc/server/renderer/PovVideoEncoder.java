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

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.avcodec.AVBSFContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import java.nio.ByteBuffer;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_CLOSED_GOP;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PROFILE_AV1_MAIN;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PROFILE_H264_BASELINE;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PROFILE_H264_HIGH;
import static org.bytedeco.ffmpeg.global.avcodec.av_bsf_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_bsf_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_bsf_get_by_name;
import static org.bytedeco.ffmpeg.global.avcodec.av_bsf_init;
import static org.bytedeco.ffmpeg.global.avcodec.av_bsf_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_bsf_send_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_PRI_BT709;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_MPEG;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_TRC_BT709;
import static org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_I;
import static org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_NONE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU709;
import static org.bytedeco.ffmpeg.global.swscale.SWS_FAST_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getCoefficients;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;
import static org.bytedeco.ffmpeg.global.swscale.sws_setColorspaceDetails;

/// One persistent, zero-reordering H.264 encoder. All calls are serialized by the stream owner.
@Slf4j
public final class PovVideoEncoder implements AutoCloseable {
  public static final int FPS = 60;
  private final int fps;
  public int fps() { return fps; }
  private final Format format;
  public enum Format { BASELINE, HIGH, AV1 }
  private final int width;
  private final int height;
  private NativeEncoder encoder;
  private long bitrate;

  public PovVideoEncoder(int width, int height) { this(width, height, FPS); }

  public PovVideoEncoder(int width, int height, int fps) {
    this(width, height, fps, Format.BASELINE, System.getProperty("sf.pov.encoder", "auto"));
  }

  PovVideoEncoder(int width, int height, String requested) { this(width, height, FPS, requested); }

  PovVideoEncoder(int width, int height, int fps, String requested) { this(width, height, fps, Format.BASELINE, requested); }

  public PovVideoEncoder(int width, int height, int fps, Format format) {
    this(width, height, fps, format, System.getProperty("sf.pov.encoder", "auto"));
  }

  PovVideoEncoder(int width, int height, int fps, Format format, String requested) {
    this.fps = fps;
    this.format = format;
    if (width < 2 || height < 2 || width % 2 != 0 || height % 2 != 0 || fps < 15 || fps > 120) {
      throw new IllegalArgumentException("H.264 dimensions must be positive and even");
    }
    this.bitrate = initialBitrate(width, height);
    this.width = width;
    this.height = height;
    var candidates = requested.equals("auto")
      ? (format == Format.AV1 ? List.of("av1_nvenc", "av1_qsv", "h264_nvenc", "h264_qsv", "h264_videotoolbox", "h264_mf", "libx264")
        : List.of("h264_nvenc", "h264_qsv", "h264_videotoolbox", "h264_mf", "libx264")) : List.of(requested);
    for (var name : candidates) {
      try {
        encoder = new NativeEncoder(name, width, height, bitrate, fps, format);
        log.info("POV video encoder: {} ({}x{}, up to {} FPS)", name, width, height, fps);
        return;
      } catch (RuntimeException error) {
        log.debug("POV encoder {} unavailable: {}", name, error.getMessage());
      }
    }
    throw new IllegalStateException("No video encoder could initialize");
  }

  public static long initialBitrate(int width, int height) {
    return Math.max(300_000L, (long) width * height * 4);
  }

  public long bitrate() { return bitrate; }

  public void bitrate(long value) {
    if (value < 300_000 || value > 80_000_000) throw new IllegalArgumentException("Invalid POV bitrate");
    if (value == bitrate) return;
    // Reopening works across all supported hardware encoders, including those
    // without runtime rate-control updates. The replacement starts with an IDR.
    var name = encoder.name;
    // Release the hardware session first so adaptation needs no extra GPU slots.
    encoder.close();
    try {
      encoder = new NativeEncoder(name, width, height, value, fps, format);
    } catch (RuntimeException error) {
      if (name.equals("libx264") || !System.getProperty("sf.pov.encoder", "auto").equals("auto")) throw error;
      log.warn("POV encoder {} could not change bitrate; switching to CPU H.264: {}", name, error.getMessage());
      encoder = new NativeEncoder("libx264", width, height, value, fps, format);
    }
    bitrate = value;
  }

  public String name() { return encoder.name; }
  public int width() { return width; }
  public int height() { return height; }

  public EncodedFrame encode(byte[] rgba, long timestampUs, boolean keyFrame) {
    try {
      return encoder.encode(rgba, timestampUs, keyFrame);
    } catch (RuntimeException error) {
      if (encoder.name.equals("libx264") || !System.getProperty("sf.pov.encoder", "auto").equals("auto")) throw error;
      log.warn("POV hardware encoder {} failed; switching to CPU H.264: {}", encoder.name, error.getMessage());
      encoder.close();
      encoder = new NativeEncoder("libx264", width, height, bitrate, fps, format);
      return encoder.encode(rgba, timestampUs, true);
    }
  }

  public EncodedFrame encode(ByteBuffer rgba, long timestampUs, boolean keyFrame) {
    try {
      return encoder.encode(rgba, timestampUs, keyFrame);
    } catch (RuntimeException error) {
      if (encoder.name.equals("libx264") || !System.getProperty("sf.pov.encoder", "auto").equals("auto")) throw error;
      log.warn("POV hardware encoder {} failed; switching to CPU H.264: {}", encoder.name, error.getMessage());
      encoder.close();
      encoder = new NativeEncoder("libx264", width, height, bitrate, fps, format);
      return encoder.encode(rgba, timestampUs, true);
    }
  }

  @Override public void close() { encoder.close(); }

  public record EncodedFrame(byte[] data, long timestampUs, boolean keyFrame, String codec) {}

  private static final class NativeEncoder implements AutoCloseable {
    private final String name;
    private final int fps;
    private AVCodecContext context;
    private AVFrame frame;
    private AVPacket packet;
    private AVBSFContext filter;
    private SwsContext scaler;
    private BytePointer input;
    private PointerPointer<BytePointer> inputPlanes;
    private IntPointer inputStride;
    private long frames;
    private String codecString;

    private NativeEncoder(String name, int width, int height, long bitrate, int fps, Format format) {
      this.fps = fps;
      this.name = name;
      try {
        var codec = avcodec_find_encoder_by_name(name);
        if (codec == null) throw new IllegalStateException("Encoder not compiled into FFmpeg");
        context = avcodec_alloc_context3(codec);
        if (context == null) throw new IllegalStateException("Cannot allocate encoder");
        var pixelFormat = name.endsWith("_qsv") || name.equals("h264_mf") ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P;
        context.width(width).height(height).pix_fmt(pixelFormat).max_b_frames(0).gop_size(fps)
          .bit_rate(bitrate).rc_max_rate(bitrate).rc_buffer_size((int) bitrate / 2)
          .thread_count(Math.min(4, Runtime.getRuntime().availableProcessors())).thread_type(AVCodecContext.FF_THREAD_SLICE)
          .flags(AV_CODEC_FLAG_LOW_DELAY | AV_CODEC_FLAG_CLOSED_GOP).profile(name.startsWith("av1_") ? AV_PROFILE_AV1_MAIN : format == Format.BASELINE ? AV_PROFILE_H264_BASELINE : AV_PROFILE_H264_HIGH)
          .level(name.startsWith("av1_") ? 13 : width > 1920 || height > 1080 || fps > 60 ? 52 : 42)
          .color_range(AVCOL_RANGE_MPEG).colorspace(AVCOL_SPC_BT709)
          .color_primaries(AVCOL_PRI_BT709).color_trc(AVCOL_TRC_BT709);
        context.time_base().num(1).den(1_000_000);
        context.framerate().num(fps).den(1);
        var options = new AVDictionary((Pointer) null);
        try {
          switch (name) {
            case "libx264" -> {
              option(options, "preset", format == Format.BASELINE ? "ultrafast" : "superfast");
              option(options, "tune", "zerolatency");
              option(options, "forced-idr", "1");
              option(options, "x264-params", "repeat-headers=1:annexb=1:scenecut=0");
            }
            case "h264_nvenc", "av1_nvenc" -> {
              option(options, "preset", "p4"); option(options, "tune", "ull");
              option(options, "zerolatency", "1"); option(options, "delay", "0"); option(options, "forced-idr", "1");
            }
            case "h264_qsv", "av1_qsv" -> { option(options, "async_depth", "1"); option(options, "preset", "veryfast"); }
            case "h264_videotoolbox" -> { option(options, "realtime", "1"); option(options, "allow_sw", "0"); }
            case "h264_mf" -> option(options, "hw_encoding", "1");
            default -> { }
          }
          check(avcodec_open2(context, codec, options), "Opening " + name);
        } finally { av_dict_free(options); }
        frame = av_frame_alloc();
        frame.format(pixelFormat).width(width).height(height).color_range(AVCOL_RANGE_MPEG)
          .colorspace(AVCOL_SPC_BT709).color_primaries(AVCOL_PRI_BT709).color_trc(AVCOL_TRC_BT709);
        check(av_frame_get_buffer(frame, 32), "Allocating video frame");
        packet = av_packet_alloc();
        if (!name.startsWith("av1_")) {
        filter = new AVBSFContext((Pointer) null);
        check(av_bsf_alloc(av_bsf_get_by_name("h264_mp4toannexb"), filter), "Allocating Annex B filter");
        check(avcodec_parameters_from_context(filter.par_in(), context), "Copying encoder parameters");
        filter.time_base_in().num(1).den(1_000_000);
        check(av_bsf_init(filter), "Initializing Annex B filter");
        }
        scaler = sws_getContext(width, height, AV_PIX_FMT_RGBA, width, height, pixelFormat, SWS_FAST_BILINEAR, null, null, (double[]) null);
        if (scaler == null) throw new IllegalStateException("Cannot create color converter");
        var coefficients = sws_getCoefficients(SWS_CS_ITU709);
        check(sws_setColorspaceDetails(scaler, coefficients, 1, coefficients, 0, 0, 1 << 16, 1 << 16), "Configuring video colors");
        inputPlanes = new PointerPointer<>(1);
        inputStride = new IntPointer(1).put(0, -width * 4);
      } catch (RuntimeException | Error error) {
        close();
        throw error;
      }
    }

    private EncodedFrame encode(byte[] rgba, long timestampUs, boolean requestedKeyFrame) {
      if (rgba.length != context.width() * context.height() * 4) throw new IllegalArgumentException("Incorrect RGBA frame size");
      if (input == null) input = new BytePointer((long) context.width() * context.height() * 4);
      input.position(0).put(rgba);
      return encode(input, timestampUs, requestedKeyFrame);
    }

    private EncodedFrame encode(ByteBuffer rgba, long timestampUs, boolean requestedKeyFrame) {
      if (!rgba.isDirect() || rgba.remaining() != context.width() * context.height() * 4) throw new IllegalArgumentException("Invalid mapped RGBA frame");
      try (var pointer = new BytePointer(rgba)) { return encode(pointer, timestampUs, requestedKeyFrame); }
    }

    private EncodedFrame encode(BytePointer rgba, long timestampUs, boolean requestedKeyFrame) {
      check(av_frame_make_writable(frame), "Preparing video frame");
      inputPlanes.put(0, rgba.getPointer((long) context.width() * (context.height() - 1) * 4));
      check(sws_scale(scaler, inputPlanes, inputStride, 0, context.height(), frame.data(), frame.linesize()), "Converting RGBA to YUV");
      var periodicKeyFrame = frames++ % fps == 0;
      frame.pts(timestampUs).pict_type(requestedKeyFrame || periodicKeyFrame ? AV_PICTURE_TYPE_I : AV_PICTURE_TYPE_NONE);
      check(avcodec_send_frame(context, frame), "Submitting video frame");
      // Delayed encoders are unsuitable for this interactive stream; fall back rather than queueing input.
      check(avcodec_receive_packet(context, packet), "Receiving low-latency video frame " + frames);
      if (filter != null) {
        check(av_bsf_send_packet(filter, packet), "Converting H.264 access unit");
        check(av_bsf_receive_packet(filter, packet), "Receiving Annex B access unit");
      }
      try {
        var bytes = new byte[packet.size()];
        packet.data().get(bytes);
        var key = (packet.flags() & AV_PKT_FLAG_KEY) != 0;
        if (key) codecString = name.startsWith("av1_") ? "av01.0.13M.08" : codecFromSps(bytes);
        if (codecString == null) throw new IllegalStateException("H.264 keyframe is missing SPS/PPS");
        return new EncodedFrame(bytes, packet.pts(), key, codecString);
      } finally { av_packet_unref(packet); }
    }

    @Override public void close() {
      if (filter != null && !filter.isNull()) av_bsf_free(filter);
      if (packet != null && !packet.isNull()) av_packet_free(packet);
      if (frame != null && !frame.isNull()) av_frame_free(frame);
      if (context != null && !context.isNull()) avcodec_free_context(context);
      if (scaler != null && !scaler.isNull()) { sws_freeContext(scaler); scaler = null; }
      if (inputPlanes != null) { inputPlanes.close(); inputPlanes = null; }
      if (inputStride != null) { inputStride.close(); inputStride = null; }
      if (input != null) { input.close(); input = null; }
    }
  }

  static String codecFromSps(byte[] data) {
    for (var i = 0; i + 6 < data.length; i++) {
      if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 31) == 7) {
        return "avc1.%02X%02X%02X".formatted(data[i + 4] & 255, data[i + 5] & 255, data[i + 6] & 255);
      }
    }
    throw new IllegalStateException("H.264 keyframe is missing its SPS");
  }

  private static void option(AVDictionary options, String key, String value) {
    check(av_dict_set(options, key, value, 0), "Setting encoder option " + key);
  }

  private static void check(int result, String operation) {
    if (result >= 0) return;
    try (var error = new BytePointer(256)) {
      av_strerror(result, error, 256);
      throw new IllegalStateException(operation + ": " + error.limit(0).getString());
    }
  }
}
