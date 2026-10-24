/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.instants

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Keep instants unseen"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val SEEN_API = "Lcom/instagram/quicksnap/data/api/QuickSnapApi;"
private const val FETCHER = "Lcom/instagram/quicksnap/data/repository/QuickSnapFetcher;"

internal const val INSTANTS_SEEN = "$EXTENSION_PACKAGE/download/InstantsSeen;"
internal const val ON_SEEN_VIEW = "$INSTANTS_SEEN->onView(Ljava/lang/Object;)V"
internal const val ON_SEEN_STORY = "$INSTANTS_SEEN->onStory(Ljava/lang/Object;)V"
internal const val ON_CONSUME =
    "$INSTANTS_SEEN->onConsume(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val BEFORE_SEEN_WRITE =
    "$INSTANTS_SEEN->beforeSeenWrite(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val FILTER_SEEN_IDS =
    "$INSTANTS_SEEN->filterSeenIds(Ljava/util/List;)Ljava/util/List;"
internal const val BEFORE_SEEN_REQUEST_ID =
    "$INSTANTS_SEEN->beforeSeenRequestId(Ljava/lang/String;)Z"
internal const val BEFORE_FETCHER_SEEN =
    "$INSTANTS_SEEN->beforeFetcherSeen(Ljava/lang/Object;Ljava/util/List;)Z"
internal const val VIEWER_CLOSE =
    "$INSTANTS_SEEN->viewerClose(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val FETCHER_STATE =
    "$INSTANTS_SEEN->fetcherState(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val SEEN_MAINTENANCE = "$INSTANTS_SEEN->seenMaintenance(Ljava/lang/Object;)I"
internal const val EMPTY_SEEN = "$INSTANTS_SEEN->emptySeenSet()Ljava/util/Set;"
internal const val BEFORE_SEEN_LIST = "$INSTANTS_SEEN->beforeSeenList(Ljava/util/List;)Z"
internal const val FETCH_DECISION = "$INSTANTS_SEEN->fetchDecision(Ljava/lang/Object;)I"

/**
 * Keeps viewed instants unseen until the eye button marks them seen.
 *
 * <p>The viewer's consumption reference is recorded at its entry so the eye button can replay
 * the call. Its body is left alone: the body advances the viewer to the next instant. What is
 * skipped is the seen-state manager's write, which adds the current media id to the seen set.
 * The viewer advances normally, the instant stays unseen, and the eye button replays the
 * consumption with the seen write forced through.
 *
 * <p>The reference is told from its constructor's kept source name
 * `markAsConsumed(QuickSnapMedia, QuickSnapMedia, QuickSnapConsumptionState.ConsumptionType)`.
 * The seen manager is the user-scoped class with a static `A00(UserSession)` returning itself,
 * an `A04(String)` write, a `Set` field and the `A02`/`A03` persistence helpers.
 */
@Suppress("unused")
val keepInstantsUnseenPatch = bytecodePatch(
    name = "Keep instants unseen",
    description = "Viewed instants stay unseen until you tap the eye button, which marks the instant as seen. " +
        "The viewer still moves on to the next instant when you tap. Starts off. Turn it on in HushGram " +
        "settings > Messages.",
    default = false,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("keepInstants")
        val model = findInstantModel()
        findInstantRenders(model).forEach { render -> hookInstantRender(render, ON_SEEN_VIEW) }
        hookStoryBind(ON_SEEN_STORY)
        hookConsumption(findConsumptionReference())
        val manager = findSeenManager()
        hookSeenWrite(manager)
        hookSeenManagerMaintenance(manager)
        hookSeenRequest()
        hookFetcherSeen()
        hookSingleSeenRequest()
        hookViewerClose()
        hookFetcherFetch()
        enableStatus("keepInstants")
    }
}

/**
 * The viewer's consumption function reference: the class whose constructor carries the source
 * name `markAsConsumed(...)`. It is a Kotlin function reference for the consumption state's
 * method and the only class that names that method.
 */
