package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.NoProgress
import com.ragnarok93.bbsremix.patch.PatchPhase
import com.ragnarok93.bbsremix.patch.PatchProgress
import com.ragnarok93.bbsremix.patch.ProgressReporter
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.math.max
import kotlin.math.min

data class IsoDirectoryEntry(
    val path: String,
    val name: String,
    val recordOffset: Long,
    val extentSector: Long,
    val size: Long,
    val flags: Int,
    val recordLength: Int,
    val sectorSize: Int,
) {
    val isDirectory: Boolean
        get() = flags and DIRECTORY_FLAG != 0

    val dataOffset: Long
        get() = extentSector * sectorSize.toLong()

    val allocatedSize: Long
        get() = alignUp(size, sectorSize.toLong())

    companion object {
        const val DIRECTORY_FLAG = 0x02
    }
}

data class IsoImageInfo(
    val sourceSize: Long,
    val sectorSize: Int,
    val volumeSpaceSize: Long,
    val volumeDescriptorOffsets: List<Long>,
    val root: IsoDirectoryEntry,
    val eboot: IsoDirectoryEntry,
)

class IsoFormatException(message: String) : IllegalArgumentException(message)

class Iso9660Reader(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun inspect(path: Path): IsoImageInfo {
        val sourceSize = fileSystem.metadata(path).size
            ?: throw IsoFormatException("Unable to determine ISO size.")
        if (sourceSize < DESCRIPTOR_START + DESCRIPTOR_SIZE) {
            throw IsoFormatException("The file is too small to contain an ISO9660 volume descriptor.")
        }

        val descriptors = readVolumeDescriptors(path, sourceSize)
        val primary = descriptors.firstOrNull { (it.bytes[0].toInt() and 0xff) == PRIMARY_DESCRIPTOR }
            ?: throw IsoFormatException("The image has no ISO9660 primary volume descriptor.")
        val sectorSize = readU16Le(primary.bytes, 128)
        if (sectorSize < 512 || sectorSize > 32768 || sectorSize and (sectorSize - 1) != 0) {
            throw IsoFormatException("Unsupported ISO logical block size $sectorSize.")
        }

        val volumeSpaceSize = readBothEndianU32(primary.bytes, 80, "volume space size")
        if (volumeSpaceSize <= 0L) {
            throw IsoFormatException("The ISO reports an empty volume.")
        }
        if (volumeSpaceSize > Long.MAX_VALUE / sectorSize || volumeSpaceSize * sectorSize > sourceSize) {
            throw IsoFormatException("The ISO volume extends beyond the selected file.")
        }
        val root = parseRecord(
            bytes = primary.bytes,
            offset = 156,
            recordOffset = primary.offset + 156,
            sectorSize = sectorSize,
            path = "",
        )
        if (!root.isDirectory) throw IsoFormatException("The ISO root record is not a directory.")
        val eboot = findEntry(path, root, TARGET_PATH)
            ?: throw IsoFormatException("Required ISO path $TARGET_PATH was not found.")
        if (eboot.isDirectory) {
            throw IsoFormatException("Required ISO path $TARGET_PATH resolves to a directory, not EBOOT.BIN.")
        }
        return IsoImageInfo(
            sourceSize = sourceSize,
            sectorSize = sectorSize,
            volumeSpaceSize = volumeSpaceSize,
            volumeDescriptorOffsets = descriptors.map { it.offset },
            root = root,
            eboot = eboot,
        )
    }

    fun readEntry(path: Path, entry: IsoDirectoryEntry): ByteArray {
        if (entry.size > Int.MAX_VALUE) {
            throw IsoFormatException("ISO entry ${entry.path} is too large to stage safely.")
        }
        if (entry.dataOffset < 0L || entry.dataOffset + entry.size > (fileSystem.metadata(path).size ?: 0L)) {
            throw IsoFormatException("ISO entry ${entry.path} points outside the image.")
        }
        return readAt(path, entry.dataOffset, entry.size.toInt())
    }

    internal fun readAt(path: Path, offset: Long, size: Int): ByteArray {
        if (offset < 0L || size < 0) throw IsoFormatException("Invalid ISO read range.")
        return try {
            fileSystem.source(path).buffer().use { source ->
                source.skip(offset)
                source.readByteArray(size.toLong())
            }
        } catch (error: Exception) {
            throw IsoFormatException("Unable to read ISO range at $offset ($size bytes): ${error.message}")
        }
    }

    internal fun readDirectory(path: Path, entry: IsoDirectoryEntry): List<IsoDirectoryEntry> {
        if (!entry.isDirectory) throw IsoFormatException("${entry.path} is not a directory.")
        if (entry.size > MAX_DIRECTORY_SIZE) {
            throw IsoFormatException("Directory ${entry.path} is unreasonably large.")
        }
        val bytes = readEntry(path, entry)
        val result = mutableListOf<IsoDirectoryEntry>()
        var offset = 0
        while (offset < bytes.size) {
            val recordSize = bytes[offset].toInt() and 0xff
            if (recordSize == 0) {
                offset = alignUp(offset + 1L, entry.sectorSize.toLong()).toInt()
                continue
            }
            if (recordSize < 34 || offset + recordSize > bytes.size) {
                throw IsoFormatException("Malformed directory record in ${entry.path} at byte $offset.")
            }
            val childPath = if (entry.path.isEmpty()) {
                decodeIdentifier(bytes, offset).trimEnd('/')
            } else {
                "${entry.path}/${decodeIdentifier(bytes, offset).trimEnd('/')}"
            }
            result += parseRecord(
                bytes = bytes,
                offset = offset,
                recordOffset = entry.dataOffset + offset,
                sectorSize = entry.sectorSize,
                path = childPath,
            )
            offset += recordSize
        }
        return result
    }

    fun findEntry(path: Path, root: IsoDirectoryEntry, targetPath: String): IsoDirectoryEntry? {
        val segments = targetPath.split('/').filter { it.isNotBlank() }.map { it.uppercase() }
        if (segments.isEmpty()) return root
        var current = root
        val visitedDirectories = mutableSetOf<Long>()
        for ((index, segment) in segments.withIndex()) {
            if (!current.isDirectory) {
                throw IsoFormatException("${current.path.ifEmpty { "/" }} is not a directory while locating $targetPath.")
            }
            if (!visitedDirectories.add(current.dataOffset)) {
                throw IsoFormatException("Directory cycle encountered while locating $targetPath.")
            }
            val matches = readDirectory(path, current).filter { normalizeName(it.name) == segment }
            if (matches.isEmpty()) return null
            if (matches.size != 1) {
                throw IsoFormatException("ISO path $targetPath is ambiguous: $segment appears ${matches.size} times.")
            }
            current = matches.single()
            if (index == segments.lastIndex) return current
        }
        return null
    }

    private fun readVolumeDescriptors(path: Path, sourceSize: Long): List<Descriptor> {
        val result = mutableListOf<Descriptor>()
        var sector = 16L
        while (sector < MAX_DESCRIPTOR_SECTORS) {
            val offset = sector * DESCRIPTOR_SIZE
            if (offset + DESCRIPTOR_SIZE > sourceSize) break
            val bytes = readAt(path, offset, DESCRIPTOR_SIZE.toInt())
            if (!bytes.copyOfRange(1, 6).contentEquals(CD001)) {
                throw IsoFormatException("Invalid ISO9660 descriptor identifier at sector $sector.")
            }
            val type = bytes[0].toInt() and 0xff
            if (type == TERMINATOR_DESCRIPTOR) break
            if (type == PRIMARY_DESCRIPTOR || type == SUPPLEMENTARY_DESCRIPTOR) {
                result += Descriptor(offset, bytes)
            }
            sector++
        }
        return result
    }

    private fun parseRecord(
        bytes: ByteArray,
        offset: Int,
        recordOffset: Long,
        sectorSize: Int,
        path: String,
    ): IsoDirectoryEntry {
        val recordLength = bytes[offset].toInt() and 0xff
        if (recordLength < 34 || offset + recordLength > bytes.size) {
            throw IsoFormatException("Malformed ISO directory record at $recordOffset.")
        }
        val extent = readBothEndianU32(bytes, offset + 2, "directory extent")
        val size = readBothEndianU32(bytes, offset + 10, "directory entry size")
        val flags = bytes[offset + 25].toInt() and 0xff
        val identifierLength = bytes[offset + 32].toInt() and 0xff
        if (33 + identifierLength > recordLength) {
            throw IsoFormatException("Malformed ISO identifier at $recordOffset.")
        }
        val name = decodeIdentifier(bytes, offset)
        return IsoDirectoryEntry(
            path = path,
            name = name,
            recordOffset = recordOffset,
            extentSector = extent,
            size = size,
            flags = flags,
            recordLength = recordLength,
            sectorSize = sectorSize,
        )
    }

    private fun decodeIdentifier(bytes: ByteArray, offset: Int): String {
        val length = bytes[offset + 32].toInt() and 0xff
        val raw = bytes.copyOfRange(offset + 33, offset + 33 + length)
        if (raw.isEmpty()) return ""
        if (raw.size % 2 == 0 && raw.indices.step(2).all { raw[it].toInt() == 0 }) {
            return buildString(raw.size / 2) {
                for (index in raw.indices step 2) append((raw[index + 1].toInt() and 0xff).toChar())
            }
        }
        return buildString(raw.size) { raw.forEach { append((it.toInt() and 0xff).toChar()) } }
    }

    private fun normalizeName(name: String): String {
        val withoutVersion = name.substringBeforeLast(';').ifEmpty { name }
        return withoutVersion.uppercase()
    }

    private data class Descriptor(val offset: Long, val bytes: ByteArray)

    private companion object {
        const val DESCRIPTOR_SIZE = 2048L
        const val DESCRIPTOR_START = 16L * DESCRIPTOR_SIZE
        const val MAX_DESCRIPTOR_SECTORS = 256L
        const val MAX_DIRECTORY_SIZE = 64L * 1024L * 1024L
        const val PRIMARY_DESCRIPTOR = 1
        const val SUPPLEMENTARY_DESCRIPTOR = 2
        const val TERMINATOR_DESCRIPTOR = 255
        const val TARGET_PATH = "PSP_GAME/SYSDIR/EBOOT.BIN"
        val CD001 = byteArrayOf('C'.code.toByte(), 'D'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())
    }
}

