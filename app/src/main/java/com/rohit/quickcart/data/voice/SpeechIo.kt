package com.rohit.quickcart.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale
import javax.inject.Inject

/** What the microphone and speaker report back. */
sealed interface SpeechEvent {
    data object ListeningStarted : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Heard(val text: String) : SpeechEvent
    /** Nothing understood (silence, noise) or a recognizer error. */
    data class NotHeard(val fatal: Boolean, val reason: String) : SpeechEvent
    data object SpeakingDone : SpeechEvent
}

/**
 * The agent's ears and mouth, behind an interface so the ViewModel can be tested with a fake
 * and the engine can be swapped (e.g. a streaming cloud STT or a custom TTS voice).
 */
interface SpeechIo {
    val events: SharedFlow<SpeechEvent>
    val isRecognitionAvailable: Boolean
    fun speak(text: String, languageTag: String)
    fun listen(languageTag: String)
    fun stop()
    fun release()
}

/**
 * Android's built-in engines: SpeechRecognizer (speech-to-text) and TextToSpeech.
 * Both must be called on the main thread. The ViewModel does that, because viewModelScope uses Main.
 * Not a singleton: each voice-agent screen gets its own instance and releases it in onCleared().
 */
class AndroidSpeechIo @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechIo {

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SpeechEvent> = _events

    override val isRecognitionAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, String>? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        pendingSpeech?.let { (text, lang) -> pendingSpeech = null; speak(text, lang) }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                _events.tryEmit(SpeechEvent.SpeakingDone) // turn-taking: agent finished talking -> start listening
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _events.tryEmit(SpeechEvent.SpeakingDone)
            }
        })
    }

    override fun speak(text: String, languageTag: String) {
        if (!ttsReady) {
            pendingSpeech = text to languageTag // TTS engine still starting up
            return
        }
        tts.language = Locale.forLanguageTag(languageTag)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent-${System.nanoTime()}")
    }

    override fun listen(languageTag: String) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // live transcript while the user speaks
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
    }

    override fun stop() {
        recognizer?.cancel()
        tts.stop()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _events.tryEmit(SpeechEvent.ListeningStarted)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstResult()?.let { _events.tryEmit(SpeechEvent.Partial(it)) }
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstResult()
            _events.tryEmit(if (text.isNullOrBlank()) SpeechEvent.NotHeard(false, "empty") else SpeechEvent.Heard(text))
        }

        override fun onError(error: Int) {
            val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            _events.tryEmit(SpeechEvent.NotHeard(fatal, "error $error"))
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
