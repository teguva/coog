package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogCached
import tv.coog.app.ui.theme.CoogDanger
import tv.coog.app.ui.theme.CoogFetch
import tv.coog.app.ui.theme.CoogReady
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

private val PosterShape = RoundedCornerShape(12.dp)
private val ProgressShape = RoundedCornerShape(50)

@Composable
fun WatchProgressBar(item: MediaItem, modifier: Modifier = Modifier) {
    val frac = item.watchFraction() ?: return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .height(5.dp)
            .clip(ProgressShape)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frac.coerceIn(0.04f, 1f))
                .fillMaxHeight()
                .clip(ProgressShape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(CoogFetch, CoogReady),
                    ),
                ),
        )
    }
}

internal data class PosterMetrics(val width: Dp, val height: Dp, val gap: Dp)

@Composable
private fun rememberPosterMetrics(compact: Boolean = false, featured: Boolean = false): PosterMetrics {
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val cardDp = when {
        featured -> widthDp * 0.145f
        compact -> widthDp * 0.078f
        else -> widthDp * 0.100f
    }
    val gapDp = when {
        featured -> widthDp * 0.010f
        compact -> widthDp * 0.009f
        else -> widthDp * 0.010f
    }
    return remember(widthDp, compact, featured) {
        PosterMetrics(width = cardDp.dp, height = (cardDp * 1.5f).dp, gap = gapDp.dp)
    }
}

