package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

private data class ChipOpt(val id: String, val label: String)

private val FilterOpts = listOf(
    ChipOpt("", "All"),
    ChipOpt("scripted", "Scripted"),
    ChipOpt("meta", "With meta"),
    ChipOpt("nometa", "No meta"),
)

private val SortOpts = listOf(
    ChipOpt("title", "A–Z"),
    ChipOpt("recent", "Recent"),
    ChipOpt("duration", "Longest"),
    ChipOpt("intensity", "Intensity"),
)

private data class AdultHomeShelf(val id: String, val label: String, val items: List<MediaItem>)

private val AdultShelfTitleBlock = 17.dp
private val AdultFocusPad = 8.dp

@Composable
fun AdultHomeScreen(
    continueWatching: List<MediaItem>,
    recentlyAdded: List<MediaItem>,
    library: List<MediaItem>,
    jobs: List<JobItem>,
    loading: Boolean,
    error: String?,
    onOpen: (MediaItem) -> Unit,
    onPlayContinue: (MediaItem) -> Unit = onOpen,
    onClearContinue: (MediaItem) -> Unit = {},
) {
    val inset = catalogInset()
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val hasAny = continueWatching.isNotEmpty() || recentlyAdded.isNotEmpty()
    Box(Modifier.fillMaxSize().background(CoogBgDeep)) {
        when {
            error != null && !hasAny && library.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = topBarHeight() + 6.dp)
                        .padding(inset),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(error, color = Color(0xFFFF8A80))
                }
            }
            loading && !hasAny && library.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = topBarHeight() + 6.dp)
                        .padding(inset),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading…", color = Color.White.copy(alpha = 0.7f))
                }
            }
            !hasAny -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = topBarHeight() + 6.dp)
                        .padding(inset),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (library.isEmpty()) "Nothing in Maize yet." else "Open Library to browse titles.",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            else -> {
                AdultHomeRows(
                    continueWatching = continueWatching,
                    recentlyAdded = recentlyAdded,
                    jobs = jobs,
                    library = library,
                    firstFocus = firstFocus,
                    inset = inset,
                    onOpen = onOpen,
                    onPlayContinue = onPlayContinue,
                    onClearContinue = onClearContinue,
                )
            }
        }
    }
}

