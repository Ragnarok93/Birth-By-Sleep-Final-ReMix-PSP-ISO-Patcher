package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.PatchOptions
import okio.FileSystem
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsoPatchingServiceTest {
    @Test
    fun unsupported_eboot_fails_before_output_creation() {
        val fileSystem = FileSystem.SYSTEM
        val source = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-service-source.iso"
        val destination = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-service-output.iso"
        fileSystem.delete(source, mustExist = false)
        fileSystem.delete(destination, mustExist = false)

        // The compact fixture is intentionally not a game EBOOT. The service must stop at
        // strict fingerprint validation and must not create a misleading output image.
        val fixture = ByteArray(64 * 2048)
        fixture[16 * 2048] = 255.toByte()
        fileSystem.sink(source).buffer().use { it.write(fixture) }
        try {
            assertFailsWith<Exception> {
                IsoPatchingService(fileSystem).patchTo(source, destination, PatchOptions())
            }
            assertTrue(!fileSystem.exists(destination))
        } finally {
            fileSystem.delete(source, mustExist = false)
            fileSystem.delete(destination, mustExist = false)
        }
    }
}
