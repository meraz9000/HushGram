/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Swipe through a chat's reels"
internal const val CHAT_REELS = "$EXTENSION_PACKAGE/direct/ChatReels;"
internal const val CHAIN = "$CHAT_REELS->chain(I)Z"
internal const val STOP = "$CHAT_REELS->stop()Z"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** What a chat's reel opener is handed: the chat and the message holding the reel. Redex keeps both names. */
internal const val THREAD_KEY = "Lcom/instagram/model/direct/DirectThreadKey;"
internal const val MESSAGE_ID = "Lcom/instagram/model/direct/messageid/MessageIdentifier;"

/**
 * The server flag behind Instagram's own chat reels test. On 453 it's read once, in a test taking
 * the session and answering it (LX/01Oo;->A00 on 385914285, LX/01PZ;->A00 on 385914282, an instance
 * method of a singleton), which the opener of a reel shared in a chat asks before it hands the chat's
 * other shared reels to the Reels viewer, and which the chat and the Reels viewer ask too. 452.1's
 * was 0x81155f00006ed0.
 */
internal const val CHAT_REELS_FLAG = 0x81153c00006fb6L

/**
 * The trace name of the Reels viewer's dual paging request (LX/0Dyx;->A03 on 385914285, LX/0Fit;->A03
 * on 385914282), which pages a source with two sides: a chat's own shared reels and, after them,
 * Instagram's suggested ones. Only that request loads it.
 */
internal const val DUAL_PAGING = "AbstractPaginatingClipsItemsFetcher.makeDualPaginationRequest"

/**
 * What the source of a chat's reels asks the server for to page the chat's own shared reels
 * (LX/0Mfh;->BYn on 385914285, LX/0Mkl;->BYs on 385914282). Only that method loads it.
 */
internal const val CHAT_REELS_PATH = "clips/direct_thread_clips/"

/**
 * Swiping up on a reel opened from a chat goes through the other reels shared in that chat before
 * any suggested reels (#350), and with the second switch stops after them (#390). In the default
 * selection with both switches off. The first answers Instagram's own test, so the chain, its order
 * and Back to the chat are Instagram's.
 */
@Suppress("unused")
val swipeChatReelsPatch = bytecodePatch(
    name = "Swipe through a chat's reels",
    description = "Swiping up on a reel you opened from a chat goes through the other reels shared in that chat before " +
        "any suggested reels, and can stop after them. Both start off. Turn them on in HushGram settings > Messages.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("chatReels")
        // Both finders refuse before either switch changes any code.
        val read = findChatReels()
        val end = findChatReelsEnd()
        chainChatReels(read)
        stopAfterChatReels(end)
        enableStatus("chatReels")
    }
}

/** Has the extension answer the chat reels test's read, found with [findChatReels], through [CHAIN]. */
internal fun BytecodePatchContext.chainChatReels(read: FlagLoad = findChatReels()) {
    answerFlagLoads(listOf(read to CHAIN))
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the one read of [CHAT_REELS_FLAG]. It has to be the flag's own read, in a test taking the
 * session and answering a boolean, and a method handed a chat ([THREAD_KEY]) and one of its messages
 * ([MESSAGE_ID]), the opener of a reel shared there, has to ask that test. Fails before anything
 * changes when any of it isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findChatReels(): FlagLoad {
    val flag = CHAT_REELS_FLAG.toString(16)
    val reads = findFlagLoads(PATCH, CHAT_REELS_FLAG, "Z")
    val read = reads.singleOrNull() ?: refuse("expected one read of the chat reels flag $flag, found ${reads.size}")
    val where = "${read.type}->${read.name}"
    if (read.shared) refuse("the read of $flag in $where is shared with another flag")
    if (read.parameters != listOf(USER_SESSION) || read.returnType != "Z") {
        refuse("$flag is read in $where(${read.parameters.joinToString("")})${read.returnType}, not in a test of the session")
    }
    val openers = methodsCalling(read.type, read.name).filter { caller ->
        val takes = caller.parameterTypes.map(CharSequence::toString)
        THREAD_KEY in takes && MESSAGE_ID in takes
    }
    if (openers.isEmpty()) refuse("nothing handed a chat and a message asks $where, so it isn't the chat reels test")
    return read
}

/** The viewer's dual paging request, and the class of the source of a chat's reels it pages. */
internal class ChatReelsEnd(val paging: MutableMethod, val chat: String)

private val BRANCHES = setOf(
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
)
private val INTERFACE_CALLS = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)

