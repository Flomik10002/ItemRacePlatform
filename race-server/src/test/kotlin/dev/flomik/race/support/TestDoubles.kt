package dev.flomik.race.support

import dev.flomik.race.application.Clock
import dev.flomik.race.application.IdGenerator
import dev.flomik.race.application.RandomSource
import java.time.Instant

class MutableClock(
    private var current: Instant,
) : Clock {
    override fun now(): Instant = current

    fun advanceMillis(millis: Long) {
        current = current.plusMillis(millis)
    }
}

class SequentialIdGenerator : IdGenerator {
    private var sessionSeq = 1
    private var roomSeq = 1
    private var matchSeq = 1

    override fun nextSessionId(): String = "session-${sessionSeq++}"

    override fun nextRoomId(): String = "room-${roomSeq++}"

    override fun nextMatchId(): String = "match-${matchSeq++}"
}

class DeterministicRandomSource(
    private val longs: MutableList<Long> = mutableListOf(),
    private val ints: MutableList<Int> = mutableListOf(),
) : RandomSource {
    private var intSeq: Int = 0

    override fun nextLong(): Long {
        if (longs.isNotEmpty()) {
            return longs.removeAt(0)
        }
        return 42L
    }

    override fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        if (ints.isNotEmpty()) {
            val next = ints.removeAt(0)
            val normalized = if (next < 0) -next else next
            return normalized % bound
        }
        val next = intSeq++
        return next % bound
    }
}
