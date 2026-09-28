package com.yshah.alfred.network

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticCaptureTest {
    @Test fun localIntentChoosesWireModeWithoutLosingUncertainDialogue() {
        assertEquals("task", automaticCaptureType("action"))
        assertEquals("note", automaticCaptureType("capture"))
        for (hint in listOf("question", "mixed", "uncertain", "unexpected", null)) {
            assertEquals("convo", automaticCaptureType(hint))
        }
    }
}
