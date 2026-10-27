package com.jarvis.agent.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.agent.tools.impl.Apps
import java.util.concurrent.atomic.AtomicInteger

/** A notification as Jarvis remembers it. [id] is a short number the model uses to refer to it. */
data class StoredNotification(
    val id: Int,
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val replyAction: Notification.Action?,
    val contentIntent: PendingIntent?,
)

/** Recent notifications (newest first), kept in memory only. */
object NotificationStore {
    private const val MAX = 80
    private val ids = AtomicInteger()
    private val items = ArrayDeque<StoredNotification>()

    @Synchronized
    fun add(n: StoredNotification) {
        items.removeAll { it.key == n.key }
        items.addFirst(n)
        while (items.size > MAX) items.removeLast()
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    @Synchronized
    fun list(): List<StoredNotification> = items.toList()

    @Synchronized
    fun byId(id: Int): StoredNotification? = items.firstOrNull { it.id == id }

    fun nextId() = ids.incrementAndGet()
}

/**
 * Reads incoming notifications so Jarvis can read them out, reply (via the notification's inline reply),
 * open or dismiss them. Notification content is untrusted third-party data.
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications?.forEach { store(it) } }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = store(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationStore.remove(sbn.key)

    private fun store(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: Bundle()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        if (title.isBlank() && text.isBlank() && lines.isNullOrBlank()) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        NotificationStore.add(
            StoredNotification(
                id = NotificationStore.nextId(),
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = Apps.label(this, sbn.packageName),
                title = title,
                text = if (!lines.isNullOrBlank() && lines.length > text.length) lines else text,
                postTime = sbn.postTime,
                replyAction = reply,
                contentIntent = n.contentIntent,
            ),
        )
    }

    fun dismiss(key: String) = cancelNotification(key)

    companion object {
        @Volatile
        var instance: JarvisNotificationListener? = null
            private set

        /** Sends [text] through the notification's inline-reply action. */
        fun reply(context: Context, n: StoredNotification, text: String): Boolean {
            val action = n.replyAction ?: return false
            val inputs = action.remoteInputs ?: return false
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, fill, results)
            return runCatching { action.actionIntent.send(context, 0, fill) }.isSuccess
        }
    }
}
