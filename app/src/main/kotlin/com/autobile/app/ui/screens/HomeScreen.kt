package com.autobile.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.autobile.app.R
import com.autobile.app.ui.AppScreen
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.asPercent
import com.autobile.app.ui.describe
import com.autobile.app.ui.design.EmptyState
import com.autobile.app.ui.design.Hairline
import com.autobile.app.ui.design.ListRow
import com.autobile.app.ui.design.ScreenTitle
import com.autobile.app.ui.design.Space
import com.autobile.app.ui.design.StateDot
import com.autobile.app.ui.design.StatusBarSpacer
import com.autobile.app.ui.design.TypeScale
import com.autobile.app.ui.design.barClearancePadding
import com.autobile.app.ui.design.theme
import com.autobile.core.model.SemanticSkill

@Composable
fun HomeScreen(state: AppUiState, viewModel: AppViewModel) {
    var command by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = barClearancePadding()) {
        item {
            StatusBarSpacer()
            ScreenTitle(stringResource(R.string.app_name), Modifier.padding(horizontal = Space.gutter))
        }

        // The resting state says what will happen if something starts, not that nothing is
        // happening — the reassurance a person wants before handing over their phone.
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 6.dp)) { StateDot(theme.muted) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.home_agent_ready), style = TypeScale.label, color = theme.ink)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.home_agent_ready_detail),
                        style = TypeScale.meta,
                        color = theme.muted,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            CommandField(
                value = command,
                onValueChange = { command = it },
                onSubmit = {
                    if (command.isNotBlank()) {
                        viewModel.submitCommand(command)
                        command = ""
                    }
                },
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.gutter, end = 8.dp, top = 36.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_automations),
                    style = TypeScale.title,
                    color = theme.ink,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { viewModel.navigate(AppScreen.TEACH) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = theme.live, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_teach), style = TypeScale.label, color = theme.live)
                }
            }
        }

        if (state.skills.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    detail = stringResource(R.string.home_empty_detail),
                )
            }
        } else {
            item { Hairline(Modifier.padding(horizontal = Space.gutter)) }
            items(state.skills, key = { it.id }) { skill ->
                SkillRow(skill) { viewModel.selectSkill(skill.id) }
                Hairline(Modifier.padding(horizontal = Space.gutter))
            }
        }
    }
}

@Composable
private fun SkillRow(skill: SemanticSkill, onClick: () -> Unit) {
    ListRow(
        title = skill.name,
        subtitle = skill.goal,
        meta = skill.trigger.describe(),
        leading = { StateDot(if (skill.enabled) theme.ink else theme.line) },
        trailing = {
            Text(
                text = skill.confidence.score.asPercent(),
                style = TypeScale.figure,
                color = theme.muted,
            )
        },
        onClick = onClick,
    )
}

/**
 * The natural-language entry point.
 *
 * A single field with a send affordance, rather than a prominent search bar: most people
 * will use the list below it, and the field is here for the times when describing the task
 * is faster than finding it.
 */
@Composable
private fun CommandField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        placeholder = { Text(stringResource(R.string.home_command_hint), style = TypeScale.body, color = theme.muted) },
        textStyle = TypeScale.body,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = theme.ink,
            unfocusedBorderColor = theme.line,
            focusedTextColor = theme.ink,
            unfocusedTextColor = theme.ink,
            cursorColor = theme.ink,
        ),
        trailingIcon = {
            if (value.isNotBlank()) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(theme.ink)
                        .clickable(onClick = onSubmit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = stringResource(R.string.home_command_send),
                        tint = theme.paper,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}
