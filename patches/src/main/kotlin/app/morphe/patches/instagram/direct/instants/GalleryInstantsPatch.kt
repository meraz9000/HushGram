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
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val GALLERY = "$EXTENSION_PACKAGE/download/InstantsGallery;"
internal const val CAMERA_HOOK = "$GALLERY->cameraOpened(Ljava/lang/Object;)V"
internal const val VIDEO_HOOK = "$GALLERY->videoInvoke(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val VIDEO_CALLBACK = "$GALLERY->videoCallback(Ljava/lang/Object;)V"
internal const val PHOTO_CALLBACK = "$GALLERY->photoCallback(Ljava/lang/Object;)V"
/**
 * Adds the transparent photo-picker activity, [GalleryPickActivity], to Instagram's manifest:
 * exportless, on the framework's translucent theme so the system picker opens over the camera.
 *
 * <p>The gallery glyph's patch depends on it. It is idempotent, so a build that somehow already
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
    description = "Puts gallery buttons on the Instants camera. Pick a picture or video from your " +
        "phone. A picture opens in the square Instants viewfinder; a video opens a lossless trim " +
        "screen with start and end handles, or you can send the whole file. Both use the camera's " +
        "own callbacks and need no normal Instants capture first, and both open Instagram's instant " +
        "preview ready to send. Starts off. Turn it on in HushGram settings > Messages.",
    default = false,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch, galleryPickActivityPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("galleryInstants")
        hookCameraOpened()
        val handoff = findCaptureHandoff()
        // The picked picture takes the recorded shutter callback, which exists on every
        // supported build. The older builds also expose the capture call itself, and their
        // publish/replay/file stubs are filled from it; 453's capture call gained a parameter
        // and moved the audience config inside the viewmodel, so those legacy stubs stay
        // unfilled there. They are never reached by the callback path.
        if (handoff.parameterTypes.size == 14) {
            hookStateReaders()
            hookStateAccessor()
            fillContinuationStub(handoff)
            fillPublishStub(handoff)
            fillReplayStub(handoff)
            hookCaptureArgs(handoff)
            val fileHandoff = findFileHandoff()
            fillFileStub(fileHandoff)
            val accessor = stateAccessor
                ?: throw PatchException("Save button on instants: no state accessor")
            fillStateStub(accessor)
            fillStateSourceStub(handoff, accessor)
        }
        val capture = findCaptureCallback()
        hookCaptureCallback(capture)
        hookPhotoFactory(capture)
        val video = findVideoCallback()
        hookVideoCallback(video)
        hookVideoFactory(video)
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
 * context and a Bitmap without a File, whose call the shutter makes. 452 has fourteen
 * parameters; 453 has fifteen (it gained a second long stamp). Its body derives the camera
 * state itself; the pieces a gallery pick has none of are passed empty.
 */
internal fun BytecodePatchContext.findCaptureHandoff(): Method {
    val vm = classDefByOrNull(CAMERA_VM) ?: throw PatchException("Save button on instants: no $CAMERA_VM")
    val handoffs = vm.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.size in 14..15 &&
            method.parameterTypes.getOrNull(0)?.toString() == CONTEXT &&
            method.parameterTypes.getOrNull(1)?.toString() == BITMAP &&
            method.parameterTypes.none { it.toString() == "Ljava/io/File;" }
    }
    return handoffs.singleOrNull() ?: throw PatchException(
        "Save button on instants: expected one capture handoff on $CAMERA_VM, found " +
            if (handoffs.isEmpty()) "none" else handoffs.joinToString { it.name },
    )
}

/**
 * The camera's file-based handoff: the other static method of [CAMERA_VM] taking the context,
 * a Bitmap and a File. 452 has thirteen parameters; 453 has fourteen. A picked picture exists
 * as a file already, so this is the door it should take.
 */
internal fun BytecodePatchContext.findFileHandoff(): Method {
    val vm = classDefByOrNull(CAMERA_VM) ?: throw PatchException("Save button on instants: no $CAMERA_VM")
    val handoffs = vm.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.size in 13..14 &&
            method.parameterTypes.getOrNull(0)?.toString() == CONTEXT &&
            method.parameterTypes.getOrNull(1)?.toString() == BITMAP &&
            method.parameterTypes.any { it.toString() == "Ljava/io/File;" }
    }
    return handoffs.singleOrNull() ?: throw PatchException(
        "Save button on instants: expected one file handoff on $CAMERA_VM, found " +
            if (handoffs.isEmpty()) "none" else handoffs.joinToString { it.name },
    )
}

/** The state accessor found by [hookStateAccessor], kept for the state getter's fill. */
internal var stateAccessor: Method? = null

/**
 * Fills the extension's state getter: the accessor the live camera calls, run on the viewmodel
 * the capture record lent us, so its own initialized state comes back.
 */
