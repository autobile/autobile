package com.autobile.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.autobile.app.R
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.theme
import com.autobile.app.ui.describe
import com.autobile.app.ui.labelRes
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.SkillStep
import com.autobile.runtime.edit.SkillEditPreview

/**
 * The interruption that asks before something irreversible happens.
 *
 * It names the action first and the reason second, because a person woken by this needs to
 * know what is about to happen before they need to know why they are being asked.
 */
@Composable
fun RiskConfirmationDialog(step: SkillStep, decision: RiskDecision, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = {
            Text(
                text = step.description.ifBlank { step.target.intentLabel },
                style = TypeScale.title,
                color = theme.ink,
            )
        },
        text = {
            Column {
                Text(decision.reason, style = TypeScale.body, color = theme.ink)
                if (decision.categories.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    decision.categories.forEach { category ->
                        Text(
                            text = stringResource(category.labelRes()),
                            style = TypeScale.meta,
                            color = theme.caution,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(stringResource(R.string.risk_allow), style = TypeScale.label, color = theme.live)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Text(stringResource(R.string.risk_decline), style = TypeScale.label, color = theme.muted)
            }
        },
        containerColor = theme.raised,
    )
}

/**
 * Shows what a plain-language edit would actually do before it is applied.
 *
 * The before and after are both shown because the interesting failure is not a refused
 * edit — it is an edit that succeeds and means something the user did not intend.
 */
@Composable
fun EditConfirmationDialog(preview: SkillEditPreview.Ready, viewModel: AppViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissEditPreview,
        title = { Text(stringResource(R.string.edit_title), style = TypeScale.title, color = theme.ink) },
        text = {
            Column {
                Text(preview.summary, style = TypeScale.body, color = theme.ink)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.edit_before), style = TypeScale.meta, color = theme.muted)
                Text(preview.original.describeForDiff(), style = TypeScale.body, color = theme.muted)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.edit_after), style = TypeScale.meta, color = theme.muted)
                Text(preview.updated.describeForDiff(), style = TypeScale.body, color = theme.ink)
                if (preview.meaningChanged) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.edit_meaning_warning),
                        style = TypeScale.meta,
                        color = theme.caution,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.applyEdit(preview) }) {
                Text(stringResource(R.string.edit_apply), style = TypeScale.label, color = theme.live)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissEditPreview) {
                Text(stringResource(R.string.action_cancel), style = TypeScale.label, color = theme.muted)
            }
        },
        containerColor = theme.raised,
    )
}

@Composable
fun EditRejectedDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_rejected_title), style = TypeScale.title, color = theme.ink) },
        text = { Text(reason, style = TypeScale.body, color = theme.ink) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok), style = TypeScale.label, color = theme.live)
            }
        },
        containerColor = theme.raised,
    )
}

/** One skill summarised in two lines, so a change can be read at a glance. */
@Composable
private fun com.autobile.core.model.SemanticSkill.describeForDiff(): String {
    val schedule = trigger.describe()
    return "$name\n$schedule\n$goal"
}
