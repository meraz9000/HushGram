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
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;

/**
 * The media picker the gallery glyph on the Instants camera opens.
 *
 * <p>A transparent, exportless activity the patch adds to the manifest. It asks Android for a
 * picture or video — the system photo picker on 13 and newer, the document picker before it,
 * neither of which needs a permission. A picture is read into a Bitmap the size the camera
 * hands its own captures at, shown in a {@link CropView} so the person can frame it, and given
 * to {@link InstantsGallery#capture}. A video opens a trim screen with start and end handles;
 * the selected part is cut without re-encoding and given to
 * {@link InstantsGallery#captureVideoFile}, which runs the camera's own recorded-video callback.
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
    @Nullable
    private VideoView videoView;
    @Nullable
    private VideoTrimView trimView;
    private long trimStartMs;
    private long trimEndMs;
    private final Runnable trimWatcher = new Runnable() {
        @Override
        public void run() {
            VideoView view = videoView;
            if (view == null) {
                return;
            }
            try {
                if (view.isPlaying()) {
                    int position = view.getCurrentPosition();
                    VideoTrimView timeline = trimView;
                    if (timeline != null) {
                        int shown = Math.max(
                            (int) trimStartMs, Math.min(position, (int) trimEndMs));
                        timeline.setPlayhead(shown);
                    }
                    if (position >= trimEndMs) {
                        view.pause();
                        view.seekTo((int) trimEndMs);
                    }
                }
            } catch (Throwable ignored) {
                // A player torn down mid-tick has nothing left to bound.
            }
            view.postDelayed(this, 100);
        }
    };

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
                showTrim(picked);
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
                    SOURCE, () -> "photo callback missing after decode");
                Utils.showToastLong(L10n.t("Take one normal Instants photo first"));
                finish();
                return;
            }
            Logger.diagnosticInfo(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                SOURCE, () -> "photo callback present after decode; framing");
            showFraming(bitmap);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not read the picked media", t);
            failed();
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        VideoView view = videoView;
        if (view != null) {
            view.pause();
        }
    }

    @Override
    protected void onDestroy() {
        VideoView view = videoView;
        videoView = null;
        trimView = null;
        if (view != null) {
            view.removeCallbacks(trimWatcher);
            try {
                view.stopPlayback();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
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

    /**
     * Shows the picked video with start and end handles. The person can play it, pick the part
     * to keep, trim it without re-encoding, or send the whole file.
     */
    private void showTrim(Uri picked) {
        long duration = durationOf(picked);
        if (duration < 1000) {
            handoffVideo(picked);
            return;
        }
        trimStartMs = 0;
        trimEndMs = duration;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout videoFrame = new FrameLayout(this);
        VideoView view = new VideoView(this);
        videoView = view;
        view.setVideoURI(picked);
        videoFrame.addView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(10), dp(8), dp(10), dp(10));
        overlay.setBackgroundColor(0x66000000);
        TextView rangeLabel = label("");
        updateTrimLabels(rangeLabel);
        VideoTrimView timeline = new VideoTrimView(this, picked, duration);
        trimView = timeline;
        timeline.setPlayhead(trimStartMs);
        timeline.setListener(new VideoTrimView.Listener() {
            @Override
            public void onSeek(long positionMs) {
                view.seekTo((int) positionMs);
            }

            @Override
            public void onRangeChanged(long startMs, long endMs) {
                trimStartMs = startMs;
                trimEndMs = endMs;
                updateTrimLabels(rangeLabel);
                if (view.getCurrentPosition() < trimStartMs) {
                    view.seekTo((int) trimStartMs);
                    timeline.setPlayhead(trimStartMs);
                } else if (view.getCurrentPosition() > trimEndMs) {
                    view.seekTo((int) trimEndMs);
                    timeline.setPlayhead(trimEndMs);
                }
            }

            @Override
            public void onHandleReleased(long startMs) {
                trimStartMs = startMs;
                playVideoFrom(startMs);
            }
        });
        overlay.addView(rangeLabel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams timelineParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        timelineParams.topMargin = dp(4);
        overlay.addView(timeline, timelineParams);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM);
        overlayParams.setMargins(dp(8), 0, dp(8), dp(8));
        videoFrame.addView(overlay, overlayParams);

        view.setOnPreparedListener(player -> {
            player.setLooping(false);
            view.seekTo((int) trimStartMs);
            timeline.setPlayhead(trimStartMs);
            view.postDelayed(trimWatcher, 100);
        });
        view.setOnCompletionListener(player -> {
            view.seekTo((int) trimStartMs);
            timeline.setPlayhead(trimStartMs);
        });
        view.setOnClickListener(v -> {
            if (view.isPlaying()) {
                view.pause();
            } else {
                long position = view.getCurrentPosition();
                if (position < trimStartMs || position >= trimEndMs) {
                    position = trimStartMs;
                }
                playVideoFrom(position);
            }
        });
        column.addView(videoFrame, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(10), dp(16), dp(40));
        controls.setBackgroundColor(0xF2000000);

        TextView title = new TextView(this);
        title.setText(L10n.t("Trim video"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        controls.addView(title);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = button(L10n.t("Cancel"));
        cancel.setOnClickListener(v -> finish());
        TextView full = button(L10n.t("Use full video"));
        full.setOnClickListener(v -> {
            view.pause();
            handoffVideo(picked);
        });
        TextView trim = button(L10n.t("Trim"));
        trim.setOnClickListener(v -> {
            view.pause();
            if (trimStartMs <= 0 && trimEndMs >= duration - 100) {
                handoffVideo(picked);
            } else {
                trimAndSend(picked, trimStartMs, trimEndMs, trim);
            }
        });
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        third.setMargins(dp(4), dp(6), dp(4), 0);
        actions.addView(cancel, third);
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        middle.setMargins(dp(4), dp(6), dp(4), 0);
        actions.addView(full, middle);
        LinearLayout.LayoutParams last = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        last.setMargins(dp(4), dp(6), dp(4), 0);
        actions.addView(trim, last);
        controls.addView(actions);

        column.addView(controls, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(root);
    }

    /** Runs the lossless cut on a worker thread, then hands the result to the camera callback. */
    private void trimAndSend(Uri picked, long startMs, long endMs, TextView trimButton) {
        File temp;
        try {
            temp = File.createTempFile("hushgram-trim-", ".mp4", getCacheDir());
        } catch (Throwable t) {
            Logger.printException(() -> "Could not create the trimmed video", t);
            failedTrim();
            return;
        }
        trimButton.setEnabled(false);
        trimButton.setText(L10n.t("Trimming video…"));
        new Thread(() -> {
            boolean trimmed = false;
            try {
                trimmed = VideoTrimmer.trim(this, picked, startMs, endMs, temp);
            } catch (Throwable t) {
                Logger.printException(() -> "Could not trim the picked video", t);
            }
            boolean result = trimmed;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    return;
                }
                if (!result) {
                    trimButton.setEnabled(true);
                    trimButton.setText(L10n.t("Trim"));
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    failedTrim();
                    return;
                }
                boolean taken = InstantsGallery.captureVideoFile(temp);
                Logger.diagnosticInfo(
                    app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                    SOURCE, () -> "trimmed video handoff " + (taken ? "went in" : "refused"));
                if (!taken) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    failedVideo();
                }
                finish();
            });
        }, "hushgram-video-trim").start();
    }

    /** Runs the recorded-video callback with the entire picked file. */
    private void handoffVideo(Uri picked) {
        boolean taken = InstantsGallery.captureVideo(this, picked);
        Logger.diagnosticInfo(
            app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
            SOURCE, () -> "video handoff " + (taken ? "went in" : "refused"));
        if (!taken) {
            failedVideo();
        }
        finish();
    }

    /** The video's duration in milliseconds, or 0 when the phone cannot read it. */
    private long durationOf(Uri picked) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, picked);
            String value = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Throwable t) {
            Logger.printException(() -> "Could not read the picked video's length", t);
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void updateTrimLabels(TextView label) {
        label.setText(L10n.t("Start") + "  " + clock(trimStartMs)
            + "    " + L10n.t("End") + "  " + clock(trimEndMs));
    }

    /**
     * Seeks and then starts playback. VideoView's seek is asynchronous, so starting immediately
     * would resume at the old frame; this waits until the player reports the requested position.
     */
    private void playVideoFrom(long positionMs) {
        VideoView view = videoView;
        if (view == null) {
            return;
        }
        long target = Math.max(0, positionMs);
        try {
            int duration = view.getDuration();
            if (duration > 0) {
                target = Math.min(target, duration);
            }
            view.pause();
            view.seekTo((int) target);
        } catch (Throwable t) {
            try {
                view.seekTo((int) target);
            } catch (Throwable ignored) {
            }
        }
        VideoTrimView timeline = trimView;
        if (timeline != null) {
            timeline.setPlayhead(target);
        }
        long wanted = target;
        int[] attempts = {0};
        Runnable startWhenSeeked = new Runnable() {
            @Override
            public void run() {
                VideoView current = videoView;
                if (current == null) {
                    return;
                }
                int now = current.getCurrentPosition();
                if (Math.abs(now - wanted) <= 350 || attempts[0]++ >= 12) {
                    try {
                        current.start();
                        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                            "trim playback started at " + wanted + ", player at " + now);
                    } catch (Throwable t) {
                        Logger.printException(() -> "Could not start the trim preview", t);
                    }
                } else {
                    current.postDelayed(this, 50);
                }
            }
        };
        view.postDelayed(startWhenSeeked, 200);
    }

    private String clock(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setPadding(dp(4), dp(8), dp(4), 0);
        return label;
    }

    /** Says a picked video couldn't be trimmed. */
    private void failedTrim() {
        try {
            Utils.showToastShort(L10n.t("The video couldn't be trimmed"));
        } catch (Throwable ignored) {
        }
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
