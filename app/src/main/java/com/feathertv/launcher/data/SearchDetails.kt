package com.feathertv.launcher.data

/** Full title details fetched when a search result is selected. */
data class SearchDetails(
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int = 0,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val tagline: String = ""
)

/** How a title can be watched in the user's region, per TMDB watch/providers. */
data class WatchOffers(
    val flatrate: Set<String> = emptySet(),
    val rent: Set<String> = emptySet(),
    val buy: Set<String> = emptySet()
)
