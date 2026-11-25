package com.rohit.quickcart.data.realtime

import com.rohit.quickcart.data.ConnectivityObserver
import com.rohit.quickcart.data.remote.fake.FakeOrderSimulator
import com.rohit.quickcart.domain.OrderStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/** One message pushed by the server over the WebSocket. */
@Serializable
data class TrackingUpdate(
    val orderId: String,
    /** A String on the wire, not an enum, so a status added later on the server doesn't crash old apps. */
    val status: String,
    val etaMinutes: Int,
    val riderProgress: Float,
    val message: String,
) {
    val orderStatus: OrderStatus? get() = OrderStatus.entries.firstOrNull { it.name == status }
}

interface OrderTrackingSocket {
    /**
     * Cold Flow: the connection opens when collection starts and closes when the collector
     * is cancelled. Network failures surface as IOException, so callers can retry.
     */
    fun track(serverOrderId: String): Flow<TrackingUpdate>
}

/** Real WebSocket client (OkHttp), used with the FastAPI server in /server. */
class OkHttpOrderTrackingSocket(
    private val client: OkHttpClient,
    private val baseWsUrl: String,
    private val json: Json,
) : OrderTrackingSocket {

    override fun track(serverOrderId: String): Flow<TrackingUpdate> = callbackFlow {
        val request = Request.Builder().url("${baseWsUrl}ws/orders/$serverOrderId").build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // A malformed message is skipped instead of killing the whole stream.
                runCatching { json.decodeFromString<TrackingUpdate>(text) }.onSuccess { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
                channel.close() // server finished (e.g. order delivered): complete normally
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.close(t as? IOException ?: IOException(t)) // -> retryWhen upstream
            }
        }
        val webSocket = client.newWebSocket(request, listener)
        // Runs when the collector is cancelled (screen left) or the channel closes.
        awaitClose { webSocket.close(NORMAL_CLOSURE, "client closed") }
    }.buffer(Channel.CONFLATED) // only the latest status matters if the UI falls behind

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

/** Fake socket for the in-app backend: pushes an update every 2 s until the order is delivered. */
class FakeOrderTrackingSocket(
    private val simulator: FakeOrderSimulator,
    private val connectivity: ConnectivityObserver,
    private val intervalMs: Long = 2_000,
) : OrderTrackingSocket {

    override fun track(serverOrderId: String): Flow<TrackingUpdate> = flow {
        delay(300) // connection handshake
        while (true) {
            if (!connectivity.isOnlineNow()) throw IOException("Connection lost")
            val update = simulator.snapshot(serverOrderId)
            emit(update)
            if (update.orderStatus?.isTerminal == true) return@flow
            delay(intervalMs)
        }
    }
}
