package tv.coog.app.ui

import android.content.Context
import android.os.Process
import coil.ImageLoader
import coil.request.ImageRequest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import tv.coog.app.CoogApplication

/**
 * Alder-style newest-first art pools: the latest hop’s work runs before stale
 * requests left in the queue from cards raced past.
 */
internal object CoogArtDispatchers {
    /** On-screen shelf / Browse thumbs (Alder card pool = 8). */
    val onScreen: CoroutineDispatcher = newestFirst(8, "coog-art-screen")

    /** Focused hero / display-tier backdrop. */
    val urgent: CoroutineDispatcher = newestFirst(2, "coog-art-urgent")

    /** Prefetch ahead of focus — never blocks on-screen or urgent. */
    val ahead: CoroutineDispatcher = newestFirst(2, "coog-art-ahead")

    private fun newestFirst(threads: Int, name: String): CoroutineDispatcher {
        val executor = ThreadPoolExecutor(
            threads,
            threads,
            60L,
            TimeUnit.SECONDS,
            object : LinkedBlockingDeque<Runnable>() {
                override fun offer(e: Runnable): Boolean = offerFirst(e)
            },
        ) { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, name)
        }
        executor.allowCoreThreadTimeOut(true)
        return executor.asCoroutineDispatcher()
    }
}

/** Which art pool a Coil request should use. */
internal enum class CoogArtPriority {
    OnScreen,
    Urgent,
    Ahead,
}

internal fun ImageRequest.Builder.coogPriority(priority: CoogArtPriority): ImageRequest.Builder {
    val dispatcher = when (priority) {
        CoogArtPriority.OnScreen -> CoogArtDispatchers.onScreen
        CoogArtPriority.Urgent -> CoogArtDispatchers.urgent
        CoogArtPriority.Ahead -> CoogArtDispatchers.ahead
    }
    return fetcherDispatcher(dispatcher).decoderDispatcher(dispatcher)
}

internal fun Context.coogThumbLoader(): ImageLoader {
    val app = applicationContext
    return if (app is CoogApplication) app.thumbImageLoader else coil.Coil.imageLoader(this)
}

internal fun Context.coogHeroLoader(): ImageLoader {
    val app = applicationContext
    return if (app is CoogApplication) app.heroImageLoader else coil.Coil.imageLoader(this)
}
