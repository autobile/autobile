package com.autobile.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.autobile.app.R
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.design.EmptyState
import com.autobile.app.ui.design.FieldRow
import com.autobile.app.ui.design.Hairline
import com.autobile.app.ui.design.ListRow
import com.autobile.app.ui.design.PrimaryButton
import com.autobile.app.ui.design.ScreenTitle
import com.autobile.app.ui.design.SectionHeading
import com.autobile.app.ui.design.Space
import com.autobile.app.ui.design.StateDot
import com.autobile.app.ui.design.Statement
import com.autobile.app.ui.design.StatusBarSpacer
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.barClearancePadding
import com.autobile.app.ui.design.theme
import com.autobile.app.ui.formatTimestamp
import com.autobile.app.ui.labelRes
import com.autobile.app.ui.resultLabelRes
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.TaskState

@Composable
fun HistoryScreen(state: AppUiState, viewModel: AppViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = barClearancePadding()) {
        item {
            StatusBarSpacer()
            ScreenTitle(stringResource(R.string.history_title), Modifier.padding(horizontal = Space.gutter))
        }
        if (state.tasks.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    detail = stringResource(R.string.history_empty_detail),
                )
            }
        } else {
            item { Hairline(Modifier.padding(horizontal = Space.gutter)) }
            items(state.tasks, key = { it.id }) { task ->
                ListRow(
                    title = task.goal,
                    meta = formatTimestamp(task.createdAt),
                    leading = { StateDot(task.state.statusColor()) },
                    trailing = {
                        Text(
                            text = stringResource(task.state.labelRes()),
                            style = TypeScale.meta,
                            color = theme.muted,
                        )
                    },
                    onClick = { viewModel.selectTask(task.id) },
                )
                Hairline(Modifier.padding(horizontal = Space.gutter))
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(state: AppUiState, viewModel: AppViewModel) {
    val task = state.selectedTask
    if (task == null) {
        EmptyState(
            title = stringResource(R.string.history_unavailable_title),
            detail = stringResource(R.string.history_unavailable_detail),
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp),
    ) {
        Text(
            text = task.goal,
            style = TypeScale.title,
            color = theme.ink,
            modifier = Modifier.padding(horizontal = Space.gutter).padding(top = 8.dp, bottom = 20.dp),
        )

        Hairline(Modifier.padding(horizontal = Space.gutter))
        FieldRow(
            label = stringResource(R.string.history_result),
            value = stringResource(state.selectedTaskOutcome?.resultLabelRes() ?: task.state.labelRes()),
            valueColor = task.state.statusColor(),
        )
        Hairline(Modifier.padding(horizontal = Space.gutter))
        FieldRow(
            label = stringResource(R.string.history_runtime),
            value = stringResource(
                R.string.history_runtime_breakdown,
                task.deterministicStepCount,
                task.deviceAiCallCount,
                task.cloudCallCount,
            ),
        )
        Hairline(Modifier.padding(horizontal = Space.gutter))

        task.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
            Column(Modifier.padding(horizontal = Space.gutter, vertical = 16.dp)) {
                Text(stringResource(R.string.history_what_happened), style = TypeScale.meta, color = theme.muted)
                Spacer(Modifier.height(4.dp))
                Statement(reason, color = theme.ink)
            }
            Hairline(Modifier.padding(horizontal = Space.gutter))
        }

        task.skillId?.let { skillId ->
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = stringResource(R.string.history_replay),
                onClick = { viewModel.runSkill(skillId, replay = true) },
                startsAgent = true,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
        }

        SectionHeading(stringResource(R.string.history_timeline), Modifier.padding(horizontal = Space.gutter))
        state.selectedTaskEvents.forEach { EventLine(it) }
    }
}

/**
 * One line of the trail.
 *
 * The event type is translated; the message beside it is the diagnostic text recorded when
 * the run happened and is shown as it was written, because a record that changes wording
 * when the phone's language changes is no longer a record of what happened.
 */
@Composable
private fun EventLine(event: ExecutionEvent) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 10.dp),
    ) {
        Box(Modifier.padding(top = 7.dp)) {
            StateDot(if (event.success == false) theme.fault else theme.line)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(event.type.labelRes()), style = TypeScale.label, color = theme.ink)
            if (event.message.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(event.message, style = TypeScale.body, color = theme.muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = com.autobile.app.ui.formatTime(event.timestamp),
            style = TypeScale.meta,
            color = theme.muted,
        )
    }
}

/** Colour is spent on the two states that need a person: something failed, or is running. */
@Composable
fun TaskState.statusColor(): Color = when (this) {
    TaskState.RUNNING -> theme.live
    TaskState.FAILED, TaskState.BLOCKED -> theme.fault
    TaskState.WAITING_FOR_REASONING, TaskState.DEFERRED, TaskState.WAITING_FOR_USER,
    TaskState.AWAITING_CONFIRMATION,
    -> theme.caution
    else -> theme.line
}
