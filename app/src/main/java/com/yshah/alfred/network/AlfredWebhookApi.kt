package com.yshah.alfred.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Returns raw ResponseBody rather than a typed WebhookResponseBody — n8n webhooks commonly
 * respond with an empty body (no "Respond to Webhook" node configured) or plain text, and a
 * typed converter would throw on anything that isn't well-formed JSON. WebhookClient parses the
 * body leniently instead, treating an empty/unparseable body as still a success if the HTTP
 * status was 2xx (confirmed against a real n8n test webhook that returns an empty body).
 */
interface AlfredWebhookApi {
    @POST
    suspend fun sendJson(@Url url: String, @Body payload: WebhookJsonPayload): Response<ResponseBody>
}
