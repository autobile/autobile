package com.autobile.ai.cloud.oauth

import com.autobile.core.common.AutobileJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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

    /** A subscription grant must be renewable; an access token alone is temporary. */
    val canAuthorize: Boolean get() = accessToken.isNotBlank() && refreshToken.isNotBlank()

    /**
     * Whether the token should be renewed before it is used.
     *
     * Renewed a minute early: a token that expires while a request is in flight fails
     * the run, and a minute costs nothing.
     */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean =
        refreshToken.isNotBlank() && (expiresAt <= 0L || now >= expiresAt - REFRESH_MARGIN_MS)

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
 * Who a session belongs to, read out of the access token itself.
 *
 * The access token is the source rather than the identity token because it is the only
 * one of the two that comes back from a refresh. Reading the identity token instead
 * would mean the account id — which every request has to carry — quietly went missing
 * the first time a session was renewed.
 *
 * The token is read, never verified: it arrived over TLS from the issuer in exchange for
 * a code this process generated the verifier for, and nothing security relevant is
 * decided from these fields. They name the account on screen and address the request.
 * The signature is the issuer's business.
 */
data class IdentityClaims(
    val email: String = "",
    val accountId: String = "",
    val plan: String = "",
    /** Expiry the issuer put in the token, which outranks any relative lifetime. */
    val expiresAt: Long = 0L,
) {
    companion object {
        private const val AUTH_CLAIM = "https://api.openai.com/auth"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"

        fun parse(jwt: String?): IdentityClaims {
            val payload = jwt?.split('.')?.getOrNull(1) ?: return IdentityClaims()
            val json = runCatching {
                AutobileJson.parseToJsonElement(String(decodeBase64Url(payload), Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return IdentityClaims()

            val auth = json[AUTH_CLAIM] as? JsonObject
            val profile = json[PROFILE_CLAIM] as? JsonObject
            val expiresSeconds = (json["exp"] as? JsonPrimitive)?.longOrNull ?: 0L
            return IdentityClaims(
                // The profile claim is where the address lives; a bare top-level `email`
                // is accepted too because the identity token spells it that way.
                email = (profile?.get("email") as? JsonPrimitive)?.content
                    ?: (json["email"] as? JsonPrimitive)?.content.orEmpty(),
                accountId = (auth?.get("chatgpt_account_id") as? JsonPrimitive)?.content.orEmpty(),
                plan = (auth?.get("chatgpt_plan_type") as? JsonPrimitive)?.content.orEmpty(),
                expiresAt = if (expiresSeconds > 0) expiresSeconds * 1_000 else 0L,
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
