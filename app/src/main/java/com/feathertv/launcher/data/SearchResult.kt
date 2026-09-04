package com.feathertv.launcher.data

/**
 * One movie or TV result from the TMDB search. Kept light: search returns
 * only what the list row needs. Full details are fetched on demand when a
 * result is selected.
 */
data class SearchResult(
    val id: Long,
    val mediaType: String, // "movie" | "tv"
    val title: String,
    val year: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double
)
