package tv.coog.app.ui

import androidx.compose.ui.graphics.Color
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.data.StreamCandidate

private val showSuffixRe = Regex("""\s*[·•]\s*S\d{1,2}E\d{1,3}.*""", RegexOption.IGNORE_CASE)
private val releaseTagRe = Regex(
    """(?i)\b(?:2160p|1080p|720p|480p|576p|4k|uhd|hdr10\+?|hdr|hlg|dv|dovi|dolby[\s.]?vision|web[\s.-]?dl|webrip|web|bluray|blu[\s.-]?ray|remux|hybrid|proper|repack|internal|amzn|nf|atvp|dsnp|hulu|hmax|ddp?(?:[.\s]\d(?:\.\d)?)?|atmos|truehd|dts(?:-hd)?|aac(?:[\s.-]lc)?|ac3|eac3|flac|opus|h[\s.-]?265|h[\s.-]?264|x265|x264|hevc|avc|av1|10bit|8bit|hdr10plus|multi|subs?|dubbed|extended|unrated|directors[\s.]?cut|eztv|bigdoc|megusta|blacktv)\b""",
)
private val squareBracketsRe = Regex("\\[[^]]*]")
private val curlyBracketsRe = Regex("\\{[^{}]*\\}")
private val groupTailRe = Regex("""\s[-–]\s*[A-Za-z0-9][A-Za-z0-9._-]*$""")
private val extraSpacesRe = Regex("""\s+""")

fun cleanReleaseName(raw: String): String {
    var s = squareBracketsRe.replace(raw, " ")
    s = curlyBracketsRe.replace(s, " ")
    s = s.replace('【', ' ').replace('】', ' ')
    s = s.replace('.', ' ').replace('_', ' ')
    s = releaseTagRe.replace(s, " ")
    s = groupTailRe.replace(s, "")
    s = extraSpacesRe.replace(s, " ").trim(' ', '-', '.', '·')
    return s
}

fun MediaItem.isTrailer(): Boolean {
    val file = path.substringAfterLast('/').lowercase()
    val stem = file.substringBeforeLast('.')
    return stem == "trailer" || stem.startsWith("trailer-") || title.equals("trailer", ignoreCase = true)
}

fun MediaItem.seriesName(): String {
    val raw = showTitle.ifBlank { title }
    return showSuffixRe.replace(raw, "").trim().ifBlank { "Series" }
}

fun MediaItem.headline(): String = when (kind) {
    "episode" -> seriesName()
    "series" -> cleanReleaseName(title).ifBlank { title }
    else -> cleanReleaseName(title).ifBlank { title }
}

fun MediaItem.episodeHeadline(): String {
    val ep = seasonEpisode()
    val name = episodeName()
    return when {
        ep.isNotBlank() && name.isNotBlank() && !name.equals(ep, ignoreCase = true) -> "$ep  ·  $name"
        ep.isNotBlank() -> ep
        name.isNotBlank() -> name
        else -> headline()
    }
}

fun MediaItem.episodeStillUrl(seriesPoster: String, seriesBackdrop: String = ""): String {
    val poster = posterUrl.trim()
    val backdrop = backdropUrl.trim()
    fun sameArt(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        // Catalog art URLs differ only by ?size=thumb|display but are the same series image.
        fun stripSize(u: String): String = u.substringBefore('?').ifBlank { u }
        return stripSize(a) == stripSize(b)
    }
    return when {
        poster.isNotBlank() && !sameArt(poster, seriesPoster) && !sameArt(poster, seriesBackdrop) -> poster
        backdrop.isNotBlank() && !sameArt(backdrop, seriesBackdrop) && !sameArt(backdrop, seriesPoster) -> backdrop
        else -> ""
    }
}

fun MediaItem.episodeName(): String {
    var s = cleanReleaseName(title)
    val show = cleanReleaseName(seriesName())
    if (show.isNotBlank() && s.startsWith(show, ignoreCase = true)) {
        s = s.substring(show.length).trim(' ', '-', ':', '·')
    }
    s = s.replace(Regex("""(?i)^S\d{1,2}E\d{1,3}\s*"""), "").trim()
    if (s.isBlank() && episode > 0) return "Episode $episode"
    return s
}

