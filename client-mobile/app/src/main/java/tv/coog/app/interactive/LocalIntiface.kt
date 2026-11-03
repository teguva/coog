package tv.coog.app.interactive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.data.InteractiveEngineState

/**
 * On-device Intiface Central bridge for mobile. TV keeps using the server engine.
 */
class LocalIntiface private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = DevicePrefsStore(appContext)
    private val bp = ButtplugClient()
    val sync = LocalSyncRuntime(bp, prefs)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maintainJob: Job? = null
    private var pairingUntilMs = 0L
    @Volatile private var scanning = false

    private val _engine = MutableStateFlow(InteractiveEngineState())
    val engine: StateFlow<InteractiveEngineState> = _engine.asStateFlow()

    init {
        bp.addOnChange { publish() }
        startMaintainer()
    }

    fun isReady(): Boolean = bp.connected()

    fun startMaintainer() {
        if (maintainJob?.isActive == true) return
        maintainJob = scope.launch {
            while (isActive) {
                if (!bp.connected()) {
                    runCatching { bp.connect("coog-mobile") }
                }
                if (pairingUntilMs > 0 && System.currentTimeMillis() > pairingUntilMs) {
                    pairingUntilMs = 0L
                    scanning = false
                    runCatching { bp.stopScanning() }
                }
                publish()
                delay(2_000)
            }
        }
    }

    fun scanStart() {
        scanning = true
        if (!bp.connected()) bp.connect("coog-mobile")
        bp.startScanning()
        publish()
    }

    fun scanPair() {
        pairingUntilMs = System.currentTimeMillis() + 90_000
        scanning = true
        if (!bp.connected()) bp.connect("coog-mobile")
        bp.startScanning()
        publish()
    }

    fun scanStop() {
        pairingUntilMs = 0L
        scanning = false
        bp.stopScanning()
        publish()
    }

    fun reconnect() {
        bp.close()
        bp.connect("coog-mobile")
        publish()
    }

    fun restart() {
        bp.close()
        delayReconnect()
    }

    fun stopAll() {
        bp.stopAll()
        sync.clear()
    }

    fun test(index: Int) {
        scope.launch {
            bp.scalar(index, 0.6)
            delay(400)
            bp.stopDevice(index)
        }
    }

    fun patchIntensity(deviceId: String, intensity: Int) {
        prefs.setIntensity(deviceId, intensity)
        publish()
    }

    fun patchOffset(deviceId: String, offsetMs: Int) {
        prefs.setOffsetMs(deviceId, offsetMs)
        publish()
    }

    fun forget(deviceId: String) {
        prefs.forget(deviceId)
        publish()
    }

    fun refreshBatteries() {
        for (d in bp.devices()) {
            if (d.batterySensorIndex >= 0) {
                bp.sensorRead(d.index, d.batterySensorIndex)
            }
        }
    }

    private fun delayReconnect() {
        scope.launch {
            delay(300)
            bp.connect("coog-mobile")
            publish()
        }
    }

    private fun publish() {
        val pairing = pairingUntilMs > System.currentTimeMillis()
        val pairingLeft = if (pairing) {
            ((pairingUntilMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val live = bp.devices().map { d ->
            InteractiveDevice(
                index = d.index,
                deviceId = d.deviceId,
                name = d.name,
                kind = d.kind,
                connected = true,
                paired = true,
                intensity = prefs.intensity(d.deviceId),
                offsetMs = prefs.offsetMs(d.deviceId),
                offsetLinearMs = prefs.offsetMs(d.deviceId),
                status = "connected",
                batterySupported = d.batterySensorIndex >= 0,
                batteryPercent = d.batteryPercent,
            )
        }
        _engine.value = InteractiveEngineState(
            running = true,
            connected = bp.connected(),
            scanning = scanning || pairing,
            pairing = pairing,
            pairingSecondsLeft = pairingLeft,
            lastError = when {
                bp.connected() -> ""
                bp.lastError.isNotBlank() -> bp.lastError
                else -> "Start Intiface Central (BLE) on this phone"
            },
            devices = live,
            trustedDevices = emptyList(),
            knownDevices = emptyList(),
        )
    }

    companion object {
        @Volatile private var instance: LocalIntiface? = null

        fun get(context: Context): LocalIntiface {
            return instance ?: synchronized(this) {
                instance ?: LocalIntiface(context.applicationContext).also {
                    instance = it
                    Log.i("CoogIntiface", "local Intiface controller ready")
                }
            }
        }
    }
}
