package tv.coog.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String = "",
    val version: String = "",
    val ffmpeg: String = "",
)

@Serializable
data class LibraryResponse(
    val items: List<MediaItem> = emptyList(),
)

@Serializable
data class MaizePinRequest(
    val pin: String = "",
)

@Serializable
data class MaizeUnlockResponse(
    val ok: Boolean = false,
    val session: String = "",
    @SerialName("idleMinutes") val idleMinutes: Int = 20,
    val bucket: String = "Maize",
)

@Serializable
data class MaizeStatusResponse(
    val configured: Boolean = false,
    val bucket: String = "Maize",
    @SerialName("idleMinutes") val idleMinutes: Int = 20,
    val unlocked: Boolean = false,
)

@Serializable
data class MaizeHomeResponse(
    val bucket: String = "Maize",
    @SerialName("continueWatching") val continueWatching: List<MediaItem> = emptyList(),
    @SerialName("recentlyAdded") val recentlyAdded: List<MediaItem> = emptyList(),
    val library: List<MediaItem> = emptyList(),
)

@Serializable
data class ActorSummary(
    val slug: String = "",
    val name: String = "",
    @SerialName("sceneCount") val sceneCount: Int = 0,
    @SerialName("hasHeadshot") val hasHeadshot: Boolean = false,
    @SerialName("galleryCount") val galleryCount: Int = 0,
    val enriched: Boolean = false,
)

@Serializable
data class ActorSimilar(
    val slug: String = "",
    val name: String = "",
    val shared: Int = 0,
)

@Serializable
data class ActorProfile(
    val slug: String = "",
    val name: String = "",
    @SerialName("sceneCount") val sceneCount: Int = 0,
    @SerialName("hasHeadshot") val hasHeadshot: Boolean = false,
    @SerialName("galleryCount") val galleryCount: Int = 0,
    val enriched: Boolean = false,
    val bio: String = "",
    val birthday: String = "",
    val birthplace: String = "",
    val ethnicity: String = "",
    val nationality: String = "",
    @SerialName("hairColor") val hairColor: String = "",
    @SerialName("eyeColor") val eyeColor: String = "",
    val height: String = "",
    val weight: String = "",
    val measurements: String = "",
    @SerialName("shoeSize") val shoeSize: String = "",
    val tattoos: String = "",
    val piercings: String = "",
    @SerialName("yearsActive") val yearsActive: String = "",
    val aliases: List<String> = emptyList(),
    val links: Map<String, String> = emptyMap(),
    val locked: Boolean = false,
    val scenes: List<MediaItem> = emptyList(),
    val similar: List<ActorSimilar> = emptyList(),
)

@Serializable
data class ActorsResponse(
    val actors: List<ActorSummary> = emptyList(),
)

@Serializable
data class FunscriptPoint(
    val t: Int = 0,
    val pos: Double = 0.0,
    @SerialName("posMin") val posMin: Double = 0.0,
    @SerialName("posMax") val posMax: Double = 0.0,
    val speed: Double = 0.0,
    val strength: Double = 0.0,
)

@Serializable
data class FunscriptPreview(
    @SerialName("durationMs") val durationMs: Int = 0,
    @SerialName("actionCount") val actionCount: Int = 0,
    val intensity: Double = 0.0,
    val points: List<FunscriptPoint> = emptyList(),
    val name: String = "",
)

@Serializable
data class MediaVariant(
    val id: String = "",
    val label: String = "",
    val filename: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    val preferred: Boolean = false,
)

@Serializable
data class FunscriptOption(
    val name: String = "",
    val label: String = "",
    val preferred: Boolean = false,
    val intensity: Double = 0.0,
)

@Serializable
data class MaizeSyncStatus(
    val supported: Boolean = false,
    val message: String = "",
)

@Serializable
data class InteractiveStatus(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    @SerialName("engineRunning") val engineRunning: Boolean = false,
    val connected: Boolean = false,
    val scanning: Boolean = false,
    val pairing: Boolean = false,
    @SerialName("deviceCount") val deviceCount: Int = 0,
    @SerialName("lastError") val lastError: String = "",
    @SerialName("reconnectPhase") val reconnectPhase: String = "",
)

@Serializable
data class InteractiveDevice(
    val index: Int = -1,
    @SerialName("deviceId") val deviceId: String = "",
    val name: String = "",
    val kind: String = "",
    val connected: Boolean = false,
    val paired: Boolean = false,
    val profile: String = "",
    val favorite: Boolean = false,
    @SerialName("offsetMs") val offsetMs: Int = 350,
    @SerialName("offsetLinearMs") val offsetLinearMs: Int = 350,
    val intensity: Int = 100,
    val status: String = "",
    val wanted: Boolean = false,
    @SerialName("batterySupported") val batterySupported: Boolean = false,
    @SerialName("batteryPercent") val batteryPercent: Int = -1,
)

