package com.autobile.ai.cloud

import com.autobile.ai.cloud.oauth.ChatGptSignIn
import com.autobile.ai.cloud.oauth.CloudSession
import com.autobile.ai.cloud.oauth.IdentityClaims
import com.autobile.ai.cloud.oauth.LoopbackCallback
import com.autobile.ai.cloud.oauth.Pkce
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

/**
 * Signing in to a subscription rather than pasting a key.
 *
 * None of this can be proved against the real issuer from a test — that needs a person
 * with an account and a browser. What can be proved is that every value the issuer
 * checks is built the way it checks it: a challenge that is genuinely the hash of the
 * verifier, a redirect that names the port actually listening, a reply that is rejected
 * when it does not match the attempt. Each of those failing produces the same opaque
 * error from the issuer, so each is pinned separately here.
 */
class SubscriptionSignInTest {

    @Test
    fun `the challenge is the hash of the verifier`() {
        val pkce = Pkce.generate()
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(pkce.verifier.toByteArray(Charsets.US_ASCII))
        assertThat(pkce.challenge).isEqualTo(base64Url(expected))
    }

    @Test
    fun `the verifier is url safe and unpadded`() {
        repeat(20) {
            val pkce = Pkce.generate()
            assertThat(pkce.verifier).matches("[A-Za-z0-9_-]+")
            assertThat(pkce.challenge).matches("[A-Za-z0-9_-]+")
            // Below the spec's 43-character floor the issuer rejects the exchange.
            assertThat(pkce.verifier.length).isAtLeast(43)
        }
    }

    @Test
    fun `two attempts never share a verifier`() {
        val seen = (1..50).map { Pkce.generate().verifier }.toSet()
        assertThat(seen).hasSize(50)
    }

    @Test
    fun `the authorize url carries everything the issuer checks`() {
        val pkce = Pkce(verifier = "v", challenge = "c")
        val url = ChatGptSignIn.authorizeUrl(ChatGptSignIn.redirectUri(1455), pkce, "st")

        assertThat(url).startsWith("${ChatGptSignIn.ISSUER}/oauth/authorize?")
        assertThat(url).contains("client_id=${ChatGptSignIn.CLIENT_ID}")
        assertThat(url).contains("code_challenge=c")
        assertThat(url).contains("code_challenge_method=S256")
        assertThat(url).contains("state=st")
        // The redirect has to survive encoding intact or the issuer refuses it.
        assertThat(url).contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback")
        // The verifier proves the exchange later; sending it now would defeat the point.
        assertThat(url).doesNotContain("code_verifier")
    }

    @Test
    fun `the sign-in asks only for what the product uses`() {
        val url = ChatGptSignIn.authorizeUrl(ChatGptSignIn.redirectUri(1455), Pkce("v", "c"), "st")

        // Identifying the account, and renewing without asking again. Nothing further:
        // a scope the product never exercises is consent taken for no reason.
        assertThat(url).contains("scope=openid+profile+email+offline_access")
        assertThat(url).doesNotContain("connectors")
    }

    @Test
    fun `the sign-in says which app is asking`() {
        val url = ChatGptSignIn.authorizeUrl(ChatGptSignIn.redirectUri(1455), Pkce("v", "c"), "st")

        // Named honestly rather than borrowing another client's name, so what the
        // service records about who is calling is true.
        assertThat(ChatGptSignIn.ORIGINATOR).isEqualTo("autobile")
        assertThat(url).contains("originator=autobile")
    }

    @Test
    fun `the redirect names a port the issuer will accept`() {
        ChatGptSignIn.CALLBACK_PORTS.forEach { port ->
            assertThat(ChatGptSignIn.redirectUri(port)).isEqualTo("http://localhost:$port/auth/callback")
        }
    }

    @Test
    fun `the callback reads the code out of the request line`() {
        val query = LoopbackCallback.parseQuery("GET /auth/callback?code=abc&state=xyz HTTP/1.1")
        assertThat(query["code"]).isEqualTo("abc")
        assertThat(query["state"]).isEqualTo("xyz")
    }

    @Test
    fun `the callback decodes an escaped value`() {
        val query = LoopbackCallback.parseQuery("GET /auth/callback?error_description=Access%20denied HTTP/1.1")
        assertThat(query["error_description"]).isEqualTo("Access denied")
    }

    @Test
    fun `a request with no query yields nothing to act on`() {
        assertThat(LoopbackCallback.parseQuery("GET /auth/callback HTTP/1.1")).isEmpty()
        assertThat(LoopbackCallback.parseQuery("")).isEmpty()
    }