internal fun BytecodePatchContext.fillStateStub(accessor: Method) {
    val stub = mutableClassDefBy(GALLERY).methods.single {
        it.name == "stateOf" && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    }
    val descriptor = "(" + accessor.parameterTypes.joinToString("") + ")" + accessor.returnType
    stub.addInstructions(
        0,
        """
            check-cast p0, $CAMERA_VM
            invoke-static {p0}, ${accessor.definingClass}->${accessor.name}$descriptor
            move-result-object v0
            return-object v0
        """,
    )
}

/**
 * Fills the extension's state-source getter: the field A04 reads from the state [A01] returns,
 * found by its type on the state class rather than by guessing its obfuscated name.
 */
internal fun BytecodePatchContext.fillStateSourceStub(handoff: Method, accessor: Method) {
    val stub = mutableClassDefBy(GALLERY).methods.single {
        it.name == "stateSourceOf" && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    }
    val stateType = accessor.returnType.toString()
    val sourceType = handoff.parameterTypes[4].toString()
    val fields = classDefBy(stateType).fields.filter { it.type.toString() == sourceType }
    val field = fields.singleOrNull() ?: throw PatchException(
        "Save button on instants: expected one $sourceType field on $stateType, found " +
            if (fields.isEmpty()) "none" else fields.joinToString { it.name },
    )
    stub.addInstructions(
        0,
        """
            check-cast p0, $stateType
            iget-object v0, p0, $stateType->${field.name}:$sourceType
            return-object v0
        """,
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
internal fun BytecodePatchContext.fillPublishStub(handoff: Method) = fillCaptureCall(handoff, "publish")

/** The replay stub takes the same call's arguments, so it is filled the same way. */
internal fun BytecodePatchContext.fillReplayStub(handoff: Method) = fillCaptureCall(handoff, "replay")

/**
 * Records every real capture call's arguments as it starts: the handoff's own parameters pass
 * through to the extension, which keeps the last set for a picked picture to replay.
 */
internal fun BytecodePatchContext.hookCaptureArgs(handoff: Method) {
    val capture = mutableClassDefBy(handoff.definingClass).methods.single {
        it.name == handoff.name && it.parameterTypes == handoff.parameterTypes
    }
    // The extension's own descriptor: its parameters are Objects where the handoff's are
    // Instagram's own types, and the call has to name the method that exists.
    val call = "$GALLERY->captureArgs" +
        "(Landroid/content/Context;Landroid/graphics/Bitmap;Ljava/lang/Object;Ljava/lang/Object;" +
        "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Long;Ljava/lang/String;Ljava/lang/String;" +
        "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;JZ)V"
    capture.addInstructions(0, "invoke-static/range {p0 .. p14}, $call")
}

/**
 * The camera's capture callback: the eight-argument `invoke` whose body builds the
 * `$onCaptured$3` state machine. It is the path a shutter press takes; a picked picture can run
 * it with only its first two arguments replaced, so Instagram builds the options and state.
 */
internal fun BytecodePatchContext.findCaptureCallback(): Method {
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.name == "invoke" &&
                method.parameterTypes.size == 8 &&
                method.parameterTypes.all { it.toString() == "Ljava/lang/Object;" } &&
                method.returnType == "Ljava/lang/Object;" &&
                method.implementation?.instructions?.toList().orEmpty().any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    reference is TypeReference &&
                        reference.type ==
                        "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel\$onCaptured\$3;"
                } == true
            ) {
                found += method
            }
        }
    }
    return found.singleOrNull() ?: throw PatchException(
        "Save button on instants: expected one capture callback, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->invoke" },
    )
}

/**
 * Records the capture callback's instance and arguments at its entry. The extension replays it
 * with the picked picture, so Instagram's own pipeline builds the options and state.
 */
internal fun BytecodePatchContext.hookCaptureCallback(callback: Method) {
    val mutable = mutableClassDefBy(callback.definingClass).methods.single {
        it.name == callback.name && it.parameterTypes == callback.parameterTypes
    }
    val call = "$GALLERY->captureInvoke" +
        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;" +
        "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
    mutable.addInstructions(0, "invoke-static/range {p0 .. p8}, $call")
}

/**
 * Keeps the camera's photo lambda when Compose builds it, before any shutter press. The lambda
 * closes over the camera's context, viewmodel, and still pipeline, so replaying it with a picked
 * picture and synthesized crop/UI arguments avoids requiring a seed photo first.
 */
internal fun BytecodePatchContext.hookPhotoFactory(callback: Method) {
    val mutable = mutableClassDefBy(callback.definingClass).methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.size == 3 &&
            it.parameterTypes[0].toString() == "Landroid/content/Context;"
    } ?: throw PatchException(
        "Add photos from the gallery to instants: ${callback.definingClass} has no photo factory",
    )
    val code = mutable.implementation!!.instructions.toList()
    val at = code.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    if (at < 0) throw PatchException(
        "Add photos from the gallery to instants: ${callback.definingClass} factory never returns",
    )
    mutable.addInstructions(
        at,
        """
            move-object/from16 v0, p0
            invoke-static {v0}, $PHOTO_CALLBACK
        """,
    )
}

