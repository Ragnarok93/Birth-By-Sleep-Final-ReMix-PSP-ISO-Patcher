package com.ragnarok93.bbsremix.patch

/**
 * Pure reference rules transcribed from Xendra's Steam Better Battle System
 * (2025-02-28 edition), with conservative PSP-port category and finisher gates.
 *
 * IMPORTANT: The animation/action/hit values in [LegacyCombatSnapshot] are
 * semantic inputs from the PC Lua mod. They are NOT proven to have the same
 * numeric values in the PSP runtime. No class here edits PSP memory, injects
 * a hook, or enables a combat toggle in the ISO patch engine.
 */
internal object CombatDecisionModel {
    enum class Character { TERRA, VENTUS, AQUA }
    enum class Input { CROSS, TRIANGLE, SQUARE, CIRCLE }
    enum class CriticalBonus {
        RELOAD_BOOST, SECOND_CHANCE, MUNNY_PLUS, BERSERK, AUTO_REMEDY, DOUBLE_CP,
    }

    data class LegacyCombatSnapshot(
        val character: Character,
        val state: Int,
        val motionId: Int,
        val motionFrame: Float,
        val animationType: Int,
        val lastActionType: Int,
        val hitLandedType: Int,
        val airborne: Boolean,
        // PSP-native command definition category when independently resolved.
        // Missing/unsupported categories must not receive timed cancels.
        val commandCategory: Int?,
        val difficulty: Int,
        val newlyPressed: Set<Input> = emptySet(),
    )

    data class Decision(
        val availableCancels: Set<Input>,
        val requestedCancel: Input?,
        val neutralState: Int?,
        val requestInvincibility: Boolean,
        val criticalBonuses: Set<CriticalBonus>,
        val exclusionsApplied: Boolean,
    )

    fun evaluate(source: LegacyCombatSnapshot, options: PatchOptions): Decision {
        val available = mutableSetOf<Input>()
        val attackThreshold = when (source.character) {
            Character.TERRA -> 30.0f
            Character.VENTUS -> 20.0f
            Character.AQUA -> 22.0f
        }
        val commandThreshold = if (source.motionId == 0x5f) 46.0f else
            when (source.character) {
                Character.TERRA -> 45.0f
                Character.VENTUS -> 35.0f
                Character.AQUA -> 40.0f
            }

        if (options.extendedDefense && source.state == 0x16 && source.motionFrame >= 5.0f) {
            available += Input.CROSS
            available += Input.TRIANGLE
        }

        if (options.hitAwareCancels && source.animationType == 5) {
            if (source.hitLandedType == 0) available += Input.TRIANGLE
            val finisherThreshold = attackThreshold + if (source.airborne) 0.0f else 15.0f
            if (source.hitLandedType == 2 && source.motionFrame > finisherThreshold) {
                available += Input.CROSS
            }
        }
        if (options.hitAwareCancels && source.airborne && source.hitLandedType == 2) {
            available += Input.CROSS
            available += Input.TRIANGLE
        }

        val normalCommandCategory = source.commandCategory in 0..3
        if (options.commandCancels && normalCommandCategory) {
            if (source.motionFrame >= commandThreshold && source.animationType == 3 &&
                source.state != 0x15 && source.state != 0x14 && source.state != 0x19
            ) {
                available += Input.CROSS
                available += Input.TRIANGLE
            }
            if ((source.animationType == 4 || source.motionId == 11) && source.state != 0x19) {
                available += Input.CROSS
                available += Input.TRIANGLE
                available += Input.SQUARE
            }
        }

        if (options.extendedDefense && source.state in setOf(0x0c, 0x10, 0x11, 0x13, 0x04)) {
            if (source.animationType != 5 || source.hitLandedType != 1) {
                available += Input.SQUARE
                if (!source.airborne) available += Input.CIRCLE
            }
            if (source.animationType == 5 && source.motionId in setOf(0x27, 0x2e)) {
                available += Input.SQUARE
                if (!source.airborne) available += Input.CIRCLE
                if (source.motionFrame >= commandThreshold) available += Input.CROSS
            }
        }

        // Mandatory PC exclusions, plus PSP category protection. Reject
        // cinematic finishers even if the old PC Lua allowed square cancels;
        // canceling them is an observed gameplay correctness regression.
        val blockedState = source.state in setOf(
            0x0e, 0x14, 0x18, 0x19, 0x1b, 0x1c, 0x1d,
        )
        val blockedAction = source.lastActionType in setOf(
            0x51, 0x58, 0x1d, 0x92, 0x93, 0x94, 0x3b, 0x6f,
        )
        val blockedCategory = options.strictSteamExclusions &&
            (source.commandCategory == null || source.commandCategory !in 0..3)
        val blockAll = blockedState || blockedAction || source.motionId == 0x65 ||
            blockedCategory
        if (blockAll) available.clear()

        // Commands that require Triangle or Cross during their animation
        // cannot safely have those inputs stolen by the cancel system.
        if (source.lastActionType in setOf(0x75, 0x5c, 0x6d, 0x62, 0x63,
                0x6e, 0x5d, 0x6f, 0x70, 0x79)
        ) available.remove(Input.TRIANGLE)
        if (source.lastActionType == 0x82) available.remove(Input.CROSS)
        if (source.airborne) available.remove(Input.CIRCLE)

        val requested = listOf(Input.CROSS, Input.TRIANGLE, Input.SQUARE, Input.CIRCLE)
            .firstOrNull { it in available && it in source.newlyPressed }
        val invincible = options.invincibilityWindows &&
            (source.state == 0x15 ||
                source.motionId in setOf(0x87, 0x88, 0x61, 0x63, 0x8f, 0x58) ||
                (source.motionId == 0x65 && source.motionFrame <= 60.0f))
        val bonuses = if (source.difficulty != 3) emptySet() else buildSet {
            if (options.criticalModeAbilities) {
                add(CriticalBonus.RELOAD_BOOST)
                add(CriticalBonus.SECOND_CHANCE)
            }
            if (options.criticalModePassives) {
                add(CriticalBonus.MUNNY_PLUS)
                add(CriticalBonus.BERSERK)
                add(CriticalBonus.AUTO_REMEDY)
                add(CriticalBonus.DOUBLE_CP)
            }
        }

        return Decision(
            availableCancels = available,
            requestedCancel = requested,
            neutralState = requested?.let { if (source.airborne) 4 else 1 },
            requestInvincibility = invincible,
            criticalBonuses = bonuses,
            exclusionsApplied = blockAll,
        )
    }
}
