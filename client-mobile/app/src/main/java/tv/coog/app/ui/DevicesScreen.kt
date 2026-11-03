package tv.coog.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.data.CoogApi
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.data.InteractiveEngineState
import tv.coog.app.data.MaizeBleMode
import tv.coog.app.data.SettingsRepository
import tv.coog.app.interactive.EmbeddedIntiface
import tv.coog.app.interactive.LocalIntiface
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

/**
 * Mobile Devices: on-device BLE or server intiface, per Maize BLE mode setting.
 * TV DevicesScreen is always server-side.
 */
@Composable
fun DevicesScreen(
    serverUrl: String,
    token: String,
    adultSession: String,
) {
    val inset = catalogInset()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val bleMode by settings.maizeBleMode.collectAsState(initial = MaizeBleMode.Device)
    val local = remember { LocalIntiface.get(context) }
    val localEngine by local.engine.collectAsState()
    var serverEngine by remember { mutableStateOf(InteractiveEngineState()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val useDeviceBle = bleMode == MaizeBleMode.Device
    val engine = if (useDeviceBle) localEngine else serverEngine

    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    fun api() = CoogApi(serverUrl, token, adultSession)

    fun refreshServer() {
        scope.launch {
            runCatching { api().interactiveEngine() }
                .onSuccess {
                    serverEngine = it
                    error = null
                }
                .onFailure { error = it.message }
        }
    }

    // Apply ownership: device mode claims server yield; server mode releases it.
    LaunchedEffect(bleMode, serverUrl, adultSession) {
        if (serverUrl.isBlank() || adultSession.isBlank()) return@LaunchedEffect
        if (useDeviceBle) {
            bleLauncher.launch(blePerms)
            local.startMaintainer()
            runCatching {
                api().interactiveBleOwner("client", "coog-mobile")
            }.onFailure { LogSoft("yield server BLE: ${it.message}") }
        } else {
            local.pauseLocal()
            runCatching {
                api().interactiveBleOwner("server")
            }.onFailure { LogSoft("reclaim server BLE: ${it.message}") }
        }
    }

    LaunchedEffect(useDeviceBle, serverUrl, adultSession) {
        if (useDeviceBle) {
            while (true) {
                if (!local.sync.isDriving()) local.refreshBatteries()
                delay(5_000)
            }
        } else {
            while (true) {
                refreshServer()
                delay(2_000)
            }
        }
    }

    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onFailure { error = it.message }
            if (!useDeviceBle) refreshServer()
            busy = false
        }
    }

    val list = remember(engine) {
        (engine.devices + engine.trustedDevices + engine.knownDevices)
            .distinctBy { it.deviceId.ifBlank { it.name + it.index } }
            .sortedWith(
                compareByDescending<InteractiveDevice> { it.connected }
                    .thenBy { it.name.ifBlank { it.deviceId } },
            )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(top = topBarHeight() + 6.dp, start = inset, end = inset),
    ) {
        Text("Devices", style = CoogType.screenTitle, color = Color.White)
        Text(
            buildString {
                append(if (useDeviceBle) "Phone BLE" else "Server BLE")
                if (useDeviceBle && EmbeddedIntiface.available()) append(" · in-app")
                append(" · ")
                append(if (engine.connected) "linked" else "not linked")
                if (engine.scanning) append(" · scanning")
                if (engine.pairing) append(" · pairing ${engine.pairingSecondsLeft}s")
                if (!useDeviceBle && engine.clientBleHold) {
                    append(" · held by ${engine.clientBleHolder.ifBlank { "client" }}")
                }
            },
            style = CoogType.cardYear,
            color = CoogTextMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Text(
            "Change in Settings → Maize → Toy Bluetooth",
            style = CoogType.cardYear,
            color = CoogTextMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (!engine.connected && useDeviceBle) {
            Text(
                "Allow Bluetooth, then Scan. Server radio is yielded while Phone BLE is on.",
                color = Color(0xFFFFCC80),
                style = CoogType.cardYear,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (!error.isNullOrBlank()) {
            Text(error!!, color = Color(0xFFFF8A80), style = CoogType.cardYear)
        }
        if (engine.lastError.isNotBlank() && !engine.connected) {
            Text(engine.lastError, color = CoogTextMuted, style = CoogType.cardYear)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            if (useDeviceBle) {
                ActionChip("Scan") { runAction { local.scanStart() } }
                ActionChip("Pair") { runAction { local.scanPair() } }
                ActionChip("Reconnect") { runAction { local.reconnect() } }
                ActionChip("Stop all") { runAction { local.stopAll() } }
            } else {
                ActionChip("Scan") { runAction { api().interactiveScanStart() } }
                ActionChip("Pair") { runAction { api().interactiveScanPair() } }
                ActionChip("Restart") { runAction { api().interactiveRestart() } }
                ActionChip("Clear offline") { runAction { api().interactiveForgetOffline() } }
            }
        }
        if (busy) {
            Text("Working…", color = CoogTextMuted, style = CoogType.cardYear)
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = mobileDockClearance()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    Text(
                        if (engine.connected) {
                            "No devices yet. Tap Pair and turn on a toy."
                        } else {
                            "Waiting for Bluetooth engine…"
                        },
                        color = CoogTextMuted,
                    )
                }
            }
            items(list, key = { it.deviceId.ifBlank { "${it.index}-${it.name}" } }) { dev ->
                DeviceRow(
                    device = dev,
                    onIntensity = { delta ->
                        runAction {
                            if (useDeviceBle) {
                                if (dev.deviceId.isBlank()) return@runAction
                                local.patchIntensity(
                                    dev.deviceId,
                                    (dev.intensity + delta).coerceIn(10, 200),
                                )
                            } else if (dev.index >= 0) {
                                api().interactivePatch(
                                    dev.index,
                                    intensity = (dev.intensity + delta).coerceIn(10, 200),
                                )
                            }
                        }
                    },
                    onOffset = { delta ->
                        runAction {
                            if (useDeviceBle) {
                                if (dev.deviceId.isBlank()) return@runAction
                                local.patchOffset(dev.deviceId, dev.offsetMs + delta)
                            } else if (dev.index >= 0) {
                                api().interactivePatch(dev.index, offsetMs = dev.offsetMs + delta)
                            }
                        }
                    },
                    onTest = {
                        runAction {
                            if (dev.index >= 0) {
                                if (useDeviceBle) local.test(dev.index)
                                else api().interactiveTest(dev.index)
                            }
                        }
                    },
                    onConnect = {
                        runAction {
                            if (dev.deviceId.isBlank()) return@runAction
                            if (useDeviceBle) local.scanStart()
                            else api().interactiveConnect(dev.deviceId)
                        }
                    },
                    onDrop = {
                        runAction {
                            if (dev.deviceId.isBlank()) return@runAction
                            // Always ask server to free bluez; also drop local link in device mode.
                            runCatching { api().interactiveDrop(dev.deviceId) }
                            if (useDeviceBle) local.drop(dev.deviceId)
                        }
                    },
                    onForget = {
                        runAction {
                            if (dev.deviceId.isBlank()) return@runAction
                            if (useDeviceBle) local.forget(dev.deviceId)
                            else api().interactiveForget(dev.deviceId)
                        }
                    },
                )
            }
        }
    }
}

private fun LogSoft(msg: String) {
    android.util.Log.i("CoogDevices", msg)
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    CoogClickable(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.10f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = CoogType.cardYear,
            color = Color.White,
        )
    }
}

@Composable
private fun DeviceRow(
    device: InteractiveDevice,
    onIntensity: (Int) -> Unit,
    onOffset: (Int) -> Unit,
    onTest: () -> Unit,
    onConnect: () -> Unit,
    onDrop: () -> Unit,
    onForget: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceBatteryIcon(device = device, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(device.name.ifBlank { device.deviceId }, style = CoogType.cardTitle, color = Color.White)
                Text(
                    buildString {
                        append(device.kind.ifBlank { "device" })
                        append(" · ")
                        append(device.status.ifBlank { if (device.connected) "connected" else "offline" })
                        if (device.batterySupported && device.batteryPercent >= 0) {
                            append(" · ${device.batteryPercent}%")
                        }
                        append(" · intensity ${device.intensity}%")
                        append(" · offset ${device.offsetMs}ms")
                    },
                    style = CoogType.cardYear,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (device.connected && device.index >= 0) {
                ActionChip("Int −") { onIntensity(-10) }
                ActionChip("Int +") { onIntensity(10) }
                ActionChip("Off −") { onOffset(-50) }
                ActionChip("Off +") { onOffset(50) }
                ActionChip("Test") { onTest() }
                ActionChip("Drop") { onDrop() }
            } else if (device.deviceId.isNotBlank()) {
                ActionChip("Connect") { onConnect() }
                ActionChip("Drop") { onDrop() }
            }
            ActionChip("Remove") { onForget() }
        }
    }
}
