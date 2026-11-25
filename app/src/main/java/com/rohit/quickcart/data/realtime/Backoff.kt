package com.rohit.quickcart.data.realtime

import kotlin.random.Random

/**
 * Exponential backoff with jitter: 1 s, 2 s, 4 s, 8 s ... capped at 30 s, each ±20%.
 * The jitter stops thousands of phones from reconnecting at the same instant after an
 * outage (the "thundering herd" problem).
 */
object Backoff {
    const val BASE_MS = 1_000L
    const val MAX_MS = 30_000L

    fun delayMillis(attempt: Long, random: Random = Random.Default): Long {
        val exponential = BASE_MS shl attempt.coerceIn(0, 10).toInt()
        val capped = exponential.coerceAtMost(MAX_MS)
        val jitter = (capped * 0.2 * (random.nextDouble() * 2 - 1)).toLong()
        return (capped + jitter).coerceIn(BASE_MS / 2, MAX_MS)
    }
}
