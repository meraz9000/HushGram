/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val GALLERY = "$EXTENSION_PACKAGE/download/InstantsGallery;"
internal const val CAMERA_HOOK = "$GALLERY->cameraOpened(Ljava/lang/Object;)V"
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

/** The switch the settings page shows, the family the diagnostics report counts. */
internal const val GALLERY_PATCH = "Add photos from the gallery to instants"

@Suppress("unused")
val galleryInstantsPatch = bytecodePatch(
    name = "Add photos from the gallery to instants",
    description = "Puts a gallery button on the Instants camera. Pick a photo and Instagram opens the instant " +
        "preview with it, ready to send, the same pipeline a shutter press uses. Starts off. Turn it on in " +
        "HushGram settings > Messages.",
    default = false,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch, galleryPickActivityPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("galleryInstants")
        hookCameraOpened()
        hookStateReaders()
        hookStateAccessor()
        val handoff = findCaptureHandoff()
        fillContinuationStub(handoff)
        fillPublishStub(handoff)
        enableStatus("galleryInstants")
    }
}

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
 * Fills the extension's continuation-interface stub with the tenth parameter's dex name, the
 * interface Instagram's own build gives the capture call for a continuation.
 */
internal fun BytecodePatchContext.fillContinuationStub(handoff: Method) {
    val name = handoff.parameterTypes[9].toString()
    if (!name.startsWith("L") || !name.endsWith(";")) {
        throw PatchException("Save button on instants: the handoff's tenth parameter $name isn't a type")
    }
    val stub = mutableClassDefBy(GALLERY).methods.single {
        it.name == "continuationInterface" && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty()
    }
    stub.returnEarly(name)
}

/**
 * The enum constant a class keeps for [label]: the static field whose initialiser in the class's
 * static block sits right after the string Instagram builds the constant with. Enum fields are
 * obfuscated; the strings in their initialisers are not.
 */
internal fun BytecodePatchContext.audienceDefault(type: String, label: String): FieldReference {
    val classDef = classDefByOrNull(type)
        ?: throw PatchException("Save button on instants: this build has no $type")
    val clinit = classDef.methods.firstOrNull { it.name == "<clinit>" }
        ?: throw PatchException("Save button on instants: $type has no static block")
    val code = clinit.implementation?.instructions?.toList().orEmpty()
    val stringAt = code.indexOfFirst { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == label
    }
    if (stringAt < 0) throw PatchException("Save button on instants: $type has no \"$label\" constant")
    val store = (stringAt until minOf(code.size, stringAt + 12)).firstNotNullOfOrNull { index ->
        if (code[index].opcode != Opcode.SPUT_OBJECT) return@firstNotNullOfOrNull null
        (code[index] as? ReferenceInstruction)?.reference as? FieldReference
    } ?: throw PatchException("Save button on instants: $type never stores its \"$label\" constant")
    return store
}

/**
 * Fills the extension's publish stub: the camera viewmodel's own capture call with the picked
 * Bitmap where the camera's frame goes, a bare options object with no effect chosen, and the
 * audience config defaulted to followers. The camera state is derived inside the call itself.
 * The command pieces the shutter would supply (a capture stamp and mentions) are passed empty.
 */
internal fun BytecodePatchContext.fillPublishStub(handoff: Method) {
    val stub = mutableClassDefBy(GALLERY).methods.single {
        it.name == "publish" && it.returnType == "Z" && it.parameterTypes.size == 14
    }
    val types = handoff.parameterTypes.map(Any::toString)
    val options = types[2]
    val config = types[5]
    val configCtor = classDefByOrNull(config)?.methods?.singleOrNull { it.name == "<init>" }
        ?: throw PatchException("Save button on instants: $config has no single constructor")
    val audience = configCtor.parameterTypes[1].toString()
    val followerOnly = audienceDefault(audience, "followers_only")
    val configDescriptor = "(" + configCtor.parameterTypes.joinToString("") + ")V"
    val descriptor = "(" + handoff.parameterTypes.joinToString("") + ")" + handoff.returnType
    val call = "${handoff.definingClass}->${handoff.name}$descriptor"
    stub.addInstructions(
        0,
        """
            new-instance v2, $options
            invoke-direct {v2}, $options-><init>()V
            new-instance v3, $config
            sget-object v4, ${followerOnly.definingClass}->${followerOnly.name}:${followerOnly.type}
            const/4 v5, 0x0
            const/4 v6, 0x0
            const/4 v7, 0x0
            const/4 v8, 0x0
            const/4 v9, 0x0
            const/4 v10, 0x0
            const/4 v11, 0x0
            invoke-direct/range {v3 .. v11}, ${configCtor.definingClass}-><init>$configDescriptor
            move-result-object v5
            move-object v0, p0
            move-object v1, p2
            move-object v3, p1
            const/4 v4, 0x0
            const/4 v6, 0x0
            const-string v7, ""
            const-string v8, ""
            move-object v9, p9
            const/4 v10, 0x0
            const/4 v11, 0x0
            const-wide/16 v12, 0x0
            const/4 v14, 0x0
            invoke-static/range {v0 .. v14}, $call
            const/4 v0, 0x1
            return v0
        """,
    )
}

