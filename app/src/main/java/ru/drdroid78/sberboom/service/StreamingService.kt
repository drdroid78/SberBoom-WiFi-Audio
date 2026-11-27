package ru.drdroid78.sberboom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.drdroid78.sberboom.R
import ru.drdroid78.sberboom.audio.FilePcmSource
import ru.drdroid78.sberboom.audio.PcmSource
import ru.drdroid78.sberboom.audio.PlaybackCaptureSource
import ru.drdroid78.sberboom.audio.StreamEngine
import ru.drdroid78.sberboom.audio.ToneSource
import ru.drdroid78.sberboom.data.AppPrefs
import ru.drdroid78.sberboom.ui.MainActivity
import ru.drdroid78.sberboom.util.DiagLog

/**
 * Foreground-сервис потока. Держит Wi-Fi/CPU wake lock, чтобы поток
 * продолжался при выключенном экране.
 */
class StreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: StreamEngine
    private var projection: MediaProjection? = null
    private var startJob: Job? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { startJob?.cancel(); engine.stop(); stopSelf() }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        engine = StreamEngine(applicationContext)
        createChannel()
        scope.launch {
            engine.state.collect { st ->
                _engineState.value = st
                updateNotification(st)
                if (st is StreamEngine.State.Error) {
                    DiagLog.e(st.message)
                    if (foregroundStarted) stopSelf()
                }
                if (st is StreamEngine.State.Idle && foregroundStarted && startJob?.isCompleted == true) stopSelf()
            }
        }
        scope.launch {
            engine.stats.collect { _stats.value = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_STOP
        if (action == ACTION_STOP) {
            startJob?.cancel()
            engine.stop()
            scope.launch {
                kotlinx.coroutines.withTimeoutOrNull(2500) {
                    while (engine.state.value !is StreamEngine.State.Idle && engine.state.value !is StreamEngine.State.Error) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (startJob?.isActive == true || engine.state.value is StreamEngine.State.Streaming) {
            DiagLog.w("Остановите текущую сессию перед новым запуском")
            return START_NOT_STICKY
        }
        val capture = action == ACTION_START_CAPTURE
        startForegroundCompat(if (capture) "Захват аудио" else "Поток на динамик", capture)

        val prefs = AppPrefs(this)
        val host = intent?.getStringExtra(EXTRA_HOST) ?: prefs.lastHost
        val port = intent?.getIntExtra(EXTRA_PORT, prefs.lastPort) ?: prefs.lastPort
        val speakerId = intent?.getStringExtra(EXTRA_SPEAKER_ID) ?: prefs.speakerId.orEmpty()
        val volume = intent?.getIntExtra(EXTRA_VOLUME, prefs.lastVolume) ?: prefs.lastVolume
        if (host.isNullOrBlank()) {
            DiagLog.e("Не задан адрес динамика")
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()

        startJob = scope.launch {
            val source: PcmSource = when (action) {
                ACTION_START_TONE -> ToneSource()
                ACTION_START_FILE -> {
                    val uri = intent?.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                    if (uri == null) {
                        DiagLog.e("Не выбран файл")
                        stopSelf()
                        return@launch
                    }
                    FilePcmSource(applicationContext, uri)
                }
                ACTION_START_CAPTURE -> {
                    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                    @Suppress("DEPRECATION")
                    val data = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA) as? Intent
                    if (resultCode == 0 || data == null) {
                        DiagLog.e("Нет разрешения MediaProjection")
                        stopSelf()
                        return@launch
                    }
                    val mgr = getSystemService(MediaProjectionManager::class.java)
                    val mp = mgr.getMediaProjection(resultCode, data)
                    if (mp == null) {
                        DiagLog.e("MediaProjection не создан")
                        stopSelf()
                        return@launch
                    }
                    mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                    projection = mp
                    PlaybackCaptureSource(mp)
                }
                else -> ToneSource()
            }

            val config = StreamEngine.Config(
                host = host,
                wsPort = port,
                speakerId = speakerId,
                pinToken = prefs.tokenFor(host, port),
                jwt = prefs.jwtFor(host, port),
                volume = volume,
                source = source,
                clientName = prefs.clientName,
                clientId = prefs.clientId,
                certificatePin = prefs.certificatePinFor(host, port)
            )
            val result = engine.start(config)
            if (result.isFailure) {
                DiagLog.e("Поток не запущен: ${result.exceptionOrNull()?.message}")
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SberBoomAudio:stream").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SberBoomAudio:wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    private fun startForegroundCompat(text: String, capture: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            if (capture) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        val notification = buildNotification(text)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        foregroundStarted = true
    }

    private fun stopForegroundCompat() {
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        releaseLocks()
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
    }

    private fun updateNotification(state: StreamEngine.State) {
        if (!foregroundStarted) return
        val text = when (state) {
            is StreamEngine.State.Idle -> "Остановлено"
            is StreamEngine.State.Connecting -> state.detail
            is StreamEngine.State.Streaming -> "UDP отправляется: ${state.source} (звук не подтверждён)"
            is StreamEngine.State.Error -> "Ошибка: ${state.message}"
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        startJob?.cancel()
        engine.shutdown()
        stopForegroundCompat()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_TONE = "ru.drdroid78.sberboom.START_TONE"
        const val ACTION_START_FILE = "ru.drdroid78.sberboom.START_FILE"
        const val ACTION_START_CAPTURE = "ru.drdroid78.sberboom.START_CAPTURE"
        const val ACTION_STOP = "ru.drdroid78.sberboom.STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SPEAKER_ID = "speaker_id"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_URI = "uri"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        private const val CHANNEL_ID = "sberboom_stream"
        private const val NOTIFICATION_ID = 1001

        private val _engineState = MutableStateFlow<StreamEngine.State>(StreamEngine.State.Idle)
        val engineState: StateFlow<StreamEngine.State> = _engineState.asStateFlow()

        private val _stats = MutableStateFlow(StreamEngine.Stats())
        val stats: StateFlow<StreamEngine.Stats> = _stats.asStateFlow()

        fun start(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }
    }
}
