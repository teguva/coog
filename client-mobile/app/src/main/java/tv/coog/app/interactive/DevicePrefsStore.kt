package tv.coog.app.interactive

import android.content.Context

/** Per-device intensity / offset for on-device Intiface (not server SQLite). */
class DevicePrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("coog_intiface_local", Context.MODE_PRIVATE)

    fun intensity(deviceId: String): Int =
        clampIntensity(prefs.getInt("int:$deviceId", 100))

    fun offsetMs(deviceId: String, deviceName: String = ""): Int {
        val key = "off:$deviceId"
        if (prefs.contains(key)) {
            val stored = prefs.getInt(key, SOLACE_DEFAULT_OFFSET_MS)
            // Legacy Solace default is too late for Handy 2.
            if (isHandyName(deviceName.ifBlank { deviceId }) && stored == SOLACE_DEFAULT_OFFSET_MS) {
                return HANDY_DEFAULT_OFFSET_MS
            }
            return stored
        }
        return linearProfileFor(deviceName.ifBlank { deviceId }).offsetMs
    }

    /** PowerBlow slow-stroke filter (ms). 0 / missing → default. */
    fun powerBlowMinStrokeMs(deviceId: String = PowerBlowHub.STABLE_ID): Int {
        val v = prefs.getInt("pbMinMs:$deviceId", DEFAULT_POWERBLOW_MIN_STROKE_MS)
        return if (v <= 0) DEFAULT_POWERBLOW_MIN_STROKE_MS else v.coerceIn(150, 2000)
    }

    fun setPowerBlowMinStrokeMs(deviceId: String, value: Int) {
        prefs.edit().putInt(
            "pbMinMs:$deviceId",
            value.coerceIn(150, 2000),
        ).apply()
    }

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
            .remove("pbMinMs:$deviceId")
            .apply()
    }
}
