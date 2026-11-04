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
    private var armJob: Job? = null
    private var idleHaltJob: Job? = null
    private var parkJob: Job? = null
    private var teardownJob: Job? = null
    private var pairingUntilMs = 0L
    @Volatile private var scanning = false
    @Volatile private var armed = false
    private val knownLiveIndexes = mutableSetOf<Int>()

    private val _engine = MutableStateFlow(InteractiveEngineState())
    val engine: StateFlow<InteractiveEngineState> = _engine.asStateFlow()

    init {
        bp.addOnChange {
            val live = bp.devices()
            val indexes = live.map { it.index }.toSet()
            val newcomers = indexes - knownLiveIndexes
            knownLiveIndexes.clear()
            knownLiveIndexes.addAll(indexes)
            // Idle DeviceAdded: freeze seeker (Oscillate:0) — never Linear (would stroke).
            if (newcomers.isNotEmpty() && armed && !sync.isPlaying()) {
                scheduleIdleHalt("device-added", newcomers.toList(), requireArmed = true)
            }
            publish()
        }
        publish()
    }

    fun isReady(): Boolean = bp.connected()

    fun startMaintainer() {
        if (maintainJob?.isActive == true) return
        maintainJob = scope.launch {
            while (isActive) {
                if (!bp.connected()) {
                    runCatching { bp.connect("coog-mobile") }
                } else if (!sync.isDriving()) {
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

    /**
     * Mirror server [onSessionReady]: connect local Intiface and open a discovery
     * window so previously paired toys reconnect without a manual Scan tap.
     */
    fun armForMaize() {
        teardownJob?.cancel()
        teardownJob = null
        armed = true
        if (EmbeddedIntiface.available()) {
            EmbeddedIntiface.start()
        }
        startMaintainer()
        armJob?.cancel()
        armJob = scope.launch {
            for (i in 0 until 25) {
                if (bp.connected()) break
                runCatching { bp.connect("coog-mobile") }
                delay(200)
            }
            if (!bp.connected()) {
                Log.w("CoogIntiface", "armForMaize: WS not ready — ${bp.lastError}")
                publish()
                return@launch
            }
            scanning = true
            runCatching { bp.startScanning() }
                .onFailure { Log.w("CoogIntiface", "armForMaize scan: ${it.message}") }
            publish()
            Log.i("CoogIntiface", "armForMaize: discovery scan started")
            delay(DISCOVERY_BUDGET_MS)
            if (pairingUntilMs == 0L && isActive) {
                scanning = false
                runCatching { bp.stopScanning() }
                publish()
                Log.i("CoogIntiface", "armForMaize: discovery window closed")
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
        parkIdle(0L, clearScript = true, reason = "stop-all")
    }

    /**
     * Media closed / left player / pause / other maize screens while not driving.
     * Retarget to last Linear, wait timing gap, then Oscillate:0 freeze.
     */
    fun onPlaybackEnded(positionMs: Long = 0L) {
        parkIdle(positionMs, clearScript = true, reason = "playback-ended")
    }

    /**
     * Idle stop without BLE disconnect.
     *
     * Do **not** send Linear on pause — that retargets Solace toward lastLin/script
     * and continues the stroke. Only Oscillate:0: patched native freezes the FSetSite
     * seeker and sends Mply:0:0. Firmware may finish the last already-sent FSetSite
     * step (~100ms), then stay put.
     */
    fun parkIdle(
        positionMs: Long = 0L,
        clearScript: Boolean = false,
        reason: String = "park",
    ) {
        sync.haltNow(positionMs.toDouble())
        if (clearScript) {
            sync.clear()
            sync.clearLastLin()
        }
        idleHaltJob?.cancel()
        parkJob?.cancel()
        parkJob = scope.launch {
            Log.i("CoogIntiface", "parkIdle[$reason] Oscillate:0 only (no Linear)")
            for (d in bp.devices()) {
                when (d.kind) {
                    "linear" -> Unit
                    "constrict" -> runCatching { bp.scalar(d.index, 0.0, "Constrict") }
                    else -> runCatching { bp.scalar(d.index, 0.0, "Vibrate") }
                }
            }
            // Immediate + two spaced rounds for DeviceMessageTimingGap drops.
            repeat(3) {
                if (sync.isPlaying()) return@launch
                runCatching { bp.haltAll() }
                delay(HALT_GAP_MS)
            }
            Log.i("CoogIntiface", "parkIdle[$reason] done")
        }
    }

    fun scheduleHaltBurst(reason: String) {
        scheduleIdleHalt(reason, onlyIndexes = null, requireArmed = false)
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
            when (d?.kind) {
                "linear" -> bp.linear(index, 0.9, 400)
                "constrict" -> bp.scalar(index, 0.9, "Constrict")
                else -> bp.scalar(index, 0.9, "Vibrate")
            }
            delay(700)
            parkIdle(0L, clearScript = false, reason = "test-end")
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
        for (d in live) bp.haltDevice(d.index)
        prefs.forget(deviceId)
        publish()
    }

    /**
     * Leave maize / yield BLE: cancel discovery, park, close WS,
     * then shut down the embedded Intiface server so peripherals disconnect.
     */
    fun pauseLocal() {
        armed = false
        armJob?.cancel()
        armJob = null
        maintainJob?.cancel()
        maintainJob = null
        idleHaltJob?.cancel()
        idleHaltJob = null
        pairingUntilMs = 0L
        scanning = false
        knownLiveIndexes.clear()
        runCatching { bp.stopScanning() }
        // Halt clock; Oscillate:0 then drop BLE (leave-maize only — not media pause).
        sync.haltNow()
        sync.clear()
        sync.clearLastLin()
        publish()
        parkJob?.cancel()
        teardownJob?.cancel()
        teardownJob = scope.launch {
            repeat(4) {
                if (armed) return@launch
                runCatching { bp.haltAll() }
                delay(HALT_GAP_MS)
            }
            if (armed) return@launch
            bp.close()
            EmbeddedIntiface.stop()
            Log.i("CoogIntiface", "pauseLocal: client closed, embedded Intiface stopped")
            publish()
        }
    }

    private fun scheduleIdleHalt(
        reason: String,
        onlyIndexes: List<Int>? = null,
        requireArmed: Boolean = true,
    ) {
        idleHaltJob?.cancel()
        idleHaltJob = scope.launch {
            Log.i("CoogIntiface", "idleHalt[$reason] burst start")
            repeat(4) {
                if (requireArmed && !armed) return@launch
                if (sync.isPlaying()) return@launch
                if (onlyIndexes != null) {
                    for (idx in onlyIndexes) bp.haltDevice(idx)
                } else {
                    bp.haltAll()
                }
                delay(HALT_GAP_MS)
            }
            Log.i("CoogIntiface", "idleHalt[$reason] burst done")
        }
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
            running = armed && maintainJob?.isActive == true,
            connected = bp.connected(),
            scanning = scanning || pairing,
            pairing = pairing,
            pairingSecondsLeft = pairingLeft,
            lastError = when {
                !armed -> ""
                bp.connected() -> ""
                bp.lastError.isNotBlank() -> bp.lastError
                EmbeddedIntiface.available() -> "Starting in-app BLE engine…"
                else -> "Start Devices / allow Bluetooth (or install Intiface Central)"
            },
            devices = if (armed) live else emptyList(),
            trustedDevices = emptyList(),
            knownDevices = emptyList(),
        )
    }

    companion object {
        private const val DISCOVERY_BUDGET_MS = 75_000L
        private const val HALT_GAP_MS = 120L

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
