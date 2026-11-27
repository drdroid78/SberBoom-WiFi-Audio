package ru.drdroid78.sberboom.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Минимальный protobuf-wire кодек (varint / length-delimited / fixed32 / fixed64).
 *
 * Используется для сообщений StarOS/SberCast, которые передаются по
 * wss://<динамик>:20000/ как «сырые» protobuf-сообщения без .proto-файлов
 * на стороне клиента. Формат: key = (tag << 3) | wire_type.
 */
object Tlv {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_BYTES = 2
    const val WIRE_FIXED32 = 5

    fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7f).toInt())
        return out.toByteArray()
    }

    private fun key(tag: Int, wire: Int): ByteArray =
        varint((tag.toLong() shl 3) or wire.toLong())

    fun fieldVarint(tag: Int, value: Long): ByteArray =
        key(tag, WIRE_VARINT) + varint(value)

    fun fieldBytes(tag: Int, payload: ByteArray): ByteArray =
        key(tag, WIRE_BYTES) + varint(payload.size.toLong()) + payload

    fun fieldString(tag: Int, value: String): ByteArray =
        fieldBytes(tag, value.toByteArray(StandardCharsets.UTF_8))

    fun fieldFloat(tag: Int, value: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(value)
        return key(tag, WIRE_FIXED32) + byteArrayOf(
            (bits and 0xff).toByte(),
            ((bits ushr 8) and 0xff).toByte(),
            ((bits ushr 16) and 0xff).toByte(),
            ((bits ushr 24) and 0xff).toByte()
        )
    }

    fun fieldDouble(tag: Int, value: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(value)
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((bits ushr (8 * i)) and 0xff).toByte()
        return key(tag, WIRE_FIXED64) + out
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Разбор сообщения в map tag -> значение (Long | ByteArray | Float | Double). */
    fun decode(data: ByteArray): Map<Int, Any> {
        val out = LinkedHashMap<Int, Any>()
        var i = 0
        val n = data.size
        while (i < n) {
            val (k, ni) = readVarint(data, i) ?: return out
            i = ni
            val tag = (k ushr 3).toInt()
            when ((k and 7L).toInt()) {
                WIRE_VARINT -> {
                    val (v, ni2) = readVarint(data, i) ?: return out
                    out[tag] = v
                    i = ni2
                }
                WIRE_FIXED64 -> {
                    if (i + 8 > n) return out
                    var bits = 0L
                    for (b in 0 until 8) bits = bits or ((data[i + b].toLong() and 0xff) shl (8 * b))
                    out[tag] = java.lang.Double.longBitsToDouble(bits)
                    i += 8
                }
                WIRE_BYTES -> {
                    val (len, ni2) = readVarint(data, i) ?: return out
                    i = ni2
                    if (len < 0 || len > Int.MAX_VALUE || i + len > n) return out
                    out[tag] = data.copyOfRange(i, i + len.toInt())
                    i += len.toInt()
                }
                WIRE_FIXED32 -> {
                    if (i + 4 > n) return out
                    val bits = (data[i].toInt() and 0xff) or
                        ((data[i + 1].toInt() and 0xff) shl 8) or
                        ((data[i + 2].toInt() and 0xff) shl 16) or
                        ((data[i + 3].toInt() and 0xff) shl 24)
                    out[tag] = java.lang.Float.intBitsToFloat(bits)
                    i += 4
                }
                else -> return out
            }
        }
        return out
    }

    /** Разбор с сохранением повторяющихся полей. */
    fun decodeRepeated(data: ByteArray): Map<Int, List<Any>> {
        val out = LinkedHashMap<Int, MutableList<Any>>()
        var i = 0
        val n = data.size
        while (i < n) {
            val (k, ni) = readVarint(data, i) ?: break
            i = ni
            val tag = (k ushr 3).toInt()
            when ((k and 7L).toInt()) {
                WIRE_VARINT -> {
                    val (v, ni2) = readVarint(data, i) ?: break
                    out.getOrPut(tag) { mutableListOf() }.add(v)
                    i = ni2
                }
                WIRE_BYTES -> {
                    val (len, ni2) = readVarint(data, i) ?: break
                    i = ni2
                    if (len < 0 || len > Int.MAX_VALUE || i + len > n) break
                    out.getOrPut(tag) { mutableListOf() }.add(data.copyOfRange(i, i + len.toInt()))
                    i += len.toInt()
                }
                WIRE_FIXED32 -> {
                    if (i + 4 > n) break
                    out.getOrPut(tag) { mutableListOf() }.add(data.copyOfRange(i, i + 4))
                    i += 4
                }
                else -> break
            }
        }
        return out
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return value to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }
}

fun Map<Int, Any>.long(tag: Int): Long? = this[tag] as? Long
fun Map<Int, Any>.float(tag: Int): Float? = this[tag] as? Float
fun Map<Int, Any>.bytes(tag: Int): ByteArray? = this[tag] as? ByteArray
fun Map<Int, Any>.str(tag: Int): String? =
    (this[tag] as? ByteArray)?.toString(StandardCharsets.UTF_8)

fun Map<Int, Any>.nested(tag: Int): Map<Int, Any>? =
    (this[tag] as? ByteArray)?.let { Tlv.decode(it) }
