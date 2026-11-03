package tv.coog.app.interactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tv.coog.app.BuildConfig

/**
 * Debug-only adb hooks:
 *   adb shell am broadcast -a tv.coog.app.mobile.INTIFACE_TEST --ei index 0
 *   adb shell am broadcast -a tv.coog.app.mobile.INTIFACE_STATUS
 */
class IntifaceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val action = intent?.action ?: return
        val intiface = LocalIntiface.get(context)
        when (action) {
            ACTION_TEST -> {
                val index = intent.getIntExtra("index", 0)
                Log.i(TAG, "debug TEST index=$index")
                intiface.test(index)
            }
            ACTION_STATUS -> {
                val eng = intiface.engine.value
                Log.i(
                    TAG,
                    "debug STATUS connected=${eng.connected} devices=${eng.devices.map { "${it.index}:${it.name}" }} err=${eng.lastError}",
                )
            }
            ACTION_RECONNECT -> {
                Log.i(TAG, "debug RECONNECT")
                intiface.reconnect()
            }
            ACTION_SCAN -> {
                Log.i(TAG, "debug SCAN")
                intiface.scanStart()
            }
        }
    }

    companion object {
        private const val TAG = "CoogIntiface"
        const val ACTION_TEST = "tv.coog.app.mobile.INTIFACE_TEST"
        const val ACTION_STATUS = "tv.coog.app.mobile.INTIFACE_STATUS"
        const val ACTION_RECONNECT = "tv.coog.app.mobile.INTIFACE_RECONNECT"
        const val ACTION_SCAN = "tv.coog.app.mobile.INTIFACE_SCAN"
    }
}
