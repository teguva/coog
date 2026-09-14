package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.coog.app.R
import tv.coog.app.data.InteractiveDevice
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogFetch

enum class BrowseTab { Home, Search, Movies, Series, Folders, Actors, Downloads, Devices, Settings }

val RailWidth = 32.dp

@Composable
fun catalogInset(): Dp {
    val w = LocalConfiguration.current.screenWidthDp
    return (w * 0.054f).dp
}

private val IconTabs = listOf(BrowseTab.Search)
private val CircleBtn = Color.White.copy(alpha = 0.10f)
/** Google TV launcher tab / search / profile control height at xhdpi. */
private val NavItemHeight = 32.dp
private val NavBarPadTop = 8.dp
private val NavFadeHeight = 28.dp

@Composable
fun topBarHeight(): Dp = NavBarPadTop + NavItemHeight

@Composable
fun topBarOverlayHeight(): Dp = NavBarPadTop + NavItemHeight + NavFadeHeight

@Composable
fun AppShell(
    tab: BrowseTab,
    onTab: (BrowseTab) -> Unit,
    showFolders: Boolean = false,
    adultMode: Boolean = false,
    activeDownloads: Boolean = false,
    enterRailRequest: Int = 0,
    connectedDevices: List<InteractiveDevice> = emptyList(),
    onRootBack: () -> Unit = {},
    onAdultUnlockGesture: () -> Unit = {},
    onAdultLock: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var railFocused by remember { mutableStateOf(false) }
    var railIndex by remember { mutableIntStateOf(0) }
    var railFocusNonce by remember { mutableIntStateOf(0) }
    val contentFocus = remember { FocusRequester() }
    val pillTabs = remember(showFolders, adultMode) {
        if (adultMode) {
            listOf(BrowseTab.Home, BrowseTab.Folders, BrowseTab.Actors, BrowseTab.Devices)
        } else {
            buildList {
                add(BrowseTab.Home)
                add(BrowseTab.Movies)
                add(BrowseTab.Series)
                if (showFolders) add(BrowseTab.Folders)
                add(BrowseTab.Downloads)
            }
        }
    }
    val iconTabs = if (adultMode) emptyList() else IconTabs
    val railOrder = pillTabs + iconTabs
    val focusCount = railOrder.size + 1
    val railRequesters = remember(focusCount) { List(focusCount) { FocusRequester() } }
    fun railIndexForTab(target: BrowseTab): Int {
        if (target == BrowseTab.Settings) return focusCount - 1
        val idx = railOrder.indexOf(target)
        return if (idx >= 0) idx else 0
    }
    val currentRail = railRequesters[railIndexForTab(tab)]
    val itemHeight = NavItemHeight
    val overlayH = NavBarPadTop + NavItemHeight + NavFadeHeight
    val solidStop = (NavBarPadTop + NavItemHeight) / overlayH
    val railFocusedState = rememberUpdatedState(railFocused)

    fun showTab(next: BrowseTab) {
        onTab(next)
    }

    fun enterTab(next: BrowseTab) {
        onTab(next)
        railFocused = false
        runCatching { contentFocus.requestFocus() }
    }

    fun leaveRail() {
        railFocused = false
        runCatching { contentFocus.requestFocus() }
    }

    fun enterRail() {
        railIndex = railIndexForTab(tab)
        // #region agent log
        coogDebug(
            "B",
            "AppShell.kt:enterRail",
            "enterRail",
            mapOf("railIndex" to railIndex, "tab" to tab.name, "nonce" to railFocusNonce),
            runId = "post-fix",
        )
        // #endregion
        railFocused = true
        railFocusNonce += 1
    }

    BackHandler(enabled = railFocused) {
        if (adultMode) {
            // Stay on the nav until Maize is selected, then ask to leave.
            if (tab != BrowseTab.Home) {
                showTab(BrowseTab.Home)
            } else {
                onRootBack()
            }
            return@BackHandler
        }
        // On Home the nav is the root — Back should leave the app, not just blur the rail.
        if (tab == BrowseTab.Home) {
            onRootBack()
        } else {
            leaveRail()
        }
    }

    LaunchedEffect(enterRailRequest) {
        if (enterRailRequest > 0) enterRail()
    }

    LaunchedEffect(tab, railOrder, focusCount) {
        railIndex = railIndexForTab(tab)
    }

    LaunchedEffect(railFocusNonce) {
        if (railFocusNonce == 0) return@LaunchedEffect
        delay(16)
        val idx = railIndex.coerceIn(0, focusCount - 1)
        var ok = runCatching { railRequesters[idx].requestFocus() }.getOrDefault(false)
        if (!ok) {
            delay(16)
            ok = runCatching { railRequesters[idx].requestFocus() }.getOrDefault(false)
        }
        // #region agent log
        coogDebug(
            "B",
            "AppShell.kt:pendingRailFocus",
            "requestFocus",
            mapOf(
                "idx" to idx,
                "ok" to ok,
                "railFocused" to railFocused,
                "tab" to tab.name,
                "nonce" to railFocusNonce,
            ),
            runId = "post-fix",
        )
        // #endregion
    }

    LaunchedEffect(Unit) {
        repeat(40) {
            delay(80)
            if (railFocusedState.value) return@LaunchedEffect
            if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        // Empty / fault screens may have nothing focusable — open the nav so Settings is reachable.
        if (!railFocusedState.value) {
            enterRail()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalBrowseContentFocus provides contentFocus,
                LocalRailFocus provides currentRail,
                LocalNavBarFocused provides railFocused,
                LocalEnterRail provides ::enterRail,
            ) {
                content()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(overlayH)
                .zIndex(2f)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to CoogBgDeep,
                            solidStop to CoogBgDeep,
                            (solidStop + (1f - solidStop) * 0.45f) to CoogBgDeep.copy(alpha = 0.55f),
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(overlayH)
                .onPreviewKeyEvent { event ->
                    val dpad = event.key == Key.DirectionRight ||
                        event.key == Key.DirectionLeft ||
                        event.key == Key.DirectionDown ||
                        event.key == Key.DirectionUp
                    if (!dpad) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionDown -> leaveRail()
                            Key.DirectionRight -> {
                                val next = (railIndex + 1).coerceAtMost(focusCount - 1)
                                runCatching { railRequesters[next].requestFocus() }
                            }
                            Key.DirectionLeft -> {
                                val prev = (railIndex - 1).coerceAtLeast(0)
                                runCatching { railRequesters[prev].requestFocus() }
                            }
                            else -> {}
                        }
                    }
                    event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                }
                .padding(
                    start = catalogInset(),
                    end = catalogInset(),
                    top = NavBarPadTop,
                    bottom = NavFadeHeight,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_coog_logo),
                contentDescription = "Coog",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(itemHeight),
            )
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pillTabs.forEachIndexed { index, value ->
                    NavPill(
                        value = value,
                        icon = tabIcon(value, selected = tab == value),
                        label = if (adultMode && value == BrowseTab.Home) "Maize" else tabLabel(value),
                        selected = tab,
                        onTab = ::enterTab,
                        requester = railRequesters[index],
                        onFocused = {
                            // #region agent log
                            coogDebug(
                                "C",
                                "AppShell.kt:NavPill.onFocused",
                                "pill focused",
                                mapOf(
                                    "tab" to value.name,
                                    "railFocused" to railFocused,
                                    "bounce" to !railFocused,
                                ),
                            )
                            // #endregion
                            if (!railFocused) {
                                runCatching { contentFocus.requestFocus() }
                            } else {
                                railIndex = index
                                showTab(value)
                            }
                        },
                        allowFocus = railFocused,
                        height = itemHeight,
                        showIndicator = value == BrowseTab.Downloads && activeDownloads,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (adultMode && connectedDevices.isNotEmpty()) {
                    connectedDevices
                        .take(6)
                        .forEach { device ->
                            DeviceBatteryIcon(
                                device = device,
                                size = (itemHeight.value + 4f).dp,
                                onClick = { enterTab(BrowseTab.Devices) },
                                // Only focusable while the nav bar owns focus — otherwise Up
                                // from content lands on these and Maize tab Left/Right breaks.
                                allowFocus = railFocused,
                            )
                        }
                }
                iconTabs.forEachIndexed { offset, value ->
                    val index = pillTabs.size + offset
                    NavIcon(
                        value = value,
                        icon = tabIcon(value, selected = tab == value),
                        label = tabLabel(value),
                        selected = tab,
                        onTab = ::enterTab,
                        requester = railRequesters[index],
                        onFocused = {
                            if (!railFocused) {
                                runCatching { contentFocus.requestFocus() }
                            } else {
                                railIndex = index
                                showTab(value)
                            }
                        },
                        allowFocus = railFocused,
                        size = itemHeight,
                    )
                }
                NavAvatar(
                    size = itemHeight,
                    requester = railRequesters.last(),
                    onFocused = {
                        if (!railFocused) {
                            runCatching { contentFocus.requestFocus() }
                        } else {
                            railIndex = focusCount - 1
                            showTab(BrowseTab.Settings)
                        }
                    },
                    allowFocus = railFocused,
                    onClick = {
                        enterTab(BrowseTab.Settings)
                    },
                    onAdultUnlockGesture = if (adultMode) {
                        {}
                    } else {
                        {
                            railFocused = false
                            onAdultUnlockGesture()
                        }
                    },
                    contentDescription = "Settings",
                )
            }
        }
        }
    }
}

