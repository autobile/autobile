package com.autobile.app.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autobile.app.R
import com.autobile.ai.cloud.CloudAuthMethod
import com.autobile.ai.cloud.CloudService
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.appLabel
import com.autobile.app.ui.asPercent
import com.autobile.app.ui.design.Hairline
import com.autobile.app.ui.design.autobileSwitchColors
import com.autobile.app.ui.design.IconAction
import com.autobile.app.ui.design.Panel
import com.autobile.app.ui.design.PrimaryButton
import com.autobile.app.ui.design.ScreenTitle
import com.autobile.app.ui.design.SectionHeading
import com.autobile.app.ui.design.Choice
import com.autobile.app.ui.design.ChoiceRow
import com.autobile.app.ui.design.Space
import com.autobile.app.ui.design.Statement
import com.autobile.app.ui.design.StatusBarSpacer
import com.autobile.app.ui.design.TextAction
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.theme
import com.autobile.app.ui.labelRes
import com.autobile.ai.mlkit.DownloadState
import com.autobile.core.model.AiFeatureStatus
import com.autobile.core.model.AppPolicy
import com.autobile.core.model.AppPolicyMode

@Composable
fun SettingsScreen(state: AppUiState, viewModel: AppViewModel, context: Context) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp),
    ) {
        StatusBarSpacer()
        Row(
            Modifier.fillMaxWidth().padding(start = Space.gutter, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle(stringResource(R.string.settings_title), Modifier.weight(1f))
            IconAction(
                icon = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.action_refresh),
                onClick = viewModel::refreshCapabilities,
            )
        }

        // The safety control comes first. Someone reaching for it is not in the mood to
        // scroll past a list of preferences to find it.
        SettingSwitch(
            title = stringResource(R.string.settings_kill_switch),
            detail = stringResource(R.string.settings_kill_switch_detail),
            checked = state.killSwitchEngaged,
            onChange = viewModel::setKillSwitch,
        )

        SectionHeading(stringResource(R.string.settings_device), Modifier.padding(horizontal = Space.gutter))
        Column(Modifier.padding(horizontal = Space.gutter)) {
            RuntimeProfileSummary(state.capability)
        }
        if (state.capability.deviceAi.featureStatus == AiFeatureStatus.DOWNLOADABLE || state.nanoDownload != null) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = Space.gutter)) { ModelDownload(state, viewModel) }
        }

        SectionHeading(stringResource(R.string.settings_permissions), Modifier.padding(horizontal = Space.gutter))
        PermissionSetting(
            stringResource(R.string.permission_screen_control),
            state.capability.accessibilityConnected,
        ) { context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        // Reachable from here as well as onboarding: someone who declined during setup
        // has no other way to discover that running automations are invisible to them.
        PermissionSetting(
            stringResource(R.string.permission_post_notifications),
            state.capability.canPostNotifications,
        ) { context.openAppNotificationSettings() }
        PermissionSetting(
            stringResource(R.string.permission_notifications),
            state.capability.notificationAccessGranted,
        ) { context.openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }
        PermissionSetting(
            stringResource(R.string.permission_overlay),
            state.capability.overlayGranted,
        ) { context.openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }

        SectionHeading(stringResource(R.string.settings_cloud), Modifier.padding(horizontal = Space.gutter))
        SettingSwitch(
            title = stringResource(R.string.settings_cloud_enabled),
            detail = stringResource(R.string.settings_cloud_enabled_detail),
            checked = state.privacy.cloudEnabled,
            onChange = viewModel::updateCloudEnabled,
        )
        // Which service comes before what may be sent to it: the answer to the second
        // question depends on the first, because not every service can read a picture.
        if (state.privacy.cloudEnabled) {
            CloudCredentials(state, viewModel, context)
        }
        SettingSwitch(
            title = stringResource(R.string.settings_cloud_screenshots),
            detail = stringResource(R.string.settings_cloud_screenshots_detail),
            checked = state.privacy.allowScreenshotToCloud,
            onChange = viewModel::updateCloudScreenshots,
            enabled = state.privacy.cloudEnabled &&
                CloudService.from(state.privacy.cloudServiceName).supportsImages,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_masking),
            detail = stringResource(R.string.settings_masking_detail),
            checked = state.privacy.maskSensitiveFields,
            onChange = viewModel::updateMasking,
        )

        SectionHeading(stringResource(R.string.settings_during_run), Modifier.padding(horizontal = Space.gutter))
        SettingSwitch(
            title = stringResource(R.string.settings_touch_indicator),
            detail = stringResource(R.string.settings_touch_indicator_detail),
            checked = state.touchIndicatorEnabled,
            onChange = viewModel::setTouchIndicator,
        )

        SectionHeading(stringResource(R.string.settings_app_policy), Modifier.padding(horizontal = Space.gutter))
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Statement(stringResource(R.string.settings_app_policy_detail))
        }
        Spacer(Modifier.height(12.dp))
        if (state.policies.isEmpty()) {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) {
                Statement(stringResource(R.string.settings_app_policy_empty), color = theme.muted)
            }
        } else {
            Hairline(Modifier.padding(horizontal = Space.gutter))
            state.policies.forEach { policy ->
                PolicyRow(policy, viewModel)
                Hairline(Modifier.padding(horizontal = Space.gutter))
            }
        }

        SectionHeading(stringResource(R.string.settings_metrics), Modifier.padding(horizontal = Space.gutter))
        Hairline(Modifier.padding(horizontal = Space.gutter))
        MetricRow(stringResource(R.string.metric_weekly_autonomous), state.metrics.weeklyAutonomousTasksCompleted.toString())
        MetricRow(stringResource(R.string.metric_no_cloud), state.metrics.noCloudCompletionRate.asPercent())
        MetricRow(stringResource(R.string.metric_local_resolution), state.metrics.localResolutionRate.asPercent())
        MetricRow(stringResource(R.string.metric_recovery), state.metrics.recoverySuccessRate.asPercent())
        MetricRow(stringResource(R.string.metric_task_success), state.metrics.taskSuccessRate.asPercent())
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TypeScale.body, color = if (enabled) theme.ink else theme.muted)
                Spacer(Modifier.height(2.dp))
                Text(detail, style = TypeScale.meta, color = theme.muted)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = autobileSwitchColors(),
            )
        }
        Hairline(Modifier.padding(horizontal = Space.gutter))
    }
}

