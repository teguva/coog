package tv.coog.app.player

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.Executors

data class PlayerTrack(
    val id: String,
    val label: String,
    val selected: Boolean,
    val type: Int,
    val groupIndex: Int,
    val indexInGroup: Int,
    val language: String = "",
    val mimeType: String = "",
    /** Matroska/ffprobe stream index when known (Format.id); -1 if unknown. */
    val containerIndex: Int = -1,
    val supported: Boolean = true,
) {
    /** True for SubRip/SSA/VTT/etc. — false for PGS/VobSub bitmaps. */
    fun isTextBasedSubtitle(): Boolean {
        if (type != C.TRACK_TYPE_TEXT) return false
        val raw = mimeType.lowercase(Locale.US)
        if (raw.isBlank()) return true
        if (raw.contains("pgs") || raw.contains("vobsub") || raw.contains("dvd_sub") ||
            raw.contains("dvb") || raw.contains("xsub") || raw.contains("image")
        ) {
            return false
        }
        return raw.contains("subrip") || raw.contains("ssa") || raw.contains("ass") ||
            raw.contains("vtt") || raw.contains("ttml") || raw.contains("text") ||
            raw.contains("cea") || raw.startsWith("application/")
    }
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    @OptIn(UnstableApi::class)
    val player: ExoPlayer = buildTvExoPlayer(app)
    private var preparedUrl: String? = null
    /** Language tags for unlabeled in-file SubRip, inferred server-side from PGS order. */
    private var embeddedLanguageHints: List<String> = emptyList()
    private var preparedMediaKey: String = ""
    private var lastToken: String = ""
    private var lastAdultSession: String = ""
    private var lastContentType: String = ""
    private var lastAudioUrl: String = ""
    private var lastAudioContentType: String = ""
    private var mkvCueSeekDisabled: Boolean = false
    private var externalSubUrl: String? = null
    private var externalSubMime: String? = null
    private var externalSubLang: String? = null
    /** Sideloaded SRT/VTT rendered without re-preparing ExoPlayer (avoids video hitch). */
    private var overlayCues: List<SubCue> = emptyList()
    private var overlayLocalCues: List<SubCue> = emptyList()
    private var overlaySameLanguage: Boolean = false
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val syncExecutor = Executors.newSingleThreadExecutor()
    /** Identity of the overlay list currently shown — stale autosync results must not apply. */
    @Volatile private var overlayGeneration: Int = 0
    private val overlayTick = object : Runnable {
        override fun run() {
            if (overlayCues.isEmpty()) return
            refreshDisplayedCues()
            overlayHandler.postDelayed(this, 80L)
        }
    }
    private var resumeAtMs: Long = 0L
    private var resumeApplied: Boolean = false
    private var pendingSeekMs: Long = -1L
    private var lastGoodPositionMs: Long = 0L
    private var oomRecovered: Boolean = false
    /** Final job size from session/meta; when known, hybrid Range can serve MKV cues. */
    private var progressiveFinalBytes: Long = 0L
    @Volatile var suppressEnded: Boolean = false

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    val audioTracks: StateFlow<List<PlayerTrack>> = _audioTracks.asStateFlow()

    private val _textTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    val textTracks: StateFlow<List<PlayerTrack>> = _textTracks.asStateFlow()

    private val _textOff = MutableStateFlow(true)
    val textOff: StateFlow<Boolean> = _textOff.asStateFlow()

    private val _firstFrame = MutableStateFlow(false)
    val firstFrame: StateFlow<Boolean> = _firstFrame.asStateFlow()

    private val _buffering = MutableStateFlow(true)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

    private val _cueLines = MutableStateFlow<List<String>>(emptyList())
    val cueLines: StateFlow<List<String>> = _cueLines.asStateFlow()

    private val _externalOverlay = MutableStateFlow(false)
    val externalOverlay: StateFlow<Boolean> = _externalOverlay.asStateFlow()

    /** Bumped on every overlay clear/replace so SubtitleView refreshes even when cue text matches. */
    private val _overlayRevision = MutableStateFlow(0)
    val overlayRevision: StateFlow<Int> = _overlayRevision.asStateFlow()

    private val _subtitleDelayMs = MutableStateFlow(0)
    val subtitleDelayMs: StateFlow<Int> = _subtitleDelayMs.asStateFlow()

    private val _subtitleSize = MutableStateFlow(1)
    val subtitleSize: StateFlow<Int> = _subtitleSize.asStateFlow()

    private val cueHistory = ArrayDeque<Pair<Long, List<String>>>(128)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _buffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_READY) {
                pendingSeekMs = -1L
                val pos = player.currentPosition
                if (pos > lastGoodPositionMs) {
                    lastGoodPositionMs = pos
                }
            }
            if (state == Player.STATE_READY) {
                maybeApplyResume()
            }
            if (state == Player.STATE_ENDED) {
                if (suppressEnded) {
                    // Overseek into unfinished progressive media ends the period; never snap to 0
                    // when we still have a known-good position from before the scrub.
                    val fromBuf = player.bufferedPosition
                    val target = when {
                        fromBuf > 2_000L -> (fromBuf - 1_500L).coerceAtLeast(0L)
                        lastGoodPositionMs > 2_000L -> (lastGoodPositionMs - 2_000L).coerceAtLeast(0L)
                        pendingSeekMs > 2_000L -> (pendingSeekMs - 2_000L).coerceAtLeast(0L)
                        else -> 0L
                    }
                    player.seekTo(target)
                    player.play()
                } else {
                    _ended.value = true
                }
                return
            }
            if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                _ended.value = false
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            maybeApplyResume()
        }

        override fun onRenderedFirstFrame() {
            _firstFrame.value = true
            _buffering.value = false
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = preparedUrl
            // Incomplete MKVs keep cues at EOF. A seek trips HTTP 416, and a bare
            // re-prepare used to restart at 0 — restore the scrub target instead.
            if (url != null && isUnreachableMkvCues(error)) {
                val fallback = when {
                    lastGoodPositionMs > 0L -> lastGoodPositionMs
                    pendingSeekMs >= 0L -> pendingSeekMs
                    player.currentPosition > 0L -> player.currentPosition
                    else -> resumeAtMs
                }.coerceAtLeast(0L)
                resumeAtMs = fallback
                resumeApplied = fallback <= 0L
                pendingSeekMs = -1L
                _error.value = null
                prepare(
                    url,
                    lastToken,
                    lastAdultSession,
                    disableMkvCueSeek = true,
                    keepPicture = true,
                    contentType = lastContentType,
                    audioUrl = lastAudioUrl,
                    audioContentType = lastAudioContentType,
                )
                return
            }
            // Progressive HLS can OOM the TV Java heap while buffering remux segments.
            // Drop back to a known-good position once; LoadControl already caps buffer size.
            if (!oomRecovered && url != null && isOutOfMemory(error)) {
                oomRecovered = true
                val fallback = (player.currentPosition - 3_000L).coerceAtLeast(0L)
                resumeAtMs = fallback
                resumeApplied = false
                System.gc()
                prepare(
                    url,
                    lastToken,
                    lastAdultSession,
                    disableMkvCueSeek = mkvCueSeekDisabled,
                    keepPicture = true,
                    contentType = lastContentType,
                    audioUrl = lastAudioUrl,
                    audioContentType = lastAudioContentType,
                )
                return
            }
            _error.value = describePlaybackError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks(tracks)
            ensureTextTrackSelected()
        }

        override fun onCues(cueGroup: CueGroup) {
            // Overlay owns the paint path; still sample embedded cues for autosync.
            val lines = cueGroup.cues.mapNotNull { cueText(it) }.filter { it.isNotBlank() }
            val at = player.currentPosition
            if (lines.isNotEmpty() && !_externalOverlay.value) {
                if (cueHistory.size >= 128) cueHistory.removeFirst()
                cueHistory.addLast(at to lines)
            }
            if (!_externalOverlay.value) {
                refreshDisplayedCues()
            }
        }
    }

    init {
        player.addListener(listener)
    }

    /** Seek to resumeAtMs once the timeline is long enough (remux/HLS grows after handoff). */
    private fun maybeApplyResume() {
        if (resumeApplied || resumeAtMs <= 0L) return
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) {
            return
        }
        val dur = player.duration
        // Fresh remux playlists are short at first — wait instead of clamping to ~0.
        if (dur > 0L && resumeAtMs > dur + 2_000L) {
            return
        }
        val target = if (dur > 0L) {
            resumeAtMs.coerceIn(0L, (dur - 1_000L).coerceAtLeast(0L))
        } else {
            resumeAtMs
        }
        if (target > 1_000L) {
            player.seekTo(target)
        }
        resumeApplied = true
    }

    fun play(
        url: String,
        token: String,
        startPositionMs: Long = 0L,
        adultSession: String = "",
        mediaKey: String = "",
        seamless: Boolean = false,
        contentType: String = "",
        audioUrl: String = "",
        audioContentType: String = "",
    ) {
        if (seamless && preparedUrl != null && (preparedUrl != url || lastAudioUrl != audioUrl)) {
            // Prefer the captured handoff position — currentPosition can already be 0 after EOF/404.
            resumeAtMs = maxOf(startPositionMs, player.currentPosition).coerceAtLeast(0L)
            resumeApplied = resumeAtMs <= 0L
            preparedMediaKey = mediaKey
            oomRecovered = false
            prepare(
                url,
                token,
                adultSession,
                disableMkvCueSeek = false,
                keepPicture = true,
                contentType = contentType,
                audioUrl = audioUrl,
                audioContentType = audioContentType,
            )
            return
        }
        resumeAtMs = startPositionMs.coerceAtLeast(0L)
        resumeApplied = resumeAtMs <= 0L
        if (
            preparedUrl == url &&
            lastAudioUrl == audioUrl &&
            preparedMediaKey == mediaKey &&
            player.mediaItemCount > 0 &&
            _error.value == null &&
            externalSubUrl == null
        ) {
            player.playWhenReady = true
            if (!resumeApplied && resumeAtMs > 0) {
                player.seekTo(resumeAtMs)
                resumeApplied = true
            }
            return
        }
        preparedMediaKey = mediaKey
        oomRecovered = false
        prepare(
            url,
            token,
            adultSession,
            disableMkvCueSeek = false,
            contentType = contentType,
            audioUrl = audioUrl,
            audioContentType = audioContentType,
        )
    }

    /**
     * Drop currently painted overlay cues while a new external track is fetched.
     * Keeps embedded text muted so the previous track cannot linger on SubtitleView.
     */
    fun prepareExternalSubtitleSwitch() {
        stopOverlayTicker()
        overlayGeneration += 1
        overlayCues = emptyList()
        overlayLocalCues = emptyList()
        overlaySameLanguage = false
        externalSubUrl = null
        externalSubLang = null
        externalSubMime = null
        _subtitleDelayMs.value = 0
        _externalOverlay.value = true
        _textOff.value = false
        // Distinct instance so StateFlow always emits and SubtitleView clears.
        _cueLines.value = emptyList()
        _overlayRevision.value = _overlayRevision.value + 1
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /**
     * Apply a pre-fetched external subtitle without tearing down video.
     * @param body SRT/VTT text already downloaded on a background thread
     * @param localBody optional sidecar SRT used only when [autoSync] is on
     */
    fun setExternalSubtitleOverlay(
        body: String,
        language: String = "",
        localBody: String? = null,
        localLanguage: String = "",
        url: String = "",
        autoSync: Boolean = false,
    ) {
        val parsed = parseSubtitleText(body)
        stopOverlayTicker()
        overlayGeneration += 1
        val generation = overlayGeneration
        if (parsed.isEmpty()) {
            // Failed parse must still leave the previous track — clear instead of no-op.
            overlayCues = emptyList()
            overlayLocalCues = emptyList()
            overlaySameLanguage = false
            externalSubUrl = null
            externalSubLang = null
            _cueLines.value = emptyList()
            _overlayRevision.value = _overlayRevision.value + 1
            return
        }
        val localParsed = localBody?.let { parseSubtitleText(it) }.orEmpty()
        val sameLang = language.isNotBlank() && localLanguage.isNotBlank() &&
            language.equals(localLanguage, ignoreCase = true)
        overlayCues = parsed
        overlayLocalCues = localParsed
        overlaySameLanguage = sameLang
        externalSubUrl = url.ifBlank { "overlay" }
        externalSubLang = language
        externalSubMime = MimeTypes.APPLICATION_SUBRIP
        _subtitleDelayMs.value = 0
        _externalOverlay.value = true
        _textOff.value = false
        // Mute in-file text so PGS/forced don't fight the overlay.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        // Paint immediately with delay 0; autosync may refine on a background thread.
        _overlayRevision.value = _overlayRevision.value + 1
        refreshDisplayedCues()
        overlayHandler.post(overlayTick)
        if (autoSync && localParsed.size >= 6) {
            scheduleAutosync(generation, parsed, localParsed, sameLang)
        }
    }

    /** Attach / replace the timing-reference cues used by Autosync (does not repaint overlay). */
    fun setAutosyncReference(body: String, language: String = "") {
        val parsed = parseSubtitleText(body)
        if (parsed.size < 6) return
        overlayLocalCues = parsed
        val extLang = externalSubLang.orEmpty()
        overlaySameLanguage = extLang.isNotBlank() && language.isNotBlank() &&
            extLang.equals(language, ignoreCase = true)
    }

    /** Recompute delay from the cached local sidecar, or clear delay when turning autosync off. */
    fun setAutosyncEnabled(enabled: Boolean) {
        if (!enabled) {
            if (_subtitleDelayMs.value != 0) {
                _subtitleDelayMs.value = 0
                _overlayRevision.value = _overlayRevision.value + 1
                refreshDisplayedCues()
            }
            return
        }
        val generation = overlayGeneration
        val ext = overlayCues
        val local = overlayLocalCues
        val same = overlaySameLanguage
        if (ext.size < 6 || local.size < 6) return
        scheduleAutosync(generation, ext, local, same)
    }

    private fun scheduleAutosync(
        generation: Int,
        external: List<SubCue>,
        local: List<SubCue>,
        sameLanguage: Boolean,
    ) {
        syncExecutor.execute {
            val result = estimateSubtitleDelayMs(
                local = local,
                external = external,
                sameLanguage = sameLanguage,
            )
            overlayHandler.post {
                if (generation != overlayGeneration) return@post
                if (overlayCues !== external) return@post
                val delay = if (result.confidence >= 0.40f) result.delayMs else 0
                // Always write so the Subtitles menu "Sync N ms" row refreshes after Autosync.
                _subtitleDelayMs.value = delay
                _overlayRevision.value = _overlayRevision.value + 1
                refreshDisplayedCues()
            }
        }
    }

    fun clearExternalSubtitle() {
        val hadOverlay = _externalOverlay.value || overlayCues.isNotEmpty()
        stopOverlayTicker()
        overlayCues = emptyList()
        overlayLocalCues = emptyList()
        overlaySameLanguage = false
        _externalOverlay.value = false
        externalSubUrl = null
        externalSubLang = null
        externalSubMime = null
        _cueLines.value = emptyList()
        _overlayRevision.value = _overlayRevision.value + 1
        if (hadOverlay) {
            setTextOff()
            return
        }
        // Legacy path: external was merged into MediaItem (should be rare now).
        if (preparedUrl == null) {
            setTextOff()
            return
        }
        setTextOff()
    }

    fun nudgeSubtitleDelay(deltaMs: Int) {
        // Match estimateSubtitleDelayMs (±30s) so fine-tune works after large autosync offsets.
        _subtitleDelayMs.value = (_subtitleDelayMs.value + deltaMs).coerceIn(-30_000, 30_000)
        refreshDisplayedCues()
    }

    fun cycleSubtitleSize() {
        _subtitleSize.value = (_subtitleSize.value + 1) % 3
    }

    private fun stopOverlayTicker() {
        overlayHandler.removeCallbacks(overlayTick)
    }

    private fun refreshDisplayedCues() {
        if (overlayCues.isNotEmpty()) {
            _cueLines.value = activeOverlayLines(
                overlayCues,
                player.currentPosition,
                _subtitleDelayMs.value,
            )
            return
        }
        val delay = _subtitleDelayMs.value.toLong()
        if (delay == 0L) {
            _cueLines.value = cueHistory.lastOrNull()?.second.orEmpty()
            return
        }
        val target = player.currentPosition - delay
        val match = cueHistory.lastOrNull { it.first <= target } ?: cueHistory.lastOrNull()
        _cueLines.value = match?.second.orEmpty()
    }

    private fun prepare(
        url: String,
        token: String,
        adultSession: String,
        disableMkvCueSeek: Boolean,
        keepPicture: Boolean = false,
        contentType: String = "",
        audioUrl: String = "",
        audioContentType: String = "",
    ) {
        _error.value = null
        if (!keepPicture) {
            _firstFrame.value = false
        }
        _buffering.value = true
        _ended.value = false
        _cueLines.value = emptyList()
        cueHistory.clear()
        lastToken = token
        lastAdultSession = adultSession
        if (contentType.isNotBlank()) {
            lastContentType = contentType
        }
        lastAudioUrl = audioUrl
        lastAudioContentType = audioContentType
        // Growing job MKVs keep cues at EOF. With known final size the server fills
        // missing Ranges from RD, so cue seeks work. Without a total, disable cues
        // to avoid 416 → restart-at-0 on incomplete local-only files.
        val progressiveJob = isProgressiveJobFileUrl(url)
        val skipMkvCues = disableMkvCueSeek ||
            (progressiveJob && progressiveFinalBytes <= 0L)
        mkvCueSeekDisabled = skipMkvCues
        val http = DefaultHttpDataSource.Factory()
        val headers = mutableMapOf<String, String>()
        if (token.isNotBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        if (adultSession.isNotBlank()) {
            headers["X-Coog-Adult-Session"] = adultSession
        }
        if (headers.isNotEmpty()) {
            http.setDefaultRequestProperties(headers)
        }
        val extractors = DefaultExtractorsFactory().also { factory ->
            // Blu-ray rips often ship 20+ PGS tracks. Media3's default "parse during
            // extraction" transcodes every PGS packet on every track → stalls and blank
            // bitmaps (androidx/media#2667/#2920). Emit raw samples; TextRenderer
            // (legacy) decodes only the selected track.
            factory.setTextTrackTranscodingEnabled(false)
            if (skipMkvCues) {
                factory.setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            }
        }
        val builder = MediaItem.Builder().setUri(url)
        // Extension-less /jobs/.../file and /media/.../stream URLs need an explicit MIME
        // so MKV/MP4 extractors bind (browsers use the HTTP Content-Type instead).
        val resolvedType = contentType.ifBlank { lastContentType }
        (mimeFromContentType(resolvedType) ?: mimeFromPlaybackUrl(url))?.let { builder.setMimeType(it) }
        // Overlay external subs are painted from parsed cues — never merge into MediaItem
        // (that used to force a full re-prepare and stall video on OpenSubtitles download).
        val textParams = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, _externalOverlay.value || _textOff.value)
            .setIgnoredTextSelectionFlags(0)
        player.trackSelectionParameters = textParams.build()

        val factory = DefaultMediaSourceFactory(http, extractors)
            .experimentalParseSubtitlesDuringExtraction(false)
        val videoSource = factory.createMediaSource(builder.build())
        val source = if (audioUrl.isNotBlank()) {
            // FilteringMediaSource keeps ONLY the listed types (whitelist). Keep video/text
            // from the original; AAC comes from the sidecar (Jellyfin Direct Stream).
            val videoOnly = FilteringMediaSource(
                videoSource,
                setOf(
                    C.TRACK_TYPE_VIDEO,
                    C.TRACK_TYPE_TEXT,
                    C.TRACK_TYPE_METADATA,
                    C.TRACK_TYPE_IMAGE,
                ),
            )
            val audioItem = MediaItem.Builder()
                .setUri(audioUrl)
                .setMimeType(
                    mimeFromContentType(audioContentType)
                        ?: MimeTypes.APPLICATION_MATROSKA,
                )
                .build()
            MergingMediaSource(videoOnly, factory.createMediaSource(audioItem))
        } else {
            videoSource
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        preparedUrl = url
        if (_externalOverlay.value) {
            _textOff.value = false
            if (overlayCues.isNotEmpty()) {
                overlayHandler.removeCallbacks(overlayTick)
                overlayHandler.post(overlayTick)
            }
        } else {
            _cueLines.value = emptyList()
        }
    }

    /** After tracks appear, pin the OpenSubtitles/sidecar text track if nothing is selected. */
    private fun preferExternalTextTrack(language: String) {
        val lang = language.trim().lowercase(Locale.US)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .setSelectUndeterminedTextLanguage(true)
            .apply {
                if (lang.isNotBlank()) setPreferredTextLanguage(lang)
            }
            .build()
        _textOff.value = false
    }

    private fun ensureTextTrackSelected() {
        if (_externalOverlay.value) return
        if (externalSubUrl == null) return
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            preferExternalTextTrack(externalSubLang.orEmpty())
        }
        val text = collect(player.currentTracks, C.TRACK_TYPE_TEXT)
        if (text.any { it.selected }) {
            _textOff.value = false
            return
        }
        val prefer = externalSubLang?.trim()?.lowercase(Locale.US).orEmpty()
        val pick = text.firstOrNull {
            prefer.isNotBlank() && (it.language.equals(prefer, true) || it.language.startsWith(prefer))
        } ?: text.firstOrNull { it.isTextBasedSubtitle() } ?: text.firstOrNull()
        if (pick != null) {
            selectTrack(pick, clearExternal = false)
        }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun resetOpening() {
        _error.value = null
        _firstFrame.value = false
        _buffering.value = true
        _ended.value = false
        stopOverlayTicker()
        overlayCues = emptyList()
        overlayLocalCues = emptyList()
        overlaySameLanguage = false
        _externalOverlay.value = false
        externalSubUrl = null
        externalSubLang = null
        externalSubMime = null
        _cueLines.value = emptyList()
        cueHistory.clear()
        _subtitleDelayMs.value = 0
        resumeAtMs = 0
        resumeApplied = true
        lastGoodPositionMs = 0L
        pendingSeekMs = -1L
        progressiveFinalBytes = 0L
        oomRecovered = false
    }

    /** Final download size for progressive /file cue seeks (hybrid RD Range fill). */
    fun setProgressiveFinalBytes(totalBytes: Long) {
        if (totalBytes > 0L) {
            progressiveFinalBytes = totalBytes
        }
    }

    /** Surfaces a stuck open (e.g. progressive 4K remux that never paints a frame). */
    fun markOpenTimeout(message: String) {
        if (_firstFrame.value || _error.value != null) return
        _error.value = message
        _buffering.value = false
    }

    fun seekBy(deltaMs: Long, maxMs: Long) {
        val target = player.currentPosition + deltaMs
        seekTo(target, maxMs)
    }

    fun seekTo(positionMs: Long, maxMs: Long = 0L) {
        val upper = if (maxMs > 0) maxMs else Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, upper)
        if (player.currentPosition > lastGoodPositionMs) {
            lastGoodPositionMs = player.currentPosition
        }
        pendingSeekMs = target
        player.seekTo(target)
        refreshDisplayedCues()
    }

    fun selectTrack(track: PlayerTrack, clearExternal: Boolean = true) {
        if (clearExternal && track.type == C.TRACK_TYPE_TEXT) {
            stopOverlayTicker()
            overlayCues = emptyList()
            overlayLocalCues = emptyList()
            overlaySameLanguage = false
            _externalOverlay.value = false
            externalSubUrl = null
            externalSubLang = null
            externalSubMime = null
            _subtitleDelayMs.value = 0
        }
        // Exact override only — do not remap PGS→softsub by (often wrong) language tags.
        val groups = player.currentTracks.groups
        if (track.groupIndex !in groups.indices) return
        val group = groups[track.groupIndex]
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(track.type, false)
            .apply {
                if (track.type == C.TRACK_TYPE_TEXT) {
                    clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    // Clear language prefs so Exo cannot jump to a different text track.
                    setPreferredTextLanguages()
                    setSelectUndeterminedTextLanguage(true)
                }
            }
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.indexInGroup))
            .build()
        if (track.type == C.TRACK_TYPE_TEXT) {
            _textOff.value = false
        }
    }

    fun setTextOff() {
        stopOverlayTicker()
        overlayCues = emptyList()
        overlayLocalCues = emptyList()
        overlaySameLanguage = false
        _externalOverlay.value = false
        externalSubUrl = null
        externalSubLang = null
        externalSubMime = null
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _textOff.value = true
        _cueLines.value = emptyList()
    }

    private fun refreshTracks(tracks: Tracks) {
        _audioTracks.value = collect(tracks, C.TRACK_TYPE_AUDIO)
        // No PGS→SRT language invention or auto-remap — container tags often lie.
        val text = collect(tracks, C.TRACK_TYPE_TEXT)
        _textTracks.value = text
        if (_externalOverlay.value) {
            _textOff.value = false
            return
        }
        _textOff.value = text.none { it.selected } ||
            player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    }

    private fun collect(tracks: Tracks, type: Int): List<PlayerTrack> {
        val out = mutableListOf<PlayerTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                val supported = group.isTrackSupported(i)
                // Keep every text stream (incl. unsupported PGS) so ordinal/index
                // mapping to ffprobe sid stays aligned.
                if (!supported && type != C.TRACK_TYPE_TEXT) continue
                val format = group.getTrackFormat(i)
                out.add(
                    PlayerTrack(
                        id = "$type-$groupIndex-$i",
                        label = trackLabel(format, type, out.size),
                        selected = group.isTrackSelected(i),
                        type = type,
                        groupIndex = groupIndex,
                        indexInGroup = i,
                        language = normalizeLanguageTag(format.language),
                        mimeType = format.sampleMimeType.orEmpty(),
                        containerIndex = format.id?.trim()?.toIntOrNull() ?: -1,
                        supported = supported,
                    ),
                )
            }
        }
        return out
    }

    fun setEmbeddedLanguageHints(langs: List<String>) {
        // Hints disabled — inventing langs from PGS order remapped selection to the wrong track.
        if (langs.isEmpty() && embeddedLanguageHints.isEmpty()) return
        embeddedLanguageHints = emptyList()
        if (player.currentTracks.groups.isNotEmpty()) {
            refreshTracks(player.currentTracks)
        }
    }

    private fun languagesMatch(a: String, b: String): Boolean {
        val x = normalizeLanguageTag(a).lowercase(Locale.US)
        val y = normalizeLanguageTag(b).lowercase(Locale.US)
        if (x.isBlank() || y.isBlank() || x == "und" || y == "und") return false
        return x == y || x.startsWith(y) || y.startsWith(x)
    }

    private fun trackLabel(format: Format, type: Int, index: Int): String {
        val parts = mutableListOf<String>()
        val lang = normalizeLanguageTag(format.language)
        if (lang.isNotBlank() && !lang.equals("und", ignoreCase = true)) {
            val display = runCatching { Locale.forLanguageTag(lang).displayLanguage }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }
                ?: lang.uppercase(Locale.US)
            parts.add(display)
        }
        format.label?.trim()?.takeIf { it.isNotBlank() }?.let { named ->
            if (parts.none { it.equals(named, ignoreCase = true) }) {
                parts.add(named)
            }
        }
        if (type == C.TRACK_TYPE_AUDIO) {
            audioCodecLabel(format)?.let { parts.add(it) }
            when (format.channelCount) {
                1 -> parts.add("Mono")
                2 -> parts.add("Stereo")
                in 6..8 -> parts.add("${format.channelCount}.1")
                in 3..Int.MAX_VALUE -> parts.add("${format.channelCount} ch")
            }
        }
        if (type == C.TRACK_TYPE_TEXT) {
            textCodecLabel(format)?.let { parts.add(it) }
        }
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return if (type == C.TRACK_TYPE_TEXT) "Subtitles ${index + 1}" else "Audio ${index + 1}"
    }

    private fun textCodecLabel(format: Format): String? {
        val raw = (format.sampleMimeType ?: format.codecs ?: "").lowercase(Locale.US)
        return when {
            raw.contains("pgs") -> "PGS"
            raw.contains("vobsub") || raw.contains("dvd_sub") -> "VobSub"
            raw.contains("dvb") -> "DVB"
            raw.contains("subrip") || raw.contains("srt") -> "SRT"
            raw.contains("ssa") || raw.contains("ass") -> "ASS"
            raw.contains("vtt") -> "VTT"
            raw.contains("ttml") -> "TTML"
            else -> null
        }
    }

    private fun audioCodecLabel(format: Format): String? {
        val raw = (format.codecs ?: format.sampleMimeType ?: "").lowercase(Locale.US)
        return when {
            raw.contains("truehd") || raw.contains("mlp") -> "TrueHD"
            raw.contains("eac3") || raw.contains("ec-3") -> "E-AC3"
            raw.contains("ac-3") || raw.contains("ac3") -> "AC3"
            raw.contains("dts") -> "DTS"
            raw.contains("opus") -> "Opus"
            raw.contains("flac") -> "FLAC"
            raw.contains("mp4a") || raw.contains("aac") -> "AAC"
            raw.contains("mp3") || raw.contains("mpeg") -> "MP3"
            else -> null
        }
    }

    override fun onCleared() {
        stopOverlayTicker()
        syncExecutor.shutdownNow()
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }
}

