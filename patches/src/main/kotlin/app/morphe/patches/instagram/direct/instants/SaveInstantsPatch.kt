/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.direct.messagebox.MessageBoxSite
import app.morphe.patches.instagram.direct.messagebox.boxLookups
import app.morphe.patches.instagram.direct.messagebox.findMessageBox
import app.morphe.patches.instagram.direct.messagebox.messageBoxPatch
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Save button on instants"
internal const val INSTANTS_SAVE = "$EXTENSION_PACKAGE/download/InstantsSave;"
internal const val ON_VIEW = "$INSTANTS_SAVE->onView(Ljava/lang/Object;)V"
internal const val COMPOSER_HOOK =
    "$INSTANTS_SAVE->composerCreated(Landroid/view/View;Landroid/view/View;Landroid/view/View;)V"
internal const val MEDIA_STUB = "$INSTANTS_SAVE->media(Ljava/lang/Object;)Ljava/lang/Object;"

/** The kept class that reads an instant back from the Room cache and builds the item model. */
internal const val ROOM_LOADER =
    "Lcom/instagram/quicksnap/data/persistence/QuickSnapRoomDbAccessor\$loadCachedResponse\$2;"

@Suppress("unused")
val saveInstantsPatch = bytecodePatch(
    name = PATCH,
    description = "Puts a Save button in the reply bar under an instant you're watching. A tap saves that one " +
        "photo or video to your gallery, the way Download saves a reel. Nothing saves unless you tap. Starts off. " +
        "Turn it on in HushGram settings > Messages.",
    default = false,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch, messageBoxPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("saveInstantsButton")
        val model = findInstantModel()
        hookInstantRender(findInstantRender(model))
        placeSaveButton(findMessageBox())
        fillMediaStub(model, findModelMediaField(model))
        enableStatus("saveInstantsButton")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * The instant item model: the class the Room cache's loader [ROOM_LOADER] builds, whose
 * constructor takes Instagram's Media first and the sender's User among its parameters. The
 * loader's kept name anchors the model through Instagram's weekly renames. Nothing here changes
 * any bytecode, so a build where the loader moved stops the patch before any hook goes in.
 */
internal fun BytecodePatchContext.findInstantModel(): String {
    val loader = classDefByOrNull(ROOM_LOADER) ?: refuse("the Room cache loader $ROOM_LOADER is gone")
    val built = loader.methods.flatMap { method ->
        method.implementation?.instructions?.mapNotNull { instruction ->
            // The model's constructor takes sixteen parameters, so it is called in the range form.
            if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) {
                return@mapNotNull null
            }
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
            if (reference.name != "<init>") return@mapNotNull null
            reference.definingClass.toString()
        }.orEmpty()
    }.filter { it != loader.type }
    val withMedia = built.filter { type ->
        classDefByOrNull(type)?.methods?.any { method ->
            method.name == "<init>" && method.parameterTypes.firstOrNull()?.toString() == MEDIA &&
                method.parameterTypes.any { it.toString() == USER }
        } == true
    }.toSet()
    return withMedia.singleOrNull() ?: refuse(
        "expected one instant item model built by $ROOM_LOADER holding a Media and a User, found " +
            if (withMedia.isEmpty()) "none" else withMedia.joinToString(),
    )
}

/**
 * The instant render: the one static method taking [model] as its third parameter among many,
 * which is where the feed hands each instant to be drawn. The render is the media component, told
 * by the Compose source marker naming its file, which Instagram keeps through obfuscation; the
 * attribution composable takes the model too, and only the marker tells them apart.
 */
internal fun BytecodePatchContext.findInstantRender(model: String): Method {
    fun takesThird(method: Method): Boolean {
        if (!AccessFlags.STATIC.isSet(method.accessFlags)) return false
        if (method.parameterTypes.size <= 10 || method.parameterTypes.getOrNull(2)?.toString() != model) return false
        return method.implementation?.instructions?.toList().orEmpty().any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                ?.contains("QuickSnapMediaComponent") == true
        }
    }
    val owners = mutableListOf<Pair<String, Method>>()
    classDefForEach { candidate ->
        candidate.methods.forEach { method -> if (takesThird(method)) owners.add(candidate.type to method) }
    }
    return owners.singleOrNull()?.second ?: refuse(
        "expected one render of the instant item, found " +
            if (owners.isEmpty()) "none" else owners.joinToString { "${it.first}->${it.second.name}" },
    )
}

/**
 * Calls the extension with the item as the render starts, so the instant on screen is always the
 * one the Save button would save. The item is the render's third parameter; nothing before it
 * takes more than one register.
 */
internal fun BytecodePatchContext.hookInstantRender(render: Method) {
    val mutable = mutableClassDefBy(render.definingClass).methods.single {
        it.name == render.name && it.parameterTypes == render.parameterTypes
    }
    val base = mutable.implementation!!.registerCount - mutable.parameterTypes.sumOf {
        if (it == "J" || it == "D") 2 else 1
    }
    val item = base + 2
    val call = if (item <= 15) "invoke-static { v$item }, $ON_VIEW" else "invoke-static/range { v$item .. v$item }, $ON_VIEW"
    mutable.addInstructions(0, call)
}

/**
 * Hands the message box's three buttons to the extension when its composer is made, the way the
 * Message box buttons patch does, so the extension can put the Save button in their row while an
 * instant is on screen. Both hooks load their own registers, so either order of the two patches
 * leaves both calls the views they need.
 */
internal fun BytecodePatchContext.placeSaveButton(site: MessageBoxSite) {
    val constructor = mutableClassDefBy(site.type).methods.single {
        it.name == "<init>" && boxLookups(site.type, it) != null
    } ?: refuse("the message box holder's constructor is gone")
    constructor.addInstructions(
        site.returnAt,
        """
            iget-object v0, p0, ${site.gallery.definingClass}->${site.gallery.name}:${site.gallery.type}
            iget-object v1, p0, ${site.sticker.definingClass}->${site.sticker.name}:${site.sticker.type}
            iget-object p1, p0, ${site.voice.definingClass}->${site.voice.name}:${site.voice.type}
            invoke-static { v0, v1, p1 }, $COMPOSER_HOOK
        """,
    )
}

/**
 * The model's Media: the one field of its own holding Instagram's Media, which the constructor
 * stores its first parameter into.
 */
internal fun BytecodePatchContext.findModelMediaField(model: String): FieldReference {
    val classDef = classDefByOrNull(model) ?: refuse("the instant item model $model is gone")
    val fields = classDef.fields.filter { it.type == MEDIA }
    val field = fields.singleOrNull() ?: refuse(
        "the instant item model holds ${fields.size} Media fields, not one",
    )
    val constructor = classDef.methods.single { it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == MEDIA }
    val stored = constructor.implementation!!.instructions.any { instruction ->
        instruction.opcode == Opcode.IPUT_OBJECT &&
            (instruction as? ReferenceInstruction)?.reference as? FieldReference == field
    }
    if (!stored) refuse("the instant item model never stores its Media field")
    return field
}

/** Fills the extension's media stub: the Media of the item the render handed in. */
internal fun BytecodePatchContext.fillMediaStub(model: String, field: FieldReference) {
    val stub = mutableClassDefBy(INSTANTS_SAVE).methods.single {
        it.name == "media" && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    }
    stub.addInstructions(
        0,
        """
            check-cast p0, $model
            iget-object p0, p0, $model->${field.name}:${field.type}
            return-object p0
        """,
    )
}
