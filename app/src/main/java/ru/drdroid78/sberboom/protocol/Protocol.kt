package ru.drdroid78.sberboom.protocol

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Протокол SberCast / StarOS (порт 20000, WebSocket поверх TLS).
 *
 * Все структуры восстановлены из libstardroid.so прошивки Salute TV
 * 1.100.39 (см. docs/PROTOCOL.md). Это «сырой» protobuf-wire, поэтому
 * сообщения собираются вручную через [Tlv].
 */
object Protocol {
    const val DEFAULT_PORT = 20000
    const val WS_PATH = "/"
    const val USER_AGENT = "WebSocket++/0.8.2"

    // ---- SberCastMessage -------------------------------------------------
    const val ENV_TYPE = 1
    const val ENV_MSG_ID = 2
    const val ENV_TOKEN = 3
    const val ENV_DIRECTIVE_DATA = 4
    const val ENV_REQUEST_DATA = 5
    const val ENV_TOKEN_TYPE = 6
    const val ENV_CLIENT_NAME = 7
    const val ENV_STAR_COMMAND_DATA = 8
    const val ENV_WSS_TYPE = 9
    const val ENV_IS_REQUEST = 10
    const val ENV_CLIENT_ID = 11
    // SberCastResponseMessage: fields differ from the request envelope.
    const val RESPONSE_CODE = 3
    const val RESPONSE_STAR_DATA = 6
    const val RESPONSE_OK = 0L
    const val PLAYER_OK = 0L
    const val PLAYER_UNSUPPORTED = 1L

    const val TYPE_DIRECTIVE = 1L
    const val TYPE_REQUEST = 2L
    const val TYPE_STAR_COMMAND = 3L

    const val TOKEN_TYPE_BACKEND_JWT = 0L
    const val TOKEN_TYPE_PIN = 1L
    const val TOKEN_TYPE_FIRST_SETUP = 2L

    // ---- CastRequestData -------------------------------------------------
    const val OP_PING = 3
    const val OP_PIN_CONNECT = 4
    const val OP_CANCEL_PIN_CONNECT = 5
    const val OP_CONFIRM_PIN_CONNECT = 6
    const val OP_GAMEPAD = 7
    const val OP_SMART_APP_STATE = 8
    const val OP_VOICE_TRANSPORT = 9
    const val OP_GET_META_DATA = 10
    const val OP_IRD = 11
    const val OP_GET_STATE = 12
    const val OP_FIND_REMOTE = 13
    const val OP_SET_VOLUME = 14
    const val OP_SET_TRACK_POS = 15
    const val OP_MEDIA_COMMAND = 16
    const val OP_GET_PLAYING_QUEUE = 17
    const val OP_KEEP_ALIVE = 18
    const val OP_GET_PAIRED_BT = 19
    const val OP_BT_DEVICE_COMMAND = 20
    const val OP_GET_SCANNED_BT = 21
    const val OP_BT_DISCOVERABLE = 22
    const val OP_SET_PLAYBACK_SPEED = 23

    // ---- MediaCommand ----------------------------------------------------
    const val MEDIA_MUTE = 0
    const val MEDIA_UNMUTE = 1
    const val MEDIA_NEXT = 2
    const val MEDIA_PREV = 3
    const val MEDIA_PLAY = 4
    const val MEDIA_PAUSE = 5
    const val MEDIA_LIKE = 6
    const val MEDIA_REMOVE_LIKE = 7
    const val MEDIA_START_MULTIROOM = 8
    const val MEDIA_SHUFFLE_ON = 9
    const val MEDIA_SHUFFLE_OFF = 10
    const val MEDIA_REPEAT_NONE = 11
    const val MEDIA_REPEAT_PLAYLIST = 12
    const val MEDIA_REPEAT_TRACK = 13
    const val MEDIA_DISLIKE = 14
    const val MEDIA_REMOVE_DISLIKE = 15

    // ---- PIN pairing -----------------------------------------------------
    // PinConnect.Response.Status
    const val PAIR_ERROR = 0L
    const val PAIR_INPUT_PIN_CODE = 1L
    const val PAIR_AUTHORIZED = 2L
    const val PAIR_SESSION_ALREADY_ACTIVE = 3L
    const val PAIR_TOO_MANY_REQUESTS = 4L
    const val PAIR_DISABLED = 5L

    // ConfirmPinConnect.Response.Status
    const val PAIR_CONFIRM_ERROR = 0L
    const val PAIR_CONFIRM_AUTHORIZED = 1L
    const val PAIR_CONFIRM_WRONG_PIN = 2L
    const val PAIR_CONFIRM_ATTEMPTS_LIMIT = 3L
    const val PAIR_CONFIRM_BAD_SESSION = 4L
    const val PAIR_CONFIRM_DISABLED = 5L

