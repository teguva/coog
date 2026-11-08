package tv.coog.app.ui.alder

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.ArrayDeque
import tv.coog.app.R
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.BrowseScrollGate
import tv.coog.app.ui.CoogJankLog
import tv.coog.app.ui.CoogServer
import tv.coog.app.ui.browsePosterThumbUrl
import tv.coog.app.ui.cardMark
import tv.coog.app.ui.jobsMarkRevision

/**
 * Browse poster wall with owned vertical pin scroll.
 * Only a small row window is attached; top/bottom spacers keep scroll range so a
 * 480-item catalog does not keep 480 ImageViews in the hierarchy (that was the hitch).
 */
internal class BrowseHoldGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val scroller = object : ScrollView(context) {
        override fun executeKeyEvent(event: KeyEvent): Boolean = false
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?): Int = 0
    }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topSpacer = View(context)
    private val bottomSpacer = View(context)

    private var server: CoogServer? = null
    private var items: List<MediaItem> = emptyList()
    private var jobs: List<JobItem> = emptyList()
    private var library: List<MediaItem> = emptyList()
    private var itemsFingerprint: String = ""
    private var marksFingerprint: Int = 0
    private var focused = 0
    private var ringIndex = -1
    private var columns = 1
    private var cardW = 0
    private var cardH = 0
    private var gap = 0
    private var inset = 0
    private var contentTopPad = 0
    private var contentBottomPad = 0
    private var anim: ValueAnimator? = null
    private var holding = false
    private var lastHopAtMs = 0L
    private var windowFirstRow = 0
    private var windowLastRow = -1

    /** index → live card in the attached window. */
    private val attachedCards = HashMap<Int, MediaCardView>()
    /** row → live row layout. */
    private val attachedRows = HashMap<Int, LinearLayout>()
    private val cardPool = ArrayDeque<MediaCardView>()

    var onSelectionChanged: ((index: Int, key: String, held: Boolean) -> Unit)? = null
    var onNearEnd: ((index: Int) -> Unit)? = null
    var onVerticalHold: ((Boolean) -> Unit)? = null
    var onOpenItem: ((MediaItem) -> Unit)? = null
    var onCardMenu: ((MediaItem) -> Unit)? = null
    var onExitUp: (() -> Boolean)? = null
    var onExitLeft: (() -> Unit)? = null
    var nearEndThreshold: Int = 108

    private val artRowsBehind = 2
    private val artRowsAhead = 5

    private companion object {
        const val HopMs = 200L
        /** Extra rows kept attached beyond the art band for hop headroom. */
        const val WindowPadRows = 2
    }

    private val shelfGap: Int
        get() = resources.getDimensionPixelSize(R.dimen.shelf_gap)
    private val homeCardH: Int
        get() = resources.getDimensionPixelSize(R.dimen.home_card_height)
    private val pitch: Int
        get() = cardH + gap
    private val totalRows: Int
        get() = if (items.isEmpty() || columns <= 0) 0 else (items.size + columns - 1) / columns

    init {
        scroller.isVerticalScrollBarEnabled = false
        scroller.overScrollMode = OVER_SCROLL_NEVER
        scroller.isFocusable = false
        column.addView(
            topSpacer,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0),
        )
        column.addView(
            bottomSpacer,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0),
        )
        scroller.addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun configurePads(topPx: Int, bottomPx: Int, insetPx: Int) {
        val topChanged = contentTopPad != topPx
        if (!topChanged && contentBottomPad == bottomPx && inset == insetPx) return
        contentTopPad = topPx
        contentBottomPad = bottomPx
        inset = insetPx
        applyGridPadding()
        if (topChanged && items.isNotEmpty()) {
            post { pinRow(focused, held = false, animate = false) }
        }
    }

    private fun applyGridPadding() {
        column.setPadding(0, contentTopPad, 0, contentBottomPad)
    }

    fun bind(
        server: CoogServer,
        media: List<MediaItem>,
        jobs: List<JobItem> = emptyList(),
        library: List<MediaItem> = emptyList(),
        stealFocus: Boolean = false,
    ) {
        this.server = server
        this.jobs = jobs
        this.library = library
        val nextMarks = jobsMarkRevision(jobs) * 31 + library.size
        val fp = "${media.size}:${media.firstOrNull()?.id.orEmpty()}:${media.lastOrNull()?.id.orEmpty()}"
        if (fp == itemsFingerprint && media.size == items.size) {
            items = media
            if (nextMarks != marksFingerprint) {
                marksFingerprint = nextMarks
                refreshAttachedMarks()
            }
            return
        }
        // Prefix grow — only update data + spacers; views attach via [syncWindow].
        if (canAppend(media)) {
            items = media
            itemsFingerprint = fp
            marksFingerprint = nextMarks
            updateSpacers()
            syncWindow(force = false)
            refreshAttachedMarks()
            CoogJankLog.mark(
                "browse-append-data",
                "n=${media.size} attached=${attachedCards.size} rows=${attachedRows.size}",
            )
            return
        }
        itemsFingerprint = fp
        marksFingerprint = nextMarks
        items = media
        val keepFocus = stealFocus || hasFocus() || findFocus() != null
        ensureMetrics()
        applyGridPadding()
        detachAllRows()
        windowFirstRow = 0
        windowLastRow = -1
        focused = focused.coerceIn(0, (media.size - 1).coerceAtLeast(0))
        ringIndex = -1
        syncWindow(force = true)
        post {
            refreshArtWindow()
            if (keepFocus && media.isNotEmpty()) {
                focusIndex(focused, held = false, animate = false)
            }
        }
    }

    private fun refreshAttachedMarks() {
        for ((index, card) in attachedCards) {
            val item = items.getOrNull(index) ?: continue
            card.setMark(item.cardMark(jobs, library))
        }
    }

    private fun canAppend(media: List<MediaItem>): Boolean {
        if (items.isEmpty() || media.size <= items.size) return false
        for (i in items.indices) {
            if (media[i].id != items[i].id) return false
        }
        return true
    }

    private fun ensureMetrics() {
        gap = shelfGap
        val contentWidth = resources.displayMetrics.widthPixels - inset * 2
        val preferredW = homeCardH * 2f / 3f
        columns = run {
            val raw = maxOf(
                1,
                ((contentWidth + gap) / (preferredW + gap)).toInt(),
            )
            val next = raw + 1
            val widthIfNext = (contentWidth - gap * (next - 1)).toFloat() / next
            if (widthIfNext >= preferredW * 0.97f) next else raw
        }
        cardW = ((contentWidth - gap * (columns - 1)) / columns).coerceAtLeast(dp(80))
        cardH = (cardW * 3f / 2f).toInt()
    }

    private fun rowHeight(row: Int): Int =
        if (row >= totalRows - 1) cardH else pitch

    private fun updateSpacers() {
        if (cardH <= 0 || columns <= 0) return
        val rows = totalRows
        var topH = 0
        for (r in 0 until windowFirstRow) topH += rowHeight(r)
        var bottomH = 0
        if (windowLastRow >= 0) {
            for (r in (windowLastRow + 1) until rows) bottomH += rowHeight(r)
        } else {
            for (r in 0 until rows) bottomH += rowHeight(r)
        }
        setSpacerHeight(topSpacer, topH)
        setSpacerHeight(bottomSpacer, bottomH)
    }

    private fun setSpacerHeight(spacer: View, h: Int) {
        val lp = spacer.layoutParams as LinearLayout.LayoutParams
        if (lp.height == h) return
        lp.height = h
        spacer.layoutParams = lp
    }

    /**
     * Attach focus±band rows. During hold, only expand the window (never move topSpacer /
     * detach behind) — spacer scroll compensation was cancelling pin deltas so hops had
     * no animation and continuous scroll stuttered every window slide.
     */
    private fun syncWindow(force: Boolean) {
        val srv = server ?: return
        if (items.isEmpty() || columns <= 0 || cardH <= 0) return
        val focusRow = focused / columns
        val rows = totalRows
        val idealFirst = (focusRow - artRowsBehind - WindowPadRows).coerceAtLeast(0)
        val idealLast = (focusRow + artRowsAhead + WindowPadRows).coerceAtMost(rows - 1)

        if (holding && !force && windowLastRow >= 0) {
            // Expand only — keep topSpacer fixed so pin anim still has distance to travel.
            var grown = false
            if (idealLast > windowLastRow) {
                for (row in (windowLastRow + 1)..idealLast) {
                    if (row !in attachedRows) attachRow(row, srv)
                }
                windowLastRow = idealLast
                grown = true
            }
            if (idealFirst < windowFirstRow) {
                for (row in idealFirst until windowFirstRow) {
                    if (row !in attachedRows) attachRow(row, srv)
                }
                windowFirstRow = idealFirst
                grown = true
            }
            if (grown) updateSpacers()
            refreshArtWindow()
            return
        }

        // Idle: hysteresis so we do not rebuild on every single-row hop.
        if (!force && windowLastRow >= 0 &&
            focusRow > windowFirstRow &&
            focusRow < windowLastRow
        ) {
            refreshArtWindow()
            return
        }

        val first = idealFirst
        val last = idealLast
        if (!force && first == windowFirstRow && last == windowLastRow) {
            refreshArtWindow()
            return
        }
        val t0 = SystemClock.elapsedRealtime()

        val toDetach = attachedRows.keys.filter { it < first || it > last }
        for (row in toDetach) {
            detachRow(row)
        }

        windowFirstRow = first
        windowLastRow = last
        updateSpacers()

        for (row in first..last) {
            if (row in attachedRows) continue
            attachRow(row, srv)
        }
        // No scrollY compensation — pinRow animates to the absolute dest in the new layout.
        applyRing(focused)
        refreshArtWindow()
        CoogJankLog.mark(
            "browse-window",
            "rows=$first..$last attached=${attachedCards.size} mainMs=${SystemClock.elapsedRealtime() - t0}",
        )
    }

    private fun detachRow(row: Int) {
        val rowView = attachedRows.remove(row) ?: return
        val start = row * columns
        for (i in 0 until columns) {
            val idx = start + i
            val card = attachedCards.remove(idx) ?: continue
            rowView.removeView(card)
            recycleCard(card)
        }
        column.removeView(rowView)
    }

    private fun detachAllRows() {
        val rows = attachedRows.keys.toList()
        for (row in rows) detachRow(row)
        attachedRows.clear()
        attachedCards.clear()
        windowFirstRow = 0
        windowLastRow = -1
        updateSpacers()
    }

    private fun attachRow(row: Int, server: CoogServer) {
        val rowView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(inset, 0, inset, 0)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                rowHeight(row),
            )
        }
        val start = row * columns
        for (col in 0 until columns) {
            val index = start + col
            if (index >= items.size) break
            val item = items[index]
            val card = obtainCard(index, item, server)
            rowView.addView(card)
            // Bind mark after attach so ComposeView has a window (see MediaCardView.paintMark).
            card.setMark(item.cardMark(jobs, library))
            attachedCards[index] = card
        }
        // Keep children ordered: topSpacer, rows ascending, bottomSpacer.
        val insertAt = 1 + attachedRows.keys.count { it < row }
        column.addView(rowView, insertAt)
        attachedRows[row] = rowView
    }

    private fun obtainCard(index: Int, item: MediaItem, server: CoogServer): MediaCardView {
        val card = cardPool.poll() ?: MediaCardView(context).apply {
            mode = MediaCardView.Mode.Immersive
            selectionRingMode = true
        }
        card.configureSizes(cardW, cardH, cardW)
        card.holding = { this@BrowseHoldGridView.holding }
        card.bindItem(item, poster = "", authToken = server.token)
        card.layoutParams = LinearLayout.LayoutParams(cardW, cardH).apply {
            marginEnd = if ((index % columns) == columns - 1) 0 else gap
        }
        card.setOnKeyListener { _, keyCode, event -> handleKey(index, keyCode, event) }
        card.setOnClickListener { onOpenItem?.invoke(item) }
        card.setOnLongClickListener {
            onCardMenu?.invoke(item)
            true
        }
        card.onCardFocusChanged = { has ->
            if (has) {
                focused = index
                applyRing(index)
            }
        }
        card.setRingSelected(index == focused)
        return card
    }

    private fun recycleCard(card: MediaCardView) {
        card.onCardFocusChanged = null
        card.setOnKeyListener(null)
        card.setOnClickListener(null)
        card.setOnLongClickListener(null)
        card.setMark(null)
        card.releaseArt()
        card.setRingSelected(false)
        if (cardPool.size < 48) cardPool.offer(card)
    }

    private fun refreshArtWindow() {
        if (attachedCards.isEmpty() || items.isEmpty() || columns <= 0) return
        val srv = server ?: return
        val focusRow = focused / columns
        val warmFirst = ((focusRow - artRowsBehind) * columns).coerceAtLeast(0)
        val warmLast = (
            ((focusRow + artRowsAhead + 1) * columns) - 1
            ).coerceAtMost(items.lastIndex)
        for (i in warmFirst..warmLast) {
            val card = attachedCards[i] ?: continue
            val item = items.getOrNull(i) ?: continue
            val url = browsePosterThumbUrl(item, srv)
            if (!card.hasPosterFace(url)) {
                card.bindItem(item, url, authToken = srv.token)
            }
        }
    }

    fun focusContent() {
        if (items.isEmpty()) return
        focusIndex(focused, held = false, animate = false)
    }

    fun focusCard(index: Int) {
        if (index !in items.indices) return
        focusIndex(index, held = false, animate = true)
    }

    fun selectedIndex(): Int = focused

    fun handleHostKey(event: KeyEvent): Boolean {
        if (items.isEmpty()) return false
        val index = focused.coerceIn(0, items.lastIndex)
        return handleKey(index, event.keyCode, event)
    }

    private fun handleKey(index: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val held = event.repeatCount > 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    endHold()
                    return true
                }
                if (index % columns == columns - 1) return true
                return hop(index + 1, held)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    endHold()
                    return true
                }
                if (index % columns == 0) {
                    onExitLeft?.invoke()
                    return true
                }
                return hop(index - 1, held)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    endHold()
                    return true
                }
                if (held) setHolding(true)
                return hop(index + columns, held)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    endHold()
                    return true
                }
                if (held) setHolding(true)
                if (index < columns) {
                    releaseHoldForChrome()
                    return onExitUp?.invoke() == true
                }
                return hop(index - columns, held)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP && !held) {
                    items.getOrNull(focused)?.let { onOpenItem?.invoke(it) }
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN && !held) {
                    items.getOrNull(focused)?.let { onCardMenu?.invoke(it) }
                }
                return onCardMenu != null
            }
        }
        return false
    }

    private fun hop(toRaw: Int, held: Boolean): Boolean {
        if (items.isEmpty()) return true
        val to = toRaw.coerceIn(0, items.lastIndex)
        if (to == focused) return true
        val now = SystemClock.elapsedRealtime()
        if (held && now - lastHopAtMs < HopMs) return true
        lastHopAtMs = now
        if (held) setHolding(true)
        CoogJankLog.hop(if (held) "browse-hold" else "browse-tap", "i=$to n=${items.size}")
        focusIndex(to, held = held, animate = true)
        return true
    }

    private fun setHolding(value: Boolean) {
        if (holding == value) return
        holding = value
        BrowseScrollGate.holdingVertical = value
        onVerticalHold?.invoke(value)
    }

    private fun releaseHoldForChrome() {
        if (holding) setHolding(false)
        refreshArtWindow()
        finishHoldPin()
    }

    private fun endHold() {
        if (!holding) return
        setHolding(false)
        attachedCards[focused]?.requestFocus()
        finishHoldPin()
        // Trim the expand-only hold window now that pin owns the settle.
        syncWindow(force = true)
        refreshArtWindow()
    }

    private fun finishHoldPin() {
        if (anim?.isRunning == true) return
        pinRow(focused, held = false, animate = true)
    }

    private fun applyRing(index: Int) {
        if (ringIndex == index) {
            attachedCards[index]?.setRingSelected(true)
            return
        }
        attachedCards[ringIndex]?.setRingSelected(false)
        attachedCards[index]?.setRingSelected(true)
        ringIndex = index
    }

    private fun focusIndex(index: Int, held: Boolean, animate: Boolean) {
        focused = index.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        syncWindow(force = false)
        applyRing(focused)
        val item = items.getOrNull(focused)
        val key = item?.let { it.playableId().ifBlank { it.id } }.orEmpty()
        onSelectionChanged?.invoke(focused, key, held)
        if (items.size - focused <= nearEndThreshold) {
            onNearEnd?.invoke(focused)
        }
        if (!held) {
            attachedCards[focused]?.requestFocus()
        }
        pinRow(focused, held, animate)
    }

    private fun pinRow(index: Int, held: Boolean, animate: Boolean) {
        if (cardH <= 0 || items.isEmpty()) return
        val row = index / columns
        val lastRow = (items.lastIndex.coerceAtLeast(0)) / columns
        val p = pitch
        val lead = if (row > 0 && row < lastRow) 1 else 0
        val target = (row - lead) * p
        val maxScroll = (column.height - scroller.height).coerceAtLeast(0)
        val dest = minOf(target, maxScroll)
        val start = scroller.scrollY
        if (kotlin.math.abs(dest - start) < 1) {
            scroller.scrollTo(0, dest)
            return
        }
        anim?.cancel()
        if (!animate) {
            scroller.scrollTo(0, dest)
            return
        }
        anim = ValueAnimator.ofInt(start, dest).apply {
            duration = HopMs
            interpolator = if (held) LinearInterpolator() else DecelerateInterpolator()
            addUpdateListener { scroller.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
