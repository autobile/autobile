package com.autobile.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The shared vocabulary of this interface.
 *
 * A row is the default container. Cards are reserved for the few things that are genuinely
 * a separate surface — a live run, a decision the user has to make — because when every
 * element is boxed, the boxes stop meaning anything and the screen reads as a list of
 * identical tiles rather than as information with a shape.
 */
object Space {
    /** The horizontal margin every screen shares. */
    val gutter = 20.dp
    val betweenSections = 32.dp
    val betweenRows = 0.dp
    val rowVertical = 16.dp
    val tight = 8.dp
    /** Clearance so the floating bar never covers the last row. */
    val barClearance = 96.dp
}

/** Prose is capped near 64 characters, which is where a line stops being comfortable. */
private val ProseWidth = 560.dp

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TypeScale.display,
        color = theme.ink,
        modifier = modifier.padding(top = 28.dp, bottom = 20.dp),
    )
}

/** Top inset for screens that draw their own title instead of an app bar. */
@Composable
fun StatusBarSpacer() {
    Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding()))
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TypeScale.title,
        color = theme.ink,
        modifier = modifier.padding(top = Space.betweenSections, bottom = 12.dp),
    )
}

/** Body prose. Width-capped so long explanations stay readable. */
@Composable
fun Statement(text: String, modifier: Modifier = Modifier, color: Color = theme.muted) {
    Text(text = text, style = TypeScale.body, color = color, modifier = modifier.widthIn(max = ProseWidth))
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(theme.line))
}

/**
 * The default container: a tappable row.
 *
 * [trailing] is given its own slot rather than being appended to the title, so a value can
 * sit hard against the right margin and stay readable when the title has to wrap.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.gutter, vertical = Space.rowVertical),
        // Aligned to the top so a leading marker sits beside the title rather than
        // drifting down to the subtitle as the row grows.
        verticalAlignment = Alignment.Top,
    ) {
        leading?.let {
            Box(Modifier.padding(top = 7.dp)) { it() }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = TypeScale.heading,
                color = theme.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = TypeScale.body,
                    color = theme.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(text = it, style = TypeScale.meta, color = theme.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(16.dp))
            Box(Modifier.padding(top = 1.dp)) { it() }
        }
    }
}

/**
 * A label and its value on one line.
 *
 * The value takes the space it needs and the label yields, because the value is the part
 * being read. Both wrap rather than truncate: a clipped runtime requirement is worse than
 * a two-line row.
 */
@Composable
fun FieldRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = theme.ink) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(text = label, style = TypeScale.body, color = theme.muted, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = TypeScale.label,
            color = valueColor,
            modifier = Modifier.weight(1.4f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** A figure read as a quantity, sized to sit calmly at the end of a row. */
@Composable
fun Figure(text: String, color: Color = theme.ink) {
    Text(text = text, style = TypeScale.figure, color = color)
}

@Composable
fun StateDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * A thin horizontal meter.
 *
 * Deliberately not a Material progress bar: this shows a standing level rather than work in
 * progress, and the difference should be visible without reading the label.
 */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = theme.ink) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(theme.line),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

/**
 * A surface, used only where content genuinely is a separate object.
 *
 * Flat with a hairline instead of a shadow. Shadows on every block are what makes an
 * interface read as a stack of identical tiles.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    background: Color = theme.raised,
    border: Color = theme.line,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) { content() }
}

/**
 * The main action on a screen.
 *
 * [startsAgent] is what decides the colour. Only an action that actually sets the agent
 * running gets the live accent; everything else is ink. Without that distinction every
 * screen ends up with a saturated button on it and the colour stops meaning anything,
 * which is exactly the signal a person needs when their phone is about to act by itself.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    startsAgent: Boolean = false,
) {
    val accent = if (startsAgent) theme.live else theme.ink
    val background = if (enabled) accent else theme.line
    val foreground = if (enabled) (if (startsAgent) theme.onLive else theme.paper) else theme.muted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = TypeScale.label, color = foreground, maxLines = 1)
    }
}

@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val foreground = if (enabled) theme.ink else theme.muted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, theme.line, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = TypeScale.label, color = foreground, maxLines = 1)
    }
}

/** A low-emphasis action that reads as text, for the secondary path out of a screen. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = theme.live) {
    Text(
        text = text,
        style = TypeScale.label,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * A row of options that wraps.
 *
 * Wrapping rather than scrolling sideways: a horizontally scrolling row cuts the selected
 * option in half whenever it sits past the fold, and translated labels are not the same
 * width as the English ones they were laid out against.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A selectable option in a small set, used where a dropdown would be heavier than the choice. */
@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) theme.ink else Color.Transparent)
            .border(1.dp, if (selected) theme.ink else theme.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = TypeScale.meta,
            color = if (selected) theme.paper else theme.muted,
            maxLines = 1,
        )
    }
}

/**
 * An empty state.
 *
 * Written as an invitation rather than a shrug: the screen says what would be here and how
 * to put something there.
 */
@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 40.dp)) {
        Text(title, style = TypeScale.heading, color = theme.ink)
        Spacer(Modifier.height(6.dp))
        Statement(detail)
    }
}

/** Bottom padding that keeps scrollable content clear of the floating bar. */
@Composable
fun barClearancePadding(): PaddingValues = PaddingValues(
    bottom = Space.barClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
)

/**
 * Switch colours stated outright.
 *
 * With the default scheme an enabled control that is off renders paler than a disabled
 * one, so the two read as the opposite of what they are. Here the track carries the state
 * and the thumb carries availability.
 */
@Composable
fun autobileSwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = theme.paper,
    checkedTrackColor = theme.live,
    checkedBorderColor = theme.live,
    uncheckedThumbColor = theme.muted,
    uncheckedTrackColor = theme.paper,
    uncheckedBorderColor = theme.line,
    disabledCheckedThumbColor = theme.paper,
    disabledCheckedTrackColor = theme.line,
    disabledUncheckedThumbColor = theme.line,
    disabledUncheckedTrackColor = theme.paper,
    disabledUncheckedBorderColor = theme.line,
)

@Composable
fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = LocalContentColor.current, modifier = Modifier.size(22.dp))
    }
}