internal fun BytecodePatchContext.findConsumptionReference(): ClassDef {
    val found = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        val constructor = classDef.methods.firstOrNull { it.name == "<init>" }
        val marks = constructor?.implementation?.instructions?.toList().orEmpty().any { instruction ->
            val text = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
            text != null && text.startsWith("markAsConsumed(") &&
                text.contains("QuickSnapConsumptionState")
        } == true
        if (marks) {
            found += classDef
        }
    }
    return found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one consumption reference, found " +
            if (found.isEmpty()) "none" else found.joinToString { it.type },
    )
}

/** Records the consumption reference's four arguments at its entry; the body still runs. */
internal fun BytecodePatchContext.hookConsumption(reference: ClassDef) {
    val invoke = consumptionInvoke(reference)
    val mutable = mutableClassDefBy(reference.type).methods.single {
        it.name == invoke.name && it.parameterTypes == invoke.parameterTypes &&
            it.returnType == invoke.returnType
    }
    mutable.addInstructions(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            move-object/from16 v2, p2
            move-object/from16 v3, p3
            invoke-static {v0, v1, v2, v3}, $ON_CONSUME
        """,
    )
}

/**
 * The seen-state manager: the user-scoped class that creates itself from a UserSession, writes
 * one media id with `A04(String)`, and keeps the seen set behind `A02`/`A03`.
 */
internal fun BytecodePatchContext.findSeenManager(): ClassDef {
    val found = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        val createsSelf = classDef.methods.any { method ->
            method.name == "A00" && AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.map(Any::toString) == listOf(USER_SESSION) &&
                method.returnType == classDef.type
        }
        val writesOne = classDef.methods.any { method ->
            method.name == "A04" &&
                method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") &&
                method.returnType == "V"
        }
        val state = classDef.fields.any { it.type == "Ljava/util/Set;" } &&
            classDef.methods.any { it.name == "A02" && it.parameterTypes.isEmpty() && it.returnType == "V" } &&
            classDef.methods.any { it.name == "A03" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        if (createsSelf && writesOne && state) {
            found += classDef
        }
    }
    return found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one seen-state manager, found " +
            if (found.isEmpty()) "none" else found.joinToString { it.type },
    )
}

/** Skips the manager's seen write while the switch is on, unless the eye button is forcing it. */
internal fun BytecodePatchContext.hookSeenWrite(manager: ClassDef) {
    val write = manager.methods.singleOrNull {
        it.name == "A04" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") &&
            it.returnType == "V"
    } ?: throw PatchException("$PATCH: ${manager.type} has no single-id seen write")
    val mutable = mutableClassDefBy(manager.type).methods.single {
        it.name == write.name && it.parameterTypes == write.parameterTypes &&
            it.returnType == write.returnType
    }
    val first = mutable.implementation!!.instructions.first()
    mutable.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            invoke-static {v0, v1}, $BEFORE_SEEN_WRITE
            move-result v0
            if-nez v0, :run
            return-void
        """,
        ExternalLabel("run", first),
    )
}

/**
 * Clears the stale hard seen set once and blocks the soft/bulk seen merges. The viewer's close
 * syncs whatever is in that set into the repository (`C07PZ.A14`); stale ids from earlier
 * builds otherwise remove the instants locally even though the server still has them unseen.
 * The eye button writes its own id through `beforeSeenWrite`, so the close removes only that.
 */
