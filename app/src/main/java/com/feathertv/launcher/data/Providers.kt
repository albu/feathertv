package com.feathertv.launcher.data

/**
 * The user's streaming subscriptions, mapped to their Android TV packages and
 * to the provider names TMDB returns for watch/providers.
 */
object Providers {
    const val APPLE_PACKAGE = "com.apple.atve.androidtv.appletv"
    const val PRIME_PACKAGE = "com.amazon.amazonvideo.livingroom"
    const val NETFLIX_PACKAGE = "com.netflix.ninja"
    const val DISNEY_PACKAGE = "com.disney.disneyplus"

    val ALL_PROVIDERS = listOf(
        APPLE_PACKAGE to "Apple TV+",
        PRIME_PACKAGE to "Prime Video",
        NETFLIX_PACKAGE to "Netflix",
        DISNEY_PACKAGE to "Disney+"
    )

    /** Launch preference when a title is available on several subscriptions. */
    val PREFERENCE_ORDER = listOf(APPLE_PACKAGE, PRIME_PACKAGE, NETFLIX_PACKAGE, DISNEY_PACKAGE)

    fun labelForPackage(packageName: String): String? = when (packageName) {
        APPLE_PACKAGE -> "Apple TV+"
        PRIME_PACKAGE -> "Prime Video"
        NETFLIX_PACKAGE -> "Netflix"
        DISNEY_PACKAGE -> "Disney+"
        else -> null
    }

    /** TMDB watch provider IDs used for /discover/movie queries. */
    fun tmdbProviderIdsForPackages(packages: Set<String>): String {
        val ids = mutableListOf<Int>()
        for (pkg in packages) {
            when (pkg) {
                APPLE_PACKAGE -> ids += 350
                PRIME_PACKAGE -> { ids += 9; ids += 119 }
                NETFLIX_PACKAGE -> ids += 8
                DISNEY_PACKAGE -> ids += 337
            }
        }
        return ids.distinct().joinToString("|")
    }

    /**
     * Map TMDB provider names to subscription packages we can launch.
     * Excludes third-party add-on channels (e.g., "Apple TV Amazon Channel",
     * "Paramount+ Amazon Channel", "HBO Max Amazon Channel").
     */
    fun matchPackages(tmdbProviderNames: Set<String>): Set<String> {
        val out = mutableSetOf<String>()
        if (tmdbProviderNames.any { isAppleTvProvider(it) }) {
            out += APPLE_PACKAGE
        }
        if (tmdbProviderNames.any { isPrimeProvider(it) }) {
            out += PRIME_PACKAGE
        }
        if (tmdbProviderNames.any { isNetflixProvider(it) }) {
            out += NETFLIX_PACKAGE
        }
        if (tmdbProviderNames.any { isDisneyProvider(it) }) {
            out += DISNEY_PACKAGE
        }
        return out
    }

    private fun isAppleTvProvider(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower.contains("amazon")) return false
        return lower == "apple tv" ||
            lower == "apple tv+" ||
            lower == "apple tv plus" ||
            lower == "apple tv store" ||
            lower == "apple tv app"
    }

    private fun isPrimeProvider(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower.contains("channel")) return false
        return lower.contains("prime video") ||
            lower == "amazon video" ||
            lower == "amazon prime" ||
            lower == "prime"
    }

    private fun isNetflixProvider(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower == "netflix" || lower.contains("netflix")
    }

    private fun isDisneyProvider(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower == "disney plus" || lower == "disney+" || lower.contains("disney")
    }
}
