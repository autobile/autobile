package com.autobile.runtime.agent

import com.autobile.runtime.agent.AppNameMatcher.Candidate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNameMatcherTest {
    private val installed = listOf(
        Candidate("com.easybrain.sudoku.android", "Sudoku.com"),
        Candidate("com.samsung.android.app.notes", "Samsung Notes"),
        Candidate("com.example.notes.addons", "Samsung Notes Add-ons"),
        Candidate("com.kakao.talk", "카카오톡"),
        Candidate("com.example.symbols", "★"),
    )

    @Test
    fun `an exact label wins`() {
        assertThat(AppNameMatcher.bestMatch("카카오톡", installed)).isEqualTo("com.kakao.talk")
    }

    @Test
    fun `a partial name finds the app that contains it`() {
        assertThat(AppNameMatcher.bestMatch("sudoku", installed)).isEqualTo("com.easybrain.sudoku.android")
    }

    @Test
    fun `the most specific of several containing labels is chosen`() {
        assertThat(AppNameMatcher.bestMatch("Samsung Notes", installed)).isEqualTo("com.samsung.android.app.notes")
    }

    @Test
    fun `a package name is accepted as it is`() {
        assertThat(AppNameMatcher.bestMatch("com.kakao.talk", installed)).isEqualTo("com.kakao.talk")
    }

    @Test
    fun `a label with no letters never matches everything`() {
        assertThat(AppNameMatcher.bestMatch("Weather", installed)).isNull()
    }

    @Test
    fun `a blank name matches nothing`() {
        assertThat(AppNameMatcher.bestMatch("  ", installed)).isNull()
    }
}
