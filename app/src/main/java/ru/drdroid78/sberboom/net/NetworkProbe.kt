package ru.drdroid78.sberboom.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Проверка сетевых возможностей колонки: SSDP/DLNA, AirPlay, Cast, StarOS.
 *
 * Нужна, чтобы понять, есть ли у динамика альтернативный Wi-Fi-приём аудио
 * (DLNA/UPnP MediaRenderer). Если есть — звук можно отдать по Wi-Fi без
 * backend-JWT: телефон поднимает HTTP-сервер, динамик играет URL.
 */
class NetworkProbe(private val context: Context) {

    data class SsdpDevice(
        val ip: String,
        val server: String,
        val st: String,
        val location: String?,
        val renderer: Boolean
    )

    data class Report(
        val ssdp: List<SsdpDevice>,
        val openPorts: List<Int>,
        val banners: Map<Int, String>,
        val host: String
    ) {
        val hasDlna: Boolean get() = ssdp.any { it.ip == host && it.renderer }

        fun toText(): String = buildString {
            appendLine("Хост: $host")
            appendLine()
            appendLine("SSDP/UPnP: " + if (ssdp.isEmpty()) "ответов нет" else "${ssdp.size} устройств(а)")
            ssdp.forEach { d ->
                appendLine("  ${d.ip}  ${d.st}")
                if (d.server.isNotBlank()) appendLine("    server: ${d.server}")
                if (d.renderer) appendLine("    [+] DLNA MediaRenderer")
            }
            appendLine()
            appendLine("Открытые TCP-порты: " + if (openPorts.isEmpty()) "нет" else openPorts.joinToString(", "))
            banners.forEach { (p, b) -> appendLine("  $p: $b") }
            appendLine()
            append(
                when {
                    hasDlna -> "DLNA-рендерер найден на выбранном IP; поддержка воспроизведения требует проверки."
                    openPorts.contains(7000) -> "Похоже на AirPlay (порт 7000), требует проверки."
                    openPorts.contains(8008) || openPorts.contains(8009) -> "Похоже на Google Cast, требует проверки."
                    openPorts.contains(20000) -> "Открыт порт StarOS SberCast; наличие других протоколов не исключено."
                    else -> "Альтернативных Wi-Fi-приёмов аудио не видно."
                }
            )
        }

        fun summaryLine(): String = when {
            hasDlna -> "DLNA найден"
            openPorts.contains(20000) -> "только StarOS"
            openPorts.isEmpty() -> "портов нет"
            else -> "порты: ${openPorts.joinToString(",")}"
        }
    }

    suspend fun run(host: String, timeoutMs: Int = 900): Report = withContext(Dispatchers.IO) {
        val ssdp = ssdpDiscover(2500)
        val ports = PORTS
        val open = coroutineScope {
            ports.map { p ->
                async(Dispatchers.IO) { p to isOpen(host, p, timeoutMs) }
            }.map { it.await() }
        }.filter { it.second }.map { it.first }

        val banners = open.filter { it in BANNER_PORTS }.mapNotNull { p ->
            httpBanner(host, p, 1200)?.let { p to it }
        }.toMap()

        Report(ssdp, open, banners, host)
    }

    private fun ssdpDiscover(timeoutMs: Int): List<SsdpDevice> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sberboom-probe")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val socket = DatagramSocket()
        val found = LinkedHashMap<String, SsdpDevice>()
        try {
            socket.soTimeout = 400
            val msg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            socket.send(
                DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900)
            )
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val headers = text.lineSequence()
                    .drop(1)
                    .mapNotNull { line ->
                        val i = line.indexOf(':')
                        if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                    }
                    .toMap()
                val location = headers["location"]
                val ip = packet.address.hostAddress ?: ""
                val key = ip + "|" + (headers["usn"] ?: headers["st"] ?: "")
                if (!found.containsKey(key)) {
                    val renderer = location?.let { isRenderer(it, ip) } ?: false
                    found[key] = SsdpDevice(
                        ip = ip,
                        server = headers["server"].orEmpty(),
                        st = headers["st"].orEmpty(),
                        location = location,
                        renderer = renderer
                    )
                }
            }
        } catch (_: Throwable) {
            // сеть может быть недоступна — вернём то, что успели собрать
        } finally {
            runCatching { socket.close() }
            runCatching { lock?.release() }
        }
        return found.values.toList()
    }

    private fun isRenderer(location: String, senderIp: String): Boolean {
        return try {
            val url = URL(location)
            if (url.protocol != "http" || url.host != senderIp || url.userInfo != null) return false
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "SberBoomProbe")
            val text = try {
                conn.inputStream.use { input ->
                    val data = ByteArray(65536)
                    var size = 0
                    while (size < data.size) {
                        val n = input.read(data, size, data.size - size)
                        if (n <= 0) break
                        size += n
                    }
                    String(data, 0, size, Charsets.UTF_8)
                }
            } finally { conn.disconnect() }
            text.contains("MediaRenderer", ignoreCase = true) ||
                text.contains("AVTransport", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun httpBanner(host: String, port: Int, timeoutMs: Int): String? = try {
        val conn = URL("http://$host:$port/").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "SberBoomProbe")
        val code = conn.responseCode
        val server = conn.getHeaderField("Server").orEmpty()
        conn.disconnect()
        "HTTP $code${if (server.isNotBlank()) ", $server" else ""}"
    } catch (_: Throwable) {
        null
    }

    companion object {
        val PORTS = listOf(
            80, 443, 1900, 7000, 8008, 8009, 8200,
            20000, 20023, 21023,
            49152, 49153, 49154, 49155, 49156, 49157
        )
        private val BANNER_PORTS = setOf(80, 8200)
    }
}
