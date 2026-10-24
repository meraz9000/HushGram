/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.messagebox.BOX_BUTTON_IDS
import app.morphe.patches.instagram.direct.messagebox.BOX_BUTTON_NAMES
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * Keep instants unseen: the viewer's consumption reference is found from its kept source name,
 * its invoke is guarded, the extension returns the app's Unit singleton, and the same viewer
 * renders the eye button.
 */
class KeepInstantsUnseenHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(INSTANTS_SEEN).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(
            ON_SEEN_VIEW, ON_SEEN_STORY, ON_CONSUME, BEFORE_SEEN_WRITE,
            FILTER_SEEN_IDS, BEFORE_SEEN_REQUEST_ID, BEFORE_FETCHER_SEEN, FETCHER_STATE,
            SEEN_MAINTENANCE, EMPTY_SEEN, BEFORE_SEEN_LIST, FETCH_DECISION, VIEWER_CLOSE,
        )) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun theConsumptionReferenceIsFoundAndGuardedOnTheDeclaredBuild() {
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
        val context = PatchContexts.of(
            candidates(bundle) + ExtensionDex.classDef(INSTANTS_SEEN) + ExtensionDex.classDef(SETTINGS_STATUS),
        )
        val model = context.findInstantModel()
        val renders = context.findInstantRenders(model)
        assertTrue("$label: no instant render", renders.isNotEmpty())
        renders.forEach { render -> context.hookInstantRender(render, ON_SEEN_VIEW) }
        context.hookStoryBind(ON_SEEN_STORY)

        val reference = context.findConsumptionReference()
        context.hookConsumption(reference)
        val manager = context.findSeenManager()
        context.hookSeenWrite(manager)
        context.hookSeenManagerMaintenance(manager)
        context.hookSeenRequest()
        context.hookFetcherSeen()
        context.hookSingleSeenRequest()
        context.hookViewerClose()
        context.hookFetcherFetch()
        context.enableStatus("keepInstants")

        // The consumption body still runs; only the call is recorded for the eye button.
        val invoke = context.mutableClassDefBy(reference.type).methods.single {
            it.name == "invoke" && it.parameterTypes.size == 3
        }
        val code = invoke.implementation!!.instructions.toList()
        assertTrue(
            "$label: the consumption isn't recorded: ${code.take(8).map { it.reference() }}",
            code.any { it.reference() == ON_CONSUME },
        )

        // The seen write is guarded and skips with a plain return.
        val write = context.mutableClassDefBy(manager.type).methods.single {
            it.name == "A04" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;")
        }
        val writeCode = write.implementation!!.instructions.toList()
        assertTrue(
            "$label: the seen write isn't guarded: ${writeCode.take(8).map { it.reference() }}",
            writeCode.any { it.reference() == BEFORE_SEEN_WRITE },
        )
        assertTrue(
            "$label: the seen write's skip doesn't return",
            writeCode.any { it.opcode == Opcode.RETURN_VOID },
        )

        // The stale hard seen set is cleared once, and the soft/bulk seen passes are skipped.
        val maintenance = context.mutableClassDefBy(manager.type).methods.single {
            it.name == "A02" && it.parameterTypes.isEmpty()
        }
        val maintenanceCode = maintenance.implementation!!.instructions.toList()
        assertTrue(
            "$label: the seen maintenance isn't guarded",
            maintenanceCode.any { it.reference() == SEEN_MAINTENANCE },
        )
        assertTrue(
            "$label: the stale seen set isn't replaced",
            maintenanceCode.any { it.reference() == EMPTY_SEEN },
        )
        val bulk = context.mutableClassDefBy(manager.type).methods.single {
            it.name == "A05" && it.parameterTypes.map(Any::toString) == listOf("Ljava/util/List;")
        }
        assertTrue(
            "$label: the bulk seen write isn't guarded",
            bulk.implementation!!.instructions.toList().any { it.reference() == BEFORE_SEEN_LIST },
        )

        // The server seen request's ids are filtered before the mutation is built.
        val api = context.mutableClassDefBy("Lcom/instagram/quicksnap/data/api/QuickSnapApi;")
            .methods.single {
                it.name == "A0C" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].toString() == "Ljava/util/List;"
            }
        val apiCode = api.implementation!!.instructions.toList()
        assertTrue(
            "$label: the server seen request isn't filtered: ${apiCode.take(6).map { it.reference() }}",
            apiCode.any { it.reference() == FILTER_SEEN_IDS },
        )

        // The viewer host's close commit is adjusted just before it reads the pending set.
        val pending = manager.fields.single { it.name == "A05" }
        val pendingRef = "${manager.type}->${pending.name}:${pending.type}"
        var closeCode: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>? = null
        context.classDefForEach { classDef ->
            classDef.methods.firstOrNull { method ->
                method.name == "onDestroy" && method.parameterTypes.isEmpty() &&
                    method.returnType == "V" && method.implementation?.instructions?.any {
                        it.opcode == Opcode.IGET_OBJECT && it.reference() == pendingRef
                    } == true
            }?.let { closeCode = it.implementation!!.instructions.toList() }
        }
        assertTrue(
            "$label: the viewer host's pending seen read wasn't found",
            closeCode != null,
        )
        assertTrue(
            "$label: the viewer host's close commit isn't adjusted",
            closeCode!!.any { it.reference() == VIEWER_CLOSE },
        )

        // The fetcher's update is skipped and its current tray state returned instead.
        val fetcher = context.mutableClassDefBy(
            "Lcom/instagram/quicksnap/data/repository/QuickSnapFetcher;",
        ).methods.single {
            it.name == "A04" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0].toString() == "Ljava/util/List;"
        }
        val fetcherCode = fetcher.implementation!!.instructions.toList()
        assertTrue(
            "$label: the fetcher's update isn't guarded",
            fetcherCode.any { it.reference() == BEFORE_FETCHER_SEEN },
        )
        assertTrue(
            "$label: the fetcher doesn't return its current state",
            fetcherCode.any { it.reference() == FETCHER_STATE },
        )
        val state = context.mutableClassDefBy(INSTANTS_SEEN).methods.single { it.name == "fetcherState" }
        assertTrue(
            "$label: the fetcher state stub is empty",
            state.implementation!!.instructions.toList().any { it.reference() == "Ljava/util/concurrent/atomic/AtomicReference;->get()Ljava/lang/Object;" },
        )

        // The fetcher's built-in in-progress flag is set before its read, which makes it return
        // the current state instead of re-fetching and dropping the older instants.
        val fetcherType = "Lcom/instagram/quicksnap/data/repository/QuickSnapFetcher;"
        val fetch = context.mutableClassDefBy(fetcherType)
            .methods.single { it.name == "A03" && it.parameterTypes.size == 5 }
        val fetchCode = fetch.implementation!!.instructions.toList()
        assertTrue(
            "$label: the fetcher's list-preserving decision isn't applied",
            fetchCode.any { it.reference() == FETCH_DECISION },
        )
        val decision = fetchCode.indexOfFirst { it.reference() == FETCH_DECISION }
        val guards = fetchCode.drop(decision + 1).take(8)
            .filter { it.opcode == Opcode.IF_EQ || it.opcode == Opcode.IF_NE }
        assertEquals("$label: the fetch decision has no branches", 2, guards.size)
        assertTrue(
            "$label: the fetch decision branches are inverted: " +
                guards.joinToString { it.opcode.name },
            guards.all { it.opcode == Opcode.IF_EQ },
        )
        val flag = "$fetcherType->A01:Z"
        val writes = fetchCode.withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.IPUT_BOOLEAN && instruction.reference() == flag
        }
        assertTrue("$label: the fetcher's list-preserving flag isn't set", writes.isNotEmpty())
        val readIndex = fetchCode.indexOfFirst {
            it.opcode == Opcode.IGET_BOOLEAN && it.reference() == flag
        }
        assertTrue(
            "$label: the flag isn't read by the fetcher",
            readIndex >= 0,
        )
        assertTrue(
            "$label: the flag is written after it is read",
            writes.all { it.index < readIndex },
        )


    }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.reference(): String? =
        (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * The classes the discovery reads: the Room loader and the model it builds, the instant
     * renders, the story menu helper and its writers, the consumption reference, and the app's
     * Unit singleton. The message box ingredients are kept so the same fixture subset also
     * feeds the Save button's own hook when both patches are tested together.
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
                val consumption = candidate.methods.any { method ->
                    method.name == "<init>" && method.implementation?.instructions?.toList().orEmpty().any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                            ?.startsWith("markAsConsumed(") == true
                    }
                }
                val seenManager = candidate.fields.any { it.type == "Ljava/util/Set;" } &&
                    candidate.methods.any { method ->
                        method.name == "A00" && AccessFlags.STATIC.isSet(method.accessFlags) &&
                            method.parameterTypes.map(Any::toString) == listOf(USER_SESSION) &&
                            method.returnType == candidate.type
                    } &&
                    candidate.methods.any { method ->
                        method.name == "A04" &&
                            method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") &&
                            method.returnType == "V"
                    }
                val viewerHost = candidate.methods.any { method ->
                    method.name == "onDestroy" && method.parameterTypes.isEmpty() &&
                        method.returnType == "V" &&
                        method.implementation?.instructions?.any { instruction ->
                            instruction.opcode == Opcode.IGET_OBJECT &&
                                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)
                                    ?.let { it.name == "A05" && it.type == "Ljava/util/Set;" } == true
                        } == true
                }
                val keep = candidate.type == ROOM_LOADER || marker || writesAReelItem ||
                    candidate.type == REEL_ITEM || consumption || seenManager || viewerHost ||
                    candidate.type == "Lcom/instagram/quicksnap/data/api/QuickSnapApi;" ||
                    candidate.type == "Lcom/instagram/quicksnap/data/repository/QuickSnapFetcher;" ||
                    candidate.type == "Lcom/instagram/quicksnap/data/repository/QuickSnapRepository;" ||
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
        val fetcherType = "Lcom/instagram/quicksnap/data/repository/QuickSnapFetcher;"
        val referenced = mutableSetOf<String>()
        kept.filter { it.type == fetcherType }.forEach { fetcher ->
            fetcher.methods.forEach { method ->
                method.implementation?.instructions?.forEach { instruction ->
                    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                        is com.android.tools.smali.dexlib2.iface.reference.TypeReference ->
                            referenced += reference.type.toString()
                        is FieldReference -> referenced += reference.definingClass.toString()
                        is com.android.tools.smali.dexlib2.iface.reference.MethodReference ->
                            referenced += reference.definingClass.toString()
                        else -> Unit
                    }
                }
            }
        }
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                if (candidate.type in referenced && kept.none { it.type == candidate.type }) {
                    kept += candidate
                }
            }
        }
        return kept
    }
}
