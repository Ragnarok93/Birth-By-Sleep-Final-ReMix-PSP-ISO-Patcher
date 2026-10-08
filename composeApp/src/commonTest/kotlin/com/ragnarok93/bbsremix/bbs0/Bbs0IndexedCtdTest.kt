package com.ragnarok93.bbsremix.bbs0

import com.ragnarok93.bbsremix.patch.NeverCancelled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Bbs0IndexedCtdTest {
    private fun fixture(): ByteArray = ByteArray(8192).apply {
        "bbsa".encodeToByteArray().copyInto(this, 0)
        writeInt(4, 6)
        writeShort(0x1a, 1) // archive0 begins at sector 1
        writeInt(0x20, 50) // archive1 logical threshold; BBS0 CTD at logical 2
        writeShort(0x0e, 1) // directory entries
        writeInt(0x14, 0x100) // directory entries start
        writeInt(0x100, 0xf36e5993.toInt()) // crc32("CT00000")
        writeInt(0x104, (2 shl 12) or 1)
        writeInt(0x108, 0xd0000000.toInt()) // system language zero
        "@CTD".encodeToByteArray().copyInto(this, 6144)
        writeInt(6148, 1)
        writeInt(6144 + 0x10, 0x20)
        writeInt(6144 + 0x14, 0x20)
        writeInt(6144 + 0x18, 0x20)
    }

    @Test
    fun indexed_ctd_reference_reads_correct_bbs0_sector() {
        val file = fixture()
        val result = Bbs0IndexedCtd.resolve(
            file.copyOfRange(0, 2048), file.size.toLong(),
            { offset, count -> file.copyOfRange(offset.toInt(), offset.toInt() + count) },
            listOf(Bbs0IndexedCtd.Reference("CT00000.ctd", 0xd0000000.toInt())),
            NeverCancelled,
        )
        assertTrue(result.unresolved.isEmpty())
        assertEquals(1, result.resources.size)
        assertEquals(6144L, result.resources.single().offset)
        assertEquals(2048, result.resources.single().data.size)
        assertEquals(64, result.resources.single().sha256.length)
    }

    @Test
    fun only_exact_directory_hash_and_valid_ctd_payload_can_be_used() {
        val file = fixture()
        val refs = listOf(
            Bbs0IndexedCtd.Reference("CT00000.ctd", 0xd2000000.toInt()),
            Bbs0IndexedCtd.Reference("CT00000.ctd", 0xd0000000.toInt()),
        )
        fun run(input: ByteArray): Bbs0IndexedCtd.Result =
            Bbs0IndexedCtd.resolve(
                input.copyOfRange(0, 2048), input.size.toLong(),
                { offset, count -> input.copyOfRange(offset.toInt(), offset.toInt() + count) },
                refs, NeverCancelled,
            )
        assertEquals(1, run(file).resources.size)
        assertEquals(1, run(file).unresolved.size)
        val corrupt = file.copyOf().apply { this[6144] = 0 }
        assertTrue(run(corrupt).resources.isEmpty())
        assertEquals(2, run(corrupt).unresolved.size)
        val wrongArchive = file.copyOf().apply { writeInt(0x20, 2) }
        assertTrue(run(wrongArchive).resources.isEmpty())
    }

    private fun ByteArray.writeInt(pos: Int, value: Int) {
        for (i in 0..3) this[pos + i] = (value ushr (i * 8)).toByte()
    }
    private fun ByteArray.writeShort(pos: Int, value: Int) {
        this[pos] = value.toByte()
        this[pos + 1] = (value ushr 8).toByte()
    }
}
