package com.feathertv.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.feathertv.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale

/**
 * Ultra-lightweight TMDB client with thread-safe LRU caching and strict rate limiting.
 * All work runs on Dispatchers.IO; network requests are concurrency-controlled
 * via Semaphore(4) and throttled to <= 35 req / 10s window to strictly respect TMDB API rules.
 */
object TmdbClient {

    private const val BASE = "https://api.themoviedb.org/3"
    private const val POSTER_BASE = "https://image.tmdb.org/t/p/w185"
    private const val DETAIL_POSTER_BASE = "https://image.tmdb.org/t/p/w342"
    private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
    private const val TIMEOUT_MS = 8000
    private const val MAX_RESULTS = 18

    private val httpSemaphore = Semaphore(4)
    private val requestTimestamps = ArrayDeque<Long>()
    private val rateLimitLock = Any()
    private const val MAX_REQUESTS_PER_10S = 35
    private const val MIN_REQUEST_INTERVAL_MS = 40L
    private var lastRequestTime = 0L

    private suspend fun throttle() = withContext(Dispatchers.IO) {
        var waitTime = 0L
        synchronized(rateLimitLock) {
            val now = System.currentTimeMillis()
            while (requestTimestamps.isNotEmpty() && now - requestTimestamps.first() > 10_000L) {
                requestTimestamps.removeFirst()
            }
            if (requestTimestamps.size >= MAX_REQUESTS_PER_10S) {
                val oldest = requestTimestamps.first()
                waitTime = (oldest + 10_050L) - now
            }
            val intervalWait = (lastRequestTime + MIN_REQUEST_INTERVAL_MS) - now
            if (intervalWait > waitTime) {
                waitTime = intervalWait
            }
            val scheduledTime = now + (if (waitTime > 0) waitTime else 0L)
            lastRequestTime = scheduledTime
            requestTimestamps.addLast(scheduledTime)
        }
        if (waitTime > 0) {
            delay(waitTime)
        }
    }

