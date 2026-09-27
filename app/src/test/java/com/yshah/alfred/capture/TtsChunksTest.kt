package com.yshah.alfred.capture

import org.junit.Assert.*
import org.junit.Test

class TtsChunksTest {
    @Test fun longResponsesPreserveAllTextAndSurrogatePairs() {
        val text = "abc\uD83D\uDE00def".repeat(1000)
        val chunks = speechChunks(text, 4)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertTrue(speechChunks("", 4).isEmpty())
    }
}
