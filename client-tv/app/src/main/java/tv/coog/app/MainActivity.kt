package tv.coog.app

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.runBlocking
import tv.coog.app.data.SettingsRepository
import tv.coog.app.ui.CoogApp
import tv.coog.app.ui.theme.CoogTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
        applyLaunchExtras()
        enableEdgeToEdge()
        setContent {
            CoogTheme {
                CoogApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchExtras()
    }

    private fun applyLaunchExtras() {
        val url = intent.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        val token = intent.getStringExtra(EXTRA_TOKEN)?.trim().orEmpty()
        if (url.isBlank() && token.isBlank()) return
        val settings = SettingsRepository(applicationContext)
        runBlocking {
            if (url.isNotBlank()) settings.setServerUrl(url)
            if (token.isNotBlank()) settings.setToken(token)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Phone/tablet: never remap gesture/system nav to D-pad — that kills touch mode.
        if (BuildConfig.TOUCH_CLIENT) {
            return super.dispatchKeyEvent(event)
        }
        val mapped = mapToDpad(event) ?: event
        // #region agent log
        if (
            mapped.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            event.keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP
        ) {
            tv.coog.app.ui.coogDebug(
                "E",
                "MainActivity.kt:dispatchKeyEvent",
                "up key",
                mapOf(
                    "raw" to event.keyCode,
                    "mapped" to mapped.keyCode,
                    "action" to mapped.action,
                    "focus" to (currentFocus?.javaClass?.simpleName ?: "none"),
                    "touchMode" to (currentFocus?.isInTouchMode == true),
                ),
            )
        }
        // #endregion
        val focus = currentFocus
        if (focus is EditText) {
            return super.dispatchKeyEvent(mapped)
        }
        if (isDpad(mapped.keyCode) && focus?.isInTouchMode == true) {
            focus.isFocusableInTouchMode = false
        }
        return super.dispatchKeyEvent(mapped)
    }

    private fun isDpad(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> true
        else -> false
    }

    private fun mapToDpad(event: KeyEvent): KeyEvent? {
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP -> KeyEvent.KEYCODE_DPAD_UP
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> return null
        }
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            code,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source or InputDevice.SOURCE_DPAD,
        )
    }

    companion object {
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_TOKEN = "token"
    }
}
