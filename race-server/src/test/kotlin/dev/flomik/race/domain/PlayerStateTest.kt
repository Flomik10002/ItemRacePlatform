package dev.flomik.race.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlayerStateTest {
    @Test
    fun runningPlayerCanFinishWithResult() {
        val state = PlayerState(status = PlayerStatus.RUNNING)

        state.finish(PlayerResult(rttMs = 1200, igtMs = 1100))

        assertEquals(PlayerStatus.FINISHED, state.status)
        assertEquals(1200, state.result?.rttMs)
        assertNull(state.leaveReason)
        assertNull(state.leftAt)
    }

    @Test
    fun terminalPlayerCannotFinishTwice() {
        val state = PlayerState(status = PlayerStatus.RUNNING)
        state.finish(PlayerResult(rttMs = 100, igtMs = 90))

        val error = assertFailsWith<DomainException> {
            state.finish(PlayerResult(rttMs = 101, igtMs = 91))
        }

        assertEquals("INVALID_TRANSITION", error.code)
    }

    @Test
    fun runningPlayerCanLeaveWithReason() {
        val state = PlayerState(status = PlayerStatus.RUNNING)
        val now = Instant.parse("2026-01-01T00:00:00Z")

        state.leave(LeaveReason.MANUAL, now)

        assertEquals(PlayerStatus.LEAVE, state.status)
        assertEquals(LeaveReason.MANUAL, state.leaveReason)
        assertEquals(now, state.leftAt)
        assertNull(state.result)
    }
}
