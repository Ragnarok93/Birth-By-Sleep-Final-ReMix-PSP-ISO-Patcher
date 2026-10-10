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
        ) {
            str(null) // absent source name
            i(0) // lineDefined
            i(0) // lastLineDefined
            b(0) // upvalues
            b(0) // params
            b(2) // vararg
            b(2) // stack
            i(1) // instruction count
            i(30) // stand-in RETURN opcode; no opcode decoding performed
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
    fun malformed_or_truncated_protos_never_claim_validity_or_string_matches() {
        val chunk = LuaBytes().apply {
            proto(listOf("OnHitAttack"))
        }.chunk()
        val badEnd = chunk.copyOf(chunk.size - 4)
        val report = IsoLua51MetadataInspector.inspect(badEnd)
        assertFalse(report.valid)
        assertTrue(report.visibleExamples.isEmpty())
        assertTrue(report.combatTermExamples.isEmpty())
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