    const val STATUS_OK = 1L
    const val STATUS_ERROR = 4L
    const val STATUS_MISSING_FIELDS = 5L

    // ---- StarCommand / AudioStreamingSessionMessage ----------------------
    const val STAR_FIELD_AUDIO_STREAM = 160
    const val AUDIO_START_STREAMING = 4
    const val AUDIO_STOP_STREAMING = 5
    const val AUDIO_ALIVE = 6
    const val AUDIO_DEAD = 7
    const val AUDIO_CHANGE_CHANNEL = 8

    const val AUDIO_FIELD_MASTER_DEVICE_ID = 2
    const val AUDIO_FIELD_MR_SESSION_ID = 3
    const val AUDIO_FIELD_SENDER_DEVICE_ID = 9

    const val SAMPLE_RATE = 48_000
    const val OPUS_FRAME_MS = 20
    const val OPUS_FRAME_SAMPLES = SAMPLE_RATE * OPUS_FRAME_MS / 1000
    const val UDP_PORT_MIN = 10_000
    const val UDP_PORT_MAX = 35_620

    // ---------------------------------------------------------------------
    // Envelope
    // ---------------------------------------------------------------------

    fun envelope(
        msgId: String,
        requestData: ByteArray,
        token: String?,
        clientName: String,
        clientId: String,
        type: Long = TYPE_REQUEST,
        tokenType: Long = TOKEN_TYPE_PIN,
        isRequest: Boolean = true
    ): ByteArray {
        val parts = ArrayList<ByteArray>(8)
        parts += Tlv.fieldVarint(ENV_TYPE, type)
        parts += Tlv.fieldString(ENV_MSG_ID, msgId)
        if (!token.isNullOrEmpty()) parts += Tlv.fieldString(ENV_TOKEN, token)
        parts += Tlv.fieldBytes(ENV_REQUEST_DATA, requestData)
        parts += Tlv.fieldVarint(ENV_TOKEN_TYPE, tokenType)
        parts += Tlv.fieldString(ENV_CLIENT_NAME, clientName)
        if (isRequest) parts += Tlv.fieldVarint(ENV_IS_REQUEST, 1)
        parts += Tlv.fieldString(ENV_CLIENT_ID, clientId)
        return Tlv.concat(*parts.toTypedArray())
    }

    /**
     * Конверт для StarCommand (SberCastMessage.type = STAR_COMMAND).
     * token_type = 0 (BACKEND_JWT) — обязательное условие приёма на динамике.
     */
    fun starCommandEnvelope(
        msgId: String,
        starCommand: ByteArray,
        token: String?,
        clientName: String,
        clientId: String
    ): ByteArray {
        val starData = Tlv.fieldBytes(1, starCommand) // CastStarCommandData.data
        val parts = ArrayList<ByteArray>(8)
        parts += Tlv.fieldVarint(ENV_TYPE, TYPE_STAR_COMMAND)
        parts += Tlv.fieldString(ENV_MSG_ID, msgId)
        if (!token.isNullOrEmpty()) parts += Tlv.fieldString(ENV_TOKEN, token)
        parts += Tlv.fieldBytes(ENV_STAR_COMMAND_DATA, starData)
        parts += Tlv.fieldVarint(ENV_TOKEN_TYPE, TOKEN_TYPE_BACKEND_JWT)
        parts += Tlv.fieldString(ENV_CLIENT_NAME, clientName)
        parts += Tlv.fieldVarint(ENV_IS_REQUEST, 1)
        parts += Tlv.fieldString(ENV_CLIENT_ID, clientId)
        return Tlv.concat(*parts.toTypedArray())
    }

    // ---------------------------------------------------------------------
    // Обычные операции CastRequestData
    // ---------------------------------------------------------------------

    fun op(opCode: Int, inner: ByteArray = emptyInner()): ByteArray =
        Tlv.fieldBytes(opCode, inner)

    fun emptyInner(): ByteArray = Tlv.fieldBytes(1, ByteArray(0))

    /** PinConnect.Request { optional string pin_access_token = 1; } */
    fun pinConnectRequest(pinAccessToken: String? = null): ByteArray = op(
        OP_PIN_CONNECT,
        pinAccessToken?.takeIf { it.isNotBlank() }?.let { Tlv.fieldString(1, it) }
            ?: ByteArray(0)
    )

