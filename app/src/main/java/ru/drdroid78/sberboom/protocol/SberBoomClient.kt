package ru.drdroid78.sberboom.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Клиент нативного SberCast-канала динамика: wss://host:20000/.
 *
 * Соединение — обычный WebSocket, payload — protobuf-wire сообщения
 * SberCastMessage (см. [Protocol]). TLS: системная проверка либо явно
 * заданный владельцем SHA-256 отпечаток сертификата конкретной колонки.
 */
class SberBoomClient(
    val host: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val clientName: String = "SberBoom Wi-Fi Audio",
    val clientId: String = UUID.randomUUID().toString(),
    val certificatePin: String? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Map<Int, Any>>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Map<Int, Any>> = _events.asSharedFlow()

    @Volatile
    var pinToken: String? = null
        private set

    /** Backend-JWT для StarCommand; PIN-токен здесь не подходит. */
    @Volatile
    var jwt: String? = null
        private set

    @Volatile
    private var webSocket: WebSocket? = null

    private val starPending = ConcurrentHashMap<String, CompletableDeferred<StreamReplyPolicy.Ack>>()

    private val http: OkHttpClient = buildHttpClient()

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (!certificatePin.isNullOrBlank()) {
            val expected = certificatePin.lowercase().replace(":", "").trim()
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 certificate fingerprint" }
            val pinned = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                    throw CertificateException("Client certificate not supported")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing certificate")
                    leaf.checkValidity()
                    val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    val actual = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
                    if (actual != expected) throw CertificateException("Speaker certificate fingerprint mismatch")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(pinned), SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, pinned)
                .hostnameVerifier { name, _ -> name.equals(host, ignoreCase = true) }
        }
        return builder
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun setTokens(pin: String?, backendJwt: String?) {
        pinToken = pin?.takeIf { it.isNotBlank() }
        jwt = backendJwt?.takeIf { it.isNotBlank() }
    }

    /** Совместимость: устанавливает только PIN-токен. */
    fun setToken(value: String?) {
        pinToken = value?.takeIf { it.isNotBlank() }
    }

    val isConnected: Boolean get() = _state.value == ConnectionState.CONNECTED

    suspend fun connect(): Result<Unit> {
        if (_state.value == ConnectionState.CONNECTED) return Result.success(Unit)
        _state.value = ConnectionState.CONNECTING
        val url = "wss://$host:$port${Protocol.WS_PATH}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Protocol.USER_AGENT)
            .build()

        val connected = CompletableDeferred<Result<Unit>>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@SberBoomClient.webSocket = webSocket
                _state.value = ConnectionState.CONNECTED
                connected.complete(Result.success(Unit))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleFrame(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnectionState.DISCONNECTED
                if (!connected.isCompleted) connected.complete(Result.failure(t))
                failPending(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnectionState.DISCONNECTED
                failPending(IllegalStateException("WebSocket closed: $code $reason"))
            }
        }
        val opening = http.newWebSocket(request, listener)
        var success = false
        try {
            val outcome = withTimeoutOrNull(12_000) { connected.await() }
                ?: Result.failure(java.net.SocketTimeoutException("connect timeout"))
            success = outcome.isSuccess
            return outcome
        } finally {
            if (!success) {
                opening.cancel()
                _state.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        val envelope = try {
            Tlv.decode(bytes)
        } catch (t: Throwable) {
            return
        }
        val msgId = envelope.str(Protocol.ENV_MSG_ID)
        val requestData = envelope.nested(Protocol.ENV_REQUEST_DATA) ?: emptyMap()

        if (msgId != null && envelope.long(Protocol.ENV_IS_REQUEST) != 1L) {
            if (envelope.long(Protocol.ENV_TYPE) != Protocol.TYPE_STAR_COMMAND) {
                pending.remove(msgId)?.complete(bytes)
            } else {
                val reply = StreamReplyPolicy.parseAck(envelope, msgId, bytes.size)
                if (reply != null) starPending.remove(msgId)?.complete(reply)
            }
        }

        scope.launch { _events.emit(requestData) }
    }

    private fun failPending(t: Throwable) {
        pending.keys.toList().forEach { key -> pending.remove(key)?.completeExceptionally(t) }
        starPending.keys.toList().forEach { key -> starPending.remove(key)?.completeExceptionally(t) }
    }

    private fun sendRaw(op: ByteArray): String {
        val ws = webSocket ?: throw IllegalStateException("not connected")
        val msgId = UUID.randomUUID().toString()
        val envelope = Protocol.envelope(msgId, op, pinToken, clientName, clientId)
        if (!ws.send(envelope.toByteString())) throw IllegalStateException("send failed")
        return msgId
    }

    /** Отправить команду без ожидания ответа. */
    fun fire(op: ByteArray) {
        sendRaw(op)
    }

    /** Запрос с ожиданием ответа по correlation id. */
    suspend fun request(
        op: ByteArray,
        timeoutMs: Long = 6_000,
        token: String? = pinToken
    ): Map<Int, Any>? {
        val msgId = UUID.randomUUID().toString()
        val ws = webSocket ?: throw IllegalStateException("not connected")
        val deferred = CompletableDeferred<ByteArray>()
        pending[msgId] = deferred
        val envelope = Protocol.envelope(msgId, op, token, clientName, clientId)
        if (!ws.send(envelope.toByteString())) {
            pending.remove(msgId)
            throw IllegalStateException("send failed")
        }
        try {
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
            return raw?.let { Tlv.decode(it) }
                ?.takeIf { Protocol.responseAccepted(it, Protocol.TYPE_REQUEST) }
                ?.nested(Protocol.ENV_REQUEST_DATA)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            pending.remove(msgId, deferred)
        }
    }

    /**
     * Отправить StarCommand (SberCastMessage.type = STAR_COMMAND).
     *
     * ВАЖНО: прошивка принимает такие сообщения только при token_type=0
     * (BACKEND_JWT) и валидном JWT. PIN-токен здесь не подходит — динамик
     * молча проигнорирует сообщение (проверено на живой прошивке 1.104.35).
     */
    suspend fun sendStarCommand(
        starCommand: ByteArray,
        timeoutMs: Long = 4_000
    ): StreamReplyPolicy.Ack? {
        val ws = webSocket ?: throw IllegalStateException("not connected")
        val token = jwt?.trim()?.removePrefix("Bearer ") ?: return null
        if (JwtInfo.validationError(token) != null) return null
        val msgId = UUID.randomUUID().toString()
        val envelope = Protocol.starCommandEnvelope(msgId, starCommand, token, clientName, clientId)
        val waiter = CompletableDeferred<StreamReplyPolicy.Ack>()
        starPending[msgId] = waiter
        try {
            if (!ws.send(envelope.toByteString())) throw IllegalStateException("send failed")
            return withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            starPending.remove(msgId, waiter)
        }
    }

    suspend fun fetchMetaData(timeoutMs: Long = 6_000): Protocol.MetaData? {
        val response = request(Protocol.getMetaDataRequest(), timeoutMs) ?: return null
        return Protocol.parseMetaData(response)
    }

    suspend fun fetchState(timeoutMs: Long = 5_000): Map<Int, Any>? {
        val response = request(Protocol.getStateRequest(), timeoutMs) ?: return null
        return response.nested(Protocol.OP_GET_STATE)
    }

    suspend fun setVolume(percent: Int, timeoutMs: Long = 5_000): Long? {
        val response = request(Protocol.setVolume(percent), timeoutMs) ?: return null
        return Protocol.parseVolumeAck(response)
    }

    suspend fun media(action: Int): Long? {
        val response = request(Protocol.mediaCommand(action)) ?: return null
        return Protocol.parsePlayerStatus(response)
    }

    /**
     * Первый этап PIN handshake. Поле 2 ответа — pin_connection_session,
     * а не access token. Новый PIN-токен выдаётся только ConfirmPinConnect.
     */
    suspend fun beginPairing(timeoutMs: Long = 8_000): PairStartResult {
        val existingToken = pinToken
        val response = try {
            request(Protocol.pinConnectRequest(existingToken), timeoutMs, token = existingToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return PairStartResult.Error(
            "Ответ PinConnect не получен", PairError.NETWORK
        )
        return when (val parsed = PinPairingPolicy.parseStart(response, existingToken)) {
            PinPairingPolicy.StartResult.State.AUTHORIZED -> PairStartResult.Success(existingToken!!)
            PinPairingPolicy.StartResult.State.PIN_REQUIRED -> PairStartResult.PinRequired(parsed.sessionId!!)
            PinPairingPolicy.StartResult.State.SESSION_ALREADY_ACTIVE -> PairStartResult.Error(
                "Сессия сопряжения уже активна", PairError.SESSION_ACTIVE
            )
            PinPairingPolicy.StartResult.State.TOO_MANY_REQUESTS -> PairStartResult.Error(
                "Слишком много запросов сопряжения", PairError.TOO_MANY_REQUESTS
            )
            PinPairingPolicy.StartResult.State.DISABLED -> PairStartResult.Error(
                "Сопряжение отключено на динамике", PairError.DISABLED
            )
            PinPairingPolicy.StartResult.State.ERROR -> PairStartResult.Error(
                "Динамик отклонил PinConnect", PairError.REJECTED
            )
            PinPairingPolicy.StartResult.State.INVALID_RESPONSE -> PairStartResult.Error(
                "Некорректный ответ PinConnect", PairError.INVALID_RESPONSE
            )
        }
    }

    /** Второй этап: session ID + показанный динамиком 6-символьный PIN. */
    suspend fun confirmPairing(
        sessionId: String,
        pinCode: String,
        timeoutMs: Long = 8_000
    ): PairResult {
        if (sessionId.isBlank() || pinCode.length != 6) {
            return PairResult.Error("Нужны session ID и 6-символьный PIN", PairError.INVALID_INPUT)
        }
        val response = try {
            request(
                Protocol.confirmPinConnectRequest(sessionId, pinCode),
                timeoutMs,
                token = null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return PairResult.Error(
            "Ответ ConfirmPinConnect не получен", PairError.NETWORK
        )
        return when (val parsed = PinPairingPolicy.parseConfirm(response)) {
            PinPairingPolicy.ConfirmResult.State.AUTHORIZED -> {
                pinToken = parsed.token
                PairResult.Success(parsed.token!!)
            }
            PinPairingPolicy.ConfirmResult.State.WRONG_PIN -> PairResult.Error(
                "Неверный PIN", PairError.WRONG_PIN
            )
            PinPairingPolicy.ConfirmResult.State.ATTEMPTS_LIMIT -> PairResult.Error(
                "Лимит неверных попыток исчерпан; начните новую сессию", PairError.ATTEMPTS_LIMIT
            )
            PinPairingPolicy.ConfirmResult.State.BAD_SESSION -> PairResult.Error(
                "Session ID не совпадает; начните новую сессию", PairError.BAD_SESSION
            )
            PinPairingPolicy.ConfirmResult.State.DISABLED -> PairResult.Error(
                "Сопряжение отключено на динамике", PairError.DISABLED
            )
            PinPairingPolicy.ConfirmResult.State.ERROR -> PairResult.Error(
                "Динамик отклонил ConfirmPinConnect", PairError.REJECTED
            )
            PinPairingPolicy.ConfirmResult.State.INVALID_RESPONSE -> PairResult.Error(
                "Некорректный ответ ConfirmPinConnect", PairError.INVALID_RESPONSE
            )
        }
    }

    /** Отменяет созданную PIN-сессию; новый токен для этого не требуется. */
    suspend fun cancelPairing(timeoutMs: Long = 2_000): Boolean = try {
        request(Protocol.cancelPinConnectRequest(), timeoutMs, token = null) != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    fun close() {
        try {
            webSocket?.close(1000, "bye")
        } catch (_: Throwable) {
        }
        webSocket = null
        _state.value = ConnectionState.DISCONNECTED
        failPending(IllegalStateException("closed"))
        scope.cancel()
    }

    sealed class PairStartResult {
        data class PinRequired(val sessionId: String) : PairStartResult()
        data class Success(val token: String) : PairStartResult()
        data class Error(val message: String, val code: PairError) : PairStartResult()
    }

    sealed class PairResult {
        data class Success(val token: String) : PairResult()
        data class Error(val message: String, val code: PairError) : PairResult()
    }

    enum class PairError {
        INVALID_INPUT,
        INVALID_RESPONSE,
        NETWORK,
        WRONG_PIN,
        ATTEMPTS_LIMIT,
        BAD_SESSION,
        SESSION_ACTIVE,
        TOO_MANY_REQUESTS,
        DISABLED,
        REJECTED
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
}
