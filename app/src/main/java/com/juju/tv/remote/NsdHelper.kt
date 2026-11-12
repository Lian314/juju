package com.juju.tv.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网 mDNS 零配置服务广播与本机 IPv4 探测助手
 *
 * 使用 Android 原生 [NsdManager] 广播 `_juju-remote._tcp` 与 `_zgj-remote._tcp`：
 * - 零第三方 mDNS 库内存开销；
 * - 让同一 Wi-Fi 下的手机或跨端设备自动发现坚果 N1 Air 投影仪。
 */
class NsdHelper(private val context: Context?) {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun registerRemoteService(port: Int, serviceName: String = "JuJuTV-N1Air") {
        if (context == null || port <= 0) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (multicastLock == null && wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("juju_mdns_lock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = manager

            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = "_juju-remote._tcp."
                this.port = port
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo?) {}
                override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            }
            registrationListener = listener
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
            // Ignore on devices without NSD service
        }
    }

    fun unregisterRemoteService() {
        try {
            val listener = registrationListener
            if (listener != null) {
                nsdManager?.unregisterService(listener)
            }
            registrationListener = null
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {}
    }

    companion object {
        /**
         * 探测投影仪/电视在局域网中的 IPv4 地址（如 `192.168.1.88`）
         */
        fun detectLanIpv4(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                val candidates = mutableListOf<String>()
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                            candidates.add(ip)
                        }
                    }
                }
                return candidates.firstOrNull() ?: "127.0.0.1"
            } catch (_: Throwable) {
                return "127.0.0.1"
            }
        }
    }
}
