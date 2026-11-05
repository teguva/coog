package tv.coog.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.player.buildTrailerExoPlayer
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogType
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi

/** Alder tap decelerate. */
private const val HomeTapScrollMs = 200

private val ImmersiveCardShape = RoundedCornerShape(12.dp)
private val ContinueCardShape = RoundedCornerShape(12.dp)

/** Target trailer / stage band height (gap expands to this when the viewport allows). */
internal val HomeTrailerStageHeight = 399.dp
/** Soft edge into page bg when the stage sits between shelves. */
internal val HomeTrailerEdgeFade = 48.dp
/** Home shelf poster height (Continue + immersive share this; aspects differ). */
internal val HomeCardHeight = 125.dp
/** Neighbour row shows this fraction of poster height (title/pad excluded). */
internal val HomeNeighbourCardFrac = 0.25f
/** Short backdrop crossfade on same-row title change (not used for row scroll). */
private const val HomeBackdropFadeMs = 280
/** Constant logo box on 16:9 billboard cards (width + height capped). */
private val BillboardLogoHeight = 28.dp
private val BillboardLogoMaxWidth = 112.dp
/** Default home trailer settle delay (Hold-scroll commits stage before this fires). */
internal const val HomeTrailerStartDelayMs = 900L
/** Horizontal selection settles before billboard width / parent stage updates. */
internal const val HomeShelfSettleMs = 420L
/** Floor between Left/Right hops while the key is held (system repeat is faster on MiTV). */
private const val HomeHoldMinIntervalMs = 50L

internal data class ImmersiveCardMetrics(
    val width: Dp,
    val height: Dp,
    val gap: Dp,
    val focusScale: Float = 1.1f,
)

internal data class ContinueCardMetrics(
    val width: Dp,
    val height: Dp,
    val gap: Dp,
    /** Selected Continue tile — 16:9 billboard; peeks stay 2:3. */
    val focusedWidth: Dp,
)

@Composable
internal fun rememberImmersiveCardMetrics(cardHeight: Dp, gap: Dp = ShelfCardGap): ImmersiveCardMetrics {
    // Same card height as Continue peeks; landscape thumbs at 16:9.
    return remember(cardHeight, gap) {
        ImmersiveCardMetrics(
            width = cardHeight * 16f / 9f,
            height = cardHeight,
            gap = gap,
            focusScale = 1f,
        )
    }
}

@Composable
internal fun rememberContinueCardMetrics(contentWidth: Dp): ContinueCardMetrics {
    // Fixed poster height; vertical peeks, 16:9 selected billboard (stable when row blurs).
    return remember(contentWidth) {
        val height = HomeCardHeight
        ContinueCardMetrics(
            width = height * 2f / 3f,
            height = height,
            gap = ShelfCardGap,
            focusedWidth = height * 16f / 9f,
        )
    }
}

/**
 * Google TV Immersive List stage: 16:9 backdrop (subject toward end), cinematic scrim,
 * left content block, and optional muted trailer overlay.
 */
