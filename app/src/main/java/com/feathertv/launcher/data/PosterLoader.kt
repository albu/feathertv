package com.feathertv.launcher.data

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap

/**
 * Small in-memory poster cache + async loader with job lifecycle tracking.
 * Prevents thread storms by canceling obsolete view requests, downsampling bitmaps,
 * and freeing all native allocations when search screens close.
 */
class PosterLoader(private val scope: CoroutineScope) {

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val runningJobs = Collections.synchronizedMap(WeakHashMap<ImageView, Job>())

    fun load(url: String?, imageView: ImageView, reqWidth: Int = 200, reqHeight: Int = 300) {
        // Cancel any pending load for this recycled/rebound ImageView
        cancel(imageView)

        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            imageView.tag = null
            return
        }

        val cacheKey = "$url:$reqWidth:$reqHeight"
        cache.get(cacheKey)?.let {
            imageView.setImageBitmap(it)
            imageView.tag = url
            return
        }

        imageView.setImageDrawable(null)
        imageView.tag = url

        val job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                TmdbClient.posterBitmap(url, reqWidth, reqHeight)
            }
            if (isActive && bitmap != null && imageView.tag == url) {
                imageView.setImageBitmap(bitmap)
                cache.put(cacheKey, bitmap)
            }
            runningJobs.remove(imageView)
        }
        runningJobs[imageView] = job
    }

    fun cancel(imageView: ImageView) {
        runningJobs.remove(imageView)?.cancel()
    }

    fun cancelAll() {
        synchronized(runningJobs) {
            runningJobs.values.forEach { it.cancel() }
            runningJobs.clear()
        }
    }

    fun clear() {
        cancelAll()
        cache.evictAll()
    }

    private companion object {
        const val MAX_BYTES = 6 * 1024 * 1024
    }
}
