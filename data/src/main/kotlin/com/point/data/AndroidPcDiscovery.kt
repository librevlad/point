package com.point.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.point.core.flow.DiscoveredPc
import com.point.core.flow.PcDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * NsdManager discovery of `_point-pc._tcp` (#147 slice C). Every callback failure is
 * swallowed into an empty/partial list — discovery is sugar, the manual path stays.
 */
class AndroidPcDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) : PcDiscovery {

    override fun discover(): Flow<List<DiscoveredPc>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val found = LinkedHashMap<String, DiscoveredPc>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(emptyList())
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            found[info.serviceName] = DiscoveredPc(info.serviceName, host, info.port)
                            trySend(found.values.toList())
                        }
                    },
                )
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                trySend(found.values.toList())
            }
        }

        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { trySend(emptyList()) }

        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private companion object {
        const val SERVICE_TYPE = "_point-pc._tcp"
    }
}
