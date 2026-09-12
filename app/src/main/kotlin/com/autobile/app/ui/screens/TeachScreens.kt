package com.autobile.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autobile.app.R
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.appLabel
import com.autobile.app.ui.design.Choice
import com.autobile.app.ui.design.ChoiceRow
import com.autobile.app.ui.design.EmptyState
import com.autobile.app.ui.design.Hairline
import com.autobile.app.ui.design.PrimaryButton
import com.autobile.app.ui.design.QuietButton
import com.autobile.app.ui.design.SectionHeading
import com.autobile.app.ui.design.Space
import com.autobile.app.ui.design.StateDot
import com.autobile.app.ui.design.Statement
import com.autobile.app.ui.design.TextAction
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.theme
import com.autobile.app.ui.labelRes
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.TriggerSpec

@Composable
fun TeachScreen(state: AppUiState, viewModel: AppViewModel, context: Context) {
    val suggestedName = state.teachLabel ?: stringResource(R.string.default_automation_name)
    var label by remember(suggestedName) { mutableStateOf(suggestedName) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.gutter)
            .padding(bottom = 120.dp),
    ) {
        if (!state.recording.recording) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.teach_title), style = TypeScale.display, color = theme.ink)
            Spacer(Modifier.height(10.dp))
            Statement(
                stringResource(
                    // Promising a notification the platform will not show would strand
                    // the user in the app they demonstrated in.
                    if (state.capability.canPostNotifications) {
                        R.string.teach_body
                    } else {
                        R.string.teach_body_no_notification
                    },
                ),
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.teach_name_label), style = TypeScale.meta) },
                textStyle = TypeScale.body,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = stringResource(R.string.teach_start),
                onClick = { viewModel.startTeaching(label) },
                startsAgent = true,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(theme.live)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.teach_recording_title, state.recording.label),
                    style = TypeScale.title,
                    color = theme.ink,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.teach_actions_captured, state.recording.eventCount),
                style = TypeScale.display,
                color = theme.ink,
            )
            state.recording.currentApp.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.teach_current_app, appLabel(it)), style = TypeScale.body, color = theme.muted)
            }
            state.recording.lastAction?.let { action ->
                Spacer(Modifier.height(24.dp))
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Text(stringResource(R.string.teach_last_action), style = TypeScale.body, color = theme.muted, modifier = Modifier.weight(1f))
                    Text(
                        text = action.argument
                            ?.let { stringResource(action.res, it) }
                            ?: stringResource(action.res),
                        style = TypeScale.label,
                        color = theme.ink,
                    )
                }
                Hairline()
            }
            Spacer(Modifier.height(32.dp))
            PrimaryButton(stringResource(R.string.teach_continue), { context.returnToHomeScreen() })
            Spacer(Modifier.height(10.dp))
            QuietButton(stringResource(R.string.teach_finish), viewModel::finishTeaching)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(stringResource(R.string.teach_cancel), viewModel::cancelTeaching, color = theme.muted)
            }
        }
    }
}

