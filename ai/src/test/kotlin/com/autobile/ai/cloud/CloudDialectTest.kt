package com.autobile.ai.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Speaking each service's API correctly.
 *
 * The differences are small and unforgiving: a header name, where the system prompt
 * lives, what the reply is nested inside. Getting one wrong fails at run time on a
 * user's phone with an HTTP error and no indication which field was at fault, so each is
 * pinned here instead.
 */
class CloudDialectTest {

    private val prompt = "Which element sends the message?"
    private val system = "You are operating a phone."

    @Test
    fun `each service is asked at its own address`() {
        assertThat(CloudDialect.GEMINI.requestUrl("https://host/v1beta", "gemini-2.5-flash"))
            .isEqualTo("https://host/v1beta/models/gemini-2.5-flash:generateContent")
        assertThat(CloudDialect.OPENAI.requestUrl("https://api.openai.com/v1", "gpt-5"))
            .isEqualTo("https://api.openai.com/v1/chat/completions")
        assertThat(CloudDialect.ANTHROPIC.requestUrl("https://api.anthropic.com/v1/", "claude-sonnet-5"))
            .isEqualTo("https://api.anthropic.com/v1/messages")
    }

    @Test
    fun `each service is given the credential the way it expects`() {
        assertThat(CloudDialect.GEMINI.authHeaders(CloudCredential.Key("k"))).containsEntry("x-goog-api-key", "k")
        assertThat(CloudDialect.OPENAI.authHeaders(CloudCredential.Key("k"))).containsEntry("Authorization", "Bearer k")
        assertThat(CloudDialect.ANTHROPIC.authHeaders(CloudCredential.Key("k"))).containsEntry("x-api-key", "k")
        // Anthropic refuses a request that does not name the API version.
        assertThat(CloudDialect.ANTHROPIC.authHeaders(CloudCredential.Key("k"))).containsKey("anthropic-version")
    }

    @Test
    fun `no credential means no credential header`() {
        CloudDialect.entries.forEach { assertThat(it.authHeaders(CloudCredential.None)).isEmpty() }
    }

    @Test
    fun `the system prompt goes where each service looks for it`() {
        val gemini = CloudDialect.GEMINI.requestBody("m", system, prompt, null, 0.2f, 256, false)
        val openAi = CloudDialect.OPENAI.requestBody("m", system, prompt, null, 0.2f, 256, false)
        val claude = CloudDialect.ANTHROPIC.requestBody("m", system, prompt, null, 0.2f, 256, false)

        assertThat(gemini).contains("systemInstruction")
        assertThat(openAi).contains("\"role\":\"system\"")
        assertThat(claude).contains("\"system\"")
    }

    @Test
    fun `the model is named in the body where the address does not carry it`() {
        // Gemini puts the model in the URL; the other two put it in the payload.
        assertThat(CloudDialect.OPENAI.requestBody("gpt-5", null, prompt, null, 0f, 16, false)).contains("gpt-5")
        assertThat(CloudDialect.ANTHROPIC.requestBody("claude-sonnet-5", null, prompt, null, 0f, 16, false))
            .contains("claude-sonnet-5")
    }

    @Test
    fun `a screenshot is attached in each service's own shape`() {
        val encoded = "QUJD"
        assertThat(CloudDialect.GEMINI.requestBody("m", null, prompt, encoded, 0f, 16, false))
            .contains("inline_data")
        assertThat(CloudDialect.OPENAI.requestBody("m", null, prompt, encoded, 0f, 16, false))
            .contains("data:image/jpeg;base64,$encoded")
        assertThat(CloudDialect.ANTHROPIC.requestBody("m", null, prompt, encoded, 0f, 16, false))
            .contains("\"type\":\"base64\"")
    }

    @Test
    fun `a reply is read out of whatever each service wrapped it in`() {
        assertThat(
            CloudDialect.GEMINI.extractText(
                """{"candidates":[{"content":{"parts":[{"text":"the send button"}]}}]}""",
            ),
        ).isEqualTo("the send button")

        assertThat(
            CloudDialect.OPENAI.extractText(
                """{"choices":[{"message":{"role":"assistant","content":"the send button"}}]}""",
            ),
        ).isEqualTo("the send button")

        assertThat(
            CloudDialect.ANTHROPIC.extractText(
                """{"content":[{"type":"text","text":"the send button"}]}""",
            ),
        ).isEqualTo("the send button")
    }

