package com.autobile.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autobile.ai.task.EditableStepAction
import com.autobile.ai.task.SkillStepEdit
import com.autobile.ai.task.SkillStepEditKind
import com.autobile.app.R
import com.autobile.app.ui.design.Choice
import com.autobile.app.ui.design.ChoiceRow
import com.autobile.app.ui.design.Hairline
import com.autobile.app.ui.design.TextAction
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.theme
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.SkillStep
import com.autobile.core.model.ValueRef
import com.autobile.runtime.edit.StepChange

/**
 * The steps of an automation, each of which can be moved, edited, made optional,
 * removed, or given a new step after it.
 *
 * Every change is explicit and applied as soon as it is confirmed. Nothing here goes
 * through a model: the person already knows what they want the step list to be, and a
 * round trip to reword it would only be slower and less certain. Every change becomes a
 * new version, so any of them can be undone from the version history.
 *
 * @param onChange receives the change and a short line describing it for the history.
 */
@Composable
internal fun StepEditor(
    steps: List<SkillStep>,
    onChange: (List<StepChange>, String) -> Unit,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SkillStep?>(null) }
    var inserting by remember { mutableStateOf<InsertPoint?>(null) }

    val movedSummary = stringResource(R.string.steps_change_moved)
    val deletedSummary = stringResource(R.string.steps_change_deleted)
    val optionalSummary = stringResource(R.string.steps_change_optional)
    val requiredSummary = stringResource(R.string.steps_change_required)

    Column(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            val title = step.title()
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (expanded == step.id) null else step.id }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    style = TypeScale.meta,
                    color = theme.muted,
                    modifier = Modifier.width(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = TypeScale.body, color = theme.ink)
                    Spacer(Modifier.height(2.dp))
                    val kind = stringResource(step.kindLabel())
                    val optionalTag = stringResource(R.string.step_optional_tag)
                    Text(
                        text = if (step.optional) "$kind · $optionalTag" else kind,
                        style = TypeScale.meta,
                        color = theme.muted,
                    )
                }
            }
            if (expanded == step.id) {
                ChoiceRow(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
                    if (index > 0) {
                        TextAction(stringResource(R.string.step_move_up), onClick = {
                            onChange(listOf(StepChange.Move(step.id, -1)), movedSummary.format(title))
                        })
                    }
                    if (index < steps.lastIndex) {
                        TextAction(stringResource(R.string.step_move_down), onClick = {
                            onChange(listOf(StepChange.Move(step.id, 1)), movedSummary.format(title))
                        })
                    }
                    TextAction(stringResource(R.string.step_edit), onClick = { editing = step })
                    TextAction(
                        text = stringResource(if (step.optional) R.string.step_make_required else R.string.step_make_optional),
                        onClick = {
                            onChange(
                                listOf(StepChange.SetOptional(step.id, !step.optional)),
                                (if (step.optional) requiredSummary else optionalSummary).format(title),
                            )
                        },
                    )
                    TextAction(stringResource(R.string.step_add_after), onClick = { inserting = InsertPoint(step.id, after = true) })
                    if (steps.size > 1) {
                        TextAction(
                            text = stringResource(R.string.step_delete),
                            color = theme.fault,
                            onClick = {
                                expanded = null
                                onChange(listOf(StepChange.Delete(step.id)), deletedSummary.format(title))
                            },
                        )
                    }
                }
            }
        }
        Hairline()
        Spacer(Modifier.height(4.dp))
        TextAction(
            text = stringResource(R.string.steps_add),
            onClick = { inserting = InsertPoint(steps.lastOrNull()?.id, after = true) },
        )
    }

    editing?.let { step ->
        EditStepDialog(
            step = step,
            onDismiss = { editing = null },
            onConfirm = { change, summary ->
                editing = null
                onChange(listOf(change), summary)
            },
        )
    }
    inserting?.let { point ->
        AddStepDialog(
            onDismiss = { inserting = null },
            onConfirm = { edit, summary ->
                inserting = null
                onChange(listOf(StepChange.Insert(point.anchorStepId, point.after, edit)), summary)
            },
        )
    }
}

private data class InsertPoint(val anchorStepId: String?, val after: Boolean)

