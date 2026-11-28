package ru.drdroid78.sberboom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.drdroid78.sberboom.protocol.PinPairingPolicy
import ru.drdroid78.sberboom.protocol.Protocol
import ru.drdroid78.sberboom.protocol.Tlv

class PinPairingPolicyTest {
    private fun start(status: Long, session: String? = null): Map<Int, Any> = Tlv.decode(
        Tlv.fieldBytes(
            Protocol.OP_PIN_CONNECT,
            Tlv.concat(
                Tlv.fieldVarint(1, status),
                session?.let { Tlv.fieldString(2, it) } ?: byteArrayOf()
            )
        )
    )

    private fun confirm(status: Long, token: String? = null): Map<Int, Any> = Tlv.decode(
        Tlv.fieldBytes(
            Protocol.OP_CONFIRM_PIN_CONNECT,
            Tlv.concat(
                Tlv.fieldVarint(1, status),
                token?.let { Tlv.fieldString(2, it) } ?: byteArrayOf()
            )
        )
    )

    @Test
    fun pinConnectReturnsSessionNotToken() {
        val result = PinPairingPolicy.parseStart(start(Protocol.PAIR_INPUT_PIN_CODE, "session-1"), null)
        assertEquals(PinPairingPolicy.StartResult.State.PIN_REQUIRED, result.state)
        assertEquals("session-1", result.sessionId)
    }

    @Test
    fun existingTokenCanBeAuthorizedWithoutNewToken() {
        val result = PinPairingPolicy.parseStart(start(Protocol.PAIR_AUTHORIZED), "existing-token")
        assertEquals(PinPairingPolicy.StartResult.State.AUTHORIZED, result.state)
        assertNull(result.sessionId)
    }

    @Test
    fun authorizedWithoutExistingTokenIsInvalid() {
        val result = PinPairingPolicy.parseStart(start(Protocol.PAIR_AUTHORIZED), null)
        assertEquals(PinPairingPolicy.StartResult.State.INVALID_RESPONSE, result.state)
    }

    @Test
    fun pinRequiredWithoutSessionIsInvalid() {
        val result = PinPairingPolicy.parseStart(start(Protocol.PAIR_INPUT_PIN_CODE), null)
        assertEquals(PinPairingPolicy.StartResult.State.INVALID_RESPONSE, result.state)
    }

    @Test
    fun pinConnectStatusesAreNotConfused() {
        assertEquals(
            PinPairingPolicy.StartResult.State.SESSION_ALREADY_ACTIVE,
            PinPairingPolicy.parseStart(start(Protocol.PAIR_SESSION_ALREADY_ACTIVE), null).state
        )
        assertEquals(
            PinPairingPolicy.StartResult.State.TOO_MANY_REQUESTS,
            PinPairingPolicy.parseStart(start(Protocol.PAIR_TOO_MANY_REQUESTS), null).state
        )
        assertEquals(
            PinPairingPolicy.StartResult.State.DISABLED,
            PinPairingPolicy.parseStart(start(Protocol.PAIR_DISABLED), null).state
        )
        assertEquals(
            PinPairingPolicy.StartResult.State.INVALID_RESPONSE,
            PinPairingPolicy.parseStart(null, null).state
        )
    }

    @Test
    fun confirmAuthorizedReturnsAccessToken() {
        val token = "12345678-1234-1234-1234-123456789012"
        val result = PinPairingPolicy.parseConfirm(confirm(Protocol.PAIR_CONFIRM_AUTHORIZED, token))
        assertEquals(PinPairingPolicy.ConfirmResult.State.AUTHORIZED, result.state)
        assertEquals(token, result.token)
    }

    @Test
    fun confirmAuthorizedRequiresToken() {
        assertEquals(
            PinPairingPolicy.ConfirmResult.State.INVALID_RESPONSE,
            PinPairingPolicy.parseConfirm(confirm(Protocol.PAIR_CONFIRM_AUTHORIZED)).state
        )
        assertEquals(
            PinPairingPolicy.ConfirmResult.State.INVALID_RESPONSE,
            PinPairingPolicy.parseConfirm(confirm(Protocol.PAIR_CONFIRM_AUTHORIZED, "short")).state
        )
    }

    @Test
    fun confirmFailureStatusesAreMapped() {
        val cases = mapOf(
            Protocol.PAIR_CONFIRM_WRONG_PIN to PinPairingPolicy.ConfirmResult.State.WRONG_PIN,
            Protocol.PAIR_CONFIRM_ATTEMPTS_LIMIT to PinPairingPolicy.ConfirmResult.State.ATTEMPTS_LIMIT,
            Protocol.PAIR_CONFIRM_BAD_SESSION to PinPairingPolicy.ConfirmResult.State.BAD_SESSION,
            Protocol.PAIR_CONFIRM_DISABLED to PinPairingPolicy.ConfirmResult.State.DISABLED,
            Protocol.PAIR_CONFIRM_ERROR to PinPairingPolicy.ConfirmResult.State.ERROR
        )
        cases.forEach { (status, expected) ->
            assertEquals(expected, PinPairingPolicy.parseConfirm(confirm(status)).state)
        }
    }

    @Test
    fun omittedDefaultErrorStatusIsRejected() {
        val emptyResponse = Tlv.decode(
            Tlv.fieldBytes(Protocol.OP_CONFIRM_PIN_CONNECT, byteArrayOf())
        )
        val result = PinPairingPolicy.parseConfirm(emptyResponse)
        assertEquals(PinPairingPolicy.ConfirmResult.State.ERROR, result.state)
        assertTrue(result.token.isNullOrBlank())
    }
}
