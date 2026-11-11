package dev.macdroid.android

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Reads the phone's notifications and forwards them to the Mac. It also carries out
 * the Mac's commands: an inline reply, an action button press, or a dismiss.
 */
class MirrorListenerService : NotificationListenerService(), MirrorClient.CommandHandler {

    /** Keys we've already sent, so re-posts count as updates rather than new alerts. */
    private val seenKeys = mutableSetOf<String>()

    override fun onListenerConnected() {
        AppLog.i("Notification access active")
        MirrorClient.commandHandler = this
        activeNotifications?.forEach { seenKeys += it.key }
        MirrorClient.connectIfConfigured()
    }

    override fun onListenerDisconnected() {
        AppLog.i("Notification access lost")
        if (MirrorClient.commandHandler === this) MirrorClient.commandHandler = null
    }

    override fun onDestroy() {
        if (MirrorClient.commandHandler === this) MirrorClient.commandHandler = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!NotificationSerializer.shouldMirror(sbn)) return
        val isUpdate = !seenKeys.add(sbn.key)
        if (!MirrorClient.isConnected) return
        val json = try {
            NotificationSerializer.toJson(this, sbn, isUpdate)
        } catch (e: Exception) {
            AppLog.i("Could not read notification from ${sbn.packageName}: ${e.message}")
            return
        }
        MirrorClient.sendIconIfNeeded(sbn.packageName) { AppIcons.pngBase64(this, sbn.packageName) }
        if (MirrorClient.send(json)) {
            val n = json.getJSONObject("notification")
            val tag = if (n.getBoolean("silent")) " (silent update)" else ""
            AppLog.i("→ ${n.getString("appName")}: ${n.getString("title")}$tag")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val wasSent = seenKeys.remove(sbn.key)
        if (wasSent && MirrorClient.isConnected) {
            MirrorClient.send(JSONObject().put("type", "notification_removed").put("key", sbn.key))
        }
    }

    // ---- Commands from the Mac ----

    override fun handle(command: JSONObject) {
        val type = command.optString("type")
        val key = command.optString("key")
        if (type == "dismiss") {
            AppLog.i("← Dismiss from Mac")
            try { cancelNotification(key) } catch (e: SecurityException) { /* listener unbound */ }
            return
        }

        val requestId = command.optString("requestId")
        val error = try {
            runAction(type, key, command.optString("actionId"), command.optString("text"))
        } catch (e: SecurityException) {
            "listener_not_connected"
        } catch (e: Exception) {
            AppLog.i("Action failed: ${e.message}")
            "internal"
        }
        AppLog.i(if (error == null) "← $type sent" else "← $type failed: $error")
        val result = JSONObject().put("type", "action_result").put("requestId", requestId).put("ok", error == null)
        if (error != null) result.put("error", error)
        MirrorClient.send(result)
    }

    /** Returns null on success or one of the protocol error codes. */
    private fun runAction(type: String, key: String, actionId: String, text: String): String? {
        val sbn = getActiveNotifications(arrayOf(key))?.firstOrNull() ?: return "notification_gone"
        val action = NotificationSerializer.findAction(sbn.notification, actionId) ?: return "action_not_found"
        val pendingIntent = action.actionIntent ?: return "action_not_found"

        val fillIn = Intent()
        if (type == "reply") {
            val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return "not_replyable"
            // Put the same text under every input key; apps only read the one they declared.
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            if (Build.VERSION.SDK_INT >= 28) {
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
        }
        return try {
            pendingIntent.send(this, 0, fillIn)
            null
        } catch (e: PendingIntent.CanceledException) {
            "intent_cancelled"
        }
    }
}
