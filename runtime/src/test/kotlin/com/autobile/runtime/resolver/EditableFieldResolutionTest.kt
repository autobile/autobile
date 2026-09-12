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
    fun `a label match that cannot accept text is refused`() = runTest {
        // The recorded target was the layout scrolling a note, so its label is
        // "ScrollView" and a text match finds it exactly. Acting on it dispatches text
        // at a layout, which is refused — with a field on the same screen all along.
        val snapshot = screen(
            "com.example.notes",
            "Note",
            node("wrap", text = "ScrollView", clickable = false, className = "android.widget.ScrollView"),
            node("body", text = "", editable = true, focused = true, clickable = false),
        )

        val resolution = resolver.resolve(nameless, snapshot, requireEditable = true)

        assertThat((resolution as Resolution.Found).node.nodeId).isEqualTo("body")
    }

    @Test
    fun `a tap step still matches the label it was taught`() = runTest {
        val snapshot = screen(
            "com.example.notes",
            "Note",
            node("wrap", text = "ScrollView", clickable = true),
            node("body", text = "", editable = true, clickable = false),
        )

        val resolution = resolver.resolve(nameless, snapshot, requireEditable = false)

        assertThat((resolution as Resolution.Found).node.nodeId).isEqualTo("wrap")
    }

    @Test
    fun `the only field on screen is the answer, without inference`() = runTest {
        val snapshot = screen(
            "com.example.notes",
            "Note",
            node("body", text = "12 September 09:12", editable = true, clickable = false),
            node("save", text = "Save"),
        )

        val resolution = resolver.resolve(nameless, snapshot, requireEditable = true)

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

        val resolution = resolver.resolve(nameless, snapshot, requireEditable = true)

        assertThat((resolution as Resolution.Found).node.nodeId).isEqualTo("body")
    }

    @Test
    fun `a step that does not type is not handed a field`() = runTest {
        val snapshot = screen("com.example.notes", "Note", node("body", text = "anything", editable = true, clickable = false))

        val resolution = resolver.resolve(nameless, snapshot, requireEditable = false)

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

        val resolution = resolver.resolve(nameless, snapshot, requireEditable = true, allowInference = false)

        assertThat(resolution).isNotInstanceOf(Resolution.Found::class.java)
    }
}
