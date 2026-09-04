package com.feathertv.launcher

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.SoundEffectConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.feathertv.launcher.data.AppPreferences
import com.feathertv.launcher.data.AppRepository
import com.feathertv.launcher.data.PosterLoader
import com.feathertv.launcher.data.Providers
import com.feathertv.launcher.data.SearchResult
import com.feathertv.launcher.data.TmdbClient
import com.feathertv.launcher.databinding.ActivitySearchBinding
import com.feathertv.launcher.ui.SearchAdapter
import com.feathertv.launcher.ui.SearchDetailDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen movie/TV search & discovery backed by TMDB. */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: SearchAdapter
    private val preferences by lazy { AppPreferences(this) }
    private val repository by lazy { AppRepository(this) }
    private lateinit var posterLoader: PosterLoader

    private var debounceJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var filterJob: Job? = null

    private val allResults = mutableListOf<SearchResult>()
    private val visibleResults = mutableListOf<SearchResult>()

    private var isDiscoverMode = true
    private var currentQuery = ""
    private var currentPage = 1
    private var totalPages = 1
    private var isLoadingMore = false

    // Search Block State
    private var onlySearchIncluded = false

    // Discover Block State
    private enum class DiscoverScope { ACTIVE_SUBS, OTHER_PLATFORMS }
    private var discoverScope = DiscoverScope.ACTIVE_SUBS
    private var discoverMediaType = "movie"
    private var discoverSort = "popularity.desc"
    private var discoverGenreId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        posterLoader = PosterLoader(lifecycleScope)
        adapter = SearchAdapter(
            posterLoader = posterLoader,
            onItemClick = { openResult(it) },
            onLoadMoreClick = { loadMore() }
        )
        val gridLayoutManager = GridLayoutManager(this, 4)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == SearchAdapter.TYPE_FOOTER) 4 else 1
            }
        }
        binding.rvResults.layoutManager = gridLayoutManager
        binding.rvResults.adapter = adapter

        setupSearchBlock()
        setupDiscoverBlock()

        updateUiStates()
        runDiscover(1)
    }

    private fun setupSearchBlock() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                debounceJob?.cancel()
                val q = binding.etSearch.text.toString().trim()
                if (q.isNotBlank()) {
                    runSearch(q)
                }
                true
            } else {
                false
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounceSearch(s?.toString()?.trim().orEmpty())
            }
        })

        binding.btnSearchFilterActive.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            onlySearchIncluded = !onlySearchIncluded
            updateUiStates()
            if (!isDiscoverMode && currentQuery.isNotBlank()) {
                if (onlySearchIncluded) {
                    applyIncludedFilter(allResults)
                } else {
                    filterJob?.cancel()
                    visibleResults.clear()
                    visibleResults.addAll(allResults)
                    adapter.submit(visibleResults.toList(), canLoadMore = currentPage < totalPages)
                    if (visibleResults.isEmpty()) {
                        showEmpty(getString(R.string.search_no_results))
                    } else {
                        hideEmpty()
                    }
                }
            }
        }
    }

    private fun setupDiscoverBlock() {
        // Discover Scopes
        binding.chipDiscoverActive.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            discoverScope = DiscoverScope.ACTIVE_SUBS
            runDiscover(1)
        }
        binding.chipDiscoverOther.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            discoverScope = DiscoverScope.OTHER_PLATFORMS
            runDiscover(1)
        }

        // Sort Chips
        binding.chipPopular.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            discoverSort = "popularity.desc"
            runDiscover(1)
        }
        binding.chipTopRated.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            discoverSort = "vote_average.desc"
            runDiscover(1)
        }
        binding.chipNew.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            discoverSort = if (discoverMediaType == "tv") "first_air_date.desc" else "primary_release_date.desc"
            runDiscover(1)
        }

        // Format Chips
        binding.chipMovies.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            discoverMediaType = "movie"
            if (discoverSort.startsWith("first_air_date")) discoverSort = "primary_release_date.desc"
            runDiscover(1)
        }
        binding.chipTv.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            discoverMediaType = "tv"
            if (discoverSort.startsWith("primary_release_date")) discoverSort = "first_air_date.desc"
            runDiscover(1)
        }

        // Genre Chips
        val genreChips = mapOf(
            binding.chipSciFi to Pair(878, 10765),
            binding.chipAction to Pair(28, 10759),
            binding.chipComedy to Pair(35, 35),
            binding.chipDrama to Pair(18, 18),
            binding.chipThriller to Pair(53, 9648),
            binding.chipAnimation to Pair(16, 16),
            binding.chipHorror to Pair(27, 9648),
            binding.chipDoc to Pair(99, 99),
            binding.chipCrime to Pair(80, 80)
        )
        genreChips.forEach { (chip, genrePair) ->
            chip.setOnClickListener {
                it.playSoundEffect(SoundEffectConstants.CLICK)
                val targetId = if (discoverMediaType == "tv") genrePair.second else genrePair.first
                discoverGenreId = if (discoverGenreId == targetId) null else targetId
                runDiscover(1)
            }
        }
    }

    private fun updateUiStates() {
        // Search Filter UI
        binding.btnSearchFilterActive.isSelected = onlySearchIncluded
        if (onlySearchIncluded) {
            binding.ivSearchFilterCheck.imageTintList = ColorStateList.valueOf(Color.parseColor("#729BFF"))
            binding.tvSearchFilterLabel.setTextColor(Color.parseColor("#E0E6FF"))
        } else {
            binding.ivSearchFilterCheck.imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
            binding.tvSearchFilterLabel.setTextColor(Color.parseColor("#808099"))
        }

        // Discover Scope Chips
        binding.chipDiscoverActive.isSelected = isDiscoverMode && discoverScope == DiscoverScope.ACTIVE_SUBS
        binding.chipDiscoverOther.isSelected = isDiscoverMode && discoverScope == DiscoverScope.OTHER_PLATFORMS

        // Discover Sort Chips
        binding.chipPopular.isSelected = isDiscoverMode && discoverSort == "popularity.desc"
        binding.chipTopRated.isSelected = isDiscoverMode && discoverSort == "vote_average.desc"
        binding.chipNew.isSelected = isDiscoverMode && (discoverSort.startsWith("primary_release_date") || discoverSort.startsWith("first_air_date"))

        // Discover Format Chips
        binding.chipMovies.isSelected = isDiscoverMode && discoverMediaType == "movie"
        binding.chipTv.isSelected = isDiscoverMode && discoverMediaType == "tv"

        // Discover Genre Chips
        val genreChips = mapOf(
            binding.chipSciFi to Pair(878, 10765),
            binding.chipAction to Pair(28, 10759),
            binding.chipComedy to Pair(35, 35),
            binding.chipDrama to Pair(18, 18),
            binding.chipThriller to Pair(53, 9648),
            binding.chipAnimation to Pair(16, 16),
            binding.chipHorror to Pair(27, 9648),
            binding.chipDoc to Pair(99, 99),
            binding.chipCrime to Pair(80, 80)
        )
        genreChips.forEach { (chip, genrePair) ->
            val targetId = if (discoverMediaType == "tv") genrePair.second else genrePair.first
            chip.isSelected = isDiscoverMode && discoverGenreId == targetId
        }
    }

    private fun getDiscoverProviderIds(): String {
        return when (discoverScope) {
            DiscoverScope.ACTIVE_SUBS -> {
                val active = preferences.getActiveSubscriptions()
                Providers.tmdbProviderIdsForPackages(active)
            }
            DiscoverScope.OTHER_PLATFORMS -> {
                val all = Providers.PREFERENCE_ORDER.toSet()
                Providers.tmdbProviderIdsForPackages(all)
            }
        }
    }

    private fun runDiscover(page: Int = 1) {
        debounceJob?.cancel()
        searchJob?.cancel()
        filterJob?.cancel()
        isDiscoverMode = true
        currentQuery = ""
        currentPage = page
        isLoadingMore = false

        if (binding.etSearch.text.isNotEmpty()) {
            binding.etSearch.setText("")
        }

        updateUiStates()

        if (page == 1) {
            allResults.clear()
            visibleResults.clear()
            adapter.submit(emptyList(), canLoadMore = false)
            showEmpty(getString(R.string.search_searching))
        }

        searchJob = lifecycleScope.launch {
            val providerIds = getDiscoverProviderIds()
            val pageData = TmdbClient.discover(
                mediaType = discoverMediaType,
                sortBy = discoverSort,
                genreId = discoverGenreId,
                providerIds = providerIds,
                region = preferences.searchRegion,
                page = page
            )
            currentPage = page
            totalPages = pageData.totalPages

            if (page == 1) {
                allResults.clear()
                allResults.addAll(pageData.results)
                visibleResults.clear()
                visibleResults.addAll(pageData.results)
            } else {
                allResults.addAll(pageData.results)
                visibleResults.addAll(pageData.results)
            }

            if (visibleResults.isNotEmpty()) {
                hideEmpty()
            } else {
                showEmpty(getString(R.string.search_no_results))
            }
            adapter.submit(visibleResults.toList(), canLoadMore = currentPage < totalPages)
        }
    }

    private fun debounceSearch(query: String) {
        debounceJob?.cancel()
        searchJob?.cancel()
        filterJob?.cancel()
        if (query.length < MIN_QUERY_LENGTH) {
            if (!isDiscoverMode) {
                runDiscover(1)
            }
            return
        }
        debounceJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(query)
        }
    }

    private fun runSearch(query: String) {
        debounceJob?.cancel()
        searchJob?.cancel()
        filterJob?.cancel()
        isDiscoverMode = false
        currentQuery = query
        currentPage = 1
        totalPages = 1
        isLoadingMore = false
        allResults.clear()
        visibleResults.clear()

        updateUiStates()

        if (query.length < MIN_QUERY_LENGTH) {
            runDiscover(1)
            return
        }
        showEmpty(getString(R.string.search_searching))
        searchJob = lifecycleScope.launch {
            val pageData = TmdbClient.searchPage(query, 1)
            currentPage = 1
            totalPages = pageData.totalPages
            allResults.addAll(pageData.results)

            if (onlySearchIncluded) {
                applyIncludedFilter(allResults)
            } else {
                visibleResults.addAll(pageData.results)
                adapter.submit(visibleResults.toList(), canLoadMore = currentPage < totalPages)
                if (visibleResults.isEmpty()) {
                    showEmpty(getString(R.string.search_no_results))
                } else {
                    hideEmpty()
                }
            }
        }
    }

    private fun applyIncludedFilter(results: List<SearchResult>) {
        if (results.isEmpty()) {
            visibleResults.clear()
            adapter.submit(emptyList(), canLoadMore = false)
            if (currentQuery.length >= MIN_QUERY_LENGTH) {
                showEmpty(getString(R.string.search_no_results))
            }
            return
        }
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            showEmpty(getString(R.string.search_checking_availability))
            val region = preferences.searchRegion
            val activeSubs = preferences.getActiveSubscriptions()
            val installedPackages = Providers.PREFERENCE_ORDER.filter {
                repository.isPackageInstalled(it) && it in activeSubs
            }.toSet()

            val filtered = withContext(Dispatchers.IO) {
                results.map { res ->
                    async {
                        val offers = TmdbClient.offers(res.id, res.mediaType, region)
                        val flatrateSet = Providers.matchPackages(offers.flatrate)
                        val isIncluded = flatrateSet.any { it in installedPackages }
                        if (isIncluded) res else null
                    }
                }.awaitAll().filterNotNull()
            }

            visibleResults.clear()
            visibleResults.addAll(filtered)

            if (visibleResults.isNotEmpty()) {
                hideEmpty()
                adapter.submit(visibleResults.toList(), canLoadMore = currentPage < totalPages)
            } else {
                adapter.submit(emptyList(), canLoadMore = currentPage < totalPages)
                showEmpty(getString(R.string.search_no_included_results))
            }
        }
    }

    private fun loadMore() {
        if (isLoadingMore || currentPage >= totalPages) return
        isLoadingMore = true
        adapter.setLoadingMoreState(true)
        lifecycleScope.launch {
            val nextPage = currentPage + 1

            if (isDiscoverMode) {
                val providerIds = getDiscoverProviderIds()
                val pageData = TmdbClient.discover(
                    mediaType = discoverMediaType,
                    sortBy = discoverSort,
                    genreId = discoverGenreId,
                    providerIds = providerIds,
                    region = preferences.searchRegion,
                    page = nextPage
                )
                currentPage = nextPage
                totalPages = pageData.totalPages
                allResults.addAll(pageData.results)
                visibleResults.addAll(pageData.results)
                adapter.submit(visibleResults.toList(), canLoadMore = currentPage < totalPages)
            } else {
                if (currentQuery.isBlank()) {
                    isLoadingMore = false
                    return@launch
                }
                val pageData = TmdbClient.searchPage(currentQuery, nextPage)
                currentPage = nextPage
                totalPages = pageData.totalPages
                allResults.addAll(pageData.results)

                if (onlySearchIncluded) {
                    val region = preferences.searchRegion
                    val activeSubs = preferences.getActiveSubscriptions()
                    val installedPackages = Providers.PREFERENCE_ORDER.filter {
                        repository.isPackageInstalled(it) && it in activeSubs
                    }.toSet()
                    val newlyIncluded = withContext(Dispatchers.IO) {
                        pageData.results.map { res ->
                            async {
                                val offers = TmdbClient.offers(res.id, res.mediaType, region)
                                val flatrateSet = Providers.matchPackages(offers.flatrate)
                                val isIncluded = flatrateSet.any { it in installedPackages }
                                if (isIncluded) res else null
                            }
                        }.awaitAll().filterNotNull()
                    }
                    visibleResults.addAll(newlyIncluded)
                    adapter.submit(visibleResults.toList(), canLoadMore = currentPage < totalPages)
                    if (visibleResults.isNotEmpty()) {
                        hideEmpty()
                    }
                } else {
                    visibleResults.addAll(pageData.results)
                    adapter.submit(visibleResults.toList(), canLoadMore = currentPage < totalPages)
                    if (visibleResults.isNotEmpty()) {
                        hideEmpty()
                    }
                }
            }

            isLoadingMore = false
        }
    }

    private fun openResult(result: SearchResult) {
        detailJob?.cancel()
        val dialog = SearchDetailDialog(this, result, posterLoader) { pkg -> openProvider(result, pkg) }
        dialog.show()
        detailJob = lifecycleScope.launch {
            val details = TmdbClient.details(result.id, result.mediaType)
            val offers = TmdbClient.offers(
                result.id,
                result.mediaType,
                preferences.searchRegion
            )
            val activeSubs = preferences.getActiveSubscriptions()
            val installedProviders = Providers.PREFERENCE_ORDER.filter { repository.isPackageInstalled(it) }
            val flatrateSet = Providers.matchPackages(offers.flatrate)
            val rentSet = Providers.matchPackages(offers.rent)
            val buySet = Providers.matchPackages(offers.buy)

            val actions = installedProviders.map { pkg ->
                val label = Providers.labelForPackage(pkg) ?: pkg
                val isSubscribed = pkg in activeSubs
                val isIncluded = isSubscribed && (pkg in flatrateSet)
                val inRent = pkg in rentSet
                val inBuy = pkg in buySet

                when {
                    isIncluded -> com.feathertv.launcher.data.ProviderAction(
                        packageName = pkg,
                        title = getString(R.string.search_watch_on) + " " + label,
                        tagText = getString(R.string.search_tag_included),
                        isPrimary = true
                    )
                    inRent && inBuy -> com.feathertv.launcher.data.ProviderAction(
                        packageName = pkg,
                        title = getString(R.string.search_tag_rent_buy) + " on " + label,
                        tagText = getString(R.string.search_tag_rent_buy),
                        isPrimary = false
                    )
                    inRent -> com.feathertv.launcher.data.ProviderAction(
                        packageName = pkg,
                        title = getString(R.string.search_tag_rent) + " on " + label,
                        tagText = getString(R.string.search_tag_rent),
                        isPrimary = false
                    )
                    inBuy -> com.feathertv.launcher.data.ProviderAction(
                        packageName = pkg,
                        title = getString(R.string.search_tag_buy) + " on " + label,
                        tagText = getString(R.string.search_tag_buy),
                        isPrimary = false
                    )
                    else -> com.feathertv.launcher.data.ProviderAction(
                        packageName = pkg,
                        title = getString(R.string.search_search_on) + " " + label,
                        tagText = getString(R.string.search_tag_search),
                        isPrimary = false
                    )
                }
            }.sortedByDescending { it.isPrimary }
            dialog.populate(details, actions)
        }
    }

    private fun openProvider(result: SearchResult, pkg: String) {
        if (pkg == Providers.APPLE_PACKAGE) {
            val uri = Uri.parse("https://tv.apple.com/search?searchParam=" + Uri.encode(result.title))
            launchDeepLink(uri, Providers.APPLE_PACKAGE, pkg)
            return
        }
        if (pkg == Providers.PRIME_PACKAGE) {
            val uri = Uri.parse("https://app.primevideo.com/search?phrase=" + Uri.encode(result.title))
            launchDeepLink(uri, Providers.PRIME_PACKAGE, pkg)
            return
        }
        repository.launchPackage(pkg)
    }

    private fun launchDeepLink(uri: Uri, targetPackage: String, fallbackPackage: String) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            repository.launchPackage(fallbackPackage)
        }
    }

    override fun onDestroy() {
        debounceJob?.cancel()
        searchJob?.cancel()
        detailJob?.cancel()
        filterJob?.cancel()
        posterLoader.clear()
        super.onDestroy()
    }

    private fun showEmpty(text: String) {
        binding.tvEmpty.text = text
        binding.tvEmpty.visibility = View.VISIBLE
    }

    private fun hideEmpty() {
        binding.tvEmpty.visibility = View.GONE
    }

    private companion object {
        const val DEBOUNCE_MS = 1200L
        private const val MIN_QUERY_LENGTH = 2
    }
}
