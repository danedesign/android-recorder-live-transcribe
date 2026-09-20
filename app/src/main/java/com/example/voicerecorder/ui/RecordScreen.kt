package com.example.voicerecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicerecorder.PauseTest
import com.example.voicerecorder.UiState

/** "3:07" style clock. */
fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun RecordScreen(ui: UiState, padding: PaddingValues, onToggle: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Debug line: which engine is active, how many seamless restarts, and the latest status.
        Text(
            "Engine: ${ui.engineLabel}  |  restarts: ${ui.restarts}" +
                if (ui.status.isNotEmpty()) "\n${ui.status}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(formatClock(ui.elapsedMs), fontSize = 44.sp, fontWeight = FontWeight.Light)

        // One big Record/Stop button. Recording only ends when this is tapped.
        Button(
            onClick = onToggle,
            shape = CircleShape,
            modifier = Modifier.size(150.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ui.recording) Color(0xFF37474F) else Color(0xFFD32F2F),
                contentColor = Color.White,
            ),
        ) {
            Text(if (ui.recording) "STOP" else "REC", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        if (ui.selfTest) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Pause test - ${formatClock((PauseTest.DURATION_MS - ui.elapsedMs).coerceAtLeast(0))} left", fontWeight = FontWeight.Bold)
                    Text(PauseTest.instruction(ui.elapsedMs), fontSize = 18.sp)
                }
            }
        }
        ui.testResult?.let { r ->
            if (!ui.recording) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (r.passed) Color(0xFFC8E6C9) else Color(0xFFFFCDD2),
                )) {
                    Text(r.summary, Modifier.fillMaxWidth().padding(12.dp), color = Color.Black)
                }
            }
        }

        Card(Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            // Keep the newest words in view while recording.
            LaunchedEffect(ui.committed.size, ui.partial) { scroll.animateScrollTo(scroll.maxValue) }
            SelectionContainer {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                    if (ui.committed.isEmpty() && ui.partial.isEmpty()) {
                        Text(
                            if (ui.recording) "Listening..." else
                                "Tap REC to start. Recording only stops when you tap STOP - never on silence.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(buildAnnotatedString {
                            append(ui.committed.joinToString("\n"))
                            if (ui.partial.isNotEmpty()) {
                                if (ui.committed.isNotEmpty()) append("\n")
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)) {
                                    append(ui.partial)
                                }
                            }
                        }, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
