package com.autobile.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.autobile.app.R
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.DeviceRuntimeProfile
import com.autobile.core.model.ExecutabilityState
import com.autobile.core.model.ExecutionEventType
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.RiskCategory
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TaskState
import com.autobile.core.model.TriggerSpec

/**
 * Every enum the interface shows, mapped to a translated string.
 *
 * The domain deliberately carries no display text. Keeping the mapping here means a new
 * language is added by writing one `values-XX/strings.xml` and nothing else, and it keeps
 * wording decisions — "Paused until this phone can decide" rather than
 * "WAITING_FOR_REASONING" — in the layer that is responsible for how things read.
 */
@StringRes
fun TaskState.labelRes(): Int = when (this) {
    TaskState.CREATED -> R.string.task_state_created
    TaskState.PLANNING -> R.string.task_state_planning
    TaskState.AWAITING_CONFIRMATION -> R.string.task_state_awaiting_confirmation
    TaskState.RUNNING -> R.string.task_state_running
    TaskState.WAITING_FOR_REASONING -> R.string.task_state_waiting_for_reasoning
    TaskState.WAITING_FOR_USER -> R.string.task_state_waiting_for_user
    TaskState.DEFERRED -> R.string.task_state_deferred
    TaskState.COMPLETED -> R.string.task_state_completed
    TaskState.FAILED -> R.string.task_state_failed
    TaskState.CANCELLED -> R.string.task_state_cancelled
    TaskState.BLOCKED -> R.string.task_state_blocked
}

/**
 * Why an automation cannot run, said in terms of what the user can do about it.
 */
@StringRes
fun ExecutabilityState.labelRes(): Int = when (this) {
    ExecutabilityState.EXECUTABLE -> R.string.executability_ready
    ExecutabilityState.SCREEN_CONTROL_UNAVAILABLE -> R.string.executability_no_screen_control
    ExecutabilityState.STOPPED_BY_USER -> R.string.executability_stopped
    ExecutabilityState.DEVICE_LOCKED -> R.string.executability_locked
    ExecutabilityState.USER_UNLOCK_REQUIRED -> R.string.executability_unlock
    ExecutabilityState.USER_INTERACTION_REQUIRED -> R.string.executability_needs_you
    ExecutabilityState.NETWORK_UNAVAILABLE -> R.string.executability_offline
    ExecutabilityState.OS_BLOCKED -> R.string.executability_os_blocked
    ExecutabilityState.APP_BLOCKED -> R.string.executability_app_blocked
}

@StringRes
fun TaskOrigin.labelRes(): Int = when (this) {
    TaskOrigin.MANUAL -> R.string.origin_manual
    TaskOrigin.NATURAL_LANGUAGE_COMMAND -> R.string.origin_command
    TaskOrigin.TIME_TRIGGER -> R.string.origin_time
    TaskOrigin.NOTIFICATION_TRIGGER -> R.string.origin_notification
    TaskOrigin.REPLAY -> R.string.origin_replay
}

@StringRes
fun AutonomyLevel.labelRes(): Int = when (this) {
    AutonomyLevel.L0_OBSERVE -> R.string.autonomy_observe
    AutonomyLevel.L1_SUGGEST -> R.string.autonomy_suggest
    AutonomyLevel.L2_ASK_BEFORE_ACTION -> R.string.autonomy_ask
    AutonomyLevel.L3_AUTONOMOUS_LOW_RISK -> R.string.autonomy_low_risk
    AutonomyLevel.L4_EXPLICITLY_TRUSTED -> R.string.autonomy_trusted
}

@StringRes
fun DeviceRuntimeProfile.labelRes(): Int = when (this) {
    DeviceRuntimeProfile.A_NANO_NATIVE -> R.string.profile_nano
    DeviceRuntimeProfile.B_LOCAL_MODEL -> R.string.profile_local
    DeviceRuntimeProfile.C_CLOUD_HYBRID -> R.string.profile_cloud
    DeviceRuntimeProfile.D_OFFLINE_DETERMINISTIC -> R.string.profile_offline
}

/**
 * What the profile means for this phone, said plainly.
 *
 * A user whose device has to reach the cloud is told so before they teach it anything,
 * rather than discovering it from a privacy setting later.
 */
@StringRes
fun DeviceRuntimeProfile.detailRes(): Int = when (this) {
    DeviceRuntimeProfile.A_NANO_NATIVE -> R.string.profile_nano_detail
    DeviceRuntimeProfile.B_LOCAL_MODEL -> R.string.profile_local_detail
    DeviceRuntimeProfile.C_CLOUD_HYBRID -> R.string.profile_cloud_detail
    DeviceRuntimeProfile.D_OFFLINE_DETERMINISTIC -> R.string.profile_offline_detail
}