@Composable
private fun AdultHomeRows(
    continueWatching: List<MediaItem>,
    recentlyAdded: List<MediaItem>,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    firstFocus: FocusRequester,
    inset: Dp,
    onOpen: (MediaItem) -> Unit,
    onPlayContinue: (MediaItem) -> Unit,
    onClearContinue: (MediaItem) -> Unit,
) {
    val shelves = remember(continueWatching, recentlyAdded) {
        buildList {
            if (continueWatching.isNotEmpty()) {
                add(AdultHomeShelf("continue", "Continue watching", continueWatching))
            }
            if (recentlyAdded.isNotEmpty()) {
                add(AdultHomeShelf("recent", "Recently added", recentlyAdded))
            }
        }
    }
    val pinFocus = remember(shelves.map { it.id }) { List(shelves.size) { FocusRequester() } }
    var focusedRow by remember { mutableIntStateOf(0) }
    var menuItem by remember { mutableStateOf<MediaItem?>(null) }
    val railFocused = LocalNavBarFocused.current
    val browseActive = LocalBrowseActive.current
    val topInset = topBarHeight()
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
            .clipToBounds(),
    ) {
        val viewport = maxHeight
        val gap = 8.dp
        val contentWidth = maxWidth - inset * 2
        val cardMetrics = rememberShelfCardMetrics(contentWidth)
        val heroCardHeight = cardMetrics.heroHeight
        val peekCardHeight = cardMetrics.peekHeight
        val activeH = AdultShelfTitleBlock + AdultFocusPad * 2 + heroCardHeight
        val idleH = AdultShelfTitleBlock + AdultFocusPad * 2 + peekCardHeight
        val prevPeek = (idleH * 0.55f).coerceIn(48.dp, idleH)
        val row0Clearance = if (focusedRow == 0) {
            topBarOverlayHeight() - topBarHeight()
        } else {
            0.dp
        }
        val heights = shelves.mapIndexed { i, _ -> if (i == focusedRow) activeH else idleH }
        val yBefore = heights.take(focusedRow).fold(0.dp) { acc, h -> acc + h + gap }
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
fun AdultLibraryScreen(
    library: List<MediaItem>,
    jobs: List<JobItem>,
    loading: Boolean,
    error: String?,
    onOpen: (MediaItem) -> Unit,
    onLibraryQuery: suspend (filter: String, sort: String) -> List<MediaItem>,
) {
    val inset = catalogInset()
    val scope = rememberCoroutineScope()
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("title") }
    var filteredLibrary by remember { mutableStateOf(library) }
    var libraryBusy by remember { mutableStateOf(false) }

    LaunchedEffect(library) {
        if (filter.isBlank() && sort == "title") {
            filteredLibrary = library
        }
    }

    fun reloadLibrary(nextFilter: String, nextSort: String) {
        filter = nextFilter
        sort = nextSort
        scope.launch {
            libraryBusy = true
            filteredLibrary = runCatching { onLibraryQuery(nextFilter, nextSort) }
                .getOrDefault(filteredLibrary)
            libraryBusy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(top = topBarHeight() + 6.dp),
    ) {
        when {
            error != null && library.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.Center) {
                    Text(error, color = Color(0xFFFF8A80))
                }
            }
            loading && library.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = Color.White.copy(alpha = 0.7f))
                }
            }
            else -> {
                val browseItems = remember(filteredLibrary) { filteredLibrary.libraryBrowseItems() }
                LocalLibraryGrid(
                    items = browseItems,
                    onOpen = onOpen,
                    firstFocus = firstFocus,
                    inset = inset,
                    emptyMessage = "No titles for this filter.",
                    headerOwnsFocus = true,
                    header = {
                        LibraryFilterBlock(
                            inset = 0.dp,
                            filter = filter,
                            sort = sort,
                            libraryBusy = libraryBusy,
                            onFilter = { reloadLibrary(it, sort) },
                            onSort = { reloadLibrary(filter, it) },
                            firstFocus = firstFocus,
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun LocalLibraryGrid(
    items: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    firstFocus: FocusRequester,
    inset: Dp,
    emptyMessage: String = "Nothing in the library yet.",
    headerOwnsFocus: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val contentWidth = LocalConfiguration.current.screenWidthDp.dp - inset * 2
    val shelf = rememberShelfCardMetrics(contentWidth)
    val cardWidth = shelf.peekWidth
    val cardHeight = shelf.peekHeight
    val gap = 16.dp
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val browseActive = LocalBrowseActive.current
    val railFocused = LocalNavBarFocused.current
    val itemKeys = remember(items) {
        items.map { it.playableId().ifBlank { it.id } }
    }
    val itemCount = itemKeys.size
    val cardRequesters = remember(itemKeys) { List(itemCount) { FocusRequester() } }
    var focusedKey by remember { mutableStateOf<Any?>(null) }
    var focusedIndex by remember { mutableIntStateOf(0) }

    fun requesterAt(index: Int): FocusRequester {
        if (!headerOwnsFocus && index == 0) return firstFocus
        return cardRequesters.getOrElse(index) { firstFocus }
    }

    fun focusCard(index: Int) {
        if (index !in 0 until itemCount) return
        focusedIndex = index
        focusedKey = itemKeys.getOrNull(index)
        scope.launch {
            // Header (filters) is grid item 0 when present — scroll past it to the card.
            val gridIndex = index + if (header != null) 1 else 0
            runCatching { gridState.scrollToItem(gridIndex) }
            awaitFrame()
            var ok = runCatching { requesterAt(index).requestFocus() }.getOrDefault(false)
            if (!ok) {
                awaitFrame()
                ok = runCatching { requesterAt(index).requestFocus() }.getOrDefault(false)
            }
        }
    }

    fun focusDefaultLanding() {
        if (headerOwnsFocus) {
            focusedKey = null
            runCatching { firstFocus.requestFocus() }
        } else if (itemCount > 0) {
            focusCard(0)
        }
    }

    // Keep selection when the filter/sort set changes; otherwise land on default.
    LaunchedEffect(itemKeys) {
        val restored = focusedKey?.let { key -> itemKeys.indexOf(key) }?.takeIf { it >= 0 }
        if (restored != null) {
            focusedIndex = restored
        } else {
            focusedKey = null
            if (itemCount > 0 && !headerOwnsFocus) {
                focusedIndex = 0
                focusedKey = itemKeys.getOrNull(0)
            }
        }
    }

    // Restore card focus when Browse becomes active again (overview/player) or when leaving the rail.
    LaunchedEffect(browseActive, railFocused, itemCount) {
        if (!browseActive || railFocused) return@LaunchedEffect
        kotlinx.coroutines.yield()
        awaitFrame()
        val idx = focusedKey?.let { key -> itemKeys.indexOf(key) }?.takeIf { it >= 0 }
        if (idx != null) {
            focusCard(idx)
        } else {
            focusDefaultLanding()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = cardWidth),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = inset),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                header()
            }
        }
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Text(
                    text = emptyMessage,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            itemsIndexed(items, key = { _, item -> item.playableId().ifBlank { item.id } }) { index, item ->
                val key = itemKeys.getOrElse(index) { item.id }
                PosterCard(
                    item = item,
                    title = item.headline(),
                    subtitle = item.supporting(),
                    onClick = { onOpen(item) },
                    onFocused = {
                        focusedIndex = index
                        focusedKey = key
                    },
                    featured = false,
                    // Maize filters own Up→rail. Normal Library: top row may exit to nav.
                    exitUp = !headerOwnsFocus && index < 6,
                    width = cardWidth,
                    height = cardHeight,
                    modifier = Modifier.focusRequester(requesterAt(index)),
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterBlock(
    inset: Dp,
    filter: String,
    sort: String,
    libraryBusy: Boolean,
    onFilter: (String) -> Unit,
    onSort: (String) -> Unit,
    firstFocus: FocusRequester? = null,
) {
    Column(Modifier.padding(start = inset, bottom = 8.dp)) {
        Text(
            text = "Library",
            style = CoogType.shelfTitle,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ChipRow(
            label = "Filter",
            options = FilterOpts,
            selected = filter,
            onSelect = onFilter,
            firstFocus = firstFocus,
            exitUpToRail = true,
        )
        ChipRow(
            label = "Sort",
            options = SortOpts,
            selected = sort,
            onSelect = onSort,
            chipModifier = Modifier.padding(top = 6.dp),
        )
        if (libraryBusy) {
            Text(
                text = "Updating…",
                color = CoogTextMuted,
                style = CoogType.cardYear,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<ChipOpt>,
    selected: String,
    onSelect: (String) -> Unit,
    chipModifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    exitUpToRail: Boolean = false,
) {
    Row(
        chipModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = CoogType.cardYear, color = CoogTextMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.id }) { opt ->
                val on = selected == opt.id
                val isFirst = opt.id == options.first().id
                Surface(
                    onClick = { onSelect(opt.id) },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (on) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = CoogBgDeep,
                    ),
                    modifier = Modifier
                        .then(
                            if (isFirst && firstFocus != null) {
                                Modifier.focusRequester(firstFocus)
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (isFirst && exitUpToRail) {
                                Modifier.exitToRailOnUp(location = "AdultLibrary.filterChip")
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        opt.label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        style = CoogType.cardYear,
                    )
                }
            }
        }
    }
}