fun MediaItem.supporting(): String {
    if (kind == "episode") {
        return listOfNotNull(
            seasonEpisode().ifBlank { null },
            resolutionLabel(),
        ).joinToString("  ·  ")
    }
    if (kind == "series" && episodeCount > 0) {
        return listOfNotNull(
            year.takeIf { it > 0 }?.toString(),
            if (episodeCount == 1) "1 episode" else "$episodeCount episodes",
        ).joinToString("  ·  ")
    }
    return listOfNotNull(
        year.takeIf { it > 0 }?.toString(),
        resolutionLabel(),
        hdrLabel(),
        formatDuration(durationMs),
    ).joinToString("  ·  ")
}

fun MediaItem.seasonEpisode(): String {
    if (season <= 0 && episode <= 0) return ""
    return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}

fun MediaItem.watchFraction(): Float? {
    if (positionMs <= 0L) return null
    val dur = when {
        durationMs > 1_000L -> durationMs
        runtimeMinutes > 0 -> runtimeMinutes * 60_000L
        else -> return null
    }
    return (positionMs.toFloat() / dur.toFloat()).coerceIn(0.04f, 0.96f)
}

fun MediaItem.resolutionLabel(): String? = when {
    width >= 3800 -> "4K"
    width >= 2500 -> "1440p"
    width >= 1800 -> "1080p"
    width >= 1200 -> "720p"
    width > 0 -> "${width}p"
    else -> null
}

fun MediaItem.hdrLabel(): String? = when (hdr.lowercase()) {
    "dolbyvision", "dovi" -> "Dolby Vision"
    "hdr10" -> "HDR10"
    "hlg" -> "HLG"
    else -> hdr.ifBlank { null }?.uppercase()
}

fun MediaItem.techLine(): String = listOfNotNull(
    codecVideo.ifBlank { null }?.uppercase(),
    codecAudio.ifBlank { null }?.uppercase(),
    if (width > 0 && height > 0) "${width}×${height}" else resolutionLabel(),
    hdrLabel(),
    formatDuration(durationMs),
).joinToString("  ·  ")

private val fileMetaTagPriority = setOf(
    "Remux", "BluRay", "WEB", "HEVC", "AVC", "DV", "HDR", "HDR10+", "Atmos",
)

fun formatSizeBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return String.format("%.1f GB", gb)
    val mb = bytes / 1_000_000.0
    if (mb >= 1.0) return String.format("%.0f MB", mb)
    return null
}

fun formatFileMetaLine(
    quality: String = "",
    sizeLabel: String = "",
    sizeBytes: Long = 0,
    pack: String = "",
    tags: List<String> = emptyList(),
    resolution: String? = null,
    hdr: String? = null,
): String {
    val parts = mutableListOf<String>()
    quality.trim().takeIf { it.isNotBlank() }?.let { parts += it }
        ?: resolution?.takeIf { it.isNotBlank() }?.let { parts += it }
    val size = sizeLabel.trim().ifBlank { formatSizeBytes(sizeBytes).orEmpty() }
    if (size.isNotBlank()) parts += size
    tags.filter { it in fileMetaTagPriority }.take(3).forEach { parts += it }
    hdr?.takeIf { it.isNotBlank() && parts.none { p -> p.contains(it, ignoreCase = true) } }?.let { parts += it }
    when (pack.trim().lowercase()) {
        "season" -> parts += "Season pack"
        "multi" -> parts += "Multi-episode"
    }
    return parts.joinToString(" · ")
}

fun JobItem.fileMetaLine(): String = formatFileMetaLine(
    quality = quality,
    sizeLabel = sizeLabel,
    sizeBytes = sizeBytes,
    pack = pack,
    tags = tags,
)

fun StreamCandidate.fileMetaLine(): String = formatFileMetaLine(
    quality = quality,
    sizeLabel = sizeLabel,
    sizeBytes = size,
    pack = pack,
    tags = tags,
)

fun MediaItem.fileMetaLine(): String = formatFileMetaLine(
    quality = fileQuality,
    sizeLabel = fileSizeLabel,
    sizeBytes = sizeBytes,
    pack = filePack,
    tags = fileTags,
    resolution = resolutionLabel(),
    hdr = hdrLabel(),
)

fun formatDuration(ms: Long): String? {
    if (ms < 60_000) return null
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m} min"
}

