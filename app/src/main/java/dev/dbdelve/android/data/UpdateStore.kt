package dev.dbdelve.android.data

import android.content.Context

/**
 * What the update check remembers: whether it runs by itself (on launch and
 * in the background), when it last asked GitHub, the newest release it found
 * (so the notice survives a restart without asking again), the version it
 * last notified about, the version the user put off, and the version that
 * last ran, so What's new opens once after an update.
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

    /** The version that last ran, to tell a launch after an update from any other. */
    var lastRun: String?
        get() = prefs.getString("last_run", null)
        set(value) = prefs.edit().putString("last_run", value).apply()

    /** Updated since the last launch, and What's new not shown for it yet. */
    var changelogPending: Boolean
        get() = prefs.getBoolean("changelog_pending", false)
        set(value) = prefs.edit().putBoolean("changelog_pending", value).apply()

    /**
     * At each launch: whether [installed] is newer than the version that ran
     * last. Before 0.6.1 nothing remembered that, so the first time
     * [updatedSinceInstall] (Android's own record of an update) answers.
     */
    fun noteLaunch(installed: String, updatedSinceInstall: Boolean) {
        val last = lastRun
        val updated = if (last == null) updatedSinceInstall else Updates.isNewer(installed, last)
        if (updated) changelogPending = true
        lastRun = installed
    }

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
