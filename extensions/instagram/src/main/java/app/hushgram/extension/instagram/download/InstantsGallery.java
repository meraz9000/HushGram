/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.shared.Logger;

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
    private static volatile WeakReference<Object> camera = new WeakReference<>(null);
    private static volatile boolean loggedMissingCamera;

    private InstantsGallery() {
    }

    /** Called with the camera's viewmodel as its screen renders. */
    public static void cameraOpened(Object viewModel) {
        if (viewModel != null) {
            camera = new WeakReference<>(viewModel);
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
            Object viewModel = camera.get();
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
                "", "", new FireAndForget(), null, null, 0L, false);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not hand the picked picture to the camera", t);
            return false;
        }
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
        String first, String second, kotlin.coroutines.Continuation<?> continuation,
        @Nullable Object frameInfo, @Nullable Object callback, long elapsed, boolean recorded
    ) {
        return false;
    }
}
