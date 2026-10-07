package com.ragnarok93.bbsremix.patch

data class PatchOptions(
    val rightStickCamera: Boolean = true,
    val cameraDistanceEnabled: Boolean = false,
    val cameraDistance: Float = 4.5f,
    val cameraHeightEnabled: Boolean = false,
    val cameraHeight: Float = 1.0f,
    val strictSteamExclusions: Boolean = false,
    val hitAwareCancels: Boolean = false,
    val invincibilityWindows: Boolean = false,
    val extendedDefense: Boolean = false,
    val commandCancels: Boolean = false,
    val telemetry: Boolean = false,
    val criticalModeAbilities: Boolean = false,
    val criticalModePassives: Boolean = false,
) {
    val combatFeatures: Boolean
        get() = strictSteamExclusions ||
            hitAwareCancels ||
            invincibilityWindows ||
            extendedDefense ||
            commandCancels ||
            telemetry ||
            criticalModeAbilities ||
            criticalModePassives

    val appliesCameraDistance: Boolean
        get() = cameraDistanceEnabled

    val appliesCameraHeight: Boolean
        get() = cameraHeightEnabled

    val hasSelectedFeature: Boolean
        get() = rightStickCamera || appliesCameraDistance || appliesCameraHeight || combatFeatures

    fun validate(): List<PatchOptionError> = buildList {
        if (combatFeatures) {
            add(PatchOptionError.UnvalidatedPspPortFeature)
        }
        if (cameraDistance !in CAMERA_DISTANCE_RANGE) {
            add(PatchOptionError.CameraDistanceOutOfRange(cameraDistance))
        }
        if (cameraHeight !in CAMERA_HEIGHT_RANGE) {
            add(PatchOptionError.CameraHeightOutOfRange(cameraHeight))
        }
        if (!hasSelectedFeature) {
            add(PatchOptionError.NoFeaturesSelected)
        }
    }

    fun requireValid() {
        validate().firstOrNull()?.let { throw IllegalArgumentException(it.message) }
    }

    companion object {
        val CAMERA_DISTANCE_RANGE: ClosedFloatingPointRange<Float> = 1.0f..12.0f
        val CAMERA_HEIGHT_RANGE: ClosedFloatingPointRange<Float> = 0.0f..4.0f
    }
}

sealed class PatchOptionError(val message: String) {
    data object UnvalidatedPspPortFeature : PatchOptionError(
        "Better Battle System combat ports remain disabled pending PSP-native re-derivation and runtime validation."
    )

    data class CameraDistanceOutOfRange(val value: Float) : PatchOptionError(
        "Camera distance must be between 1.0 and 12.0 (received $value)."
    )

    data class CameraHeightOutOfRange(val value: Float) : PatchOptionError(
        "Camera height must be between 0.0 and 4.0 (received $value)."
    )

    data object NoFeaturesSelected : PatchOptionError(
        "Enable at least one patch feature before creating an output ISO."
    )
}

