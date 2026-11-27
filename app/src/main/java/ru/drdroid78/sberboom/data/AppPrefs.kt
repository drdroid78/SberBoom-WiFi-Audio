package ru.drdroid78.sberboom.data

import android.content.Context
import android.content.SharedPreferences

/** Сохранённые настройки приложения. */
class AppPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sberboom_prefs", Context.MODE_PRIVATE)

    var clientName: String
        get() = sp.getString(KEY_CLIENT_NAME, "SberBoom Wi-Fi Audio") ?: "SberBoom Wi-Fi Audio"
        set(value) = sp.edit().putString(KEY_CLIENT_NAME, value).apply()

    val clientId: String
        get() {
            val existing = sp.getString(KEY_CLIENT_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = java.util.UUID.randomUUID().toString()
            sp.edit().putString(KEY_CLIENT_ID, generated).apply()
            return generated
        }

    var lastHost: String?
        get() = sp.getString(KEY_LAST_HOST, null)
        set(value) = sp.edit().putString(KEY_LAST_HOST, value).apply()

    var lastPort: Int
        get() = sp.getInt(KEY_LAST_PORT, 20000)
        set(value) = sp.edit().putInt(KEY_LAST_PORT, value).apply()

    var lastDeviceName: String?
        get() = sp.getString(KEY_LAST_DEVICE, null)
        set(value) = sp.edit().putString(KEY_LAST_DEVICE, value).apply()

    /** ID динамика (mDNS TXT id / GetMetaData serial_number). */
    var speakerId: String?
        get() = sp.getString(KEY_SPEAKER_ID, null)
        set(value) = sp.edit().putString(KEY_SPEAKER_ID, value).apply()

    private val secrets = SecurePrefs(sp)
    private fun endpoint(host: String, port: Int): String = "${host.trim().lowercase()}:$port"
    fun tokenFor(host: String, port: Int): String? = secrets.get("pin/" + endpoint(host, port))
    fun jwtFor(host: String, port: Int): String? = secrets.get("jwt/" + endpoint(host, port))
    fun certificatePinFor(host: String, port: Int): String? = sp.getString("cert/" + endpoint(host, port), null)
    fun setCertificatePin(host: String, port: Int, pin: String?) {
        require(pin == null || pin.matches(Regex("[0-9a-f]{64}")))
        if (certificatePinFor(host, port) != pin) {
            secrets.set("pin/" + endpoint(host, port), null)
            secrets.set("jwt/" + endpoint(host, port), null)
        }
        sp.edit().putString("cert/" + endpoint(host, port), pin).apply()
    }
    fun setTokenFor(host: String, port: Int, value: String?) = secrets.set("pin/" + endpoint(host, port), value)
    var token: String?
        get() = lastHost?.let { tokenFor(it, lastPort) }
        set(value) { lastHost?.let { setTokenFor(it, lastPort, value) } }

    /** Пользовательский backend-JWT (если он у пользователя есть). */
    var jwt: String?
        get() = lastHost?.let { jwtFor(it, lastPort) }
        set(value) { lastHost?.let { secrets.set("jwt/" + endpoint(it, lastPort), value) } }

    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var lastVolume: Int
        get() = sp.getInt(KEY_VOLUME, 40)
        set(value) = sp.edit().putInt(KEY_VOLUME, value.coerceIn(0, 100)).apply()

    var sampleRate: Int
        get() = sp.getInt(KEY_SAMPLE_RATE, 48000)
        set(value) = sp.edit().putInt(KEY_SAMPLE_RATE, value).apply()

    var channelCount: Int
        get() = sp.getInt(KEY_CHANNELS, 2)
        set(value) = sp.edit().putInt(KEY_CHANNELS, value).apply()

    var lastSource: String?
        get() = sp.getString(KEY_LAST_SOURCE, null)
        set(value) = sp.edit().putString(KEY_LAST_SOURCE, value).apply()

    fun forgetPairing() {
        lastHost?.let { host ->
            secrets.set("pin/" + endpoint(host, lastPort), null)
            secrets.set("jwt/" + endpoint(host, lastPort), null)
        }
        sp.edit().remove(KEY_TOKEN).remove(KEY_JWT).apply()
    }

    companion object {
        private const val KEY_CLIENT_NAME = "client_name"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_DEVICE = "last_device"
        private const val KEY_SPEAKER_ID = "speaker_id"
        private const val KEY_TOKEN = "pin_token"
        private const val KEY_JWT = "backend_jwt"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_VOLUME = "volume"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_LAST_SOURCE = "last_source"
    }
}
