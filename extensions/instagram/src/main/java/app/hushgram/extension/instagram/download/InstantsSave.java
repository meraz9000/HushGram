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
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Save button on stories and instants" patch: stories keep a button, while an
 * instant is saved by holding it.
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
    /** The name the diagnostic log and report count this feature under. */
    static final String SOURCE = "InstantsSave";

    /** Counted for each instant that became the one on screen. */
    static final String BOUND = "bound an instant on screen";
    /** Whether the switch-off notice went to the diagnostic log this process. */
    private static volatile boolean loggedOff;
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
    /** How long the floating glyph stays after the last render that kept it alive. */
    private static final long OVERLAY_LIFE_MS = 6_000;

    /** The instant's Media on screen now, or null. Guarded by {@link #LOCK}. */
    @Nullable
    private static volatile Object current;
    /** When the current media was bound, for placement windows. */
    private static volatile long boundAt;
    /** When a long-press last asked to save, so press and release make one save. */
    private static volatile long lastSaveAt;
    /** The reply bar row the viewer's composer offered, or null. */
    @Nullable
    private static volatile ViewGroup row;
    /** When that row was captured. */
    private static volatile long rowAt;

    private static final Object LOCK = new Object();
    /** The floating glyph over a viewer whose reply bar can't hold the button, or null. */
    @Nullable
    private static volatile android.widget.PopupWindow overlay;
    /** The handler that takes the floating glyph away once renders stop. */
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable HIDE = InstantsSave::hideOverlay;

    private InstantsSave() {
    }

    /**
     * Called with the item model of each instant as Instagram renders it. Makes it the instant on
     * screen and takes the Save button away: an instant is saved by holding it, like InstaEclipse,
     * while stories keep their button. Never throws.
     */
    public static void onInstant(Object item) {
        onInstantBound(item == null ? null : media(item));
    }

    /** What {@link #onInstant} does once the item's Media is in, tests included. */
    static void onInstantBound(@Nullable Object media) {
        try {
            if (!on()) {
                logOff();
                return;
            }
            HookStatus.invoked(FamilyNames.SAVE_INSTANTS_BUTTON);
            if (media == null) {
                return;
            }
            bind(media);
            removeButton();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, SWITCH, t);
        }
    }

    /**
     * Called with the story viewer's item whenever the story or instant on screen changes — both
     * are the same viewer, and its menu helper keeps the current item in a field the patch hooks.
     * The Media reaches this class through a stub the patch fills. Never throws.
     */
    public static void onStory(Object item) {
        Object media = item == null ? null : storyMedia(item);
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
            "story bound: item " + (item == null ? "null" : "ok") + ", media " + (media == null ? "null" : "ok"));
        onViewBound(media);
    }

    /** What the story viewer does once the item's Media is in: bind and place its button. */
    static void onViewBound(@Nullable Object media) {
        try {
            if (!on()) {
                logOff();
                return;
            }
            HookStatus.invoked(FamilyNames.SAVE_INSTANTS_BUTTON);
            if (media == null) {
                return;
            }
            bind(media);
            place();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, SWITCH, t);
        }
    }

    /** Makes [media] the instant or story on screen. */
    private static void bind(Object media) {
        synchronized (LOCK) {
            current = media;
            boundAt = SystemClock.elapsedRealtime();
        }
        HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, BOUND);
    }

    /** Says once why nothing is placed or saved. */
    private static void logOff() {
        if (loggedOff) {
            return;
        }
        loggedOff = true;
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
            "off: settingsReady " + Utils.settingsReady() + ", switch " +
                (Utils.settingsReady() ? Settings.SAVE_INSTANTS_BUTTON.get() : null));
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
     * Puts the Save button where the surface keeps its controls: in the reply bar's row when the
     * surface has one, else as a floating glyph over the viewer that every render keeps alive and
     * that goes away shortly after the renders stop. The icon is drawn here either way.
     */
    private static void place() {
        ViewGroup captured = row;
        if (captured != null && SystemClock.elapsedRealtime() - rowAt <= ROW_FRESH_MS) {
            if (captured.findViewWithTag(TAG) == null) {
                placeIn(captured);
            }
            return;
        }
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing()) {
            HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, NO_ROW);
            return;
        }
        View bar = bar(activity);
        if (bar instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) bar;
            if (group.findViewWithTag(TAG) != null) {
                return;
            }
            placeIn(group);
            return;
        }
        showOverlay(activity);
    }

    /** Puts the icon at the end of [group], with the metrics of its last visible child. */
    private static void placeIn(ViewGroup group) {
        ImageView save = icon(group.getContext());
        ViewGroup.LayoutParams params = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE && child.getLayoutParams() != null) {
                params = new ViewGroup.LayoutParams(child.getLayoutParams());
            }
        }
        if (params == null) {
            params = new ViewGroup.LayoutParams(dp(group, 40), dp(group, 40));
        }
        HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, PLACED);
        group.addView(save, group.getChildCount(), params);
    }

    /** The floating glyph over the viewer, kept alive by the renders and hidden once they stop. */
    private static void showOverlay(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!decor.isAttachedToWindow() || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                return;
            }
            android.widget.PopupWindow showing = overlay;
            if (showing != null && showing.isShowing() && showing.getContentView() != null &&
                showing.getContentView().isAttachedToWindow()) {
                MAIN.removeCallbacks(HIDE);
                MAIN.postDelayed(HIDE, OVERLAY_LIFE_MS);
                return;
            }
            ImageView save = icon(activity);
            int size = dp(activity, 44);
            android.widget.PopupWindow popup = new android.widget.PopupWindow(save, size, size);
            popup.setBackgroundDrawable(null);
            popup.setFocusable(false);
            popup.setOutsideTouchable(false);
            // On the top bar, just left of the grid and camera buttons.
            int x = Math.round(decor.getWidth() * 0.58f);
            int y = Math.round(decor.getHeight() * 0.042f);
            popup.showAtLocation(decor, android.view.Gravity.TOP | android.view.Gravity.START, x, y);
            overlay = popup;
            MAIN.removeCallbacks(HIDE);
            MAIN.postDelayed(HIDE, OVERLAY_LIFE_MS);
            HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, PLACED);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "overlay shown at " + x + "," + y + " in " + decor.getWidth() + "x" + decor.getHeight());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, PLACE, t);
        }
    }

    /** Takes the floating glyph away. */
    private static void hideOverlay() {
        try {
            android.widget.PopupWindow showing = overlay;
            overlay = null;
            if (showing != null && showing.isShowing()) {
                showing.dismiss();
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "overlay hidden");
            }
        } catch (Throwable ignored) {
            // A window already gone needs nothing.
        }
    }

    /** The drawn Save glyph, with the tap that saves the instant on screen. */
    private static ImageView icon(android.content.Context context) {
        ImageView save = new ImageView(context);
        save.setTag(TAG);
        save.setImageDrawable(new Glyph(context));
        save.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
        save.setOnClickListener(v -> tap(v));
        return save;
    }

    /**
     * The view the Save button goes in: the reply bar a composer offered, or a reply bar the
     * activity carries by the id the patch supplies.
     */
    @Nullable
    private static View bar(Activity activity) {
        try {
            return activity.findViewById(replyBarId());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Clears the instant on screen, as the viewer's close will. Tests use it too. */
    static void forget() {
        synchronized (LOCK) {
            current = null;
        }
    }

    /** The tap on the story Save button: saves the story on screen. */
    private static void tap(View button) {
        saveCurrent(activity(button), button.getContext());
    }

    /**
     * Wraps the Compose viewer's long-press callback so a held instant saves first and Instagram's
     * own long-press action still runs. The patch passes the field it found on that build, so the
     * field letter is not written down here.
     */
    public static void wrapLongPress(@Nullable Object handler, @Nullable String fieldName) {
        try {
            if (handler == null || fieldName == null) {
                return;
            }
            java.lang.reflect.Field field = field(handler.getClass(), fieldName);
            if (field == null) {
                return;
            }
            Object original = field.get(handler);
            if (original == null || java.lang.reflect.Proxy.isProxyClass(original.getClass())) {
                return;
            }
            Class<?> function = Class.forName("kotlin.jvm.functions.Function1");
            if (!function.isInstance(original)) {
                return;
            }
            Object wrapper = java.lang.reflect.Proxy.newProxyInstance(
                function.getClassLoader(),
                new Class<?>[] {function},
                (proxy, method, arguments) -> {
                    if ("invoke".equals(method.getName())) {
                        boolean saved = saveCurrent(Utils.getActivity(), null);
                        if (saved && InstantsSeen.seenAndAdvance()) {
                            // The save marked this instant seen and moved past it. Instagram's
                            // menu would belong to the card that just left, so it doesn't run.
                            return null;
                        }
                    }
                    try {
                        return method.invoke(original, arguments);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
            field.set(handler, wrapper);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "long-press wrapped on " + fieldName);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, "long-press", t);
        }
    }

    /** Saves the instant on screen once, for a long-press or the story button. */
    static boolean saveCurrent(
        @Nullable Activity activity, @Nullable android.content.Context feedback
    ) {
        try {
            if (!on()) {
                logOff();
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            synchronized (LOCK) {
                if (lastSaveAt != 0 && now - lastSaveAt < 1_000) {
                    return false;
                }
                lastSaveAt = now;
            }
            Object media;
            synchronized (LOCK) {
                media = current;
            }
            if (media == null || activity == null) {
                HookStatus.counted(FamilyNames.SAVE_INSTANTS_BUTTON, NOTHING);
                if (activity != null) {
                    Feedback.show(activity, L10n.t(activity, "Download failed"), true);
                }
                return false;
            }
            if (!ReelDownload.saveInstantsMedia(media, activity)) {
                Feedback.show(activity, L10n.t(activity, "Download failed"), true);
                return false;
            }
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, SAVE, t);
            try {
                android.content.Context context = feedback != null ? feedback : activity;
                if (context != null) {
                    Feedback.show(context, L10n.t(context, "Download failed"), true);
                }
            } catch (Throwable ignored) {
                // The save already reported its own failure.
            }
            return false;
        }
    }

    /** Takes any Save button off the surface an instant is about to use. */
    private static void removeButton() {
        try {
            ViewGroup captured = row;
            if (captured != null) {
                View old = captured.findViewWithTag(TAG);
                if (old != null) {
                    captured.removeView(old);
                }
            }
            Activity activity = Utils.getActivity();
            if (activity != null) {
                View bar = bar(activity);
                if (bar instanceof ViewGroup) {
                    View old = bar.findViewWithTag(TAG);
                    if (old != null) {
                        ((ViewGroup) bar).removeView(old);
                    }
                }
            }
            hideOverlay();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_INSTANTS_BUTTON, PLACE, t);
        }
    }

    /** The field [name] of [type], including inherited fields, or null. */
    @Nullable
    private static java.lang.reflect.Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // Try the superclass.
            }
        }
        return null;
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
        return dp(view.getContext(), value);
    }

    /** [value] device-independent pixels in the context's scale. */
    private static int dp(android.content.Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
    }

    /** Filled in by the patch: the Media of [item], one of Instagram's instant items, or null. */
    public static Object media(Object item) {
        return null;
    }

    /** Filled in by the patch: the Media of [item], the story or instant the viewer shows, or null. */
    public static Object storyMedia(Object item) {
        return null;
    }

    /** Filled in by the patch: the resource id of the story viewer's reply bar. */
    public static int replyBarId() {
        return 0;
    }
}
