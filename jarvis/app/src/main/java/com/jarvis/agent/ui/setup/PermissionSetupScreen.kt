package com.jarvis.agent.ui.setup

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.agent.ui.theme.JarvisOk
import com.jarvis.agent.ui.theme.JarvisWarn

/**
 * Step-by-step permission setup. Each card explains in Roman Urdu why the permission is needed, shows
 * whether it is granted, and opens the right system dialog or settings page. Re-checks on resume.
 */
@Composable
fun PermissionSetupScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val items = remember { PermissionCatalog.items() }

    val grantedMap = remember(tick) { items.associate { it.id to it.isGranted(context) } }
    val requiredMissing = items.count { it.required && grantedMap[it.id] != true }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Permissions setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Jarvis aap ka phone aap ki taraf se chalata hai, is liye isay kuch ijazatein chahiye. " +
                        "Har ijazat ke neeche likha hai ke kyun. \"Zaroori\" wali dena lazmi hai; baqi sirf un features ke liye hain.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(items, key = { it.id }) { item ->
            val granted = grantedMap[item.id] == true
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (granted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = if (granted) JarvisOk else JarvisWarn,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(item.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (item.required) "Zaroori" else "Optional", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(item.why, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                    if (!granted) {
                        OutlinedButton(onClick = {
                            if (item.runtime.isNotEmpty()) {
                                launcher.launch(item.runtime.toTypedArray())
                            } else {
                                item.settingsIntent?.let { factory ->
                                    try {
                                        context.startActivity(factory(context))
                                    } catch (_: ActivityNotFoundException) {
                                    }
                                }
                            }
                        }) { Text("Ijazat dein") }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(vertical = 16.dp)) {
                if (requiredMissing > 0) {
                    Text("$requiredMissing zaroori ijazatein baqi hain.", color = JarvisWarn)
                }
                Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                    Text(if (requiredMissing == 0) "Setup mukammal — Jarvis shuru karein" else "Abhi ke liye aage barhein")
                }
            }
        }
    }
}
