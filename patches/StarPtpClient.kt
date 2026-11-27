package ru.drdroid78.sberboom.audio

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Peer implementation of the StarOS StarPTP UDP transport discovered in
 * Salute TV libstardroid.so. Wire timestamps are monotonic microseconds.
 * StartStreaming mono_timestamp is milliseconds (a separate clock unit).
 *
 * This client intentionally does not send StarCommand 0x9F: the speaker's own
 * PTP service should learn the connected client's clock from the UDP exchange.
 */
class StarPtpClient(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val localPort: Int = DEFAULT_PORT,
    private val intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
    private val onLog: (String) -> Unit = {},
) : Closeable {
    data class Stats(
        val syncSent: Long,
        val delayReqSent: Long,
        val syncReceived: Long,
        val delayReqReceived: Long,
        val delayRespSent: Long,
        val delayRespReceived: Long,
        val unknownReceived: Long,
    )

    private val running = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val syncSent = AtomicLong(0)
    private val delayReqSent = AtomicLong(0)
    private val syncReceived = AtomicLong(0)
    private val delayReqReceived = AtomicLong(0)
    private val delayRespSent = AtomicLong(0)
    private val delayRespReceived = AtomicLong(0)
    private val unknownReceived = AtomicLong(0)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var remoteAddress: InetAddress? = null
    @Volatile private var senderThread: Thread? = null
    @Volatile private var receiverThread: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val address = InetAddress.getByName(host)
            val udp = DatagramSocket(null)
            udp.reuseAddress = true
            udp.broadcast = true
            udp.receiveBufferSize = 0x2800
            udp.sendBufferSize = 0x2800
            udp.soTimeout = 100
            udp.bind(InetSocketAddress(localPort))
            remoteAddress = address
            socket = udp

            receiverThread = Thread(::receiveLoop, "sberboom-starptp-rx").apply {
                isDaemon = true
                start()
            }
            senderThread = Thread(::sendLoop, "sberboom-starptp-tx").apply {
                isDaemon = true
                start()
            }
            onLog("StarPTP started local=0.0.0.0:$localPort remote=$host:$port interval=${intervalNanos / 1_000_000.0}ms")
        } catch (t: Throwable) {
            running.set(false)
            socket?.close()
            socket = null
            throw t
        }
    }

    fun snapshot(): Stats = Stats(
        syncSent.get(), delayReqSent.get(), syncReceived.get(),
        delayReqReceived.get(), delayRespSent.get(), delayRespReceived.get(),
        unknownReceived.get(),
    )

    private fun sendLoop() {
        var nextSync = System.nanoTime()
        var nextDelayReq = nextSync
        while (running.get()) {
            val now = System.nanoTime()
            if (now >= nextSync) {
                sendToSpeaker(packSync(now / 1_000L))
                syncSent.incrementAndGet()
                nextSync = advanceDeadline(nextSync, now)
            }
            if (now >= nextDelayReq) {
                val seq = sequence.getAndUpdate { (it + 1) and 0xFFFF }
                sendToSpeaker(packDelayReq(seq))
                delayReqSent.incrementAndGet()
                nextDelayReq = advanceDeadline(nextDelayReq, now)
            }
            val waitNs = minOf(nextSync, nextDelayReq) - System.nanoTime()
            if (waitNs > 0) LockSupport.parkNanos(waitNs)
        }
    }

    private fun advanceDeadline(previous: Long, now: Long): Long =
        if (now - previous > intervalNanos * 5) now + intervalNanos else previous + intervalNanos

    private fun receiveLoop() {
        val udp = socket ?: return
        val expectedAddress = remoteAddress
        val buffer = ByteArray(64)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running.get()) onLog("StarPTP receive error: ${t.message}")
                break
            }
            if (packet.address != expectedAddress || packet.port != port || packet.length < 1) continue
            val data = buffer.copyOf(packet.length)
            when (data[0].toInt() and 0xFF) {
                TYPE_SYNC -> {
                    if (data.size >= 11) syncReceived.incrementAndGet() else unknownReceived.incrementAndGet()
                }
                TYPE_DELAY_REQ -> {
                    if (data.size == 3) {
                        val seq = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                        delayReqReceived.incrementAndGet()
                        send(packet.address, packet.port, packDelayResp(seq, monotonicMicros()))
                        delayRespSent.incrementAndGet()
                    } else {
                        unknownReceived.incrementAndGet()
                    }
                }
                TYPE_DELAY_RESP -> {
                    if (data.size >= 11) delayRespReceived.incrementAndGet()
                    else unknownReceived.incrementAndGet()
                }
                else -> unknownReceived.incrementAndGet()
            }
        }
    }

    private fun sendToSpeaker(data: ByteArray) {
        val address = remoteAddress ?: return
        send(address, port, data)
    }

    private fun send(address: InetAddress, destinationPort: Int, data: ByteArray) {
        try {
            val udp = socket ?: return
            udp.send(DatagramPacket(data, data.size, address, destinationPort))
        } catch (t: Throwable) {
            if (running.get()) onLog("StarPTP send error: ${t.message}")
        }
    }

    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        senderThread?.interrupt()
        receiverThread?.interrupt()
        senderThread?.join(300)
        receiverThread?.join(300)
        socket = null
        senderThread = null
        receiverThread = null
        onLog("StarPTP stopped: ${snapshot()}")
    }

    companion object {
        const val DEFAULT_PORT = 4391
        const val DEFAULT_INTERVAL_NANOS = 1_000_000L // 1000 us, confirmed in StarPTP constructor
        private const val TYPE_SYNC = 0x10
        private const val TYPE_DELAY_REQ = 0x11
        private const val TYPE_DELAY_RESP = 0x12

        fun monotonicMicros(): Long = System.nanoTime() / 1_000L
        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

        /** 11 bytes: 0x10, two reserved zero bytes, big-endian 64-bit timestamp. */
        fun packSync(timestampMicros: Long): ByteArray = ByteArray(11).also { out ->
            out[0] = TYPE_SYNC.toByte()
            ByteBuffer.wrap(out, 3, 8).order(ByteOrder.BIG_ENDIAN).putLong(timestampMicros)
        }

        /** 3 bytes: 0x11, 16-bit little-endian sequence. */
        fun packDelayReq(sequence: Int): ByteArray = byteArrayOf(
            TYPE_DELAY_REQ.toByte(),
            (sequence and 0xFF).toByte(),
            ((sequence ushr 8) and 0xFF).toByte(),
        )

        /** 11 bytes: 0x12, 16-bit little-endian sequence, big-endian 64-bit timestamp. */
        fun packDelayResp(sequence: Int, timestampMicros: Long): ByteArray = ByteArray(11).also { out ->
            out[0] = TYPE_DELAY_RESP.toByte()
            out[1] = (sequence and 0xFF).toByte()
            out[2] = ((sequence ushr 8) and 0xFF).toByte()
            ByteBuffer.wrap(out, 3, 8).order(ByteOrder.BIG_ENDIAN).putLong(timestampMicros)
        }
    }
}
