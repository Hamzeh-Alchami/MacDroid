package dev.macdroid.android

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps one WebSocket connection to the Mac (see docs/PROTOCOL.md).
 * All state changes and incoming commands are delivered on the main thread.
 */
object MirrorClient {

    sealed class State {
        data object Disconnected : State()
        data object Connecting : State()
        data class Connected(val macName: String) : State()
        data class Rejected(val reason: String) : State()
        data class Retrying(val inSeconds: Long, val error: String) : State()
    }

    /** Implemented by the notification listener; runs reply/action/dismiss commands. */
    fun interface CommandHandler {
        fun handle(command: JSONObject)
    }

    private const val PROTOCOL_VERSION = 1
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var appContext: Context
    private var socket: WebSocket? = null
    /** Incremented per connection attempt so callbacks from stale sockets are ignored. */
    private var generation = 0
    private var backoffMs = 1_000L
    private val listeners = mutableSetOf<(State) -> Unit>()
    /** Packages whose icon the Mac already has on this connection. */
    private val iconsSent = mutableSetOf<String>()

    var state: State = State.Disconnected
        private set(value) {
            field = value
            listeners.forEach { it(value) }
        }

    var commandHandler: CommandHandler? = null

    val isConnected: Boolean get() = state is State.Connected

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun addListener(l: (State) -> Unit) { listeners += l; l(state) }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    fun connectIfConfigured() {
        if (Prefs.enabled && Prefs.isConfigured && socket == null) connect()
    }

    fun connect() {
        main.post { connectNow() }
    }

    private fun connectNow() {
        main.removeCallbacksAndMessages(RECONNECT_TOKEN)
        socket?.close(1000, "reconnecting")
        socket = null
        Prefs.enabled = true
        if (!Prefs.isConfigured) {
            AppLog.i("Set the Mac address and 6-digit pairing code first")
            state = State.Disconnected
            return
        }
        val gen = ++generation
        val url = "ws://${formatHost(Prefs.host)}:${Prefs.port}/"
        AppLog.i("Connecting to $url")
        state = State.Connecting
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener(gen))
    }

    fun disconnect() {
        main.post { disconnectNow() }
    }

    private fun disconnectNow() {
        Prefs.enabled = false
        main.removeCallbacksAndMessages(RECONNECT_TOKEN)
        generation++
        socket?.close(1000, "user disconnected")
        socket = null
        state = State.Disconnected
        AppLog.i("Disconnected")
    }

    /** Sends a message to the Mac if the handshake has completed. Thread-safe. */
    fun send(message: JSONObject): Boolean {
        val ws = socket ?: return false
        if (!isConnected) return false
        return ws.send(message.toString())
    }

    /** Sends `app_icon` once per package per connection, before its first notification. */
    fun sendIconIfNeeded(pkg: String, png: () -> String?) {
        if (!isConnected || pkg in iconsSent) return
        val data = png() ?: return
        if (send(JSONObject().put("type", "app_icon").put("package", pkg).put("png", data))) {
            iconsSent += pkg
        }
    }

    private val RECONNECT_TOKEN = Any()

    /** Called when a network becomes available: retry right away instead of waiting out the backoff. */
    fun onNetworkAvailable() {
        main.post {
            if (Prefs.enabled && Prefs.isConfigured && !isConnected && state != State.Connecting) {
                AppLog.i("Network available, reconnecting")
                backoffMs = 1_000L
                connectNow()
            }
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Keeps the CPU awake just long enough for the retry to run. Without this, a retry
     * scheduled while the screen is off waits until the phone wakes up for another reason.
     */
    private fun holdWakeLock(ms: Long) {
        val pm = appContext.getSystemService(android.os.PowerManager::class.java) ?: return
        val lock = wakeLock ?: pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MacDroid:reconnect")
            .also { it.setReferenceCounted(false); wakeLock = it }
        lock.acquire(ms)
    }

    private fun scheduleReconnect(error: String) {
        socket = null
        if (!Prefs.enabled) {
            state = State.Disconnected
            return
        }
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        state = State.Retrying(delay / 1000, error)
        holdWakeLock(delay + 15_000L)
        main.postAtTime({ connectIfConfigured() }, RECONNECT_TOKEN, android.os.SystemClock.uptimeMillis() + delay)
    }

    private fun formatHost(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private class Listener(private val gen: Int) : WebSocketListener() {
        private fun onMain(block: () -> Unit) {
            main.post { if (gen == generation) block() }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            AppLog.i("WebSocket open, sending pairing code")
            val hello = JSONObject()
                .put("type", "hello")
                .put("version", PROTOCOL_VERSION)
                .put("device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
                .put("code", Prefs.code)
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (e: Exception) {
                AppLog.i("Ignoring malformed message from Mac")
                return
            }
            onMain {
                when (msg.optString("type")) {
                    "hello_ok" -> {
                        backoffMs = 1_000L
                        val name = msg.optString("name", "Mac")
                        AppLog.i("Paired with $name")
                        iconsSent.clear()
                        wakeLock?.takeIf { it.isHeld }?.release()
                        state = State.Connected(name)
                    }
                    "hello_rejected" -> {
                        val reason = msg.optString("reason", "rejected")
                        AppLog.i("Mac rejected the connection: $reason")
                        Prefs.enabled = false // a wrong code won't fix itself by retrying
                        state = State.Rejected(reason)
                        webSocket.close(1000, null)
                        socket = null
                    }
                    "reply", "action", "dismiss" -> {
                        val handler = commandHandler
                        if (handler == null) {
                            AppLog.i("Got '${msg.optString("type")}' but notification access is off")
                            if (msg.has("requestId")) {
                                send(
                                    JSONObject().put("type", "action_result")
                                        .put("requestId", msg.optString("requestId"))
                                        .put("ok", false)
                                        .put("error", "listener_not_connected")
                                )
                            }
                        } else {
                            handler.handle(msg)
                        }
                    }
                    else -> Unit // forward compatible: ignore unknown types
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain {
            if (state is State.Rejected) return@onMain
            AppLog.i("Connection closed ($code${if (reason.isNotEmpty()) " $reason" else ""})")
            scheduleReconnect("closed")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onMain {
            if (state is State.Rejected) return@onMain
            val http = response?.let { " (HTTP ${it.code})" } ?: ""
            AppLog.i("Connection failed: ${t.message ?: t.javaClass.simpleName}$http")
            scheduleReconnect(t.message ?: "error")
        }
    }
}
