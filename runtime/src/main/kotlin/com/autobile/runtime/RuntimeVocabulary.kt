package com.autobile.runtime

/**
 * The words a run uses to explain itself.
 *
 * Every string here ends up in the execution timeline the user reads when an automation
 * did not do what they expected — which is exactly the moment they least want to be
 * reading a language they do not speak. Kept as an interface for the same reasons as the
 * compiler's vocabulary: the runtime is exercised without a device, and the wording is
 * written into stored history, so a run keeps the words it was recorded with.
 *
 * This deliberately does not cover diagnostic output. Log lines and model prompts keep a
 * fixed English wording so they stay greppable and so the same screen is judged the same
 * way regardless of where the phone is.
 */
interface RuntimeVocabulary {

    // Lifecycle.
    fun taskCreated(): String

    // Why a step was allowed, refused, or put to the user.
    fun automationStopped(): String
    fun categoryBlocked(category: String): String
    fun appObserveOnly(): String
    fun suspendedPendingReview(): String
    fun lowRiskAction(): String
    fun riskDecision(verdict: String, reason: String): String

    // How a run ended.
    fun ownScreenInFront(): String
    fun couldNotComplete(step: String): String
    fun awaitingRuntime(step: String): String
    fun stopped(): String
    fun stepFailed(): String
    fun screenProtected(): String

    // What an action could not do.
    fun couldNotDetermineText(): String
    fun actionNeedsAnElement(): String
    fun unsupportedAction(): String

    // Whether the screen matched what the step expected.
    fun noValidationRequired(): String
    fun noExpectationDeclared(): String
    fun wrongApp(expected: String, actual: String): String
    fun screenMissing(items: String): String
    fun screenShowsForbidden(items: String): String
    fun expectedScreenConfirmed(): String

    // Whether a value read off the screen was the value expected.
    fun noValueConstraints(): String
    fun noValueRead(): String
    fun emptyValueAccepted(): String
    fun valueFormatMismatch(): String
    fun valueNotNumeric(): String
    fun valueBelowMinimum(value: String): String
    fun valueAboveMaximum(value: String): String
    fun fieldNotOnScreen(field: String): String
    fun fieldRead(field: String): String

    // Whether the goal itself was reached.
    fun outcomeNotVerified(): String
    fun noPostConditions(): String
    fun goalConfirmed(): String
}

/** The wording used when nothing supplies translations. */
object EnglishRuntimeVocabulary : RuntimeVocabulary {
    override fun taskCreated() = "Task created"

    override fun automationStopped() = "Automation is stopped"
    override fun categoryBlocked(category: String) = "$category apps are blocked"
    override fun appObserveOnly() = "this app is set to observe only"
    override fun suspendedPendingReview() = "this automation is suspended until you review it"
    override fun lowRiskAction() = "low risk action"
    override fun riskDecision(verdict: String, reason: String) = "$verdict: $reason"

    override fun ownScreenInFront() =
        "Autobile's own screen is in front, so this step has nothing to act on"
    override fun couldNotComplete(step: String) = "Could not complete \"$step\""
    override fun awaitingRuntime(step: String) = "Waiting for a runtime that can decide \"$step\""
    override fun stopped() = "Stopped"
    override fun stepFailed() = "Step failed"
    override fun screenProtected() = "This screen is protected and cannot be read"

    override fun couldNotDetermineText() = "Could not determine what to type"
    override fun actionNeedsAnElement() = "Action does not operate on an element"
    override fun unsupportedAction() = "Unsupported action"

    override fun noValidationRequired() = "no validation required"
    override fun noExpectationDeclared() = "no expectation declared"
    override fun wrongApp(expected: String, actual: String) =
        "expected $expected but the foreground app is $actual"
    override fun screenMissing(items: String) = "screen does not show $items"
    override fun screenShowsForbidden(items: String) = "screen shows $items"
    override fun expectedScreenConfirmed() = "expected screen confirmed"

    override fun noValueConstraints() = "value validation has no constraints"
    override fun noValueRead() = "no value was read"
    override fun emptyValueAccepted() = "empty value accepted"
    override fun valueFormatMismatch() = "value does not match the expected format"
    override fun valueNotNumeric() = "value is not numeric"
    override fun valueBelowMinimum(value: String) = "value $value is below the expected minimum"
    override fun valueAboveMaximum(value: String) = "value $value is above the expected maximum"
    override fun fieldNotOnScreen(field: String) = "the screen does not show a \"$field\" field"
    override fun fieldRead(field: String) = "$field read successfully"

    override fun outcomeNotVerified() = "the outcome could not be verified"
    override fun noPostConditions() = "no post-conditions declared"
    override fun goalConfirmed() = "goal confirmed"
}
