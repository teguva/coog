package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.coog.app.BuildConfig
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

private val TouchClient = BuildConfig.TOUCH_CLIENT

@Composable
fun catalogInset(): Dp {
    if (TouchClient) {
        val w = LocalConfiguration.current.screenWidthDp
        return when {
            w >= 900 -> 28.dp
            w >= 600 -> 20.dp
            else -> 16.dp
        }
    }
    val w = LocalConfiguration.current.screenWidthDp
    return (w * 0.054f).dp
}

private val IconTabs = listOf(BrowseTab.Search)
/** Compact top dock — shorter than mobile bottom dock for 16:9. */
private val NavItemHeightTv = 40.dp
private val NavItemHeightTouch = 44.dp
private val NavBarPadTopTv = 10.dp
private val NavBarPadTopTouch = 10.dp
private val NavFadeHeightTv = 28.dp
private val NavFadeHeightTouch = 16.dp
private val TvDockHeight = 48.dp
/** Same soft white chips for pills + Search/Settings. */
private val NavChipIdle = Color.White.copy(alpha = 0.10f)
private val NavChipActive = Color.White.copy(alpha = 0.22f)
private val NavFocusedContent = Color(0xFF121214)

private val NavItemHeight: Dp get() = if (TouchClient) NavItemHeightTouch else NavItemHeightTv
private val NavBarPadTop: Dp get() = if (TouchClient) NavBarPadTopTouch else NavBarPadTopTv
private val NavFadeHeight: Dp get() = if (TouchClient) NavFadeHeightTouch else NavFadeHeightTv

@Composable
fun topBarHeight(): Dp {
    // Mobile shell lays content below its own header — don't double-pad like TV overlay.
    if (TouchClient) return 8.dp
    return NavBarPadTop + TvDockHeight
}

