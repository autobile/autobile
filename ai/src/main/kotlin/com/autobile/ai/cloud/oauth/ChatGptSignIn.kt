package com.autobile.ai.cloud.oauth

import com.autobile.core.common.AutobileJson
import com.autobile.core.common.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Signing in to a ChatGPT subscription instead of pasting an API key.
 *
 * Autobile speaks the sign-in flow OpenAI exposes for letting a subscription — rather
 * than a pay-per-token balance — answer a request. The tokens are the user's own: they
 * are exchanged on the device, stored on the device, and every request is billed to the
 * account that signed in. Nothing is proxied through a server of ours and no session is
 * ever shared between people.
 *
 * Autobile identifies itself as itself. The `originator` it sends is its own name, not
 * another client's, so what OpenAI sees is accurate about which software is asking.
 *
 * This is an arrangement OpenAI permits rather than one it guarantees. If that changes,
 * the API key path beside it keeps working, which is why both exist.
 */
object ChatGptSignIn {

    const val ISSUER = "https://auth.openai.com"

    /**
     * The client the sign-in identifies as.
     *
     * A public identifier, not a secret — the flow is secured by [Pkce] instead. The
     * matching redirect has to be one the issuer already allows, which is why the port
     * below is fixed rather than chosen freely.
     */
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    /** How this app names itself to the service, on the sign-in and on every request. */
    const val ORIGINATOR = "autobile"

    /** Ports the issuer will redirect a loopback callback to. */
    val CALLBACK_PORTS = listOf(1455, 1457)

    const val CALLBACK_PATH = "/auth/callback"

