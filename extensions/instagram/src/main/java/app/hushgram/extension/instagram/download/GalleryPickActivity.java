/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.io.InputStream;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * The photo picker the gallery glyph on the Instants camera opens.
 *
 * <p>A transparent, exportless activity the patch adds to the manifest. It asks Android for a
 * picture — the system photo picker on 13 and newer, the document picker before it, neither of
 * which needs a permission — reads the picked picture into a Bitmap the size the camera hands
 * its own captures at, and gives it to {@link InstantsGallery#capture}, the bridge the patch
 * fills with the camera's own handoff. The screen closes either way; a pick that can't be read
 * says so and changes nothing.
 */
public final class GalleryPickActivity extends Activity {
    private static final String SOURCE = "GalleryPickActivity";
    /** The longest side a picked picture is decoded at, the camera's own stills' ceiling. */
    private static final int MAX_SIDE = 1440;
    private static final int REQUEST = 0x4853; // "HS"

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        try {
            Intent pick;
            if (Build.VERSION.SDK_INT >= 33) {
                pick = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
                pick.setType("image/*");
            } else {
                pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
            }
            startActivityForResult(pick, REQUEST);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not open the photo picker", t);
            failed();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST) {
            finish();
            return;
        }
        try {
            Uri picked = result == RESULT_OK && data != null ? data.getData() : null;
            Bitmap bitmap = picked == null ? null : decode(picked);
            if (bitmap == null) {
                if (result == RESULT_OK) failed();
                return;
            }
            if (!InstantsGallery.capture(this, bitmap)) {
                failed();
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Could not read the picked picture", t);
            failed();
        } finally {
            finish();
        }
    }

    /** The picked picture, decoded no larger than [MAX_SIDE] on its longest side. */
    @Nullable
    private Bitmap decode(Uri picked) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(picked)) {
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not size the picked picture", t);
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = getContentResolver().openInputStream(picked)) {
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not read the picked picture", t);
            return null;
        }
    }

    /** Says a pick couldn't be used, in the phone's language. */
    private void failed() {
        try {
            Utils.showToastShort(L10n.t("That photo couldn't be read"));
        } catch (Throwable ignored) {
            // A toast that can't show changes nothing.
        }
    }
}
