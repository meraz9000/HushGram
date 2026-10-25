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
import app.morphe.patches.instagram.download.story.downloadStoryPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Save button on stories and instants"
internal const val INSTANTS_SAVE = "$EXTENSION_PACKAGE/download/InstantsSave;"
internal const val ON_INSTANT = "$INSTANTS_SAVE->onInstant(Ljava/lang/Object;)V"
internal const val ON_STORY = "$INSTANTS_SAVE->onStory(Ljava/lang/Object;)V"
internal const val WRAP_LONG_PRESS =
    "$INSTANTS_SAVE->wrapLongPress(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val COMPOSER_HOOK =
    "$INSTANTS_SAVE->composerCreated(Landroid/view/View;Landroid/view/View;Landroid/view/View;)V"
internal const val MEDIA_STUB = "$INSTANTS_SAVE->media(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val STORY_MEDIA_STUB = "$INSTANTS_SAVE->storyMedia(Ljava/lang/Object;)Ljava/lang/Object;"
private const val REPLY_BAR_ID_STUB = "$INSTANTS_SAVE->replyBarId()I"

/** The kept class that reads an instant back from the Room cache and builds the item model. */
internal const val ROOM_LOADER =
    "Lcom/instagram/quicksnap/data/persistence/QuickSnapRoomDbAccessor\$loadCachedResponse\$2;"

/** The story viewer's menu helper, kept by its Redex marker, and the story it holds. */
internal const val HELPER_NAME = "ReelOptionsOverflowHelper"
internal const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"

/** Instagram 452's id/instant_reply_bar, the story and instants viewer's reply bar (aapt2). */
internal const val REPLY_BAR_ID = 0x7f0b2161