fun friendlyPlayError(raw: String): String {
    val text = raw.trim()
    val lower = text.lowercase()
    if (text.startsWith("Real-Debrid", ignoreCase = true) && text.length > 48) {
        return text
    }
    return when {
        "451" in lower || "infringing" in lower || "blocklist" in lower ->
            "Real-Debrid blocked this torrent: the filename or hash is on their blocklist. Pick another source — Cached / RD+ usually work."
        lower.contains("invalid_token") || lower.contains("bad_token") ||
            (lower.contains("real-debrid") && "401" in lower) ->
            "Real-Debrid rejected the API token. Update it in Settings."
        lower.contains("traffic") && lower.contains("real-debrid") ->
            "Real-Debrid traffic limit reached. Wait for reset or upgrade the account."
        lower.contains("real-debrid") ->
            "Real-Debrid could not start this file. Try another source."
        else -> text
    }
}

fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun playbackDurationMs(exoDuration: Long, expectedMs: Long, bufferedMs: Long, streaming: Boolean): Long {
    val exo = if (exoDuration > 0) exoDuration else 0L
    if (expectedMs > 0) {
        return maxOf(expectedMs, exo)
    }
    if (streaming && exo > 0 && bufferedMs > 0 && exo <= bufferedMs + 5_000L) {
        return 0L
    }
    return exo
}

fun MediaItem.posterColors(): Pair<Color, Color> {
    val palette = listOf(
        Color(0xFF1D4E89) to Color(0xFF0B1F33),
        Color(0xFF6B2D5B) to Color(0xFF241018),
        Color(0xFF2F6F4E) to Color(0xFF102018),
        Color(0xFF8A4B12) to Color(0xFF2A1608),
        Color(0xFF3F3D9B) to Color(0xFF12122A),
        Color(0xFF7A2F2F) to Color(0xFF220E0E),
        Color(0xFF1F6F73) to Color(0xFF0C2426),
        Color(0xFF4A5D23) to Color(0xFF161C0C),
    )
    val idx = (id.hashCode() and 0x7FFFFFFF) % palette.size
    return palette[idx]
}

fun MediaItem.monogram(): String {
    val src = headline().trim()
    val parts = src.split(Regex("""[\s._-]+""")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        src.isNotEmpty() -> src.take(2).uppercase()
        else -> "CO"
    }
}

fun MediaItem.libraryBucket(): String {
    val marker = "/Videos/"
    val idx = path.indexOf(marker, ignoreCase = true)
    if (idx >= 0) {
        val rest = path.substring(idx + marker.length)
        return rest.substringBefore('/').ifBlank { "Videos" }
    }
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return parts.getOrNull(parts.size - 3) ?: parts.getOrNull(parts.size - 2) ?: "Videos"
}

data class ShowRow(
    val name: String,
    val episodes: List<MediaItem>,
    val header: MediaItem? = null,
    val seasons: List<tv.coog.app.data.SeasonInfo> = emptyList(),
) {
    val cover: MediaItem get() = header ?: episodes.firstOrNull() ?: MediaItem(id = "", kind = "series", title = name)

    val totalEpisodeCount: Int
        get() = seasons.sumOf { it.episodeCount }.takeIf { it > 0 }
            ?: cover.episodeCount.takeIf { it > 0 }
            ?: episodes.size

    fun asFeaturedItem(): MediaItem = cover.copy(
        kind = "series",
        title = name.ifBlank { cover.title },
        showTitle = name.ifBlank { cover.showTitle },
        episodeCount = totalEpisodeCount,
        season = 0,
        episode = 0,
    )
    val subtitle: String get() {
        val seasonCount = seasons.count { it.number > 0 }.takeIf { it > 0 }
            ?: episodes.map { it.season }.filter { it > 0 }.distinct().size
        val n = totalEpisodeCount
        val local = episodes.count { it.isLocal() }
        return when {
            local > 0 && local < n && seasons.isEmpty() -> "$local of $n on disk"
            local > 0 && seasons.isNotEmpty() -> {
                val localSeasons = episodes.filter { it.isLocal() }.map { it.season }.distinct().size
                if (localSeasons > 0) "$local on disk  ·  $seasonCount seasons" else "$seasonCount seasons  ·  $n episodes"
            }
            seasonCount > 1 -> "$seasonCount seasons  ·  $n episodes"
            else -> "$n episodes"
        }
    }
}

