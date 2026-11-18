package com.quickmath.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(
    ui: UiState,
    overlayGranted: Boolean,
    prefs: AppPrefs,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGrantOverlay: () -> Unit,
    onBatterySettings: () -> Unit
) {
    var interval by remember { mutableStateOf(prefs.intervalMs.toFloat()) }
    var bubbleSize by remember { mutableStateOf(prefs.bubbleSizeSp.toFloat()) }
    var opacity by remember { mutableStateOf(prefs.opacityPct.toFloat()) }
    var duration by remember { mutableStateOf(prefs.displaySec.toFloat()) }
    var haptic by remember { mutableStateOf(prefs.haptic) }
    var opAdd by remember { mutableStateOf(prefs.opAdd) }
    var opSub by remember { mutableStateOf(prefs.opSub) }
    var opMul by remember { mutableStateOf(prefs.opMul) }
    var opDiv by remember { mutableStateOf(prefs.opDiv) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "QuickMath Overlay",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // ---- status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(if (ui.running) Color(0xFF22C55E) else Color(0xFF9CA3AF), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ui.running) "Monitoring" else "Stopped",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text("OCR: ${ui.ocrStatus}")
                if (ui.message.isNotEmpty()) {
                    Text(ui.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onStart, enabled = !ui.running, modifier = Modifier.weight(1f)) {
                Text("Start Monitoring")
            }
            OutlinedButton(onClick = onStop, enabled = ui.running, modifier = Modifier.weight(1f)) {
                Text("Stop Monitoring")
            }
        }

        if (!overlayGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"Display over other apps\" is not allowed yet. The answer bubble needs it.")
                    Button(onClick = onGrantOverlay) { Text("Allow overlay") }
                }
            }
        }

        // ---- last result
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last question", style = MaterialTheme.typography.labelMedium)
                Text(ui.lastQuestion, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(6.dp))
                Text("Last answer", style = MaterialTheme.typography.labelMedium)
                Text(
                    ui.lastAnswer,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ---- bubble preview
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bubble preview", style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .background(
                            Color(0xFF111827).copy(alpha = opacity / 100f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = (bubbleSize * 0.7f).dp, vertical = (bubbleSize * 0.3f).dp)
                ) {
                    Text(
                        "504812",
                        color = Color.White,
                        fontSize = bubbleSize.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "On screen: drag to move, tap to shrink to a dot, tap the dot to expand.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ---- stats
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Statistics", style = MaterialTheme.typography.labelMedium)
                val total = ui.solved + ui.skipped
                val rate = if (total > 0) ui.solved * 100 / total else 0
                Text("Average detection time: ${ui.avgMs} ms")
                Text("Answers shown: ${ui.solved}")
                Text("Unclear reads skipped: ${ui.skipped}")
                Text("Clean-read rate: $rate%")
                Text("Frames scanned: ${ui.scans}")
            }
        }

        // ---- settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleMedium)

                SliderRow(
                    "Scan pause (lower = faster, more battery)", "${interval.toInt()} ms",
                    interval, 100f..1000f
                ) {
                    interval = it
                    prefs.intervalMs = it.toInt()
                }
                SliderRow("Bubble size", "${bubbleSize.toInt()} sp", bubbleSize, 14f..48f) {
                    bubbleSize = it
                    prefs.bubbleSizeSp = it.toInt()
                }
                SliderRow("Bubble opacity", "${opacity.toInt()}%", opacity, 30f..100f) {
                    opacity = it
                    prefs.opacityPct = it.toInt()
                }
                SliderRow(
                    "Answer display time",
                    if (duration < 1f) "until next question" else "${duration.toInt()} s",
                    duration, 0f..30f
                ) {
                    duration = it
                    prefs.displaySec = it.toInt()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Haptic feedback")
                    Switch(checked = haptic, onCheckedChange = {
                        haptic = it
                        prefs.haptic = it
                    })
                }

                Text("Supported operations", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth()) {
                    OpRow("Add +", opAdd, Modifier.weight(1f)) {
                        opAdd = it
                        prefs.opAdd = it
                    }
                    OpRow("Subtract \u2212", opSub, Modifier.weight(1f)) {
                        opSub = it
                        prefs.opSub = it
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    OpRow("Multiply \u00D7", opMul, Modifier.weight(1f)) {
                        opMul = it
                        prefs.opMul = it
                    }
                    OpRow("Divide \u00F7", opDiv, Modifier.weight(1f)) {
                        opDiv = it
                        prefs.opDiv = it
                    }
                }
            }
        }

        // ---- battery + tips
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Battery and reliability", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Motorola phones can stop background apps. Set Battery to Unrestricted for " +
                        "QuickMath Overlay (Settings > Apps > QuickMath Overlay > Battery).",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = onBatterySettings) { Text("Open battery settings") }
                Text(
                    "When Android asks what to share, choose \"Entire screen\" (not \"A single app\"). " +
                        "Questions need a space on both sides of - and / (or use \u2212 and \u00F7).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Text("$label: $valueText", style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun OpRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
