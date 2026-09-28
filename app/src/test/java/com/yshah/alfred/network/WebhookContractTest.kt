package com.yshah.alfred.network

import com.yshah.alfred.settings.AuthScheme
import com.yshah.alfred.settings.WebhookSettings
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WebhookContractTest {
    @Test fun outcomesDoNotConfuseDeliveryWithCompletion() {
        assertEquals(WebhookOutcome.ACCEPTED, parseResponseBody("").outcome)
        for (outcome in WebhookOutcome.entries) {
            assertEquals(outcome, parseResponseBody("""{"status":"${outcome.name.lowercase()}","message":"result"}""").outcome)
        }
        assertEquals(WebhookOutcome.FAILED, parseResponseBody("""{"success":false,"message":"denied"}""").outcome)
        assertEquals(WebhookOutcome.FAILED, parseResponseBody("""{"status":"completed","error":"denied"}""").outcome)
        val legacy = parseResponseBody("""[{"output":"Done"}]""")
        assertEquals("Done", legacy.responseText)
        assertEquals(WebhookOutcome.UNKNOWN, legacy.outcome)
        assertTrue(legacy.isLegacyResponse)
    }

    @Test fun ndjsonPreservesBusinessOutcome() {
        val body = """{"type":"begin"}
            |{"type":"item","content":"partial"}
            |{"type":"item","content":"{\"status\":\"needs_confirmation\",\"output\":\"Confirm?\"}"}
            |{"type":"end"}""".trimMargin()
        assertEquals(WebhookOutcome.NEEDS_CONFIRMATION, parseResponseBody(body).outcome)
        assertEquals("Confirm?", parseResponseBody(body).responseText)
        assertEquals(WebhookOutcome.FAILED, parseResponseBody("{\"type\":\"begin\"}\n{\"type\":\"error\",\"message\":\"denied\"}").outcome)
        assertEquals(WebhookOutcome.COMPLETED, parseResponseBody("""{"type":"item","content":{"status":"completed"}}""").outcome)
    }

    @Test fun plaintextIsPreservedAndOversizedBodiesAreRejected() {
        val text = "a".repeat(4000)
        assertEquals(text, parseResponseBody(text).message)
        assertEquals(text, text.toResponseBody().readBounded())
        assertThrows(IOException::class.java) { "a".repeat(MAX_RESPONSE_BYTES.toInt() + 1).toResponseBody().readBounded() }
    }

    @Test fun settingsValidateUrlAndHeadersWithoutLeakingSecret() {
        val valid = WebhookSettings("https://example.com/hook", AuthScheme.CUSTOM_HEADER, "X-Secret", "private")
        assertEquals(mapOf("X-Secret" to "private"), valid.authHeaders())
        for (url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com/#x", "")) {
            assertThrows(IllegalArgumentException::class.java) { valid.copy(webhookUrl = url).validate() }
        }
        for (header in listOf("X Bad", "X:Bad", "X\r\nBad", "Host", "Content-Length", "")) {
            assertThrows(IllegalArgumentException::class.java) { valid.copy(authHeaderName = header).validate() }
        }
        for (secret in listOf("", "private\r\nInjected: yes", "private\u0000")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { valid.copy(authSecret = secret).validate() }
            assertFalse(failure.message.orEmpty().contains("private"))
        }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(credentialUnavailable = true).validate() }
    }

    @Test fun clientsDisableRedirectsRetriesAndSetTotalTimeout() {
        val factory = WebhookClientFactory()
        assertEquals(300_000, factory.longRunningClient.callTimeoutMillis)
        assertEquals(120_000, factory.convoClient.callTimeoutMillis)
        for (client in listOf(factory.longRunningClient, factory.convoClient)) {
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
        }
    }

    @Test fun pingRequiresStructuredCapabilities() {
        assertTrue(validateCapabilityPing("""{"type":"pong","schemaVersion":1,"capabilities":["task","note","convo"]}""", 200) is ConnectionTestResult.Success)
        for (body in listOf("", "OK", "{}", """{"type":"pong","schemaVersion":1,"capabilities":["task"]}""")) {
            assertTrue(validateCapabilityPing(body, 200) is ConnectionTestResult.Failure)
        }
    }

    @Test fun legacyPingExplainsReachabilityWithoutClaimingVerificationOrBlockingValidSettings() {
        val result = validateCapabilityPing("""{"output":"Hello"}""", 200)
        assertTrue(result is ConnectionTestResult.Failure)
        val message = (result as ConnectionTestResult.Failure).message
        assertTrue(message.contains("HTTP 200 reached"))
        assertTrue(message.contains("capabilities are unverified"))
        assertTrue(message.contains("you can save"))
        WebhookSettings("https://example.com/legacy").validate()
    }

    @Test fun metadataKeepsCaptureIdentityAndRejectsInvalidInputs() {
        val metadata = WebhookRequestMetadata(1234L, "UTC", "watch", "request-1", "conversation-1")
        metadata.validate()
        assertEquals(1234L, metadata.capturedAt)
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(requestId = "bad/id").validate() }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(source = "unknown").validate() }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(schemaVersion = 2).validate() }
    }
}
