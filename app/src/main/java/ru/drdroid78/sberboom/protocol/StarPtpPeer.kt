package ru.drdroid78.sberboom.protocol

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Real UDP StarPTP transport for an explicitly selected peer. No multicast discovery,
 * device impersonation or authorization. Clock readiness must not be equated with audio playback.
 * Interoperability still requires an actual SberBoom test and session negotiation.
 */
class StarPtpPeer(
    private val socket: DatagramSocket,
    private val peer: InetSocketAddress,
    private val nowUs: () -> Long = { System.nanoTime() / 1000 },
    private val intervalUs: Long = 200_000,
    private val onError: (Exception) -> Unit = {}
) : Closeable {
    init {
        require(!peer.isUnresolved && intervalUs in 20_000..1_000_000)
        require(socket.isBound && !socket.isClosed)
        socket.soTimeout = 20
    }
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val clock = StarPtpClock()
    private var worker: Thread? = null
    fun estimate(): StarPtpClock.Estimate? = clock.estimate(nowUs())

    fun start() {
        check(!closed.get() && started.compareAndSet(false, true))
        worker = Thread({ loop() }, "starptp-peer").apply { isDaemon = true; start() }
    }
    private fun send(type: StarPtpPacket.Type, sequence: Int, time: Long?) {
        val bytes = StarPtpPacket.encode(type, sequence, time)
        socket.send(DatagramPacket(bytes, bytes.size, peer))
    }
    private fun loop() {
        var next = nowUs()
        var sequence = 0
        val buffer = ByteArray(64)
        try {
            while (!closed.get()) {
                val now = nowUs()
                if (now >= next) {
                    send(StarPtpPacket.Type.SYNC, 0, now)
                    clock.onDelayRequest(sequence, nowUs())
                    send(StarPtpPacket.Type.DELAY_REQUEST, sequence, null)
                    sequence = (sequence + 1) and 0xffff
                    next = now + intervalUs
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val received = nowUs()
                if (packet.socketAddress != peer) continue
                val message = StarPtpPacket.decode(packet.data.copyOfRange(packet.offset, packet.offset + packet.length)) ?: continue
                when (message.type) {
                    StarPtpPacket.Type.SYNC -> clock.onSync(message.micros!!, received)
                    StarPtpPacket.Type.DELAY_REQUEST -> send(StarPtpPacket.Type.DELAY_RESPONSE, message.sequence, received)
                    StarPtpPacket.Type.DELAY_RESPONSE -> clock.onDelayResponse(message.sequence, message.micros!!, received)
                }
            }
        } catch (e: SocketException) { if (!closed.get()) onError(e) }
        catch (e: Exception) { if (!closed.get()) onError(e) }
        finally { closed.set(true); socket.close() }
    }
    override fun close() {
        closed.set(true); socket.close()
        if (Thread.currentThread() != worker) worker?.join(1000)
    }
}
