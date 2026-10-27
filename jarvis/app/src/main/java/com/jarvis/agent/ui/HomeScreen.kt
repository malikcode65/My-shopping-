package com.jarvis.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.core.JarvisRuntime
import com.jarvis.agent.core.KillSwitch
import com.jarvis.agent.service.JarvisService
import com.jarvis.agent.ui.theme.JarvisDanger

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val state by JarvisRuntime.state.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Jarvis", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val graph = com.jarvis.agent.JarvisApp.graph
        val warnings = buildList {
            if (graph.prefs.usesDirectApi && !graph.secure.has(com.jarvis.agent.core.SecureStore.Secret.ANTHROPIC_API_KEY)) {
                add("Claude API key nahi hai — Settings mein daalein.")
            }
            if (!com.jarvis.agent.ui.setup.PermissionCatalog.isAccessibilityEnabled(context)) {
                add("Accessibility band hai — Jarvis apps nahi chala sakta aur volume kill switch kaam nahi karega.")
            }
        }
        warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Status: ${phaseLabel(state.phase)}", fontWeight = FontWeight.SemiBold)
                if (state.currentStep > 0) Text("Step ${state.currentStep}: ${state.currentAction}")
                state.wakeWordError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.lastHeard.isNotBlank()) Text("Aap: ${state.lastHeard}", modifier = Modifier.padding(top = 8.dp))
                if (state.lastReply.isNotBlank()) Text("Jarvis: ${state.lastReply}", modifier = Modifier.padding(top = 4.dp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            FilledIconButton(
                onClick = {
                    if (!state.serviceRunning) JarvisService.start(context)
                    JarvisService.pushToTalk(context)
                },
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
            ) { Icon(Icons.Filled.Mic, contentDescription = "Push to talk", modifier = Modifier.size(48.dp)) }
        }
        Text("Mic dabayein ya \"Hey Jarvis\" kahein", modifier = Modifier.align(Alignment.CenterHorizontally))

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Ya command type karein") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = {
                    val t = typed.trim()
                    if (t.isNotEmpty()) {
                        JarvisService.runText(context, t)
                        typed = ""
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.serviceRunning) {
                OutlinedButton(onClick = { JarvisService.stop(context) }) { Text("Jarvis band karein") }
            } else {
                Button(onClick = { JarvisService.start(context) }) { Text("Jarvis shuru karein") }
            }
            Button(
                onClick = { KillSwitch.trigger("in-app button") },
                colors = ButtonDefaults.buttonColors(containerColor = JarvisDanger),
            ) { Text("STOP sab kuch") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Kill switch: volume-down 3 dafa jaldi dabayein, notification ka STOP button, ya floating button par long-press.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun phaseLabel(p: AgentPhase) = when (p) {
    AgentPhase.OFF -> "Band / push-to-talk"
    AgentPhase.WAITING_WAKE_WORD -> "\"Hey Jarvis\" ka intezar"
    AgentPhase.LISTENING -> "Sun raha hoon…"
    AgentPhase.THINKING -> "Soch raha hoon…"
    AgentPhase.ACTING -> "Kaam kar raha hoon…"
    AgentPhase.WAITING_CONFIRMATION -> "Aap ki tasdeeq ka intezar"
    AgentPhase.SPEAKING -> "Bol raha hoon…"
}
