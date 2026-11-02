package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.coog.app.R
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogFetch

/** Floating dock height (pill only — excludes bottom margin / system inset). */
val MobileBottomBarHeight = 64.dp

/** Floating top chrome (logo + settings) — content draws underneath for full-bleed heroes. */
val MobileTopBarHeight = 52.dp

private val DockShape = RoundedCornerShape(50)
/** Frosted dock — lightly transparent so content peeks through. */
private val DockGlassTop = Color(0xD91A1A1C)
private val DockGlassFill = Color(0xD2141416)
private val DockGlassBottom = Color(0xD90C0C0E)
private val DockGlassStroke = Color.White.copy(alpha = 0.16f)

@Composable
fun mobileStatusBarInset(): Dp {
    val density = LocalDensity.current
    val px = WindowInsets.statusBars.getTop(density)
    return with(density) { px.toDp() }
}

@Composable
fun mobileNavBarInset(): Dp {
    val density = LocalDensity.current
    val px = WindowInsets.navigationBars.getBottom(density)
    return with(density) { px.toDp() }
}

/** Space to keep scroll content clear of the floating glass dock. */
@Composable
fun mobileDockClearance(): Dp = MobileBottomBarHeight + 14.dp + mobileNavBarInset()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileAppShell(
    tab: BrowseTab,
    onTab: (BrowseTab) -> Unit,
    showFolders: Boolean = false,
    adultMode: Boolean = false,
    activeDownloads: Boolean = false,
    connectedDevices: List<InteractiveDevice> = emptyList(),
    onRootBack: () -> Unit = {},
    onAdultUnlockGesture: () -> Unit = {},
    onOpenSearch: (() -> Unit)? = null,
    searchOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val contentFocus = remember { FocusRequester() }
    // Browse stays composed under Movie/Player overlays — only handle Back on the browse surface.
    // Search overlay owns Back while open (registered later in the tree).
    val browseActive = LocalBrowseActive.current
    BackHandler(enabled = browseActive && !searchOpen) {
        if (adultMode) {
            if (tab != BrowseTab.Home) onTab(BrowseTab.Home) else onRootBack()
        } else if (tab != BrowseTab.Home) {
            onTab(BrowseTab.Home)
        } else {
            onRootBack()
        }
    }

    // Settings + Search live in the top bar — dock is content destinations only.
    val bottomTabs = if (adultMode) {
        listOf(BrowseTab.Home, BrowseTab.Folders, BrowseTab.Actors, BrowseTab.Devices)
    } else {
        listOf(
            BrowseTab.Home,
            BrowseTab.Movies,
            BrowseTab.Downloads,
        )
    }
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val dockMaxWidth = minOf(screenW * 0.88f, if (screenW < 600.dp) 400.dp else 480.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep),
    ) {
        // Content draws under the dock so the glass can read as translucent, not a black plate.
        CompositionLocalProvider(
            LocalBrowseContentFocus provides contentFocus,
            LocalRailFocus provides contentFocus,
            LocalNavBarFocused provides false,
            LocalEnterRail provides {},
        ) {
            content()
        }

        if (!searchOpen) {
            LiquidGlassDock(
                tabs = bottomTabs,
                selected = tab,
                adultMode = adultMode,
                activeDownloads = activeDownloads,
                onTab = onTab,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp + mobileNavBarInset())
                    .widthIn(max = dockMaxWidth)
                    .fillMaxWidth(0.88f),
            )
        }

        // Float over content so home billboard / trailer can bleed to the top edge.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(MobileTopBarHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.65f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Transparent,
                    ),
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_coog_logo),
                contentDescription = "Coog",
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = if (adultMode) "Maize" else "Coog",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (adultMode && connectedDevices.isNotEmpty()) {
                connectedDevices.take(3).forEach { device ->
                    DeviceBatteryIcon(
                        device = device,
                        size = 28.dp,
                        onClick = { onTab(BrowseTab.Devices) },
                        allowFocus = false,
                    )
                }
            }
            if (!adultMode && onOpenSearch != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (searchOpen) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            onClick = onOpenSearch,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = if (searchOpen) "Close search" else "Search",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = { onTab(BrowseTab.Settings) },
                        // Same secret entry as TV: hold Settings to unlock Maize with PIN.
                        onLongClick = if (adultMode) {
                            null
                        } else {
                            { onAdultUnlockGesture() }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiquidGlassDock(
    tabs: List<BrowseTab>,
    selected: BrowseTab,
    adultMode: Boolean,
    activeDownloads: Boolean,
    onTab: (BrowseTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .shadow(22.dp, DockShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f), clip = false)
            .clip(DockShape)
            .background(
                Brush.verticalGradient(
                    0f to DockGlassTop,
                    0.45f to DockGlassFill,
                    1f to DockGlassBottom,
                ),
            )
            .border(1.dp, DockGlassStroke, DockShape)
            .height(MobileBottomBarHeight)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { value ->
                val isSelected = selected == value ||
                    (value == BrowseTab.Movies && selected == BrowseTab.Series)
                DockTab(
                    tab = value,
                    selected = isSelected,
                    label = mobileTabLabel(value, adultMode),
                    showDot = value == BrowseTab.Downloads && activeDownloads,
                    onClick = { onTab(value) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockTab(
    tab: BrowseTab,
    selected: Boolean,
    label: String,
    showDot: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val fg = if (selected) Color.White else Color.White.copy(alpha = 0.58f)
    val clickMod = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = true),
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = true),
            onClick = onClick,
        )
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(clickMod)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box {
                Icon(
                    mobileTabIcon(tab, selected = selected),
                    contentDescription = label,
                    tint = fg,
                    modifier = Modifier.size(24.dp),
                )
                if (showDot) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(CoogFetch),
                    )
                }
            }
        }
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun mobileTabIcon(tab: BrowseTab, selected: Boolean): ImageVector = when (tab) {
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

private fun mobileTabLabel(tab: BrowseTab, adultMode: Boolean): String = when (tab) {
    BrowseTab.Home -> if (adultMode) "Maize" else "Home"
    BrowseTab.Movies -> "Browse"
    BrowseTab.Series -> "Series"
    BrowseTab.Folders -> "Library"
    BrowseTab.Actors -> "Actors"
    BrowseTab.Downloads -> "Downloads"
    BrowseTab.Devices -> "Devices"
    BrowseTab.Search -> "Search"
    BrowseTab.Settings -> "Settings"
}