private fun tabIcon(tab: BrowseTab, selected: Boolean): ImageVector = when (tab) {
    BrowseTab.Home -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    BrowseTab.Movies -> Icons.Outlined.Movie
    BrowseTab.Series -> Icons.Outlined.Tv
    BrowseTab.Folders -> Icons.Outlined.Folder
    BrowseTab.Actors -> Icons.Outlined.Person
    BrowseTab.Downloads -> Icons.Outlined.Download
    BrowseTab.Devices -> Icons.Outlined.Speaker
    BrowseTab.Search -> Icons.Outlined.Search
    BrowseTab.Settings -> Icons.Outlined.Settings
}

private fun tabLabel(tab: BrowseTab): String = when (tab) {
    BrowseTab.Home -> "Home"
    BrowseTab.Movies -> "Movies"
    BrowseTab.Series -> "Series"
    BrowseTab.Folders -> "Library"
    BrowseTab.Actors -> "Actors"
    BrowseTab.Downloads -> "Downloads"
    BrowseTab.Devices -> "Devices"
    BrowseTab.Search -> "Search"
    BrowseTab.Settings -> "Settings"
}

@Composable
private fun NavPill(
    value: BrowseTab,
    icon: ImageVector,
    label: String,
    selected: BrowseTab,
    onTab: (BrowseTab) -> Unit,
    requester: FocusRequester,
    onFocused: () -> Unit,
    allowFocus: Boolean,
    height: Dp,
    showIndicator: Boolean = false,
) {
    val active = selected == value
    Surface(
        onClick = { onTab(value) },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Color.White.copy(alpha = 0.14f) else CircleBtn,
            contentColor = Color.White.copy(alpha = if (active) 0.92f else 0.70f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF121214),
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = Color(0xFF121214),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        modifier = Modifier
            .height(height)
            .focusRequester(requester)
            .focusProperties { canFocus = allowFocus }
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                Icon(icon, contentDescription = label, tint = LocalContentColor.current, modifier = Modifier.size(16.dp))
                if (showIndicator) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 0.dp, end = 0.dp)
                            .size(7.dp)
                            .background(CoogFetch, CircleShape),
                    )
                }
            }
            Text(label, color = LocalContentColor.current, fontSize = 14.sp)
        }
    }
}

