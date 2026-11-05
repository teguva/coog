package tv.coog.app.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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

/** Shelf title + breathing room under Continue / Immersive rows. */
private val HomeShelfTitleBlock = 17.dp
/** Symmetric ease — FastOutSlowIn made the up-to-Continue leg look like a snap. */
private val HomeRowScrollMs = 360
/** Hold-scroll: wait before swapping stage backdrop/trailer so the row can catch up. */
private const val HomeStageCommitMs = 420L
/** Trailer autoplay after stage title settles. */
private const val HomeTrailerDelayMs = 900L

/** Trailer crop focus inside the 399dp stage (-1..1; 0 = center). */
private const val HomeTrailerCropBiasX = 0f
private const val HomeTrailerCropBiasY = 0f

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
    onManageLibrary: ((MediaItem) -> Unit)? = null,
    libraryMenuOpen: Boolean = false,
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
                    onCardMenu = onManageLibrary,
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
                onManageLibrary = onManageLibrary,
                libraryMenuOpen = libraryMenuOpen,
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
    onManageLibrary: ((MediaItem) -> Unit)? = null,
    libraryMenuOpen: Boolean = false,
) {
    val (continueItems, discovery) = remember(continueWatching, forYou, movies, series) {
        val seen = mutableSetOf<String>()
        val cw = continueWatching.withoutHomeDuplicates(seen)
        val disc = buildList {
            fun addShelf(id: String, label: String, items: List<MediaItem>) {
                val kept = items.withoutHomeDuplicates(seen)
                if (kept.isNotEmpty()) add(HomeShelf(id, label, kept))
            }
            addShelf("foryou", "For you", forYou)
            addShelf("movies", "Recommended movies", movies)
            addShelf("series", "Recommended series", series)
        }
        cw to disc
    }
    val hasContinue = continueItems.isNotEmpty()
    val rowCount = (if (hasContinue) 1 else 0) + discovery.size
    val discoveryFocus = remember(discovery.map { it.id }) {
        List(discovery.size) { FocusRequester() }
    }
    var focusedRow by remember { mutableIntStateOf(0) }
    var menuItem by remember { mutableStateOf<MediaItem?>(null) }
    var discoverySelected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val railFocused = LocalNavBarFocused.current
    val browseActive = LocalBrowseActive.current
    val reportContentFocusRow = LocalReportContentFocusRow.current

    fun focusRequesterFor(row: Int): FocusRequester? = when {
        hasContinue && row == 0 -> firstFocus
        hasContinue -> discoveryFocus.getOrNull(row - 1)
        row == 0 -> firstFocus
        else -> discoveryFocus.getOrNull(row)
    }

    LaunchedEffect(focusedRow) {
        reportContentFocusRow(focusedRow)
    }

    LaunchedEffect(focusedRow, rowCount, railFocused, menuItem, browseActive, libraryMenuOpen) {
        if (!browseActive || railFocused || menuItem != null || libraryMenuOpen) return@LaunchedEffect
        if (rowCount > 0 && focusedRow > rowCount - 1) focusedRow = rowCount - 1
        val target = focusRequesterFor(focusedRow) ?: return@LaunchedEffect
        kotlinx.coroutines.yield()
        runCatching { target.requestFocus() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            // No top pad — rows scroll under the transparent menu chrome to the screen edge.
            .clipToBounds(),
    ) {
        val viewport = maxHeight
        val gap = 10.dp
        val contentWidth = maxWidth - inset * 2
        val continueMetrics = rememberContinueCardMetrics(contentWidth)
        val immersiveMetrics = rememberImmersiveCardMetrics(continueMetrics.height, continueMetrics.gap)

        // Same card band height for Continue + discovery (title + peekHeight + pad).
        val rowBandH = HomeShelfTitleBlock + continueMetrics.height + 8.dp
        val continueH = rowBandH
        val immersiveRowH = rowBandH

        val forYouShelf = discovery.firstOrNull { it.id == "foryou" }
        val forYouRowIndex = if (hasContinue) {
            discovery.indexOfFirst { it.id == "foryou" }.takeIf { it >= 0 }?.let { it + 1 } ?: -1
        } else {
            discovery.indexOfFirst { it.id == "foryou" }
        }
        val onContinue = hasContinue && focusedRow == 0
        val onForYou = forYouRowIndex >= 0 && focusedRow == forYouRowIndex

        val discoveryRowIndex = when {
            !hasContinue -> focusedRow
            focusedRow <= 0 -> -1
            else -> focusedRow - 1
        }
        val activeDiscovery = discovery.getOrNull(discoveryRowIndex)
        val immersiveItem = activeDiscovery?.let { shelf ->
            val sel = discoverySelected[shelf.id] ?: shelf.items.first().id
            shelf.items.firstOrNull { it.id == sel } ?: shelf.items.firstOrNull()
        }

        // Selection is any For you card; trailerPool is only for Continue teaser rotation.
        val forYouItems = forYouShelf?.items.orEmpty()
        val trailerPool = remember(forYouItems) {
            val playable = forYouItems.filter { it.canPlayTrailer() }
            playable.ifEmpty { forYouItems }
        }
        val forYouIdsKey = remember(forYouItems) { forYouItems.joinToString { it.id } }
        LaunchedEffect(forYouIdsKey, discoverySelected["foryou"]) {
            if (forYouItems.isEmpty()) return@LaunchedEffect
            val cur = discoverySelected["foryou"]
            // Validate against the full shelf — not trailerPool — or non-trailer cards
            // (e.g. 4th item) snap selection back to the first playable title.
            if (cur != null && forYouItems.any { it.id == cur }) return@LaunchedEffect
            discoverySelected = discoverySelected + ("foryou" to forYouItems.first().id)
        }
        fun advanceTeaser() {
            if (trailerPool.isEmpty()) return
            val cur = discoverySelected["foryou"]
            val idx = trailerPool.indexOfFirst { it.id == cur }.let { if (it >= 0) it else 0 }
            val next = trailerPool[(idx + 1) % trailerPool.size]
            discoverySelected = discoverySelected + ("foryou" to next.id)
        }
        // Debounce stage title + trailer when the *stage content* changes — not when
        // hopping Continue ↔ For you (same shared stage / same for-you title).
        var stageDiscoverySelected by remember { mutableStateOf(discoverySelected) }
        var stageSettled by remember { mutableStateOf(false) }
        val trailerStageKey = when {
            hasContinue && focusedRow <= 1 ->
                "shared:${discoverySelected["foryou"].orEmpty()}"
            focusedRow > 0 -> {
                val shelf = discovery.getOrNull(
                    if (hasContinue) focusedRow - 1 else focusedRow,
                )
                val sid = shelf?.id.orEmpty()
                "row$focusedRow:${discoverySelected[sid].orEmpty()}"
            }
            else -> "top:${discoverySelected["foryou"].orEmpty()}"
        }
        LaunchedEffect(trailerStageKey) {
            stageSettled = false
            delay(HomeStageCommitMs)
            stageDiscoverySelected = discoverySelected
            stageSettled = true
        }
        val stageForYouItem = run {
            val id = stageDiscoverySelected["foryou"]
            forYouItems.firstOrNull { it.id == id } ?: forYouItems.firstOrNull()
        }
        val stageImmersiveItem = activeDiscovery?.let { shelf ->
            val sel = stageDiscoverySelected[shelf.id] ?: shelf.items.first().id
            shelf.items.firstOrNull { it.id == sel } ?: shelf.items.firstOrNull()
        }
        // Continue + For you share the same staged title (horizontal For you pick wins).
        val stageItem = when {
            onContinue || onForYou -> stageForYouItem
            focusedRow > 0 -> stageImmersiveItem
            else -> null
        }
        val showTrailerStage = stageItem != null
        val onSharedStage = hasContinue && focusedRow <= 1

        // Continue: under top bar. Discovery focus: prev peeks at top, focused at bottom.
        val row0Clearance = topBarHeight() + 2.dp
        val cardH = continueMetrics.height
        val cardPeek = cardH * HomeNeighbourCardFrac
        val rowBottomPad = 8.dp
        // From row bottom: pad under cards + visible slice of the poster.
        val prevPeek = rowBottomPad + cardPeek
        // From row top: title block + visible slice of the poster.
        val nextPeek = HomeShelfTitleBlock + cardPeek

        val heights = buildList {
            if (hasContinue) add(continueH)
            discovery.forEachIndexed { _, _ -> add(immersiveRowH) }
        }
        val focusedH = heights.getOrElse(focusedRow) { immersiveRowH }
        // Always reserve a next-row peek band so the last focused row sits like middle rows
        // (not flush to the screen bottom).
        val nextReserve = gap + nextPeek
        // Prefer the real trailer band height; shrink only when the viewport is too short.
        fun stageGapFor(available: Dp): Dp = when {
            available >= HomeTrailerStageHeight -> HomeTrailerStageHeight
            else -> available.coerceAtLeast(120.dp)
        }
        // Continue focused: stage between Continue and peek-visible next.
        val continueStageGap = stageGapFor(
            viewport - row0Clearance - continueH - nextPeek,
        )
        // Discovery focused: stage between prev peek and focused row.
        val discoveryStageGap = stageGapFor(
            viewport - prevPeek - focusedH - nextReserve,
        )
        // Without Continue, stage opens above the first discovery row under the menu.
        val topStageGap = stageGapFor(
            viewport - row0Clearance - focusedH - nextReserve,
        )

        fun spacingBeforeFor(focus: Int, index: Int): Dp = when {
            index == 0 -> 0.dp
            // Continue ↔ For you share one stage band — keep height stable so the
            // trailer surface is not remounted/resized on row hops.
            hasContinue && index == 1 && focus <= 1 -> continueStageGap
            // Other discovery rows: stage above focused.
            focus > 0 && index == focus -> discoveryStageGap
            else -> gap
        }

        /** Column index that owns the expanding trailer gap for this focus. */
        fun stageSlotFor(focus: Int): Int = when {
            hasContinue && focus <= 1 -> 1
            focus > 0 -> focus
            else -> -1 // top-of-column stage when there is no Continue
        }

        fun offsetYFor(focus: Int): Dp = when {
            rowCount == 0 -> 0.dp
            focus <= 0 -> row0Clearance
            else -> {
                val yPrevEnd = (0 until focus).fold(0.dp) { acc, i ->
                    val space = if (i == 0) 0.dp else spacingBeforeFor(focus, i)
                    acc + space + heights.getOrElse(i) { 0.dp }
                }
                prevPeek - yPrevEnd
            }
        }

        // Row scroll only — stage stays mounted in the gap (no panel slide).
        val rowTransition = updateTransition(focusedRow, label = "home-row")
        val rowAnimSpec = tween<Dp>(HomeRowScrollMs, easing = EaseInOut)
        val offsetY by rowTransition.animateDp(
            transitionSpec = { rowAnimSpec },
            label = "home-row-scroll",
        ) { focus -> offsetYFor(focus) }
        val topStageH by rowTransition.animateDp(
            transitionSpec = { rowAnimSpec },
            label = "home-top-stage",
        ) { focus ->
            if (!hasContinue && focus == 0) topStageGap else 0.dp
        }
        // Fixed call count keeps Compose animation slots stable as shelves load.
        val animatedSpaces = List(8) { index ->
            rowTransition.animateDp(
                transitionSpec = { rowAnimSpec },
                label = "home-space-$index",
            ) { focus ->
                if (index < rowCount) spacingBeforeFor(focus, index) else 0.dp
            }.value
        }
        val stageSlot = stageSlotFor(focusedRow)

        val shelfActive = { globalRow: Int ->
            focusedRow == globalRow && !railFocused && menuItem == null &&
                !libraryMenuOpen && browseActive
        }
        fun moveRow(delta: Int): Boolean {
            val next = (focusedRow + delta).coerceIn(0, (rowCount - 1).coerceAtLeast(0))
            if (next != focusedRow) focusedRow = next
            return true
        }

        // Trailer keeps playing during shelf hops — only gated on stage settle / rail.
        val playTrailers = browseActive && menuItem == null && stageSettled && !railFocused

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
            ) {
                if (!hasContinue && showTrailerStage && topStageH > 0.dp) {
                    ImmersiveListStage(
                        item = stageItem,
                        inset = inset,
                        playTrailer = playTrailers,
                        trailerDelayMs = HomeTrailerDelayMs,
                        loopTrailer = true,
                        showContentBlock = true,
                        edgeFadeTop = HomeTrailerEdgeFade,
                        edgeFadeBottom = HomeTrailerEdgeFade,
                        cropBiasX = HomeTrailerCropBiasX,
                        cropBiasY = HomeTrailerCropBiasY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(topStageH),
                    )
                }
                if (hasContinue) {
                    // Always compose Continue — AppShell entry FocusRequester binds here.
                    ContinueWatchingRow(
                        items = continueItems,
                        label = "Continue watching",
                        jobs = jobs,
                        library = library,
                        metrics = continueMetrics,
                        inset = inset,
                        active = shelfActive(0),
                        firstFocus = firstFocus,
                        exitUp = true,
                        onOpen = { item ->
                            if (item.isLocal()) onPlayContinue(item) else onOpen(item)
                        },
                        onCardMenu = { menuItem = it },
                        onRowFocused = { focusedRow = 0 },
                        onVerticalMove = ::moveRow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(continueH),
                    )
                }
                discovery.forEachIndexed { i, shelf ->
                    val globalRow = if (hasContinue) i + 1 else i
                    val rowFocus = when {
                        !hasContinue && i == 0 -> firstFocus
                        else -> discoveryFocus[i]
                    }
                    val space = animatedSpaces.getOrElse(globalRow) {
                        spacingBeforeFor(focusedRow, globalRow)
                    }
                    if (space > 0.dp) {
                        if (showTrailerStage && globalRow == stageSlot) {
                            // Expanding gap *is* the trailer stage (Continue ↔ For you share slot 1).
                            ImmersiveListStage(
                                item = stageItem,
                                inset = inset,
                                playTrailer = playTrailers,
                                trailerDelayMs = HomeTrailerDelayMs,
                                // Stable while on shared Continue/For you band so ExoPlayer
                                // is not torn down when only the focused row changes.
                                loopTrailer = if (onSharedStage) false else true,
                                onTrailerEnded = if (onSharedStage) {
                                    { advanceTeaser() }
                                } else {
                                    null
                                },
                                showContentBlock = true,
                                edgeFadeTop = HomeTrailerEdgeFade,
                                edgeFadeBottom = HomeTrailerEdgeFade,
                                cropBiasX = HomeTrailerCropBiasX,
                                cropBiasY = HomeTrailerCropBiasY,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(space),
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxWidth().height(space))
                        }
                    }
                    // Keep ±2 shelves composed so Coil thumbs stay warm when hopping rows.
                    val nearFocus = kotlin.math.abs(globalRow - focusedRow) <= 2
                    androidx.compose.runtime.key(shelf.id) {
                        if (nearFocus) {
                            ImmersiveCardRow(
                                items = shelf.items,
                                label = shelf.label,
                                jobs = jobs,
                                library = library,
                                metrics = immersiveMetrics,
                                inset = inset,
                                active = shelfActive(globalRow),
                                firstFocus = rowFocus,
                                exitUp = !hasContinue && i == 0,
                                initialSelectedId = discoverySelected[shelf.id]
                                    ?: shelf.items.first().id,
                                onSettledSelectedId = { id ->
                                    discoverySelected = discoverySelected + (shelf.id to id)
                                },
                                onOpen = onOpen,
                                onCardMenu = { item ->
                                    if (item.canManageLibrary()) onManageLibrary?.invoke(item)
                                },
                                onRowFocused = { focusedRow = globalRow },
                                onVerticalMove = ::moveRow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .requiredHeight(heights[globalRow]),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .requiredHeight(heights[globalRow]),
                            )
                        }
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
            onManageLibrary = if (item.canManageLibrary() && onManageLibrary != null) {
                {
                    menuItem = null
                    onManageLibrary.invoke(item)
                }
            } else {
                null
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
    onManageLibrary: (() -> Unit)? = null,
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
                if (onManageLibrary != null) {
                    GhostButton(
                        label = "Manage files",
                        onClick = { if (armed) onManageLibrary() },
                        modifier = Modifier
                            .focusProperties { canFocus = armed }
                            .fillMaxWidth(),
                    )
                }
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