    /** ConfirmPinConnect.Request { session = 1; pin_code = 2; } */
    fun confirmPinConnectRequest(sessionId: String, pinCode: String): ByteArray = op(
        OP_CONFIRM_PIN_CONNECT,
        Tlv.concat(Tlv.fieldString(1, sessionId), Tlv.fieldString(2, pinCode))
    )

    /** CancelPinConnect.Request { Empty empty = 1; } */
    fun cancelPinConnectRequest(): ByteArray = op(OP_CANCEL_PIN_CONNECT, emptyInner())

    fun getMetaDataRequest(): ByteArray = op(OP_GET_META_DATA)
    fun getStateRequest(): ByteArray = op(OP_GET_STATE)

    fun mediaCommand(action: Int): ByteArray =
        op(OP_MEDIA_COMMAND, Tlv.fieldVarint(1, action.toLong()))

    fun setVolume(percent: Int): ByteArray =
        op(OP_SET_VOLUME, Tlv.fieldVarint(1, percent.coerceIn(0, 100).toLong()))

    fun seek(seconds: Int): ByteArray =
        op(OP_SET_TRACK_POS, Tlv.fieldVarint(1, seconds.coerceAtLeast(0).toLong()))

    fun playbackSpeed(speed: Float): ByteArray =
        op(OP_SET_PLAYBACK_SPEED, Tlv.fieldFloat(1, speed))

    fun btDeviceCommand(mac: String, cmd: Int): ByteArray = op(
        OP_BT_DEVICE_COMMAND,
        Tlv.concat(Tlv.fieldString(1, mac), Tlv.fieldVarint(2, cmd.toLong()))
    )

    // ---------------------------------------------------------------------
    // AudioStreamingSessionMessage
    // ---------------------------------------------------------------------

    /** StartStreaming{ settings=1, mono_timestamp=2, synced_masters=3 }. Field 3 is omitted until real records are negotiated. */
    fun startStreaming(settingsJson: String, monoTimestamp: Long, syncedMasters: List<ByteArray> = emptyList()): ByteArray =
        Tlv.concat(Tlv.fieldString(1, settingsJson), Tlv.fieldVarint(2, monoTimestamp),
            *syncedMasters.map { Tlv.fieldBytes(3, it) }.toTypedArray())

    fun audioSession(
        masterDeviceId: String,
        mrSessionId: String,
        senderDeviceId: String,
        innerField: Int,
        inner: ByteArray
    ): ByteArray = Tlv.concat(
        Tlv.fieldString(AUDIO_FIELD_MASTER_DEVICE_ID, masterDeviceId),
        Tlv.fieldString(AUDIO_FIELD_MR_SESSION_ID, mrSessionId),
        Tlv.fieldString(AUDIO_FIELD_SENDER_DEVICE_ID, senderDeviceId),
        Tlv.fieldBytes(innerField, inner)
    )

    fun starCommandAudioStart(
        masterDeviceId: String,
        mrSessionId: String,
        senderDeviceId: String,
        settingsJson: String,
        monoTimestamp: Long = 0,
        syncedMasters: List<ByteArray> = emptyList()
    ): ByteArray = Tlv.fieldBytes(
        STAR_FIELD_AUDIO_STREAM,
        audioSession(
            masterDeviceId,
            mrSessionId,
            senderDeviceId,
            AUDIO_START_STREAMING,
            startStreaming(settingsJson, monoTimestamp, syncedMasters)
        )
    )

    fun starCommandAudioStop(
        masterDeviceId: String,
        mrSessionId: String,
        senderDeviceId: String
    ): ByteArray = Tlv.fieldBytes(
        STAR_FIELD_AUDIO_STREAM,
        audioSession(
            masterDeviceId,
            mrSessionId,
            senderDeviceId,
            AUDIO_STOP_STREAMING,
            Tlv.fieldBytes(1, ByteArray(0))
        )
    )

    fun starCommandAudioAlive(
        masterDeviceId: String,
        mrSessionId: String,
        senderDeviceId: String
    ): ByteArray = Tlv.fieldBytes(
        STAR_FIELD_AUDIO_STREAM,
        audioSession(
            masterDeviceId,
            mrSessionId,
            senderDeviceId,
            AUDIO_ALIVE,
            Tlv.fieldBytes(1, ByteArray(0))
        )
    )

    fun starCommandAudioDead(
        masterDeviceId: String,
        mrSessionId: String,
        senderDeviceId: String
    ): ByteArray = Tlv.fieldBytes(
        STAR_FIELD_AUDIO_STREAM,
        audioSession(
            masterDeviceId,
            mrSessionId,
            senderDeviceId,
            AUDIO_DEAD,
            Tlv.fieldBytes(1, ByteArray(0))
        )
    )