/**
 * Finds the viewer's dual paging request and the source of a chat's reels, before anything changes.
 * The request is the one method loading [DUAL_PAGING]: an instance method taking a source and three
 * flags, with a local for the hook's answer and nothing jumping to its first instruction. Its first
 * branch is an `if-eqz` on the last flag that goes to the source's page of the chat's own reels, the
 * one method loading [CHAT_REELS_PATH], in a class that is that source. Falling through asks for the
 * stock side, Instagram's suggested reels. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findChatReelsEnd(): ChatReelsEnd {
    val pagings = classesLoadingString(DUAL_PAGING).flatMap { holder -> holder.methods.filter { loadsString(it, DUAL_PAGING) } }
    val paging = pagings.singleOrNull() ?: refuse("expected one method loading \"$DUAL_PAGING\", found ${pagings.size}")
    val takes = paging.parameterTypes.map(CharSequence::toString)
    val where = "${paging.definingClass}->${paging.name}"
    if (AccessFlags.STATIC.isSet(paging.accessFlags) || paging.returnType != "V" || takes.size != 4 ||
        !takes[0].startsWith("L") || takes.drop(1) != listOf("Z", "Z", "Z")
    ) {
        refuse("$where(${takes.joinToString("")})${paging.returnType} isn't an instance request taking a source and three flags")
    }
    if (paging.localRegisterCount() < 1) refuse("$where has no local for the answer")
    if (0 in paging.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")
    val source = takes[0]

    val pages = classesLoadingString(CHAT_REELS_PATH).flatMap { holder -> holder.methods.filter { loadsString(it, CHAT_REELS_PATH) } }
    val page = pages.singleOrNull() ?: refuse("expected one method asking for $CHAT_REELS_PATH, found ${pages.size}")
    val chat = page.definingClass
    if (source !in classDefBy(chat).interfaces) refuse("$chat asks for $CHAT_REELS_PATH but isn't a $source, what $where pages")

    val code = paging.implementation!!.instructions.toList()
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val last = paging.implementation!!.registerCount - 1
    val branch = code.indexOfFirst { it.opcode in BRANCHES }
    val target = if (branch < 0) -1 else address.indexOf(address[branch] + (code[branch] as OffsetInstruction).codeOffset)
    fun sideAt(from: Int): MethodReference? = (from until code.size).firstNotNullOfOrNull { at ->
        if (code[at].opcode in INTERFACE_CALLS) (code[at] as ReferenceInstruction).reference as MethodReference else null
    }
    val chatSide = if (target < 0) null else sideAt(target)
    val stockSide = if (branch < 0) null else sideAt(branch + 1)
    if (branch < 0 || code[branch].opcode != Opcode.IF_EQZ || (code[branch] as OneRegisterInstruction).registerA != last ||
        chatSide?.definingClass != source || chatSide.name != page.name || stockSide == null || stockSide.name == page.name
    ) {
        refuse("the first branch in $where isn't the last flag picking $source->${page.name}, the chat's own reels")
    }

    val mutable = mutableClassDefBy(paging.definingClass).methods.single { method ->
        method.name == paging.name && method.parameterTypes.map(CharSequence::toString) == takes
    }
    return ChatReelsEnd(mutable, chat)
}

/**
 * Asks the extension through [STOP] first in the dual paging request, only when the request is for
 * the stock side (the last flag set) of the source of a chat's reels. A yes returns before anything
 * is asked for, so the viewer ends after the chat's own reels. Everything else goes on to Instagram.
 */
internal fun BytecodePatchContext.stopAfterChatReels(found: ChatReelsEnd = findChatReelsEnd()) {
    val paging = found.paging
    paging.addInstructionsWithLabels(
        0,
        """
            if-eqz p${paging.parameterTypes.size}, :instagram
            move-object/from16 v0, p1
            instance-of v0, v0, ${found.chat}
            if-eqz v0, :instagram
            invoke-static { }, $STOP
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", paging.getInstruction(0)),
    )
}