    // Thread-safe in-memory LRU caches
    private val searchCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, SearchPage>(40, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchPage>?): Boolean = size > 40
        }
    )

    private val offersCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, WatchOffers>(300, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WatchOffers>?): Boolean = size > 300
        }
    )

    private val detailsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, SearchDetails>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchDetails>?): Boolean = size > 100
        }
    )

    val apiKey: String
        get() = BuildConfig.TMDB_API_KEY

    data class SearchPage(
        val results: List<SearchResult>,
        val page: Int,
        val totalPages: Int
    )

    /** Broad movies + TV search, with full support for actor and director filmographies. */
    suspend fun search(query: String): List<SearchResult> = searchPage(query, 1).results

    suspend fun searchPage(query: String, page: Int = 1): SearchPage = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (apiKey.isBlank() || trimmed.isBlank()) return@withContext SearchPage(emptyList(), 1, 1)

        val cacheKey = "${trimmed.lowercase(Locale.US)}:$page"
        searchCache[cacheKey]?.let { return@withContext it }

        try {
            val url = "$BASE/search/multi?api_key=$apiKey&query=${encode(trimmed)}&page=$page&language=en-US"
            val json = getJson(url)
            val totalPages = json.optInt("total_pages", 1).coerceAtLeast(1)
            val results = json.optJSONArray("results") ?: return@withContext SearchPage(emptyList(), page, totalPages)
            val out = mutableListOf<SearchResult>()
            val seenIds = mutableSetOf<Pair<String, Long>>()

            fun parseItem(item: JSONObject): SearchResult? {
                val type = item.optString("media_type")
                if (type != "movie" && type != "tv") return null
                val id = item.optLong("id")
                if (id == 0L || !seenIds.add(type to id)) return null
                val title = if (type == "movie") item.optString("title") else item.optString("name")
                if (title.isBlank()) return null
                val date =
                    if (type == "movie") item.optString("release_date")
                    else item.optString("first_air_date")
                return SearchResult(
                    id = id,
                    mediaType = type,
                    title = title,
                    year = date.take(4),
                    posterUrl = item.optString("poster_path")
                        .takeIf { it.isNotBlank() }
                        ?.let { POSTER_BASE + it },
                    backdropUrl = item.optString("backdrop_path")
                        .takeIf { it.isNotBlank() }
                        ?.let { BACKDROP_BASE + it },
                    rating = item.optDouble("vote_average", 0.0)
                )
            }

            var primaryPersonId = 0L

            for (i in 0 until results.length()) {
                if (out.size >= MAX_RESULTS) break
                val item = results.optJSONObject(i) ?: continue
                val type = item.optString("media_type")
                if (type == "movie" || type == "tv") {
                    parseItem(item)?.let { out += it }
                } else if (type == "person") {
                    if (primaryPersonId == 0L) {
                        primaryPersonId = item.optLong("id")
                    }
                    val knownFor = item.optJSONArray("known_for")
                    if (knownFor != null) {
                        for (k in 0 until knownFor.length()) {
                            if (out.size >= MAX_RESULTS) break
                            val kItem = knownFor.optJSONObject(k) ?: continue
                            parseItem(kItem)?.let { out += it }
                        }
                    }
                }
            }

            // If an actor/director was searched and we have room, fetch their filmography credits
            if (primaryPersonId != 0L && out.size < MAX_RESULTS) {
                try {
                    val creditsUrl = "$BASE/person/$primaryPersonId/combined_credits?api_key=$apiKey&language=en-US"
                    val creditsJson = getJson(creditsUrl)
                    val cast = creditsJson.optJSONArray("cast")
                    if (cast != null) {
                        val candidates = mutableListOf<JSONObject>()
                        for (c in 0 until cast.length()) {
                            val cItem = cast.optJSONObject(c) ?: continue
                            val cType = cItem.optString("media_type")
                            if (cType != "movie" && cType != "tv") continue
                            if (cItem.optString("poster_path").isBlank()) continue
                            if (cItem.optInt("vote_count", 0) < 15) continue
                            candidates += cItem
                        }
                        candidates.sortByDescending {
                            it.optInt("vote_count", 0) * 10.0 + it.optDouble("popularity", 0.0)
                        }
                        val startIndex = (page - 1) * MAX_RESULTS
                        val pagedCandidates = candidates.drop(startIndex)
                        for (cItem in pagedCandidates) {
                            if (out.size >= MAX_RESULTS) break
                            parseItem(cItem)?.let { out += it }
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to existing results
                }
            }

            val searchPage = SearchPage(out, page, totalPages)
            searchCache[cacheKey] = searchPage
            searchPage
        } catch (e: Exception) {
            SearchPage(emptyList(), page, 1)
        }
    }

    /** Flexible TMDB Discover endpoint supporting mediaType, sort, genre, and subscription providers. */
    suspend fun discover(
        mediaType: String = "movie",
        sortBy: String = "popularity.desc",
        genreId: Int? = null,
        providerIds: String = "",
        region: String = "FI",
        page: Int = 1
    ): SearchPage = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SearchPage(emptyList(), 1, 1)

        val path = if (mediaType == "tv") "tv" else "movie"
        val cacheKey = "discover:$path:$sortBy:$genreId:$providerIds:${region.uppercase(Locale.US)}:$page"
        searchCache[cacheKey]?.let { return@withContext it }

        try {
            val sb = StringBuilder("$BASE/discover/$path?api_key=$apiKey&language=en-US&sort_by=$sortBy&page=$page")
            if (providerIds.isNotBlank()) {
                sb.append("&watch_region=${region.uppercase(Locale.US)}&with_watch_providers=$providerIds&with_watch_monetization_types=flatrate")
            }
            if (genreId != null && genreId > 0) {
                sb.append("&with_genres=$genreId")
            }
            if (sortBy.startsWith("vote_average")) {
                sb.append("&vote_count.gte=100")
            } else if (sortBy.startsWith("primary_release_date") || sortBy.startsWith("first_air_date")) {
                sb.append("&vote_count.gte=15")
            }

            val json = getJson(sb.toString())
            val totalPages = json.optInt("total_pages", 1).coerceAtLeast(1)
            val results = json.optJSONArray("results") ?: return@withContext SearchPage(emptyList(), page, totalPages)
            val out = mutableListOf<SearchResult>()

            for (i in 0 until results.length()) {
                if (out.size >= MAX_RESULTS) break
                val item = results.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                if (id == 0L) continue
                val title = if (path == "tv") item.optString("name") else item.optString("title")
                if (title.isBlank()) continue
                val date = if (path == "tv") item.optString("first_air_date") else item.optString("release_date")
                out += SearchResult(
                    id = id,
                    mediaType = path,
                    title = title,
                    year = date.take(4),
                    posterUrl = item.optString("poster_path")
                        .takeIf { it.isNotBlank() }
                        ?.let { POSTER_BASE + it },
                    backdropUrl = item.optString("backdrop_path")
                        .takeIf { it.isNotBlank() }
                        ?.let { BACKDROP_BASE + it },
                    rating = item.optDouble("vote_average", 0.0)
                )
            }

            val searchPage = SearchPage(out, page, totalPages)
            searchCache[cacheKey] = searchPage
            searchPage
        } catch (e: Exception) {
            SearchPage(emptyList(), page, 1)
        }
    }

    /** Full details for one selected title. */
    suspend fun details(id: Long, mediaType: String): SearchDetails =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext SearchDetails()
            val cacheKey = "$mediaType:$id"
            detailsCache[cacheKey]?.let { return@withContext it }
            try {
                val path = if (mediaType == "tv") "tv" else "movie"
                val url = "$BASE/$path/$id?api_key=$apiKey&language=en-US"
                val json = getJson(url)
                val genres = json.optJSONArray("genres")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            arr.optJSONObject(it)?.optString("name")
                        }
                    }
                    ?: emptyList()
                val runtime = if (mediaType == "tv") {
                    val epRuntime = json.optJSONArray("episode_run_time")?.optInt(0) ?: 0
                    if (epRuntime > 0) epRuntime else json.optInt("number_of_seasons", 0)
                } else {
                    json.optInt("runtime", 0)
                }
                val details = SearchDetails(
                    overview = json.optString("overview"),
                    genres = genres,
                    runtimeMinutes = runtime,
                    backdropUrl = json.optString("backdrop_path")
                        .takeIf { it.isNotBlank() }
                        ?.let { BACKDROP_BASE + it },
                    posterUrl = json.optString("poster_path")
                        .takeIf { it.isNotBlank() }
                        ?.let { DETAIL_POSTER_BASE + it },
                    tagline = json.optString("tagline")
                )
                detailsCache[cacheKey] = details
                details
            } catch (e: Exception) {
                SearchDetails()
            }
        }

    /** Watch offers (flatrate/rent/buy) for a title in [region]. */
    suspend fun offers(id: Long, mediaType: String, region: String): WatchOffers =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext WatchOffers()
            val cacheKey = "$mediaType:$id:${region.uppercase(Locale.US)}"
            offersCache[cacheKey]?.let { return@withContext it }
            try {
                val path = if (mediaType == "tv") "tv" else "movie"
                val url = "$BASE/$path/$id/watch/providers?api_key=$apiKey&language=en-US"
                val json = getJson(url)
                val results = json.optJSONObject("results") ?: return@withContext WatchOffers()
                val regional =
                    results.optJSONObject(region.uppercase(Locale.US))
                        ?: return@withContext WatchOffers()
                val offers = WatchOffers(
                    flatrate = providerNames(regional, "flatrate"),
                    rent = providerNames(regional, "rent"),
                    buy = providerNames(regional, "buy")
                )
                offersCache[cacheKey] = offers
                offers
            } catch (e: Exception) {
                WatchOffers()
            }
        }

    private fun providerNames(regional: JSONObject, key: String): Set<String> {
        val arr = regional.optJSONArray(key) ?: return emptySet()
        val names = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("provider_name")
            if (!name.isNullOrBlank()) names += name
        }
        return names
    }

    suspend fun posterBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "yb-launcher")
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val bytes = conn.inputStream.use { it.readBytes() }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun getJson(url: String): JSONObject = httpSemaphore.withPermit {
        throttle()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "yb-launcher")
            conn.connect()
            var code = conn.responseCode
            if (code == 429) {
                // Rate limited: wait 1 second and retry once
                conn.disconnect()
                delay(1000)
                throttle()
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "yb-launcher")
                conn.connect()
                code = conn.responseCode
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("TMDB HTTP $code")
            }
            conn.inputStream.use { input ->
                val text = input.bufferedReader().use { it.readText() }
                JSONObject(text)
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
