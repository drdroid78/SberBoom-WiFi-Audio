package ru.drdroid78.sberboom.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire codec from Salute TV StarPTP 1.100.39, not a complete clock service. */
object StarPtpPacket {
    const val PORT = 10001
    enum class Type(val code: Int, val size: Int) { SYNC(0x10, 11), DELAY_REQUEST(0x11, 3), DELAY_RESPONSE(0x12, 11) }
    data class Message(val type: Type, val sequence: Int, val micros: Long?)

    fun encode(type: Type, sequence: Int = 0, micros: Long? = null): ByteArray {
        require(sequence in 0..65535)
        require((micros != null) == (type != Type.DELAY_REQUEST))
        val out = ByteBuffer.allocate(type.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(type.code.toByte()).putShort(sequence.toShort())
        if (micros != null) out.putLong(micros)
        return out.array()
    }

    fun decode(bytes: ByteArray): Message? {
        if (bytes.isEmpty()) return null
        val type = Type.entries.firstOrNull { it.code == (bytes[0].toInt() and 255) } ?: return null
        if (bytes.size != type.size) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.get()
        val seq = input.short.toInt() and 65535
        return Message(type, seq, if (type == Type.DELAY_REQUEST) null else input.long)
    }
}
