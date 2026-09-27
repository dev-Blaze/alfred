package com.yshah.alfred.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingWebhookRequestsTest {
    @Test
    fun newerCompletionWaitsForOlderWorkAndStopsWithLatestId() {
        val requests = PendingWebhookRequests()
        requests.started(1)
        requests.started(2)
        assertNull(requests.finished()) // Request 2 finishes first.
        assertEquals(2, requests.finished()) // Request 1 finishes last.
    }

    @Test
    fun newArrivalWhileOlderWorkRemainsExtendsLifetime() {
        val requests = PendingWebhookRequests()
        requests.started(1)
        requests.started(2)
        assertNull(requests.finished())
        requests.started(3)
        assertNull(requests.finished())
        assertEquals(3, requests.finished())
        requests.started(4)
        assertEquals(4, requests.finished())
    }
}
