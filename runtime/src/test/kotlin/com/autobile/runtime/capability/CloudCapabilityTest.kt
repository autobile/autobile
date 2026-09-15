package com.autobile.runtime.capability

import com.autobile.ai.cloud.CloudConfig
import com.autobile.ai.cloud.CloudService
import com.autobile.ai.cloud.oauth.CloudSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudCapabilityTest {
    @Test
    fun `a ChatGPT subscription session is recognized without an API key`() {
        val capability = CloudConfig(
            enabled = true,
            service = CloudService.CHATGPT,
            endpoint = CloudService.CHATGPT.endpoint,
            session = CloudSession(accessToken = "access", refreshToken = "refresh"),
            allowImages = true,
        ).toCapability()

        assertThat(capability.configured).isTrue()
        assertThat(capability.userConsented).isTrue()
        assertThat(capability.visionSupported).isTrue()
        assertThat(capability.isUsable).isTrue()
    }

    @Test
    fun `cloud consent without a credential remains unavailable`() {
        val capability = CloudConfig(
            enabled = true,
            service = CloudService.CHATGPT,
            endpoint = CloudService.CHATGPT.endpoint,
            allowImages = true,
        ).toCapability()

        assertThat(capability.configured).isFalse()
        assertThat(capability.isUsable).isFalse()
    }
}
