package tv.coog.app.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SubCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class SubtitleSyncResult(
    val delayMs: Int,
    /** 0..1 — below ~0.40 treat as unreliable and keep delay 0. */
    val confidence: Float,
)

/**
 * Parse SubRip (and lightly tolerant VTT) into timed cues.
 * Used for overlay playback so ExoPlayer does not re-prepare the video.
 */
fun parseSubtitleText(raw: String): List<SubCue> {
    val text = raw.trimStart('\uFEFF').replace("\r\n", "\n").replace('\r', '\n')
    if (text.isBlank()) return emptyList()
    val body = if (text.startsWith("WEBVTT", ignoreCase = true)) {
        text.substringAfter('\n')
    } else {
        text
    }
    val blocks = body.split(Regex("\n{2,}"))
    val out = ArrayList<SubCue>(blocks.size)
    val timeLine = Regex(
        """(\d{1,2}:)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})""",
    )
    for (block in blocks) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        var idx = 0
        if (lines[0].all { it.isDigit() }) idx = 1
        if (idx >= lines.size) continue
        val match = timeLine.find(lines[idx]) ?: continue
        val start = parseTimestamp(
            match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4],
        )
        val end = parseTimestamp(
            match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8],
        )
        if (end <= start) continue
        val payload = lines.drop(idx + 1)
            .joinToString("\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()
        if (payload.isBlank()) continue
        out.add(SubCue(start, end, payload))
    }
    return out.sortedBy { it.startMs }
}

private fun parseTimestamp(h: String, m: String, s: String, ms: String): Long {
    val hours = h.trimEnd(':').toLongOrNull() ?: 0L
    val minutes = m.toLongOrNull() ?: 0L
    val seconds = s.toLongOrNull() ?: 0L
    val millis = ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
}

/**
 * Lines for the cue active at [positionMs] (after [delayMs]).
 * If several cues overlap in the file, keep only the newest — stacking two dialogues
 * looks like double subtitles.
 */
