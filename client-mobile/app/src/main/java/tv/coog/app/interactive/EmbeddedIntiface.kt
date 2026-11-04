package tv.coog.app.interactive

import android.util.Log

/**
 * In-process Intiface engine (btleplug + intiface-engine via JNI).
 *
 * When the native lib loads, [start] opens `ws://127.0.0.1:12345` so the existing
 * [ButtplugClient] path works without Intiface Central. Falls back silently when
 * the .so is absent.
 */
object EmbeddedIntiface {
    private const val TAG = "CoogIntiface"
    private const val LIB = "coog_buttplug"
    private const val PORT = 12345

    @Volatile private var loaded = false
    @Volatile private var running = false

    init {
        loaded = runCatching {
            System.loadLibrary(LIB)
            true
        }.getOrElse {
            Log.i(TAG, "embedded engine lib not loaded (${it.message}); using Intiface Central if present")
            false
        }
    }

    fun available(): Boolean = loaded && nativeAvailable()

    fun start(): Boolean {
        if (!available()) return false
        if (running) return true
        val ok = nativeStart(PORT)
        running = ok
        Log.i(TAG, "embedded engine start port=$PORT ok=$ok")
        return ok
    }

    fun stop() {
        if (!loaded) return
        if (!running) {
            // Still invoke native stop in case Kotlin flag drifted after a crash/restart path.
            nativeStop()
            return
        }
        nativeStop()
        running = false
        Log.i(TAG, "embedded engine stopped")
    }

    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeStart(port: Int): Boolean
    @JvmStatic private external fun nativeStop()
}