@Composable
fun topBarOverlayHeight(): Dp {
    if (TouchClient) return 8.dp
    return NavBarPadTop + TvDockHeight + NavFadeHeight
}

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
    if (TouchClient) {
        MobileAppShell(
            tab = tab,
            onTab = onTab,
            showFolders = showFolders,
            adultMode = adultMode,
            activeDownloads = activeDownloads,
            connectedDevices = connectedDevices,
            onRootBack = onRootBack,
            onAdultUnlockGesture = onAdultUnlockGesture,
            content = content,
        )
        return
    }
    var railFocused by remember { mutableStateOf(false) }
    var railIndex by remember { mutableIntStateOf(0) }
    var railFocusNonce by remember { mutableIntStateOf(0) }
    // While >= 0, ignore / bounce focus to this index so Up-into-rail cannot
    // land on Home (or another pill) and switch the tab before Settings/etc.
    var railEntryIndex by remember { mutableIntStateOf(-1) }
    // 0 = first content row (top bar visible); >0 hides top bar while content focused.
    var contentFocusRow by remember { mutableIntStateOf(0) }
    val contentFocus = remember { FocusRequester() }
    val showTopBar = railFocused || contentFocusRow <= 0
    val railScope = rememberCoroutineScope()
    // Compact top dock: Home · Browse · Downloads (Library lives inside Browse filters).
    val pillTabs = remember(adultMode) {
        if (adultMode) {
            listOf(BrowseTab.Home, BrowseTab.Folders, BrowseTab.Actors, BrowseTab.Devices)
        } else {
            listOf(BrowseTab.Home, BrowseTab.Movies, BrowseTab.Downloads)
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
    val overlayH = NavBarPadTop + TvDockHeight
    val railFocusedState = rememberUpdatedState(railFocused)
    val tabState = rememberUpdatedState(tab)

    /** During entry lock, only the destination pill may take focus — Home must stay inert. */
    fun railItemAllowFocus(index: Int): Boolean {
        if (TouchClient) return true
        if (!railFocused) return false
        val entry = railEntryIndex
        return entry < 0 || entry == index
    }

    fun showTab(next: BrowseTab) {
        onTab(next)
    }

    fun leaveRail() {
        railFocused = false
        railEntryIndex = -1
        runCatching { contentFocus.requestFocus() }
    }

    fun enterTab(next: BrowseTab) {
        onTab(next)
        railFocused = false
        railEntryIndex = -1
        runCatching { contentFocus.requestFocus() }
    }

    fun enterRail() {
        val idx = railIndexForTab(tabState.value)
        railIndex = idx
        railEntryIndex = idx
        // Show dock immediately and keep entry lock on the *current* tab pill (Browse, not Home).
        contentFocusRow = 0
        // #region agent log
        coogDebug(
            "B",
            "AppShell.kt:enterRail",
            "enterRail",
            mapOf("railIndex" to railIndex, "tab" to tabState.value.name, "nonce" to railFocusNonce),
            runId = "post-fix",
        )
        // #endregion
        railFocused = true
        railFocusNonce += 1
        // canFocus is still false this frame — wait one frame so only the locked pill is focusable,
        // then land there. Immediate requestFocus used to fail and let Home win on recompose.
        railScope.launch {
            withFrameNanos { }
            if (!railFocusedState.value || railEntryIndex != idx) return@launch
            runCatching { railRequesters[idx].requestFocus() }
        }
    }

    fun onRailItemFocused(index: Int, value: BrowseTab) {
        if (TouchClient) {
            railIndex = index
            showTab(value)
            return
        }
        if (!railFocused) {
            runCatching { contentFocus.requestFocus() }
            return
        }
        val entry = railEntryIndex
        if (entry >= 0) {
            if (index != entry) {
                // Bounce stray focus (often Home) back to the locked pill — do not clear
                // the lock here; settle delay in railFocusNonce effect owns that.
                runCatching { railRequesters[entry.coerceIn(0, focusCount - 1)].requestFocus() }
                return
            }
            railIndex = index
            // Entry pill landed — drop the lock so the next Left/Right can reach neighbors
            // (otherwise the first horizontal press is a no-op until the settle timeout).
            railEntryIndex = -1
            return
        }
        railIndex = index
        showTab(value)
    }

    BackHandler(enabled = railFocused || TouchClient) {
        if (TouchClient) {
            if (adultMode) {
                if (tab != BrowseTab.Home) showTab(BrowseTab.Home) else onRootBack()
            } else if (tab != BrowseTab.Home) {
                showTab(BrowseTab.Home)
            } else {
                onRootBack()
            }
            return@BackHandler
        }
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
        contentFocusRow = 0
    }

    LaunchedEffect(railFocusNonce) {
        if (railFocusNonce == 0) return@LaunchedEffect
        val idx = (if (railEntryIndex >= 0) railEntryIndex else railIndex)
            .coerceIn(0, focusCount - 1)
        // Keep lock pinned while we retry + settle — early clear let focus fall to Home
        // and showTab(Home) after Up from Browse.
        railEntryIndex = idx
        var ok = false
        for (attempt in 0 until 16) {
            delay(if (attempt == 0) 40L else 28L)
            if (!railFocusedState.value) return@LaunchedEffect
            railEntryIndex = idx
            ok = runCatching { railRequesters[idx].requestFocus() }.getOrDefault(false)
            if (ok) break
        }
        // Hold the lock longer so dock AnimatedVisibility cannot leave Home selected.
        if (ok) {
            runCatching { railRequesters[idx].requestFocus() }
            delay(450L)
            runCatching { railRequesters[idx].requestFocus() }
        }
        if (railFocusedState.value && railEntryIndex == idx) {
            railEntryIndex = -1
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
        if (TouchClient) return@LaunchedEffect
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
                LocalReportContentFocusRow provides { row -> contentFocusRow = row.coerceAtLeast(0) },
            ) {
                content()
            }
        }
        AnimatedVisibility(
            visible = showTopBar,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f),
            enter = fadeIn(tween(220)) + slideInVertically(tween(280)) { -it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(240)) { -it / 2 },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(overlayH),
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
                                        // Intentional rail move — unlock entry so neighbor pills canFocus.
                                        railEntryIndex = -1
                                        val next = (railIndex + 1).coerceAtMost(focusCount - 1)
                                        runCatching { railRequesters[next].requestFocus() }
                                    }
                                    Key.DirectionLeft -> {
                                        railEntryIndex = -1
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
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.height(TvDockHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                            "entry" to railEntryIndex,
                                        ),
                                    )
                                    // #endregion
                                    onRailItemFocused(index, value)
                                },
                                allowFocus = railItemAllowFocus(index),
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
                                        // Keep non-focusable during rail-entry lock so Up from Settings
                                        // can land on the Settings icon instead of a battery chip.
                                        allowFocus = TouchClient || (railFocused && railEntryIndex < 0),
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
                                onFocused = { onRailItemFocused(index, value) },
                                allowFocus = railItemAllowFocus(index),
                                size = itemHeight,
                            )
                        }
                        NavAvatar(
                            size = itemHeight,
                            requester = railRequesters.last(),
                            onFocused = {
                                onRailItemFocused(focusCount - 1, BrowseTab.Settings)
                            },
                            allowFocus = railItemAllowFocus(focusCount - 1),
                            onClick = {
                                enterTab(BrowseTab.Settings)
                            },
                            onAdultUnlockGesture = if (adultMode) {
                                {}
                            } else {
                                {
                                    railFocused = false
                                    railEntryIndex = -1
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
    BrowseTab.Movies -> "Browse"
    BrowseTab.Series -> "Browse"
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
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(percent = 50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) NavChipActive else NavChipIdle,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = NavFocusedContent,
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = NavFocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = Modifier
            .height(height)
            .focusRequester(requester)
            .focusProperties { canFocus = allowFocus }
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(20.dp),
                )
                if (showIndicator) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(CoogFetch),
                    )
                }
            }
            Text(
                label,
                color = LocalContentColor.current,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
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
            containerColor = if (active) NavChipActive else NavChipIdle,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = NavFocusedContent,
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = NavFocusedContent,
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
            containerColor = NavChipIdle,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = NavFocusedContent,
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = NavFocusedContent,
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