@Serializable
data class InteractiveEngineState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val scanning: Boolean = false,
    val pairing: Boolean = false,
    @SerialName("pairingSecondsLeft") val pairingSecondsLeft: Int = 0,
    @SerialName("reconnectPhase") val reconnectPhase: String = "",
    @SerialName("reconnectAttempts") val reconnectAttempts: Int = 0,
    @SerialName("reconnectReason") val reconnectReason: String = "",
    @SerialName("lastError") val lastError: String = "",
    @SerialName("missingPaired") val missingPaired: Int = 0,
    val devices: List<InteractiveDevice> = emptyList(),
    @SerialName("trustedDevices") val trustedDevices: List<InteractiveDevice> = emptyList(),
    @SerialName("knownDevices") val knownDevices: List<InteractiveDevice> = emptyList(),
)

@Serializable
data class InteractiveLoadRequest(
    @SerialName("mediaId") val mediaId: String = "",
    val script: String = "",
    @SerialName("resumeMs") val resumeMs: Double = 0.0,
    val offset: Int = 350,
    val scale: Double = 1.0,
    val speed: Double = 0.9,
    @SerialName("minInterval") val minInterval: Int = 60,
    val invert: Boolean = false,
)

@Serializable
data class OkEngineResponse(
    val ok: Boolean = false,
    val engine: InteractiveEngineState = InteractiveEngineState(),
)

@Serializable
data class OkResponse(
    val ok: Boolean = false,
)

@Serializable
data class MediaItem(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val year: Int = 0,
    val season: Int = 0,
    val episode: Int = 0,
    @SerialName("showTitle") val showTitle: String = "",
    val path: String = "",
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("codecVideo") val codecVideo: String = "",
    @SerialName("codecAudio") val codecAudio: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val hdr: String = "",
    @SerialName("durationMs") val durationMs: Long = 0,
    @SerialName("contentType") val contentType: String = "",
    @SerialName("imdbId") val imdbId: String = "",
    @SerialName("matchStatus") val matchStatus: String = "",
    val tagline: String = "",
    val plot: String = "",
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    @SerialName("posterUrl") val posterUrl: String = "",
    @SerialName("backdropUrl") val backdropUrl: String = "",
    @SerialName("logoUrl") val logoUrl: String = "",
    @SerialName("inLibrary") val inLibrary: Boolean = false,
    @SerialName("mediaId") val libraryId: String = "",
    @SerialName("releasePhase") val releasePhase: String = "",
    @SerialName("releaseDate") val releaseDate: String = "",
    @SerialName("tmdbId") val tmdbId: Int = 0,
    @SerialName("episodeCount") val episodeCount: Int = 0,
    val cast: List<CastMember> = emptyList(),
    val director: CastMember = CastMember(),
    @SerialName("runtimeMinutes") val runtimeMinutes: Int = 0,
    val certification: String = "",
    val country: String = "",
    @SerialName("positionMs") val positionMs: Long = 0,
    @SerialName("matchPercent") val tasteMatch: Int = 0,
    @SerialName("hasFunscript") val hasFunscript: Boolean = false,
    @SerialName("hasMeta") val hasMeta: Boolean = false,
    @SerialName("scriptIntensity") val scriptIntensity: Double = 0.0,
    val studio: String = "",
    val performers: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val funscript: FunscriptPreview? = null,
    @SerialName("funscriptName") val funscriptName: String = "",
    val videos: List<MediaVariant> = emptyList(),
    val funscripts: List<FunscriptOption> = emptyList(),
    /** Client-only: chosen script basename for interactive load. */
    @SerialName("selectedFunscript") val selectedFunscript: String = "",
    @SerialName("fileQuality") val fileQuality: String = "",
    @SerialName("fileSizeLabel") val fileSizeLabel: String = "",
    @SerialName("filePack") val filePack: String = "",
    @SerialName("fileTags") val fileTags: List<String> = emptyList(),
    @SerialName("fileLanguages") val fileLanguages: List<String> = emptyList(),
    @SerialName("fileReleaseTitle") val fileReleaseTitle: String = "",
) {
    fun isLocal(): Boolean = path.isNotBlank() || diskMediaId().isNotBlank()

    fun diskMediaId(): String {
        val lib = libraryId.trim()
        if (lib.isNotBlank() && !lib.isVirtualMediaId()) return lib
        if (id.isNotBlank() && !id.isVirtualMediaId()) return id
        return ""
    }

    fun playableId(): String = diskMediaId().ifBlank { id }

    fun trailerMediaId(): String {
        if (id.startsWith("catalog:")) return id
        val imdb = imdbId.trim()
        if (imdb.startsWith("tt")) {
            return if (kind == "series" || kind == "episode") {
                "catalog:$imdb:1:1"
            } else {
                "catalog:$imdb"
            }
        }
        return id
    }

    /** True when we can request an official trailer (catalog / IMDb-backed titles). */
    fun canPlayTrailer(): Boolean {
        if (imdbId.trim().startsWith("tt")) return true
        if (id.startsWith("catalog:tt")) return true
        return false
    }

    fun playBlocked(): Boolean = kind != "series" && kind != "episode" &&
        releasePhase == "coming_soon" && !isLocal()

    /** Human-readable release line for unreleased titles, e.g. "Releases Oct 15, 2026". */
    fun releaseAnnouncement(): String {
        val formatted = formatReleaseDate(releaseDate)
        return if (formatted != null) "Releases $formatted" else "Coming soon"
    }
}

