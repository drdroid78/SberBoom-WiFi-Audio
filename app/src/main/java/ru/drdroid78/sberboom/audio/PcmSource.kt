package ru.drdroid78.sberboom.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Источник PCM 16-bit stereo 48 кГц. */
interface PcmSource {
    val description: String
    fun open()
    /** Заполняет [out] целиком; возвращает число байт (0 — конец потока). */
    fun read(out: ByteArray): Int
    fun close()
}

/** Тестовый тон 440 Гц — проверка тракта без файлов и разрешений. */
class ToneSource(
    private val sampleRate: Int = 48_000,
    private val frequency: Double = 440.0,
    private val amplitude: Double = 0.22
) : PcmSource {
    override val description: String = "Тестовый тон ${frequency.toInt()} Гц"

    private var phase = 0.0

    override fun open() {
        phase = 0.0
    }

    override fun read(out: ByteArray): Int {
        val frames = out.size / 4
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val step = 2.0 * PI * frequency / sampleRate
        for (i in 0 until frames) {
            val value = (sin(phase) * amplitude * Short.MAX_VALUE).toInt().toShort()
            buf.putShort(value)
            buf.putShort(value)
            phase += step
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
        return out.size
    }

    override fun close() = Unit
}

/**
 * Декодирование локального аудиофайла в PCM 48 кГц stereo.
 * MediaExtractor + MediaCodec; при необходимости — линейный ресемплер.
 */
class FilePcmSource(
    private val context: Context,
    private val uri: Uri
) : PcmSource {
    override val description: String = "Файл: $uri"

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    private var inRate = 48_000
    private var inChannels = 2
    private val converter = PcmConverter()
    private val queue = ChunkQueue()

    override fun open() {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        var mime: String? = null
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) {
                track = i
                mime = m
                inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                inChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                break
            }
        }
        if (track < 0 || mime == null) {
            ex.release()
            throw IllegalStateException("В файле нет аудиодорожки")
        }
        ex.selectTrack(track)
        val c = MediaCodec.createDecoderByType(mime)
        val fmt = ex.getTrackFormat(track)
        fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        c.configure(fmt, null, null, 0)
        c.start()
        extractor = ex
        codec = c
        inputDone = false
        outputDone = false
    }

    override fun read(out: ByteArray): Int {
        var written = 0
        var idle = 0
        while (written < out.size) {
            val n = queue.pop(out, written, out.size - written)
            if (n > 0) {
                written += n
                idle = 0
                continue
            }
            if (outputDone) break
            if (!pump()) {
                idle++
                if (idle > 200) break
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return written
    }

    private fun pump(): Boolean {
        var progress = false
        val c = codec ?: return false
        if (!inputDone) {
            val idx = c.dequeueInputBuffer(0)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx)
                val ex = extractor
                if (buf != null && ex != null) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                    progress = true
                }
            }
        }
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            when {
                idx >= 0 -> {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val pcm = ByteArray(info.size)
                            buf.get(pcm)
                            val converted = converter.convert(pcm, info.size, inRate, inChannels)
                            if (converted.isNotEmpty()) queue.push(converted)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    progress = true
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    progress = true
                }
                else -> return progress
            }
        }
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
    }
}

/**
 * Захват системного/чужого аудио через AudioPlaybackCapture + MediaProjection.
 * Требует разрешение RECORD_AUDIO и согласие пользователя на захват экрана.
 */
class PlaybackCaptureSource(
    private val projection: MediaProjection,
    private val sampleRate: Int = 48_000
) : PcmSource {
    override val description: String = "Захват аудио приложения (AudioPlaybackCapture)"

    private var record: AudioRecord? = null
    private var carry = ByteArray(0)

    override fun open() {
        carry = ByteArray(0)
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 2, 16 * 1024))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord не инициализирован (нет разрешения RECORD_AUDIO?)")
        }
        rec.startRecording()
        record = rec
    }

    override fun read(out: ByteArray): Int {
        val rec = record ?: return 0
        var total = 0
        // Сначала отдаём остаток от прошлого чтения, чтобы не разрывать сэмпл.
        if (carry.isNotEmpty()) {
            val n = minOf(carry.size, out.size)
            System.arraycopy(carry, 0, out, 0, n)
            carry = if (n == carry.size) ByteArray(0) else carry.copyOfRange(n, carry.size)
            total = n
        }
        while (total < out.size) {
            val n = rec.read(out, total, out.size - total)
            if (n <= 0) break
            total += n
        }
        // Кадр stereo 16-bit = 4 байта; хвост переносим на следующий вызов.
        val aligned = total - (total % 4)
        if (aligned < total) {
            carry += out.copyOfRange(aligned, total)
            total = aligned
        }
        return total
    }

    override fun close() {
        val rec = record
        record = null
        carry = ByteArray(0)
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }
}

/** Линейный ресемплер + приведение каналов к stereo 48 кГц. */
class PcmConverter(private val outRate: Int = 48_000) {
    private var carry = 0.0

    fun convert(pcm: ByteArray, size: Int, inRate: Int, inChannels: Int): ByteArray {
        if (size < 2) return ByteArray(0)
        val channels = inChannels.coerceAtLeast(1)
        val samples = size / 2 / channels
        if (samples <= 0) return ByteArray(0)

        val shorts = ShortArray(samples * channels)
        ByteBuffer.wrap(pcm, 0, samples * channels * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)

        if (inRate == outRate && channels == 2) {
            val out = ByteArray(samples * 4)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples) {
                bb.putShort(shorts[i * 2])
                bb.putShort(shorts[i * 2 + 1])
            }
            return out
        }

        val ratio = inRate.toDouble() / outRate
        val out = java.io.ByteArrayOutputStream(((samples / ratio) * 4).toInt() + 8)
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        var t = carry
        while (t < samples - 1) {
            val i0 = floor(t).toInt()
            val frac = t - i0
            for (ch in 0 until 2) {
                val c = if (channels == 1) 0 else minOf(ch, channels - 1)
                val s0 = shorts[i0 * channels + c].toDouble()
                val s1 = shorts[(i0 + 1) * channels + c].toDouble()
                val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                bb.clear()
                bb.putShort(v)
                out.write(bb.array(), 0, 2)
            }
            t += ratio
        }
        carry = t - samples
        return out.toByteArray()
    }
}

/** Очередь байтовых блоков без лишнего копирования. */
private class ChunkQueue {
    private val chunks = ArrayDeque<ByteArray>()
    private var offset = 0
    private var size = 0

    fun push(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        chunks.addLast(bytes)
        size += bytes.size
    }

    fun pop(dst: ByteArray, dstOffset: Int, max: Int): Int {
        if (max <= 0 || size == 0) return 0
        var written = 0
        while (written < max && chunks.isNotEmpty()) {
            val head = chunks.first()
            val available = head.size - offset
            val n = minOf(available, max - written)
            System.arraycopy(head, offset, dst, dstOffset + written, n)
            written += n
            offset += n
            size -= n
            if (offset >= head.size) {
                chunks.removeFirst()
                offset = 0
            }
        }
        return written
    }
}
