package com.autobile.core.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Recognises a date or a time written into a field.
 *
 * This is the difference between an automation and a macro. Someone teaching "write the
 * date into a note" types today's date, and replaying that literally writes the day they
 * taught it, forever. What they meant was "today" — and the format they used says so
 * plainly enough to read without asking a model.
 *
 * Deliberately conservative. Only unambiguous shapes are recognised, because writing a
 * different value than the user typed is a worse failure than writing the same one.
 */
object TemporalText {

    private data class Shape(
        val pattern: String,
        val regex: Regex,
        val hasDate: Boolean,
        val hasTime: Boolean = pattern.contains("HH"),
    )

    /**
     * Ordered longest-first, so a value carrying both a date and a time is recognised as
     * both rather than matching the date half and losing the clock.
     */
    private val shapes = listOf(
        Shape("yyyy-MM-dd HH:mm", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}"""), hasDate = true),
        Shape("yyyy.MM.dd HH:mm", Regex("""\d{4}\.\d{2}\.\d{2} \d{2}:\d{2}"""), hasDate = true),
        Shape("yyyy/MM/dd HH:mm", Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}"""), hasDate = true),
        Shape("yy.MM.dd HH:mm", Regex("""\d{2}\.\d{2}\.\d{2} \d{2}:\d{2}"""), hasDate = true),
        Shape("yy-MM-dd HH:mm", Regex("""\d{2}-\d{2}-\d{2} \d{2}:\d{2}"""), hasDate = true),
        Shape("yyyy-MM-dd", Regex("""\d{4}-\d{2}-\d{2}"""), hasDate = true),
        Shape("yyyy.MM.dd", Regex("""\d{4}\.\d{2}\.\d{2}"""), hasDate = true),
        Shape("yyyy/MM/dd", Regex("""\d{4}/\d{2}/\d{2}"""), hasDate = true),
        Shape("yy.MM.dd", Regex("""\d{2}\.\d{2}\.\d{2}"""), hasDate = true),
        Shape("HH:mm", Regex("""\d{2}:\d{2}"""), hasDate = false),
    )

    /** What a piece of typed text turned out to be, when it was a moment in time. */
    data class Recognised(
        val pattern: String,
        /** Whole days between the moment written and the day it was written on. */
        val offsetDays: Int,
    )

    /**
     * Reads a typed value as a moment, relative to when it was typed.
     *
     * The offset is what makes this useful: someone writing tomorrow's date is not
     * writing today's, and the gap between what they typed and the day they typed it is
     * the only evidence of which they meant.
     */
    fun recognise(typed: String, writtenOn: LocalDateTime): Recognised? {
        val value = typed.trim()
        val shape = shapes.firstOrNull { it.regex.matches(value) } ?: return null
        if (!shape.hasDate) return Recognised(shape.pattern, offsetDays = 0)

        val formatter = DateTimeFormatter.ofPattern(shape.pattern, Locale.ROOT)
        val parsed = runCatching {
            if (shape.hasTime) {
                LocalDateTime.parse(value, formatter).toLocalDate()
            } else {
                LocalDate.parse(value, formatter)
            }
        }.getOrNull() ?: return null

        val days = parsed.toEpochDay() - writtenOn.toLocalDate().toEpochDay()
        // Anything further out than a fortnight is far more likely a date that means
        // itself — a deadline, a birthday — than one meant to move with the calendar.
        if (days !in -MAX_OFFSET_DAYS..MAX_OFFSET_DAYS) return null
        return Recognised(shape.pattern, offsetDays = days.toInt())
    }

    private const val MAX_OFFSET_DAYS = 14L
}
