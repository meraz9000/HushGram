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
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gallery handoff: the camera viewmodel's fourteen-parameter capture call is found, the
 * extension's publish stub takes its invocations with the picked Bitmap, and the camera screen
 * render hands the viewmodel over on the declared build.
 */
class GalleryInstantsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(GALLERY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(CAMERA_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        assertTrue("publish is not in the extension: $declared", declared.any { it.startsWith("publish(") })
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
        assertEquals("$label: the handoff's width", 14, handoff.parameterTypes.size)
        context.fillPublishStub(handoff)
        context.hookCameraOpened()

        val stub = context.mutableClassDefBy(GALLERY).methods.single { it.name == "publish" }
        val code = stub.implementation!!.instructions.toList()
        assertTrue(
            "$label: the publish stub doesn't call the handoff",
            code.any { (it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                ?.reference.toString().orEmpty().contains("->${handoff.name}(") },
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
                    candidate.methods.any { method ->
                        AccessFlags.STATIC.isSet(method.accessFlags) &&
                            method.parameterTypes.any { it.toString() == CAMERA_VM }
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
