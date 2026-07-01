package com.yshah.alfred.capture

import kotlinx.coroutines.flow.StateFlow

enum class CaptureMode { AUTO_STOP, MANUAL_STOP }

sealed class CaptureState {
    data object Idle : CaptureState()
    data object Listening : CaptureState()
    data class PartialTranscript(val text: String) : CaptureState()
    data class Finished(val finalText: String) : CaptureState()
    data class Error(val code: Int, val message: String) : CaptureState()
}

interface SpeechCaptureController {
    val state: StateFlow<CaptureState>

    /** AUTO_STOP (task mode) ends capture itself on a pause in speech; MANUAL_STOP (note mode)
     * only ends via [stop]. */
    fun start(mode: CaptureMode)
    fun stop()
    fun cancel()
}
