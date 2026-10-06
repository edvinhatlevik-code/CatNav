package com.example.catnav.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

data class GatewayEndpoint(val host: String, val port: Int)

class GatewayDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
        ?: throw IllegalStateException("Android network service discovery is unavailable.")
    private val handler = Handler(Looper.getMainLooper())

    fun discover(
        onFound: (GatewayEndpoint) -> Unit,
        onFailure: (String) -> Unit
    ): AutoCloseable {
        val finished = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener
        lateinit var timeout: Runnable
        fun fail(message: String, stopDiscovery: Boolean = true) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            if (stopDiscovery) manager.stopServiceDiscovery(listener)
            onFailure(message)
        }
        timeout = Runnable {
            fail("No CatNav gateway HTTP service was found. Enter its IP address as a fallback.")
        }

        fun complete(endpoint: GatewayEndpoint) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            manager.stopServiceDiscovery(listener)
            onFound(endpoint)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val nameMatches = serviceInfo.serviceName.contains("cat-gateway", ignoreCase = true) ||
                    serviceInfo.serviceName.contains("catnav", ignoreCase = true)
                if (!nameMatches || finished.get()) return

                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        fail("Could not resolve the discovered gateway service (error $errorCode).")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host?.hostAddress
                        if (address.isNullOrBlank()) {
                            fail("The gateway service did not provide an IP address.")
                            return
                        }
                        complete(GatewayEndpoint(address, info.port))
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                fail("Gateway discovery failed (error $errorCode).", stopDiscovery = false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            manager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            handler.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
        } catch (exception: RuntimeException) {
            if (finished.compareAndSet(false, true)) {
                onFailure(exception.message ?: "Gateway discovery could not be started.")
            }
        }

        return AutoCloseable {
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                manager.stopServiceDiscovery(listener)
            }
        }
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
    }
}
