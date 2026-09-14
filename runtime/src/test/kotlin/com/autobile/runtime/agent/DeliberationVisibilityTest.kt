package com.autobile.runtime.agent

import com.autobile.core.model.RuntimeTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Saying that the agent is thinking, while it is thinking.
 *
 * A reasoning call runs for seconds — longer over a network — and the step description
 * does not change while it does. The run then looks stopped at exactly the moment it is
 * doing the most work, which is when someone reaches for the stop button and concludes
 * the product is broken.
 *
 * The state has to end up cleared on every path out of a call, not only the successful
 * one: a banner that says the agent is still thinking after it has given up is a worse
 * lie than saying nothing.
 */
class DeliberationVisibilityTest {

    private val running = AgentActivity.Running(
        taskId = "t1",
        skillName = "Write a note",
        stepDescription = "Enter text in Title",
        stepIndex = 1,
        totalSteps = 4,
    )

    @Test
    fun `a run says nothing about thinking until it starts`() {
        assertThat(running.deliberating).isNull()
    }

    @Test
    fun `the tier being consulted is carried on the run`() {
        val thinking = running.copy(deliberating = RuntimeTier.CLOUD_ADVANCED)

        assertThat(thinking.deliberating).isEqualTo(RuntimeTier.CLOUD_ADVANCED)
        // Everything else about the step is unchanged: this reports on the same step,
        // it does not replace it.
        assertThat(thinking.stepDescription).isEqualTo(running.stepDescription)
        assertThat(thinking.stepIndex).isEqualTo(running.stepIndex)
    }

    @Test
    fun `whether it left the phone is answerable from the state alone`() {
        assertThat(running.copy(deliberating = RuntimeTier.DEVICE_AI).deliberating?.isCloud).isFalse()
        assertThat(running.copy(deliberating = RuntimeTier.LOCAL_LLM).deliberating?.isCloud).isFalse()
        assertThat(running.copy(deliberating = RuntimeTier.CLOUD_LIGHT).deliberating?.isCloud).isTrue()
        assertThat(running.copy(deliberating = RuntimeTier.CLOUD_ADVANCED).deliberating?.isCloud).isTrue()
    }

    @Test
    fun `an escalation replaces the tier rather than stacking another`() {
        val escalated = running
            .copy(deliberating = RuntimeTier.DEVICE_AI)
            .copy(deliberating = RuntimeTier.CLOUD_LIGHT)

        assertThat(escalated.deliberating).isEqualTo(RuntimeTier.CLOUD_LIGHT)
    }

    @Test
    fun `clearing it leaves the rest of the run intact`() {
        val cleared = running.copy(deliberating = RuntimeTier.CLOUD_LIGHT).copy(deliberating = null)

        assertThat(cleared).isEqualTo(running)
    }
}
