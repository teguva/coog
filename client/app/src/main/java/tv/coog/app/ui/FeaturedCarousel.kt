package tv.coog.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import tv.coog.app.data.CoogApi
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgSoft
import tv.coog.app.ui.theme.CoogType
import androidx.media3.common.MediaItem as ExoMediaItem

private val CardShape = RoundedCornerShape(16.dp)
private val FocusPad = 8.dp

/** Focus/scroll snap is handled by scrollToItem — skip animated bring-into-view. */
@OptIn(ExperimentalFoundationApi::class)
private val NoScrollBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = 0f
}

internal suspend fun catalogMatch(api: CoogApi, item: MediaItem): MediaItem? {
    val query = item.headline().ifBlank { item.title }
    if (query.isBlank()) return null
    val result = runCatching { api.catalogSearch(query) }.getOrNull() ?: return null
    val pool = if (item.kind == "series" || item.kind == "episode") result.series else result.movies
    val want = normalizeBrowseTitle(query)
    val hits = pool.filter { normalizeBrowseTitle(it.headline()) == want }
    if (hits.isEmpty()) return null
    val year = item.year
    return hits.firstOrNull { year == 0 || it.year == 0 || it.year == year } ?: hits.singleOrNull()
}

private fun hasCatalogArt(item: MediaItem): Boolean {
    val poster = item.posterUrl
    val backdrop = item.backdropUrl
    fun remoteCdn(url: String) =
        url.startsWith("http") && !url.contains("/api/v1/media/")
    return remoteCdn(poster) || remoteCdn(backdrop)
}

private suspend fun resolveCatalogArt(api: CoogApi, item: MediaItem): MediaItem? {
    val kind = item.kind.ifBlank { "movie" }
    val remote = when {
        item.imdbId.isNotBlank() -> runCatching {
            api.catalogTitle(item.imdbId, kind)
        }.getOrNull()
        item.tmdbId != 0 -> runCatching {
            api.catalogTmdb(kind, item.tmdbId)
        }.getOrNull()
        else -> catalogMatch(api, item)
    } ?: return null
    // Catalog search often returns TMDB art without imdbId; trailers need catalog:tt…
    if (remote.imdbId.isNotBlank() || remote.tmdbId == 0) return remote
    val hydrated = runCatching {
        api.catalogTmdb(remote.kind.ifBlank { kind }, remote.tmdbId)
    }.getOrNull() ?: return remote
    return if (hydrated.imdbId.isNotBlank()) hydrated else remote
}

