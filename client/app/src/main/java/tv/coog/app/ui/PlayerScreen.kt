package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import android.os.SystemClock
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.coog.app.data.CoogApi
import tv.coog.app.data.MediaItem
import tv.coog.app.data.PlaybackSession
import tv.coog.app.data.PlaybackSyncClient
import tv.coog.app.data.SubtitleTrack
import tv.coog.app.player.PlayerTrack
import tv.coog.app.player.PlayerViewModel
import tv.coog.app.ui.theme.CoogFetch
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType
import androidx.compose.runtime.rememberCoroutineScope

    private enum class PlayerMenu { None, Audio, Subtitles, Picture }

private enum class HudRailAction { Play, Audio, Subtitles, Picture, Previous, Next }

/** How video is fitted into the TV frame. Default Fill keeps aspect ratio (no stretch). */
private enum class PictureMode(val label: String, val scale: ContentScale) {
    Fill("Fill", ContentScale.Fit),
    Crop("Crop", ContentScale.Crop),
    Stretch("Stretch", ContentScale.FillBounds),
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    session: PlaybackSession?,
    title: String,
    token: String,
    serverUrl: String,
    adultSession: String = "",
    item: MediaItem? = null,
    nextItem: MediaItem? = null,
    previousItem: MediaItem? = null,
    autoplayNext: Boolean = true,
    prefetchNext: Boolean = false,
    prefetchBeforeEndMinutes: Int = 5,
    onBack: () -> Unit,
    onPlayNeighbor: ((MediaItem) -> Unit)? = null,
    onPrefetchNeighbor: ((MediaItem) -> Unit)? = null,
    playerViewModel: PlayerViewModel = viewModel(),
) {
    val player = playerViewModel.player
    val playError by playerViewModel.error.collectAsState()
    val audioTracks by playerViewModel.audioTracks.collectAsState()
    val textTracks by playerViewModel.textTracks.collectAsState()
    val textOff by playerViewModel.textOff.collectAsState()
    val firstFrame by playerViewModel.firstFrame.collectAsState()
    val ended by playerViewModel.ended.collectAsState()
    val cueLines by playerViewModel.cueLines.collectAsState()
    val subtitleDelayMs by playerViewModel.subtitleDelayMs.collectAsState()
    val subtitleSize by playerViewModel.subtitleSize.collectAsState()
    // Full-screen loader only until the first frame. Seek/rebuffer must not cover video —
    // ExoPlayer enters BUFFERING on every seek and the logo overlay was fighting the scrub.
    val splash = playError == null && (session == null || !firstFrame)

    var hudVisible by remember { mutableStateOf(true) }
    var hudExpanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(PlayerMenu.None) }
    var pictureMode by remember { mutableStateOf(PictureMode.Fill) }
    var position by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(session?.expectedDurationMs ?: 0L) }
    var playing by remember { mutableStateOf(true) }
    var hudNonce by remember { mutableStateOf(0) }
    var jobBuffered by remember { mutableLongStateOf(session?.bufferedMs ?: 0L) }
    var jobExpected by remember { mutableLongStateOf(session?.expectedDurationMs ?: 0L) }
    var jobDownloading by remember { mutableStateOf(session?.method == "progressive") }
    var remoteTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var remoteError by remember { mutableStateOf("") }
    var remoteBusy by remember { mutableStateOf(false) }
    var selectedRemoteId by remember { mutableStateOf<String?>(null) }
    var autoLoadDone by remember { mutableStateOf(false) }
    var preferredLangs by remember { mutableStateOf(listOf("en")) }
    var autoLoad by remember { mutableStateOf(true) }
    var preferEmbedded by remember { mutableStateOf(true) }
    val syncClient = remember(serverUrl, token, adultSession) {
        if (adultSession.isNotBlank()) PlaybackSyncClient(CoogApi(serverUrl, token, adultSession)) else null
    }
    val scope = rememberCoroutineScope()

    val rootFocus = remember { FocusRequester() }
    var railSel by remember { mutableStateOf(0) }
    var menuSel by remember { mutableStateOf(0) }
    // Scrub preview while holding ←/→; ExoPlayer seek only commits on key-up.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubResumePlay by remember { mutableStateOf(false) }
    var scrubStartedAt by remember { mutableLongStateOf(0L) }

    fun bumpHud(expanded: Boolean = hudExpanded) {
        hudVisible = true
        hudExpanded = expanded
        hudNonce++
    }

    fun seekLimit(): Long {
        val caps = listOf(jobBuffered, buffered, duration).filter { it > 0 }
        return caps.minOrNull() ?: 0L
    }

    fun scrubStepMs(repeatCount: Int, heldMs: Long): Long = when {
        heldMs >= 5_000L || repeatCount >= 28 -> 5 * 60_000L
        heldMs >= 3_000L || repeatCount >= 16 -> 60_000L
        heldMs >= 1_500L || repeatCount >= 8 -> 30_000L
        heldMs >= 700L || repeatCount >= 3 -> 15_000L
        else -> 10_000L
    }

    fun nudgeScrub(deltaSign: Int, repeatCount: Int) {
        val limit = seekLimit().takeIf { it > 0 } ?: duration.coerceAtLeast(0L)
        val upper = if (limit > 0) limit else Long.MAX_VALUE
        if (scrubMs == null) {
            scrubResumePlay = player.isPlaying || player.playWhenReady
            if (scrubResumePlay) player.pause()
            scrubMs = player.currentPosition.coerceAtLeast(0L)
            scrubStartedAt = SystemClock.elapsedRealtime()
        }
        val held = SystemClock.elapsedRealtime() - scrubStartedAt
        val step = scrubStepMs(repeatCount, held)
        scrubMs = ((scrubMs ?: 0L) + deltaSign * step).coerceIn(0L, upper)
        menu = PlayerMenu.None
        bumpHud(expanded = false)
    }

    fun commitScrub() {
        val target = scrubMs ?: return
        scrubMs = null
        playerViewModel.seekTo(target, seekLimit())
        if (scrubResumePlay) {
            player.play()
            player.playWhenReady = true
        }
        scrubResumePlay = false
        bumpHud(expanded = false)
    }

    fun cancelScrub() {
        if (scrubMs == null) return
        scrubMs = null
        if (scrubResumePlay) {
            player.play()
            player.playWhenReady = true
        }
        scrubResumePlay = false
    }

    fun collapseHud() {
        cancelScrub()
        menu = PlayerMenu.None
        hudExpanded = false
        hudVisible = true
        hudNonce++
        runCatching { rootFocus.requestFocus() }
    }

    fun hideHud() {
        cancelScrub()
        menu = PlayerMenu.None
        hudExpanded = false
        hudVisible = false
        runCatching { rootFocus.requestFocus() }
    }

    fun expandHud() {
        menu = PlayerMenu.None
        railSel = 0
        hudVisible = true
        hudExpanded = true
        hudNonce++
    }

    fun togglePlay() {
        cancelScrub()
        playerViewModel.togglePlay()
        bumpHud(expanded = hudExpanded)
    }

    fun seekBy(deltaMs: Long) {
        // Kept for media-key one-shots that are not hold-scrubbed.
        cancelScrub()
        playerViewModel.seekBy(deltaMs, seekLimit())
        menu = PlayerMenu.None
        bumpHud(expanded = false)
    }

    fun reportWatch() {
        val media = item ?: return
        val pos = player.currentPosition
        val dur = duration.takeIf { it > 0 } ?: player.duration.takeIf { it > 0 } ?: 0L
        val mediaId = session?.mediaId.orEmpty().ifBlank { media.diskMediaId() }
        val url = serverUrl
        val tok = token
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { CoogApi(url, tok).reportProgress(media, pos, dur, mediaId) }
        }
    }

    fun rankRemote(track: SubtitleTrack): Int {
        val lang = track.language.lowercase()
        var score = when (track.source) {
            "sidecar" -> 30
            "embedded" -> 20
            "opensubtitles" -> 10
            else -> 0
        }
        if (lang.isNotBlank() && preferredLangs.isNotEmpty()) {
            val idx = preferredLangs.indexOfFirst { lang == it || lang.startsWith(it) }
            if (idx >= 0) score += 50 - idx * 5
        }
        return score
    }

    fun subtitleSourceTier(source: String): Int = when (source) {
        "embedded" -> 3
        "sidecar" -> 2
        "opensubtitles" -> 1
        else -> 0
    }

    /** In-file / beside-file first, then web — language preference only within a tier. */
    fun sortSubtitleTracks(tracks: List<SubtitleTrack>): List<SubtitleTrack> =
        tracks.sortedWith(
            compareByDescending<SubtitleTrack> { subtitleSourceTier(it.source) }
                .thenByDescending { rankRemote(it) }
                .thenBy { it.label },
        )

    val movieRemoteTracks = sortSubtitleTracks(remoteTracks.filter { subtitleSourceTier(it.source) >= 2 })
    val externalRemoteTracks = sortSubtitleTracks(remoteTracks.filter { subtitleSourceTier(it.source) < 2 })

    fun applyRemote(track: SubtitleTrack) {
        when {
            track.id == "off" || track.source == "none" -> {
                selectedRemoteId = null
                playerViewModel.clearExternalSubtitle()
            }
            track.source == "opensubtitles" || track.source == "sidecar" || track.source == "embedded" -> {
                val url = CoogApi(serverUrl, token).subtitleFileUrl(track.id)
                selectedRemoteId = track.id
                playerViewModel.setExternalSubtitle(url, track.language)
            }
        }
        bumpHud(expanded = true)
    }

    fun shortSubLabel(): String {
        if (selectedRemoteId != null) {
            val t = remoteTracks.firstOrNull { it.id == selectedRemoteId }
            return t?.language?.uppercase()?.ifBlank { null } ?: "On"
        }
        if (textOff || textTracks.isEmpty()) return "Off"
        val sel = textTracks.firstOrNull { it.selected }
        return sel?.language?.uppercase()?.ifBlank { null } ?: "On"
    }

    BackHandler {
        when {
            menu != PlayerMenu.None -> {
                menu = PlayerMenu.None
                bumpHud(expanded = true)
            }
            else -> {
                reportWatch()
                player.pause()
                onBack()
            }
        }
    }

    LaunchedEffect(item?.id, title) {
        playerViewModel.resetOpening()
        remoteTracks = emptyList()
        remoteError = ""
        selectedRemoteId = null
        autoLoadDone = false
        menu = PlayerMenu.None
        hudExpanded = false
        hudVisible = true
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(hudExpanded, menu) {
        // Always keep root focus — rail/menu use selection indices, not child focus.
        delay(40)
        runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(session?.url, token, adultSession, item?.positionMs, item?.id, item?.season, item?.episode) {
        val url = session?.url?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val mediaKey = listOf(
            item?.id.orEmpty(),
            item?.season?.toString().orEmpty(),
            item?.episode?.toString().orEmpty(),
            session?.mediaId.orEmpty(),
            session?.jobId.orEmpty(),
        ).joinToString("|")
        playerViewModel.play(
            url,
            token,
            startPositionMs = item?.positionMs ?: 0L,
            adultSession = adultSession,
            mediaKey = mediaKey,
        )
    }
    LaunchedEffect(session?.url, item?.id, serverUrl, token, firstFrame, adultSession) {
        if (!firstFrame || serverUrl.isBlank()) return@LaunchedEffect
        // Maize titles never have useful subs — skip OpenSubtitles / sidecar lookup.
        if (adultSession.isNotBlank()) {
            remoteTracks = emptyList()
            remoteBusy = false
            remoteError = ""
            autoLoadDone = true
            return@LaunchedEffect
        }
        remoteBusy = true
        try {
            val api = CoogApi(serverUrl, token)
            val media = item
            val res = api.listSubtitles(
                imdbId = media?.imdbId.orEmpty(),
                kind = media?.kind.orEmpty(),
                season = media?.season ?: 0,
                episode = media?.episode ?: 0,
                mediaId = session?.mediaId.orEmpty().ifBlank { media?.diskMediaId().orEmpty() },
                query = media?.title.orEmpty().ifBlank { media?.showTitle.orEmpty() },
                tmdbId = media?.tmdbId ?: 0,
            )
            preferredLangs = res.settings.languages.ifEmpty { listOf("en") }
            autoLoad = res.settings.autoLoad
            preferEmbedded = res.settings.preferEmbedded
            remoteError = res.error
            remoteTracks = sortSubtitleTracks(
                res.tracks.filter { it.id != "off" && it.source != "none" },
            ).take(12)
            if (!autoLoadDone && autoLoad && textOff && selectedRemoteId == null) {
                val embedded = textTracks.filter { it.language.isNotBlank() }
                val embeddedBest = if (preferEmbedded) {
                    embedded.maxByOrNull { track ->
                        val lang = track.language.lowercase()
                        val idx = preferredLangs.indexOfFirst { lang == it || lang.startsWith(it) }
                        if (idx >= 0) 50 - idx * 5 else 0
                    }?.takeIf { track ->
                        val lang = track.language.lowercase()
                        preferredLangs.any { lang == it || lang.startsWith(it) }
                    }
                } else null
                when {
                    embeddedBest != null -> playerViewModel.selectTrack(embeddedBest)
                    else -> remoteTracks.firstOrNull { rankRemote(it) > 0 }?.let { applyRemote(it) }
                }
                autoLoadDone = true
            }
        } catch (err: Exception) {
            remoteError = err.message.orEmpty()
        } finally {
            remoteBusy = false
        }
    }
    LaunchedEffect(playError, serverUrl, token, session?.id) {
        val message = playError ?: return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        runCatching {
            CoogApi(serverUrl, token).reportEvent(
                type = "player.error",
                message = listOfNotNull(
                    message,
                    active.method.takeIf { it.isNotBlank() }?.let { "method $it" },
                ).joinToString(" · "),
                mediaId = active.mediaId,
                jobId = active.jobId,
                sessionId = active.id,
            )
        }
    }
    LaunchedEffect(session?.jobId, serverUrl, token) {
        val jobId = session?.jobId.orEmpty()
        if (jobId.isBlank()) {
            playerViewModel.suppressEnded = false
            return@LaunchedEffect
        }
        val api = CoogApi(serverUrl, token)
        while (true) {
            try {
                val job = api.job(jobId)
                jobBuffered = job.bufferedMs
                if (job.expectedDurationMs > 0) {
                    jobExpected = job.expectedDurationMs
                    duration = job.expectedDurationMs
                }
                jobDownloading = job.status == "downloading" || job.status == "ready" || job.status == "queued"
                playerViewModel.suppressEnded = jobDownloading
                if (job.status == "finished" || job.status == "error" || job.status == "cancelled") {
                    playerViewModel.suppressEnded = false
                    break
                }
            } catch (_: Exception) {
                // Job removed after cancel/finish — stop treating this as a live download.
                playerViewModel.suppressEnded = false
                jobDownloading = false
                break
            }
            delay(1500)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            buffered = player.bufferedPosition
            playing = player.isPlaying
            duration = playbackDurationMs(
                exoDuration = player.duration,
                expectedMs = maxOf(session?.expectedDurationMs ?: 0L, jobExpected),
                bufferedMs = maxOf(jobBuffered, buffered),
                streaming = jobDownloading || session?.method == "progressive",
            )
            delay(250)
        }
    }
    LaunchedEffect(hudNonce, playing, hudExpanded, menu, hudVisible, scrubMs) {
        if (!hudVisible || !playing || hudExpanded || menu != PlayerMenu.None || scrubMs != null) {
            return@LaunchedEffect
        }
        delay(3500)
        hudVisible = false
    }
    LaunchedEffect(session?.url, item?.id, playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            delay(15_000)
            reportWatch()
        }
    }
    LaunchedEffect(ended, autoplayNext, nextItem?.id) {
        if (!ended || !autoplayNext) return@LaunchedEffect
        val next = nextItem ?: return@LaunchedEffect
        reportWatch()
        onPlayNeighbor?.invoke(next)
    }
    var prefetchSent by remember(session?.url, nextItem?.id) { mutableStateOf(false) }
    LaunchedEffect(position, duration, prefetchNext, nextItem?.id, prefetchBeforeEndMinutes) {
        if (!prefetchNext || prefetchSent) return@LaunchedEffect
        val next = nextItem ?: return@LaunchedEffect
        if (duration <= 0L) return@LaunchedEffect
        val windowMs = prefetchBeforeEndMinutes.coerceAtLeast(1) * 60_000L
        if (position >= (duration - windowMs).coerceAtLeast(0L)) {
            prefetchSent = true
            onPrefetchNeighbor?.invoke(next)
        }
    }
    DisposableEffect(session?.url, item?.id) {
        onDispose {
            reportWatch()
            syncClient?.stop()
        }
    }
    LaunchedEffect(session?.url, item?.id, adultSession, item?.hasFunscript) {
        val sync = syncClient ?: return@LaunchedEffect
        val media = item ?: return@LaunchedEffect
        val mediaId = session?.mediaId.orEmpty().ifBlank { media.diskMediaId() }
        if (mediaId.isBlank() || adultSession.isBlank() || !media.hasFunscript) {
            sync.stop()
            return@LaunchedEffect
        }
        // Only sync when interactive is supported and title has a script.
        val supported = runCatching { CoogApi(serverUrl, token, adultSession).maizeSyncStatus().supported }
            .getOrDefault(false)
        if (!supported) {
            sync.stop()
            return@LaunchedEffect
        }
        sync.start(
            mediaId = mediaId,
            resumeMs = media.positionMs.coerceAtLeast(0L),
            scope = scope,
            position = { player.currentPosition },
            playing = { player.isPlaying },
        )
    }

    val sizeLabel = when (subtitleSize) {
        0 -> "S"
        2 -> "L"
        else -> "M"
    }
    val cueSp = when (subtitleSize) {
        0 -> 22.sp
        2 -> 34.sp
        else -> 28.sp
    }
    val maizePlayback = adultSession.isNotBlank()
    val showAudioPicker = audioTracks.size > 1
    val showSubtitles = !maizePlayback
    val subsOn = showSubtitles && (selectedRemoteId != null || (!textOff && textTracks.any { it.selected }))
    val audioLabel = audioTracks.firstOrNull { it.selected }?.label?.take(16) ?: "Audio"
    val subLabel = shortSubLabel()
    val railActions = remember(
        showAudioPicker,
        showSubtitles,
        previousItem != null,
        nextItem != null,
    ) {
        buildList {
            add(HudRailAction.Play)
            if (showAudioPicker) add(HudRailAction.Audio)
            if (showSubtitles) add(HudRailAction.Subtitles)
            add(HudRailAction.Picture)
            if (previousItem != null) add(HudRailAction.Previous)
            if (nextItem != null) add(HudRailAction.Next)
        }
    }
    val railCount = railActions.size
    LaunchedEffect(railCount) {
        if (railSel >= railCount) railSel = (railCount - 1).coerceAtLeast(0)
    }
    LaunchedEffect(showAudioPicker, menu) {
        if (!showAudioPicker && menu == PlayerMenu.Audio) {
            menu = PlayerMenu.None
        }
    }

    fun subtitleMenuActions(): List<() -> Unit> = buildList {
        if (subsOn) {
            add {
                playerViewModel.nudgeSubtitleDelay(-250)
                bumpHud(expanded = true)
            }
            add {
                playerViewModel.nudgeSubtitleDelay(250)
                bumpHud(expanded = true)
            }
            add {
                playerViewModel.cycleSubtitleSize()
                bumpHud(expanded = true)
            }
        }
        add {
            selectedRemoteId = null
            playerViewModel.clearExternalSubtitle()
            bumpHud(expanded = true)
        }
        // In-container tracks first, then sidecar/embedded files, then web.
        textTracks.forEach { track ->
            add {
                selectedRemoteId = null
                playerViewModel.selectTrack(track)
                bumpHud(expanded = true)
            }
        }
        movieRemoteTracks.forEach { track ->
            add { applyRemote(track) }
        }
        externalRemoteTracks.forEach { track ->
            add { applyRemote(track) }
        }
    }

    fun activateRail() {
        when (railActions.getOrNull(railSel.coerceIn(0, (railCount - 1).coerceAtLeast(0)))) {
            HudRailAction.Play -> togglePlay()
            HudRailAction.Audio -> {
                menu = if (menu == PlayerMenu.Audio) PlayerMenu.None else PlayerMenu.Audio
                menuSel = 0
                bumpHud(expanded = true)
            }
            HudRailAction.Subtitles -> {
                menu = if (menu == PlayerMenu.Subtitles) PlayerMenu.None else PlayerMenu.Subtitles
                menuSel = 0
                bumpHud(expanded = true)
            }
            HudRailAction.Picture -> {
                menu = if (menu == PlayerMenu.Picture) PlayerMenu.None else PlayerMenu.Picture
                menuSel = PictureMode.entries.indexOf(pictureMode).coerceAtLeast(0)
                bumpHud(expanded = true)
            }
            HudRailAction.Previous -> {
                val prev = previousItem ?: return
                reportWatch()
                onPlayNeighbor?.invoke(prev)
            }
            HudRailAction.Next -> {
                val next = nextItem ?: return
                reportWatch()
                onPlayNeighbor?.invoke(next)
            }
            null -> Unit
        }
    }

    fun activateMenu() {
        when (menu) {
            PlayerMenu.None -> Unit
            PlayerMenu.Audio -> {
                val track = audioTracks.getOrNull(menuSel) ?: return
                playerViewModel.selectTrack(track)
                bumpHud(expanded = true)
            }
            PlayerMenu.Subtitles -> {
                subtitleMenuActions().getOrNull(menuSel)?.invoke()
            }
            PlayerMenu.Picture -> {
                val mode = PictureMode.entries.getOrNull(menuSel) ?: return
                pictureMode = mode
                bumpHud(expanded = true)
            }
        }
    }

    fun menuCount(): Int = when (menu) {
        PlayerMenu.None -> 0
        PlayerMenu.Audio -> audioTracks.size
        PlayerMenu.Subtitles -> subtitleMenuActions().size
        PlayerMenu.Picture -> PictureMode.entries.size
    }

    // Keep the TV awake for the whole player session (screensaver otherwise kicks in).
    // Pause when the box Home / another app takes focus — ExoPlayer otherwise keeps audio going.
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var resumePlayback = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumePlayback = player.playWhenReady || player.isPlaying
                    player.playWhenReady = false
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (resumePlayback) {
                        player.playWhenReady = true
                        player.play()
                        resumePlayback = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            player.playWhenReady = false
            player.pause()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Never swallow system Back — BackHandler owns exit.
                if (event.key == Key.Back || event.key == Key.Escape) {
                    return@onPreviewKeyEvent false
                }

                val playPause = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter ||
                    event.key == Key.MediaPlayPause ||
                    event.key == Key.MediaPlay ||
                    event.key == Key.MediaPause
                val left = event.key == Key.DirectionLeft || event.key == Key.MediaRewind
                val right = event.key == Key.DirectionRight || event.key == Key.MediaFastForward
                val down = event.key == Key.DirectionDown
                val up = event.key == Key.DirectionUp
                val mediaNext = event.key == Key.MediaNext
                val mediaPrev = event.key == Key.MediaPrevious
                val transportScrub = menu == PlayerMenu.None && !hudExpanded && (left || right)

                // Hold-to-scrub: preview on key-down repeats, one ExoPlayer seek on key-up.
                if (transportScrub && event.type == KeyEventType.KeyUp) {
                    if (scrubMs != null) {
                        commitScrub()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }

                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // Side settings panel: ↑/↓ move, OK select, ← closes.
                if (menu != PlayerMenu.None) {
                    cancelScrub()
                    val count = menuCount().coerceAtLeast(1)
                    when {
                        playPause -> {
                            activateMenu()
                            true
                        }
                        left -> {
                            menu = PlayerMenu.None
                            bumpHud(expanded = true)
                            true
                        }
                        up -> {
                            if (menuSel <= 0) {
                                menu = PlayerMenu.None
                                bumpHud(expanded = true)
                            } else {
                                menuSel -= 1
                                bumpHud(expanded = true)
                            }
                            true
                        }
                        down || right -> {
                            menuSel = (menuSel + 1).coerceAtMost(count - 1)
                            bumpHud(expanded = true)
                            true
                        }
                        else -> false
                    }.let { return@onPreviewKeyEvent it }
                }

                // Rail open: move selection / activate / collapse.
                if (hudExpanded) {
                    cancelScrub()
                    when {
                        up -> {
                            collapseHud()
                            true
                        }
                        left -> {
                            railSel = (railSel - 1).coerceAtLeast(0)
                            bumpHud(expanded = true)
                            true
                        }
                        right -> {
                            railSel = (railSel + 1).coerceAtMost(railCount - 1)
                            bumpHud(expanded = true)
                            true
                        }
                        playPause -> {
                            activateRail()
                            true
                        }
                        down -> true
                        else -> false
                    }.let { return@onPreviewKeyEvent it }
                }

                val repeat = event.nativeKeyEvent.repeatCount
                // Transport mode (hidden or collapsed chrome).
                when {
                    !hudVisible -> {
                        when {
                            down -> { bumpHud(expanded = true); true }
                            playPause -> { bumpHud(expanded = false); togglePlay(); true }
                            left -> { bumpHud(expanded = false); nudgeScrub(-1, repeat); true }
                            right -> { bumpHud(expanded = false); nudgeScrub(1, repeat); true }
                            else -> false
                        }
                    }
                    down -> { cancelScrub(); expandHud(); true }
                    up -> { cancelScrub(); hideHud(); true }
                    playPause -> { togglePlay(); true }
                    left -> { nudgeScrub(-1, repeat); true }
                    right -> { nudgeScrub(1, repeat); true }
                    mediaNext && nextItem != null -> {
                        cancelScrub()
                        reportWatch()
                        onPlayNeighbor?.invoke(nextItem)
                        true
                    }
                    mediaPrev && previousItem != null -> {
                        cancelScrub()
                        reportWatch()
                        onPlayNeighbor?.invoke(previousItem)
                        true
                    }
                    else -> false
                }
            },
    ) {
        if (session != null) {
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                ContentFrame(
                    player = player,
                    contentScale = pictureMode.scale,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = false },
                )
            }
        }

        if (cueLines.isNotEmpty() && !splash && menu == PlayerMenu.None) {
            val bottomPad = when {
                hudExpanded -> 156.dp
                hudVisible -> 120.dp
                else -> 48.dp
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 80.dp, vertical = bottomPad)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                cueLines.forEach { line ->
                    Text(
                        line,
                        color = Color.White,
                        fontSize = cueSp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (splash) MediaLoadingScreen(item = item, title = title)

        playError?.let { message ->
            Text(
                message,
                style = CoogType.heroTagline,
                color = Color(0xFFFF8B8B),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
            )
        }

        AnimatedVisibility(
            visible = !splash && menu != PlayerMenu.None,
            enter = fadeIn(tween(140)) + slideInHorizontally(tween(180)) { it / 3 },
            exit = fadeOut(tween(110)) + slideOutHorizontally(tween(150)) { it / 3 },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 36.dp, top = 48.dp, bottom = 120.dp),
        ) {
            SideSettingsPanel(
                menu = menu,
                menuSel = menuSel,
                audioTracks = audioTracks,
                textTracks = textTracks,
                movieRemoteTracks = movieRemoteTracks,
                externalRemoteTracks = externalRemoteTracks,
                remoteBusy = remoteBusy,
                remoteError = remoteError,
                textOff = !subsOn,
                selectedRemoteId = selectedRemoteId,
                subtitleDelayMs = subtitleDelayMs,
                sizeLabel = sizeLabel,
                subsOn = subsOn,
                pictureMode = pictureMode,
                onActivate = { index ->
                    menuSel = index
                    activateMenu()
                },
            )
        }

        AnimatedVisibility(
            visible = hudVisible && !splash,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerHud(
                title = title,
                positionMs = scrubMs ?: position,
                durationMs = duration,
                bufferedMs = when {
                    session?.method == "direct" && duration > 0 && !jobDownloading -> duration
                    else -> maxOf(jobBuffered, buffered)
                },
                remainingMs = if (jobDownloading && duration > 0) {
                    (duration - maxOf(jobBuffered, buffered)).coerceAtLeast(0L)
                } else 0L,
                playing = if (scrubMs != null) false else playing,
                scrubbing = scrubMs != null,
                expanded = hudExpanded,
                menuOpen = menu != PlayerMenu.None,
                railSel = railSel,
                railActions = railActions,
                audioLabel = audioLabel,
                subLabel = subLabel,
                pictureLabel = pictureMode.label,
                onActivateRail = { index ->
                    railSel = index
                    activateRail()
                },
            )
        }
    }
}

