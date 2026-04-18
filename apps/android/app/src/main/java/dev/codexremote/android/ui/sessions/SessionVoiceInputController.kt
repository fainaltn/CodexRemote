package dev.codexremote.android.ui.sessions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import dev.codexremote.android.R
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

internal data class SessionVoiceUiState(
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val partialText: String? = null,
    val levels: List<Float> = emptyList(),
    val elapsedMs: Long = 0L,
    val mode: SessionVoiceInputMode = SessionVoiceInputMode.Idle,
    val readiness: SessionVoiceInputReadiness = SessionVoiceInputReadiness.Unavailable,
)

internal enum class SessionVoiceInputMode {
    Idle,
    Local,
    External,
}

internal enum class SessionVoiceInputReadiness {
    LocalReady,
    ExternalFallbackReady,
    ExternalFallbackTentative,
    NeedsMicrophonePermission,
    Unavailable,
}

internal data class SessionVoiceInputCapabilities(
    val microphoneAvailable: Boolean,
    val microphonePermissionGranted: Boolean,
) {
    val readiness: SessionVoiceInputReadiness = when {
        microphoneAvailable && microphonePermissionGranted ->
            SessionVoiceInputReadiness.LocalReady
        microphoneAvailable ->
            SessionVoiceInputReadiness.NeedsMicrophonePermission
        else -> SessionVoiceInputReadiness.Unavailable
    }

    val preferredMode: SessionVoiceInputMode = when (readiness) {
        SessionVoiceInputReadiness.LocalReady -> SessionVoiceInputMode.Local
        SessionVoiceInputReadiness.NeedsMicrophonePermission,
        SessionVoiceInputReadiness.Unavailable,
        SessionVoiceInputReadiness.ExternalFallbackReady,
        SessionVoiceInputReadiness.ExternalFallbackTentative -> SessionVoiceInputMode.Idle
    }

    val canStartVoiceInput: Boolean = microphoneAvailable
    val requiresMicrophonePermission: Boolean = readiness == SessionVoiceInputReadiness.NeedsMicrophonePermission
}

internal fun resolveSessionVoiceInputCapabilities(
    localRecognizerAvailable: Boolean,
    microphonePermissionGranted: Boolean,
    externalRecognizerAvailable: Boolean,
): SessionVoiceInputCapabilities = SessionVoiceInputCapabilities(
    microphoneAvailable = localRecognizerAvailable || externalRecognizerAvailable,
    microphonePermissionGranted = microphonePermissionGranted,
)

internal fun mapSessionVoiceInputError(error: Int): SessionVoiceInputError = when (error) {
    else -> SessionVoiceInputError.Failed
}

private const val MIN_VOICE_RECORDING_MS = 2_000L
internal const val MAX_VOICE_RECORDING_MS = 90_000L

internal fun isVoiceRecordingLongEnough(elapsedMs: Long): Boolean =
    elapsedMs >= MIN_VOICE_RECORDING_MS

