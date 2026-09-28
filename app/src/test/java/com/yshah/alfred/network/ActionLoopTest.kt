package com.yshah.alfred.network

import com.yshah.alfred.data.InteractionEntity
import com.yshah.alfred.webhook.deliveryOutcome
import com.yshah.alfred.webhook.followUp
import com.yshah.alfred.webhook.requestMetadata
import com.yshah.alfred.webhook.requireSameCapture
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ActionLoopTest {
    @Test fun optionalMetadataDoesNotHideResponseAndClarificationReachesNotification() {
        for (body in listOf(
            """{"status":"failed","error":"Calendar denied","receipt":null}""",
            """{"status":"failed","error":"Calendar denied","clarification":{}}""",
            """{"status":"failed","error":"Calendar denied","receipt":{"action":"attempted"}}""",
        )) {
            val parsed = parseResponseBody(body)
            assertEquals(WebhookOutcome.FAILED, parsed.outcome)
            assertEquals(body, parsed.message)
        }
        val parsed = parseResponseBody(response)
        assertEquals("Which time?", parsed.message)
        assertTrue(deliveryOutcome(WebhookResult.Success(parsed)).message!!.contains("Which time?"))
        assertEquals("Explicit explanation", parseResponseBody(
            """{"responseText":"Explicit explanation","receipt":{"action":"created"}}""").responseText)
    }

    private val response = """{"status":"needs_confirmation","conversationId":"thread-1","receipt":{"action":"created","externalId":"event/1","url":"https://example.com/event/1"},"clarification":{"token":"opaque+/=","question":"Which time?"}}"""

    @Test fun metadataSurvivesStreamingStorageAndFollowUpCorrelation() {
        val parsed = parseResponseBody("{\"type\":\"begin\"}\n{\"type\":\"item\",\"content\":$response}\n{\"type\":\"end\"}")
        val stored = deliveryOutcome(WebhookResult.Success(parsed)).metadata
        assertEquals(parsed.receipt, responseMetadata(stored).receipt)
        assertEquals(parsed.clarification, responseMetadata(stored).clarification)
        val original = InteractionEntity("original", "task", "Create event", 1234, "needs_confirmation", null, responseMetadata = stored)
        val reply = followUp(original, "  At noon  ")
        assertNotEquals(original.sessionId, reply.requestId)
        assertNotEquals(reply.requestId, followUp(original, "At noon").requestId)
        assertEquals("original", reply.inReplyTo)
        assertEquals("thread-1", reply.conversationId)
        assertEquals("opaque+/=", reply.contextToken)
        assertEquals("At noon", reply.text)
        assertEquals(reply.requestId, reply.requestMetadata().requestId)
        reply.requestMetadata().validate()
        requireSameCapture(reply, reply.copy(status = "uncertain"))
        assertThrows(IllegalArgumentException::class.java) { requireSameCapture(reply, reply.copy(contextToken = "other")) }
        val payload = WebhookJsonPayload("task", reply.text, "2026-09-27T12:00:00Z", reply.requestId,
            requestId = reply.requestId, conversationId = reply.conversationId, inReplyTo = reply.inReplyTo, contextToken = reply.contextToken)
        // Match the network serializer: dynamic capture-time defaults must be sent explicitly.
        val wire = Json { encodeDefaults = true }.encodeToString(payload)
        assertTrue(wire.contains("\"inReplyTo\":\"original\""))
        assertEquals(payload, Json.decodeFromString<WebhookJsonPayload>(wire))
    }

    @Test fun malformedOptionalMetadataDoesNotBreakAcceptedOrLegacyResponses() {
        for (token in listOf("", "x".repeat(2049))) {
            val parsed = parseResponseBody("""{"status":"accepted","clarification":{"token":"$token","question":"When?"}}""")
            assertEquals(WebhookOutcome.ACCEPTED, parsed.outcome)
            assertNull(parsed.clarification)
        }
        assertNull(parseResponseBody("""{"clarification":{"token":42,"question":"When?"}}""").clarification)
        assertNull(parseResponseBody("""{"receipt":{"action":[]},"conversationId":"bad/id"}""").receipt)
        assertNull(parseResponseBody("""{"conversationId":"bad/id"}""").conversationId)
        assertTrue(parseResponseBody("""{"output":"Done","receipt":false}""").isLegacyResponse)
        assertEquals(WebhookOutcome.ACCEPTED, parseResponseBody("").outcome)
        assertThrows(IllegalArgumentException::class.java) { WebhookRequestMetadata(contextToken = "orphan").validate() }
        assertThrows(IllegalArgumentException::class.java) { WebhookRequestMetadata(requestId = "same", inReplyTo = "same").validate() }
    }

    @Test fun receiptLinksRejectUnsafeSchemesCredentialsAndMalformedUrls() {
        for (url in listOf("http://example.com", "javascript:alert(1)", "intent://example.com", "https://user:pass@example.com", "https:///path", "https://example.com\\@evil.com", "https://example.com/\n", "https://example.com:99999", "https://example.com/" + "x".repeat(2048))) {
            assertNull(url, safeReceiptUrl(url))
        }
        assertEquals("https://example.com/event?q=1#details", safeReceiptUrl("https://example.com/event?q=1#details"))
        val parsed = parseResponseBody("""{"receipt":{"action":"created","externalId":"1","url":"javascript:alert(1)"}}""")
        assertEquals("1", parsed.receipt?.externalId)
        assertNull(parsed.receipt?.url)
    }
}
