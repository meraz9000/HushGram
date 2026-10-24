/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.messagebox.BOX_BUTTON_IDS
import app.morphe.patches.instagram.direct.messagebox.BOX_BUTTON_NAMES
import app.morphe.patches.instagram.direct.messagebox.CREATED_HOOK
import app.morphe.patches.instagram.direct.messagebox.findMessageBox
import app.morphe.patches.instagram.direct.messagebox.hideMessageBoxButtons
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Save button on instants: the instant render hands the item model to the extension as it
 * starts, the message box composer hands its three buttons over as it's made, and the extension's
 * media stub reads the model's Media. Checked on the declared build and every other build of the
 * version, with a stand-in refusal, and the extension's own hooks declared.
 */
class SaveInstantsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(INSTANTS_SAVE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(ON_VIEW, COMPOSER_HOOK, MEDIA_STUB)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun withoutTheRoomLoaderThePatchRefuses() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(ExtensionDex.classDef(INSTANTS_SAVE))).findInstantModel()
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("Room cache loader"))
    }

    @Test
    fun theWholePatchHooksTheRenderAndTheComposerOnTheDeclaredBuild() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                build(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun theOtherBuildsHookTheSameWay() {
        for (base in Fixtures.otherBuilds()) build(base, base.parentFile.name)
    }

    private fun Instruction.reference(): String? =
        (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * The classes the patch reads on a build: the Room loader, the item models it builds, the
     * render candidates that take one third, the story menu helper and the classes that write its
     * story, and the message box holder's own ingredients.
     */
    private fun candidates(bundle: File): List<ClassDef> {
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                val marker = candidate.staticFields.any { field ->
                    field.name == "__redex_internal_original_name" &&
                        (field.initialValue as? StringEncodedValue)?.value == HELPER_NAME
                }
                val writesAReelItem = candidate.methods.any { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.IPUT_OBJECT &&
                            ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.type == REEL_ITEM
                    } == true
                }
                val keep = candidate.type == ROOM_LOADER || marker || writesAReelItem ||
                    candidate.type == REEL_ITEM ||
                    candidate.methods.any { method ->
                        method.name == "<init>" && method.parameterTypes.firstOrNull()?.toString() == MEDIA &&
                            method.parameterTypes.any { it.toString() == USER }
                    } ||
                    candidate.methods.any { method ->
                        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size > 10 &&
                            method.parameterTypes.getOrNull(2)?.toString()?.startsWith("LX/") == true
                    } ||
                    candidate.methods.any { method ->
                        val code = method.implementation?.instructions?.toList().orEmpty()
                        method.name == "<clinit>" && code.mapNotNull { instruction ->
                            (instruction as? ReferenceInstruction)?.reference as? StringReference
                        }.map { it.string }.containsAll(BOX_BUTTON_NAMES)
                    } ||
                    candidate.methods.any { method ->
                        method.implementation?.instructions?.toList().orEmpty().any {
                            (it as? NarrowLiteralInstruction)?.narrowLiteral in BOX_BUTTON_IDS
                        }
                    }
                if (keep) kept += candidate
            }
        }
        return kept
    }

    private fun build(bundle: File, label: String) {
        val context = PatchContexts.of(
            candidates(bundle) + ExtensionDex.classDef(INSTANTS_SAVE) + ExtensionDex.classDef(SETTINGS_STATUS),
        )
        val model = context.findInstantModel()
        val renders = context.findInstantRenders(model)
        assertEquals("$label: the media component and the attribution renders", 2, renders.size)
        val site = context.findMessageBox()
        context.hideMessageBoxButtons(site)
        context.hookStoryBind()
        renders.forEach { render -> context.hookInstantRender(render) }
        context.placeSaveButton(site)
        context.fillMediaStub(model, context.findModelMediaField(model))
        context.fillStoryMediaStub()
        context.fillReplyBarId()
        context.enableStatus("saveInstantsButton")

        // Every render calls the extension with the item exactly once, first of all.
        for (render in renders) {
            val hooked = context.mutableClassDefBy(render.definingClass).methods.single {
                it.name == render.name && it.parameterTypes == render.parameterTypes
            }
            val code = hooked.implementation!!.instructions.toList()
            assertEquals("$label: the render's bind isn't the first instruction", ON_VIEW, code.first().reference())
            assertEquals(1, code.count { it.reference() == ON_VIEW })
        }

        // The composer hands its three views over beside the message box's own hook.
        val constructor = context.mutableClassDefBy(site.type).methods.single { it.name == "<init>" }
        val constructorCode = constructor.implementation!!.instructions.toList()
        assertEquals(1, constructorCode.count { it.reference() == COMPOSER_HOOK })
        assertEquals(1, constructorCode.count { it.reference() == CREATED_HOOK })

        // The story stub reads the item's Media, and the reply-bar id is the patch's. A bind that
        // hooked nothing would have refused before this line.
        val storyStub = context.mutableClassDefBy(INSTANTS_SAVE).methods.single { it.name == "storyMedia" }
        assertEquals("$label: the story stub doesn't read the item", REEL_ITEM, storyStub.implementation!!.instructions.first().reference())
        val barId = context.mutableClassDefBy(INSTANTS_SAVE).methods.single { it.name == "replyBarId" }
        assertTrue(
            "$label: the reply bar id isn't the patch's",
            barId.implementation!!.instructions.any {
                (it as? NarrowLiteralInstruction)?.narrowLiteral == REPLY_BAR_ID
            },
        )

        // The media stub's first instruction reads the model.
        val stub = context.mutableClassDefBy(INSTANTS_SAVE).methods.single {
            it.name == "media" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
        }
        assertEquals("$label: the media stub doesn't read the model", model, stub.implementation!!.instructions.first().reference())

        // The status answers that the family is in the build.
        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "saveInstantsButton" }
        val statusCode = status.implementation!!.instructions.toList()
        assertTrue(
            "$label: the status isn't turned on: " +
                statusCode.joinToString(" ") { "${it.opcode.name}(${(it as? OneRegisterInstruction)?.registerA})" },
            statusCode.first().opcode == Opcode.CONST_4 &&
                (statusCode.first() as? NarrowLiteralInstruction)?.narrowLiteral == 1,
        )
    }
}
