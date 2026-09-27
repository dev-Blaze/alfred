package com.yshah.alfred.capture

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechRecognizerCaptureControllerTest {
    @Test
    fun manualStopDuringSilentSessionPreservesEarlierNote() {
        for (error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            assertEquals(
                CaptureState.Finished("Earlier note"),
                recoveredNoteAtCutoff(CaptureMode.MANUAL_STOP, true, error, "Earlier note"),
            )
            assertNull(recoveredNoteAtCutoff(CaptureMode.MANUAL_STOP, true, error, " \n"))
            assertNull(recoveredNoteAtCutoff(CaptureMode.MANUAL_STOP, false, error, "Earlier note"))
            assertNull(recoveredNoteAtCutoff(CaptureMode.AUTO_STOP, true, error, "Earlier note"))
        }
    }

    @Test
    fun genuineErrorsAreNotConvertedToSuccessfulNotes() {
        for (error in listOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
        )) {
            assertNull(recoveredNoteAtCutoff(CaptureMode.MANUAL_STOP, true, error, "Earlier note"))
        }
    }
}