    @Test
    fun `an error payload is not mistaken for an answer`() {
        val error = """{"error":{"message":"invalid api key","type":"authentication_error"}}"""
        CloudDialect.entries.forEach { assertThat(it.extractText(error)).isNull() }
    }

    @Test
    fun `nonsense is not mistaken for an answer`() {
        CloudDialect.entries.forEach {
            assertThat(it.extractText("not json at all")).isNull()
            assertThat(it.extractText("")).isNull()
        }
    }

    @Test
    fun `every service a user can pick knows how to reach itself`() {
        CloudService.entries.filter { it != CloudService.CUSTOM }.forEach { service ->
            assertThat(service.endpoint).startsWith("https://")
            assertThat(service.lightModel).isNotEmpty()
            assertThat(service.advancedModel).isNotEmpty()
        }
    }

    @Test
    fun `a service that wants a key says where to get one`() {
        CloudService.entries
            .filter { it != CloudService.CUSTOM && it.authMethod == CloudAuthMethod.API_KEY }
            .forEach { assertThat(it.credentialUrl).startsWith("https://") }
    }

    @Test
    fun `a service that is signed in to asks for no key page`() {
        // Offering "get a key" beside a sign-in button would send someone to buy API
        // credit they do not need, on top of the subscription they already pay for.
        CloudService.entries
            .filter { it.authMethod == CloudAuthMethod.SIGN_IN }
            .forEach { assertThat(it.credentialUrl).isEmpty() }
    }

    @Test
    fun `a subscription request is addressed and signed the way that surface expects`() {
        assertThat(CloudDialect.CHATGPT.requestUrl("https://chatgpt.com/backend-api/codex", "gpt-5.4"))
            .isEqualTo("https://chatgpt.com/backend-api/codex/responses")

        val headers = CloudDialect.CHATGPT.authHeaders(CloudCredential.Session("tok", "acct"))
        assertThat(headers).containsEntry("Authorization", "Bearer tok")
        // Without the account the surface cannot tell which subscription to bill.
        assertThat(headers).containsEntry("ChatGPT-Account-ID", "acct")
        // The reply arrives as events, and asking for anything else gets a refusal.
        assertThat(headers).containsEntry("Accept", "text/event-stream")
    }

    @Test
    fun `a subscription request omits the controls that surface rejects`() {
        val body = CloudDialect.CHATGPT.requestBody("gpt-5.4", system, prompt, null, 0.2f, 256, true)

        assertThat(body).contains("\"instructions\":\"$system\"")
        assertThat(body).contains("input_text")
        assertThat(body).contains("\"stream\":true")
        // Sampling and length controls make this surface answer HTTP 400 with no
        // indication which field was at fault.
        assertThat(body).doesNotContain("temperature")
        assertThat(body).doesNotContain("max_output_tokens")
        assertThat(body).doesNotContain("max_completion_tokens")
    }

    @Test
    fun `a picture rides along as an input image`() {
        val body = CloudDialect.CHATGPT.requestBody("gpt-5.4", null, prompt, "AAAA", 0.2f, 256, false)
        assertThat(body).contains("input_image")
        assertThat(body).contains("data:image/jpeg;base64,AAAA")
    }

    @Test
    fun `an answer is reassembled from the deltas it arrived in`() {
        val stream = """
            data: {"type":"response.created"}

            data: {"type":"response.output_text.delta","delta":"Open "}

            data: {"type":"response.output_text.delta","delta":"the note"}

            data: {"type":"response.completed"}

            data: [DONE]
        """.trimIndent()
        assertThat(CloudDialect.CHATGPT.extractText(stream)).isEqualTo("Open the note")
    }

    @Test
    fun `an answer that arrived whole is still read`() {
        val stream = "data: {\"type\":\"response.completed\",\"response\":{\"output\":" +
            "[{\"content\":[{\"type\":\"output_text\",\"text\":\"Tap send\"}]}]}}"
        assertThat(CloudDialect.CHATGPT.extractText(stream)).isEqualTo("Tap send")
    }

    @Test
    fun `a stream carrying no answer reports none rather than an empty one`() {
        assertThat(CloudDialect.CHATGPT.extractText("data: {\"type\":\"response.created\"}")).isNull()
        assertThat(CloudDialect.CHATGPT.extractText("")).isNull()
        assertThat(CloudDialect.CHATGPT.extractText("not events at all")).isNull()
    }
}