fun activeOverlayLines(cues: List<SubCue>, positionMs: Long, delayMs: Int): List<String> {
    if (cues.isEmpty()) return emptyList()
    val t = positionMs - delayMs
    var lo = 0
    var hi = cues.lastIndex
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].startMs <= t) {
            idx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (idx < 0) return emptyList()
    var i = idx
    while (i >= 0) {
        val hit = cues[i]
        if (hit.startMs > t) {
            i--
            continue
        }
        if (t < hit.endMs) {
            return hit.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        i--
        if (idx - i > 8) break
    }
    return emptyList()
}

/**
 * Estimate subtitle delay so [external] aligns with [local] reference (usually a sidecar).
 *
 * Display uses `position - delay`, so delay = local.start - external.start for a matched pair.
 *
 * Signals (strongest first):
 * 1. Shared proper names / untranslated tokens across the two files
 * 2. Inter-cue gap-window correlation
 * 3. Median of timing votes refined around those seeds
 */
fun estimateSubtitleDelayMs(
    local: List<SubCue>,
    external: List<SubCue>,
    sameLanguage: Boolean = false,
    searchRangeMs: Int = 45_000,
): SubtitleSyncResult {
    if (local.size < 6 || external.size < 6) {
        return SubtitleSyncResult(0, 0f)
    }
    val localUse = sliceForSync(local)
    val externalUse = sliceForSync(external)
    if (localUse.size < 6 || externalUse.size < 6) {
        return SubtitleSyncResult(0, 0f)
    }

    val nameAlign = alignBySharedNames(localUse, externalUse, searchRangeMs)
    val seeds = linkedSetOf<Int>()
    seeds += 0
    if (nameAlign != null) seeds += nameAlign.delayMs
    alignByGapWindows(localUse, externalUse)?.let { seeds += it }
    for (d in -searchRangeMs..searchRangeMs step 2_500) seeds += d

    var best = SubtitleSyncResult(0, 0f)
    // Name-only result is already a strong candidate.
    if (nameAlign != null && nameAlign.confidence >= 0.35f) {
        best = nameAlign
    }

    for (seed in seeds) {
        if (seed !in -searchRangeMs..searchRangeMs) continue
        val got = refineByMedianVotes(localUse, externalUse, seed, sameLanguage, searchRangeMs)
        if (isBetter(got, best)) best = got
    }

    // Prefer name median when timing refine is weak but names agreed.
    if (nameAlign != null &&
        nameAlign.confidence >= 0.4f &&
        (best.confidence < 0.45f || abs(best.delayMs - nameAlign.delayMs) <= 250)
    ) {
        if (isBetter(nameAlign, best) || best.confidence < 0.45f) {
            best = nameAlign
        }
    }

    if (best.confidence < 0.40f) {
        return SubtitleSyncResult(0, best.confidence)
    }
    if (abs(best.delayMs) < 80) {
        return SubtitleSyncResult(0, best.confidence)
    }
    return SubtitleSyncResult(best.delayMs.coerceIn(-30_000, 30_000), best.confidence)
}

private fun isBetter(candidate: SubtitleSyncResult, current: SubtitleSyncResult): Boolean {
    if (candidate.confidence > current.confidence + 0.03f) return true
    if (abs(candidate.confidence - current.confidence) <= 0.03f &&
        candidate.confidence >= current.confidence &&
        abs(candidate.delayMs) < abs(current.delayMs)
    ) {
        return true
    }
    return false
}

/**
 * Names are often left untranslated ("Shrek", "Fiona"). Tokens that appear in both
 * files with capitals are paired by occurrence order and vote a constant delay.
 */
private fun alignBySharedNames(
    local: List<SubCue>,
    external: List<SubCue>,
    searchRangeMs: Int,
): SubtitleSyncResult? {
    val localIdx = indexProperNames(local)
    val extIdx = indexProperNames(external)
    if (localIdx.isEmpty() || extIdx.isEmpty()) return null

    val votes = ArrayList<Int>(64)
    var rarePairs = 0
    for (name in localIdx.keys) {
        val extHits = extIdx[name] ?: continue
        val localHits = localIdx.getValue(name)
        // Very frequent tokens are usually not character names.
        if (localHits.size > 12 || extHits.size > 12) continue
        val n = min(localHits.size, extHits.size)
        if (n <= 0) continue
        val rare = localHits.size <= 2 && extHits.size <= 2
        val weight = when {
            localHits.size == 1 && extHits.size == 1 -> 4
            rare -> 2
            else -> 1
        }
        for (i in 0 until n) {
            val d = (localHits[i] - extHits[i]).toInt()
            if (d !in -searchRangeMs..searchRangeMs) continue
            repeat(weight) { votes += d }
            if (rare) rarePairs++
        }
    }
    if (votes.size < 4) return null

    val delay = medianInt(votes).coerceIn(-searchRangeMs, searchRangeMs)
    val inliers = votes.count { abs(it - delay) <= 220 }
    if (inliers < 4) return null
    val refined = medianInt(votes.filter { abs(it - delay) <= 220 })
    // Confidence from inlier fraction, boosted when rare 1:1 name pairs agree.
    val base = inliers.toFloat() / votes.size.toFloat()
    val boost = (rarePairs.toFloat() / 12f).coerceAtMost(0.25f)
    val confidence = (base * 0.85f + boost).coerceIn(0f, 1f)
    return SubtitleSyncResult(refined, confidence)
}

private val NAME_TOKEN = Regex("""[\p{L}][\p{L}\p{M}'’-]{2,}""")

/** Lowercase forms that show up capitalized but are not useful anchors. */
private val NAME_STOPWORDS = setOf(
    "the", "and", "but", "for", "you", "your", "yours", "with", "from", "this", "that",
    "what", "when", "where", "which", "while", "who", "whom", "whose", "will", "would",
    "could", "should", "about", "into", "over", "under", "then", "than", "them", "they",
    "their", "there", "here", "have", "has", "had", "were", "was", "are", "been", "being",
    "not", "yes", "yeah", "okay", "hey", "hi", "hello", "thanks", "thank", "please",
    "mister", "miss", "missus", "sir", "madam", "doctor", "captain", "professor",
    "english", "spanish", "french", "german", "chinese", "japanese", "korean", "russian",
    "subtitle", "subtitles", "opening", "credits", "continue", "watching",
    "www", "http", "https", "com", "net", "org", "opensubtitles", "yify", "sparkle",
    "webrip", "bluray", "hdtv", "proper", "repack", "internal",
)

private fun indexProperNames(cues: List<SubCue>): Map<String, List<Long>> {
    val map = LinkedHashMap<String, MutableList<Long>>()
    for (cue in cues) {
        val cleaned = cue.text.replace(Regex("<[^>]+>"), " ")
        val seenInCue = HashSet<String>()
        for (match in NAME_TOKEN.findAll(cleaned)) {
            val raw = match.value
            if (!raw.any { it.isUpperCase() }) continue
            val key = raw.lowercase()
            if (key.length < 3) continue
            if (key in NAME_STOPWORDS) continue
            // Skip all-caps noise like "OMG" / "LOL" (keep longer ALLCAPS names).
            if (raw.length <= 4 && raw.all { !it.isLetter() || it.isUpperCase() } &&
                raw.none { it.isLowerCase() }
            ) {
                continue
            }
            if (!seenInCue.add(key)) continue
            map.getOrPut(key) { ArrayList(4) }.add(cue.startMs)
        }
    }
    return map
}

private fun refineByMedianVotes(
    local: List<SubCue>,
    external: List<SubCue>,
    seed: Int,
    sameLanguage: Boolean,
    searchRangeMs: Int,
): SubtitleSyncResult {
    var delay = seed.coerceIn(-searchRangeMs, searchRangeMs)
    var votes = emptyList<Int>()
    repeat(4) {
        votes = collectDelayVotes(local, external, delay, sameLanguage)
        if (votes.size < 5) {
            return SubtitleSyncResult(0, votes.size.toFloat() / local.size.toFloat())
        }
        delay = medianInt(votes).coerceIn(-searchRangeMs, searchRangeMs)
    }
    val inlierTol = 180
    val inliers = votes.filter { abs(it - delay) <= inlierTol }
    if (inliers.size < 5) {
        return SubtitleSyncResult(0, inliers.size.toFloat() / local.size.toFloat())
    }
    delay = medianInt(inliers).coerceIn(-searchRangeMs, searchRangeMs)
    val confidence = (inliers.size.toFloat() / local.size.toFloat()).coerceIn(0f, 1f)
    return SubtitleSyncResult(delay, confidence)
}

private fun collectDelayVotes(
    local: List<SubCue>,
    external: List<SubCue>,
    delay: Int,
    sameLanguage: Boolean,
): List<Int> {
    val extStarts = LongArray(external.size) { external[it].startMs }
    val votes = ArrayList<Int>(local.size)
    for (l in local) {
        val want = l.startMs - delay
        val j = nearestIndex(extStarts, want)
        val e = external[j]
        val dt = abs(e.startMs - want)
        if (dt > 800) continue
        val durL = (l.endMs - l.startMs).coerceAtLeast(1)
        val durE = (e.endMs - e.startMs).coerceAtLeast(1)
        val durRatio = min(durL, durE).toDouble() / max(durL, durE)
        if (durRatio < 0.4) continue
        if (sameLanguage) {
            val lenL = l.text.replace("\n", "").length.coerceAtLeast(1)
            val lenE = e.text.replace("\n", "").length.coerceAtLeast(1)
            val lenRatio = min(lenL, lenE).toDouble() / max(lenL, lenE)
            if (lenRatio < 0.4) continue
        }
        votes += (l.startMs - e.startMs).toInt()
    }
    return votes
}

private fun medianInt(values: List<Int>): Int {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        ((sorted[mid - 1].toLong() + sorted[mid].toLong()) / 2L).toInt()
    } else {
        sorted[mid]
    }
}

