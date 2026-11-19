package dev.dbdelve.android.data

import android.content.Context

/**
 * What the update check remembers: whether it runs by itself, when it last
 * asked GitHub, the newest release it found (so the notice survives a
 * restart without asking again), and the version the user put off.
 */
class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) = prefs.edit().putBoolean("automatic", value).apply()

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0L)
        set(value) = prefs.edit().putLong("last_check", value).apply()

    var dismissed: String?
        get() = prefs.getString("dismissed", null)
        set(value) = prefs.edit().putString("dismissed", value).apply()

    var found: Updates.Release?
        get() {
            val version = prefs.getString("found_version", null) ?: return null
            val page = prefs.getString("found_page", null) ?: return null
            return Updates.Release(version, page, prefs.getString("found_apk", null))
        }
        set(value) {
            prefs.edit()
                .putString("found_version", value?.version)
                .putString("found_page", value?.page)
                .putString("found_apk", value?.apk)
                .apply()
        }
}
