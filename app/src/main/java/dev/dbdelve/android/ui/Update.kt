package dev.dbdelve.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.dbdelve.android.AppViewModel
import dev.dbdelve.android.data.Updates
import dev.dbdelve.android.update.Updater
import dev.dbdelve.android.update.Updater.Download

/** What the update's main button does now, and what it says. */
private class UpdateAction(val label: String, val run: () -> Unit)

/**
 * Download, then Install, as Mihon does it: the app downloads the APK itself
 * (progress here and in a notification), checks it, and hands it to Android's
 * installer, which asks before replacing the app. A release with no APK, or a
 * build without the updater (tests), opens the download in the browser.
 */
@Composable
private fun rememberUpdateAction(model: AppViewModel, release: Updates.Release): UpdateAction {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val download by Updater.download.collectAsState()
    val browser = {
        runCatching { uri.openUri(release.download) }
            .onFailure { model.connectError = "No app on this phone opens web links." }
        Unit
    }
    val start = {
        if (!Updater.start(release)) browser()
    }
    // Android 13 on: asked once, at the first Download, for the progress and
    // "ready" notifications. Refused, the download goes on, shown only here.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    return when (val now = download) {
        is Download.Ready -> if (now.version == release.version) {
            UpdateAction("Install") {
                if (!Updater.install(context, now.file)) model.connectError = "Android's installer didn't open."
            }
        } else {
            null
        }
        is Download.Running -> if (now.version == release.version) UpdateAction("Cancel") { Updater.cancel() } else null
        else -> null
    } ?: UpdateAction(if (download is Download.Failed) "Try again" else "Download") {
        val needsPermission = release.apk != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
    }
}

/** A newer release than the one installed, on the Connections screen. */
@Composable
fun UpdateBanner(model: AppViewModel, release: Updates.Release) {
    val action = rememberUpdateAction(model, release)
    val download by Updater.download.collectAsState()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text("DBDelve ${release.version} is available", fontWeight = FontWeight.SemiBold)
            val status = when (val now = download) {
                is Download.Running -> if (now.version == release.version) {
                    now.fraction?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…"
                } else {
                    null
                }
                is Download.Ready -> if (now.version == release.version) "Downloaded and checked: ready to install." else null
                is Download.Failed -> if (now.version == release.version) now.reason else null
                Download.Idle -> null
            }
            Text(
                status ?: "You have ${model.installedVersion}.",
                fontSize = 13.sp,
                color = if (download is Download.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            (download as? Download.Running)?.takeIf { it.version == release.version }?.let { running ->
                val fraction = running.fraction
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
                }
            }
            // A taste of the notes, the first lines that say something.
            if (status == null) {
                noteBlocks(release.notes).firstOrNull { it !is NoteBlock.Heading }?.let { first ->
                    Text(
                        when (first) {
                            is NoteBlock.Bullet -> first.text
                            is NoteBlock.Paragraph -> first.text
                            is NoteBlock.Heading -> first.text
                        },
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (download !is Download.Running) TextButton(onClick = model::dismissUpdate) { Text("Later") }
                TextButton(onClick = { model.showUpdateNotes = true }) { Text("What's new") }
                TextButton(onClick = action.run) { Text(action.label) }
            }
        }
    }
}

/** The release's notes, when asked for: from the banner or a notification. */
@Composable
fun UpdateNotes(model: AppViewModel) {
    val release = model.update ?: return
    if (!model.showUpdateNotes) return
    val action = rememberUpdateAction(model, release)
    ReleaseNotesSheet(
        release,
        actionLabel = action.label,
        onAction = { if (action.label != "Cancel") model.showUpdateNotes = false; action.run() },
    ) { model.showUpdateNotes = false }
}
