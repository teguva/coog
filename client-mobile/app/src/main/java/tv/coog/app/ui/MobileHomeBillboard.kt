package tv.coog.app.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Text
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.player.buildTrailerExoPlayer
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogType
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Survives LazyColumn billboard dispose so scroll-away can pause/resume.
 * Must be [release]d when leaving Home (tab change / overlay) so audio never
 * continues in the background.
 */
internal class HomeBillboardTrailerSession {
    private var player: ExoPlayer? = null
    private var loadedUrl: String? = null
    private var loadedToken: String? = null
    private val finishedFired = AtomicBoolean(false)
    var onFinished: (() -> Unit)? = null

    fun hasLoaded(url: String): Boolean =
        player != null && loadedUrl == url

    fun acquire(app: Application, url: String, token: String): ExoPlayer {
        val existing = player
        if (existing != null && loadedUrl == url && loadedToken == token) {
            return existing
        }
        releasePlayer()
        finishedFired.set(false)
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        if (token.isNotBlank()) {
            http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        }
        val exo = buildTrailerExoPlayer(app).apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(ExoMediaItem.fromUri(url)))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && finishedFired.compareAndSet(false, true)) {
                        playWhenReady = false
                        onFinished?.invoke()
                    }
                }
            })
            prepare()
            playWhenReady = false
        }
        player = exo
        loadedUrl = url
        loadedToken = token
        return exo
    }

    fun pause() {
        player?.let { exo ->
            exo.playWhenReady = false
            exo.pause()
        }
    }

    fun release() {
        onFinished = null
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let { exo ->
            exo.playWhenReady = false
            exo.stop()
            exo.release()
        }
        player = null
        loadedUrl = null
        loadedToken = null
        finishedFired.set(false)
    }
}

/**
 * Phone/tablet home billboard: backdrop first, then delayed muted trailer.
 * Not driven by shelf focus (TV FeaturedCarousel pattern).
 */
@Composable
internal fun MobileHomeBillboard(
    item: MediaItem,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    height: Dp,
    inset: Dp,
    trailerActive: Boolean,
    trailerSession: HomeBillboardTrailerSession,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    onMutedChange: (Boolean) -> Unit = {},
    onTrailerFinished: () -> Unit = {},
) {
    var trailerReady by remember { mutableStateOf(false) }
    val tagline = item.heroSubtitle()
    val plot = item.heroDescription()
    val resume = item.isLocal() && (item.positionMs > 0L || item.season > 0 || item.episode > 0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(CoogBgDeep)
            .clipToBounds(),
    ) {
        PosterArt(
            item = item,
            kind = ArtKind.Backdrop,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            preferDisplay = true,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.canPlayTrailer()) {
            BillboardTrailer(
                item = item,
                muted = muted,
                active = trailerActive,
                session = trailerSession,
                onReady = { trailerReady = it },
                onFinished = onTrailerFinished,
                modifier = Modifier.fillMaxSize(),
            )
        }
        LaunchedEffect(item.id) {
            trailerReady = false
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.22f),
                        0.40f to Color.Transparent,
                        0.68f to CoogBgDeep.copy(alpha = 0.55f),
                        1.00f to CoogBgDeep,
                    ),
                ),
        ) {}
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to CoogBgDeep.copy(alpha = 0.78f),
                        0.38f to CoogBgDeep.copy(alpha = 0.32f),
                        0.72f to Color.Transparent,
                        1.00f to Color.Transparent,
                    ),
                ),
        ) {}
        if (trailerReady) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = MobileTopBarHeight + 8.dp, end = inset.coerceAtLeast(16.dp))
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .clickable { onMutedChange(!muted) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = if (muted) "Unmute trailer" else "Mute trailer",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = inset, end = inset, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitleLockup(item = item, logoHeight = 56.dp)
            if (tagline.isNotBlank()) {
                Text(
                    tagline,
                    style = CoogType.heroTagline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (plot.isNotBlank()) {
                Text(
                    plot,
                    style = CoogType.heroPlot,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.92f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.cardMark(jobs, library)?.let { StatusMark(mark = it, size = MarkSize.Comfort) }
                item.matchPercent()?.let { MatchMark(percent = it, size = MarkSize.Comfort) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WhitePill(label = if (resume) "Resume" else "Play", onClick = onPlay)
                GhostButton(label = "More info", onClick = onMoreInfo)
            }
        }
    }
}

@Composable
private fun BillboardTrailer(
    item: MediaItem,
    muted: Boolean,
    active: Boolean,
    session: HomeBillboardTrailerSession,
    modifier: Modifier = Modifier,
    onReady: (Boolean) -> Unit = {},
    onFinished: () -> Unit = {},
) {
    val server = LocalCoogServer.current
    val mediaId = item.trailerMediaId()
    val url = server.trailerUrl(mediaId)
    var start by remember(mediaId) { mutableStateOf(session.hasLoaded(url)) }
    LaunchedEffect(mediaId, server.url, server.token, server.adultSession, url) {
        if (session.hasLoaded(url)) {
            start = true
            return@LaunchedEffect
        }
        start = false
        onReady(false)
        if (mediaId.isBlank() || server.url.isBlank()) return@LaunchedEffect
        delay(1_000)
        val api = CoogApi(server.url, server.token, server.adultSession)
        start = runCatching { api.waitForTrailer(mediaId) }.getOrDefault(false)
    }
    if (start && url.isNotBlank()) {
        BillboardTrailerPlayer(
            url = url,
            token = server.token,
            muted = muted,
            active = active,
            session = session,
            onReady = onReady,
            onFinished = onFinished,
            modifier = modifier,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun BillboardTrailerPlayer(
    url: String,
    token: String,
    muted: Boolean,
    active: Boolean,
    session: HomeBillboardTrailerSession,
    modifier: Modifier = Modifier,
    onReady: (Boolean) -> Unit = {},
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ready by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val onFinishedState by rememberUpdatedState(onFinished)
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

    val shouldPlay = resumed && active
    LaunchedEffect(url, token, shouldPlay, session) {
        session.onFinished = { onFinishedState() }
        val app = context.applicationContext as Application
        if (!shouldPlay) {
            session.pause()
            // Keep a handle if the session still owns this URL (scroll pause).
            player = if (session.hasLoaded(url)) session.acquire(app, url, token) else null
            ready = player?.playbackState == Player.STATE_READY
            return@LaunchedEffect
        }
        val exo = session.acquire(app, url, token)
        player = exo
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready = true
            }

            override fun onPlayerError(error: PlaybackException) {
                ready = false
            }
        }
        exo.addListener(listener)
        ready = exo.playbackState == Player.STATE_READY
        exo.volume = if (muted) 0f else 1f
        exo.playWhenReady = true
        try {
            // Stay subscribed until cancelled (url/active change).
            awaitCancellation()
        } finally {
            exo.removeListener(listener)
        }
    }
    DisposableEffect(session) {
        onDispose {
            // Billboard left composition (scrolled away) — pause only; session keeps position.
            session.pause()
            player = null
            ready = false
            onReady(false)
        }
    }
    LaunchedEffect(muted, player) {
        player?.volume = if (muted) 0f else 1f
    }
    val exo = player
    // Keep the last frame visible while paused in-viewport; hide only when not ready.
    AnimatedVisibility(visible = ready && exo != null && shouldPlay, enter = fadeIn(), exit = fadeOut()) {
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
