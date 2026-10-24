/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val GALLERY = "$EXTENSION_PACKAGE/download/InstantsGallery;"
internal const val CAMERA_HOOK = "$GALLERY->cameraOpened(Ljava/lang/Object;)V"
/** The kept camera viewmodel whose capture call the gallery pick reuses. */
internal const val CAMERA_VM = "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel;"
private const val CONTEXT = "Landroid/content/Context;"
private const val BITMAP = "Landroid/graphics/Bitmap;"
private const val FIRE_AND_FORGET = "$EXTENSION_PACKAGE/download/FireAndForget;"

/**
 * The camera's captured-picture handoff: the one static method of [CAMERA_VM] taking the
 * context and a Bitmap whose call the shutter makes, fourteen parameters wide. Its body derives
 * the camera state itself; the pieces it is given that a gallery pick has none of are passed
 * empty, which the experiment on the device checks.
 */
internal fun BytecodePatchContext.findCaptureHandoff(): Method {
    val vm = classDefByOrNull(CAMERA_VM) ?: throw PatchException("Save button on instants: no $CAMERA_VM")
    val handoffs = vm.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.size == 14 &&
            method.parameterTypes.getOrNull(0)?.toString() == CONTEXT &&
            method.parameterTypes.getOrNull(1)?.toString() == BITMAP
    }
    return handoffs.singleOrNull() ?: throw PatchException(
        "Save button on instants: expected one capture handoff on $CAMERA_VM, found " +
            if (handoffs.isEmpty()) "none" else handoffs.joinToString { it.name },
    )
}

/**
 * Fills the extension's publish stub: the camera viewmodel's own capture call with the picked
 * Bitmap where the camera's frame goes, its camera state derived inside that call, and the
 * pieces only a real shutter press has (options, effects, mentions) passed empty. The stub's
 * parameters mirror the call's, so the body is check-casts onto them and one ranged invoke.
 */
internal fun BytecodePatchContext.fillPublishStub(handoff: Method) {
    val stub = mutableClassDefBy(GALLERY).methods.single {
        it.name == "publish" && it.returnType == "Z" && it.parameterTypes.size == 14
    }
    val types = handoff.parameterTypes.map(Any::toString)
    val descriptor = "(" + handoff.parameterTypes.joinToString("") + ")" + handoff.returnType
    val call = "${handoff.definingClass}->${handoff.name}$descriptor"
    fun cast(register: Int, index: Int) = "check-cast p$register, ${types[index]}"
    stub.addInstructions(
        0,
        """
            ${cast(2, 2)}
            ${cast(4, 4)}
            ${cast(5, 5)}
            ${cast(10, 10)}
            ${cast(11, 11)}
            invoke-static/range {p0 .. p14}, $call
            const/4 v0, 0x1
            return v0
        """,
    )
}

/**
 * The camera screen's renders: every static method taking the viewmodel whose code carries the
 * `QuickSnapCameraScreen.kt` Compose source marker. The screen and the pieces it hands the
 * viewmodel to all report it; each hands the viewmodel to the extension at its first
 * instruction, and the extension keeps whichever came last, so every one of them is hooked.
 */
internal fun BytecodePatchContext.hookCameraOpened() {
    val renders = mutableListOf<Method>()
    classDefForEach { candidate ->
        candidate.methods.forEach { method ->
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@forEach
            if (method.parameterTypes.none { it.toString() == CAMERA_VM }) return@forEach
            val marked = method.implementation?.instructions?.toList().orEmpty().any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    ?.contains("QuickSnapCameraScreen.kt") == true
            }
            if (marked) renders.add(method)
        }
    }
    if (renders.isEmpty()) {
        throw PatchException("Save button on instants: expected camera screen renders, found none")
    }
    renders.forEach { render ->
        val mutable = mutableClassDefBy(render.definingClass).methods.single {
            it.name == render.name && it.parameterTypes == render.parameterTypes
        }
        val words = mutable.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val base = mutable.implementation!!.registerCount - words
        val index = mutable.parameterTypes.indexOfFirst { it.toString() == CAMERA_VM }
        val register = base + mutable.parameterTypes.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }
        val call = if (register <= 15) "invoke-static { v$register }, $CAMERA_HOOK"
        else "invoke-static/range { v$register .. v$register }, $CAMERA_HOOK"
        mutable.addInstructions(0, call)
    }
}

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

private const val PICK_ACTIVITY = "app.hushgram.extension.instagram.download.GalleryPickActivity"

/** A framework theme, so the picker needs no resource of its own. */
private const val TRANSPARENT_THEME = "@android:style/Theme.Translucent.NoTitleBar"

/** Appends the picker's entry unless the manifest already has one. */
internal fun addPickActivity(manifest: org.w3c.dom.Document) {
    val root = manifest.documentElement
        ?: throw PatchException("AndroidManifest.xml has no manifest element")
    val application = (0 until root.getElementsByTagName("application").length)
        .map { root.getElementsByTagName("application").item(it) as org.w3c.dom.Element }
        .singleOrNull()
        ?: throw PatchException("AndroidManifest.xml has no single application element")
    val declared = (0 until application.getElementsByTagName("activity").length)
        .map { application.getElementsByTagName("activity").item(it) as org.w3c.dom.Element }
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
