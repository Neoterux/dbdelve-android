package dev.dbdelve.android.update

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.dbdelve.android.MainActivity
import dev.dbdelve.android.data.UpdateStore
import dev.dbdelve.android.data.Updates
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Updating the app from inside it, the way Mihon does: a check in the
 * background that notifies when a release is out, the APK downloaded by the
 * app itself with its progress in a notification, and, once it is here and
 * checked, Android's own installer, which asks the user before it replaces
 * anything. The app never installs silently.
 *
 * Before the installer sees it, a download must match the SHA-256 GitHub
 * published for it (when GitHub gives one), be this app, be newer than this
 * build, and be signed with this build's key: Android would refuse the last
 * anyway, but only after the user had agreed, and with a vaguer reason.
 */
object Updater {
    /** What the download is doing, for the notice on the Connections screen. */
    sealed interface Download {
        data object Idle : Download
        /** [fraction] is null while the size is unknown. */
        data class Running(val version: String, val fraction: Float?) : Download
        data class Ready(val version: String, val file: File) : Download
        data class Failed(val version: String, val reason: String) : Download
    }

    private val state = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> get() = state

    /**
     * The application's context, which lives as long as the process: no
     * screen is held. Null until [init] (in tests), and nothing here then
     * does anything.
     */
    @SuppressLint("StaticFieldLeak")
    private var app: Context? = null

    internal const val CHANNEL_AVAILABLE = "updates"
    internal const val CHANNEL_PROGRESS = "update_download"
    internal const val NOTIFY_AVAILABLE = 2
    internal const val NOTIFY_DOWNLOAD = 3
    /**
     * Ready or failed: apart from [NOTIFY_DOWNLOAD], which WorkManager takes
     * down with the foreground service when the download ends.
     */
    internal const val NOTIFY_RESULT = 4
    private const val CHECK_WORK = "update-check"
    private const val DOWNLOAD_WORK = "update-download"
    private const val CHECK_HOURS = 24L

    internal const val KEY_VERSION = "version"
    internal const val KEY_URL = "url"
    internal const val KEY_SHA256 = "sha256"

    /** Set to open the release's notes when the app opens from a notification. */
    const val EXTRA_SHOW_UPDATE = "dev.dbdelve.android.SHOW_UPDATE"

