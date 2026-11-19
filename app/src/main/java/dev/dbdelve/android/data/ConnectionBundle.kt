package dev.dbdelve.android.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A connection as it travels in a shared file: the profile, and its password if sent. */
data class SharedConnection(val profile: Profile, val password: String?, val clientCert: ClientCert? = null)

/**
 * A CA certificate travels with its connection, as it is public; a client
 * certificate and its key only when passwords do.
 *
 * What a `.dbdelve` file holds once opened: plain JSON, sealed and opened by
 * the core (`sealConnections`/`openConnections`). Ids are not sent; each
 * import gets new ones, so a file can never overwrite by accident.
 */
object ConnectionBundle {
    private const val VERSION = 1

    fun toJson(connections: List<SharedConnection>): String {
        val array = JSONArray()
        connections.forEach { shared ->
            val json = shared.profile.toJson()
            json.remove("id")
            shared.password?.let { json.put("password", it) }
            shared.clientCert?.let { json.put("client_cert", it.toJson()) }
            array.put(json)
        }
        return JSONObject().put("version", VERSION).put("connections", array).toString()
    }

    fun fromJson(text: String): List<SharedConnection> {
        val json = JSONObject(text)
        require(json.optInt("version") == VERSION) { "Made by a newer DBDelve: update this app to open it." }
        val array = json.getJSONArray("connections")
        return List(array.length()) {
            val item = array.getJSONObject(it)
            val password = if (item.has("password")) item.getString("password") else null
            val clientCert = item.optJSONObject("client_cert")?.let(ClientCert::fromJson)
            SharedConnection(Profile.fromJson(item.put("id", UUID.randomUUID().toString())), password, clientCert)
        }
    }
}
