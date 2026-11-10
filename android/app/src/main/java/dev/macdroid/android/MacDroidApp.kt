package dev.macdroid.android

import android.app.Application

class MacDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MirrorClient.init(this)
        // The system creates this Application when it binds the notification listener,
        // so the phone reconnects to the Mac without the UI being opened.
        MirrorClient.connectIfConfigured()
    }
}