@Composable
fun CatalogRow(
    label: String,
    items: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    onFocused: ((MediaItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    jobs: List<JobItem> = emptyList(),
    library: List<MediaItem> = emptyList(),
    insetStart: Dp = RailWidth,
    compact: Boolean = false,
    featured: Boolean = false,
    exitUp: Boolean = false,
    showBadge: Boolean = !featured,
    onCardMenu: ((MediaItem) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val hideCaptions = compact || featured
    val metrics = rememberPosterMetrics(compact = hideCaptions, featured = featured)
    PivotedShelf(
        label = label,
        metrics = metrics,
        items = items,
        keyOf = { it.id },
        modifier = modifier,
        insetStart = insetStart,
        compact = hideCaptions,
        firstFocus = firstFocus,
    ) { _, item, itemFocus ->
        PosterCard(
            item = item,
            title = item.headline(),
            subtitle = item.year.takeIf { it > 0 }?.toString().orEmpty(),
            onClick = { onOpen(item) },
            onLongClick = onCardMenu?.let { menu ->
                {
                    if (item.canManageLibrary()) menu(item)
                }
            },
            onFocused = onFocused?.let { cb -> { cb(item) } },
            modifier = itemFocus,
            mark = if (showBadge) item.cardMark(jobs, library) else null,
            exitUp = exitUp,
            compact = hideCaptions,
            featured = featured,
        )
    }
}

@Composable
fun EpisodeSeasonShelf(
    episodes: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    seriesPoster: String = "",
    seriesBackdrop: String = "",
    jobs: List<JobItem> = emptyList(),
    insetStart: Dp = RailWidth,
    seasons: List<tv.coog.app.data.SeasonInfo> = emptyList(),
    onSeasonSelected: ((Int) -> Unit)? = null,
    loadingSeason: Int? = null,
    /** Focus target when leaving Play/Trailer downward — lands on the season chips. */
    entryFocus: FocusRequester? = null,
    /** Long-press on an episode (Sources / manage). */
    onEpisodeMenu: ((MediaItem) -> Unit)? = null,
    onManageSeason: ((Int) -> Unit)? = null,
) {
    val seasonNumbers = remember(seasons, episodes) {
        if (seasons.isNotEmpty()) {
            seasons.map { it.number }.distinct().sortedWith(
                compareBy { season -> if (season <= 0) Int.MAX_VALUE else season },
            )
        } else {
            episodes.map { it.season }.distinct().sortedWith(
                compareBy { season -> if (season <= 0) Int.MAX_VALUE else season },
            )
        }
    }
    if (seasonNumbers.isEmpty() && episodes.isEmpty()) return
    // Continue-watching season if progress exists; otherwise first regular season (S1).
    val continueEpisode = remember(episodes) { episodes.firstOrNull { it.positionMs > 0 } }
    val defaultSeason = remember(continueEpisode, seasonNumbers) {
        continueEpisode?.season
            ?: seasonNumbers.firstOrNull { it > 0 }
            ?: seasonNumbers.firstOrNull()
            ?: 1
    }
    // Chip highlight follows focus immediately; episode shelf settles after a short debounce
    // so D-pad scrolling seasons does not thrash art / network on every hop.
    var highlightedSeason by remember { mutableIntStateOf(defaultSeason) }
    var contentSeason by remember { mutableIntStateOf(defaultSeason) }
    var userPickedSeason by remember { mutableStateOf(false) }
    LaunchedEffect(defaultSeason) {
        if (!userPickedSeason) {
            highlightedSeason = defaultSeason
            contentSeason = defaultSeason
        }
    }
    LaunchedEffect(defaultSeason, seasonNumbers) {
        if (highlightedSeason !in seasonNumbers && seasonNumbers.isNotEmpty()) {
            highlightedSeason = defaultSeason
            contentSeason = defaultSeason
            userPickedSeason = false
        }
    }
    fun seasonIncomplete(value: Int): Boolean {
        val have = episodes.count { it.season == value }
        if (have == 0) return true
        val expected = seasons.firstOrNull { it.number == value }?.episodeCount ?: 0
        if (expected > 0) return have < expected
        return episodes.none { it.season == value && it.id.startsWith("catalog:", ignoreCase = true) }
    }
    fun requestSeasonCatalog(value: Int) {
        if (seasonIncomplete(value)) {
            onSeasonSelected?.invoke(value)
        }
    }
    fun commitSeason(value: Int) {
        userPickedSeason = true
        highlightedSeason = value
        contentSeason = value
        requestSeasonCatalog(value)
    }
    LaunchedEffect(highlightedSeason) {
        if (highlightedSeason == contentSeason) return@LaunchedEffect
        delay(150)
        if (highlightedSeason != contentSeason) {
            contentSeason = highlightedSeason
            userPickedSeason = true
            requestSeasonCatalog(highlightedSeason)
        }
    }
    val season = if (contentSeason in seasonNumbers) contentSeason else defaultSeason
    val chipSeason = if (highlightedSeason in seasonNumbers) highlightedSeason else season
    val visible = remember(episodes, season) {
        episodes.filter { it.season == season }.sortedBy { it.episode }
    }
    val entryEpisodeIndex = remember(visible, continueEpisode, season) {
        val cont = continueEpisode
        if (cont != null && cont.season == season) {
            visible.indexOfFirst { it.episode == cont.episode }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
    }
    val seasonLoading = loadingSeason == season && seasonIncomplete(season)
    val metrics = rememberEpisodeMetrics()
    val episodeFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    // One LazyListState per season so swapping seasons does not remount / jump layout.
    val listStates = remember { mutableMapOf<Int, LazyListState>() }
    val listState = listStates.getOrPut(season) { LazyListState() }
    val scope = rememberCoroutineScope()
    // Fixed episode-row height so season changes do not reflow the overview.
    val episodeRowHeight = metrics.height + 22.dp
    fun focusEntryEpisode() {
        scope.launch {
            if (visible.isNotEmpty()) {
                runCatching { listState.scrollToItem(entryEpisodeIndex.coerceIn(0, visible.lastIndex)) }
            }
            runCatching { episodeFocus.requestFocus() }
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Episodes",
            style = CoogType.shelfTitle,
            modifier = Modifier.padding(start = insetStart),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = insetStart, end = insetStart),
            modifier = Modifier.fillMaxWidth().focusRestorer(),
        ) {
            itemsIndexed(seasonNumbers, key = { _, value -> "season-$value" }) { _, value ->
                FilterChip(
                    label = if (value <= 0) "Specials" else "Season $value",
                    selected = value == chipSeason,
                    onClick = { commitSeason(value) },
                    onFocused = { highlightedSeason = value },
                    onLongClick = onManageSeason?.let { manage ->
                        {
                            if (episodes.any { it.season == value && it.canManageLibrary() }) {
                                manage(value)
                            }
                        }
                    },
                    mark = seasonMark(episodes, value, jobs),
                    modifier = Modifier
                        .then(
                            if (value == chipSeason) {
                                // Play/Trailer Down lands here (entryFocus), not on episode cards.
                                Modifier
                                    .focusRequester(seasonFocus)
                                    .then(
                                        if (entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier,
                                    )
                            } else {
                                Modifier
                            },
                        )
                        .focusProperties {
                            if (visible.isNotEmpty()) down = episodeFocus
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            if (visible.isEmpty()) return@onPreviewKeyEvent true
                            if (event.type == KeyEventType.KeyDown) {
                                if (highlightedSeason != contentSeason) {
                                    commitSeason(highlightedSeason)
                                }
                                focusEntryEpisode()
                            }
                            true
                        },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(episodeRowHeight + if (seasonLoading) 28.dp else 0.dp),
        ) {
            Column {
                if (seasonLoading) {
                    Text(
                        "Loading season…",
                        style = CoogType.cardYear,
                        color = CoogTextMuted,
                        modifier = Modifier.padding(start = insetStart, top = 4.dp, bottom = 4.dp),
                    )
                }
                PivotBringIntoView(pin = insetStart) {
                    LazyRow(
                        state = listState,
                        userScrollEnabled = false,
                        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                        contentPadding = PaddingValues(
                            start = insetStart,
                            end = (LocalConfiguration.current.screenWidthDp.dp - insetStart - metrics.width)
                                .coerceAtLeast(insetStart),
                            top = 10.dp,
                            bottom = 12.dp,
                        ),
                        modifier = Modifier.height(episodeRowHeight),
                    ) {
                        itemsIndexed(
                            visible,
                            key = { _, item -> "${item.season}:${item.episode}:${item.id}" },
                        ) { index, item ->
                            val ep = item.withLibraryFromJobs(jobs)
                            val epJobs = jobs.filter {
                                it.status != "finished" && it.status != "cancelled" && it.status != "error"
                            }
                            val isEntry = index == entryEpisodeIndex
                            EpisodeCard(
                                item = ep,
                                seriesPoster = seriesPoster,
                                seriesBackdrop = seriesBackdrop,
                                onClick = { onOpen(ep) },
                                onLongClick = onEpisodeMenu?.let { menu ->
                                    { menu(ep) }
                                },
                                mark = ep.cardMark(jobs),
                                status = ep.episodeStatusLine(jobs),
                                job = ep.matchingJob(epJobs),
                                modifier = when {
                                    isEntry -> {
                                        Modifier
                                            .focusRequester(episodeFocus)
                                            .focusProperties { up = seasonFocus }
                                            .onPreviewKeyEvent { event ->
                                                if (event.key != Key.DirectionUp) {
                                                    return@onPreviewKeyEvent false
                                                }
                                                if (event.type == KeyEventType.KeyDown) {
                                                    runCatching { seasonFocus.requestFocus() }
                                                }
                                                true
                                            }
                                    }
                                    else -> Modifier
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberEpisodeMetrics(): PosterMetrics {
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val cardDp = widthDp * 0.22f
    val gapDp = widthDp * 0.010f
    return remember(widthDp) {
        PosterMetrics(width = cardDp.dp, height = (cardDp * 9f / 16f).dp, gap = gapDp.dp)
    }
}

@Composable
private fun EpisodeCard(
    item: MediaItem,
    seriesPoster: String,
    seriesBackdrop: String,
    onClick: () -> Unit,
    mark: CardMark?,
    status: String? = null,
    job: JobItem? = null,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val size = rememberEpisodeMetrics()
    var focused by remember { mutableStateOf(false) }
    val still = item.episodeStillUrl(seriesPoster, seriesBackdrop)
    val art = item.copy(posterUrl = still, backdropUrl = still)
    val number = item.seasonEpisode().ifBlank { if (item.episode > 0) "E${item.episode}" else "" }
    val heading = item.episodeName()
    Column(
        modifier = Modifier.width(size.width),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CoogClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = PosterShape,
            color = Color.Transparent,
            modifier = modifier
                .fillMaxWidth()
                .height(size.height)
                .onFocusChanged { focused = it.isFocused },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(PosterShape)
                    .then(
                        if (focused) {
                            Modifier.border(3.dp, Color.White, PosterShape)
                        } else {
                            Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), PosterShape)
                        },
                    )
                    .background(Color.White.copy(alpha = 0.06f)),
            ) {
                PosterArt(
                    item = art,
                    kind = ArtKind.Still,
                    mark = mark,
                    serverFallback = false,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                )
                if (still.isBlank() && number.isNotBlank()) {
                    Text(
                        number,
                        style = CoogType.heroTagline,
                        color = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                WatchProgressBar(
                    item = item,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (number.isNotBlank()) {
                Text(
                    number,
                    style = CoogType.cardYear,
                    color = CoogTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                heading.ifBlank { number.ifBlank { item.episodeHeadline() } },
                style = CoogType.cardTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!status.isNullOrBlank()) {
                Text(
                    status,
                    style = CoogType.cardYear,
                    color = when {
                        status == "Local" -> CoogCached
                        job?.status == "error" -> CoogDanger
                        else -> CoogTextMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val fileMeta = when {
                job != null -> job.fileMetaLine()
                item.isLocal() -> item.fileMetaLine()
                else -> ""
            }
            if (fileMeta.isNotBlank()) {
                Text(
                    fileMeta,
                    style = CoogType.cardYear,
                    color = CoogTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <T> PivotedShelf(
    label: String,
    metrics: PosterMetrics,
    items: List<T>,
    keyOf: (T) -> Any,
    modifier: Modifier = Modifier,
    insetStart: Dp = RailWidth,
    compact: Boolean = false,
    firstFocus: FocusRequester? = null,
    itemContent: @Composable (index: Int, item: T, focusModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val itemCount = items.size
    val itemKeys = remember(items) { items.map(keyOf) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var focusedKey by remember { mutableStateOf<Any?>(null) }
    val localRequesters = remember(itemKeys) { List(itemCount) { FocusRequester() } }
    val browseActive = LocalBrowseActive.current
    fun requesterAt(index: Int): FocusRequester =
        if (index == 0 && firstFocus != null) firstFocus else localRequesters.getOrElse(index) { localRequesters.first() }

    fun focusIndex(to: Int): Boolean {
        if (to !in 0 until itemCount) return false
        focusedIndex = to
        focusedKey = itemKeys.getOrNull(to)
        scope.launch {
            runCatching { listState.scrollToItem(to) }
            awaitFrame()
            var ok = runCatching { requesterAt(to).requestFocus() }.getOrDefault(false)
            if (!ok) {
                awaitFrame()
                ok = runCatching { requesterAt(to).requestFocus() }.getOrDefault(false)
            }
        }
        return true
    }

    // New result sets start at the first poster; same set keeps the last focused key.
    LaunchedEffect(itemKeys) {
        val restored = focusedKey?.let { key -> itemKeys.indexOf(key) }?.takeIf { it >= 0 }
        if (restored != null) {
            focusedIndex = restored
            runCatching { listState.scrollToItem(restored) }
        } else {
            focusedIndex = 0
            focusedKey = itemKeys.getOrNull(0)
            runCatching { listState.scrollToItem(0) }
        }
    }

    // Returning from overview/player: Browse stayed composed — put focus back on the card.
    val railFocused = LocalNavBarFocused.current
    var browseWasActive by remember { mutableStateOf(browseActive) }
    LaunchedEffect(browseActive, railFocused) {
        val returning = browseActive && !browseWasActive
        browseWasActive = browseActive
        if (!returning || railFocused || itemCount <= 0) return@LaunchedEffect
        kotlinx.coroutines.yield()
        focusIndex(focusedIndex.coerceIn(0, itemCount - 1))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp)) {
        Text(
            label,
            style = CoogType.shelfTitle,
            modifier = Modifier.padding(start = insetStart),
        )
        PivotBringIntoView(pin = insetStart) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .focusRestorer(requesterAt(0))
                    .focusProperties {
                        // Redirect into the restored/first poster — never cancelFocusChange,
                        // or Down skips this whole row when the target isn't ready yet.
                        onEnter = {
                            val target = focusedIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
                            runCatching { requesterAt(target).requestFocus() }
                        }
                    },
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                contentPadding = PaddingValues(
                    start = insetStart,
                    end = (LocalConfiguration.current.screenWidthDp.dp - insetStart - metrics.width).coerceAtLeast(insetStart),
                    top = if (compact) 14.dp else 12.dp,
                    bottom = if (compact) 14.dp else 16.dp,
                ),
            ) {
                itemsIndexed(items, key = { _, item -> keyOf(item) }) { index, item ->
                    val focusMod = Modifier
                        .focusRequester(requesterAt(index))
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusedIndex = index
                                focusedKey = itemKeys.getOrNull(index)
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionRight -> focusIndex(index + 1)
                                Key.DirectionLeft -> focusIndex(index - 1)
                                else -> false
                            }
                        }
                    itemContent(index, item, focusMod)
                }
            }
        }
    }
}

@Composable
fun PosterCard(
    item: MediaItem,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    mark: CardMark? = item.cardMark(),
    exitUp: Boolean = false,
    compact: Boolean = false,
    featured: Boolean = false,
    width: Dp? = null,
    height: Dp? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val defaults = rememberPosterMetrics(compact = compact, featured = featured)
    val cardWidth = width ?: defaults.width
    val cardHeight = height ?: defaults.height
    var focused by remember { mutableStateOf(false) }
    val enterRail = LocalEnterRail.current
    Column(
        modifier = Modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CoogClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = PosterShape,
            color = Color.Transparent,
            modifier = modifier
                .fillMaxWidth()
                .height(cardHeight)
                .onPreviewKeyEvent { event ->
                    if (!exitUp || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                    // #region agent log
                    coogDebug(
                        "F",
                        "CatalogRow.kt:PosterCard",
                        "poster up",
                        mapOf("title" to title, "type" to event.type.toString()),
                        runId = "post-fix",
                    )
                    // #endregion
                    if (event.type == KeyEventType.KeyDown) enterRail()
                    event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(PosterShape)
                    .then(
                        if (focused) {
                            Modifier.border(3.dp, Color.White, PosterShape)
                        } else {
                            Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), PosterShape)
                        },
                    )
                    .background(Color.White.copy(alpha = 0.06f)),
            ) {
                PosterArt(
                    item = item,
                    kind = ArtKind.Poster,
                    mark = mark,
                    modifier = Modifier.fillMaxSize(),
                )
                WatchProgressBar(
                    item = item,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart),
                )
            }
        }
        if (!compact) {
            Column(
                modifier = Modifier.padding(horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    title,
                    style = CoogType.cardTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = CoogType.cardYear,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