    /**
     * What the sign-in asks for.
     *
     * Nothing beyond identifying the account and being able to renew without asking
     * again. A scope the product does not use is consent taken for no reason.
     */
    private const val SCOPE = "openid profile email offline_access"

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
            "originator" to ORIGINATOR,
            "state" to state,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$ISSUER/oauth/authorize?$query"
    }

    /**
     * Reads the code out of whatever the person pasted.
     *
     * A browser that will not hand a redirect back to a local port leaves the user
     * holding the address bar, and what is in it may be the whole redirect URL or just
     * the code. Both are accepted; a URL carrying a different state than this attempt is
     * refused the same way the loopback refuses one.
     */
    fun readPastedCode(input: String, state: String): Result<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.failure(SignInFailed("Nothing was pasted"))
        val query = trimmed.substringAfter('?', "")
        if (!trimmed.contains("://") && query.isEmpty()) return Result.success(trimmed)

        val fields = query.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=', "")
            if (name.isBlank()) null else name to decode(pair.substringAfter('=', ""))
        }.toMap()

        fields["error_description"]?.let { return Result.failure(SignInFailed(it)) }
        val pastedState = fields["state"]
        if (pastedState != null && pastedState != state) {
            return Result.failure(SignInFailed("That link belongs to a different sign-in attempt"))
        }
        val code = fields["code"]
        return if (code.isNullOrBlank()) {
            Result.failure(SignInFailed("That link carries no sign-in code"))
        } else {
            Result.success(code)
        }
    }

    /** Turns the code the browser handed back into a usable session. */
    suspend fun exchange(code: String, verifier: String, redirectUri: String): Result<CloudSession> =
        post(
            "exchange",
            "grant_type=authorization_code" +
                "&client_id=${encode(CLIENT_ID)}" +
                "&code=${encode(code)}" +
                "&code_verifier=${encode(verifier)}" +
                "&redirect_uri=${encode(redirectUri)}",
        )

    /**
     * Renews an expiring session.
     *
     * The issuer may or may not hand back a new refresh token; when it does not, the
     * existing one stays valid and is carried forward rather than blanked.
     */
    suspend fun refresh(session: CloudSession): Result<CloudSession> =
        post(
            "refresh",
            "grant_type=refresh_token" +
                "&client_id=${encode(CLIENT_ID)}" +
                "&refresh_token=${encode(session.refreshToken)}",
            existingRefreshToken = session.refreshToken,
        ).map { renewed ->
            renewed.copy(
                refreshToken = renewed.refreshToken.ifBlank { session.refreshToken },
                accountId = renewed.accountId.ifBlank { session.accountId },
                email = renewed.email.ifBlank { session.email },
                plan = renewed.plan.ifBlank { session.plan },
            )
        }

    private suspend fun post(
        operation: String,
        form: String,
        existingRefreshToken: String = "",
    ): Result<CloudSession> = withContext(Dispatchers.IO) {
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
                Logx.w("ChatGPT sign-in $operation returned HTTP $status")
                return@withContext Result.failure(failureFor(status, detail))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseTokenResponse(body, existingRefreshToken)?.let { Result.success(it) }
                ?: Result.failure(SignInFailed("The sign-in reply did not contain a token"))
        } catch (e: Throwable) {
            Logx.w("ChatGPT sign-in $operation failed", e)
            Result.failure(SignInFailed(e.message ?: "sign-in failed"))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Separates a grant that will never work again from one that might.
     *
     * A revoked, reused or expired refresh token is not a setback to retry — every later
     * attempt fails the same way. Renewing forever against a dead grant would leave
     * someone with an account that silently never answers and no hint that signing in
     * again is what fixes it.
     */
    private fun failureFor(status: Int, body: String): SignInFailed {
        val root = runCatching { AutobileJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error")
        val code = when (error) {
            is JsonPrimitive -> error.content
            is JsonObject -> (error["code"] as? JsonPrimitive)?.content
            else -> null
        } ?: (root?.get("code") as? JsonPrimitive)?.content
        val described = (root?.get("error_description") as? JsonPrimitive)?.content
            ?: ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.content

        val terminal = code?.lowercase() in TERMINAL_GRANT_ERRORS
        return SignInFailed(
            message = described ?: "Sign-in failed (HTTP $status)",
            grantIsDead = terminal,
        )
    }

    internal fun parseTokenResponse(
        body: String,
        existingRefreshToken: String = "",
        now: Long = System.currentTimeMillis(),
    ): CloudSession? {
        val root = runCatching { AutobileJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val access = (root["access_token"] as? JsonPrimitive)?.content.orEmpty()
        if (access.isBlank()) return null
        val refresh = (root["refresh_token"] as? JsonPrimitive)?.content.orEmpty()
            .ifBlank { existingRefreshToken }
        if (refresh.isBlank()) return null

        // The access token carries the account; the identity token is only consulted for
        // an address the access token happened not to include.
        val claims = IdentityClaims.parse(access)
        val identity = IdentityClaims.parse((root["id_token"] as? JsonPrimitive)?.content)
        val lifetimeSeconds = (root["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?.takeIf { it > 0L } ?: return null

        return CloudSession(
            accessToken = access,
            refreshToken = refresh,
            accountId = claims.accountId.ifBlank { identity.accountId },
            email = claims.email.ifBlank { identity.email },
            plan = claims.plan.ifBlank { identity.plan },
            expiresAt = when {
                claims.expiresAt > 0 -> claims.expiresAt
                else -> now + lifetimeSeconds * 1_000
            },
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** Issuer error codes that mean the grant is gone for good. */
    private val TERMINAL_GRANT_ERRORS = setOf(
        "invalid_grant",
        "invalid_refresh_token",
        "refresh_token_expired",
        "refresh_token_invalidated",
        "refresh_token_reused",
    )

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
}

/**
 * A sign-in that did not produce a session.
 *
 * [grantIsDead] separates the two cases that need different answers: a network that was
 * down, which will work later, and a grant the issuer has retired, which needs the
 * person to sign in again.
 */
class SignInFailed(
    message: String,
    val grantIsDead: Boolean = false,
) : Exception(message)
