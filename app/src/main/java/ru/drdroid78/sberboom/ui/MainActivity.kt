package ru.drdroid78.sberboom.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import ru.drdroid78.sberboom.R
import ru.drdroid78.sberboom.audio.StreamEngine
import ru.drdroid78.sberboom.data.AppPrefs
import ru.drdroid78.sberboom.discovery.SpeakerDevice
import ru.drdroid78.sberboom.discovery.StarosDiscovery
import ru.drdroid78.sberboom.net.NetworkProbe
import ru.drdroid78.sberboom.protocol.Protocol
import ru.drdroid78.sberboom.protocol.SberBoomClient
import ru.drdroid78.sberboom.service.StreamingService
import ru.drdroid78.sberboom.util.DiagLog

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var adapter: DeviceAdapter

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var streamParams: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var scanProgress: ProgressBar
    private lateinit var devicesList: RecyclerView
    private lateinit var noDevicesText: TextView
    private lateinit var manualHost: TextInputEditText
    private lateinit var speakerId: TextInputEditText
    private lateinit var jwtStatus: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var probeButton: MaterialButton
    private lateinit var pairButton: MaterialButton
    private lateinit var pairHint: TextView
    private lateinit var volumeSlider: Slider
    private lateinit var volumeValue: TextView

    private var client: SberBoomClient? = null
    private var connectedName: String = ""
    private var pairDialog: AlertDialog? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = buildStreamIntent(StreamingService.ACTION_START_CAPTURE).apply {
                putExtra(StreamingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StreamingService.EXTRA_PROJECTION_DATA, data)
            }
            StreamingService.start(this, intent)
        } else {
            toast(getString(R.string.capture_permission_required))
        }
    }

    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestProjection() else toast(getString(R.string.capture_permission_required))
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prefs.lastSource = uri.toString()
            val intent = buildStreamIntent(StreamingService.ACTION_START_FILE).apply {
                putExtra(StreamingService.EXTRA_URI, uri.toString())
            }
            StreamingService.start(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = AppPrefs(this)

        statusText = findViewById(R.id.statusText)
        deviceText = findViewById(R.id.deviceText)
        streamParams = findViewById(R.id.streamParams)
        scanButton = findViewById(R.id.scanButton)
        scanProgress = findViewById(R.id.scanProgress)
        devicesList = findViewById(R.id.devicesList)
        noDevicesText = findViewById(R.id.noDevicesText)
        manualHost = findViewById(R.id.manualHost)
        speakerId = findViewById(R.id.speakerId)
        jwtStatus = findViewById(R.id.jwtStatus)
        connectButton = findViewById(R.id.connectButton)
        probeButton = findViewById(R.id.probeButton)
        pairButton = findViewById(R.id.pairButton)
        pairHint = findViewById(R.id.pairHint)
        volumeSlider = findViewById(R.id.volumeSlider)
        volumeValue = findViewById(R.id.volumeValue)

        adapter = DeviceAdapter { device -> onDeviceSelected(device) }
        devicesList.layoutManager = LinearLayoutManager(this)
        devicesList.adapter = adapter

        manualHost.setText(prefs.lastHost.orEmpty())
        speakerId.setText(prefs.speakerId.orEmpty())
        volumeSlider.value = prefs.lastVolume.toFloat()
        volumeValue.text = getString(R.string.volume_value, prefs.lastVolume)
        updateJwtStatus()

        scanButton.setOnClickListener { scan() }
        connectButton.setOnClickListener { connectCurrent() }
        probeButton.setOnClickListener { probeNetwork() }
        pairButton.setOnClickListener { pair() }
        findViewById<MaterialButton>(R.id.testToneButton).setOnClickListener { startTone() }
        findViewById<MaterialButton>(R.id.playFileButton).setOnClickListener {
            if (checkHost()) filePicker.launch(arrayOf("audio/*"))
        }
        findViewById<MaterialButton>(R.id.captureButton).setOnClickListener { startCapture() }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP))
        }
        findViewById<MaterialButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.logButton).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) volumeValue.text = getString(R.string.volume_value, value.toInt())
        }
        volumeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                val value = slider.value.toInt()
                prefs.lastVolume = value
                lifecycleScope.launch { applyVolume(value) }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        listOf(R.id.mediaPrevious to Protocol.MEDIA_PREV, R.id.mediaPlay to Protocol.MEDIA_PLAY,
            R.id.mediaPause to Protocol.MEDIA_PAUSE, R.id.mediaNext to Protocol.MEDIA_NEXT,
            R.id.mediaMute to Protocol.MEDIA_MUTE, R.id.mediaUnmute to Protocol.MEDIA_UNMUTE,
            R.id.mediaMultiroom to Protocol.MEDIA_START_MULTIROOM).forEach { (id, command) ->
            findViewById<MaterialButton>(id).setOnClickListener {
                lifecycleScope.launch {
                    val c = client
                    if (c == null || !c.isConnected || c.pinToken.isNullOrBlank() ||
                        !c.host.equals(manualHost.text?.toString()?.trim(), ignoreCase = true) || c.port != prefs.lastPort) {
                        toast(getString(R.string.media_requires_pairing)); return@launch
                    }
                    val status = runCatching { c.media(command) }.getOrNull()
                    toast(getString(when (status) {
                        Protocol.PLAYER_OK -> R.string.media_accepted
                        Protocol.PLAYER_UNSUPPORTED -> R.string.media_unsupported
                        else -> R.string.media_unconfirmed
                    }))
                }
            }
        }
        observeService()

        if (prefs.lastHost != null && prefs.speakerId != null) {
            DiagLog.i("Восстановлены настройки: ${prefs.lastHost}, id=${prefs.speakerId}")
        }
    }

    override fun onResume() {
        super.onResume()
        manualHost.setText(prefs.lastHost.orEmpty())
        if (client?.let { !it.host.equals(prefs.lastHost, ignoreCase = true) || it.port != prefs.lastPort ||
                it.certificatePin != prefs.lastHost?.let { host -> prefs.certificatePinFor(host, prefs.lastPort) } ||
                it.pinToken != prefs.token || it.jwt != prefs.jwt } == true) {
            client?.close()
            client = null
        }
        updateJwtStatus()
        if (speakerId.text.isNullOrBlank()) speakerId.setText(prefs.speakerId.orEmpty())
    }

    override fun onDestroy() {
        pairDialog?.dismiss()
        pairDialog = null
        runCatching { client?.close() }
        client = null
        super.onDestroy()
    }

    private fun observeService() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    StreamingService.engineState.collect { renderState(it) }
                }
                launch {
                    StreamingService.stats.collect { stats ->
                        if (stats.uptimeMs > 0) {
                            streamParams.text = getString(
                                R.string.params_value, 48_000, 2, "Opus"
                            ) + "\n" + "UDP: ${stats.packets} пакетов, ${stats.bytes} байт, " +
                                "${stats.uptimeMs / 1000} с"
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: StreamEngine.State) {
        when (state) {
            is StreamEngine.State.Idle -> {
                statusText.text = getString(R.string.status_disconnected)
                streamParams.text = getString(R.string.params_value, 48_000, 2, "Opus")
            }
            is StreamEngine.State.Connecting -> {
                statusText.text = getString(R.string.status_connecting)
                deviceText.text = state.detail
            }
            is StreamEngine.State.Streaming -> {
                statusText.text = getString(R.string.status_streaming)
                deviceText.text = "${state.host} · UDP ${state.udpPort} · ${state.source}"
                streamParams.text = getString(R.string.params_value, 48_000, 2, "Opus") +
                    if (!state.jwtUsed) "\n" + getString(R.string.jwt_missing) else ""
                if (!state.jwtUsed) toast(getString(R.string.stream_not_confirmed))
            }
            is StreamEngine.State.Error -> {
                statusText.text = getString(R.string.status_disconnected)
                deviceText.text = getString(R.string.error_generic, state.message)
            }
        }
    }

    private fun scan() {
        scanProgress.visibility = ProgressBar.VISIBLE
        scanButton.isEnabled = false
        noDevicesText.visibility = TextView.GONE
        lifecycleScope.launch {
            try {
                val devices = StarosDiscovery(this@MainActivity).scan()
                adapter.submit(devices)
                noDevicesText.visibility = if (devices.isEmpty()) TextView.VISIBLE else TextView.GONE
                DiagLog.i("mDNS: найдено ${devices.size} динамик(ов)")
            } catch (t: Throwable) {
                DiagLog.e("mDNS scan", t)
                noDevicesText.visibility = TextView.VISIBLE
            } finally {
                scanProgress.visibility = ProgressBar.GONE
                scanButton.isEnabled = true
            }
        }
    }

    private fun onDeviceSelected(device: SpeakerDevice) {
        manualHost.setText(device.host)
        if (device.id.isNotBlank()) speakerId.setText(device.id)
        connectedName = device.name
        prefs.lastHost = device.host
        prefs.lastPort = device.port
        prefs.speakerId = device.id.takeIf { it.isNotBlank() }
        prefs.lastDeviceName = device.name
        lifecycleScope.launch { connectTo(device.host, device.port) }
    }

    private fun connectCurrent() {
        val host = manualHost.text?.toString()?.trim().orEmpty()
        if (host.isBlank()) {
            toast(getString(R.string.manual_host_hint))
            return
        }
        lifecycleScope.launch { connectTo(host, prefs.lastPort) }
    }

    private fun probeNetwork() {
        val host = manualHost.text?.toString()?.trim().orEmpty()
            .ifEmpty { prefs.lastHost.orEmpty() }
        if (host.isBlank()) {
            toast(getString(R.string.probe_no_host))
            return
        }
        probeButton.isEnabled = false
        probeButton.text = getString(R.string.probe_running)
        lifecycleScope.launch {
            try {
                val report = NetworkProbe(this@MainActivity).run(host)
                DiagLog.i("Проверка сети $host: ${report.summaryLine()}")
                report.toText().lineSequence().filter { it.isNotBlank() }
                    .forEach { DiagLog.i("  $it") }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.probe_title)
                    .setMessage(report.toText())
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.open_log) { _, _ ->
                        startActivity(Intent(this@MainActivity, LogActivity::class.java))
                    }
                    .show()
            } catch (t: Throwable) {
                DiagLog.e("Проверка сети", t)
                toast(getString(R.string.error_generic, t.message ?: ""))
            } finally {
                probeButton.isEnabled = true
                probeButton.text = getString(R.string.probe_network)
            }
        }
    }

    private suspend fun connectTo(host: String, port: Int): Boolean {
        statusText.text = getString(R.string.status_connecting)
        deviceText.text = "$host:$port"
        runCatching { client?.close() }
        client = null

        val c = SberBoomClient(host, port, prefs.clientName, prefs.clientId, prefs.certificatePinFor(host, port))
        c.setTokens(prefs.tokenFor(host, port), prefs.jwtFor(host, port))
        val result = c.connect()
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "connect failed"
            DiagLog.e("Подключение к $host:$port: $msg")
            statusText.text = getString(R.string.status_disconnected)
            deviceText.text = getString(R.string.error_generic, msg)
            toast(getString(R.string.error_generic, msg))
            return false
        }
        client = c
        prefs.lastHost = host
        prefs.lastPort = port
        statusText.text = getString(R.string.status_connected)
        deviceText.text = getString(R.string.connected_to, "$host:$port")
        DiagLog.i("Подключено к $host:$port")

        val meta = runCatching { c.fetchMetaData() }.getOrNull()
        findViewById<MaterialButton>(R.id.mediaMultiroom).isEnabled = meta?.multiroomSupported == true
        if (meta != null) {
            val parts = ArrayList<String>()
            meta.model?.let { if (it.isNotBlank()) parts += getString(R.string.model_label, it) }
            meta.firmware?.let { if (it.isNotBlank()) parts += getString(R.string.fw_label, it) }
            if (meta.serialNumber.isNullOrBlank().not()) {
                parts += "id ${meta.serialNumber}"
                if (speakerId.text.isNullOrBlank()) {
                    speakerId.setText(meta.serialNumber)
                    prefs.speakerId = meta.serialNumber
                }
            }
            if (meta.multiroomSupported) {
                parts += getString(R.string.multiroom_supported)
                val mode = runCatching { org.json.JSONObject(meta.multiroomInfoJson ?: "{}").optString("md") }.getOrNull()
                if (!mode.isNullOrBlank()) parts += getString(R.string.multiroom_mode, mode.take(48))
            }
            if (parts.isNotEmpty()) deviceText.text = getString(R.string.connected_to, "$host:$port") +
                "\n" + parts.joinToString(" · ")
            DiagLog.i("GetMetaData: serial=${meta.serialNumber}, model=${meta.model}, fw=${meta.firmware}, udp=${meta.udpProtocolVersion}")
        } else {
            DiagLog.w("GetMetaData: ответ не получен")
        }
        return true
    }

    private fun pair() {
        lifecycleScope.launch {
            var c = client
            if (c == null || !c.isConnected || !c.host.equals(manualHost.text?.toString()?.trim(), ignoreCase = true) || c.port != prefs.lastPort) {
                val host = manualHost.text?.toString()?.trim().orEmpty()
                if (host.isBlank()) {
                    toast(getString(R.string.manual_host_hint))
                    return@launch
                }
                if (!connectTo(host, prefs.lastPort)) return@launch
                c = client
            }
            if (c == null) return@launch
            pairButton.isEnabled = false
            pairHint.text = getString(R.string.pair_waiting)
            try {
                when (val result = c.beginPairing()) {
                    is SberBoomClient.PairStartResult.Success -> {
                        if (!savePairingToken(c, result.token)) return@launch
                        pairHint.text = getString(R.string.pair_success)
                        toast(getString(R.string.pair_success))
                    }
                    is SberBoomClient.PairStartResult.PinRequired -> showPinDialog(c, result.sessionId)
                    is SberBoomClient.PairStartResult.Error -> {
                        DiagLog.e("PIN-сопряжение: ${result.message}")
                        pairHint.text = getString(R.string.pair_failed, result.message)
                    }
                }
            } finally {
                pairButton.isEnabled = pairDialog == null
            }
        }
    }

    private fun showPinDialog(c: SberBoomClient, sessionId: String) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.pair_pin_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val pinInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(6))
            maxLines = 1
        }
        inputLayout.addView(
            pinInput,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(inputLayout)
        }
        var completed = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_pin_title)
            .setMessage(R.string.pair_pin_message)
            .setView(container)
            .setPositiveButton(R.string.pair_confirm, null)
            .setNegativeButton(R.string.pair_cancel) { _, _ ->
                completed = true
                lifecycleScope.launch {
                    val cancelled = c.cancelPairing()
                    pairHint.text = getString(
                        if (cancelled) R.string.pair_cancelled else R.string.pair_cancel_unconfirmed
                    )
                }
            }
            .setCancelable(true)
            .create()
        pairDialog = dialog
        pairButton.isEnabled = false
        pairHint.text = getString(R.string.pair_pin_required)
        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirmButton.setOnClickListener {
                val pin = pinInput.text?.toString()?.trim().orEmpty()
                if (pin.length != 6) {
                    inputLayout.error = getString(R.string.pair_pin_length)
                    return@setOnClickListener
                }
                inputLayout.error = null
                confirmButton.isEnabled = false
                lifecycleScope.launch {
                    when (val result = c.confirmPairing(sessionId, pin)) {
                        is SberBoomClient.PairResult.Success -> {
                            completed = true
                            if (!savePairingToken(c, result.token)) {
                                dialog.dismiss()
                                return@launch
                            }
                            DiagLog.i("PIN-сопряжение успешно, токен сохранён (${result.token.length} символов)")
                            pairHint.text = getString(R.string.pair_success)
                            toast(getString(R.string.pair_success))
                            dialog.dismiss()
                        }
                        is SberBoomClient.PairResult.Error -> {
                            if (!dialog.isShowing) return@launch
                            if (result.code == SberBoomClient.PairError.WRONG_PIN) {
                                inputLayout.error = result.message
                                pinInput.text?.clear()
                                pinInput.requestFocus()
                                confirmButton.isEnabled = true
                            } else {
                                completed = true
                                DiagLog.e("PIN-сопряжение: ${result.message}")
                                pairHint.text = getString(R.string.pair_failed, result.message)
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            if (pairDialog === dialog) pairDialog = null
            pairButton.isEnabled = true
            if (!completed) {
                lifecycleScope.launch {
                    val cancelled = c.cancelPairing()
                    pairHint.text = getString(
                        if (cancelled) R.string.pair_cancelled else R.string.pair_cancel_unconfirmed
                    )
                }
            }
        }
        dialog.show()
        pinInput.requestFocus()
    }

    private fun savePairingToken(c: SberBoomClient, token: String): Boolean {
        return try {
            prefs.setTokenFor(c.host, c.port, token)
            true
        } catch (e: Exception) {
            DiagLog.e("Не удалось сохранить PIN-токен", e)
            toast(getString(R.string.credentials_save_error))
            false
        }
    }

    private suspend fun applyVolume(value: Int) {
        val c = client ?: return
        if (!c.host.equals(manualHost.text?.toString()?.trim(), ignoreCase = true) || c.port != prefs.lastPort) return
        val status = runCatching { c.setVolume(value) }.getOrNull()
        DiagLog.i("Громкость $value% -> $status")
        if (status == Protocol.STATUS_OK) {
            toast(getString(R.string.volume_applied, value))
        }
    }

    private fun startTone() {
        if (!checkHost()) return
        StreamingService.start(this, buildStreamIntent(StreamingService.ACTION_START_TONE))
    }

    private fun startCapture() {
        if (!checkHost()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun checkHost(): Boolean {
        val host = manualHost.text?.toString()?.trim().orEmpty()
        if (host.isBlank()) {
            toast(getString(R.string.manual_host_hint))
            return false
        }
        val authError = ru.drdroid78.sberboom.protocol.JwtInfo.validationError(prefs.jwtFor(host, prefs.lastPort))
        if (authError != null) { toast(authError); return false }
        if (speakerId.text.isNullOrBlank()) {
            toast(getString(R.string.speaker_id_required))
            return false
        }
        return true
    }

    private fun buildStreamIntent(action: String): Intent {
        val host = manualHost.text?.toString()?.trim().orEmpty()
        val port = prefs.lastPort
        return Intent(this, StreamingService::class.java).apply {
            this.action = action
            putExtra(StreamingService.EXTRA_HOST, host)
            putExtra(StreamingService.EXTRA_PORT, port)
            putExtra(StreamingService.EXTRA_SPEAKER_ID, speakerId.text?.toString()?.trim().orEmpty())
            putExtra(StreamingService.EXTRA_VOLUME, volumeSlider.value.toInt())
        }
    }

    private fun updateJwtStatus() {
        val hasJwt = ru.drdroid78.sberboom.protocol.JwtInfo.validationError(prefs.jwt) == null
        jwtStatus.text = getString(if (hasJwt) R.string.jwt_present else R.string.jwt_missing)
        jwtStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasJwt) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
