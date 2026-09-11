package com.autobile.app

import android.content.Context
import com.autobile.core.model.StepIntent
import com.autobile.runtime.compiler.CompilerVocabulary

/**
 * The compiler's fallback wording, in the language the phone is set to.
 *
 * Resolved at compile time rather than at display time. What this produces is written
 * into the automation and stays there: an automation that renamed its own steps when the
 * phone changed language would no longer be a record of what the user agreed to.
 */
class ResourceCompilerVocabulary(private val context: Context) : CompilerVocabulary {

    override fun newAutomation(): String = context.getString(R.string.compiled_new_automation)

    override fun repeatTask(stepCount: Int, apps: List<String>): String = context.getString(
        R.string.compiled_repeat_task,
        stepCount,
        apps.joinToString(context.getString(R.string.compiled_app_separator)),
    )

    override fun step(intent: StepIntent, target: String): String = when (intent) {
        StepIntent.LAUNCH_APP -> context.getString(R.string.compiled_step_launch_app, target)
        StepIntent.NAVIGATE -> context.getString(R.string.compiled_step_navigate, target)
        StepIntent.SELECT_ITEM -> context.getString(R.string.compiled_step_select_item, target)
        StepIntent.OPEN_TARGET -> context.getString(R.string.compiled_step_open_target, target)
        StepIntent.READ_VALUE -> context.getString(R.string.compiled_step_read_value, target)
        StepIntent.ENTER_TEXT -> context.getString(R.string.compiled_step_enter_text, target)
        StepIntent.SET_OPTION -> context.getString(R.string.compiled_step_set_option, target)
        StepIntent.SCROLL_TO -> context.getString(R.string.compiled_step_scroll_to, target)
        StepIntent.CONFIRM -> context.getString(R.string.compiled_step_confirm, target)
        StepIntent.SEND -> context.getString(R.string.compiled_step_send, target)
        StepIntent.SHARE -> context.getString(R.string.compiled_step_share, target)
        StepIntent.SAVE -> context.getString(R.string.compiled_step_save, target)
        StepIntent.DELETE -> context.getString(R.string.compiled_step_delete, target)
        StepIntent.GO_BACK -> context.getString(R.string.compiled_step_go_back)
        StepIntent.GO_HOME -> context.getString(R.string.compiled_step_go_home)
        StepIntent.WAIT -> context.getString(R.string.compiled_step_wait)
    }

    override fun theApp(name: String): String = context.getString(R.string.compiled_the_app, name)

    override fun nothingRecorded(): String = context.getString(R.string.compiled_nothing_recorded)

    override fun noRepeatableSteps(): String = context.getString(R.string.compiled_no_repeatable_steps)

    override fun stepsNotUnderstood(): String = context.getString(R.string.compiled_steps_not_understood)
}
