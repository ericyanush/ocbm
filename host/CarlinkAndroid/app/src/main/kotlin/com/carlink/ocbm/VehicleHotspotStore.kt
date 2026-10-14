package com.carlink.ocbm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials the adapter must hand to the iPhone for its Wi-Fi leg of wireless CarPlay. */
data class VehicleHotspotCredentials(
    val ssid: String,
    val password: String,
    val channel: Int,
) {
    fun validationError(): String? {
        if (ssid.isBlank() || ssid != ssid.trim()) return "Enter the hotspot SSID without leading or trailing spaces."
        if (ssid.toByteArray(Charsets.UTF_8).size > 32) return "The hotspot SSID must be 32 bytes or fewer."
        val passBytes = password.toByteArray(Charsets.UTF_8).size
        if (passBytes !in 8..63) return "The hotspot password must be 8–63 bytes."
        if (channel !in 1..196) return "Enter a valid Wi-Fi channel number."
        // The adapter's small shell parser reads these as raw YAML scalars. Reject line breaks and
        // YAML delimiters that could corrupt the config or make the adapter read a different value.
        val unsafe =
            listOf(ssid, password).any { value ->
                value.any { it == '\n' || it == '\r' || it.code < 0x20 || it.code in 0x7f..0x9f } ||
                    value.firstOrNull() in setOf('[', ']', '{', '}', ',', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`') ||
                    value.startsWith("- ") ||
                    value.startsWith("? ") ||
                    value.startsWith(": ") ||
                    ": " in value ||
                    Regex("\\s#").containsMatchIn(value)
            }
        if (unsafe) return "SSID and password cannot contain line breaks or YAML-sensitive patterns."
        return null
    }
}

/** Small private app store; values are read synchronously on the connection path. */
class VehicleHotspotStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Fields(
        val ssid: String = "",
        val password: String = "",
        val channel: String = "",
    )

    fun fields(): Fields =
        Fields(
            ssid = prefs.getString(KEY_SSID, "").orEmpty(),
            password = runCatching { decryptPassword() }.getOrNull().orEmpty(),
            channel = prefs.getString(KEY_CHANNEL, "").orEmpty(),
        )

    fun credentials(): VehicleHotspotCredentials? {
        val fields = fields()
        val channel = fields.channel.toIntOrNull() ?: return null
        val credentials = VehicleHotspotCredentials(fields.ssid, fields.password, channel)
        return credentials.takeIf { it.validationError() == null }
    }

    /** Validates and commits all fields together so a key-cycle never sees a partially saved set. */
    fun save(fields: Fields): String? {
        val channel =
            fields.channel.toIntOrNull()
                ?: return "Enter the hotspot Wi-Fi channel as a number."
        val credentials = VehicleHotspotCredentials(fields.ssid, fields.password, channel)
        credentials.validationError()?.let { return it }
        val protectedPassword =
            runCatching { encryptPassword(credentials.password) }
                .getOrElse { return "Could not securely save the hotspot password on this device." }
        val saved =
            prefs
                .edit()
                .putString(KEY_SSID, credentials.ssid)
                .putString(KEY_PASSWORD_CIPHERTEXT, protectedPassword.first)
                .putString(KEY_PASSWORD_IV, protectedPassword.second)
                .putString(KEY_CHANNEL, credentials.channel.toString())
                .commit()
        return if (saved) null else "Could not save hotspot settings. Please try again."
    }

    private fun encryptPassword(password: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decryptPassword(): String? {
        val ciphertext = prefs.getString(KEY_PASSWORD_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_PASSWORD_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec =
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "vehicle_hotspot"
        const val KEY_SSID = "ssid"
        const val KEY_PASSWORD_CIPHERTEXT = "password_ciphertext"
        const val KEY_PASSWORD_IV = "password_iv"
        const val KEY_CHANNEL = "channel"
        const val KEY_ALIAS = "com.carlink.vehicle-hotspot-password"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
