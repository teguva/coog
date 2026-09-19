package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogDanger
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

@Composable
fun DownloadsScreen(
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onPlayJob: (JobItem) -> Unit,
    onPauseJob: (JobItem) -> Unit,
    onResumeJob: (JobItem) -> Unit,
    onCancelJob: (JobItem) -> Unit,
    error: String? = null,
) {
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val rows = remember(jobs) { jobs.filter { !it.isFinished() } }
    val inset = catalogInset()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(start = inset, end = inset, top = topBarHeight() + 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Downloads", style = CoogType.screenTitle)
        if (error != null) {
            Text(friendlyPlayError(error), color = CoogDanger)
        }
        if (rows.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Queue is empty",
                    style = CoogType.heroTagline,
                    modifier = Modifier.focusRequester(firstFocus).focusable(),
                )
                Text(
                    "Pick a source on a title, or add a URL in Settings, and it will show up here with artwork and progress.",
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                )
            }
        } else {
            Text(
                "${rows.count { it.isActive() }} in progress",
                style = CoogType.heroPlot,
                color = CoogTextMuted,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(rows, key = { _, job -> job.id }) { index, job ->
                    DownloadCard(
                        job = job,
                        art = jobArt(job, library),
                        onPlay = { onPlayJob(job) },
                        onPause = { onPauseJob(job) },
                        onResume = { onResumeJob(job) },
                        onCancel = { onCancelJob(job) },
                        firstFocus = if (index == 0) firstFocus else null,
                        exitUp = index == 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    job: JobItem,
    art: MediaItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    firstFocus: FocusRequester? = null,
    exitUp: Boolean = false,
) {
    val actions = buildList {
        if (job.ready || job.status == "ready") add(DownloadAction("Play", true, onPlay))
        if (job.canPause()) add(DownloadAction("Pause", false, onPause))
        if (job.canResume()) add(DownloadAction("Start", true, onResume))
        if (job.canCancel()) {
            val label = if (job.status == "error") "Remove download" else "Cancel"
            add(DownloadAction(label, false, onCancel))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterArt(
            item = art,
            kind = ArtKind.Poster,
            modifier = Modifier
                .width(80.dp)
                .height(118.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(job.headline(), style = CoogType.cardTitle, maxLines = 1)
            val meta = job.fileMetaLine()
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = CoogType.cardYear,
                    maxLines = 1,
                    color = CoogTextMuted,
                )
            }
            Text(
                job.subtitle(),
                style = CoogType.cardYear,
                maxLines = 1,
                color = CoogTextMuted,
            )
            job.transferLine()?.let { transfer ->
                Text(
                    transfer,
                    style = CoogType.cardYear,
                    maxLines = 1,
                    color = CoogTextMuted,
                )
            }
            if (actions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEachIndexed { index, action ->
                        val mod = Modifier
                            .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                            .exitToRailOnUp(enabled = exitUp, location = "DownloadsScreen.kt:action")
                        if (action.primary) {
                            WhitePill(label = action.label, onClick = action.onClick, modifier = mod)
                        } else {
                            GhostButton(label = action.label, onClick = action.onClick, modifier = mod)
                        }
                    }
                }
            }
        }
        TransferRing(
            mark = job.toCardMark(),
            size = MarkSize.Comfort,
            modifier = Modifier.padding(end = 16.dp),
        )
    }
}

private fun jobArt(job: JobItem, library: List<MediaItem>): MediaItem {
    val byMedia = library.firstOrNull { it.playableId() == job.mediaId || it.id == job.mediaId }
    if (byMedia != null) return byMedia
    val imdb = job.imdbId
    if (imdb.isNotBlank()) {
        library.firstOrNull { it.imdbId.equals(imdb, ignoreCase = true) }?.let { return it }
    }
    return MediaItem(
        id = job.mediaId.ifBlank { job.id },
        kind = "movie",
        title = job.title,
        imdbId = job.imdbId,
        inLibrary = job.mediaId.isNotBlank(),
        libraryId = job.mediaId,
    )
}

private data class DownloadAction(val label: String, val primary: Boolean, val onClick: () -> Unit)
