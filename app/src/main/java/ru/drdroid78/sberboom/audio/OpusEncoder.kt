package ru.drdroid78.sberboom.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Opus-энкодер на базе Android MediaCodec ("audio/opus").
 *
 * Прошивка динамика декодирует поток через opus_decode(48000, 2), поэтому
 * на вход кодеку подаётся PCM 16-bit stereo 48 кГц, а на выходе получаются
 * готовые Opus-пакеты (один output buffer = один пакет).
 */
class OpusEncoder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private val bitrate: Int = 64_000
) {
    private var codec: MediaCodec? = null
    private var inputSamples = 0L
    private var started = false

    @Volatile
    var lastError: String? = null
        private set

    fun start(onPacket: (ByteArray) -> Unit) {
        if (started) return
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        // CBR, как в прошивке (VBR=0, VBR-constraint=0).
        runCatching { format.setInteger("vbr", 0) }
        runCatching { format.setInteger("complexity", 10) }

        val c = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (t: Throwable) {
            lastError = "На устройстве нет Opus-энкодера MediaCodec: ${t.message}"
            throw IllegalStateException(lastError, t)
        }
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (t: Throwable) {
            runCatching { c.release() }
            lastError = "Не удалось запустить Opus-энкодер: ${t.message}"
            throw IllegalStateException(lastError, t)
        }
        codec = c
        started = true
        inputSamples = 0
        this.onPacket = onPacket
    }

    private var onPacket: ((ByteArray) -> Unit)? = null

    /** Подать PCM 16-bit interleaved stereo. Размер должен быть кратен 4. */
    fun encode(pcm: ByteArray, size: Int = pcm.size) {
        val c = codec ?: return
        if (size <= 0) return
        val info = MediaCodec.BufferInfo()
        var offset = 0
        while (offset < size) {
            val index = c.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(c, info)
                continue
            }
            val buffer = c.getInputBuffer(index) ?: continue
            buffer.clear()
            // Ровно 20 мс (960 кадров stereo 16-bit) и целое число кадров,
            // чтобы MediaCodec выдавал по одному Opus-пакету на 20 мс.
            val wanted = minOf(buffer.capacity(), size - offset, FRAME_BYTES)
            val chunk = wanted - (wanted % 4)
            if (chunk <= 0) {
                c.queueInputBuffer(index, 0, 0, 0, 0)
                break
            }
            buffer.put(pcm, offset, chunk)
            val pts = inputSamples * 1_000_000L / sampleRate
            c.queueInputBuffer(index, 0, chunk, pts, 0)
            inputSamples += chunk / (2L * channels)
            offset += chunk
            drain(c, info)
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val packet = ByteArray(info.size)
                            buffer.get(packet)
                            onPacket?.invoke(packet)
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "output format: ${c.outputFormat}")
                }
                else -> return
            }
        }
    }

    fun stop() {
        val c = codec ?: return
        codec = null
        started = false
        try {
            val index = c.dequeueInputBuffer(50_000)
            if (index >= 0) {
                c.queueInputBuffer(index, 0, 0, inputSamples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < deadline) {
                val out = c.dequeueOutputBuffer(info, 20_000)
                if (out >= 0) {
                    c.releaseOutputBuffer(out, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }
        } catch (_: Throwable) {
        }
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    companion object {
        private const val TAG = "SberBoomOpus"

        /** 20 мс при 48 кГц stereo 16-bit: 960 * 2 * 2. */
        private const val FRAME_BYTES = 3_840

        fun isAvailable(): Boolean = try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            c.release()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
