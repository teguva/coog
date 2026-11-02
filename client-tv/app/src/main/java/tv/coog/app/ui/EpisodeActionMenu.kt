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
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgSoft
import tv.coog.app.ui.theme.CoogType

@Composable
fun EpisodeActionMenu(
    episode: MediaItem,
    onSources: () -> Unit,
    onManage: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val catchFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    var sawSelectDown by remember { mutableStateOf(false) }
    LaunchedEffect(episode.id) {
        armed = false
        sawSelectDown = false
        runCatching { catchFocus.requestFocus() }
        delay(300)
        if (!sawSelectDown && !armed) {
            armed = true
        }
    }
    LaunchedEffect(armed) {
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
                Text(episode.episodeHeadline(), style = CoogType.heroTagline)
                val meta = episode.heroMetaLine()
                if (meta.isNotBlank()) {
                    Text(meta, style = CoogType.cardYear)
                }
                WhitePill(
                    label = "Sources",
                    onClick = { if (armed) onSources() },
                    modifier = Modifier
                        .focusRequester(firstFocus)
                        .focusProperties { canFocus = armed }
                        .fillMaxWidth(),
                )
                if (onManage != null) {
                    GhostButton(
                        label = "Manage library",
                        onClick = { if (armed) onManage() },
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
            }
        }
    }
}
