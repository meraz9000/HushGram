/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A video timeline drawn as a strip of frames. The part between the two white handles is kept;
 * the rest is dimmed. Dragging anywhere else on the strip moves the playhead, which the trim
 * screen uses to scrub the preview. The handles cannot cross or get closer than a short minimum.
 */
public final class VideoTrimView extends View {
    /** Called while the person scrubs or moves either end of the kept range. */
    public interface Listener {
        void onSeek(long positionMs);

        void onRangeChanged(long startMs, long endMs);

        /** Called when a handle drag ends, so the preview can play from the start handle. */
        void onHandleReleased(long startMs);
    }

    private static final int THUMBNAILS = 10;
    private static final int NONE = 0;
    private static final int START = 1;
    private static final int END = 2;
    private static final int PLAYHEAD = 3;

    private final Uri source;
    private final long durationMs;
    private final long minimumMs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Bitmap> frames = new ArrayList<>();
    private final int handleWidth = dp(5);
    private final int handleInset = dp(2);
    private final int touchSlop = dp(26);

    private long startMs;
    private long endMs;
    private long playheadMs;
    private int dragging = NONE;
    private boolean loaded;
    private boolean loading;
    @Nullable
    private Listener listener;

    public VideoTrimView(Context context, Uri source, long durationMs) {
        super(context);
        this.source = source;
        this.durationMs = Math.max(1, durationMs);
        this.minimumMs = Math.max(300, durationMs / 100);
        this.endMs = this.durationMs;
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public void setPlayhead(long positionMs) {
        playheadMs = clamp(positionMs, 0, durationMs);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0 && height > 0 && !loaded && !loading) {
            loadFrames(width, height);
        }
    }

    /** Reads a strip of frames off the UI thread; a missing frame just leaves a dark cell. */
    private void loadFrames(int width, int height) {
        loading = true;
        int thumbWidth = Math.max(1, width / THUMBNAILS);
        int thumbHeight = Math.max(1, height);
        new Thread(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            List<Bitmap> found = new ArrayList<>();
            try {
                retriever.setDataSource(getContext(), source);
                for (int i = 0; i < THUMBNAILS; i++) {
                    long timeUs = durationMs * 1000L * i / Math.max(1, THUMBNAILS - 1);
                    Bitmap frame = retriever.getScaledFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        thumbWidth, thumbHeight);
                    if (frame != null) {
                        found.add(frame);
                    }
                }
            } catch (Throwable ignored) {
                // A codec that refuses a frame leaves the remaining cells dark.
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
            post(() -> {
                if (!isAttachedToWindow()) {
                    for (Bitmap frame : found) {
                        frame.recycle();
                    }
                    loading = false;
                    return;
                }
                frames.clear();
                frames.addAll(found);
                loaded = true;
                loading = false;
                invalidate();
            });
        }, "hushgram-trim-thumbnails").start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = handleInset;
        float right = getWidth() - handleInset;
        float top = handleInset;
        float bottom = getHeight() - handleInset;
        if (right <= left || bottom <= top) {
            return;
        }

        // The strip of frames, or a flat plate while the decoder is still working.
        if (frames.isEmpty()) {
            paint.setColor(0xFF2A2A2A);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(6), dp(6), paint);
        } else {
            float cell = (right - left) / frames.size();
            for (int i = 0; i < frames.size(); i++) {
                float cellLeft = left + i * cell;
                float cellRight = i == frames.size() - 1 ? right : cellLeft + cell;
                canvas.drawBitmap(frames.get(i), null,
                    new RectF(cellLeft, top, cellRight, bottom), paint);
            }
        }

        float startX = xFor(startMs, left, right);
        float endX = xFor(endMs, left, right);

        // Dim what will be cut away, then outline what remains.
        paint.setColor(0xB3000000);
        canvas.drawRect(left, top, startX, bottom, paint);
        canvas.drawRect(endX, top, right, bottom, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFFFFFFFF);
        canvas.drawRect(startX, top, endX, bottom, paint);
        paint.setStyle(Paint.Style.FILL);

        // The playhead sits inside the kept range while previewing.
        paint.setColor(0xCCFFFFFF);
        paint.setStrokeWidth(dp(1.5f));
        float playX = xFor(playheadMs, left, right);
        canvas.drawLine(playX, top, playX, bottom, paint);

        drawHandle(canvas, startX, top, bottom);
        drawHandle(canvas, endX, top, bottom);
    }

    private void drawHandle(Canvas canvas, float x, float top, float bottom) {
        paint.setColor(0xFFFFFFFF);
        float half = handleWidth / 2f;
        canvas.drawRoundRect(
            new RectF(x - half, top, x + half, bottom), dp(2), dp(2), paint);
        paint.setColor(0xFF111111);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(x, top + dp(4), x, bottom - dp(4), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float left = handleInset;
        float right = getWidth() - handleInset;
        if (right <= left || durationMs <= 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float startX = xFor(startMs, left, right);
                float endX = xFor(endMs, left, right);
                float toStart = Math.abs(x - startX);
                float toEnd = Math.abs(x - endX);
                if (toStart <= touchSlop || toEnd <= touchSlop) {
                    dragging = toStart <= toEnd ? START : END;
                } else {
                    dragging = PLAYHEAD;
                    playheadMs = timeFor(x, left, right);
                    notifySeek();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging == NONE) {
                    return true;
                }
                long position = timeFor(event.getX(), left, right);
                if (dragging == START) {
                    startMs = clamp(position, 0, endMs - minimumMs);
                    playheadMs = startMs;
                    notifyRange();
                    notifySeek();
                } else if (dragging == END) {
                    endMs = clamp(position, startMs + minimumMs, durationMs);
                    playheadMs = endMs;
                    notifyRange();
                    notifySeek();
                } else {
                    playheadMs = clamp(position, 0, durationMs);
                    notifySeek();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging == START || dragging == END) {
                    notifyHandleReleased();
                }
                dragging = NONE;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = NONE;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void notifySeek() {
        Listener target = listener;
        if (target != null) {
            target.onSeek(playheadMs);
        }
    }

    private void notifyRange() {
        Listener target = listener;
        if (target != null) {
            target.onRangeChanged(startMs, endMs);
        }
    }

    private void notifyHandleReleased() {
        Listener target = listener;
        if (target != null) {
            target.onHandleReleased(startMs);
        }
    }

    private float xFor(long positionMs, float left, float right) {
        return left + (right - left) * (float) clamp(positionMs, 0, durationMs) / durationMs;
    }

    private long timeFor(float x, float left, float right) {
        float fraction = (x - left) / (right - left);
        return clamp(Math.round(durationMs * fraction), 0, durationMs);
    }

    @Override
    protected void onDetachedFromWindow() {
        for (Bitmap frame : frames) {
            frame.recycle();
        }
        frames.clear();
        super.onDetachedFromWindow();
    }

    private static long clamp(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
