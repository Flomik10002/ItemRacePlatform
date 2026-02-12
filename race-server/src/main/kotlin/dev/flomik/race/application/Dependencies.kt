package dev.flomik.race.application

import java.time.Instant
import java.util.UUID
import kotlin.random.Random

interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

interface IdGenerator {
    fun nextSessionId(): String
    fun nextRoomId(): String
    fun nextMatchId(): String
}

class UuidIdGenerator : IdGenerator {
    override fun nextSessionId(): String = UUID.randomUUID().toString()
    override fun nextRoomId(): String = UUID.randomUUID().toString()
    override fun nextMatchId(): String = UUID.randomUUID().toString()
}

interface RandomSource {
    fun nextLong(): Long
    fun nextInt(bound: Int): Int
}

class KotlinRandomSource(
    private val random: Random = Random.Default,
) : RandomSource {
    override fun nextLong(): Long = random.nextLong()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}
