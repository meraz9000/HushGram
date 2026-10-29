/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Cuts a video without re-encoding it. The first video keyframe at or before the requested start
 * becomes the clip's first frame, so the result can begin slightly earlier than the chosen time;
 * the end is the last sample at or before the chosen end. Audio before that first keyframe is
 * dropped to keep the tracks aligned.
 */
public final class VideoTrimmer {
    private static final int BUFFER_BYTES = 4 * 1024 * 1024;

    private VideoTrimmer() {
    }

    /** Trims [source] to [startMs]..[endMs] into [output], answering whether it wrote a clip. */
    public static boolean trim(
        Context context, @Nullable Uri source, long startMs, long endMs, File output
    ) {
        if (context == null || source == null || output == null
            || startMs < 0 || endMs <= startMs + 100) {
            return false;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(context, source, null);
            int videoTrack = -1;
            int audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (videoTrack < 0 && mime.startsWith("video/")) {
                    videoTrack = i;
                } else if (audioTrack < 0 && mime.startsWith("audio/")) {
                    audioTrack = i;
                }
            }
            if (videoTrack < 0) {
                return false;
            }

            muxer = new MediaMuxer(
                output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideo = muxer.addTrack(extractor.getTrackFormat(videoTrack));
            int muxAudio = audioTrack < 0
                ? -1 : muxer.addTrack(extractor.getTrackFormat(audioTrack));

            extractor.selectTrack(videoTrack);
            if (audioTrack >= 0) {
                extractor.selectTrack(audioTrack);
            }
            long startUs = startMs * 1000L;
            long endUs = endMs * 1000L;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long baseUs = -1;
            boolean videoSeen = false;
            boolean wrote = false;

            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) {
                    break;
                }
                long timeUs = extractor.getSampleTime();
                if (timeUs < 0) {
                    break;
                }
                // The seek lands on the video keyframe before the requested start. Everything is
                // timed relative to that frame; leading audio is discarded rather than shifted.
                if (track == videoTrack && !videoSeen) {
                    baseUs = timeUs;
                    videoSeen = true;
                }
                if (baseUs < 0 || timeUs > endUs) {
                    if (timeUs > endUs) {
                        break;
                    }
                    extractor.advance();
                    continue;
                }
                if (timeUs < baseUs) {
                    extractor.advance();
                    continue;
                }
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                buffer.position(0);
                buffer.limit(size);
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, timeUs - baseUs, flags);
                if (track == videoTrack) {
                    muxer.writeSampleData(muxVideo, buffer, info);
                    wrote = true;
                } else if (track == audioTrack) {
                    muxer.writeSampleData(muxAudio, buffer, info);
                    wrote = true;
                }
                extractor.advance();
            }
            return wrote;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Throwable ignored) {
                }
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