@Composable
fun TeachReviewScreen(state: AppUiState, viewModel: AppViewModel) {
    val draft = state.compilation
    if (draft == null) {
        EmptyState(
            title = stringResource(R.string.review_empty_title),
            detail = stringResource(R.string.review_empty_detail),
        )
        return
    }
    val skill = draft.skill
    var correction by remember(skill.id) { mutableStateOf("") }
    var scheduled by remember(skill.id) { mutableStateOf(skill.trigger is TriggerSpec.Time) }
    var hour by remember(skill.id) { mutableStateOf((skill.trigger as? TriggerSpec.Time)?.hour?.toString() ?: "9") }
    var minute by remember(skill.id) { mutableStateOf((skill.trigger as? TriggerSpec.Time)?.minute?.toString() ?: "0") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.gutter)
            .padding(bottom = 120.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.review_title), style = TypeScale.display, color = theme.ink)
        Spacer(Modifier.height(12.dp))
        Text(draft.summary, style = TypeScale.body, color = theme.ink)
        Spacer(Modifier.height(8.dp))
        Statement(stringResource(R.string.review_body, draft.discardedSteps))

        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = skill.name,
            onValueChange = viewModel::updateTeachName,
            label = { Text(stringResource(R.string.review_name), style = TypeScale.meta) },
            textStyle = TypeScale.body,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = skill.goal,
            onValueChange = viewModel::updateTeachGoal,
            label = { Text(stringResource(R.string.review_goal), style = TypeScale.meta) },
            textStyle = TypeScale.body,
            minLines = 2,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )

        SectionHeading(stringResource(R.string.review_variables))
        if (skill.variables.isEmpty()) {
            Statement(stringResource(R.string.review_variables_none))
        } else {
            skill.variables.forEach { variable ->
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(variable.name, style = TypeScale.body, color = theme.ink, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        variable.description.ifBlank { variable.exampleValue.orEmpty() },
                        style = TypeScale.meta,
                        color = theme.muted,
                    )
                }
            }
            Hairline()
        }

        SectionHeading(stringResource(R.string.review_constants))
        if (skill.constants.isEmpty()) {
            Statement(stringResource(R.string.review_constants_none))
        } else {
            skill.constants.forEach { constant ->
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(constant.name, style = TypeScale.body, color = theme.ink, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Text(constant.value, style = TypeScale.label, color = theme.ink)
                }
            }
            Hairline()
        }

        SectionHeading(stringResource(R.string.review_steps))
        skill.steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(
                    text = "${index + 1}",
                    style = TypeScale.meta,
                    color = theme.muted,
                    modifier = Modifier.width(24.dp),
                )
                Text(
                    text = step.description.ifBlank { step.target.intentLabel },
                    style = TypeScale.body,
                    color = theme.ink,
                )
            }
        }

        SectionHeading(stringResource(R.string.review_correction_heading))
        Statement(stringResource(R.string.review_correction_body))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = correction,
            onValueChange = { correction = it },
            placeholder = { Text(stringResource(R.string.review_correction_hint), style = TypeScale.body, color = theme.muted) },
            textStyle = TypeScale.body,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        QuietButton(
            text = stringResource(R.string.review_correction_action),
            onClick = { viewModel.correctUnderstanding(correction); correction = "" },
            enabled = correction.isNotBlank(),
        )

        SectionHeading(stringResource(R.string.review_schedule))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.review_schedule), style = TypeScale.body, color = theme.ink, modifier = Modifier.weight(1f))
            Switch(
                colors = com.autobile.app.ui.design.autobileSwitchColors(),
                checked = scheduled,
                onCheckedChange = {
                    scheduled = it
                    viewModel.updateTeachSchedule(
                        if (it) hour.toIntOrNull() else null,
                        if (it) minute.toIntOrNull() else null,
                    )
                },
            )
        }
        if (scheduled) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hour,
                    onValueChange = { hour = it; viewModel.updateTeachSchedule(it.toIntOrNull(), minute.toIntOrNull()) },
                    label = { Text(stringResource(R.string.review_hour), style = TypeScale.meta) },
                    textStyle = TypeScale.body,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = fieldColors(),
                )
                OutlinedTextField(
                    value = minute,
                    onValueChange = { minute = it; viewModel.updateTeachSchedule(hour.toIntOrNull(), it.toIntOrNull()) },
                    label = { Text(stringResource(R.string.review_minute), style = TypeScale.meta) },
                    textStyle = TypeScale.body,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = fieldColors(),
                )
            }
        }

        SectionHeading(stringResource(R.string.review_autonomy))
        ChoiceRow {
            AutonomyLevel.entries.forEach { level ->
                Choice(
                    text = stringResource(level.labelRes()),
                    selected = skill.autonomyLevel == level,
                    onClick = { viewModel.updateTeachAutonomy(level) },
                )
            }
        }

        // Which runtime read the demonstration. Said plainly because a run's counters
        // only report what a *run* needed, so a phone that understood this perfectly can
        // still show zeroes afterwards and look as though its model does nothing.
        Spacer(Modifier.height(20.dp))
        Statement(
            draft.understoodBy?.let { stringResource(R.string.review_understood_by, stringResource(it.labelRes())) }
                ?: stringResource(R.string.review_understood_by_rules),
            color = theme.muted,
        )

        if (draft.usedCloud) {
            Spacer(Modifier.height(12.dp))
            Statement(stringResource(R.string.review_used_cloud), color = theme.caution)
        }

        Spacer(Modifier.height(32.dp))
        PrimaryButton(stringResource(R.string.review_save), viewModel::saveTeaching)
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = theme.ink,
    unfocusedBorderColor = theme.line,
    focusedLabelColor = theme.muted,
    unfocusedLabelColor = theme.muted,
    focusedTextColor = theme.ink,
    unfocusedTextColor = theme.ink,
    cursorColor = theme.ink,
)

/** Sends the user back to their launcher so they can demonstrate in the app they actually use. */
private fun Context.returnToHomeScreen() {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
