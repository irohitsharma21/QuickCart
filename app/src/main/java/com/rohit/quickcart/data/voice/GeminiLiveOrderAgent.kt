package com.rohit.quickcart.data.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.rohit.quickcart.BuildConfig
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.domain.PaymentMethod
import com.rohit.quickcart.domain.PricingRules
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.util.formatRupees
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * "AI mode" of the voice agent: Gemini Live over a WebSocket, straight from the phone. No backend.
 *
 *   mic (AudioRecord 16 kHz PCM) ──base64 JSON──▶ Gemini Live ──▶ voice (24 kHz PCM) ──▶ AudioTrack
 *                                                      │
 *                                                      └── toolCall ──▶ our validated tools (Room) ──▶ toolResponse
 *
 * Gemini does the listening, understanding and talking. The app keeps control of what matters through
 * FUNCTION CALLING: the model can only change the cart through our tools, every call is validated
 * (real products only, stock, quantity limits), and place_order goes through the normal OrderRepository.
 *
 * Security: the API key comes from local.properties (never committed) into BuildConfig, so this build is
 * for a personal demo only. In production a small backend would mint short-lived "ephemeral tokens".
 */
class GeminiLiveOrderAgent @Inject constructor(
    private val cartRepository: CartRepository,
    private val orderRepository: OrderRepository,
) {
    sealed interface Event {
        /** A chunk of live transcript. Chunks arrive while people speak, so the UI appends them. */
        data class Transcript(val fromUser: Boolean, val text: String) : Event
        data class OrderPlaced(val localOrderId: String) : Event
        data object NeedsAddress : Event
        data class Disconnected(val reason: String) : Event
    }

    val isConfigured: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var scope: CoroutineScope? = null
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var playback = Channel<ByteArray>(Channel.UNLIMITED)

    /** Connects, sends the setup (model, instructions, tools), waits for setupComplete, then streams the mic. */
    suspend fun start(products: List<Product>, onEvent: (Event) -> Unit) {
        check(isConfigured) { "No GEMINI_API_KEY in local.properties" }
        stop()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sessionScope
        playback = Channel(Channel.UNLIMITED)
        val ready = CompletableDeferred<Unit>()

        val request = Request.Builder().url("$ENDPOINT?key=${BuildConfig.GEMINI_API_KEY}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(products).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onServerMessage(text, products, onEvent, ready)

            // The server sends its JSON in binary frames too.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onServerMessage(bytes.utf8(), products, onEvent, ready)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                fail(reason.ifBlank { "connection closed ($code)" }, ready, onEvent)
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName), ready, onEvent)
            }
        })

        try {
            withTimeout(10_000) { ready.await() }
        } catch (e: Exception) {
            stop()
            throw e
        }
        startPlayer(sessionScope)
        startMic(sessionScope)
    }

    private fun fail(reason: String, ready: CompletableDeferred<Unit>, onEvent: (Event) -> Unit) {
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(reason))
        else if (scope != null) onEvent(Event.Disconnected(reason))
    }

    /** A typed message, or a nudge such as "greet the user". */
    fun sendText(text: String) {
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    })
                }
                put("turnComplete", true)
            }
        }
        socket?.send(msg.toString())
    }

    fun stop() {
        scope?.cancel()
        scope = null
        socket?.close(1000, "bye")
        socket = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playback.close()
    }

    // ---- audio ----

    @SuppressLint("MissingPermission") // AI mode only starts after RECORD_AUDIO was granted
    private fun startMic(scope: CoroutineScope) = scope.launch {
        val minBuffer = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION enables the phone's echo cancellation, so Gemini doesn't hear (and answer) its own voice.
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        recorder = rec
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        rec.startRecording()
        val buffer = ByteArray(CHUNK_BYTES) // 40 ms per frame
        while (isActive) {
            val read = rec.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            val frame = buildJsonObject {
                putJsonObject("realtimeInput") {
                    putJsonObject("audio") {
                        put("mimeType", "audio/pcm;rate=$IN_RATE")
                        put("data", Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                    }
                }
            }
            socket?.send(frame.toString())
        }
    }

    private fun startPlayer(scope: CoroutineScope) = scope.launch {
        val minBuffer = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        player = track
        track.play()
        for (chunk in playback) {
            if (!isActive) break
            track.write(chunk, 0, chunk.size) // blocks while the buffer is full: natural pacing
        }
    }

    /** The user started talking over Gemini (barge-in): drop queued audio so it goes quiet at once. */
    private fun interruptPlayback() {
        while (playback.tryReceive().isSuccess) Unit
        player?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    // ---- protocol ----

    private fun onServerMessage(text: String, products: List<Product>, onEvent: (Event) -> Unit, ready: CompletableDeferred<Unit>) {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        if ("setupComplete" in msg) {
            ready.complete(Unit)
            return
        }
        msg["serverContent"]?.jsonObject?.let { content ->
            if (content["interrupted"]?.jsonPrimitive?.booleanOrNull == true) interruptPlayback()
            content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { onEvent(Event.Transcript(fromUser = true, text = it)) }
            content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { onEvent(Event.Transcript(fromUser = false, text = it)) }
            content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                part.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                    ?.let { playback.trySend(Base64.decode(it, Base64.DEFAULT)) }
            }
        }
        msg["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.let { calls ->
            scope?.launch {
                val responses = calls.map { call ->
                    val c = call.jsonObject
                    val name = c["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val args = c["args"]?.jsonObject ?: JsonObject(emptyMap())
                    buildJsonObject {
                        c["id"]?.let { put("id", it) }
                        put("name", name)
                        put("response", runTool(name, args, products, onEvent))
                    }
                }
                socket?.send(buildJsonObject { putJsonObject("toolResponse") { put("functionResponses", JsonArray(responses)) } }.toString())
            }
        }
    }

    // ---- tools: the model can't touch the cart any other way ----

    /** Gemini sometimes sends the product NAME instead of the id; accept either, but only real products. */
    private fun findProduct(idOrName: String?, products: List<Product>): Product? {
        val key = idOrName?.trim()?.lowercase() ?: return null
        return products.firstOrNull { it.id == key } ?: products.firstOrNull { it.name.lowercase() == key }
    }

    internal suspend fun runTool(name: String, args: JsonObject, products: List<Product>, onEvent: (Event) -> Unit): JsonObject =
        runCatching {
            when (name) {
                "add_to_cart" -> {
                    val product = findProduct(args["product_id"]?.jsonPrimitive?.contentOrNull, products)
                        ?: error("Unknown product. Use only product_id values from the catalog.")
                    if (!product.inStock) error("${product.name} is out of stock. Suggest an in-stock alternative.")
                    val qty = (args["quantity"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, PricingRules.MAX_QUANTITY_PER_ITEM)
                    cartRepository.add(product.id, qty)
                    ok("Added $qty x ${product.name} (${product.unit}).", "cart" to cartJson())
                }
                "remove_from_cart" -> {
                    val product = findProduct(args["product_id"]?.jsonPrimitive?.contentOrNull, products)
                    val line = cartRepository.observeCart().first().firstOrNull { it.product.id == product?.id }
                        ?: error("That product is not in the cart.")
                    val qty = (args["quantity"]?.jsonPrimitive?.intOrNull ?: line.quantity).coerceIn(1, line.quantity)
                    repeat(qty) { cartRepository.remove(line.product.id) }
                    ok("Removed $qty x ${line.product.name}.", "cart" to cartJson())
                }
                "get_cart" -> ok("Current cart.", "cart" to cartJson())
                "place_order" -> {
                    val lines = cartRepository.observeCart().first()
                    val address = orderRepository.observeLastAddress().first()
                    when {
                        lines.isEmpty() -> error("The cart is empty.")
                        address.isNullOrBlank() -> {
                            onEvent(Event.NeedsAddress)
                            error("No saved delivery address. Tell the user you're opening checkout to add one.")
                        }
                        else -> {
                            val localId = orderRepository.placeOrder(address, PaymentMethod.CASH_ON_DELIVERY)
                            onEvent(Event.OrderPlaced(localId))
                            ok("Order placed, cash on delivery, to $address. Arriving in about 10 minutes.")
                        }
                    }
                }
                else -> error("Unknown function $name")
            }
        }.getOrElse { e ->
            Log.w(TAG, "tool $name failed: ${e.message}")
            buildJsonObject { put("success", false); put("error", e.message ?: "failed") }
        }

    private suspend fun cartJson(): JsonObject {
        val lines = cartRepository.observeCart().first()
        val bill = PricingRules.bill(lines)
        val address = orderRepository.observeLastAddress().first()
        return buildJsonObject {
            put("items", buildJsonArray {
                lines.forEach {
                    add(buildJsonObject {
                        put("name", it.product.name)
                        put("quantity", it.quantity)
                        put("line_total", it.lineTotalPaise.formatRupees())
                    })
                }
            })
            put("item_count", lines.sumOf { it.quantity })
            put("item_total", bill.itemTotalPaise.formatRupees())
            put("delivery_fee", if (bill.deliveryFeePaise == 0L) "FREE" else bill.deliveryFeePaise.formatRupees())
            put("handling_fee", bill.handlingFeePaise.formatRupees())
            put("total_to_pay", bill.totalPaise.formatRupees())
            put("delivery_address", address ?: "NONE SAVED")
        }
    }

    private fun ok(message: String, vararg extra: Pair<String, JsonElement>) = buildJsonObject {
        put("success", true)
        put("message", message)
        extra.forEach { (k, v) -> put(k, v) }
    }

    companion object {
        private const val TAG = "GeminiLive"
        const val MODEL = "gemini-3.1-flash-live-preview"
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val IN_RATE = 16_000
        private const val OUT_RATE = 24_000
        private const val CHUNK_BYTES = IN_RATE * 2 / 25 // 40 ms of 16-bit mono audio

        fun setupMessage(products: List<Product>): JsonObject = buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/$MODEL")
                putJsonObject("generationConfig") { putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) } }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction(products)) }) }
                }
                putJsonArray("tools") { add(buildJsonObject { put("functionDeclarations", TOOLS) }) }
                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}
            }
        }

        private fun fn(
            name: String,
            description: String,
            properties: Map<String, Pair<String, String>> = emptyMap(),
            required: List<String> = emptyList(),
        ) = buildJsonObject {
            put("name", name)
            put("description", description)
            if (properties.isNotEmpty()) {
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        properties.forEach { (prop, typeAndDescription) ->
                            putJsonObject(prop) {
                                put("type", typeAndDescription.first)
                                put("description", typeAndDescription.second)
                            }
                        }
                    }
                    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }

        private val TOOLS = JsonArray(
            listOf(
                fn(
                    "add_to_cart", "Add a product to the cart. Call once per product the user asks for.",
                    mapOf("product_id" to ("STRING" to "The product_id (first column) from the catalog, e.g. milk"), "quantity" to ("INTEGER" to "Number of packs, 1-10")),
                    required = listOf("product_id", "quantity"),
                ),
                fn(
                    "remove_from_cart", "Remove a product from the cart.",
                    mapOf("product_id" to ("STRING" to "The product_id from the catalog"), "quantity" to ("INTEGER" to "Packs to remove; omit to remove all")),
                    required = listOf("product_id"),
                ),
                fn("get_cart", "Get the cart items, the bill with fees, and the saved delivery address."),
                fn(
                    "place_order",
                    "Place the order, cash on delivery, to the saved address. ONLY after you read back the total and address and the user clearly said yes.",
                ),
            ),
        )

        fun systemInstruction(products: List<Product>): String = buildString {
            appendLine("You are QuickCart's voice ordering assistant, for a 10-minute grocery delivery app in India.")
            appendLine("Reply in the user's language: English, Hinglish, or Hindi. Keep every reply to one short sentence.")
            appendLine("Only these products exist. Columns: product_id | name | pack size | price | stock")
            products.forEach { p ->
                appendLine("${p.id} | ${p.name} | ${p.unit} | ${p.pricePaise.formatRupees()} | ${if (p.inStock) "in stock" else "OUT OF STOCK"}")
            }
            appendLine(
                """
                Rules:
                - When the user asks for items, call add_to_cart for each with the product_id (first column) and the quantity in packs.
                  Convert amounts: "1 kilo doodh" = 1 litre = 2 packs of 500 ml; "ek dozen ande" = 12 eggs = 2 packs of 6.
                  1 kg of a liquid is about 1 litre.
                - Common Hindi names: doodh=milk, pyaaz=onion, aloo=potato, tamatar=tomato, ande=eggs, chawal=rice,
                  aata=atta, cheeni=sugar, namak=salt, dahi=curd, kela=banana.
                - If you are not sure which product they mean, ask one short question instead of guessing.
                - After adding, say briefly what was added and ask if they want anything else.
                - Use remove_from_cart to remove, get_cart for the total.
                - When the user is done ("that's it", "bas", "order kar do", "place the order"): call get_cart, tell them
                  the total to pay and the delivery address, and ask "Shall I place the order?".
                - Call place_order ONLY after the user clearly agrees to that question. If they hesitate or say no, don't.
                - Never invent products, prices or discounts. If something is out of stock, say so and suggest a similar item.
                """.trimIndent(),
            )
        }
    }
}
