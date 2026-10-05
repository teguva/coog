package tv.coog.app

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class CoogApplication : Application(), ImageLoaderFactory {
    @Volatile
    private var imageLoader: ImageLoader? = null

    override fun newImageLoader(): ImageLoader {
        imageLoader?.let { return it }
        return ImageLoader.Builder(this)
            .crossfade(false)
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            imageLoader?.memoryCache?.clear()
        }
    }
}
