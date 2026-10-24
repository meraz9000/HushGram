/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Intent;
import android.provider.MediaStore;
import app.hushgram.extension.shared.SettingsContextRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

/**
 * The gallery picker the Instants camera opens: it asks Android's photo picker for one picture,
 * opens over the app, and closes without taking anything when the pick is cancelled. The handoff
 * answers false until the patch fills it.
 */
@RunWith(RobolectricTestRunner.class)
public class GalleryPickActivityTest {
    @Rule
    public final SettingsContextRule settingsContext = new SettingsContextRule();

    private GalleryPickActivity launch() {
        return Robolectric.buildActivity(GalleryPickActivity.class).setup().get();
    }

    @Test
    public void asksThePhotoPickerForOnePicture() {
        GalleryPickActivity activity = launch();
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("no picker intent", started);
        assertEquals(MediaStore.ACTION_PICK_IMAGES, started.getAction());
        assertEquals("image/*", started.getType());
        activity.finish();
    }

    @Test
    public void aCancelledPickTakesNothingAndCloses() {
        GalleryPickActivity activity = launch();
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        Shadows.shadowOf(activity).receiveResult(started, Activity.RESULT_CANCELED, null);
        assertEquals(true, activity.isFinishing());
    }

    @Test
    public void theHandoffAnswersFalseUntilThePatchFillsIt() {
        assertFalse(InstantsGallery.capture(Robolectric.setupActivity(Activity.class), null));
    }
}