/** Replace one season's episodes in an accumulated show list. */
fun mergeSeasonIntoShow(existing: List<MediaItem>, seasonEps: List<MediaItem>, season: Int): List<MediaItem> {
    val kept = existing.filter { it.season != season }
    return (kept + seasonEps).sortedWith(compareBy({ it.season }, { it.episode }, { it.title }))
}

fun mergeShowEpisodes(catalog: List<MediaItem>, local: List<MediaItem>): List<MediaItem> {
    if (catalog.isEmpty()) return local
    if (local.isEmpty()) return catalog
    val localBySE = LinkedHashMap<Pair<Int, Int>, MediaItem>()
    for (ep in local) {
        if (ep.diskMediaId().isBlank() && ep.path.isBlank()) continue
        localBySE.putIfAbsent(ep.season to ep.episode, ep)
    }
    val seen = HashSet<Pair<Int, Int>>()
    val out = ArrayList<MediaItem>(catalog.size + local.size)
    for (ep in catalog) {
        val key = ep.season to ep.episode
        seen.add(key)
        val loc = localBySE[key]
        out.add(
            if (loc == null) ep
            else ep.copy(
                inLibrary = true,
                libraryId = loc.diskMediaId().ifBlank { loc.libraryId.ifBlank { loc.id } },
                path = loc.path.ifBlank { ep.path },
            ),
        )
    }
    local.filter { (it.season to it.episode) !in seen && (it.diskMediaId().isNotBlank() || it.path.isNotBlank()) }
        .sortedWith(compareBy({ it.season }, { it.episode }, { it.title }))
        .forEach { out.add(it) }
    return out
}

data class FolderRow(
    val name: String,
    val items: List<MediaItem>,
) {
    val cover: MediaItem get() = items.first()
    val subtitle: String get() {
        val n = items.size
        return if (n == 1) "1 video" else "$n videos"
    }
}

fun List<MediaItem>.movieItems(): List<MediaItem> {
    val showNames = showRows().map { it.name }
    return filter { item ->
        if (item.kind != "movie") return@filter false
        if (item.season > 0 || item.episode > 0 || item.showTitle.isNotBlank()) return@filter false
        if (looksLikeEpisodeFile(item)) return@filter false
        showNames.none { matchesShowTitle(item.headline(), it) }
    }.sortedBy { it.headline().lowercase() }
}

fun overlayCatalog(local: List<MediaItem>, catalog: List<MediaItem>): List<MediaItem> {
    if (local.isEmpty() || catalog.isEmpty()) return local
    return local.map { item ->
        if (item.imdbId.isNotBlank() || item.tmdbId != 0) item
        else {
            val want = normalizeBrowseTitle(item.headline())
            if (want.isBlank()) item
            else {
                val hits = catalog.filter { normalizeBrowseTitle(it.headline()) == want }
                val hit = hits.firstOrNull { item.year == 0 || it.year == 0 || it.year == item.year }
                    ?: hits.singleOrNull()
                if (hit != null) mergeDetails(item, hit) else item
            }
        }
    }
}

fun matchesShowTitle(title: String, showName: String): Boolean {
    val a = normalizeBrowseTitle(title)
    val b = normalizeBrowseTitle(showName)
    return a.isNotBlank() && b.isNotBlank() && (a == b || a.startsWith("$b "))
}

private val yearSuffixRe = Regex("""\s*\(\d{4}\)\s*$""")

fun normalizeBrowseTitle(raw: String): String =
    yearSuffixRe.replace(cleanReleaseName(raw), "")
        .replace('’', '\'')
        .replace('‘', '\'')
        .lowercase()
        .trim()

private val episodePathRe = Regex("""(?i)(?:[/\\]season\s*\d+|s\d{1,2}e\d{1,3})""")

private fun looksLikeEpisodeFile(item: MediaItem): Boolean =
    episodePathRe.containsMatchIn("${item.path} ${item.title}")

fun List<MediaItem>.showRows(): List<ShowRow> =
    filter { it.kind == "episode" && !it.isTrailer() }
        .groupBy { it.seriesName() }
        .map { (name, eps) ->
            ShowRow(
                name = name,
                episodes = eps.sortedWith(compareBy({ it.season }, { it.episode }, { it.title })),
            )
        }
        .sortedBy { it.name.lowercase() }

