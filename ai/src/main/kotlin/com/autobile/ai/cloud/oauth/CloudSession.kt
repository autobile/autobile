package com.autobile.ai.cloud.oauth

import com.autobile.core.common.AutobileJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A signed-in cloud account.
 *
 * Held instead of an API key for services that bill a subscription rather than a
 * balance. [expiresAt] is stored so a stale token is replaced before a run needs it,
 * rather than after the run has already failed.
 */
@Serializable
data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String = "",
    val email: String = "",
    val plan: String = "",
    val expiresAt: Long = 0L,
) {
    val isEmpty: Boolean get() = accessToken.isBlank()

    /**
     * Whether the token should be renewed before it is used.
     *
     * Renewed a minute early: a token that expires while a request is in flight fails
     * the run, and a minute costs nothing.
     */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt > 0L && now >= expiresAt - REFRESH_MARGIN_MS

    /** What the settings screen shows in place of the account itself. */
    val label: String get() = listOf(email, plan).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        private const val REFRESH_MARGIN_MS = 60_000L

        val NONE = CloudSession(accessToken = "", refreshToken = "")

        fun decode(stored: String?): CloudSession {
            if (stored.isNullOrBlank()) return NONE
            return runCatching { AutobileJson.decodeFromString<CloudSession>(stored) }.getOrDefault(NONE)
        }

        fun encode(session: CloudSession): String =
            if (session.isEmpty) "" else AutobileJson.encodeToString(session)
    }
}

/**
 * The account details carried inside an OpenID identity token.
 *
 * The token is read, never verified here: it arrived over TLS from the issuer in
 * exchange for a code this process generated the verifier for, and nothing security
 * relevant is decided from these fields — they name the account in the settings screen
 * and address the request. The signature is the issuer's business.
 */
data class IdentityClaims(
    val email: String = "",
    val accountId: String = "",
    val plan: String = "",
) {
    companion object {
        /** The namespaced claim OpenAI puts its account fields under. */
        private const val AUTH_CLAIM = "https://api.openai.com/auth"

        fun parse(idToken: String?): IdentityClaims {
            val payload = idToken?.split('.')?.getOrNull(1) ?: return IdentityClaims()
            val json = runCatching {
                AutobileJson.parseToJsonElement(String(decodeBase64Url(payload), Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return IdentityClaims()
            val auth = json[AUTH_CLAIM] as? JsonObject
            return IdentityClaims(
                email = (json["email"] as? JsonPrimitive)?.content.orEmpty(),
                accountId = (auth?.get("chatgpt_account_id") as? JsonPrimitive)?.content.orEmpty(),
                plan = (auth?.get("chatgpt_plan_type") as? JsonPrimitive)?.content.orEmpty(),
            )
        }

        private fun decodeBase64Url(value: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = java.io.ByteArrayOutputStream(value.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (character in value) {
                val index = alphabet.indexOf(character)
                if (index < 0) continue
                buffer = buffer shl 6 or index
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write(buffer ushr bits and 0xFF)
                }
            }
            return out.toByteArray()
        }
    }
}
