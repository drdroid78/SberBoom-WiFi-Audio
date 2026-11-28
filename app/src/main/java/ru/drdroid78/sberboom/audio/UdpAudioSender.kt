package ru.drdroid78.sberboom.audio

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Отправитель UDP-потока на динамик.
 *
 * Динамик по команде StartStreaming открывает UDP-порт (udpSettings.port)
 * на всех интерфейсах и ждёт пакеты seq|timestamp|opus. FEC отключён,
 * поэтому дополнительная обфускация/чётность не используется.
 */
class UdpAudioSender(
    host: String,
    private val port: Int,
    private val onError: (String) -> Unit = {}
) {
    val destinationPort: Int get() = port
    private val target: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket().apply {
        // connect() нужен, чтобы localAddress() вернул адрес исходящего интерфейса.
        connect(target, port)
        soTimeout = 0
        sendBufferSize = 256 * 1024
    }

    private var sequence = 0
    private var timestamp = 0L

    val packetsSent = AtomicLong()
    val bytesSent = AtomicLong()
    private val lastPacketAtMs = AtomicLong()

    fun lastPacketAgeMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastPacketAtMs.get()
        return if (last == 0L) Long.MAX_VALUE else (nowMs - last).coerceAtLeast(0)
    }

    fun localAddress(): String = try {
        socket.localAddress?.hostAddress ?: "0.0.0.0"
    } catch (_: Throwable) {
        "0.0.0.0"
    }

    @Synchronized
    fun sendOpus(opus: ByteArray) {
        if (opus.isEmpty()) return
        val packet = UdpPacket.build(sequence, timestamp, opus)
        try {
            socket.send(DatagramPacket(packet, packet.size, target, port))
            lastPacketAtMs.set(System.currentTimeMillis())
            packetsSent.incrementAndGet()
            bytesSent.addAndGet(packet.size.toLong())
        } catch (t: Throwable) {
            onError("UDP send: ${t.message}")
        }
        sequence += 1
        timestamp += OpusPacket.samplesAt48k(opus).toLong()
    }

    fun close() {
        runCatching { socket.close() }
    }
}
