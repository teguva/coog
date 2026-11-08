package tv.coog.app.ui.alder

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.BrowseScrollGate
import tv.coog.app.ui.HomeArtPrefetch
import tv.coog.app.ui.HomeShelfSettleMs
import tv.coog.app.ui.LocalCoogServer
import tv.coog.app.ui.LocalEnterRail
import tv.coog.app.ui.PrefetchShelfThumbs
import tv.coog.app.ui.cardMark
import tv.coog.app.ui.jobsMarkRevision
import tv.coog.app.ui.shelfLogoThumbUrl
import tv.coog.app.ui.shelfThumbUrl
import tv.coog.app.ui.watchFraction

/**
 * Compose host for [ShelfScrollRow]: keep Home chrome callbacks, View owns hop/art.
 *
 * Keys are handled on the Compose focusable (Browse pattern) and forwarded into the
 * View row — so card requestFocus never fights the Compose focus node / ring state.
 */
@Composable
internal fun ViewBackedShelf(
    items: List<MediaItem>,
    label: String,
    inset: Dp,
    peekWidth: Dp,
    focusedWidth: Dp,
    height: Dp,
    gap: Dp,
    continueBillboard: Boolean,
    active: Boolean,
    firstFocus: FocusRequester,
    exitUp: Boolean,
    jobs: List<JobItem> = emptyList(),
    library: List<MediaItem> = emptyList(),
    initialSelectedId: String? = null,
    onSettledSelectedId: ((String) -> Unit)? = null,
    onSelectionMoving: (() -> Unit)? = null,
    onHoldingChanged: ((Boolean) -> Unit)? = null,
    onOpen: (MediaItem) -> Unit,
    onCardMenu: ((MediaItem) -> Unit)?,
    onRowFocused: () -> Unit,
    onVerticalMove: (Int) -> Boolean,
    /** Left on first card — Search → keyboard; null keeps Continue (stay) / discovery defaults. */
    onExitLeft: (() -> Unit)? = null,
    /** When false, Compose will not land focus here (Search: only the lead/active row). */
    focusEnabled: Boolean = true,
    settleMs: Long = HomeShelfSettleMs,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val server = LocalCoogServer.current
    val enterRail = LocalEnterRail.current
    val density = LocalDensity.current
    val peekPx = with(density) { peekWidth.roundToPx() }
    val focusedPx = with(density) { focusedWidth.roundToPx() }
    val heightPx = with(density) { height.roundToPx() }
    val gapPx = with(density) { gap.roundToPx() }
    val insetPx = with(density) { inset.roundToPx() }
    val jobsRev = jobsMarkRevision(jobs)

    val openCb = rememberUpdatedState(onOpen)
    val menuCb = rememberUpdatedState(onCardMenu)
    val rowFocusedCb = rememberUpdatedState(onRowFocused)
    val verticalCb = rememberUpdatedState(onVerticalMove)
    val enterRailCb = rememberUpdatedState(enterRail)
    val exitLeftCb = rememberUpdatedState(onExitLeft)
    val settledCb = rememberUpdatedState(onSettledSelectedId)
    val movingCb = rememberUpdatedState(onSelectionMoving)
    val holdingCb = rememberUpdatedState(onHoldingChanged)
    val exitUpState = rememberUpdatedState(exitUp)
    val itemsState = rememberUpdatedState(items)
    val jobsState = rememberUpdatedState(jobs)
    val libraryState = rememberUpdatedState(library)

    fun marksFor(media: List<MediaItem>) =
        media.map { it.cardMark(jobsState.value, libraryState.value) }

    val seed = initialSelectedId?.takeIf { id -> items.any { it.id == id } }
        ?: items.first().id
    var selectedId by remember(items.firstOrNull()?.id) { mutableStateOf(seed) }
    var holding by remember { mutableStateOf(false) }
    val rowRef = remember { arrayOf<ShelfScrollRow?>(null) }

    fun artsFor(media: List<MediaItem>): List<ShelfScrollRow.CardArt> =
        media.map { item ->
            ShelfScrollRow.CardArt(
                posterUrl = shelfThumbUrl(item, server, preferBackdrop = false),
                backdropUrl = shelfThumbUrl(item, server, preferBackdrop = true),
                logoUrl = shelfLogoThumbUrl(item, server),
                progressFrac = if (continueBillboard) {
                    item.watchFraction() ?: 0f
                } else {
                    0f
                },
            )
        }

    fun bindCallbacks(row: ShelfScrollRow) {
        row.onOpen = { openCb.value(it) }
        row.onLongPress = menuCb.value
        row.onExitUp = {
            if (exitUpState.value) enterRailCb.value()
            else verticalCb.value(-1)
        }
        // Explicit exit (Search → keyboard) wins; else Continue stays put; discovery → rail.
        row.onExitLeft = when {
            exitLeftCb.value != null -> {
                { exitLeftCb.value?.invoke() }
            }
            continueBillboard -> null
            else -> {
                {
                    if (exitUpState.value) enterRailCb.value()
                }
            }
        }
        row.onVertical = { delta ->
            if (delta < 0 && exitUpState.value) {
                enterRailCb.value()
                true
            } else {
                verticalCb.value(delta)
            }
        }
        row.onSelectionChanged = { index, held ->
            val id = itemsState.value.getOrNull(index)?.id
            if (id != null) {
                selectedId = id
                if (holding != held) {
                    holding = held
                    holdingCb.value?.invoke(held)
                }
                rowFocusedCb.value()
            }
        }
    }

    LaunchedEffect(selectedId, holding) {
        if (holding) {
            movingCb.value?.invoke()
            return@LaunchedEffect
        }
        movingCb.value?.invoke()
        delay(settleMs)
        if (!holding) settledCb.value?.invoke(selectedId)
    }

    // Ring + selection chrome follow Home `active` — never clearFocus() (steals DPAD).
    LaunchedEffect(active) {
        val row = rowRef[0] ?: return@LaunchedEffect
        row.setShelfActive(active)
    }

    LaunchedEffect(jobsRev, library.size, items.map { it.id }) {
        val row = rowRef[0] ?: return@LaunchedEffect
        row.refreshMarks(marksFor(itemsState.value))
    }

    DisposableEffect(Unit) {
        onDispose {
            holding = false
            BrowseScrollGate.holdingHorizontal = false
            holdingCb.value?.invoke(false)
        }
    }

    // Warm backdrop+logo ahead of hops so Search/Continue billboards aren't cold on first focus.
    PrefetchShelfThumbs(
        items = items,
        selectedId = selectedId,
        landscape = true,
        alsoPosters = continueBillboard,
        enabled = active,
        tier = if (active) HomeArtPrefetch.Focused else HomeArtPrefetch.Off,
    )

    AndroidView(
        factory = { ctx ->
            ShelfScrollRow(ctx).also { row ->
                rowRef[0] = row
                row.setShelfTitle(label)
                row.configure(
                    peekWidthPx = peekPx,
                    heightPx = heightPx,
                    gapPx = gapPx,
                    insetPx = insetPx,
                    focusedWidthPx = focusedPx,
                    continueBillboard = continueBillboard,
                    authToken = server.token,
                )
                bindCallbacks(row)
                val media = items
                row.setItems(media, artsFor(media), marksFor(media))
                val seedIndex = media.indexOfFirst { it.id == seed }.coerceAtLeast(0)
                row.selectById(media[seedIndex].id, animate = false)
                row.setShelfActive(active)
            }
        },
        update = { row ->
            rowRef[0] = row
            row.setShelfTitle(label)
            row.configure(
                peekWidthPx = peekPx,
                heightPx = heightPx,
                gapPx = gapPx,
                insetPx = insetPx,
                focusedWidthPx = focusedPx,
                continueBillboard = continueBillboard,
                authToken = server.token,
            )
            bindCallbacks(row)
            val media = itemsState.value
            row.setItems(media, artsFor(media), marksFor(media))
            row.setShelfActive(active)
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(firstFocus)
            .focusable(enabled = focusEnabled)
            .focusProperties {
                canFocus = focusEnabled
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
            }
            .onFocusChanged {
                if (it.isFocused) {
                    rowFocusedCb.value()
                    rowRef[0]?.setShelfActive(true)
                }
            }
            .onPreviewKeyEvent { event ->
                if (!active) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                val code = native.keyCode
                val isDpad = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                    code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                    code == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                    code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == android.view.KeyEvent.KEYCODE_ENTER ||
                    code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    code == android.view.KeyEvent.KEYCODE_MENU
                if (!isDpad) return@onPreviewKeyEvent false
                // Forward both Down and Up (hold end needs KeyUp).
                rowRef[0]?.handleHostKey(native) == true
            },
    )
}
