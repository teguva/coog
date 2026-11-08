package tv.coog.app.ui.alder

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import coil.request.ImageRequest
import coil.size.Scale
import tv.coog.app.ui.CoogArtPriority
import tv.coog.app.ui.coogPriority
import tv.coog.app.ui.coogThumbLoader

/**
 * Alder-style ImageView paint: Coil decode → setImageDrawable without Compose invalidation.
 *
 * Software RGB_565 (not HARDWARE): MiTV otherwise flashes placeholder when a HW bitmap is
 * purged or when a blank rebind clears the face between shell bind and warm-window paint.
 */
internal class PosterImageBinder {
    private class Bind {
        var url: String = ""
        var disposable: coil.request.Disposable? = null
        /** Decode finished mid-hold — apply on KeyUp so GPU upload doesn't hitch the glide. */
        var pending: android.graphics.drawable.Drawable? = null
        var widthPx: Int = 0
        var heightPx: Int = 0
        var token: String = ""
    }

    fun bind(
        view: ImageView,
        url: String,
        widthPx: Int,
        heightPx: Int,
        token: String = "",
        holding: () -> Boolean = { false },
        priority: CoogArtPriority = CoogArtPriority.OnScreen,
        /** Posters/backdrops use FILL+RGB_565; logos need FIT+ARGB (transparent PNG). */
        logo: Boolean = false,
    ) {
        val bind = (view.tag as? Bind) ?: Bind().also { view.tag = it }
        if (url.isBlank()) {
            // Shell / metadata-only rebinds must never wipe a painted face.
            // Far-tile eviction goes through [clear] only.
            return
        }
        bind.widthPx = widthPx
        bind.heightPx = heightPx
        bind.token = token
        // Already painted or in-flight for this URL — never dispose/restart (that flashes).
        // Logos: also retry when the ImageView is GONE/empty after a peek clear — hasLiveFace
        // can stay true for a stale non-bitmap placeholder and skip the real decode.
        val live = hasLiveFace(view) && (!logo || view.visibility == ImageView.VISIBLE)
        if (bind.url == url && (
                live ||
                    bind.disposable != null ||
                    bind.pending != null
                )
        ) {
            return
        }
        // Cancel only an in-flight different URL. Keep the current drawable until onSuccess
        // swaps it — never blank first. Start/apply during hold so Browse glide shows art.
        if (bind.url != url) {
            bind.disposable?.dispose()
            bind.disposable = null
            bind.pending = null
        }
        bind.url = url
        val reqUrl = url
        val w = widthPx.coerceIn(80, 960)
        val h = heightPx.coerceIn(80, 960)
        val ctx = view.context
        val req = ImageRequest.Builder(ctx)
            .data(url)
            .size(w, h)
            .scale(if (logo) Scale.FIT else Scale.FILL)
            .allowHardware(false)
            .bitmapConfig(if (logo) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
            .coogPriority(priority)
            .apply {
                if (token.isNotBlank()) {
                    addHeader("Authorization", "Bearer $token")
                }
            }
            .target(
                onSuccess = { result ->
                    if (bind.url != reqUrl) return@target
                    bind.disposable = null
                    bind.pending = null
                    view.setImageDrawable(result)
                },
                onError = {
                    if (bind.url != reqUrl) return@target
                    bind.disposable = null
                },
            )
            .build()
        bind.disposable = ctx.coogThumbLoader().enqueue(req)
    }

    /** Stop decoder work without wiping a painted face (off-window Browse tiles). */
    fun cancelInFlight(view: ImageView) {
        val bind = view.tag as? Bind ?: return
        bind.disposable?.dispose()
        bind.disposable = null
    }

    /** Apply deferred decode after hold KeyUp. */
    fun flush(view: ImageView) {
        val bind = view.tag as? Bind ?: return
        val pending = bind.pending ?: return
        bind.pending = null
        view.setImageDrawable(pending)
    }

    fun needsPaint(view: ImageView): Boolean {
        val bind = view.tag as? Bind ?: return true
        if (bind.url.isBlank()) return true
        if (bind.pending != null || bind.disposable != null) return false
        return !hasLiveFace(view)
    }

    fun boundUrl(view: ImageView): String = (view.tag as? Bind)?.url.orEmpty()

    fun clear(view: ImageView) {
        val bind = view.tag as? Bind ?: return
        bind.disposable?.dispose()
        bind.disposable = null
        bind.pending = null
        bind.url = ""
        view.setImageDrawable(null)
    }

    private fun hasLiveFace(view: ImageView): Boolean {
        val d = view.drawable ?: return false
        val bmp = (d as? BitmapDrawable)?.bitmap
        return bmp == null || !bmp.isRecycled
    }
}

internal fun ImageView.hasPaintedBitmap(): Boolean =
    (drawable as? BitmapDrawable)?.bitmap?.isRecycled == false
