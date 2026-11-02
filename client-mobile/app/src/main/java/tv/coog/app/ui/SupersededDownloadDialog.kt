package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import tv.coog.app.BuildConfig
import tv.coog.app.data.JobItem
import tv.coog.app.ui.theme.CoogBgSoft
import tv.coog.app.ui.theme.CoogType

@Composable
fun SupersededDownloadDialog(
    jobs: List<JobItem>,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val deleteFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!BuildConfig.TOUCH_CLIENT) {
            runCatching { deleteFocus.requestFocus() }
        }
    }
    val summary = jobs.joinToString("\n") { job ->
        val pct = (job.progress * 100).toInt().coerceIn(0, 99)
        val meta = listOfNotNull(
            job.quality.takeIf { it.isNotBlank() },
            job.sizeLabel.takeIf { it.isNotBlank() },
            "$pct%",
        ).joinToString(" · ")
        listOfNotNull(job.headline().takeIf { it.isNotBlank() }, meta.takeIf { it.isNotBlank() })
            .joinToString(" — ")
    }.ifBlank { "Previous download" }
    Dialog(
        onDismissRequest = onKeep,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(480.dp)
                    .background(CoogBgSoft, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Previous download paused", style = CoogType.screenTitle, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "A new source started. Delete the earlier download and its progress?\n\n$summary",
                    style = CoogType.cardYear,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoogClickable(
                        onClick = onKeep,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.width(150.dp).height(44.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Keep", style = CoogType.cardTitle, color = Color.White)
                        }
                    }
                    CoogClickable(
                        onClick = onDelete,
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFB71C1C),
                        modifier = Modifier
                            .width(150.dp)
                            .height(44.dp)
                            .then(
                                if (!BuildConfig.TOUCH_CLIENT) {
                                    Modifier.focusRequester(deleteFocus)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Delete", style = CoogType.cardTitle, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
