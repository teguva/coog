package tv.coog.app.ui

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.interactive.LocalIntiface
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

/**
 * Mobile Devices tab talks to Intiface Central on this phone (BLE).
 * TV DevicesScreen still uses the Coog server intiface-engine.
 */
@Composable
fun DevicesScreen(
    @Suppress("UNUSED_PARAMETER") serverUrl: String,
    @Suppress("UNUSED_PARAMETER") token: String,
    @Suppress("UNUSED_PARAMETER") adultSession: String,
) {
    val inset = catalogInset()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val intiface = remember { LocalIntiface.get(context) }
    val engine by intiface.engine.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Keep attempting Intiface Central while this tab is visible.
    LaunchedEffect(Unit) {
        intiface.startMaintainer()
        while (true) {
            intiface.refreshBatteries()
            delay(5_000)
        }
    }

    fun runAction(block: () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onFailure { error = it.message }
            busy = false
        }
    }

    val list = remember(engine) {
        engine.devices
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
                append("Intiface Central (this phone)")
                append(" · ")
                append(if (engine.connected) "linked" else "not linked")
                if (engine.scanning) append(" · scanning")
                if (engine.pairing) append(" · pairing ${engine.pairingSecondsLeft}s")
            },
            style = CoogType.cardYear,
            color = CoogTextMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (!engine.connected) {
            Text(
                "Start Intiface Central on this phone with Bluetooth LE enabled, then tap Scan.",
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
            ActionChip("Scan", firstFocus) { runAction { intiface.scanStart() } }
            ActionChip("Pair") { runAction { intiface.scanPair() } }
            ActionChip("Reconnect") { runAction { intiface.reconnect() } }
            ActionChip("Stop all") { runAction { intiface.stopAll() } }
        }
        if (busy) {
            Text("Working…", color = CoogTextMuted, style = CoogType.cardYear)
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    Text(
                        if (engine.connected) {
                            "No devices yet. Press Pair and turn on a toy (do not pair in Android Bluetooth settings)."
                        } else {
                            "Waiting for Intiface Central…"
                        },
                        color = CoogTextMuted,
                    )
                }
            }
            items(list, key = { it.deviceId.ifBlank { "${it.index}-${it.name}" } }) { dev ->
                DeviceRow(
                    device = dev,
                    onIntensity = { delta ->
                        if (dev.deviceId.isBlank()) return@DeviceRow
                        runAction {
                            intiface.patchIntensity(dev.deviceId, (dev.intensity + delta).coerceIn(10, 200))
                        }
                    },
                    onOffset = { delta ->
                        if (dev.deviceId.isBlank()) return@DeviceRow
                        runAction {
                            intiface.patchOffset(dev.deviceId, dev.offsetMs + delta)
                        }
                    },
                    onTest = {
                        if (dev.index >= 0) runAction { intiface.test(dev.index) }
                    },
                    onConnect = {
                        runAction { intiface.scanStart() }
                    },
                    onForget = {
                        if (dev.deviceId.isNotBlank()) runAction { intiface.forget(dev.deviceId) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, focus: FocusRequester? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = CoogBgDeep,
        ),
        modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = CoogType.cardYear)
    }
}

@Composable
private fun DeviceRow(
    device: InteractiveDevice,
    onIntensity: (Int) -> Unit,
    onOffset: (Int) -> Unit,
    onTest: () -> Unit,
    onConnect: () -> Unit,
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
            } else if (device.deviceId.isNotBlank()) {
                ActionChip("Connect") { onConnect() }
            }
            ActionChip("Remove") { onForget() }
        }
    }
}
