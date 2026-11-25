package com.rohit.quickcart.ui.voiceagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.data.voice.SpeechEvent
import com.rohit.quickcart.data.voice.SpeechIo
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.domain.voiceagent.AgentLanguage
import com.rohit.quickcart.domain.voiceagent.CartAction
import com.rohit.quickcart.domain.voiceagent.PendingConfirmation
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
enum class AgentPhase { IDLE, SPEAKING, LISTENING, THINKING }

data class ChatMessage(val fromUser: Boolean, val text: String)

data class VoiceAgentUiState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    /** Live transcript while the user is still speaking. */
    val partial: String = "",
    val language: AgentLanguage = AgentLanguage.ENGLISH,
    val micAllowed: Boolean = false,
    val recognitionAvailable: Boolean = true,
    val cart: CartSummary = CartSummary(),
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
    private val speech: SpeechIo,
) : ViewModel() {

    private val products: StateFlow<List<Product>> =
        catalogRepository.observeProducts(null, "").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val cartLines: StateFlow<List<CartLine>> =
        cartRepository.observeCart().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val state = MutableStateFlow(VoiceAgentUiState(recognitionAvailable = speech.isRecognitionAvailable))
    val uiState: StateFlow<VoiceAgentUiState> = combine(state, cartLines) { s, cart -> s.copy(cart = cart.toCartSummary()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    private val _goToCart = Channel<Unit>(Channel.BUFFERED)
    val goToCart: Flow<Unit> = _goToCart.receiveAsFlow()

    private var pending: PendingConfirmation? = null
    private var finishAfterSpeaking = false
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
            say(agent().greeting())
        }
    }

    fun onLanguageChange(language: AgentLanguage) {
        if (language == state.value.language) return
        speech.stop()
        pending = null
        state.update { it.copy(language = language, partial = "") }
        say(agent().greeting())
    }

    /** Mic button: start listening, interrupt the agent, or stop listening. */
    fun onMicTap() {
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
                if (finishAfterSpeaking) {
                    finishAfterSpeaking = false
                    state.update { it.copy(phase = AgentPhase.IDLE) }
                    _goToCart.trySend(Unit)
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
            val turn = agent().handle(text, cartLines.value, pending)
            turn.actions.forEach { action ->
                when (action) {
                    is CartAction.Add -> cartRepository.add(action.productId, action.quantity)
                    is CartAction.Remove -> repeat(action.quantity) { cartRepository.remove(action.productId) }
                }
            }
            pending = turn.pending
            finishAfterSpeaking = turn.done
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
    }
}
