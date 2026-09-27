package com.yshah.alfred.wear

import org.junit.Assert.*
import org.junit.Test

class WatchCaptureTimeTest {
    @Test fun legacyPreservesTimestampWithDeterministicUtc() {
        assertEquals(1234L to "UTC", watchCaptureTime(null, 1234, null))
        assertEquals(5678L to "Asia/Kolkata", watchCaptureTime(5678, 1234, "Asia/Kolkata"))
    }

    @Test fun malformedMetadataIsNotSilentlyReplaced() {
        assertThrows(IllegalArgumentException::class.java) { watchCaptureTime(null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { watchCaptureTime(0, 1234, "UTC") }
        assertThrows(IllegalArgumentException::class.java) { watchCaptureTime(null, 1234, "") }
        assertThrows(IllegalStateException::class.java) { watchCaptureTime(1234, null, null) }
    }
}
