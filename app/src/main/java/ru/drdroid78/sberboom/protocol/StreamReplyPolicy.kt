package ru.drdroid78.sberboom.protocol

/** Correlation is necessary, not proof that a loudspeaker actually rendered sound. */
object StreamReplyPolicy {
    data class Ack(val code: Long, val description: String?, val rawSize: Int) {
        val accepted: Boolean get() = code == Protocol.RESPONSE_OK
    }

    /** Transport acknowledgement only. It cannot confirm a playing audio session. */
    fun parseAck(envelope: Map<Int, Any>, expectedId: String, rawSize: Int): Ack? {
        if (expectedId.isEmpty() || envelope.str(Protocol.ENV_MSG_ID) != expectedId ||
            envelope.long(Protocol.ENV_TYPE) != Protocol.TYPE_STAR_COMMAND ||
            (envelope[Protocol.ENV_IS_REQUEST] != null && envelope.long(Protocol.ENV_IS_REQUEST) != 0L)) return null
        val response = envelope.nested(Protocol.RESPONSE_STAR_DATA) ?: return null
        val code = if (envelope[Protocol.RESPONSE_CODE] == null) 0L
            else envelope.long(Protocol.RESPONSE_CODE) ?: return null
        return Ack(code, response.str(1), rawSize)
    }

    /** For a separate session event, not a SberCast response envelope. */
    fun matches(star: Map<Int, Any>?, masterId: String, sessionId: String): Boolean {
        val audio = star?.nested(Protocol.STAR_FIELD_AUDIO_STREAM) ?: return false
        return audio.str(Protocol.AUDIO_FIELD_MASTER_DEVICE_ID) == masterId &&
            audio.str(Protocol.AUDIO_FIELD_MR_SESSION_ID) == sessionId &&
            audio.containsKey(Protocol.AUDIO_START_STREAMING) &&
            !audio.containsKey(Protocol.AUDIO_DEAD) && !audio.containsKey(Protocol.AUDIO_STOP_STREAMING)
    }
}
