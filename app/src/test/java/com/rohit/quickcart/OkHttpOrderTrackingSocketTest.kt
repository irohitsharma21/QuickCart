package com.rohit.quickcart

import com.rohit.quickcart.data.realtime.OkHttpOrderTrackingSocket
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Tests the real OkHttp WebSocket client against a local MockWebServer. */
class OkHttpOrderTrackingSocketTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun socket(): OkHttpOrderTrackingSocket {
        val wsUrl = server.url("/").toString().replaceFirst("http", "ws")
        return OkHttpOrderTrackingSocket(OkHttpClient(), wsUrl, testJson)
    }

    @Test
    fun parsesMessages_skipsMalformedOnes_andCompletesWhenServerCloses() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"orderId":"QC1","status":"PACKED","etaMinutes":8,"riderProgress":0,"message":"m"}""")
                    webSocket.send("not json at all")
                    webSocket.send("""{"orderId":"QC1","status":"OUT_FOR_DELIVERY","etaMinutes":4,"riderProgress":0.5,"message":"m","extra":"ignored"}""")
                    webSocket.close(1000, "done")
                }
            }),
        )
        server.start()

        val updates = withTimeout(5_000) { socket().track("QC1").toList() }

        // The flow is conflated (only the latest status matters), so intermediate updates may be skipped,
        // but the last one must arrive, and the malformed message must not break the stream.
        assertTrue(updates.isNotEmpty())
        assertEquals("OUT_FOR_DELIVERY", updates.last().status)
        assertEquals(0.5f, updates.last().riderProgress)
        assertEquals("/ws/orders/QC1", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun failedHandshake_surfacesAsIOException_soTheRepositoryRetries() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            withTimeout(5_000) { socket().track("QC1").toList() }
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
