package com.ragnarok93.bbsremix.bbs0

import com.ragnarok93.bbsremix.patch.CancellationToken
import okio.Path
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal actual fun writeBbs0Zip(
    destination: Path,
    entries: List<Bbs0ZipEntry>,
    cancellation: CancellationToken,
) {
    val file = File(destination.toString())
    require(!file.exists()) { "Refusing to overwrite an existing BBS0 export." }
    ZipOutputStream(FileOutputStream(file)).use { zip ->
        entries.forEach { entry ->
            cancellation.throwIfCancelled()
            require(entry.name.startsWith("bbs0/") &&
                !entry.name.contains("..") && !entry.name.contains('\\')
            ) { "Invalid BBS0 export ZIP path." }
            zip.putNextEntry(ZipEntry(entry.name))
            zip.write(entry.bytes)
            zip.closeEntry()
        }
    }
}
