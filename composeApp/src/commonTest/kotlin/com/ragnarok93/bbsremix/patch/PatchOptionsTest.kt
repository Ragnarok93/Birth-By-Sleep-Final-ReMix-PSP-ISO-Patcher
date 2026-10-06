package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchOptionsTest {
    @Test
    fun defaults_match_reference_patcher() {
        val options = PatchOptions()

        assertEquals(PatchMode.COMBINED, options.mode)
        assertTrue(options.rightStickCamera)
        assertTrue(options.combatFeatures)
        assertTrue(options.appliesCameraDistance)
        assertTrue(options.appliesCameraHeight)
        assertEquals(4.5f, options.cameraDistance)
        assertEquals(1.0f, options.cameraHeight)
        assertFalse(options.strictSteamExclusions)
        assertTrue(options.hitAwareCancels)
        assertTrue(options.invincibilityWindows)
        assertTrue(options.extendedDefense)
        assertTrue(options.commandCancels)
        assertTrue(options.telemetry)
        assertTrue(options.criticalModeAbilities)
        assertTrue(options.criticalModePassives)
    }

    @Test
    fun modes_disable_only_the_features_disabled_by_reference_script() {
        val cameraOnly = PatchOptions(mode = PatchMode.CAMERA_ONLY)
        assertTrue(cameraOnly.rightStickCamera)
        assertFalse(cameraOnly.combatFeatures)
        assertTrue(cameraOnly.appliesCameraDistance)
        assertTrue(cameraOnly.appliesCameraHeight)

        val combatOnly = PatchOptions(mode = PatchMode.COMBAT_ONLY)
        assertFalse(combatOnly.rightStickCamera)
        assertTrue(combatOnly.combatFeatures)
        assertFalse(combatOnly.appliesCameraDistance)
        assertFalse(combatOnly.appliesCameraHeight)
    }

    @Test
    fun camera_values_are_checked_at_reference_boundaries() {
        assertTrue(PatchOptions(cameraDistance = 1.0f, cameraHeight = 0.0f).validate().isEmpty())
        assertTrue(PatchOptions(cameraDistance = 12.0f, cameraHeight = 4.0f).validate().isEmpty())
        assertEquals(1, PatchOptions(cameraDistance = 0.999f).validate().size)
        assertEquals(1, PatchOptions(cameraHeight = 4.001f).validate().size)
    }
}
