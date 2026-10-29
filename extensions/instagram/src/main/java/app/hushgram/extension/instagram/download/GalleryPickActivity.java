/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * The media picker the gallery glyph on the Instants camera opens.
 *
 * <p>A transparent, exportless activity the patch adds to the manifest. It asks Android for a
 * picture or video — the system photo picker on 13 and newer, the document picker before it,
 * neither of which needs a permission. A picture is read into a Bitmap the size the camera
 * hands its own captures at, shown in a {@link CropView} so the person can frame it, and given
 * to {@link InstantsGallery#capture}. A video is copied and given to
 * {@link InstantsGallery#captureVideo}, which runs the camera's own recorded-video callback.
 * Cancel closes without sending; a pick that can't be read says so and changes nothing.
 */
public final class GalleryPickActivity extends Activity {
    /** True when the glyph asked for a video instead of a picture. */
    public static final String EXTRA_VIDEO = "hushgram_video";
    private static final String SOURCE = "GalleryPickActivity";
    /** The longest side a picked picture is decoded at, the camera's own stills' ceiling. */
    private static final int MAX_SIDE = 1440;
    private static final int REQUEST = 0x4853; // "HS"
    private boolean video;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        try {
            video = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
            String type = video ? "video/*" : "image/*";
            Intent pick;
            if (Build.VERSION.SDK_INT >= 33) {
                pick = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
                pick.setType(type);
            } else {
                pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType(type);
            }
            Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "picker launched: " + pick.getAction() + " on sdk " + Build.VERSION.SDK_INT);
            startActivityForResult(pick, REQUEST);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not open the photo picker", t);
            failed();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
            SOURCE, () -> "result: request " + request + ", code " + result + ", data " + (data == null ? "null" : String.valueOf(data.getData())));
        if (request != REQUEST) {
            finish();
            return;
        }
        try {
            Uri picked = result == RESULT_OK && data != null ? data.getData() : null;
            String mime = picked == null ? null : getContentResolver().getType(picked);
            Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "picked: " + picked + " type " + mime);
            if (video && picked != null && (mime == null || !mime.startsWith("video/"))) {
                failedVideo();
                finish();
                return;
            }
            if (picked != null && mime != null && mime.startsWith("video/")) {
                if (!InstantsGallery.hasVideoCapture()) {
                    Utils.showToastLong(L10n.t("Take one normal Instants video first"));
                    finish();
                    return;
                }
                boolean taken = InstantsGallery.captureVideo(this, picked);
                Logger.diagnosticInfo(
                    app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                    SOURCE, () -> "video handoff " + (taken ? "went in" : "refused"));
                if (!taken) {
                    failedVideo();
                }
                finish();
                return;
            }
            if (video) {
                finish();
                return;
            }
            Bitmap bitmap = picked == null ? null : decode(picked);
            Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "decoded: " + (bitmap == null ? "null" : bitmap.getWidth() + "x" + bitmap.getHeight()));
            if (bitmap == null) {
                if (result == RESULT_OK) failed();
                finish();
                return;
            }
            if (!InstantsGallery.hasCapture()) {
                Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                    SOURCE, () -> "photo seed missing after decode");
                Utils.showToastLong(L10n.t("Take one normal Instants photo first"));
                finish();
                return;
            }
            Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "photo seed present after decode; framing");
            showFraming(bitmap);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not read the picked media", t);
            failed();
            finish();
        }
    }

    /** Shows the picked picture in the framing view, with Cancel and Save over it. */
    private void showFraming(Bitmap bitmap) {
        CropView crop = new CropView(this);
        crop.setPicture(bitmap);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(crop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(12), dp(12), dp(48));
        bar.setBackgroundColor(0xF2000000);
        TextView cancel = button(L10n.t("Cancel"));
        cancel.setOnClickListener(v -> finish());
        TextView save = button(L10n.t("Save"));
        save.setOnClickListener(v -> {
            Bitmap framed = crop.crop();
            if (framed == null) {
                failed();
                finish();
                return;
            }
            boolean taken = InstantsGallery.capture(this, framed);
            Logger.diagnosticInfo(
                app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "framed handoff " + (taken ? "went in" : "refused"));
            if (!taken) {
                failed();
            }
            finish();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(cancel, half);
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        other.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(save, other);
        root.addView(bar, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM));

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(root);
    }

    /** A plain white button for the framing bar. */
    private TextView button(String label) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(12), dp(14), dp(12), dp(14));
        text.setClickable(true);
        text.setFocusable(true);
        android.graphics.drawable.GradientDrawable background =
            new android.graphics.drawable.GradientDrawable();
        background.setColor(0x33FFFFFF);
        background.setCornerRadius(dp(22));
        text.setBackground(background);
        return text;
    }

    /** [value] device-independent pixels in this activity's scale. */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
            Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "bounds unreadable: " + bounds.outWidth + "x" + bounds.outHeight);
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

    /** Says a picked video couldn't be handed to the camera. */
    private void failedVideo() {
        try {
            Utils.showToastShort(L10n.t("That video couldn't be read"));
        } catch (Throwable ignored) {
            // A toast that can't show changes nothing.
        }
    }
}
