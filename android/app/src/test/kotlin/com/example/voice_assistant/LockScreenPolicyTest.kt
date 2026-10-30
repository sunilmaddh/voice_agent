package com.example.voice_assistant

import com.example.voice_assistant.lockscreen.*
import org.junit.Assert.*
import org.junit.Test

class LockScreenPolicyTest {
    @Test fun allCommandsAndStatesRemainPendingUntilDeviceExecution() {
        val matrix = LockScreenPolicy.matrix()
        assertEquals(36, matrix.size)
        assertEquals(36, matrix.map { it.command to it.state }.distinct().size)
        assertTrue(matrix.all { it.evidence == "PENDING_DEVICE_TEST" && it.status != LockScreenStatus.WORKS })
    }
    @Test fun privateActionsAlwaysRequireUnlock() {
        val matrix = LockScreenPolicy.matrix()
        assertTrue(matrix.filterNot { LockScreenPolicy.isTime(it.command) || it.command == "Create a reminder." }.all { it.unlockRequired })
        assertTrue(matrix.filter { it.command == "Create a reminder." }.all { !it.unlockRequired })
        assertTrue(matrix.filter { it.command == "Read my latest message." }.all { it.status == LockScreenStatus.NOT_SUPPORTED })
    }
    @Test fun timeWhitelistRejectsCompoundAndUnknownRequests() {
        assertTrue(LockScreenPolicy.isTime("What time is it?"))
        assertFalse(LockScreenPolicy.isTime("What time is it and read my messages"))
        assertFalse(LockScreenPolicy.isTime("Don't tell me what time is it"))
    }
    @Test fun noWakeWordOrScreenOffListeningClaims() {
        LockScreenPolicy.matrix().forEach {
            assertEquals(true, it.toWire()["screenOnRequired"])
            assertTrue((it.toWire()["wakeWordBehavior"] as String).contains("PENDING_DEVICE_TEST"))
        }
    }
}
