package eu.kanade.tachiyomi.ui.source.globalsearch

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.removeCover
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import java.util.Date
import java.util.Locale

/**
 * Presenter of [GlobalSearchController]
 * Function calls should be done from here. UI calls should be done from the controller.
 *
 * @param sourceManager manages the different sources.
 * @param preferences manages the preference calls.
 */
open class GlobalSearchPresenter(
    private val initialQuery: String? = "",
    private val initialExtensionFilter: String? = null,
    private val sourcesToUse: List<CatalogueSource>? = null,
    val sourceManager: SourceManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
) : BaseCoroutinePresenter<GlobalSearchController>() {
    private val getManga: GetManga by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()

    /** Enabled sources. */
    lateinit var sources: List<CatalogueSource>
        private set

    /** false for migration searches & extension intent searches */
    val sourceFilterEnabled: Boolean = sourcesToUse == null && initialExtensionFilter.isNullOrEmpty()

    private var fetchSourcesJob: Job? = null

    // Debounces setItems() emissions: with several sources completing within milliseconds of
    // each other, emitting (and fully re-sorting/rebinding the list) once per source cancels
    // each cover's in-flight Coil load before it can finish, permanently stranding it blank.
    // Coalescing rapid completions into a single emission gives loads a chance to complete.
    private var setItemsJob: Job? = null

    private var loadTime = hashMapOf<Long, Long>()

    var query = ""

    private val fetchImageFlow = MutableSharedFlow<Pair<List<Manga>, Source>>()

    private var fetchImageJob: Job? = null

    private val extensionManager: ExtensionManager by injectLazy()

    private var extensionFilter: String? = null

    var items: List<GlobalSearchItem> = emptyList()

    private val semaphore = Semaphore(5)

    override fun onCreate() {
        super.onCreate()

        extensionFilter = initialExtensionFilter
        sources = getSourcesToQuery()

        if (items.isEmpty()) {
            // Perform a search with previous or initial state, unless the query is a manga URL
            // that trySearchMangaByUrl can resolve directly (see its kdoc).
            val query = initialQuery.orEmpty()
            if (!trySearchMangaByUrl(query)) {
                search(query)
            }
        }
        presenterScope.launchUI {
            view?.setItems(items)
        }
    }

    fun refreshSourceFilter() {
        if (!sourceFilterEnabled) return
        sources = getSourcesToQuery()
        search(query, force = true)
    }

    /**
     * Returns a list of enabled sources ordered by language and name.
     *
     * @return list containing enabled sources.
     */
    protected open fun getEnabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hiddenCatalogues = preferences.hiddenSources().get()
        val pinnedCatalogues = preferences.pinnedCatalogues().get()

        val list = sourceManager.getCatalogueSources()
            .filter { it.lang in languages }
            .filterNot { it.id.toString() in hiddenCatalogues }
            .sortedBy { "(${it.lang}) ${it.name}" }

        return if (sourceFilterEnabled && preferences.onlySearchPinned().get()) {
            list.filter { it.id.toString() in pinnedCatalogues }
        } else {
            list.sortedBy { it.id.toString() !in pinnedCatalogues }
        }
    }

    private fun getSourcesToQuery(): List<CatalogueSource> {
        if (sourcesToUse != null) return sourcesToUse
        val filter = extensionFilter
        val enabledSources = getEnabledSources()
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        val languages = preferences.enabledLanguages().get()
        val filterSources = extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filterIsInstance<CatalogueSource>()

        val result = filterSources.filter { it in enabledSources }

        if (result.isEmpty()) {
            return enabledSources
        }

        return result
    }

    /**
     * Creates a catalogue search item
     */
    private fun scheduleSetItems() {
        setItemsJob?.cancel()
        setItemsJob = presenterScope.launch {
            delay(250)
            withUIContext { view?.setItems(items) }
        }
    }

    protected open fun createCatalogueSearchItem(
        source: CatalogueSource,
        results: List<GlobalSearchMangaItem>?,
    ): GlobalSearchItem {
        return GlobalSearchItem(source, results)
    }

    fun confirmDeletion(manga: Manga) {
        manga.removeCover(coverCache)
        val downloadManager: DownloadManager = Injekt.get()
        sourceManager.get(manga.source)?.let { source ->
            downloadManager.deleteManga(manga, source)
        }
    }

    /**
     * Initiates a search for manga per catalogue.
     *
     * @param query query on which to search.
     * @param force if true, re-runs the search even if [query] hasn't changed - used when the
     * set of [sources] to query changes instead, e.g. toggling the pinned/all sources filter.
     */
    fun search(
        query: String,
        force: Boolean = false,
    ) {
        // Return if there's nothing to do
        if (this.query == query && !force) return

        // Update query
        this.query = query

        // Create image fetch subscription
        initializeFetchImageSubscription()

        // Create items with the initial state
        val initialItems = sources.map { createCatalogueSearchItem(it, null) }
        items = initialItems
        presenterScope.launchUI { view?.setItems(items) }
        val pinnedSourceIds = preferences.pinnedCatalogues().get()

        fetchSourcesJob?.cancel()
        fetchSourcesJob = presenterScope.launch {
            sources.forEach { source ->
                launch mainLaunch@{
                    semaphore.withPermit {
                        if (this@GlobalSearchPresenter.items.find { it.source == source }?.results != null) {
                            return@mainLaunch
                        }
                        val mangas = try {
                            source.getSearchManga(1, query, source.getFilterList())
                        } catch (error: Exception) {
                            MangasPage(emptyList(), false)
                        }
                            .mangas.take(10)
                            .mapNotNull { networkToLocalManga(it, source.id) }
                        fetchImage(mangas, source)
                        if (mangas.isNotEmpty() && !loadTime.containsKey(source.id)) {
                            loadTime[source.id] = Date().time
                        }
                        val checkDuplicates = preferences.showDuplicateInLibraryItems().get()
                        val result = createCatalogueSearchItem(
                            source,
                            mangas.map {
                                GlobalSearchMangaItem(
                                    it,
                                    getManga.subscribeByUrlAndSource(it.url, it.source),
                                    isDuplicate = checkDuplicates && !it.favorite &&
                                        getManga.awaitDuplicateFavorite(it.title, it.source) != null,
                                )
                            },
                        )
                        items = items
                            .map { item -> if (item.source == result.source) result else item }
                            .sortedWith(
                                compareBy(
                                    // Bubble up sources that actually have results
                                    { it.results.isNullOrEmpty() },
                                    // Same as initial sort, i.e. pinned first then alphabetically
                                    { it.source.id.toString() !in pinnedSourceIds },
                                    { loadTime[it.source.id] ?: 0L },
                                    { "${it.source.name.lowercase(Locale.getDefault())} (${it.source.lang})" },
                                ),
                            )
                        scheduleSetItems()
                    }
                }
            }
        }
    }

    /**
     * Finds an installed [CatalogueSource] whose base URL is a prefix of [query], meaning the
     * pasted text is likely a manga URL for a source the user already has installed.
     *
     * Sources with the same base URL (e.g. one HttpSource extension registered per language, like
     * MangaDex) are disambiguated by preferring an English source, then any other of the user's
     * enabled sources -- otherwise chapters can come back empty for a language the manga doesn't
     * have translations in, even though the user has multiple languages enabled.
     *
     * @return the matching source, or null if none match.
     */
    private fun getUrlMatchingSource(query: String): CatalogueSource? {
        if (!query.startsWith("http://", true) && !query.startsWith("https://", true)) return null
        val matches = sourceManager.getCatalogueSources()
            .filterIsInstance<HttpSource>()
            .filter { query.startsWith(it.baseUrl, ignoreCase = true) }
        val enabledMatches = matches.filter { it in sources }
        return enabledMatches.firstOrNull { it.lang == "en" }
            ?: enabledMatches.firstOrNull()
            ?: matches.firstOrNull()
    }

    /**
     * Finds an installed [ResolvableSource] that recognizes [query] as a manga URL, letting the
     * source itself resolve it instead of assuming the URL path maps to `manga.url`.
     *
     * @return the matching source, or null if none recognize [query] as a manga URL.
     */
    private fun getResolvableMangaSource(query: String): ResolvableSource? {
        return sourceManager.getCatalogueSources()
            .filterIsInstance<ResolvableSource>()
            .firstOrNull { it.getUriType(query) == UriType.Manga }
    }

    private var urlSearchJob: Job? = null
    private var urlSearchQuery: String? = null

    /**
     * If [query] is a manga URL matching an installed source, fetches that manga directly and
     * asks the view to open it, skipping the per-source search entirely.
     *
     * Sources implementing [ResolvableSource] (extensions-lib 1.5+) resolve the URL themselves.
     * Otherwise, [query] is run through the matching source's own [CatalogueSource.getSearchManga]
     * as a normal search query -- many sources (MangaDex, Madara-based templates, etc.) detect
     * URL-shaped queries there and resolve straight to the matching manga. `manga.url` can't be
     * reliably derived by stripping the source's base URL off the pasted URL, since many sources
     * only store an id or slug there, so the app never constructs it itself.
     *
     * @return true if [query] matched an installed source and is being resolved.
     */
    fun trySearchMangaByUrl(query: String): Boolean {
        // This exact query was already matched and is being (or was already) resolved -- e.g. the
        // presenter's initial resolution in onCreate() and the controller's onViewCreated()
        // fallback both call this for the same initial query. Report it as handled again without
        // starting a second, redundant resolution that could re-navigate and undo the first one.
        if (query.isNotEmpty() && query == urlSearchQuery) return true

        val resolvableSource = getResolvableMangaSource(query)
        val urlMatchingSource = if (resolvableSource == null) getUrlMatchingSource(query) else null
        if (resolvableSource == null && urlMatchingSource == null) return false

        this.query = query
        urlSearchQuery = query
        view?.showUrlSearchLoading()
        urlSearchJob = presenterScope.launch {
            try {
                val (source, sManga) = if (resolvableSource != null) {
                    val resolvedManga = resolvableSource.getManga(query) ?: run {
                        withUIContext { view?.onUrlSearchFailed(query) }
                        return@launch
                    }
                    resolvableSource to resolvedManga
                } else {
                    val source = urlMatchingSource!!
                    val results = source.getSearchManga(1, query, source.getFilterList()).mangas
                    val resolvedManga = results.singleOrNull() ?: run {
                        withUIContext { view?.onUrlSearchFailed(query) }
                        return@launch
                    }
                    source to resolvedManga
                }
                val manga = networkToLocalManga(sManga, source.id) ?: return@launch
                // Leave manga.initialized as-is (don't fetch details/chapters here) -- same as
                // the normal single-result-search shortcut below, so MangaDetailsController's own
                // presenter does the full fetch (including chapters) when it opens.
                withUIContext { view?.openMangaFromUrl(manga) }
            } catch (e: Exception) {
                withUIContext { view?.onUrlSearchFailed(query) }
            }
        }
        return true
    }

    /**
     * Initialize a list of manga.
     *
     * @param manga the list of manga to initialize.
     */
    private fun fetchImage(manga: List<Manga>, source: Source) {
        presenterScope.launch {
            fetchImageFlow.emit(Pair(manga, source))
        }
    }

    /**
     * Subscribes to the initializer of manga details and updates the view if needed.
     */
    private fun initializeFetchImageSubscription() {
        fetchImageJob?.cancel()
        fetchImageJob = fetchImageFlow.onEach { (mangaList, source) ->
            mangaList
                .filter { it.thumbnail_url == null && !it.initialized }
                .forEach {
                    presenterScope.launchIO {
                        try {
                            val manga = getMangaDetails(it, source)
                            withUIContext {
                                view?.onMangaInitialized(source as CatalogueSource, manga)
                            }
                        } catch (e: Exception) {
                            withUIContext {
                                view?.onMangaInitialized(source as CatalogueSource, it)
                            }
                        }
                    }
                }
        }.launchIn(presenterScope)
    }

    /**
     * Initializes the given manga.
     *
     * @param manga the manga to initialize.
     * @return The initialized manga.
     */
    private suspend fun getMangaDetails(manga: Manga, source: Source): Manga {
        val networkManga = source.getMangaUpdate(
            manga.copy(),
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
        manga.copyFrom(networkManga)
        manga.initialized = true
        updateManga.await(manga.toMangaUpdate())
        return manga
    }

    /**
     * Returns a manga from the database for the given manga from network. It creates a new entry
     * if the manga is not yet in the database.
     *
     * @param sManga the manga from the source.
     * @return a manga from the database.
     */
    protected open suspend fun networkToLocalManga(sManga: SManga, sourceId: Long): Manga? {
        var localManga = getManga.awaitByUrlAndSource(sManga.url, sourceId)
        if (localManga == null) {
            val newManga =
                try {
                    Manga.create(sManga.url, sManga.title, sourceId)
                } catch (_: UninitializedPropertyAccessException) {
                    return null
                }
            newManga.copyFrom(sManga)
            newManga.id = insertManga.await(newManga)
            localManga = newManga
        } else if (!localManga.favorite) {
            // if the manga isn't a favorite, set its display title from source
            // if it later becomes a favorite, updated title will go to db
            localManga.title =
                try {
                    sManga.title
                } catch (_: UninitializedPropertyAccessException) {
                    return localManga
                }
        }
        return localManga
    }
}
