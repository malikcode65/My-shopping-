package com.jarvis.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.core.KillSwitch
import com.jarvis.agent.core.Notifications

/** Starts Jarvis after reboot. On Android 14+ a microphone service cannot start at boot, so we notify. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = JarvisApp.graph.prefs
        if (!prefs.startOnBoot || !prefs.setupDone) return
        try {
            JarvisService.start(context)
        } catch (e: Exception) {
            Notifications.simpleAlert(context, Notifications.ID_BOOT, "Jarvis", "Jarvis start karne ke liye yahan tap karein.")
        }
    }
}

/** The STOP button in the notification. */
class KillSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_KILL) KillSwitch.trigger("notification button")
    }

    companion object {
        const val ACTION_KILL = "com.jarvis.agent.KILL"
    }
}
