/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.download.ReelDownload;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Save button on instants" patch.
 *
 * <p>An instant (a friend's quick photo or video in the Instants feed) is rendered by a function
 * that receives the item model. The patch calls {@link #onView} with that model each time it
 * renders, and the model's Media reaches this class through a stub the patch fills. Each call
 * makes that instant the one on screen, and places a Save button in the reply bar's button row —
 * the row the message box's own buttons sit in, whose creation the patch also hooks and whose
 * views hand it to us.
 *
 * <p>The button only ever comes from a render, never from a composer of its own: a chat opened
 * after the viewer closes makes a composer too, and placing there would offer a save of an
 * instant that isn't on the screen. The row is held weakly, so the button goes where the viewer's
 * own reply bar goes and dies with it. While an instant renders again and again as it plays, a
 * row that only turned up after the first render is found by a later one.
 *
 * <p>A tap saves the instant the screen last rendered, straight through the reel save: photo to
 * the gallery, video to the movies, the quality setting, the file name and the toasts the same.
 * A save that can't start says so. Everything fails open: with the switch off, HushGram paused,
 * the settings not read yet, no row to hold the button or anything thrown, nothing is placed and
 * nothing is saved.
 */
public final class InstantsSave {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";
    /** The step a failed button placement is reported under. */
    static final String PLACE = "button placement";
    /** The step a failed save is reported under. */
    static final String SAVE = "save";

    /** Counted for each instant that became the one on screen. */
    static final String BOUND = "bound an instant on screen";
    /** Counted for each button put in a reply bar. */
    static final String PLACED = "placed the Save button";
    /** Counted when a render found no reply bar to hold the button yet. */
    static final String NO_ROW = "no reply bar yet";
    /** Counted when a save was refused because no instant was on screen. */
    static final String NOTHING = "nothing on screen to save";

    /** The tag the Save button carries, so a row never holds two. */
    private static final Object TAG = "hushgram_save_instants";
    /** How long a composer's row stays worth placing into after the composer was made. */
    private static final long ROW_FRESH_MS = 15_000;

    /** The instant's Media on screen now, or null. Guarded by {@link #LOCK}. */
    @Nullable
    private static volatile Object current;
    /** When the current media was bound, for placement windows. */
    private static volatile long boundAt;
    /** The reply bar row the viewer's composer offered, or null. */
    @Nullable
    private static volatile ViewGroup row;
    /** When that row was captured. */
    private static volatile long rowAt;

    private static final Object LOCK = new Object();

    private InstantsSave() {
    }

    /**
     * Called with the item model of each instant as Instagram renders it. Makes it the instant on
     * screen and places the Save button in the captured reply bar, if there is one. Never throws.
     */
    public static void onView(Object item) {
        onViewBound(item == null ? null : media(item));
    }

    /** What {@link #onView} does once the item's Media is in, tests included. Never throws. */
    static void onViewBound(@Nullable Object media) {
        try {
            if (!on()) {
                return;
            }
            HookStatus.invoked(FamilyNames.SAVE_INSTANTS_BUTTON);
            if (media == null) {
                return;
            }
            synchronized (LOCK) {
                current = media;
                boundAt = SystemClock.elapsedRealtime();
            }
            HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, BOUND);
            place();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, SWITCH, t);
        }
    }

    /**
     * Called with the message box's gallery, sticker and voice message views when a composer is
     * made. The row they sit in is kept for the next render to place into — this class never adds
     * the button from a composer alone, so a chat's own message box never grows one. Never throws.
     */
    public static void composerCreated(View gallery, View sticker, View voice) {
        try {
            if (!on()) {
                return;
            }
            ViewGroup parent = gallery == null ? null : (ViewGroup) gallery.getParent();
            if (parent == null) {
                return;
            }
            row = parent;
            rowAt = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, PLACE, t);
        }
    }

    /**
     * Puts the Save button at the end of the captured row, once, while the row is fresh. The icon
     * is drawn here, not taken from Instagram, and the row lays it out with the voice message
     * button's own metrics.
     */
    private static void place() {
        ViewGroup bar = row;
        if (bar == null || SystemClock.elapsedRealtime() - rowAt > ROW_FRESH_MS) {
            HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, NO_ROW);
            return;
        }
        if (bar.findViewWithTag(TAG) != null) {
            return;
        }
        ImageView save = new ImageView(bar.getContext());
        save.setTag(TAG);
        save.setImageDrawable(new Glyph(bar.getContext()));
        save.setPadding(dp(bar, 4), dp(bar, 4), dp(bar, 4), dp(bar, 4));
        save.setOnClickListener(v -> tap(v));
        ViewGroup.LayoutParams params = null;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE && child.getLayoutParams() != null) {
                params = new ViewGroup.LayoutParams(child.getLayoutParams());
            }
        }
        if (params == null) {
            params = new ViewGroup.LayoutParams(dp(bar, 40), dp(bar, 40));
        }
        HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, PLACED);
        bar.addView(save, bar.getChildCount(), params);
    }

    /** Clears the instant on screen, as the viewer's close will. Tests use it too. */
    static void forget() {
        synchronized (LOCK) {
            current = null;
        }
    }

    /** The tap on the Save button: saves the instant on screen, and says so when it can't. */
    private static void tap(View button) {
        try {
            Object media;
            synchronized (LOCK) {
                media = current;
            }
            Activity activity = activity(button);
            if (media == null || activity == null) {
                HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, NOTHING);
                Feedback.show(activity, L10n.t(activity, "Download failed"), true);
                return;
            }
            if (!ReelDownload.saveInstantsMedia(media, activity)) {
                Feedback.show(activity, L10n.t(activity, "Download failed"), true);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, SAVE, t);
            try {
                Feedback.show(button.getContext(), L10n.t(button.getContext(), "Download failed"), true);
            } catch (Throwable ignored) {
                // The save already reported its own failure.
            }
        }
    }

    /** The activity showing [view], or null. */
    @Nullable
    private static Activity activity(View view) {
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** [value] device-independent pixels in the view's scale. */
    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    /** Whether the switch is on. The pause and the settings' readiness are read with it. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.SAVE_INSTANTS_BUTTON.get();
    }

    /** The download glyph: an arrow into a tray, the way Instagram's own save glyphs read. */
    private static final class Glyph extends BitmapDrawable {
        private Glyph(android.content.Context context) {
            super(context.getResources(), android.graphics.Bitmap.createBitmap(
                    dp(context, 24), dp(context, 24), android.graphics.Bitmap.Config.ARGB_8888));
            Canvas canvas = new Canvas(getBitmap());
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            float mid = dp(context, 12f);
            path.moveTo(mid, dp(context, 4.5f));
            path.lineTo(mid, dp(context, 13.5f));
            path.moveTo(dp(context, 7.5f), dp(context, 9.5f));
            path.lineTo(mid, dp(context, 13.5f));
            path.lineTo(dp(context, 16.5f), dp(context, 9.5f));
            canvas.drawPath(path, paint);
            canvas.drawLine(dp(context, 5.5f), dp(context, 17.5f), dp(context, 18.5f), dp(context, 17.5f), paint);
        }

        private static int dp(android.content.Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        @Override
        public void draw(Canvas canvas) {
            // The bitmap is the drawing; nothing to add.
        }
    }

    /** Filled in by the patch: the Media of [item], one of Instagram's instant items, or null. */
    public static Object media(Object item) {
        return null;
    }
}
