package dev.macdroid.android

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/** Turns an Android notification into the `notification_posted` payload (docs/PROTOCOL.md). */
object NotificationSerializer {

    private const val MAX_MESSAGES = 10
    private const val MAX_TEXT = 2_000

    /** Filters out notifications that make no sense on the Mac. */
    fun shouldMirror(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        // Ongoing notifications are music players, navigation, uploads, VPNs and so on.
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        // Group summaries duplicate their children ("3 new messages").
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        return !title.isNullOrBlank() || !text.isNullOrBlank()
    }

    fun toJson(context: Context, sbn: StatusBarNotification, isUpdate: Boolean): JSONObject {
        val n = sbn.notification
        val extras = n.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)

        val messages = JSONArray()
        var lastMessage: NotificationCompat.MessagingStyle.Message? = null
        var lastFromSelf = false
        if (style != null) {
            val all = style.messages.takeLast(MAX_MESSAGES)
            for (m in all) {
                val fromSelf = isFromSelf(style, m)
                messages.put(
                    JSONObject()
                        .put("sender", if (fromSelf) "You" else m.person?.name?.toString() ?: "")
                        .put("text", m.text?.toString()?.take(MAX_TEXT) ?: "")
                        .put("time", m.timestamp)
                        .put("fromSelf", fromSelf)
                )
            }
            lastMessage = all.lastOrNull()
            lastFromSelf = lastMessage != null && isFromSelf(style, lastMessage)
        }

        val isGroup = style?.isGroupConversation == true
        val title = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
        val text = when {
            lastMessage != null -> {
                val body = lastMessage.text?.toString() ?: ""
                val sender = if (lastFromSelf) "You" else lastMessage.person?.name?.toString()
                if (isGroup && !sender.isNullOrBlank()) "$sender: $body" else body
            }
            else -> (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        }.take(MAX_TEXT)

        val actions = JSONArray()
        for ((id, action) in mirroredActions(n)) {
            val inputs = action.remoteInputs.orEmpty()
            val isReply = inputs.any { it.allowFreeFormInput }
            val choices = JSONArray()
            inputs.flatMap { it.choices?.toList().orEmpty() }.forEach { choices.put(it.toString()) }
            actions.put(
                JSONObject()
                    .put("id", id)
                    .put("title", action.title?.toString() ?: "Action")
                    .put("isReply", isReply)
                    .put("choices", choices)
            )
        }

        val onlyAlertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        val silent = lastFromSelf || (isUpdate && onlyAlertOnce)

        val payload = JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("appName", appName(context, sbn.packageName))
            .put("title", title)
            .put("text", text)
            .put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: JSONObject.NULL)
            .put("conversationId", "${sbn.packageName}:${n.shortcutId ?: title}")
            .put("isGroup", isGroup)
            .put("messages", messages)
            .put("actions", actions)
            .put("postedAt", sbn.postTime)
            .put("silent", silent)

        return JSONObject().put("type", "notification_posted").put("notification", payload)
    }

    /**
     * The actions we expose, with stable ids. Many messaging apps (WhatsApp among them)
     * only put their reply action in the Wear extender, so both lists are included,
     * skipping Wear actions whose title duplicates a normal one.
     */
    fun mirroredActions(n: Notification): List<Pair<String, Notification.Action>> {
        val result = mutableListOf<Pair<String, Notification.Action>>()
        val titles = mutableSetOf<String>()
        n.actions.orEmpty().forEachIndexed { i, a ->
            result += "n$i" to a
            titles += a.title?.toString().orEmpty()
        }
        Notification.WearableExtender(n).actions.forEachIndexed { i, a ->
            if (a.title?.toString().orEmpty() !in titles) result += "w$i" to a
        }
        return result
    }

    fun findAction(n: Notification, id: String): Notification.Action? =
        mirroredActions(n).firstOrNull { it.first == id }?.second

    private fun isFromSelf(
        style: NotificationCompat.MessagingStyle,
        m: NotificationCompat.MessagingStyle.Message,
    ): Boolean {
        val person = m.person ?: return true // a null sender means the device user
        val user = style.user
        if (person.key != null && person.key == user.key) return true
        return person.name != null && person.name.toString() == user.name?.toString()
    }

    private val labelCache = mutableMapOf<String, String>()

    private fun appName(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