@Composable
private fun PlayerHud(
    title: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    remainingMs: Long,
    playing: Boolean,
    scrubbing: Boolean = false,
    expanded: Boolean,
    menuOpen: Boolean,
    railSel: Int,
    railActions: List<HudRailAction>,
    audioLabel: String,
    subLabel: String,
    pictureLabel: String,
    onActivateRail: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to Color.Black.copy(alpha = 0.42f),
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            )
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = CoogType.heroTagline.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 20.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!playing) {
                    HudMetaPill(label = "Paused", tone = Color.White.copy(alpha = 0.88f))
                }
                if (remainingMs > 0 && remainingMs < durationMs) {
                    HudMetaPill(label = "${formatClock(bufferedMs)} ready", tone = CoogFetch)
                }
            }
        }

        HudScrubber(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(90)),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                railActions.forEachIndexed { index, action ->
                    when (action) {
                        HudRailAction.Play -> HudRailButton(
                            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            label = if (playing) "Pause" else "Play",
                            highlighted = !menuOpen && railSel == index,
                            onClick = { onActivateRail(index) },
                        )
                        HudRailAction.Audio -> HudRailButton(
                            icon = Icons.Outlined.GraphicEq,
                            label = audioLabel.take(12),
                            highlighted = !menuOpen && railSel == index,
                            onClick = { onActivateRail(index) },
                        )
                        HudRailAction.Subtitles -> HudRailButton(
                            icon = Icons.Outlined.ClosedCaption,
                            label = subLabel,
                            highlighted = !menuOpen && railSel == index,
                            onClick = { onActivateRail(index) },
                        )
                        HudRailAction.Picture -> HudRailButton(
                            icon = Icons.Outlined.AspectRatio,
                            label = pictureLabel,
                            highlighted = !menuOpen && railSel == index,
                            onClick = { onActivateRail(index) },
                        )
                        HudRailAction.Previous -> HudRailButton(
                            icon = Icons.Filled.SkipPrevious,
                            label = "Prev",
                            highlighted = !menuOpen && railSel == index,
                            onClick = { onActivateRail(index) },
                        )
                        HudRailAction.Next -> HudRailButton(
                            icon = Icons.Filled.SkipNext,
                            label = "Next",
                            highlighted = !menuOpen && railSel == index,
                            onClick = { onActivateRail(index) },
                        )
                    }
                }
            }
        }

        if (!expanded) {
            Text(
                if (scrubbing) {
                    "Release to seek"
                } else {
                    "OK play/pause   ·   hold ← → scrub   ·   ↓ more"
                },
                style = CoogType.cardYear,
                color = CoogTextMuted.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun HudMetaPill(label: String, tone: Color) {
    Text(
        label,
        style = CoogType.chip.copy(fontSize = 10.sp, color = tone),
        modifier = Modifier
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(99.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HudScrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
) {
    val dur = durationMs.coerceAtLeast(1L)
    val played = (positionMs.toFloat() / dur).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / dur).coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            formatClock(positionMs),
            style = CoogType.chip.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White),
            modifier = Modifier.widthIn(min = 44.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = buffered)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color.White.copy(alpha = 0.30f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = played)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color.White),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = played)
                    .height(14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }
        Text(
            if (durationMs > 0) formatClock(durationMs) else "—",
            style = CoogType.chip.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CoogTextSecondary),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 44.dp),
        )
    }
}

