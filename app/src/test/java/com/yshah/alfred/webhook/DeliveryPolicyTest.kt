package com.yshah.alfred.webhook

import com.yshah.alfred.data.DeliveryEntity
import com.yshah.alfred.network.WebhookResult
import com.yshah.alfred.network.WebhookOutcome
import com.yshah.alfred.network.WebhookResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DeliveryPolicyTest {
    @Test fun duplicateIdentityRequiresTheEntireOriginalPayload() {
        val capture = DeliveryEntity("same-id", "Buy milk", "task", 1234, "UTC", "watch")
        requireSameCapture(capture.copy(status = "completed"), capture)
        listOf(capture.copy(text = "Different"), capture.copy(type = "note"),
            capture.copy(source = "phone"), capture.copy(capturedAt = 1235),
            capture.copy(timeZone = "Asia/Kolkata")
        ).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { requireSameCapture(capture, changed) }
        }
    }

    @Test fun onlyExplicitCompletionMeansWatchSuccess() {
        val expected = mapOf(WebhookOutcome.COMPLETED to "completed", WebhookOutcome.ACCEPTED to "accepted",
            WebhookOutcome.FAILED to "failed", WebhookOutcome.NEEDS_CONFIRMATION to "needs_confirmation",
            WebhookOutcome.UNKNOWN to "unknown")
        expected.forEach { (outcome, status) ->
            val result = deliveryOutcome(WebhookResult.Success(WebhookResponseBody(outcome = outcome), 202))
            assertEquals(status, result.status)
            assertEquals(202, result.httpCode)
            assertEquals(outcome == WebhookOutcome.COMPLETED, watchStatus(status) == "success")
        }
        val legacy = deliveryOutcome(WebhookResult.Success(WebhookResponseBody(isLegacyResponse = true)))
        assertEquals("delivered", legacy.status)
        assertEquals("uncertain", watchStatus(legacy.status))
        assertEquals("uncertain", watchStatus("success"))
    }

    @Test fun ambiguousFailuresNeverBecomePending() {
        assertEquals("uncertain", deliveryOutcome(WebhookResult.Timeout(100)).status)
        assertEquals("uncertain", deliveryOutcome(WebhookResult.NetworkError(IOException())).status)
        assertEquals(503, deliveryOutcome(WebhookResult.HttpError(503, null)).httpCode)
    }

    @Test fun rejectsMalformedCaptures() {
        val capture = DeliveryEntity("valid-id", "Buy milk", "task", 1, "UTC", "watch")
        validateDelivery(capture)
        listOf(capture.copy(requestId = "../bad"), capture.copy(type = "convo"),
            capture.copy(text = " "), capture.copy(capturedAt = 0), capture.copy(source = "unknown")
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { validateDelivery(invalid) } }
    }
}
