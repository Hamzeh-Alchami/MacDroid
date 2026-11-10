package dev.macdroid.android

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log shown on the main screen, so Phase 0 can be debugged without adb. */
object AppLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) {
        Log.i("MacDroid", msg)
        val line = "${fmt.format(Date())}  $msg"
        main.post {
            lines.addFirst(line)
            while (lines.size > MAX_LINES) lines.removeLast()
            listeners.forEach { it() }
        }
    }

    fun text(): String = lines.joinToString("\n")

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