internal class SessionVoiceInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (SessionVoiceInputError) -> Unit,
    private val onVoiceNoteReady: suspend (RecordedVoiceInput) -> Unit,
) : RecognitionListener {
    private val packageManager: PackageManager = context.packageManager
    private val microphoneAvailable: Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    private val _uiState = MutableStateFlow(idleUiState())
    val uiState: StateFlow<SessionVoiceUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var mediaRecorder: MediaRecorder? = null
    private var activeOutputFile: File? = null

    fun capabilitySnapshot(): SessionVoiceInputCapabilities = resolveSessionVoiceInputCapabilities(
        localRecognizerAvailable = microphoneAvailable,
        microphonePermissionGranted = hasRecordAudioPermission(),
        externalRecognizerAvailable = false,
    )

    fun isAvailable(): Boolean = capabilitySnapshot().canStartVoiceInput

    fun usesExternalRecognizer(): Boolean = false

    fun needsMicrophonePermission(): Boolean = capabilitySnapshot().requiresMicrophonePermission

    fun hasLocalRecognizer(): Boolean = microphoneAvailable

    fun readiness(): SessionVoiceInputReadiness = capabilitySnapshot().readiness

    fun readinessLabel(context: Context = this.context): String = when (readiness()) {
        SessionVoiceInputReadiness.LocalReady ->
            context.getString(R.string.session_detail_voice_ready_local)
        SessionVoiceInputReadiness.NeedsMicrophonePermission ->
            context.getString(R.string.session_detail_voice_permission_required)
        else ->
            context.getString(R.string.session_detail_voice_unavailable)
    }

    fun buildRecognitionIntent(prompt: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (prompt.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
        }

    fun startExternal(prompt: String, allowTentative: Boolean = false) {
        onError(SessionVoiceInputError.Unavailable)
    }

    fun handleExternalResult(data: Intent?) = Unit

    fun start(prompt: String) {
        val capabilities = capabilitySnapshot()
        if (!capabilities.microphoneAvailable) {
            onError(SessionVoiceInputError.Unavailable)
            return
        }
        if (!capabilities.microphonePermissionGranted) {
            onError(SessionVoiceInputError.PermissionDenied)
            return
        }
        if (_uiState.value.recording || _uiState.value.transcribing) return

        runCatching {
            val outputFile = createOutputFile()
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(96_000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            activeOutputFile = outputFile
            mediaRecorder = recorder
            _uiState.value = SessionVoiceUiState(
                recording = true,
                mode = SessionVoiceInputMode.Local,
                readiness = capabilities.readiness,
            )
            startTimer()
        }.onFailure {
            cleanupRecorder(deleteFile = true)
            onError(SessionVoiceInputError.Failed)
        }
    }

    fun stop() {
        val recorder = mediaRecorder ?: return
        val outputFile = activeOutputFile ?: run {
            cleanupRecorder(deleteFile = true)
            onError(SessionVoiceInputError.Failed)
            return
        }
        val recordingElapsedMs = _uiState.value.elapsedMs

        val stopped = runCatching {
            recorder.stop()
        }.isSuccess
        cleanupRecorder(deleteFile = !stopped)

        if (!stopped || !outputFile.exists() || outputFile.length() == 0L) {
            outputFile.delete()
            _uiState.value = idleUiState()
            onError(SessionVoiceInputError.NoMatch)
            return
        }
        if (!isVoiceRecordingLongEnough(recordingElapsedMs)) {
            outputFile.delete()
            _uiState.value = idleUiState()
            onError(SessionVoiceInputError.TooShort)
            return
        }

        _uiState.value = _uiState.value.copy(
            recording = false,
            transcribing = true,
            mode = SessionVoiceInputMode.Local,
        )

        scope.launch(Dispatchers.Main.immediate) {
            val bytes = runCatching { outputFile.readBytes() }.getOrElse {
                outputFile.delete()
                _uiState.value = idleUiState()
                onError(SessionVoiceInputError.Failed)
                return@launch
            }

            val delivered = runCatching {
                onVoiceNoteReady(
                    RecordedVoiceInput(
                        fileName = outputFile.name,
                        mimeType = "audio/mp4",
                        bytes = bytes,
                        durationMs = recordingElapsedMs,
                    ),
                )
            }.isSuccess

            outputFile.delete()
            _uiState.value = idleUiState()
            if (!delivered) {
                onError(SessionVoiceInputError.Failed)
                return@launch
            }
        }
    }

    fun cancel() {
        cleanupRecorder(deleteFile = true)
        _uiState.value = idleUiState()
    }

    fun release() {
        cleanupRecorder(deleteFile = true)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) = Unit

    override fun onResults(results: Bundle?) = Unit

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun idleUiState(): SessionVoiceUiState = SessionVoiceUiState(
        readiness = capabilitySnapshot().readiness,
    )

    private fun createOutputFile(): File {
        val directory = File(context.cacheDir, "voice").apply { mkdirs() }
        return File.createTempFile("voice_", ".m4a", directory)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Main.immediate) {
            val startedAt = System.currentTimeMillis()
            while (_uiState.value.recording || _uiState.value.transcribing) {
                val amplitude = mediaRecorder?.maxAmplitude?.toFloat() ?: 0f
                val normalized = (amplitude / 32_767f).coerceIn(0.08f, 1f)
                val nextLevels = (_uiState.value.levels + normalized).takeLast(40)
                _uiState.value = _uiState.value.copy(
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    levels = nextLevels,
                )
                if (_uiState.value.recording && _uiState.value.elapsedMs >= MAX_VOICE_RECORDING_MS) {
                    stop()
                    return@launch
                }
                delay(100L)
            }
        }
    }

    private fun cleanupRecorder(deleteFile: Boolean) {
        timerJob?.cancel()
        timerJob = null
        runCatching {
            mediaRecorder?.reset()
        }
        mediaRecorder?.release()
        mediaRecorder = null
        if (deleteFile) {
            activeOutputFile?.delete()
        }
        activeOutputFile = null
    }
}

internal enum class SessionVoiceInputError {
    PermissionDenied,
    Unavailable,
    TooShort,
    NoMatch,
    Failed,
}
