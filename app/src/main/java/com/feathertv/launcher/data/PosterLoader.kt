package com.feathertv.launcher.data

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small in-memory poster cache + async loader. The cache lives with the
 * activity's scope and is freed when the search screen closes, keeping the
 * launcher RAM footprint flat.
 */
class PosterLoader(private val scope: CoroutineScope) {

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(url: String?, imageView: ImageView) {
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageDrawable(null)
        imageView.tag = url
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { TmdbClient.posterBitmap(url) }
            if (bitmap != null && imageView.tag == url) {
                imageView.setImageBitmap(bitmap)
                cache.put(url, bitmap)
            }
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private companion object {
        const val MAX_BYTES = 6 * 1024 * 1024
    }
}