internal fun BytecodePatchContext.hookSeenManagerMaintenance(manager: ClassDef) {
    val soft = manager.methods.singleOrNull {
        it.name == "A02" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: throw PatchException("$PATCH: ${manager.type} has no soft seen pass")
    val list = manager.methods.singleOrNull {
        it.name == "A05" && it.parameterTypes.map(Any::toString) == listOf("Ljava/util/List;") &&
            it.returnType == "V"
    } ?: throw PatchException("$PATCH: ${manager.type} has no bulk seen write")
    val state = manager.fields.singleOrNull { it.name == "A06" }
        ?: throw PatchException("$PATCH: ${manager.type} has no seen set")
    val persist = manager.methods.singleOrNull {
        it.name == "A01" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: throw PatchException("$PATCH: ${manager.type} has no seen persist")

    val softMutable = mutableClassDefBy(manager.type).methods.single {
        it.name == soft.name && it.parameterTypes == soft.parameterTypes &&
            it.returnType == soft.returnType
    }
    softMutable.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v1, p0
            invoke-static {v1}, $SEEN_MAINTENANCE
            move-result v0
            if-eqz v0, :run
            const/4 v1, 0x1
            if-ne v0, v1, :clear
            return-void
            :clear
            iget-object v0, p0, ${manager.type}->${state.name}:${state.type}
            invoke-static {}, $EMPTY_SEEN
            move-result-object v1
            invoke-interface {v0, v1}, ${state.type}->HQ5(Ljava/lang/Object;)V
            invoke-direct {p0}, ${manager.type}->${persist.name}()V
            return-void
        """,
        ExternalLabel("run", softMutable.implementation!!.instructions.first()),
    )

    val listMutable = mutableClassDefBy(manager.type).methods.single {
        it.name == list.name && it.parameterTypes == list.parameterTypes &&
            it.returnType == list.returnType
    }
    listMutable.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            invoke-static {v0}, $BEFORE_SEEN_LIST
            move-result v0
            if-eqz v0, :run
            return-void
        """,
        ExternalLabel("run", listMutable.implementation!!.instructions.first()),
    )
}

/**
 * Adjusts the seen manager's pending ids just before the viewer host commits them on close.
 *
 * <p>The host's `onDestroy` reads the manager (`A0E`), then its pending set (`A05`), moves those
 * ids into the hard set and calls `A14`. That inline commit bypasses every method hook, which is
 * why viewed instants still vanished on exit. The hook runs after the manager is loaded and
 * before the pending read: `viewerClose` empties it, or fills it with the eye's ids only, so the
 * host's commit becomes a no-op for viewed instants and a normal local+server sync for the eye.
 */
internal fun BytecodePatchContext.hookViewerClose() {
    val manager = findSeenManager()
    val pending = manager.fields.singleOrNull { it.name == "A05" }
        ?: throw PatchException("$PATCH: ${manager.type} has no pending seen set")
    val found = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        for (method in classDef.methods) {
            if (method.name != "onDestroy" || method.parameterTypes.isNotEmpty() ||
                method.returnType != "V" || method.implementation == null) {
                continue
            }
            val reads = method.implementation!!.instructions.any { instruction ->
                if (instruction.opcode != Opcode.IGET_OBJECT) {
                    return@any false
                }
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                field != null && field.definingClass == manager.type && field.name == pending.name
            }
            if (reads) {
                found += classDef to method
            }
        }
    }
    val (host, method) = found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one viewer host that commits the pending seen set, found ${found.size}",
    )
    val mutable = mutableClassDefBy(host.type).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes &&
            it.returnType == method.returnType
    }
    val instructions = mutable.implementation!!.instructions.toList()
    val index = instructions.indexOfFirst { instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) {
            return@indexOfFirst false
        }
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        field != null && field.definingClass == manager.type && field.name == pending.name
    }
    if (index < 0) throw PatchException("$PATCH: ${host.type} lost its pending seen read")
    val managerRegister = (instructions[index] as? TwoRegisterInstruction)?.registerB
        ?: throw PatchException("$PATCH: ${host.type} pending seen read has no manager register")
    val load = (index - 1 downTo 0).firstOrNull { candidate ->
        val instruction = instructions[candidate]
        val destination = (instruction as? TwoRegisterInstruction)?.registerA
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.IGET_OBJECT && destination == managerRegister &&
            field?.type?.toString() == manager.type
    } ?: throw PatchException("$PATCH: ${host.type} never loads the seen manager")
    val viewModelRegister = (instructions[load] as TwoRegisterInstruction).registerB
    if (managerRegister > 15 || viewModelRegister > 15) {
        throw PatchException(
            "$PATCH: ${host.type} close hook needs v$viewModelRegister and v$managerRegister",
        )
    }
    mutable.addInstructions(
        index,
        "invoke-static {v$managerRegister, v$viewModelRegister}, $VIEWER_CLOSE",
    )
}

