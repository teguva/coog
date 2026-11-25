package tv.coog.app.interactive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Native FeelConnect-style PowerBlow driver (Intiface has no protocol).
 *
 * Init 0x1902 ← 00 32 00 00 00
 * Suction 0x1401 ← XX YY (intensity, duration ticks ≈50ms)
 * Release 0x1402 ← same XX YY
 */
class PowerBlowHub(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var initChar: BluetoothGattCharacteristic? = null
    @Volatile private var suctionChar: BluetoothGattCharacteristic? = null
    @Volatile private var releaseChar: BluetoothGattCharacteristic? = null
    @Volatile private var colorChar: BluetoothGattCharacteristic? = null
    @Volatile private var batteryChar: BluetoothGattCharacteristic? = null

    private val connected = AtomicBoolean(false)
    private val inited = AtomicBoolean(false)
    private val batteryPct = AtomicInteger(-1)
    private val lastXX = AtomicInteger(0xC0)
    private val lastYY = AtomicInteger(0x28)
    private val address = AtomicReference("")
    /** Serializes pump/vent sleeps off the funscript tick thread. */
    private val bleExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PowerBlow-BLE").apply { isDaemon = true }
    }

    @Volatile var onChange: (() -> Unit)? = null

    private var scanCallback: ScanCallback? = null
    private var connectLatch: CountDownLatch? = null
    private var servicesLatch: CountDownLatch? = null
    private var writeLatch: CountDownLatch? = null
    private var writeOk = AtomicBoolean(false)

    fun isConnected(): Boolean = connected.get() && inited.get()

    fun deviceAddress(): String = address.get()

    fun batteryPercent(): Int = batteryPct.get()

    fun liveDevice(): LiveDevice? {
        if (!isConnected()) return null
        val bat = batteryPct.get()
        return LiveDevice(
            index = INDEX,
            deviceId = STABLE_ID,
            name = NAME,
            kind = "constrict",
            batterySensorIndex = if (bat >= 0) 0 else -1,
            batteryPercent = bat,
        )
    }

    @SuppressLint("MissingPermission")
    fun tryConnect(timeoutMs: Long = 15_000): Boolean {
        if (isConnected()) return true
        close()
        val adapter = bluetoothAdapter() ?: run {
            Log.w(TAG, "no bluetooth adapter")
            return false
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "bluetooth disabled")
            return false
        }

        // Bonded first, then LE scan (pairing-mode ads as "PowerBlow R1").
        val known = adapter.bondedDevices?.firstOrNull { isPowerBlowName(it.name) }
        val found = known ?: scanForPowerBlow(adapter, timeoutMs.coerceAtMost(10_000))
        if (found == null) {
            Log.i(TAG, "PowerBlow not advertising")
            return false
        }
        return connectGatt(found, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    private fun scanForPowerBlow(adapter: BluetoothAdapter, timeoutMs: Long): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val result = AtomicReference<BluetoothDevice?>(null)
        val done = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scan: ScanResult?) {
                val dev = scan?.device ?: return
                val name = dev.name ?: scan.scanRecord?.deviceName
                if (!isPowerBlowName(name)) return
                if (result.compareAndSet(null, dev)) {
                    done.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed code=$errorCode")
                done.countDown()
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (t: Throwable) {
            Log.w(TAG, "startScan: ${t.message}")
            return null
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        try {
            scanner.stopScan(cb)
        } catch (_: Throwable) {
        }
        scanCallback = null
        return result.get()
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice, timeoutMs: Long): Boolean {
        address.set(device.address ?: "")
        connectLatch = CountDownLatch(1)
        servicesLatch = CountDownLatch(1)
        val g = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
        }
        gatt = g
        if (g == null) return false
        if (!connectLatch!!.await(timeoutMs, TimeUnit.MILLISECONDS) || !connected.get()) {
            Log.w(TAG, "GATT connect timeout")
            close()
            return false
        }
        if (!servicesLatch!!.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "services resolve timeout")
            close()
            return false
        }
        if (initChar == null || suctionChar == null || releaseChar == null) {
            Log.w(TAG, "missing PowerBlow characteristics")
            close()
            return false
        }
        if (!writeSync(initChar!!, INIT_PAYLOAD)) {
            Log.w(TAG, "init write failed")
            close()
            return false
        }
        // FeelConnect-style hello pulse.
        suction(0.55, 800)
        try {
            Thread.sleep(250)
        } catch (_: InterruptedException) {
        }
        release()
        writeColor(0x00, 0xC8, 0xFF, 0xA0)
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
        }
        refreshBattery()
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {
        }
        inited.set(true)
        Log.i(TAG, "connected ${address.get()} battery=${batteryPct.get()}")
        notifyChange()
        return true
    }

    fun isVacuumOn(): Boolean = vacuumOn

    /**
     * FeelConnect-style stroke on a single BLE worker.
     * Pump and vent are mutually exclusive: full pump pulse finishes before any
     * vent write; vent settle finishes before any suck write. Latest intent wins
     * via [desiredSuck] coalescing — never two overlapping GATT ops.
     */
    fun applyStroke(st: PowerBlowStroke): Boolean {
        if (!connected.get()) return false
        if (st.suck) {
            // Never cancel a pending vent — that skips the valve and stacks a
            // second pump, which pushes the following stroke late.
            if (desiredSuck.get() == false) {
                PowerBlowTrace.ble("SUCK", ok = true, blocked = "keep-pending-vent")
                return true
            }
            if (vacuumOn || pumping) {
                PowerBlowTrace.ble("SUCK", ok = true, blocked = "already-holding")
                return true
            }
            desiredLevel.set(st.level)
            desiredDurMs.set(st.durationMs.coerceIn(50L, 12_750L))
            desiredSuck.set(true)
        } else {
            // Vent always wins over a pending suck; YY scales with insertion dt.
            desiredLevel.set(st.level)
            desiredDurMs.set(st.durationMs.coerceIn(50L, 12_750L))
            desiredSuck.set(false)
        }
        scheduleBleDrain()
        return true
    }

    @Volatile private var vacuumOn = false
    /** True only during the suck GATT write. */
    @Volatile private var pumping = false
    /** Device still running suction YY — vent must wait. */
    @Volatile private var pumpUntilMs = 0L
    /** Device still running release YY — suck must wait. */
    @Volatile private var ventUntilMs = 0L
    @Volatile private var lastReleaseAtMs = 0L
    private val desiredSuck = AtomicReference<Boolean?>(null)
    private val desiredLevel = AtomicReference(0.55)
    private val desiredDurMs = AtomicReference(350L)
    private val bleScheduled = AtomicBoolean(false)

    fun suction(level: Double, durationMs: Long): Boolean {
        if (!connected.get()) return false
        return applyStroke(
            PowerBlowStroke(suck = true, level = level, durationMs = durationMs),
        )
    }

    fun release(): Boolean {
        if (!connected.get()) return false
        desiredSuck.set(false)
        scheduleBleDrain()
        return true
    }

    private fun scheduleBleDrain() {
        if (!bleScheduled.compareAndSet(false, true)) return
        bleExec.execute {
            try {
                drainDesired()
            } finally {
                bleScheduled.set(false)
                if (desiredSuck.get() != null) scheduleBleDrain()
            }
        }
    }

    private fun drainDesired() {
        while (connected.get()) {
            val want = desiredSuck.getAndSet(null) ?: return
            if (want) {
                if (pumping || vacuumOn) {
                    PowerBlowTrace.ble(
                        "SUCK",
                        ok = true,
                        blocked = if (pumping) "busy-pump" else "already-holding",
                    )
                } else {
                    suctionExclusive(desiredLevel.get(), desiredDurMs.get())
                }
            } else {
                if (!vacuumOn && !pumping) {
                    PowerBlowTrace.ble("VENT", ok = true, blocked = "already-open")
                } else {
                    releaseExclusive()
                }
            }
        }
    }

    /** Wait [ms], abort early if desired intent flips to [abortWhenSuck]. */
    private fun sleepInterruptible(ms: Long, abortWhenSuck: Boolean?): Boolean {
        var left = ms.coerceAtLeast(0L)
        while (left > 0L && connected.get()) {
            if (abortWhenSuck != null && desiredSuck.get() == abortWhenSuck) return false
            val chunk = left.coerceAtMost(40L)
            try {
                Thread.sleep(chunk)
            } catch (_: InterruptedException) {
                return false
            }
            left -= chunk
        }
        return true
    }

    private fun suctionExclusive(level: Double, durationMs: Long): Boolean {
        if (!connected.get()) return false
        val suction = suctionChar ?: return false
        if (pumping || vacuumOn) {
            PowerBlowTrace.ble("SUCK", ok = true, blocked = if (pumping) "busy-pump" else "already-holding")
            return true
        }
        // Never start pump while the device is still executing a release pulse.
        var settleWaited = 0L
        val ventLeft = (ventUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val settle = (PB_VENT_SETTLE_MS - (SystemClock.elapsedRealtime() - lastReleaseAtMs)).coerceAtLeast(0L)
        val preWait = maxOf(ventLeft, settle)
        if (preWait > 0L) {
            settleWaited = preWait
            if (!sleepInterruptible(preWait, abortWhenSuck = false)) {
                PowerBlowTrace.ble("SUCK", ok = true, blocked = "aborted-for-vent", waitedMs = preWait)
                return false
            }
            if (desiredSuck.get() == false) {
                PowerBlowTrace.ble("SUCK", ok = true, blocked = "skipped-want-vent", waitedMs = preWait)
                return false
            }
        }
        ventUntilMs = 0L
        // FeelConnect suction band 0x80–0xFF, linear with stroke level.
        val clamped = level.coerceIn(0.0, 1.0)
        val xx = (0x80 + clamped * 0x7F).toInt().coerceIn(0x80, 0xFF)
        // Cap on-wire pump so vent isn't blocked for a full long script edge.
        val pumpMs = durationMs.coerceIn(50L, PB_PUMP_PULSE_MAX_MS)
        var ticks = (pumpMs / 50L).toInt()
        if (ticks < 1) ticks = 1
        if (ticks > 255) ticks = 255
        lastXX.set(xx)
        lastYY.set(ticks)
        vacuumOn = true
        pumping = true
        pumpUntilMs = SystemClock.elapsedRealtime() + pumpMs
        val ok = writeSync(suction, byteArrayOf(xx.toByte(), ticks.toByte()))
        PowerBlowTrace.ble("SUCK", ok = ok, xx = xx, yy = ticks, waitedMs = settleWaited)
        pumping = false
        sleepInterruptible(PB_WRITE_GAP_MS, abortWhenSuck = null)
        return ok
    }

    private fun releaseExclusive(): Boolean {
        if (!connected.get()) return false
        val release = releaseChar ?: return false
        // Finish on-device pump pulse before opening the valve.
        var waited = 0L
        val wait = (pumpUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L).coerceAtMost(PB_PUMP_PULSE_MAX_MS)
        if (wait > 0L || pumping) {
            waited = wait
            sleepInterruptible(wait, abortWhenSuck = null)
            pumping = false
            pumpUntilMs = 0L
        }
        if (!vacuumOn) {
            PowerBlowTrace.ble("VENT", ok = true, blocked = "already-open", waitedMs = waited)
            return true
        }
        val xx = when {
            desiredLevel.get() > 0 -> (0x80 + desiredLevel.get().coerceIn(0.0, 1.0) * 0x7F).toInt().coerceIn(0x80, 0xFF)
            else -> lastXX.get()
        }
        val ventMs = desiredDurMs.get().coerceIn(50L, PB_VENT_PULSE_MAX_MS)
        var ticks = (ventMs / 50L).toInt()
        if (ticks < 1) ticks = 1
        if (ticks > 255) ticks = 255
        val yy = ticks
        vacuumOn = false
        pumpUntilMs = 0L
        lastReleaseAtMs = SystemClock.elapsedRealtime()
        // Block the next suck until this release YY finishes on-device.
        ventUntilMs = SystemClock.elapsedRealtime() + ventMs
        val ok = writeSync(release, byteArrayOf(xx.toByte(), yy.toByte()))
        PowerBlowTrace.ble("VENT", ok = ok, xx = xx, yy = yy, waitedMs = waited)
        sleepInterruptible(PB_WRITE_GAP_MS, abortWhenSuck = null)
        return ok
    }

    /** Test / Halt: >0 short suck, 0 release. */
    fun constrict(level: Double): Boolean {
        if (level <= 0.02) return release()
        return suction(level, 900)
    }

    @SuppressLint("MissingPermission")
    fun refreshBattery() {
        val c = batteryChar ?: return
        val g = gatt ?: return
        try {
            g.readCharacteristic(c)
        } catch (t: Throwable) {
            Log.w(TAG, "battery read: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        inited.set(false)
        connected.set(false)
        initChar = null
        suctionChar = null
        releaseChar = null
        colorChar = null
        batteryChar = null
        val g = gatt
        gatt = null
        if (g != null) {
            try {
                g.disconnect()
            } catch (_: Throwable) {
            }
            try {
                g.close()
            } catch (_: Throwable) {
            }
            notifyChange()
        }
        address.set("")
    }

    private fun writeColor(r: Int, g: Int, b: Int, w: Int) {
        val c = colorChar ?: return
        writeSync(c, byteArrayOf(r.toByte(), g.toByte(), b.toByte(), w.toByte()))
    }

    @SuppressLint("MissingPermission")
    private fun writeSync(char: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: return false
        writeLatch = CountDownLatch(1)
        writeOk.set(false)
        val okEnqueue = synchronized(lock) {
            try {
                @Suppress("DEPRECATION")
                char.value = data
                // Write without response — matches FeelConnect / Web Bluetooth timing.
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(char)
            } catch (t: Throwable) {
                Log.w(TAG, "write enqueue: ${t.message}")
                false
            }
        }
        if (!okEnqueue) return false
        // NO_RESPONSE may skip callback on some stacks; brief wait then trust enqueue.
        writeLatch?.await(200, TimeUnit.MILLISECONDS)
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.set(true)
                connectLatch?.countDown()
                try {
                    g.discoverServices()
                } catch (t: Throwable) {
                    Log.w(TAG, "discoverServices: ${t.message}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = connected.getAndSet(false)
                inited.set(false)
                connectLatch?.countDown()
                servicesLatch?.countDown()
                if (was) {
                    Log.i(TAG, "disconnected")
                    notifyChange()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesLatch?.countDown()
                return
            }
            for (svc in g.services.orEmpty()) {
                for (ch in svc.characteristics.orEmpty()) {
                    when (ch.uuid) {
                        UUID_INIT -> initChar = ch
                        UUID_SUCTION -> suctionChar = ch
                        UUID_RELEASE -> releaseChar = ch
                        UUID_COLOR -> colorChar = ch
                        UUID_BATTERY -> batteryChar = ch
                    }
                }
            }
            servicesLatch?.countDown()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeOk.set(status == BluetoothGatt.GATT_SUCCESS)
            writeLatch?.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            @Suppress("DEPRECATION")
            val raw = characteristic.value ?: return
            if (characteristic.uuid == UUID_BATTERY && raw.isNotEmpty()) {
                batteryPct.set(raw[0].toInt() and 0xFF)
                notifyChange()
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == UUID_BATTERY && value.isNotEmpty()) {
                batteryPct.set(value[0].toInt() and 0xFF)
                notifyChange()
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    private fun notifyChange() {
        main.post { onChange?.invoke() }
    }

    companion object {
        private const val TAG = "CoogPowerBlow"
        const val INDEX = 9100
        const val STABLE_ID = "name:powerblow-r1"
        const val NAME = "PowerBlow R1"
        /** Extra settle after release if ventUntil already elapsed. */
        private const val PB_VENT_SETTLE_MS = 40L
        /** On-wire pump pulse cap — long script edges still scale XX, not endless YY. */
        private const val PB_PUMP_PULSE_MAX_MS = 280L
        /** On-wire vent pulse cap; next suck waits for this via ventUntil. */
        private const val PB_VENT_PULSE_MAX_MS = 120L
        private const val PB_WRITE_GAP_MS = 30L

        private val UUID_INIT = UUID.fromString("00001902-0000-1000-8000-00805f9b34fb")
        private val UUID_SUCTION = UUID.fromString("00001401-0000-1000-8000-00805f9b34fb")
        private val UUID_RELEASE = UUID.fromString("00001402-0000-1000-8000-00805f9b34fb")
        private val UUID_COLOR = UUID.fromString("00001601-0000-1000-8000-00805f9b34fb")
        private val UUID_BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val INIT_PAYLOAD = byteArrayOf(0x00, 0x32, 0x00, 0x00, 0x00)

        fun isPowerBlowName(name: String?): Boolean {
            val n = name?.trim()?.lowercase() ?: return false
            return n.contains("powerblow") || n == "kpb"
        }

        fun isPowerBlowId(deviceId: String?): Boolean {
            val id = deviceId?.trim()?.lowercase() ?: return false
            return id == STABLE_ID || id.contains("powerblow")
        }
    }
}
