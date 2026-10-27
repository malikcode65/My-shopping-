package com.jarvis.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.log.ActionLogEntry
import com.jarvis.agent.ui.theme.JarvisCyan
import com.jarvis.agent.ui.theme.JarvisDanger
import com.jarvis.agent.ui.theme.JarvisOk
import com.jarvis.agent.ui.theme.JarvisWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything Jarvis did: tasks, every tool call with its risk level, confirmations and results. */
@Composable
fun ActionLogScreen() {
    val logger = JarvisApp.graph.actionLog
    val flow = remember { logger.recent(400) }
    val entries by flow.collectAsState(initial = emptyList())
    val fmt = remember { SimpleDateFormat("d MMM HH:mm:ss", Locale.ENGLISH) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text("Action log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { logger.clear() }) { Text("Clear") }
        }
        if (entries.isEmpty()) Text("Abhi tak kuch nahi hua.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.id }) { e -> LogRow(e, fmt.format(Date(e.timestamp))) }
        }
    }
}

@Composable
private fun LogRow(e: ActionLogEntry, time: String) {
    val color = when {
        e.kind == "KILL" || e.kind == "ERROR" -> JarvisDanger
        e.risk == "HIGH" -> JarvisDanger
        e.risk == "MEDIUM" -> JarvisWarn
        e.kind == "TASK" || e.kind == "REPLY" -> JarvisCyan
        e.kind == "RESULT" && e.summary.startsWith("Failed") -> JarvisWarn
        else -> JarvisOk
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text(e.kind + (e.risk?.let { " · $it" } ?: ""), color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
            Text(e.summary, style = MaterialTheme.typography.bodyMedium)
            e.outcome?.takeIf { it.isNotBlank() }?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        }
    }
}
