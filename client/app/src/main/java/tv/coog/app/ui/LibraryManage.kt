package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.tv.material3.Text
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgSoft
import tv.coog.app.ui.theme.CoogType

enum class LibraryManageFocus { Title, Season, Episode }

data class LibraryManageTarget(
    val item: MediaItem,
    val focus: LibraryManageFocus = LibraryManageFocus.Title,
)

data class LibraryDeleteAction(
    val scope: String,
    val label: String,
    val confirm: String,
)

fun MediaItem.canManageLibrary(): Boolean = isLocal() || diskMediaId().isNotBlank()

fun libraryDeleteActions(item: MediaItem, focus: LibraryManageFocus): List<LibraryDeleteAction> {
    val show = item.showTitle.ifBlank { item.headline() }
    val fileLabel = when {
        item.kind == "episode" -> "Delete episode file"
        else -> "Remove from library"
    }
    val fileConfirm = when {
        item.kind == "episode" ->
            "Delete the episode file “${item.headline()}”? Show artwork is kept."
        else ->
            "Delete “${item.headline()}” from disk, including its folder and metadata? This cannot be undone."
    }
    val seasonLabel = if (item.season > 0) "Delete season ${item.season}" else "Delete this season"
    val seasonConfirm =
        "Delete every on-disk episode in ${if (item.season > 0) "season ${item.season}" else "this season"} of “$show”? Show artwork is kept. This cannot be undone."
    val seriesLabel = "Remove entire series"
    val seriesConfirm =
        "Remove the entire series folder and every episode file for “$show”? This cannot be undone."
    val file = LibraryDeleteAction("file", fileLabel, fileConfirm)
    val season = LibraryDeleteAction("season", seasonLabel, seasonConfirm)
    val series = LibraryDeleteAction("series", seriesLabel, seriesConfirm)
    val episodeish = item.kind == "episode" || (item.kind == "series" && item.season > 0)
    val seriesish = item.kind == "series" || item.kind == "episode"
    return when (focus) {
        LibraryManageFocus.Season -> listOf(season, series)
        LibraryManageFocus.Episode -> listOf(file, season, series)
        LibraryManageFocus.Title -> when {
            item.kind == "series" && !episodeish -> listOf(series)
            episodeish -> listOf(file, season, series)
            seriesish -> listOf(series)
            else -> listOf(file)
        }
    }
}

@Composable
fun LibraryManageMenu(
    target: LibraryManageTarget,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDelete: (scope: String) -> Unit,
) {
    val item = target.item
    val actions = remember(item.id, item.kind, item.season, target.focus) {
        libraryDeleteActions(item, target.focus)
    }
    val catchFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    var sawSelectDown by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<LibraryDeleteAction?>(null) }
    val detail = when {
        target.focus == LibraryManageFocus.Season && item.season > 0 ->
            if (item.showTitle.isNotBlank()) "${item.showTitle} · Season ${item.season}" else "Season ${item.season}"
        item.season > 0 || item.episode > 0 -> item.episodeHeadline()
        else -> item.heroMetaLine()
    }
    LaunchedEffect(item.id, target.focus) {
        pending = null
        runCatching { catchFocus.requestFocus() }
        delay(300)
        if (!sawSelectDown && !armed) {
            armed = true
        }
    }
    LaunchedEffect(armed, pending?.scope) {
        if (!armed) return@LaunchedEffect
        delay(16)
        runCatching { firstFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .focusRequester(catchFocus)
                .focusProperties { canFocus = !armed }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (armed) return@onPreviewKeyEvent false
                    if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
                        return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        sawSelectDown = true
                    } else if (event.type == KeyEventType.KeyUp) {
                        armed = true
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .background(CoogBgSoft, RoundedCornerShape(18.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.headline(), style = CoogType.heroTagline)
                if (detail.isNotBlank() && !detail.equals(item.headline(), ignoreCase = true)) {
                    Text(detail, style = CoogType.cardYear)
                }
                val confirm = pending
                if (confirm == null) {
                    WhitePill(
                        label = "Open",
                        onClick = { if (armed) onOpen() },
                        modifier = Modifier
                            .focusRequester(firstFocus)
                            .focusProperties { canFocus = armed }
                            .fillMaxWidth(),
                    )
                    actions.forEach { action ->
                        GhostButton(
                            label = action.label,
                            onClick = { if (armed) pending = action },
                            modifier = Modifier
                                .focusProperties { canFocus = armed }
                                .fillMaxWidth(),
                        )
                    }
                    GhostButton(
                        label = "Cancel",
                        onClick = { if (armed) onDismiss() },
                        modifier = Modifier
                            .focusProperties { canFocus = armed }
                            .fillMaxWidth(),
                    )
                } else {
                    Text(confirm.confirm, style = CoogType.cardYear)
                    WhitePill(
                        label = "Delete",
                        onClick = { if (armed) onDelete(confirm.scope) },
                        modifier = Modifier
                            .focusRequester(firstFocus)
                            .focusProperties { canFocus = armed }
                            .fillMaxWidth(),
                    )
                    GhostButton(
                        label = "Back",
                        onClick = { if (armed) pending = null },
                        modifier = Modifier
                            .focusProperties { canFocus = armed }
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}
