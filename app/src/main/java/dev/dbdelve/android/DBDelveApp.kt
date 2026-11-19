package dev.dbdelve.android

import android.app.Application
import dev.dbdelve.android.data.HistoryStore
import dev.dbdelve.android.data.ProfileStore
import dev.dbdelve.android.data.SavedQueryStore
import dev.dbdelve.android.data.Secrets
import dev.dbdelve.android.data.TabStore
import dev.dbdelve.android.data.UpdateStore
import java.io.File

/**
 * Owns the app's state, and with it the open session, for the life of the
 * process: an activity is only a view of it. While a statement runs,
 * [QueryService] keeps the process in the foreground, so the session and
 * the statement outlive any screen.
 */
class DBDelveApp : Application() {
    val model: AppViewModel by lazy {
        AppViewModel(
            ProfileStore(filesDir),
            Secrets(this),
            TabStore(File(filesDir, "tabs")),
            SavedQueryStore(filesDir),
            HistoryStore(File(filesDir, "history")),
            UpdateStore(this),
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0",
            File(noBackupFilesDir, "tls"),
        ) { connection ->
            QueryService.keepAlive(this, connection)
        }
    }
}
