package tv.coog.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CoogApi(
    private val serverUrl: String,
    private val token: String,
    private val adultSession: String = "",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request.Builder {
        val builder = Request.Builder().url(serverUrl.trimEnd('/') + path)
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        if (adultSession.isNotBlank()) {
            builder.header("X-Coog-Adult-Session", adultSession)
        }
        return builder
    }

    suspend fun health(): HealthResponse = get("/health")

    suspend fun library(): List<MediaItem> = get<LibraryResponse>("/api/v1/library").items

    suspend fun maizeUnlock(pin: String): MaizeUnlockResponse = post(
        "/api/v1/maize/unlock",
        json.encodeToString(MaizePinRequest.serializer(), MaizePinRequest(pin = pin)),
    )

    suspend fun maizeLock() {
        post<OkResponse>("/api/v1/maize/lock", "{}")
    }

    suspend fun maizeLibrary(filter: String = "", sort: String = ""): List<MediaItem> {
        val q = buildString {
            append("/api/v1/maize/library")
            val parts = mutableListOf<String>()
            if (filter.isNotBlank()) parts += "filter=${enc(filter)}"
            if (sort.isNotBlank()) parts += "sort=${enc(sort)}"
            if (parts.isNotEmpty()) append("?").append(parts.joinToString("&"))
        }
        return get<LibraryResponse>(q).items
    }

    suspend fun maizeHome(): MaizeHomeResponse = get("/api/v1/maize/home")

    suspend fun maizeMedia(id: String): MediaItem = get("/api/v1/maize/media/${enc(id)}")

    suspend fun maizeActors(): List<ActorSummary> =
        get<ActorsResponse>("/api/v1/maize/actors").actors

    suspend fun maizeActor(slug: String): ActorProfile =
        get("/api/v1/maize/actors/${enc(slug)}")

    fun maizeActorHeadshotUrl(slug: String): String {
        val base = serverUrl.trimEnd('/') + "/api/v1/maize/actors/${enc(slug)}/headshot"
        return if (adultSession.isNotBlank()) "$base?adult=${enc(adultSession)}" else base
    }

    fun maizeActorGalleryUrl(slug: String, index: Int): String {
        val base = serverUrl.trimEnd('/') + "/api/v1/maize/actors/${enc(slug)}/gallery/$index"
        return if (adultSession.isNotBlank()) "$base?adult=${enc(adultSession)}" else base
    }

    suspend fun maizeFunscript(id: String, buckets: Int = 0): FunscriptPreview {
        val q = if (buckets > 0) "?buckets=$buckets" else ""
        return get("/api/v1/maize/media/${enc(id)}/funscript$q")
    }

    suspend fun maizeSyncStatus(): MaizeSyncStatus = get("/api/v1/maize/sync/status")

    suspend fun interactiveStatus(): InteractiveStatus = get("/api/v1/interactive/status")

    suspend fun interactiveEngine(): InteractiveEngineState = get("/api/v1/interactive/engine")

    suspend fun interactiveScanStart(): OkEngineResponse = post("/api/v1/interactive/engine/scan/start", "{}")

    suspend fun interactiveScanPair(): OkEngineResponse = post("/api/v1/interactive/engine/scan/pair", "{}")

    suspend fun interactiveScanStop(): OkEngineResponse = post("/api/v1/interactive/engine/scan/stop", "{}")

    suspend fun interactiveReconnect(): OkEngineResponse = post("/api/v1/interactive/engine/reconnect", "{}")

    suspend fun interactiveRestart(): OkEngineResponse = post("/api/v1/interactive/engine/restart", "{}")

    suspend fun interactiveStopAll(): OkEngineResponse = post("/api/v1/interactive/engine/stop_all", "{}")

    suspend fun interactiveForgetOffline(): OkEngineResponse = post("/api/v1/interactive/devices/forget-offline", "{}")

    suspend fun interactiveTest(index: Int): OkResponse = post("/api/v1/interactive/devices/$index/test", "{}")

    suspend fun interactivePatch(index: Int, intensity: Int? = null, offsetMs: Int? = null): OkResponse {
        val parts = mutableListOf<String>()
        if (intensity != null) parts += "\"intensity\":$intensity"
        if (offsetMs != null) parts += "\"offsetMs\":$offsetMs"
        return patch("/api/v1/interactive/devices/$index", "{${parts.joinToString(",")}}")
    }

    suspend fun interactiveConnect(deviceId: String): OkEngineResponse =
        post("/api/v1/interactive/devices/id/${enc(deviceId)}/connect", "{}")

    suspend fun interactiveDisconnect(deviceId: String): OkEngineResponse =
        post("/api/v1/interactive/devices/id/${enc(deviceId)}/disconnect", "{}")

    suspend fun interactiveForget(deviceId: String): OkResponse = withContext(Dispatchers.IO) {
        val req = request("/api/v1/interactive/devices/id/${enc(deviceId)}").delete().build()
        execute(req)
    }

    suspend fun interactiveLoad(req: InteractiveLoadRequest): OkResponse = post(
        "/api/v1/interactive/load",
        json.encodeToString(InteractiveLoadRequest.serializer(), req),
    )

    fun interactiveSyncWsUrl(): String {
        val base = serverUrl.trimEnd('/').replace("http://", "ws://").replace("https://", "wss://")
        val q = buildList {
            if (token.isNotBlank()) add("token=${enc(token)}")
            if (adultSession.isNotBlank()) add("adult=${enc(adultSession)}")
        }.joinToString("&")
        return "$base/ws/v1/sync" + if (q.isNotBlank()) "?$q" else ""
    }

    suspend fun maizeStatus(): MaizeStatusResponse = get("/api/v1/maize/status")

    suspend fun item(id: String): MediaItem = get("/api/v1/library/$id")

    suspend fun playbackSession(
        mediaId: String = "",
        jobId: String = "",
        imdbId: String = "",
        kind: String = "",
        title: String = "",
        year: Int = 0,
        season: Int = 0,
        episode: Int = 0,
    ): PlaybackSession {
        val body = json.encodeToString(
            PlaybackSessionRequest.serializer(),
            PlaybackSessionRequest(
                mediaId = mediaId,
                jobId = jobId,
                imdbId = imdbId,
                kind = kind,
                title = title,
                year = year,
                season = season,
                episode = episode,
            ),
        )
        return withContext(Dispatchers.IO) {
            val req = request("/api/v1/playback/sessions")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val session = runCatching { json.decodeFromString<PlaybackSession>(text) }.getOrNull()
                if (session != null && (resp.isSuccessful || session.jobId.isNotBlank())) {
                    return@use session
                }
                throw ApiException(resp.code, text.ifBlank { resp.message })
            }
        }
    }

    suspend fun trailerExists(id: String): Boolean = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val req = request("/api/v1/media/$enc/trailer").head().build()
        client.newCall(req).execute().use { resp ->
            resp.code != 404 && resp.code < 500
        }
    }

    suspend fun catalogHome(): CatalogHomeResponse = get("/api/v1/catalog/home")

    suspend fun streamingSettings(): StreamingSettings = get("/api/v1/settings/streaming")

    suspend fun serverStats(): ServerStats = get("/api/v1/server/stats")

    suspend fun prefetchNextEpisode(
        imdbId: String,
        season: Int,
        episode: Int,
        title: String = "",
        year: Int = 0,
    ) {
        post<PrefetchNextResponse>(
            "/api/v1/playback/prefetch-next",
            json.encodeToString(
                PrefetchNextRequest(
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    title = title,
                    year = year,
                ),
            ),
        )
    }

    suspend fun catalogBrowse(
        kind: String,
        sort: String,
        genreIds: List<Int> = emptyList(),
        yearMin: Int = 0,
        yearMax: Int = 0,
        mood: String = "",
        minRating: Double = 0.0,
    ): List<MediaItem> {
        val q = buildString {
            append("/api/v1/catalog/browse?kind=${enc(kind.ifBlank { "movie" })}")
            append("&sort=${enc(sort.ifBlank { "trending" })}")
            val genres = genreIds.filter { it > 0 }.distinct().take(3)
            if (genres.isNotEmpty()) append("&genres=${enc(genres.joinToString(","))}")
            if (yearMin > 0) append("&yearMin=$yearMin")
            if (yearMax > 0) append("&yearMax=$yearMax")
            if (mood.isNotBlank()) append("&mood=${enc(mood)}")
            if (minRating > 0) append("&minRating=$minRating")
        }
        return get<CatalogItemsResponse>(q).items
    }

    suspend fun catalogGenres(kind: String): List<CatalogGenre> =
        get<CatalogGenresResponse>("/api/v1/catalog/genres?kind=${enc(kind.ifBlank { "movie" })}").items

    suspend fun catalogMoods(kind: String): List<CatalogMood> =
        get<CatalogMoodsResponse>("/api/v1/catalog/moods?kind=${enc(kind.ifBlank { "movie" })}").items

    suspend fun catalogContinue(): List<MediaItem> =
        get<CatalogItemsResponse>("/api/v1/catalog/continue").items

    suspend fun reportProgress(
        item: MediaItem?,
        positionMs: Long,
        durationMs: Long,
        mediaId: String = "",
    ) {
        val title = item ?: return
        val body = json.encodeToString(
            PlaybackProgressRequest.serializer(),
            PlaybackProgressRequest(
                imdbId = title.imdbId,
                tmdbId = title.tmdbId,
                kind = title.kind,
                title = title.title.ifBlank { title.showTitle },
                year = title.year,
                season = title.season,
                episode = title.episode,
                positionMs = positionMs,
                durationMs = durationMs,
                mediaId = mediaId.ifBlank { title.diskMediaId() },
            ),
        )
        withContext(Dispatchers.IO) {
            val req = request("/api/v1/playback/progress")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().close()
        }
    }

    suspend fun clearContinue(item: MediaItem) {
        val body = json.encodeToString(
            PlaybackProgressRequest.serializer(),
            PlaybackProgressRequest(
                imdbId = item.imdbId,
                tmdbId = item.tmdbId,
                kind = item.kind,
                title = item.title.ifBlank { item.showTitle },
                year = item.year,
                season = item.season,
                episode = item.episode,
                mediaId = item.diskMediaId(),
            ),
        )
        withContext(Dispatchers.IO) {
            val req = request("/api/v1/playback/progress/clear")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, resp.body?.string().orEmpty().ifBlank { resp.message })
                }
            }
        }
    }

    suspend fun catalogShow(imdbId: String, season: Int? = null): CatalogShowResponse {
        val path = buildString {
            append("/api/v1/catalog/series/$imdbId")
            if (season != null) append("?season=$season")
        }
        return get(path)
    }

    suspend fun catalogTitle(imdbId: String, kind: String = "movie"): MediaItem {
        val k = kind.ifBlank { "movie" }
        return get("/api/v1/catalog/title/$imdbId?kind=${enc(k)}")
    }

    suspend fun catalogTmdb(kind: String, tmdbId: Int): MediaItem =
        get("/api/v1/catalog/tmdb/${enc(kind.ifBlank { "movie" })}/$tmdbId")

    suspend fun catalogStreams(
        imdbId: String,
        kind: String = "movie",
        season: Int = 0,
        episode: Int = 0,
        title: String = "",
        year: Int = 0,
    ): StreamsResponse {
        val q = buildString {
            append("/api/v1/catalog/streams?imdb=${enc(imdbId)}")
            append("&kind=${enc(kind.ifBlank { "movie" })}")
            if (season > 0) append("&season=$season")
            if (episode > 0) append("&episode=$episode")
            if (title.isNotBlank()) append("&title=${enc(title)}")
            if (year > 0) append("&year=$year")
        }
        return get(q)
    }

    suspend fun saveStreamingSettings(cfg: StreamingSettings): StreamingSettings {
        val body = json.encodeToString(StreamingSettings.serializer(), cfg)
        return post("/api/v1/settings/streaming", body)
    }

    suspend fun catalogSearch(q: String): SearchResponse = get("/api/v1/catalog/search?q=${enc(q)}")

    suspend fun catalogPerson(tmdbId: Int): PersonSummary = get("/api/v1/catalog/person/$tmdbId")

    suspend fun catalogSimilar(imdbId: String, kind: String = "movie"): List<MediaItem> =
        get<CatalogItemsResponse>("/api/v1/catalog/title/$imdbId/similar?kind=${enc(kind.ifBlank { "movie" })}").items

    suspend fun jobs(): List<JobItem> = get<JobsResponse>("/api/v1/jobs").items

    suspend fun enqueueJob(url: String, title: String = ""): JobItem {
        val body = json.encodeToString(
            EnqueueJobRequest.serializer(),
            EnqueueJobRequest(url = url, title = title),
        )
        return post("/api/v1/jobs", body)
    }

    suspend fun enqueueTorrent(
        imdbId: String,
        infoHash: String,
        title: String = "",
        kind: String = "movie",
        season: Int = 0,
        episode: Int = 0,
        year: Int = 0,
        quality: String = "",
        sizeBytes: Long = 0,
        sizeLabel: String = "",
        pack: String = "",
        tags: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        releaseTitle: String = "",
        force: Boolean = false,
    ): JobItem {
        val body = json.encodeToString(
            EnqueueJobRequest.serializer(),
            EnqueueJobRequest(
                type = "torrent",
                imdbId = imdbId,
                infoHash = infoHash,
                title = title,
                kind = kind,
                season = season,
                episode = episode,
                year = year,
                quality = quality,
                sizeBytes = sizeBytes,
                sizeLabel = sizeLabel,
                pack = pack,
                tags = tags,
                languages = languages,
                releaseTitle = releaseTitle,
                force = force,
            ),
        )
        return post("/api/v1/jobs", body)
    }

    suspend fun enqueueWeb(
        url: String,
        title: String = "",
        imdbId: String = "",
        kind: String = "movie",
        season: Int = 0,
        episode: Int = 0,
        year: Int = 0,
        quality: String = "",
        sizeBytes: Long = 0,
        sizeLabel: String = "",
        pack: String = "",
        tags: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        releaseTitle: String = "",
        force: Boolean = false,
    ): JobItem {
        val body = json.encodeToString(
            EnqueueJobRequest.serializer(),
            EnqueueJobRequest(
                type = "ytdlp",
                url = url,
                title = title,
                imdbId = imdbId,
                kind = kind,
                season = season,
                episode = episode,
                year = year,
                quality = quality,
                sizeBytes = sizeBytes,
                sizeLabel = sizeLabel,
                pack = pack,
                tags = tags,
                languages = languages,
                releaseTitle = releaseTitle,
                force = force,
            ),
        )
        return post("/api/v1/jobs", body)
    }

    suspend fun enqueueDebrid(
        imdbId: String,
        infoHash: String,
        title: String = "",
        kind: String = "movie",
        season: Int = 0,
        episode: Int = 0,
        year: Int = 0,
        quality: String = "",
        sizeBytes: Long = 0,
        sizeLabel: String = "",
        pack: String = "",
        tags: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        releaseTitle: String = "",
        force: Boolean = false,
    ): JobItem {
        val body = json.encodeToString(
            EnqueueJobRequest.serializer(),
            EnqueueJobRequest(
                type = "debrid",
                imdbId = imdbId,
                infoHash = infoHash,
                title = title,
                kind = kind,
                season = season,
                episode = episode,
                year = year,
                quality = quality,
                sizeBytes = sizeBytes,
                sizeLabel = sizeLabel,
                pack = pack,
                tags = tags,
                languages = languages,
                releaseTitle = releaseTitle,
                force = force,
            ),
        )
        return post("/api/v1/jobs", body)
    }

    suspend fun job(id: String): JobItem = get("/api/v1/jobs/$id")

    suspend fun pauseJob(id: String): JobItem = post("/api/v1/jobs/$id/pause", "{}")

    suspend fun cancelJob(id: String): JobItem = post("/api/v1/jobs/$id/cancel", "{}")

    suspend fun retryJob(id: String): JobItem = post("/api/v1/jobs/$id/retry", "{}")

    suspend fun reportEvent(
        type: String,
        message: String,
        mediaId: String = "",
        jobId: String = "",
        sessionId: String = "",
        level: String = "error",
    ) {
        val body = json.encodeToString(
            ClientEventRequest.serializer(),
            ClientEventRequest(
                level = level,
                type = type,
                message = message,
                mediaId = mediaId,
                jobId = jobId,
                sessionId = sessionId,
            ),
        )
        withContext(Dispatchers.IO) {
            val req = request("/api/v1/client/events")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().close()
        }
    }

    suspend fun listSubtitles(
        imdbId: String = "",
        kind: String = "",
        season: Int = 0,
        episode: Int = 0,
        mediaId: String = "",
        query: String = "",
        tmdbId: Int = 0,
    ): SubtitlesListResponse {
        val q = buildString {
            append("/api/v1/subtitles?")
            val parts = mutableListOf<String>()
            if (imdbId.isNotBlank()) parts += "imdbId=${enc(imdbId)}"
            if (kind.isNotBlank()) parts += "kind=${enc(kind)}"
            if (season > 0) parts += "season=$season"
            if (episode > 0) parts += "episode=$episode"
            if (mediaId.isNotBlank()) parts += "mediaId=${enc(mediaId)}"
            if (query.isNotBlank()) parts += "query=${enc(query)}"
            if (tmdbId > 0) parts += "tmdbId=$tmdbId"
            append(parts.joinToString("&"))
        }
        return get(q)
    }

    fun subtitleFileUrl(id: String): String {
        val base = serverUrl.trimEnd('/') + "/api/v1/subtitles/file?id=" + enc(id)
        return if (token.isNotBlank()) "$base&token=${enc(token)}" else base
    }

    private suspend inline fun <reified T> get(path: String): T {
        val req = request(path).get().build()
        return execute(req)
    }

    private suspend inline fun <reified T> post(path: String, body: String): T {
        val req = request(path)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return execute(req)
    }

    private suspend inline fun <reified T> patch(path: String, body: String): T {
        val req = request(path)
            .patch(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return execute(req)
    }

    private suspend inline fun <reified T> execute(req: Request): T = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, text.ifBlank { resp.message })
            }
            json.decodeFromString(text)
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

class ApiException(val code: Int, override val message: String) : RuntimeException(message)
