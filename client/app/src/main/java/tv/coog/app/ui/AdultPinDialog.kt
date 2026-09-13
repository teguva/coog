package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogBgSoft
import tv.coog.app.ui.theme.CoogType

@Composable
fun AdultPinDialog(
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
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
                    .width(420.dp)
                    .background(CoogBgSoft, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Enter PIN", style = CoogType.screenTitle, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "•".repeat(pin.length).ifBlank { " " },
                    style = CoogType.heroTagline,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
                if (!error.isNullOrBlank()) {
                    Text(error, color = Color(0xFFFF8A80), style = CoogType.cardYear)
                    Spacer(Modifier.height(8.dp))
                }
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("⌫", "0", "OK"),
                )
                keys.forEachIndexed { rowIdx, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { colIdx, label ->
                            val requester = if (rowIdx == 0 && colIdx == 0) firstFocus else null
                            PinKey(
                                label = label,
                                enabled = !busy,
                                requester = requester,
                                onClick = {
                                    when (label) {
                                        "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                        "OK" -> if (pin.length >= 4) onSubmit(pin)
                                        else -> if (pin.length < 12) pin += label
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Surface(
                    onClick = onDismiss,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White.copy(alpha = 0.7f),
                        focusedContainerColor = Color.White.copy(alpha = 0.12f),
                        focusedContentColor = Color.White,
                    ),
                ) {
                    Text("Cancel", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
fun AdultExitConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val leaveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { leaveFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
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
                    .width(420.dp)
                    .background(CoogBgSoft, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Leave Maize?", style = CoogType.screenTitle, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "You'll need the PIN to unlock again.",
                    style = CoogType.cardYear,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        onClick = onDismiss,
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            focusedContainerColor = Color.White.copy(alpha = 0.22f),
                        ),
                        modifier = Modifier.width(140.dp).height(44.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Stay", style = CoogType.cardTitle, color = Color.White)
                        }
                    }
                    Surface(
                        onClick = onConfirm,
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFFB71C1C),
                            focusedContainerColor = Color(0xFFE53935),
                        ),
                        modifier = Modifier
                            .width(140.dp)
                            .height(44.dp)
                            .focusRequester(leaveFocus),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Leave", style = CoogType.cardTitle, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    enabled: Boolean,
    requester: FocusRequester?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = CoogBgDeep,
        ),
        modifier = Modifier
            .size(72.dp, 48.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = CoogType.cardTitle)
        }
    }
}
