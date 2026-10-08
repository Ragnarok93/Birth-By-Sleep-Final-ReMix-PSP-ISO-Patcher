package com.ragnarok93.bbsremix.bbs0

import com.ragnarok93.bbsremix.patch.CancellationToken
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Bbs0UiExporterTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun exports_compact_layouts_and_index_without_modifying_original() {
        val source = path("source")
        val output = path("ui-output", "zip")
        try {
            val input = fixture()
            fs.sink(source).buffer().use { it.write(input) }
            val result = Bbs0UiExporter.export(source, output)
            assertEquals(1, result.archiveCount)
            assertEquals(1, result.layoutsFound)
            assertEquals(1, result.externalLinks)
            assertEquals(1, result.layoutsExported)
            assertTrue(result.zipSize > 0)
            assertTrue(result.zipSize < input.size)
            assertTrue(fs.exists(output))
            assertTrue(input.contentEquals(fs.source(source).buffer().use { it.readByteArray() }))
        } finally {
            fs.delete(source, mustExist = false)
            fs.delete(output, mustExist = false)
        }
    }

    @Test
    fun in_app_export_includes_standalone_linked_ctd_and_respects_metadata_only() {
        val source = path("linked-ctd-source")
        val output = path("linked-ctd-full", "zip")
        val metadata = path("linked-ctd-index", "zip")
        try {
            fs.sink(source).buffer().use { it.write(fixtureWithStandaloneCtd()) }
            val full = Bbs0UiExporter.export(source, output)
            assertEquals(1, full.externalLinks)
            assertEquals(1, full.standaloneCtdLocated)
            assertEquals(1, full.standaloneCtdExported)
            assertTrue(full.zipSize > 0)
            val indexOnly = Bbs0UiExporter.export(source, metadata, metadataOnly = true)
            assertEquals(1, indexOnly.standaloneCtdLocated)
            assertEquals(0, indexOnly.standaloneCtdExported)
        } finally {
            fs.delete(source, mustExist = false)
            fs.delete(output, mustExist = false)
            fs.delete(metadata, mustExist = false)
        }
    }

    @Test
    fun metadata_only_creates_small_export_without_layout_payloads() {
        val source = path("metadata-source")
        val output = path("metadata-output", "zip")
        try {
            fs.sink(source).buffer().use { it.write(fixture()) }
            val result = Bbs0UiExporter.export(source, output, metadataOnly = true)
            assertEquals(0, result.layoutsExported)
            assertEquals(1, result.layoutsFound)
            assertTrue(result.zipSize > 0)
        } finally {
            fs.delete(source, mustExist = false)
            fs.delete(output, mustExist = false)
        }
    }

    @Test
    fun fails_closed_for_wrong_archive_version_or_cancellation() {
        val source = path("invalid-source")
        val output = path("invalid-output", "zip")
        try {
            fs.sink(source).buffer().use { it.write(fixture().apply { this[0] = 0 }) }
            assertFailsWith<IllegalArgumentException> {
                Bbs0UiExporter.export(source, output)
            }
            assertTrue(!fs.exists(output))
            fs.sink(source).buffer().use { it.write(fixture()) }
            assertFailsWith<Cancelled> {
                Bbs0UiExporter.export(source, output, cancellation = object : CancellationToken {
                    override val isCancelled: Boolean get() = true
                    override fun throwIfCancelled() { throw Cancelled() }
                })
            }
            assertTrue(!fs.exists(output))
        } finally {
            fs.delete(source, mustExist = false)
            fs.delete(output, mustExist = false)
        }
    }

    private fun fixtureWithStandaloneCtd(): ByteArray = fixture().copyOf(8192).apply {
        writeInt(0x14, 0x100) // BBSA global directory starts at 0x100
        writeShort(0x0e, 1) // one directory record
        writeInt(0x20, 50) // Archive 1 begins after logical sector 2
        writeInt(0x100, 0xf36e5993.toInt()) // CRC32 CT00000 stem
        writeInt(0x104, (2 shl 12) or 1) // one sector at BBS0 physical 3
        writeInt(0x108, 0xd0000000.toInt())
        val second = 2048 + 48
        writeInt(second, 0xd0000000.toInt())
        "CT00000.ctd".encodeToByteArray().copyInto(this, second + 16)
        "@CTD".encodeToByteArray().copyInto(this, 6144)
        writeInt(6148, 1) // CTD version 1
        writeInt(6144 + 0x10, 0x20) // messages offset
        writeInt(6144 + 0x14, 0x20) // layouts offset
        writeInt(6144 + 0x18, 0x20) // text offset
    }

    private fun fixture(): ByteArray = ByteArray(4096).apply {
        "bbsa".encodeToByteArray().copyInto(this, 0)
        writeInt(4, 6)
        writeShort(0x1a, 1)
        val base = 2048
        byteArrayOf(65, 82, 67, 0).copyInto(this, base)
        writeShort(base + 4, 1)
        writeShort(base + 6, 2)
        // one embedded L2D, one externally linked CTD
        writeInt(base + 16 + 4, 0x60)
        writeInt(base + 16 + 8, 0x40)
        "comm_00.l2d".encodeToByteArray().copyInto(this, base + 16 + 16)
        writeInt(base + 48, 0x12345678)
        "dialog.ctd".encodeToByteArray().copyInto(this, base + 48 + 16)
        "L2D@".encodeToByteArray().copyInto(this, base + 0x60)
        writeInt(base + 0x60 + 0x2c, 0x40)
    }

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        for (i in 0..3) this[offset + i] = (value ushr (i * 8)).toByte()
    }
    private fun ByteArray.writeShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }
    private fun path(name: String, ext: String = "dat"): Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("bbs0-ui-export-test-" + name + "." + ext)
    private class Cancelled : RuntimeException()
}
