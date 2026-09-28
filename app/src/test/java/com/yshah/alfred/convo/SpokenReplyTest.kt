package com.yshah.alfred.convo

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenReplyTest {
    @Test fun speechKeepsMeaningWithoutReadingMarkupOrLinks() {
        assertEquals("Logged 18 push-ups. Journal", spokenReply("**Logged 18 push-ups.**\n[Journal](https://outline.example/doc/123)"))
        assertEquals("Completion is not confirmed. Check your journal.", spokenReply("Completion is not confirmed. Check your journal. https://outline.example/doc/123"))
    }
}
