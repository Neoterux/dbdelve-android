package dev.dbdelve.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.dbdelve.android.data.UpdateStore
import dev.dbdelve.android.data.Updates
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private fun installedVersion(context: Context) =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

/**
 * The daily check, with the app closed: asks GitHub for the latest release
 * and, the first time it sees a newer one the user has not put off, says so
 * in a notification. What it finds is remembered, so the app shows the same
 * notice when it next opens.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = UpdateStore(applicationContext)
        if (!store.automatic) return Result.success()
        val installed = installedVersion(applicationContext)
        val latest = try {
            withContext(Dispatchers.IO) { Updates.fetchLatest() }
        } catch (error: IOException) {
            // Offline, or GitHub's limit: the next day's run asks again.
            return Result.success()
        }
        store.lastCheck = System.currentTimeMillis()
        val newer = latest?.takeIf { Updates.isNewer(it.version, installed) }
        store.found = newer
        if (newer != null && newer.version != store.dismissed && newer.version != store.notified) {
            store.notified = newer.version
            Updater.notifyAvailable(applicationContext, newer)
        }
        return Result.success()
    }
}

/**
 * One release's APK, downloaded into the app's cache with its progress in a
 * notification, checked (see [Updater.refusal]), then offered to the
 * installer. A foreground job, so it finishes with the app in the background.
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = foreground(inputData.getString(Updater.KEY_VERSION).orEmpty(), null)

    private fun foreground(version: String, fraction: Float?) = ForegroundInfo(
        Updater.NOTIFY_DOWNLOAD,
        Updater.progressNotification(applicationContext, version, fraction),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val version = inputData.getString(Updater.KEY_VERSION) ?: return Result.failure()
        val url = inputData.getString(Updater.KEY_URL)?.takeIf { it.startsWith("https://") } ?: return Result.failure()
        val expected = inputData.getString(Updater.KEY_SHA256)
        val context = applicationContext
        // From the background (a notification's Download) Android may refuse
        // a foreground service; the download goes on as plain work.
        runCatching { setForeground(foreground(version, null)) }
        val target = Updater.apkFile(context, version)
        val part = File(target.path + ".part")
        return try {
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                val hash = fetch(url, part, version)
                if (expected != null && hash != expected) {
                    throw IOException("The download does not match the checksum GitHub published for it. Try again.")
                }
                Updater.refusal(context, part, installedVersion(context))?.let { throw IOException(it) }
                if (!part.renameTo(target)) throw IOException("Couldn't save the download.")
            }
            Updater.report(Updater.Download.Ready(version, target))
            Updater.notifyReady(context, version, target)
            Result.success()
        } catch (cancelled: CancellationException) {
            part.delete()
            Updater.report(Updater.Download.Idle)
            Updater.dismiss(context, Updater.NOTIFY_DOWNLOAD)
            throw cancelled
        } catch (error: Exception) {
            part.delete()
            val reason = error.message ?: error.toString()
            Updater.report(Updater.Download.Failed(version, reason))
            Updater.notifyFailed(context, version, reason)
            Result.failure()
        }
    }

    /** Streams [url] into [into], reporting progress; the SHA-256 of what came. */
    private suspend fun fetch(url: String, into: File, version: String): String {
        // GitHub answers an asset's URL with a redirect to its storage, which
        // HttpURLConnection follows within https.
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "DBDelve-Android")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered $code")
            val size = connection.contentLengthLong.takeIf { it > 0 }
            var lastShown = -1
            var lastAt = 0L
            val scope = kotlin.coroutines.coroutineContext
            return connection.inputStream.use { input ->
                into.outputStream().use { out ->
                    Updater.copyHashing(input, out) { done ->
                        scope.ensureActive()
                        val fraction = size?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
                        val percent = fraction?.let { (it * 100).toInt() } ?: -1
                        val now = System.currentTimeMillis()
                        // At most every percent and half second: a notification
                        // per buffer is throttled away by Android anyway.
                        if (percent != lastShown && now - lastAt >= 500) {
                            lastShown = percent
                            lastAt = now
                            Updater.report(Updater.Download.Running(version, fraction))
                            Updater.post(applicationContext, Updater.NOTIFY_DOWNLOAD, Updater.progressNotification(applicationContext, version, fraction))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** The notifications' Download and Cancel. */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DOWNLOAD -> {
                val release = UpdateStore(context).found ?: return
                Updater.cancelNotice(context)
                Updater.start(release)
            }
            CANCEL -> Updater.cancel()
        }
    }

    companion object {
        const val DOWNLOAD = "dev.dbdelve.android.update.DOWNLOAD"
        const val CANCEL = "dev.dbdelve.android.update.CANCEL"
    }
}
