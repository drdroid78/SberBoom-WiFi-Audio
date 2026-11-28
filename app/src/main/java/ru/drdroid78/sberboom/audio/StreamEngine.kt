package ru.drdroid78.sberboom.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import ru.drdroid78.sberboom.data.AppPrefs
import ru.drdroid78.sberboom.protocol.Protocol
import ru.drdroid78.sberboom.protocol.JwtInfo
import ru.drdroid78.sberboom.protocol.SberBoomClient
import ru.drdroid78.sberboom.protocol.StarPtpPacket
import ru.drdroid78.sberboom.protocol.StarPtpPeer
import ru.drdroid78.sberboom.util.DiagLog
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Движок потока: SberCast StarCommand(160) StartStreaming + Opus/UDP.
 *
 * Ограничение прошивки: StarCommand принимается только с token_type=0
 * (BACKEND_JWT) и валидным JWT. PIN-токен для этого канала не подходит.
 * Без JWT или коррелированного ответа аудиопоток не отправляется.
 */
class StreamEngine(private val context: Context) {

    sealed class State {
        data object Idle : State()
        data class Connecting(val detail: String) : State()
        data class Streaming(
            val host: String,
            val udpPort: Int,
            val source: String,
            val jwtUsed: Boolean
        ) : State()
        data class Error(val message: String) : State()
    }

    data class Stats(
        val packets: Long = 0,
        val bytes: Long = 0,
        val uptimeMs: Long = 0,
        val lastPacketAgeMs: Long = 0
    )

    data class Config(
        val host: String,
        val wsPort: Int,
        val speakerId: String,
        val pinToken: String?,
        val jwt: String?,
        val volume: Int,
        val source: PcmSource,
        val clientName: String,
        val clientId: String,
        val certificatePin: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val running = AtomicBoolean(false)
    private val gate = SessionGate()
    private val lifecycle = Mutex()
    private val starting = AtomicReference<Pair<SessionGate.Ticket, Job>?>(null)
    private var client: SberBoomClient? = null
    private var sender: UdpAudioSender? = null
    private var encoder: OpusEncoder? = null
    @Volatile private var ptpPeer: StarPtpPeer? = null
    private var sessionId: String = ""
    private var masterId: String = ""
    private var senderId: String = ""
    private var sourceThread: Thread? = null
    @Volatile private var activeSource: PcmSource? = null
    private var keepAliveJob: Job? = null
    private var monitorJob: Job? = null
    private var statsJob: Job? = null
    private var startedAt = 0L

    var lastSettingsJson: String = ""
        private set

    suspend fun start(config: Config): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val ticket = gate.begin() ?: return@withLock Result.failure(IllegalStateException("Предыдущая сессия ещё не завершена"))
            val owner = ticket to currentCoroutineContext()[Job]!!
            starting.set(owner)
            try {
                suspend fun checkStarting() {
                    currentCoroutineContext().ensureActive()
                    if (!gate.isStarting(ticket)) throw kotlinx.coroutines.CancellationException("Остановка сессии")
                }
                checkStarting()
                require(config.host.isNotBlank() && config.clientId.isNotBlank()) {
                    "Не заданы адрес динамика или ID устройства-источника"
                }
                JwtInfo.validationError(config.jwt)?.let { throw IllegalStateException(it) }
                val sourceDeviceId = config.clientId.trim()
                _state.value = State.Connecting("Подключение к ${config.host}:${config.wsPort}")
                val c = SberBoomClient(config.host, config.wsPort, config.clientName, sourceDeviceId, config.certificatePin)
                client = c
                c.setTokens(config.pinToken, config.jwt)
                c.connect().getOrThrow()
                checkStarting()
                val speaker = config.speakerId.trim().ifEmpty {
                    c.fetchMetaData()?.serialNumber.orEmpty()
                }
                checkStarting()
                if (config.speakerId.isBlank() && speaker.isNotEmpty()) AppPrefs(context).speakerId = speaker
                // The stock sender is its own timing master; the speaker serial is identity metadata only.
                masterId = sourceDeviceId
                sessionId = Protocol.newSessionId()
                senderId = sourceDeviceId
                if (config.volume in 0..100) c.setVolume(config.volume)
                checkStarting()

                val ptpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(StarPtpPacket.PORT))
                }
                val peer = try {
                    StarPtpPeer(
                        ptpSocket,
                        InetSocketAddress(config.host, StarPtpPacket.PORT),
                        intervalUs = 20_000,
                        onError = { DiagLog.e("StarPTP: ${it.message}") }
                    )
                } catch (t: Throwable) {
                    ptpSocket.close()
                    throw t
                }
                ptpPeer = peer
                peer.start()
                val readyEstimate = withTimeoutOrNull(2_500) {
                    var current = peer.estimate()
                    while (current == null) {
                        delay(25)
                        current = peer.estimate()
                    }
                    current
                }
                checkStarting()
                requireNotNull(readyEstimate) { "StarPTP не синхронизирован за 2.5 с; StartStreaming не отправлен" }

