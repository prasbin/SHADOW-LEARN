package com.prasbin.shadowlearn.data.listener

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM state-machine tests: every legal transition is accepted and every
 * illegal one throws. No Android, no database — the gate itself is the unit.
 */
class ListenerStateTest {

    @Test
    fun idleStartsRecordingOrPermissionFlow() {
        assertTrue(ListenerState.canTransition(ListenerState.IDLE, ListenerState.REQUESTING_PERMISSION))
        assertTrue(ListenerState.canTransition(ListenerState.IDLE, ListenerState.RECORDING))
    }

    @Test
    fun permissionFlowResolves() {
        assertTrue(ListenerState.canTransition(ListenerState.REQUESTING_PERMISSION, ListenerState.RECORDING))
        assertTrue(ListenerState.canTransition(ListenerState.REQUESTING_PERMISSION, ListenerState.IDLE))
        assertTrue(ListenerState.canTransition(ListenerState.REQUESTING_PERMISSION, ListenerState.ERROR))
    }

    @Test
    fun recordingPausesCompletesOrFails() {
        assertTrue(ListenerState.canTransition(ListenerState.RECORDING, ListenerState.PAUSED))
        assertTrue(ListenerState.canTransition(ListenerState.RECORDING, ListenerState.COMPLETED))
        assertTrue(ListenerState.canTransition(ListenerState.RECORDING, ListenerState.ERROR))
    }

    @Test
    fun pausedResumesCompletesOrFails() {
        assertTrue(ListenerState.canTransition(ListenerState.PAUSED, ListenerState.RECORDING))
        assertTrue(ListenerState.canTransition(ListenerState.PAUSED, ListenerState.COMPLETED))
        assertTrue(ListenerState.canTransition(ListenerState.PAUSED, ListenerState.ERROR))
    }

    @Test
    fun completedAndErrorOnlyReset() {
        assertTrue(ListenerState.canTransition(ListenerState.COMPLETED, ListenerState.IDLE))
        assertTrue(ListenerState.canTransition(ListenerState.ERROR, ListenerState.IDLE))
    }

    @Test
    fun illegalTransitionsAreRejected() {
        val illegal = listOf(
            ListenerState.PAUSED to ListenerState.PAUSED,
            ListenerState.RECORDING to ListenerState.RECORDING,
            ListenerState.COMPLETED to ListenerState.RECORDING,
            ListenerState.COMPLETED to ListenerState.PAUSED,
            ListenerState.RECORDING to ListenerState.IDLE,
            ListenerState.PAUSED to ListenerState.IDLE,
            ListenerState.IDLE to ListenerState.PAUSED,
            ListenerState.IDLE to ListenerState.COMPLETED,
            ListenerState.IDLE to ListenerState.ERROR,
            ListenerState.ERROR to ListenerState.RECORDING,
            ListenerState.ERROR to ListenerState.COMPLETED,
            ListenerState.REQUESTING_PERMISSION to ListenerState.PAUSED,
            ListenerState.REQUESTING_PERMISSION to ListenerState.COMPLETED
        )
        for ((from, to) in illegal) {
            assertFalse("$from -> $to must be illegal", ListenerState.canTransition(from, to))
            try {
                ListenerState.requireTransition(from, to)
                fail("$from -> $to must throw")
            } catch (e: IllegalStateException) {
                // expected
            }
        }
    }
}
