package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombatDecisionModelTest {
    private val model = CombatDecisionModel

    private fun scene(
        character: CombatDecisionModel.Character = CombatDecisionModel.Character.TERRA,
        state: Int = 0x10,
        motionId: Int = 0x20,
        frame: Float = 50f,
        type: Int = 5,
        action: Int = 0,
        hit: Int = 0,
        airborne: Boolean = false,
        category: Int? = 0,
        difficulty: Int = 2,
        pressed: Set<CombatDecisionModel.Input> = emptySet(),
    ) = CombatDecisionModel.LegacyCombatSnapshot(
        character, state, motionId, frame, type, action, hit, airborne, category,
        difficulty, pressed,
    )

    @Test
    fun all_combat_groups_remain_disabled_by_default() {
        val d = model.evaluate(scene(pressed = setOf(model.Input.CROSS)), PatchOptions())
        assertTrue(d.availableCancels.isEmpty())
        assertNull(d.requestedCancel)
        assertNull(d.neutralState)
        assertFalse(d.requestInvincibility)
        assertTrue(d.criticalBonuses.isEmpty())
    }

    @Test
    fun hit_aware_rules_distinguish_whiffs_landed_hits_and_finishers() {
        val enabled = PatchOptions(hitAwareCancels = true)
        assertTrue(model.Input.TRIANGLE in model.evaluate(scene(hit = 0), enabled).availableCancels)
        assertTrue(model.evaluate(scene(hit = 1), enabled).availableCancels.isEmpty())
        assertFalse(model.Input.CROSS in model.evaluate(scene(hit = 2, frame = 45f), enabled).availableCancels)
        assertTrue(model.Input.CROSS in model.evaluate(scene(hit = 2, frame = 46f), enabled).availableCancels)
        assertTrue(model.Input.CROSS in model.evaluate(
            scene(hit = 2, airborne = true, frame = 0f), enabled,
        ).availableCancels)
        assertTrue(model.Input.TRIANGLE in model.evaluate(
            scene(hit = 2, airborne = true, frame = 0f), enabled,
        ).availableCancels)
        assertTrue(model.Input.CROSS in model.evaluate(
            scene(character = model.Character.AQUA, hit = 2, frame = 38f), enabled,
        ).availableCancels)
    }

    @Test
    fun command_threshold_is_character_specific_and_category_gated() {
        val options = PatchOptions(commandCancels = true)
        for ((character, minFrame) in listOf(
            model.Character.TERRA to 45f,
            model.Character.VENTUS to 35f,
            model.Character.AQUA to 40f,
        )) {
            assertFalse(model.Input.TRIANGLE in model.evaluate(
                scene(character = character, frame = minFrame - 1f, type = 3), options,
            ).availableCancels)
            assertTrue(model.Input.TRIANGLE in model.evaluate(
                scene(character = character, frame = minFrame, type = 3), options,
            ).availableCancels)
        }
        assertFalse(model.Input.TRIANGLE in model.evaluate(
            scene(frame = 45f, motionId = 0x5f, type = 3), options,
        ).availableCancels)
        assertTrue(model.Input.TRIANGLE in model.evaluate(
            scene(frame = 46f, motionId = 0x5f, type = 3), options,
        ).availableCancels)
        for (category in listOf(null, 0xe, 0xf, 4, 7)) {
            assertTrue(model.evaluate(
                scene(type = 3, category = category), options,
            ).availableCancels.isEmpty(), "Unknown or excluded category $category")
        }
    }

    @Test
    fun extended_defense_uses_guard_minimum_and_native_neutral_modes() {
        val options = PatchOptions(extendedDefense = true)
        assertTrue(model.evaluate(scene(state = 0x16, frame = 4f), options).availableCancels.isEmpty())
        assertTrue(model.Input.CROSS in model.evaluate(
            scene(state = 0x16, frame = 5f), options,
        ).availableCancels)
        val grounded = model.evaluate(
            scene(state = 0x10, pressed = setOf(model.Input.SQUARE)), options,
        )
        assertEquals(model.Input.SQUARE, grounded.requestedCancel)
        assertEquals(1, grounded.neutralState)
        val airborne = model.evaluate(
            scene(state = 0x10, airborne = true, pressed = setOf(model.Input.SQUARE)), options,
        )
        assertEquals(4, airborne.neutralState)
        assertFalse(model.Input.CIRCLE in airborne.availableCancels)
    }

    @Test
    fun mandatory_finish_guarding_and_sensitive_input_exclusions_are_enforced() {
        val options = PatchOptions(
            hitAwareCancels = true, extendedDefense = true, commandCancels = true,
        )
        for (state in listOf(0x0e, 0x14, 0x18, 0x19, 0x1b, 0x1c, 0x1d)) {
            val d = model.evaluate(scene(state = state, type = 4, frame = 100f), options)
            assertTrue(d.availableCancels.isEmpty(), "Protected state $state")
            assertTrue(d.exclusionsApplied)
        }
        assertFalse(model.Input.TRIANGLE in model.evaluate(
            scene(type = 3, action = 0x75), options,
        ).availableCancels)
        assertFalse(model.Input.CROSS in model.evaluate(
            scene(type = 3, action = 0x82), options,
        ).availableCancels)
        assertTrue(model.evaluate(
            scene(type = 3, action = 0x92), options,
        ).availableCancels.isEmpty())
        assertTrue(model.evaluate(
            scene(type = 3, motionId = 0x65), options,
        ).availableCancels.isEmpty())
    }

    @Test
    fun iframes_and_critical_bonuses_are_independent_and_difficulty_gated() {
        val options = PatchOptions(
            invincibilityWindows = true,
            criticalModeAbilities = true,
            criticalModePassives = true,
        )
        assertTrue(model.evaluate(scene(state = 0x15), options).requestInvincibility)
        assertTrue(model.evaluate(scene(state = 1, motionId = 0x87), options).requestInvincibility)
        assertTrue(model.evaluate(scene(state = 1, motionId = 0x65, frame = 60f), options).requestInvincibility)
        assertFalse(model.evaluate(scene(state = 1, motionId = 0x65, frame = 61f), options).requestInvincibility)
        assertTrue(model.evaluate(scene(state = 1, motionId = 0x20), options).criticalBonuses.isEmpty())
        val crit = model.evaluate(scene(state = 1, difficulty = 3), options).criticalBonuses
        assertEquals(6, crit.size)
        assertTrue(model.CriticalBonus.SECOND_CHANCE in crit)
        assertTrue(model.CriticalBonus.DOUBLE_CP in crit)
        assertEquals(2, model.evaluate(
            scene(difficulty = 3), PatchOptions(criticalModeAbilities = true),
        ).criticalBonuses.size)
        assertEquals(4, model.evaluate(
            scene(difficulty = 3), PatchOptions(criticalModePassives = true),
        ).criticalBonuses.size)
    }
}