@Composable
fun FeaturedCarousel(
    items: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    jobs: List<tv.coog.app.data.JobItem> = emptyList(),
    library: List<MediaItem> = emptyList(),
    expanded: Boolean = true,
    /** True when this shelf should show focus chrome + trailer (nav/dialog not on top). */
    active: Boolean = expanded,
    /** Focused hero card height; when set, used instead of measuring from the row. */
    heroCardHeight: Dp = Dp.Unspecified,
    /** Shared height for every unfocused poster (focused-row peeks and idle rows). */
    peekCardHeight: Dp = Dp.Unspecified,
    /** When set, drives hero/peek width and height from the 18-unit content band. */
    cardMetrics: ShelfCardMetrics? = null,
    onRowFocused: () -> Unit = {},
    firstFocus: FocusRequester? = null,
    exitUp: Boolean = false,
    insetStart: Dp = catalogInset(),
    onCardMenu: ((MediaItem) -> Unit)? = null,
    /** +1 down / -1 up between shelves; return true if handled. */
    onVerticalMove: (Int) -> Boolean = { false },
) {
    if (items.isEmpty()) return
    val server = LocalCoogServer.current
    // Keep horizontal position by item id so Continue refresh doesn't jump to card 0.
    var selectedId by remember { mutableStateOf(items.first().id) }
    val index = items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
        ?: 0.coerceAtMost(items.lastIndex)
    LaunchedEffect(items.map { it.id }) {
        if (items.none { it.id == selectedId }) {
            selectedId = items.getOrNull(index)?.id ?: items.firstOrNull()?.id ?: return@LaunchedEffect
        }
    }
    val enterRail = LocalEnterRail.current
    val peekListState = rememberLazyListState()
    val idleListState = rememberLazyListState()
    val rowFocus = firstFocus ?: remember { FocusRequester() }
    var rowFocused by remember { mutableStateOf(false) }
    var extras by remember { mutableStateOf<Map<String, MediaItem>>(emptyMap()) }
    val featured = extras[items[index].id] ?: items[index]
    var selectPressed by remember { mutableStateOf(false) }
    var selectLongHandled by remember { mutableStateOf(false) }

    LaunchedEffect(selectPressed, featured.id) {
        if (!selectPressed) return@LaunchedEffect
        selectLongHandled = false
        delay(550)
        if (selectPressed && onCardMenu != null) {
            selectLongHandled = true
            onCardMenu(featured)
        }
    }

    val idsKey = remember(items) { items.joinToString { it.id } }
    LaunchedEffect(idsKey, server.url, server.token) {
        val api = CoogApi(server.url, server.token)
        val missing = items.filter { raw ->
            val cur = extras[raw.id] ?: raw
            !hasCatalogArt(cur)
        }
        missing.forEachIndexed { i, raw ->
            if (i > 0) delay(40L)
            val remote = resolveCatalogArt(api, raw)
            if (remote != null) {
                extras = extras + (raw.id to mergeDetails(raw, remote))
            }
        }
    }

    LaunchedEffect(featured.id, featured.imdbId, featured.tmdbId, featured.title, featured.kind, server.url, server.token) {
        if (hasCatalogArt(featured)) return@LaunchedEffect
        delay(120)
        val api = CoogApi(server.url, server.token)
        val remote = resolveCatalogArt(api, featured)
        if (remote != null) {
            extras = extras + (featured.id to mergeDetails(featured, remote))
        }
    }

    LaunchedEffect(index, expanded) {
        if (expanded) {
            runCatching { peekListState.scrollToItem(0) }
        } else {
            runCatching { idleListState.scrollToItem(index) }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Text(
                label,
                style = CoogType.shelfTitle,
                modifier = Modifier.padding(start = insetStart, bottom = 4.dp),
            )
        }
        BoxWithConstraints(
            // Stable focus target — never disposed when switching expanded/idle.
            // Up/Down/Left/Right are handled here (focusProperties Cancel) so D-pad key
            // repeat cannot walk focus past a shelf via the system focus engine.
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(rowFocus)
                .focusable()
                .focusProperties {
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
                .onFocusChanged {
                    rowFocused = it.isFocused
                    if (it.isFocused) onRowFocused()
                }
                .onPreviewKeyEvent { event ->
                    val left = event.key == Key.DirectionLeft
                    val right = event.key == Key.DirectionRight
                    val up = event.key == Key.DirectionUp
                    val down = event.key == Key.DirectionDown
                    val select = event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter
                    if (!left && !right && !up && !down && !select) {
                        return@onPreviewKeyEvent false
                    }

                    val repeat = event.nativeKeyEvent.repeatCount > 0
                    val toMenu = (exitUp && up) || (exitUp && index == 0 && left)
                    if (toMenu) {
                        // Ignore key-repeat so holding Up doesn't spam the rail.
                        if (event.type == KeyEventType.KeyDown && !repeat) enterRail()
                        return@onPreviewKeyEvent true
                    }
                    if (select) {
                        // Cards are non-focusable; OK / long-OK land on this shelf shell.
                        when (event.type) {
                            KeyEventType.KeyDown -> if (!repeat) {
                                selectPressed = true
                                selectLongHandled = false
                            }
                            KeyEventType.KeyUp -> {
                                if (selectPressed && !selectLongHandled) onOpen(featured)
                                selectPressed = false
                                selectLongHandled = false
                            }
                            else -> Unit
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        when {
                            // Hold Left/Right for continuous horizontal browse.
                            right && index < items.lastIndex -> selectedId = items[index + 1].id
                            left && index > 0 -> selectedId = items[index - 1].id
                            // Vertical: one shelf per physical press (repeat skips rows).
                            !repeat && down -> onVerticalMove(1)
                            !repeat && up && !exitUp -> onVerticalMove(-1)
                        }
                    }
                    // Consume KeyUp too so the focus engine never gets a second chance.
                    left || right || down || (up && !exitUp)
                },
        ) {
            val metrics = cardMetrics
            val gap = metrics?.gap ?: ShelfCardGap
            val pin = insetStart
            val rowHeight = maxHeight
            val featuredHeight = when {
                metrics != null && expanded -> metrics.heroHeight
                metrics != null -> metrics.peekHeight
                expanded && heroCardHeight != Dp.Unspecified -> heroCardHeight
                else -> (rowHeight - FocusPad * 2).coerceAtLeast(1.dp)
            }
            val peekHeight = when {
                metrics != null -> metrics.peekHeight
                peekCardHeight != Dp.Unspecified -> peekCardHeight
                else -> featuredHeight * 0.5f
            }
            val featuredWidth = when {
                metrics != null && expanded -> metrics.heroWidth
                metrics != null -> metrics.peekWidth
                else -> featuredHeight * 2f
            }
            val peekWidth = metrics?.peekWidth ?: (peekHeight * 2f / 3f)
            val metaGap = 8.dp
            val metaHeight = (featuredHeight - peekHeight - metaGap).coerceAtLeast(0.dp)
            val metaWidth = (peekWidth * 3f + gap * 2f)
                .coerceAtMost((maxWidth - pin - featuredWidth - gap - pin).coerceAtLeast(0.dp))

            if (expanded) {
                ExpandedShelf(
                    items = items,
                    extras = extras,
                    index = index,
                    featured = featured,
                    jobs = jobs,
                    library = library,
                    pin = pin,
                    gap = gap,
                    featuredWidth = featuredWidth,
                    featuredHeight = featuredHeight,
                    peekWidth = peekWidth,
                    peekHeight = peekHeight,
                    metaWidth = metaWidth,
                    metaHeight = metaHeight,
                    peekListState = peekListState,
                    active = active,
                    onOpen = onOpen,
                    onCardMenu = onCardMenu,
                )
            } else {
                IdleShelf(
                    items = items,
                    extras = extras,
                    index = index,
                    jobs = jobs,
                    library = library,
                    pin = pin,
                    gap = gap,
                    rowHeight = rowHeight,
                    peekWidth = peekWidth,
                    peekHeight = peekHeight,
                    maxWidth = maxWidth,
                    listState = idleListState,
                    active = active,
                    onOpen = onOpen,
                    onCardMenu = onCardMenu,
                )
            }
        }
    }
}

@Composable
private fun ExpandedShelf(
    items: List<MediaItem>,
    extras: Map<String, MediaItem>,
    index: Int,
    featured: MediaItem,
    jobs: List<tv.coog.app.data.JobItem>,
    library: List<MediaItem>,
    pin: Dp,
    gap: Dp,
    featuredWidth: Dp,
    featuredHeight: Dp,
    peekWidth: Dp,
    peekHeight: Dp,
    metaWidth: Dp,
    metaHeight: Dp,
    peekListState: androidx.compose.foundation.lazy.LazyListState,
    active: Boolean,
    onOpen: (MediaItem) -> Unit,
    onCardMenu: ((MediaItem) -> Unit)?,
) {
    val heroMark = remember(featured, jobs, library) { featured.cardMark(jobs, library) }
    val prevRaw = items.getOrNull(index - 1)
    val prevItem = prevRaw?.let { extras[it.id] ?: it }
    val prevMark = remember(prevItem, jobs, library) { prevItem?.cardMark(jobs, library) }
    val nextItems = remember(items, index) { items.drop(index + 1) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = FocusPad, bottom = FocusPad),
    ) {
        // Previous peek: same gap as the right strip; hero stays at `pin` (not pushed right).
        if (prevItem != null) {
            RowCard(
                item = prevItem,
                featured = false,
                width = peekWidth,
                height = peekHeight,
                mark = prevMark,
                showFocus = false,
                onOpen = { onOpen(prevItem) },
                onLongClick = onCardMenu?.let { menu -> { menu(prevItem) } },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = pin - peekWidth - gap)
                    .focusProperties { canFocus = false },
            )
        }
        RowCard(
            item = featured,
            featured = true,
            width = featuredWidth,
            height = featuredHeight,
            playTrailer = active,
            mark = heroMark,
            showFocus = active,
            onOpen = { onOpen(featured) },
            onLongClick = onCardMenu?.let { menu -> { menu(featured) } },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = pin)
                .zIndex(1f)
                .focusProperties { canFocus = false },
        )
        if (metaHeight > 0.dp && metaWidth > 0.dp) {
            FocusedMetaPanel(
                item = featured,
                mark = heroMark,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = pin + featuredWidth + gap)
                    .width(metaWidth)
                    .height(metaHeight),
            )
        }
        if (nextItems.isNotEmpty()) {
            LazyRow(
                state = peekListState,
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(gap),
                contentPadding = PaddingValues(end = pin),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = pin + featuredWidth + gap)
                    .fillMaxWidth()
                    .height(peekHeight),
            ) {
                itemsIndexed(nextItems, key = { _, item -> item.id }) { _, raw ->
                    val item = extras[raw.id] ?: raw
                    val mark = remember(item, jobs, library) { item.cardMark(jobs, library) }
                    RowCard(
                        item = item,
                        featured = false,
                        width = peekWidth,
                        height = peekHeight,
                        mark = mark,
                        showFocus = false,
                        onOpen = { onOpen(item) },
                        onLongClick = onCardMenu?.let { menu -> { menu(item) } },
                        modifier = Modifier.focusProperties { canFocus = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdleShelf(
    items: List<MediaItem>,
    extras: Map<String, MediaItem>,
    index: Int,
    jobs: List<tv.coog.app.data.JobItem>,
    library: List<MediaItem>,
    pin: Dp,
    gap: Dp,
    rowHeight: Dp,
    peekWidth: Dp,
    peekHeight: Dp,
    maxWidth: Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    active: Boolean,
    onOpen: (MediaItem) -> Unit,
    onCardMenu: ((MediaItem) -> Unit)?,
) {
    val endPad = (maxWidth - pin - peekWidth).coerceAtLeast(pin)
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollBringIntoView) {
        LazyRow(
            state = listState,
            userScrollEnabled = false,
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(
                start = pin,
                end = endPad,
                top = FocusPad,
                bottom = FocusPad,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { i, raw ->
                val item = extras[raw.id] ?: raw
                val mark = remember(item, jobs, library) { item.cardMark(jobs, library) }
                RowCard(
                    item = item,
                    featured = false,
                    width = peekWidth,
                    height = peekHeight,
                    mark = mark,
                    showFocus = active && i == index,
                    onOpen = { onOpen(item) },
                    onLongClick = onCardMenu?.let { menu -> { menu(item) } },
                    modifier = Modifier.focusProperties { canFocus = false },
                )
            }
        }
    }
}

@Composable
private fun RowCard(
    item: MediaItem,
    featured: Boolean,
    width: Dp,
    height: Dp,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    playTrailer: Boolean = false,
    mark: CardMark? = null,
    showFocus: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var selfFocused by remember { mutableStateOf(false) }
    val drawFocus = showFocus ?: selfFocused
    Surface(
        onClick = onOpen,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = modifier
            .width(width)
            .height(height)
            .clipToBounds()
            .onFocusChanged { selfFocused = it.isFocused },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CardShape)
                .then(if (drawFocus) Modifier.border(3.dp, Color.White, CardShape) else Modifier),
        ) {
            if (featured) {
                PosterArt(
                    item = item,
                    kind = ArtKind.Backdrop,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    preferDisplay = true,
                    modifier = Modifier.fillMaxSize(),
                )
                if (playTrailer) {
                    FocusedTrailer(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                PosterArt(
                    item = item,
                    kind = ArtKind.Poster,
                    mark = mark,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            WatchProgressBar(
                item = item,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun FocusedMetaPanel(
    item: MediaItem,
    mark: CardMark?,
    modifier: Modifier = Modifier,
) {
    val genres = remember(item) { item.heroGenres() }
    val meta = remember(item) { item.heroMetaLine().ifBlank { item.cardMetaLine() } }
    val resumeAt = remember(item) { item.seasonEpisode() }
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(CoogBgSoft)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            item.headline(),
            style = CoogType.heroTitle.copy(
                fontSize = 20.sp,
                lineHeight = 24.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (resumeAt.isNotBlank()) {
            Text(
                resumeAt,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mark?.let { StatusMark(mark = it, size = MarkSize.Comfort) }
            item.matchPercent()?.let { MatchMark(percent = it, size = MarkSize.Comfort) }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (genres.isNotEmpty()) {
            Text(
                genres.joinToString("  •  "),
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FocusedTrailer(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onPlaying: (Boolean) -> Unit = {},
) {
    val server = LocalCoogServer.current
    val mediaId = item.trailerMediaId()
    val url = server.trailerUrl(mediaId)
    var start by remember(mediaId) { mutableStateOf(false) }
    LaunchedEffect(mediaId, server.url, server.token) {
        start = false
        onPlaying(false)
        val api = CoogApi(server.url, server.token)
        val check = async {
            runCatching { api.trailerExists(mediaId) }.getOrDefault(false)
        }
        // Short debounce so focus doesn't thrash; do not wait seconds on a warm cache.
        delay(400)
        start = check.await()
    }
    if (start && url.isNotBlank()) {
        TrailerPlayer(url = url, token = server.token, onReady = onPlaying, modifier = modifier)
    }
}

@Composable
private fun TrailerPlayer(
    url: String,
    token: String,
    modifier: Modifier = Modifier,
    onReady: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ready by remember(url) { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    LaunchedEffect(ready) { onReady(ready) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(url, token) {
        val http = DefaultHttpDataSource.Factory()
        if (token.isNotBlank()) {
            http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        }
        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                volume = 1f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(ExoMediaItem.fromUri(url))
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        ready = false
                    }
                })
                prepare()
                playWhenReady = true
            }
        player = exo
        onDispose {
            onReady(false)
            exo.playWhenReady = false
            exo.stop()
            exo.release()
            player = null
            ready = false
        }
    }
    LaunchedEffect(resumed, player) {
        val exo = player ?: return@LaunchedEffect
        exo.playWhenReady = resumed
        if (!resumed) exo.pause()
    }
    val exo = player
    AnimatedVisibility(visible = ready && resumed && exo != null, enter = fadeIn(), exit = fadeOut()) {
        if (exo != null) {
            Box(modifier.clipToBounds()) {
                ContentFrame(
                    player = exo,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}
