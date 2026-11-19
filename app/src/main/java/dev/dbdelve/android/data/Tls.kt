package dev.dbdelve.android.data

import android.util.Base64
import java.security.MessageDigest
import org.json.JSONObject

/** A client certificate chain and its unencrypted private key, both PEM. */
data class ClientCert(val cert: String, val key: String) {
    fun toJson(): JSONObject = JSONObject().put("cert", cert).put("key", key)

    companion object {
        fun fromJson(json: JSONObject) = ClientCert(json.getString("cert"), json.getString("key"))
    }
}

/**
 * Just enough PEM reading to tell the user what a file holds before it is
 * saved. Whether it really is a certificate the core checks, on connecting.
 */
object Pem {
    private val BLOCK = Regex("-----BEGIN ([A-Z0-9 ]+)-----([^-]*)-----END \\1-----")

    /** The DER bytes of each block labelled [label]. */
    private fun blocks(text: String, label: (String) -> Boolean): List<ByteArray> =
        BLOCK.findAll(text).filter { label(it.groupValues[1]) }.mapNotNull {
            runCatching { Base64.decode(it.groupValues[2].filterNot(Char::isWhitespace), Base64.DEFAULT) }.getOrNull()
        }.toList()

    fun certificates(text: String): List<ByteArray> = blocks(text) { it == "CERTIFICATE" }

    /** A private key, unencrypted: PKCS #8, PKCS #1 (RSA) or SEC1 (EC). */
    fun hasKey(text: String): Boolean = blocks(text) { it == "PRIVATE KEY" || it == "RSA PRIVATE KEY" || it == "EC PRIVATE KEY" }.isNotEmpty()

    fun hasEncryptedKey(text: String): Boolean = "ENCRYPTED PRIVATE KEY" in text || "Proc-Type: 4,ENCRYPTED" in text

    /**
     * The first certificate's SHA-256 fingerprint, as `openssl x509
     * -fingerprint -sha256` prints it, shortened: enough to compare with what
     * the server's administrator sees.
     */
    fun fingerprint(text: String): String? = certificates(text).firstOrNull()?.let { der ->
        MessageDigest.getInstance("SHA-256").digest(der).take(8).joinToString(":") { "%02X".format(it) } + "…"
    }

    /** "2 certificates · SHA-256 AB:CD:…", or why it is not one. */
    fun describe(text: String): String {
        val count = certificates(text).size
        if (count == 0) return "No certificate in this file"
        val certificates = if (count == 1) "1 certificate" else "$count certificates"
        return "$certificates · SHA-256 ${fingerprint(text)}"
    }
}
