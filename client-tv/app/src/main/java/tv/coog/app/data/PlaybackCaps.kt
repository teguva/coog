package tv.coog.app.data

import android.content.Context

/** Process-wide client capability profile for playback negotiation. */
object PlaybackCaps {
    @Volatile
    var current: ClientCapabilities = ClientCapabilities()
        private set

    fun init(context: Context) {
        current = DeviceCapabilities.probe(context.applicationContext)
    }
}
