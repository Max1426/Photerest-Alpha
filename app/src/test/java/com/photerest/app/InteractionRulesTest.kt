package com.photerest.app

import org.junit.Assert.*
import org.junit.Test

class InteractionRulesTest {
    @Test fun hapticsRespectImmediateSettingChanges() {
        var enabled = true; var pulses = 0
        val feedback = TapFeedback({ enabled }) { pulses++ }
        feedback.perform(); assertEquals(1, pulses)
        enabled = false
        repeat(20) { feedback.perform() }; assertEquals(1, pulses)
        enabled = true
        feedback.perform(); assertEquals(2, pulses)
    }

    @Test fun noTextModeDefaultsOn() {
        // The text-free grid is the shipped default; existing installs have no preference key.
        assertTrue(AppSettings().noTextMode)
        assertFalse(AppSettings(noTextMode = false).noTextMode)
    }

    @Test fun videoAutoplayDefaultsOn() {
        // Muted grid previews are on by default for upgrades too; the prefs key is absent on 1.3.1.
        assertTrue(AppSettings().autoplayVideos)
        assertFalse(AppSettings(autoplayVideos = false).autoplayVideos)
    }

    @Test fun videosAreShownByDefault() {
        // Videos stay in the waterfalls until the new setting is turned off; old installs have no key.
        assertTrue(AppSettings().showVideos)
        assertFalse(AppSettings(showVideos = false).showVideos)
    }
}
