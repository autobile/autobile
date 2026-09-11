package com.autobile.runtime.compiler

import com.autobile.core.model.StepIntent

/**
 * The words the compiler uses when it has to name something itself.
 *
 * Compiling a demonstration produces text the user reads for as long as the automation
 * exists: its name, its goal, and the description of every step. When no AI runtime is
 * available — the common case on a phone with cloud assistance off — that text comes
 * from here rather than from a model, and it has to arrive in the user's own language.
 *
 * An interface rather than a Context because the compiler is exercised without a device,
 * and because the wording is written once into stored data. An automation keeps the
 * words it was compiled with, the same way a run keeps the words it was recorded with.
 */
interface CompilerVocabulary {

    /** Fallback name when the demonstration carries no label and no app to name it after. */
    fun newAutomation(): String

    /** Fallback goal: what was repeated, and where. */
    fun repeatTask(stepCount: Int, apps: List<String>): String

    /** What a single step does, in one short phrase. */
    fun step(intent: StepIntent, target: String): String

    /** How an app is referred to when a step describes what it is looking for. */
    fun theApp(name: String): String

    fun nothingRecorded(): String

    fun noRepeatableSteps(): String

    fun stepsNotUnderstood(): String
}

/**
 * The wording used when nothing supplies translations.
 *
 * Kept as the default so the compiler stays constructible in a test, and so a caller
 * that forgets to pass a vocabulary produces readable English rather than blank text.
 */
object EnglishCompilerVocabulary : CompilerVocabulary {
    override fun newAutomation(): String = "New automation"

    override fun repeatTask(stepCount: Int, apps: List<String>): String =
        "Repeat a $stepCount-step task in ${apps.joinToString(" and ")}"

    override fun step(intent: StepIntent, target: String): String = when (intent) {
        StepIntent.LAUNCH_APP -> "Open $target"
        StepIntent.NAVIGATE -> "Go to $target"
        StepIntent.SELECT_ITEM -> "Select $target"
        StepIntent.OPEN_TARGET -> "Open $target"
        StepIntent.READ_VALUE -> "Read $target"
        StepIntent.ENTER_TEXT -> "Enter text in $target"
        StepIntent.SET_OPTION -> "Set $target"
        StepIntent.SCROLL_TO -> "Scroll to $target"
        StepIntent.CONFIRM -> "Confirm $target"
        StepIntent.SEND -> "Send using $target"
        StepIntent.SHARE -> "Share via $target"
        StepIntent.SAVE -> "Save with $target"
        StepIntent.DELETE -> "Delete using $target"
        StepIntent.GO_BACK -> "Go back"
        StepIntent.GO_HOME -> "Go to the home screen"
        StepIntent.WAIT -> "Wait"
    }

    override fun theApp(name: String): String = "the $name app"

    override fun nothingRecorded(): String = "Nothing was recorded"

    override fun noRepeatableSteps(): String = "No repeatable steps were found in this demonstration"

    override fun stepsNotUnderstood(): String = "The recorded actions could not be turned into steps"
}
