/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwipeChatReelsHookTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val gates = "Lfixture/Gates;"
    private val opener = "Lfixture/Opener;"
    private val flagHex = CHAT_REELS_FLAG.toString(16)
    private val source = "Lfixture/Source;"
    private val chatSource = "Lfixture/ChatSource;"
    private val fetcher = "Lfixture/Fetcher;"

    /** The hooks the patch writes are in the ChatReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(CHAIN, STOP)) assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
    }

    /** The dual paging request asks the extension first, for the stock side of a chat's source only (#390). */
    @Test
    fun theStockSideOfAChatAsksFirst() {
        val context = PatchContexts.of(pagingClasses())
        val found = context.findChatReelsEnd()
        val before = found.paging.implementation!!.instructions.count()

        context.stopAfterChatReels(found)

        assertEquals(chatSource, found.chat)
        assertStops("stand-in", context.mutableClassDefBy(fetcher).methods.single { it.name == "page" }, chatSource, before)
        val chat = context.mutableClassDefBy(chatSource).methods.single().code()
        assertTrue("the chat's source was touched", chat.none { it.referenceText() == STOP })
    }

    /** A build the end finder can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildTheEndFinderCantReadFailsBeforeAnythingChanges() {
        val paging = "$fetcher->page"
        val shape = "isn't an instance request taking a source and three flags"
        val branch = "the first branch in $paging isn't the last flag picking $source->chat, the chat's own reels"
        val cases = listOf(
            pagingClasses(pagings = 0) to "expected one method loading \"$DUAL_PAGING\", found 0",
            pagingClasses(pagings = 2) to "expected one method loading \"$DUAL_PAGING\", found 2",
            pagingClasses(static = true) to "$paging(${source}ZZZ)V $shape",
            pagingClasses(takes = listOf(source, "Z", "Z")) to "$paging(${source}ZZ)V $shape",
            pagingClasses(locals = 0) to "$paging has no local for the answer",
            pagingClasses(entered = true) to "a jump or exception handler enters $paging at its first instruction",
            pagingClasses(pages = 0) to "expected one method asking for $CHAT_REELS_PATH, found 0",
            pagingClasses(pages = 2) to "expected one method asking for $CHAT_REELS_PATH, found 2",
            pagingClasses(chatIs = emptyList()) to "$chatSource asks for $CHAT_REELS_PATH but isn't a $source, what $paging pages",
            pagingClasses(flag = "p3") to branch,
            pagingClasses(test = "if-nez") to branch,
            pagingClasses(chatOnTheJump = false) to branch,
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.stopAfterChatReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == STOP } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the dual paging request asks first for the stock side of a chat's source. */
    @Test
    fun eachDeclaredBuildStopsAfterAChatsReels() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                stops(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildStopsAfterAChatsReelsToo() {
        for (base in Fixtures.otherBuilds()) stops(base, base.parentFile.name)
    }

    private fun stops(bundle: File, label: String) {
        val loading = (FixtureDex.classesLoading(bundle, DUAL_PAGING) + FixtureDex.classesLoading(bundle, CHAT_REELS_PATH))
            .distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading))

        val found = context.findChatReelsEnd()
        val before = found.paging.implementation!!.instructions.count()
        context.stopAfterChatReels(found)

        val takes = found.paging.parameterTypes.map(CharSequence::toString)
        val paging = context.mutableClassDefBy(found.paging.definingClass).methods
            .single { it.name == found.paging.name && it.parameterTypes.map(CharSequence::toString) == takes }
        assertStops(label, paging, found.chat, before)
        val hooked = loading.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
            .count { method -> method.code().any { it.referenceText() == STOP } }
        assertEquals("${label}: methods hooked", 1, hooked)
    }

    /**
     * First thing: the last flag tested, the source checked against [chat], the hook asked, and a
     * yes returning. Every no lands on Instagram's own first instruction.
     */
    private fun assertStops(what: String, method: Method, chat: String, before: Int) {
        val code = method.code()
        val last = method.implementation!!.registerCount - 1
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == STOP })
        assertEquals("$what: added", before + 8, code.size)
        assertEquals("$what: the flag test", Opcode.IF_EQZ, code[0].opcode)
        assertEquals("$what: the last flag", last, (code[0] as OneRegisterInstruction).registerA)
        assertEquals("$what: the source's copy", Opcode.MOVE_OBJECT_FROM16, code[1].opcode)
        assertEquals("$what: the source", last - 3, (code[1] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the chat check", Opcode.INSTANCE_OF, code[2].opcode)
        assertEquals("$what: the chat's source", chat, code[2].referenceText())
        assertEquals("$what: the hook", STOP, code[4].referenceText())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[5].opcode)
        assertEquals("$what: the stop", Opcode.RETURN_VOID, code[7].opcode)
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        for (at in listOf(0, 3, 6)) {
            assertEquals("$what: branch $at", Opcode.IF_EQZ, code[at].opcode)
            val target = address.indexOf(address[at] + (code[at] as OffsetInstruction).codeOffset)
            assertEquals("$what: branch $at goes to Instagram", 8, target)
        }
    }

    /**
     * A fetcher shaped like Instagram 453's 0Dyx: its dual paging request loads [DUAL_PAGING], and an
     * `if-eqz` on its last flag jumps to the source's page of the chat's own reels, else asks for the
     * stock side. A source shaped like 0Mfh asks for [CHAT_REELS_PATH] in that page.
     */
    private fun pagingClasses(
        pagings: Int = 1,
        takes: List<String> = listOf(source, "Z", "Z", "Z"),
        static: Boolean = false,
        locals: Int = 2,
        entered: Boolean = false,
        pages: Int = 1,
        chatIs: List<String> = listOf(source),
        flag: String = if (static) "p${takes.size - 1}" else "p${takes.size}",
        test: String = "if-eqz",
        chatOnTheJump: Boolean = true,
    ): List<ClassDef> {
        val (jump, fall) = if (chatOnTheJump) "chat" to "stock" else "stock" to "chat"
        val held = if (static) "p0" else "p1"
        val scratch = if (locals == 0) "p2" else "v${locals - 1}"
        val name = if (pagings == 0) "AbstractPaginatingClipsItemsFetcher.makeFetchRequest" else DUAL_PAGING
        val body = """
            ${if (entered) ":top" else ""}
            const-string $scratch, "$name"
            $test $flag, :chat
            invoke-interface {$held}, $source->$fall()V
            ${if (entered) "goto :top" else "return-void"}
            :chat
            invoke-interface {$held}, $source->$jump()V
            return-void
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
        val requests = List(maxOf(pagings, 1)) { method(fetcher, if (it == 0) "page" else "page$it", takes, "V", locals, flags, body) }
        val asks = List(maxOf(pages, 1)) {
            val load = if (pages == 0) "" else "const-string v0, \"$CHAT_REELS_PATH\"\n"
            method(chatSource, if (it == 0) "chat" else "chat$it", emptyList(), "V", 1, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "${load}return-void")
        }
        return listOf(classDef(fetcher, requests), classDef(chatSource, asks, chatIs))
    }

    /** The chat reels test gets its flag answered through the extension, and nothing else changes. */
    @Test
    fun theTestIsAnswered() {
        val context = PatchContexts.of(classes())

        context.chainChatReels()

        assertAnswered("stand-in", context.mutableClassDefBy(gates).methods.single { it.name == "chain" })
        val asks = context.mutableClassDefBy(opener).methods.single().code()
        assertTrue("the opener was touched", asks.none { it.referenceText() == CHAIN })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val test = "$gates->chain"
        val cases = listOf(
            classes(reads = 0) to "expected one read of the chat reels flag $flagHex, found 0",
            classes(reads = 2) to "expected one read of the chat reels flag $flagHex, found 2",
            classes(flag = CHAT_REELS_FLAG + 1) to "expected one read of the chat reels flag $flagHex, found 0",
            classes(sharedRead = true) to "the read of $flagHex in $test is shared with another flag",
            classes(takes = "Ljava/lang/Object;") to "$flagHex is read in $test(Ljava/lang/Object;)Z, not in a test of the session",
            classes(openerTakes = listOf(THREAD_KEY)) to "nothing handed a chat and a message asks $test, so it isn't the chat reels test",
            classes(openerAsks = false) to "nothing handed a chat and a message asks $test, so it isn't the chat reels test",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.chainChatReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == CHAIN } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the flag's one read is in a test of the session that the opener of a
     * reel shared in a chat asks, and it's answered.
     */
    @Test
    fun eachDeclaredBuildChainsAChatsReels() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildChainsAChatsReelsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val opens = classDef.methods.any { m ->
                    val takes = m.parameterTypes.map(CharSequence::toString)
                    THREAD_KEY in takes && MESSAGE_ID in takes
                }
                if (opens || classDef.methods.any { it.loadsTheFlag() }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val loading = holders.filter { c -> c.methods.any { it.loadsTheFlag() } }
        assertEquals("${label}: classes loading $flagHex", 1, loading.size)
        val context = PatchContexts.of(holders)

        val read = context.findChatReels()
        context.chainChatReels()

        val patched = context.mutableClassDefBy(read.type).methods
        assertAnswered(label, patched.single { it.name == read.name && it.parameterTypes.map(CharSequence::toString) == listOf(session) })
        assertEquals("${label}: methods hooked", 1, patched.count { m -> m.code().any { it.referenceText() == CHAIN } })
    }

    /** Right after the flag's move-result: the range call with that register, and its answer back in it. */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        val load = code.indexOfFirst { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == CHAT_REELS_FLAG }
        assertTrue("$what: no load of $flagHex", load >= 0)
        val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == CHAIN })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", CHAIN, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * Gates shaped like Instagram 453's 01Oo: a test taking the session reads [CHAT_REELS_FLAG]
     * through a cast config and answers it. An opener handed a chat and a message asks it.
     */
    private fun classes(
        reads: Int = 1,
        flag: Long = CHAT_REELS_FLAG,
        sharedRead: Boolean = false,
        takes: String = session,
        openerTakes: List<String> = listOf(THREAD_KEY, MESSAGE_ID),
        openerAsks: Boolean = true,
    ): List<ClassDef> {
        val readFlag = List(reads) {
            """
                invoke-static {v4}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                ${if (sharedRead) "if-eqz v2, :cast$it" else ""}
                const-wide v0, 0x${flag.toString(16)}L
                ${if (sharedRead) ":cast$it" else ""}
                check-cast v2, $configs
                invoke-interface {v2, v0, v1}, $configs->Bae(J)Z
                move-result v0
            """.trimIndent()
        }.joinToString("\n")
        // Three locals, this in v3, the session in v4.
        val test = method(
            gates, "chain", listOf(takes), "Z", 3, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            if (reads == 0) "const/4 v0, 0x0\nreturn v0" else "$readFlag\nreturn v0",
        )
        // One local, this in v1, then the chat, the message and the gates.
        val open = method(
            opener, "open", openerTakes + gates, "V", 1, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            if (openerAsks) {
                "const/4 v0, 0x0\ninvoke-virtual {p${openerTakes.size + 1}, v0}, $gates->chain($takes)Z\nreturn-void"
            } else {
                "return-void"
            },
        )
        return listOf(classDef(gates, listOf(test)), classDef(opener, listOf(open)))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, interfaces: List<String> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", interfaces, null, null, null, methods)

    private fun Method.loadsTheFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == CHAT_REELS_FLAG
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
