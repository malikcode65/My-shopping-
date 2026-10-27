package com.jarvis.agent

import android.app.Application
import com.jarvis.agent.core.AppGraph
import com.jarvis.agent.core.Notifications

class JarvisApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: JarvisApp
            private set

        val graph: AppGraph get() = instance.graph
    }
}
