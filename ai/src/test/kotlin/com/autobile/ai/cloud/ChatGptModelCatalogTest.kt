package com.autobile.ai.cloud

import com.autobile.core.model.InferenceErrorKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatGptModelCatalogTest {
    @Test
    fun `catalog keeps supported API models in server priority order`() {
        val catalog = ChatGptModelCatalog.parse(
            """{"models":[
                {"slug":"gpt-current","supported_in_api":true},
                {"slug":"gpt-hidden","supported_in_api":false},
                {"slug":"gpt-fallback"}
            ]}""",
        )

        assertThat(catalog?.models?.map { it.slug }).containsExactly("gpt-current", "gpt-fallback").inOrder()
        assertThat(catalog?.select("gpt-fallback")).isEqualTo("gpt-fallback")
        assertThat(catalog?.select("retired-model")).isEqualTo("gpt-current")
    }

    @Test
    fun `vision selection avoids a model that explicitly rejects images`() {
        val catalog = ChatGptModelCatalog.parse(
            """{"models":[
                {"slug":"text-only","supported_in_api":true,"input_modalities":["text"]},
                {"slug":"vision","supported_in_api":true,"input_modalities":["text","image"]}
            ]}""",
        )

        assertThat(catalog?.select("text-only", needsVision = false)).isEqualTo("text-only")
        assertThat(catalog?.select("text-only", needsVision = true)).isEqualTo("vision")
    }

    @Test
    fun `vision selection fails closed when every advertised model is text only`() {
        val catalog = ChatGptModelCatalog.parse(
            """{"models":[
                {"slug":"text-only","supported_in_api":true,"supports_image_input":false}
            ]}""",
        )

        assertThat(catalog?.select("text-only", needsVision = true)).isEmpty()
    }

    @Test
    fun `light and advanced vision tiers choose different catalog fallbacks`() {
        val catalog = ChatGptModelCatalog.parse(
            """{"models":[
                {"slug":"vision-fast","supported_in_api":true,"input_modalities":["text","image"]},
                {"slug":"vision-deep","supported_in_api":true,"input_modalities":["text","image"]}
            ]}""",
        )!!

        assertThat(catalog.select("missing", needsVision = true, preferAdvanced = false))
            .isEqualTo("vision-fast")
        assertThat(catalog.select("missing", needsVision = true, preferAdvanced = true))
            .isEqualTo("vision-deep")
    }

    @Test
    fun `invalid or empty catalogs do not replace a configured model`() {
        assertThat(ChatGptModelCatalog.parse("not json")).isNull()
        assertThat(ChatGptModelCatalog.parse("""{"models":[]}""")).isNull()
    }

    @Test
    fun `bad request only means unsupported when the response names a modality`() {
        assertThat(CloudHttpErrorClassifier.classify(400, "image modality is unsupported"))
            .isEqualTo(InferenceErrorKind.UNSUPPORTED)
        assertThat(CloudHttpErrorClassifier.classify(400, "model does not exist"))
            .isEqualTo(InferenceErrorKind.UNAVAILABLE)
        assertThat(CloudHttpErrorClassifier.classify(400, "invalid request"))
            .isEqualTo(InferenceErrorKind.UNKNOWN)
    }
}
