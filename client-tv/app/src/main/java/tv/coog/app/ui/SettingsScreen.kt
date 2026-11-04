package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.data.DevicePrefs
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogDanger
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType
import tv.coog.app.update.UpdateUiState

private enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Connection("Connection", "Server URL and access token", Icons.Outlined.Link),
    Device("Device", "Trailer sound, trim, and hero art", Icons.Outlined.Settings),
    Downloads("Downloads", "Queue a yt-dlp source", Icons.Outlined.CloudDownload),
    App("App & updates", "Version and GitHub releases", Icons.Outlined.SystemUpdate),
    About("About", "Server status and build info", Icons.Outlined.Info),
}

@Composable
fun SettingsScreen(
    serverUrl: String,
    token: String,
    update: UpdateUiState,
    health: String? = null,
    devicePrefs: DevicePrefs = DevicePrefs(),
    onDevicePrefsChange: (DevicePrefs) -> Unit = {},
    onSave: (String, String) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onQueueDownload: (String) -> Unit,
    queueMessage: String?,
) {
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    var tok by remember(token) { mutableStateOf(token) }
    var sourceUrl by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(SettingsCategory.Connection) }
    // While checking for updates, ignore accidental category-row focus so the
    // App pane is not torn down (that used to dump D-pad onto Sources).
    var pinCategory by remember { mutableStateOf(false) }
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val railFocused = LocalNavBarFocused.current
    val categoryFocus = remember(firstFocus) {
        SettingsCategory.entries.associateWith { cat ->
            if (cat == SettingsCategory.Connection) firstFocus else FocusRequester()
        }
    }
    val detailFocus = remember { FocusRequester() }
    val inset = catalogInset()

    // Only steal focus when the user entered Settings (rail dismissed). Browsing
    // onto the Settings gear while the nav is focused must keep focus on the rail.
    LaunchedEffect(railFocused) {
        if (railFocused) return@LaunchedEffect
        runCatching { firstFocus.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(start = inset, top = topBarHeight() + 8.dp, end = inset, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Settings",
                style = CoogType.screenTitle,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SettingsCategory.entries.forEachIndexed { index, item ->
                val requester = categoryFocus.getValue(item)
                SettingsCategoryRow(
                    category = item,
                    selected = category == item,
                    onSelect = { category = item },
                    modifier = Modifier
                        .focusRequester(requester)
                        .focusProperties {
                            right = detailFocus
                            // Only the first category may leave toward the top nav.
                            up = if (index == 0) FocusRequester.Cancel else FocusRequester.Default
                            down = if (index == SettingsCategory.entries.lastIndex) {
                                FocusRequester.Cancel
                            } else {
                                FocusRequester.Default
                            }
                        }
                        .then(
                            if (index == 0) {
                                Modifier.exitToRailOnUp(location = "SettingsScreen.topCategory")
                            } else {
                                Modifier
                            },
                        )
                        .onFocusChanged {
                            if (it.isFocused && !pinCategory) category = item
                        },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "← categories   ·   details →",
                style = CoogType.cardYear,
                color = CoogTextMuted.copy(alpha = 0.55f),
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusProperties {
                    left = categoryFocus.getValue(category)
                }
                .verticalScroll(rememberScrollState())
                .padding(end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(category.title, style = CoogType.shelfTitle)
            Text(
                category.subtitle,
                style = CoogType.heroPlot,
                color = CoogTextMuted,
                modifier = Modifier.widthIn(max = 720.dp).padding(bottom = 4.dp),
            )
            when (category) {
                SettingsCategory.Connection -> ConnectionPane(
                    url = url,
                    tok = tok,
                    serverUrl = serverUrl,
                    onUrl = { url = it },
                    onTok = { tok = it },
                    onSave = onSave,
                    firstFieldFocus = detailFocus,
                    upTarget = categoryFocus.getValue(category),
                )
                SettingsCategory.Device -> DevicePrefsPane(
                    prefs = devicePrefs,
                    onChange = onDevicePrefsChange,
                    firstFocus = detailFocus,
                    upTarget = categoryFocus.getValue(category),
                )
                SettingsCategory.Downloads -> DownloadsPane(
                    sourceUrl = sourceUrl,
                    onSourceUrl = { sourceUrl = it },
                    queueMessage = queueMessage,
                    onQueue = onQueueDownload,
                    firstFieldFocus = detailFocus,
                    upTarget = categoryFocus.getValue(category),
                )
                SettingsCategory.App -> AppPane(
                    update = update,
                    onCheckUpdate = onCheckUpdate,
                    onInstallUpdate = onInstallUpdate,
                    firstFocus = detailFocus,
                    upTarget = categoryFocus.getValue(category),
                    onPinCategory = { pinCategory = it },
                    onStayOnApp = { category = SettingsCategory.App },
                )
                SettingsCategory.About -> AboutPane(
                    update = update,
                    health = health,
                    serverUrl = serverUrl,
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoogClickable(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = when {
            selected -> Color.White.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                category.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(category.title, style = CoogType.cardTitle, color = Color.White)
                Text(
                    category.subtitle,
                    style = CoogType.cardYear,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ConnectionPane(
    url: String,
    tok: String,
    serverUrl: String,
    onUrl: (String) -> Unit,
    onTok: (String) -> Unit,
    onSave: (String, String) -> Unit,
    firstFieldFocus: FocusRequester,
    upTarget: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Tell the TV where Coog is running. Emulator: http://10.0.2.2:8090. A real Google TV needs the server LAN IP.",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        FieldLabel("Server URL")
        TvTextField(
            value = url,
            onValueChange = onUrl,
            placeholder = "http://192.168.1.10:8090",
            uri = true,
            exitUp = false,
            modifier = Modifier
                .focusRequester(firstFieldFocus)
                .focusProperties {
                    up = upTarget
                    left = upTarget
                },
        )
        FieldLabel("Bearer token")
        TvTextField(
            value = tok,
            onValueChange = onTok,
            placeholder = "Required if the server sets COOG_AUTH_TOKEN",
            password = true,
            exitUp = false,
        )
        WhitePill(label = "Save", onClick = { onSave(url, tok) })
        Text(
            if (serverUrl.isNotBlank()) "Saved · $serverUrl" else "Not connected yet",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
        )
    }
}

@Composable
private fun DownloadsPane(
    sourceUrl: String,
    onSourceUrl: (String) -> Unit,
    queueMessage: String?,
    onQueue: (String) -> Unit,
    firstFieldFocus: FocusRequester,
    upTarget: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Paste a YouTube or other yt-dlp URL. It appears in Downloads and can play before the file finishes.",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        FieldLabel("Source URL")
        TvTextField(
            value = sourceUrl,
            onValueChange = onSourceUrl,
            placeholder = "https://…",
            uri = true,
            exitUp = false,
            modifier = Modifier
                .focusRequester(firstFieldFocus)
                .focusProperties {
                    up = upTarget
                    left = upTarget
                },
        )
        queueMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, style = CoogType.heroPlot, color = Color(0xFFFF8B8B), modifier = Modifier.widthIn(max = 720.dp))
        }
        WhitePill(label = "Queue download", onClick = {
            if (sourceUrl.isNotBlank()) onQueue(sourceUrl)
        })
    }
}

@Composable
private fun AppPane(
    update: UpdateUiState,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    firstFocus: FocusRequester,
    upTarget: FocusRequester,
    onPinCategory: (Boolean) -> Unit = {},
    onStayOnApp: () -> Unit = {},
) {
    // Check button must stay mounted: when an update appears the old tree was
    // disposed and D-pad focus fell back to the category list.
    val checkFocus = remember { FocusRequester() }
    var restoreFocus by remember { mutableStateOf(false) }
    val available = update.available

    LaunchedEffect(update.checking, available?.versionCode, update.installing, restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        onStayOnApp()
        onPinCategory(true)
        // Retry: the Check button may still be recomposing after status text appears.
        var held = false
        repeat(12) {
            delay(40)
            onStayOnApp()
            held = runCatching { checkFocus.requestFocus() }.getOrDefault(false)
            if (held) return@repeat
        }
        if (!update.checking) {
            restoreFocus = false
            onPinCategory(false)
            if (!held) {
                // Last resort: entry focus in this pane (Update pill or Check).
                runCatching { firstFocus.requestFocus() }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { onPinCategory(false) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Installed Coog ${update.currentVersion} (${update.currentCode}). New APKs come from GitHub Releases. Use the GitHub/release build for updates — Studio debug installs use a different signing key and cannot update in place.",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        val status = update.error ?: update.message
        if (status.isNotBlank()) {
            Text(status, style = CoogType.heroPlot, modifier = Modifier.widthIn(max = 720.dp))
        }
        if (available != null) {
            WhitePill(
                label = if (update.installing) {
                    val pct = update.progress?.let { "${(it * 100).toInt()}%" }
                    if (pct != null) "Updating $pct" else "Updating…"
                } else {
                    "Update to ${available.versionName}"
                },
                onClick = { if (!update.installing) onInstallUpdate() },
                modifier = Modifier
                    .focusRequester(firstFocus)
                    .focusProperties {
                        up = upTarget
                        left = upTarget
                        down = checkFocus
                    },
            )
        }
        GhostButton(
            label = if (update.checking) "Checking…" else "Check for updates",
            onClick = {
                if (!update.checking && !update.installing) {
                    onStayOnApp()
                    onPinCategory(true)
                    restoreFocus = true
                    onCheckUpdate()
                }
            },
            modifier = Modifier
                .focusRequester(checkFocus)
                .then(
                    if (available == null) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                )
                .focusProperties {
                    up = if (available != null) firstFocus else upTarget
                    left = upTarget
                },
        )
    }
}

@Composable
private fun AboutPane(
    update: UpdateUiState,
    health: String?,
    serverUrl: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AboutRow("App version", "${update.currentVersion} (${update.currentCode})")
        AboutRow("Server", serverUrl.ifBlank { "Not connected" })
        if (!health.isNullOrBlank()) {
            Text("Server status", style = CoogType.chip, color = CoogTextMuted)
            Text(
                health,
                style = CoogType.heroPlot,
                color = CoogTextMuted,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        } else {
            AboutRow("Server status", "Unavailable")
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = CoogType.chip, color = CoogTextMuted)
        Text(value, style = CoogType.cardTitle, color = Color.White)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = CoogType.chip, color = CoogTextMuted)
}

@Composable
private fun DevicePrefsPane(
    prefs: DevicePrefs,
    onChange: (DevicePrefs) -> Unit,
    firstFocus: FocusRequester,
    upTarget: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FieldLabel("Home trailer sound")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                label = "On",
                selected = prefs.trailerSound == "on",
                onClick = { onChange(prefs.copy(trailerSound = "on")) },
                modifier = Modifier
                    .focusRequester(firstFocus)
                    .focusProperties { up = upTarget; left = upTarget },
            )
            FilterChip(
                label = "Soft",
                selected = prefs.trailerSound == "soft",
                onClick = { onChange(prefs.copy(trailerSound = "soft")) },
            )
            FilterChip(
                label = "Off",
                selected = prefs.trailerSound == "off",
                onClick = { onChange(prefs.copy(trailerSound = "off")) },
            )
        }
        Text(
            "This device only. Soft is ~15% volume; On matches player volume. Applies immediately.",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        FieldLabel("Skip trailer start")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 10, 15, 20, 30).forEach { sec ->
                FilterChip(
                    label = if (sec == 0) "0s" else "${sec}s",
                    selected = prefs.trailerSkipStartSec == sec,
                    onClick = { onChange(prefs.copy(trailerSkipStartSec = sec)) },
                )
            }
        }
        FieldLabel("Skip trailer end")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 5, 10, 15, 20).forEach { sec ->
                FilterChip(
                    label = if (sec == 0) "0s" else "${sec}s",
                    selected = prefs.trailerSkipEndSec == sec,
                    onClick = { onChange(prefs.copy(trailerSkipEndSec = sec)) },
                )
            }
        }
        Text(
            "Cuts intros and outros on Home preview trailers.",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        FieldLabel("Large backdrops (hero / focus)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                label = "1080p",
                selected = prefs.preferredBackdropMax == "1080p",
                onClick = { onChange(prefs.copy(preferredBackdropMax = "1080p")) },
            )
            FilterChip(
                label = "1440p",
                selected = prefs.preferredBackdropMax == "1440p",
                onClick = { onChange(prefs.copy(preferredBackdropMax = "1440p")) },
            )
            FilterChip(
                label = "4K",
                selected = prefs.preferredBackdropMax == "2160p",
                onClick = { onChange(prefs.copy(preferredBackdropMax = "2160p")) },
            )
        }
        Text(
            "Row cards stay small; heroes use this cap. Prefer 1080p on non‑4K TVs or slow Wi‑Fi.",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Text(
            "Source pick rules (quality, packs, RD+) are configured in the Coog admin Streaming page.",
            style = CoogType.heroPlot,
            color = CoogTextMuted,
            modifier = Modifier.widthIn(max = 720.dp),
        )
    }
}