    /** At process start: channels, the background check, and what is on disk. */
    fun init(context: Context, store: UpdateStore, installed: String) {
        val app = context.applicationContext
        this.app = app
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AVAILABLE, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A new version of DBDelve is out, or downloaded and ready to install" },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Update downloads", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Progress while a new version downloads" },
        )
        schedule(store.automatic)
        // An APK already downloaded for the release found is ready; anything
        // else in the folder (an older one, one now installed) goes.
        // The release found is installed now (this is it, or later): its
        // notice and notifications are done with.
        if (store.found?.let { Updates.isNewer(it.version, installed) } == false) {
            store.found = null
            listOf(NOTIFY_AVAILABLE, NOTIFY_DOWNLOAD, NOTIFY_RESULT).forEach { dismiss(app, it) }
        }
        val found = store.found
        val ready = found?.let { apkFile(app, it.version) }?.takeIf { it.isFile }
        folder(app).listFiles()?.filter { it != ready }?.forEach { it.delete() }
        if (ready != null && state.value == Download.Idle) state.value = Download.Ready(found.version, ready)
    }

    /** The background check: daily, on any network, while automatic checks are on. */
    fun schedule(automatic: Boolean) {
        val app = app ?: return
        runCatching {
            val work = WorkManager.getInstance(app)
            if (automatic) {
                val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(CHECK_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(CHECK_HOURS, TimeUnit.HOURS)
                    .build()
                work.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                work.cancelUniqueWork(CHECK_WORK)
            }
        }
    }

    /**
     * Downloads [release]'s APK in the background; nothing if one is already
     * downloading or downloaded. False when there is no APK to download.
     */
    fun start(release: Updates.Release): Boolean {
        val app = app ?: return false
        val url = release.apk ?: return false
        when (val now = state.value) {
            is Download.Running -> if (now.version == release.version) return true
            is Download.Ready -> if (now.version == release.version && now.file.isFile) return true
            else -> {}
        }
        state.value = Download.Running(release.version, null)
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KEY_VERSION to release.version, KEY_URL to url, KEY_SHA256 to release.sha256))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, request)
        return true
    }

    fun cancel() {
        val app = app ?: return
        WorkManager.getInstance(app).cancelUniqueWork(DOWNLOAD_WORK)
        state.value = Download.Idle
        dismiss(app, NOTIFY_DOWNLOAD)
    }

    /** Takes down "version X is available": the user is acting on it. */
    fun cancelNotice(context: Context) = dismiss(context, NOTIFY_AVAILABLE)

    internal fun dismiss(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id) }
    }

    /** Hands the downloaded APK to Android's installer, which asks the user first. */
    fun install(context: Context, file: File): Boolean = runCatching {
        context.startActivity(installIntent(context, file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    internal fun report(value: Download) {
        state.value = value
    }

    internal fun folder(context: Context) = File(context.cacheDir, "updates")

    internal fun apkFile(context: Context, version: String) =
        File(folder(context), "dbdelve-android-${version.replace(Regex("[^0-9A-Za-z.-]"), "_")}.apk")

    internal fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Opens the app on the update notice and its notes. */
    internal fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
            // Single top: an open screen gets onNewIntent rather than being
            // finished and recreated, which would close its connection.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "Version X is available", with Download, from the background check. */
    internal fun notifyAvailable(context: Context, release: Updates.Release) {
        val builder = Notification.Builder(context, CHANNEL_AVAILABLE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DBDelve ${release.version} is available")
            .setContentText("Tap to see what's new.")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
        if (release.apk != null) {
            val download = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, UpdateActionReceiver::class.java).setAction(UpdateActionReceiver.DOWNLOAD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, "Download", download).build())
        }
        post(context, NOTIFY_AVAILABLE, builder.build())
    }

    internal fun progressNotification(context: Context, version: String, fraction: Float?): Notification {
        val cancel = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, UpdateActionReceiver::class.java).setAction(UpdateActionReceiver.CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading DBDelve $version")
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    internal fun notifyReady(context: Context, version: String, file: File) {
        val install = PendingIntent.getActivity(
            context,
            2,
            installIntent(context, file),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_AVAILABLE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DBDelve $version is ready to install")
            .setContentText("Tap to install.")
            .setContentIntent(install)
            .setAutoCancel(true)
            .build()
        dismiss(context, NOTIFY_DOWNLOAD)
        post(context, NOTIFY_RESULT, notification)
    }

    internal fun notifyFailed(context: Context, version: String, reason: String) {
        val notification = Notification.Builder(context, CHANNEL_AVAILABLE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Couldn't download DBDelve $version")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        dismiss(context, NOTIFY_DOWNLOAD)
        post(context, NOTIFY_RESULT, notification)
    }

    /** Without the notification permission (Android 13 on) this posts nothing; the app shows the same. */
    internal fun post(context: Context, id: Int, notification: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    /** Copies [input] to [out], hashing as it goes; the SHA-256 in hex. */
    internal fun copyHashing(input: InputStream, out: java.io.OutputStream, progress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
            progress(total)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Why the APK at [file] must not go to the installer, or null when it may:
     * this app, newer than [installed], and signed with this build's key (or
     * a key rotated from it).
     */
    @Suppress("DEPRECATION")
    internal fun refusal(context: Context, file: File, installed: String): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "The download is not an app Android can read."
        if (archive.packageName != context.packageName) return "The download is a different app (${archive.packageName})."
        val version = archive.versionName.orEmpty()
        if (!Updates.isNewer(version, installed)) return "The download is version $version, not newer than $installed."
        val theirs = archive.signingInfo?.let { info ->
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        }
        // Some releases read no signatures from an archive: the installer still checks.
        if (theirs.isNullOrEmpty()) return null
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            ?: return null
        // Several signers: all of them must sign the download. One: its
        // current key (the last of its history) must be in the download's
        // history, which is how a rotated key still updates the app.
        val ours = if (info.hasMultipleSigners()) info.apkContentsSigners.toList() else listOfNotNull(info.signingCertificateHistory?.lastOrNull())
        if (ours.isEmpty()) return null
        return if (sameSigner(ours, theirs.toList())) {
            null
        } else {
            "The download is signed with a different key from this app, so Android would refuse to update it. " +
                "Install it by hand after uninstalling this one (which deletes the app's data)."
        }
    }

    /** Whether every one of this build's signers is among the download's. */
    internal fun sameSigner(ours: List<Signature>, theirs: List<Signature>): Boolean = ours.all { it in theirs }
}
