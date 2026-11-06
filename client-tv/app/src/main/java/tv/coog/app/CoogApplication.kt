package tv.coog.app

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import tv.coog.app.data.CoogHttp
import tv.coog.app.data.PlaybackCaps
import tv.coog.app.ui.CoogArtDispatchers

/**
 * Dual Coil loaders (Alder cards vs screens):
 * - [thumbImageLoader] — shelf / Browse RGB_565 thumbs (default [ImageLoaderFactory])
 * - [heroImageLoader] — display/backdrop plates so heroes cannot evict card thumbs
 *
 * Fetch/decode use newest-first pools ([CoogArtDispatchers]).
 */
class CoogApplication : Application(), ImageLoaderFactory {
    @Volatile
    private var thumbLoader: ImageLoader? = null

    @Volatile
    private var heroLoader: ImageLoader? = null

    @Volatile
    private var disk: DiskCache? = null

    val thumbImageLoader: ImageLoader
        get() = thumbLoader ?: newThumbLoader().also { thumbLoader = it }

    val heroImageLoader: ImageLoader
        get() = heroLoader ?: newHeroLoader().also { heroLoader = it }

    override fun onCreate() {
        super.onCreate()
        PlaybackCaps.init(this)
    }

    /** Default Coil / AsyncImage path — card thumbs. */
    override fun newImageLoader(): ImageLoader = thumbImageLoader

    private fun sharedDiskCache(): DiskCache {
        disk?.let { return it }
        return DiskCache.Builder()
            .directory(cacheDir.resolve("coil_disk"))
            .maxSizeBytes(96L * 1024L * 1024L)
            .build()
            .also { disk = it }
    }

    private fun newThumbLoader(): ImageLoader {
        val heap = Runtime.getRuntime().maxMemory()
        val diskCache = sharedDiskCache()
        return ImageLoader.Builder(this)
            .okHttpClient(CoogHttp.client)
            .crossfade(false)
            .respectCacheHeaders(false)
            .fetcherDispatcher(CoogArtDispatchers.onScreen)
            .decoderDispatcher(CoogArtDispatchers.onScreen)
            .memoryCache {
                // Alder cards ≈ maxMemory/4 — keep shelf thumbs when heroes churn.
                MemoryCache.Builder(this)
                    .maxSizeBytes((heap / 4).toInt().coerceAtLeast(16 * 1024 * 1024))
                    .build()
            }
            .diskCache { diskCache }
            .build()
    }

    private fun newHeroLoader(): ImageLoader {
        val heap = Runtime.getRuntime().maxMemory()
        val diskCache = sharedDiskCache()
        return ImageLoader.Builder(this)
            .okHttpClient(CoogHttp.client)
            .crossfade(false)
            .respectCacheHeaders(false)
            .fetcherDispatcher(CoogArtDispatchers.urgent)
            .decoderDispatcher(CoogArtDispatchers.urgent)
            .memoryCache {
                // Alder screens ≈ maxMemory/6 — stage + fade-out neighbour.
                MemoryCache.Builder(this)
                    .maxSizeBytes((heap / 6).toInt().coerceAtLeast(12 * 1024 * 1024))
                    .build()
            }
            .diskCache { diskCache }
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Keep shelf thumbs warm through UI_HIDDEN (player / brief background).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            thumbLoader?.memoryCache?.clear()
            heroLoader?.memoryCache?.clear()
        }
    }
}
