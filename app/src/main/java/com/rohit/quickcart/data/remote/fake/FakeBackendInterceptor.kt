package com.rohit.quickcart.data.remote.fake

import com.rohit.quickcart.data.ConnectivityObserver
import com.rohit.quickcart.data.remote.CreateOrderRequest
import com.rohit.quickcart.data.remote.CreateOrderResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * An in-app backend. It's the last OkHttp interceptor, so it answers requests itself
 * instead of sending them over the network. Retrofit, JSON parsing and error handling still
 * run exactly as they would against a real server.
 *
 * It throws IOException when the device is offline, so airplane mode really breaks the
 * "network", and the offline-first code paths can be demoed honestly.
 */
class FakeBackendInterceptor(
    private val connectivity: ConnectivityObserver,
    private val simulator: FakeOrderSimulator,
    private val json: Json,
    private val latencyMs: Long = 600,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!connectivity.isOnlineNow()) throw IOException("No network connection")
        // Runs on an OkHttp worker thread (Retrofit's suspend calls use enqueue), never the main thread.
        if (latencyMs > 0) Thread.sleep(latencyMs)

        val path = request.url.encodedPath
        return when {
            request.method == "GET" && path == "/catalog" ->
                respond(request, 200, json.encodeToString(FakeCatalog.response()))

            request.method == "POST" && path == "/orders" -> createOrder(request)

            else -> respond(request, 404, """{"error":"not found"}""")
        }
    }

    private fun createOrder(request: Request): Response {
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        val order = runCatching { json.decodeFromString<CreateOrderRequest>(body) }.getOrNull()
            ?: return respond(request, 400, """{"error":"bad request"}""")
        val known = FakeCatalog.productIds()
        if (order.items.isEmpty() || order.items.any { it.productId !in known || it.quantity <= 0 }) {
            return respond(request, 422, """{"error":"invalid items"}""")
        }
        val serverId = simulator.createOrder(order.clientOrderId)
        val response = CreateOrderResponse(serverId, "PLACED", OrderTimeline.TOTAL_ETA_MINUTES)
        return respond(request, 201, json.encodeToString(response))
    }

    private fun respond(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code < 400) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
