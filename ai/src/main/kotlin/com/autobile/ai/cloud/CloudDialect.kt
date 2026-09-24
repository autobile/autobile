package com.autobile.ai.cloud

import com.autobile.ai.cloud.oauth.ChatGptSignIn
import com.autobile.core.common.AutobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * How one vendor's API differs from another's.
 *
 * The differences are entirely mechanical — a header name, where the system prompt goes,
 * what the reply is nested inside — and confining them here keeps the rest of the cloud
 * client, its error handling, its privacy gates and its retry behaviour identical
 * whichever service a user chooses. A provider added later touches this file and nothing
 * else.
 */
enum class CloudDialect {
    /** Google's `generateContent`. */
    GEMINI,

    /** OpenAI's chat completions, which several other services also implement. */
    OPENAI,

    /** Anthropic's messages API. */
    ANTHROPIC,

    /**
     * OpenAI's responses API as reached by a signed-in ChatGPT subscription.
     *
     * Separate from [OPENAI] because the subscription surface differs in three ways
     * that matter: the request is the responses shape rather than chat completions, it
     * only answers as a stream, and it needs the account the session belongs to named
     * alongside the token.
     */
    CHATGPT;

    fun requestUrl(endpoint: String, model: String): String {
        val base = endpoint.trimEnd('/')
        return when (this) {
            GEMINI -> "$base/models/$model:generateContent"
            OPENAI -> "$base/chat/completions"
            ANTHROPIC -> "$base/messages"
            CHATGPT -> "$base/responses"
        }
    }

    /** Headers carrying the credential, and any version the service insists on. */
    fun authHeaders(credential: CloudCredential): Map<String, String> = when (credential) {
        is CloudCredential.Key -> when (this) {
            GEMINI -> mapOf("x-goog-api-key" to credential.value)
            OPENAI, CHATGPT -> mapOf("Authorization" to "Bearer ${credential.value}")
            ANTHROPIC -> mapOf("x-api-key" to credential.value, "anthropic-version" to ANTHROPIC_VERSION)
        }

        is CloudCredential.Session -> buildMap {
            put("Authorization", "Bearer ${credential.accessToken}")
            // Without the account the surface cannot tell which subscription to bill.
            if (credential.accountId.isNotBlank()) put("chatgpt-account-id", credential.accountId)
            // Autobile names itself, rather than borrowing another client's name.
            put("originator", ChatGptSignIn.ORIGINATOR)
            put("User-Agent", USER_AGENT)
            // The surface answers only as a stream, and only when asked on this beta.
            put("OpenAI-Beta", "responses=experimental")
            put("Accept", "text/event-stream")
            // Cache affinity is derived from these, so one value serves both.
            credential.requestId.takeIf { it.isNotBlank() }?.let {
                put("session_id", it)
                put("x-client-request-id", it)
            }
        }

        CloudCredential.None -> emptyMap()
    }

    /** Headers for the subscription model catalog, which is JSON rather than SSE. */
    fun modelCatalogHeaders(credential: CloudCredential): Map<String, String> =
        if (this != CHATGPT) emptyMap() else authHeaders(credential)
            .filterKeys { it !in setOf("Accept", "OpenAI-Beta", "session_id", "x-client-request-id") }
            .plus("Accept" to "application/json")

    fun requestBody(
        model: String,
        systemInstruction: String?,
        prompt: String,
        imageBase64: String?,
        temperature: Float,
        maxOutputTokens: Int,
        forceJson: Boolean,
        reasoningEffort: String? = null,
    ): String = when (this) {
        GEMINI -> gemini(model, systemInstruction, prompt, imageBase64, temperature, maxOutputTokens, forceJson)
        OPENAI -> openAi(model, systemInstruction, prompt, imageBase64, temperature, maxOutputTokens, forceJson)
        ANTHROPIC -> anthropic(model, systemInstruction, prompt, imageBase64, temperature, maxOutputTokens)
        CHATGPT -> chatGpt(model, systemInstruction, prompt, imageBase64, reasoningEffort)
    }