@Composable
private fun NavIcon(
    value: BrowseTab,
    icon: ImageVector,
    label: String,
    selected: BrowseTab,
    onTab: (BrowseTab) -> Unit,
    requester: FocusRequester,
    onFocused: () -> Unit,
    allowFocus: Boolean,
    size: Dp,
) {
    val active = selected == value
    Surface(
        onClick = { onTab(value) },
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Color.White.copy(alpha = 0.18f) else CircleBtn,
            contentColor = if (active) Color.White else Color.White.copy(alpha = 0.82f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF121214),
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = Color(0xFF121214),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        modifier = Modifier
            .size(size)
            .focusRequester(requester)
            .focusProperties { canFocus = allowFocus }
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = LocalContentColor.current, modifier = Modifier.size(size * 0.48f))
        }
    }
}

@Composable
private fun NavAvatar(
    size: Dp,
    requester: FocusRequester,
    onFocused: () -> Unit,
    allowFocus: Boolean,
    onClick: () -> Unit,
    onAdultUnlockGesture: () -> Unit = {},
    contentDescription: String = "Profile",
) {
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }
    Surface(
        onClick = {
            if (longPressFired) {
                longPressFired = false
                return@Surface
            }
            onClick()
        },
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF3A3A40),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF121214),
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = Color(0xFF121214),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        modifier = Modifier
            .size(size)
            .focusRequester(requester)
            .focusProperties { canFocus = allowFocus }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                val ok = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!ok || !allowFocus) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        // Ignore key-repeat while a hold timer is already running.
                        if (holdJob?.isActive == true) return@onPreviewKeyEvent true
                        longPressFired = false
                        holdJob = scope.launch {
                            delay(5_000)
                            longPressFired = true
                            onAdultUnlockGesture()
                        }
                        false
                    }
                    KeyEventType.KeyUp -> {
                        holdJob?.cancel()
                        holdJob = null
                        false
                    }
                    else -> false
                }
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = contentDescription,
                tint = LocalContentColor.current,
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}
