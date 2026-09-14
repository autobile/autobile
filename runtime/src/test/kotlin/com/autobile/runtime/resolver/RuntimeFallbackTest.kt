package com.autobile.runtime.resolver

import com.autobile.ai.task.ElementMatch
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.TargetSemantics
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What happens when a runtime cannot answer.
 *
 * These are the release gates about degradation rather than features: a phone with no
 * on-device model, a model busy for a moment, a network that has gone away. Each has a
 * defined behaviour in the specification, and each was implemented without anything
 * asserting it stayed that way.
 */
@RunWith(RobolectricTestRunner::class)
class RuntimeFallbackTest {

    private val target = TargetSemantics(intentLabel = "Daily totals", description = "the daily totals row")

    /** A screen whose element cannot be matched by id or label, so reasoning is needed. */
    private fun ambiguousScreen() = screen(
        "com.example.business",
        "Reports",
        node("a", text = "Totals for the week", clickable = true),
        node("b", text = "Totals for the month", clickable = true),
    )

    private val matched = ElementMatch(index = 0, confidence = 0.9f, reason = "weekly totals")

    @Test
    fun `a phone with no on-device model reaches the cloud instead`() = runTest {
        val device = ScriptedProvider(RuntimeTier.DEVICE_AI, "nano", available = false)
        val cloud = ScriptedProvider(RuntimeTier.CLOUD_LIGHT, "cloud")
            .answerWith("element-match", matched)

        val resolution = ExecutionResolver(routerWith(device, cloud))
            .resolve(target, ambiguousScreen(), localOnly = false)

        assertThat(resolution).isInstanceOf(Resolution.Found::class.java)
        assertThat((resolution as Resolution.Found).tier).isEqualTo(RuntimeTier.CLOUD_LIGHT)
        assertThat(device.requestedLabels).isEmpty()
    }

    @Test
    fun `a model busy for a moment is retried rather than escalated`() = runTest {
        // Busy is usually busy for milliseconds. Escalating to the network over it would
        // spend a user's money and their privacy on a wait that resolves itself.
        val device = ScriptedProvider(RuntimeTier.DEVICE_AI, "nano")
            .failOnceWith("element-match", InferenceErrorKind.BUSY)
            .answerWith("element-match", matched)
        val cloud = ScriptedProvider(RuntimeTier.CLOUD_LIGHT, "cloud")
            .answerWith("element-match", matched)

        val resolution = ExecutionResolver(routerWith(device, cloud))
            .resolve(target, ambiguousScreen(), localOnly = false)

        assertThat((resolution as Resolution.Found).tier).isEqualTo(RuntimeTier.DEVICE_AI)
        assertThat(cloud.requestedLabels).isEmpty()
    }

    @Test
    fun `a model that stays busy gives way to the cloud`() = runTest {
        val device = ScriptedProvider(RuntimeTier.DEVICE_AI, "nano")
            .failWith("element-match", InferenceErrorKind.BUSY)
        val cloud = ScriptedProvider(RuntimeTier.CLOUD_LIGHT, "cloud")
            .answerWith("element-match", matched)

        val resolution = ExecutionResolver(routerWith(device, cloud))
            .resolve(target, ambiguousScreen(), localOnly = false)

        assertThat((resolution as Resolution.Found).tier).isEqualTo(RuntimeTier.CLOUD_LIGHT)
    }

    @Test
    fun `Nano blocked behind the controlled app gives way to the cloud`() = runTest {
        val device = ScriptedProvider(RuntimeTier.DEVICE_AI, "nano")
            .failWith("element-match", InferenceErrorKind.DEVICE_BACKGROUND_RESTRICTED)
        val cloud = ScriptedProvider(RuntimeTier.CLOUD_LIGHT, "cloud")
            .answerWith("element-match", matched)

        val resolution = ExecutionResolver(routerWith(device, cloud))
            .resolve(target, ambiguousScreen(), localOnly = false)

        assertThat((resolution as Resolution.Found).tier).isEqualTo(RuntimeTier.CLOUD_LIGHT)
        assertThat(cloud.requestedLabels).containsExactly("element-match")
    }

    @Test
    fun `a network that has gone away leaves the step unresolved, not wrongly resolved`() = runTest {
        val device = ScriptedProvider(RuntimeTier.DEVICE_AI, "nano", available = false)
        val cloud = ScriptedProvider(RuntimeTier.CLOUD_LIGHT, "cloud")
            .failWith("element-match", InferenceErrorKind.NETWORK)

        val resolution = ExecutionResolver(routerWith(device, cloud))
            .resolve(target, ambiguousScreen(), localOnly = false)

        assertThat(resolution).isNotInstanceOf(Resolution.Found::class.java)
    }

    @Test
    fun `keeping everything local never reaches the cloud, even when nothing local can answer`() = runTest {
        val device = ScriptedProvider(RuntimeTier.DEVICE_AI, "nano", available = false)
        val cloud = ScriptedProvider(RuntimeTier.CLOUD_LIGHT, "cloud")
            .answerWith("element-match", matched)

        val resolution = ExecutionResolver(routerWith(device, cloud))
            .resolve(target, ambiguousScreen(), localOnly = true)

        assertThat(resolution).isNotInstanceOf(Resolution.Found::class.java)
        assertThat(cloud.requestedLabels).isEmpty()
    }

    @Test
    fun `an answer that will not parse escalates instead of being half-used`() = runTest {
        // The platform's typed-output feature is not relied on anywhere: every tier is
        // asked for constrained text and the reply is extracted, parsed and validated
        // here. What matters is that a reply failing that is treated as no answer at
        // all, rather than a partly-filled object reaching a step that presses buttons.
        val device = ScriptedProvider(RuntimeTier.DEVICE_AI, "nano")
            .failWith("element-match", InferenceErrorKind.PARSE_FAILED)
        val cloud = ScriptedProvider(RuntimeTier.CLOUD_LIGHT, "cloud")
            .answerWith("element-match", matched)

        val resolution = ExecutionResolver(routerWith(device, cloud))
            .resolve(target, ambiguousScreen(), localOnly = false)

        assertThat((resolution as Resolution.Found).tier).isEqualTo(RuntimeTier.CLOUD_LIGHT)
    }
}