@Suppress("unused")
val saveInstantsPatch = bytecodePatch(
    name = "Save button on stories and instants",
    description = "Holding an instant saves it, marks it seen, and moves to the next instant; stories keep " +
        "a Save button in the reply bar. " +
        "A save puts that one photo or video in your gallery, the way Download saves a reel. Nothing saves " +
        "unless you hold or tap. Starts off. Turn it on in HushGram settings > Messages.",
    default = false,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch, messageBoxPatch, downloadStoryPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("saveInstantsButton")
        hookStoryBind()
        val model = findInstantModel()
        findInstantRenders(model).forEach { render -> hookInstantRender(render, ON_INSTANT) }
        hookLongPress()
        placeSaveButton(findMessageBox())
        fillMediaStub(model, findModelMediaField(model))
        fillStoryMediaStub()
        fillReplyBarId()
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
 * The instant renders: every static method taking [model] as its third parameter among many whose
 * code carries one of the QuickSnap source markers. The feed's media component draws the
 * viewer's own instants and the archive preview, and the attribution composable draws the line
 * naming who sent the instant the viewer is watching — the consumption surface. Each keeps its
 * Compose source marker through obfuscation, so both are told from any other taker of the model.
 */
internal fun BytecodePatchContext.findInstantRenders(model: String): List<Method> {
    val markers = listOf("QuickSnapMediaComponent", "QuickSnapMediaAttribution")
    fun takesThird(method: Method): Boolean {
        if (!AccessFlags.STATIC.isSet(method.accessFlags)) return false
        if (method.parameterTypes.size <= 10 || method.parameterTypes.getOrNull(2)?.toString() != model) return false
        val strings = method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
        return markers.any { marker -> strings.any { it.contains(marker) } }
    }
    val owners = mutableListOf<Pair<String, Method>>()
    classDefForEach { candidate ->
        candidate.methods.forEach { method -> if (takesThird(method)) owners.add(candidate.type to method) }
    }
    val found = owners.map { it.second }
    if (found.isEmpty()) {
        refuse("expected the instant renders, found none")
    }
    return found
}

/**
 * Calls the extension with the item as the render starts, so the instant on screen is always the
 * one the feature would act on. The item is the render's third parameter; nothing before it takes
 * more than one register.
 */
internal fun BytecodePatchContext.hookInstantRender(render: Method, hook: String = ON_INSTANT) {
    val mutable = mutableClassDefBy(render.definingClass).methods.single {
        it.name == render.name && it.parameterTypes == render.parameterTypes
    }
    val base = mutable.implementation!!.registerCount - mutable.parameterTypes.sumOf {
        if (it.toString() == "J" || it.toString() == "D") 2 else 1
    }
    val item = base + 2
    val call = if (item <= 15) "invoke-static { v$item }, $hook" else "invoke-static/range { v$item .. v$item }, $hook"
    mutable.addInstructions(0, call)
}

/** The Compose pointer handler's synthetic long-press class, whose suffix Compose renumbers. */
internal const val LONG_PRESS_PREFIX =
    "Lcom/instagram/quicksnap/viewer/compose/PointerTouchKt\$handleTapAndLongPressWithRelease$"

/**
 * Wraps the viewer's long-press callback so a held instant saves. Compose keeps the handler's
 * class name literally, and its constructor takes `(Continuation, Function0, Function1,
 * Function1, float, long)`: the second Function1 is the long-press callback. The field it lands in
 * is read from the constructor's own write, so this build's letter is never assumed. The call goes
 * before the constructor returns, after every field is set.
 */
internal fun BytecodePatchContext.hookLongPress() {
    val function1 = "Lkotlin/jvm/functions/Function1;"
    val found = mutableListOf<Triple<ClassDef, Method, FieldReference>>()
    classDefForEach { classDef ->
        if (!classDef.type.startsWith(LONG_PRESS_PREFIX)) return@classDefForEach
        for (method in classDef.methods) {
            if (method.name != "<init>" || method.parameterTypes.size != 6) continue
            val types = method.parameterTypes.map(Any::toString)
            if (types[1] != "Lkotlin/jvm/functions/Function0;" ||
                types[2] != function1 || types[3] != function1 ||
                types[4] != "F" || types[5] != "J") {
                continue
            }
            val mutable = mutableClassDefBy(classDef.type).methods.single {
                it.name == method.name && it.parameterTypes == method.parameterTypes
            }
            val parameterRegisters = 1 + method.parameterTypes.sumOf {
                if (it.toString() == "J" || it.toString() == "D") 2 else 1
            }
            val first = mutable.implementation!!.registerCount - parameterRegisters
            val longPress = mutable.implementation!!.instructions.toList().firstOrNull { instruction ->
                instruction.opcode == Opcode.IPUT_OBJECT &&
                    (instruction as? TwoRegisterInstruction)?.registerA == first + 4 &&
                    ((instruction as? ReferenceInstruction)?.reference as? FieldReference)
                        ?.type?.toString() == function1
            } ?: continue
            found += Triple(classDef, method, (longPress as ReferenceInstruction).reference as FieldReference)
        }
    }
    val (host, method, field) = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one long-press handler, found ${found.size}")
    val mutable = mutableClassDefBy(host.type).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes
    }
    val parameterRegisters = 1 + method.parameterTypes.sumOf {
        if (it.toString() == "J" || it.toString() == "D") 2 else 1
    }
    val first = mutable.implementation!!.registerCount - parameterRegisters
    if (first > 15) {
        throw PatchException("$PATCH: ${host.type} long-press constructor starts at v$first")
    }
    val last = mutable.implementation!!.instructions.toList()
        .indexOfLast { it.opcode == Opcode.RETURN_VOID }
    if (last < 0) throw PatchException("$PATCH: ${host.type} long-press constructor never returns")
    mutable.addInstructions(
        last,
        """
            const-string v0, "${field.name}"
            invoke-static {p0, v0}, $WRAP_LONG_PRESS
        """,
    )
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

/**
 * The story viewer's menu helper, found by the name Redex kept for it in a static field. The
 * helper holds the story or instant on screen, and its builders read that item's Media.
 */
internal fun BytecodePatchContext.findStoryHelper(): ClassDef {
    val helpers = mutableListOf<ClassDef>()
    classDefForEach { classDef -> if (classDef.originalName() == HELPER_NAME) helpers += classDef }
    return helpers.singleOrNull() ?: refuse(
        "expected one class named $HELPER_NAME, found " +
            if (helpers.isEmpty()) "none" else helpers.joinToString { it.type },
    )
}

/**
 * Hands the story viewer's current item to the extension whenever it changes: every write of the
 * story menu helper's story field, the item of the story or instant on screen, calls the
 * extension on its way in. The helper is found by the name Redex kept for it, so the hook
 * survives Instagram's renames.
 */
internal fun BytecodePatchContext.hookStoryBind(hook: String = ON_STORY) {
    val helper = findStoryHelper()
    val storyField = helper.fields.singleOrNull { field ->
        field.type == REEL_ITEM && !AccessFlags.STATIC.isSet(field.accessFlags)
    } ?: refuse("the $HELPER_NAME helper holds no story")

    var hooks = 0
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions ?: return@forEach
            val writes = code.mapIndexedNotNull { index, instruction ->
                if (instruction.opcode != Opcode.IPUT_OBJECT) return@mapIndexedNotNull null
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    ?: return@mapIndexedNotNull null
                if (field.definingClass == helper.type && field.name == storyField.name) index else null
            }
            if (writes.isEmpty()) return@forEach
            val mutable = mutableClassDefBy(classDef.type).methods.single {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            writes.forEach { index ->
                val value = (mutable.implementation!!.instructions[index] as TwoRegisterInstruction).registerA
                val call = if (value <= 15) "invoke-static { v$value }, $hook"
                else "invoke-static/range { v$value .. v$value }, $hook"
                mutable.addInstructions(index + 1, call)
            }
            hooks += writes.size
        }
    }
    if (hooks == 0) refuse("nothing writes the story menu helper's story")
}

/**
 * Fills the extension's story stub: the Media of the story or instant the viewer holds. ReelItem
 * keeps two Media fields; the one that matters is the one the story menu's own builders read, so
 * the helper's reads decide, the way Download any story's do.
 */
internal fun BytecodePatchContext.fillStoryMediaStub() {
    val helper = findStoryHelper()
    val reads = helper.methods.flatMap { method ->
        method.implementation?.instructions?.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
            if (field.definingClass == REEL_ITEM && field.type == MEDIA) field else null
        }.orEmpty()
    }.distinct()
    val field = reads.singleOrNull() ?: refuse(
        "the story menu's builders read ${reads.size} Media fields of $REEL_ITEM, not one: " +
            reads.joinToString { it.name },
    )
    val stub = mutableClassDefBy(INSTANTS_SAVE).methods.single {
        it.name == "storyMedia" && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    }
    stub.addInstructions(
        0,
        """
            check-cast p0, $REEL_ITEM
            iget-object p0, p0, $REEL_ITEM->${field.name}:$MEDIA
            return-object p0
        """,
    )
}

/** Fills the extension's reply-bar id stub with the id aapt2 names on this build. */
internal fun BytecodePatchContext.fillReplyBarId() {
    val stub = mutableClassDefBy(INSTANTS_SAVE).methods.single {
        it.name == "replyBarId" && it.returnType == "I" && it.parameterTypes.isEmpty()
    }
    stub.returnEarly(REPLY_BAR_ID)
}
