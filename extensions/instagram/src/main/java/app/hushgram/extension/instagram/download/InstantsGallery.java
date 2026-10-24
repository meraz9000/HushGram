/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The gallery glyph on the Instants camera, and the handoff its picked picture takes.
 *
 * <p>{@link #open} starts the transparent picker activity the patch adds to the manifest.
 * {@link #cameraOpened} is called with the camera's viewmodel as its screen renders, and
 * {@link #capture} hands the picked picture to the same pipeline a shutter press uses: the
 * patch fills {@link #publish} with the camera viewmodel's own capture call, the picture
 * entered where the camera's frame would be, and Instagram encodes it and opens the instant
 * preview to send. Unpatched everything answers false and touches nothing.
 */
public final class InstantsGallery {
    private static final String SOURCE = "InstantsGallery";

    /** The camera's viewmodel while its screen is on, or null. */
    private static volatile WeakReference<Object> cameraRef = new WeakReference<>(null);
    private static volatile boolean loggedMissingCamera;
    /** The floating glyph and its hide timer. */
    private static volatile PopupWindow glyph;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = InstantsGallery::hideGlyph;
    private static final long GLYPH_LIFE_MS = 6000;

    private InstantsGallery() {
    }

    /** Called with the camera's viewmodel as its screen renders. Also keeps the glyph alive. */
    public static void cameraOpened(Object viewModel) {
        if (viewModel == null
            || !"com.instagram.quicksnap.camera.domain.QuickSnapCameraViewModel"
                .equals(viewModel.getClass().getName())) {
            return;
        }
        Object previous = cameraRef.get();
        cameraRef = new WeakReference<>(viewModel);
        if (previous != viewModel) {
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "camera viewmodel: " + viewModel.getClass().getName()
                    + "@" + Integer.toHexString(System.identityHashCode(viewModel)));
        }
        try {
            if (!(Utils.settingsReady() && Settings.GALLERY_INSTANTS.get())) {
                return;
            }
            MAIN.post(InstantsGallery::showGlyph);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.GALLERY_INSTANTS, "glyph", t);
        }
    }

    /** The floating gallery glyph over the camera, refreshed by every render, gone once they stop. */
    private static void showGlyph() {
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!decor.isAttachedToWindow() || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            return;
        }
        PopupWindow showing = glyph;
        if (showing != null && showing.isShowing() && showing.getContentView() != null &&
            showing.getContentView().isAttachedToWindow()) {
            MAIN.removeCallbacks(HIDE);
            MAIN.postDelayed(HIDE, GLYPH_LIFE_MS);
            return;
        }
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new Glyph(activity));
        icon.setOnClickListener(v -> {
            try {
                open(v.getContext());
                HookStatus.counted(FamilyNames.GALLERY_INSTANTS, "gallery opened");
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.GALLERY_INSTANTS, "gallery", t);
            }
        });
        int size = Math.round(44 * activity.getResources().getDisplayMetrics().density);
        PopupWindow popup = new PopupWindow(icon, size, size);
        popup.setBackgroundDrawable(null);
        popup.setFocusable(false);
        popup.setOutsideTouchable(false);
        int x = Math.round(decor.getWidth() * 0.40f);
        int y = Math.round(decor.getHeight() * 0.042f);
        popup.showAtLocation(decor, Gravity.TOP | Gravity.START, x, y);
        glyph = popup;
        MAIN.removeCallbacks(HIDE);
        MAIN.postDelayed(HIDE, GLYPH_LIFE_MS);
        HookStatus.counted(FamilyNames.GALLERY_INSTANTS, "glyph shown");
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
            "gallery glyph at " + x + "," + y + " in " + decor.getWidth() + "x" + decor.getHeight());
    }

    private static void hideGlyph() {
        try {
            PopupWindow showing = glyph;
            glyph = null;
            if (showing != null && showing.isShowing()) {
                showing.dismiss();
            }
        } catch (Throwable ignored) {
            // A window already gone needs nothing.
        }
    }

    /** Opens the system photo picker over the app, if the app has one. */
    public static void open(Context context) {
        try {
            Intent intent = new Intent(context, GalleryPickActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not start the gallery picker", t);
        }
    }

    /**
     * Called with the picture the picker read. Hands it to the camera's pipeline through
     * {@link #publish}, answering whether that was possible. Never throws.
     */
    public static boolean capture(Context context, @Nullable Bitmap bitmap) {
        try {
            Object viewModel = cameraRef.get();
            if (viewModel == null) {
                if (!loggedMissingCamera) {
                    loggedMissingCamera = true;
                    Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                        SOURCE, () -> "no camera on screen, so the pick was not handed over");
                }
                return false;
            }
            if (bitmap == null) {
                return false;
            }
            return publish(
                context, bitmap, null, viewModel, null, null, null,
                "", "", InstantsContinuation.create(continuationInterface()), null, null, 0L, false);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not hand the picked picture to the camera", t);
            return false;
        }
    }

    /** Filled in by the patch: the dex name of the continuation interface the capture call takes. */
    public static String continuationInterface() {
        return null;
    }

    /**
     * Filled in by the patch: runs the camera viewmodel's own captured-picture call with
     * [bitmap] in place of the camera frame, the camera state derived inside that call, and
     * the pieces only a real shutter press has passed empty. Its parameters mirror that
     * call's, so the patch's body is a straight pass-through. Unpatched it answers false.
     */
    public static boolean publish(
        Context context, Bitmap bitmap, @Nullable Object options, @Nullable Object viewModel,
        @Nullable Object stateSource, @Nullable Object effects, @Nullable Long stamp,
        String first, String second, @Nullable Object continuation,
        @Nullable Object frameInfo, @Nullable Object callback, long elapsed, boolean recorded
    ) {
        return false;
    }

    /** The gallery glyph: a picture frame with a mountain, the way pickers read. */
    private static final class Glyph extends BitmapDrawable {
        private Glyph(Context context) {
            super(context.getResources(), Bitmap.createBitmap(dp(context, 24), dp(context, 24), Bitmap.Config.ARGB_8888));
            Canvas canvas = new Canvas(getBitmap());
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 2f));
            canvas.drawRoundRect(dp(context, 4f), dp(context, 5f), dp(context, 20f), dp(context, 19f), dp(context, 2.5f), dp(context, 2.5f), paint);
            Path mountains = new Path();
            mountains.moveTo(dp(context, 6.5f), dp(context, 16.5f));
            mountains.lineTo(dp(context, 10.5f), dp(context, 11.5f));
            mountains.lineTo(dp(context, 13.5f), dp(context, 14.5f));
            mountains.lineTo(dp(context, 15.5f), dp(context, 12.5f));
            mountains.lineTo(dp(context, 18f), dp(context, 16.5f));
            canvas.drawPath(mountains, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(dp(context, 15.5f), dp(context, 9f), dp(context, 1.2f), paint);
        }

        private static int dp(Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        private static float dpFloat(Context context, float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }
    }
}
