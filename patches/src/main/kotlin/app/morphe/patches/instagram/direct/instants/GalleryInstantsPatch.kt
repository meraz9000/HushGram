/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PICK_ACTIVITY = "app.hushgram.extension.instagram.download.GalleryPickActivity"

/** A framework theme, so the picker needs no resource of its own. */
private const val TRANSPARENT_THEME = "@android:style/Theme.Translucent.NoTitleBar"

/**
 * Adds the transparent photo-picker activity, [GalleryPickActivity], to Instagram's manifest:
 * exportless, on the framework's translucent theme so the system picker opens over the camera.
 *
 * <p>Not yet depended on by a named patch. The gallery glyph's patch arrives with the capture
 * bridge, and the catalog's reviewed decisions must not name a patch that does nothing; until
 * then this resource patch stays unreferenced. It is idempotent, so a build that somehow already
 * declares the activity keeps one entry.
 */
internal val galleryPickActivityPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use(::addPickActivity)
    }
}

/** Appends the picker's entry unless the manifest already has one. */
internal fun addPickActivity(manifest: Document) {
    val root = manifest.documentElement
        ?: throw PatchException("AndroidManifest.xml has no manifest element")
    val application = (0 until root.getElementsByTagName("application").length)
        .map { root.getElementsByTagName("application").item(it) as Element }
        .singleOrNull()
        ?: throw PatchException("AndroidManifest.xml has no single application element")
    val declared = (0 until application.getElementsByTagName("activity").length)
        .map { application.getElementsByTagName("activity").item(it) as Element }
        .any { it.getAttribute("android:name") == PICK_ACTIVITY }
    if (declared) {
        return
    }
    val activity = manifest.createElement("activity")
    activity.setAttribute("android:name", PICK_ACTIVITY)
    activity.setAttribute("android:exported", "false")
    activity.setAttribute("android:theme", TRANSPARENT_THEME)
    application.appendChild(activity)
}
