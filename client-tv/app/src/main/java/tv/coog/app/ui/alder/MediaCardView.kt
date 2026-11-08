package tv.coog.app.ui.alder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import tv.coog.app.R
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.CardMark
import tv.coog.app.ui.CardMarks
import tv.coog.app.ui.MarkSize

/**
 * Shelf / Browse poster tile.
 * Focus ring matches Compose BrowsePosterTile: drawn in draw phase (not clipped away),
 * driven by [setRingSelected] so mid-hold hops keep a border without View focus.
 */
internal class MediaCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    enum class Mode { Immersive, Continue }

    private val binder = PosterImageBinder()
    private val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundResource(R.drawable.bg_card_placeholder)
    }
    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = GONE
    }
    private val gradient = View(context).apply {
        visibility = GONE
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.argb(184, 0, 0, 0)),
        )
    }
    private val logo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_START
        adjustViewBounds = true
        visibility = GONE
    }
    private val title = TextView(context).apply {
        setTextAppearance(context, R.style.CoogText_CardTitle)
        visibility = GONE
        maxLines = 2
    }
    private val progress = View(context).apply {
        setBackgroundResource(R.drawable.bg_progress_track)
        visibility = GONE
    }
    private val markHost = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.focus_ring_stroke)
    }
    private val ringRect = RectF()
    private val faceRect = RectF()
    private val facePath = Path()
    private val ringRadius = resources.getDimension(R.dimen.card_radius)
    private var ringSelected = false

    var mode: Mode = Mode.Immersive
        set(value) {
            if (field == value) return
            field = value
            // Continue needs edge-clip for Compose-matched ring weight. Browse rounds
            // faces via [dispatchDraw] clipPath — ImageView clipToOutline flashes on load.
            clipToOutline = value == Mode.Continue
        }
    private var peekW = 0
    private var peekH = 0
    private var billboardW = 0
    private var item: MediaItem? = null
    private var token: String = ""
    private var posterUrl: String = ""
    private var backdropUrl: String = ""
    private var logoUrl: String = ""
    private var billboard: Boolean = false
    private var boundMark: CardMark? = null
    var holding: () -> Boolean = { false }

    /** Extra listener — does not replace ring handling. */
    var onCardFocusChanged: ((Boolean) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        // Custom ring only — platform focus highlight stacks and looks thicker than Compose.
        defaultFocusHighlightEnabled = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ringRadius)
            }
        }
        clipToOutline = false
        addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(gradient, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            height = 0
            gravity = Gravity.BOTTOM
        })
        addView(logo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(10)
            bottomMargin = dp(8)
        })
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(10)
            rightMargin = dp(10)
            bottomMargin = dp(8)
        })
        addView(progress, LayoutParams(0, dp(3)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(8)
            rightMargin = dp(8)
            bottomMargin = dp(8)
        })
        addView(
            markHost,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(6)
                topMargin = dp(6)
            },
        )
        setWillNotDraw(false)
    }

    /** Compact status chip (local / cinema / soon / download) — same marks as Compose shelves. */
    fun setMark(mark: CardMark?) {
        boundMark = mark
        paintMark()
    }

    private fun paintMark() {
        val mark = boundMark
        if (mark == null) {
            markHost.visibility = GONE
            markHost.disposeComposition()
            return
        }
        // ComposeView needs a window; obtainCard often binds before addView.
        if (!isAttachedToWindow) {
            markHost.visibility = GONE
            return
        }
        markHost.visibility = VISIBLE
        markHost.setContent {
            CardMarks(mark = mark, size = MarkSize.Compact)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        paintMark()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!selectionRingMode) setRingSelected(gainFocus)
        onCardFocusChanged?.invoke(gainFocus)
    }

    /** When true, [setRingSelected] is authoritative (Browse hop / hold). */
    var selectionRingMode: Boolean = false

    fun setRingSelected(selected: Boolean) {
        if (ringSelected == selected) return
        ringSelected = selected
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Round face + ring inside one clipPath (same visual as Home Continue clipToOutline).
        // Browse used to inset the stroke so the full 3dp sat inside — looked ~2× thicker.
        faceRect.set(0f, 0f, width.toFloat(), height.toFloat())
        facePath.reset()
        facePath.addRoundRect(faceRect, ringRadius, ringRadius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(facePath)
        super.dispatchDraw(canvas)
        ringRect.set(0f, 0f, width.toFloat(), height.toFloat())
        ringPaint.color = if (ringSelected) {
            Color.WHITE
        } else {
            Color.argb((0.10f * 255).toInt(), 255, 255, 255)
        }
        canvas.drawRoundRect(ringRect, ringRadius, ringRadius, ringPaint)
        canvas.restoreToCount(save)
    }

    fun configureSizes(peekWidth: Int, height: Int, focusedWidth: Int = peekWidth) {
        peekW = peekWidth
        peekH = height
        billboardW = focusedWidth
        applyWidth()
    }

    fun bindItem(
        media: MediaItem,
        poster: String,
        backdrop: String = "",
        logo: String = "",
        authToken: String = "",
        progressFrac: Float = 0f,
    ) {
        val sameFace =
            item?.id == media.id &&
                posterUrl == poster &&
                backdropUrl == backdrop &&
                logoUrl == logo &&
                token == authToken
        item = media
        token = authToken
        posterUrl = poster
        backdropUrl = backdrop
        logoUrl = logo
        title.text = media.title
        if (progressFrac > 0.02f) {
            progress.visibility = VISIBLE
            val lp = progress.layoutParams as LayoutParams
            val want = ((if (billboard) billboardW else peekW) * progressFrac.coerceIn(0f, 1f)).toInt()
            if (lp.width != want) {
                lp.width = want
                progress.layoutParams = lp
            }
        } else if (progress.visibility != GONE) {
            progress.visibility = GONE
        }
        // Shell / art-window eviction uses blank URLs — never touch ImageViews for those.
        if (poster.isBlank() && backdrop.isBlank() && logo.isBlank()) return
        // Skip paint when the face is already on screen — avoids placeholder flash.
        if (sameFace && this.poster.hasPaintedBitmap()) return
        paint()
    }

    /** True when the poster ImageView still holds a live bitmap for [url]. */
    fun hasPosterFace(url: String): Boolean =
        posterUrl == url && this.poster.hasPaintedBitmap()

    fun boundItemId(): String = item?.id.orEmpty()

    fun setBillboard(selected: Boolean) {
        if (mode != Mode.Continue) {
            if (!billboard) return
            billboard = false
            applyWidth()
            paint()
            return
        }
        val changed = billboard != selected
        // Re-paint when becoming / staying selected without a live logo (peek clear left
        // binder state that skipped decode on the next hop).
        val needsLogo = selected && logoUrl.isNotBlank() &&
            (logo.visibility != VISIBLE || !logo.hasPaintedBitmap())
        if (!changed && !needsLogo) return
        billboard = selected
        applyWidth()
        paint()
    }

    fun isBillboard(): Boolean = billboard

    /** Mid-morph width without flipping art — [setBillboard] owns poster/backdrop. */
    fun setFaceWidthPx(widthPx: Int) {
        val w = widthPx.coerceAtLeast(1)
        val h = peekH
        if (h <= 0) return
        val lp = layoutParams ?: LayoutParams(w, h)
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        layoutParams = lp
    }

    private fun applyWidth() {
        val w = if (billboard) billboardW else peekW
        val h = peekH
        if (w <= 0 || h <= 0) return
        val lp = layoutParams ?: LayoutParams(w, h)
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            layoutParams = lp
        }
        val gradH = (h * 0.42f).toInt()
        (gradient.layoutParams as LayoutParams).let { glp ->
            if (glp.height != gradH) {
                glp.height = gradH
                gradient.layoutParams = glp
            }
        }
        val logoH = resources.getDimensionPixelSize(R.dimen.billboard_logo_height)
        val logoMaxW = resources.getDimensionPixelSize(R.dimen.billboard_logo_max_width)
        (logo.layoutParams as LayoutParams).let { llp ->
            if (llp.height != logoH || llp.width != logoMaxW) {
                llp.height = logoH
                llp.width = logoMaxW
                logo.layoutParams = llp
            }
        }
    }

    private fun paint() {
        // Paint immediately even mid-hold — Browse hold scroll must show warm posters.
        val neverDefer = { false }
        if (billboard) {
            backdrop.visibility = VISIBLE
            gradient.visibility = VISIBLE
            binder.bind(poster, posterUrl, peekW, peekH, token, neverDefer)
            binder.bind(
                backdrop,
                backdropUrl.ifBlank { posterUrl },
                billboardW,
                peekH,
                token,
                neverDefer,
            )
            if (logoUrl.isNotBlank()) {
                logo.visibility = VISIBLE
                title.visibility = GONE
                binder.bind(
                    logo,
                    logoUrl,
                    resources.getDimensionPixelSize(R.dimen.billboard_logo_max_width),
                    resources.getDimensionPixelSize(R.dimen.billboard_logo_height),
                    token,
                    neverDefer,
                    logo = true,
                )
            } else {
                logo.visibility = GONE
                title.visibility = VISIBLE
            }
        } else {
            backdrop.visibility = GONE
            gradient.visibility = GONE
            logo.visibility = GONE
            title.visibility = GONE
            binder.bind(poster, posterUrl, peekW, peekH, token, neverDefer)
            binder.clear(backdrop)
            binder.clear(logo)
        }
    }

    /** Apply Coil results deferred during hold. */
    fun flushDeferredArt() {
        binder.flush(poster)
        binder.flush(backdrop)
        binder.flush(logo)
    }

    fun needsArtPaint(): Boolean = binder.needsPaint(poster)

    fun cancelInFlightArt() {
        binder.cancelInFlight(poster)
        binder.cancelInFlight(backdrop)
        binder.cancelInFlight(logo)
    }

    /** Drop face + in-flight work for far-off Browse tiles. */
    fun releaseArt() {
        binder.clear(poster)
        binder.clear(backdrop)
        binder.clear(logo)
        posterUrl = ""
        backdropUrl = ""
        logoUrl = ""
        setMark(null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