@Composable
private fun EditStepDialog(
    step: SkillStep,
    onDismiss: () -> Unit,
    onConfirm: (StepChange.Update, String) -> Unit,
) {
    val input = step.action as? ActionSpec.InputText
    val wait = step.action as? ActionSpec.Wait
    var description by remember(step.id) { mutableStateOf(step.title()) }
    var text by remember(step.id) { mutableStateOf((input?.value as? ValueRef.Literal)?.value.orEmpty()) }
    var millis by remember(step.id) { mutableStateOf(wait?.millis?.toString().orEmpty()) }
    val summary = stringResource(R.string.steps_change_edited)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.step_edit_title), style = TypeScale.title) },
        text = {
            Column {
                StepField(description, { description = it }, R.string.step_field_description)
                if (input != null) {
                    Spacer(Modifier.height(10.dp))
                    StepField(text, { text = it }, R.string.step_field_text)
                }
                if (wait != null) {
                    Spacer(Modifier.height(10.dp))
                    StepField(millis, { millis = it }, R.string.step_field_wait, numeric = true)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = description.isNotBlank(),
                onClick = {
                    onConfirm(
                        StepChange.Update(
                            stepId = step.id,
                            description = description.trim().takeIf { it != step.title() },
                            text = text.takeIf { input != null && it.isNotEmpty() && it != (input.value as? ValueRef.Literal)?.value },
                            waitMs = millis.toLongOrNull()?.takeIf { wait != null && it != wait.millis },
                        ),
                        summary.format(description.trim()),
                    )
                },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = theme.raised,
    )
}

@Composable
private fun AddStepDialog(
    onDismiss: () -> Unit,
    onConfirm: (SkillStepEdit, String) -> Unit,
) {
    var kind by remember { mutableStateOf(EditableStepAction.CLICK) }
    var target by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var millis by remember { mutableStateOf("1000") }
    var app by remember { mutableStateOf("") }
    var objective by remember { mutableStateOf("") }
    var completion by remember { mutableStateOf("") }
    val summary = stringResource(R.string.steps_change_added)

    val ready = when (kind) {
        EditableStepAction.CLICK -> target.isNotBlank()
        EditableStepAction.INPUT_TEXT -> target.isNotBlank() && text.isNotEmpty()
        EditableStepAction.WAIT -> millis.toLongOrNull() != null
        EditableStepAction.LAUNCH_APP -> app.isNotBlank()
        EditableStepAction.VISUAL_TASK -> objective.isNotBlank() && completion.isNotBlank()
        else -> true
    }
    val kindLabel = stringResource(kind.label())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.step_add_title), style = TypeScale.title) },
        text = {
            Column {
                ChoiceRow {
                    ADDABLE_KINDS.forEach { option ->
                        Choice(
                            text = stringResource(option.label()),
                            selected = kind == option,
                            onClick = { kind = option },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (kind) {
                    EditableStepAction.CLICK -> StepField(target, { target = it }, R.string.step_field_target)
                    EditableStepAction.INPUT_TEXT -> {
                        StepField(target, { target = it }, R.string.step_field_target)
                        Spacer(Modifier.height(10.dp))
                        StepField(text, { text = it }, R.string.step_field_text)
                    }
                    EditableStepAction.WAIT -> StepField(millis, { millis = it }, R.string.step_field_wait, numeric = true)
                    EditableStepAction.LAUNCH_APP -> StepField(app, { app = it }, R.string.step_field_app)
                    EditableStepAction.VISUAL_TASK -> {
                        StepField(objective, { objective = it }, R.string.step_field_objective)
                        Spacer(Modifier.height(10.dp))
                        StepField(completion, { completion = it }, R.string.step_field_completion)
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val edit = SkillStepEdit(
                        kind = SkillStepEditKind.INSERT_AFTER,
                        stepId = "",
                        action = kind,
                        target = target.trim(),
                        objective = objective.trim(),
                        completionCriteria = completion.trim(),
                        durationMs = millis.toLongOrNull() ?: 1_000L,
                        packageName = app.trim(),
                        text = text,
                        clearExisting = true,
                    )
                    val described = listOf(target, app, objective).firstOrNull { it.isNotBlank() }?.trim()
                    onConfirm(edit, summary.format(described?.let { "$kindLabel: $it" } ?: kindLabel))
                },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = theme.raised,
    )
}

@Composable
private fun StepField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label), style = TypeScale.meta) },
        textStyle = TypeScale.body,
        singleLine = numeric,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors(),
    )
}

/** Kinds a person can add by hand. Each needs only what the form asks for. */
private val ADDABLE_KINDS = listOf(
    EditableStepAction.CLICK,
    EditableStepAction.INPUT_TEXT,
    EditableStepAction.WAIT,
    EditableStepAction.BACK,
    EditableStepAction.LAUNCH_APP,
    EditableStepAction.VISUAL_TASK,
)

private fun SkillStep.title(): String = description.ifBlank { target.description.ifBlank { target.intentLabel } }

@StringRes
private fun EditableStepAction.label(): Int = when (this) {
    EditableStepAction.CLICK -> R.string.step_kind_tap
    EditableStepAction.INPUT_TEXT -> R.string.step_kind_type
    EditableStepAction.WAIT -> R.string.step_kind_wait
    EditableStepAction.BACK -> R.string.step_kind_back
    EditableStepAction.LAUNCH_APP -> R.string.step_kind_open_app
    EditableStepAction.VISUAL_TASK -> R.string.step_kind_goal
    else -> R.string.step_kind_other
}

@StringRes
private fun SkillStep.kindLabel(): Int = when (action) {
    ActionSpec.Click, is ActionSpec.Tap -> R.string.step_kind_tap
    is ActionSpec.LongPress -> R.string.step_kind_long_press
    is ActionSpec.InputText -> R.string.step_kind_type
    is ActionSpec.Wait -> R.string.step_kind_wait
    ActionSpec.Back -> R.string.step_kind_back
    ActionSpec.Home -> R.string.step_kind_home
    is ActionSpec.LaunchApp -> R.string.step_kind_open_app
    is ActionSpec.VisualTask -> R.string.step_kind_goal
    is ActionSpec.Scroll, is ActionSpec.Swipe -> R.string.step_kind_scroll
    is ActionSpec.ReadValue -> R.string.step_kind_read
}
