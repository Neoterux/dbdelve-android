package dev.dbdelve.android

import android.app.Application
import dev.dbdelve.android.data.HistoryStore
import dev.dbdelve.android.data.ProfileStore
import dev.dbdelve.android.data.SavedQueryStore
import dev.dbdelve.android.data.Secrets
import dev.dbdelve.android.data.TabStore
import dev.dbdelve.android.data.UpdateStore
import dev.dbdelve.android.update.Updater
import java.io.File

/**
 * Owns the app's state, and with it the open session, for the life of the
 * process: an activity is only a view of it. While a statement runs,
 * [QueryService] keeps the process in the foreground, so the session and
 * the statement outlive any screen.
 */
class DBDelveApp : Application() {
    private val installedVersion get() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

    override fun onCreate() {
        super.onCreate()
        val updates = UpdateStore(this)
        val info = packageManager.getPackageInfo(packageName, 0)
        updates.noteLaunch(installedVersion, updatedSinceInstall = info.lastUpdateTime > info.firstInstallTime)
        // The updater's channels and daily check, and an APK already downloaded.
        Updater.init(this, updates, installedVersion)
    }

    val model: AppViewModel by lazy {
        AppViewModel(
            ProfileStore(filesDir),
            Secrets(this),
            TabStore(File(filesDir, "tabs")),
            SavedQueryStore(filesDir),
            HistoryStore(File(filesDir, "history")),
            UpdateStore(this),
            installedVersion,
            File(noBackupFilesDir, "tls"),
        ) { connection ->
            QueryService.keepAlive(this, connection)
        }
    }
}