/** Movies + one card per series (episodes collapsed) for the Library grid. */
fun List<MediaItem>.libraryBrowseItems(): List<MediaItem> {
    val shows = showRows()
    val showCards = shows.map { it.asFeaturedItem() }
    val movies = movieItems()
    val episodeKeys = shows.flatMap { row ->
        row.episodes.map { it.playableId().ifBlank { it.id } }
    }.toHashSet()
    val other = filter { item ->
        if (item.isTrailer()) return@filter false
        when (item.kind) {
            "movie", "episode", "series" -> false
            else -> item.playableId().ifBlank { item.id } !in episodeKeys
        }
    }
    return (movies + showCards + other).sortedBy { it.headline().lowercase() }
}

fun List<MediaItem>.folderRows(): List<FolderRow> =
    filter { it.kind != "movie" && it.kind != "episode" && !it.isTrailer() }
        .groupBy { it.libraryBucket() }
        .filterKeys { it.lowercase() !in setOf("movies", "series", "maize") }
        .map { (name, files) ->
            FolderRow(
                name = name,
                items = files.sortedBy { it.headline().lowercase() },
            )
        }
        .sortedBy { it.name.lowercase() }

fun MediaItem.kindLabel(): String = when (kind) {
    "movie" -> "Movie"
    "episode", "series" -> "Series"
    else -> "Video"
}

fun MediaItem.hasOfficialMeta(): Boolean = when (matchStatus.lowercase()) {
    "unmatched", "ignored", "suggested" -> false
    "matched" -> true
    else -> path.isBlank() || imdbId.isNotBlank()
}

fun MediaItem.heroGenres(): List<String> =
    if (!hasOfficialMeta()) emptyList()
    else genres.map { it.trim() }.filter { it.isNotBlank() }.take(3)

fun MediaItem.heroMetaLine(): String {
    if (!hasOfficialMeta() && year <= 0 && durationMs <= 0 && runtimeMinutes <= 0 && studio.isBlank() && !isLocal()) {
        return ""
    }
    val runtime = when {
        runtimeMinutes > 0 -> formatDuration(runtimeMinutes * 60_000L)
        durationMs > 0 -> formatDuration(durationMs)
        else -> null
    }
    val base = listOfNotNull(
        studio.takeIf { it.isNotBlank() },
        country.takeIf { hasOfficialMeta() && it.isNotBlank() && !it.equals(studio, ignoreCase = true) },
        year.takeIf { it > 0 }?.toString(),
        certification.takeIf { hasOfficialMeta() && it.isNotBlank() },
        runtime,
    ).joinToString("  ·  ")
    val file = if (isLocal()) fileMetaLine() else ""
    return listOfNotNull(base.takeIf { it.isNotBlank() }, file.takeIf { it.isNotBlank() })
        .joinToString("  ·  ")
}

fun MediaItem.cardMetaLine(): String {
    val runtime = when {
        runtimeMinutes > 0 -> formatDuration(runtimeMinutes * 60_000L)
        durationMs > 0 -> formatDuration(durationMs)
        else -> null
    }
    return listOfNotNull(
        year.takeIf { it > 0 }?.toString(),
        certification.takeIf { hasOfficialMeta() && it.isNotBlank() },
        runtime,
    ).joinToString("  ·  ")
}

fun MediaItem.heroSubtitle(): String {
    if (!hasOfficialMeta()) return ""
    return tagline.trim()
}

fun MediaItem.heroDescription(): String {
    if (!hasOfficialMeta()) return ""
    val plot = plot.trim()
    val tag = heroSubtitle()
    if (plot.isBlank()) return ""
    if (tag.isNotBlank() && plot.equals(tag, ignoreCase = true)) return ""
    return plot
}

fun MediaItem.heroChips(rowLabel: String = kindLabel(), jobs: List<tv.coog.app.data.JobItem> = emptyList()): List<String> {
    val chips = mutableListOf<String>()
    if (rowLabel.isNotBlank()) chips.add(rowLabel)
    year.takeIf { it > 0 }?.toString()?.let { chips.add(it) }
    formatDuration(durationMs)?.let { chips.add(it) }
    if (hasOfficialMeta()) chips.addAll(genres.map { it.trim() }.filter { it.isNotBlank() }.take(3))
    return chips
}

