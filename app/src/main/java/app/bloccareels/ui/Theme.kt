package app.bloccareels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App palette. A single, dark theme. */
object AppColors {
    val Background = Color(0xFF12181F)
    val Surface = Color(0xFF1C2530)
    val Border = Color(0xFF2A3541)
    val BorderStrong = Color(0xFF5E6C7A)
    val TextPrimary = Color(0xFFE8ECEF)
    val TextSecondary = Color(0xFF9AA7B4)
    val Accent = Color(0xFF4FB0A5)
    val OnAccent = Color(0xFF0E1418)
    val Warning = Color(0xFFE0A84F)
}

private val scheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = AppColors.OnAccent,
    secondary = AppColors.Accent,
    onSecondary = AppColors.OnAccent,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Background,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.Surface,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.Surface,
    surfaceContainerHighest = AppColors.Surface,
    outline = AppColors.BorderStrong,
    outlineVariant = AppColors.Border,
    error = AppColors.Warning,
    onError = AppColors.OnAccent,
)

@Composable
fun ReelsKillerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = AppColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(start = 4.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.Surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AppColors.OnAccent,
    checkedTrackColor = AppColors.Accent,
    checkedBorderColor = AppColors.Accent,
    uncheckedThumbColor = AppColors.BorderStrong,
    uncheckedTrackColor = AppColors.Background,
    uncheckedBorderColor = AppColors.BorderStrong,
    disabledUncheckedTrackColor = AppColors.Background,
    disabledUncheckedBorderColor = AppColors.Border,
    disabledUncheckedThumbColor = AppColors.Border,
)

/** Row with a switch: the whole row is tappable and read as a single control. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    note: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AppColors.TextPrimary, fontSize = 16.sp)
            if (note != null) Text(note, color = AppColors.TextSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = appSwitchColors())
    }
}

/** Choice among a few values, as side-by-side buttons. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSel = option == selected
            val desc = description(option)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isSel) AppColors.Accent else AppColors.Surface)
                    .border(1.dp, if (isSel) AppColors.Accent else AppColors.Border, RoundedCornerShape(24.dp))
                    .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(option) })
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = if (isSel) AppColors.OnAccent else AppColors.TextPrimary,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