private fun cueText(cue: Cue): String? {
    val text = cue.text?.toString()?.trim().orEmpty()
    return text.ifBlank { null }
}

/**
 * Google TV / Chromecast often run with a ~192MB Java heap. ExoPlayer's default
 * LoadControl will happily buffer tens of seconds of high-bitrate remux into
 * on-heap byte[], which OOMs mid-progressive play. Cap both duration and total bytes.
 *
 * Full-rate remux progressive (~50–80 Mbps) needs a larger byte target or the
 * player starves and looks like low framerate — the LAN/server are not the limit.
 */
@OptIn(UnstableApi::class)
internal fun buildTvExoPlayer(app: Application): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setAllocator(DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
        .setBufferDurationsMs(
            /* minBufferMs= */ 10_000,
            /* maxBufferMs= */ 18_000,
            /* bufferForPlaybackMs= */ 2_000,
            /* bufferForPlaybackAfterRebufferMs= */ 3_500,
        )
        // ~8–10s at 60Mbps; keep under typical TV heap headroom.
        .setTargetBufferBytes(64 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(/* backBufferDurationMs= */ 3_000, /* retainBackBufferFromKeyframe= */ false)
        .build()
    // Boxes with no Android HDR types often mis-map DV Profile 8 codecs; use the
    // HEVC base layer. Real HDR/DV displays (TCL) keep the DV decoder.
    val preferHevcWhenNoHdr = !CoogRenderersFactory.displayAdvertisesHdr(app)
    val preferHevcForDolbyVision = MediaCodecSelector { mimeType, secure, tunneling ->
        if (
            preferHevcWhenNoHdr &&
            mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)
        ) {
            MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265, secure, tunneling)
        } else {
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        }
    }
    val renderers = CoogRenderersFactory(app)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(preferHevcForDolbyVision)
        .forceEnableMediaCodecAsynchronousQueueing()
    val trackSelector = DefaultTrackSelector(app).apply {
        parameters = buildUponParameters()
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264)
            // Verity-style remuxes list fre E-AC3 before eng; prefer English.
            .setPreferredAudioLanguages("en", "eng")
            // Off until auto-load / user picks — Blu-ray default PGS must not win.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTunnelingEnabled(false)
            .build()
    }
    return ExoPlayer.Builder(app, renderers)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .build()
}

