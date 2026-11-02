package tv.coog.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Legacy callback path; prefer [UpdateConfirmActivity] for install status. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val forward = Intent(context, UpdateConfirmActivity::class.java).apply {
                    action = ApkInstaller.ACTION_INSTALL_STATUS
                    putExtras(intent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(forward) }
                    .onFailure { Log.w(TAG, "forward confirm failed", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdater.installEvents.tryEmit(InstallEvent.Success)
            }
            else -> {
                Log.w(TAG, "package install status=$status message=$message")
                AppUpdater.installEvents.tryEmit(
                    InstallEvent.Failed(UpdateConfirmActivity.friendlyFailure(status, message)),
                )
            }
        }
    }

    companion object {
        private const val TAG = "CoogUpdate"
    }
}
