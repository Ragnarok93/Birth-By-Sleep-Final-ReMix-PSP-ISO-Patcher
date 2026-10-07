package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchOptionsTest {
    @Test
    fun defaults_match_reference_patcher() {
        val options = PatchOptions()

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
    fun camera_features_are_independently_toggleable() {
        val options = PatchOptions(
            rightStickCameraEnabled = false,
            cameraDistanceEnabled = true,
            cameraHeightEnabled = false,
        )

        assertFalse(options.rightStickCamera)
        assertTrue(options.appliesCameraDistance)
        assertFalse(options.appliesCameraHeight)
    }

    @Test
    fun combat_features_are_independently_toggleable() {
        val none = PatchOptions(
            strictSteamExclusions = false,
            hitAwareCancels = false,
            invincibilityWindows = false,
            extendedDefense = false,
            commandCancels = false,
            telemetry = false,
            criticalModeAbilities = false,
            criticalModePassives = false,
        )
        assertFalse(none.combatFeatures)

        assertTrue(none.copy(hitAwareCancels = true).combatFeatures)
        assertTrue(none.copy(invincibilityWindows = true).combatFeatures)
        assertTrue(none.copy(criticalModeAbilities = true).combatFeatures)
        assertTrue(none.copy(criticalModePassives = true).combatFeatures)
    }

    @Test
    fun camera_values_are_checked_at_reference_boundaries() {
        assertTrue(PatchOptions(cameraDistance = 1.0f, cameraHeight = 0.0f).validate().isEmpty())
        assertTrue(PatchOptions(cameraDistance = 12.0f, cameraHeight = 4.0f).validate().isEmpty())
        assertEquals(1, PatchOptions(cameraDistance = 0.999f).validate().size)
        assertEquals(1, PatchOptions(cameraHeight = 4.001f).validate().size)
    }
}
