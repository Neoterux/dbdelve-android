package dev.dbdelve.android.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One statement as it was sent, exactly, and what came of it. Only
 * statements the gate let through are here: a stop sends nothing.
 */
data class HistoryEntry(
    val text: String,
    /** When it was sent, in epoch milliseconds. */
    val at: Long,
    val elapsedMs: Long?,
    /** Rows returned, null when it returned none (a write, or a failure). */
    val rows: Int?,
    /** More rows came than the limit kept. */
    val capped: Boolean,
    val affected: Long?,
    /** Why it failed, or null. */
    val error: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("at", at)
        .putOpt("elapsed_ms", elapsedMs)
        .putOpt("rows", rows)
        .put("capped", capped)
        .putOpt("affected", affected)
        .putOpt("error", error)

    companion object {
        fun fromJson(json: JSONObject) = HistoryEntry(
            text = json.getString("text"),
            at = json.getLong("at"),
            elapsedMs = json.optLong("elapsed_ms", -1).takeIf { it >= 0 },
            rows = json.optInt("rows", -1).takeIf { it >= 0 },
            capped = json.optBoolean("capped"),
            affected = json.optLong("affected", -1).takeIf { it >= 0 },
            error = json.optString("error").takeIf { json.has("error") },
        )
    }
}

/**
 * Each connection's history, newest first, in a file of its own beside its
 * tabs: app-private and out of backups. Kept to the last [LIMIT] statements.
 */
class HistoryStore(private val directory: File) {
    private fun file(profileId: String) = File(directory, "$profileId.json")

    fun load(profileId: String): List<HistoryEntry> = try {
        val array = JSONArray(file(profileId).readText())
        List(array.length()) { HistoryEntry.fromJson(array.getJSONObject(it)) }
    } catch (missingOrUnreadable: Exception) {
        // A file that will not read loses only history, never connections.
        emptyList()
    }

    fun save(profileId: String, entries: List<HistoryEntry>) {
        directory.mkdirs()
        val array = JSONArray()
        entries.take(LIMIT).forEach { array.put(it.toJson()) }
        val next = File(directory, "$profileId.json.next")
        next.writeText(array.toString())
        next.renameTo(file(profileId))
    }

    fun forget(profileId: String) {
        file(profileId).delete()
    }

    companion object {
        const val LIMIT = 200

        /** A failure's reason is kept short: the statement is the point. */
        const val ERROR_CHARS = 300
    }
}
