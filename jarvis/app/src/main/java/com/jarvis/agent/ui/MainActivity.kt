package com.jarvis.agent.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.FragmentActivity
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.service.JarvisService
import com.jarvis.agent.ui.setup.PermissionSetupScreen
import com.jarvis.agent.ui.theme.JarvisTheme

enum class Screen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    SETUP("Permissions", Icons.Filled.Security),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** FragmentActivity (not ComponentActivity) so BiometricPrompt can be used by child screens. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = JarvisApp.graph.prefs
        setContent {
            JarvisTheme {
                var screen by rememberSaveable { mutableStateOf(if (prefs.setupDone) Screen.HOME else Screen.SETUP) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Screen.entries.forEach { s ->
                                NavigationBarItem(
                                    selected = screen == s,
                                    onClick = { screen = s },
                                    icon = { Icon(s.icon, contentDescription = s.label) },
                                    label = { Text(s.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ScreenContent(screen) { next ->
                            if (next == Screen.HOME && !prefs.setupDone) {
                                prefs.setupDone = true
                                JarvisService.start(this@MainActivity)
                            }
                            screen = next
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen, navigate: (Screen) -> Unit) {
    when (screen) {
        Screen.HOME -> HomeScreen()
        Screen.SETUP -> PermissionSetupScreen(onFinished = { navigate(Screen.HOME) })
        Screen.SETTINGS -> SettingsScreen()
    }
}
