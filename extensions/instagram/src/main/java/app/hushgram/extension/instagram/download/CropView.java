/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The framing view a picture picked for an instant opens in. The frame has the instant camera's
 * portrait shape; the picture is drawn to cover it, and a drag or a pinch moves and scales the
 * picture underneath. Everything outside the frame is dimmed, so the saved picture is exactly
 * what the frame shows.
 */
public final class CropView extends View {
    /** The space between the frame and the screen's sides, the camera's own margin. */
    private static final float MARGIN_DP = 16f;
    /** The camera cover frame's rounded corners. */
    private static final float RADIUS_DP = 22f;
    /** The most the picture can be enlarged past the size that covers the frame. */
    private static final float MAX_ZOOM = 4f;

    private final Matrix matrix = new Matrix();
    private final RectF frame = new RectF();
    private final Paint dim = new Paint();
    private final Paint border = new Paint();
    private final Paint picturePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector pinch;
    private final GestureDetector drag;

    @Nullable
    private Bitmap picture;
    private float scale = 1f;
    private float minScale = 1f;
    private float tx;
    private float ty;

    public CropView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        dim.setColor(0xAA000000);
        border.setColor(Color.WHITE);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(2));
        pinch = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float next = scale * detector.getScaleFactor();
                    if (next < minScale) {
                        next = minScale;
                    } else if (next > minScale * MAX_ZOOM) {
                        next = minScale * MAX_ZOOM;
                    }
                    float factor = next / scale;
                    scale = next;
                    tx = detector.getFocusX() - (detector.getFocusX() - tx) * factor;
                    ty = detector.getFocusY() - (detector.getFocusY() - ty) * factor;
                    clamp();
                    invalidate();
                    return true;
                }
            });
        drag = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onScroll(
                @Nullable MotionEvent first, MotionEvent current, float dx, float dy
            ) {
                if (pinch.isInProgress()) {
                    return false;
                }
                tx -= dx;
                ty -= dy;
                clamp();
                invalidate();
                return true;
            }
        });
    }

    /** The picture to frame, or null to clear it. */
    public void setPicture(@Nullable Bitmap bitmap) {
        picture = bitmap;
        fit();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // The Instants camera's own viewfinder is a square (QuickSnapCoverFrameLayout), inset
        // from the sides and centered over the preview.
        float margin = dp(MARGIN_DP);
        float sidePx = Math.min(width - margin * 2, height - dp(150));
        float left = (width - sidePx) / 2f;
        float top = Math.max(margin, (height - sidePx) / 2f - dp(20));
        frame.set(left, top, left + sidePx, top + sidePx);
        fit();
    }

    /** Sizes the picture so it covers the frame, then centers it. */
    private void fit() {
        if (picture == null || frame.isEmpty()) {
            return;
        }
        minScale = Math.max(
            frame.width() / picture.getWidth(),
            frame.height() / picture.getHeight());
        scale = minScale;
        tx = frame.centerX() - picture.getWidth() * scale / 2f;
        ty = frame.centerY() - picture.getHeight() * scale / 2f;
        clamp();
    }

    /** Keeps the picture covering the frame after a move or a pinch. */
    private void clamp() {
        if (picture == null || frame.isEmpty()) {
            return;
        }
        float width = picture.getWidth() * scale;
        float height = picture.getHeight() * scale;
        if (tx > frame.left) {
            tx = frame.left;
        }
        if (ty > frame.top) {
            ty = frame.top;
        }
        if (tx + width < frame.right) {
            tx = frame.right - width;
        }
        if (ty + height < frame.bottom) {
            ty = frame.bottom - height;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (picture == null || frame.isEmpty()) {
            return;
        }
        matrix.setScale(scale, scale);
        matrix.postTranslate(tx, ty);

        // The camera clips its preview to the rounded square; everything outside stays black.
        canvas.drawColor(Color.BLACK);
        Path clip = new Path();
        float radius = dp(RADIUS_DP);
        clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmap(picture, matrix, picturePaint);
        canvas.restoreToCount(save);
        canvas.drawRoundRect(frame, radius, radius, border);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event);
        drag.onTouchEvent(event);
        return true;
    }

    /**
     * The part of the picture inside the frame, at up to 1440 pixels on its long side, or null
     * when there is no picture yet.
     */
    @Nullable
    public Bitmap crop() {
        if (picture == null || frame.isEmpty()) {
            return null;
        }
        int height = Math.min(1440, Math.round(frame.height()));
        int width = Math.max(1, Math.round(height * frame.width() / frame.height()));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(width / frame.width(), height / frame.height());
        canvas.translate(-frame.left, -frame.top);
        matrix.setScale(scale, scale);
        matrix.postTranslate(tx, ty);
        canvas.drawBitmap(picture, matrix, picturePaint);
        return out;
    }

    /** [value] device-independent pixels in this view's scale. */
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
