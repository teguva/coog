package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogBgSoft
import tv.coog.app.ui.theme.CoogType

/** Matches FeaturedCarousel label + FocusPad so idle peeks share one stable card height. */
private val HomeShelfTitleBlock = 17.dp
private val HomeFocusPad = 8.dp

@Composable
fun HomeScreen(
    tab: BrowseTab,
    loading: Boolean,
    error: String?,
    items: List<MediaItem>,
    jobs: List<JobItem>,
    continueWatching: List<MediaItem>,
    forYou: List<MediaItem> = emptyList(),
    tasteColdStart: Boolean = false,
    trendingMovies: List<MediaItem>,
    trendingSeries: List<MediaItem>,
    onOpenMovie: (MediaItem) -> Unit,
    onOpenFolder: (FolderRow) -> Unit,
    onPlayContinue: (MediaItem) -> Unit = {},
    onClearContinue: (MediaItem) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val enterRail = LocalEnterRail.current

    val hasContent = when (tab) {
        BrowseTab.Folders -> items.isNotEmpty()
        else -> continueWatching.isNotEmpty() || forYou.isNotEmpty() ||
            trendingMovies.isNotEmpty() || trendingSeries.isNotEmpty()
    }

    val inset = catalogInset()
    val pad = Modifier.padding(start = inset, top = topBarHeight() + 6.dp, end = inset)
    when {
        error != null && !hasContent -> {
            BrowseFaultPane(
                title = error,
                body = "Open Settings and set the server to this PC's LAN IP on port 8090.",
                firstFocus = firstFocus,
                enterRail = enterRail,
                actionLabel = "Open Settings",
                onAction = onOpenSettings,
                titleIsError = true,
                modifier = pad,
            )
        }
        loading -> {
            BrowseFaultPane(
                title = "One moment.",
                body = "Press ↑ for the menu if this takes too long.",
                firstFocus = firstFocus,
                enterRail = enterRail,
                modifier = pad,
            )
        }
        !hasContent -> {
            BrowseFaultPane(
                title = "Nothing to show yet",
                body = if (tasteColdStart) {
                    "Watch a few titles so Match can learn your taste. Trending still works meanwhile."
                } else {
                    "Trending titles appear here once the server can reach TMDB. Press ↑ for Settings if the server URL is wrong."
                },
                firstFocus = firstFocus,
                enterRail = enterRail,
                actionLabel = "Open Settings",
                onAction = onOpenSettings,
                modifier = pad,
            )
        }
        tab == BrowseTab.Folders -> {
            val browseItems = remember(items) { items.libraryBrowseItems() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(CoogBgDeep)
                    .padding(top = topBarHeight() + 6.dp),
            ) {
                LocalLibraryGrid(
                    items = browseItems,
                    onOpen = onOpenMovie,
                    firstFocus = firstFocus,
                    inset = inset,
                    emptyMessage = "Nothing in the library yet.",
                    headerOwnsFocus = false,
                    header = {
                        Text(
                            text = "Library",
                            style = CoogType.shelfTitle,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    },
                )
            }
        }
        else -> {
            HomeRows(
                continueWatching = continueWatching,
                forYou = forYou,
                movies = trendingMovies,
                series = trendingSeries,
                jobs = jobs,
                library = items,
                firstFocus = firstFocus,
                inset = inset,
                onOpen = onOpenMovie,
                onPlayContinue = onPlayContinue,
                onClearContinue = onClearContinue,
            )
        }
    }
}

@Composable
private fun HomeRows(
    continueWatching: List<MediaItem>,
    forYou: List<MediaItem>,
    movies: List<MediaItem>,
    series: List<MediaItem>,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    firstFocus: FocusRequester,
    inset: Dp,
    onOpen: (MediaItem) -> Unit,
    onPlayContinue: (MediaItem) -> Unit,
    onClearContinue: (MediaItem) -> Unit,
) {
    val shelves = remember(continueWatching, forYou, movies, series) {
        buildList {
            if (continueWatching.isNotEmpty()) {
                add(HomeShelf("continue", "Continue watching", continueWatching))
            }
            if (forYou.isNotEmpty()) {
                add(HomeShelf("foryou", "For you", forYou))
            }
            if (movies.isNotEmpty()) {
                add(HomeShelf("movies", "Recommended movies", movies))
            }
            if (series.isNotEmpty()) {
                add(HomeShelf("series", "Recommended series", series))
            }
        }
    }
    val pinFocus = remember(shelves.map { it.id }) {
        List(shelves.size) { FocusRequester() }
    }
    var focusedRow by remember { mutableIntStateOf(0) }
    var menuItem by remember { mutableStateOf<MediaItem?>(null) }
    val railFocused = LocalNavBarFocused.current
    val browseActive = LocalBrowseActive.current
    // Pad only under the solid nav strip so AppShell's top fade can blend over the hero
    // (same as Movies/Series). Using overlay height pushed content below the fade and left
    // a hard black edge. Keep this inset fixed so row-0 focus never reflows the viewport.
    val topInset = topBarHeight()
    // After expand/collapse layout, re-assert the shelf focus so Up/Down never leave
    // the home rows without a focused target (which skips shelves on the next press).
    // Also restore focus when returning from overview/player (Browse stays composed).
    LaunchedEffect(focusedRow, shelves.size, railFocused, menuItem, browseActive) {
        if (!browseActive || railFocused || menuItem != null) return@LaunchedEffect
        val target = when {
            focusedRow <= 0 -> firstFocus
            focusedRow < pinFocus.size -> pinFocus[focusedRow]
            else -> return@LaunchedEffect
        }
        kotlinx.coroutines.yield()
        runCatching { target.requestFocus() }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(top = topInset)
            .clipToBounds()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                    coogDebug(
                        "A",
                        "HomeScreen.kt:HomeRows",
                        "shelf up",
                        mapOf(
                            "focusedRow" to focusedRow,
                            "rows" to shelves.size,
                            "continue" to continueWatching.size,
                        ),
                    )
                }
                false
            },
    ) {
        val viewport = maxHeight
        val gap = 8.dp
        val contentWidth = maxWidth - inset * 2
        val cardMetrics = rememberShelfCardMetrics(contentWidth)
        // Card size from the 18-unit band — not from leftover viewport or nav fade.
        val heroCardHeight = cardMetrics.heroHeight
        val peekCardHeight = cardMetrics.peekHeight
        val activeH = HomeShelfTitleBlock + HomeFocusPad * 2 + heroCardHeight
        val idleH = HomeShelfTitleBlock + HomeFocusPad * 2 + peekCardHeight
        // Vertical peeks are display-only; they must not change card metrics.
        val prevPeek = (idleH * 0.55f).coerceIn(48.dp, idleH)
        // Nudge row 0 under the nav fade without shrinking cards.
        val row0Clearance = if (focusedRow == 0) {
            topBarOverlayHeight() - topBarHeight()
        } else {
            0.dp
        }
        val heights = shelves.mapIndexed { i, _ ->
            if (i == focusedRow) activeH else idleH
        }
        val yBefore = heights.take(focusedRow).fold(0.dp) { acc, h -> acc + h + gap }
        // Snap shelf positions — one layout pass per focus change, no per-frame remeasure.
        val offsetY = if (focusedRow == 0) row0Clearance else -(yBefore - prevPeek)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewport)
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .offset(y = offsetY),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
            shelves.forEachIndexed { i, shelf ->
                androidx.compose.runtime.key(shelf.id) {
                    FeaturedCarousel(
                        items = shelf.items,
                        label = shelf.label,
                        onOpen = { item ->
                            if (shelf.id == "continue" && item.isLocal()) onPlayContinue(item)
                            else onOpen(item)
                        },
                        jobs = jobs,
                        library = library,
                        expanded = i == focusedRow,
                        active = i == focusedRow && !railFocused && menuItem == null && browseActive,
                        heroCardHeight = heroCardHeight,
                        peekCardHeight = peekCardHeight,
                        cardMetrics = cardMetrics,
                        onRowFocused = { focusedRow = i },
                        firstFocus = if (i == 0) firstFocus else pinFocus[i],
                        exitUp = i == 0,
                        insetStart = inset,
                        onVerticalMove = { delta ->
                            val next = (focusedRow + delta).coerceIn(0, shelves.lastIndex)
                            if (next != focusedRow) focusedRow = next
                            true
                        },
                        onCardMenu = if (shelf.id == "continue") {
                            { item -> menuItem = item }
                        } else {
                            null
                        },
                        // No horizontal clip — previous peek draws into the left inset
                        // at pin - peek - gap without shifting the hero.
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(heights[i]),
                    )
                }
            }
            }
        }
    }
    menuItem?.let { item ->
        ContinueCardMenu(
            item = item,
            onDismiss = { menuItem = null },
            onResume = {
                menuItem = null
                onPlayContinue(item)
            },
            onMoreInfo = {
                menuItem = null
                onOpen(item)
            },
            onClearProgress = {
                menuItem = null
                onClearContinue(item)
            },
        )
    }
}

