package com.jarvis.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.core.ListenLanguage
import com.jarvis.agent.core.SecureStore.Secret
import com.jarvis.agent.core.SttEngine
import com.jarvis.agent.core.TtsEngine

@Composable
fun SettingsScreen() {
    val graph = JarvisApp.graph
    val prefs = graph.prefs
    val secure = graph.secure
    var saved by remember { mutableStateOf(false) }

    var anthropic by remember { mutableStateOf("") }
    var proxyToken by remember { mutableStateOf("") }
    var picovoice by remember { mutableStateOf("") }
    var eleven by remember { mutableStateOf("") }
    var openai by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(prefs.apiBaseUrl) }
    var model by remember { mutableStateOf(prefs.model) }
    var voiceId by remember { mutableStateOf(prefs.elevenLabsVoiceId) }
    var wake by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var overlay by remember { mutableStateOf(prefs.overlayEnabled) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var stt by remember { mutableStateOf(prefs.sttEngine) }
    var tts by remember { mutableStateOf(prefs.ttsEngine) }
    var lang by remember { mutableStateOf(prefs.listenLanguage) }
    var effort by remember { mutableStateOf(prefs.effort) }

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Section("API keys (Android Keystore mein encrypted)")
        KeyField("Claude API key", secure.has(Secret.ANTHROPIC_API_KEY), anthropic) { anthropic = it }
        KeyField("Picovoice AccessKey (wake word)", secure.has(Secret.PICOVOICE_ACCESS_KEY), picovoice) { picovoice = it }
        KeyField("ElevenLabs API key (optional)", secure.has(Secret.ELEVENLABS_API_KEY), eleven) { eleven = it }
        KeyField("OpenAI key for Whisper STT (optional)", secure.has(Secret.OPENAI_API_KEY), openai) { openai = it }

        Section("Claude")
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API base URL ya apna proxy") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        KeyField("Proxy token (sirf proxy ke liye)", secure.has(Secret.PROXY_TOKEN), proxyToken) { proxyToken = it }
        OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Effort (kam = tez jawab)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("low", "medium", "high").forEach { e ->
                FilterChip(selected = effort == e, onClick = { effort = e }, label = { Text(e) })
            }
        }

        Section("Awaaz")
        Toggle("\"Hey Jarvis\" wake word", wake) { wake = it }
        Toggle("Floating mic button", overlay) { overlay = it }
        Toggle("Phone on hone par shuru karein", boot) { boot = it }
        Text("Sunne ki zabaan")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ListenLanguage.entries.forEach { l ->
                FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l.name) })
            }
        }
        Text("Speech-to-text")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SttEngine.entries.forEach { e -> FilterChip(selected = stt == e, onClick = { stt = e }, label = { Text(e.name) }) }
        }
        Text("Text-to-speech")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TtsEngine.entries.forEach { e -> FilterChip(selected = tts == e, onClick = { tts = e }, label = { Text(e.name) }) }
        }
        OutlinedTextField(voiceId, { voiceId = it }, label = { Text("ElevenLabs voice ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        Button(
            onClick = {
                if (anthropic.isNotBlank()) secure.put(Secret.ANTHROPIC_API_KEY, anthropic)
                if (proxyToken.isNotBlank()) secure.put(Secret.PROXY_TOKEN, proxyToken)
                if (picovoice.isNotBlank()) secure.put(Secret.PICOVOICE_ACCESS_KEY, picovoice)
                if (eleven.isNotBlank()) secure.put(Secret.ELEVENLABS_API_KEY, eleven)
                if (openai.isNotBlank()) secure.put(Secret.OPENAI_API_KEY, openai)
                anthropic = ""; proxyToken = ""; picovoice = ""; eleven = ""; openai = ""
                prefs.apiBaseUrl = baseUrl
                prefs.model = model
                prefs.effort = effort
                prefs.elevenLabsVoiceId = voiceId
                prefs.wakeWordEnabled = wake
                prefs.overlayEnabled = overlay
                prefs.startOnBoot = boot
                prefs.sttEngine = stt
                prefs.ttsEngine = tts
                prefs.listenLanguage = lang
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (saved) Text("Mehfooz ho gaya. Wake word/bubble ki tabdeeli ke liye Jarvis dobara shuru karein.")
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun KeyField(label: String, isSet: Boolean, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (isSet) "$label ✓ (badalne ke liye likhein)" else label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
