package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchOptionsTest {
    @Test
    fun defaults_enable_each_supported_feature() {
        val options = PatchOptions()

        assertTrue(options.rightStickCamera)
        assertTrue(options.combatFeatures)
        assertTrue(options.appliesCameraDistance)
        assertTrue(options.appliesCameraHeight)
        assertTrue(options.hasSelectedFeature)
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
    fun camera_and_combat_features_can_be_disabled_independently() {
        val options = PatchOptions(
            rightStickCamera = false,
            cameraDistanceEnabled = false,
            cameraHeightEnabled = false,
            hitAwareCancels = false,
            invincibilityWindows = false,
            extendedDefense = false,
            commandCancels = false,
            telemetry = false,
            criticalModeAbilities = false,
            criticalModePassives = false,
        )

        assertFalse(options.rightStickCamera)
        assertFalse(options.combatFeatures)
        assertFalse(options.appliesCameraDistance)
        assertFalse(options.appliesCameraHeight)
        assertEquals(1, options.validate().size)
        assertTrue(options.validate().single() is PatchOptionError.NoFeaturesSelected)
    }

    @Test
    fun camera_values_are_checked_at_reference_boundaries() {
        assertTrue(PatchOptions(cameraDistance = 1.0f, cameraHeight = 0.0f).validate().isEmpty())
        assertTrue(PatchOptions(cameraDistance = 12.0f, cameraHeight = 4.0f).validate().isEmpty())
        assertEquals(1, PatchOptions(cameraDistance = 0.999f).validate().size)
        assertEquals(1, PatchOptions(cameraHeight = 4.001f).validate().size)
    }

    @Test
    fun disabling_one_combat_feature_keeps_other_combat_features_active() {
        val options = PatchOptions(hitAwareCancels = false)

        assertTrue(options.combatFeatures)
        assertTrue(options.hasSelectedFeature)
    }
}
