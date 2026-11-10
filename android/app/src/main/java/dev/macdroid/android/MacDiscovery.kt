package dev.macdroid.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** Finds Macs running MacDroid on the local network via Bonjour (`_macdroid._tcp`). */
class MacDiscovery(context: Context, private val onFound: (name: String, host: String, port: Int) -> Unit) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        multicastLock = wifi?.createMulticastLock("macdroid-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.i("Mac discovery failed to start ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) = resolve(service)
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        multicastLock?.release()
        multicastLock = null
    }

    @Suppress("DEPRECATION") // resolveService is fine for Phase 0; API 34's callback API is a later upgrade.
    private fun resolve(service: NsdServiceInfo) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    main.postDelayed({ resolve(service) }, 500)
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = pickAddress(info) ?: return
                main.post { onFound(info.serviceName, host, info.port) }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): String? {
        // Prefer IPv4: IPv6 link-local addresses need a scope id, which is fiddly to use.
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress?.let { return it }
        }
        return info.host?.hostAddress?.substringBefore('%')
    }

    companion object {
        const val SERVICE_TYPE = "_macdroid._tcp."
    }
}