/** Smaller buffer for muted home trailers — they share the process with the browse UI. */
@OptIn(UnstableApi::class)
internal fun buildTrailerExoPlayer(app: Application): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setAllocator(DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
        .setBufferDurationsMs(5_000, 10_000, 750, 1_500)
        .setTargetBufferBytes(8 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(1_000, false)
        .build()
    return ExoPlayer.Builder(app)
        .setLoadControl(loadControl)
        .build()
}

private fun mimeFor(url: String?): String {
    val path = url?.substringBefore('?')?.lowercase().orEmpty()
    return when {
        path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/** Matroska often stores ISO 639-2/B (`fre`); Locale/BCP-47 wants `fr`. */
private fun normalizeLanguageTag(raw: String?): String {
    val lang = raw?.trim().orEmpty()
    if (lang.isBlank() || lang.equals("und", ignoreCase = true)) return lang
    val low = lang.lowercase(Locale.US)
    return when (low) {
        "fre", "fra" -> "fr"
        "eng" -> "en"
        "ger", "deu" -> "de"
        "spa", "esl" -> "es"
        "ita" -> "it"
        "por" -> "pt"
        "dut", "nld" -> "nl"
        "rus" -> "ru"
        "jpn" -> "ja"
        "chi", "zho" -> "zh"
        "kor" -> "ko"
        "pol" -> "pl"
        "cze", "ces" -> "cs"
        "gre", "ell" -> "el"
        "heb" -> "he"
        "hun" -> "hu"
        "vie" -> "vi"
        "rum", "ron" -> "ro"
        "slo", "slk" -> "sk"
        "slv" -> "sl"
        "may", "msa" -> "ms"
        "alb", "sqi" -> "sq"
        "arm", "hye" -> "hy"
        "baq", "eus" -> "eu"
        "bur", "mya" -> "my"
        "geo", "kat" -> "ka"
        "ice", "isl" -> "is"
        "mac", "mkd" -> "mk"
        "mao", "mri" -> "mi"
        "per", "fas" -> "fa"
        "tib", "bod" -> "bo"
        "wel", "cym" -> "cy"
        else -> low.substringBefore('-').substringBefore('_')
    }
}

private fun mimeFromContentType(raw: String): String? {
    val ctype = raw.substringBefore(';').trim().lowercase(Locale.US)
    if (ctype.isBlank()) return null
    // RD/CDN often labels originals as force-download / octet-stream — that MIME
    // makes ExoPlayer skip Matroska and never paint a frame on /jobs/{id}/file.
    if (
        ctype == "application/octet-stream" ||
        ctype == "application/force-download" ||
        ctype == "binary/octet-stream" ||
        ctype == "application/download"
    ) {
        return null
    }
    return when {
        ctype.startsWith("audio/") -> ctype
        ctype.contains("matroska") || ctype.contains("x-mkv") || ctype == "video/mkv" ->
            MimeTypes.VIDEO_MATROSKA
        ctype.contains("mp4") || ctype.contains("m4v") || ctype.contains("quicktime") ->
            MimeTypes.VIDEO_MP4
        ctype.contains("webm") -> MimeTypes.VIDEO_WEBM
        ctype.contains("mpegts") || ctype.contains("mp2t") || ctype == "video/mp2t" ->
            MimeTypes.VIDEO_MP2T
        ctype.startsWith("video/") -> ctype
        else -> null
    }
}

/** Best-effort MIME when the session forgot contentType on an extension-less stream URL. */
private fun mimeFromPlaybackUrl(url: String): String? {
    val path = url.substringBefore('?').lowercase(Locale.US)
    return when {
        // Extension first — /progressive/audio.mka must not be treated as HLS.
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mka") -> MimeTypes.APPLICATION_MATROSKA
        path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        path.endsWith(".ts") || path.endsWith(".m2ts") -> MimeTypes.VIDEO_MP2T
        path.contains("/remux/") || path.contains("/progressive/") -> MimeTypes.APPLICATION_M3U8
        // Extension-less library streams need session contentType; guessing MP4 breaks MKV+subs.
        path.contains("/api/v1/media/") && path.endsWith("/stream") -> null
        path.contains("/api/v1/jobs/") && path.endsWith("/file") -> MimeTypes.VIDEO_MATROSKA
        else -> null
    }
}

internal fun isOutOfMemory(error: PlaybackException): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is OutOfMemoryError ||
            current.message?.contains("OutOfMemory", ignoreCase = true) == true
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

internal fun isUnreachableMkvCues(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
        return true
    }
    var current: Throwable? = error
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException && current.responseCode == 416) {
            return true
        }
        current = current.cause
    }
    return false
}

