package com.autobile.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/**
 * Reading a typed value as a moment rather than as characters.
 *
 * Someone teaching "write the date into a note" types today's date. Replaying that
 * literally writes the day they taught it, for ever, which is the behaviour of a
 * recorder. The format they used says what they meant clearly enough to read.
 */
class TemporalTextTest {

    private val taughtOn = LocalDateTime.of(2026, 9, 12, 14, 56)

    @Test
    fun `a date and time typed today is recognised as now`() {
        val recognised = TemporalText.recognise("26.09.12 14:56", taughtOn)

        assertThat(recognised).isNotNull()
        assertThat(recognised!!.offsetDays).isEqualTo(0)
        assertThat(recognised.pattern).isEqualTo("yy.MM.dd HH:mm")
    }

    @Test
    fun `yesterday's date keeps its distance`() {
        val recognised = TemporalText.recognise("2026-09-11", taughtOn)

        assertThat(recognised!!.offsetDays).isEqualTo(-1)
    }

    @Test
    fun `a time on its own is recognised`() {
        assertThat(TemporalText.recognise("09:30", taughtOn)!!.pattern).isEqualTo("HH:mm")
    }

    @Test
    fun `a date far from the demonstration is left alone`() {
        // A deadline or a birthday means itself. Moving it with the calendar would write
        // a different value than the user asked for, which is worse than writing theirs.
        assertThat(TemporalText.recognise("2027-01-01", taughtOn)).isNull()
    }

    @Test
    fun `ordinary text is not a moment`() {
        assertThat(TemporalText.recognise("Daily sales report", taughtOn)).isNull()
        assertThat(TemporalText.recognise("12345", taughtOn)).isNull()
        assertThat(TemporalText.recognise("", taughtOn)).isNull()
    }

    @Test
    fun `a date and time is never read as the date alone`() {
        // Matching the date half and dropping the clock would write the right day at the
        // wrong hour, which is the kind of near-miss nobody notices until it matters.
        val recognised = TemporalText.recognise("2026-09-12 14:56", taughtOn)

        assertThat(recognised!!.pattern).isEqualTo("yyyy-MM-dd HH:mm")
    }
}
