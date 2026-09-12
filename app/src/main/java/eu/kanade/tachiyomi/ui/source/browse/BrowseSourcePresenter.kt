package eu.kanade.tachiyomi.ui.source.browse

import co.touchlab.kermit.Logger
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.flexibleadapter.items.ISectionable
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.removeCover
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.ui.source.filter.CheckboxItem
import eu.kanade.tachiyomi.ui.source.filter.CheckboxSectionItem
import eu.kanade.tachiyomi.ui.source.filter.GroupItem
import eu.kanade.tachiyomi.ui.source.filter.GroupLabelItem
import eu.kanade.tachiyomi.ui.source.filter.HeaderItem
import eu.kanade.tachiyomi.ui.source.filter.SelectItem
import eu.kanade.tachiyomi.ui.source.filter.SelectSectionItem
import eu.kanade.tachiyomi.ui.source.filter.SeparatorItem
import eu.kanade.tachiyomi.ui.source.filter.SortGroup
import eu.kanade.tachiyomi.ui.source.filter.SortItem
import eu.kanade.tachiyomi.ui.source.filter.TextItem
import eu.kanade.tachiyomi.ui.source.filter.TextSectionItem
import eu.kanade.tachiyomi.ui.source.filter.TriStateItem
import eu.kanade.tachiyomi.ui.source.filter.TriStateSectionItem
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.source.browse.filter.FilterSerializer
import yokai.domain.source.browse.filter.interactor.DeleteSavedSearch
import yokai.domain.source.browse.filter.interactor.GetSavedSearch
import yokai.domain.source.browse.filter.interactor.InsertSavedSearch
import yokai.domain.source.browse.filter.models.SavedSearch
import yokai.domain.ui.UiPreferences

// FIXME: Migrate to Compose
/**
 * Presenter of [BrowseSourceController].
 */
