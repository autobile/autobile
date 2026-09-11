package com.autobile.app.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.autobile.app.R
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.OnboardingStep
import com.autobile.app.ui.design.Hairline
import com.autobile.app.ui.design.Meter
import com.autobile.app.ui.design.PrimaryButton
import com.autobile.app.ui.design.QuietButton
import com.autobile.app.ui.design.Space
import com.autobile.app.ui.design.Statement
import com.autobile.app.ui.design.StatusBarSpacer
import com.autobile.app.ui.design.TextAction
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.theme
import com.autobile.app.ui.detailRes
import com.autobile.app.ui.labelRes
import com.autobile.core.model.DeviceCapabilityProfile

private val OnboardingOrder = listOf(
    OnboardingStep.CAPABILITY,
    OnboardingStep.PERMISSIONS,
    OnboardingStep.INSTANT_TASK,
    OnboardingStep.TEACH,
    OnboardingStep.REPLAY,
)

/**
 * Setup, told as five short screens.
 *
 * The order is deliberate: what the phone can do, then what it needs, then a task it runs
 * in front of you, then one you teach it. Asking for accessibility access before showing
 * anything would be asking for a great deal on faith.
 */
@Composable
fun OnboardingScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    context: Context,
    requestNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = OnboardingOrder.indexOf(state.onboardingStep).coerceAtLeast(0)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.gutter),
    ) {
        StatusBarSpacer()
        Spacer(Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onboarding_tagline),
            style = TypeScale.display,
            color = theme.ink,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.onboarding_step, index + 1, OnboardingOrder.size),
            style = TypeScale.meta,
            color = theme.muted,
        )
        Spacer(Modifier.height(10.dp))
        Meter(fraction = (index + 1f) / OnboardingOrder.size, color = theme.ink)
        Spacer(Modifier.height(36.dp))

        when (state.onboardingStep) {
            OnboardingStep.CAPABILITY -> CapabilityStep(state, viewModel)
            OnboardingStep.PERMISSIONS -> PermissionsStep(state, viewModel, context, requestNotificationPermission)
            OnboardingStep.INSTANT_TASK -> FirstRunStep(viewModel)
            OnboardingStep.TEACH -> TeachStep(viewModel)
            OnboardingStep.REPLAY, OnboardingStep.COMPLETE -> ReplayStep(state, viewModel)
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun CapabilityStep(state: AppUiState, viewModel: AppViewModel) {
    Text(stringResource(R.string.onboarding_capability_title), style = TypeScale.title, color = theme.ink)
    Spacer(Modifier.height(16.dp))

    CapabilityLine(stringResource(R.string.capability_phone_control), state.capability.canControlScreen)
    CapabilityLine(
        stringResource(R.string.capability_screen_understanding),
        state.capability.canUnderstandScreenVisually || state.capability.accessibilityConnected,
    )
    CapabilityLine(stringResource(R.string.capability_on_device_ai), state.capability.deviceAi.isUsable)
    CapabilityLine(stringResource(R.string.capability_cloud_ai), state.capability.cloud.isUsable)

    Spacer(Modifier.height(28.dp))
    RuntimeProfileSummary(state.capability)

    Spacer(Modifier.height(32.dp))
    PrimaryButton(stringResource(R.string.action_continue), viewModel::nextOnboardingStep)
}

@Composable
fun RuntimeProfileSummary(profile: DeviceCapabilityProfile) {
    Text(
        text = stringResource(R.string.capability_runtime_profile),
        style = TypeScale.meta,
        color = theme.muted,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(profile.runtimeProfile.labelRes()),
        style = TypeScale.heading,
        color = theme.ink,
    )
    Spacer(Modifier.height(8.dp))
    Statement(stringResource(profile.runtimeProfile.detailRes()))
}

/**
 * One capability and whether it is there.
 *
 * Availability is carried by weight, not by colour. A green "Not available" reads as
 * approval at a glance, and colour in this interface means the system is acting.
 */
@Composable
private fun CapabilityLine(label: String, available: Boolean) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TypeScale.body, color = theme.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(
                    if (available) R.string.capability_available else R.string.capability_unavailable,
                ),
                style = TypeScale.label,
                color = if (available) theme.ink else theme.muted,
            )
        }
        Hairline()
    }
}

