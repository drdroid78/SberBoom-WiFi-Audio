package ru.drdroid78.sberboom.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-GCM, endpoint bound through authenticated data. */
internal class SecurePrefs(private val prefs: SharedPreferences) {
    init { prefs.edit().remove("pin_token").remove("backend_jwt").apply() }
    private fun key(): SecretKey = synchronized(LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    fun get(id: String): String? = runCatching {
        val raw = prefs.getString("secure/$id", null) ?: return null
        val packed = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }.getOrNull()
    fun set(id: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove("secure/$id").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12)
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("secure/$id", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }
    companion object { private val LOCK = Any(); private const val ALIAS = "sberboom.credentials.v2" }
}