class Iso9660Rebuilder(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun rebuild(
        source: Path,
        destination: Path,
        image: IsoImageInfo,
        replacement: ByteArray,
        cancellation: CancellationToken = NeverCancelled,
        progress: ProgressReporter = NoProgress,
    ) {
        if (replacement.isEmpty()) throw IsoFormatException("Replacement EBOOT cannot be empty.")
        val oldAllocated = image.eboot.allocatedSize
        val inPlace = replacement.size.toLong() <= oldAllocated
        val appendOffset = if (inPlace) 0L else alignUp(image.sourceSize, image.sectorSize.toLong())
        val replacementOffset = if (inPlace) image.eboot.dataOffset else appendOffset
        val replacementExtent = replacementOffset / image.sectorSize
        val outputSize = if (inPlace) {
            image.sourceSize
        } else {
            alignUp(appendOffset + replacement.size.toLong(), image.sectorSize.toLong())
        }
        val volumeSpaceSize = max(image.volumeSpaceSize, outputSize / image.sectorSize)
        val recordPatches = directoryRecordPatches(image.eboot, replacementExtent, replacement.size.toLong())
        val volumePatches = volumeSpacePatches(image.volumeDescriptorOffsets, volumeSpaceSize)
        var completed = 0L
        val destinationWasAbsent = !fileSystem.exists(destination)
        if (!destinationWasAbsent) {
            throw IsoFormatException("The output ISO already exists; choose a separate output path.")
        }

        try {
            fileSystem.source(source).buffer().use { input ->
                fileSystem.sink(destination, mustCreate = true).buffer().use { output ->
                    val buffer = Buffer()
                    while (completed < image.sourceSize) {
                        cancellation.throwIfCancelled()
                        val requested = min(CHUNK_SIZE.toLong(), image.sourceSize - completed)
                        val read = input.read(buffer, requested)
                        if (read <= 0L) break
                        val chunk = buffer.readByteArray(read)
                        recordPatches.forEach { (offset, patch) -> overlay(chunk, completed, offset, patch) }
                        volumePatches.forEach { (offset, patch) -> overlay(chunk, completed, offset, patch) }
                        if (inPlace) overlay(chunk, completed, image.eboot.dataOffset, replacement)
                        output.write(chunk)
                        completed += read
                        progress.report(PatchProgress(PatchPhase.REBUILDING_ISO, completed, image.sourceSize))
                    }
                    if (completed != image.sourceSize) {
                        throw IsoFormatException("The source ISO ended before its declared size.")
                    }
                    if (!inPlace) {
                        writeZeros(output, appendOffset - image.sourceSize, cancellation)
                        output.write(replacement)
                        writeZeros(output, outputSize - appendOffset - replacement.size, cancellation)
                    }
                }
            }
        } catch (error: Throwable) {
            if (destinationWasAbsent) fileSystem.delete(destination, mustExist = false)
            throw error
        }
    }

    private fun directoryRecordPatches(entry: IsoDirectoryEntry, extent: Long, size: Long): List<Pair<Long, ByteArray>> {
        val extentPatch = ByteArray(8).also { writeBothEndianU32(it, 0, extent) }
        val sizePatch = ByteArray(8).also { writeBothEndianU32(it, 0, size) }
        return listOf(
            entry.recordOffset + 2L to extentPatch,
            entry.recordOffset + 10L to sizePatch,
        )
    }

    private fun volumeSpacePatches(offsets: List<Long>, sectors: Long): List<Pair<Long, ByteArray>> =
        offsets.map { offset ->
            val patch = ByteArray(8)
            writeBothEndianU32(patch, 0, sectors)
            offset + 80L to patch
        }

    private fun overlay(target: ByteArray, targetOffset: Long, patchOffset: Long, patch: ByteArray) {
        val destinationStart = max(0L, patchOffset - targetOffset)
        val sourceStart = max(0L, targetOffset - patchOffset)
        val length = min(
            patch.size.toLong() - sourceStart,
            target.size.toLong() - destinationStart,
        )
        if (length <= 0L) return
        patch.copyInto(
            destination = target,
            destinationOffset = destinationStart.toInt(),
            startIndex = sourceStart.toInt(),
            endIndex = (sourceStart + length).toInt(),
        )
    }

    private fun writeZeros(output: okio.BufferedSink, count: Long, cancellation: CancellationToken) {
        if (count <= 0L) return
        val zeros = ByteArray(min(CHUNK_SIZE.toLong(), count).toInt())
        var remaining = count
        while (remaining > 0L) {
            cancellation.throwIfCancelled()
            val write = min(remaining, zeros.size.toLong()).toInt()
            output.write(zeros, 0, write)
            remaining -= write
        }
    }

    private companion object {
        const val CHUNK_SIZE = 1024 * 1024
    }
}

internal fun alignUp(value: Long, alignment: Long): Long {
    if (alignment <= 0L) throw IllegalArgumentException("Alignment must be positive")
    return if (value % alignment == 0L) value else value + alignment - value % alignment
}

private fun readU16Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun readU32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xffL) or
        ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xffL) shl 24)

private fun readU32Be(bytes: ByteArray, offset: Int): Long =
    (bytes[offset + 3].toLong() and 0xffL) or
        ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
        ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
        ((bytes[offset].toLong() and 0xffL) shl 24)

private fun readBothEndianU32(bytes: ByteArray, offset: Int, label: String): Long {
    val little = readU32Le(bytes, offset)
    val big = readU32Be(bytes, offset + 4)
    if (little != big) throw IsoFormatException("Mismatched little/big endian $label fields.")
    return little
}

private fun writeBothEndianU32(bytes: ByteArray, offset: Int, value: Long) {
    require(value in 0..0xffffffffL)
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
    bytes[offset + 4] = (value ushr 24).toByte()
    bytes[offset + 5] = (value ushr 16).toByte()
    bytes[offset + 6] = (value ushr 8).toByte()
    bytes[offset + 7] = value.toByte()
}
