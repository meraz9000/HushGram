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
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Keep instants unseen" patch.
 *
 * <p>The viewer marks an instant consumed through an eight-argument function reference; the
 * patch calls {@link #beforeConsume} at that reference's entry. Case 2 is the consumption:
 * its body writes the seen state, removes the instant and sends Instagram's seen mutation.
 * With the switch on that body is skipped, so the instant stays unseen and can be watched
 * again. The eye button places over the viewer while an instant renders and calls the kept
 * reference with the recorded instant, which runs the body exactly as Instagram would.
 *
 * <p>Everything fails open: with the switch off, HushGram paused, the settings not read yet,
 * no recorded consumption, or anything thrown, the viewer consumes as it always did and the
 * button changes nothing.
 */
public final class InstantsSeen {
    private static final String SOURCE = "InstantsSeen";
    private static final String KEPT = "kept an instant unseen";
    private static final String SHOWN = "placed the eye button";
    private static final String MARKED = "marked an instant seen";

    /** The tag the eye button carries, so a viewer never holds two. */
    private static final Object TAG = "hushgram_keep_instants";
    /** How long the eye button stays after the last render that kept it alive. */
    private static final long OVERLAY_LIFE_MS = 8_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = InstantsSeen::hideOverlay;

    /** The eye button over the viewer, or null. */
    @Nullable
    private static volatile PopupWindow overlay;

    /** The last consumption reference's arguments: the reference, instant, previous, type. */
    @Nullable
    private static volatile Object[] lastConsume;
    /** The instant the viewer is showing right now, as its render hook last saw it. */
    @Nullable
    private static volatile Object currentItem;

    /** True while the eye button is running the kept reference. */
    private static volatile boolean force;
    /** The ids the eye button marked seen, waiting for the viewer's close to sync them. */
    private static final java.util.Set<String> EYE_IDS =
        java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private InstantsSeen() {
    }

    /**
     * Called at the consumption reference's entry with its four arguments. Case 2 is the instant
     * consumption; the call is kept so the eye button can replay it. The body itself always
     * runs, because it is what advances the viewer to the next instant. Only the seen write
     * inside it is skipped. Never throws.
     */
    public static void onConsume(
        Object callback, @Nullable Object current, @Nullable Object previous,
        @Nullable Object type
    ) {
        try {
            if (caseOf(callback) == 2) {
                lastConsume = new Object[] {callback, current, previous, type};
                Object id = mediaId(current);
                String callers = callers();
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                    "consume id=" + tag(id) + " force=" + force + " callers=" + callers);
                MAIN.post(InstantsSeen::show);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "consumption", t);
        }
    }

    /**
     * Called at the seen-state manager's write entry with the manager and the media id. Answers
     * true when the write should be skipped: the viewer still advances, but the instant stays
     * unseen. The eye button runs the same call with {@link #force} set, so the write lands.
     */
    public static boolean beforeSeenWrite(Object manager, @Nullable Object mediaId) {
        try {
            boolean skip = on() && !force;
            String callers = callers();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "seen write id=" + tag(mediaId) + " skip=" + skip + " force=" + force
                    + " callers=" + callers);
            if (!skip) {
                return false;
            }
            HookStatus.counted(FamilyNames.KEEP_INSTANTS, KEPT);
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "seen write", t);
            return false;
        }
    }

    /**
     * Called with the media ids of the server's seen request. With the switch on it answers only
     * the ids the eye button marked, so viewed instants stay unseen on Instagram's side while the
     * eye's own instant is still reported. The eye button runs the request with {@link #force}
     * set, so the real ids go through.
     */
    public static java.util.List<?> filterSeenIds(@Nullable java.util.List<?> ids) {
        if (ids == null) {
            return null;
        }
        try {
            boolean skip = on() && !force;
            java.util.List<Object> out = new java.util.ArrayList<>();
            if (skip) {
                synchronized (EYE_IDS) {
                    for (Object id : ids) {
                        if (id != null && EYE_IDS.contains(String.valueOf(id))) {
                            out.add(id);
                        }
                    }
                    EYE_IDS.removeAll(out);
                }
            } else {
                out.addAll(ids);
            }
            String callers = callers();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "seen ids " + ids.size() + " -> " + out.size() + " force=" + force
                    + " callers=" + callers);
            if (!skip) {
                return ids;
            }
            if (ids.size() > out.size()) {
                HookStatus.counted(FamilyNames.KEEP_INSTANTS, "kept a seen request empty");
            }
            return out;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "seen request", t);
            return ids;
        }
    }

    /**
     * Called just before the viewer host moves the seen manager's pending ids (field `A05`) into
     * its hard seen set on close. That commit is what removed viewed instants from the tray even
     * though every normal seen write was skipped, because it does not go through `A04`/`A05`.
     *
     * <p>With no eye tap the pending set is emptied, so the host's commit and its follow-up sync
     * both see nothing. When the eye did mark an instant, the pending and hard sets are replaced
     * by exactly the eye's ids, so the host's own local+server sync reports those ids and nothing
     * else.
     */
    public static void viewerClose(@Nullable Object manager, @Nullable Object viewModel) {
        try {
            if (!on() || manager == null) {
                return;
            }
            Object pending = field(manager, "A05");
            Object flow = field(manager, "A07");
            java.util.List<String> eye;
            synchronized (EYE_IDS) {
                eye = new java.util.ArrayList<>(EYE_IDS);
            }
            int before = collectionSize(pending);
            if (pending instanceof java.util.Collection) {
                java.util.Collection set = (java.util.Collection) pending;
                set.clear();
                for (String id : eye) {
                    try {
                        set.add(id);
                    } catch (Throwable ignored) {
                        // The hard set below still carries the eye's id to the sync.
                    }
                }
            }
            java.util.LinkedHashSet<String> hard = new java.util.LinkedHashSet<>(eye);
            setFlow(flow, hard);
            if (!eye.isEmpty()) {
                dropFromTray(viewModel, new java.util.LinkedHashSet<>(eye));
            }
            int after = collectionSize(pending);
            String callers = callers();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "viewer close eye=" + eye.size() + " pending " + before + " -> " + after
                    + " callers=" + callers);
            if (!eye.isEmpty()) {
                HookStatus.counted(FamilyNames.KEEP_INSTANTS, "synced the eye's seen id");
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "viewer close", t);
        }
    }

    /**
     * Marks the instant on screen seen and moves to the next one. Used by the long-press save:
     * it replays the kept consumption with the seen write forced, drops the instant from the tray
     * and fetcher state, asks the viewer's own close path to sync it to Instagram now, and calls
     * the viewer's next action. Answers whether it could do all of that; when it cannot, the
     * long-press save leaves Instagram's own action alone. Never throws.
     */
    public static boolean seenAndAdvance() {
        try {
            Object[] consume = lastConsume;
            Object current = currentItem != null ? currentItem
                : consume == null || consume.length < 2 ? null : consume[1];
            if (consume == null || consume.length != 4 || consume[0] == null || current == null) {
                return false;
            }
            Object viewModel = field(consume[0], "receiver");
            if (viewModel == null) {
                return false;
            }
            Object id = mediaId(current);
            boolean before = force;
            force = true;
            try {
                Method invoke = consume[0].getClass().getMethod(
                    "invoke", Object.class, Object.class, Object.class);
                invoke.invoke(consume[0], current, consume[2], consume[3]);
            } finally {
                force = before;
            }
            if (id != null) {
                String key = String.valueOf(id);
                EYE_IDS.add(key);
                Object manager = field(viewModel, "A0Z");
                if (manager != null) {
                    viewerClose(manager, viewModel);
                } else {
                    dropFromTray(viewModel, java.util.Collections.singleton(key));
                }
            }
            try {
                viewModel.getClass().getMethod("A13").invoke(viewModel);
            } catch (NoSuchMethodException e) {
                try {
                    viewModel.getClass().getMethod("A14").invoke(viewModel);
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.KEEP_INSTANTS, "long-press seen sync", t);
                }
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.KEEP_INSTANTS, "long-press seen sync", t);
            }
            try {
                viewModel.getClass().getMethod("A16", Integer.class).invoke(viewModel, 1);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.KEEP_INSTANTS, "long-press next", t);
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "long-press seen and advanced id=" + tag(id));
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "long-press seen", t);
            return false;
        }
    }

    /**
     * Takes the eye's ids out of the tray and the fetcher's stored state. The fetcher's merge
     * keeps old items whose ids the server still calls valid, so leaving them there makes a
     * marked instant reappear on the next fetch. Never throws.
     */
    private static void dropFromTray(@Nullable Object viewModel, java.util.Set<String> ids) {
        try {
            if (viewModel == null || ids.isEmpty()) {
                return;
            }
            Object repository = field(viewModel, "A0V");
            if (repository == null) {
                repository = field(viewModel, "A0A");
            }
            if (repository == null) {
                return;
            }
            Class<?> repositoryType = repository.getClass();
            java.lang.reflect.Method remove = null;
            for (String name : new String[] {"A06", "A05"}) {
                try {
                    remove = repositoryType.getDeclaredMethod(name, repositoryType, String.class);
                    remove.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Try the next remove helper.
                }
            }
            if (remove == null) {
                HookStatus.threw(
                    FamilyNames.KEEP_INSTANTS,
                    "tray drop",
                    new NoSuchMethodException(repositoryType.getName() + " has no tray remove")
                );
                return;
            }
            for (String id : ids) {
                remove.invoke(null, repository, id);
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "tray dropped eye ids=" + ids.size());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "tray drop", t);
        }
    }

    /** A copy of [state] with the media items whose id is in [ids] removed from its list. */
    @Nullable
    private static Object withoutIds(@Nullable Object state, java.util.Set<String> ids) {
        if (state == null || ids.isEmpty()) {
            return state;
        }
        try {
            // The constructor takes its fields in a different order from their letters: the three
            // lists are (A0B items, A0C, A0A). Reading them by name here keeps the state the same
            // shape as the one it replaces; the items list, A0B, is the one to filter.
            String[] order = {
                "A00", "A03", "A02", "A01", "A04", "A07", "A08",
                "A06", "A05", "A09", "A0B", "A0C", "A0A",
            };
            Object[] values = new Object[order.length];
            for (int index = 0; index < order.length; index++) {
                String name = order[index];
                Object value = field(state, name);
                if ("A0B".equals(name) && value instanceof java.util.List) {
                    java.util.ArrayList<Object> kept = new java.util.ArrayList<>();
                    for (Object item : (java.util.List<?>) value) {
                        Object itemId = item == null ? null : field(item, "A0C");
                        if (itemId == null || !ids.contains(String.valueOf(itemId))) {
                            kept.add(item);
                        }
                    }
                    value = kept;
                }
                values[index] = value;
            }
            for (java.lang.reflect.Constructor<?> constructor
                : state.getClass().getDeclaredConstructors()) {
                if (constructor.getParameterTypes().length == 13) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(values);
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "tray state copy", t);
        }
        return state;
    }

    /** Replaces the value of the seen flow, whichever setter this build exposes. */
    private static void setFlow(@Nullable Object flow, Object value) {
        if (flow == null) {
            return;
        }
        for (String name : new String[] {"HUv", "HQ5", "setValue"}) {
            try {
                java.lang.reflect.Method set = flow.getClass().getMethod(name, Object.class);
                set.setAccessible(true);
                set.invoke(flow, value);
                return;
            } catch (Throwable ignored) {
                // Try the next shape.
            }
        }
    }

    /** The size of [collection], or 0 when it is null or not a collection. */
    private static int collectionSize(@Nullable Object collection) {
        return collection instanceof java.util.Collection
            ? ((java.util.Collection<?>) collection).size()
            : 0;
    }

    /**
     * Called at the bulk fetcher's seen entry. Answers true when its update should be skipped
     * entirely: the fetcher would otherwise replace its tray state from the API response, which
     * can drop every instant on screen. The eye button forces the real update through.
     */
    public static boolean beforeFetcherSeen(Object fetcher, @Nullable java.util.List<?> ids) {
        try {
            boolean skip = on() && !force;
            int pending;
            synchronized (EYE_IDS) {
                pending = EYE_IDS.size();
            }
            if (skip && (pending > 0 || containsEyeId(ids))) {
                // A long-press sync can arrive with a list the manager has not caught up with yet;
                // the pending eye ids are what the request filter keeps, so let it through.
                skip = false;
            }
            final boolean skipLogged = skip;
            final int pendingLogged = pending;
            String callers = callers();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "fetcher seen skip=" + skipLogged + " force=" + force + " pending=" + pendingLogged
                    + " callers=" + callers);
            if (!skip) {
                return false;
            }
            HookStatus.counted(FamilyNames.KEEP_INSTANTS, "kept the instants tray");
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "fetcher seen", t);
            return false;
        }
    }

    /** Filled in by the patch: the fetcher's current tray state, an AtomicReference value. */
    public static Object fetcherState(Object fetcher) {
        return null;
    }

    /**
     * Called with a bulk seen write's ids. With the switch on the list is not merged into the
     * hard seen set; the eye button's single id still goes through {@link #beforeSeenWrite}.
     */
    public static boolean beforeSeenList(@Nullable java.util.List<?> ids) {
        try {
            boolean skip = on();
            String callers = callers();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "seen list size=" + (ids == null ? "null" : ids.size()) + " skip=" + skip
                    + " callers=" + callers);
            if (!skip) {
                return false;
            }
            HookStatus.counted(FamilyNames.KEEP_INSTANTS, "kept a soft seen list");
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "soft seen list", t);
            return false;
        }
    }

    /**
     * Decides whether the fetcher's built-in "return current state" path (`A01`) should be used:
     * 1 keeps the state (the switch is on and it already holds instants), 0 lets the fetch run
     * (the state is empty, so a fresh login still loads the tray), 2 leaves the flag untouched
     * (switch off, or the state couldn't be read). The fetcher otherwise sends the known list to
     * the server and replaces the state with only what comes back, so old instants would vanish
     * from the tray on every re-enter.
     */
    public static int fetchDecision(@Nullable Object fetcher) {
        try {
            if (!on() || fetcher == null) {
                return 2;
            }
            Object reference = field(fetcher, "A06");
            Object state = reference == null ? null
                : reference.getClass().getMethod("get").invoke(reference);
            Object list = state == null ? null : field(state, "A0C");
            if (list == null && state != null) {
                list = field(state, "A0B");
            }
            boolean keep = list instanceof java.util.Collection
                && !((java.util.Collection<?>) list).isEmpty();
            String callers = callers();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "fetcher fetch " + (keep ? "kept" : "allowed") + " callers=" + callers);
            return keep ? 1 : 0;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "fetcher fetch", t);
            return 2;
        }
    }

    /** Whether any of [ids] is an id the eye button marked. */
    private static boolean containsEyeId(@Nullable java.util.List<?> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        synchronized (EYE_IDS) {
            for (Object id : ids) {
                if (id != null && EYE_IDS.contains(String.valueOf(id))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The field [name] of [owner], including inherited fields, or null. */
    @Nullable
    private static Object field(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (Throwable ignored) {
                // Try the superclass.
            }
        }
        return null;
    }

    /** Called with the item model of each instant as Instagram renders it. Never throws. */
    public static void onView(Object item) {
        bindView(item, "viewer render");
    }

    /** Called with the story viewer's item whenever the story or instant changes. Never throws. */
    public static void onStory(Object item) {
        bindView(item, "story render");
    }

    /**
     * Keeps the eye tied to the instant the viewer is showing. The consumption record is only
     * kept as the reference and its argument types: the item it names is the one that was just
     * consumed, which is usually the previous card, not the one on screen. The tapped item is
     * always {@link #currentItem}, so a tap marks what the person sees.
     */
    private static void bindView(@Nullable Object item, String step) {
        try {
            if (!on()) {
                return;
            }
            currentItem = item;
            if (lastConsume != null) {
                show();
            } else {
                hideOverlay();
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, step, t);
        }
    }

    /** Places the eye button over the activity's decor view, or keeps a present one alive. */
    private static void show() {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            View decor = activity.getWindow().getDecorView();
            if (!decor.isAttachedToWindow() || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                return;
            }
            PopupWindow showing = overlay;
            if (showing != null && showing.isShowing() && showing.getContentView() != null
                && showing.getContentView().isAttachedToWindow()) {
                MAIN.removeCallbacks(HIDE);
                MAIN.postDelayed(HIDE, OVERLAY_LIFE_MS);
                return;
            }
            ImageView eye = icon(activity);
            int size = dp(activity, 44);
            PopupWindow popup = new PopupWindow(eye, size, size);
            popup.setBackgroundDrawable(null);
            popup.setFocusable(false);
            popup.setOutsideTouchable(false);
            // Below the archive button, in the same right-hand column, clear of the top bar.
            int x = Math.round(decor.getWidth() * 0.70f);
            int y = Math.round(decor.getHeight() * 0.105f);
            popup.showAtLocation(decor, Gravity.TOP | Gravity.START, x, y);
            overlay = popup;
            MAIN.removeCallbacks(HIDE);
            MAIN.postDelayed(HIDE, OVERLAY_LIFE_MS);
            HookStatus.counted(FamilyNames.KEEP_INSTANTS, SHOWN);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "eye button at " + x + "," + y + " in " + decor.getWidth() + "x" + decor.getHeight());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "button placement", t);
        }
    }

    /** Takes the eye button away. */
    private static void hideOverlay() {
        try {
            PopupWindow showing = overlay;
            overlay = null;
            if (showing != null && showing.isShowing()) {
                showing.dismiss();
            }
        } catch (Throwable ignored) {
            // A window already gone needs nothing.
        }
    }

    /** The drawn eye, with the tap that marks the instant on screen seen. */
    private static ImageView icon(android.content.Context context) {
        ImageView eye = new ImageView(context);
        eye.setTag(TAG);
        eye.setImageDrawable(new Glyph(context));
        eye.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
        eye.setOnClickListener(InstantsSeen::tap);
        return eye;
    }

    /** The tap on the eye: runs the kept consumption for the instant on screen. */
    private static void tap(View button) {
        android.content.Context context = button.getContext();
        try {
            Object[] consume = lastConsume;
            Object current = currentItem != null ? currentItem
                : consume == null || consume.length < 2 ? null : consume[1];
            Object id = mediaId(current);
            Object recorded = consume == null || consume.length < 2 ? null : mediaId(consume[1]);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "eye tap id=" + tag(id) + " recorded=" + tag(recorded)
                    + " record=" + (consume != null) + " force=" + force);
            if (consume == null || consume.length != 4 || consume[0] == null || current == null) {
                Feedback.show(context, L10n.t(context, "Could not locate the instant on screen"), true);
                return;
            }
            force = true;
            try {
                Method invoke = consume[0].getClass().getMethod(
                    "invoke", Object.class, Object.class, Object.class);
                invoke.invoke(consume[0], current, consume[2], consume[3]);
                if (id != null) {
                    EYE_IDS.add(String.valueOf(id));
                }
            } finally {
                force = false;
            }
            HookStatus.counted(FamilyNames.KEEP_INSTANTS, MARKED);
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () ->
                "eye done id=" + tag(id));
            Feedback.show(context, L10n.t(context, "Instant marked as seen"), false);
            hideOverlay();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_INSTANTS, "mark seen", t);
            Feedback.show(context, L10n.t(context, "Could not locate the instant on screen"), true);
        }
    }

    /**
     * The function reference's case number, the only int field it carries. Read through
     * reflection because the field's `$t` name cannot be written in the patch's smali: the
     * assembler drops the line and leaves the case register unset.
     */
    private static int caseOf(@Nullable Object callback) {
        if (callback == null) {
            return -1;
        }
        try {
            java.lang.reflect.Field field = callback.getClass().getDeclaredField("$t");
            field.setAccessible(true);
            return field.getInt(callback);
        } catch (Throwable ignored) {
            for (java.lang.reflect.Field field : callback.getClass().getDeclaredFields()) {
                if (field.getType() != int.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.getInt(callback);
                } catch (Throwable ignoredField) {
                    // Try the next int field.
                }
            }
            return -1;
        }
    }

    /** Whether the switch is on. The pause and the settings' readiness are read with it. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_INSTANTS.get();
    }

    /** [value] device-independent pixels in the context's scale. */
    private static int dp(android.content.Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** A few caller frames, without the hook itself, for the diagnostic log. */
    private static String callers() {
        try {
            StackTraceElement[] stack = new Throwable().getStackTrace();
            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (StackTraceElement frame : stack) {
                String cls = frame.getClassName();
                if (cls.equals(InstantsSeen.class.getName())
                    || cls.startsWith("java.lang.reflect")
                    || cls.startsWith("java.lang.Thread")) {
                    continue;
                }
                if (shown > 0) {
                    out.append(" <- ");
                }
                out.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName());
                if (++shown >= 3) {
                    break;
                }
            }
            return shown == 0 ? "?" : out.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** A short, non-reversible tag for a media id, for correlating log lines. */
    private static String tag(@Nullable Object id) {
        if (id == null) {
            return "null";
        }
        String text = String.valueOf(id);
        return "len=" + text.length() + "/" + Integer.toHexString(text.hashCode());
    }

    /** The media id field of an instant model, or null. */
    @Nullable
    private static Object mediaId(@Nullable Object media) {
        if (media == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = media.getClass().getDeclaredField("A0C");
            field.setAccessible(true);
            return field.get(media);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The eye glyph: an outline with a pupil. */
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
            Path eye = new Path();
            eye.moveTo(dp(context, 3f), dp(context, 12f));
            eye.quadTo(dp(context, 12f), dp(context, 3f), dp(context, 21f), dp(context, 12f));
            eye.quadTo(dp(context, 12f), dp(context, 21f), dp(context, 3f), dp(context, 12f));
            eye.close();
            canvas.drawPath(eye, paint);
            canvas.drawCircle(dp(context, 12f), dp(context, 12f), dp(context, 3f), paint);
        }

        private static int dp(android.content.Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