@StringRes
fun RuntimeTier.labelRes(): Int = when (this) {
    RuntimeTier.DETERMINISTIC -> R.string.tier_deterministic
    RuntimeTier.DEVICE_AI -> R.string.tier_device
    RuntimeTier.LOCAL_LLM -> R.string.tier_local
    RuntimeTier.CLOUD_LIGHT -> R.string.tier_cloud_light
    RuntimeTier.CLOUD_ADVANCED -> R.string.tier_cloud_advanced
}

@StringRes
fun AppPolicyMode.labelRes(): Int = when (this) {
    AppPolicyMode.ALLOW -> R.string.policy_allow
    AppPolicyMode.ASK -> R.string.policy_ask
    AppPolicyMode.OBSERVE_ONLY -> R.string.policy_observe
    AppPolicyMode.BLOCK -> R.string.policy_block
}

@StringRes
fun RiskCategory.labelRes(): Int = when (this) {
    RiskCategory.MESSAGE_SEND -> R.string.risk_message_send
    RiskCategory.EXTERNAL_POST -> R.string.risk_external_post
    RiskCategory.PURCHASE -> R.string.risk_purchase
    RiskCategory.PAYMENT -> R.string.risk_payment
    RiskCategory.TRANSFER -> R.string.risk_transfer
    RiskCategory.SUBSCRIPTION -> R.string.risk_subscription
    RiskCategory.BOOKING -> R.string.risk_booking
    RiskCategory.CANCELLATION -> R.string.risk_cancellation
    RiskCategory.DELETE -> R.string.risk_delete
    RiskCategory.PERMISSION_CHANGE -> R.string.risk_permission_change
    RiskCategory.ACCOUNT_CHANGE -> R.string.risk_account_change
}

@StringRes
fun ExecutionEventType.labelRes(): Int = when (this) {
    ExecutionEventType.TASK_CREATED -> R.string.event_task_created
    ExecutionEventType.SKILL_MATCHED -> R.string.event_skill_matched
    ExecutionEventType.PRECONDITION_CHECK -> R.string.event_precondition
    ExecutionEventType.STEP_STARTED -> R.string.event_step_started
    ExecutionEventType.RESOLVER_SELECTED -> R.string.event_resolver_selected
    ExecutionEventType.AI_RUNTIME_SELECTED -> R.string.event_runtime_selected
    ExecutionEventType.ACTION_EXECUTED -> R.string.event_action_executed
    ExecutionEventType.VALIDATION_RESULT -> R.string.event_validation
    ExecutionEventType.RECOVERY_STARTED -> R.string.event_recovery_started
    ExecutionEventType.RECOVERY_COMPLETED -> R.string.event_recovery_completed
    ExecutionEventType.STEP_FAILED -> R.string.event_step_failed
    ExecutionEventType.RISK_DECISION -> R.string.event_risk_decision
    ExecutionEventType.USER_CONFIRMATION -> R.string.event_user_confirmation
    ExecutionEventType.SKILL_PATCH_PROPOSED -> R.string.event_patch_proposed
    ExecutionEventType.TASK_DEFERRED -> R.string.event_task_deferred
    ExecutionEventType.TASK_COMPLETED -> R.string.event_task_completed
    ExecutionEventType.TASK_FAILED -> R.string.event_task_failed
    ExecutionEventType.TASK_CANCELLED -> R.string.event_task_cancelled
}

/** A finished run's verdict reuses the task-state wording so the two never disagree. */
@StringRes
fun OutcomeStatus.labelRes(): Int = when (this) {
    OutcomeStatus.SUCCESS -> R.string.task_state_completed
    OutcomeStatus.PARTIAL -> R.string.task_state_failed
    OutcomeStatus.FAILED -> R.string.task_state_failed
    OutcomeStatus.DEFERRED -> R.string.task_state_deferred
    OutcomeStatus.CANCELLED -> R.string.task_state_cancelled
    OutcomeStatus.BLOCKED -> R.string.task_state_blocked
}

/**
 * When an automation runs, written as a sentence.
 *
 * Weekday sets are rendered through translated day names rather than by formatting a
 * locale-independent list, so the order and separators read naturally in each language.
 */
@Composable
fun TriggerSpec.describe(): String = when (this) {
    is TriggerSpec.Manual -> stringResource(R.string.trigger_manual)
    is TriggerSpec.Time -> {
        val time = "%02d:%02d".format(hour, minute)
        if (daysOfWeek.isEmpty() || daysOfWeek.size == 7) {
            stringResource(R.string.trigger_time_daily, time)
        } else {
            val names = listOf(
                stringResource(R.string.day_mon),
                stringResource(R.string.day_tue),
                stringResource(R.string.day_wed),
                stringResource(R.string.day_thu),
                stringResource(R.string.day_fri),
                stringResource(R.string.day_sat),
                stringResource(R.string.day_sun),
            )
            val days = daysOfWeek.sorted().joinToString(" ") { names[it - 1] }
            stringResource(R.string.trigger_time_days, days, time)
        }
    }

    is TriggerSpec.Notification -> semanticCondition?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.trigger_notification_condition, it) }
        ?: stringResource(R.string.trigger_notification)
}
