package com.yshah.alfred.network

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentClassifierDeviceTest {
    @Test fun bundledModelClassifiesVoiceTranscripts() {
        val classifier = IntentClassifier(InstrumentationRegistry.getInstrumentation().targetContext)
        val cases = listOf(
            "What is the weather today?" to "question",
            "Book a meeting tomorrow at noon" to "action",
            "Log my workout: three sets of ten squats" to "capture",
            "Save this recipe and tell me how much protein it has" to "mixed",
            "What is the capital of France?" to "question",
        )
        for ((text, expected) in cases) {
            val start = System.nanoTime()
            val result = classifier.classify(text, false)
            Log.i("AlfredClassifierTest", "$expected result=$result elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
            assertEquals(expected, result)
        }
        assertEquals("uncertain", classifier.classify("yes", true))
        assertEquals("uncertain", classifier.classify("word ".repeat(500), false))
    }
}
