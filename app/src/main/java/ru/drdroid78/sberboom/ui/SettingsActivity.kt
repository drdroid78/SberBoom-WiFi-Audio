package ru.drdroid78.sberboom.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import ru.drdroid78.sberboom.R
import ru.drdroid78.sberboom.data.AppPrefs
import ru.drdroid78.sberboom.protocol.JwtInfo

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = AppPrefs(this)

        val clientName = findViewById<TextInputEditText>(R.id.clientName)
        val host = findViewById<TextInputEditText>(R.id.host)
        val port = findViewById<TextInputEditText>(R.id.port)
        val speakerId = findViewById<TextInputEditText>(R.id.speakerId)
        val certPin = findViewById<TextInputEditText>(R.id.certPin)
        val jwt = findViewById<TextInputEditText>(R.id.jwt)
        val jwtInfo = findViewById<TextView>(R.id.jwtInfo)
        val autoReconnect = findViewById<MaterialSwitch>(R.id.autoReconnect)

        clientName.setText(prefs.clientName)
        host.setText(prefs.lastHost.orEmpty())
        port.setText(prefs.lastPort.toString())
        speakerId.setText(prefs.speakerId.orEmpty())
        certPin.setText(prefs.lastHost?.let { prefs.certificatePinFor(it, prefs.lastPort) }.orEmpty())
        jwt.setText(prefs.jwt.orEmpty())
        autoReconnect.isChecked = false
        autoReconnect.isEnabled = false
        autoReconnect.setText(R.string.reconnect_pending)
        val originalHost = prefs.lastHost.orEmpty()
        val originalPort = prefs.lastPort

        fun refreshJwtInfo() {
            val text = jwt.text?.toString()?.trim().orEmpty()
            jwtInfo.text = if (text.isEmpty()) {
                ""
            } else {
                getString(R.string.settings_jwt_check, JwtInfo.describe(text))
            }
        }
        refreshJwtInfo()
        jwt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshJwtInfo()
        })

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val pin = certPin.text?.toString()?.trim()?.lowercase()?.replace(":", "").orEmpty()
            if (pin.isNotEmpty() && !pin.matches(Regex("[0-9a-f]{64}"))) {
                certPin.error = getString(R.string.cert_pin_invalid); return@setOnClickListener
            }
            prefs.clientName = clientName.text?.toString()?.trim().orEmpty()
                .ifBlank { "SberBoom Wi-Fi Audio" }
            val newHost = host.text?.toString()?.trim().orEmpty()
            val newPort = port.text?.toString()?.toIntOrNull()?.coerceIn(1, 65535) ?: 20000
            if (!newHost.equals(originalHost, ignoreCase = true) || newPort != originalPort) {
                // Do not copy credentials or trust to a different endpoint.
                prefs.lastHost = newHost.takeIf { it.isNotBlank() }
                prefs.lastPort = newPort
                prefs.speakerId = null
                Toast.makeText(this, R.string.endpoint_changed, Toast.LENGTH_LONG).show()
                finish()
                return@setOnClickListener
            }
            prefs.lastHost = newHost.takeIf { it.isNotBlank() }
            prefs.lastPort = newPort
            prefs.speakerId = speakerId.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            try {
                val newPin = pin.takeIf { it.isNotBlank() }
                val certChanged = prefs.lastHost?.let { prefs.certificatePinFor(it, prefs.lastPort) != newPin } ?: false
                prefs.lastHost?.let { prefs.setCertificatePin(it, prefs.lastPort, newPin) }
                if (!certChanged) prefs.jwt = jwt.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                else Toast.makeText(this, R.string.endpoint_changed, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.credentials_save_error, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.autoReconnect = false
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<MaterialButton>(R.id.forgetButton).setOnClickListener {
            prefs.forgetPairing()
            jwt.setText("")
            Toast.makeText(this, R.string.settings_pairing_forgotten, Toast.LENGTH_SHORT).show()
        }
    }
}
