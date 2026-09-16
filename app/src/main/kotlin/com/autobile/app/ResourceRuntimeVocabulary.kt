package com.autobile.app

import android.content.Context
import com.autobile.runtime.RuntimeVocabulary

/**
 * How a run explains itself, in the language the phone is set to.
 *
 * These strings land in the execution timeline, which is what someone opens when an
 * automation did not do what they expected. Resolved as the run happens and stored with
 * it, so a record keeps the wording it was written with.
 */
class ResourceRuntimeVocabulary(private val context: Context) : RuntimeVocabulary {

    override fun taskCreated(): String = context.getString(R.string.run_task_created)

    override fun riskMovesMoney(): String = context.getString(R.string.risk_why_moves_money)
    override fun riskPurchase(): String = context.getString(R.string.risk_why_purchase)
    override fun riskDelete(): String = context.getString(R.string.risk_why_delete)
    override fun riskMessageSend(): String = context.getString(R.string.risk_why_message_send)
    override fun riskExternalPost(): String = context.getString(R.string.risk_why_external_post)
    override fun riskCancellation(): String = context.getString(R.string.risk_why_cancellation)
    override fun riskPermissionChange(): String = context.getString(R.string.risk_why_permission_change)
    override fun riskAccountChange(): String = context.getString(R.string.risk_why_account_change)
    override fun riskSubscription(): String = context.getString(R.string.risk_why_subscription)
    override fun riskBooking(): String = context.getString(R.string.risk_why_booking)
    override fun riskAppSetToAsk(): String = context.getString(R.string.risk_why_app_set_to_ask)
    override fun riskConfirmationRequired(): String = context.getString(R.string.risk_why_confirmation_required)

    override fun automationStopped(): String = context.getString(R.string.run_automation_stopped)
    override fun categoryBlocked(category: String): String =
        context.getString(R.string.run_category_blocked, category)
    override fun appObserveOnly(): String = context.getString(R.string.run_app_observe_only)
    override fun automationObserveOnly(): String = context.getString(R.string.run_automation_observe_only)
    override fun lowRiskAction(): String = context.getString(R.string.run_low_risk)
    override fun riskDecision(verdict: String, reason: String): String =
        context.getString(R.string.run_risk_decision, verdict, reason)

    override fun ownScreenInFront(): String = context.getString(R.string.run_own_screen)
    override fun couldNotComplete(step: String): String =
        context.getString(R.string.run_could_not_complete, step)
    override fun awaitingRuntime(step: String): String =
        context.getString(R.string.run_awaiting_runtime, step)

    override fun stopped(): String = context.getString(R.string.run_stopped)
    override fun stepFailed(): String = context.getString(R.string.run_step_failed)
    override fun screenProtected(): String = context.getString(R.string.run_screen_protected)

    override fun couldNotDetermineText(): String = context.getString(R.string.run_no_text)
    override fun actionNeedsAnElement(): String = context.getString(R.string.run_action_needs_element)
    override fun unsupportedAction(): String = context.getString(R.string.run_unsupported_action)

    override fun noValidationRequired(): String = context.getString(R.string.validate_none)
    override fun noExpectationDeclared(): String = context.getString(R.string.validate_no_expectation)
    override fun wrongApp(expected: String, actual: String): String =
        context.getString(R.string.validate_wrong_app, expected, actual)
    override fun screenMissing(items: String): String =
        context.getString(R.string.validate_screen_missing, items)
    override fun screenShowsForbidden(items: String): String =
        context.getString(R.string.validate_screen_forbidden, items)
    override fun expectedScreenConfirmed(): String = context.getString(R.string.validate_screen_confirmed)

    override fun noValueConstraints(): String = context.getString(R.string.validate_no_constraints)
    override fun noValueRead(): String = context.getString(R.string.validate_no_value)
    override fun emptyValueAccepted(): String = context.getString(R.string.validate_empty_value)
    override fun valueFormatMismatch(): String = context.getString(R.string.validate_format_mismatch)
    override fun valueNotNumeric(): String = context.getString(R.string.validate_not_numeric)
    override fun valueBelowMinimum(value: String): String =
        context.getString(R.string.validate_below_minimum, value)
    override fun valueAboveMaximum(value: String): String =
        context.getString(R.string.validate_above_maximum, value)
    override fun fieldNotOnScreen(field: String): String =
        context.getString(R.string.validate_field_missing, field)
    override fun fieldRead(field: String): String = context.getString(R.string.validate_field_read, field)

    override fun outcomeNotVerified(): String = context.getString(R.string.validate_unverified)
    override fun noPostConditions(): String = context.getString(R.string.validate_no_post_conditions)
    override fun goalConfirmed(): String = context.getString(R.string.validate_goal_confirmed)
}