open class BrowseSourcePresenter(
    private val sourceId: Long,
    searchQuery: String? = null,
    var useLatest: Boolean = false,
    val sourceManager: SourceManager = Injekt.get(),
    val uiPreferences: UiPreferences = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
) : BaseCoroutinePresenter<BrowseSourceController>() {
    private val getManga: GetManga by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()

    private val deleteSavedSearch: DeleteSavedSearch by injectLazy()
    private val getSavedSearch: GetSavedSearch by injectLazy()
    private val insertSavedSearch: InsertSavedSearch by injectLazy()
    private val filterSerializer: FilterSerializer by injectLazy()

    /**
     * Selected source.
     */
    lateinit var source: CatalogueSource

    val sourceIsInitialized
        get() = this::source.isInitialized

    var filtersChanged = false

    val page: Int
        get() = pager.currentPage

    /**
     * Modifiable list of filters.
     */
    var sourceFilters = FilterList()
        set(value) {
            field = value
            filtersChanged = true
            filterItems = value.toItems()
        }

    var filterItems: List<IFlexible<*>> = emptyList()

    /**
     * List of filters used by the [Pager]. If empty alongside [query], the popular query is used.
     */
    var appliedFilters = FilterList()

    /**
     * Pager containing a list of manga results.
     */
    private lateinit var pager: Pager
    private var pagerJob: Job? = null

    /**
     * Subscription for one request from the pager.
     */
    private var nextPageJob: Job? = null

    var query = searchQuery ?: ""

    private val oldFilters = mutableListOf<Any?>()

    private var filterPollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (!::pager.isInitialized) {
            source = sourceManager.get(sourceId) as? CatalogueSource ?: return

            sourceFilters = source.getFilterList()

            if (oldFilters.isEmpty()) {
                for (i in sourceFilters) {
                    oldFilters.add(i.snapshotState())
                }
            }
            filtersChanged = false

            pollForFilterUpdates()

            runBlocking { view?.savedSearches = loadSearches() }

            getSavedSearch.subscribeAllBySourceId(sourceId)
                .map { it.applyAllSave(source.getFilterList()) }
                .onEach {
                    withUIContext { view?.savedSearches = it }
                }
                .launchIn(presenterScope)
        }
    }

    /**
     * Some sources (e.g. Keiyoushi's `KeiSource` "filter fetching") don't have every nested
     * [Filter.Group]'s options (e.g. genre/publisher lists) ready on the very first
     * [CatalogueSource.getFilterList] call: that call kicks off a background network fetch and
     * returns a placeholder (e.g. a "Tap Reset to load filters" header) until it completes,
     * caching the result to disk for next time. Poll a few times with a delay so the sheet picks
     * up the real filters once they land, instead of requiring the user to manually hit Reset -
     * but bail as soon as the user starts interacting with the sheet ([filtersChanged]), so we
     * never clobber in-progress edits.
     */
    private fun pollForFilterUpdates() {
        filterPollJob?.cancel()
        filterPollJob = presenterScope.launchIO {
            var lastSnapshot = sourceFilters.map { it.snapshotState() }
            repeat(6) {
                delay(3000)
                if (filtersChanged || !sourceIsInitialized) return@launchIO
                // A misbehaving extension can throw here (e.g. NPE building its filter list);
                // it must not crash the app - just stop polling.
                val newFilters = try {
                    source.getFilterList()
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to poll filter updates for ${source.name}" }
                    return@launchIO
                }
                val newSnapshot = newFilters.map { it.snapshotState() }
                if (newSnapshot != lastSnapshot) {
                    lastSnapshot = newSnapshot
                    sourceFilters = newFilters
                    filtersChanged = false
                    withUIContext { view?.onFilterItemsRefreshed() }
                }
            }
        }
    }

    fun filtersMatchDefault(): Boolean {
        return sourceFilters.indices.all { i ->
            sourceFilters[i].stateMatches(oldFilters.getOrNull(i))
        }
    }

    /**
     * Snapshots this filter's state for later comparison via [stateMatches]. For a [Filter.Group],
     * this recurses into its children (which may themselves be nested [Filter.Group]s) instead of
     * just capturing the list of child Filter objects, so that changes to grandchildren (e.g. a
     * checkbox inside a nested group) are detected.
     */
    private fun Filter<*>.snapshotState(): Any? {
        return if (this is Filter.Group<*>) {
            state.map { (it as Filter<*>).snapshotState() }
        } else {
            state
        }
    }

    private fun Filter<*>.stateMatches(snapshot: Any?): Boolean {
        return if (this is Filter.Group<*>) {
            val snapshotList = snapshot as? List<*> ?: return false
            state.indices.all { j ->
                (state[j] as Filter<*>).stateMatches(snapshotList.getOrNull(j))
            }
        } else {
            state == snapshot
        }
    }

    /**
     * Restarts the pager for the active source with the provided query and filters.
     *
     * @param query the query.
     * @param filters the current state of the filters (for search mode).
     */
    fun restartPager(query: String = this.query, filters: FilterList = this.appliedFilters) {
        this.query = query
        this.appliedFilters = filters

        // Create a new pager.
        pager = createPager(
            query,
            filters.takeIf { it.isNotEmpty() || query.isBlank() } ?: source.getFilterList(),
        )

        val sourceId = source.id

        val browseAsList = preferences.browseAsList()
        val sourceListType = preferences.libraryLayout()
        val outlineCovers = uiPreferences.outlineOnCovers()

        view?.unsubscribe()

        // Prepare the pager.
        pagerJob?.cancel()
        pagerJob = presenterScope.launchIO {
            pager.asFlow()
                .map { (first, second) ->
                    first to second
                        .map { networkToLocalManga(it, sourceId) }
                        .filter { !preferences.hideInLibraryItems().get() || !it.favorite }
                }
                .onEach { initializeMangas(it.second) }
                .map { (first, second) ->
                    val checkDuplicates = preferences.showDuplicateInLibraryItems().get()
                    first to second.map {
                        BrowseSourceItem(
                            it,
                            browseAsList,
                            sourceListType,
                            outlineCovers,
                            isDuplicate = checkDuplicates && !it.favorite &&
                                getManga.awaitDuplicateFavorite(it.title, it.source) != null,
                        )
                    }
                }
                .catch { error ->
                    Logger.e(error) { "Unable to prepare a page" }
                }
                .collectLatest { (page, mangas) ->
                    if (mangas.isEmpty() && page == 1) {
                        withUIContext { view?.onAddPageError(NoResultsException()) }
                        return@collectLatest
                    }
                    withUIContext { view?.onAddPage(page, mangas) }
                }
        }

        // Request first page.
        requestNext()
    }

    /**
     * Requests the next page for the active pager.
     */
    fun requestNext() {
        if (!hasNextPage()) return

        nextPageJob?.cancel()
        nextPageJob = presenterScope.launchIO {
            try {
                pager.requestNextPage()
            } catch (e: Throwable) {
                withUIContext { view?.onAddPageError(e) }
            }
        }
    }

    /**
     * Returns true if the last fetched page has a next page.
     */
    fun hasNextPage(): Boolean {
        return pager.hasNextPage
    }

    /**
     * Returns a manga from the database for the given manga from network. It creates a new entry
     * if the manga is not yet in the database.
     *
     * @param sManga the manga from the source.
     * @return a manga from the database.
     */
    private suspend fun networkToLocalManga(sManga: SManga, sourceId: Long): Manga {
        var localManga = getManga.awaitByUrlAndSource(sManga.url, sourceId)
        if (localManga == null) {
            val newManga = Manga.create(sManga.url, sManga.title, sourceId)
            newManga.copyFrom(sManga)
            newManga.id = insertManga.await(newManga)
            localManga = newManga
        } else if (localManga.title.isBlank()) {
            localManga.title = sManga.title
            updateManga.await(
                MangaUpdate(
                    id = localManga.id!!,
                    title = sManga.title,
                ),
            )
        } else if (!localManga.favorite) {
            // if the manga isn't a favorite, set its display title from source
            // if it later becomes a favorite, updated title will go to db
            localManga.title = sManga.title
        }
        return localManga
    }

    /**
     * Initialize a list of manga.
     *
     * @param mangas the list of manga to initialize.
     */
    fun initializeMangas(mangas: List<Manga>) {
        presenterScope.launchIO {
            mangas.asFlow()
                .filter { it.thumbnail_url == null && !it.initialized }
                .map { getMangaDetails(it) }
                .onEach {
                    withUIContext { view?.onMangaInitialized(it) }
                }
                .catch { e -> Logger.e(e) { "Unable to initialize manga" } }
                .collect()
        }
    }

    /**
     * Returns the initialized manga.
     *
     * @param manga the manga to initialize.
     * @return the initialized manga
     */
    private suspend fun getMangaDetails(manga: Manga): Manga {
        try {
            val networkManga = source.getMangaUpdate(
                manga.copy(),
                chapters = emptyList(),
                fetchDetails = true,
                fetchChapters = false,
            ).manga
            manga.copyFrom(networkManga)
            manga.initialized = true
            updateManga.await(manga.toMangaUpdate())
        } catch (e: Exception) {
            Logger.e(e) { "Something went wrong while trying to initialize manga" }
        }
        return manga
    }

    fun confirmDeletion(manga: Manga) {
        launchIO {
            manga.removeCover(coverCache)
            val downloadManager: DownloadManager = Injekt.get()
            downloadManager.deleteManga(manga, source)
        }
    }

    /**
     * Set the filter states for the current source.
     *
     * @param filters a list of active filters.
     */
    fun setSourceFilter(filters: FilterList) {
        filtersChanged = true
        restartPager(filters = filters)
    }

    open fun createPager(query: String, filters: FilterList): Pager {
        return if (useLatest && query.isBlank() && !filtersChanged) {
            LatestUpdatesPager(source)
        } else {
            useLatest = false
            BrowseSourcePager(source, query, filters)
        }
    }

    private fun FilterList.toItems(): List<IFlexible<*>> {
        return mapNotNull { filter ->
            when (filter) {
                is Filter.Header -> HeaderItem(filter)

                is Filter.Separator -> SeparatorItem(filter)

                is Filter.CheckBox -> CheckboxItem(filter)

                is Filter.TriState -> TriStateItem(filter)

                is Filter.Text -> TextItem(filter)

                is Filter.Select<*> -> SelectItem(filter)

                is Filter.Group<*> -> {
                    val group = GroupItem(filter)
                    group.subItems = filter.toVisibleSubItems(group)
                    group
                }

                is Filter.Sort -> {
                    val group = SortGroup(filter)
                    val subItems = filter.values.map {
                        SortItem(it, group)
                    }
                    group.subItems = subItems
                    group
                }
            }
        }
    }

    /**
     * Builds a [Filter.Group]'s direct sub-items, all sectioned under the single top-level
     * [header]. A nested [Filter.Group] becomes a [GroupLabelItem] whose own children are built
     * the same way and stashed on [GroupLabelItem.children] - not spliced into this return value -
     * so it starts collapsed; [GroupLabelItem] itself splices them into the adapter (and into
     * [header]'s own subItems model) on tap. See [GroupLabelItem]'s class doc for why this can't
     * just be a nested expandable item instead.
     */
    private fun Filter.Group<*>.toVisibleSubItems(header: GroupItem): List<ISectionable<*, GroupItem>> {
        return state.mapNotNull { type ->
            when (type) {
                is Filter.CheckBox -> CheckboxSectionItem(type).apply { setHeader(header) }

                is Filter.TriState -> TriStateSectionItem(type).apply { setHeader(header) }

                is Filter.Text -> TextSectionItem(type).apply { setHeader(header) }

                is Filter.Select<*> -> SelectSectionItem(type).apply { setHeader(header) }

                is Filter.Group<*> -> GroupLabelItem(type).apply {
                    setHeader(header)
                    children = type.toVisibleSubItems(header)
                }

                else -> null
            }
        }
    }

    fun saveSearch(name: String, query: String, filters: FilterList) {
        presenterScope.launchNonCancellableIO {
            insertSavedSearch.await(
                sourceId,
                name,
                query,
                try {
                    Json.encodeToString(filterSerializer.serialize(filters))
                } catch (e: Exception) {
                    "[]"
                },
            )
        }
    }

    fun deleteSearch(searchId: Long) {
        presenterScope.launchNonCancellableIO {
            deleteSavedSearch.await(searchId)
        }
    }

    suspend fun loadSearch(id: Long): SavedSearch? {
        return getSavedSearch.awaitById(id)?.applySave(source.getFilterList())
    }

    suspend fun loadSearches(): List<SavedSearch> {
        return getSavedSearch.awaitAllBySourceId(sourceId).applyAllSave(source.getFilterList())
    }
}
