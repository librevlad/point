package com.point.desktop

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS advertising (#147 slice C): register `_point-pc._tcp` on every site-local
 * interface so the phone's NSD finds this PC by itself. Best-effort — a failed
 * interface is skipped; manual host:port pairing never depends on this.
 */
class Advertiser(private val pcName: String, private val port: Int) {

    private val instances = mutableListOf<JmDNS>()

    fun start() {
        siteLocalAddresses().forEach { address ->
            runCatching {
                val jmdns = JmDNS.create(InetAddress.getByName(address))
                jmdns.registerService(
                    ServiceInfo.create("_point-pc._tcp.local.", pcName, port, "Point для ПК"),
                )
                instances += jmdns
            }
        }
    }

    fun stop() {
        instances.forEach { runCatching { it.unregisterAllServices(); it.close() } }
        instances.clear()
    }
}
