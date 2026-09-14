package com.autobile.ai.cloud

import com.autobile.ai.cloud.oauth.LoopbackCallback
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The socket the sign-in redirect comes back over.
 *
 * A port left bound is not a cosmetic leak: the issuer only redirects to a fixed set of
 * ports, so one still held by an abandoned attempt makes the next sign-in fail with an
 * error that names neither cause nor remedy.
 */
class LoopbackCallbackTest {

    /**
     * A port of this test's own.
     *
     * Each case takes a different one: they run in one process, and a case that left a
     * socket behind would otherwise fail the next case instead of itself, which is the
     * hardest kind of failure to read.
     */
    private val port = nextPort.incrementAndGet()

    @Test
    fun `an abandoned sign-in gives the port straight back`() = runBlocking {
        val callback = LoopbackCallback(listOf(port))
        assertThat(callback.open().isSuccess).isTrue()

        val waiting = launch(Dispatchers.IO) { callback.awaitCode("state") }
        waitUntilBound()
        waiting.cancel()
        waiting.join()

        // Binding again is the only proof that matters: the operating system, not the
        // coroutine, decides whether the port is free.
        assertThat(becomesFree()).isTrue()
    }

    @Test
    fun `the redirect is answered and the code handed back`() = runBlocking {
        val callback = LoopbackCallback(listOf(port))
        assertThat(callback.open().isSuccess).isTrue()

        val result = async(Dispatchers.IO) { callback.awaitCode("st") }
        waitUntilBound()
        val page = request("GET /auth/callback?code=granted&state=st HTTP/1.1")

        assertThat(result.await().getOrNull()).isEqualTo("granted")
        // The browser is left on a page saying what happened, not a connection reset.
        assertThat(page).contains("200 OK")
        assertThat(page).contains("Signed in")
    }

    @Test
    fun `a reply from a different attempt is refused`() = runBlocking {
        val callback = LoopbackCallback(listOf(port))
        assertThat(callback.open().isSuccess).isTrue()

        val result = async(Dispatchers.IO) { callback.awaitCode("expected") }
        waitUntilBound()
        request("GET /auth/callback?code=granted&state=someone-elses HTTP/1.1")

        assertThat(result.await().isFailure).isTrue()
    }

    @Test
    fun `a refusal carries the reason the issuer gave`() = runBlocking {
        val callback = LoopbackCallback(listOf(port))
        assertThat(callback.open().isSuccess).isTrue()

        val result = async(Dispatchers.IO) { callback.awaitCode("st") }
        waitUntilBound()
        request("GET /auth/callback?error=access_denied&error_description=You%20said%20no HTTP/1.1")

        assertThat(result.await().exceptionOrNull()).hasMessageThat().isEqualTo("You said no")
    }

    private fun request(line: String): String =
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.getOutputStream().write("$line\r\nHost: localhost\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            BufferedReader(InputStreamReader(socket.getInputStream())).readText()
        }

    /** Waits for the listener to actually be accepting, not merely constructed. */
    private fun waitUntilBound() {
        repeat(POLLS) {
            if (!canBind()) return
            Thread.sleep(POLL_MS)
        }
    }

    /** Whether the port comes back within a moment, rather than at the accept timeout. */
    private fun becomesFree(): Boolean {
        repeat(POLLS) {
            if (canBind()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun canBind(): Boolean = runCatching {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
    }.isSuccess

    private companion object {
        const val POLLS = 100
        const val POLL_MS = 20L
        val nextPort = java.util.concurrent.atomic.AtomicInteger(45_530)
    }
}