@Composable
internal fun ContinueCardMenu(
    item: MediaItem,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    onMoreInfo: () -> Unit,
    onClearProgress: () -> Unit,
) {
    val catchFocus = remember { FocusRequester() }
    val resumeFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    var sawSelectDown by remember { mutableStateOf(false) }
    val detail = when {
        item.season > 0 || item.episode > 0 -> item.episodeHeadline()
        else -> item.heroMetaLine()
    }
    LaunchedEffect(item.id) {
        runCatching { catchFocus.requestFocus() }
        delay(300)
        if (!sawSelectDown && !armed) {
            armed = true
        }
    }
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        delay(16)
        runCatching { resumeFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .focusRequester(catchFocus)
                .focusProperties { canFocus = !armed }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (armed) return@onPreviewKeyEvent false
                    if (!event.key.isSelectKey()) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        sawSelectDown = true
                    } else if (event.type == KeyEventType.KeyUp) {
                        armed = true
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .background(CoogBgSoft, RoundedCornerShape(18.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.headline(), style = CoogType.heroTagline)
                if (detail.isNotBlank() && !detail.equals(item.headline(), ignoreCase = true)) {
                    Text(detail, style = CoogType.cardYear)
                }
                WhitePill(
                    label = "Resume",
                    onClick = { if (armed) onResume() },
                    modifier = Modifier
                        .focusRequester(resumeFocus)
                        .focusProperties { canFocus = armed }
                        .fillMaxWidth(),
                )
                GhostButton(
                    label = "More info",
                    onClick = { if (armed) onMoreInfo() },
                    modifier = Modifier
                        .focusProperties { canFocus = armed }
                        .fillMaxWidth(),
                )
                GhostButton(
                    label = "Clear progress",
                    onClick = { if (armed) onClearProgress() },
                    modifier = Modifier
                        .focusProperties { canFocus = armed }
                        .fillMaxWidth(),
                )
            }
        }
    }
}

private fun Key.isSelectKey(): Boolean =
    this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

@Composable
private fun BrowseFaultPane(
    title: String,
    body: String,
    firstFocus: FocusRequester,
    enterRail: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    titleIsError: Boolean = false,
) {
    LaunchedEffect(title, actionLabel) {
        delay(40)
        runCatching { firstFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .then(modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            style = if (titleIsError) CoogType.heroTagline else CoogType.heroTitle,
            color = if (titleIsError) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Text(
            body,
            style = CoogType.heroPlot,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        if (actionLabel != null && onAction != null) {
            WhitePill(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier
                    .focusRequester(firstFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) enterRail()
                        event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                    },
            )
        } else {
            Box(
                modifier = Modifier
                    .focusRequester(firstFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) enterRail()
                        event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                    },
            )
        }
    }
}

private data class HomeShelf(
    val id: String,
    val label: String,
    val items: List<MediaItem>,
)
