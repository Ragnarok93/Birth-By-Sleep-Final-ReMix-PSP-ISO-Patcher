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
        } finally {
            fileSystem.delete(source, mustExist = false)
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
    fun ambiguous_target_path_is_rejected() {
        val source = createFixture("ambiguous")
        try {
            val image = reader.inspect(source)
            val duplicate = record("EBOOT.BIN;2", 24, 3000, false)
            fileSystem.source(source).buffer().use { input ->
                val bytes = input.readByteArray()
                duplicate.copyInto(bytes, (22 * SECTOR_SIZE) + 160)
                fileSystem.sink(source).buffer().use { output -> output.write(bytes) }
            }
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
        writeDirectory(image, 21, listOf(
            record("\u0000", 21, SECTOR_SIZE, true),
            record("\u0001", 20, SECTOR_SIZE, true),
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
        KEEP_BYTES.copyInto(image, KEEP_SECTOR * SECTOR_SIZE)

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
