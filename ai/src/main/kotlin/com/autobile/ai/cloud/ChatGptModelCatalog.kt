package com.autobile.ai.cloud

import com.autobile.core.common.AutobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A small, fail-closed view of the subscription model catalog. */
internal data class ChatGptModelCatalog(val models: List<Model>) {
    data class Model(
        val slug: String,
        val acceptsImages: Boolean?,
        /** Reasoning efforts the model advertises; empty when the catalog says nothing. */
        val reasoningEfforts: Set<String> = emptySet(),
    )

    /**
     * The effort to request from [slug], or null to leave the model's default.
     *
     * Autobile's questions are short and structured, and a phone operated one turn at a
     * time feels every second of deliberation. Low effort is asked for only when the
     * model advertises it: an effort the model does not accept fails the whole request.
     */
    fun preferredEffort(slug: String): String? =
        models.firstOrNull { it.slug == slug }?.reasoningEfforts?.firstOrNull { it == PREFERRED_EFFORT }

    fun select(requested: String, needsVision: Boolean = false, preferAdvanced: Boolean = false): String {
        return candidates(requested, needsVision, preferAdvanced).firstOrNull().orEmpty()
    }

    fun candidates(requested: String, needsVision: Boolean = false, preferAdvanced: Boolean = false): List<String> {
        val eligible = if (needsVision) {
            val explicitlyVisual = models.filter { it.acceptsImages == true }
            explicitlyVisual.ifEmpty { models.filter { it.acceptsImages != false } }
        } else {
            models
        }
        val ordered = if (preferAdvanced) eligible.asReversed() else eligible
        return buildList {
            eligible.firstOrNull { it.slug == requested }?.slug?.let(::add)
            ordered.mapTo(this) { it.slug }
        }.distinct()
    }

    companion object {
        private const val PREFERRED_EFFORT = "low"

        fun parse(body: String): ChatGptModelCatalog? {
            val root = runCatching { AutobileJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return null
            val models = (root["models"] as? JsonArray)
                ?.mapNotNull { item ->
                    val model = item as? JsonObject ?: return@mapNotNull null
                    val supported = (model["supported_in_api"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        ?: true
                    val slug = (model["slug"] as? JsonPrimitive)?.content
                        ?.takeIf { supported && it.isNotBlank() }
                        ?: return@mapNotNull null
                    Model(slug, model.acceptsImages(), model.reasoningEfforts())
                }
                ?.distinctBy { it.slug }
                .orEmpty()
            return models.takeIf { it.isNotEmpty() }?.let(::ChatGptModelCatalog)
        }
    }
}

/** Accepts both `["low", ...]` and `[{"effort": "low", ...}, ...]`. */
private fun JsonObject.reasoningEfforts(): Set<String> =
    (get("supported_reasoning_levels") as? JsonArray)
        ?.mapNotNull { level ->
            when (level) {
                is JsonPrimitive -> level.content
                is JsonObject -> (level["effort"] as? JsonPrimitive)?.content
                else -> null
            }
        }
        ?.map { it.lowercase() }
        ?.toSet()
        .orEmpty()

/** Catalog revisions have used both a boolean and a modality list. Unknown stays eligible. */
private fun JsonObject.acceptsImages(): Boolean? {
    (get("supports_image_input") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { return it }
    val modalities = (get("input_modalities") as? JsonArray)
        ?: (get("supported_inputs") as? JsonArray)
        ?: return null
    return modalities.any { (it as? JsonPrimitive)?.content?.equals("image", ignoreCase = true) == true }
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
