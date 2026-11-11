package dev.macdroid.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var listenerText: TextView
    private lateinit var discoveryText: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var logText: TextView
    private lateinit var discovery: MacDiscovery

    private val stateListener: (MirrorClient.State) -> Unit = { renderState(it) }
    private val logListener: () -> Unit = { logText.text = AppLog.text() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        listenerText = findViewById(R.id.listenerText)
        discoveryText = findViewById(R.id.discoveryText)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        codeInput = findViewById(R.id.codeInput)
        logText = findViewById(R.id.logText)

        hostInput.setText(Prefs.host)
        portInput.setText(Prefs.port.toString())
        codeInput.setText(Prefs.code)

        findViewById<Button>(R.id.grantAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.connectButton).setOnClickListener {
            Prefs.host = hostInput.text.toString()
            Prefs.port = portInput.text.toString().toIntOrNull() ?: Prefs.DEFAULT_PORT
            Prefs.code = codeInput.text.toString()
            MirrorClient.connect()
        }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { MirrorClient.disconnect() }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                TestNotification.post(this)
            }
        }

        discovery = MacDiscovery(this) { name, host, port ->
            discoveryText.text = "Found \"$name\" at $host:$port"
            // Only auto-fill an empty address, so a manual setup (e.g. relay port 7320) sticks.
            if (hostInput.text.isNullOrBlank()) {
                hostInput.setText(host)
                portInput.setText(port.toString())
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) TestNotification.post(this)
    }

    override fun onStart() {
        super.onStart()
        MirrorClient.addListener(stateListener)
        AppLog.addListener(logListener)
        logListener()
        discoveryText.text = "Looking for your Mac on this network…"
        discovery.start()
    }

    override fun onResume() {
        super.onResume()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        listenerText.text = if (granted) {
            "✅ Notification access granted"
        } else {
            "⚠️ Notification access is off. If Android says it's a \"restricted setting\", open " +
                "App info → ⋮ → Allow restricted settings, then try again."
        }
    }

    override fun onStop() {
        discovery.stop()
        MirrorClient.removeListener(stateListener)
        AppLog.removeListener(logListener)
        super.onStop()
    }

    private fun renderState(state: MirrorClient.State) {
        statusText.text = when (state) {
            is MirrorClient.State.Connected -> "🟢 Connected to ${state.macName}"
            MirrorClient.State.Connecting -> "🟡 Connecting…"
            MirrorClient.State.Disconnected -> "⚪ Not connected"
            is MirrorClient.State.Rejected -> "🔴 Wrong pairing code? (${state.reason})"
            is MirrorClient.State.Retrying -> "🟠 Retrying in ${state.inSeconds}s (${state.error})"
        }
    }
}
