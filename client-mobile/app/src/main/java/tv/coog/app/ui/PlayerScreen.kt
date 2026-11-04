package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.zIndex
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import android.os.SystemClock
import android.view.WindowManager
import coil.imageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.coog.app.BuildConfig
import tv.coog.app.data.CoogApi
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.data.MediaItem
import tv.coog.app.data.PlaybackSession
import tv.coog.app.data.PlaybackSyncClient
import tv.coog.app.data.SubtitleTrack
import tv.coog.app.interactive.LocalIntiface
import tv.coog.app.interactive.LocalPlaybackDriver
import tv.coog.app.player.PlayerTrack
import tv.coog.app.player.PlayerViewModel
import tv.coog.app.ui.theme.CoogFetch
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType

private enum class PlayerMenu { None, Audio, Subtitles, Picture, Devices }

private enum class HudRailAction { Play, Audio, Subtitles, Picture, Devices, Previous, Next }

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
    val context = LocalContext.current
    val player = playerViewModel.player
    val playError by playerViewModel.error.collectAsState()
    val audioTracks by playerViewModel.audioTracks.collectAsState()
    val textTracks by playerViewModel.textTracks.collectAsState()
    val textOff by playerViewModel.textOff.collectAsState()
    val firstFrame by playerViewModel.firstFrame.collectAsState()
    val ended by playerViewModel.ended.collectAsState()
    val subtitleDelayMs by playerViewModel.subtitleDelayMs.collectAsState()
    val subtitleSize by playerViewModel.subtitleSize.collectAsState()
    val cueLines by playerViewModel.cueLines.collectAsState()
    val externalOverlay by playerViewModel.externalOverlay.collectAsState()
    val overlayRevision by playerViewModel.overlayRevision.collectAsState()
    // Full-screen loader only until the first frame. Seek/rebuffer must not cover video —
    // ExoPlayer enters BUFFERING on every seek and the logo overlay was fighting the scrub.
    val splash = playError == null && (session == null || !firstFrame)

    // Free browse-art bitmaps so progressive play has room in the TV Java heap.
    LaunchedEffect(Unit) {
        context.imageLoader.memoryCache?.clear()
    }

    // Phone/tablet: start with the action rail open — TV keeps collapsed chrome for D-pad.
    var hudVisible by remember { mutableStateOf(true) }
    var hudExpanded by remember { mutableStateOf(BuildConfig.TOUCH_CLIENT) }
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
    var jobProgress by remember { mutableFloatStateOf(0f) }
    var jobStatus by remember { mutableStateOf("") }
    var jobQuality by remember { mutableStateOf("") }
    var jobSizeLabel by remember { mutableStateOf("") }
    // Local copy so we can hand off from progressive file → library stream mid-play.
    var liveSession by remember(session?.id, session?.url) { mutableStateOf(session) }
    var seamlessHandoff by remember { mutableStateOf(false) }
    /** Capture playhead before URL swap — clearing seamlessHandoff must not re-prepare at 0. */
    var handoffResumeMs by remember { mutableStateOf<Long?>(null) }
    var remoteTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var remoteError by remember { mutableStateOf("") }
    var remoteBusy by remember { mutableStateOf(false) }
    var selectedRemoteId by remember { mutableStateOf<String?>(null) }
    var autoLoadDone by remember { mutableStateOf(false) }
    var preferredLangs by remember { mutableStateOf(listOf("en")) }
    var autoLoad by remember { mutableStateOf(true) }
    var autoSync by remember { mutableStateOf(false) }
    var preferEmbedded by remember { mutableStateOf(true) }
    val settingsRepo = remember { tv.coog.app.data.SettingsRepository(context) }
    val maizeBleMode by settingsRepo.maizeBleMode.collectAsState(
        initial = tv.coog.app.data.MaizeBleMode.Device,
    )
    val localIntiface = remember { LocalIntiface.get(context) }
    val localEngine by localIntiface.engine.collectAsState()
    val syncClient = remember(serverUrl, token, adultSession) {
        if (adultSession.isNotBlank()) PlaybackSyncClient(CoogApi(serverUrl, token, adultSession)) else null
    }
    val localDriver = remember(serverUrl, token, adultSession, localIntiface) {
        if (adultSession.isNotBlank()) {
            LocalPlaybackDriver(localIntiface, CoogApi(serverUrl, token, adultSession))
        } else {
            null
        }
    }
    val useDeviceBle = maizeBleMode == tv.coog.app.data.MaizeBleMode.Device
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

    /** Server still growing the progressive file — HUD ready pill + disk fill. */
    fun serverStillDownloading(): Boolean =
        liveSession?.method == "progressive" && jobDownloading

    fun seekLimit(): Long {
        val dur = duration.coerceAtLeast(0L)
        // Progressive / still-downloading: scrub into bytes already on disk (jobBuffered).
        // Never clamp to ExoPlayer's short LoadControl window (~10–18s ahead) — that made
        // ←/→ feel stuck at ~10s steps while the HUD "ready" bar showed much more.
        if (serverStillDownloading()) {
            val disk = jobBuffered.takeIf { it > 0L }
            return when {
                disk != null && dur > 0L -> minOf(disk, dur)
                disk != null -> disk
                dur > 0L -> dur
                buffered > 0L -> buffered
                else -> 0L
            }
        }
        if (dur > 0L) return dur
        return maxOf(jobBuffered, buffered)
    }

    /**
     * Seekbar buffer fill:
     * - progressive download: bytes already on the server (jobBuffered)
     * - local server file: ExoPlayer device buffer only (no fake full-duration fill)
     */
    fun readyBufferedMs(): Long {
        if (serverStillDownloading()) {
            if (jobBuffered > 0L) return jobBuffered
            return buffered
        }
        return buffered
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
        val resume = scrubResumePlay
        scrubMs = null
        scrubResumePlay = false
        playerViewModel.seekTo(target, seekLimit())
        // Always re-assert play after progressive MKV seeks — approximate seeks can
        // leave the player buffering with playWhenReady cleared by an earlier pause.
        if (resume) {
            player.playWhenReady = true
            player.play()
        }
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
        // Capture intent before toggle — ExoPlayer state can lag one frame, which skipped
        // forcePaused and left Solace Pro thrusting after pause.
        val willPause = player.playWhenReady || player.isPlaying
        playerViewModel.togglePlay()
        if (willPause) {
            localDriver?.forcePaused(player.currentPosition)
            syncClient?.pause()
        }
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
        val active = liveSession
        // Trailer flow pushes Player with session=null while the file is fetched, then
        // replaces with id=trailer:…. Either way must not write continue-watching.
        if (active == null) return
        val sessionId = active.id
        val sessionUrl = active.url
        if (sessionId.startsWith("trailer:") ||
            sessionUrl.contains("/trailer") ||
            title.contains("·  Trailer") ||
            title.endsWith(" Trailer", ignoreCase = true)
        ) {
            return
        }
        val pos = player.currentPosition
        val dur = duration.takeIf { it > 0 } ?: player.duration.takeIf { it > 0 } ?: 0L
        // Only library media ids — never a progressive job id (breaks continue → Play).
        val raw = active.mediaId.ifBlank { media.diskMediaId() }
        val mediaId = raw.takeUnless {
            it.isBlank() || it.startsWith("catalog:") || it.startsWith("continue:") || it.startsWith("job:")
        }.orEmpty()
        val url = serverUrl
        val tok = token
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { CoogApi(url, tok).reportProgress(media, pos, dur, mediaId, title) }
        }
    }

    fun rankRemote(track: SubtitleTrack): Int {
        val lang = track.language.lowercase()
        var score = when (track.source) {
            "sidecar" -> 30
            "embedded" -> 20
            "opensubtitles" -> 10 + track.matchScore.coerceAtMost(80)
            else -> 0
        }
        if (track.movieHashMatch) score += 40
        if (lang.isNotBlank() && preferredLangs.isNotEmpty()) {
            val idx = preferredLangs.indexOfFirst { lang == it || lang.startsWith(it) }
            if (idx >= 0) score += 50 - idx * 5
        }
        return score
    }

    fun subtitleTitleOverlap(track: SubtitleTrack, title: String): Int {
        val hay = listOf(track.filename, track.release, track.label, track.id)
            .joinToString(" ")
            .lowercase()
        val tokens = title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in setOf("the", "and", "for", "with", "season", "episode") }
        if (tokens.isEmpty()) return 1
        return tokens.count { hay.contains(it) }
    }

    fun episodeAligned(track: SubtitleTrack, season: Int, episode: Int): Boolean {
        if (season <= 0 && episode <= 0) return true
        if (track.source != "opensubtitles") return true
        if (track.movieHashMatch) return true
        if (track.season <= 0 && track.episode <= 0) return false
        if (season > 0 && track.season > 0 && track.season != season) return false
        if (episode > 0 && track.episode > 0 && track.episode != episode) return false
        return true
    }

    /** Sidecars first; OpenSubtitles only when S/E (or hash) looks right. */
    fun pickAutoRemote(
        tracks: List<SubtitleTrack>,
        preferredLangs: List<String>,
        title: String,
        season: Int = 0,
        episode: Int = 0,
    ): SubtitleTrack? {
        val series = season > 0 || episode > 0
        fun acceptable(track: SubtitleTrack): Boolean {
            if (rankRemote(track) <= 0) return false
            if (track.source == "sidecar" || track.source == "embedded") return true
            if (track.source != "opensubtitles") return false
            if (!episodeAligned(track, season, episode)) return false
            if (track.movieHashMatch) return true
            if (series) {
                // Require an explicit S/E hit from the server — popularity alone used to
                // auto-load the wrong episode and look "out of sync".
                val seOk = (season <= 0 || track.season == season) &&
                    (episode <= 0 || track.episode == episode) &&
                    (track.season > 0 || track.episode > 0)
                return seOk && track.matchScore >= 40
            }
            return track.matchScore >= 20 || subtitleTitleOverlap(track, title) > 0 || title.isBlank()
        }
        fun bestForLang(lang: String): SubtitleTrack? {
            val candidates = tracks.filter { track ->
                val l = track.language.lowercase()
                (l == lang || l.startsWith(lang)) && acceptable(track)
            }
            return candidates.maxWithOrNull(
                compareBy<SubtitleTrack> { if (it.source == "sidecar") 1 else 0 }
                    .thenBy { if (it.movieHashMatch) 1 else 0 }
                    .thenBy { it.matchScore }
                    .thenBy { subtitleTitleOverlap(it, title) }
                    .thenBy { it.downloadCount }
                    .thenBy { rankRemote(it) },
            )
        }
        for (lang in preferredLangs.map { it.lowercase() }.filter { it.isNotBlank() }) {
            bestForLang(lang)?.let { return it }
        }
        return tracks.filter(::acceptable).maxWithOrNull(
            compareBy<SubtitleTrack> { if (it.source == "sidecar") 1 else 0 }
                .thenBy { if (it.movieHashMatch) 1 else 0 }
                .thenBy { it.matchScore }
                .thenBy { subtitleTitleOverlap(it, title) }
                .thenBy { it.downloadCount }
                .thenBy { rankRemote(it) },
        )
    }

    fun subtitleSourceTier(source: String): Int = when (source) {
        "embedded" -> 3
        "sidecar" -> 2
        "opensubtitles" -> 1
        else -> 0
    }

    /** In-file / beside-file first, then preferred languages (et before en), then score. */
    fun sortSubtitleTracks(tracks: List<SubtitleTrack>): List<SubtitleTrack> =
        tracks.sortedWith(
            compareByDescending<SubtitleTrack> { subtitleSourceTier(it.source) }
                .thenBy { track ->
                    val lang = track.language.lowercase()
                    val idx = preferredLangs.indexOfFirst { lang == it || lang.startsWith(it) }
                    if (idx >= 0) idx else 1000
                }
                .thenByDescending { it.matchScore }
                .thenByDescending { rankRemote(it) }
                .thenBy { it.label },
        )

    // Keep embedded tracks in ffprobe/mpv order — do not re-sort by preferred language.
    val movieRemoteTracks = run {
        val inFile = remoteTracks.filter { subtitleSourceTier(it.source) >= 2 }
        val embeds = inFile.filter { it.source == "embedded" }
        val other = sortSubtitleTracks(inFile.filter { it.source != "embedded" })
        embeds + other
    }
    val externalRemoteTracks = sortSubtitleTracks(remoteTracks.filter { subtitleSourceTier(it.source) < 2 })

    fun langCompatible(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        if (x.isBlank() || y.isBlank()) return false
        return x == y || x.startsWith(y) || y.startsWith(x)
    }

    val embeddedRemotes = movieRemoteTracks.filter { it.source == "embedded" }
    val sidecarRemotes = movieRemoteTracks.filter { it.source == "sidecar" }
    // Server ffprobe list is the source of truth (same order/tags as mpv). Showing Exo
    // rows on top of it invented duplicates (PGS + softsub + extract) and wrong names.
    val menuTextTracks = if (embeddedRemotes.isNotEmpty()) emptyList() else textTracks
    val menuEmbeddedRemotes = embeddedRemotes
    val menuMovieRemotes = sidecarRemotes + menuEmbeddedRemotes

    /** Map a server emb:* row to the matching Exo text track (index, then ordinal). */
    fun resolveExoForEmbedded(track: SubtitleTrack, bitmapOnly: Boolean): PlayerTrack? {
        fun matches(t: PlayerTrack) =
            if (bitmapOnly) !t.isTextBasedSubtitle() else t.isTextBasedSubtitle()
        val streamIdx = parseEmbeddedStreamIndex(track.id)
        if (streamIdx >= 0) {
            textTracks.firstOrNull { it.containerIndex == streamIdx && matches(it) }?.let { return it }
        }
        // Overall list ordinal: server embeds and Exo text tracks share file order.
        val allOrd = embeddedRemotes.indexOfFirst { it.id == track.id }
        textTracks.getOrNull(allOrd)?.takeIf(::matches)?.let { return it }
        val pool = textTracks.filter(::matches)
        val typedOrd = if (bitmapOnly) {
            embeddedRemotes.filter { it.isBitmapSubtitle() }.indexOfFirst { it.id == track.id }
        } else {
            embeddedRemotes.filter { !it.isBitmapSubtitle() }.indexOfFirst { it.id == track.id }
        }
        return pool.getOrNull(typedOrd)
    }

    LaunchedEffect(embeddedRemotes) {
        // Server list owns in-file naming — never copy stream tags onto other tracks.
        playerViewModel.setEmbeddedLanguageHints(emptyList())
    }

    /**
     * Timing reference for autosync.
     * - PGS/bitmap: none (Exo video-locked; button stays disabled).
     * - In-file SRT/sidecar: another in-file text track or sidecar only (never OS —
     *   different release). Prefer other-language embed for name anchors.
     * - Online: sidecar → other-lang embed → any embed → best OS.
     */
    fun pickSyncReference(selected: SubtitleTrack): SubtitleTrack? {
        if (selected.isBitmapSubtitle()) return null

        val pool = (movieRemoteTracks + externalRemoteTracks)
            .filter {
                it.id != selected.id && it.id != "off" && it.source != "none" &&
                    !(it.source == "embedded" && it.isBitmapSubtitle())
            }
        val preferred = preferredLangs.map { it.lowercase() }.filter { it.isNotBlank() }
        val selectedIsFileNative = selected.source == "sidecar" ||
            (selected.source == "embedded" && !selected.isBitmapSubtitle())

        fun scoreOs(t: SubtitleTrack): Int {
            val lang = t.language.lowercase()
            val langBonus = when {
                lang == "en" || lang.startsWith("en") -> 1_000_000
                preferred.any { lang == it || lang.startsWith(it) || it.startsWith(lang) } -> 500_000
                else -> 0
            }
            val hashBonus = if (t.movieHashMatch) 50_000 else 0
            return langBonus + hashBonus + t.matchScore
        }

        fun preferOtherLangEmbed(): SubtitleTrack? =
            pool.filter { it.source == "embedded" }
                .firstOrNull { !langCompatible(it.language, selected.language) }
                ?: pool.firstOrNull { it.source == "embedded" }

        // Video-locked file timing first when present.
        pool.firstOrNull { it.source == "sidecar" }?.let { return it }

        if (selectedIsFileNative) {
            // Only other in-file text — no OpenSubtitles (often a different cut).
            return preferOtherLangEmbed()
        }

        preferOtherLangEmbed()?.let { return it }
        return pool.filter { it.source == "opensubtitles" }.maxByOrNull(::scoreOs)
    }

    fun applyRemote(track: SubtitleTrack) {
        when {
            track.id == "off" || track.source == "none" -> {
                selectedRemoteId = null
                remoteError = ""
                playerViewModel.clearExternalSubtitle()
            }
            track.source == "embedded" && track.isBitmapSubtitle() -> {
                // Bitmap (PGS/…) — match Exo by stream index or list ordinal (same
                // order as ffprobe). Never remap by language — tags often lie.
                // selectTrack clears any overlay; do not call clearExternalSubtitle()
                // first (that setTextOff and raced the enable).
                selectedRemoteId = track.id
                remoteError = ""
                val target = resolveExoForEmbedded(track, bitmapOnly = true)
                if (target != null) {
                    playerViewModel.selectTrack(target)
                    if (!target.supported) {
                        remoteError = "This device cannot decode PGS — try an SRT track"
                    }
                } else {
                    playerViewModel.clearExternalSubtitle()
                    remoteError = "Bitmap subtitle not available on this device"
                }
            }
            track.source == "opensubtitles" || track.source == "sidecar" || track.source == "embedded" -> {
                val api = CoogApi(serverUrl, token)
                val url = api.subtitleFileUrl(track.id)
                val trackId = track.id
                selectedRemoteId = trackId
                remoteError = ""
                // Clear previous overlay immediately so the old track cannot linger while fetching.
                playerViewModel.prepareExternalSubtitleSwitch()
                // Sidecar preferred; else EN/high-score online track as timing reference.
                val localRef = pickSyncReference(track)
                val syncOn = autoSync
                val exoFallback = if (track.source == "embedded") {
                    resolveExoForEmbedded(track, bitmapOnly = false)
                } else {
                    null
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val bodyResult = runCatching { api.fetchSubtitleBody(trackId) }
                    val body = bodyResult.getOrNull()
                    if (bodyResult.isFailure || body.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            if (selectedRemoteId != trackId) return@withContext
                            // Extract failed — fall back to Exo softsub at the same ordinal.
                            if (exoFallback != null && exoFallback.isTextBasedSubtitle() && exoFallback.supported) {
                                remoteError = ""
                                playerViewModel.selectTrack(exoFallback)
                                return@withContext
                            }
                            remoteError = bodyResult.exceptionOrNull()?.message
                                ?.take(160)
                                ?.ifBlank { null }
                                ?: "Could not download subtitle"
                            selectedRemoteId = null
                            playerViewModel.clearExternalSubtitle()
                        }
                        return@launch
                    }
                    // Cache reference body whenever possible so Autosync toggle can run later.
                    val localBody = localRef?.let {
                        runCatching { api.fetchSubtitleBody(it.id) }.getOrNull()
                    }
                    withContext(Dispatchers.Main) {
                        // Ignore stale responses if the user picked another track meanwhile.
                        if (selectedRemoteId != trackId) return@withContext
                        playerViewModel.setExternalSubtitleOverlay(
                            body = body,
                            language = track.language,
                            localBody = localBody,
                            localLanguage = localRef?.language.orEmpty(),
                            url = url,
                            autoSync = syncOn,
                        )
                    }
                }
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
        hudExpanded = BuildConfig.TOUCH_CLIENT
        hudVisible = true
        hudNonce++
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
    // Local/direct/remux sessions must not keep splash/job "downloading" state (ready pill).
    LaunchedEffect(liveSession?.id, liveSession?.method) {
        val method = liveSession?.method ?: return@LaunchedEffect
        if (!method.equals("progressive", ignoreCase = true)) {
            jobDownloading = false
            if (jobStatus == "preparing" || jobStatus == "queued" ||
                jobStatus == "downloading" || jobStatus == "ready"
            ) {
                jobStatus = ""
            }
        }
    }
    // Do not key on seamlessHandoff — flipping it false used to re-run play() and wipe resume to 0.
    LaunchedEffect(liveSession?.url, liveSession?.audioUrl, liveSession?.contentType, token, adultSession, item?.positionMs, item?.id, item?.season, item?.episode) {
        val active = liveSession ?: return@LaunchedEffect
        val url = active.url.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val mediaKey = listOf(
            item?.id.orEmpty(),
            item?.season?.toString().orEmpty(),
            item?.episode?.toString().orEmpty(),
            active.mediaId,
            active.jobId,
            active.audioUrl,
        ).joinToString("|")
        val seamless = seamlessHandoff
        val resumeOverride = handoffResumeMs
        if (active.totalBytes > 0L) {
            playerViewModel.setProgressiveFinalBytes(active.totalBytes)
        }
        val trailerPlayback = active.id.startsWith("trailer:") ||
            active.url.contains("/trailer") ||
            title.contains("·  Trailer") ||
            title.endsWith(" Trailer", ignoreCase = true)
        playerViewModel.play(
            url,
            token,
            startPositionMs = when {
                resumeOverride != null -> resumeOverride
                seamless || trailerPlayback -> 0L
                else -> item?.positionMs ?: 0L
            },
            adultSession = adultSession,
            mediaKey = mediaKey,
            seamless = seamless || resumeOverride != null,
            contentType = active.contentType.ifBlank { item?.contentType.orEmpty() },
            audioUrl = active.audioUrl,
            audioContentType = active.audioContentType,
        )
        if (seamless) seamlessHandoff = false
        if (resumeOverride != null) handoffResumeMs = null
    }
    // Progressive / first-frame on ATV can take well over 30s to paint.
    // Only time out if we never got a frame and the player is not still working.
    LaunchedEffect(liveSession?.url, liveSession?.id, liveSession?.method) {
        val active = liveSession ?: return@LaunchedEffect
        if (active.url.isBlank()) return@LaunchedEffect
        val progressive = active.method.equals("progressive", ignoreCase = true) &&
            active.jobId.isNotBlank()
        delay(if (progressive) 90_000 else 45_000)
        if (playerViewModel.firstFrame.value || playerViewModel.error.value != null) return@LaunchedEffect
        // Still opening (decoder / growing file) — don't scare with "pick smaller".
        if (playerViewModel.buffering.value) return@LaunchedEffect
        if (progressive) {
            val again = runCatching {
                CoogApi(serverUrl, token, adultSession).playbackSession(jobId = active.jobId)
            }.getOrNull()
            if (again != null && again.url.isNotBlank() && again.error.isBlank() && again.url != active.url) {
                handoffResumeMs = maxOf(player.currentPosition, position).coerceAtLeast(0L)
                seamlessHandoff = true
                liveSession = again
                return@LaunchedEffect
            }
            playerViewModel.markOpenTimeout(
                "Still opening this download. Wait a bit — the file is growing on the server.",
            )
            return@LaunchedEffect
        }
        playerViewModel.markOpenTimeout("Playback timed out before video started.")
    }
    LaunchedEffect(liveSession?.url, liveSession?.id, item?.id, serverUrl, token, firstFrame, adultSession) {
        if (!firstFrame || serverUrl.isBlank()) return@LaunchedEffect
        // Maize titles never have useful subs — skip OpenSubtitles / sidecar lookup.
        if (adultSession.isNotBlank()) {
            remoteTracks = emptyList()
            remoteBusy = false
            remoteError = ""
            autoLoadDone = true
            return@LaunchedEffect
        }
        // Trailers reuse the movie MediaItem — never auto-load that title's subtitles.
        val trailerSession = liveSession?.id.orEmpty().startsWith("trailer:") ||
            liveSession?.url.orEmpty().contains("/trailer")
        if (trailerSession) {
            remoteTracks = emptyList()
            remoteBusy = false
            remoteError = ""
            autoLoadDone = true
            selectedRemoteId = null
            playerViewModel.clearExternalSubtitle()
            playerViewModel.setTextOff()
            return@LaunchedEffect
        }
        remoteBusy = true
        try {
            val api = CoogApi(serverUrl, token)
            val media = item
            val series = (media?.season ?: 0) > 0 || (media?.episode ?: 0) > 0 ||
                media?.kind == "episode" || media?.kind == "series"
            val res = api.listSubtitles(
                imdbId = media?.imdbId.orEmpty(),
                kind = when {
                    media?.kind.orEmpty().isNotBlank() -> media!!.kind
                    series -> "episode"
                    else -> "movie"
                },
                season = media?.season ?: 0,
                episode = media?.episode ?: 0,
                mediaId = liveSession?.mediaId.orEmpty().ifBlank { media?.diskMediaId().orEmpty() },
                // Show title for series — episode titles confuse OpenSubtitles query matching.
                query = if (series) {
                    media?.showTitle.orEmpty().ifBlank { media?.title.orEmpty() }
                } else {
                    media?.title.orEmpty().ifBlank { media?.showTitle.orEmpty() }
                },
                tmdbId = media?.tmdbId ?: 0,
            )
            preferredLangs = res.settings.languages.ifEmpty { listOf("en") }
            autoLoad = res.settings.autoLoad
            autoSync = res.settings.autoSync
            preferEmbedded = res.settings.preferEmbedded
            remoteError = res.error
            // Server already returns a few per preferred language — keep that list intact.
            remoteTracks = sortSubtitleTracks(
                res.tracks.filter { it.id != "off" && it.source != "none" },
            )
            // Don't clobber a track the user already picked (selectedRemoteId stays null for
            // in-file Exo tracks — guard on text selection / textOff instead).
            val userPickedInFile = textTracks.any { it.selected } && !textOff
            if (!autoLoadDone && autoLoad && selectedRemoteId == null && !userPickedInFile) {
                // Only SubRip/SSA/VTT count as preferEmbedded — PGS looks "selected" but
                // never paints in the text overlay, and used to block OpenSubtitles.
                val embedded = textTracks.filter {
                    it.isTextBasedSubtitle() && it.language.isNotBlank()
                }
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
                    else -> {
                        if (!textOff) playerViewModel.setTextOff()
                        pickAutoRemote(
                            tracks = remoteTracks,
                            preferredLangs = preferredLangs,
                            title = media?.showTitle.orEmpty().ifBlank { media?.title.orEmpty() }
                                .ifBlank { title },
                            season = media?.season ?: 0,
                            episode = media?.episode ?: 0,
                        )?.let { applyRemote(it) }
                    }
                }
                autoLoadDone = true
            } else if (!autoLoadDone) {
                autoLoadDone = true
            }
        } catch (err: Exception) {
            remoteError = err.message.orEmpty()
        } finally {
            remoteBusy = false
        }
    }
    LaunchedEffect(playError, serverUrl, token, liveSession?.id) {
        val message = playError ?: return@LaunchedEffect
        val active = liveSession ?: return@LaunchedEffect
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
    LaunchedEffect(session?.jobId, serverUrl, token, adultSession) {
        val jobId = session?.jobId.orEmpty()
        if (jobId.isBlank() || session?.method != "progressive") {
            playerViewModel.suppressEnded = false
            return@LaunchedEffect
        }
        val api = CoogApi(serverUrl, token, adultSession)
        var knownMediaId = session?.mediaId.orEmpty()
        suspend fun handoffToLibrary(mediaId: String): Boolean {
            if (mediaId.isBlank()) return false
            var next = runCatching {
                api.playbackSession(mediaId = mediaId)
            }.getOrNull() ?: return false
            if (next.url.isBlank() || next.error.isNotBlank()) return false
            if (next.url == liveSession?.url && next.audioUrl == liveSession?.audioUrl) return true
            val at = maxOf(player.currentPosition, position).coerceAtLeast(0L)
            if (next.method == "remux" && next.audioUrl.isNotBlank() && at > 3_000L) {
                jobStatus = "preparing"
                playerViewModel.suppressEnded = true
                api.waitForRemuxAudio(next.audioUrl, at)
                runCatching { api.playbackSession(mediaId = mediaId) }.getOrNull()?.let { fresh ->
                    if (fresh.url.isNotBlank() && fresh.error.isBlank()) next = fresh
                }
            }
            handoffResumeMs = at
            seamlessHandoff = true
            liveSession = next.copy(jobId = "")
            jobDownloading = false
            jobStatus = ""
            playerViewModel.suppressEnded = false
            return true
        }
        while (true) {
            try {
                val job = api.job(jobId)
                jobBuffered = job.bufferedMs
                jobProgress = job.progress.toFloat()
                jobStatus = job.status
                if (job.quality.isNotBlank()) jobQuality = job.quality
                if (job.sizeLabel.isNotBlank()) jobSizeLabel = job.sizeLabel
                if (job.sizeBytes > 0L) {
                    playerViewModel.setProgressiveFinalBytes(job.sizeBytes)
                }
                if (job.expectedDurationMs > 0) {
                    jobExpected = job.expectedDurationMs
                    duration = job.expectedDurationMs
                }
                if (job.mediaId.isNotBlank()) knownMediaId = job.mediaId
                jobDownloading = job.status == "downloading" || job.status == "ready" || job.status == "queued"
                playerViewModel.suppressEnded = jobDownloading
                when (job.status) {
                    "finished" -> {
                        playerViewModel.suppressEnded = false
                        jobDownloading = false
                        jobStatus = ""
                        handoffToLibrary(job.mediaId.ifBlank { knownMediaId })
                        break
                    }
                    "error", "cancelled" -> {
                        playerViewModel.suppressEnded = false
                        jobDownloading = false
                        jobStatus = job.status
                        break
                    }
                }
            } catch (_: Exception) {
                // Job removed after finish — hand off using the last known library id.
                playerViewModel.suppressEnded = false
                jobDownloading = false
                jobStatus = ""
                handoffToLibrary(knownMediaId.ifBlank { liveSession?.mediaId.orEmpty() })
                break
            }
            delay(1500)
        }
    }
    // Splash before a session exists: poll the matching download for status text.
    LaunchedEffect(session?.id, item?.id, item?.imdbId, serverUrl, token, adultSession) {
        if (session != null) return@LaunchedEffect
        if (item == null || serverUrl.isBlank()) return@LaunchedEffect
        val api = CoogApi(serverUrl, token, adultSession)
        while (true) {
            val jobs = runCatching { api.jobs() }.getOrDefault(emptyList())
            val job = item.matchingJob(jobs.filter { it.status != "finished" && it.status != "cancelled" })
            if (job != null) {
                jobBuffered = job.bufferedMs
                jobProgress = job.progress.toFloat()
                jobStatus = job.status
                if (job.quality.isNotBlank()) jobQuality = job.quality
                if (job.sizeLabel.isNotBlank()) jobSizeLabel = job.sizeLabel
                jobDownloading = job.status == "downloading" || job.status == "ready" || job.status == "queued"
                if (job.expectedDurationMs > 0) jobExpected = job.expectedDurationMs
            } else {
                jobStatus = "preparing"
                jobDownloading = true
            }
            delay(1500)
        }
    }
    // If the progressive file 404s before the poll notices finish, recover once.
    LaunchedEffect(playError, liveSession?.method, liveSession?.mediaId, liveSession?.jobId, serverUrl, token, adultSession) {
        val err = playError ?: return@LaunchedEffect
        val active = liveSession ?: return@LaunchedEffect
        if (active.method != "progressive") return@LaunchedEffect
        if (active.id.startsWith("trailer:") || active.url.contains("/trailer")) return@LaunchedEffect
        val looksGone = err.contains("404", ignoreCase = true) ||
            err.contains("BAD_HTTP", ignoreCase = true) ||
            err.contains("Not Found", ignoreCase = true)
        if (!looksGone) return@LaunchedEffect
        val mediaId = active.mediaId.ifBlank {
            val jobId = active.jobId
            if (jobId.isBlank()) return@LaunchedEffect
            runCatching { CoogApi(serverUrl, token, adultSession).job(jobId).mediaId }.getOrNull().orEmpty()
        }
        if (mediaId.isBlank()) return@LaunchedEffect
        var next = runCatching {
            CoogApi(serverUrl, token, adultSession).playbackSession(mediaId = mediaId)
        }.getOrNull() ?: return@LaunchedEffect
        if (next.url.isBlank() || next.error.isNotBlank()) return@LaunchedEffect
        val at = maxOf(player.currentPosition, position).coerceAtLeast(0L)
        if (next.method == "remux" && next.audioUrl.isNotBlank() && at > 3_000L) {
            CoogApi(serverUrl, token, adultSession).waitForRemuxAudio(next.audioUrl, at)
            runCatching {
                CoogApi(serverUrl, token, adultSession).playbackSession(mediaId = mediaId)
            }.getOrNull()?.let { fresh ->
                if (fresh.url.isNotBlank() && fresh.error.isBlank()) next = fresh
            }
        }
        handoffResumeMs = at
        seamlessHandoff = true
        liveSession = next.copy(jobId = "")
        jobDownloading = false
        playerViewModel.suppressEnded = false
    }
    // Dolby E-AC3 etc. can advertise support then fail at runtime — remux to AAC once.
    var audioRemuxRetried by remember(liveSession?.id) { mutableStateOf(false) }
    LaunchedEffect(playError, liveSession?.id, serverUrl, token, adultSession) {
        val err = playError ?: return@LaunchedEffect
        val active = liveSession ?: return@LaunchedEffect
        if (audioRemuxRetried) return@LaunchedEffect
        // Never swap a trailer into a library playback session (that leaks continue-watching).
        if (active.id.startsWith("trailer:") ||
            active.url.contains("/trailer") ||
            title.contains("·  Trailer") ||
            title.endsWith(" Trailer", ignoreCase = true)
        ) {
            return@LaunchedEffect
        }
        val decodeFail = err.contains("decode failed", ignoreCase = true) ||
            err.contains("DECODING_FAILED", ignoreCase = true) ||
            err.contains("CodecException", ignoreCase = true) ||
            err.contains("eac3", ignoreCase = true) ||
            err.contains("ac3", ignoreCase = true) ||
            err.contains("truehd", ignoreCase = true) ||
            err.contains("dts", ignoreCase = true)
        if (!decodeFail) return@LaunchedEffect
        if (active.method != "progressive" && active.method != "direct" && active.method != "remux") {
            return@LaunchedEffect
        }
        // Already using an AAC sidecar / HLS remux — further force will not help.
        if (active.audioUrl.isNotBlank() ||
            active.contentType.contains("mpegurl", ignoreCase = true) ||
            active.url.contains("/progressive/audio", ignoreCase = true) ||
            active.url.contains("/remux/", ignoreCase = true)
        ) {
            return@LaunchedEffect
        }
        audioRemuxRetried = true
        val api = CoogApi(serverUrl, token, adultSession)
        var next = runCatching {
            api.playbackSession(
                mediaId = active.mediaId,
                jobId = active.jobId,
                forceAudioRemux = true,
            )
        }.getOrNull() ?: return@LaunchedEffect
        if (next.url.isBlank() || next.error.isNotBlank()) return@LaunchedEffect
        if (next.url == active.url && next.audioUrl == active.audioUrl) return@LaunchedEffect
        val at = maxOf(player.currentPosition, position).coerceAtLeast(0L)
        if (next.audioUrl.isNotBlank() && at > 3_000L) {
            api.waitForRemuxAudio(next.audioUrl, at)
            runCatching {
                api.playbackSession(
                    mediaId = active.mediaId,
                    jobId = active.jobId,
                    forceAudioRemux = true,
                )
            }.getOrNull()?.let { fresh ->
                if (fresh.url.isNotBlank() && fresh.error.isBlank()) next = fresh
            }
        }
        handoffResumeMs = at
        seamlessHandoff = true
        liveSession = next
    }
    // Progress ticks must not invalidate the subtitle/audio side panel.
    LaunchedEffect(player, menu, scrubMs, hudVisible) {
        while (true) {
            val tickHud = scrubMs != null || (menu == PlayerMenu.None && hudVisible)
            if (tickHud) {
                position = player.currentPosition
                buffered = player.bufferedPosition
                playing = player.isPlaying
                duration = playbackDurationMs(
                    exoDuration = player.duration,
                    expectedMs = maxOf(liveSession?.expectedDurationMs ?: 0L, jobExpected),
                    bufferedMs = if (jobBuffered > 0L) jobBuffered else buffered,
                    streaming = serverStillDownloading(),
                )
                delay(250)
            } else {
                val nowPlaying = player.isPlaying
                if (nowPlaying != playing) playing = nowPlaying
                delay(1_000)
            }
        }
    }
    LaunchedEffect(hudNonce, playing, hudExpanded, menu, hudVisible, scrubMs) {
        if (!hudVisible || !playing || menu != PlayerMenu.None || scrubMs != null) {
            return@LaunchedEffect
        }
        // TV: only auto-hide the collapsed transport strip. Touch: hide the full chrome.
        if (!BuildConfig.TOUCH_CLIENT && hudExpanded) {
            return@LaunchedEffect
        }
        delay(if (BuildConfig.TOUCH_CLIENT) 4_500 else 3_500)
        hudVisible = false
        hudExpanded = false
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
    // Prefetch API resolves *next after* the given S/E — pass the episode now playing,
    // not nextItem (that would queue the episode after next).
    var prefetchSent by remember(session?.url, item?.id) { mutableStateOf(false) }
    LaunchedEffect(prefetchNext, item?.id, nextItem?.id, prefetchBeforeEndMinutes, liveSession?.expectedDurationMs, jobExpected) {
        if (!prefetchNext || prefetchSent) return@LaunchedEffect
        val current = item ?: return@LaunchedEffect
        if (nextItem == null) return@LaunchedEffect
        val windowMs = prefetchBeforeEndMinutes.coerceAtLeast(1) * 60_000L
        while (!prefetchSent) {
            val dur = playbackDurationMs(
                exoDuration = player.duration,
                expectedMs = maxOf(liveSession?.expectedDurationMs ?: 0L, jobExpected),
                bufferedMs = jobBuffered.takeIf { it > 0L } ?: player.bufferedPosition,
                streaming = serverStillDownloading(),
            )
            if (dur > 0L && player.currentPosition >= (dur - windowMs).coerceAtLeast(0L)) {
                prefetchSent = true
                onPrefetchNeighbor?.invoke(current)
                return@LaunchedEffect
            }
            delay(2_000)
        }
    }
    // Stop sync only when leaving the player — not when session URL attaches after push.
    DisposableEffect(syncClient, localDriver) {
        onDispose {
            reportWatch()
            // Halt before tearing the clock loop so Solace does not coast after Back.
            localDriver?.forcePaused(player.currentPosition)
            localDriver?.stop()
            syncClient?.stop()
        }
    }
    LaunchedEffect(
        item?.id,
        adultSession,
        item?.hasFunscript,
        item?.selectedFunscript,
        item?.funscriptName,
        liveSession?.mediaId,
        localEngine.connected,
        useDeviceBle,
    ) {
        val media = item ?: return@LaunchedEffect
        // Prefer library disk id — session mediaId can be a job id with no funscript.
        val mediaId = media.diskMediaId()
            .ifBlank { liveSession?.mediaId.orEmpty() }
            .takeUnless {
                it.isBlank() ||
                    it.startsWith("job:") ||
                    it.startsWith("catalog:") ||
                    it.startsWith("continue:")
            }
            .orEmpty()
            .ifBlank { media.diskMediaId() }
        val wantsScript = media.hasFunscript || media.funscripts.isNotEmpty() || media.funscriptName.isNotBlank()
        if (mediaId.isBlank() || adultSession.isBlank() || !wantsScript) {
            localDriver?.stop()
            syncClient?.stop()
            return@LaunchedEffect
        }
        val script = media.selectedFunscript.ifBlank { media.funscriptName }
        val resume = media.positionMs.coerceAtLeast(0L)
        // Phone BLE mode → local drive only (never fall through to server sync while
        // the phone owns the radio; wait until the in-app Intiface WS is up).
        if (useDeviceBle) {
            syncClient?.stop()
            if (localEngine.connected && localDriver != null) {
                localDriver.start(
                    mediaId = mediaId,
                    resumeMs = resume,
                    scope = scope,
                    position = { player.currentPosition },
                    player = player,
                    script = script,
                )
            } else {
                localDriver?.stop()
            }
            return@LaunchedEffect
        }
        localDriver?.stop()
        val sync = syncClient ?: return@LaunchedEffect
        val supported = runCatching { CoogApi(serverUrl, token, adultSession).maizeSyncStatus().supported }
            .getOrDefault(false)
        if (!supported) {
            sync.stop()
            return@LaunchedEffect
        }
        sync.start(
            mediaId = mediaId,
            resumeMs = resume,
            scope = scope,
            position = { player.currentPosition },
            player = player,
            script = script,
        )
    }

    val sizeLabel = when (subtitleSize) {
        0 -> "S"
        2 -> "L"
        else -> "M"
    }
    val maizePlayback = adultSession.isNotBlank()
    val showAudioPicker = audioTracks.isNotEmpty()
    val showSubtitles = !maizePlayback
    val showDevices = maizePlayback
    val subsOn = showSubtitles && (selectedRemoteId != null || (!textOff && textTracks.any { it.selected }))
    val audioLabel = audioTracks.firstOrNull { it.selected }?.label?.take(18)
        ?: audioTracks.firstOrNull()?.label?.take(18)
        ?: "Audio"
    val subLabel = shortSubLabel()
    var serverDevices by remember { mutableStateOf<List<InteractiveDevice>>(emptyList()) }
    LaunchedEffect(maizePlayback, useDeviceBle, serverUrl, token, adultSession, menu) {
        if (!maizePlayback || useDeviceBle) {
            serverDevices = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            serverDevices = runCatching {
                CoogApi(serverUrl, token, adultSession).interactiveEngine().devices
            }.getOrDefault(emptyList())
            delay(2_000)
        }
    }
    val playerDevices = remember(useDeviceBle, localEngine.devices, serverDevices) {
        val raw = if (useDeviceBle) localEngine.devices else serverDevices
        raw.filter { it.connected || it.index >= 0 }
            .distinctBy { it.deviceId.ifBlank { "${it.index}-${it.name}" } }
    }
    val railActions = remember(
        showAudioPicker,
        showSubtitles,
        showDevices,
        previousItem != null,
        nextItem != null,
    ) {
        buildList {
            add(HudRailAction.Play)
            if (showAudioPicker) add(HudRailAction.Audio)
            if (showSubtitles) add(HudRailAction.Subtitles)
            add(HudRailAction.Picture)
            if (showDevices) add(HudRailAction.Devices)
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
    LaunchedEffect(showDevices, menu) {
        if (!showDevices && menu == PlayerMenu.Devices) {
            menu = PlayerMenu.None
        }
    }

    /**
     * True when a usable timing reference exists for the *current* selection.
     * PGS → always false. In-file SRT → other in-file text/sidecar. Online → embed/OS.
     */
    val autosyncPossible = run {
        val selected = remoteTracks.firstOrNull { it.id == selectedRemoteId }
        when {
            selected == null ->
                // No overlay selection yet — allow arming if a future OS/sidecar pick can sync.
                externalRemoteTracks.isNotEmpty() || sidecarRemotes.isNotEmpty()
            selected.isBitmapSubtitle() -> false
            else -> pickSyncReference(selected) != null
        }
    }
    LaunchedEffect(autosyncPossible) {
        if (!autosyncPossible && autoSync) {
            autoSync = false
            playerViewModel.setAutosyncEnabled(false)
        }
    }

    fun toggleAutosync() {
        if (!autosyncPossible) return
        val turningOn = !autoSync
        autoSync = turningOn
        if (!turningOn) {
            playerViewModel.setAutosyncEnabled(false)
            bumpHud(expanded = true)
            return
        }
        // Ensure a timing reference is loaded, then run sync.
        val selected = remoteTracks.firstOrNull { it.id == selectedRemoteId }
        val ref = selected?.let { pickSyncReference(it) }
        if (ref == null) {
            playerViewModel.setAutosyncEnabled(true)
            bumpHud(expanded = true)
            return
        }
        val trackId = selectedRemoteId
        CoroutineScope(Dispatchers.IO).launch {
            val body = runCatching { CoogApi(serverUrl, token).fetchSubtitleBody(ref.id) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (trackId != null && selectedRemoteId != trackId) return@withContext
                if (!body.isNullOrBlank()) {
                    playerViewModel.setAutosyncReference(body, ref.language)
                }
                playerViewModel.setAutosyncEnabled(true)
                bumpHud(expanded = true)
            }
        }
    }

    val subtitleMenuActions = remember(
        subsOn,
        remoteError,
        menuTextTracks.map { it.id to it.selected },
        menuMovieRemotes.map { it.id },
        externalRemoteTracks.map { it.id },
        remoteBusy,
        autosyncPossible,
        autoSync,
    ) {
        buildList {
            val noop: () -> Unit = {}
            if (subsOn) {
                add(noop) // Sync ms display-only
                add {
                    playerViewModel.nudgeSubtitleDelay(-250)
                    bumpHud(expanded = true)
                }
                add {
                    playerViewModel.nudgeSubtitleDelay(250)
                    bumpHud(expanded = true)
                }
                add {
                    toggleAutosync()
                }
                add {
                    playerViewModel.cycleSubtitleSize()
                    bumpHud(expanded = true)
                }
            }
            add {
                selectedRemoteId = null
                playerViewModel.clearExternalSubtitle()
                playerViewModel.setTextOff()
                bumpHud(expanded = true)
            }
            if (remoteError.isNotBlank()) {
                add(noop)
            }
            val inFile = menuTextTracks.isNotEmpty() || menuMovieRemotes.isNotEmpty()
            if (inFile) {
                add(noop) // "In this file"
                menuTextTracks.forEach { track ->
                    add {
                        selectedRemoteId = null
                        playerViewModel.selectTrack(track)
                        bumpHud(expanded = true)
                    }
                }
                menuMovieRemotes.forEach { track ->
                    add { applyRemote(track) }
                }
            }
            if (externalRemoteTracks.isNotEmpty() || remoteBusy) {
                add(noop) // "Online"
                if (remoteBusy && externalRemoteTracks.isEmpty()) {
                    add(noop) // "Searching…"
                }
                externalRemoteTracks.forEach { track ->
                    add { applyRemote(track) }
                }
            }
        }
    }

    fun subtitleRowIsSection(index: Int): Boolean {
        // Mirror SideSettingsPanel section rows so DPAD skips headers.
        var i = 0
        if (subsOn) {
            if (index == i) return true // Sync ms display
            i += 1
            i += 2 // −250, +250
            i += 1 // Autosync
            i += 1 // Size
        }
        if (index == i) return false // Off
        i += 1
        if (remoteError.isNotBlank()) {
            if (index == i) return true
            i += 1
        }
        val inFile = menuTextTracks.isNotEmpty() || menuMovieRemotes.isNotEmpty()
        if (inFile) {
            if (index == i) return true
            i += 1 + menuTextTracks.size + menuMovieRemotes.size
        }
        if (externalRemoteTracks.isNotEmpty() || remoteBusy) {
            if (index == i) return true
            i += 1
            if (remoteBusy && externalRemoteTracks.isEmpty()) {
                if (index == i) return true
            }
        }
        return false
    }

    fun stepMenuSel(delta: Int) {
        // Inline counts — local funs can't call siblings defined later in the composable.
        val count = when (menu) {
            PlayerMenu.None -> 0
            PlayerMenu.Audio -> audioTracks.size
            PlayerMenu.Subtitles -> subtitleMenuActions.size
            PlayerMenu.Picture -> PictureMode.entries.size
            PlayerMenu.Devices -> 0
        }.coerceAtLeast(1)
        if (menu != PlayerMenu.Subtitles) {
            menuSel = (menuSel + delta).coerceIn(0, count - 1)
            return
        }
        var next = menuSel
        repeat(count) {
            next += delta
            if (next !in 0 until count) {
                if (delta < 0) {
                    menu = PlayerMenu.None
                }
                return
            }
            if (!subtitleRowIsSection(next)) {
                menuSel = next
                return
            }
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
            HudRailAction.Devices -> {
                menu = if (menu == PlayerMenu.Devices) PlayerMenu.None else PlayerMenu.Devices
                menuSel = 0
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
            PlayerMenu.None, PlayerMenu.Devices -> Unit
            PlayerMenu.Audio -> {
                val track = audioTracks.getOrNull(menuSel) ?: return
                playerViewModel.selectTrack(track)
                bumpHud(expanded = true)
            }
            PlayerMenu.Subtitles -> {
                subtitleMenuActions.getOrNull(menuSel)?.invoke()
            }
            PlayerMenu.Picture -> {
                val mode = PictureMode.entries.getOrNull(menuSel) ?: return
                pictureMode = mode
                bumpHud(expanded = true)
            }
        }
    }

    fun menuCount(): Int = when (menu) {
        PlayerMenu.None, PlayerMenu.Devices -> 0
        PlayerMenu.Audio -> audioTracks.size
        PlayerMenu.Subtitles -> subtitleMenuActions.size
        PlayerMenu.Picture -> PictureMode.entries.size
    }

    fun patchDeviceIntensity(device: InteractiveDevice, delta: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (useDeviceBle) {
                    if (device.deviceId.isBlank()) return@runCatching
                    localIntiface.patchIntensity(
                        device.deviceId,
                        (device.intensity + delta).coerceIn(10, 200),
                    )
                } else if (device.index >= 0) {
                    CoogApi(serverUrl, token, adultSession).interactivePatch(
                        device.index,
                        intensity = (device.intensity + delta).coerceIn(10, 200),
                    )
                    serverDevices = runCatching {
                        CoogApi(serverUrl, token, adultSession).interactiveEngine().devices
                    }.getOrDefault(serverDevices)
                }
            }
        }
        bumpHud(expanded = true)
    }

    fun patchDeviceOffset(device: InteractiveDevice, delta: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (useDeviceBle) {
                    if (device.deviceId.isBlank()) return@runCatching
                    localIntiface.patchOffset(device.deviceId, device.offsetMs + delta)
                } else if (device.index >= 0) {
                    CoogApi(serverUrl, token, adultSession).interactivePatch(
                        device.index,
                        offsetMs = device.offsetMs + delta,
                    )
                    serverDevices = runCatching {
                        CoogApi(serverUrl, token, adultSession).interactiveEngine().devices
                    }.getOrDefault(serverDevices)
                }
            }
        }
        bumpHud(expanded = true)
    }

    fun testDevice(device: InteractiveDevice) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (device.index < 0) return@runCatching
                if (useDeviceBle) localIntiface.test(device.index)
                else CoogApi(serverUrl, token, adultSession).interactiveTest(device.index)
            }
        }
        bumpHud(expanded = true)
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
                    localDriver?.forcePaused(player.currentPosition)
                    syncClient?.pause()
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
                                stepMenuSel(-1)
                                bumpHud(expanded = true)
                            }
                            true
                        }
                        down || right -> {
                            stepMenuSel(1)
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
        val subsVisible = !splash && !textOff && (
            externalOverlay || selectedRemoteId != null || textTracks.any { it.selected }
        )
        val subtitleFraction = when (subtitleSize) {
            0 -> 0.032f
            2 -> 0.056f
            else -> 0.042f
        }
        // Composition-level reads — AndroidView's update lambda alone is not a reliable
        // snapshot observer for cue text changes when switching external tracks.
        val overlayBlock = remember(cueLines, overlayRevision) {
            cueLines.joinToString("\n").trim()
        }
        val subtitleViewRef = remember { mutableStateOf<SubtitleView?>(null) }

        if (session != null) {
            // Touch: TextureView so SubtitleView/Compose sit above video (Jellyfin/Stremio model).
            // TV: keep SurfaceView for HDR path.
            ContentFrame(
                player = player,
                surfaceType = if (BuildConfig.TOUCH_CLIENT) {
                    SURFACE_TYPE_TEXTURE_VIEW
                } else {
                    SURFACE_TYPE_SURFACE_VIEW
                },
                contentScale = pictureMode.scale,
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = false },
            )
        }

        // Above video, below HUD/menus — never z-index over controls.
        val subtitleBottomFraction = when {
            menu != PlayerMenu.None -> 0.30f
            hudExpanded -> 0.24f
            hudVisible -> 0.16f
            else -> 0.08f
        }
        LaunchedEffect(
            overlayBlock,
            overlayRevision,
            externalOverlay,
            subsVisible,
            selectedRemoteId,
            subtitleFraction,
            subtitleBottomFraction,
        ) {
            val view = subtitleViewRef.value ?: return@LaunchedEffect
            view.setFractionalTextSize(subtitleFraction)
            view.setBottomPaddingFraction(subtitleBottomFraction)
            @Suppress("UNCHECKED_CAST")
            val gate = (view.tag as? Pair<Player.Listener, BooleanArray>)?.second
            if (!subsVisible) {
                gate?.set(0, false)
                view.setCues(emptyList())
                return@LaunchedEffect
            }
            if (externalOverlay) {
                gate?.set(0, true)
                view.setCues(
                    if (overlayBlock.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            androidx.media3.common.text.Cue.Builder().setText(overlayBlock).build(),
                        )
                    },
                )
            } else {
                // Must replace stale overlay text — gate alone leaves the previous cues painted
                // (e.g. English OS still showing after picking EL/Greek in-file).
                gate?.set(0, false)
                view.setCues(player.currentCues.cues)
            }
        }
        // One SubtitleView for both embedded (Exo cues) and online (parsed overlay cues)
        // so caption style stays identical. Lag fix is fetch/parse without re-prepare — not Compose text.
        if (session != null && subsVisible) {
            key(selectedRemoteId ?: "embedded") {
                AndroidView(
                    factory = { context ->
                        SubtitleView(context).also { view ->
                            view.setUserDefaultStyle()
                            view.setFractionalTextSize(subtitleFraction)
                            view.setBottomPaddingFraction(subtitleBottomFraction)
                            val gate = booleanArrayOf(externalOverlay)
                            val listener = object : Player.Listener {
                                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                                    if (gate[0]) return
                                    view.setCues(cueGroup.cues)
                                }
                            }
                            view.tag = listener to gate
                            player.addListener(listener)
                            subtitleViewRef.value = view
                            if (externalOverlay) {
                                view.setCues(
                                    if (overlayBlock.isEmpty()) {
                                        emptyList()
                                    } else {
                                        listOf(
                                            androidx.media3.common.text.Cue.Builder()
                                                .setText(overlayBlock)
                                                .build(),
                                        )
                                    },
                                )
                            } else {
                                view.setCues(player.currentCues.cues)
                            }
                        }
                    },
                    update = { view ->
                        subtitleViewRef.value = view
                        view.setFractionalTextSize(subtitleFraction)
                        view.setBottomPaddingFraction(subtitleBottomFraction)
                        @Suppress("UNCHECKED_CAST")
                        val gate = (view.tag as? Pair<Player.Listener, BooleanArray>)?.second
                        gate?.set(0, externalOverlay)
                        if (externalOverlay) {
                            view.setCues(
                                if (overlayBlock.isEmpty()) {
                                    emptyList()
                                } else {
                                    listOf(
                                        androidx.media3.common.text.Cue.Builder()
                                            .setText(overlayBlock)
                                            .build(),
                                    )
                                },
                            )
                        } else {
                            view.setCues(player.currentCues.cues)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .focusProperties { canFocus = false },
                    onRelease = { view ->
                        if (subtitleViewRef.value === view) subtitleViewRef.value = null
                        @Suppress("UNCHECKED_CAST")
                        val listener = (view.tag as? Pair<Player.Listener, BooleanArray>)?.first
                        listener?.let { player.removeListener(it) }
                    },
                )
            }
        }

        // Tap video to show/hide chrome (HUD + side panel sit above and keep their own clicks).
        if (BuildConfig.TOUCH_CLIENT && !splash) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .pointerInput(hudVisible, hudExpanded, menu) {
                        detectTapGestures(
                            onTap = {
                                when {
                                    menu != PlayerMenu.None -> {
                                        menu = PlayerMenu.None
                                        bumpHud(expanded = true)
                                    }
                                    !hudVisible -> expandHud()
                                    else -> hideHud()
                                }
                            },
                            onDoubleTap = { offset ->
                                val mid = size.width / 2f
                                if (offset.x < mid) seekBy(-10_000L) else seekBy(10_000L)
                            },
                        )
                    },
            )
        }

        if (splash) {
            val splashStatus = remember(
                jobDownloading,
                jobStatus,
                jobProgress,
                jobBuffered,
                jobQuality,
                jobSizeLabel,
                session?.id,
                session?.method,
            ) {
                splashStatusLabel(
                    sessionReady = session != null,
                    progressive = session?.method == "progressive" || jobDownloading,
                    jobDownloading = jobDownloading,
                    jobStatus = jobStatus,
                    jobProgress = jobProgress,
                    jobBufferedMs = jobBuffered,
                    quality = jobQuality,
                    sizeLabel = jobSizeLabel,
                )
            }
            MediaLoadingScreen(item = item, title = title, statusLabel = splashStatus)
        }

        playError?.let { message ->
            Text(
                message,
                style = CoogType.heroTagline,
                color = Color(0xFFFF8B8B),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
            )
        }

        val touchClient = BuildConfig.TOUCH_CLIENT
        AnimatedVisibility(
            visible = !splash && menu != PlayerMenu.None,
            enter = if (touchClient) {
                fadeIn(tween(140)) + slideInVertically(tween(200)) { it / 2 }
            } else {
                fadeIn(tween(140)) + slideInHorizontally(tween(180)) { it / 3 }
            },
            exit = if (touchClient) {
                fadeOut(tween(110)) + slideOutVertically(tween(160)) { it / 2 }
            } else {
                fadeOut(tween(110)) + slideOutHorizontally(tween(150)) { it / 3 }
            },
            modifier = if (touchClient) {
                Modifier
                    .align(Alignment.BottomEnd)
                    .zIndex(4f)
                    .padding(start = 16.dp, end = 16.dp, bottom = 108.dp)
            } else {
                Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(4f)
                    .padding(end = 36.dp, top = 48.dp, bottom = 120.dp)
            },
        ) {
            if (menu == PlayerMenu.Devices) {
                PlayerDevicesPanel(
                    devices = playerDevices,
                    onIntensity = { device, delta -> patchDeviceIntensity(device, delta) },
                    onOffset = { device, delta -> patchDeviceOffset(device, delta) },
                    onTest = { device -> testDevice(device) },
                    onClose = {
                        menu = PlayerMenu.None
                        bumpHud(expanded = true)
                    },
                )
            } else {
                SideSettingsPanel(
                    menu = menu,
                    menuSel = menuSel,
                    audioTracks = audioTracks,
                    textTracks = menuTextTracks,
                    movieRemoteTracks = menuMovieRemotes,
                    externalRemoteTracks = externalRemoteTracks,
                    remoteBusy = remoteBusy,
                    remoteError = remoteError,
                    textOff = !subsOn,
                    selectedRemoteId = selectedRemoteId,
                    subtitleDelayMs = subtitleDelayMs,
                    sizeLabel = sizeLabel,
                    subsOn = subsOn,
                    autoSync = autoSync,
                    autosyncPossible = autosyncPossible,
                    pictureMode = pictureMode,
                    onActivate = { index ->
                        menuSel = index
                        activateMenu()
                    },
                    onClose = {
                        menu = PlayerMenu.None
                        bumpHud(expanded = true)
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = hudVisible && !splash,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(3f),
        ) {
            PlayerHud(
                title = title,
                positionMs = scrubMs ?: position,
                durationMs = duration,
                bufferedMs = readyBufferedMs(),
                seekMaxMs = seekLimit(),
                // "N:NN ready" pill only while the server is still downloading the stream.
                // Local library/direct/remux: seekbar alone shows device buffer.
                remainingMs = if (serverStillDownloading() && duration > 0) {
                    (duration - readyBufferedMs()).coerceAtLeast(0L)
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
                onScrub = { target ->
                    if (scrubMs == null) {
                        scrubResumePlay = playing
                        if (playing) player.pause()
                        scrubStartedAt = SystemClock.elapsedRealtime()
                    }
                    scrubMs = target.coerceIn(0L, seekLimit())
                    bumpHud(expanded = true)
                },
                onScrubEnd = {
                    commitScrub()
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
    seekMaxMs: Long = durationMs,
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
    onScrub: ((Long) -> Unit)? = null,
    onScrubEnd: (() -> Unit)? = null,
) {
    val touch = BuildConfig.TOUCH_CLIENT
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
            .padding(
                start = if (touch) 20.dp else 48.dp,
                end = if (touch) 20.dp else 48.dp,
                top = if (touch) 20.dp else 28.dp,
                bottom = if (touch) 20.dp else 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 10.dp),
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
            seekMaxMs = seekMaxMs,
            onScrub = onScrub,
            onScrubEnd = onScrubEnd,
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(90)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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
                        HudRailAction.Devices -> HudRailButton(
                            icon = Icons.Outlined.Speaker,
                            label = "Devices",
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
                when {
                    scrubbing && touch -> "Release to seek"
                    scrubbing -> "Release to seek"
                    touch -> "Tap for controls   ·   double-tap ±10s"
                    else -> "OK play/pause   ·   hold ← → scrub   ·   ↓ more"
                },
                style = CoogType.cardYear,
                color = CoogTextMuted.copy(alpha = 0.65f),
            )
        }
    }
}

/** Compact splash status under the logo; null hides the pill. */
private fun splashStatusLabel(
    sessionReady: Boolean,
    progressive: Boolean,
    jobDownloading: Boolean,
    jobStatus: String,
    jobProgress: Float,
    jobBufferedMs: Long,
    quality: String = "",
    sizeLabel: String = "",
): String? {
    fun metaSuffix(): String {
        val bits = listOf(quality.trim(), sizeLabel.trim()).filter { it.isNotBlank() }
        return if (bits.isEmpty()) "" else " · ${bits.joinToString(" · ")}"
    }
    if (!sessionReady || jobDownloading || progressive) {
        when (jobStatus) {
            "queued" -> return "Queued${metaSuffix()}"
            "paused" -> return "Paused${metaSuffix()}"
            "preparing" -> return "Preparing…"
            "error", "cancelled" -> return null
            else -> {
                val pct = (jobProgress * 100f).toInt().coerceIn(0, 99)
                return when {
                    jobBufferedMs > 0L ->
                        "Buffering · ${formatClock(jobBufferedMs)} ready${metaSuffix()}"
                    pct > 0 ->
                        "Downloading · $pct%${metaSuffix()}"
                    jobStatus == "ready" ->
                        "Buffering…${metaSuffix()}"
                    jobStatus == "downloading" ->
                        "Downloading…${metaSuffix()}"
                    progressive || jobDownloading ->
                        "Preparing…"
                    else -> null
                }
            }
        }
    }
    // Session ready, waiting on first frame (local/network buffer).
    if (sessionReady) return "Buffering…"
    return null
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
    seekMaxMs: Long = durationMs,
    onScrub: ((Long) -> Unit)? = null,
    onScrubEnd: (() -> Unit)? = null,
) {
    val touch = BuildConfig.TOUCH_CLIENT && onScrub != null
    // Track width = full title length. Downloaded/ready range is only a fill + scrub clamp.
    val dur = durationMs.coerceAtLeast(1L)
    val seekCapMs = when {
        seekMaxMs > 0L -> seekMaxMs.coerceAtMost(dur)
        else -> dur
    }
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
        if (touch) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.White.copy(alpha = 0.16f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = buffered)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.White.copy(alpha = 0.30f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = played)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.White),
                )
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, dur.toFloat()),
                    onValueChange = { value ->
                        onScrub?.invoke(value.toLong().coerceIn(0L, seekCapMs))
                    },
                    onValueChangeFinished = {
                        onScrubEnd?.invoke()
                    },
                    valueRange = 0f..dur.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                        disabledActiveTrackColor = Color.Transparent,
                        disabledInactiveTrackColor = Color.Transparent,
                    ),
                )
            }
        } else {
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
    val touch = BuildConfig.TOUCH_CLIENT
    val bg = if (highlighted) Color.White else Color.White.copy(alpha = 0.10f)
    val fg = if (highlighted) Color(0xFF121214) else Color.White.copy(alpha = 0.92f)
    val chipStyle = CoogType.chip.merge(
        TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
    )
    CoogClickable(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = bg,
        modifier = Modifier.height(if (touch) 44.dp else 32.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = if (touch) 14.dp else 10.dp)
                .focusProperties { canFocus = false },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(if (touch) 18.dp else 14.dp),
                )
                Text(
                    label,
                    color = fg,
                    style = chipStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val HudPanelBg = Color(0xFF1A1A1C)
private val HudPanelStroke = Color(0x14FFFFFF)

@Composable
private fun PlayerDevicesPanel(
    devices: List<InteractiveDevice>,
    onIntensity: (InteractiveDevice, Int) -> Unit,
    onOffset: (InteractiveDevice, Int) -> Unit,
    onTest: (InteractiveDevice) -> Unit,
    onClose: () -> Unit,
) {
    val touch = BuildConfig.TOUCH_CLIENT
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val panelWidth = if (touch) {
        minOf(420.dp, screenWidth * 0.9f)
    } else {
        268.dp
    }
    val corner = if (touch) 20.dp else 12.dp
    Column(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(max = if (touch) 520.dp else 420.dp)
            .background(HudPanelBg, RoundedCornerShape(corner))
            .padding(
                horizontal = if (touch) 16.dp else 14.dp,
                vertical = if (touch) 14.dp else 12.dp,
            )
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (touch) 10.dp else 8.dp),
    ) {
        HudPanelHeader(
            icon = Icons.Outlined.Speaker,
            title = "Devices",
            onClose = if (touch) onClose else null,
        )
        if (devices.isEmpty()) {
            Text(
                "No devices connected",
                style = CoogType.cardYear,
                color = CoogTextMuted,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            devices.forEach { device ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        device.name.ifBlank { device.deviceId.ifBlank { "Device" } },
                        style = CoogType.cardTitle,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(device.status.ifBlank { if (device.connected) "connected" else "offline" })
                            append(" · intensity ${device.intensity}%")
                            append(" · offset ${device.offsetMs}ms")
                        },
                        style = CoogType.cardYear,
                        color = CoogTextMuted,
                    )
                    if (device.connected && device.index >= 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PlayerDeviceChip("Int −") { onIntensity(device, -10) }
                            PlayerDeviceChip("Int +") { onIntensity(device, 10) }
                            PlayerDeviceChip("Off −") { onOffset(device, -50) }
                            PlayerDeviceChip("Off +") { onOffset(device, 50) }
                            PlayerDeviceChip("Test") { onTest(device) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerDeviceChip(label: String, onClick: () -> Unit) {
    CoogClickable(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.10f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = CoogType.cardYear,
            color = Color.White,
        )
    }
}

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
    autoSync: Boolean = false,
    autosyncPossible: Boolean = false,
    pictureMode: PictureMode,
    onActivate: (Int) -> Unit,
    onClose: () -> Unit = {},
) {
    data class RowItem(
        val label: String,
        val checked: Boolean,
        val checkable: Boolean = true,
        val section: Boolean = false,
        val enabled: Boolean = true,
    )

    if (menu == PlayerMenu.None) return

    val touch = BuildConfig.TOUCH_CLIENT
    val icon: ImageVector
    val title: String
    val rows: List<RowItem>
    val empty: String?
    val toolCount: Int
    when (menu) {
        PlayerMenu.None, PlayerMenu.Devices -> return
        PlayerMenu.Audio -> {
            icon = Icons.Outlined.GraphicEq
            title = "Audio"
            rows = audioTracks.map { RowItem(it.label.take(48), checked = it.selected) }
            empty = if (rows.isEmpty()) "No audio tracks" else null
            toolCount = 0
        }
        PlayerMenu.Subtitles -> {
            icon = Icons.Outlined.ClosedCaption
            title = "Subtitles"
            rows = buildList {
                if (subsOn) {
                    // Sync value is display-only (section); ± buttons are separate actions.
                    add(
                        RowItem(
                            formatDelay(subtitleDelayMs),
                            checked = false,
                            checkable = false,
                            section = true,
                        ),
                    )
                    add(RowItem("−250 ms", checked = false, checkable = false))
                    add(RowItem("+250 ms", checked = false, checkable = false))
                    add(
                        RowItem(
                            label = "Autosync",
                            checked = autoSync && autosyncPossible,
                            enabled = autosyncPossible,
                        ),
                    )
                    add(RowItem("Size $sizeLabel", checked = false, checkable = false))
                }
                add(RowItem("Off", checked = textOff && selectedRemoteId == null))
                if (remoteError.isNotBlank()) {
                    add(RowItem(remoteError.take(72), checked = false, checkable = false, section = true))
                }
                val inFile = textTracks.isNotEmpty() || movieRemoteTracks.isNotEmpty()
                if (inFile) {
                    add(RowItem("In this file", checked = false, checkable = false, section = true))
                    textTracks.forEach { track ->
                        val active = selectedRemoteId == null && track.selected && !textOff
                        add(RowItem(trackDisplayLabel(track), checked = active))
                    }
                    movieRemoteTracks.forEach { track ->
                        add(RowItem(remoteDisplayLabel(track), checked = selectedRemoteId == track.id))
                    }
                }
                if (externalRemoteTracks.isNotEmpty() || remoteBusy) {
                    add(RowItem("Online", checked = false, checkable = false, section = true))
                    if (remoteBusy && externalRemoteTracks.isEmpty()) {
                        add(RowItem("Searching…", checked = false, checkable = false, section = true))
                    }
                    externalRemoteTracks.forEach { track ->
                        add(RowItem(remoteDisplayLabel(track), checked = selectedRemoteId == track.id))
                    }
                }
            }
            empty = when {
                remoteBusy &&
                    textTracks.isEmpty() &&
                    movieRemoteTracks.isEmpty() &&
                    externalRemoteTracks.isEmpty() -> "Searching…"
                remoteError.isNotBlank() &&
                    movieRemoteTracks.isEmpty() &&
                    externalRemoteTracks.isEmpty() &&
                    textTracks.isEmpty() -> remoteError
                textTracks.isEmpty() &&
                    movieRemoteTracks.isEmpty() &&
                    externalRemoteTracks.isEmpty() &&
                    !remoteBusy -> "No subtitles found"
                else -> null
            }
            // Sync label + −250 + +250 + Autosync + Size above the track list divider.
            // Sync label is section (skipped in touch tool strip via filter below).
            toolCount = if (subsOn) 5 else 0
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

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val panelWidth = if (touch) {
        minOf(420.dp, screenWidth * 0.9f)
    } else {
        268.dp
    }
    val corner = if (touch) 20.dp else 12.dp
    val trackRows = if (toolCount > 0) rows.drop(toolCount) else rows
    val toolRows = if (toolCount > 0) rows.take(toolCount) else emptyList()

    Column(
        modifier = Modifier
            .width(panelWidth)
            .heightIn(max = if (touch) 520.dp else 420.dp)
            .background(HudPanelBg, RoundedCornerShape(corner))
            .padding(
                horizontal = if (touch) 16.dp else 14.dp,
                vertical = if (touch) 14.dp else 12.dp,
            )
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (touch) 4.dp else 2.dp),
    ) {
        HudPanelHeader(icon = icon, title = title, onClose = if (touch) onClose else null)
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
                    Text(
                        empty,
                        style = CoogType.cardYear,
                        color = CoogTextMuted,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (touch && toolRows.isNotEmpty()) {
                    val syncLabel = toolRows.firstOrNull { it.section }
                    val actionTools = toolRows.mapIndexed { index, row -> index to row }
                        .filter { !it.second.section }
                    if (syncLabel != null) {
                        Text(
                            syncLabel.label,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actionTools.forEach { (index, row) ->
                            val toolOn = row.enabled && (row.checked || menuSel == index)
                            CoogClickable(
                                onClick = { if (row.enabled) onActivate(index) },
                                enabled = row.enabled,
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    !row.enabled -> Color.White.copy(alpha = 0.04f)
                                    row.checked -> Color.White.copy(alpha = 0.18f)
                                    menuSel == index -> Color.White.copy(alpha = 0.16f)
                                    else -> Color.White.copy(alpha = 0.08f)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        row.label,
                                        color = Color.White.copy(alpha = if (row.enabled) 0.92f else 0.32f),
                                        fontSize = 13.sp,
                                        fontWeight = if (toolOn) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(HudPanelStroke),
                    )
                    trackRows.forEachIndexed { offset, row ->
                        val index = toolCount + offset
                        if (row.section) {
                            HudSectionLabel(row.label)
                        } else {
                            HudListRow(
                                label = row.label,
                                checked = row.checked,
                                checkable = row.checkable,
                                enabled = row.enabled,
                                highlighted = menuSel == index,
                                onClick = { if (row.enabled) onActivate(index) },
                            )
                        }
                    }
                } else {
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
                        if (row.section) {
                            HudSectionLabel(row.label)
                        } else {
                            HudListRow(
                                label = row.label,
                                checked = row.checked,
                                checkable = row.checkable,
                                enabled = row.enabled,
                                highlighted = menuSel == index,
                                onClick = { if (row.enabled) onActivate(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudPanelHeader(
    icon: ImageVector,
    title: String,
    onClose: (() -> Unit)? = null,
) {
    val touch = BuildConfig.TOUCH_CLIENT
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (touch) 10.dp else 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(if (touch) 20.dp else 16.dp),
        )
        Text(
            title,
            style = CoogType.chip.copy(
                fontSize = if (touch) 16.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            ),
            modifier = Modifier.weight(1f),
        )
        if (onClose != null) {
            CoogClickable(
                onClick = onClose,
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HudSectionLabel(label: String) {
    val touch = BuildConfig.TOUCH_CLIENT
    Text(
        label.uppercase(),
        color = Color.White.copy(alpha = 0.42f),
        fontSize = if (touch) 12.sp else 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (touch) 10.dp else 8.dp, bottom = if (touch) 4.dp else 2.dp, start = 4.dp),
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
    )
}

@Composable
private fun HudListRow(
    label: String,
    checked: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    checkable: Boolean = true,
    enabled: Boolean = true,
) {
    val touch = BuildConfig.TOUCH_CLIENT
    val fg = when {
        !enabled -> Color.White.copy(alpha = 0.28f)
        highlighted || checked -> Color.White
        else -> Color.White.copy(alpha = 0.55f)
    }
    val bg = when {
        !enabled -> Color.Transparent
        checked && touch -> Color.White.copy(alpha = 0.14f)
        highlighted -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    CoogClickable(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(if (touch) 10.dp else 6.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth().height(if (touch) 48.dp else 28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (touch) 10.dp else 4.dp)
                .focusProperties { canFocus = false },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(if (touch) 20.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (checkable && checked && enabled) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (touch) 18.dp else 13.dp),
                    )
                }
            }
            Text(
                label,
                color = fg,
                fontSize = if (touch) 16.sp else 12.sp,
                fontWeight = if (highlighted || checked) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

private fun formatDelay(ms: Int): String {
    val sign = if (ms > 0) "+" else ""
    return "Sync $sign${ms} ms"
}

/** Stream index from `emb:<index>:<absPath>`. */
private fun parseEmbeddedStreamIndex(id: String): Int {
    if (!id.startsWith("emb:")) return -1
    val rest = id.removePrefix("emb:")
    val cut = rest.indexOf(':')
    if (cut <= 0) return -1
    return rest.substring(0, cut).toIntOrNull() ?: -1
}

private fun trackDisplayLabel(track: PlayerTrack): String {
    val lang = track.language.trim().uppercase()
    val base = when {
        lang.isNotBlank() && lang != "UND" -> lang
        else -> track.label.take(28)
    }
    val name = track.label.take(24).takeIf { it.isNotBlank() && !it.equals(base, ignoreCase = true) }
    return if (name != null) "$base · $name" else base
}

private fun remoteDisplayLabel(track: SubtitleTrack): String {
    // Server already formats in-file rows like mpv ("EL · PGS", "#8 · SRT").
    if (track.source == "embedded" || track.source == "sidecar") {
        return track.label.ifBlank {
            val lang = track.language.trim().uppercase().ifBlank { "SUB" }
            listOfNotNull(lang, track.codec.uppercase().ifBlank { null }).joinToString(" · ")
        }
    }
    val lang = track.language.trim().uppercase().ifBlank { "SUB" }
    val se = if (track.season > 0 && track.episode > 0) {
        "S%02dE%02d".format(track.season, track.episode)
    } else {
        ""
    }
    val release = track.release.ifBlank {
        track.label
            .substringAfter(" · ", "")
            .substringBefore(" · OpenSubtitles")
            .trim()
    }.let { raw ->
        var text = raw
        if (se.isNotBlank()) text = text.replace(se, "", ignoreCase = true)
        text.trim(' ', '·', '-', '.')
    }.take(18)
    val bits = buildList {
        add(lang)
        if (se.isNotBlank()) add(se)
        when {
            release.isNotBlank() -> add(release)
            track.movieHashMatch -> add("hash match")
            else -> add("OpenSubtitles")
        }
    }
    return bits.joinToString(" · ")
}
