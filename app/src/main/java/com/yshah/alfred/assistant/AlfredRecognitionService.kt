package com.yshah.alfred.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required for the framework to consider Alfred's VoiceInteractionService registration valid —
 * confirmed on-device via `adb shell dumpsys voiceinteraction` reporting "NOT VALID: No
 * recognitionService specified" when this was omitted, resolving the open question from the
 * design phase. Alfred's own SpeechCaptureController (added later) calls SpeechRecognizer as a
 * *client* rather than providing recognition itself, so nothing routes through this stub —
 * it exists purely to satisfy the manifest/role validity check.
 */
class AlfredRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
