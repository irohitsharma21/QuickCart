package com.rohit.quickcart.data.remote.fake

import com.rohit.quickcart.data.realtime.TrackingUpdate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Plays the server's role in the fake backend: remembers orders and works out each order's
 * status from how long ago it was placed. A 10-minute delivery is compressed into ~65 seconds
 * so it can be demoed. The FastAPI server uses the same timeline.
 */
@Singleton
class FakeOrderSimulator @Inject constructor() {

    internal var clock: () -> Long = System::currentTimeMillis

    private val startTimes = ConcurrentHashMap<String, Long>()          // serverOrderId -> placedAt
    private val serverIdByClientId = ConcurrentHashMap<String, String>() // idempotency map

    /** Same clientOrderId -> same server order. This is what makes retries safe. */
    fun createOrder(clientOrderId: String): String =
        serverIdByClientId.computeIfAbsent(clientOrderId) {
            val serverId = "QC" + (100_000..999_999).random()
            startTimes[serverId] = clock()
            serverId
        }

    fun snapshot(serverOrderId: String): TrackingUpdate {
        // Unknown id (e.g. the app was killed, and with it this in-memory "server"): start the timeline now.
        // The client never lets status go backwards, so this can't regress the UI.
        val start = startTimes.computeIfAbsent(serverOrderId) { clock() }
        return OrderTimeline.at(serverOrderId, clock() - start)
    }

    fun orderCount(): Int = serverIdByClientId.size
}

object OrderTimeline {
    const val PACKED_AT_MS = 10_000L
    const val OUT_FOR_DELIVERY_AT_MS = 25_000L
    const val DELIVERED_AT_MS = 65_000L
    const val TOTAL_ETA_MINUTES = 10
    private const val MS_PER_DISPLAY_MINUTE = DELIVERED_AT_MS / TOTAL_ETA_MINUTES

    fun at(orderId: String, elapsedMs: Long): TrackingUpdate {
        val eta = ceil((DELIVERED_AT_MS - elapsedMs).toDouble() / MS_PER_DISPLAY_MINUTE).toInt().coerceAtLeast(1)
        return when {
            elapsedMs < PACKED_AT_MS ->
                TrackingUpdate(orderId, "PLACED", eta, 0f, "Order received, picking your items")
            elapsedMs < OUT_FOR_DELIVERY_AT_MS ->
                TrackingUpdate(orderId, "PACKED", eta, 0f, "Packed and waiting for the rider")
            elapsedMs < DELIVERED_AT_MS -> {
                val progress = (elapsedMs - OUT_FOR_DELIVERY_AT_MS).toFloat() / (DELIVERED_AT_MS - OUT_FOR_DELIVERY_AT_MS)
                TrackingUpdate(orderId, "OUT_FOR_DELIVERY", eta, progress.coerceIn(0f, 1f), "Rider is on the way")
            }
            else -> TrackingUpdate(orderId, "DELIVERED", 0, 1f, "Delivered. Enjoy!")
        }
    }
}
