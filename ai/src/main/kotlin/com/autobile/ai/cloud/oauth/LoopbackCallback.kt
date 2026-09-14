package com.autobile.ai.cloud.oauth

import com.autobile.core.common.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Catches the one redirect a browser sign-in sends back.
 *
 * The browser that shows the approval page is not this app, so the code has to arrive
 * over something both can reach. A socket on the loopback interface is that thing: it is
 * reachable only from this device, the issuer already allows it as a redirect, and it
 * needs no custom URL scheme that another installed app could also claim.
 *
 * Exactly one request is served and the socket closes. Leaving it listening would be a
 * port on the user's phone accepting connections for no further purpose.
 */
class LoopbackCallback(private val ports: List<Int> = ChatGptSignIn.CALLBACK_PORTS) {

    /** The port that was actually bound, so the redirect can name the same one. */
    var boundPort: Int = 0
        private set

    private var socket: ServerSocket? = null

    /**
     * Binds before the browser opens.
     *
     * Done as a separate step because the redirect URI has to be known — and has to be
     * the one that will actually be listening — before the authorize URL is built.
     */
    fun open(): Result<Int> {
        close()
        for (port in ports) {
            val bound = runCatching {
                ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).apply {
                    soTimeout = ACCEPT_TIMEOUT_MS
                }
            }.getOrNull()
            if (bound != null) {
                socket = bound
                boundPort = port
                return Result.success(port)
            }
        }
        return Result.failure(
            SignInFailed("Another app is using the sign-in port. Close it and try again."),
        )
    }

    /**
     * Waits for the redirect and returns the code it carries.
     *
     * The [state] the caller generated must come back unchanged. Anything else reaching
     * this port — a stale redirect, another app's probe — is answered and discarded
     * rather than treated as an approval.
     */
    suspend fun awaitCode(state: String): Result<String> = suspendCancellableCoroutine { continuation ->
        val listening = socket
        if (listening == null) {
            continuation.resume(Result.failure(SignInFailed("The sign-in listener was not open")))
            return@suspendCancellableCoroutine
        }
        // Accepting a connection blocks inside the operating system, where cancelling a
        // coroutine cannot reach it. Closing the socket is what unblocks it, so an
        // abandoned sign-in gives the port back at once rather than holding it until the
        // accept times out — and the issuer only redirects to a couple of fixed ports,
        // so one still held would make the next attempt fail for no visible reason.
        continuation.invokeOnCancellation { close() }
        val worker = Thread({
            val outcome = serve(listening, state)
            close()
            // A no-op if the sign-in was already abandoned, which is the usual way this
            // thread ends: the socket closed underneath it and the accept threw.
            if (continuation.isActive) continuation.resume(outcome)
        }, "autobile-signin-callback")
        worker.isDaemon = true
        worker.start()
    }

    /**
     * Serves the single redirect.
     *
     * The [state] the caller generated must come back unchanged. Anything else reaching
     * this port — a stale redirect, another app's probe — is answered and discarded
     * rather than treated as an approval.
     */
    private fun serve(listening: ServerSocket, state: String): Result<String> = try {
        listening.accept().use { connection ->
            val requestLine = connection.getInputStream().bufferedReader().readLine().orEmpty()
            val query = parseQuery(requestLine)
            val body: String
            val outcome: Result<String>
            when {
                query["error"] != null -> {
                    body = page(DENIED_TITLE, query["error_description"] ?: DENIED_BODY)
                    outcome = Result.failure(SignInFailed(query["error_description"] ?: "sign-in was declined"))
                }

                query["state"] != state -> {
                    body = page(PROBLEM_TITLE, MISMATCH_BODY)
                    outcome = Result.failure(SignInFailed("The sign-in reply did not match this attempt"))
                }

                query["code"].isNullOrBlank() -> {
                    body = page(PROBLEM_TITLE, NO_CODE_BODY)
                    outcome = Result.failure(SignInFailed("The sign-in reply carried no code"))
                }

                else -> {
                    body = page(DONE_TITLE, DONE_BODY)
                    outcome = Result.success(query.getValue("code"))
                }
            }
            connection.getOutputStream().use { stream ->
                stream.write(httpResponse(body).toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            outcome
        }
    } catch (e: SocketTimeoutException) {
        Result.failure(SignInFailed("The sign-in was not completed in time"))
    } catch (e: IOException) {
        Logx.w("Sign-in callback failed", e)
        Result.failure(SignInFailed(e.message ?: "the sign-in reply could not be read"))
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun httpResponse(body: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: text/html; charset=utf-8\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        append("Connection: close\r\n\r\n")
        append(body)
    }

    private fun page(title: String, detail: String): String =
        "<!doctype html><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title>" +
            "<body style=\"font-family:system-ui;margin:0;display:grid;place-items:center;height:100vh\">" +
            "<div style=\"text-align:center;padding:24px\"><h1 style=\"font-size:20px\">$title</h1>" +
            "<p style=\"color:#666\">$detail</p></div>"

    internal companion object {
        private const val ACCEPT_TIMEOUT_MS = 5 * 60 * 1000

        private const val DONE_TITLE = "Signed in"
        private const val DONE_BODY = "You can close this tab and go back to Autobile."
        private const val DENIED_TITLE = "Not signed in"
        private const val DENIED_BODY = "The sign-in was declined."
        private const val PROBLEM_TITLE = "Something went wrong"
        private const val MISMATCH_BODY = "This reply did not match the sign-in that was started."
        private const val NO_CODE_BODY = "The reply did not carry a sign-in code."

        /** Reads the query off a `GET /auth/callback?... HTTP/1.1` request line. */
        fun parseQuery(requestLine: String): Map<String, String> {
            val target = requestLine.split(' ').getOrNull(1) ?: return emptyMap()
            val query = target.substringAfter('?', "")
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=', "")
                if (name.isBlank()) return@mapNotNull null
                name to decode(pair.substringAfter('=', ""))
            }.toMap()
        }

        private fun decode(value: String): String =
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
