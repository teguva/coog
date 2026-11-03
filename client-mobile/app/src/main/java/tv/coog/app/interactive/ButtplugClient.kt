package tv.coog.app.interactive

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

data class DeviceFeature(
    val featureIndex: Int,
    val outputType: String, // Vibrate | Constrict | HwPositionWithDuration | Position | …
    val valueMin: Int = 0,
    val valueMax: Int = 20,
    val durationMin: Int = 50,
    val durationMax: Int = 60_000,
)

data class LiveDevice(
    val index: Int,
    val deviceId: String,
    val name: String,
    val kind: String, // scalar|linear|constrict
    val features: List<DeviceFeature> = emptyList(),
    val batterySensorIndex: Int = -1,
    val batteryPercent: Int = -1,
)

/**
 * Buttplug WebSocket client for Intiface Central on the phone.
 * Prefers protocol v4 (OutputCmd + DeviceFeatures); falls back to v3 ScalarCmd/LinearCmd.
 */
class ButtplugClient(
    private var host: String = "127.0.0.1",
    private var port: Int = 12345,
) {
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private var socket: WebSocket? = null
    private val msgId = AtomicLong(1)
    private val devices = linkedMapOf<Int, LiveDevice>()
    private val ready = AtomicBoolean(false)
    /** Negotiated major; 4 = OutputCmd, 3 = ScalarCmd/LinearCmd. */
    private val protocolMajor = AtomicInteger(3)
    /** Server MaxPingTime ms; 0 = no ping required. */
    private val maxPingMs = AtomicInteger(0)
    @Volatile var lastError: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var pingThread: Thread? = null

    fun addOnChange(listener: () -> Unit) {
        listeners += listener
    }

    fun removeOnChange(listener: () -> Unit) {
        listeners -= listener
    }

    fun connected(): Boolean = ready.get()

    fun devices(): List<LiveDevice> = synchronized(lock) {
        devices.values.sortedWith(compareBy({ it.deviceId }, { it.index }))
    }

    fun deviceByIndex(idx: Int): LiveDevice? = synchronized(lock) { devices[idx] }

    /** 4 = try ProtocolVersionMajor first; 3 = MessageVersion fallback for older IC. */
    private val handshakeMajor = AtomicInteger(4)
    @Volatile private var clientName = "coog-mobile"

    fun connect(clientName: String = "coog-mobile") {
        close()
        this.clientName = clientName
        handshakeMajor.set(4)
        openSocket()
    }

    private fun openSocket() {
        val url = "ws://$host:$port"
        val req = Request.Builder().url(url).build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendHandshake(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                markDown("closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                markDown(t.message ?: "connection failed")
                Log.w(TAG, "ws failure: ${t.message}")
            }
        })
        synchronized(lock) {
            socket = ws
            devices.clear()
        }
    }

    private fun sendHandshake(webSocket: WebSocket) {
        val id = nextId()
        val info = JSONObject()
            .put("Id", id)
            .put("ClientName", clientName)
        if (handshakeMajor.get() >= 4) {
            info.put("ProtocolVersionMajor", 4).put("ProtocolVersionMinor", 0)
        } else {
            info.put("MessageVersion", 3)
        }
        webSocket.send(JSONArray().put(JSONObject().put("RequestServerInfo", info)).toString())
        Log.i(TAG, "handshake v${handshakeMajor.get()} → ws://$host:$port")
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: run {
            Log.w(TAG, "non-array msg: ${text.take(200)}")
            return
        }
        var changed = false
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            when {
                m.has("ServerInfo") -> {
                    val info = m.getJSONObject("ServerInfo")
                    val major = when {
                        info.has("ProtocolVersionMajor") -> info.optInt("ProtocolVersionMajor", 4)
                        info.has("MessageVersion") -> info.optInt("MessageVersion", 3)
                        info.has("MessageMajorVersion") -> info.optInt("MessageMajorVersion", 3)
                        else -> 3
                    }
                    protocolMajor.set(major.coerceIn(3, 4))
                    maxPingMs.set(info.optInt("MaxPingTime", 0).coerceAtLeast(0))
                    ready.set(true)
                    lastError = ""
                    Log.i(
                        TAG,
                        "connected protocol=v${protocolMajor.get()} " +
                            "server=${info.optString("ServerName")} maxPing=${maxPingMs.get()}",
                    )
                    command("RequestDeviceList", JSONObject())
                    startPingLoop(webSocket)
                    changed = true
                }
                m.has("DeviceList") -> {
                    Log.i(TAG, "DeviceList raw=${text.take(1500)}")
                    if (ingestDeviceList(m.getJSONObject("DeviceList"))) changed = true
                }
                m.has("DeviceAdded") -> {
                    Log.i(TAG, "DeviceAdded raw=${m.toString().take(800)}")
                    if (upsertDevice(m.getJSONObject("DeviceAdded"))) changed = true
                }
                m.has("DeviceRemoved") -> {
                    val rem = m.getJSONObject("DeviceRemoved")
                    val idx = rem.optInt("DeviceIndex", -1)
                    synchronized(lock) {
                        if (devices.remove(idx) != null) changed = true
                    }
                }
                m.has("Ok") -> { /* ack */ }
                m.has("InputReading") -> {
                    if (ingestInputReading(m.getJSONObject("InputReading"))) changed = true
                }
                m.has("SensorReading") -> {
                    if (ingestSensorReading(m.getJSONObject("SensorReading"))) changed = true
                }
                m.has("Error") -> {
                    val err = m.getJSONObject("Error")
                    val msg = err.optString("ErrorMessage")
                    val code = err.optInt("ErrorCode", -1)
                    Log.w(TAG, "error[$code]: $msg")
                    // Handshake rejected for v4 → reconnect as v3 MessageVersion client.
                    if (!ready.get() && handshakeMajor.get() >= 4 &&
                        (msg.contains("version", ignoreCase = true) ||
                            msg.contains("MessageVersion", ignoreCase = true) ||
                            msg.contains("ProtocolVersion", ignoreCase = true) ||
                            code == 3)
                    ) {
                        Log.i(TAG, "falling back to protocol v3 handshake")
                        handshakeMajor.set(3)
                        // Re-handshake on the same socket — do not cancel (avoids markDown race).
                        sendHandshake(webSocket)
                        return
                    }
                    // Device powered off / BLE gone — drop immediately (v4 may lag on DeviceList).
                    val goneIdx = parseDeviceNotAvailable(msg)
                    if (goneIdx >= 0) {
                        synchronized(lock) {
                            if (devices.remove(goneIdx) != null) {
                                Log.i(TAG, "device[$goneIdx] dropped (DeviceNotAvailable)")
                                changed = true
                            }
                        }
                    }
                    if (msg.isNotBlank() && !msg.contains("Ping", ignoreCase = true)) {
                        lastError = msg
                        changed = true
                    }
                }
            }
        }
        if (changed) notifyChange()
    }

    private fun ingestDeviceList(list: JSONObject): Boolean {
        // Spec v4: DeviceList is the full truth — diff removes powered-off toys.
        // DeviceAdded/DeviceRemoved were removed in v4.
        val seen = linkedSetOf<Int>()
        var changed = false
        val devicesNode = list.opt("Devices")
        when (devicesNode) {
            is JSONArray -> {
                for (j in 0 until devicesNode.length()) {
                    val obj = devicesNode.optJSONObject(j) ?: continue
                    val idx = obj.optInt("DeviceIndex", -1)
                    if (idx >= 0) seen += idx
                    if (upsertDevice(obj)) changed = true
                }
            }
            is JSONObject -> {
                val keys = devicesNode.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = devicesNode.optJSONObject(key) ?: continue
                    if (!obj.has("DeviceIndex")) obj.put("DeviceIndex", key.toIntOrNull() ?: -1)
                    val idx = obj.optInt("DeviceIndex", -1)
                    if (idx >= 0) seen += idx
                    if (upsertDevice(obj)) changed = true
                }
            }
            // null / empty → drop all
            else -> { /* seen stays empty */ }
        }
        synchronized(lock) {
            val stale = devices.keys.filter { it !in seen }
            for (idx in stale) {
                Log.i(TAG, "device[$idx] removed (absent from DeviceList)")
                devices.remove(idx)
                changed = true
            }
        }
        return changed
    }

    private fun upsertDevice(raw: JSONObject): Boolean {
        val index = raw.optInt("DeviceIndex", -1)
        if (index < 0) return false
        val name = raw.optString("DeviceName")
            .ifBlank { raw.optString("DeviceDisplayName") }
            .ifBlank { "Device $index" }
        val features = parseFeatures(raw)
        val kind = inferKind(features, name)
        val batteryIdx = features.firstOrNull { it.outputType == "Battery" }?.featureIndex
            ?: parseV3Battery(raw.optJSONObject("DeviceMessages"))
        val id = stableDeviceId(name)
        synchronized(lock) {
            val prev = devices[index]
            devices[index] = LiveDevice(
                index = index,
                deviceId = id,
                name = name,
                kind = kind,
                features = features.filter { it.outputType != "Battery" },
                batterySensorIndex = batteryIdx,
                batteryPercent = prev?.batteryPercent ?: -1,
            )
            Log.i(TAG, "device[$index] $name kind=$kind features=${features.map { "${it.featureIndex}:${it.outputType}[${it.valueMin}-${it.valueMax}]" }}")
            return prev == null || prev.name != name || prev.kind != kind || prev.features != features
        }
    }

    private fun parseFeatures(raw: JSONObject): List<DeviceFeature> {
        val out = ArrayList<DeviceFeature>()
        when (val featsNode = raw.opt("DeviceFeatures")) {
            is JSONObject -> {
                val keys = featsNode.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val f = featsNode.optJSONObject(key) ?: continue
                    parseOneFeature(f, key.toIntOrNull() ?: -1, out)
                }
                if (out.isNotEmpty()) return out
            }
            is JSONArray -> {
                for (i in 0 until featsNode.length()) {
                    val f = featsNode.optJSONObject(i) ?: continue
                    parseOneFeature(f, i, out)
                }
                if (out.isNotEmpty()) return out
            }
        }
        // v3: DeviceMessages.ScalarCmd / LinearCmd arrays
        val msgs = raw.optJSONObject("DeviceMessages") ?: return out
        val scalars = msgs.optJSONArray("ScalarCmd")
        if (scalars != null) {
            for (i in 0 until scalars.length()) {
                val s = scalars.optJSONObject(i) ?: continue
                val type = s.optString("ActuatorType", "Vibrate")
                val steps = s.optInt("StepCount", 20).coerceAtLeast(1)
                out += DeviceFeature(i, type, 0, steps)
            }
        }
        if (msgs.has("LinearCmd")) {
            val linear = msgs.optJSONArray("LinearCmd")
            val n = linear?.length() ?: 1
            for (i in 0 until n) {
                out += DeviceFeature(i, "HwPositionWithDuration", 0, 100, 50, 60_000)
            }
        }
        return out
    }

    private fun parseOneFeature(f: JSONObject, fallbackIndex: Int, out: MutableList<DeviceFeature>) {
        val fi = f.optInt("FeatureIndex", fallbackIndex)
        if (fi < 0) return
        val output = f.optJSONObject("Output")
        val input = f.optJSONObject("Input")
        if (output != null) {
            val ok = output.keys()
            while (ok.hasNext()) {
                val type = ok.next()
                val meta = output.optJSONObject(type) ?: continue
                val range = parseRange(meta.opt("Value"), 0, 20)
                val dur = parseRange(meta.opt("Duration"), 50, 60_000)
                out += DeviceFeature(fi, type, range.first, range.second, dur.first, dur.second)
            }
        }
        if (input != null && input.has("Battery")) {
            out += DeviceFeature(fi, "Battery", 0, 100)
        }
    }

    private fun parseRange(raw: Any?, defaultMin: Int, defaultMax: Int): Pair<Int, Int> {
        return when (raw) {
            is JSONArray -> {
                if (raw.length() >= 2) raw.optInt(0) to raw.optInt(1)
                else defaultMin to defaultMax
            }
            is Number -> defaultMin to raw.toInt()
            else -> defaultMin to defaultMax
        }
    }

    private fun parseV3Battery(msgs: JSONObject?): Int {
        val attrs = msgs?.optJSONArray("SensorReadCmd") ?: return -1
        for (i in 0 until attrs.length()) {
            val m = attrs.optJSONObject(i) ?: continue
            if (m.optString("SensorType").equals("Battery", ignoreCase = true)) {
                return if (m.has("Index")) m.optInt("Index") else i
            }
        }
        return -1
    }

    private fun inferKind(features: List<DeviceFeature>, name: String): String {
        if (isVortexName(name)) return "constrict"
        val types = features.map { it.outputType }.toSet()
        return when {
            "HwPositionWithDuration" in types || "Position" in types -> "linear"
            "Constrict" in types && "Vibrate" !in types -> "constrict"
            else -> "scalar"
        }
    }

    fun startScanning() = command("StartScanning", JSONObject())
    fun stopScanning() = command("StopScanning", JSONObject())
    fun requestDeviceList() = command("RequestDeviceList", JSONObject())

    fun stopAll() {
        if (protocolMajor.get() >= 4) {
            command("StopCmd", JSONObject())
        } else {
            command("StopAllDevices", JSONObject())
        }
    }

    fun stopDevice(idx: Int) {
        if (protocolMajor.get() >= 4) {
            command("StopCmd", JSONObject().put("DeviceIndex", idx))
        } else {
            command("StopDeviceCmd", JSONObject().put("DeviceIndex", idx))
        }
    }

    /** [level01] 0..1 → OutputCmd/ScalarCmd on every matching vibrate (or constrict) feature. */
    fun scalar(idx: Int, level01: Double, actuator: String = "Vibrate") {
        val level = level01.coerceIn(0.0, 1.0)
        val act = actuator.ifBlank { "Vibrate" }
        val device = deviceByIndex(idx) ?: return
        val targets = device.features.filter {
            it.outputType.equals(act, ignoreCase = true) ||
                (act.equals("Vibrate", ignoreCase = true) && it.outputType == "Oscillate")
        }
        if (targets.isEmpty()) return
        if (protocolMajor.get() >= 4) {
            for (f in targets) {
                val steps = (f.valueMax - f.valueMin).coerceAtLeast(1)
                val value = f.valueMin + (level * steps).roundToInt().coerceIn(0, steps)
                outputCmd(idx, f.featureIndex, f.outputType, value)
            }
        } else {
            val featureIndex = targets.first().featureIndex
            command(
                "ScalarCmd",
                JSONObject()
                    .put("DeviceIndex", idx)
                    .put(
                        "Scalars",
                        JSONArray().put(
                            JSONObject()
                                .put("Index", featureIndex)
                                .put("Scalar", level)
                                .put("ActuatorType", targets.first().outputType),
                        ),
                    ),
            )
        }
    }

    fun linear(idx: Int, pos01: Double, durationMs: Int) {
        val pos = pos01.coerceIn(0.0, 1.0)
        val device = deviceByIndex(idx) ?: return
        val f = device.features.firstOrNull {
            it.outputType == "HwPositionWithDuration" || it.outputType == "Position"
        } ?: return
        if (protocolMajor.get() >= 4) {
            val max = f.valueMax
            val min = f.valueMin
            val value = min + (pos * (max - min)).roundToInt().coerceIn(0, (max - min).coerceAtLeast(0))
            val dur = durationMs.coerceIn(f.durationMin, f.durationMax)
            val commandBody = if (f.outputType == "HwPositionWithDuration") {
                JSONObject().put("HwPositionWithDuration", JSONObject().put("Value", value).put("Duration", dur))
            } else {
                JSONObject().put("Position", JSONObject().put("Value", value))
            }
            command(
                "OutputCmd",
                JSONObject()
                    .put("DeviceIndex", idx)
                    .put("FeatureIndex", f.featureIndex)
                    .put("Command", commandBody),
            )
        } else {
            command(
                "LinearCmd",
                JSONObject()
                    .put("DeviceIndex", idx)
                    .put(
                        "Vectors",
                        JSONArray().put(
                            JSONObject()
                                .put("Index", f.featureIndex)
                                .put("Duration", durationMs.coerceAtLeast(50))
                                .put("Position", pos),
                        ),
                    ),
            )
        }
    }

    private fun outputCmd(deviceIndex: Int, featureIndex: Int, outputType: String, value: Int) {
        command(
            "OutputCmd",
            JSONObject()
                .put("DeviceIndex", deviceIndex)
                .put("FeatureIndex", featureIndex)
                .put("Command", JSONObject().put(outputType, JSONObject().put("Value", value))),
        )
    }

    fun sensorRead(idx: Int, sensorIdx: Int) {
        if (protocolMajor.get() >= 4) {
            command(
                "InputCmd",
                JSONObject()
                    .put("DeviceIndex", idx)
                    .put("FeatureIndex", sensorIdx)
                    .put("Type", "Battery")
                    .put("Command", "Read"),
            )
        } else {
            command(
                "SensorReadCmd",
                JSONObject()
                    .put("DeviceIndex", idx)
                    .put("SensorIndex", sensorIdx)
                    .put("SensorType", "Battery"),
            )
        }
    }

    private fun ingestInputReading(raw: JSONObject): Boolean {
        val idx = raw.optInt("DeviceIndex", -1)
        val reading = raw.optJSONObject("Reading") ?: return false
        val battery = reading.optJSONObject("Battery") ?: return false
        val pct = battery.optInt("Value", -1)
        if (idx < 0 || pct < 0) return false
        synchronized(lock) {
            val prev = devices[idx] ?: return false
            devices[idx] = prev.copy(batteryPercent = pct)
        }
        return true
    }

    private fun ingestSensorReading(raw: JSONObject): Boolean {
        val idx = raw.optInt("DeviceIndex", -1)
        val data = raw.optJSONArray("Data") ?: return false
        val pct = data.optInt(0, -1)
        if (idx < 0 || pct < 0) return false
        synchronized(lock) {
            val prev = devices[idx] ?: return false
            devices[idx] = prev.copy(batteryPercent = pct)
        }
        return true
    }

    fun close() {
        ready.set(false)
        pingThread = null
        val ws = synchronized(lock) {
            val s = socket
            socket = null
            devices.clear()
            s
        }
        runCatching { ws?.close(1000, null) }
        runCatching { ws?.cancel() }
        notifyChange()
    }

    private fun markDown(reason: String) {
        ready.set(false)
        if (reason.isNotBlank()) lastError = reason
        synchronized(lock) {
            socket = null
            devices.clear()
        }
        notifyChange()
    }

    private fun command(name: String, body: JSONObject) {
        body.put("Id", nextId())
        val payload = JSONArray().put(JSONObject().put(name, body)).toString()
        val ws = synchronized(lock) { socket }
        if (ws == null) {
            Log.w(TAG, "drop $name — not connected")
            return
        }
        val sent = runCatching { ws.send(payload) }.getOrElse {
            lastError = it.message ?: "send failed"
            Log.w(TAG, "send failed $name: $lastError")
            false
        }
        if (!sent) Log.w(TAG, "send returned false for $name")
        else if (name == "OutputCmd" || name == "ScalarCmd" || name == "LinearCmd") {
            Log.d(TAG, "→ $name ${payload.take(160)}")
        } else if (name != "Ping") {
            Log.i(TAG, "→ $name ${payload.take(240)}")
        }
    }

    private fun nextId(): Long = msgId.incrementAndGet()

    /** Parses `DeviceNotAvailable: N` from ErrorMessage JSON or plain text. */
    private fun parseDeviceNotAvailable(msg: String): Int {
        if (msg.isBlank()) return -1
        // {"ButtplugDeviceError":{"DeviceNotAvailable":0}}
        val jsonIdx = Regex(""""DeviceNotAvailable"\s*:\s*(\d+)""").find(msg)
        if (jsonIdx != null) return jsonIdx.groupValues[1].toIntOrNull() ?: -1
        val plain = Regex("""DeviceNotAvailable[^\d]*(\d+)""", RegexOption.IGNORE_CASE).find(msg)
        return plain?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun startPingLoop(webSocket: WebSocket) {
        val max = maxPingMs.get()
        if (max <= 0) return // server does not require pings
        val interval = (max / 2).coerceIn(50, 1_000).toLong()
        pingThread = Thread({
            while (ready.get() && synchronized(lock) { socket === webSocket }) {
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!ready.get()) return@Thread
                command("Ping", JSONObject())
            }
        }, "buttplug-ping").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun notifyChange() {
        listeners.forEach { runCatching { it.invoke() } }
    }

    companion object {
        private const val TAG = "CoogButtplug"
    }
}
