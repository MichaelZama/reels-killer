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

/** Palette dell'app. Un solo tema, scuro. */
object BrColors {
    val Sfondo = Color(0xFF12181F)
    val Superficie = Color(0xFF1C2530)
    val Bordo = Color(0xFF2A3541)
    val BordoForte = Color(0xFF5E6C7A)
    val Testo = Color(0xFFE8ECEF)
    val TestoSecondario = Color(0xFF9AA7B4)
    val Accento = Color(0xFF4FB0A5)
    val SuAccento = Color(0xFF0E1418)
    val Avviso = Color(0xFFE0A84F)
}

private val scheme = darkColorScheme(
    primary = BrColors.Accento,
    onPrimary = BrColors.SuAccento,
    secondary = BrColors.Accento,
    onSecondary = BrColors.SuAccento,
    background = BrColors.Sfondo,
    onBackground = BrColors.Testo,
    surface = BrColors.Sfondo,
    onSurface = BrColors.Testo,
    surfaceVariant = BrColors.Superficie,
    onSurfaceVariant = BrColors.TestoSecondario,
    surfaceContainer = BrColors.Superficie,
    surfaceContainerHigh = BrColors.Superficie,
    surfaceContainerHighest = BrColors.Superficie,
    outline = BrColors.BordoForte,
    outlineVariant = BrColors.Bordo,
    error = BrColors.Avviso,
    onError = BrColors.SuAccento,
)

@Composable
fun BloccaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = BrColors.TestoSecondario,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(start = 4.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BrColors.Superficie)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun brSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = BrColors.SuAccento,
    checkedTrackColor = BrColors.Accento,
    checkedBorderColor = BrColors.Accento,
    uncheckedThumbColor = BrColors.BordoForte,
    uncheckedTrackColor = BrColors.Sfondo,
    uncheckedBorderColor = BrColors.BordoForte,
    disabledUncheckedTrackColor = BrColors.Sfondo,
    disabledUncheckedBorderColor = BrColors.Bordo,
    disabledUncheckedThumbColor = BrColors.Bordo,
)

/** Riga con interruttore: tutta la riga è toccabile e letta come un unico controllo. */
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
            Text(title, color = BrColors.Testo, fontSize = 16.sp)
            if (note != null) Text(note, color = BrColors.TestoSecondario, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = brSwitchColors())
    }
}

/** Scelta tra pochi valori, come pulsanti affiancati. */
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
                    .background(if (isSel) BrColors.Accento else BrColors.Superficie)
                    .border(1.dp, if (isSel) BrColors.Accento else BrColors.Bordo, RoundedCornerShape(24.dp))
                    .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(option) })
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = if (isSel) BrColors.SuAccento else BrColors.Testo,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
