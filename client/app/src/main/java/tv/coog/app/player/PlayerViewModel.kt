package tv.coog.app.player

import android.app.Application
import android.net.Uri
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
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
    val player: ExoPlayer = ExoPlayer.Builder(app).build()
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
            if (!mkvCueSeekDisabled && url != null && isUnreachableMkvCues(error)) {
                prepare(url, lastToken, lastAdultSession, disableMkvCueSeek = true)
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

    fun play(url: String, token: String, startPositionMs: Long = 0L, adultSession: String = "", mediaKey: String = "") {
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
        prepare(url, token, adultSession, disableMkvCueSeek = false)
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

    private fun prepare(url: String, token: String, adultSession: String, disableMkvCueSeek: Boolean, keepPicture: Boolean = false) {
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
        mkvCueSeekDisabled = disableMkvCueSeek
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
        if (disableMkvCueSeek) {
            extractors.setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        }
        val builder = MediaItem.Builder().setUri(url)
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
    }

    fun seekBy(deltaMs: Long, maxMs: Long) {
        val target = player.currentPosition + deltaMs
        val upper = if (maxMs > 0) maxMs else Long.MAX_VALUE
        player.seekTo(target.coerceIn(0L, upper))
        refreshDisplayedCues()
    }

    fun selectTrack(track: PlayerTrack) {
        externalSubUrl = null
        externalSubLang = null
        externalSubMime = null
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
                        language = format.language?.trim().orEmpty(),
                    ),
                )
            }
        }
        return out
    }

    private fun trackLabel(format: Format, type: Int, index: Int): String {
        val named = format.label?.trim().orEmpty()
        if (named.isNotBlank()) return named
        val lang = format.language?.trim().orEmpty()
        if (lang.isNotBlank() && lang != "und") {
            return runCatching { Locale.forLanguageTag(lang).displayLanguage }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: lang
        }
        return if (type == C.TRACK_TYPE_TEXT) "Subtitles ${index + 1}" else "Audio ${index + 1}"
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

private fun mimeFor(url: String?): String {
    val path = url?.substringBefore('?')?.lowercase().orEmpty()
    return when {
        path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
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
