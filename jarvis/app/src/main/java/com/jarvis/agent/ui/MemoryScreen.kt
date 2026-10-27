package com.jarvis.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.routines.RoutineTime
import kotlinx.coroutines.launch

/** What Jarvis remembers, and the scheduled routines. The user can delete anything here directly. */
@Composable
fun MemoryScreen() {
    val graph = JarvisApp.graph
    val dao = remember { graph.memoryDb.dao() }
    val scope = rememberCoroutineScope()
    val facts by remember { dao.factsFlow() }.collectAsState(initial = emptyList())
    val nicknames by remember { dao.nicknamesFlow() }.collectAsState(initial = emptyList())
    val routines by remember { dao.routinesFlow() }.collectAsState(initial = emptyList())
    val conversations by remember { dao.conversationsFlow() }.collectAsState(initial = emptyList())

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Header("Routines") }
        if (routines.isEmpty()) item { Hint("Koi routine nahi. Kahein: \"Har roz subah 7 baje mujhe aaj ka schedule batao\".") }
        items(routines, key = { "r${it.id}" }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("%s — %02d:%02d, %s".format(r.name, r.hour, r.minute, RoutineTime.describeDays(r.daysMask)), fontWeight = FontWeight.SemiBold)
                        Text(r.prompt, style = MaterialTheme.typography.bodySmall)
                        r.lastResult?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    }
                    Switch(checked = r.enabled, onCheckedChange = { on ->
                        scope.launch {
                            val updated = r.copy(enabled = on)
                            dao.updateRoutine(updated)
                            graph.routineScheduler.schedule(updated)
                        }
                    })
                    IconButton(onClick = {
                        scope.launch {
                            graph.routineScheduler.cancel(r.id)
                            dao.deleteRoutine(r.id)
                        }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                }
            }
        }

        item { Header("Facts") }
        if (facts.isEmpty()) item { Hint("Kahein: \"Yaad rakho ke meri ammi ka number contacts mein Shabana Khan hai\".") }
        items(facts, key = { "f${it.id}" }) { f ->
            DeletableRow(f.text) { scope.launch { dao.deleteFact(f.id) } }
        }

        item { Header("Contact nicknames") }
        items(nicknames, key = { "n${it.nickname}" }) { n ->
            DeletableRow("\"${n.nickname}\" → ${n.contactName}") { scope.launch { dao.deleteNickname(n.nickname) } }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Header("Recent conversations", Modifier.weight(1f))
                TextButton(onClick = { scope.launch { dao.clearConversations() } }) { Text("Clear") }
            }
        }
        items(conversations, key = { "c${it.id}" }) { c ->
            Text("• ${c.userText} → ${c.reply}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Color.Gray)

@Composable
private fun DeletableRow(text: String, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}
