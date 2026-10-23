/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowToast;

/**
 * The Save button on instants: a render binds the instant on screen and places one button in the
 * reply bar a composer offered; a composer of its own never places one; a row never holds two;
 * the switch keeps everything off; and a tap that can't save says so.
 */
@RunWith(RobolectricTestRunner.class)
public class InstantsSaveTest {
    @Rule
    public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TAG = "hushgram_save_instants";
    private static final Object MEDIA = new Object();

    private Activity activity() {
        return Robolectric.setupActivity(Activity.class);
    }

    private LinearLayout row() {
        LinearLayout bar = new LinearLayout(activity());
        bar.addView(new View(activity()));
        bar.addView(new View(activity()));
        return bar;
    }

    private String report() {
        return String.valueOf(HookStatus.report());
    }

    @Test
    public void withTheSwitchOffNothingBindsOrPlaces() {
        Settings.SAVE_INSTANTS_BUTTON.save(false);
        HookStatus.clear();
        LinearLayout bar = row();
        InstantsSave.composerCreated(bar.getChildAt(0), null, bar.getChildAt(1));
        InstantsSave.onViewBound(MEDIA);
        assertFalse(report(), report().contains("Save button on instants"));
        assertEquals(2, bar.getChildCount());
    }

    @Test
    public void aRenderPlacesOneButtonInTheCapturedRow() {
        Settings.SAVE_INSTANTS_BUTTON.save(true);
        HookStatus.clear();
        LinearLayout bar = row();
        InstantsSave.composerCreated(bar.getChildAt(0), null, bar.getChildAt(1));
        InstantsSave.onViewBound(MEDIA);

        View save = bar.findViewWithTag(TAG);
        assertNotNull("no Save button in the row", save);
        assertEquals(3, bar.getChildCount());
        assertTrue(report(), report().contains("placed the Save button 1"));

        // A second render never adds a second button.
        InstantsSave.onViewBound(MEDIA);
        assertEquals(3, bar.getChildCount());
    }

    @Test
    public void aComposerAloneNeverPlacesTheButton() {
        Settings.SAVE_INSTANTS_BUTTON.save(true);
        HookStatus.clear();
        LinearLayout bar = row();
        InstantsSave.composerCreated(bar.getChildAt(0), null, null);
        assertEquals("the composer alone placed a button", 2, bar.getChildCount());
        assertFalse(report(), report().contains("placed the Save button"));
    }

    @Test
    public void aTapWithNothingOnScreenSaysSoWithAToast() {
        Settings.SAVE_INSTANTS_BUTTON.save(true);
        HookStatus.clear();
        LinearLayout bar = row();
        InstantsSave.composerCreated(bar.getChildAt(0), null, bar.getChildAt(1));
        InstantsSave.onViewBound(MEDIA);
        View save = bar.findViewWithTag(TAG);
        assertNotNull(save);

        // The viewer closed and the instant on screen is gone.
        InstantsSave.forget();
        save.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(report(), report().contains("nothing on screen to save 1"));
        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
    }

    @Test
    public void theMediaStubAnswersNullUntilThePatchFillsIt() {
        assertNull(InstantsSave.media(new Object()));
    }
}