/**
 * Every static accessor of the viewmodel that takes only the viewmodel: the state readers the
 * live camera screen calls as it composes and runs. Each hands its own viewmodel to the
 * extension at its first instruction, so the instance kept is the one the app itself uses,
 * whose internal state is initialized — the screen's render arguments can be an earlier copy.
 */
internal fun BytecodePatchContext.hookStateReaders() {
    var hooked = 0
    classDefForEach { candidate ->
        candidate.methods.forEach { method ->
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@forEach
            if (method.parameterTypes.map(Any::toString) != listOf(CAMERA_VM)) return@forEach
            val mutable = mutableClassDefBy(candidate.type).methods.single {
                it.name == method.name && it.parameterTypes == method.parameterTypes
            }
            val words = mutable.parameterTypes.size
            val base = mutable.implementation!!.registerCount - words
            val register = base
            val call = if (register <= 15) "invoke-static { v$register }, $CAMERA_HOOK"
            else "invoke-static/range { v$register .. v$register }, $CAMERA_HOOK"
            mutable.addInstructions(0, call)
            hooked++
        }
    }
    if (hooked == 0) {
        throw PatchException("Save button on instants: expected viewmodel accessors, found none")
    }
}

/**
 * The state accessor: the one static method taking the viewmodel that reads a field whose type
 * answers a zero-argument `getValue` and answers that value. The live camera calls it with the
 * instance it actually uses, so its own viewmodel is the one handed to the extension — the
 * screen's render arguments can be instances whose state was never initialized.
 */
internal fun BytecodePatchContext.hookStateAccessor() {
    val vm = classDefByOrNull(CAMERA_VM) ?: throw PatchException("Save button on instants: no $CAMERA_VM")
    val accessors = vm.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(Any::toString) == listOf(CAMERA_VM) &&
            method.returnType.startsWith("L") &&
            method.implementation?.instructions?.toList().orEmpty().let { code ->
                val readsField = code.any { instruction ->
                    instruction.opcode == Opcode.IGET_OBJECT &&
                        ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.type
                            ?.toString()?.startsWith("L") == true &&
                        classDefByOrNull(((instruction as ReferenceInstruction).reference as FieldReference).type.toString())
                            ?.methods?.any { it.name == "getValue" && it.parameterTypes.isEmpty() } == true
                }
                readsField && code.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getValue"
                }
            }
    }
    val accessor = accessors.singleOrNull() ?: throw PatchException(
        "Save button on instants: expected one viewmodel state accessor, found " +
            if (accessors.isEmpty()) "none" else accessors.joinToString { it.name },
    )
    val mutable = mutableClassDefBy(accessor.definingClass).methods.single {
        it.name == accessor.name && it.parameterTypes == accessor.parameterTypes
    }
    val register = mutable.implementation!!.registerCount - 1
    val call = if (register <= 15) "invoke-static { v$register }, $CAMERA_HOOK"
    else "invoke-static/range { v$register .. v$register }, $CAMERA_HOOK"
    mutable.addInstructions(0, call)
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
        val words = mutable.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        val base = mutable.implementation!!.registerCount - words
        val index = mutable.parameterTypes.indexOfFirst { it.toString() == CAMERA_VM }
        val register = base + mutable.parameterTypes.take(index).sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        val call = if (register <= 15) "invoke-static { v$register }, $CAMERA_HOOK"
        else "invoke-static/range { v$register .. v$register }, $CAMERA_HOOK"
        mutable.addInstructions(0, call)
    }
}
