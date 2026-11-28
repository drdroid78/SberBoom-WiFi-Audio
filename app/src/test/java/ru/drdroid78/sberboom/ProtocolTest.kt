package ru.drdroid78.sberboom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.drdroid78.sberboom.audio.OpusPacket
import ru.drdroid78.sberboom.audio.PcmConverter
import ru.drdroid78.sberboom.audio.UdpPacket
import ru.drdroid78.sberboom.protocol.Protocol
import ru.drdroid78.sberboom.protocol.Tlv
import ru.drdroid78.sberboom.protocol.bytes
import ru.drdroid78.sberboom.protocol.long
import ru.drdroid78.sberboom.protocol.nested
import ru.drdroid78.sberboom.protocol.str

class ProtocolTest {

    @Test
    fun varintRoundTrip() {
        assertEquals("ac02", Tlv.varint(300).joinToString("") { "%02x".format(it) })
        assertEquals("00", Tlv.varint(0).joinToString("") { "%02x".format(it) })
        assertEquals("ffffffff0f", Tlv.varint(0xffffffffL).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun fieldStringRoundTrip() {
        val bytes = Tlv.concat(
            Tlv.fieldString(2, "hello"),
            Tlv.fieldVarint(3, 42),
            Tlv.fieldString(7, "мир")
        )
        val map = Tlv.decode(bytes)
        assertEquals("hello", map.str(2))
        assertEquals(42L, map.long(3))
        assertEquals("мир", map.str(7))
    }

    @Test
    fun requestEnvelopeHasRequiredFields() {
        val env = Tlv.decode(
            Protocol.envelope("id-1", Protocol.getStateRequest(), "pin-token", "client", "cid")
        )
        assertEquals(Protocol.TYPE_REQUEST, env.long(Protocol.ENV_TYPE))
        assertEquals("id-1", env.str(Protocol.ENV_MSG_ID))
        assertEquals("pin-token", env.str(Protocol.ENV_TOKEN))
        assertEquals(Protocol.TOKEN_TYPE_PIN, env.long(Protocol.ENV_TOKEN_TYPE))
        assertEquals("client", env.str(Protocol.ENV_CLIENT_NAME))
        assertEquals("cid", env.str(Protocol.ENV_CLIENT_ID))
        assertNotNull(env.bytes(Protocol.ENV_REQUEST_DATA))
    }

    @Test
    fun starCommandEnvelopeUsesBackendJwtType() {
        val cmd = Protocol.starCommandAudioStart("speaker", "mr", "sender", "{}", 0)
        val env = Tlv.decode(Protocol.starCommandEnvelope("id-2", cmd, null, "client", "cid"))
        assertEquals(Protocol.TYPE_STAR_COMMAND, env.long(Protocol.ENV_TYPE))
        assertEquals(Protocol.TOKEN_TYPE_BACKEND_JWT, env.long(Protocol.ENV_TOKEN_TYPE))
        val castData = env.nested(Protocol.ENV_STAR_COMMAND_DATA)
        assertNotNull(castData)
        val starBytes = castData!!.bytes(1)
        assertNotNull(starBytes)
        val star = Tlv.decode(starBytes!!)
        assertNotNull(star.nested(Protocol.STAR_FIELD_AUDIO_STREAM))
    }

    @Test
    fun audioStartCommandLayout() {
        val json = Protocol.audioSettingsJson("192.168.1.5", 12346, "mr-1")
        val cmd = Protocol.starCommandAudioStart("speaker-id", "mr-1", "sender-id", json, 0)
        val star = Tlv.decode(cmd)
        val audio = star.nested(Protocol.STAR_FIELD_AUDIO_STREAM)!!
        assertEquals("speaker-id", audio.str(Protocol.AUDIO_FIELD_MASTER_DEVICE_ID))
        assertEquals("mr-1", audio.str(Protocol.AUDIO_FIELD_MR_SESSION_ID))
        assertEquals("sender-id", audio.str(Protocol.AUDIO_FIELD_SENDER_DEVICE_ID))
        val start = audio.nested(Protocol.AUDIO_START_STREAMING)!!
        assertEquals(json, start.str(1))
        assertEquals(0L, start.long(2))
    }

    @Test
    fun settingsJsonHasNoFecAndRootAudiosync() {
        val json = Protocol.audioSettingsJson("10.0.0.2", 20000, "mr", fecEcc = 0, fecMsg = 1)
        assertTrue(json.contains("\"ecc\":0"))
        assertTrue(json.contains("\"msg\":1"))
        assertTrue(json.contains("\"port\":20000"))
        assertTrue(json.contains("\"audiosync\":{\"stereo\":0}"))
        assertTrue(json.contains("\"sampleRate\":48000"))
        assertTrue(json.contains("\"type\":\"BOOM_BOX\""))
    }

    @Test
    fun udpPortIsEvenAndInRange() {
        val rnd = java.util.Random(7)
        repeat(200) {
            val p = Protocol.chooseUdpPort(rnd)
            assertTrue("port $p", p in Protocol.UDP_PORT_MIN..Protocol.UDP_PORT_MAX)
            assertEquals(0, p % 2)
        }
    }

    @Test
    fun udpPacketHeaderIsLittleEndian() {
        val opus = byteArrayOf(0x11, 0x22, 0x33)
        val packet = UdpPacket.build(0x01020304, 0x0a0b0c0d, opus)
        assertEquals(11, packet.size)
        assertEquals(0x01020304, UdpPacket.sequence(packet))
        assertEquals(0x0a0b0c0dL, UdpPacket.timestamp(packet))
        assertEquals(opus.toList(), UdpPacket.payload(packet).toList())
    }

    @Test
    fun opusPacketDuration() {
        // config 31 (CELT FB 20 ms), code 0
        assertEquals(960, OpusPacket.samplesAt48k(byteArrayOf((31 shl 3).toByte())))
        // config 16 (CELT NB 2.5 ms)
        assertEquals(120, OpusPacket.samplesAt48k(byteArrayOf((16 shl 3).toByte())))
        // config 11 (SILK WB 60 ms)
        assertEquals(2880, OpusPacket.samplesAt48k(byteArrayOf((11 shl 3).toByte())))
        // code 1 = 2 frames
        assertEquals(1920, OpusPacket.samplesAt48k(byteArrayOf(((31 shl 3) or 1).toByte())))
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun envelopeMatchesPythonGolden() {
        val env = Protocol.envelope(
            "id-1", Protocol.getStateRequest(), "pin", "client", "cid"
        )
        assertEquals(
            "0802120469642d311a0370696e2a0462020a0030013a06636c69656e7450015a03636964",
            env.hex()
        )
    }

    @Test
    fun audioStartMatchesPythonGolden() {
        val json = Protocol.audioSettingsJson("10.0.0.2", 20000, "mr")
        val cmd = Protocol.starCommandAudioStart("speaker", "mr", "sender", json, 0)
        assertEquals(
            "820ab1021207737065616b65721a026d724a0673656e6465722299020a94027b2273616d706c6552617465223a34383030302c2275647053657474696e6773223a7b22464543223a7b22656363223a302c226d7367223a317d2c22756e69636173744c697374223a2231302e302e302e32222c2275647050726f746f636f6c56657273696f6e223a302c2274797065223a22424f4f4d5f424f58222c226d7253657373696f6e4964223a226d72222c22706f7274223a32303030302c226f7075734672616d6553697a654d73223a32302c22726573616d706c65725175616c697479223a2244454641554c54227d2c22617564696f73796e63223a7b2273746572656f223a307d2c226563686f223a66616c73652c22636f7272656374556e64657272756e4d6173746572223a66616c73657d1000",
            cmd.hex()
        )
    }

    @Test
    fun starEnvelopeMatchesPythonGolden() {
        val json = Protocol.audioSettingsJson("10.0.0.2", 20000, "mr")
        val cmd = Protocol.starCommandAudioStart("speaker", "mr", "sender", json, 0)
        val env = Protocol.starCommandEnvelope("id-2", cmd, null, "client", "cid")
        assertEquals(
            "0803120469642d3242b8020ab502820ab1021207737065616b65721a026d724a0673656e6465722299020a94027b2273616d706c6552617465223a34383030302c2275647053657474696e6773223a7b22464543223a7b22656363223a302c226d7367223a317d2c22756e69636173744c697374223a2231302e302e302e32222c2275647050726f746f636f6c56657273696f6e223a302c2274797065223a22424f4f4d5f424f58222c226d7253657373696f6e4964223a226d72222c22706f7274223a32303030302c226f7075734672616d6553697a654d73223a32302c22726573616d706c65725175616c697479223a2244454641554c54227d2c22617564696f73796e63223a7b2273746572656f223a307d2c226563686f223a66616c73652c22636f7272656374556e64657272756e4d6173746572223a66616c73657d100030003a06636c69656e7450015a03636964",
            env.hex()
        )
    }

    @Test
    fun udpPacketMatchesPythonGolden() {
        val packet = UdpPacket.build(0x01020304, 0x0a0b0c0d, byteArrayOf(0x11, 0x22, 0x33))
        assertEquals("040302010d0c0b0a112233", packet.hex())
    }

    @Test
    fun truncatedMessageDoesNotThrow() {
        val map = Tlv.decode(byteArrayOf(0x12, 0x05, 0x61))
        assertTrue(map[2] == null)
    }

    @Test
    fun udpPayloadHandlesShortPacket() {
        assertEquals(0, UdpPacket.payload(ByteArray(8)).size)
        assertEquals(0, UdpPacket.payload(ByteArray(3)).size)
        assertEquals(0, OpusPacket.samplesAt48k(ByteArray(0)))
    }

    @Test
    fun pinConnectWireUsesSessionFlow() {
        assertEquals("2200", Protocol.pinConnectRequest().hex())
        assertEquals("22070a05746f6b656e", Protocol.pinConnectRequest("token").hex())
    }

    @Test
    fun confirmPinConnectWireHasSessionAndPin() {
        val request = Protocol.confirmPinConnectRequest("sess", "ABC123")
        assertEquals("320e0a04736573731206414243313233", request.hex())
        val inner = Tlv.decode(request).bytes(Protocol.OP_CONFIRM_PIN_CONNECT)!!
        assertEquals("sess", Tlv.decode(inner).str(1))
        assertEquals("ABC123", Tlv.decode(inner).str(2))
    }

    @Test
    fun cancelPinConnectWireHasEmptyRequest() {
        assertEquals("2a020a00", Protocol.cancelPinConnectRequest().hex())
    }

    @Test
    fun metaDataParsing() {
        val deviceInfo = Tlv.concat(
            Tlv.fieldString(1, "SberBoom Home"),
            Tlv.fieldString(2, "sberboom-r2"),
            Tlv.fieldString(3, "1.104.35"),
            Tlv.fieldString(5, "SN123456")
        )
        val meta = Tlv.concat(
            Tlv.fieldBytes(1, deviceInfo),
            Tlv.fieldVarint(8, 1)
        )
        val response = Tlv.concat(
            Tlv.fieldVarint(1, Protocol.STATUS_OK),
            Tlv.fieldBytes(2, meta)
        )
        val requestData = Tlv.fieldBytes(Protocol.OP_GET_META_DATA, response)
        val parsed = Protocol.parseMetaData(Tlv.decode(requestData))
        assertNotNull(parsed)
        assertEquals("SN123456", parsed!!.serialNumber)
        assertEquals("SberBoom Home", parsed.deviceName)
        assertEquals(1L, parsed.udpProtocolVersion)
    }

    @Test
    fun pcmConverterPassesThrough48kStereo() {
        val src = ByteArray(3840) { (it % 251).toByte() }
        val out = PcmConverter().convert(src, src.size, 48_000, 2)
        assertEquals(src.size, out.size)
        assertTrue(src.contentEquals(out))
    }

    @Test
    fun pcmConverterResamples44100To48000() {
        // 100 мс mono 44.1 кГц -> 100 мс stereo 48 кГц.
        val framesIn = 4410
        val src = ByteArray(framesIn * 2)
        val out = PcmConverter().convert(src, src.size, 44_100, 1)
        val framesOut = out.size / 4
        assertTrue("framesOut=$framesOut", framesOut in 4700..4810)
        // Каналы должны быть заполнены одинаково (mono -> dual mono).
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 10) {
            assertEquals(bb.getShort(i * 4), bb.getShort(i * 4 + 2))
        }
    }
}