@Composable
internal fun ImmersiveListStage(
    item: MediaItem?,
    inset: Dp,
    modifier: Modifier = Modifier,
    playTrailer: Boolean = false,
    /** Pause without tearing down ExoPlayer (hold-scroll hops). */
    trailerPaused: Boolean = false,
    /** Shorter delay for Continue-focus teaser band. */
    trailerDelayMs: Long = 1_000L,
    /** When false, play once then invoke [onTrailerEnded] (teaser sequence). */
    loopTrailer: Boolean = true,
    onTrailerEnded: (() -> Unit)? = null,
    showContentBlock: Boolean = true,
    /**
     * Soft edge into the page background behind overlapping cards.
     * Height should match how far the stage extends under those cards.
     */
    edgeFadeTop: Dp = HomeTrailerEdgeFade,
    edgeFadeBottom: Dp = HomeTrailerEdgeFade,
    edgeFadeColor: Color = CoogBgDeep,
    /**
     * Crop focus inside the trailer viewport: -1..1 on each axis
     * (0 = center, -1 = start/top, +1 = end/bottom). Applies to backdrop + video.
     */
    cropBiasX: Float = 0f,
    cropBiasY: Float = 0f,
) {
    if (item == null) return
    val server = LocalCoogServer.current
    var display by remember(item.id) { mutableStateOf(item) }
    LaunchedEffect(item.id, item.imdbId, item.tmdbId, server.url, server.token) {
        display = item
        if (hasImmersiveArt(item)) return@LaunchedEffect
        delay(80)
        val api = CoogApi(server.url, server.token)
        val remote = resolveImmersiveArt(api, item) ?: return@LaunchedEffect
        display = mergeDetails(item, remote)
    }
    val cropAlign = BiasAlignment(
        horizontalBias = cropBiasX.coerceIn(-1f, 1f),
        verticalBias = cropBiasY.coerceIn(-1f, 1f),
    )
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        AnimatedContent(
            targetState = display,
            transitionSpec = {
                fadeIn(tween(HomeBackdropFadeMs)) togetherWith fadeOut(tween(HomeBackdropFadeMs))
            },
            contentKey = { it.id },
            label = "immersive-backdrop",
            modifier = Modifier.fillMaxSize(),
        ) { art ->
            PosterArt(
                item = art,
                kind = ArtKind.Backdrop,
                contentScale = ContentScale.Crop,
                alignment = cropAlign,
                preferDisplay = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (playTrailer && display.canPlayTrailer()) {
            HomeFocusedTrailer(
                item = display,
                delayMs = trailerDelayMs,
                loop = loopTrailer,
                paused = trailerPaused,
                onEnded = onTrailerEnded,
                cropBiasX = cropBiasX,
                cropBiasY = cropBiasY,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (playTrailer && onTrailerEnded != null && !display.canPlayTrailer()) {
            // No trailer available — advance teaser sequence after a short still.
            LaunchedEffect(display.id) {
                delay(6_000)
                onTrailerEnded()
            }
        }
        // Cinematic scrim: readable content block on the left, fade toward art on the right.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.88f),
                        0.42f to Color.Black.copy(alpha = 0.55f),
                        0.72f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        // Card-overlap fades: bg at the stage edge → transparent where the clear trailer begins.
        if (edgeFadeTop > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(edgeFadeTop)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to edgeFadeColor,
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
        if (edgeFadeBottom > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(edgeFadeBottom)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to edgeFadeColor,
                        ),
                    ),
            )
        }
        if (showContentBlock) {
            // Sit meta on the bottom of the clear trailer band (above bottom edge fade / cards).
            val metaBottom = (edgeFadeBottom + 12.dp).coerceAtLeast(12.dp)
            ImmersiveContentBlock(
                item = display,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = inset, end = inset, bottom = metaBottom)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(0.46f),
            )
        }
    }
}

