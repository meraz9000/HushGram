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
        context.fillReplayStub(handoff)
        context.hookCaptureArgs(handoff)
        context.hookCameraOpened()
        context.hookStateAccessor()
        val accessor = stateAccessor ?: throw AssertionError("$label: no state accessor")
        context.fillStateStub(accessor)
        context.fillStateSourceStub(handoff, accessor)
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

        for (stubName in listOf("publish", "replay")) {
            val stub = context.mutableClassDefBy(GALLERY).methods.single { it.name == stubName }
            val code = stub.implementation!!.instructions.toList()
            assertTrue(
                "$label: the $stubName stub doesn't call the handoff",
                code.any { (it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                    ?.reference.toString().orEmpty().contains("->${handoff.name}(") },
            )
            // The stub's own parameters are the handoff's arguments, so the call has to start
            // at the parameter block and pass all fifteen words through. The only slots the
            // fill replaces are the options and the config. Building those in a parameter
            // register once left the config in the viewmodel slot: copying a parameter into
            // its own register is a no-op.
            val base = stub.implementation!!.registerCount - 15
            val call = code.filterIsInstance<RegisterRangeInstruction>().single { instruction ->
                ((instruction as? ReferenceInstruction)?.reference?.toString().orEmpty())
                    .contains("->${handoff.name}(")
            }
            assertEquals("$label: the $stubName call's first register", 0, call.startRegister)
            assertEquals("$label: the $stubName call's width", 15, call.registerCount)
            val prologueWrites = code.take(stub.parameterTypes.size)
                .filterIsInstance<TwoRegisterInstruction>()
                .map { it.registerA }
            assertTrue(
                "$label: the $stubName stub's parameter copies run into its parameters: " +
                    "$prologueWrites (base $base)",
                prologueWrites.all { it < base },
            )
            val builds = code.filter { it.opcode == Opcode.NEW_INSTANCE }.map {
                (it as OneRegisterInstruction).registerA
            }
            assertTrue(
                "$label: the $stubName stub builds objects in its parameters: $builds (base $base)",
                builds.all { it < base },
            )
            val writes = code.drop(stub.parameterTypes.size).filter {
                it.opcode == Opcode.MOVE_OBJECT || it.opcode == Opcode.MOVE_OBJECT_FROM16
            }.map {
                (it as TwoRegisterInstruction).registerA
            }.toSet()
            val ops = code.drop(stub.parameterTypes.size).map { instruction ->
                instruction.opcode.name + ":" + (instruction as? TwoRegisterInstruction)?.registerA
            }
            assertEquals(
                "$label: the $stubName stub writes registers other than the options and config " +
                    "slots (post-prologue ops: $ops)",
                setOf(2, 5),
                writes,
            )
        }
        val hooked = context.mutableClassDefBy(handoff.definingClass).methods.single {
            it.name == handoff.name && it.parameterTypes == handoff.parameterTypes
        }
        assertTrue(
            "$label: the handoff doesn't record its arguments",
            hooked.implementation!!.instructions.first().let {
                (it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                    ?.reference.toString().orEmpty().contains("->captureArgs(")
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
