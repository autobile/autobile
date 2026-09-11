package com.autobile.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class BarDestination(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * The navigation bar, floating clear of the screen edge.
 *
 * It carries a label only for the destination you are on. Three icons with three permanent
 * labels is a lot of standing text for a bar that is mostly being ignored, and the label on
 * the active item alone is enough to say where you are while letting the pill stay small
 * enough to read as an object rather than a wall.
 */
@Composable
fun FloatingBar(
    destinations: List<BarDestination>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Content scrolls underneath the bar, so it fades out rather than colliding with
        // it. Without this a row of text ends up half-hidden behind the pill and reads as
        // a layout mistake instead of as something further down the page.
        Box(
            Modifier
                .fillMaxWidth()
                .height(bottomInset + 108.dp)
                .background(
                    Brush.verticalGradient(
                        0f to theme.paper.copy(alpha = 0f),
                        0.45f to theme.paper.copy(alpha = 0.92f),
                        1f to theme.paper,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .padding(horizontal = Space.gutter)
                .padding(bottom = bottomInset + 12.dp),
            contentAlignment = Alignment.Center,
        ) {
        Row(
            modifier = Modifier
                .shadow(12.dp, CircleShape, clip = false, ambientColor = theme.ink, spotColor = theme.ink)
                .clip(CircleShape)
                .background(theme.raised)
                .border(1.dp, theme.line, CircleShape)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                BarItem(
                    destination = destination,
                    active = destination.key == selected,
                    onClick = { onSelect(destination.key) },
                )
            }
        }
        }
    }
}

@Composable
private fun BarItem(destination: BarDestination, active: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (active) theme.ink else theme.raised,
        label = "barItemBackground",
    )
    val foreground by animateColorAsState(
        targetValue = if (active) theme.paper else theme.muted,
        label = "barItemForeground",
    )
    val horizontal by animateDpAsState(if (active) 16.dp else 18.dp, label = "barItemPadding")

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.label,
            tint = foreground,
            modifier = Modifier.size(20.dp),
        )
        AnimatedVisibility(
            visible = active,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(destination.label, style = TypeScale.label, color = foreground, maxLines = 1)
            }
        }
    }
}

/**
 * The band shown while a run is in progress.
 *
 * This is the one place the interface raises its voice. Everything else is neutral, so an
 * automation driving the phone by itself is impossible to mistake for a resting screen, and
 * the way to stop it is in the same place every time.
 */
@Composable
fun LiveBand(
    skillName: String,
    step: String,
    stepIndex: Int,
    totalSteps: Int,
    repairNote: String?,
    stopLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = Space.gutter)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.liveWash)
            .border(1.dp, theme.live.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp)) { StateDot(theme.live) }
            Spacer(Modifier.width(12.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(skillName, style = TypeScale.heading, color = theme.ink, maxLines = 1)
                Spacer(Modifier.size(2.dp))
                Text(step, style = TypeScale.body, color = theme.ink, maxLines = 2)
                Spacer(Modifier.size(8.dp))
                Meter(
                    fraction = if (totalSteps <= 0) 0f else (stepIndex + 1f) / totalSteps,
                    color = theme.live,
                )
                repairNote?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = TypeScale.meta, color = theme.muted, maxLines = 2)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stopLabel,
                style = TypeScale.label,
                color = theme.live,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                maxLines = 1,
            )
        }
    }
}
