package dev.flomik.race.transport

fun isIgnorableAdvancementId(advancementIdRaw: String): Boolean {
    val normalized = advancementIdRaw.trim().lowercase()
    if (normalized.isEmpty()) return true

    val path = normalized.substringAfter(':', normalized)
    return path == "root" || path.endsWith("/root")
}

