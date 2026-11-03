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
        // Prefer in-app engine when the native lib is present; else Intiface Central.
        if (EmbeddedIntiface.available()) {
            EmbeddedIntiface.start()
            // Engine needs a beat before the WS listener is up.
            scope.launch {
                delay(400)
                if (!bp.connected()) bp.connect("coog-mobile")
            }
        }
        startMaintainer()
    }

    fun isReady(): Boolean = bp.connected()

    fun startMaintainer() {
        if (maintainJob?.isActive == true) return
        maintainJob = scope.launch {
            while (isActive) {
                if (!bp.connected()) {
                    runCatching { bp.connect("coog-mobile") }
                } else if (!sync.isDriving()) {
                    // v4 may only push DeviceList on change; refresh so powered-off toys drop.
                    runCatching { bp.requestDeviceList() }
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
            val d = bp.deviceByIndex(index)
            Log.i(
                "CoogIntiface",
                "test pulse device=$index name=${d?.name} kind=${d?.kind} " +
                    "features=${d?.features?.map { "${it.featureIndex}:${it.outputType}" }} " +
                    "protoConnected=${bp.connected()}",
            )
            // Pulse every output feature the device actually exposes.
            val acts = d?.features?.map { it.outputType }?.distinct().orEmpty()
            if (acts.isEmpty()) {
                bp.scalar(index, 0.9, "Vibrate")
            } else {
                for (act in acts) {
                    when (act) {
                        "HwPositionWithDuration", "Position" -> bp.linear(index, 0.9, 400)
                        else -> bp.scalar(index, 0.9, act)
                    }
                }
            }
            delay(700)
            bp.stopDevice(index)
            for (act in acts) {
                if (act != "HwPositionWithDuration" && act != "Position") {
                    bp.scalar(index, 0.0, act)
                }
            }
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

    /** Stop + forget local link so another host can claim the toy. */
    fun drop(deviceId: String) {
        val live = bp.devices().filter { it.deviceId == deviceId }
        for (d in live) bp.stopDevice(d.index)
        prefs.forget(deviceId)
        publish()
    }

    /** Shut down local WS client (server BLE mode). */
    fun pauseLocal() {
        bp.close()
        sync.clear()
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
                EmbeddedIntiface.available() -> "Starting in-app BLE engine…"
                else -> "Start Devices / allow Bluetooth (or install Intiface Central)"
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
