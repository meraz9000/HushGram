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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
 * {@link #captureInvoke} records the camera's own capture callback and its arguments on a
 * normal shutter press. {@link #capture} then runs that same callback with the picked picture
 * in place of the camera's frame, so Instagram builds the options and state itself and opens
 * the instant preview to send. Unpatched everything answers false and touches nothing.
 */
public final class InstantsGallery {
    private static final String SOURCE = "InstantsGallery";

    /** The camera's viewmodel while its screen is on, or null. */
    private static volatile WeakReference<Object> cameraRef = new WeakReference<>(null);
    /** The last real capture call's arguments, in the capture call's own order, or null. */
    private static volatile Object[] lastCapture;
    /** True while the extension itself is calling the capture, so its own call isn't recorded. */
    private static volatile boolean synthetic;
    /** The last real capture callback's instance and arguments, or null. */
    private static volatile Object[] lastInvoke;
    /** The last real recorded-video callback's instance and file, or null. */
    private static volatile Object[] lastVideoInvoke;
    /** True while the extension itself is replaying the video callback. */
    private static volatile boolean syntheticVideo;

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
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                    "glyph off: settingsReady " + Utils.settingsReady() + ", switch "
                        + (Utils.settingsReady() ? Settings.GALLERY_INSTANTS.get() : null));
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
        int size = Math.round(44 * activity.getResources().getDisplayMetrics().density);
        int gap = Math.round(8 * activity.getResources().getDisplayMetrics().density);

        ImageView photo = new ImageView(activity);
        photo.setImageDrawable(new Glyph(activity));
        photo.setOnClickListener(v -> openGallery(v.getContext(), false));

        ImageView video = new ImageView(activity);
        video.setImageDrawable(new VideoGlyph(activity));
        video.setOnClickListener(v -> openGallery(v.getContext(), true));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(photo, new LinearLayout.LayoutParams(size, size));
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(size, size);
        videoParams.setMargins(gap, 0, 0, 0);
        row.addView(video, videoParams);

        PopupWindow popup = new PopupWindow(row, size * 2 + gap, size);
        popup.setBackgroundDrawable(null);
        popup.setFocusable(false);
        popup.setOutsideTouchable(false);
        int x = Math.round(decor.getWidth() * 0.11f);
        int y = Math.round(decor.getHeight() * 0.042f);
        popup.showAtLocation(decor, Gravity.TOP | Gravity.START, x, y);
        glyph = popup;
        MAIN.removeCallbacks(HIDE);
        MAIN.postDelayed(HIDE, GLYPH_LIFE_MS);
        HookStatus.counted(FamilyNames.GALLERY_INSTANTS, "glyph shown");
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
            "gallery glyphs at " + x + "," + y + " in " + decor.getWidth() + "x" + decor.getHeight());
    }

    /** Opens the photo or video picker, counting the tap for diagnostics. */
    private static void openGallery(Context context, boolean video) {
        try {
            open(context, video);
            HookStatus.counted(FamilyNames.GALLERY_INSTANTS, video ? "video opened" : "gallery opened");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.GALLERY_INSTANTS, "gallery", t);
        }
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
        open(context, false);
    }

    /** Opens the picker for photos or videos. */
    private static void open(Context context, boolean video) {
        try {
            Intent intent = new Intent(context, GalleryPickActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(GalleryPickActivity.EXTRA_VIDEO, video);
            context.startActivity(intent);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not start the gallery picker", t);
        }
    }

    /**
     * Called at the real capture call's entry with the call's own arguments, in its order: the
     * context, the camera's frame, its options, the viewmodel, the state source, the effects
     * config, a stamp, two strings, the continuation, a frame holder, a callback, a long and a
     * flag. The last set is kept so a picked picture can replay the call with only its frame
     * and continuation replaced — everything else stays the camera's own live objects.
     */
    public static void captureArgs(
        Context context, Bitmap frame, @Nullable Object options, @Nullable Object viewModel,
        @Nullable Object stateSource, @Nullable Object effects, @Nullable Long stamp,
        String first, String second, @Nullable Object continuation,
        @Nullable Object frameInfo, @Nullable Object callback, long elapsed, boolean recorded
    ) {
        try {
            if (synthetic) {
                return;
            }
            lastCapture = new Object[] {
                context, frame, options, viewModel, stateSource, effects, stamp,
                first, second, continuation, frameInfo, callback, elapsed, recorded,
            };
        } catch (Throwable t) {
            Logger.printException(() -> "Could not keep the capture call's arguments", t);
        }
    }

    /** The kept capture call's arguments, tests included, or null. */
    @Nullable
    static Object[] captured() {
        return lastCapture;
    }

    /**
     * Called at the real capture callback's entry with the callback instance and its eight
     * arguments: the picture, the overlay text, the config, a stamp, the state source, a flag,
     * the effect, and the extra value. Kept so a picked picture can run the same callback, the
     * way the camera's own shutter does, with only the picture and text replaced.
     */
    public static void captureInvoke(
        Object callback, @Nullable Object bitmap, @Nullable Object text,
        @Nullable Object config, @Nullable Object stamp, @Nullable Object stateSource,
        @Nullable Object flag, @Nullable Object effect, @Nullable Object extra
    ) {
        try {
            if (synthetic) {
                return;
            }
            lastInvoke = new Object[] {
                callback, bitmap, text, config, stamp, stateSource, flag, effect, extra,
            };
        } catch (Throwable t) {
            Logger.printException(() -> "Could not keep the capture callback's arguments", t);
        }
    }

    /** Whether a normal Instants capture has been recorded, so a gallery picture can replay it. */
    public static boolean hasCapture() {
        Object[] callback = lastInvoke;
        return callback != null && callback.length == 9 && callback[0] != null;
    }

    /**
     * Called at the camera's recorded-video callback entry with the callback and the finished
     * file. The callback captures the live camera context, viewmodel, and recording controllers;
     * replaying it with a picked file runs Instagram's own compose and upload pipeline.
     */
    public static void videoInvoke(Object callback, @Nullable Object file) {
        try {
            if (syntheticVideo || callback == null || !(file instanceof File)) {
                return;
            }
            Object context = field(callback, "A01");
            Object viewModel = field(callback, "A00");
            if (!(context instanceof Context) || viewModel == null ||
                !"com.instagram.quicksnap.camera.domain.QuickSnapCameraViewModel"
                    .equals(viewModel.getClass().getName())) {
                return;
            }
            lastVideoInvoke = new Object[] {callback, file};
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "kept the recorded-video callback for a gallery video");
        } catch (Throwable t) {
            Logger.printException(() -> "Could not keep the video callback", t);
        }
    }

    /** Whether a normal Instants video has been recorded, so a gallery video can replay it. */
    public static boolean hasVideoCapture() {
        Object[] callback = lastVideoInvoke;
        return callback != null && callback.length == 2 && callback[0] != null;
    }

    /**
     * Copies [picked] into the app cache and runs the kept video callback with it. The callback
     * composes and uploads the file through Instagram's own path, exactly as a recorded video.
     */
    public static boolean captureVideo(Context context, @Nullable Uri picked) {
        Object[] callback = lastVideoInvoke;
        if (context == null || picked == null || callback == null
            || callback.length != 2 || callback[0] == null) {
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "no camera video recorded yet; record one normal Instants video first");
            return false;
        }
        File temp = null;
        boolean started = false;
        try {
            temp = File.createTempFile("hushgram-instant-", ".mp4", context.getCacheDir());
            try (InputStream input = context.getContentResolver().openInputStream(picked);
                 OutputStream output = new FileOutputStream(temp)) {
                if (input == null) {
                    return false;
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
            if (temp.length() == 0) {
                return false;
            }
            started = replayVideoInvoke(callback, temp);
            return started;
        } catch (Throwable t) {
            Logger.printException(() -> "Could not hand the picked video to the camera", t);
            return false;
        } finally {
            if (!started && temp != null) {
                // The real callback reads the file asynchronously, so a started upload is not
                // deleted; a failed one leaves nothing useful behind.
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /** Runs the kept video callback with [file], answering whether it was started. */
    private static boolean replayVideoInvoke(Object[] callback, File file) {
        try {
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "replaying the recorded-video callback with the picked video");
            syntheticVideo = true;
            try {
                java.lang.reflect.Method invoke = callback[0].getClass().getMethod(
                    "invoke", Object.class);
                invoke.invoke(callback[0], file);
            } finally {
                syntheticVideo = false;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "the recorded-video callback took the picked video");
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "Could not replay the recorded-video callback", t);
            return false;
        }
    }

    /** The field [name] of [owner] or a superclass, or null. */
    @Nullable
    private static Object field(Object owner, String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** Runs the kept callback with [bitmap], answering whether it was taken. */
    private static boolean replayInvoke(Object[] callback, Bitmap bitmap) {
        try {
            Object[] args = new Object[8];
            args[0] = bitmap;
            args[1] = "";
            System.arraycopy(callback, 3, args, 2, 6);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "replaying the capture callback with the picked frame");
            synthetic = true;
            try {
                java.lang.reflect.Method invoke = callback[0].getClass().getMethod(
                    "invoke",
                    Object.class, Object.class, Object.class, Object.class,
                    Object.class, Object.class, Object.class, Object.class);
                invoke.invoke(callback[0], args);
            } finally {
                synthetic = false;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "the capture callback took the picked frame");
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "Could not replay the capture callback", t);
            return false;
        }
    }

    /**
     * Called with the picture the picker read. Hands it to the camera's pipeline through
     * {@link #publish}, answering whether that was possible. Never throws.
     */
    public static boolean capture(Context context, @Nullable Bitmap bitmap) {
        try {
            if (bitmap == null) {
                return false;
            }
            // The camera's own capture callback is the path the shutter uses: it builds fresh
            // options from the live viewmodel, saves and overlays the picture, updates the
            // state, and launches the capture with arguments that are all still valid. Running
            // it with the picked picture is the gallery equivalent of a shutter press. It is
            // recorded on the camera's first normal capture, so the button needs one photo.
            Object[] callback = lastInvoke;
            if (callback == null || callback.length != 9 || callback[0] == null) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "no camera capture recorded yet; take one normal Instants photo first");
                return false;
            }
            return replayInvoke(callback, bitmap);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not hand the picked picture to the camera", t);
            return false;
        }
    }

    /**
     * The legacy low-level hand-off, kept for the fixture tests and for a future no-seed path.
     * It is not called by {@link #capture}: every argument A04 reads must come from a live
     * camera session, and a synthesized call reaches native encoder code that crashes.
     */
    private static boolean legacyCapture(
        Context context, Bitmap bitmap, Object viewModel, Object[] real
    ) {
        try {
            // The real capture's continuation carries the camera coroutine's context, which the
            // state machine Instagram builds around ours reads in its constructor.
            Object continuation = InstantsContinuation.create(
                continuationInterface(), real == null || real.length != 14 ? null : real[9]);
            if (continuation == null) {
                return false;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reading the camera state");
            Object state = stateOf(viewModel);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "state " + (state == null ? "null" : "read"));
            if (state == null) {
                return false;
            }
            Object stateSource = stateSourceOf(state);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "state source " + (stateSource == null ? "null" : "read"));
            if (stateSource == null) {
                return false;
            }
            synthetic = true;
            try {
                return publish(
                    context, bitmap, null, viewModel, stateSource, null, null,
                    "", "", continuation, null, null, 0L, false);
            } finally {
                synthetic = false;
            }
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

    /** Filled in by the patch: the camera viewmodel's own state, read by its own accessor. */
    public static Object stateOf(Object viewModel) {
        return null;
    }

    /** Filled in by the patch: the state's own session source, the field A04 reads from it. */
    public static Object stateSourceOf(Object state) {
        return null;
    }

    /**
     * Filled in by the patch: the camera's file-based handoff. Its parameters mirror that
     * call's, so the patch's body is check-casts onto them and one ranged invoke; the pieces
     * only a camera session carries are passed empty.
     */
    public static boolean publishFile(
        Context context, Bitmap bitmap, @Nullable Object effect, @Nullable Object state,
        @Nullable Object viewModel, @Nullable Object source, java.io.File file,
        @Nullable Object zoom, String text, @Nullable Object continuation,
        int width, int height, long elapsed
    ) {
        return false;
    }

    /**
     * Filled in by the patch, as [publish] is: the same capture call, made with the arguments a
     * real shutter press handed over. Answers whether the call went in.
     */
    public static boolean replay(
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

    /** The video half of the gallery glyph: a play mark inside the same frame. */
    private static final class VideoGlyph extends BitmapDrawable {
        private VideoGlyph(Context context) {
            super(context.getResources(), Bitmap.createBitmap(dp(context, 24), dp(context, 24), Bitmap.Config.ARGB_8888));
            Canvas canvas = new Canvas(getBitmap());
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 2f));
            canvas.drawRoundRect(dp(context, 4f), dp(context, 5f), dp(context, 20f), dp(context, 19f), dp(context, 2.5f), dp(context, 2.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            Path play = new Path();
            play.moveTo(dp(context, 10f), dp(context, 8.5f));
            play.lineTo(dp(context, 17f), dp(context, 12f));
            play.lineTo(dp(context, 10f), dp(context, 15.5f));
            play.close();
            canvas.drawPath(play, paint);
        }

        private static int dp(Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