    /**
     * Pulls the assistant's words out of whatever the service wrapped them in.
     *
     * A streaming service is read the same way as any other: the whole body is already
     * in hand by the time this is called, so the events are replayed in order rather
     * than consumed as they arrive. Nothing downstream wants a partial answer.
     */
    fun extractText(response: String): String? {
        if (this == CHATGPT) return streamedText(response)
        val root = runCatching { AutobileJson.parseToJsonElement(response) as? JsonObject }.getOrNull() ?: return null
        val text = when (this) {
            GEMINI -> {
                val parts = ((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.let { it["content"] as? JsonObject }
                    ?.let { it["parts"] as? JsonArray }
                parts?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }?.joinToString("")
            }

            OPENAI -> ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.let { it["message"] as? JsonObject }
                ?.let { (it["content"] as? JsonPrimitive)?.content }

            ANTHROPIC -> (root["content"] as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
                ?.joinToString("")

            CHATGPT -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun gemini(
        model: String,
        systemInstruction: String?,
        prompt: String,
        imageBase64: String?,
        temperature: Float,
        maxOutputTokens: Int,
        forceJson: Boolean,
    ) = buildJsonObject {
        if (!systemInstruction.isNullOrBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
            }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        imageBase64?.let {
                            add(
                                buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", IMAGE_MIME)
                                        put("data", it)
                                    }
                                },
                            )
                        }
                        add(buildJsonObject { put("text", prompt) })
                    }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("temperature", temperature)
            // Thinking models spend part of this budget before the first visible token.
            // Capped at the answer's own size, a thinking model routinely returns
            // nothing at all, which read as the model failing every visual turn.
            val thinking = geminiThinking(model)
            put("maxOutputTokens", maxOutputTokens + (thinking?.allowance ?: 0))
            put("candidateCount", 1)
            if (forceJson) put("responseMimeType", "application/json")
            thinking?.budget?.let { budget -> putJsonObject("thinkingConfig") { put("thinkingBudget", budget) } }
        }
    }.toString()

