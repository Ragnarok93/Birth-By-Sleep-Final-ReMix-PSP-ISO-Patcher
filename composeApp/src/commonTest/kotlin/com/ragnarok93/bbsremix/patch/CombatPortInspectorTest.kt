package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombatPortInspectorTest {
    private fun syntheticEboot(): ByteArray = ByteArray(CombatPortInspector.SUPPORTED_SIZE).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
            .copyInto(it, 0)
        it.writeIntLe(0x1c, 0x34)
        it.writeShortLe(0x2a, 0x20)
        it.writeShortLe(0x2c, 2)
        it.writeIntLe(0x34, 1)
        it.writeIntLe(0x38, 0x1018)
        it.writeIntLe(0x3c, 0x08804018)
        it.writeIntLe(0x44, 0x0036ae64)
        it.writeIntLe(0x48, 0x0036ae64)
        it.writeIntLe(0x54, 1)
        it.writeIntLe(0x58, 0x0036c000)
        it.writeIntLe(0x5c, 0x08BB4780)
        it.writeIntLe(0x64, 0x20)
        it.writeIntLe(0x68, 0x20)
        it.writeIntLe(
            CombatPortInspector.INPUT_HOOK_VA - CombatPortInspector.VA_FILE_DELTA,
            CombatPortInspector.INPUT_HOOK_EXPECTED,
        )
    }

    @Test
    fun combat_inspection_is_read_only_and_does_not_fake_gameplay_validation() {
        val source = syntheticEboot()
        val original = source.copyOf()
        val result = CombatPortInspector.inspect(source)
        assertTrue(source.contentEquals(original), "Combat analysis must never modify source bytes")
        assertTrue(result.hookMatchesReference)
        assertTrue(result.overlayCollision)
        assertFalse(result.exactSupportedSource, "Synthetic ELF cannot masquerade as the supported game")
        assertFalse(result.unmodifiedSource)
        assertContains(result.summary, "READ ONLY")
        assertContains(result.summary, "overlay_collision=true")
        assertContains(result.summary, "LOAD[0]")
        assertContains(result.summary, "0x08816904")
        assertContains(result.summary, "ABI HAZARD")
        assertContains(result.summary, "restores saved register s0")
        assertContains(result.summary, "ELF section map: unavailable or invalid")
        assertContains(result.summary, "Status: UNVALIDATED")
    }

    @Test
    fun unknown_or_truncated_source_remains_unvalidated_without_throwing() {
        for (source in listOf(ByteArray(0), byteArrayOf(0x7f), "~PSP".encodeToByteArray())) {
            val result = CombatPortInspector.inspect(source)
            assertFalse(result.exactSupportedSource)
            assertFalse(result.hookMatchesReference)
            assertContains(result.summary, "window unavailable")
            assertContains(result.summary, "Status: UNVALIDATED")
        }
    }

    @Test
    fun source_hook_mismatch_is_detected_and_reported_without_modifying_eboot() {
        val source = syntheticEboot()
        source.writeIntLe(
            CombatPortInspector.INPUT_HOOK_VA - CombatPortInspector.VA_FILE_DELTA,
            0x0C000000,
        )
        val report = CombatPortInspector.inspect(source)
        assertFalse(report.hookMatchesReference)
        assertContains(report.summary, "matches=false")
    }

    @Test
    fun invalid_program_header_table_is_not_trusted() {
        val source = syntheticEboot()
        source.writeIntLe(0x1c, source.size - 1)
        val report = CombatPortInspector.inspect(source)
        assertContains(report.summary, "cannot be decoded safely")
        assertFalse(report.exactSupportedSource)
    }

    @Test
    fun investigated_addresses_do_not_overlap_camera_edits_or_active_payload() {
        val report = CombatPortInspector.inspect(syntheticEboot())
        assertTrue(report.overlayCollision)
        assertEquals(0x08B6EE7C, CombatPortInspector.OVERLAY_START_VA)
        assertTrue(Stage5Payloads.S4_VA >= CombatPortInspector.OVERLAY_START_VA)
        assertTrue(Stage5Payloads.S5_WRAPPER_VA >= CombatPortInspector.OVERLAY_START_VA)
        assertTrue(PspNativeCameraGeometryPatch.CAMERA_TABLE_VA < CombatPortInspector.OVERLAY_START_VA)
        assertTrue(PspNativeRightStickPatch.wordPatches.all {
            it.virtualAddress < CombatPortInspector.OVERLAY_START_VA
        })
    }
}
