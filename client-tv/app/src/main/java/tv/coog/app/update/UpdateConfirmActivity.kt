package tv.coog.app.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Foreground trampoline for PackageInstaller callbacks.
 * Launching the system confirm UI from a BroadcastReceiver is often blocked on
 * modern Android / Google TV; an Activity PendingIntent stays in the foreground.
 */
class UpdateConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    AppUpdater.installEvents.tryEmit(InstallEvent.Failed("Install needs confirmation, but Android sent no UI."))
                    finish()
                    return
                }
                try {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(confirm)
                } catch (e: Exception) {
                    Log.w(TAG, "could not open install confirm UI", e)
                    AppUpdater.installEvents.tryEmit(
                        InstallEvent.Failed("Could not open the install confirm screen: ${e.message}"),
                    )
                }
                finish()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdater.installEvents.tryEmit(InstallEvent.Success)
                finish()
            }
            else -> {
                Log.w(TAG, "package install status=$status message=$message")
                AppUpdater.installEvents.tryEmit(InstallEvent.Failed(friendlyFailure(status, message)))
                finish()
            }
        }
    }

    private fun confirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    companion object {
        private const val TAG = "CoogUpdate"

        fun friendlyFailure(status: Int, message: String): String = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install was cancelled."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "This APK is signed with a different key. Uninstall the Studio/debug Coog, then install the GitHub APK once."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This APK is not compatible with this device."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded APK is invalid."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
            else -> message.ifBlank { "Install failed ($status)" }
        }
    }
}
