package tv.coog.app

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import tv.coog.app.data.PlaybackCaps

class CoogApplication : Application(), ImageLoaderFactory {
    @Volatile
    private var imageLoader: ImageLoader? = null

    override fun onCreate() {
        super.onCreate()
        PlaybackCaps.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        imageLoader?.let { return it }
        return ImageLoader.Builder(this)
            .crossfade(false)
            // Server art URLs are stable (version query); ignore short Cache-Control from proxies.
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Browse art must not compete with ExoPlayer's on-heap media buffer.
                    .maxSizePercent(0.12)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_disk"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .build()
            .also { imageLoader = it }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Keep shelf thumbs warm through UI_HIDDEN (player / brief background).
        // Only clear when the process is under real background pressure.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            imageLoader?.memoryCache?.clear()
        }
    }
}
