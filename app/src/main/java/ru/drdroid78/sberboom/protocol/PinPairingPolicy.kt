package ru.drdroid78.sberboom.protocol

/** Pure parsing of the PIN handshake recovered from RequestEntities.proto. */
object PinPairingPolicy {
    data class StartResult(
        val state: State,
        val sessionId: String? = null
    ) {
        enum class State {
            AUTHORIZED,
            PIN_REQUIRED,
            SESSION_ALREADY_ACTIVE,
            TOO_MANY_REQUESTS,
            DISABLED,
            ERROR,
            INVALID_RESPONSE
        }
    }

    data class ConfirmResult(
        val state: State,
        val token: String? = null
    ) {
        enum class State {
            AUTHORIZED,
            WRONG_PIN,
            ATTEMPTS_LIMIT,
            BAD_SESSION,
            DISABLED,
            ERROR,
            INVALID_RESPONSE
        }
    }

    fun parseStart(requestData: Map<Int, Any>?, existingToken: String?): StartResult {
        val response = requestData?.nested(Protocol.OP_PIN_CONNECT) ?: return invalidStart()
        return when (response.long(1) ?: Protocol.PAIR_ERROR) {
            Protocol.PAIR_AUTHORIZED -> if (existingToken.isNullOrBlank()) invalidStart()
                else StartResult(StartResult.State.AUTHORIZED)
            Protocol.PAIR_INPUT_PIN_CODE -> response.str(2)
                ?.takeIf { it.isNotBlank() }
                ?.let { StartResult(StartResult.State.PIN_REQUIRED, it) }
                ?: invalidStart()
            Protocol.PAIR_SESSION_ALREADY_ACTIVE -> StartResult(StartResult.State.SESSION_ALREADY_ACTIVE)
            Protocol.PAIR_TOO_MANY_REQUESTS -> StartResult(StartResult.State.TOO_MANY_REQUESTS)
            Protocol.PAIR_DISABLED -> StartResult(StartResult.State.DISABLED)
            else -> StartResult(StartResult.State.ERROR)
        }
    }

    fun parseConfirm(requestData: Map<Int, Any>?): ConfirmResult {
        val response = requestData?.nested(Protocol.OP_CONFIRM_PIN_CONNECT) ?: return invalidConfirm()
        return when (response.long(1) ?: Protocol.PAIR_CONFIRM_ERROR) {
            Protocol.PAIR_CONFIRM_AUTHORIZED -> response.str(2)
                ?.takeIf { it.length >= 16 }
                ?.let { ConfirmResult(ConfirmResult.State.AUTHORIZED, it) }
                ?: invalidConfirm()
            Protocol.PAIR_CONFIRM_WRONG_PIN -> ConfirmResult(ConfirmResult.State.WRONG_PIN)
            Protocol.PAIR_CONFIRM_ATTEMPTS_LIMIT -> ConfirmResult(ConfirmResult.State.ATTEMPTS_LIMIT)
            Protocol.PAIR_CONFIRM_BAD_SESSION -> ConfirmResult(ConfirmResult.State.BAD_SESSION)
            Protocol.PAIR_CONFIRM_DISABLED -> ConfirmResult(ConfirmResult.State.DISABLED)
            else -> ConfirmResult(ConfirmResult.State.ERROR)
        }
    }

    private fun invalidStart() = StartResult(StartResult.State.INVALID_RESPONSE)
    private fun invalidConfirm() = ConfirmResult(ConfirmResult.State.INVALID_RESPONSE)
}
