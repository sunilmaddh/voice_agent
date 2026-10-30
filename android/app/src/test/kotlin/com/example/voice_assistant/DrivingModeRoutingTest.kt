package com.example.voice_assistant

import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.lockscreen.SessionAccessPolicy
import org.junit.Assert.*
import org.junit.Test

class DrivingModeRoutingTest {
    @Test fun drivingCommandsBypassModelsAndValidate() {
        val router = LocalIntentRouter()
        listOf("Hey Aiko, I'm driving. Don't disturb me.", "Hey Aiko I'm driving don't disturb me",
            "Hey Indo I am driving do not disturb me", "I'm driving. Don't disturb me.",
            "Hey Aiko, I\u2019m driving. Don\u2019t disturb me.", "I am driving", "enable driving mode").forEach {
            val route = router.decide(it)
            assertEquals(IntentKind.drivingModeOn, route.intent.kind)
            assertEquals(RoutingTier.deterministic, route.tier)
            assertNotNull(route.proposedAction)
        }
        assertEquals(IntentKind.drivingModeOff, router.decide("turn off driving mode").intent.kind)
    }
    @Test fun negationAndCompoundInstructionsDoNotToggleMode() {
        listOf("don't enable driving mode", "maybe I am driving", "I am driving and send an SMS",
            "turn off driving mode and call Rahul", "Hey Aiko don't enable driving mode",
            "Hey Aiko I'm driving don't disturb me and send a message").forEach {
            val route = LocalIntentRouter().decide(it)
            assertNull(route.proposedAction)
        }
    }
    @Test fun modelCannotSmuggleAutomaticSmsSettingsIntoToggle() {
        val intent = AssistantIntent(IntentKind.drivingModeOn,
            listOf(IntentParameter("automaticReply", ParameterType.boolean, true)), 1.0)
        assertTrue(CommandValidator().validate(intent) is CapabilityResult.Unavailable)
        assertFalse(SessionAccessPolicy.requiresUnlock(IntentKind.drivingModeOn, true, true))
    }
}
