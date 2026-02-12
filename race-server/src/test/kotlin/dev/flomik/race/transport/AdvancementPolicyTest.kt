package dev.flomik.race.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancementPolicyTest {
    @Test
    fun rootAdvancementsAreIgnored() {
        assertTrue(isIgnorableAdvancementId("minecraft:story/root"))
        assertTrue(isIgnorableAdvancementId("minecraft:adventure/root"))
        assertTrue(isIgnorableAdvancementId("root"))
        assertTrue(isIgnorableAdvancementId("  MINECRAFT:END/ROOT  "))
    }

    @Test
    fun regularAdvancementsAreNotIgnored() {
        assertFalse(isIgnorableAdvancementId("minecraft:story/mine_stone"))
        assertFalse(isIgnorableAdvancementId("minecraft:adventure/sleep_in_bed"))
    }
}

