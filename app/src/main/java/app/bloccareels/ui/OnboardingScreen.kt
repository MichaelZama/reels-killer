package app.bloccareels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bloccareels.R

/** Primo avvio in tre passaggi. */
@Composable
fun OnboardingScreen(serviceOn: Boolean, batteryOk: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val total = 3

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.onb_step, step + 1, total),
            color = BrColors.TestoSecondario,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(32.dp))
        Image(
            painter = painterResource(
                when (step) {
                    0 -> R.drawable.ill_foglia
                    1 -> R.drawable.ill_tazza
                    else -> R.drawable.ill_tramonto
                },
            ),
            contentDescription = null,
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(BrColors.Superficie)
                .padding(24.dp),
        )
        Spacer(Modifier.height(32.dp))

        val (title, text) = when (step) {
            0 -> R.string.onb1_title to R.string.onb1_text
            1 -> R.string.onb2_title to R.string.onb2_text
            else -> R.string.onb3_title to R.string.onb3_text
        }
        Text(
            stringResource(title),
            color = BrColors.Testo,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(text),
            color = BrColors.TestoSecondario,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        when (step) {
            1 -> {
                if (serviceOn) {
                    DoneLine(stringResource(R.string.onb2_done))
                } else {
                    PrimaryButton(stringResource(R.string.onb2_open)) { SystemStatus.openAccessibilitySettings(context) }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.onb2_restricted),
                        color = BrColors.TestoSecondario,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        onClick = { SystemStatus.openAppInfo(context) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.onb2_open_app_info), color = BrColors.Accento) }
                }
            }
            2 -> {
                if (batteryOk) {
                    DoneLine(stringResource(R.string.onb3_done))
                } else {
                    PrimaryButton(stringResource(R.string.onb3_open)) { SystemStatus.requestIgnoreBatteryOptimizations(context) }
                }
                if (SystemStatus.isXiaomi()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.xiaomi_autostart_note),
                        color = BrColors.TestoSecondario,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        onClick = { SystemStatus.openAutostartSettings(context) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.xiaomi_autostart_button), color = BrColors.Accento) }
                }
            }
        }

        Spacer(Modifier.height(40.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) {
                TextButton(onClick = { step-- }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.back), color = BrColors.TestoSecondario)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            val last = step == total - 1
            OutlinedButton(
                onClick = { if (last) onFinish() else step++ },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            last -> R.string.onb_finish
                            step == 1 && !serviceOn -> R.string.onb_skip
                            step == 2 && !batteryOk -> R.string.onb_skip
                            else -> R.string.onb_next
                        },
                    ),
                    color = BrColors.Testo,
                )
            }
        }
    }
}

@Composable
private fun DoneLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = BrColors.Testo, fontSize = 15.sp)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = BrColors.Accento, contentColor = BrColors.SuAccento),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}
