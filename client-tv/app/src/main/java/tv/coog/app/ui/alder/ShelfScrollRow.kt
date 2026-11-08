package tv.coog.app.ui.alder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt
import tv.coog.app.R
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.BrowseScrollGate
import tv.coog.app.ui.CardMark
import tv.coog.app.ui.CoogJankLog

/**
 * Alder-style shelf: HorizontalScrollView + focus-owned Left/Right pin.
 *
 * Continue: one shared 0→1 animator drives complementary peek/focused widths and scrollX
 * (same invariant as Compose ContinueHopSync). Tap = 200ms decelerate; hold = 200ms linear.
 * Hold KeyUp lets the in-flight hop finish — never snap-cancel.
 */
internal class ShelfScrollRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    data class CardArt(
        val posterUrl: String,
        val backdropUrl: String = "",
        val logoUrl: String = "",
        val progressFrac: Float = 0f,
    )

    private val titleView = TextView(context).apply {
        setTextAppearance(context, R.style.CoogText_ShelfTitle)
    }
    private val scroller = object : HorizontalScrollView(context) {
        override fun executeKeyEvent(event: KeyEvent): Boolean = false
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?): Int = 0
    }
    private val track = LinearLayout(context).apply {
        orientation = HORIZONTAL
    }

    private var items: List<MediaItem> = emptyList()
    private var arts: List<CardArt> = emptyList()
    private var marks: List<CardMark?> = emptyList()
    private var cards: List<MediaCardView> = emptyList()
    private var selected = 0
    private var hopFrom = 0
    private var hopTo = 0
    private var anim: ValueAnimator? = null
    private var cardW = 0
    private var cardH = 0
    private var focusedW = 0
    private var gap = 0
    private var inset = 0
    private var continueMode = false
    private var token = ""
    private var holding = false
    private var shelfActive = false
    private var lastHopAtMs = 0L
    /** Bumps on each hop so a stale post{} after interrupt cannot start an old animator. */
    private var hopGen = 0
    /** Select/OK long-press → card menu (Compose host owns focus; View never gets OnLongClick). */
    private var selectPressed = false
    private var selectLongHandled = false
    private val selectLongRunnable = Runnable {
        if (!selectPressed || onLongPress == null) return@Runnable
        selectLongHandled = true
        items.getOrNull(selected)?.let { onLongPress?.invoke(it) }
    }

    var onSelectionChanged: ((index: Int, held: Boolean) -> Unit)? = null
    var onOpen: ((MediaItem) -> Unit)? = null
    var onLongPress: ((MediaItem) -> Unit)? = null
    var onExitUp: (() -> Unit)? = null
    /** Left on first card — Home row0 → rail; else cancel (do not reuse onExitUp). */
    var onExitLeft: (() -> Unit)? = null
    var onVertical: ((delta: Int) -> Boolean)? = null
    var artUrlProvider: ((MediaItem) -> CardArt)? = null

    private companion object {
        const val HopMs = 200L
        /** Match ImmersiveHome / FeaturedCarousel select hold → menu. */
        const val SelectLongPressMs = 550L
    }

    init {
        orientation = VERTICAL
        scroller.isHorizontalScrollBarEnabled = false
        scroller.overScrollMode = OVER_SCROLL_NEVER
        scroller.isFocusable = false
        scroller.addView(
            track,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            titleView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.home_shelf_title_block),
            ),
        )
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setShelfTitle(text: String) {
        titleView.text = text
        titleView.visibility = if (text.isBlank()) GONE else VISIBLE
    }

    fun configure(
        peekWidthPx: Int,
        heightPx: Int,
        gapPx: Int,
        insetPx: Int,
        focusedWidthPx: Int = peekWidthPx,
        continueBillboard: Boolean = false,
        authToken: String = "",
    ) {
        cardW = peekWidthPx
        cardH = heightPx
        gap = gapPx
        inset = insetPx
        focusedW = focusedWidthPx.coerceAtLeast(peekWidthPx)
        continueMode = continueBillboard
        token = authToken
        (titleView.layoutParams as? LayoutParams)?.let { lp ->
            lp.marginStart = insetPx
            titleView.layoutParams = lp
        }
    }

    fun setItems(
        media: List<MediaItem>,
        art: List<CardArt> = emptyList(),
        cardMarks: List<CardMark?> = emptyList(),
    ) {
        val fp = media.joinToString(",") { it.id }
        val artFp = art.joinToString(",") {
            "${it.posterUrl}|${it.backdropUrl}|${it.logoUrl}|${it.progressFrac}"
        }
        val marksFp = cardMarks.joinToString(",") {
            it?.let { m -> "${m.kind}:${m.progress}:${m.have}:${m.total}" } ?: "-"
        }
        if (fp == itemsFingerprint && artFp == artsFingerprint && cards.size == media.size) {
            items = media
            arts = art
            if (marksFp != marksFingerprint) {
                marks = cardMarks
                marksFingerprint = marksFp
                refreshMarks()
            }
            return
        }
        itemsFingerprint = fp
        artsFingerprint = artFp
        marksFingerprint = marksFp
        items = media
        arts = art
        marks = cardMarks
        anim?.cancel()
        anim = null
        track.removeAllViews()
        val built = ArrayList<MediaCardView>(media.size)
        media.forEachIndexed { index, item ->
            val card = MediaCardView(context).apply {
                mode = if (continueMode) MediaCardView.Mode.Continue else MediaCardView.Mode.Immersive
                configureSizes(cardW, cardH, focusedW)
                holding = { this@ShelfScrollRow.holding }
                val a = art.getOrNull(index) ?: artUrlProvider?.invoke(item) ?: CardArt("")
                bindItem(item, a.posterUrl, a.backdropUrl, a.logoUrl, token, a.progressFrac)
                setBillboard(continueMode && index == selected)
                layoutParams = LinearLayout.LayoutParams(
                    if (continueMode && index == selected) focusedW else cardW,
                    cardH,
                ).apply {
                    marginEnd = if (index == media.lastIndex) inset else gap
                    if (index == 0) marginStart = inset
                }
                // Compose host owns DPAD focus; cards only paint the selection ring.
                isFocusable = false
                selectionRingMode = true
                setOnClickListener { onOpen?.invoke(item) }
                setOnLongClickListener {
                    onLongPress?.invoke(item)
                    true
                }
            }
            track.addView(card)
            // After addView so ComposeView mark host has a window.
            card.setMark(cardMarks.getOrNull(index))
            built.add(card)
        }
        cards = built
        if (selected !in media.indices) selected = 0
        hopFrom = selected
        hopTo = selected
        post { settleFocused(selected, animateScroll = false) }
    }

    fun refreshMarks(cardMarks: List<CardMark?> = marks) {
        marks = cardMarks
        cards.forEachIndexed { index, card ->
            card.setMark(cardMarks.getOrNull(index))
        }
    }

    private var itemsFingerprint = ""
    private var artsFingerprint = ""
    private var marksFingerprint = ""

    fun focusSelected() {
        setShelfActive(true)
    }

    /**
     * White focus ring only while this shelf owns Home focus.
     * Idle plates keep the faint ring. Does not clearFocus() — that steals DPAD from Compose.
     */
    fun setShelfActive(active: Boolean) {
        shelfActive = active
        cards.forEachIndexed { i, c -> c.setRingSelected(active && i == selected) }
    }

    fun selectedIndex(): Int = selected

    fun selectById(id: String, animate: Boolean = false) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        select(i, held = false, animate = animate)
    }

    /** Compose host forwards DPAD here (Browse pattern) so View cards need not hold focus. */
    fun handleHostKey(event: KeyEvent): Boolean = handleCardKey(event.keyCode, event)

    private fun handleCardKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val held = event.repeatCount > 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    endHold()
                    return true
                }
                return hop(+1, held)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    endHold()
                    return true
                }
                if (selected == 0 && !held) {
                    // Consume Left at leading edge — optional rail exit via callback.
                    onExitLeft?.invoke()
                    return true
                }
                return hop(-1, held)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                if (onVertical?.invoke(-1) == true) return true
                onExitUp?.invoke()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return onVertical?.invoke(1) == true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            selectPressed = true
                            selectLongHandled = false
                            removeCallbacks(selectLongRunnable)
                            if (onLongPress != null) {
                                postDelayed(selectLongRunnable, SelectLongPressMs)
                            }
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        removeCallbacks(selectLongRunnable)
                        val open = selectPressed && !selectLongHandled
                        selectPressed = false
                        selectLongHandled = false
                        if (open) {
                            items.getOrNull(selected)?.let { onOpen?.invoke(it) }
                        }
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    cancelSelectLongPress()
                    items.getOrNull(selected)?.let { onLongPress?.invoke(it) }
                }
                return onLongPress != null
            }
        }
        return false
    }

    private fun cancelSelectLongPress() {
        removeCallbacks(selectLongRunnable)
        selectPressed = false
        selectLongHandled = false
    }

    override fun onDetachedFromWindow() {
        cancelSelectLongPress()
        super.onDetachedFromWindow()
    }

    private fun hop(delta: Int, held: Boolean): Boolean {
        val to = (selected + delta).coerceIn(0, items.lastIndex.coerceAtLeast(0))
        if (to == selected) return true
        val now = android.os.SystemClock.elapsedRealtime()
        val since = now - lastHopAtMs
        // Hold only: one hop per HopMs. Taps always accept and restart morph.
        if (held && since < HopMs) return true
        lastHopAtMs = now
        if (held) {
            holding = true
            BrowseScrollGate.holdingHorizontal = true
        }
        val surface = if (held) "home-hold" else "home-tap"
        CoogJankLog.hop(
            surface,
            "dir=${if (delta > 0) "R" else "L"} i=$to n=${items.size} id=${items[to].id.take(28)}",
        )
        select(to, held = held, animate = true)
        return true
    }

    /**
     * Hold KeyUp: clear holding and let the in-flight hop finish.
     * Snap only when idle and not already settled on [selected].
     */
    private fun endHold() {
        if (!holding) return
        holding = false
        BrowseScrollGate.holdingHorizontal = false
        val running = anim?.isRunning == true
        if (!running) {
            settleFocused(selected, animateScroll = false)
        }
        cards.forEach { it.flushDeferredArt() }
        onSelectionChanged?.invoke(selected, false)
    }

    private fun select(
        index: Int,
        held: Boolean,
        animate: Boolean,
    ) {
        val prev = selected
        selected = index
        cards.forEachIndexed { i, c -> c.setRingSelected(shelfActive && i == index) }
        onSelectionChanged?.invoke(index, held)
        if (!animate) {
            settleFocused(index, animateScroll = false)
            return
        }
        runHop(from = prev, to = index, held = held)
    }

    private fun peek(): Int = cardW.coerceAtLeast(1)
    private fun focused(): Int = focusedW.coerceAtLeast(peek())
    private fun pitch(): Int = peek() + gap

    private fun leftIdeal(index: Int): Int = index.coerceAtLeast(0) * pitch()

    private fun maxScroll(): Int {
        val viewport = scroller.width
        if (viewport <= 0) return 0
        return (track.width - viewport).coerceAtLeast(0)
    }

    private fun inEndRegion(index: Int): Boolean {
        val max = maxScroll()
        if (max <= 0) return false
        return leftIdeal(index) >= max
    }

    private fun targetScroll(index: Int): Int {
        val max = maxScroll()
        return if (inEndRegion(index)) max else leftIdeal(index).coerceIn(0, max)
    }

    /** Settled: [index] focused width, everyone else peek; scroll pinned. */
    private fun settleFocused(index: Int, animateScroll: Boolean) {
        // Always kill in-flight morph first — otherwise a cancelled/rapid hop leaves the
        // previous animator writing mid-frame widths/scroll over the settle.
        val running = anim
        anim = null
        running?.cancel()
        hopFrom = index
        hopTo = index
        if (continueMode) {
            cards.forEachIndexed { i, c ->
                val billboard = i == index
                c.setBillboard(billboard)
                c.setFaceWidthPx(if (billboard) focused() else peek())
            }
            track.requestLayout()
        }
        val target = targetScroll(index)
        if (!animateScroll || abs(scroller.scrollX - target) < 1) {
            scroller.scrollTo(target, 0)
            return
        }
        startScrollOnly(scroller.scrollX, target, held = false)
    }

    private fun faceWidth(index: Int, progress: Float, from: Int, to: Int): Int {
        val peek = peek()
        val focused = focused()
        val p = progress.coerceIn(0f, 1f)
        if (from != to && p < 0.999f) {
            val total = peek + focused
            return when (index) {
                from -> {
                    (peek + (focused - peek) * (1f - p)).roundToInt().coerceIn(peek, focused)
                }
                to -> {
                    val fromW = (peek + (focused - peek) * (1f - p)).roundToInt()
                        .coerceIn(peek, focused)
                    (total - fromW).coerceIn(peek, focused)
                }
                else -> peek
            }
        }
        return if (index == to) focused else peek
    }

    private fun applyMorphFrame(progress: Float, from: Int, to: Int, scrollStart: Int, scrollEnd: Int) {
        if (continueMode) {
            cards.forEachIndexed { i, c ->
                val w = faceWidth(i, progress, from, to)
                // Art follows width past peek — same threshold as Compose billboard flag.
                // setBillboard may reset layout width; setFaceWidthPx must run after.
                if (i == from || i == to) {
                    c.setBillboard(w > peek() + 1)
                } else if (c.isBillboard()) {
                    c.setBillboard(false)
                }
                c.setFaceWidthPx(w)
            }
            track.requestLayout()
        }
        val endRegionOnly = inEndRegion(from) && inEndRegion(to)
        if (!endRegionOnly) {
            val want = (scrollStart + (scrollEnd - scrollStart) * progress).roundToInt()
            if (want != scroller.scrollX) scroller.scrollTo(want, 0)
        }
    }

    private fun runHop(from: Int, to: Int, held: Boolean) {
        if (cardW <= 0 || items.isEmpty()) return
        if (from == to) {
            settleFocused(to, animateScroll = false)
            return
        }
        // Interrupt: land cleanly on `from`, then morph to `to` (always animate — no snap skip).
        val gen = ++hopGen
        settleFocused(from, animateScroll = false)
        hopFrom = from
        hopTo = to

        fun beginHop() {
            if (gen != hopGen) return
            if (scroller.width <= 0) {
                post { beginHop() }
                return
            }
            // settleFocused already pinned widths + scroll at `from`.
            val scrollStart = scroller.scrollX
            val scrollEnd = targetScroll(to)
            val endRegionOnly = inEndRegion(from) && inEndRegion(to)
            val interpolator = if (held) LinearInterpolator() else DecelerateInterpolator()
            val hopAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HopMs
                this.interpolator = interpolator
                addUpdateListener { va ->
                    if (gen != hopGen) return@addUpdateListener
                    applyMorphFrame(
                        progress = va.animatedValue as Float,
                        from = from,
                        to = to,
                        scrollStart = scrollStart,
                        scrollEnd = if (endRegionOnly) scrollStart else scrollEnd,
                    )
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled || gen != hopGen) return
                        settleFocused(to, animateScroll = false)
                    }
                })
            }
            anim = hopAnim
            hopAnim.start()
        }
        // One frame after requestLayout so maxScroll / track width match settled widths.
        post { beginHop() }
    }

    private fun startScrollOnly(fromX: Int, toX: Int, held: Boolean) {
        anim?.cancel()
        if (abs(toX - fromX) < 1) {
            scroller.scrollTo(toX, 0)
            return
        }
        val interpolator = if (held) LinearInterpolator() else DecelerateInterpolator()
        val scrollAnim = ValueAnimator.ofInt(fromX, toX).apply {
            duration = HopMs
            this.interpolator = interpolator
            addUpdateListener { scroller.scrollTo(it.animatedValue as Int, 0) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scroller.scrollTo(toX, 0)
                }
            })
        }
        anim = scrollAnim
        scrollAnim.start()
    }
}
