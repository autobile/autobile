package com.autobile.ai.cloud.oauth

import com.autobile.core.common.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.autobile.core.common.AutobileJson
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Signing in to a ChatGPT subscription instead of pasting an API key.
 *
 * Autobile speaks the same sign-in flow the Codex CLI uses, because that is the flow
 * OpenAI exposes for letting a subscription — rather than a pay-per-token balance —
 * answer a request. The tokens it returns are the user's own: they are exchanged on the
 * device, stored on the device, and every request is billed to the account that signed
 * in. Nothing is proxied through a server of ours, and no second user ever shares a
 * session.
 *
 * This is an arrangement OpenAI permits rather than one it guarantees. If that changes,
 * the API key path beside it keeps working, which is why both exist.
 */
object ChatGptSignIn {

    const val ISSUER = "https://auth.openai.com"

    /**
     * The client the sign-in identifies as.
     *
     * A public identifier, not a secret: the flow is secured by [Pkce] instead. The
     * matching redirect must be one the issuer already allows, which is why the port
     * below is fixed rather than chosen freely.
     */
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    /** Ports the issuer will redirect a loopback callback to. */
    val CALLBACK_PORTS = listOf(1455, 1457)

    const val CALLBACK_PATH = "/auth/callback"

    private const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"

    fun redirectUri(port: Int): String = "http://localhost:$port$CALLBACK_PATH"

    /** Where the browser is sent to ask the person to approve. */
    fun authorizeUrl(redirectUri: String, pkce: Pkce, state: String): String {
        val query = listOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to SCOPE,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$ISSUER/oauth/authorize?$query"
    }

    /** Turns the code the browser handed back into a usable session. */
    suspend fun exchange(code: String, verifier: String, redirectUri: String): Result<CloudSession> =
        post(
            "grant_type=authorization_code" +
                "&code=${encode(code)}" +
                "&redirect_uri=${encode(redirectUri)}" +
                "&client_id=${encode(CLIENT_ID)}" +
                "&code_verifier=${encode(verifier)}",
        )

    /**
     * Renews an expiring session.
     *
     * The issuer may or may not hand back a new refresh token; when it does not, the
     * existing one stays valid and is carried forward rather than blanked.
     */
    suspend fun refresh(session: CloudSession): Result<CloudSession> =
        post(
            "grant_type=refresh_token" +
                "&client_id=${encode(CLIENT_ID)}" +
                "&refresh_token=${encode(session.refreshToken)}" +
                "&scope=${encode(SCOPE)}",
        ).map { renewed ->
            renewed.copy(
                refreshToken = renewed.refreshToken.ifBlank { session.refreshToken },
                accountId = renewed.accountId.ifBlank { session.accountId },
                email = renewed.email.ifBlank { session.email },
                plan = renewed.plan.ifBlank { session.plan },
            )
        }

    private suspend fun post(form: String): Result<CloudSession> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$ISSUER/oauth/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Logx.w("ChatGPT sign-in exchange returned HTTP $status")
                return@withContext Result.failure(SignInFailed("HTTP $status ${Logx.redact(detail)}"))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)?.let { Result.success(it) }
                ?: Result.failure(SignInFailed("The sign-in reply did not contain a token"))
        } catch (e: Throwable) {
            Logx.w("ChatGPT sign-in exchange failed", e)
            Result.failure(SignInFailed(e.message ?: "sign-in failed"))
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(body: String): CloudSession? {
        val root = runCatching { AutobileJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val access = (root["access_token"] as? JsonPrimitive)?.content.orEmpty()
        if (access.isBlank()) return null
        val claims = IdentityClaims.parse((root["id_token"] as? JsonPrimitive)?.content)
        val lifetimeSeconds = (root["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        return CloudSession(
            accessToken = access,
            refreshToken = (root["refresh_token"] as? JsonPrimitive)?.content.orEmpty(),
            accountId = claims.accountId,
            email = claims.email,
            plan = claims.plan,
            expiresAt = if (lifetimeSeconds > 0) System.currentTimeMillis() + lifetimeSeconds * 1_000 else 0L,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
}

/** A sign-in that did not produce a session, with the reason to show the user. */
class SignInFailed(message: String) : Exception(message)
