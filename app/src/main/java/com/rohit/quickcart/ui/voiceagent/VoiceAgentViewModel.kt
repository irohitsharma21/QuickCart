package com.rohit.quickcart.ui.voiceagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.data.ConnectivityObserver
import com.rohit.quickcart.data.voice.GeminiLiveOrderAgent
import com.rohit.quickcart.data.voice.SpeechEvent
import com.rohit.quickcart.data.voice.SpeechIo
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.domain.voiceagent.AgentLanguage
import com.rohit.quickcart.domain.voiceagent.AgentNext
import com.rohit.quickcart.domain.voiceagent.CartAction
import com.rohit.quickcart.domain.voiceagent.Pending
import com.rohit.quickcart.domain.voiceagent.VoiceOrderAgent
import com.rohit.quickcart.ui.home.CartSummary
import com.rohit.quickcart.ui.home.toCartSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the conversation is right now: drives the animated orb and the status text. */
enum class AgentPhase { IDLE, SPEAKING, LISTENING, THINKING, CONNECTING, LIVE }

/** GEMINI = Gemini Live (natural conversation, needs internet). OFFLINE = on-device rule-based agent. */
enum class AgentMode { GEMINI, OFFLINE }

data class ChatMessage(val fromUser: Boolean, val text: String)

/** Where to go after the agent's last sentence. */
sealed interface AgentNavigation {
    data object Cart : AgentNavigation
    data object Checkout : AgentNavigation
    data class Tracking(val localOrderId: String) : AgentNavigation
}

data class VoiceAgentUiState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    /** Live transcript while the user is still speaking. */
    val partial: String = "",
    val language: AgentLanguage = AgentLanguage.ENGLISH,
    val micAllowed: Boolean = false,
    val recognitionAvailable: Boolean = true,
    val cart: CartSummary = CartSummary(),
    val mode: AgentMode = AgentMode.OFFLINE,
    val geminiAvailable: Boolean = false,
    /** Why AI mode isn't running (shown as a small note), e.g. no internet. */
    val notice: String? = null,
)

/**
 * Runs the conversation loop:
 *
 *   speak(reply) ──SpeakingDone──▶ listen() ──Heard(text)──▶ agent.handle() ──▶ apply cart actions ──▶ speak(reply) ...
 *
 * Turn-taking is half-duplex: the mic opens only after the agent finishes speaking, so the agent never
 * hears itself. Tapping the mic while it speaks interrupts it (manual barge-in).
 */
