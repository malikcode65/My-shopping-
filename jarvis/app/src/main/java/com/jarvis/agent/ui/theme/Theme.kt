package com.jarvis.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val JarvisCyan = Color(0xFF22D3EE)
val JarvisBg = Color(0xFF0B1320)
val JarvisSurface = Color(0xFF14213A)
val JarvisDanger = Color(0xFFEF4444)
val JarvisOk = Color(0xFF22C55E)
val JarvisWarn = Color(0xFFF59E0B)

private val scheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = Color(0xFF00242B),
    background = JarvisBg,
    surface = JarvisSurface,
    surfaceVariant = Color(0xFF1C2B47),
    onBackground = Color(0xFFE6EDF7),
    onSurface = Color(0xFFE6EDF7),
    error = JarvisDanger,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
