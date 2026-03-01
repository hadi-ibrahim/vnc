package com.vnc.service;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public class H264EncoderService {

    private static final Logger log = LoggerFactory.getLogger(H264EncoderService.class);

    private AVCodecContext codecCtx;
    private SwsContext swsCtx;
    private AVFrame rgbFrame;
    private AVFrame yuvFrame;
    private AVPacket packet;
    private long startTime;
    private boolean lastFrameWasKeyframe;
    private byte[] codecConfig;

    public synchronized void start(int width, int height, int fps) {
        AVCodec codec = avcodec_find_encoder_by_name("libx264");
        boolean isLibx264 = codec != null && !codec.isNull();

        if (!isLibx264) {
            codec = avcodec_find_encoder_by_name("libopenh264");
        }
        if (codec == null || codec.isNull()) {
            codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        }
        if (codec == null || codec.isNull()) {
            throw new IllegalStateException("No H.264 encoder found");
        }

        String codecName = codec.name().getString();
        log.info("Using H.264 encoder: {}", codecName);

        codecCtx = avcodec_alloc_context3(codec);
        codecCtx.width(width);
        codecCtx.height(height);
        codecCtx.time_base(av_make_q(1, fps));
        codecCtx.framerate(av_make_q(fps, 1));
        codecCtx.pix_fmt(AV_PIX_FMT_YUV420P);
        codecCtx.gop_size(fps * 2);
        codecCtx.max_b_frames(0);
        codecCtx.flags(codecCtx.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);

        AVDictionary opts = new AVDictionary(null);

        if (isLibx264) {
            av_dict_set(opts, "preset", "ultrafast", 0);
            av_dict_set(opts, "tune", "zerolatency", 0);
            av_dict_set(opts, "crf", "28", 0);
            av_dict_set(opts, "profile", "baseline", 0);
        } else {
            codecCtx.profile(66);
            codecCtx.bit_rate(400_000L);
            av_dict_set(opts, "allow_skip_frames", "1", 0);
        }

        int ret = avcodec_open2(codecCtx, codec, opts);
        av_dict_free(opts);
        if (ret < 0) {
            throw new IllegalStateException("Failed to open H.264 encoder: " + ret);
        }

        if (codecCtx.extradata_size() > 0) {
            byte[] raw = new byte[codecCtx.extradata_size()];
            codecCtx.extradata().get(raw);
            codecConfig = ensureAvcc(raw, width, height);
        }

        swsCtx = sws_getContext(
                width, height, AV_PIX_FMT_BGRA,
                width, height, AV_PIX_FMT_YUV420P,
                SWS_BILINEAR, null, null, (double[]) null);

        rgbFrame = av_frame_alloc();
        rgbFrame.format(AV_PIX_FMT_BGRA);
        rgbFrame.width(width);
        rgbFrame.height(height);
        av_frame_get_buffer(rgbFrame, 32);

        yuvFrame = av_frame_alloc();
        yuvFrame.format(AV_PIX_FMT_YUV420P);
        yuvFrame.width(width);
        yuvFrame.height(height);
        av_frame_get_buffer(yuvFrame, 32);

        packet = av_packet_alloc();
        startTime = System.currentTimeMillis();

        log.info("H.264 encoder started – {}x{} @ {} FPS, extradata {} bytes",
                width, height, fps, codecConfig != null ? codecConfig.length : 0);
    }

    public synchronized byte[] encode(BufferedImage image) {
        if (codecCtx == null) return null;

        int w = image.getWidth();
        int h = image.getHeight();

        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        ByteBuffer buf = rgbFrame.data(0).capacity((long) w * h * 4).asByteBuffer();
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            buf.put(i * 4,     (byte) (px & 0xFF));
            buf.put(i * 4 + 1, (byte) ((px >> 8) & 0xFF));
            buf.put(i * 4 + 2, (byte) ((px >> 16) & 0xFF));
            buf.put(i * 4 + 3, (byte) 0xFF);
        }

        sws_scale(swsCtx,
                rgbFrame.data(), rgbFrame.linesize(), 0, h,
                yuvFrame.data(), yuvFrame.linesize());

        yuvFrame.pts((System.currentTimeMillis() - startTime) * 90);

        int ret = avcodec_send_frame(codecCtx, yuvFrame);
        if (ret < 0) {
            log.warn("avcodec_send_frame failed: {}", ret);
            return null;
        }

        ret = avcodec_receive_packet(codecCtx, packet);
        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF) {
            return null;
        }
        if (ret < 0) {
            log.warn("avcodec_receive_packet failed: {}", ret);
            return null;
        }

        lastFrameWasKeyframe = (packet.flags() & AV_PKT_FLAG_KEY) != 0;
        byte[] raw = new byte[packet.size()];
        packet.data().get(raw);
        av_packet_unref(packet);

        return annexBToAvccPacket(raw);
    }

    public boolean isLastFrameKeyframe() {
        return lastFrameWasKeyframe;
    }

    public byte[] getCodecConfig() {
        return codecConfig;
    }

    public long getTimestamp() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Converts Annex B NAL units (start-code-prefixed) to AVCC format
     * (4-byte length-prefixed) for WebCodecs compatibility.
     */
    private byte[] annexBToAvccPacket(byte[] annexB) {
        List<byte[]> nals = parseAnnexBNals(annexB);
        if (nals.isEmpty()) return annexB;

        var out = new ByteArrayOutputStream(annexB.length);
        for (byte[] nal : nals) {
            int nalType = nal[0] & 0x1F;
            if (nalType == 7 || nalType == 8) continue; // skip SPS/PPS in stream data
            out.write((nal.length >> 24) & 0xFF);
            out.write((nal.length >> 16) & 0xFF);
            out.write((nal.length >> 8) & 0xFF);
            out.write(nal.length & 0xFF);
            out.write(nal, 0, nal.length);
        }
        return out.toByteArray();
    }

    /**
     * Converts Annex B formatted SPS+PPS to AVCDecoderConfigurationRecord if needed.
     * WebCodecs requires AVCC format for the description parameter.
     */
    private byte[] ensureAvcc(byte[] extradata, int width, int height) {
        if (extradata.length > 0 && extradata[0] == 0x01) {
            log.info("Extradata already in AVCC format ({} bytes)", extradata.length);
            return extradata;
        }

        List<byte[]> nalUnits = parseAnnexBNals(extradata);
        byte[] sps = null;
        byte[] pps = null;

        for (byte[] nal : nalUnits) {
            if (nal.length == 0) continue;
            int nalType = nal[0] & 0x1F;
            if (nalType == 7) sps = nal;
            else if (nalType == 8) pps = nal;
        }

        if (sps == null || pps == null) {
            log.warn("Could not find SPS/PPS in extradata, using raw bytes");
            return extradata;
        }

        byte profileIdc = sps.length > 1 ? sps[1] : 0x42;
        byte constraints = sps.length > 2 ? sps[2] : 0x00;
        byte levelIdc = sps.length > 3 ? sps[3] : 0x1E;

        var out = new ByteArrayOutputStream();
        out.write(0x01);            // version
        out.write(profileIdc);      // profile
        out.write(constraints);     // constraint flags
        out.write(levelIdc);        // level
        out.write(0xFF);            // 4-byte NAL length (0xFC | 3)
        out.write(0xE1);            // 1 SPS (0xE0 | 1)
        out.write((sps.length >> 8) & 0xFF);
        out.write(sps.length & 0xFF);
        out.write(sps, 0, sps.length);
        out.write(0x01);            // 1 PPS
        out.write((pps.length >> 8) & 0xFF);
        out.write(pps.length & 0xFF);
        out.write(pps, 0, pps.length);

        byte[] avcc = out.toByteArray();
        log.info("Converted Annex B to AVCC: {} -> {} bytes, profile=0x{}, level=0x{}",
                extradata.length, avcc.length,
                String.format("%02X", profileIdc), String.format("%02X", levelIdc));
        return avcc;
    }

    private List<byte[]> parseAnnexBNals(byte[] data) {
        List<byte[]> nals = new ArrayList<>();
        int i = 0;
        while (i < data.length) {
            // skip to start code
            if (i + 2 < data.length && data[i] == 0 && data[i + 1] == 0) {
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    i += 4;
                } else if (data[i + 2] == 1) {
                    i += 3;
                } else {
                    i++;
                    continue;
                }
                int start = i;
                while (i < data.length) {
                    if (i + 2 < data.length && data[i] == 0 && data[i + 1] == 0
                            && (data[i + 2] == 1 || (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1))) {
                        break;
                    }
                    i++;
                }
                byte[] nal = new byte[i - start];
                System.arraycopy(data, start, nal, 0, nal.length);
                nals.add(nal);
            } else {
                i++;
            }
        }
        return nals;
    }

    public synchronized void stop() {
        if (codecCtx != null) {
            avcodec_send_frame(codecCtx, null);
            while (avcodec_receive_packet(codecCtx, packet) == 0) {
                av_packet_unref(packet);
            }
            avcodec_free_context(codecCtx);
            codecCtx = null;
        }
        if (swsCtx != null) {
            sws_freeContext(swsCtx);
            swsCtx = null;
        }
        if (rgbFrame != null) {
            av_frame_free(rgbFrame);
            rgbFrame = null;
        }
        if (yuvFrame != null) {
            av_frame_free(yuvFrame);
            yuvFrame = null;
        }
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        log.info("H.264 encoder stopped");
    }
}