@HiltViewModel
class VoiceAgentViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    private val cartRepository: CartRepository,
    private val orderRepository: OrderRepository,
    private val speech: SpeechIo,
    private val gemini: GeminiLiveOrderAgent,
    private val connectivity: ConnectivityObserver,
) : ViewModel() {

    private val products: StateFlow<List<Product>> =
        catalogRepository.observeProducts(null, "").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val cartLines: StateFlow<List<CartLine>> =
        cartRepository.observeCart().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val lastAddress: StateFlow<String?> =
        orderRepository.observeLastAddress().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val state = MutableStateFlow(
        VoiceAgentUiState(
            recognitionAvailable = speech.isRecognitionAvailable,
            geminiAvailable = gemini.isConfigured,
            mode = if (gemini.isConfigured) AgentMode.GEMINI else AgentMode.OFFLINE,
        ),
    )
    val uiState: StateFlow<VoiceAgentUiState> = combine(state, cartLines) { s, cart -> s.copy(cart = cart.toCartSummary()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    private val _navigation = Channel<AgentNavigation>(Channel.BUFFERED)
    val navigation: Flow<AgentNavigation> = _navigation.receiveAsFlow()

    private var pending: Pending? = null
    /** Set when the agent's current sentence is its last one: navigate once it finishes speaking. */
    private var navigateAfterSpeaking: AgentNavigation? = null
    private var missesInARow = 0
    private var started = false

    init {
        viewModelScope.launch { speech.events.collect(::onSpeechEvent) }
    }

    /** Called once the screen knows whether the mic permission was granted. */
    fun start(micAllowed: Boolean) {
        state.update { it.copy(micAllowed = micAllowed) }
        if (started) return
        started = true
        viewModelScope.launch {
            products.first { it.isNotEmpty() } // wait for the catalog before greeting
            if (state.value.mode == AgentMode.GEMINI) startGemini() else say(agent().greeting())
        }
    }

    /** Toggle between Gemini Live and the on-device agent. */
    fun onModeChange(mode: AgentMode) {
        if (mode == state.value.mode) return
        speech.stop()
        viewModelScope.launch {
            gemini.stop()
            pending = null
            state.update { it.copy(mode = mode, partial = "", notice = null) }
            if (mode == AgentMode.GEMINI) startGemini() else say(agent().greeting())
        }
    }

    private suspend fun startGemini() {
        val s = state.value
        val reason = when {
            !gemini.isConfigured -> "AI mode needs a Gemini API key. Using the offline agent."
            !s.micAllowed -> "AI mode needs the microphone. Using the offline agent."
            !connectivity.isOnlineNow() -> "No internet, so I'm using the offline agent."
            else -> null
        }
        if (reason != null) return fallBackToOffline(reason)
        state.update { it.copy(phase = AgentPhase.CONNECTING) }
        try {
            gemini.start(products.value) { event -> viewModelScope.launch { onGeminiEvent(event) } }
            state.update { it.copy(phase = AgentPhase.LIVE) }
            gemini.sendText("Greet the user in one short sentence and ask what they would like to order.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            fallBackToOffline("Couldn't reach Gemini (${e.message ?: e.javaClass.simpleName}). Using the offline agent.")
        }
    }

    private fun fallBackToOffline(reason: String) {
        state.update { it.copy(mode = AgentMode.OFFLINE, notice = reason, phase = AgentPhase.IDLE) }
        say(agent().greeting())
    }

    private fun onGeminiEvent(event: GeminiLiveOrderAgent.Event) {
        when (event) {
            is GeminiLiveOrderAgent.Event.Transcript -> state.update { s ->
                // Transcripts arrive in chunks: append to the last bubble if the same person is still talking.
                val last = s.messages.lastOrNull()
                val messages = if (last != null && last.fromUser == event.fromUser) {
                    s.messages.dropLast(1) + last.copy(text = (last.text + event.text).replace(Regex("\\s+"), " ").trim())
                } else {
                    s.messages + ChatMessage(event.fromUser, event.text.trim())
                }
                s.copy(messages = messages)
            }
            is GeminiLiveOrderAgent.Event.OrderPlaced -> viewModelScope.launch {
                kotlinx.coroutines.delay(3_500) // let Gemini finish saying "order placed"
                gemini.stop()
                _navigation.trySend(AgentNavigation.Tracking(event.localOrderId))
            }
            is GeminiLiveOrderAgent.Event.Disconnected -> {
                gemini.stop()
                state.update { it.copy(phase = AgentPhase.IDLE, notice = "Gemini disconnected (${event.reason}). Tap the mic to reconnect.") }
            }
            GeminiLiveOrderAgent.Event.NeedsAddress -> viewModelScope.launch {
                kotlinx.coroutines.delay(3_000)
                gemini.stop()
                _navigation.trySend(AgentNavigation.Checkout)
            }
        }
    }

    fun onLanguageChange(language: AgentLanguage) {
        if (language == state.value.language) return
        if (state.value.mode == AgentMode.GEMINI) { // Gemini follows whatever language you speak
            state.update { it.copy(language = language) }
            return
        }
        speech.stop()
        pending = null
        state.update { it.copy(language = language, partial = "") }
        say(agent().greeting())
    }

    /** Mic button: start listening, interrupt the agent, or stop listening. */
    fun onMicTap() {
        if (state.value.mode == AgentMode.GEMINI) {
            when (state.value.phase) {
                AgentPhase.LIVE -> gemini.interrupt() // stop Gemini talking, listen to the user
                AgentPhase.CONNECTING -> Unit
                else -> viewModelScope.launch { startGemini() } // reconnect if it dropped
            }
            return
        }
        when (state.value.phase) {
            AgentPhase.LISTENING -> {
                speech.stop()
                state.update { it.copy(phase = AgentPhase.IDLE, partial = "") }
            }
            else -> {
                speech.stop()
                listen()
            }
        }
    }

    /** "Type instead" box: same brain, no microphone. Handy in a noisy room. */
    fun onTyped(text: String) {
        if (text.isBlank()) return
        if (state.value.mode == AgentMode.GEMINI && state.value.phase == AgentPhase.LIVE) {
            state.update { it.copy(messages = it.messages + ChatMessage(true, text)) }
            viewModelScope.launch { gemini.sendText(text) }
            return
        }
        speech.stop()
        handleUtterance(text)
    }

    private fun onSpeechEvent(event: SpeechEvent) {
        when (event) {
            SpeechEvent.ListeningStarted -> state.update { it.copy(phase = AgentPhase.LISTENING) }
            is SpeechEvent.Partial -> state.update { it.copy(partial = event.text) }
            is SpeechEvent.Heard -> handleUtterance(event.text)
            is SpeechEvent.NotHeard -> {
                state.update { it.copy(partial = "") }
                if (event.fatal || missesInARow >= 2) {
                    // Don't nag forever: go quiet and let the user tap the mic.
                    missesInARow = 0
                    state.update { it.copy(phase = AgentPhase.IDLE) }
                } else {
                    missesInARow++
                    say(agent().didNotHear())
                }
            }
            SpeechEvent.SpeakingDone -> {
                val destination = navigateAfterSpeaking
                if (destination != null) {
                    navigateAfterSpeaking = null
                    state.update { it.copy(phase = AgentPhase.IDLE) }
                    _navigation.trySend(destination)
                } else if (state.value.phase == AgentPhase.SPEAKING) {
                    listen() // agent finished talking -> user's turn
                }
            }
        }
    }

    private fun handleUtterance(text: String) {
        missesInARow = 0
        state.update { it.copy(phase = AgentPhase.THINKING, partial = "", messages = it.messages + ChatMessage(true, text)) }
        viewModelScope.launch {
            val turn = agent().handle(text, cartLines.value, pending, lastAddress.value)
            turn.actions.forEach { action ->
                when (action) {
                    is CartAction.Add -> cartRepository.add(action.productId, action.quantity)
                    is CartAction.Remove -> repeat(action.quantity) { cartRepository.remove(action.productId) }
                }
            }
            pending = turn.pending
            navigateAfterSpeaking = when (turn.next) {
                AgentNext.OPEN_CART -> AgentNavigation.Cart
                AgentNext.OPEN_CHECKOUT -> AgentNavigation.Checkout
                AgentNext.CONTINUE -> null
            }
            // The user said "yes" to "Shall I place the order…?": place it for real (works offline too,
            // it's saved as PENDING_SYNC and WorkManager sends it), then open live tracking.
            turn.placeOrder?.let { order ->
                try {
                    val localId = orderRepository.placeOrder(order.address, order.paymentMethod)
                    navigateAfterSpeaking = AgentNavigation.Tracking(localId)
                } catch (e: IllegalStateException) {
                    say(if (state.value.language == AgentLanguage.HINDI) "कार्ट खाली है।" else "Your cart is empty, so there's nothing to order.")
                    return@launch
                }
            }
            say(turn.reply)
        }
    }

    private fun say(text: String) {
        state.update { it.copy(phase = AgentPhase.SPEAKING, messages = it.messages + ChatMessage(false, text)) }
        speech.speak(text, state.value.language.speechTag)
    }

    private fun listen() {
        val s = state.value
        if (!s.micAllowed || !s.recognitionAvailable) {
            state.update { it.copy(phase = AgentPhase.IDLE) } // typed mode only
            return
        }
        state.update { it.copy(phase = AgentPhase.LISTENING, partial = "") }
        speech.listen(s.language.speechTag)
    }

    private fun agent() = VoiceOrderAgent(products.value, state.value.language)

    override fun onCleared() {
        speech.release() // free the mic and the TTS engine
        // viewModelScope is already cancelled here, so close the Gemini session on its own short-lived scope.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { gemini.stop() }
    }
}
