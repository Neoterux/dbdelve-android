package dev.dbdelve.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.dbdelve.android.ui.ConnectionForm
import dev.dbdelve.android.ui.ConnectionsScreen
import dev.dbdelve.android.ui.DBDelveTheme
import dev.dbdelve.android.ui.UpdateNotes
import dev.dbdelve.android.ui.WorkspaceScreen
import dev.dbdelve.android.update.Updater

// A FragmentActivity because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    private val model: AppViewModel get() = (application as DBDelveApp).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.screenShown()
        // Throttled and off when the user turned it off: see checkForUpdates.
        model.checkForUpdates(manual = false)
        showUpdateFrom(intent)
        setContent {
            DBDelveTheme {
                Surface {
                    when (val screen = model.screen) {
                        Screen.Connections -> ConnectionsScreen(model)
                        is Screen.Edit -> {
                            BackHandler { model.screen = Screen.Connections }
                            ConnectionForm(model, screen.profile, screen.duplicate)
                        }
                        Screen.Workspace -> WorkspaceScreen(model, ::authenticate)
                    }
                    // The Connections screen shows the notes itself, under its notice.
                    if (model.screen != Screen.Connections) UpdateNotes(model)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showUpdateFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        model.refreshUpdate()
    }

    /** Opened from "version X is available": straight to its notes. */
    private fun showUpdateFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(Updater.EXTRA_SHOW_UPDATE, false) != true) return
        intent.removeExtra(Updater.EXTRA_SHOW_UPDATE)
        model.refreshUpdate()
        model.showUpdateNotes = model.update != null
    }

    override fun onDestroy() {
        // Gone for good, not rotated: see AppViewModel.screenGone.
        if (isFinishing && !isChangingConfigurations) model.screenGone()
        super.onDestroy()
    }

    /**
     * The device's own lock before a connection goes above its mode. Weak
     * biometrics or the PIN are enough: this guards against a stray tap, not
     * a stolen phone, which the lock screen already does.
     */
    private fun authenticate(title: String, onSuccess: () -> Unit) {
        val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock set: the dialog's own tap is all there is to ask.
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }
}
