package com.ragnarok93.bbsremix.patch

data class PatchOptions(
    val fpsTarget: Int = 30,
    val rightStickCamera: Boolean = true,
    val cameraDistanceEnabled: Boolean = false,
    val cameraDistance: Float = 4.0f,
    val cameraHeightEnabled: Boolean = false,
    val cameraHeight: Float = 1.75f,
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

    val appliesFrameRate: Boolean
        get() = fpsTarget != 30

    val appliesCameraDistance: Boolean
        get() = cameraDistanceEnabled

    val appliesCameraHeight: Boolean
        get() = cameraHeightEnabled

    val hasSelectedFeature: Boolean
        get() = appliesFrameRate || rightStickCamera || appliesCameraDistance || appliesCameraHeight || combatFeatures

    fun validate(): List<PatchOptionError> = buildList {
        if (fpsTarget !in FPS_TARGETS) {
            add(PatchOptionError.FrameRateTargetUnsupported(fpsTarget))
        }
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
        val FPS_TARGETS: Set<Int> = setOf(30, 60, 90, 120)
        val CAMERA_DISTANCE_RANGE: ClosedFloatingPointRange<Float> = 2.0f..6.0f
        val CAMERA_HEIGHT_RANGE: ClosedFloatingPointRange<Float> = 1.0f..2.5f
        val CAMERA_LEVEL_RANGE: ClosedFloatingPointRange<Float> = 1.0f..5.0f
        const val CAMERA_LEVEL_STEPS = 3

        private val CAMERA_DISTANCE_LEVEL_VALUES = floatArrayOf(2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
        private val CAMERA_HEIGHT_LEVEL_VALUES = floatArrayOf(1.0f, 1.375f, 1.75f, 2.125f, 2.5f)

        fun cameraDistanceForLevel(level: Int): Float =
            CAMERA_DISTANCE_LEVEL_VALUES[(level - 1).coerceIn(0, CAMERA_DISTANCE_LEVEL_VALUES.lastIndex)]

        fun cameraHeightForLevel(level: Int): Float =
            CAMERA_HEIGHT_LEVEL_VALUES[(level - 1).coerceIn(0, CAMERA_HEIGHT_LEVEL_VALUES.lastIndex)]

        fun cameraDistanceLevel(value: Float): Int = nearestLevel(CAMERA_DISTANCE_LEVEL_VALUES, value)

        fun cameraHeightLevel(value: Float): Int = nearestLevel(CAMERA_HEIGHT_LEVEL_VALUES, value)

        private fun nearestLevel(values: FloatArray, value: Float): Int {
            var bestIndex = 0
            var bestDistance = kotlin.math.abs(values[0] - value)
            for (index in 1..values.lastIndex) {
                val distance = kotlin.math.abs(values[index] - value)
                if (distance < bestDistance) {
                    bestIndex = index
                    bestDistance = distance
                }
            }
            return bestIndex + 1
        }
    }
}

sealed class PatchOptionError(val message: String) {
    data class FrameRateTargetUnsupported(val value: Int) : PatchOptionError(
        "FPS target must be 30, 60, 90, or 120 (received $value)."
    )

    data object UnvalidatedPspPortFeature : PatchOptionError(
        "Better Battle System combat ports remain disabled pending PSP-native re-derivation and runtime validation."
    )

    data class CameraDistanceOutOfRange(val value: Float) : PatchOptionError(
        "Camera distance must be between 2.0 and 6.0 (received $value)."
    )

    data class CameraHeightOutOfRange(val value: Float) : PatchOptionError(
        "Camera height must be between 1.0 and 2.5 (received $value)."
    )

    data object NoFeaturesSelected : PatchOptionError(
        "Enable at least one patch feature before creating an output ISO."
    )
}

