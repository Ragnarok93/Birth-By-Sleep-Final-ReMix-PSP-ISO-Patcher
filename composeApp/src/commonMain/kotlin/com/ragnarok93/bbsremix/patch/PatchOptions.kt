package com.ragnarok93.bbsremix.patch

data class PatchOptions(
    val rightStickCameraEnabled: Boolean = true,
    val cameraDistanceEnabled: Boolean = true,
    val cameraDistance: Float = 4.5f,
    val cameraHeightEnabled: Boolean = true,
    val cameraHeight: Float = 1.0f,
    val strictSteamExclusions: Boolean = false,
    val hitAwareCancels: Boolean = true,
    val invincibilityWindows: Boolean = true,
    val extendedDefense: Boolean = true,
    val commandCancels: Boolean = true,
    val telemetry: Boolean = true,
    val criticalModeAbilities: Boolean = true,
    val criticalModePassives: Boolean = true,
) {
    val rightStickCamera: Boolean
        get() = rightStickCameraEnabled

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

    fun validate(): List<PatchOptionError> = buildList {
        if (cameraDistance !in CAMERA_DISTANCE_RANGE) {
            add(PatchOptionError.CameraDistanceOutOfRange(cameraDistance))
        }
        if (cameraHeight !in CAMERA_HEIGHT_RANGE) {
            add(PatchOptionError.CameraHeightOutOfRange(cameraHeight))
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
    data class CameraDistanceOutOfRange(val value: Float) : PatchOptionError(
        "Camera distance must be between 1.0 and 12.0 (received $value)."
    )

    data class CameraHeightOutOfRange(val value: Float) : PatchOptionError(
        "Camera height must be between 0.0 and 4.0 (received $value)."
    )
}