private fun alignByGapWindows(local: List<SubCue>, external: List<SubCue>): Int? {
    val localGaps = gapVector(local) ?: return null
    val extGaps = gapVector(external) ?: return null
    val window = min(12, min(localGaps.size, extGaps.size))
    if (window < 6) return null

    data class Hit(val corr: Double, val delay: Int)
    val hits = ArrayList<Hit>(32)
    // Cap work on very large packs — sample every 2nd gap start.
    val localStep = if (localGaps.size > 400) 2 else 1
    val extStep = if (extGaps.size > 400) 2 else 1
    var li = 0
    while (li <= localGaps.size - window) {
        val localWin = localGaps.copyOfRange(li, li + window)
        var ei = 0
        while (ei <= extGaps.size - window) {
            val extWin = extGaps.copyOfRange(ei, ei + window)
            val corr = pearson(localWin, extWin)
            if (corr >= 0.94) {
                hits += Hit(corr, (local[li].startMs - external[ei].startMs).toInt())
            }
            ei += extStep
        }
        li += localStep
    }
    if (hits.isEmpty()) return null
    hits.sortByDescending { it.corr }
    val top = hits.take(min(9, hits.size))
    return medianInt(top.map { it.delay })
}

private fun gapVector(cues: List<SubCue>): DoubleArray? {
    if (cues.size < 6) return null
    val out = DoubleArray(cues.size - 1)
    for (i in 0 until cues.lastIndex) {
        val gap = (cues[i + 1].startMs - cues[i].startMs).toDouble()
        out[i] = gap.coerceIn(200.0, 12_000.0)
    }
    return out
}

private fun pearson(a: DoubleArray, b: DoubleArray): Double {
    if (a.size != b.size || a.isEmpty()) return 0.0
    var sumA = 0.0
    var sumB = 0.0
    for (i in a.indices) {
        sumA += a[i]
        sumB += b[i]
    }
    val n = a.size.toDouble()
    val meanA = sumA / n
    val meanB = sumB / n
    var num = 0.0
    var denA = 0.0
    var denB = 0.0
    for (i in a.indices) {
        val da = a[i] - meanA
        val db = b[i] - meanB
        num += da * db
        denA += da * da
        denB += db * db
    }
    val den = sqrt(denA * denB)
    if (den < 1e-9) return 0.0
    return num / den
}

private fun sliceForSync(cues: List<SubCue>): List<SubCue> {
    if (cues.size <= 16) return cues
    val drop = (cues.size * 0.10).toInt().coerceAtLeast(2)
    return cues.drop(drop).dropLast(drop)
}

private fun nearestIndex(sortedStarts: LongArray, target: Long): Int {
    var lo = 0
    var hi = sortedStarts.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = sortedStarts[mid]
        when {
            v < target -> lo = mid + 1
            v > target -> hi = mid - 1
            else -> return mid
        }
    }
    return when {
        lo <= 0 -> 0
        lo >= sortedStarts.size -> sortedStarts.lastIndex
        else -> {
            val before = lo - 1
            if (abs(sortedStarts[before] - target) <= abs(sortedStarts[lo] - target)) before else lo
        }
    }
}
