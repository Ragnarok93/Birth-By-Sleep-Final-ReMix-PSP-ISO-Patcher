package com.ragnarok93.bbsremix.bbs0

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.sha256Hex

/**
 * Resolve standalone CTD game resources using OpenKh's global BBSA directory
 * records, not the embedded ARC entry table. This only reads resources located
 * in BBS0 itself; references into BBS1-4 remain unresolved/fail-closed.
 */
internal object Bbs0IndexedCtd {
    data class Reference(val name: String, val directoryHash: Int)

    data class Resource(
        val name: String,
        val directoryHash: Int,
        val offset: Long,
        val data: ByteArray,
        val sha256: String,
    )

    data class Result(val resources: List<Resource>, val unresolved: List<String>)

    fun resolve(
        index: ByteArray,
        sourceSize: Long,
        read: (Long, Int) -> ByteArray,
        references: List<Reference>,
        cancellation: CancellationToken,
    ): Result {
        require(index.size >= 0x30 && index.copyOfRange(0, 4).contentEquals("bbsa".encodeToByteArray())) {
            "Invalid BBSA index header."
        }
        val firstArchiveSector = index.u16(0x1a)
        val archive1Sector = index.u32(0x20)
        val recordOffset = index.u32(0x14)
        val recordCount = index.u16(0x0e)
        require(recordOffset >= 0 && recordCount <= 50000 &&
            recordOffset.toLong() + recordCount.toLong() * 12L <= index.size
        ) { "BBSA global directory entries extend beyond the exported index." }

        val resources = mutableListOf<Resource>()
        val unresolved = mutableListOf<String>()
        val unique = references.distinctBy { it.name.uppercase() to it.directoryHash }
        for (reference in unique) {
            cancellation.throwIfCancelled()
            if (!reference.name.endsWith(".ctd", ignoreCase = true) ||
                reference.name.length <= 4
            ) {
                unresolved += reference.name
                continue
            }
            val targetHash = crc32(reference.name.dropLast(4).uppercase())
            var matches = 0
            var recordInfo = 0
            for (i in 0 until recordCount) {
                val pos = recordOffset + i * 12
                if (index.u32(pos) == targetHash &&
                    index.u32(pos + 8) == reference.directoryHash
                ) {
                    recordInfo = index.u32(pos + 4)
                    matches++
                }
            }
            if (matches != 1) {
                unresolved += reference.name
                continue
            }
            val logicalSector = recordInfo ushr 12
            val sectorCount = recordInfo and 0xfff
            val offset = (logicalSector.toLong() + firstArchiveSector.toLong()) * 2048L
            val allocatedSize = sectorCount.toLong() * 2048L
            if (logicalSector >= archive1Sector || sectorCount == 0 ||
                allocatedSize > MAX_CTD_BYTES || offset < index.size ||
                offset > sourceSize || allocatedSize > sourceSize - offset
            ) {
                unresolved += reference.name
                continue
            }
            val bytes = read(offset, allocatedSize.toInt())
            if (!validCtd(bytes)) {
                unresolved += reference.name
                continue
            }
            resources += Resource(reference.name, reference.directoryHash,
                offset, bytes, sha256Hex(bytes))
        }
        return Result(resources, unresolved)
    }

    private fun validCtd(data: ByteArray): Boolean {
        if (data.size < 0x20 ||
            !data.copyOfRange(0, 4).contentEquals("@CTD".encodeToByteArray()) ||
            data.u32(4) != 1
        ) return false
        val countLayouts = data.u16(0x0c)
        val countMessages = data.u16(0x0e)
        val messages = data.u32(0x10)
        val layouts = data.u32(0x14)
        val text = data.u32(0x18)
        return messages >= 0x20 && layouts >= 0x20 && text >= 0x20 &&
            messages.toLong() + countMessages.toLong() * 12 <= data.size &&
            layouts.toLong() + countLayouts.toLong() * 32 <= data.size &&
            text <= data.size
    }

    // OpenKh.Bbs.Bbsa.GetHash uses the standard reflected CRC32 polynomial.
    private fun crc32(input: String): Int {
        var result = -1
        for (b in input.encodeToByteArray()) {
            result = result xor (b.toInt() and 0xff)
            repeat(8) {
                result = if (result and 1 != 0) (result ushr 1) xor 0xedb88320.toInt()
                else result ushr 1
            }
        }
        return result.inv()
    }

    private fun ByteArray.u16(pos: Int): Int =
        (this[pos].toInt() and 0xff) or ((this[pos + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32(pos: Int): Int = u16(pos) or (u16(pos + 2) shl 16)

    private const val MAX_CTD_BYTES: Long = 4L * 1024L * 1024L
}