    // ---------------------------------------------------------------------
    // JSON настроек UDP-приёмника
    // ---------------------------------------------------------------------

    /**
     * JSON, который динамик передаёт в UdpStreamReceiverImpl::setSettings.
     *
     * FEC отключён явно: ecc=0, msg=1. При ecc<1 прошивка не создаёт
     * BlockBuffer и использует processNoFEC (проверено по дизассемблеру).
     */
    fun audioSettingsJson(
        unicastIp: String,
        port: Int,
        mrSessionId: String,
        sampleRate: Int = SAMPLE_RATE,
        opusFrameMs: Int = OPUS_FRAME_MS,
        fecEcc: Int = 0,
        fecMsg: Int = 1,
        udpProtocolVersion: Int = 0
    ): String {
        val p = port.coerceIn(UDP_PORT_MIN, UDP_PORT_MAX)
        return buildString(512) {
            append('{')
            append("\"sampleRate\":").append(sampleRate).append(',')
            append("\"udpSettings\":{")
            append("\"FEC\":{\"ecc\":").append(fecEcc).append(",\"msg\":").append(fecMsg).append("},")
            append("\"unicastList\":\"").append(escapeJson(unicastIp)).append("\",")
            append("\"udpProtocolVersion\":").append(udpProtocolVersion).append(',')
            append("\"type\":\"BOOM_BOX\",")
            append("\"mrSessionId\":\"").append(escapeJson(mrSessionId)).append("\",")
            append("\"port\":").append(p).append(',')
            append("\"opusFrameSizeMs\":").append(opusFrameMs).append(',')
            append("\"resamplerQuality\":\"DEFAULT\"")
            append("},")
            append("\"audiosync\":{\"stereo\":0},")
            append("\"echo\":false,")
            append("\"correctUnderrunMaster\":false")
            append('}')
        }
    }

    fun chooseUdpPort(random: java.util.Random = java.util.Random()): Int {
        val steps = (UDP_PORT_MAX - UDP_PORT_MIN) / 2
        return UDP_PORT_MIN + 2 * random.nextInt(steps + 1)
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // Разбор ответов
    // ---------------------------------------------------------------------

    data class MetaData(
        val serialNumber: String?,
        val deviceName: String?,
        val model: String?,
        val firmware: String?,
        val udpProtocolVersion: Long?,
        val multiroomSupported: Boolean = false,
        val multiroomInfoJson: String? = null,
        val deviceGroupServiceVersion: Long? = null,
        val deviceGroupInfoJson: String? = null
    )

    /** Разбор GetMetaData.Response из request_data. */
    fun parseMetaData(requestData: Map<Int, Any>): MetaData? {
        val response = requestData.nested(OP_GET_META_DATA) ?: return null
        if (response.long(1) != STATUS_OK) return null
        val meta = response.nested(2) ?: return null
        val info = meta.nested(1)
        val serial = info?.str(5)
        val name = info?.str(1)
        val model = info?.str(2)
        val firmware = info?.str(3)
        val udp = meta.long(8)
        return MetaData(serial, name, model, firmware, udp,
            meta.long(5) == 1L, meta.str(9), meta.long(10), meta.str(14))
    }

    /** A matching response envelope with a successful request-level code. */
    fun responseAccepted(envelope: Map<Int, Any>, type: Long): Boolean =
        envelope.long(ENV_TYPE) == type &&
            !envelope.str(ENV_MSG_ID).isNullOrEmpty() &&
            (envelope[ENV_IS_REQUEST] == null || envelope.long(ENV_IS_REQUEST) == 0L) &&
            (envelope[RESPONSE_CODE] == null || envelope.long(RESPONSE_CODE) == RESPONSE_OK)

    fun parsePlayerStatus(requestData: Map<Int, Any>): Long? {
        val response = requestData.nested(OP_MEDIA_COMMAND) ?: return null
        // Proto3 omits an enum field whose value is the default (PRS_OK=0).
        return if (response[1] == null) PLAYER_OK else response.long(1)
    }

    fun parseVolumeAck(requestData: Map<Int, Any>): Long? {
        val response = requestData.nested(OP_SET_VOLUME) ?: return null
        // SetVolume.Response contains Empty, not a numeric Status.
        return if (response.isEmpty() || response.bytes(1)?.isEmpty() == true) STATUS_OK else null
    }

    fun parseStatus(requestData: Map<Int, Any>, opCode: Int): Long? =
        requestData.nested(opCode)?.long(1)

    fun decodeText(bytes: ByteArray): String = bytes.toString(StandardCharsets.UTF_8)
}