/**
 * Keeps the tray list across re-fetches without touching the fetcher's coroutine internals.
 *
 * <p>The fetcher reads its `A01` field on the initial-call path and, when set, returns the
 * current state instead of fetching. That is the same early return Instagram uses while a fetch
 * is already running, so it is safe. Before the read we set the field from the decision: keep
 * when the switch is on and the state already holds instants, clear it when the state is empty
 * (so a fresh login still loads the tray), and leave it alone when the switch is off.
 *
 * <p>The fetcher otherwise sends the known list to the server and replaces the state with only
 * what comes back; the repository then drops the older instants from the tray.
 */
internal fun BytecodePatchContext.hookFetcherFetch() {
    val fetcher = classDefByOrNull(FETCHER) ?: throw PatchException("$PATCH: $FETCHER is gone")
    val fetch = fetcher.methods.singleOrNull {
        it.name == "A03" && it.parameterTypes.size == 5 &&
            it.returnType == "Ljava/lang/Object;"
    } ?: throw PatchException("$PATCH: $FETCHER has no fetch call")
    val mutable = mutableClassDefBy(fetcher.type).methods.single {
        it.name == fetch.name && it.parameterTypes == fetch.parameterTypes &&
            it.returnType == fetch.returnType
    }
    val instructions = mutable.implementation!!.instructions.toList()
    val flagIndex = instructions.indexOfFirst { instruction ->
        if (instruction.opcode != Opcode.IGET_BOOLEAN) {
            return@indexOfFirst false
        }
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        field != null && field.definingClass.toString() == FETCHER && field.name == "A01"
    }
    if (flagIndex < 0) throw PatchException("$PATCH: $FETCHER has no fetch-in-progress flag read")
    if (mutable.implementation!!.registerCount < 12) {
        throw PatchException("$PATCH: $FETCHER fetch call has too few registers")
    }
    val field = (instructions[flagIndex] as ReferenceInstruction).reference as FieldReference
    val flag = "$FETCHER->${field.name}:${field.type}"
    mutable.addInstructionsWithLabels(
        flagIndex,
        """
            move-object/from16 v11, p0
            invoke-static {v11}, $FETCH_DECISION
            move-result v12
            const/4 v13, 0x1
            if-eq v12, v13, :keep
            const/4 v13, 0x0
            if-eq v12, v13, :allow
            goto :run
            :allow
            const/4 v13, 0x0
            iput-boolean v13, v11, $flag
            goto :run
            :keep
            const/4 v13, 0x1
            iput-boolean v13, v11, $flag
        """,
        ExternalLabel("run", instructions[flagIndex]),
    )
}

/**
 * Empties the server seen request's media ids while the switch is on. The request is
 * `QuickSnapApi.A0C(List, Continuation)`; its first parameter is the list the mutation sends.
 * The eye button forces the real list through.
 */
internal fun BytecodePatchContext.hookSeenRequest() {
    val api = classDefByOrNull(SEEN_API) ?: throw PatchException("$PATCH: $SEEN_API is gone")
    val method = api.methods.singleOrNull {
        it.name == "A0C" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0].toString() == "Ljava/util/List;" &&
            it.returnType == "Ljava/lang/Object;"
    } ?: throw PatchException("$PATCH: $SEEN_API has no seen request")
    val mutable = mutableClassDefBy(api.type).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes &&
            it.returnType == method.returnType
    }
    mutable.addInstructions(
        0,
        """
            move-object/from16 v0, p1
            invoke-static {v0}, $FILTER_SEEN_IDS
            move-result-object v0
            move-object/from16 p1, v0
        """,
    )
}

