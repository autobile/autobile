package com.autobile.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.autobile.app.R
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.asPercent
import com.autobile.app.ui.describe
import com.autobile.app.ui.design.Choice
import com.autobile.app.ui.design.ChoiceRow
import com.autobile.app.ui.design.EmptyState
import com.autobile.app.ui.design.FieldRow
import com.autobile.app.ui.design.Hairline
import com.autobile.app.ui.design.Meter
import com.autobile.app.ui.design.Panel
import com.autobile.app.ui.design.PrimaryButton
import com.autobile.app.ui.design.QuietButton
import com.autobile.app.ui.design.SectionHeading
import com.autobile.app.ui.design.Space
import com.autobile.app.ui.design.Statement
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.theme
import com.autobile.app.ui.formatTimestamp
import com.autobile.app.ui.labelRes
import com.autobile.core.data.PatchStatus
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.SemanticSkill

@Composable
fun SkillDetailScreen(state: AppUiState, viewModel: AppViewModel) {
    val skill = state.selectedSkill
    if (skill == null) {
        EmptyState(
            title = stringResource(R.string.skill_unavailable_title),
            detail = stringResource(R.string.skill_unavailable_detail),
        )
        return
    }

    var editText by remember(skill.id) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp),
    ) {
        Column(Modifier.padding(horizontal = Space.gutter).padding(top = 8.dp)) {
            Text(skill.name, style = TypeScale.display, color = theme.ink)
            Spacer(Modifier.height(6.dp))
            Statement(skill.goal)
        }

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(
                text = stringResource(R.string.skill_run_now),
                onClick = { viewModel.runSkill(skill.id) },
                enabled = skill.enabled,
                icon = Icons.Outlined.PlayArrow,
                startsAgent = true,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = skill.enabled,
                onCheckedChange = { viewModel.setSkillEnabled(skill, it) },
                colors = com.autobile.app.ui.design.autobileSwitchColors(),
            )
        }

        Spacer(Modifier.height(28.dp))
        Hairline(Modifier.padding(horizontal = Space.gutter))
        FieldRow(stringResource(R.string.skill_trigger), skill.trigger.describe())
        Hairline(Modifier.padding(horizontal = Space.gutter))
        FieldRow(stringResource(R.string.skill_autonomy), stringResource(skill.effectiveAutonomy().labelRes()))
        Hairline(Modifier.padding(horizontal = Space.gutter))
        FieldRow(stringResource(R.string.skill_runtime_requirements), skill.requirementSummary())
        Hairline(Modifier.padding(horizontal = Space.gutter))
        FieldRow(
            label = stringResource(R.string.skill_last_run),
            value = state.selectedSkillLastTask?.let { formatTimestamp(it.finishedAt ?: it.createdAt) }
                ?: stringResource(R.string.skill_never_run),
        )
        Hairline(Modifier.padding(horizontal = Space.gutter))

        SectionHeading(stringResource(R.string.skill_confidence), Modifier.padding(horizontal = Space.gutter))
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.confidence.score.asPercent(), style = TypeScale.display, color = theme.ink)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(
                        R.string.skill_success_rate,
                        skill.confidence.successCount,
                        skill.confidence.executionCount,
                    ),
                    style = TypeScale.body,
                    color = theme.muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            Meter(skill.confidence.score, color = theme.ink)
        }

        Column(Modifier.padding(horizontal = Space.gutter)) {
            SectionHeading(stringResource(R.string.review_autonomy))
            ChoiceRow {
                AutonomyLevel.entries.forEach { level ->
                    Choice(
                        text = stringResource(level.labelRes()),
                        selected = skill.autonomyLevel == level,
                        onClick = { viewModel.setSkillAutonomy(skill, level) },
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = Space.gutter)) {
            SectionHeading(stringResource(R.string.skill_edit_heading))
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                placeholder = { Text(stringResource(R.string.skill_edit_hint), style = TypeScale.body, color = theme.muted) },
                textStyle = TypeScale.body,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.ink,
                    unfocusedBorderColor = theme.line,
                    focusedTextColor = theme.ink,
                    unfocusedTextColor = theme.ink,
                    cursorColor = theme.ink,
                ),
            )
            Spacer(Modifier.height(12.dp))
            QuietButton(
                text = stringResource(R.string.skill_edit_review),
                onClick = { viewModel.previewEdit(skill, editText) },
                enabled = editText.isNotBlank(),
            )
        }

        val patches = state.patches.filter { it.skillId == skill.id && it.status == PatchStatus.PENDING }
        if (patches.isNotEmpty()) {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                SectionHeading(stringResource(R.string.skill_repairs))
                patches.forEach { candidate ->
                    RepairPanel(candidate, viewModel)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        SectionHeading(stringResource(R.string.skill_versions), Modifier.padding(horizontal = Space.gutter))
        Hairline(Modifier.padding(horizontal = Space.gutter))
        state.skillVersions.forEach { version ->
            Row(
                Modifier.fillMaxWidth().padding(start = Space.gutter, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.skill_version_label, version.version),
                        style = TypeScale.label,
                        color = theme.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        version.history.lastOrNull()?.reason ?: stringResource(R.string.skill_version_saved),
                        style = TypeScale.meta,
                        color = theme.muted,
                    )
                }
                Spacer(Modifier.width(12.dp))
                com.autobile.app.ui.design.TextAction(
                    text = stringResource(R.string.skill_version_restore),
                    onClick = { viewModel.rollbackSkill(skill.id, version.version) },
                )
            }
            Hairline(Modifier.padding(horizontal = Space.gutter))
        }

        Spacer(Modifier.height(32.dp))
        com.autobile.app.ui.design.TextAction(
            text = stringResource(R.string.skill_delete),
            onClick = { confirmDelete = true },
            color = theme.fault,
            modifier = Modifier.padding(horizontal = Space.gutter - 12.dp),
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.skill_delete_confirm_title, skill.name), style = TypeScale.title) },
            text = { Text(stringResource(R.string.skill_delete_confirm_body), style = TypeScale.body) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteSkill(skill) }) {
                    Text(stringResource(R.string.skill_delete), color = theme.fault)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = theme.raised,
        )
    }
}

