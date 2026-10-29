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
private const val VIEW_MODEL = "Lcom/instagram/quicksnap/viewer/viewmodel/QuickSnapConsumptionViewModel;"

internal const val INSTANTS_SEEN = "$EXTENSION_PACKAGE/download/InstantsSeen;"
internal const val ON_SEEN_VIEW = "$INSTANTS_SEEN->onView(Ljava/lang/Object;)V"
internal const val ON_SEEN_STORY = "$INSTANTS_SEEN->onStory(Ljava/lang/Object;)V"
internal const val ON_CONSUME =
    "$INSTANTS_SEEN->onConsume(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val BEFORE_SEEN_WRITE =
    "$INSTANTS_SEEN->beforeSeenWrite(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val FILTER_SEEN_IDS =
    "$INSTANTS_SEEN->filterSeenIds(Ljava/util/List;)Ljava/util/List;"
internal const val BEFORE_FETCHER_SEEN =
    "$INSTANTS_SEEN->beforeFetcherSeen(Ljava/lang/Object;Ljava/util/List;)Z"
internal const val VIEWER_CLOSE =
    "$INSTANTS_SEEN->viewerClose(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val FETCHER_STATE =
    "$INSTANTS_SEEN->fetcherState(Ljava/lang/Object;)Ljava/lang/Object;"
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
        hookBulkSeen(manager)
        hookSeenRequest()
        hookFetcherSeen()
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
            method.name == "A03" &&
                method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") &&
                method.returnType == "V"
        }
        val writesMany = classDef.methods.any { method ->
            method.name == "A04" &&
                method.parameterTypes.map(Any::toString) == listOf("Ljava/util/List;") &&
                method.returnType == "V"
        }
        val state = classDef.fields.any { it.name == "A05" && it.type == "Ljava/util/Set;" } &&
            classDef.fields.any { it.name == "A07" } &&
            classDef.methods.any { it.name == "A01" && it.parameterTypes.isEmpty() && it.returnType == "V" } &&
            classDef.methods.any { it.name == "A02" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        if (createsSelf && writesOne && writesMany && state) {
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
        it.name == "A03" &&
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
 * Blocks the seen manager's bulk seen write while the switch is on, so a batch heard from
 * Instagram cannot mark the kept instants seen. The eye button writes its own id through the
 * single seen method (`beforeSeenWrite`), which this hook leaves alone.
 */
internal fun BytecodePatchContext.hookBulkSeen(manager: ClassDef) {
    val bulk = manager.methods.singleOrNull {
        it.name == "A04" && it.parameterTypes.map(Any::toString) == listOf("Ljava/util/List;") &&
            it.returnType == "V"
    } ?: throw PatchException("$PATCH: ${manager.type} has no bulk seen write")
    val mutable = mutableClassDefBy(manager.type).methods.single {
        it.name == bulk.name && it.parameterTypes == bulk.parameterTypes &&
            it.returnType == bulk.returnType
    }
    mutable.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            invoke-static {v0}, $BEFORE_SEEN_LIST
            move-result v0
            if-eqz v0, :run
            return-void
        """,
        ExternalLabel("run", mutable.implementation!!.instructions.first()),
    )
}

/**
 * Hooks the viewer's close sync. On 453 the viewer viewmodel's `A13()` persists the seen set
 * and hands its ids to `QuickSnapRepository.A07`, which both marks them on the server and
 * removes them from the tray. That path is the one that dropped viewed instants on exit.
 * The hook runs before `A13` reads the set: `viewerClose` empties it for viewed-only, or fills
 * it with the eye's ids so only the eye's instant is synced.
 */
internal fun BytecodePatchContext.hookViewerClose() {
    val manager = findSeenManager()
    val viewModel = classDefByOrNull(VIEW_MODEL)
        ?: throw PatchException("$PATCH: $VIEW_MODEL is gone")
    val close = viewModel.methods.singleOrNull {
        it.name == "A13" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: throw PatchException("$PATCH: $VIEW_MODEL has no close sync")
    val managerField = viewModel.fields.singleOrNull { it.type == manager.type }
        ?: throw PatchException("$PATCH: $VIEW_MODEL has no seen manager field")
    val mutable = mutableClassDefBy(viewModel.type).methods.single {
        it.name == close.name && it.parameterTypes == close.parameterTypes &&
            it.returnType == close.returnType
    }
    if (mutable.implementation!!.registerCount < 2) {
        throw PatchException("$PATCH: $VIEW_MODEL close sync has too few registers")
    }
    mutable.addInstructions(
        0,
        """
            move-object/from16 v0, p0
            iget-object v1, v0, ${viewModel.type}->${managerField.name}:${managerField.type}
            invoke-static {v1, v0}, $VIEWER_CLOSE
        """,
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
        it.name == "A02" && it.parameterTypes.size == 5 &&
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
    if (mutable.implementation!!.registerCount < 14) {
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
        it.name == "A0D" && it.parameterTypes.size == 2 &&
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
        it.name == "A03" && it.parameterTypes.size == 2 &&
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

/** The reference's `invoke` taking the instant, the previous instant and the consumption type. */
private fun consumptionInvoke(reference: ClassDef): Method = reference.methods.singleOrNull {
    it.name == "invoke" &&
        it.parameterTypes.map(Any::toString) ==
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;") &&
        it.returnType == "Ljava/lang/Object;"
} ?: throw PatchException("$PATCH: ${reference.type} has no three-argument invoke")
