package dev.macdroid.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * A fake chat conversation that behaves like a real messaging app: it has a reply box
 * and a "Mark as read" button, and it re-posts itself with your reply. This tests the
 * whole loop without waiting for someone to message you.
 */
object TestNotification {
    private const val CHANNEL_ID = "test_chat"
    private const val NOTIFICATION_ID = 4242
    private const val KEY_TEXT = "reply_text"
    private const val ACTION_REPLY = "dev.macdroid.android.TEST_REPLY"
    private const val ACTION_MARK_READ = "dev.macdroid.android.TEST_MARK_READ"

    private val friend = Person.Builder().setName("Test Friend").setKey("test-friend").build()
    private val me = Person.Builder().setName("Me").setKey("me").build()
    private val history = mutableListOf<NotificationCompat.MessagingStyle.Message>()
    private var counter = 0

    private val prompts = listOf(
        "Hey! Can you reply to this from your Mac? 💻",
        "Nice, that worked. Try another one?",
        "Are we still on for dinner at 7?",
    )

    fun post(context: Context) {
        val text = prompts[counter++ % prompts.size]
        history += NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), friend)
        show(context)
    }

    private fun show(context: Context) {
        ensureChannel(context)
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(null)
        history.takeLast(8).forEach { style.addMessage(it) }

        val remoteInput = RemoteInput.Builder(KEY_TEXT)
            .setLabel("Reply")
            .setChoices(arrayOf("👍", "On my way", "Can't talk now"))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif, "Reply", broadcast(context, ACTION_REPLY, 1, mutable = true),
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif, "Mark as read", broadcast(context, ACTION_MARK_READ, 2, mutable = false),
        ).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(replyAction)
            .addAction(markReadAction)
            .setOnlyAlertOnce(false)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            AppLog.i("Can't post the test notification: notification permission denied")
        }
    }

    private fun broadcast(context: Context, action: String, requestCode: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ReplyReceiver::class.java).setAction(action)
        // The reply intent must be mutable so the system (or MacDroid) can attach the typed text.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Test chat", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_REPLY -> {
                    val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)
                    if (reply.isNullOrBlank()) return
                    AppLog.i("Test chat received your reply: \"$reply\"")
                    // Like real chat apps: show the reply in the conversation (sender = you).
                    history += NotificationCompat.MessagingStyle.Message(reply, System.currentTimeMillis(), null as Person?)
                    show(context)
                }
                ACTION_MARK_READ -> {
                    AppLog.i("Test chat marked as read")
                    history.clear()
                    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                }
            }
        }
    }
}
