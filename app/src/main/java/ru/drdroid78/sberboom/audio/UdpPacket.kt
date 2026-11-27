package ru.drdroid78.sberboom.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Утилиты для UDP-пакета аудиопотока SberBoom.
 *
 * Формат (подтверждён по FecUdpReceiver::processNoFEC / onRtpData):
 *   uint32 LE seq | uint32 LE timestamp (счётчик сэмплов 48 кГц) | Opus-пакет
 *
 * Это НЕ стандартный RTP: заголовок 8 байт, без version/PT/SSRC.
 */
object UdpPacket {

    const val HEADER_SIZE = 8

    fun build(sequence: Int, timestamp: Long, opus: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + opus.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(sequence)
        buf.putInt(timestamp.toInt())
        buf.put(opus)
        return out
    }

    fun sequence(packet: ByteArray): Int =
        ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int

    fun timestamp(packet: ByteArray): Long =
        ByteBuffer.wrap(packet, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    fun payload(packet: ByteArray): ByteArray =
        if (packet.size <= HEADER_SIZE) ByteArray(0) else packet.copyOfRange(HEADER_SIZE, packet.size)
}

/**
 * Разбор TOC Opus-пакета, чтобы точно знать длительность в сэмплах.
 * Нужен для корректного timestamp в заголовке UDP.
 */
object OpusPacket {

    private val FRAME_MS = arrayOf(
        10.0, 20.0, 40.0, 60.0,   // 0..3   SILK NB
        10.0, 20.0, 40.0, 60.0,   // 4..7   SILK MB
        10.0, 20.0, 40.0, 60.0,   // 8..11  SILK WB
        10.0, 20.0,               // 12..13 Hybrid SWB
        10.0, 20.0,               // 14..15 Hybrid FB
        2.5, 5.0, 10.0, 20.0,     // 16..19 CELT NB
        2.5, 5.0, 10.0, 20.0,     // 20..23 CELT WB
        2.5, 5.0, 10.0, 20.0,     // 24..27 CELT SWB
        2.5, 5.0, 10.0, 20.0      // 28..31 CELT FB
    )

    /** Количество сэмплов на канал при 48 кГц. */
    fun samplesAt48k(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xff
        val config = toc ushr 3
        val code = toc and 0x3
        val frameSamples = (FRAME_MS[config] * 48.0).toInt()
        return when (code) {
            0 -> frameSamples
            1, 2 -> frameSamples * 2
            else -> {
                // c=3: число кадров в следующем байте (младшие 6 бит).
                val frames = if (packet.size > 1) packet[1].toInt() and 0x3F else 2
                frameSamples * frames.coerceAtLeast(1)
            }
        }
    }
}