enum class CardMarkKind {
    Local, Partial, Fetching, Downloading, Ready, Paused, Theatrical, ComingSoon, Failed
}

data class CardMark(
    val kind: CardMarkKind,
    val progress: Float = 0f,
    val have: Int = 0,
    val total: Int = 0,
) {
    val shortLabel: String
        get() = when (kind) {
            CardMarkKind.Local -> "Local"
            CardMarkKind.Partial -> when {
                total > 0 -> "$have/$total"
                have > 0 -> "$have"
                else -> "Some"
            }
            CardMarkKind.Fetching -> "Wait"
            CardMarkKind.Downloading -> "Save"
            CardMarkKind.Ready -> "Ready"
            CardMarkKind.Paused -> "Pause"
            CardMarkKind.Theatrical -> "Cinema"
            CardMarkKind.ComingSoon -> "Soon"
            CardMarkKind.Failed -> "Fail"
        }
}

fun tv.coog.app.data.JobItem.transferProgress(): Float {
    if (expectedDurationMs > 0 && bufferedMs > 0) {
        return (bufferedMs.toFloat() / expectedDurationMs.toFloat()).coerceIn(0f, 1f)
    }
    return progress.toFloat().coerceIn(0f, 1f)
}

fun tv.coog.app.data.JobItem.toCardMark(): CardMark = when (status) {
    "finished" -> CardMark(CardMarkKind.Local)
    "error", "cancelled" -> CardMark(CardMarkKind.Failed)
    "queued" -> CardMark(CardMarkKind.Fetching)
    "paused" -> CardMark(CardMarkKind.Paused, transferProgress())
    else -> {
        val frac = transferProgress()
        if (ready) CardMark(CardMarkKind.Ready, frac) else CardMark(CardMarkKind.Downloading, frac)
    }
}

fun MediaItem.cardMark(
    jobs: List<tv.coog.app.data.JobItem> = emptyList(),
    library: List<MediaItem> = emptyList(),
    episodes: List<MediaItem> = emptyList(),
): CardMark? {
    if (kind == "series") {
        val peers = episodes.ifEmpty { library.libraryEpisodesFor(this) }
        val catalogTotal = when {
            episodes.isNotEmpty() -> episodes.size
            episodeCount > 0 -> episodeCount
            else -> peers.maxOfOrNull { it.episodeCount } ?: 0
        }
        collectionMark(peers, jobs, catalogTotal)?.let { return it }
        return when (releasePhase) {
            "coming_soon" -> CardMark(CardMarkKind.ComingSoon)
            "theatrical" -> CardMark(CardMarkKind.Theatrical)
            else -> null
        }
    }
    val active = matchingJob(jobs.filter { it.status != "finished" && it.status != "cancelled" })
    if (active != null) return active.toCardMark()
    if (isLocal() || matchingJob(jobs.filter { it.status == "finished" || it.mediaId.isNotBlank() }) != null) {
        return CardMark(CardMarkKind.Local)
    }
    return when (releasePhase) {
        "coming_soon" -> CardMark(CardMarkKind.ComingSoon)
        "theatrical" -> CardMark(CardMarkKind.Theatrical)
        else -> null
    }
}

fun ShowRow.cardMark(jobs: List<tv.coog.app.data.JobItem> = emptyList()): CardMark? {
    val catalogTotal = header?.episodeCount?.takeIf { it > 0 }
        ?: episodes.maxOfOrNull { it.episodeCount }?.takeIf { it > 0 }
        ?: 0
    return collectionMark(episodes, jobs, catalogTotal)
}

fun collectionMark(
    episodes: List<MediaItem>,
    jobs: List<tv.coog.app.data.JobItem>,
    catalogTotal: Int = 0,
): CardMark? {
    if (episodes.isEmpty()) return null
    val live = jobs.filter { it.status != "finished" && it.status != "cancelled" }
    val transfers = episodes.mapNotNull { it.matchingJob(live) }.filter { it.status != "error" }
    preferredTransfer(transfers)?.let { return it.toCardMark() }
    val local = episodes.count { it.isLocal() }
    val total = catalogTotal
    if (local > 0 && total > 0 && local >= total) return CardMark(CardMarkKind.Local)
    if (local > 0) {
        val denom = if (total > 0) total else local
        return CardMark(
            kind = CardMarkKind.Partial,
            progress = (local.toFloat() / denom.toFloat()).coerceIn(0.04f, 1f),
            have = local,
            total = total,
        )
    }
    if (episodes.any { it.matchingJob(live)?.status == "error" }) {
        return CardMark(CardMarkKind.Failed)
    }
    return null
}

