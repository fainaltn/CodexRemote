package dev.codexremote.android.ui.sessions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

internal data class SessionVoiceUiState(
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val partialText: String? = null,
    val levels: List<Float> = emptyList(),
    val elapsedMs: Long = 0L,
)

internal class SessionVoiceInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTranscript: (String) -> Unit,
    private val onError: (SessionVoiceInputError) -> Unit,
) : RecognitionListener {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
        } else {
            null
        }

    private val _uiState = MutableStateFlow(SessionVoiceUiState())
    val uiState: StateFlow<SessionVoiceUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    fun isAvailable(): Boolean = recognizer != null

    fun start(prompt: String) {
        val recognizer = recognizer ?: run {
            onError(SessionVoiceInputError.Unavailable)
            return
        }
        if (_uiState.value.recording || _uiState.value.transcribing) return

        _uiState.value = SessionVoiceUiState(recording = true)
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Main.immediate) {
            val startedAt = System.currentTimeMillis()
            while (_uiState.value.recording || _uiState.value.transcribing) {
                _uiState.value = _uiState.value.copy(
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
                delay(100L)
            }
        }

        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            },
        )
    }

    fun stop() {
        recognizer?.stopListening()
        _uiState.value = _uiState.value.copy(recording = false, transcribing = true)
    }

    fun cancel() {
        recognizer?.cancel()
        timerJob?.cancel()
        _uiState.value = SessionVoiceUiState()
    }

    fun release() {
        timerJob?.cancel()
        recognizer?.destroy()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) {
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        val nextLevels = (_uiState.value.levels + normalized).takeLast(40)
        _uiState.value = _uiState.value.copy(levels = nextLevels)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        _uiState.value = _uiState.value.copy(recording = false, transcribing = true)
    }

    override fun onError(error: Int) {
        timerJob?.cancel()
        _uiState.value = SessionVoiceUiState()
        val mapped = when (error) {
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> SessionVoiceInputError.Failed
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SessionVoiceInputError.PermissionDenied
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SessionVoiceInputError.NoMatch
            else -> SessionVoiceInputError.Failed
        }
        onError(mapped)
    }

    override fun onResults(results: Bundle?) {
        timerJob?.cancel()
        val transcript = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        _uiState.value = SessionVoiceUiState()
        if (transcript.isBlank()) {
            onError(SessionVoiceInputError.NoMatch)
        } else {
            onTranscript(transcript)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
        _uiState.value = _uiState.value.copy(partialText = partial)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

internal enum class SessionVoiceInputError {
    PermissionDenied,
    Unavailable,
    NoMatch,
    Failed,
}