    private fun openAi(
        model: String,
        systemInstruction: String?,
        prompt: String,
        imageBase64: String?,
        temperature: Float,
        maxOutputTokens: Int,
        forceJson: Boolean,
    ) = buildJsonObject {
        put("model", model)
        if (isOpenAiReasoningModel(model)) {
            // Reasoning models reject any temperature but the default, and count their
            // hidden reasoning against max_completion_tokens. Sending the answer's own
            // size as the cap produced an empty reply with finish_reason "length".
            put("reasoning_effort", "low")
            put("max_completion_tokens", maxOutputTokens + REASONING_ALLOWANCE_TOKENS)
        } else {
            put("temperature", temperature)
            put("max_completion_tokens", maxOutputTokens)
        }
        if (forceJson) putJsonObject("response_format") { put("type", "json_object") }
        putJsonArray("messages") {
            if (!systemInstruction.isNullOrBlank()) {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemInstruction)
                    },
                )
            }
            add(
                buildJsonObject {
                    put("role", "user")
                    if (imageBase64 == null) {
                        // Plain text where no picture is involved: some services that
                        // implement this API accept only the string form.
                        put("content", prompt)
                    } else {
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", prompt) })
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:$IMAGE_MIME;base64,$imageBase64")
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    }.toString()

    private fun anthropic(
        model: String,
        systemInstruction: String?,
        prompt: String,
        imageBase64: String?,
        temperature: Float,
        maxOutputTokens: Int,
    ) = buildJsonObject {
        put("model", model)
        put("temperature", temperature)
        put("max_tokens", maxOutputTokens)
        if (!systemInstruction.isNullOrBlank()) put("system", systemInstruction)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        imageBase64?.let {
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", IMAGE_MIME)
                                        put("data", it)
                                    }
                                },
                            )
                        }
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    }
                },
            )
        }
    }.toString()

    /**
     * The responses shape, kept to the fields the subscription surface accepts.
     *
     * Sampling controls and output caps are deliberately absent: the models behind this
     * surface reject them, and a rejected request reads as a flat HTTP 400 with no
     * indication which field was at fault.
     */
    private fun chatGpt(
        model: String,
        systemInstruction: String?,
        prompt: String,
        imageBase64: String?,
        reasoningEffort: String?,
    ) = buildJsonObject {
        put("model", model)
        reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
        // Refused outright when true — this surface never stores a conversation.
        put("store", false)
        put("stream", true)
        // Must not be empty. A blank instruction is rejected rather than defaulted.
        put("instructions", systemInstruction?.takeIf { it.isNotBlank() } ?: DEFAULT_INSTRUCTIONS)
        putJsonArray("input") {
            add(
                buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "input_text"); put("text", prompt) })
                        imageBase64?.let {
                            add(
                                buildJsonObject {
                                    put("type", "input_image")
                                    put("detail", "auto")
                                    put("image_url", "data:$IMAGE_MIME;base64,$it")
                                },
                            )
                        }
                    }
                },
            )
        }
        putJsonObject("text") { put("verbosity", "low") }
        putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
    }.toString()

    /**
     * Reassembles an answer from a server-sent event stream.
     *
     * The deltas are preferred because they are always present; the completed event is
     * the fallback for a reply short enough to arrive whole.
     */
    private fun streamedText(body: String): String? {
        val deltas = StringBuilder()
        var completed: String? = null
        body.lineSequence().forEach { line ->
            val payload = line.trim().removePrefix("data:").trim()
            if (payload.isBlank() || payload == "[DONE]" || !line.trim().startsWith("data:")) return@forEach
            val event = runCatching { AutobileJson.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                ?: return@forEach
            when ((event["type"] as? JsonPrimitive)?.content) {
                "response.output_text.delta" ->
                    deltas.append((event["delta"] as? JsonPrimitive)?.content.orEmpty())

                "response.completed" -> completed = completedText(event)
            }
        }
        return deltas.toString().takeIf { it.isNotBlank() } ?: completed?.takeIf { it.isNotBlank() }
    }

    private fun completedText(event: JsonObject): String? =
        ((event["response"] as? JsonObject)?.get("output") as? JsonArray)
            ?.mapNotNull { item ->
                ((item as? JsonObject)?.get("content") as? JsonArray)
                    ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
                    ?.joinToString("")
            }
            ?.joinToString("")

    companion object {
        /**
         * Whether an OpenAI-compatible model is a reasoning model.
         *
         * Matched on the published family prefixes. A chat snapshot of a reasoning
         * family is served without reasoning and still accepts sampling controls.
         */
        internal fun isOpenAiReasoningModel(model: String): Boolean {
            val name = model.substringAfterLast('/').lowercase()
            if ("chat" in name) return false
            return REASONING_FAMILIES.any { name.startsWith(it) }
        }

        /**
         * Thinking settings for a Gemini model, or null when it does not think.
         *
         * 2.5 Flash and Pro think by default and share the output budget with the
         * answer, so they get an explicit, bounded budget. Lite models only think when
         * asked to. Later families take a thinking level instead of a budget, so they
         * keep their default and only receive the extra room.
         */
        internal fun geminiThinking(model: String): GeminiThinking? {
            val name = model.substringAfterLast('/').lowercase()
            return when {
                !name.startsWith("gemini-") -> null
                "lite" in name -> null
                name.startsWith("gemini-2.5") -> GeminiThinking(GEMINI_THINKING_BUDGET, GEMINI_THINKING_BUDGET)
                name.startsWith("gemini-1") || name.startsWith("gemini-2.0") -> null
                else -> GeminiThinking(budget = null, allowance = REASONING_ALLOWANCE_TOKENS)
            }
        }

        private val REASONING_FAMILIES = listOf("gpt-5", "o1", "o3", "o4")
        private const val REASONING_ALLOWANCE_TOKENS = 4_096
        private const val GEMINI_THINKING_BUDGET = 1_024

        /**
         * Sent when the caller supplies no system prompt.
         *
         * The field cannot be empty — this surface rejects a blank instruction rather
         * than filling one in — so something harmless has to stand in its place.
         */
        const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."

        private const val IMAGE_MIME = "image/jpeg"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * How the product introduces itself over HTTP.
         *
         * Honest about what is calling and from where, which is what a service
         * operator needs in order to tell ordinary traffic from something else.
         */
        private val USER_AGENT: String = "autobile (Android ${android.os.Build.VERSION.RELEASE})"
    }
}

/** How much of a Gemini output budget thinking may use, and how much room to add for it. */
internal data class GeminiThinking(val budget: Int?, val allowance: Int)
