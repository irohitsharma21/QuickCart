package com.rohit.quickcart.domain

import kotlin.math.roundToInt

/**
 * "Running low?" suggestions, made on the phone from the user's own order history.
 *
 * For every product bought at least twice, the average gap between purchases is the
 * user's personal "repeat interval" (e.g. milk every 2 days). When enough of that interval
 * has passed since the last purchase, the product is suggested.
 *
 * Products bought only once can't have an interval yet (the cold-start problem). They're
 * returned as plain "Buy again" items so the row is never empty for a returning user.
 *
 * It's deliberately simple: explainable, testable, offline, zero cost. The next step would be
 * weighting recent gaps more (exponential smoothing), or a server model across all users.
 */
object ReorderPredictor {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Suggest when at least this fraction of the usual gap has passed. */
    private const val DUE_FRACTION = 0.8

    data class Suggestion(
        val productId: String,
        val reason: String,
        /** Higher = more overdue. Used for sorting. */
        val urgency: Double,
        val isPrediction: Boolean,
    )

    fun suggest(orders: List<Order>, nowMs: Long, limit: Int = 8): List<Suggestion> {
        // productId -> sorted, de-duplicated purchase days
        val purchaseTimes = orders
            .filter { it.status != OrderStatus.CANCELLED }
            .flatMap { order -> order.lines.map { it.productId to order.createdAt } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.sorted().distinctBy { it / DAY_MS } }

        val predictions = purchaseTimes.mapNotNull { (productId, times) ->
            if (times.size < 2) return@mapNotNull null
            val gaps = times.zipWithNext { a, b -> b - a }
            val avgGap = gaps.average()
            if (avgGap <= 0) return@mapNotNull null
            val sinceLast = (nowMs - times.last()).toDouble()
            val urgency = sinceLast / avgGap
            if (urgency < DUE_FRACTION) return@mapNotNull null
            Suggestion(productId, "You buy this every ${describe(avgGap)}", urgency, isPrediction = true)
        }.sortedByDescending { it.urgency }

        val predicted = predictions.map { it.productId }.toSet()
        val buyAgain = purchaseTimes
            .filter { (id, times) -> id !in predicted && times.size == 1 }
            .entries
            .sortedByDescending { it.value.last() }
            .map { Suggestion(it.key, "Bought ${describeAgo(nowMs - it.value.last())}", urgency = 0.0, isPrediction = false) }

        return (predictions + buyAgain).take(limit)
    }

    private fun describe(gapMs: Double): String {
        val days = (gapMs / DAY_MS).roundToInt()
        return when {
            days <= 1 -> "day"
            days < 7 -> "~$days days"
            days < 14 -> "week"
            else -> "~${(days / 7.0).roundToInt()} weeks"
        }
    }

    private fun describeAgo(ms: Long): String {
        val days = ms / DAY_MS
        return when {
            days < 1 -> "today"
            days < 2 -> "yesterday"
            else -> "$days days ago"
        }
    }
}