fun formatReleaseDate(raw: String): String? {
    val s = raw.trim()
    if (s.length < 10) return null
    val parts = s.substring(0, 10).split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null
    val months = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    return "${months[month - 1]} $day, $year"
}

@Serializable
data class PlaybackSessionRequest(
    val mediaId: String = "",
    val jobId: String = "",
    val imdbId: String = "",
    val kind: String = "",
    val title: String = "",
    val year: Int = 0,
    val season: Int = 0,
    val episode: Int = 0,
    val clientCapabilities: ClientCapabilities? = ClientCapabilities(),
)

@Serializable
data class ClientCapabilities(
    val videoCodecs: List<String> = listOf("h264", "hevc", "vp9", "av1"),
    val audioCodecs: List<String> = listOf("aac", "ac3", "eac3", "opus", "mp3", "truehd", "dts", "dtshd"),
    val containers: List<String> = listOf("mp4", "mkv", "webm", "ts"),
    val hdr: List<String> = listOf("hdr10", "hlg", "dolbyvision"),
)

@Serializable
data class PlaybackSession(
    val id: String = "",
    val method: String = "",
    val reason: String = "",
    val url: String = "",
    val mediaId: String = "",
    val expectedDurationMs: Long = 0,
    val bufferedMs: Long = 0,
    val error: String = "",
    val jobId: String = "",
)

@Serializable
data class CatalogItemsResponse(
    val items: List<MediaItem> = emptyList(),
)

@Serializable
data class CatalogHomeResponse(
    val trendingMovies: List<MediaItem> = emptyList(),
    val trendingSeries: List<MediaItem> = emptyList(),
    val forYou: List<MediaItem> = emptyList(),
    val coldStart: Boolean = false,
)

@Serializable
data class StreamingSettings(
    val saveToLibrary: Boolean = true,
    val autoplayNextEpisode: Boolean = true,
    val autoDownloadNextEpisode: Boolean = true,
    val prefetchBeforeEndMinutes: Int = 5,
    val prefetchCount: Int = 1,
    val continueOverlaySeconds: Int = 10,
    val includeWebStreams: Boolean = true,
    val autoSelectSource: Boolean = true,
    val preferredQualities: List<String> = listOf("1080p", "2160p"),
    val preferredLanguages: List<String> = listOf("en", "eng", "english"),
    val minSizeMb: Int = 0,
    val maxSizeMb: Int = 0,
    val preferSingleEpisode: Boolean = true,
    val allowSeasonPacks: Boolean = true,
    val requireCached: Boolean = false,
    val excludeQualities: List<String> = listOf("threed", "480p", "cam", "scr"),
    /** Caps hero/focus backdrop size: 1080p, 1440p, or 2160p. */
    val preferredBackdropMax: String = "1080p",
)

@Serializable
data class ServerStats(
    val version: String = "",
    val libraryPath: String = "",
    val dataPath: String = "",
    val mediaCount: Int = 0,
    val disk: DiskStats? = null,
    val worker: WorkerStats = WorkerStats(),
    val realDebrid: RealDebridStats = RealDebridStats(),
    val catalogError: String = "",
)

@Serializable
data class DiskStats(
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val path: String = "",
)

@Serializable
data class WorkerStats(
    val stale: Boolean = true,
    val updatedAt: Long = 0,
    val pid: Int = 0,
    val seenAgoS: Long = -1,
)

@Serializable
data class RealDebridStats(
    val configured: Boolean = false,
    val premium: Boolean = false,
    val username: String = "",
    val type: String = "",
    val error: String = "",
    val expiration: String = "",
)

