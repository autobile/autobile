package com.autobile.ai.cloud

import com.autobile.core.common.AutobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A small, fail-closed view of the subscription model catalog. */
internal data class ChatGptModelCatalog(val models: List<String>) {
    fun select(requested: String): String =
        requested.takeIf(models::contains) ?: models.firstOrNull().orEmpty()

    companion object {
        fun parse(body: String): ChatGptModelCatalog? {
            val root = runCatching { AutobileJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return null
            val models = (root["models"] as? JsonArray)
                ?.mapNotNull { item ->
                    val model = item as? JsonObject ?: return@mapNotNull null
                    val supported = (model["supported_in_api"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        ?: true
                    (model["slug"] as? JsonPrimitive)?.content?.takeIf { supported && it.isNotBlank() }
                }
                ?.distinct()
                .orEmpty()
            return models.takeIf { it.isNotEmpty() }?.let(::ChatGptModelCatalog)
        }
    }
}

/** Maps protocol errors without claiming every malformed request lacks a modality. */
internal object CloudHttpErrorClassifier {
    fun classify(status: Int, body: String): com.autobile.core.model.InferenceErrorKind {
        val normalized = body.lowercase()
        return when (status) {
            400 -> when {
                normalized.contains("model") && (
                    normalized.contains("not found") ||
                        normalized.contains("model_not_found") ||
                        normalized.contains("does not exist") ||
                        normalized.contains("unsupported") ||
                        normalized.contains("not supported") ||
                        normalized.contains("unknown model") ||
                        normalized.contains("not available")
                    ) -> com.autobile.core.model.InferenceErrorKind.UNAVAILABLE

                normalized.contains("image") || normalized.contains("modality") ->
                    com.autobile.core.model.InferenceErrorKind.UNSUPPORTED

                else -> com.autobile.core.model.InferenceErrorKind.UNKNOWN
            }

            401, 403 -> com.autobile.core.model.InferenceErrorKind.POLICY_BLOCKED
            413 -> com.autobile.core.model.InferenceErrorKind.REQUEST_TOO_LARGE
            429 -> com.autobile.core.model.InferenceErrorKind.QUOTA_EXCEEDED
            in 500..599 -> com.autobile.core.model.InferenceErrorKind.UNAVAILABLE
            else -> com.autobile.core.model.InferenceErrorKind.UNKNOWN
        }
    }
}
