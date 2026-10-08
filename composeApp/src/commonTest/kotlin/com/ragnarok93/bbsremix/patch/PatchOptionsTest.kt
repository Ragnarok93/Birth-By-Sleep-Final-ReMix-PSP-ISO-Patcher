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
        assertEquals(4.0f, options.cameraDistance)
        assertEquals(1.75f, options.cameraHeight)
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
    fun camera_values_keep_validated_boundaries() {
        assertTrue(PatchOptions(cameraDistance = 2.0f, cameraHeight = 1.0f).validate().isEmpty())
        assertTrue(PatchOptions(cameraDistance = 6.0f, cameraHeight = 2.5f).validate().isEmpty())
        assertEquals(1, PatchOptions(cameraDistance = 1.999f).validate().size)
        assertEquals(1, PatchOptions(cameraDistance = 6.001f).validate().size)
        assertEquals(1, PatchOptions(cameraHeight = 0.999f).validate().size)
        assertEquals(1, PatchOptions(cameraHeight = 2.501f).validate().size)
    }

    @Test
    fun camera_sliders_use_five_discrete_levels() {
        assertEquals(2.0f, PatchOptions.cameraDistanceForLevel(1))
        assertEquals(3.0f, PatchOptions.cameraDistanceForLevel(2))
        assertEquals(4.0f, PatchOptions.cameraDistanceForLevel(3))
        assertEquals(5.0f, PatchOptions.cameraDistanceForLevel(4))
        assertEquals(6.0f, PatchOptions.cameraDistanceForLevel(5))

        assertEquals(1.0f, PatchOptions.cameraHeightForLevel(1))
        assertEquals(1.375f, PatchOptions.cameraHeightForLevel(2))
        assertEquals(1.75f, PatchOptions.cameraHeightForLevel(3))
        assertEquals(2.125f, PatchOptions.cameraHeightForLevel(4))
        assertEquals(2.5f, PatchOptions.cameraHeightForLevel(5))

        assertEquals(3, PatchOptions.cameraDistanceLevel(4.0f))
        assertEquals(3, PatchOptions.cameraHeightLevel(1.75f))
        assertEquals(1.0f, PatchOptions.CAMERA_LEVEL_RANGE.start)
        assertEquals(5.0f, PatchOptions.CAMERA_LEVEL_RANGE.endInclusive)
        assertEquals(3, PatchOptions.CAMERA_LEVEL_STEPS)
    }
}