/**
 * Skips the bulk fetcher's seen update and returns its current tray state. The fetcher
 * replaces its state from the API response, so an empty or short response would drop the
 * instants; returning the current state leaves the tray untouched. The eye button forces the
 * real update through.
 */
internal fun BytecodePatchContext.hookFetcherSeen() {
    val fetcher = classDefByOrNull(FETCHER) ?: throw PatchException("$PATCH: $FETCHER is gone")
    val method = fetcher.methods.singleOrNull {
        it.name == "A04" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0].toString() == "Ljava/util/List;" &&
            it.returnType == "Ljava/lang/Object;"
    } ?: throw PatchException("$PATCH: $FETCHER has no bulk seen call")
    val mutable = mutableClassDefBy(fetcher.type).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes &&
            it.returnType == method.returnType
    }
    val first = mutable.implementation!!.instructions.first()
    mutable.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            invoke-static {v0, v1}, $BEFORE_FETCHER_SEEN
            move-result v0
            if-eqz v0, :run
            move-object/from16 v0, p0
            invoke-static {v0}, $FETCHER_STATE
            move-result-object v0
            return-object v0
        """,
        ExternalLabel("run", first),
    )
    fillFetcherStateStub(fetcher)
}

/** Fills the extension's fetcher-state stub with the fetcher's AtomicReference value. */
internal fun BytecodePatchContext.fillFetcherStateStub(fetcher: ClassDef) {
    val state = fetcher.fields.singleOrNull {
        it.type == "Ljava/util/concurrent/atomic/AtomicReference;"
    } ?: throw PatchException("$PATCH: $FETCHER has no AtomicReference state")
    val stub = mutableClassDefBy(INSTANTS_SEEN).methods.single {
        it.name == "fetcherState" && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    }
    stub.addInstructions(
        0,
        """
            check-cast p0, ${fetcher.type}
            iget-object v0, p0, ${fetcher.type}->${state.name}:${state.type}
            invoke-virtual {v0}, Ljava/util/concurrent/atomic/AtomicReference;->get()Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """,
    )
}

/**
 * Answers the single-id seen request false without sending it. The repository's consume method
 * removes the instant from its list only when this request answers true; false leaves it.
 * The eye button forces the real request through.
 */
internal fun BytecodePatchContext.hookSingleSeenRequest() {
    val api = classDefByOrNull(SEEN_API) ?: throw PatchException("$PATCH: $SEEN_API is gone")
    val method = api.methods.singleOrNull {
        it.name == "A06" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0].toString() == "Ljava/lang/String;" &&
            it.returnType == "Ljava/lang/Object;"
    } ?: throw PatchException("$PATCH: $SEEN_API has no single seen request")
    val mutable = mutableClassDefBy(api.type).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes &&
            it.returnType == method.returnType
    }
    val first = mutable.implementation!!.instructions.first()
    mutable.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            invoke-static {v0}, $BEFORE_SEEN_REQUEST_ID
            move-result v0
            if-eqz v0, :run
            sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            return-object v0
        """,
        ExternalLabel("run", first),
    )
}

/** The reference's `invoke` taking the instant, the previous instant and the consumption type. */
private fun consumptionInvoke(reference: ClassDef): Method = reference.methods.singleOrNull {
    it.name == "invoke" &&
        it.parameterTypes.map(Any::toString) ==
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;") &&
        it.returnType == "Ljava/lang/Object;"
} ?: throw PatchException("$PATCH: ${reference.type} has no three-argument invoke")
