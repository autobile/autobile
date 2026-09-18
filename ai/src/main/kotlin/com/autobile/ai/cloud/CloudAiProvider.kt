package com.autobile.ai.cloud

import android.graphics.Bitmap
import android.util.Base64
import com.autobile.ai.cloud.oauth.CloudSession
import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.ReasoningProvider
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.provider.TextRequest
import com.autobile.ai.provider.VisionProvider
import com.autobile.ai.provider.VisionRequest
import com.autobile.core.common.AutobileJson
import com.autobile.core.common.Logx
import com.autobile.core.model.EscalationReason
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Network inference, used only when no local tier can answer.
 *
 * The service, its endpoint, its models and the credential are all the user's choice,
 * so the product is not bound to one vendor and can be pointed at a deployment someone
 * runs themselves. What differs between services lives in [CloudDialect]; everything
 * here — the privacy gates, the error mapping, the timeouts — is the same for all of
 * them.
 *
 * Two tiers share this implementation. The light tier is a small, fast model used for
 * classification; the advanced tier is a larger model used for recovery planning and
 * for reading screenshots. Splitting them keeps the common case cheap.
 */
class CloudAiProvider(
    override val tier: RuntimeTier,
    private val config: () -> CloudConfig,
    /**
     * Renews an expiring subscription session and stores the result.
     *
     * Passed in rather than performed here because renewal is only meaningful if it is
     * persisted, and where settings live is not this class's concern. The default does
     * nothing, which is correct for a key-based service.
     */
    private val renewSession: suspend (CloudSession) -> Unit = {},
) : ReasoningProvider, VisionProvider, StructuredInferenceProvider {

    override val id: String = if (tier == RuntimeTier.CLOUD_ADVANCED) "cloud-advanced" else "cloud-light"

    private val consecutiveFailures = AtomicLong(0)
    @Volatile private var chatGptCatalog: CachedChatGptCatalog? = null

    override suspend fun capabilities(): ProviderCapabilities {
        val cfg = config()
        if (!cfg.isUsable) return ProviderCapabilities.unavailable("Cloud access is off or unconfigured")
        return ProviderCapabilities(
            available = true,
            supportsVision = cfg.allowImages && cfg.service.supportsImages,
            visionRestriction = when {
                !cfg.service.supportsImages -> EscalationReason.MODALITY_UNSUPPORTED
                !cfg.allowImages -> EscalationReason.POLICY_REQUIRED
                else -> null
            },
            supportsSystemPrompt = true,
            maxInputTokens = cfg.maxInputTokens,
            modelName = cfg.modelFor(tier),
            detail = cfg.endpointLabel,
        )
    }

    override suspend fun complete(request: TextRequest): InferenceResult<String> =
        send(request.label, request.systemInstruction, request.prompt, null, request.temperature, request.maxOutputTokens)

    override suspend fun describe(request: VisionRequest): InferenceResult<String> {
        val cfg = config()
        if (!cfg.allowImages) {
            return failure(
                InferenceErrorKind.POLICY_BLOCKED,
                "Sending screenshots off the device is disabled",
            )
        }
        return send(
            request.label,
            request.systemInstruction,
            request.prompt,
            request.image,
            request.temperature,
            request.maxOutputTokens,
        )
    }

    override suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T> {
        val prompt = buildString {
            append(request.prompt.trimEnd())
            append("\n\n")
            append(request.schema.promptContract())
        }
        val text = send(
            request.label,
            request.systemInstruction,
            prompt,
            request.image,
            request.temperature,
            request.maxOutputTokens,
            forceJson = true,
        )
        return com.autobile.ai.provider.decodeStructured(text, request.schema, tier, id)
    }

    private suspend fun send(
        label: String,
        systemInstruction: String?,
        prompt: String,
        image: Bitmap?,
        temperature: Float,
        maxOutputTokens: Int,
        forceJson: Boolean = false,
    ): InferenceResult<String> = withContext(Dispatchers.IO) {
        val cfg = currentConfig()
        if (!cfg.isUsable) {
            return@withContext failure<String>(InferenceErrorKind.UNAVAILABLE, "Cloud access is off or unconfigured")
        }
        if (image != null && !cfg.allowImages) {
            return@withContext failure<String>(
                InferenceErrorKind.POLICY_BLOCKED,
                "Sending screenshots off the device is disabled",
            )
        }

        val startedAt = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            val model = currentModel(cfg, needsVision = image != null)
            if (image != null && model.isBlank()) {
                return@withContext failure<String>(
                    InferenceErrorKind.UNSUPPORTED,
                    "The signed-in account has no model advertising image input",
                )
            }
            val url = URL(cfg.service.dialect.requestUrl(cfg.endpoint, model))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = cfg.connectTimeoutMs
                readTimeout = cfg.readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                cfg.credential.let { cfg.service.dialect.authHeaders(it) }.forEach(::setRequestProperty)
            }
            val body = cfg.service.dialect.requestBody(
                model = model,
                systemInstruction = systemInstruction,
                prompt = prompt,
                imageBase64 = image?.let(::encodeImage),
                temperature = temperature,
                maxOutputTokens = maxOutputTokens,
                forceJson = forceJson,
            )
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                consecutiveFailures.incrementAndGet()
                Logx.w("Cloud inference HTTP $status [$label]")
                return@withContext failure<String>(
                    CloudHttpErrorClassifier.classify(status, error),
                    "HTTP $status ${Logx.redact(error)}",
                )
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val text = cfg.service.dialect.extractText(responseText)
            consecutiveFailures.set(0)
            InferenceResult(
                value = text,
                confidence = if (text == null) 0f else 1f,
                tier = tier,
                providerId = id,
                rawText = text,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: java.net.SocketTimeoutException) {
            consecutiveFailures.incrementAndGet()
            failure(InferenceErrorKind.TIMEOUT, e.message ?: "cloud request timed out")
        } catch (e: java.io.IOException) {
            consecutiveFailures.incrementAndGet()
            failure(InferenceErrorKind.NETWORK, e.message ?: "cloud request failed")
        } catch (e: Throwable) {
            consecutiveFailures.incrementAndGet()
            Logx.w("Cloud inference failed [$label]", e)
            failure(InferenceErrorKind.UNKNOWN, e.message ?: "cloud request failed")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * The configuration to send with, after renewing a session that is about to lapse.
     *
     * Done before the request rather than after a failure: a token that expires mid-run
     * would surface as an authorisation error on a step the user was watching, and
     * retrying it would mean repeating whatever the step had already done.
     */
    private suspend fun currentConfig(): CloudConfig {
        val cfg = config()
        if (cfg.session.isEmpty || !cfg.session.needsRefresh()) return cfg
        runCatching { renewSession(cfg.session) }
            .onFailure { Logx.w("Cloud session could not be renewed", it) }
        return config()
    }

    /**
     * Uses the account's live subscription catalog when the preset has aged out.
     * Discovery is best-effort: a network failure must not prevent a valid configured
     * model from being attempted.
     */
    private fun currentModel(cfg: CloudConfig, needsVision: Boolean): String {
        val requested = cfg.modelFor(tier)
        if (cfg.service != CloudService.CHATGPT) return requested
        val discoveryEnabled = if (tier == RuntimeTier.CLOUD_ADVANCED) {
            cfg.discoverAdvancedModel
        } else {
            cfg.discoverLightModel
        }
        if (!discoveryEnabled) return requested
        val cacheKey = "${cfg.endpoint.trimEnd('/')}|${cfg.session.accountId}"
        val catalog = chatGptCatalog
            ?.takeIf { it.key == cacheKey }
            ?.catalog
            ?: discoverChatGptModels(cfg)?.also {
                chatGptCatalog = CachedChatGptCatalog(cacheKey, it)
            }
        return catalog?.select(
            requested,
            needsVision,
            preferAdvanced = tier == RuntimeTier.CLOUD_ADVANCED,
        ) ?: requested
    }

    private fun discoverChatGptModels(cfg: CloudConfig): ChatGptModelCatalog? {
        var connection: HttpURLConnection? = null
        return try {
            val base = cfg.endpoint.trimEnd('/')
            connection = (URL("$base/models?client_version=$CLIENT_VERSION").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = cfg.connectTimeoutMs
                readTimeout = cfg.readTimeoutMs
                cfg.service.dialect.modelCatalogHeaders(cfg.credential).forEach(::setRequestProperty)
            }
            if (connection.responseCode !in 200..299) return null
            ChatGptModelCatalog.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: java.io.IOException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encodeImage(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun <T : Any> failure(kind: InferenceErrorKind, message: String): InferenceResult<T> =
        InferenceResult(
            value = null,
            confidence = 0f,
            tier = tier,
            providerId = id,
            error = InferenceError(kind, message),
        )

    private companion object {
        const val JPEG_QUALITY = 70
        const val CLIENT_VERSION = "0.9.3"
    }

    private data class CachedChatGptCatalog(
        val key: String,
        val catalog: ChatGptModelCatalog,
    )
}

/**
 * Everything needed to reach a cloud endpoint.
 *
 * [allowImages] is separate from [enabled] on purpose: users routinely accept sending
 * a few lines of interface text off the device while refusing to send a picture of
 * their screen, and the two decisions must be independently revocable.
 */
data class CloudConfig(
    val enabled: Boolean = false,
    /** Which service this points at, which decides the wire format and the defaults. */
    val service: CloudService = CloudService.GEMINI,
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    /** A signed-in subscription, for services that are proved to that way. */
    val session: CloudSession = CloudSession.NONE,
    val lightModel: String = DEFAULT_LIGHT_MODEL,
    val advancedModel: String = DEFAULT_ADVANCED_MODEL,
    /** Whether a service preset may follow the signed-in account's live model catalog. */
    val discoverLightModel: Boolean = false,
    val discoverAdvancedModel: Boolean = false,
    val allowImages: Boolean = false,
    val maxInputTokens: Int = 32_000,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 45_000,
) {
    /** Whichever of a key or a session this service actually expects. */
    val credential: CloudCredential get() = CloudCredential.of(service, apiKey, session)

    val isUsable: Boolean get() = enabled && endpoint.isNotBlank() && credential.isPresent

    val endpointLabel: String
        get() = runCatching { URL(endpoint).host }.getOrNull() ?: endpoint

    fun modelFor(tier: RuntimeTier): String =
        if (tier == RuntimeTier.CLOUD_ADVANCED) advancedModel else lightModel

    fun requestUrl(tier: RuntimeTier): String = service.dialect.requestUrl(endpoint, modelFor(tier))

    /** True when this service can be sent a picture at all, before the user's choice. */
    val serviceSupportsImages: Boolean get() = service.supportsImages

    companion object {
        const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_LIGHT_MODEL = "gemini-2.5-flash-lite"
        const val DEFAULT_ADVANCED_MODEL = "gemini-2.5-flash"
    }
}
