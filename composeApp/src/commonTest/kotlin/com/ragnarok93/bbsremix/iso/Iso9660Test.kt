package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.NoProgress
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Iso9660Test {
    private val fileSystem = FileSystem.SYSTEM
    private val reader = Iso9660Reader(fileSystem)
    private val rebuilder = Iso9660Rebuilder(fileSystem)

    @Test
    fun locates_versioned_target_path_and_preserves_directory_metadata() {
        val source = createFixture("lookup")
        try {
            val image = reader.inspect(source)

            assertEquals("EBOOT.BIN;1", image.eboot.name)
            assertEquals("PSP_GAME/SYSDIR/EBOOT.BIN;1", image.eboot.path)
            assertEquals(24L, image.eboot.extentSector)
            assertEquals(3000L, image.eboot.size)
            assertEquals(64L, image.volumeSpaceSize)
            assertEquals("PSP_GAME/ICON0.PNG;1", image.coverArt?.path)
            assertEquals("ULJM05775", image.discSerial)
            assertContentEquals(COVER_ART, reader.readEntry(source, image.coverArt!!))
        } finally {
            fileSystem.delete(source, mustExist = false)
        }
    }

    @Test
    fun identical_replacement_produces_a_byte_identical_iso() {
        val source = createFixture("identity")
        val destination = path("identity-output")
        try {
            val originalImage = fileSystem.source(source).buffer().use { it.readByteArray() }
            val image = reader.inspect(source)
            val originalEboot = reader.readEntry(source, image.eboot)

            rebuilder.rebuild(source, destination, image, originalEboot)

            val rebuiltImage = fileSystem.source(destination).buffer().use { it.readByteArray() }
            val rebuilt = reader.inspect(destination)
            assertContentEquals(originalImage, rebuiltImage)
            assertEquals(image.eboot.extentSector, rebuilt.eboot.extentSector)
            assertEquals(image.eboot.size, rebuilt.eboot.size)
            assertContentEquals(originalEboot, reader.readEntry(destination, rebuilt.eboot))
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }

    @Test
    fun larger_replacement_is_appended_and_unrelated_sectors_are_unchanged() {
        val source = createFixture("growth")
        val destination = path("growth-output")
        val replacement = ByteArray(5000) { (it * 7).toByte() }
        try {
            val image = reader.inspect(source)
            rebuilder.rebuild(source, destination, image, replacement)
            val output = reader.inspect(destination)

            assertTrue(output.eboot.extentSector >= 64L)
            assertEquals(replacement.size.toLong(), output.eboot.size)
            assertContentEquals(replacement, reader.readEntry(destination, output.eboot))
            assertContentEquals(
                KEEP_BYTES,
                reader.readAt(destination, KEEP_SECTOR * SECTOR_SIZE.toLong(), KEEP_BYTES.size),
            )
            assertTrue(output.volumeSpaceSize >= 67L)
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }

    @Test
    fun replacement_that_fits_existing_extent_does_not_move_the_file() {
        val source = createFixture("in-place")
        val destination = path("in-place-output")
        val replacement = ByteArray(3500) { (255 - it).toByte() }
        try {
            val image = reader.inspect(source)
            rebuilder.rebuild(source, destination, image, replacement)
            val output = reader.inspect(destination)

            assertEquals(image.eboot.extentSector, output.eboot.extentSector)
            assertEquals(replacement.size.toLong(), output.eboot.size)
            assertContentEquals(replacement, reader.readEntry(destination, output.eboot))
            assertEquals(image.volumeSpaceSize, output.volumeSpaceSize)
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }

    @Test
    fun cancellation_removes_partial_destination() {
        val source = createFixture("cancel")
        val destination = path("cancel-output")
        val token = CancelAfterFirstCheck()
        try {
            val image = reader.inspect(source)
            assertFailsWith<CancelledFixtureException> {
                rebuilder.rebuild(source, destination, image, ByteArray(5000), token, NoProgress)
            }
            assertTrue(!fileSystem.exists(destination))
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }

    @Test
    fun existing_destination_is_not_deleted_when_rebuild_is_rejected() {
        val source = createFixture("existing-destination-source")
        val destination = path("existing-destination-output")
        val sentinel = "do-not-delete".encodeToByteArray()
        try {
            fileSystem.sink(destination).buffer().use { it.write(sentinel) }
            val image = reader.inspect(source)

            assertFailsWith<IsoFormatException> {
                rebuilder.rebuild(source, destination, image, ByteArray(16))
            }
            assertContentEquals(sentinel, fileSystem.source(destination).buffer().use { it.readByteArray() })
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }

    @Test
    fun guarded_byte_overlay_patches_other_entry_without_repacking_or_changing_eboot() {
        val source = createFixture("overlay-source")
        val dest = path("overlay-output")
        val position = KEEP_SECTOR * SECTOR_SIZE.toLong() + 5L
        try {
            val image = reader.inspect(source)
            val originalEboot = reader.readEntry(source, image.eboot)
            val preimage = reader.readAt(source, position, 4)
            val replacement = "TEST".encodeToByteArray()
            rebuilder.rebuild(
                source, dest, image, originalEboot,
                extraPatches = listOf(IsoBytePatch(position, preimage, replacement, "test-layout")),
            )
            assertContentEquals(replacement, reader.readAt(dest, position, 4))
            assertContentEquals(originalEboot, reader.readEntry(dest, reader.inspect(dest).eboot))
            val sourceData = fileSystem.source(source).buffer().use { it.readByteArray() }
            val outputData = fileSystem.source(dest).buffer().use { it.readByteArray() }
            replacement.copyInto(sourceData, position.toInt())
            assertContentEquals(sourceData, outputData)
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(dest, mustExist = false)
        }
    }

    @Test
    fun multiple_ui_overlays_and_relocated_eboot_survive_one_iso_rebuild() {
        val source = createFixture("ui-multi-source")
        val destination = path("ui-multi-output")
        try {
            val image = reader.inspect(source)
            val eboot = ByteArray(5000) { ((it * 17) and 255).toByte() }
            val offsets = listOf(
                23L * SECTOR_SIZE + 1L,
                KEEP_SECTOR.toLong() * SECTOR_SIZE + 2L,
            )
            val replacements = listOf("HUD".encodeToByteArray(), "CTD".encodeToByteArray())
            val overlays = offsets.zip(replacements).mapIndexed { index, (offset, replacement) ->
                IsoBytePatch(
                    offset, reader.readAt(source, offset, replacement.size),
                    replacement, "ui-test-" + index,
                )
            }
            rebuilder.rebuild(source, destination, image, eboot, extraPatches = overlays)
            val result = reader.inspect(destination)
            assertTrue(result.eboot.extentSector != image.eboot.extentSector)
            assertContentEquals(eboot, reader.readEntry(destination, result.eboot))
            overlays.forEach { patch ->
                assertContentEquals(
                    patch.replacement,
                    reader.readAt(destination, patch.absoluteOffset, patch.replacement.size),
                )
            }
            assertContentEquals(
                reader.readAt(source, 23L * SECTOR_SIZE, 1),
                reader.readAt(destination, 23L * SECTOR_SIZE, 1),
            )
            assertContentEquals(
                reader.readAt(source, KEEP_SECTOR.toLong() * SECTOR_SIZE, 2),
                reader.readAt(destination, KEEP_SECTOR.toLong() * SECTOR_SIZE, 2),
            )
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }

    @Test
    fun overlapping_ui_overlay_preimages_are_rejected_without_an_output() {
        val source = createFixture("ui-overlap-source")
        val destination = path("ui-overlap-output")
        try {
            val image = reader.inspect(source)
            val eboot = reader.readEntry(source, image.eboot)
            val a = 23L * SECTOR_SIZE + 1L
            val b = a + 2L
            assertFailsWith<IsoFormatException> {
                rebuilder.rebuild(source, destination, image, eboot,
                    extraPatches = listOf(
                        IsoBytePatch(a, reader.readAt(source, a, 4),
                            byteArrayOf(1, 2, 3, 4), "first"),
                        IsoBytePatch(b, reader.readAt(source, b, 4),
                            byteArrayOf(5, 6, 7, 8), "second"),
                    ),
                )
            }
            assertTrue(!fileSystem.exists(destination))
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }

    @Test
    fun overlay_rejects_wrong_preimage_and_eboot_overlap_without_creating_destination() {
        val source = createFixture("overlay-reject-source")
        val dest = path("overlay-reject-output")
        try {
            val image = reader.inspect(source)
            val original = reader.readEntry(source, image.eboot)
            val position = KEEP_SECTOR * SECTOR_SIZE.toLong()
            assertFailsWith<IsoFormatException> {
                rebuilder.rebuild(
                    source, dest, image, original,
                    extraPatches = listOf(
                        IsoBytePatch(position, byteArrayOf(0x00), byteArrayOf(0x01), "wrong"),
                    ),
                )
            }
            assertTrue(!fileSystem.exists(dest))
            assertFailsWith<IsoFormatException> {
                rebuilder.rebuild(
                    source, dest, image, original,
                    extraPatches = listOf(
                        IsoBytePatch(image.eboot.dataOffset, byteArrayOf(0), byteArrayOf(1), "overlap"),
                    ),
                )
            }
            assertTrue(!fileSystem.exists(dest))
            assertFailsWith<IsoFormatException> {
                rebuilder.rebuild(
                    source, dest, image, original,
                    extraPatches = listOf(
                        IsoBytePatch(position, byteArrayOf(1), byteArrayOf(2, 3), "resize"),
                    ),
                )
            }
            assertTrue(!fileSystem.exists(dest))
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(dest, mustExist = false)
        }
    }

    @Test
    fun ambiguous_target_path_is_rejected() {
        val source = createFixture("ambiguous")
        try {
            val image = reader.inspect(source)
            val duplicate = record("PSP_GAME;2", 21, SECTOR_SIZE, true)
            val bytes = fileSystem.source(source).buffer().use { it.readByteArray() }
            duplicate.copyInto(bytes, (20 * SECTOR_SIZE) + 154)
            fileSystem.sink(source).buffer().use { it.write(bytes) }
            assertFailsWith<IsoFormatException> { reader.inspect(source) }
            assertEquals(24L, image.eboot.extentSector)
        } finally {
            fileSystem.delete(source, mustExist = false)
        }
    }

    private fun createFixture(label: String): Path {
        val path = path(label)
        fileSystem.delete(path, mustExist = false)
        val image = ByteArray(VOLUME_SECTORS * SECTOR_SIZE)
        val pvd = image.copyOfRange(16 * SECTOR_SIZE, 17 * SECTOR_SIZE)
        pvd[0] = 1
        "CD001".encodeToByteArray().copyInto(pvd, 1)
        pvd[6] = 1
        writeBoth(pvd, 80, VOLUME_SECTORS.toLong())
        writeBoth(pvd, 128, SECTOR_SIZE.toLong())
        val root = record("\u0000", 20, SECTOR_SIZE, directory = true)
        root.copyInto(pvd, 156)
        pvd.copyInto(image, 16 * SECTOR_SIZE)

        val terminator = image.copyOfRange(17 * SECTOR_SIZE, 18 * SECTOR_SIZE)
        terminator[0] = 255.toByte()
        "CD001".encodeToByteArray().copyInto(terminator, 1)
        terminator[6] = 1
        terminator.copyInto(image, 17 * SECTOR_SIZE)

        writeDirectory(image, 20, listOf(
            record("\u0000", 20, SECTOR_SIZE, true),
            record("\u0001", 20, SECTOR_SIZE, true),
            record("PSP_GAME", 21, SECTOR_SIZE, true),
            record("OTHER.BIN;1", 23, OTHER_BYTES.size, false),
        ))
        val paramSfo = paramSfo("ULJM05775")
        writeDirectory(image, 21, listOf(
            record("\u0000", 21, SECTOR_SIZE, true),
            record("\u0001", 20, SECTOR_SIZE, true),
            record("ICON0.PNG;1", 25, COVER_ART.size, false),
            record("PARAM.SFO;1", 27, paramSfo.size, false),
            record("SYSDIR", 22, SECTOR_SIZE, true),
        ))
        writeDirectory(image, 22, listOf(
            record("\u0000", 22, SECTOR_SIZE, true),
            record("\u0001", 21, SECTOR_SIZE, true),
            record("EBOOT.BIN;1", 24, 3000, false),
            record("KEEP.DAT;1", KEEP_SECTOR, KEEP_BYTES.size, false),
        ))
        OTHER_BYTES.copyInto(image, 23 * SECTOR_SIZE)
        ByteArray(3000) { it.toByte() }.copyInto(image, 24 * SECTOR_SIZE)
        COVER_ART.copyInto(image, 25 * SECTOR_SIZE)
        KEEP_BYTES.copyInto(image, KEEP_SECTOR * SECTOR_SIZE)
        paramSfo.copyInto(image, 27 * SECTOR_SIZE)

        fileSystem.sink(path).buffer().use { it.write(image) }
        return path
    }

    private fun writeDirectory(image: ByteArray, sector: Int, records: List<ByteArray>) {
        var offset = sector * SECTOR_SIZE
        records.forEach { record ->
            record.copyInto(image, offset)
            offset += record.size
        }
    }

    private fun record(name: String, extent: Int, size: Int, directory: Boolean): ByteArray {
        val identifier = when (name) {
            "\u0000" -> byteArrayOf(0)
            "\u0001" -> byteArrayOf(1)
            else -> name.encodeToByteArray()
        }
        var length = 33 + identifier.size
        if (length % 2 != 0) length++
        return ByteArray(length).also {
            it[0] = length.toByte()
            writeBoth(it, 2, extent.toLong())
            writeBoth(it, 10, size.toLong())
            it[25] = if (directory) IsoDirectoryEntry.DIRECTORY_FLAG.toByte() else 0
            it[32] = identifier.size.toByte()
            identifier.copyInto(it, 33)
        }
    }

    private fun paramSfo(discId: String): ByteArray {
        val key = "DISC_ID\u0000".encodeToByteArray()
        val value = (discId + "\u0000").encodeToByteArray()
        val keyTableOffset = 36
        val dataTableOffset = keyTableOffset + key.size
        return ByteArray(dataTableOffset + value.size).also { bytes ->
            byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()).copyInto(bytes)
            writeU32(bytes, 8, keyTableOffset)
            writeU32(bytes, 12, dataTableOffset)
            writeU32(bytes, 16, 1)
            writeU16(bytes, 20, 0)
            writeU16(bytes, 22, 0x0204)
            writeU32(bytes, 24, value.size)
            writeU32(bytes, 28, value.size)
            writeU32(bytes, 32, 0)
            key.copyInto(bytes, keyTableOffset)
            value.copyInto(bytes, dataTableOffset)
        }
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun path(label: String): Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-iso-${label.replace(' ', '-')}.iso"

    private class CancelAfterFirstCheck : CancellationToken {
        private var checks = 0
        override val isCancelled: Boolean
            get() = ++checks > 1

        override fun throwIfCancelled() {
            if (isCancelled) throw CancelledFixtureException()
        }
    }

    private class CancelledFixtureException : Exception()

    private companion object {
        const val SECTOR_SIZE = 2048
        const val VOLUME_SECTORS = 64
        const val KEEP_SECTOR = 26
        val OTHER_BYTES = "unrelated-file".encodeToByteArray()
        val COVER_ART = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x63, 0x6f, 0x76, 0x65, 0x72,
        )
        val KEEP_BYTES = "keep-this-file".encodeToByteArray()

        fun writeBoth(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
            bytes[offset + 3] = (value ushr 24).toByte()
            bytes[offset + 4] = (value ushr 24).toByte()
            bytes[offset + 5] = (value ushr 16).toByte()
            bytes[offset + 6] = (value ushr 8).toByte()
            bytes[offset + 7] = value.toByte()
        }
    }
}