/** Progressive download URL: `/api/v1/jobs/{id}/file` (cues not written yet). */
internal fun isProgressiveJobFileUrl(url: String): Boolean {
    val path = url.substringBefore('?').lowercase()
    return path.contains("/api/v1/jobs/") && path.endsWith("/file")
}

internal fun describePlaybackError(error: PlaybackException): String {
    val parts = mutableListOf<String>()
    parts.add(humanErrorCode(error.errorCode, error.errorCodeName))
    var current: Throwable? = error
    val seen = LinkedHashSet<String>()
    while (current != null) {
        when (current) {
            is HttpDataSource.InvalidResponseCodeException -> {
                parts.add("HTTP ${current.responseCode}")
                current.responseMessage?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                shortUri(current.dataSpec.uri)?.let { parts.add(it) }
                if (current.responseCode == 416) {
                    parts.add("byte range not in file (often truncated MKV cues)")
                }
            }
            is HttpDataSource.HttpDataSourceException -> {
                val kind = when (current.type) {
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN -> "open"
                    HttpDataSource.HttpDataSourceException.TYPE_READ -> "read"
                    HttpDataSource.HttpDataSourceException.TYPE_CLOSE -> "close"
                    else -> "request"
                }
                parts.add("HTTP $kind failed")
                shortUri(current.dataSpec.uri)?.let { parts.add(it) }
            }
            is UnrecognizedInputFormatException -> parts.add("unrecognized container")
            is BehindLiveWindowException -> parts.add("fell behind live window")
            is MediaCodecRenderer.DecoderInitializationException -> {
                val codec = current.codecInfo?.name ?: current.mimeType ?: "decoder"
                parts.add("decoder init failed ($codec)")
                current.diagnosticInfo?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
            is MediaCodecDecoderException -> {
                parts.add("decoder failed (${current.codecInfo?.name ?: "codec"})")
            }
            else -> {
                if (current !is PlaybackException) {
                    val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
                    val msg = current.message?.trim().orEmpty()
                    seen.add(
                        when {
                            msg.isBlank() || msg.equals("Source error", ignoreCase = true) -> name
                            msg.contains(name, ignoreCase = true) -> msg
                            else -> "$name: $msg"
                        },
                    )
                }
            }
        }
        current = current.cause
    }
    parts.addAll(seen)
    return parts.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" · ")
        .ifBlank { error.errorCodeName.ifBlank { "playback failed" } }
}

private fun humanErrorCode(code: Int, name: String): String {
    val label = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "network failed"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "network timeout"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "bad HTTP status"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "file not found"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "no permission"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "cleartext HTTP blocked"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "read past end of file"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "malformed container"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "malformed manifest"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "unsupported container"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "unsupported manifest"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "decoder init failed"
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "decoder query failed"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "decode failed"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "format exceeds decoder"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "unsupported decode format"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "audio track init failed"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "audio track write failed"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "behind live window"
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "I/O failed"
        PlaybackException.ERROR_CODE_TIMEOUT -> "player timeout"
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> "player runtime check"
        else -> name.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase().ifBlank { "playback error" }
    }
    return if (code != PlaybackException.ERROR_CODE_UNSPECIFIED) "$label ($name)" else label
}

private fun shortUri(uri: android.net.Uri?): String? {
    if (uri == null) return null
    val path = uri.encodedPath?.trim().orEmpty()
    return when {
        path.isNotBlank() -> path
        uri.host.isNullOrBlank() -> null
        else -> uri.host
    }
}
