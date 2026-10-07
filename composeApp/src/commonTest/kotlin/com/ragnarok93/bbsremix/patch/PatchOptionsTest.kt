package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchOptionsTest {
    @Test
    fun defaults_expose_only_psp_native_right_stick_candidate() {
        val options = PatchOptions()

        assertTrue(options.rightStickCamera)
        assertFalse(options.combatFeatures)
        assertFalse(options.appliesCameraDistance)
        assertFalse(options.appliesCameraHeight)
        assertTrue(options.hasSelectedFeature)
        assertEquals(4.5f, options.cameraDistance)
        assertEquals(1.0f, options.cameraHeight)
        assertTrue(options.validate().isEmpty())
    }

    @Test
    fun psp_native_camera_geometry_is_allowed_but_combat_ports_are_rejected() {
        assertTrue(PatchOptions(cameraDistanceEnabled = true).validate().isEmpty())
        assertTrue(PatchOptions(cameraHeightEnabled = true).validate().isEmpty())
        assertTrue(PatchOptions(cameraDistanceEnabled = true, cameraHeightEnabled = true).validate().isEmpty())

        listOf(
            PatchOptions(extendedDefense = true),
            PatchOptions(commandCancels = true),
            PatchOptions(telemetry = true),
            PatchOptions(hitAwareCancels = true),
            PatchOptions(invincibilityWindows = true),
            PatchOptions(criticalModeAbilities = true),
            PatchOptions(criticalModePassives = true),
            PatchOptions(strictSteamExclusions = true),
        ).forEach { options ->
            assertTrue(options.validate().any { it is PatchOptionError.UnvalidatedPspPortFeature })
        }
    }

    @Test
    fun all_features_can_be_disabled_for_inspection_but_not_patching() {
        val options = PatchOptions(rightStickCamera = false)

        assertFalse(options.hasSelectedFeature)
        assertEquals(1, options.validate().size)
        assertTrue(options.validate().single() is PatchOptionError.NoFeaturesSelected)
    }

    @Test
    fun camera_values_keep_reference_boundaries() {
        assertEquals(1, PatchOptions(cameraDistance = 0.999f).validate().size)
        assertEquals(1, PatchOptions(cameraHeight = 4.001f).validate().size)
    }
}