fun seasonMark(
    episodes: List<MediaItem>,
    season: Int,
    jobs: List<tv.coog.app.data.JobItem>,
): CardMark? = collectionMark(
    episodes.filter { it.season == season },
    jobs,
    catalogTotal = episodes.count { it.season == season },
)

private fun preferredTransfer(jobs: List<tv.coog.app.data.JobItem>): tv.coog.app.data.JobItem? =
    jobs.firstOrNull { it.ready || it.status == "ready" }
        ?: jobs.firstOrNull { it.status == "downloading" }
        ?: jobs.firstOrNull { it.status == "queued" }
        ?: jobs.firstOrNull { it.status == "paused" }

fun List<MediaItem>.libraryEpisodesFor(item: MediaItem): List<MediaItem> {
    val imdb = item.imdbId.trim()
    val name = item.seriesName().ifBlank { item.headline() }
    return filter { ep ->
        if (ep.kind != "episode") return@filter false
        when {
            imdb.isNotBlank() && ep.imdbId.equals(imdb, ignoreCase = true) -> true
            name.isNotBlank() && matchesShowTitle(ep.seriesName(), name) -> true
            else -> false
        }
    }
}

fun MediaItem.matchPercent(): Int? {
    // Only show Match when household taste scored this title — never disguise rating as Match.
    if (tasteMatch > 0) return tasteMatch.coerceIn(1, 99)
    return null
}

private val jobEpisodeRe = Regex("""(?i)(?<![A-Z0-9])S(\d{1,2})E(\d{1,3})(?![A-Z0-9])""")

fun tv.coog.app.data.JobItem.seasonEpisode(): Pair<Int, Int>? {
    val raw = url.trim()
    if (raw.startsWith("imdb:", ignoreCase = true)) {
        val parts = raw.substringAfter(":").split(":")
        if (parts.size >= 3) {
            val season = parts[1].toIntOrNull()
            val episode = parts[2].toIntOrNull()
            if (season != null && episode != null && episode > 0) return season to episode
        }
    }
    jobEpisodeRe.find(title)?.let { hit ->
        return hit.groupValues[1].toInt() to hit.groupValues[2].toInt()
    }
    jobEpisodeRe.find(raw)?.let { hit ->
        return hit.groupValues[1].toInt() to hit.groupValues[2].toInt()
    }
    return null
}

fun MediaItem.matchingJob(jobs: List<tv.coog.app.data.JobItem>): tv.coog.app.data.JobItem? {
    if (jobs.isEmpty()) return null
    val imdb = imdbId.trim()
    if (imdb.isBlank()) return null
    val pool = jobs.filter { it.imdbId.equals(imdb, ignoreCase = true) }
    if (pool.isEmpty()) return null
    if (kind == "episode" && episode > 0) {
        val want = season to episode
        return pool.firstOrNull { it.seasonEpisode() == want }
    }
    if (kind == "series") {
        return pool.firstOrNull { it.seasonEpisode() == null }
            ?: pool.firstOrNull()
    }
    return pool.firstOrNull { it.seasonEpisode() == null } ?: pool.firstOrNull()
}

fun MediaItem.withLibraryFromJobs(jobs: List<tv.coog.app.data.JobItem>): MediaItem {
    if (isLocal()) return this
    val done = matchingJob(jobs.filter { it.status == "finished" || (it.mediaId.isNotBlank() && it.status != "error" && it.status != "cancelled") })
        ?: return this
    val media = done.mediaId
    if (media.isBlank()) return this
    return copy(inLibrary = true, libraryId = media)
}

fun MediaItem.episodeStatusLine(jobs: List<tv.coog.app.data.JobItem>): String? {
    val job = matchingJob(jobs.filter { it.status != "cancelled" }) ?: return null
    return when (job.status) {
        "finished" -> null
        "error" -> job.error.ifBlank { "Failed" }
        else -> job.remainingLabel() ?: job.subtitle().takeIf { it.contains("/") }
    }
}