@Composable
private fun HudRailButton(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (highlighted) Color.White else Color.White.copy(alpha = 0.10f)
    val fg = if (highlighted) Color(0xFF121214) else Color.White.copy(alpha = 0.92f)
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp)
            .focusProperties { canFocus = false },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(
            label,
            color = fg,
            style = CoogType.chip,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val HudPanelBg = Color(0xFF1A1A1C)
private val HudPanelStroke = Color(0x14FFFFFF)

@Composable
private fun SideSettingsPanel(
    menu: PlayerMenu,
    menuSel: Int,
    audioTracks: List<PlayerTrack>,
    textTracks: List<PlayerTrack>,
    movieRemoteTracks: List<SubtitleTrack>,
    externalRemoteTracks: List<SubtitleTrack>,
    remoteBusy: Boolean,
    remoteError: String,
    textOff: Boolean,
    selectedRemoteId: String?,
    subtitleDelayMs: Int,
    sizeLabel: String,
    subsOn: Boolean,
    pictureMode: PictureMode,
    onActivate: (Int) -> Unit,
) {
    data class RowItem(val label: String, val checked: Boolean, val checkable: Boolean = true)

    if (menu == PlayerMenu.None) return

    val icon: ImageVector
    val title: String
    val rows: List<RowItem>
    val empty: String?
    val toolCount: Int
    when (menu) {
        PlayerMenu.None -> return
        PlayerMenu.Audio -> {
            icon = Icons.Outlined.GraphicEq
            title = "Audio"
            rows = audioTracks.map { RowItem(it.label.take(32), checked = it.selected) }
            empty = if (rows.isEmpty()) "No audio tracks" else null
            toolCount = 0
        }
        PlayerMenu.Subtitles -> {
            icon = Icons.Outlined.ClosedCaption
            title = "Subtitles"
            rows = buildList {
                if (subsOn) {
                    add(RowItem(formatDelay(subtitleDelayMs), checked = false, checkable = false))
                    add(RowItem("+250 ms", checked = false, checkable = false))
                    add(RowItem("Size $sizeLabel", checked = false, checkable = false))
                }
                add(RowItem("Off", checked = textOff))
                textTracks.forEach { track ->
                    val active = selectedRemoteId == null && track.selected && !textOff
                    add(RowItem(trackDisplayLabel(track), checked = active))
                }
                movieRemoteTracks.forEach { track ->
                    add(RowItem(remoteDisplayLabel(track), checked = selectedRemoteId == track.id))
                }
                externalRemoteTracks.forEach { track ->
                    add(RowItem(remoteDisplayLabel(track), checked = selectedRemoteId == track.id))
                }
            }
            empty = when {
                remoteBusy && rows.size <= 1 -> "Searching…"
                remoteError.isNotBlank() &&
                    movieRemoteTracks.isEmpty() &&
                    externalRemoteTracks.isEmpty() &&
                    textTracks.isEmpty() -> remoteError
                rows.isEmpty() -> "No subtitles found"
                else -> null
            }
            toolCount = if (subsOn) 3 else 0
        }
        PlayerMenu.Picture -> {
            icon = Icons.Outlined.AspectRatio
            title = "Picture"
            rows = PictureMode.entries.map { mode ->
                RowItem(mode.label, checked = pictureMode == mode)
            }
            empty = null
            toolCount = 0
        }
    }

    Column(
        modifier = Modifier
            .width(268.dp)
            .heightIn(max = 420.dp)
            .background(HudPanelBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HudPanelHeader(icon = icon, title = title)
        when {
            empty != null && rows.isEmpty() -> Text(
                empty,
                style = CoogType.cardYear,
                color = if (empty == remoteError) Color(0xFFFF8B8B) else CoogTextMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            else -> {
                if (empty != null && rows.size <= 1) {
                    Text(empty, style = CoogType.cardYear, color = CoogTextMuted, modifier = Modifier.padding(vertical = 4.dp))
                }
                rows.forEachIndexed { index, row ->
                    if (toolCount > 0 && index == toolCount) {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(HudPanelStroke),
                        )
                    }
                    HudListRow(
                        label = row.label,
                        checked = row.checked,
                        checkable = row.checkable,
                        highlighted = menuSel == index,
                        onClick = { onActivate(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HudPanelHeader(icon: ImageVector, title: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            title,
            style = CoogType.chip.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
        )
    }
}

@Composable
private fun HudListRow(
    label: String,
    checked: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    checkable: Boolean = true,
) {
    val fg = when {
        highlighted -> Color.White
        checked -> Color.White.copy(alpha = 0.92f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                if (highlighted) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 4.dp)
            .focusProperties { canFocus = false },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (checkable && checked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (highlighted || checked) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatDelay(ms: Int): String {
    val sign = if (ms > 0) "+" else ""
    return "Sync $sign${ms} ms"
}

private fun trackDisplayLabel(track: PlayerTrack): String {
    val lang = track.language.trim().uppercase()
    val base = when {
        lang.isNotBlank() && lang != "UND" -> lang
        else -> track.label.take(28)
    }
    return "$base · movie"
}

private fun remoteDisplayLabel(track: SubtitleTrack): String {
    val lang = track.language.trim().uppercase().ifBlank { "SUB" }
    val source = when (track.source) {
        "embedded" -> "movie"
        "sidecar" -> "file"
        "opensubtitles" -> "web"
        else -> track.source.take(6)
    }
    val release = track.label
        .substringAfter(" · ", "")
        .substringBefore(" · OpenSubtitles")
        .trim()
        .take(14)
    return if (release.isNotBlank()) "$lang · $release" else "$lang · $source"
}