@Composable
private fun HomeFocusedTrailer(
    item: MediaItem,
    modifier: Modifier = Modifier,
    delayMs: Long = 1_000L,
    loop: Boolean = true,
    paused: Boolean = false,
    onEnded: (() -> Unit)? = null,
    cropBiasX: Float = 0f,
    cropBiasY: Float = 0f,
) {
    val server = LocalCoogServer.current
    val mediaId = item.trailerMediaId()
    val url = server.trailerUrl(mediaId)
    val delayMsState = rememberUpdatedState(delayMs)
    val onEndedState = rememberUpdatedState(onEnded)
    var start by remember(mediaId) { mutableStateOf(false) }
    // Key only on media/server — row switches must not restart the wait / playback.
    LaunchedEffect(mediaId, server.url, server.token) {
        start = false
        if (mediaId.isBlank() || server.url.isBlank()) {
            if (onEndedState.value != null) {
                delay(6_000)
                onEndedState.value?.invoke()
            }
            return@LaunchedEffect
        }
        delay(delayMsState.value)
        val api = CoogApi(server.url, server.token)
        val ok = runCatching { api.waitForTrailer(mediaId) }.getOrDefault(false)
        start = ok
        if (!ok && onEndedState.value != null) {
            delay(6_000)
            onEndedState.value?.invoke()
        }
    }
    if (start && url.isNotBlank()) {
        HomeTrailerPlayer(
            url = url,
            token = server.token,
            loop = loop,
            paused = paused,
            onEnded = onEnded,
            cropBiasX = cropBiasX,
            cropBiasY = cropBiasY,
            modifier = modifier,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun HomeTrailerPlayer(
    url: String,
    token: String,
    modifier: Modifier = Modifier,
    loop: Boolean = true,
    paused: Boolean = false,
    onEnded: (() -> Unit)? = null,
    cropBiasX: Float = 0f,
    cropBiasY: Float = 0f,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val server = LocalCoogServer.current
    val trailerVolume = server.trailerVolume
    val skipStartMs = server.trailerSkipStartMs
    val skipEndMs = server.trailerSkipEndMs
    var ready by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val endedCb = rememberUpdatedState(onEnded)
    val loopState = rememberUpdatedState(loop)
    val volumeState = rememberUpdatedState(trailerVolume)
    val skipStartState = rememberUpdatedState(skipStartMs)
    val skipEndState = rememberUpdatedState(skipEndMs)
    val pausedState = rememberUpdatedState(paused)
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
    // One player for the stage lifetime — swap media with setMediaItem, release only on leave.
    DisposableEffect(Unit) {
        val app = context.applicationContext as android.app.Application
        var endedFired = false
        fun fireEnded() {
            if (endedFired || loopState.value) return
            endedFired = true
            endedCb.value?.invoke()
        }
        val exo = buildTrailerExoPlayer(app).apply {
            volume = volumeState.value.coerceIn(0f, 1f)
            repeatMode = if (loopState.value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        val start = skipStartState.value.coerceAtLeast(0L)
                        if (start > 0L && currentPosition < start) {
                            seekTo(start)
                        }
                        ready = true
                        endedFired = false
                    }
                    if (state == Player.STATE_ENDED) fireEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    ready = false
                    fireEnded()
                }
            })
        }
        player = exo
        onDispose {
            exo.playWhenReady = false
            exo.pause()
            exo.stop()
            exo.release()
            player = null
            ready = false
        }
    }
    LaunchedEffect(url, token, player) {
        val exo = player ?: return@LaunchedEffect
        if (url.isBlank()) return@LaunchedEffect
        ready = false
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        if (token.isNotBlank()) {
            http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        }
        exo.playWhenReady = false
        exo.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(ExoMediaItem.fromUri(url)))
        exo.prepare()
        val wantPlay = resumed && !pausedState.value
        exo.playWhenReady = wantPlay
        if (!wantPlay) exo.pause()
    }
    LaunchedEffect(loop, player) {
        player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(trailerVolume, player) {
        player?.volume = trailerVolume.coerceIn(0f, 1f)
    }
    LaunchedEffect(resumed, paused, player) {
        val exo = player ?: return@LaunchedEffect
        val wantPlay = resumed && !paused
        exo.playWhenReady = wantPlay
        if (!wantPlay) exo.pause()
    }
    // Cut outro: loop back to skip-start, or fire ended for one-shot teasers.
    LaunchedEffect(player, skipEndMs, skipStartMs, loop, ready) {
        val exo = player ?: return@LaunchedEffect
        if (!ready || skipEndMs <= 0L) return@LaunchedEffect
        while (true) {
            delay(400)
            val dur = exo.duration
            if (dur <= 0L || dur == C.TIME_UNSET) continue
            val endAt = (dur - skipEndMs).coerceAtLeast(skipStartMs + 1_000L)
            if (exo.currentPosition >= endAt) {
                if (loop) {
                    exo.seekTo(skipStartMs.coerceAtLeast(0L))
                } else {
                    endedCb.value?.invoke()
                    break
                }
            }
        }
    }
    val exo = player
    // SurfaceView: cheap on TV SoCs; alpha fade is unreliable so we cut when ready.
    // Keep surface while paused so hold-scroll doesn't tear down the decoder path.
    if (ready && resumed && exo != null) {
        CroppedTrailerSurface(
            player = exo,
            cropBiasX = cropBiasX,
            cropBiasY = cropBiasY,
            modifier = modifier,
        )
    }
}

/**
 * Crop-fill the player into [modifier], panning with [cropBiasX]/[cropBiasY] (-1..1).
 * Same idea as [ContentScale.Crop] + [BiasAlignment] for images.
 */
@OptIn(UnstableApi::class)
@Composable
private fun CroppedTrailerSurface(
    player: Player,
    cropBiasX: Float,
    cropBiasY: Float,
    modifier: Modifier = Modifier,
) {
    val presentationState = rememberPresentationState(player, keepContentOnReset = false)
    val density = LocalDensity.current
    val align = BiasAlignment(
        horizontalBias = cropBiasX.coerceIn(-1f, 1f),
        verticalBias = cropBiasY.coerceIn(-1f, 1f),
    )
    Box(modifier.clipToBounds()) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(align)
                .then(
                    presentationState.videoSizeDp?.let { srcSizeDp ->
                        Modifier.layout { measurable, constraints ->
                            val srcSizePx = with(density) {
                                Size(Dp(srcSizeDp.width).toPx(), Dp(srcSizeDp.height).toPx())
                            }
                            val dstSizePx = Size(
                                constraints.maxWidth.toFloat(),
                                constraints.maxHeight.toFloat(),
                            )
                            val scale = ContentScale.Crop.computeScaleFactor(srcSizePx, dstSizePx)
                            val placeable = measurable.measure(
                                constraints.copy(
                                    maxWidth = (srcSizePx.width * scale.scaleX).roundToInt()
                                        .coerceAtLeast(0),
                                    maxHeight = (srcSizePx.height * scale.scaleY).roundToInt()
                                        .coerceAtLeast(0),
                                ),
                            )
                            layout(placeable.width, placeable.height) {
                                placeable.place(0, 0)
                            }
                        }
                    } ?: Modifier,
                ),
        )
        if (presentationState.coverSurface) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