@Composable
private fun PermissionSetting(title: String, granted: Boolean, onOpen: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Space.gutter, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = TypeScale.body, color = theme.ink, modifier = Modifier.weight(1f))
            if (granted) {
                Text(
                    stringResource(R.string.permission_granted),
                    style = TypeScale.label,
                    color = theme.ink,
                    modifier = Modifier.padding(end = 12.dp),
                )
            } else {
                TextAction(stringResource(R.string.action_open_settings), onOpen)
            }
        }
        Hairline(Modifier.padding(horizontal = Space.gutter))
    }
}

@Composable
private fun CloudCredentials(state: AppUiState, viewModel: AppViewModel, context: Context) {
    val service = CloudService.from(state.privacy.cloudServiceName)
    var endpoint by remember(service) { mutableStateOf(state.privacy.cloudEndpoint) }
    var key by remember(service) { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = Space.gutter, vertical = 16.dp)) {
        Text(stringResource(R.string.settings_cloud_service), style = TypeScale.label, color = theme.ink)
        Spacer(Modifier.height(10.dp))
        // Choosing a service is choosing a name. The endpoint and model identifiers
        // follow from it, and are only worth showing to someone who wants to change them.
        ChoiceRow {
            CloudService.entries.forEach { option ->
                Choice(
                    text = option.displayName,
                    selected = option == service,
                    onClick = { viewModel.updateCloudService(option.name) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!service.supportsImages) {
            Statement(stringResource(R.string.settings_cloud_text_only, service.displayName), color = theme.muted)
            Spacer(Modifier.height(12.dp))
        }

        when (service.authMethod) {
            CloudAuthMethod.SIGN_IN -> CloudSignIn(state, viewModel)

            CloudAuthMethod.API_KEY -> {
                if (service.credentialUrl.isNotBlank()) {
                    TextAction(
                        stringResource(R.string.settings_cloud_get_key, service.displayName),
                        { context.openUrl(service.credentialUrl) },
                        color = theme.live,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; viewModel.setCloudApiKey(it) },
                    label = { Text(stringResource(R.string.settings_cloud_key), style = TypeScale.meta) },
                    placeholder = {
                        if (state.privacy.cloudApiKeyPresent) {
                            Text(
                                stringResource(R.string.settings_cloud_key_set),
                                style = TypeScale.body,
                                color = theme.muted,
                            )
                        }
                    },
                    textStyle = TypeScale.body,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        TextAction(
            stringResource(
                if (showAdvanced) R.string.settings_cloud_hide_advanced else R.string.settings_cloud_show_advanced,
            ),
            { showAdvanced = !showAdvanced },
            color = theme.muted,
        )

        if (showAdvanced) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it; viewModel.updateCloudEndpoint(it) },
                label = { Text(stringResource(R.string.settings_cloud_endpoint), style = TypeScale.meta) },
                placeholder = { Text(service.endpoint, style = TypeScale.body, color = theme.muted) },
                textStyle = TypeScale.body,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
        }
    }
}

/**
 * Connecting a subscription instead of pasting a key.
 *
 * The signed-out state says plainly whose account pays, because "sign in" next to a
 * cloud switch could otherwise be read as signing in to Autobile. The signed-in state
 * names the account, so someone with two of them can see which one is connected.
 */
@Composable
private fun CloudSignIn(state: AppUiState, viewModel: AppViewModel) {
    if (state.privacy.cloudSignedIn) {
        Text(
            state.privacy.cloudAccountLabel.ifBlank { stringResource(R.string.settings_cloud_signed_in) },
            style = TypeScale.body,
            color = theme.ink,
        )
        Spacer(Modifier.height(10.dp))
        TextAction(stringResource(R.string.settings_cloud_sign_out), viewModel::signOutOfCloud, color = theme.muted)
    } else if (state.cloudSignInPending) {
        Statement(stringResource(R.string.settings_cloud_sign_in_waiting), color = theme.muted)
        Spacer(Modifier.height(10.dp))
        TextAction(
            stringResource(R.string.settings_cloud_sign_in_cancel),
            viewModel::cancelCloudSignIn,
            color = theme.muted,
        )
    } else {
        Statement(stringResource(R.string.settings_cloud_sign_in_detail), color = theme.muted)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.settings_cloud_sign_in), viewModel::signInToCloud)
    }
}

@Composable
private fun ModelDownload(state: AppUiState, viewModel: AppViewModel) {
    Panel {
        Column {
            Text(stringResource(R.string.settings_model_download), style = TypeScale.label, color = theme.ink)
            Spacer(Modifier.height(6.dp))
            Statement(stringResource(R.string.settings_model_download_body))
            Spacer(Modifier.height(14.dp))
            val progress = state.nanoDownload
            if (progress != null && progress.state == DownloadState.IN_PROGRESS) {
                com.autobile.app.ui.design.Meter(progress.fraction, color = theme.live)
            } else {
                PrimaryButton(
                    text = stringResource(
                        if (progress?.state == DownloadState.FAILED) {
                            R.string.settings_model_download_retry
                        } else {
                            R.string.settings_model_download_action
                        },
                    ),
                    onClick = viewModel::consentAndDownloadDeviceModel,
                    enabled = progress == null || progress.state == DownloadState.FAILED,
                )
            }
        }
    }
}

@Composable
private fun PolicyRow(policy: AppPolicy, viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = Space.gutter, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(policy.label.ifBlank { appLabel(policy.packageName) }, style = TypeScale.body, color = theme.ink)
            Spacer(Modifier.height(2.dp))
            Text(policy.packageName, style = TypeScale.meta, color = theme.muted, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            TextAction(stringResource(policy.mode.labelRes()), { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppPolicyMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(stringResource(mode.labelRes()), style = TypeScale.body) },
                        onClick = { expanded = false; viewModel.savePolicy(policy, mode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TypeScale.body, color = theme.muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(value, style = TypeScale.figure, color = theme.ink)
    }
    Hairline(Modifier.padding(horizontal = Space.gutter))
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun Context.openSettings(action: String) {
    runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * This app's notification settings.
 *
 * Not the runtime permission dialog: by the time someone comes looking in settings they
 * have usually already declined it, and Android will not show that dialog again.
 */
private fun Context.openAppNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }.onFailure {
        openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
}
