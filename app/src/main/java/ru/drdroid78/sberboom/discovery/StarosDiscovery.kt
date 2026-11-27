package ru.drdroid78.sberboom.discovery

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** Найденный в LAN динамик. */
data class SpeakerDevice(
    val name: String,
    val model: String,
    val id: String,
    val firmware: String,
    val host: String,
    val port: Int
) {
    val displayTitle: String get() = if (name.isBlank()) host else name
}

/**
 * Поиск динамиков через mDNS/DNS-SD, service type `_staros._tcp`.
 *
 * TXT-записи (по данным upstream-исследований):
 *   name = "SberBoom Home", type = "sberboom-r2", id = <serial>, v = <firmware>
 */
class StarosDiscovery(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_staros._tcp.local."
    }

    suspend fun scan(timeoutMs: Long = 6_000): List<SpeakerDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sberboom-mdns")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        var jmdns: JmDNS? = null
        val channel = Channel<SpeakerDevice>(Channel.UNLIMITED)
        val found = LinkedHashMap<String, SpeakerDevice>()
        try {
            jmdns = JmDNS.create(InetAddress.getByName("0.0.0.0"), "sberboom-audio")
            val listener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    runCatching { jmdns?.requestServiceInfo(event.type, event.name, true) }
                }

                override fun serviceRemoved(event: ServiceEvent) = Unit

                override fun serviceResolved(event: ServiceEvent) {
                    runCatching {
                        val info = event.info ?: return
                        val device = toDevice(info) ?: return
                        channel.trySend(device)
                    }
                }
            }
            jmdns.addServiceListener(SERVICE_TYPE, listener)

            withTimeoutOrNull(timeoutMs) {
                for (device in channel) {
                    found[device.host] = device
                }
            }
        } catch (_: Throwable) {
            // mDNS может быть недоступен (нет Wi-Fi / запрещён multicast).
        } finally {
            runCatching { jmdns?.close() }
            runCatching { lock?.release() }
            channel.close()
        }
        found.values.sortedBy { it.name }
    }

    private fun toDevice(info: ServiceInfo): SpeakerDevice? {
        val host = info.inetAddresses
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
            ?.hostAddress
            ?: info.hostAddresses?.firstOrNull { !it.contains(':') }
            ?: return null
        val props = HashMap<String, String>()
        val names = info.propertyNames
        if (names != null) {
            while (names.hasMoreElements()) {
                val key = names.nextElement()
                val value = info.getPropertyString(key)
                if (value != null) props[key] = value
            }
        }
        fun prop(vararg keys: String): String {
            for (k in keys) {
                val v = props[k]
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }
        return SpeakerDevice(
            name = prop("name", "device_name").ifBlank { info.name ?: host },
            model = prop("type", "model"),
            id = prop("id", "device_id", "serial", "serial_number"),
            firmware = prop("v", "version", "firmware"),
            host = host,
            port = if (info.port > 0) info.port else 20000
        )
    }
}
