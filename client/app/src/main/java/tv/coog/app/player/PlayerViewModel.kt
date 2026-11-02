package tv.coog.app.player

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class PlayerTrack(
    val id: String,
    val label: String,
    val selected: Boolean,
    val type: Int,
    val groupIndex: Int,
    val indexInGroup: Int,
    val language: String = "",
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    @OptIn(UnstableApi::class)
    val player: ExoPlayer = buildTvExoPlayer(app)
    private var preparedUrl: String? = null
    private var preparedMediaKey: String = ""
    private var lastToken: String = ""
    private var lastAdultSession: String = ""
    private var mkvCueSeekDisabled: Boolean = false
    private var externalSubUrl: String? = null
    private var externalSubMime: String? = null
    private var externalSubLang: String? = null
    private var resumeAtMs: Long = 0L
    private var resumeApplied: Boolean = false
    private var pendingSeekMs: Long = -1L
    private var oomRecovered: Boolean = false
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

    private val _subtitleDelayMs = MutableStateFlow(0)
    val subtitleDelayMs: StateFlow<Int> = _subtitleDelayMs.asStateFlow()

    private val _subtitleSize = MutableStateFlow(1)
    val subtitleSize: StateFlow<Int> = _subtitleSize.asStateFlow()

    private val cueHistory = ArrayDeque<Pair<Long, List<String>>>(64)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _buffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_READY) {
                pendingSeekMs = -1L
            }
            if (state == Player.STATE_READY && !resumeApplied && resumeAtMs > 0) {
                val dur = player.duration
                val target = if (dur > 0) {
                    resumeAtMs.coerceIn(0L, (dur - 5_000L).coerceAtLeast(0L))
                } else {
                    resumeAtMs
                }
                if (target > 2_000L) {
                    player.seekTo(target)
                }
                resumeApplied = true
            }
            if (state == Player.STATE_ENDED) {
                if (suppressEnded) {
                    val target = (player.bufferedPosition - 1_500L).coerceAtLeast(0L)
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

        override fun onRenderedFirstFrame() {
            _firstFrame.value = true
            _buffering.value = false
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = preparedUrl
            // Incomplete MKVs keep cues at EOF. A seek trips HTTP 416, and a bare
            // re-prepare used to restart at 0 — restore the scrub target instead.
            if (!mkvCueSeekDisabled && url != null && isUnreachableMkvCues(error)) {
                val fallback = when {
                    pendingSeekMs >= 0L -> pendingSeekMs
                    player.currentPosition > 0L -> player.currentPosition
                    else -> resumeAtMs
                }.coerceAtLeast(0L)
                resumeAtMs = fallback
                resumeApplied = fallback <= 0L
                pendingSeekMs = -1L
                prepare(
                    url,
                    lastToken,
                    lastAdultSession,
                    disableMkvCueSeek = true,
                    keepPicture = true,
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
                prepare(url, lastToken, lastAdultSession, disableMkvCueSeek = mkvCueSeekDisabled, keepPicture = true)
                return
            }
            _error.value = describePlaybackError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            val lines = cueGroup.cues.mapNotNull { cueText(it) }.filter { it.isNotBlank() }
            val at = player.currentPosition
            if (cueHistory.size >= 64) cueHistory.removeFirst()
            cueHistory.addLast(at to lines)
            refreshDisplayedCues()
        }
    }

    init {
        player.addListener(listener)
    }

    fun play(
        url: String,
        token: String,
        startPositionMs: Long = 0L,
        adultSession: String = "",
        mediaKey: String = "",
        seamless: Boolean = false,
        contentType: String = "",
    ) {
        if (seamless && preparedUrl != null && preparedUrl != url) {
            resumeAtMs = player.currentPosition.coerceAtLeast(0L)
            resumeApplied = resumeAtMs <= 0L
            preparedMediaKey = mediaKey
            oomRecovered = false
            prepare(url, token, adultSession, disableMkvCueSeek = false, keepPicture = true, contentType = contentType)
            return
        }
        resumeAtMs = startPositionMs.coerceAtLeast(0L)
        resumeApplied = resumeAtMs <= 0L
        if (
            preparedUrl == url &&
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
        prepare(url, token, adultSession, disableMkvCueSeek = false, contentType = contentType)
    }

    fun setExternalSubtitle(url: String?, language: String = "", mimeType: String = MimeTypes.APPLICATION_SUBRIP) {
        externalSubUrl = url
        externalSubLang = language
        externalSubMime = mimeType.ifBlank { mimeFor(url) }
        val video = preparedUrl ?: return
        resumeAtMs = player.currentPosition
        resumeApplied = false
        prepare(video, lastToken, lastAdultSession, disableMkvCueSeek = mkvCueSeekDisabled, keepPicture = true)
        if (url != null) {
            _textOff.value = false
        }
    }

    fun clearExternalSubtitle() {
        if (externalSubUrl == null) {
            setTextOff()
            return
        }
        externalSubUrl = null
        externalSubLang = null
        externalSubMime = null
        val video = preparedUrl ?: return
        resumeAtMs = player.currentPosition
        resumeApplied = false
        prepare(video, lastToken, lastAdultSession, disableMkvCueSeek = mkvCueSeekDisabled, keepPicture = true)
        setTextOff()
    }

    fun nudgeSubtitleDelay(deltaMs: Int) {
        _subtitleDelayMs.value = (_subtitleDelayMs.value + deltaMs).coerceIn(-10_000, 10_000)
        refreshDisplayedCues()
    }

    fun cycleSubtitleSize() {
        _subtitleSize.value = (_subtitleSize.value + 1) % 3
    }

    private fun refreshDisplayedCues() {
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
        // Growing job files never have MKV cues on disk yet (they live at EOF). Seeking
        // must not probe the final size or ExoPlayer 416s and restarts from 0.
        val progressiveJob = isProgressiveJobFileUrl(url)
        val skipMkvCues = disableMkvCueSeek || progressiveJob
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
        val extractors = DefaultExtractorsFactory()
        if (skipMkvCues) {
            extractors.setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        }
        val builder = MediaItem.Builder().setUri(url)
        // Extension-less /jobs/.../file and /media/.../stream URLs need an explicit MIME
        // so MKV/MP4 extractors bind (browsers use the HTTP Content-Type instead).
        (mimeFromContentType(contentType) ?: mimeFromPlaybackUrl(url))?.let { builder.setMimeType(it) }
        val subUrl = externalSubUrl
        if (!subUrl.isNullOrBlank()) {
            val sub = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(externalSubMime ?: mimeFor(subUrl))
                .setLanguage(externalSubLang?.takeIf { it.isNotBlank() } ?: "und")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(sub))
        }
        val source = DefaultMediaSourceFactory(http, extractors)
            .createMediaSource(builder.build())
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        preparedUrl = url
        if (subUrl != null) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
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
        externalSubUrl = null
        externalSubLang = null
        externalSubMime = null
        _cueLines.value = emptyList()
        cueHistory.clear()
        _subtitleDelayMs.value = 0
        resumeAtMs = 0
        resumeApplied = true
        oomRecovered = false
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
        pendingSeekMs = target
        player.seekTo(target)
        refreshDisplayedCues()
    }

    fun selectTrack(track: PlayerTrack) {
        if (track.type == C.TRACK_TYPE_TEXT) {
            externalSubUrl = null
            externalSubLang = null
            externalSubMime = null
        }
        val groups = player.currentTracks.groups
        if (track.groupIndex !in groups.indices) return
        val group = groups[track.groupIndex]
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(track.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.indexInGroup))
            .build()
        if (track.type == C.TRACK_TYPE_TEXT) {
            _textOff.value = false
        }
    }

    fun setTextOff() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _textOff.value = true
        _cueLines.value = emptyList()
        cueHistory.clear()
    }

    private fun refreshTracks(tracks: Tracks) {
        _audioTracks.value = collect(tracks, C.TRACK_TYPE_AUDIO)
        val text = collect(tracks, C.TRACK_TYPE_TEXT)
        _textTracks.value = text
        _textOff.value = text.none { it.selected } ||
            player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    }

    private fun collect(tracks: Tracks, type: Int): List<PlayerTrack> {
        val out = mutableListOf<PlayerTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
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
                    ),
                )
            }
        }
        return out
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
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return if (type == C.TRACK_TYPE_TEXT) "Subtitles ${index + 1}" else "Audio ${index + 1}"
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
    // Xiaomi / many ATV boxes pick a Dolby Vision MediaCodec for Profile 8 that
    // mis-maps PQ → inverted/washed colors. P8's base layer is HDR10-compatible —
    // decode as HEVC. If the Display advertises no HDR types, also request
    // MediaCodec (API 31+) or OpenGL (API ≤30) HDR→SDR tone-map.
    val preferHevcForDolbyVision = MediaCodecSelector { mimeType, secure, tunneling ->
        if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)) {
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
            .setTunnelingEnabled(false)
            .build()
    }
    val player = ExoPlayer.Builder(app, renderers)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .build()
    // Attach the video-effects sink so PlaybackVideoGraphWrapper OpenGL tone-map runs.
    // Identity ScaleAndRotate is a no-op; tone-map is requested in CoogRenderersFactory.
    if (!CoogRenderersFactory.displayAdvertisesHdr(app) && Build.VERSION.SDK_INT < 31) {
        player.setVideoEffects(listOf(ScaleAndRotateTransformation.Builder().build()))
        Log.i(CoogRenderersFactory.TAG, "enabled OpenGL video-effects sink for HDR→SDR")
    }
    return player
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
        ctype.contains("matroska") || ctype.contains("x-mkv") || ctype == "video/mkv" ->
            MimeTypes.VIDEO_MATROSKA
        ctype.contains("mp4") || ctype.contains("m4v") || ctype.contains("quicktime") ->
            MimeTypes.VIDEO_MP4
        ctype.contains("webm") -> MimeTypes.VIDEO_WEBM
        ctype.contains("mpegts") || ctype.contains("mp2t") || ctype == "video/mp2t" ->
            MimeTypes.VIDEO_MP2T
        ctype.startsWith("video/") || ctype.startsWith("audio/") -> ctype
        else -> null
    }
}

/** Best-effort MIME when the session forgot contentType on an extension-less stream URL. */
private fun mimeFromPlaybackUrl(url: String): String? {
    val path = url.substringBefore('?').lowercase(Locale.US)
    return when {
        path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        path.endsWith(".ts") || path.endsWith(".m2ts") -> MimeTypes.VIDEO_MP2T
        // Library direct-play URLs are extension-less; default to MP4 (common) — server
        // should send contentType. MKV library rows still need contentType from the API.
        path.contains("/api/v1/media/") && path.endsWith("/stream") -> MimeTypes.VIDEO_MP4
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
