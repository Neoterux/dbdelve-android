package dev.dbdelve.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Passwords and client-certificate keys, sealed with an AES key the Android Keystore holds and never
 * hands out. The ciphertext sits in shared preferences; the key cannot leave
 * the device, which is why backups are off.
 */
class Secrets(context: Context) {
    private val preferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun password(profileId: String): String {
        val sealed = preferences.getString(profileId, null) ?: return ""
        return open(sealed)
    }

    /** A blank password is stored like any other: it is a valid one. */
    fun setPassword(profileId: String, password: String) {
        preferences.edit().putString(profileId, seal(password)).apply()
    }

    private fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** The connection's client certificate and key, sealed like a password. */
    fun clientCert(profileId: String): ClientCert? {
        val sealed = preferences.getString(clientKey(profileId), null) ?: return null
        return ClientCert.fromJson(JSONObject(open(sealed)))
    }

    fun setClientCert(profileId: String, cert: ClientCert?) {
        if (cert == null) {
            preferences.edit().remove(clientKey(profileId)).apply()
        } else {
            preferences.edit().putString(clientKey(profileId), seal(cert.toJson().toString())).apply()
        }
    }

    fun forget(profileId: String) {
        preferences.edit().remove(profileId).remove(clientKey(profileId)).apply()
    }

    private fun clientKey(profileId: String) = "$profileId#client-cert"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dbdelve-passwords"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