@Serializable
data class PrefetchNextRequest(
    val imdbId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val title: String = "",
    val year: Int = 0,
)

@Serializable
data class PrefetchNextResponse(
    val ok: Boolean = false,
    val skipped: Boolean = false,
    val reason: String = "",
    val jobId: String = "",
)

@Serializable
data class CatalogGenre(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class CatalogGenresResponse(
    val items: List<CatalogGenre> = emptyList(),
)

@Serializable
data class CatalogMood(
    val id: String = "",
    val label: String = "",
)

@Serializable
data class CatalogMoodsResponse(
    val items: List<CatalogMood> = emptyList(),
)

@Serializable
data class PlaybackProgressRequest(
    val imdbId: String = "",
    val tmdbId: Int = 0,
    val kind: String = "",
    val title: String = "",
    val year: Int = 0,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val mediaId: String = "",
)

@Serializable
data class SeasonInfo(
    val number: Int = 0,
    @SerialName("episodeCount") val episodeCount: Int = 0,
)

@Serializable
data class CatalogShowResponse(
    val item: MediaItem = MediaItem(id = ""),
    val seasons: List<SeasonInfo> = emptyList(),
    val season: Int = 0,
    val episodes: List<MediaItem> = emptyList(),
)

@Serializable
data class CastMember(
    @SerialName("tmdbId") val tmdbId: Int = 0,
    val name: String = "",
    val character: String = "",
    @SerialName("profileUrl") val profileUrl: String = "",
)

@Serializable
data class StreamCandidate(
    @SerialName("infoHash") val infoHash: String = "",
    val title: String = "",
    val name: String = "",
    val quality: String = "",
    val cached: Boolean = false,
    val seeders: Int = 0,
    val size: Long = 0,
    @SerialName("sizeLabel") val sizeLabel: String = "",
    val source: String = "",
    val provider: String = "",
    val kind: String = "",
    val url: String = "",
    val pack: String = "",
    val tags: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
)

@Serializable
data class StreamPick(
    val ok: Boolean = false,
    val reason: String = "",
    val pack: String = "",
    val candidate: StreamCandidate? = null,
)

@Serializable
data class StreamsResponse(
    val items: List<StreamCandidate> = emptyList(),
    val pick: StreamPick? = null,
    val autoSelect: Boolean = true,
)

@Serializable
data class SubtitleTrack(
    val id: String = "",
    val source: String = "",
    val language: String = "",
    val label: String = "",
    val path: String = "",
    val filename: String = "",
    @SerialName("fileId") val fileId: Int = 0,
    @SerialName("downloadCount") val downloadCount: Int = 0,
    @SerialName("hearingImpaired") val hearingImpaired: Boolean = false,
)

@Serializable
data class SubtitlesListResponse(
    val ok: Boolean = false,
    val tracks: List<SubtitleTrack> = emptyList(),
    val error: String = "",
    val settings: SubtitleSettings = SubtitleSettings(),
)

@Serializable
data class SubtitleSettings(
    val enabled: Boolean = true,
    @SerialName("hasApiKey") val hasApiKey: Boolean = false,
    @SerialName("apiKeyMasked") val apiKeyMasked: String = "",
    val username: String = "",
    @SerialName("hasPassword") val hasPassword: Boolean = false,
    val userAgent: String = "",
    val languages: List<String> = emptyList(),
    val autoLoad: Boolean = true,
    @SerialName("preferEmbedded") val preferEmbedded: Boolean = true,
)

@Serializable
data class PersonSummary(
    @SerialName("tmdbId") val tmdbId: Int = 0,
    val name: String = "",
    @SerialName("profileUrl") val profileUrl: String = "",
    @SerialName("knownForDepartment") val knownForDepartment: String = "",
    val biography: String = "",
    val birthday: String = "",
    @SerialName("placeOfBirth") val placeOfBirth: String = "",
    val credits: List<MediaItem> = emptyList(),
)

@Serializable
data class SearchResponse(
    val movies: List<MediaItem> = emptyList(),
    val series: List<MediaItem> = emptyList(),
    val people: List<PersonSummary> = emptyList(),
    val error: String = "",
)

@Serializable
data class EnqueueJobRequest(
    val url: String = "",
    val title: String = "",
    val type: String = "ytdlp",
    val imdbId: String = "",
    val infoHash: String = "",
    val kind: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val year: Int = 0,
    val quality: String = "",
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("sizeLabel") val sizeLabel: String = "",
    val pack: String = "",
    val tags: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    @SerialName("releaseTitle") val releaseTitle: String = "",
    /** Explicit Sources pick — allow another library file beside an existing copy. */
    val force: Boolean = false,
)

@Serializable
data class JobsResponse(
    val items: List<JobItem> = emptyList(),
)

@Serializable
data class TransferStats(
    @SerialName("downloadBps") val downloadBps: Long = 0,
    @SerialName("uploadBps") val uploadBps: Long = 0,
    val peers: Int = 0,
    val seeders: Int = 0,
    @SerialName("totalPeers") val totalPeers: Int = 0,
    val health: String = "",
)

@Serializable
data class JobItem(
    val id: String,
    val type: String = "",
    val url: String = "",
    val title: String = "",
    val status: String = "",
    val progress: Double = 0.0,
    val ready: Boolean = false,
    @SerialName("expectedDurationMs") val expectedDurationMs: Long = 0,
    @SerialName("bufferedMs") val bufferedMs: Long = 0,
    val error: String = "",
    @SerialName("mediaId") val mediaId: String = "",
    @SerialName("imdbId") val imdbId: String = "",
    @SerialName("infoHash") val infoHash: String = "",
    @SerialName("logTail") val logTail: String = "",
    val quality: String = "",
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("sizeLabel") val sizeLabel: String = "",
    val pack: String = "",
    val tags: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    @SerialName("releaseTitle") val releaseTitle: String = "",
    val transfer: TransferStats? = null,
) {
    fun isActive(): Boolean = status == "queued" || status == "downloading" || status == "ready" || status == "paused"

    fun isFinished(): Boolean = status == "finished"

    fun canPause(): Boolean = status == "queued" || status == "downloading" || status == "ready"

    fun canResume(): Boolean = status == "paused" || status == "error"

    fun canCancel(): Boolean = status != "finished" && status != "cancelled"

    fun headline(): String = title.ifBlank { "Downloading…" }

    fun subtitle(): String {
        val pct = (progress * 100).toInt().coerceIn(0, 99)
        val readyLabel = when {
            expectedDurationMs > 0 && bufferedMs > 0 ->
                "${jobClock(bufferedMs)} / ${jobClock(expectedDurationMs)}"
            pct > 0 -> "$pct%"
            else -> null
        }
        val left = remainingLabel()
        return when {
            status == "error" -> error.ifBlank { "error" }
            status == "finished" -> "Finished"
            status == "queued" -> "Queued"
            status == "paused" -> listOfNotNull("Paused", readyLabel, left).joinToString("  ·  ")
            status == "cancelled" -> "Cancelled"
            ready && status != "finished" -> listOfNotNull("Ready to play", readyLabel, left).joinToString("  ·  ")
            else -> listOfNotNull("Downloading", readyLabel ?: pct.takeIf { it > 0 }?.let { "$it%" }, left).joinToString("  ·  ")
        }
    }

    fun transferLine(): String? {
        val t = transfer ?: return null
        if (status != "downloading" && status != "ready" && status != "paused") return null
        if (t.health.isBlank() && t.peers == 0 && t.seeders == 0 && t.downloadBps == 0L && t.uploadBps == 0L) {
            return null
        }
        val parts = buildList {
            if (t.downloadBps > 0 || t.uploadBps > 0 || t.peers > 0 || t.seeders > 0) {
                add("↓ ${formatByteRate(t.downloadBps)}")
                add("↑ ${formatByteRate(t.uploadBps)}")
            }
            add("${t.seeders} seeders")
            add("${t.peers} peers")
            if (t.health.isNotBlank()) add(t.health)
        }
        return parts.joinToString("  ·  ")
    }

    fun remainingLabel(): String? {
        if (expectedDurationMs <= 0 || bufferedMs <= 0 || bufferedMs >= expectedDurationMs) return null
        val left = expectedDurationMs - bufferedMs
        val minutes = (left / 60_000).toInt().coerceAtLeast(1)
        return if (minutes >= 60) {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "${h}h left" else "${h}h ${m}m left"
        } else {
            "$minutes min left"
        }
    }
}

private fun formatByteRate(bps: Long): String = when {
    bps < 1024 -> "$bps B/s"
    bps < 1024 * 1024 -> "%.1f KB/s".format(bps / 1024.0)
    else -> "%.2f MB/s".format(bps / (1024.0 * 1024.0))
}

private fun jobClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Serializable
data class ClientEventRequest(
    val level: String = "error",
    val type: String,
    val message: String,
    val mediaId: String = "",
    val jobId: String = "",
    val sessionId: String = "",
)

internal fun String.isVirtualMediaId(): Boolean =
    startsWith("catalog:") || startsWith("continue:")
