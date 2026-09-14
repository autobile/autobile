package com.autobile.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.autobile.app.ui.AppScreen
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.AppViewModelFactory
import com.autobile.app.ui.labelRes
import com.autobile.app.ui.design.AutobileTheme
import com.autobile.app.ui.design.BarDestination
import com.autobile.app.ui.design.FloatingBar
import com.autobile.app.ui.design.IconAction
import com.autobile.app.ui.design.LiveBand
import com.autobile.app.ui.design.Space
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.theme
import com.autobile.app.ui.screens.EditConfirmationDialog
import com.autobile.app.ui.screens.EditRejectedDialog
import com.autobile.app.ui.screens.HistoryDetailScreen
import com.autobile.app.ui.screens.HistoryScreen
import com.autobile.app.ui.screens.HomeScreen
import com.autobile.app.ui.screens.OnboardingScreen
import com.autobile.app.ui.screens.RiskConfirmationDialog
import com.autobile.app.ui.screens.SettingsScreen
import com.autobile.app.ui.screens.SkillDetailScreen
import com.autobile.app.ui.screens.TeachReviewScreen
import com.autobile.app.ui.screens.TeachScreen
import com.autobile.runtime.agent.AgentActivity
import com.autobile.runtime.background.AppReturn
import com.autobile.runtime.edit.SkillEditPreview

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModelFactory((application as AutobileApplication).graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Recent Android versions lay every app out edge to edge whether or not it asks.
        // Declaring it is what makes the system bar insets reach Compose, so nothing is
        // drawn underneath the status bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AutobileTheme {
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    viewModel.refreshCapabilities()
                    // Android stops showing the dialog once someone has declined twice,
                    // and silently does nothing on every later request. Without this the
                    // row would stay on "Allow" and never respond again.
                    if (!granted && !shouldShowNotificationRationale()) openNotificationSettings()
                }

                AutobileApp(
                    viewModel = viewModel,
                    requestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings()
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppReturn.onInterfaceVisible(true)
        viewModel.refreshCapabilities()
    }

    override fun onPause() {
        AppReturn.onInterfaceVisible(false)
        super.onPause()
    }

    private fun shouldShowNotificationRationale(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

    /** The settings page for this app's notifications, for when the dialog is spent. */
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            }
        }
    }
}

/**
 * The root layout.
 *
 * There is no app bar. Each screen sets its own title as the first thing in its content,
 * which removes a strip of duplicated chrome from every screen and lets the title be large
 * enough to orient someone at a glance. Navigation sits in a floating bar that clears the
 * content rather than cutting the screen off at the bottom.
 */
@Composable
private fun AutobileApp(viewModel: AppViewModel, requestNotificationPermission: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Overlays(state, viewModel)

    val teaching = state.screen == AppScreen.TEACH || state.screen == AppScreen.TEACH_REVIEW
    val showOnboarding = !state.onboardingComplete && !teaching

    Box(Modifier.fillMaxSize().background(theme.paper)) {
        if (showOnboarding) {
            OnboardingScreen(
                state = state,
                viewModel = viewModel,
                context = context,
                requestNotificationPermission = requestNotificationPermission,
            )
        } else {
            MainSurface(state, viewModel)
        }

        LiveOverlay(state, viewModel)

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) { data ->
            Snackbar(
                containerColor = theme.ink,
                contentColor = theme.paper,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) { Text(data.visuals.message, style = TypeScale.body) }
        }

        if (state.loading) {
            Box(
                Modifier.fillMaxSize().background(theme.paper.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = theme.ink) }
        }
    }
}

@Composable
private fun MainSurface(state: AppUiState, viewModel: AppViewModel) {
    val rootScreen = state.screen in ROOT_SCREENS

    if (!rootScreen) {
        BackHandler {
            if (state.recording.recording) viewModel.cancelTeaching() else viewModel.navigate(AppScreen.HOME)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!rootScreen) {
                BackRow(state, viewModel)
            }
            Box(Modifier.weight(1f)) {
                when (state.screen) {
                    AppScreen.HOME -> HomeScreen(state, viewModel)
                    AppScreen.HISTORY -> HistoryScreen(state, viewModel)
                    AppScreen.SETTINGS -> SettingsScreen(state, viewModel, LocalContext.current)
                    AppScreen.SKILL_DETAIL -> SkillDetailScreen(state, viewModel)
                    AppScreen.HISTORY_DETAIL -> HistoryDetailScreen(state, viewModel)
                    AppScreen.TEACH -> TeachScreen(state, viewModel, LocalContext.current)
                    AppScreen.TEACH_REVIEW -> TeachReviewScreen(state, viewModel)
                }
            }
        }

        if (rootScreen) {
            FloatingBar(
                destinations = listOf(
                    BarDestination(AppScreen.HOME.name, stringResource(R.string.nav_home), Icons.Outlined.Home),
                    BarDestination(AppScreen.HISTORY.name, stringResource(R.string.nav_history), Icons.Outlined.History),
                    BarDestination(AppScreen.SETTINGS.name, stringResource(R.string.nav_settings), Icons.Outlined.Settings),
                ),
                selected = state.screen.name,
                onSelect = { viewModel.navigate(AppScreen.valueOf(it)) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** A single back affordance for the screens that are pushed on top of a root screen. */
@Composable
private fun BackRow(state: AppUiState, viewModel: AppViewModel) {
    Column {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding()))
        Row(
            Modifier.fillMaxWidth().padding(start = Space.gutter - 10.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                onClick = {
                    if (state.recording.recording) viewModel.cancelTeaching() else viewModel.navigate(AppScreen.HOME)
                },
            )
        }
    }
}

/**
 * The running band, floated above whatever screen is open.
 *
 * It enters from the top rather than displacing the content, so the screen a person was
 * reading does not jump the moment an automation starts.
 */
@Composable
private fun LiveOverlay(state: AppUiState, viewModel: AppViewModel) {
    val activity = state.activity
    AnimatedVisibility(
        visible = activity is AgentActivity.Running,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (activity is AgentActivity.Running) {
            Column {
                Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
                LiveBand(
                    skillName = activity.skillName,
                    step = activity.stepDescription,
                    stepIndex = activity.stepIndex,
                    totalSteps = activity.totalSteps,
                    repairNote = activity.repairNote,
                    thinkingNote = activity.deliberating?.let {
                        stringResource(R.string.agent_thinking, stringResource(it.labelRes()))
                    },
                    stopLabel = stringResource(R.string.action_stop),
                    onStop = viewModel::stopAgent,
                )
            }
        }
    }
}

@Composable
private fun Overlays(state: AppUiState, viewModel: AppViewModel) {
    state.pendingConfirmation?.let { pending ->
        RiskConfirmationDialog(
            step = pending.step,
            decision = pending.decision,
            onAnswer = viewModel::approvePending,
        )
    }

    when (val preview = state.editPreview) {
        is SkillEditPreview.Ready -> EditConfirmationDialog(preview, viewModel)
        is SkillEditPreview.Rejected -> EditRejectedDialog(preview.reason, viewModel::dismissEditPreview)
        null -> Unit
    }
}

private val ROOT_SCREENS = setOf(AppScreen.HOME, AppScreen.HISTORY, AppScreen.SETTINGS)
