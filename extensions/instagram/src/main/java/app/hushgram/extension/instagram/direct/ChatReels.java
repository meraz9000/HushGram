/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Swipe through a chat's reels" patch (#350).
 *
 * <p>Instagram already knows how to chain the reels shared in a chat, behind a server test. With
 * the test on, a reel opened from a chat takes the chat's other shared reels along into the Reels
 * viewer, so swiping up goes through the ones shared there before Instagram's suggested reels, and
 * Back still lands in the chat. The patch answers the test's one read through {@link #chain(int)}.
 *
 * <p>The viewer pages a chat's reels in two sides, the chat's own shared reels and then Instagram's
 * suggested ones. The patch asks {@link #stop()} before the viewer asks for that second side, so
 * with the second switch on it ends after the chat's reels (#390).
 */
public final class ChatReels {
    private ChatReels() {
    }

    /**
     * The chat reels test: Instagram's answer ([answer] nonzero for yes), or yes while the switch is
     * on. Instagram's answer while the switch is off, HushGram is paused or the settings aren't
     * ready. Never throws.
     */
    public static boolean chain(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            return instagram || (Utils.settingsReady() && Settings.SWIPE_CHAT_REELS.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels flag", failure);
            return instagram;
        }
    }

    /**
     * Whether the viewer of a chat's reels skips asking for suggested reels once the chat's own run
     * out: yes while the switch is on. No while it's off, HushGram is paused or the settings aren't
     * ready, so Instagram goes on to its suggestions. Never throws.
     */
    public static boolean stop() {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            return Utils.settingsReady() && Settings.STOP_AFTER_CHAT_REELS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels end", failure);
            return false;
        }
    }
}
