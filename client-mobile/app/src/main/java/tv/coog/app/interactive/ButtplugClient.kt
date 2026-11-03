package tv.coog.app.interactive

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList

data class LiveDevice(
    val index: Int,
    val deviceId: String,
    val name: String,
    val kind: String, // scalar|linear|constrict
    val batterySensorIndex: Int = -1,
    val batteryPercent: Int = -1,
)

/**
 * Buttplug v3 WebSocket client for Intiface Central on the phone (default :12345).
 * Mirrors server/internal/interactive/buttplug.go.
 */
class ButtplugClient(
    private var host: String = "127.0.0.1",
    private var port: Int = 12345,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private var socket: WebSocket? = null
    private val msgId = AtomicLong(1)
    private val devices = linkedMapOf<Int, LiveDevice>()
    private val ready = AtomicBoolean(false)
    @Volatile var lastError: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var pingThread: Thread? = null

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

    fun connect(clientName: String = "coog-mobile") {
        close()
        val url = "ws://$host:$port"
        val req = Request.Builder().url(url).build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val id = nextId()
                val handshake = JSONArray().put(
                    JSONObject().put(
                        "RequestServerInfo",
                        JSONObject()
                            .put("Id", id)
                            .put("ClientName", clientName)
                            .put("MessageVersion", 3),
                    ),
                )
                webSocket.send(handshake.toString())
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
                Log.w(TAG, "buttplug ws failure: ${t.message}")
            }
        })
        synchronized(lock) {
            socket = ws
            devices.clear()
        }
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
        if (!ready.get()) {
            // First successful ServerInfo (or any reply after handshake) → ready.
            ready.set(true)
            lastError = ""
            Log.i(TAG, "buttplug connected :$port")
            command("RequestDeviceList", JSONObject())
            startPingLoop(webSocket)
            notifyChange()
        }
        var changed = false
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            when {
                m.has("DeviceList") -> {
                    val list = m.getJSONObject("DeviceList").optJSONArray("Devices") ?: continue
                    for (j in 0 until list.length()) {
                        if (upsertDevice(list.getJSONObject(j))) changed = true
                    }
                }
                m.has("DeviceAdded") -> {
                    if (upsertDevice(m.getJSONObject("DeviceAdded"))) changed = true
                }
                m.has("DeviceRemoved") -> {
                    val idx = m.getJSONObject("DeviceRemoved").optInt("DeviceIndex", -1)
                    synchronized(lock) {
                        if (devices.remove(idx) != null) changed = true
                    }
                }
                m.has("SensorReading") -> {
                    val reading = m.getJSONObject("SensorReading")
                    if (reading.optString("SensorType").equals("Battery", ignoreCase = true)) {
                        val idx = reading.optInt("DeviceIndex", -1)
                        val data = reading.optJSONArray("Data")
                        val pct = data?.optInt(0, -1) ?: -1
                        if (idx >= 0 && pct >= 0) {
                            synchronized(lock) {
                                val prev = devices[idx] ?: return@synchronized
                                devices[idx] = prev.copy(batteryPercent = pct)
                                changed = true
                            }
                        }
                    }
                }
                m.has("Error") -> {
                    val msg = m.getJSONObject("Error").optString("ErrorMessage")
                    if (msg.isNotBlank() && !msg.contains("Ping")) {
                        lastError = msg
                        Log.w(TAG, "buttplug error: $msg")
                    }
                }
            }
        }
        if (changed) notifyChange()
    }

    private fun upsertDevice(raw: JSONObject): Boolean {
        val index = raw.optInt("DeviceIndex", -1)
        if (index < 0) return false
        val name = raw.optString("DeviceName").ifBlank { "Device $index" }
        val msgs = raw.optJSONObject("DeviceMessages")
        val kind = inferKind(msgs, name)
        val batteryIdx = batterySensorIndex(msgs)
        val id = stableDeviceId(name)
        synchronized(lock) {
            val prev = devices[index]
            devices[index] = LiveDevice(
                index = index,
                deviceId = id,
                name = name,
                kind = kind,
                batterySensorIndex = batteryIdx,
                batteryPercent = prev?.batteryPercent ?: -1,
            )
            return prev == null || prev.name != name || prev.kind != kind
        }
    }

    private fun inferKind(msgs: JSONObject?, name: String): String {
        if (isVortexName(name)) return "constrict"
        if (msgs == null) return "scalar"
        var hasVibrate = false
        var hasConstrict = false
        var hasLinear = msgs.has("LinearCmd")
        val scalars = msgs.optJSONArray("ScalarCmd")
        if (scalars != null) {
            for (i in 0 until scalars.length()) {
                when (scalars.optJSONObject(i)?.optString("ActuatorType")) {
                    "Vibrate" -> hasVibrate = true
                    "Constrict" -> hasConstrict = true
                    "Position" -> hasLinear = true
                }
            }
        }
        return when {
            hasLinear -> "linear"
            hasConstrict && !hasVibrate -> "constrict"
            else -> "scalar"
        }
    }

    private fun batterySensorIndex(msgs: JSONObject?): Int {
        val attrs = msgs?.optJSONArray("SensorReadCmd") ?: return -1
        for (i in 0 until attrs.length()) {
            val m = attrs.optJSONObject(i) ?: continue
            if (m.optString("SensorType").equals("Battery", ignoreCase = true)) {
                return if (m.has("Index")) m.optInt("Index") else i
            }
        }
        return -1
    }

    fun startScanning() = command("StartScanning", JSONObject())
    fun stopScanning() = command("StopScanning", JSONObject())
    fun stopAll() = command("StopAllDevices", JSONObject())

    fun stopDevice(idx: Int) = command(
        "StopDeviceCmd",
        JSONObject().put("DeviceIndex", idx),
    )

    fun scalar(idx: Int, scalar: Double, actuator: String = "Vibrate") {
        val s = scalar.coerceIn(0.0, 1.0)
        val act = actuator.ifBlank { "Vibrate" }
        command(
            "ScalarCmd",
            JSONObject()
                .put("DeviceIndex", idx)
                .put(
                    "Scalars",
                    JSONArray().put(
                        JSONObject()
                            .put("Index", 0)
                            .put("Scalar", s)
                            .put("ActuatorType", act),
                    ),
                ),
        )
    }

    fun linear(idx: Int, pos: Double, durationMs: Int) {
        val p = pos.coerceIn(0.0, 1.0)
        val dur = durationMs.coerceAtLeast(50)
        command(
            "LinearCmd",
            JSONObject()
                .put("DeviceIndex", idx)
                .put(
                    "Vectors",
                    JSONArray().put(
                        JSONObject()
                            .put("Index", 0)
                            .put("Duration", dur)
                            .put("Position", p),
                    ),
                ),
        )
    }

    fun sensorRead(idx: Int, sensorIdx: Int) = command(
        "SensorReadCmd",
        JSONObject()
            .put("DeviceIndex", idx)
            .put("SensorIndex", sensorIdx)
            .put("SensorType", "Battery"),
    )

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
        val ws = synchronized(lock) { socket } ?: return
        runCatching { ws.send(payload) }.onFailure {
            lastError = it.message ?: "send failed"
        }
    }

    private fun nextId(): Long = msgId.incrementAndGet()

    private fun startPingLoop(webSocket: WebSocket) {
        pingThread = Thread({
            while (ready.get() && synchronized(lock) { socket === webSocket }) {
                try {
                    Thread.sleep(500)
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
