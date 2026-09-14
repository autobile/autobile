package com.autobile.ai.cloud

import com.autobile.ai.cloud.oauth.CloudSession

/**
 * What proves a cloud request may be made.
 *
 * Services bill in two different ways and prove entitlement in two different ways with
 * it: a key identifies a balance, a session identifies a person with a subscription.
 * Keeping both behind one type means the request path does not care which the user
 * chose, and a service that accepts only one of them cannot be handed the other.
 */
sealed interface CloudCredential {

    /** Nothing has been supplied yet. */
    data object None : CloudCredential

    /** A key the user pasted, sent on every request. */
    data class Key(val value: String) : CloudCredential

    /**
     * A subscription the user signed in to, renewed as it expires.
     *
     * [requestId] identifies one request to the service. It is what cache affinity is
     * derived from, so a run that asks twice about the same screen is not charged twice
     * for reading it.
     */
    data class Session(
        val accessToken: String,
        val accountId: String,
        val requestId: String = "",
    ) : CloudCredential

    val isPresent: Boolean
        get() = when (this) {
            None -> false
            is Key -> value.isNotBlank()
            is Session -> accessToken.isNotBlank()
        }

    companion object {
        private fun requestId(): String = java.util.UUID.randomUUID().toString()

        fun of(service: CloudService, apiKey: String, session: CloudSession): CloudCredential = when {
            service.authMethod == CloudAuthMethod.SIGN_IN ->
                if (session.isEmpty) {
                    None
                } else {
                    Session(session.accessToken, session.accountId, requestId())
                }

            apiKey.isBlank() -> None
            else -> Key(apiKey)
        }
    }
}

/** How a service expects to be proved to. */
enum class CloudAuthMethod {
    /** A key pasted into a field. */
    API_KEY,

    /** A browser sign-in that bills the user's own subscription. */
    SIGN_IN,
}
