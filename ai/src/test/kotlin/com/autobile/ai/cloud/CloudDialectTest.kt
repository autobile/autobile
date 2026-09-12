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
        assertThat(CloudDialect.GEMINI.authHeaders("k")).containsEntry("x-goog-api-key", "k")
        assertThat(CloudDialect.OPENAI.authHeaders("k")).containsEntry("Authorization", "Bearer k")
        assertThat(CloudDialect.ANTHROPIC.authHeaders("k")).containsEntry("x-api-key", "k")
        // Anthropic refuses a request that does not name the API version.
        assertThat(CloudDialect.ANTHROPIC.authHeaders("k")).containsKey("anthropic-version")
    }

    @Test
    fun `no credential means no credential header`() {
        CloudDialect.entries.forEach { assertThat(it.authHeaders("")).isEmpty() }
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
            assertThat(service.credentialUrl).startsWith("https://")
        }
    }
}
