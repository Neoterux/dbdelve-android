package dev.dbdelve.android.data

import android.content.Context

/**
 * What the update check remembers: whether it runs by itself (on launch and
 * in the background), when it last asked GitHub, the newest release it found
 * (so the notice survives a restart without asking again), the version it
 * last notified about, and the version the user put off.
 */
class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) = prefs.edit().putBoolean("automatic", value).apply()

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0L)
        set(value) = prefs.edit().putLong("last_check", value).apply()

    /** The version the background check last notified about, so it says so once. */
    var notified: String?
        get() = prefs.getString("notified", null)
        set(value) = prefs.edit().putString("notified", value).apply()

    var dismissed: String?
        get() = prefs.getString("dismissed", null)
        set(value) = prefs.edit().putString("dismissed", value).apply()

    var found: Updates.Release?
        get() {
            val version = prefs.getString("found_version", null) ?: return null
            val page = prefs.getString("found_page", null) ?: return null
            return Updates.Release(
                version,
                page,
                prefs.getString("found_apk", null),
                prefs.getString("found_notes", null).orEmpty(),
                prefs.getString("found_sha256", null),
            )
        }
        set(value) {
            prefs.edit()
                .putString("found_version", value?.version)
                .putString("found_page", value?.page)
                .putString("found_apk", value?.apk)
                .putString("found_notes", value?.notes)
                .putString("found_sha256", value?.sha256)
                .apply()
        }
}
