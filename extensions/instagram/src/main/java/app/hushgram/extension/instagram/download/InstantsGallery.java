/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import app.hushgram.extension.shared.Logger;

/**
 * The gallery glyph on the Instants camera, and the handoff its picked picture takes.
 *
 * <p>{@link #open} starts the transparent picker activity the patch adds to the manifest.
 * {@link #capture} is the bridge the patch fills: it runs the camera's own captured-picture
 * pipeline with the picked Bitmap, the same call a shutter press makes, so the instant preview
 * opens ready to send. Unpatched it answers false. Everything fails open: a pick that can't be
 * handed over says so through the picker and leaves Instagram as it is.
 */
public final class InstantsGallery {
    private static final String SOURCE = "InstantsGallery";

    private InstantsGallery() {
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
     * Filled in by the patch: hands the picked picture to the camera's captured-picture
     * pipeline, which encodes it and opens the instant preview to send. Answers whether the
     * pipeline took it. The patch writes the body with the viewmodel's own camera state, so it
     * matches a shutter press's call.
     */
    public static boolean capture(Context context, @Nullable Bitmap bitmap) {
        return false;
    }
}
