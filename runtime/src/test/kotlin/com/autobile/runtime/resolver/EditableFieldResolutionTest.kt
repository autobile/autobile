package com.autobile.runtime.resolver

import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.TargetSemantics
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Finding the field a typing step meant.
 *
 * A text field is usually the one thing on screen with no usable name: its contents are
 * the value, and its placeholder is gone the moment anything is typed. Left to a label
 * match, such a step reached a model that could not identify it either, and the run was
 * reported as waiting for a runtime that could decide — for a screen holding exactly one
 * place to type.
 */
class EditableFieldResolutionTest {

    private val resolver = ExecutionResolver(routerWith(ScriptedProvider()))

    private val nameless = TargetSemantics(intentLabel = "ScrollView", description = "")

    @Test
    fun `the only field on screen is the answer, without inference`() = runTest {
        val snapshot = screen(
            "com.example.notes",
            "Note",
            node("body", text = "12 September 09:12", editable = true, clickable = false),
            node("save", text = "Save"),
        )

        val resolution = resolver.resolve(nameless, snapshot, preferEditable = true)

        assertThat(resolution).isInstanceOf(Resolution.Found::class.java)
        val found = resolution as Resolution.Found
        assertThat(found.node.nodeId).isEqualTo("body")
        assertThat(found.tier).isEqualTo(RuntimeTier.DETERMINISTIC)
    }

    @Test
    fun `the focused field wins when a screen has several`() = runTest {
        val snapshot = screen(
            "com.example.notes",
            "Note",
            node("title", text = "", editable = true, clickable = false),
            node("body", text = "", editable = true, focused = true, clickable = false),
        )

        val resolution = resolver.resolve(nameless, snapshot, preferEditable = true)

        assertThat((resolution as Resolution.Found).node.nodeId).isEqualTo("body")
    }

    @Test
    fun `a step that does not type is not handed a field`() = runTest {
        val snapshot = screen("com.example.notes", "Note", node("body", text = "anything", editable = true, clickable = false))

        val resolution = resolver.resolve(nameless, snapshot, preferEditable = false)

        assertThat(resolution).isNotInstanceOf(Resolution.Found::class.java)
    }

    @Test
    fun `an ambiguous screen with no focus is left to reasoning`() = runTest {
        val snapshot = screen(
            "com.example.notes",
            "Note",
            node("title", text = "", editable = true, clickable = false),
            node("body", text = "", editable = true, clickable = false),
        )

        val resolution = resolver.resolve(nameless, snapshot, preferEditable = true, allowInference = false)

        assertThat(resolution).isNotInstanceOf(Resolution.Found::class.java)
    }
}
