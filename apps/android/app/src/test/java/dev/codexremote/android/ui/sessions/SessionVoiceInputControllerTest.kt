package dev.codexremote.android.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVoiceInputControllerTest {

    @Test
    fun `voice input is ready when microphone and permission are both available`() {
        val capabilities = resolveSessionVoiceInputCapabilities(
            localRecognizerAvailable = true,
            microphonePermissionGranted = true,
            externalRecognizerAvailable = false,
        )

        assertEquals(SessionVoiceInputReadiness.LocalReady, capabilities.readiness)
        assertEquals(SessionVoiceInputMode.Local, capabilities.preferredMode)
        assertTrue(capabilities.canStartVoiceInput)
        assertFalse(capabilities.requiresMicrophonePermission)
    }

    @Test
    fun `voice input requests microphone permission when mic exists but permission is missing`() {
        val capabilities = resolveSessionVoiceInputCapabilities(
            localRecognizerAvailable = true,
            microphonePermissionGranted = false,
            externalRecognizerAvailable = false,
        )

        assertEquals(SessionVoiceInputReadiness.NeedsMicrophonePermission, capabilities.readiness)
        assertEquals(SessionVoiceInputMode.Idle, capabilities.preferredMode)
        assertTrue(capabilities.canStartVoiceInput)
        assertTrue(capabilities.requiresMicrophonePermission)
    }

    @Test
    fun `voice input is unavailable when device has no microphone path`() {
        val capabilities = resolveSessionVoiceInputCapabilities(
            localRecognizerAvailable = false,
            microphonePermissionGranted = false,
            externalRecognizerAvailable = false,
        )

        assertEquals(SessionVoiceInputReadiness.Unavailable, capabilities.readiness)
        assertEquals(SessionVoiceInputMode.Idle, capabilities.preferredMode)
        assertFalse(capabilities.canStartVoiceInput)
    }

    @Test
    fun `voice note shorter than two seconds is invalid`() {
        assertFalse(isVoiceRecordingLongEnough(1_999L))
        assertTrue(isVoiceRecordingLongEnough(2_000L))
    }

    @Test
    fun `voice note length limit stays at ninety seconds`() {
        assertEquals(90_000L, MAX_VOICE_RECORDING_MS)
    }
}