fun MediaItem.posterBadgeLabel(jobs: List<tv.coog.app.data.JobItem> = emptyList()): String? =
    cardMark(jobs)?.shortLabel

fun List<MediaItem>.librarySummary(): String {
    val movies = movieItems().size
    val shows = showRows().size
    val folders = folderRows()
    val extra = folders.sumOf { it.items.size }
    val parts = buildList {
        if (movies > 0) add(if (movies == 1) "1 movie" else "$movies movies")
        if (shows > 0) add(if (shows == 1) "1 series" else "$shows series")
        if (extra > 0) add("$extra videos")
    }
    return if (parts.isEmpty()) "Library ready" else parts.joinToString("  ·  ") + " ready to play"
}

/** Torrentio-style country flags for audio languages. */
fun languageFlagEmojis(codes: List<String>): List<String> {
    val out = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (raw in codes) {
        val flag = languageFlagEmoji(raw) ?: continue
        if (seen.add(flag)) out += flag
    }
    return out
}

fun languageFlagCluster(codes: List<String>): String = languageFlagEmojis(codes).joinToString(" ")

fun languageExtraLabels(codes: List<String>): List<String> {
    val lower = codes.map { it.trim().lowercase() }.toSet()
    val out = mutableListOf<String>()
    if ("dual" in lower) out += "Dual Audio"
    if ("multi" in lower) out += "Multi Audio"
    if ("subs" in lower) out += "Multi Subs"
    if ("dubbed" in lower) out += "Dubbed"
    return out
}

fun StreamCandidate.audioLanguageFlags(): String =
    languageFlags.distinct().joinToString(" ").ifBlank { languageFlagCluster(languages) }

private fun languageFlagEmoji(code: String): String? = when (code.trim().lowercase()) {
    "en", "eng", "english" -> "🇬🇧"
    "ja", "jp", "jpn", "japanese" -> "🇯🇵"
    "ru", "rus", "russian" -> "🇷🇺"
    "it", "ita", "italian" -> "🇮🇹"
    "pt", "por", "portuguese" -> "🇵🇹"
    "es", "spa", "spanish" -> "🇪🇸"
    "latino" -> "🇲🇽"
    "ko", "kor", "korean" -> "🇰🇷"
    "zh", "chi", "chinese" -> "🇨🇳"
    "tw", "taiwanese" -> "🇹🇼"
    "fr", "fra", "fre", "french" -> "🇫🇷"
    "de", "ger", "deu", "german" -> "🇩🇪"
    "nl", "dutch" -> "🇳🇱"
    "hi", "hin", "hindi", "te", "tel", "telugu", "ta", "tam", "tamil" -> "🇮🇳"
    "pl", "pol", "polish" -> "🇵🇱"
    "lt", "lit", "lithuanian" -> "🇱🇹"
    "lv", "lav", "latvian" -> "🇱🇻"
    "et", "estonian" -> "🇪🇪"
    "cs", "cze", "czech" -> "🇨🇿"
    "sk", "slovak", "slovakian" -> "🇸🇰"
    "sl", "slovenian" -> "🇸🇮"
    "hu", "hun", "hungarian" -> "🇭🇺"
    "ro", "ron", "romanian" -> "🇷🇴"
    "bg", "bul", "bulgarian" -> "🇧🇬"
    "sr", "srp", "serbian" -> "🇷🇸"
    "hr", "hrv", "croatian" -> "🇭🇷"
    "uk", "ukr", "ukrainian" -> "🇺🇦"
    "el", "gre", "greek" -> "🇬🇷"
    "da", "dan", "danish" -> "🇩🇰"
    "fi", "fin", "finnish" -> "🇫🇮"
    "sv", "swe", "swedish", "nordic" -> "🇸🇪"
    "no", "nor", "norwegian" -> "🇳🇴"
    "tr", "tur", "turkish" -> "🇹🇷"
    "ar", "ara", "arabic" -> "🇸🇦"
    "fa", "fas", "persian" -> "🇮🇷"
    "he", "heb", "hebrew" -> "🇮🇱"
    "vi", "vie", "vietnamese" -> "🇻🇳"
    "id", "ind", "indonesian" -> "🇮🇩"
    "ms", "malay" -> "🇲🇾"
    "th", "tha", "thai" -> "🇹🇭"
    else -> null
}