    @Test
    fun `the account is read out of the access token`() {
        // Read from the access token rather than the identity token because a refresh
        // returns only the former. Reading the other would mean the account id — which
        // every request has to carry — went missing the first time a session renewed.
        val claims = IdentityClaims.parse(accessToken())

        assertThat(claims.email).isEqualTo("someone@example.com")
        assertThat(claims.accountId).isEqualTo("acct-123")
        assertThat(claims.plan).isEqualTo("pro")
    }

    @Test
    fun `the expiry the issuer signed outranks any relative lifetime`() {
        val claims = IdentityClaims.parse(accessToken(expiresAtSeconds = 1_800_000_000L))
        assertThat(claims.expiresAt).isEqualTo(1_800_000_000_000L)
    }

    @Test
    fun `an unreadable identity token leaves the account blank rather than failing`() {
        assertThat(IdentityClaims.parse(null)).isEqualTo(IdentityClaims())
        assertThat(IdentityClaims.parse("not-a-token")).isEqualTo(IdentityClaims())
        assertThat(IdentityClaims.parse("a.!!!.c")).isEqualTo(IdentityClaims())
    }

    @Test
    fun `a session is renewed before it lapses, not after`() {
        val now = 1_000_000L
        val expiring = CloudSession("a", "r", expiresAt = now + 30_000)
        val fresh = CloudSession("a", "r", expiresAt = now + 600_000)

        assertThat(expiring.needsRefresh(now)).isTrue()
        assertThat(fresh.needsRefresh(now)).isFalse()
    }

    @Test
    fun `a session with no known expiry is left alone`() {
        assertThat(CloudSession("a", "r", expiresAt = 0L).needsRefresh()).isFalse()
    }

    @Test
    fun `a stored session survives a round trip and an empty one stores nothing`() {
        val session = CloudSession("access", "refresh", "acct", "someone@example.com", "pro", 42L)
        assertThat(CloudSession.decode(CloudSession.encode(session))).isEqualTo(session)
        assertThat(CloudSession.encode(CloudSession.NONE)).isEmpty()
        assertThat(CloudSession.decode(null)).isEqualTo(CloudSession.NONE)
        assertThat(CloudSession.decode("{not json")).isEqualTo(CloudSession.NONE)
    }

    @Test
    fun `a key service ignores a session and a sign-in service ignores a key`() {
        val session = CloudSession("access", "refresh", accountId = "acct")

        val keyed = CloudCredential.of(CloudService.GEMINI, "k", session)
        assertThat(keyed).isEqualTo(CloudCredential.Key("k"))

        val signedIn = CloudCredential.of(CloudService.CHATGPT, "k", session)
        assertThat(signedIn).isInstanceOf(CloudCredential.Session::class.java)
        with(signedIn as CloudCredential.Session) {
            assertThat(accessToken).isEqualTo("access")
            assertThat(accountId).isEqualTo("acct")
            assertThat(requestId).isNotEmpty()
        }
    }

    @Test
    fun `each request is identified separately`() {
        val session = CloudSession("access", "refresh", accountId = "acct")
        val ids = (1..20).map {
            (CloudCredential.of(CloudService.CHATGPT, "", session) as CloudCredential.Session).requestId
        }
        assertThat(ids.toSet()).hasSize(20)
    }

    @Test
    fun `a pasted redirect is accepted whole or as the bare code`() {
        assertThat(ChatGptSignIn.readPastedCode("abc123", "st").getOrNull()).isEqualTo("abc123")
        assertThat(
            ChatGptSignIn.readPastedCode("http://localhost:1455/auth/callback?code=abc123&state=st", "st").getOrNull(),
        ).isEqualTo("abc123")
    }

    @Test
    fun `a pasted redirect from another attempt is refused`() {
        val result = ChatGptSignIn.readPastedCode(
            "http://localhost:1455/auth/callback?code=abc&state=someone-elses",
            "st",
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `a pasted refusal reports what the issuer said`() {
        val result = ChatGptSignIn.readPastedCode(
            "http://localhost:1455/auth/callback?error=access_denied&error_description=You%20said%20no",
            "st",
        )
        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("You said no")
    }

    @Test
    fun `nothing pasted is reported rather than exchanged`() {
        assertThat(ChatGptSignIn.readPastedCode("   ", "st").isFailure).isTrue()
    }

    @Test
    fun `a sign-in service with nobody signed in has no credential`() {
        assertThat(CloudCredential.of(CloudService.CHATGPT, "k", CloudSession.NONE))
            .isEqualTo(CloudCredential.None)
    }

    private fun accessToken(expiresAtSeconds: Long = 0L): String {
        val payload = """
            {"exp":$expiresAtSeconds,
             "https://api.openai.com/profile":{"email":"someone@example.com"},
             "https://api.openai.com/auth":{"chatgpt_account_id":"acct-123","chatgpt_plan_type":"pro"}}
        """.trimIndent()
        return "header.${base64Url(payload.toByteArray(Charsets.UTF_8))}.signature"
    }

    private fun base64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
