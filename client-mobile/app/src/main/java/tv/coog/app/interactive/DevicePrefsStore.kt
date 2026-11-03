package tv.coog.app.interactive

import android.content.Context

/** Per-device intensity / offset for on-device Intiface (not server SQLite). */
class DevicePrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("coog_intiface_local", Context.MODE_PRIVATE)

    fun intensity(deviceId: String): Int =
        clampIntensity(prefs.getInt("int:$deviceId", 100))

    fun offsetMs(deviceId: String): Int =
        prefs.getInt("off:$deviceId", 350)

    fun setIntensity(deviceId: String, value: Int) {
        prefs.edit().putInt("int:$deviceId", clampIntensity(value)).apply()
    }

    fun setOffsetMs(deviceId: String, value: Int) {
        prefs.edit().putInt("off:$deviceId", value).apply()
    }

    fun forget(deviceId: String) {
        prefs.edit()
            .remove("int:$deviceId")
            .remove("off:$deviceId")
            .apply()
    }
}