@Composable
private fun PermissionsStep(
    state: AppUiState,
    viewModel: AppViewModel,
    context: Context,
    requestNotificationPermission: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_permissions_title), style = TypeScale.title, color = theme.ink)
    Spacer(Modifier.height(10.dp))
    Statement(stringResource(R.string.onboarding_permissions_body))
    Spacer(Modifier.height(24.dp))

    PermissionLine(
        label = stringResource(R.string.permission_screen_control),
        granted = state.capability.accessibilityConnected,
        onOpen = { context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
    )
    // Posting notifications and reading them are different grants that happen to share
    // a word. They were one row, which meant granting the reader marked the row done and
    // the permission an automation needs to be visible at all was never asked for.
    PermissionLine(
        label = stringResource(R.string.permission_post_notifications),
        granted = state.capability.canPostNotifications,
        action = stringResource(R.string.permission_allow),
        onOpen = requestNotificationPermission,
    )
    PermissionLine(
        label = stringResource(R.string.permission_notifications),
        granted = state.capability.notificationAccessGranted,
        onOpen = { context.openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") },
    )
    PermissionLine(
        label = stringResource(R.string.permission_overlay),
        granted = state.capability.overlayGranted,
        onOpen = { context.openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
    )

    Spacer(Modifier.height(28.dp))
    PrimaryButton(stringResource(R.string.action_continue), viewModel::nextOnboardingStep)
    Spacer(Modifier.height(12.dp))
    Statement(stringResource(R.string.onboarding_permissions_note))
}

@Composable
private fun PermissionLine(
    label: String,
    granted: Boolean,
    onOpen: () -> Unit,
    action: String? = null,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TypeScale.body, color = theme.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            if (granted) {
                Text(stringResource(R.string.permission_granted), style = TypeScale.label, color = theme.ink)
            } else {
                TextAction(action ?: stringResource(R.string.action_open_settings), onOpen, color = theme.live)
            }
        }
        Hairline()
    }
}

@Composable
private fun FirstRunStep(viewModel: AppViewModel) {
    Text(stringResource(R.string.onboarding_first_run_title), style = TypeScale.title, color = theme.ink)
    Spacer(Modifier.height(10.dp))
    Statement(stringResource(R.string.onboarding_first_run_body))
    Spacer(Modifier.height(28.dp))
    PrimaryButton(
        text = stringResource(R.string.onboarding_first_run_action),
        onClick = viewModel::runStarterTask,
        icon = Icons.Outlined.PlayArrow,
        startsAgent = true,
    )
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextAction(stringResource(R.string.onboarding_first_run_skip), viewModel::nextOnboardingStep, color = theme.muted)
    }
}

@Composable
private fun TeachStep(viewModel: AppViewModel) {
    Text(stringResource(R.string.onboarding_teach_title), style = TypeScale.title, color = theme.ink)
    Spacer(Modifier.height(10.dp))
    Statement(stringResource(R.string.onboarding_teach_body))
    Spacer(Modifier.height(28.dp))
    PrimaryButton(
        text = stringResource(R.string.onboarding_teach_action),
        onClick = { viewModel.navigate(com.autobile.app.ui.AppScreen.TEACH) },
        startsAgent = true,
    )
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextAction(stringResource(R.string.onboarding_teach_later), viewModel::nextOnboardingStep, color = theme.muted)
    }
}

@Composable
private fun ReplayStep(state: AppUiState, viewModel: AppViewModel) {
    val skill = state.skills.firstOrNull()
    Text(
        text = stringResource(
            if (skill != null) R.string.onboarding_replay_title else R.string.onboarding_ready_title,
        ),
        style = TypeScale.title,
        color = theme.ink,
    )
    Spacer(Modifier.height(10.dp))
    if (skill == null) {
        Statement(stringResource(R.string.onboarding_ready_body))
        Spacer(Modifier.height(24.dp))
    }

    if (skill != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(skill.name, style = TypeScale.heading, color = theme.ink)
            Text(skill.goal, style = TypeScale.body, color = theme.muted)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = stringResource(R.string.onboarding_replay_action, skill.name),
            onClick = { viewModel.runSkill(skill.id, replay = true) },
            startsAgent = true,
        )
        Spacer(Modifier.height(12.dp))
    }
    QuietButton(stringResource(R.string.onboarding_finish), viewModel::finishOnboarding)
}

private fun Context.openSettings(action: String) {
    runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
