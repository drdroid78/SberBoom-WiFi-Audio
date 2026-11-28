package ru.drdroid78.sberboom

import org.junit.Assert.*
import org.junit.Test
import ru.drdroid78.sberboom.protocol.*

class SessionPolicyTest {
    @Test fun jwtLocalChecks() {
        assertNotNull(TokenPolicy.error(null, null, "bad", 100))
        assertNotNull(TokenPolicy.error("none", 200, null, 100))
        assertNotNull(TokenPolicy.error("RS256", null, null, 100))
        assertNotNull(TokenPolicy.error("RS256", 100, null, 100))
        assertNull(TokenPolicy.error("RS256", 101, null, 100))
    }
    @Test fun sessionMatchesOnlyStartAndExactIds() {
        val star = Tlv.decode(Protocol.starCommandAudioStart("m", "s", "u", "{}", 0))
        assertTrue(StreamReplyPolicy.matches(star, "m", "s"))
        assertFalse(StreamReplyPolicy.matches(star, "other", "s"))
        assertFalse(StreamReplyPolicy.matches(star, "m", "other"))
        assertFalse(StreamReplyPolicy.matches(null, "m", "s"))
        assertFalse(StreamReplyPolicy.matches(Tlv.decode(Protocol.starCommandAudioDead("m", "s", "u")), "m", "s"))
    }
    @Test fun repeatedMastersPreserved() {
        val a = byteArrayOf(8, 1)
        val b = byteArrayOf(8, 2)
        val decoded = Tlv.decodeRepeated(Protocol.startStreaming("{}", 55, listOf(a, b)))
        assertEquals(55L, decoded[2]?.single())
        assertArrayEquals(a, decoded[3]?.get(0) as ByteArray)
        assertArrayEquals(b, decoded[3]?.get(1) as ByteArray)
    }
    @Test fun starPtpUsesRecoveredPort() {
        assertEquals(4391, StarPtpPacket.PORT)
    }
}
