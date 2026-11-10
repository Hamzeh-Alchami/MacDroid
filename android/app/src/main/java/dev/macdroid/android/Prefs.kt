package dev.macdroid.android

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val DEFAULT_PORT = 7319

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("macdroid", Context.MODE_PRIVATE)
    }

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v.trim()).apply()

    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v.trim()).apply()

    /** False after the user pressed Disconnect; stops automatic reconnects. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    val isConfigured: Boolean get() = host.isNotBlank() && code.length == 6
}