                // Prepare local resources before requesting remote state. A failed encoder/file must not start the speaker.
                val s = UdpAudioSender(config.host, Protocol.chooseUdpPort()) { err ->
                    DiagLog.e(err)
                    if (gate.isActive(ticket)) requestStop(ticket, err)
                }
                sender = s
                val settings = Protocol.audioSettingsJson(s.localAddress(), s.destinationPort, sessionId)
                lastSettingsJson = settings
                val enc = OpusEncoder()
                encoder = enc
                enc.start { opus -> if (running.get() && gate.isActive(ticket)) s.sendOpus(opus) }
                activeSource = config.source
                config.source.open()
                checkStarting()
                val fresh = ptpPeer?.estimate()
                    ?: error("StarPTP estimate истёк; StartStreaming не отправлен")
                val offsetMs = Protocol.StartStreamingTiming.offsetMs(fresh.localMinusRemoteUs)
                val monoTimestampMs = Protocol.StartStreamingTiming.monoTimestampMs(
                    System.nanoTime() / 1_000_000L
                )
                val syncedMasters = listOf(Protocol.syncedMaster(masterId, offsetMs))
                DiagLog.i(
                    "StarPTP готов: offset=$offsetMs ms, path=${fresh.pathDelayUs / 1_000L} ms, " +
                        "samples=${fresh.samples}; это ещё не подтверждение звука"
                )
                val star = Protocol.starCommandAudioStart(
                    masterId,
                    sessionId,
                    senderId,
                    settings,
                    monoTimestampMs,
                    syncedMasters
                )
                if (!gate.markStartSent(ticket)) throw kotlinx.coroutines.CancellationException("Остановка перед StartStreaming")
                val reply = c.sendStarCommand(star, 4_000)
                checkStarting()
                require(reply?.accepted == true) { "StartStreaming: нет успешного ответа; UDP не запущен" }
                if (!gate.activate(ticket)) throw kotlinx.coroutines.CancellationException("Сессия отменена")
                running.set(true)
                startedAt = android.os.SystemClock.elapsedRealtime()
                _state.value = State.Streaming(config.host, s.destinationPort, config.source.description, true)
                DiagLog.i("Получен транспортный ACK; звук и PTP на колонке не подтверждены")
                sourceThread = Thread({ sourceLoop(config.source, ticket, enc) }, "sberboom-audio-source").apply { start() }
                keepAliveJob = scope.launch {
                    var missed = 0
                    while (isActive && gate.isActive(ticket)) {
                        delay(5_000)
                        if (!gate.isActive(ticket)) break
                        val replyAlive = try { c.sendStarCommand(Protocol.starCommandAudioAlive(masterId, sessionId, senderId), 1_500) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { null }
                        missed = if (replyAlive?.accepted == true) 0 else missed + 1
                        if (missed >= 3) { requestStop(ticket, "Нет подтверждения Alive"); break }
                    }
                }
                monitorJob = scope.launch {
                    var missedPtp = 0
                    while (isActive && gate.isActive(ticket)) {
                        delay(1_000)
                        if (!gate.isActive(ticket)) break
                        if (!c.isConnected || JwtInfo.validationError(config.jwt) != null) {
                            requestStop(ticket, "Соединение потеряно или JWT истёк")
                            break
                        }
                        missedPtp = if (ptpPeer?.estimate() != null) 0 else missedPtp + 1
                        if (missedPtp >= 3) {
                            requestStop(ticket, "StarPTP не синхронизирован")
                            break
                        }
                    }
                }
                statsJob = scope.launch {
                    while (isActive && gate.isActive(ticket)) {
                        delay(500)
                        _stats.value = Stats(
                            s.packetsSent.get(),
                            s.bytesSent.get(),
                            android.os.SystemClock.elapsedRealtime() - startedAt,
                            s.lastPacketAgeMs()
                        )
                    }
                }
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) { terminate(ticket, null) }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) { terminate(ticket, e.message ?: "Ошибка аудиосессии") }
                Result.failure(e)
            } finally {
                starting.compareAndSet(owner, null)
            }
        }
    }

    private fun sourceLoop(source: PcmSource, ticket: SessionGate.Ticket, enc: OpusEncoder) {
        val chunk = ByteArray(Protocol.OPUS_FRAME_SAMPLES * 4)
        DiagLog.i("Источник открыт: ${source.description}")
        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        var frames = 0L
        try {
            while (running.get() && gate.isActive(ticket)) {
                val n = source.read(chunk)
                if (n <= 0) {
                    DiagLog.i("Источник завершён")
                    break
                }
                enc.encode(chunk, n)
                frames += n / 4
                // Держим реальное время: файл/тон декодируются быстрее, чем звучат.
                val expectedMs = frames * 1000L / 48_000L
                val actualMs = android.os.SystemClock.elapsedRealtime() - startedAtMs
                val sleepMs = expectedMs - actualMs
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs.coerceAtMost(50))
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            if (gate.isActive(ticket)) requestStop(ticket, "Ошибка источника: ${t.message}")
        } finally {
            // Энкодером владеет этот поток: terminate() больше не освобождает его параллельно.
            runCatching { enc.stop() }
            if (gate.isActive(ticket)) requestStop(ticket, null)
        }
    }

    fun stop() {
        val ticket = gate.requestStop() ?: return
        requestStop(ticket, null)
    }

    private fun requestStop(ticket: SessionGate.Ticket, error: String?) {
        if (!gate.requestStop(ticket)) return
        running.set(false)
        starting.get()?.takeIf { it.first == ticket }?.second?.cancel()
        scope.launch { lifecycle.withLock { terminate(ticket, error) } }
    }

    /** Called only while lifecycle is locked, including cancellation/partial startup. */
    private suspend fun terminate(ticket: SessionGate.Ticket, error: String?) {
        if (!gate.isCurrent(ticket)) return
        running.set(false)
        gate.requestStop(ticket)
        keepAliveJob?.cancel(); monitorJob?.cancel(); statsJob?.cancel()
        keepAliveJob = null; monitorJob = null; statsJob = null
        // Stop network output before closing the remote session.
        runCatching { sender?.close() }
        runCatching { ptpPeer?.close() }
        ptpPeer = null
        val thread = sourceThread
        thread?.interrupt()
        runCatching { activeSource?.close() }
        activeSource = null
        if (thread != null) {
            runCatching { thread.join(3_000) }
            if (thread.isAlive) DiagLog.w("Поток источника не завершился за 3 с; энкодер освободит сам поток")
        } else {
            // До запуска sourceThread энкодером владеет startup-путь.
            runCatching { encoder?.stop() }
        }
        sourceThread = null
        encoder = null; sender = null
        if (gate.shouldSendStop(ticket) && masterId.isNotEmpty()) {
            val ack = try {
                withTimeoutOrNull(1_500) { client?.sendStarCommand(Protocol.starCommandAudioStop(masterId, sessionId, senderId), 1_000) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { null }
            if (ack?.accepted != true) DiagLog.w("StopStreaming не подтверждён колонкой")
        }
        runCatching { client?.close() }
        client = null; masterId = ""; sessionId = ""; senderId = ""
        gate.finish(ticket)
        _stats.value = Stats()
        _state.value = if (error == null) State.Idle else State.Error(error)
    }

    fun shutdown() {
        val ticket = gate.close()
        running.set(false)
        starting.get()?.second?.cancel()
        scope.launch {
            try { lifecycle.withLock { if (ticket != null) terminate(ticket, null) } }
            finally { scope.cancel() }
        }
    }
}