@Composable
private fun ImmersiveContentBlock(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val meta = remember(item) { item.heroMetaLine().ifBlank { item.cardMetaLine() } }
    val genres = remember(item) { item.heroGenres() }
    val plot = remember(item) { item.heroDescription() }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (meta.isNotBlank() || genres.isNotEmpty()) {
            Text(
                buildString {
                    if (meta.isNotBlank()) append(meta)
                    if (genres.isNotEmpty()) {
                        if (isNotEmpty()) append("  •  ")
                        append(genres.joinToString(" / "))
                    }
                },
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            item.headline(),
            style = CoogType.heroTitle.copy(fontSize = 34.sp, lineHeight = 40.sp),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (plot.isNotBlank()) {
            Text(
                plot,
                style = CoogType.heroPlot,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact Continue strip — progress-first posters, no immersive backdrop / trailer.
 * Shelf-shell focus: Left/Right select, OK resumes, long-OK menu.
 */
@Composable
internal fun ContinueWatchingRow(
    items: List<MediaItem>,
    label: String,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    metrics: ContinueCardMetrics,
    inset: Dp,
    active: Boolean,
    firstFocus: FocusRequester,
    exitUp: Boolean,
    onOpen: (MediaItem) -> Unit,
    onCardMenu: ((MediaItem) -> Unit)?,
    onRowFocused: () -> Unit,
    onVerticalMove: (Int) -> Boolean,
    onSelectionMoving: (() -> Unit)? = null,
    onSettledSelectedId: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val jobsRev = jobsMarkRevision(jobs)
    val marksById = remember(items, jobsRev, library) {
        items.associate { it.id to it.cardMark(jobs, library) }
    }
    ShelfFocusRow(
        items = items,
        active = active,
        firstFocus = firstFocus,
        exitUp = exitUp,
        onSelectionMoving = onSelectionMoving,
        onSettledSelectedId = onSettledSelectedId,
        onOpen = onOpen,
        onCardMenu = onCardMenu,
        onRowFocused = onRowFocused,
        onVerticalMove = onVerticalMove,
        modifier = modifier.fillMaxWidth(),
    ) { _, selectedId, extras, animateScroll, settled, bindSyncHop ->
        val listState = rememberLazyListState()
        val density = LocalDensity.current
        // Pitch uses peek width; actual pin waits for billboard width (deferPin).
        val pitchPx = with(density) { (metrics.width + metrics.gap).toPx() }
        PrefetchShelfThumbs(
            items = items,
            selectedId = selectedId,
            landscape = true,
            alsoPosters = true,
            enabled = settled,
        )
        // Same hop backend as immersive shelves; deferPin keeps peek↔billboard visuals correct.
        BindShelfSyncHop(
            listState = listState,
            selectedId = selectedId,
            items = items,
            pitchPx = pitchPx,
            animateScroll = animateScroll,
            bindSyncHop = bindSyncHop,
            deferPin = true,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = CoogType.shelfTitle,
                modifier = Modifier.padding(start = inset, bottom = 2.dp),
            )
            LazyRow(
                state = listState,
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                contentPadding = PaddingValues(start = inset, end = inset),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, raw ->
                    val item = extras[raw.id] ?: raw
                    val selected = item.id == selectedId
                    val focused = active && selected
                    // Large focused card stays on every hop — required Continue look.
                    ContinuePosterCard(
                        item = item,
                        mark = marksById[item.id],
                        width = if (selected) metrics.focusedWidth else metrics.width,
                        height = metrics.height,
                        focused = focused,
                        billboard = selected,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinuePosterCard(
    item: MediaItem,
    mark: CardMark?,
    width: Dp,
    height: Dp,
    focused: Boolean,
    billboard: Boolean,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(ContinueCardShape)
            .border(
                width = 3.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = ContinueCardShape,
            )
            .background(Color.White.copy(alpha = 0.06f)),
    ) {
        if (billboard) {
            // Instant plate from cached poster thumb; backdrop thumb fades in on top.
            PosterArt(
                item = item,
                kind = ArtKind.Poster,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                preferDisplay = false,
                mark = null,
                crossfadeMs = 0,
                modifier = Modifier.fillMaxSize(),
            )
            PosterArt(
                item = item,
                kind = ArtKind.Backdrop,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                preferDisplay = false,
                mark = mark,
                crossfadeMs = 0,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height * 0.42f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
            )
            TitleLockup(
                item = item,
                logoHeight = BillboardLogoHeight,
                logoMaxWidth = BillboardLogoMaxWidth,
                preferLogo = true,
                titleStyle = CoogType.shelfTitle.copy(
                    fontSize = 13.sp,
                    shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset.Zero, 10f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        } else {
            PosterArt(
                item = item,
                kind = ArtKind.Poster,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                preferDisplay = false,
                mark = mark,
                crossfadeMs = 0,
                modifier = Modifier.fillMaxSize(),
            )
        }
        WatchProgressBar(
            item = item,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * Immersive List content grid row — 16:9 cards; focused card scales 1.1 + border.
 */
@Composable
internal fun ImmersiveCardRow(
    items: List<MediaItem>,
    label: String,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    metrics: ImmersiveCardMetrics,
    inset: Dp,
    active: Boolean,
    firstFocus: FocusRequester,
    exitUp: Boolean,
    /** Seed only — hops stay local until [onSettledSelectedId]. */
    initialSelectedId: String? = null,
    onSettledSelectedId: ((String) -> Unit)? = null,
    onSelectionMoving: (() -> Unit)? = null,
    onOpen: (MediaItem) -> Unit,
    onCardMenu: ((MediaItem) -> Unit)?,
    onRowFocused: () -> Unit,
    onVerticalMove: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val jobsRev = jobsMarkRevision(jobs)
    val marksById = remember(items, jobsRev, library) {
        items.associate { it.id to it.cardMark(jobs, library) }
    }
    ShelfFocusRow(
        items = items,
        active = active,
        firstFocus = firstFocus,
        exitUp = exitUp,
        initialSelectedId = initialSelectedId,
        onSettledSelectedId = onSettledSelectedId,
        onSelectionMoving = onSelectionMoving,
        onOpen = onOpen,
        onCardMenu = onCardMenu,
        onRowFocused = onRowFocused,
        onVerticalMove = onVerticalMove,
        modifier = modifier.fillMaxWidth(),
    ) { _, sel, extras, animateScroll, settled, bindSyncHop ->
        val listState = rememberLazyListState()
        val density = LocalDensity.current
        val pitchPx = with(density) { (metrics.width + metrics.gap).toPx() }
        PrefetchShelfThumbs(
            items = items,
            selectedId = sel,
            landscape = true,
            enabled = settled,
        )
        // Constant-width cards: key-sync hop on hold; animate on single tap.
        BindShelfSyncHop(
            listState = listState,
            selectedId = sel,
            items = items,
            pitchPx = pitchPx,
            animateScroll = animateScroll,
            bindSyncHop = bindSyncHop,
        )
        // Logos load immediately; TitleLockup sticky URL keeps them through settle flips.
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = CoogType.shelfTitle,
                modifier = Modifier.padding(start = inset, bottom = 4.dp),
            )
            LazyRow(
                state = listState,
                userScrollEnabled = false,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                contentPadding = PaddingValues(start = inset, end = inset),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.height + 8.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, raw ->
                    val item = extras[raw.id] ?: raw
                    val focused = active && item.id == sel
                    ImmersiveThumbCard(
                        item = item,
                        mark = marksById[item.id],
                        width = metrics.width,
                        height = metrics.height,
                        focused = focused,
                        preferLogo = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveThumbCard(
    item: MediaItem,
    mark: CardMark?,
    width: Dp,
    height: Dp,
    focused: Boolean,
    preferLogo: Boolean,
) {
    val density = LocalDensity.current
    val ringStrokePx = with(density) { 3.dp.toPx() }
    val ringRadiusPx = with(density) { 12.dp.toPx() }
    // Read in draw — hop updates focused without border Modifier recomposition cost.
    val focusedState = rememberUpdatedState(focused)
    Box(
        modifier = Modifier
            .width(width)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(ImmersiveCardShape)
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = if (focusedState.value) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.10f)
                        },
                        cornerRadius = CornerRadius(ringRadiusPx),
                        style = Stroke(width = ringStrokePx),
                    )
                },
        ) {
            // Billboard plate: landscape backdrop thumb (≤480) + logo/title.
            PosterArt(
                item = item,
                kind = ArtKind.Backdrop,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                preferDisplay = false,
                mark = mark,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height * 0.48f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
            )
            TitleLockup(
                item = item,
                logoHeight = BillboardLogoHeight,
                logoMaxWidth = BillboardLogoMaxWidth,
                preferLogo = preferLogo,
                titleStyle = CoogType.shelfTitle.copy(
                    fontSize = 13.sp,
                    shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset.Zero, 10f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Delta to pin [index] to the leading content edge. LazyRow contentPadding means a
 * correctly-pinned item has offset == beforeContentPadding — scrolling by raw offset
 * shoved the first card under the inset (slightly off-screen left).
 */
private fun LazyListState.pinDeltaPx(index: Int, from: Int, pitchPx: Float): Float {
    val info = layoutInfo
    val pad = info.beforeContentPadding
    val target = info.visibleItemsInfo.find { it.index == index }
    return if (target != null) {
        (target.offset - pad).toFloat()
    } else {
        (index - from) * pitchPx
    }
}

@Composable
private fun BindShelfSyncHop(
    listState: LazyListState,
    selectedId: String,
    items: List<MediaItem>,
    pitchPx: Float,
    animateScroll: Boolean,
    bindSyncHop: (((Int) -> Unit) -> Unit),
    /**
     * Continue: peek↔billboard width must land before pin, or card 2+ parks wrong.
     * Immersive: constant width — pin in the same key event.
     */
    deferPin: Boolean = false,
) {
    val anchor = remember { intArrayOf(-1) }
    val itemsState = rememberUpdatedState(items)
    val scope = rememberCoroutineScope()
    val hopJob = remember { arrayOf<Job?>(null) }

    suspend fun pinTo(index: Int, from: Int, animate: Boolean) {
        if (deferPin) {
            withFrameNanos { }
            withFrameNanos { }
        }
        if (index == 0 || deferPin) {
            // Variable-width Continue: pin after billboard width. Index 0 always exact.
            if (animate && index != 0) {
                runCatching { listState.animateScrollToItem(index) }
            } else {
                runCatching { listState.scrollToItem(index) }
                if (deferPin && index == 0) {
                    withFrameNanos { }
                    runCatching { listState.scrollToItem(0) }
                }
            }
            return
        }
        val dx = listState.pinDeltaPx(index, from, pitchPx)
        if (dx != 0f) {
            if (animate) {
                listState.animateScrollBy(dx, tween(HomeTapScrollMs, easing = EaseOut))
            } else {
                listState.dispatchRawDelta(dx)
            }
        } else if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            if (animate) {
                runCatching { listState.animateScrollToItem(index) }
            } else {
                runCatching { listState.scrollToItem(index) }
            }
        }
    }

    val hop = remember(listState, pitchPx, deferPin) {
        { deltaItems: Int ->
            // delta 0 = KeyUp snap to current anchor.
            if (deltaItems == 0) {
                val i = anchor[0]
                if (i >= 0) {
                    hopJob[0]?.cancel()
                    hopJob[0] = scope.launch {
                        pinTo(i, i, animate = false)
                    }
                }
                return@remember
            }
            val live = itemsState.value
            val from = anchor[0].takeIf { it >= 0 } ?: 0
            val to = (from + deltaItems).coerceIn(0, live.lastIndex)
            if (to == from) return@remember
            hopJob[0]?.cancel()
            anchor[0] = to
            if (deferPin) {
                // setSel runs right after this invoke — wait for billboard width, then pin.
                hopJob[0] = scope.launch {
                    pinTo(to, from, animate = false)
                }
            } else if (to == 0) {
                hopJob[0] = scope.launch {
                    runCatching { listState.scrollToItem(0) }
                }
            } else {
                val dx = listState.pinDeltaPx(to, from, pitchPx)
                if (dx != 0f) listState.dispatchRawDelta(dx)
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == to }) {
                    hopJob[0] = scope.launch {
                        runCatching { listState.scrollToItem(to) }
                    }
                }
            }
        }
    }
    DisposableEffect(hop) {
        bindSyncHop(hop)
        onDispose {
            hopJob[0]?.cancel()
            bindSyncHop { }
        }
    }
    LaunchedEffect(selectedId, animateScroll) {
        val i = itemsState.value.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        when {
            anchor[0] < 0 -> {
                hopJob[0]?.cancel()
                hopJob[0] = scope.launch {
                    pinTo(i, 0, animate = false)
                }
                anchor[0] = i
            }
            animateScroll -> {
                hopJob[0]?.cancel()
                hopJob[0] = scope.launch {
                    pinTo(i, anchor[0].coerceAtLeast(0), animate = true)
                }
                anchor[0] = i
            }
            else -> {
                // Hold: hop() already scheduled / applied the pin.
                anchor[0] = i
            }
        }
    }
}

@Composable
private fun ShelfFocusRow(
    items: List<MediaItem>,
    active: Boolean,
    firstFocus: FocusRequester,
    exitUp: Boolean,
    onOpen: (MediaItem) -> Unit,
    onCardMenu: ((MediaItem) -> Unit)?,
    onRowFocused: () -> Unit,
    onVerticalMove: (Int) -> Boolean,
    modifier: Modifier = Modifier,
    initialSelectedId: String? = null,
    onSettledSelectedId: ((String) -> Unit)? = null,
    onSelectionMoving: (() -> Unit)? = null,
    settleMs: Long = HomeShelfSettleMs,
    content: @Composable (
        index: Int,
        selectedId: String,
        extras: Map<String, MediaItem>,
        animateScroll: Boolean,
        settled: Boolean,
        bindSyncHop: (((Int) -> Unit) -> Unit),
    ) -> Unit,
) {
    val enterRail = LocalEnterRail.current
    val server = LocalCoogServer.current
    val seed = initialSelectedId?.takeIf { id -> items.any { it.id == id } }
        ?: items.first().id
    // Refs keep Left/Right hops advancing when several KeyDowns arrive before recompose
    // (composed `index`/`settled` were stale → ~1 hop/sec under rapid D-pad).
    val selRef = remember { arrayOf(seed) }
    val settledRef = remember { booleanArrayOf(true) }
    val lastHopAtMs = remember { longArrayOf(0L) }
    val holdingHoriz = remember { booleanArrayOf(false) }
    val syncHop = remember { arrayOf<((Int) -> Unit)?>(null) }
    var sel by remember(items.firstOrNull()?.id) {
        selRef[0] = seed
        mutableStateOf(seed)
    }
    fun setSel(id: String) {
        if (id == selRef[0]) return
        selRef[0] = id
        lastHopAtMs[0] = SystemClock.elapsedRealtime()
        sel = id
    }
    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
    val index = indexOf(sel)
    // Single tap animates; hold glides via syncHop. Continue uses deferPin for billboard width.
    var animateScroll by remember { mutableStateOf(true) }
    var settled by remember { mutableStateOf(true) }
    val idsKey = remember(items) { items.joinToString { it.id } }
    val movingCb = rememberUpdatedState(onSelectionMoving)
    val settledCb = rememberUpdatedState(onSettledSelectedId)
    val itemsState = rememberUpdatedState(items)
    LaunchedEffect(idsKey) {
        if (items.none { it.id == selRef[0] }) {
            setSel(items.firstOrNull()?.id ?: return@LaunchedEffect)
        }
    }
    LaunchedEffect(sel) {
        val i = indexOf(sel)
        // Quiet while holding — hop log contended with scroll on Main.
        if (!holdingHoriz[0]) {
            CoogJankLog.hop("home-shelf", "sel=${sel.take(28)} i=$i n=${items.size}")
        }
        settled = false
        settledRef[0] = false
        movingCb.value?.invoke()
        delay(settleMs)
        settled = true
        settledRef[0] = true
        settledCb.value?.invoke(sel)
    }
    var extras by remember { mutableStateOf<Map<String, MediaItem>>(emptyMap()) }
    var selectPressed by remember { mutableStateOf(false) }
    var selectLongHandled by remember { mutableStateOf(false) }
    val featured = extras[items[index].id] ?: items[index]

    LaunchedEffect(selectPressed, featured.id) {
        if (!selectPressed) return@LaunchedEffect
        selectLongHandled = false
        delay(550)
        if (selectPressed && onCardMenu != null) {
            selectLongHandled = true
            onCardMenu(featured)
        }
    }

    // Skip immersive-art network work while D-pad is still hopping.
    LaunchedEffect(idsKey, sel, settled, server.url, server.token) {
        if (!settled) return@LaunchedEffect
        val api = CoogApi(server.url, server.token)
        val focus = items.indexOfFirst { it.id == sel }.coerceAtLeast(0)
        val from = (focus - ArtPrefetchRadius).coerceAtLeast(0)
        val to = (focus + ArtPrefetchRadius).coerceAtMost(items.lastIndex)
        for (j in from..to) {
            val raw = items[j]
            val cur = extras[raw.id] ?: raw
            if (hasImmersiveArt(cur)) continue
            if (j != focus) delay(40L)
            val remote = resolveImmersiveArt(api, raw) ?: continue
            extras = extras + (raw.id to mergeDetails(raw, remote))
        }
    }

    // Shared AppShell entry FocusRequester may only bind on the visible tab pane.
    val contentEntry = LocalBrowseContentFocus.current
    val paneActive = LocalBrowseActive.current
    val bindEntryFocus = paneActive || contentEntry == null || firstFocus !== contentEntry
    Box(
        modifier = modifier
            .then(if (bindEntryFocus) Modifier.focusRequester(firstFocus) else Modifier)
            .focusable()
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
            }
            .onFocusChanged { if (it.isFocused) onRowFocused() }
            .onPreviewKeyEvent { event ->
                val left = event.key == Key.DirectionLeft
                val right = event.key == Key.DirectionRight
                val up = event.key == Key.DirectionUp
                val down = event.key == Key.DirectionDown
                val select = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!left && !right && !up && !down && !select) return@onPreviewKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount > 0
                val live = itemsState.value
                val cur = live.indexOfFirst { it.id == selRef[0] }.takeIf { it >= 0 } ?: 0
                val toMenu = (exitUp && up) || (exitUp && cur == 0 && left)
                if (toMenu) {
                    if (event.type == KeyEventType.KeyDown && !repeat) enterRail()
                    return@onPreviewKeyEvent true
                }
                if (select) {
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
                if ((left || right) && event.type == KeyEventType.KeyUp) {
                    if (holdingHoriz[0]) {
                        // Snap selected card to leading edge after hold drift.
                        syncHop[0]?.invoke(0)
                    }
                    holdingHoriz[0] = false
                }
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        right && cur < live.lastIndex -> {
                            if (repeat &&
                                SystemClock.elapsedRealtime() - lastHopAtMs[0] < HomeHoldMinIntervalMs
                            ) {
                                return@onPreviewKeyEvent true
                            }
                            if (repeat) holdingHoriz[0] = true
                            animateScroll = !repeat
                            // Hold: sync pin (pad-corrected). Tap: decelerate after compose.
                            if (repeat) syncHop[0]?.invoke(+1)
                            setSel(live[cur + 1].id)
                        }
                        left && cur > 0 -> {
                            if (repeat &&
                                SystemClock.elapsedRealtime() - lastHopAtMs[0] < HomeHoldMinIntervalMs
                            ) {
                                return@onPreviewKeyEvent true
                            }
                            if (repeat) holdingHoriz[0] = true
                            animateScroll = !repeat
                            if (repeat) syncHop[0]?.invoke(-1)
                            setSel(live[cur - 1].id)
                        }
                        // Vertical: same EaseInOut row transition; allow hold repeats.
                        down -> {
                            if (repeat &&
                                SystemClock.elapsedRealtime() - lastHopAtMs[0] < HomeHoldMinIntervalMs
                            ) {
                                return@onPreviewKeyEvent true
                            }
                            lastHopAtMs[0] = SystemClock.elapsedRealtime()
                            onVerticalMove(1)
                        }
                        up && !exitUp -> {
                            if (repeat &&
                                SystemClock.elapsedRealtime() - lastHopAtMs[0] < HomeHoldMinIntervalMs
                            ) {
                                return@onPreviewKeyEvent true
                            }
                            lastHopAtMs[0] = SystemClock.elapsedRealtime()
                            onVerticalMove(-1)
                        }
                    }
                }
                left || right || down || (up && !exitUp)
            },
    ) {
        content(index, sel, extras, animateScroll, settled) { hop -> syncHop[0] = hop }
    }
}

private fun hasImmersiveArt(item: MediaItem): Boolean {
    val poster = item.posterUrl
    val backdrop = item.backdropUrl
    fun remoteCdn(url: String) =
        url.startsWith("http") && !url.contains("/api/v1/media/")
    return remoteCdn(poster) || remoteCdn(backdrop)
}

private suspend fun resolveImmersiveArt(api: CoogApi, item: MediaItem): MediaItem? {
    val kind = item.kind.ifBlank { "movie" }
    val remote = when {
        item.imdbId.isNotBlank() -> runCatching { api.catalogTitle(item.imdbId, kind) }.getOrNull()
        item.tmdbId != 0 -> runCatching { api.catalogTmdb(kind, item.tmdbId) }.getOrNull()
        else -> catalogMatch(api, item)
    } ?: return null
    if (remote.imdbId.isNotBlank() || remote.tmdbId == 0) return remote
    val hydrated = runCatching {
        api.catalogTmdb(remote.kind.ifBlank { kind }, remote.tmdbId)
    }.getOrNull() ?: return remote
    return if (hydrated.imdbId.isNotBlank()) hydrated else remote
}
