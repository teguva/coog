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
import androidx.compose.runtime.key
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogCached
import tv.coog.app.ui.theme.CoogDanger
import tv.coog.app.ui.theme.CoogFetch
import tv.coog.app.ui.theme.CoogReady
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel as FocusCancel

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
) {
    if (episodes.isEmpty()) return
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val defaultSeason = remember(episodes) {
        episodes.firstOrNull { it.isLocal() }?.season ?: seasons.first()
    }
    var selectedSeason by remember { mutableIntStateOf(defaultSeason) }
    val season = if (selectedSeason in seasons) selectedSeason else defaultSeason
    val visible = remember(episodes, season) {
        episodes.filter { it.season == season }.sortedBy { it.episode }
    }
    val metrics = rememberEpisodeMetrics()
    val firstEpisodeFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    val listState = remember(season) { LazyListState() }
    val scope = rememberCoroutineScope()
    fun focusFirstEpisode() {
        scope.launch {
            runCatching { listState.scrollToItem(0) }
            runCatching { firstEpisodeFocus.requestFocus() }
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
            itemsIndexed(seasons, key = { _, value -> "season-$value" }) { _, value ->
                FilterChip(
                    label = if (value <= 0) "Specials" else "Season $value",
                    selected = value == season,
                    onClick = { selectedSeason = value },
                    onFocused = { selectedSeason = value },
                    mark = seasonMark(episodes, value, jobs),
                    modifier = Modifier
                        .then(if (value == season) Modifier.focusRequester(seasonFocus) else Modifier)
                        .focusProperties { down = firstEpisodeFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) focusFirstEpisode()
                            true
                        },
                )
            }
        }
        key(season) {
            PivotBringIntoView(pin = insetStart) {
                LazyRow(
                    state = listState,
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                    contentPadding = PaddingValues(
                        start = insetStart,
                        end = (LocalConfiguration.current.screenWidthDp.dp - insetStart - metrics.width).coerceAtLeast(insetStart),
                        top = 10.dp,
                        bottom = 12.dp,
                    ),
                ) {
                    itemsIndexed(visible, key = { _, item -> "${item.season}:${item.episode}:${item.id}" }) { index, item ->
                        val ep = item.withLibraryFromJobs(jobs)
                        val epJobs = jobs.filter { it.status != "finished" && it.status != "cancelled" && it.status != "error" }
                        EpisodeCard(
                            item = ep,
                            seriesPoster = seriesPoster,
                            seriesBackdrop = seriesBackdrop,
                            onClick = { onOpen(ep) },
                            mark = ep.cardMark(jobs),
                            status = ep.episodeStatusLine(jobs),
                            job = ep.matchingJob(epJobs),
                            modifier = if (index == 0) {
                                Modifier
                                    .focusRequester(firstEpisodeFocus)
                                    .focusProperties { up = seasonFocus }
                                    .onPreviewKeyEvent { event ->
                                        if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                                        if (event.type == KeyEventType.KeyDown) {
                                            runCatching { seasonFocus.requestFocus() }
                                        }
                                        true
                                    }
                            } else {
                                Modifier
                            },
                        )
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
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = PosterShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
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
        }
    }
}

@Composable
fun ShowCatalogRow(
    label: String,
    shows: List<ShowRow>,
    onOpen: (ShowRow) -> Unit,
    onFocused: ((MediaItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    jobs: List<JobItem> = emptyList(),
    compact: Boolean = false,
    insetStart: Dp = RailWidth,
    featured: Boolean = false,
    exitUp: Boolean = false,
) {
    if (shows.isEmpty()) return
    val hideCaptions = compact || featured
    val metrics = rememberPosterMetrics(compact = hideCaptions, featured = featured)
    PivotedShelf(
        label = label,
        metrics = metrics,
        items = shows,
        keyOf = { it.name },
        modifier = modifier,
        compact = hideCaptions,
        insetStart = insetStart,
        firstFocus = firstFocus,
    ) { _, show, itemFocus ->
        PosterCard(
            item = show.cover,
            title = show.name,
            subtitle = show.subtitle,
            onClick = { onOpen(show) },
            onFocused = onFocused?.let { cb -> { cb(show.cover) } },
            modifier = itemFocus,
            mark = if (featured) null else show.cardMark(jobs),
            exitUp = exitUp,
            compact = hideCaptions,
            featured = featured,
        )
    }
}

@Composable
fun FolderCatalogRow(
    label: String,
    folders: List<FolderRow>,
    onOpen: (FolderRow) -> Unit,
    onFocused: ((MediaItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    compact: Boolean = false,
    insetStart: Dp = RailWidth,
    featured: Boolean = false,
    exitUp: Boolean = false,
) {
    if (folders.isEmpty()) return
    val hideCaptions = compact || featured
    val metrics = rememberPosterMetrics(compact = hideCaptions, featured = featured)
    PivotedShelf(
        label = label,
        metrics = metrics,
        items = folders,
        keyOf = { it.name },
        modifier = modifier,
        compact = hideCaptions,
        insetStart = insetStart,
        firstFocus = firstFocus,
    ) { _, folder, itemFocus ->
        PosterCard(
            item = folder.cover,
            title = folder.name,
            subtitle = folder.subtitle,
            onClick = { onOpen(folder) },
            onFocused = onFocused?.let { cb -> { cb(folder.cover) } },
            modifier = itemFocus,
            exitUp = exitUp,
            compact = hideCaptions,
            featured = featured,
        )
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
    var browseWasActive by remember { mutableStateOf(browseActive) }
    LaunchedEffect(browseActive) {
        val returning = browseActive && !browseWasActive
        browseWasActive = browseActive
        if (!returning || itemCount <= 0) return@LaunchedEffect
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
                    .focusProperties {
                        // Down from the search field lands mid-row geometrically; always take the first poster.
                        enter = {
                            focusedIndex = 0
                            val ok = runCatching { requesterAt(0).requestFocus() }.getOrDefault(false)
                            if (!ok) {
                                scope.launch {
                                    runCatching { listState.scrollToItem(0) }
                                    awaitFrame()
                                    runCatching { requesterAt(0).requestFocus() }
                                }
                            } else {
                                scope.launch { runCatching { listState.scrollToItem(0) } }
                            }
                            FocusCancel
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
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = PosterShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
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

@Composable
fun JobCatalogRow(
    jobs: List<JobItem>,
    onOpen: (JobItem) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    if (jobs.isEmpty()) return
    val metrics = rememberPosterMetrics()
    val cardWidth = metrics.width * 1.9f
    val cardHeight = metrics.height * 0.42f
    PivotedShelf(
        label = "Downloading",
        metrics = metrics,
        items = jobs,
        keyOf = { it.id },
        modifier = modifier,
        firstFocus = firstFocus,
    ) { index, job, itemFocus ->
        JobCard(
            job = job,
            width = cardWidth,
            height = cardHeight,
            onClick = { onOpen(job) },
            modifier = itemFocus,
            exitUp = index == 0,
        )
    }
}

@Composable
internal fun JobCard(
    job: JobItem,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    exitUp: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val enterRail = LocalEnterRail.current
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = PosterShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (!exitUp || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                // #region agent log
                coogDebug(
                    "F",
                    "CatalogRow.kt:JobCard",
                    "job up",
                    mapOf("type" to event.type.toString()),
                    runId = "post-fix",
                )
                // #endregion
                if (event.type == KeyEventType.KeyDown) enterRail()
                event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
            }
            .onFocusChanged { focused = it.isFocused }
            .width(width)
            .height(height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(PosterShape)
                .then(
                    if (focused) Modifier.border(3.dp, Color.White, PosterShape)
                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), PosterShape),
                )
                .background(Color.White.copy(alpha = 0.08f))
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        job.headline(),
                        style = CoogType.cardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        job.subtitle(),
                        style = CoogType.cardYear,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TransferRing(mark = job.toCardMark(), size = MarkSize.Comfort)
            }
        }
    }
}
