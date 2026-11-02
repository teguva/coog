package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.coog.app.R
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogBgSoft
import tv.coog.app.ui.theme.CoogFetch

/** Bottom nav height used by [topBarHeight]/[shellBottomInset] for content padding. */
val MobileBottomBarHeight = 64.dp

@Composable
fun mobileStatusBarInset(): Dp {
    val density = LocalDensity.current
    val px = WindowInsets.statusBars.getTop(density)
    return with(density) { px.toDp() }
}

@Composable
fun MobileAppShell(
    tab: BrowseTab,
    onTab: (BrowseTab) -> Unit,
    showFolders: Boolean = false,
    adultMode: Boolean = false,
    activeDownloads: Boolean = false,
    connectedDevices: List<InteractiveDevice> = emptyList(),
    onRootBack: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onAdultUnlockGesture: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val contentFocus = remember { FocusRequester() }
    BackHandler {
        if (adultMode) {
            if (tab != BrowseTab.Home) onTab(BrowseTab.Home) else onRootBack()
        } else if (tab != BrowseTab.Home) {
            onTab(BrowseTab.Home)
        } else {
            onRootBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = { onTab(BrowseTab.Settings) },
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            CompositionLocalProvider(
                LocalBrowseContentFocus provides contentFocus,
                LocalRailFocus provides contentFocus,
                LocalNavBarFocused provides false,
                LocalEnterRail provides {},
            ) {
                content()
            }
        }

        NavigationBar(
            containerColor = CoogBgSoft,
            contentColor = Color.White,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets.navigationBars,
        ) {
            // Settings lives in the top bar — dock is content destinations only.
            val bottomTabs = if (adultMode) {
                listOf(BrowseTab.Home, BrowseTab.Folders, BrowseTab.Actors, BrowseTab.Devices)
            } else {
                listOf(
                    BrowseTab.Home,
                    BrowseTab.Search,
                    BrowseTab.Movies,
                    BrowseTab.Downloads,
                )
            }
            bottomTabs.forEach { value ->
                NavigationBarItem(
                    selected = tab == value || (value == BrowseTab.Movies && tab == BrowseTab.Series),
                    onClick = { onTab(value) },
                    icon = {
                        Box {
                            Icon(
                                mobileTabIcon(value, selected = tab == value || (value == BrowseTab.Movies && tab == BrowseTab.Series)),
                                contentDescription = mobileTabLabel(value, adultMode),
                            )
                            if (value == BrowseTab.Downloads && activeDownloads) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(CoogFetch),
                                )
                            }
                        }
                    },
                    label = {
                        Text(
                            mobileTabLabel(value, adultMode),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color.White.copy(alpha = 0.14f),
                        unselectedIconColor = Color.White.copy(alpha = 0.55f),
                        unselectedTextColor = Color.White.copy(alpha = 0.55f),
                    ),
                )
            }
        }
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
    BrowseTab.Movies -> "Movies"
    BrowseTab.Series -> "Series"
    BrowseTab.Folders -> "Library"
    BrowseTab.Actors -> "Actors"
    BrowseTab.Downloads -> "Downloads"
    BrowseTab.Devices -> "Devices"
    BrowseTab.Search -> "Search"
    BrowseTab.Settings -> "Settings"
}
