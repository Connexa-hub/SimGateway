package com.connexa.simgateway

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

/**
 * NSD discovery of providers. All state is touched on the main thread only:
 * NSD callbacks arrive on a binder thread and are re-posted to main.
 * Resolves are serialized because older Android versions reject parallel resolves.
 */
class Discovery(context: Context) {

    data class Gateway(val key: String, val name: String, val address: InetAddress, val port: Int)

    interface Listener {
        fun onGatewaysChanged(list: List<Gateway>)
        fun onSearchState(searching: Boolean, timedOut: Boolean)
        fun onError(message: String)
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 12000L
        private const val MAX_RESOLVE_RETRIES = 2
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    var listener: Listener? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, Gateway>()
    private val queue = mutableListOf<NsdServiceInfo>()
    private val retries = HashMap<String, Int>()
    private var resolving = false
    private var running = false

    private val timeoutRunnable = Runnable {
        if (running) listener?.onSearchState(true, found.isEmpty())
    }

    fun start() {
        stop()
        running = true
        found.clear()
        queue.clear()
        retries.clear()
        resolving = false
        listener?.onGatewaysChanged(emptyList())
        listener?.onSearchState(true, false)

        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (running && serviceInfo.serviceType.contains("_simgateway._tcp")) {
                        queue.add(serviceInfo)
                        resolveNext()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (found.remove(serviceInfo.serviceName) != null) {
                        listener?.onGatewaysChanged(found.values.toList())
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    running = false
                    listener?.onError("Couldn't search for gateways (code $errorCode).")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = l
        try {
            nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            running = false
            discoveryListener = null
            listener?.onError("Couldn't start searching: ${e.javaClass.simpleName}")
        }
        main.postDelayed(timeoutRunnable, SEARCH_TIMEOUT_MS)
    }

    fun stop() {
        main.removeCallbacks(timeoutRunnable)
        running = false
        queue.clear()
        resolving = false
        val l = discoveryListener
        discoveryListener = null
        if (l != null) {
            try {
                nsd.stopServiceDiscovery(l)
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveNext() {
        if (resolving || !running || queue.isEmpty()) return
        val info = queue.removeAt(0)
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        val n = retries[serviceInfo.serviceName] ?: 0
                        if (n < MAX_RESOLVE_RETRIES) {
                            retries[serviceInfo.serviceName] = n + 1
                            queue.add(serviceInfo)
                        }
                        main.postDelayed({
                            resolving = false
                            resolveNext()
                        }, 300)
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        handleResolved(serviceInfo)
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        if (!running) return
        val host = info.host ?: return
        val nameBytes = info.attributes?.get("name")
        val display = if (nameBytes != null) String(nameBytes, Charsets.UTF_8) else info.serviceName
        found[info.serviceName] = Gateway(info.serviceName, display, host, info.port)
        listener?.onGatewaysChanged(found.values.toList())
        listener?.onSearchState(true, false)
    }
}
