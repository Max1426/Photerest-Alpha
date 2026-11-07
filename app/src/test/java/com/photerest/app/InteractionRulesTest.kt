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
}
