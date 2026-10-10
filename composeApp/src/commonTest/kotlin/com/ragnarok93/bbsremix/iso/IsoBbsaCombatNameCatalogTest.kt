package com.ragnarok93.bbsremix.iso

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IsoBbsaCombatNameCatalogTest {
    @Test
    fun original_iso_lua_hashes_are_resolved_to_static_dictionary_hints() {
        assertEquals(205, IsoBbsaCombatNameCatalog.size())
        for (name in listOf(
            "B56EX00", "B82VS00", "M08EX00", "B10CD00",
            "B83VS00", "B10SB00", "B52EX00",
            "TERRA", "VENTUS", "B11CD00",
        )) {
            val hash = IsoBbsaDirectoryEvidence.fileNameHash(name)
            assertEquals(name, IsoBbsaCombatNameCatalog.lookup(hash))
            assertEquals(name, IsoBbsaLuaCategorySurvey.nameHint(hash))
        }
        assertEquals(null, IsoBbsaCombatNameCatalog.lookup(0x12345678))
    }

    @Test
    fun cooccurrence_matrix_reports_leads_without_promoting_enemy_callbacks() {
        val candidate = IsoBbsaExhaustiveCombatSurvey.ScriptCrossReference(
            hash = IsoBbsaDirectoryEvidence.fileNameHash("B56EX00"),
            filenameHint = "B56EX00",
            nativeApiNames = listOf("SetTrgFlagCancel", "IsAttacking"),
            hitEventNames = listOf("OnHitAttack"),
            callbackNames = listOf("OnUpdate"),
        )
        val other = IsoBbsaExhaustiveCombatSurvey.ScriptCrossReference(
            hash = IsoBbsaDirectoryEvidence.fileNameHash("M50EX00"),
            filenameHint = "M50EX00",
            nativeApiNames = listOf("GetPlayerState", "IsAttacking"),
            hitEventNames = listOf("OnHitAttack"),
            callbackNames = listOf("OnUpdate", "OnCommand"),
        )
        val lines = IsoBbsaExhaustiveCombatSurvey.candidateLines(listOf(candidate, other))
        val text = lines.joinToString("\n")
        assertContains(text, "API_MATRIX SetTrgFlagCancel script_count=1")
        assertContains(text, "name_hint=B56EX00")
        assertContains(text, "HIT_PLAYER_ATTACK_NAME_OVERLAP scripts=1")
        assertContains(text, "name_hint=M50EX00")
        assertContains(text, "not proof of player hit-confirm")
        assertFalse(text.contains("player hook verified"))
    }
}
