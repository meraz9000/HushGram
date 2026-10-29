/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SystemDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gallery handoff: the camera viewmodel's fifteen-parameter capture call is found and the
 * shutter callback is recorded. On 453 the legacy fourteen-parameter publish stubs cannot be
 * filled, so the gallery replays that recorded callback instead.
 */
class GalleryInstantsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(GALLERY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(CAMERA_HOOK, VIDEO_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        assertTrue("publish is not in the extension: $declared", declared.any { it.startsWith("publish(") })
        assertTrue("captureVideo is not in the extension: $declared",
            declared.any { it.startsWith("captureVideo(") })
        assertTrue("hasVideoCapture is not in the extension: $declared",
            declared.any { it.startsWith("hasVideoCapture(") })
    }

    @Test
    fun theDeclaredBuildCarriesTheHandoffAndTheCameraRender() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun check(bundle: File, label: String) {
        val context = PatchContexts.of(candidates(bundle) + ExtensionDex.classDef(GALLERY))
        val handoff = context.findCaptureHandoff()
        assertEquals("$label: the handoff's width", 15, handoff.parameterTypes.size)
        assertEquals("$label: the wrong handoff was found", "A06", handoff.name)
        context.hookCameraOpened()
        val callback = context.findCaptureCallback()
        context.hookCaptureCallback(callback)
        assertTrue(
            "$label: the capture callback isn't recorded",
            context.mutableClassDefBy(callback.definingClass).methods.single {
                it.name == callback.name && it.parameterTypes == callback.parameterTypes
            }.implementation!!.instructions.first().let {
                (it as? ReferenceInstruction)?.reference.toString().orEmpty()
                    .contains("->captureInvoke(")
            },
        )

        val video = context.findVideoCallback()
        context.hookVideoCallback(video)
        assertTrue(
            "$label: the video callback isn't recorded",
            context.mutableClassDefBy(video.definingClass).methods.single {
                it.name == video.name && it.parameterTypes == video.parameterTypes
            }.implementation!!.instructions.first().let {
                (it as? ReferenceInstruction)?.reference.toString().orEmpty()
                    .contains("->videoInvoke(")
            },
        )
    }

    /**
     * Everything the discoveries read: the viewmodel, its callers, and the classes the handoff's
     * own types name, expanded over referenced types until nothing new arrives.
     */
    private fun candidates(bundle: File): List<ClassDef> {
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                val keep = candidate.type == CAMERA_VM ||
                    candidate.fields.any { it.type.toString() == CAMERA_VM } ||
                    candidate.methods.any { method ->
                        (AccessFlags.STATIC.isSet(method.accessFlags) &&
                            method.parameterTypes.any { it.toString() == CAMERA_VM }) ||
                            (method.name == "invoke" && method.parameterTypes.size == 1 &&
                                method.returnType == "Ljava/lang/Object;" &&
                                method.implementation?.instructions?.toList().orEmpty().any { instruction ->
                                    val reference = (instruction as? ReferenceInstruction)?.reference
                                    reference is com.android.tools.smali.dexlib2.iface.reference.TypeReference &&
                                        reference.type ==
                                        "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel\$onVideoRecorded\$3;"
                                })
                    }
                if (keep) kept += candidate
            }
        }
        repeat(3) {
            val named = kept.flatMap { candidate ->
                candidate.methods.flatMap { method ->
                    method.parameterTypes.map(Any::toString) + method.returnType.toString()
                } + candidate.fields.map { it.type } + candidate.interfaces
            }.toSet()
            FixtureDex.forEach(bundle) { dex ->
                for (candidate in dex.classes) {
                    if (candidate.type in named && kept.none { it.type == candidate.type }) kept += candidate
                }
            }
        }
        return kept
    }
}
