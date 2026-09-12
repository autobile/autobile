package com.autobile.runtime.agent

import com.autobile.ai.router.RoutingAttempt
import com.autobile.core.model.EscalationReason
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.RuntimeTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a failed run says about the reasoning it attempted.
 *
 * The timeline used to show which runtime was chosen and then nothing at all — enough to
 * see that reasoning had been tried, never enough to see why it had not worked. Every
 * attempt already carried its tier, whether it was skipped and why, the confidence it
 * returned and what went wrong, and all of it was thrown away.
 */
class RoutingDiagnosticsTest {

    @Test
    fun `each runtime says what it did with the question`() {
        val line = describeRoutingExhaustion(
            "element-match",
            listOf(
                RoutingAttempt(RuntimeTier.DEVICE_AI, "nano", skipped = false, confidence = 0.2f),
                RoutingAttempt(
                    tier = RuntimeTier.CLOUD_LIGHT,
                    providerId = "cloud",
                    skipped = true,
                    reason = EscalationReason.RUNTIME_UNAVAILABLE,
                ),
            ),
        )

        assertThat(line).contains("element match")
        assertThat(line).contains("0.20")
        assertThat(line).contains("skipped")
    }

    @Test
    fun `an error is named rather than left blank`() {
        val line = describeRoutingExhaustion(
            "element-match",
            listOf(RoutingAttempt(RuntimeTier.DEVICE_AI, "nano", skipped = false, errorKind = InferenceErrorKind.BUSY)),
        )

        assertThat(line).contains("busy")
    }

    @Test
    fun `having nothing to try is itself worth saying`() {
        assertThat(describeRoutingExhaustion("element-match", emptyList())).contains("no runtime")
    }
}
