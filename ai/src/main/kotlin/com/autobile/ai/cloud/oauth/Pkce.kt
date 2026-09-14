package com.autobile.ai.cloud.oauth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The proof that the app finishing a sign-in is the app that started it.
 *
 * A phone cannot keep a client secret — anything shipped in the APK is readable by
 * anyone holding the APK — so the authorization code is bound to a secret generated
 * fresh for each attempt instead. Only the verifier's hash travels to the browser; the
 * verifier itself never leaves the process, which is what stops another app that
 * intercepted the redirect from redeeming the code.
 */
data class Pkce(val verifier: String, val challenge: String) {

    companion object {
        /** 32 bytes, the length the spec recommends once base64url-encoded. */
        private const val VERIFIER_BYTES = 32

        fun generate(random: SecureRandom = SecureRandom()): Pkce {
            val bytes = ByteArray(VERIFIER_BYTES).also(random::nextBytes)
            val verifier = encode(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier, encode(digest))
        }

        /** A random value the callback must echo back, so a stray redirect is ignored. */
        fun state(random: SecureRandom = SecureRandom()): String =
            encode(ByteArray(VERIFIER_BYTES).also(random::nextBytes))

        /**
         * Base64url without padding.
         *
         * Written out rather than taken from `android.util.Base64` so the encoding is
         * the same in a unit test as it is on a device, and so a wrong flag cannot make
         * a `+` or `=` reach a URL where it would be re-encoded and no longer match.
         */
        private fun encode(bytes: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var index = 0
            while (index + 2 < bytes.size) {
                val chunk = (bytes[index].toInt() and 0xFF shl 16) or
                    (bytes[index + 1].toInt() and 0xFF shl 8) or
                    (bytes[index + 2].toInt() and 0xFF)
                out.append(alphabet[chunk ushr 18 and 0x3F])
                out.append(alphabet[chunk ushr 12 and 0x3F])
                out.append(alphabet[chunk ushr 6 and 0x3F])
                out.append(alphabet[chunk and 0x3F])
                index += 3
            }
            when (bytes.size - index) {
                1 -> {
                    val chunk = bytes[index].toInt() and 0xFF shl 16
                    out.append(alphabet[chunk ushr 18 and 0x3F])
                    out.append(alphabet[chunk ushr 12 and 0x3F])
                }

                2 -> {
                    val chunk = (bytes[index].toInt() and 0xFF shl 16) or
                        (bytes[index + 1].toInt() and 0xFF shl 8)
                    out.append(alphabet[chunk ushr 18 and 0x3F])
                    out.append(alphabet[chunk ushr 12 and 0x3F])
                    out.append(alphabet[chunk ushr 6 and 0x3F])
                }
            }
            return out.toString()
        }
    }
}
