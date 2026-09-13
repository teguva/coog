package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.data.CoogApi
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.data.InteractiveEngineState
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

@Composable
fun DevicesScreen(
    serverUrl: String,
    token: String,
    adultSession: String,
) {
    val inset = catalogInset()
    val scope = rememberCoroutineScope()
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    var engine by remember { mutableStateOf(InteractiveEngineState()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun api() = CoogApi(serverUrl, token, adultSession)

    fun refresh() {
        scope.launch {
            runCatching { api().interactiveEngine() }
                .onSuccess {
                    engine = it
                    error = null
                }
                .onFailure { error = it.message }
        }
    }

    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onFailure { error = it.message }
            refresh()
            busy = false
        }
    }

    LaunchedEffect(serverUrl, adultSession) {
        while (true) {
            refresh()
            delay(2000)
        }
    }

    val list = remember(engine) {
        engine.trustedDevices.ifEmpty { engine.devices + engine.knownDevices }
            .distinctBy { it.deviceId.ifBlank { it.name + it.index } }
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
                append(if (engine.running) "Engine on" else "Engine off")
                append(" · ")
                append(if (engine.connected) "linked" else "not linked")
                if (engine.scanning) append(" · scanning")
                if (engine.pairing) append(" · pairing ${engine.pairingSecondsLeft}s")
                if (engine.reconnectPhase.isNotBlank() && engine.reconnectPhase != "idle") {
                    append(" · ${engine.reconnectPhase}")
                    if (engine.reconnectReason.isNotBlank()) append(" (${engine.reconnectReason})")
                    if (engine.missingPaired > 0) append(" · missing ${engine.missingPaired}")
                }
            },
            style = CoogType.cardYear,
            color = CoogTextMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (!error.isNullOrBlank()) {
            Text(error!!, color = Color(0xFFFF8A80), style = CoogType.cardYear)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            ActionChip("Scan", firstFocus) { runAction { api().interactiveScanStart() } }
            ActionChip("Pair") { runAction { api().interactiveScanPair() } }
            ActionChip("Restart") { runAction { api().interactiveRestart() } }
            ActionChip("Clear offline") { runAction { api().interactiveForgetOffline() } }
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
                    Text("No devices yet. Press Pair and turn on a toy.", color = CoogTextMuted)
                }
            }
            items(list, key = { it.deviceId.ifBlank { "${it.index}-${it.name}" } }) { dev ->
                DeviceRow(
                    device = dev,
                    onIntensity = { delta ->
                        if (dev.index < 0) return@DeviceRow
                        runAction {
                            api().interactivePatch(dev.index, intensity = (dev.intensity + delta).coerceIn(10, 200))
                        }
                    },
                    onOffset = { delta ->
                        if (dev.index < 0) return@DeviceRow
                        runAction {
                            api().interactivePatch(dev.index, offsetMs = dev.offsetMs + delta)
                        }
                    },
                    onTest = {
                        if (dev.index >= 0) runAction { api().interactiveTest(dev.index) }
                    },
                    onConnect = {
                        if (dev.deviceId.isNotBlank()) runAction { api().interactiveConnect(dev.deviceId) }
                    },
                    onForget = {
                        if (dev.deviceId.isNotBlank()) runAction { api().interactiveForget(dev.deviceId) }
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
