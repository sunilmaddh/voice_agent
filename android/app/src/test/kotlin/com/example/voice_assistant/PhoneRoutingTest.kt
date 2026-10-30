package com.example.voice_assistant

import com.example.voice_assistant.actions.PhoneRecipient
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.*
import org.junit.Assert.*
import org.junit.Test

class PhoneRoutingTest {
    @Test fun directCallingExcludesShortCodesAndInjectedDialSequences() {
        assertTrue(com.example.voice_assistant.actions.DirectCallPolicy.allowedNumber("+919876543210"))
        for (number in listOf("112", "911", "123456", "*123#", "9876543210;123", "9876543210,123"))
            assertFalse(com.example.voice_assistant.actions.DirectCallPolicy.allowedNumber(number))
    }
    @Test fun numberAndNameAreStructuredLocally() {
        for (input in listOf("Call Rahul", "dial +91 98765 43210", "phone Rahul Sharma")) {
            val route = LocalIntentRouter().decide(input)
            assertEquals(IntentKind.callPhone, route.proposedAction!!.intent.kind)
            assertEquals(RoutingTier.deterministic, route.tier)
        }
    }
    @Test fun unsafeOrAmbiguousCommandsNeverProposeCalls() {
        for (input in listOf("don't call Rahul", "call Rahul or Amit", "call him", "call *123#", "call tel:123", "call 123;456", "call Rahul on WhatsApp"))
            assertNull(input, LocalIntentRouter().decide(input).proposedAction)
    }
    @Test fun numberNormalizationRejectsControlSequences() {
        assertEquals("+919876543210", PhoneRecipient.number("+91 (98765) 43210"))
        for (value in listOf("123,456", "*123#", "123;456", "1234567890123456")) assertNull(PhoneRecipient.number(value))
    }
}
