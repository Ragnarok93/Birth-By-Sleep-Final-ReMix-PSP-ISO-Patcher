package com.ragnarok93.bbsremix.iso

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoLua51MetadataInspectorTest {
    private class LuaBytes {
        val out = mutableListOf<Byte>()
        fun b(n: Int) { out += n.toByte() }
        fun i(n: Int) {
            for (k in 0 until 4) b(n ushr (8 * k))
        }
        fun str(s: String?) {
            if (s == null) { i(0); return }
            val raw = s.encodeToByteArray()
            i(raw.size + 1)
            raw.forEach { out += it }
            b(0)
        }
        fun proto(
            constants: List<String>,
            nested: Boolean = false,
            opcodes: List<Int> = listOf(30),
        ) {
            str(null) // absent source name
            i(0) // lineDefined
            i(0) // lastLineDefined
            b(0) // upvalues
            b(0) // params
            b(2) // vararg
            b(2) // stack
            i(opcodes.size) // instruction count
            opcodes.forEach { i(it) } // decoded only when operand references a named string
            i(constants.size)
            for (value in constants) {
                b(4) // LUA_TSTRING
                str(value)
            }
            i(if (nested) 1 else 0)
            if (nested) proto(listOf("GetPlayerState"))
            i(1) // debug lineinfo count
            i(0) // one source-line number
            i(0) // local variable count
            i(0) // upvalue names
        }
        fun chunk(): ByteArray {
            val bytes = byteArrayOf(
                0x1b, 'L'.code.toByte(), 'u'.code.toByte(), 'a'.code.toByte(),
                0x51, 0, 1, 4, 4, 4, 4, 0,
            )
            return bytes + out.toByteArray()
        }
    }

    @Test
    fun parses_nested_lua51_structure_and_flags_candidate_strings_without_execution() {
        val body = LuaBytes().apply {
            proto(listOf("SetTrgFlagCancel", "cosmetic"), nested = true)
        }.chunk()
        // The BBSA index gives allocated sectors; appended zeros are
        // padding and must not be assumed to be additional Lua code.
        val input = body + ByteArray(2048 - body.size)
        val before = input.copyOf()
        val report = IsoLua51MetadataInspector.inspect(input)
        assertTrue(report.valid)
        assertEquals(2, report.functions)
        assertEquals(2, report.instructions)
        assertEquals(3, report.constantStrings)
        assertContains(report.visibleExamples, "SetTrgFlagCancel")
        assertContains(report.visibleExamples, "GetPlayerState")
        assertContains(report.visibleExamples, "cosmetic")
        assertContains(report.combatTermExamples, "SetTrgFlagCancel")
        assertContains(report.combatTermExamples, "GetPlayerState")
        assertEquals(body.size, report.consumedBytes)
        assertContains(report.lines.joinToString("\n"), "VALID bounded Lua 5.1")
        assertContains(report.lines.joinToString("\n"), "do NOT prove a")
        assertTrue(before.contentEquals(input))
    }

    @Test
    fun full_lua_string_census_finds_late_hit_event_beyond_truncated_display_examples() {
        val constants = (1..25).map { "gimmick${it.toString().padStart(2, '0')}" } +
            listOf("OnInit", "OnUpdate", "OnHitAttack", "SetMotion",
                "SetTrgFlagCancel", "EntityFactory", "__index")
        val bytes = LuaBytes().apply {
            proto(constants, nested = true)
        }.chunk()
        val input = bytes + ByteArray(4096 - bytes.size)
        val report = IsoLua51MetadataInspector.inspect(input)
        assertTrue(report.valid)
        assertEquals(2, report.functions)
        assertEquals(constants.size + 1, report.constantStrings)
        assertEquals(constants.size + 1, report.printableConstantCount)
        assertEquals(listOf("OnInit", "OnUpdate", "OnHitAttack"),
            report.callbackNameConstants)
        assertContains(report.nativeApiNameConstants, "SetTrgFlagCancel")
        assertContains(report.nativeApiNameConstants, "GetPlayerState")
        assertContains(report.hitEventNameConstants, "OnHitAttack")
        assertFalse(report.visibleExamples.contains("OnHitAttack"))
        assertEquals(2, report.prototypeSummaries.size)
        assertEquals(1, report.prototypeSummaries[0].ordinal)
        assertEquals(0, report.prototypeSummaries[0].depth)
        assertEquals(1, report.prototypeSummaries[1].depth)
        assertContains(report.prototypeSummaries[0].callbackNameConstants, "OnHitAttack")
        assertEquals(1, report.prototypeSummaries[0].instructions)
        assertEquals(1, report.prototypeSummaries[1].instructions)
        assertContains(report.lines.joinToString("\n"), "Exact hit-event-name constants=1: OnHitAttack")
        assertContains(report.lines.joinToString("\n"), "Prototype[2] depth=1")
        assertContains(report.lines.joinToString("\n"), "not execution evidence")
    }

    @Test
    fun valid_gimmick_callback_strings_do_not_imply_a_hit_event_name_constant() {
        val bytes = LuaBytes().apply {
            proto(listOf("g01", "g01_mt", "OnInit", "OnUpdate", "SetMotion"))
        }.chunk()
        val report = IsoLua51MetadataInspector.inspect(bytes)
        assertTrue(report.valid)
        assertEquals(listOf("OnInit", "OnUpdate"), report.callbackNameConstants)
        assertTrue(report.hitEventNameConstants.isEmpty())
        assertTrue(report.nativeApiNameConstants.isEmpty())
        assertEquals(listOf("SetMotion"), report.combatTermExamples)
        assertContains(report.lines.joinToString("\n"), "Exact hit-event-name constants=0: none")
    }

    @Test
    fun lua51_opcode_operand_decoding_distinguishes_table_key_write_and_global_read() {
        fun abc(op: Int, a: Int, b: Int, c: Int): Int =
            op or (a shl 6) or (c shl 14) or (b shl 23)
        val map = mapOf(0 to "OnHitAttack", 1 to "GetPlayerState")
        val setter = IsoLua51MetadataInspector.symbolOpcodeUses(
            abc(9, 0, 256, 1), map)
        assertEquals(1, setter.size)
        assertEquals("SETTABLE", setter.single().opcode)
        assertEquals("OnHitAttack", setter.single().name)
        assertEquals("table key write", setter.single().role)
        val getter = IsoLua51MetadataInspector.symbolOpcodeUses(
            5 or (1 shl 14), map)
        assertEquals("GETGLOBAL", getter.single().opcode)
        assertEquals("GetPlayerState", getter.single().name)
        assertEquals("global name read", getter.single().role)
        val reader = IsoLua51MetadataInspector.symbolOpcodeUses(
            abc(6, 0, 0, 256), map)
        assertEquals("GETTABLE", reader.single().opcode)
        assertEquals("table key read", reader.single().role)
        assertTrue(IsoLua51MetadataInspector.symbolOpcodeUses(
            abc(9, 0, 0, 1), map).isEmpty())
    }

    @Test
    fun reports_validated_symbol_opcode_provenance_but_no_runtime_behavior() {
        val setTable = 9 or (256 shl 23)
        val getGlobal = 5 or (1 shl 14)
        val bytecode = LuaBytes().apply {
            proto(listOf("OnHitAttack", "GetPlayerState", "SetMotion"),
                opcodes = listOf(setTable, getGlobal, 30))
        }.chunk()
        val report = IsoLua51MetadataInspector.inspect(bytecode)
        assertTrue(report.valid)
        assertEquals(2, report.opcodeSymbolReferences.size)
        assertEquals("OnHitAttack", report.opcodeSymbolReferences[0].constantName)
        assertEquals(0, report.opcodeSymbolReferences[0].pc)
        assertEquals("SETTABLE", report.opcodeSymbolReferences[0].opcode)
        assertEquals("GETGLOBAL", report.opcodeSymbolReferences[1].opcode)
        assertEquals("GetPlayerState", report.opcodeSymbolReferences[1].constantName)
        assertContains(report.lines.joinToString("\n"), "OPCODE_REF proto=1")
        assertTrue(report.lines.any { it.contains("not execution") })
        val broken = bytecode.copyOf(bytecode.size - 2)
        assertTrue(IsoLua51MetadataInspector.inspect(broken).opcodeSymbolReferences.isEmpty())
    }

    @Test
    fun exact_hit_event_value_operand_and_immediate_closure_provenance() {
        fun abc(op: Int, a: Int, b: Int, c: Int): Int =
            op or (a shl 6) or (c shl 14) or (b shl 23)
        fun abx(op: Int, a: Int, bx: Int): Int =
            op or (a shl 6) or (bx shl 14)

        val closureIntoR2 = abx(36, 2, 0) // CLOSURE R2, child-proto 0
        val hitWrite = abc(9, 0, 256, 2) // SETTABLE R0, K0, R2
        val operands = IsoLua51MetadataInspector.hitEventTableWrite(
            hitWrite, closureIntoR2,
            mapOf(0 to "OnHitAttack"), childCount = 1,
        )
        assertEquals("OnHitAttack", operands?.eventName)
        assertEquals(0, operands?.tableRegister)
        assertEquals("R[2]", operands?.valueOperand)
        assertEquals(0, operands?.adjacentClosureProtoIndex)

        // A register assignment is NOT automatically a child Lua closure.
        assertEquals(null, IsoLua51MetadataInspector.hitEventTableWrite(
            hitWrite, abx(36, 3, 0), mapOf(0 to "OnHitAttack"), 1
        )?.adjacentClosureProtoIndex)
        assertEquals(null, IsoLua51MetadataInspector.hitEventTableWrite(
            hitWrite, abx(36, 2, 1), mapOf(0 to "OnHitAttack"), 1
        )?.adjacentClosureProtoIndex)
        assertEquals(null, IsoLua51MetadataInspector.hitEventTableWrite(
            hitWrite, null, mapOf(0 to "OnHitAttack"), 1
        )?.adjacentClosureProtoIndex)
        assertTrue(IsoLua51MetadataInspector.hitEventTableWrite(
            abc(9, 0, 0, 2), closureIntoR2,
            mapOf(0 to "OnHitAttack"), 1
        ) == null)

        val bytes = LuaBytes().apply {
            proto(listOf("OnHitAttack"), nested = true,
                opcodes = listOf(closureIntoR2, hitWrite, 30))
        }.chunk()
        val result = IsoLua51MetadataInspector.inspect(bytes)
        assertTrue(result.valid)
        assertEquals(1, result.hitEventTableWrites.size)
        val found = result.hitEventTableWrites.single()
        assertEquals(1, found.ordinal)
        assertEquals(1, found.pc)
        assertEquals("OnHitAttack", found.eventName)
        assertEquals("R[2]", found.valueOperand)
        assertEquals(0, found.adjacentClosureProtoIndex)
        assertEquals(2, found.adjacentClosureFunctionOrdinal)
        assertEquals(1, found.adjacentClosureInstructionCount)
        assertContains(found.adjacentClosureNativeApis, "GetPlayerState")
        assertContains(found.adjacentClosureCombatConstants, "GetPlayerState")
        assertContains(result.lines.joinToString("\n"),
            "adjacent_child_closure=0")
        assertContains(result.lines.joinToString("\n"),
            "resolved_child_proto=2")
        assertContains(result.lines.joinToString("\n"),
            "child_native_API_constants=GetPlayerState")
    }

    @Test
    fun unresolved_hit_value_reports_preceding_closure_and_move_without_claiming_register_flow() {
        // The immediate-predecessor-only rule cannot identify the value
        // when a MOVE appears between a CLOSURE and a table assignment.
        // We deliberately DO NOT infer the move's origin; the raw context
        // is diagnostic evidence for a later, separately verified pass.
        fun abc(op: Int, a: Int, b: Int, c: Int): Int =
            op or (a shl 6) or (c shl 14) or (b shl 23)
        fun abx(op: Int, a: Int, bx: Int): Int =
            op or (a shl 6) or (bx shl 14)

        val closure = abx(36, 2, 0)  // CLOSURE R2 <- child 0
        val move = abc(0, 3, 2, 0) // MOVE R3 <- R2
        val hitWrite = abc(9, 0, 256, 3) // R0["OnHitAttack"] <- R3
        val raw = LuaBytes().apply {
            proto(listOf("OnHitAttack"), nested = true,
                opcodes = listOf(closure, move, hitWrite, 30))
        }.chunk()
        val report = IsoLua51MetadataInspector.inspect(raw)
        assertTrue(report.valid)
        val write = report.hitEventTableWrites.single()
        assertEquals(2, write.pc)
        assertEquals("R[3]", write.valueOperand)
        assertEquals(null, write.adjacentClosureFunctionOrdinal)
        assertEquals(2, write.unresolvedPriorInstructions.size)
        assertContains(write.unresolvedPriorInstructions[0], "op=CLOSURE A=2")
        assertContains(write.unresolvedPriorInstructions[1], "op=MOVE A=3 B=2")
        assertContains(report.lines.joinToString("\n"), "UNRESOLVED HIT VALUE provenance")
        assertContains(report.lines.joinToString("\n"), "no dataflow proof")
    }

    @Test
    fun unresolved_opcode_context_is_clamped_to_seven_previous_instructions() {
        fun abc(op: Int, a: Int, b: Int, c: Int): Int =
            op or (a shl 6) or (c shl 14) or (b shl 23)
        val opcodes = List(12) { abc(0, 1, 2, 0) } +
            abc(9, 0, 256, 3) + 30
        val report = IsoLua51MetadataInspector.inspect(LuaBytes().apply {
            proto(listOf("OnHitAttack"), opcodes = opcodes)
        }.chunk())
        assertTrue(report.valid)
        val lines = report.hitEventTableWrites.single().unresolvedPriorInstructions
        assertEquals(7, lines.size)
        assertTrue(lines.first().startsWith("pc=5 "))
        assertTrue(lines.last().startsWith("pc=11 "))
    }

    @Test
    fun malformed_or_truncated_protos_never_claim_validity_or_string_matches() {
        val chunk = LuaBytes().apply {
            proto(listOf("OnHitAttack"))
        }.chunk()
        val badEnd = chunk.copyOf(chunk.size - 4)
        val report = IsoLua51MetadataInspector.inspect(badEnd)
        assertFalse(report.valid)
        assertTrue(report.visibleExamples.isEmpty())
        assertTrue(report.combatTermExamples.isEmpty())
        assertTrue(report.hitEventNameConstants.isEmpty())
        assertTrue(report.prototypeSummaries.isEmpty())
        assertContains(report.lines.joinToString("\n"), "UNVERIFIED")

        // Replace the first Lua constants type with an invalid tag.
        val broken = chunk.copyOf()
        val at = broken.indexOfSlice("OnHitAttack".encodeToByteArray())
        assertTrue(at > 0)
        broken[at - 5] = 127.toByte() // constant type before size_t
        assertFalse(IsoLua51MetadataInspector.inspect(broken).valid)
    }

    @Test
    fun rejects_unsupported_abi_and_oversized_input() {
        val chunk = LuaBytes().apply { proto(emptyList()) }.chunk()
        assertTrue(IsoLua51MetadataInspector.inspect(chunk).valid)
        val big = chunk + ByteArray(IsoLua51MetadataInspector.MAX_INPUT_BYTES)
        assertFalse(IsoLua51MetadataInspector.inspect(big).valid)
        val endian = chunk.copyOf()
        endian[6] = 0
        assertFalse(IsoLua51MetadataInspector.inspect(endian).valid)
        val number = chunk.copyOf()
        number[10] = 16
        assertFalse(IsoLua51MetadataInspector.inspect(number).valid)
        val magic = chunk.copyOf()
        magic[0] = 0
        assertFalse(IsoLua51MetadataInspector.inspect(magic).valid)
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        for (i in 0..(size - needle.size)) {
            if (needle.indices.all { this[i + it] == needle[it] }) return i
        }
        return -1
    }
}