/**
 * The camera's recorded-video callback: the one-argument [Function1.invoke] whose body builds
 * the `$onVideoRecorded$3` state machine. It captures the live camera context, viewmodel, and
 * recording controllers, and takes the finished [java.io.File]; replaying it with a picked
 * video runs Instagram's own compose/upload pipeline.
 */
internal fun BytecodePatchContext.findVideoCallback(): Method {
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.name == "invoke" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].toString() == "Ljava/lang/Object;" &&
                method.returnType == "Ljava/lang/Object;" &&
                method.implementation?.instructions?.toList().orEmpty().any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    reference is TypeReference &&
                        reference.type ==
                        "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel\$onVideoRecorded\$3;"
                } == true
            ) {
                found += method
            }
        }
    }
    return found.singleOrNull() ?: throw PatchException(
        "Add photos from the gallery to instants: expected one video callback, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->invoke" },
    )
}

/**
 * Records the video callback's instance and file at its entry. The extension replays it with
 * the picked video, so Instagram's own compose and upload path is used.
 */
internal fun BytecodePatchContext.hookVideoCallback(callback: Method) {
    val mutable = mutableClassDefBy(callback.definingClass).methods.single {
        it.name == callback.name && it.parameterTypes == callback.parameterTypes
    }
    mutable.addInstructions(0, "invoke-static/range {p0 .. p1}, $VIDEO_HOOK")
}

/**
 * Keeps the camera's video lambda when Compose builds it, before any video is recorded. The
 * lambda closes over the camera's context, viewmodel, and recording controllers, so replaying
 * it with a picked file is enough; no normal video needs to be recorded first. The class is a
 * shared lambda type, so [InstantsGallery.videoCallback] filters on the variant and fields.
 */
internal fun BytecodePatchContext.hookVideoFactory(callback: Method) {
    val mutable = mutableClassDefBy(callback.definingClass).methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.size == 5 &&
            it.parameterTypes[0].toString() == "I"
    } ?: throw PatchException(
        "Add photos from the gallery to instants: ${callback.definingClass} has no video factory",
    )
    val code = mutable.implementation!!.instructions.toList()
    val at = code.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    if (at < 0) throw PatchException(
        "Add photos from the gallery to instants: ${callback.definingClass} factory never returns",
    )
    mutable.addInstructions(
        at,
        """
            move-object/from16 v0, p0
            invoke-static {v0}, $VIDEO_CALLBACK
        """,
    )
}

/**
 * Fills the extension's file stub: the camera's file-based handoff with the picked picture's
 * file, the viewmodel's own state from [stateOf], and the empty pieces only a camera session
 * carries. The stub's parameters mirror the call's, so the body is check-casts and one invoke.
 */
internal fun BytecodePatchContext.fillFileStub(handoff: Method) {
    val stub = mutableClassDefBy(GALLERY).methods.single {
        it.name == "publishFile" && it.returnType == "Z" && it.parameterTypes.size == 13
    }
    val types = handoff.parameterTypes.map(Any::toString)
    val descriptor = "(" + handoff.parameterTypes.joinToString("") + ")" + handoff.returnType
    val call = "${handoff.definingClass}->${handoff.name}$descriptor"
    fun cast(register: Int) = "check-cast p$register, ${types[register]}"
    stub.addInstructions(
        0,
        """
            ${cast(2)}
            ${cast(3)}
            ${cast(4)}
            ${cast(5)}
            ${cast(7)}
            invoke-static/range {p0 .. p13}, $call
            const/4 v0, 0x1
            return v0
        """,
    )
}

private fun BytecodePatchContext.fillCaptureCall(handoff: Method, stubName: String) {
    val classDef = mutableClassDefBy(GALLERY)
    val original = classDef.methods.single {
        it.name == stubName && it.returnType == "Z" && it.parameterTypes.size == 14
    }
    // The clone copies every parameter into a low register, and those copies must land below
    // the new parameter block. Fewer than fifteen spare registers put the copies over p0..p4:
    // the viewmodel was replaced by the high half of the long and A04 read a field off null.
    // The call then passes the low copies, with only slots 2 and 5 replaced; moves whose
    // destination is a parameter alias are dropped, so the substitutes are true locals.
    val stub = original.cloneMutable(additionalRegisters = 30)
    classDef.methods.apply {
        remove(original)
        add(stub)
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
    // The objects are built in the spare low registers, and only the options and config slots
    // are replaced; every other argument stays the parameter the extension handed over.
    stub.addInstructions(
        stub.parameterTypes.size,
        """
            new-instance v15, $options
            invoke-direct {v15}, $options-><init>()V
            new-instance v16, $config
            sget-object v17, ${followerOnly.definingClass}->${followerOnly.name}:${followerOnly.type}
            const/16 v18, 0x0
            const/16 v19, 0x0
            const/16 v20, 0x0
            const/16 v21, 0x0
            const/16 v22, 0x0
            const/16 v23, 0x0
            const/16 v24, 0x0
            invoke-direct/range {v16 .. v24}, ${configCtor.definingClass}-><init>$configDescriptor
            move-object/from16 v2, v15
            move-object/from16 v5, v16
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
    stateAccessor = accessor
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