/**
 * A proposed repair.
 *
 * One of the few places a panel is justified: this is a discrete decision with two outcomes,
 * and it should not read as another row in the list of facts above it.
 */
@Composable
private fun RepairPanel(candidate: SkillPatchCandidate, viewModel: AppViewModel) {
    Panel(
        background = if (candidate.requiresUserConfirmation) theme.raised else theme.raised,
        border = if (candidate.requiresUserConfirmation) theme.caution else theme.line,
    ) {
        Column {
            Text(candidate.summary, style = TypeScale.label, color = theme.ink)
            if (candidate.requiresUserConfirmation) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.skill_repair_meaning_warning),
                    style = TypeScale.meta,
                    color = theme.caution,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.autobile.app.ui.design.TextAction(
                    text = stringResource(R.string.skill_repair_apply),
                    onClick = { viewModel.reviewPatch(candidate, true) },
                )
                com.autobile.app.ui.design.TextAction(
                    text = stringResource(R.string.skill_repair_dismiss),
                    onClick = { viewModel.reviewPatch(candidate, false) },
                    color = theme.muted,
                )
            }
        }
    }
}

/** What a skill needs before it can run, as a short readable list. */
@Composable
private fun SemanticSkill.requirementSummary(): String {
    val parts = buildList {
        runtimeRequirements.requiredPackages.forEach { add(com.autobile.app.ui.appLabel(it)) }
        if (runtimeRequirements.requiresNetwork) add(stringResource(R.string.capability_cloud_ai))
        if (runtimeRequirements.requiresScreenshot) add(stringResource(R.string.capability_screen_understanding))
    }
    return if (parts.isEmpty()) stringResource(R.string.trigger_manual) else parts.joinToString(", ")
}
