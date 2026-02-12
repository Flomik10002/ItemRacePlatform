package dev.flomik.race.domain

class DomainException(
    val code: String,
    override val message: String,
) : RuntimeException(message)
