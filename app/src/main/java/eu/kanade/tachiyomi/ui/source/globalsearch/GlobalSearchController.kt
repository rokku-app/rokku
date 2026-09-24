package eu.kanade.tachiyomi.ui.source.globalsearch

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SourceGlobalSearchControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.main.SearchControllerInterface
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.searchhistory.SearchHistoryDelegate
import eu.kanade.tachiyomi.ui.source.searchhistory.addToSearchHistory
import eu.kanade.tachiyomi.util.addOrRemoveToFavorites
import eu.kanade.tachiyomi.util.system.extensionIntentForText
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.toolbarHeight
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * This controller shows and manages the different search result in global search.
 * This controller should only handle UI actions, IO actions should be done by [GlobalSearchPresenter]
 * [GlobalSearchCardAdapter.OnMangaClickListener] called when manga is clicked in global search
 */
open class GlobalSearchController(
    protected val initialQuery: String? = null,
    val extensionFilter: String? = null,
    bundle: Bundle? = null,
) : BaseCoroutineController<SourceGlobalSearchControllerBinding, GlobalSearchPresenter>(bundle),
    SearchControllerInterface,
    GlobalSearchAdapter.OnTitleClickListener,
    GlobalSearchCardAdapter.OnMangaClickListener {

    /**
     * Start activity in a safe way: post to view and use activity context.
     */
    private fun safeStartActivity(intent: Intent) {
        val activity = activity ?: return

        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.applicationContext.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    /**
     * Preferences helper.
     */
    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Adapter containing search results grouped by lang.
     */
    protected var adapter: GlobalSearchAdapter? = null

    private var customTitle: String? = null

    /**
     * Snackbar containing an error message when a request fails.
     */
    private var snack: Snackbar? = null
    private var lastPosition: Int = -1

    protected open val supportsSearchHistory: Boolean = true

    private val searchHistory =
        SearchHistoryDelegate(
            controller = this,
            container = { binding.root },
            recycler = { binding.recycler },
            isEnabled = { supportsSearchHistory },
            requireSearchExpanded = false,
        )

    override val mainRecycler: RecyclerView
        get() = binding.recycler

    private var showOnlyResults = false
    private var lastSearchResult: List<GlobalSearchItem> = emptyList()

    // Called when controller is initialized.
    init {
        setHasOptionsMenu(true)
    }

    override fun createBinding(inflater: LayoutInflater) = SourceGlobalSearchControllerBinding.inflate(inflater)

    override fun getSearchTitle(): String? {
        return customTitle ?: presenter.query
    }

    override val presenter = GlobalSearchPresenter(initialQuery, extensionFilter)

    override fun onTitleClick(position: Int) {
        val source = (adapter?.getItem(position) as? GlobalSearchItem)?.source ?: return
        preferences.lastUsedCatalogueSource().set(source.id)
        router.pushController(BrowseSourceController(source, presenter.query).withFadeTransaction())
        lastPosition = position
    }

    /**
     * Called when manga in global search is clicked, opens manga.
     *
     * @param manga clicked item containing manga information.
     */
    override fun onMangaClick(manga: Manga) {
        // Open MangaController.
        lastPosition =
            adapter?.currentItems?.indexOfFirst { (it as? GlobalSearchItem)?.source?.id == manga.source } ?: -1
        router.pushController(
            MangaDetailsController(manga, true, shouldLockIfNeeded = activity is SearchActivity)
                .withFadeTransaction(),
        )
    }

    /**
     * Called when manga in global search is long clicked.
     *
     * @param position clicked item containing manga information.
     */
    override fun onMangaLongClick(position: Int, adapter: GlobalSearchCardAdapter) {
        val manga = adapter.getItem(position)?.manga ?: return

        val view = view ?: return
        val activity = activity ?: return
        viewScope.launchIO {
            withUIContext { snack?.dismiss() }
            snack = manga.addOrRemoveToFavorites(
                preferences,
                view,
                activity,
                presenter.sourceManager,
                this@GlobalSearchController,
                onMangaAdded = { migrationInfo ->
                    migrationInfo?.let { (source, stillFaved) ->
                        val index = this@GlobalSearchController.adapter
                            ?.currentItems
                            ?.indexOfFirst { (it as? GlobalSearchItem)?.source?.id == source } ?: return@let
                        val item = this@GlobalSearchController.adapter?.getItem(index) as? GlobalSearchItem ?: return@let
                        val oldMangaIndex = item.results?.indexOfFirst {
                            it.manga.title.lowercase() == manga.title.lowercase()
                        } ?: return@let
                        val oldMangaItem = item.results.getOrNull(oldMangaIndex)
                        oldMangaItem?.manga?.favorite = stillFaved
                        val holder = binding.recycler.findViewHolderForAdapterPosition(index) as? GlobalSearchHolder
                        holder?.updateManga(oldMangaIndex)
                    }
                    adapter.notifyItemChanged(position)
                    snack = view.snack(MR.strings.added_to_library)
                },
                onMangaMoved = { adapter.notifyItemChanged(position) },
                onMangaDeleted = { presenter.confirmDeletion(manga) },
                scope = viewScope,
            )
            if (snack?.duration == Snackbar.LENGTH_INDEFINITE) {
                withUIContext {
                    (activity as? MainActivity)?.setUndoSnackBar(snack)
                }
            }
        }
    }

    override fun showFloatingBar() =
        activity !is SearchActivity ||
            customTitle == null ||
            extensionFilter == null

    /**
     * Adds items to the options menu.
     *
     * @param menu menu containing options.
     * @param inflater used to load the menu xml.
     */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Inflate menu.
        inflater.inflate(R.menu.catalogue_new_list, menu)

        // Initialize search menu
        activityBinding?.searchToolbar?.setQueryHint(view?.context?.getString(MR.strings.global_search), false)
        activityBinding?.searchToolbar?.searchItem?.expandActionView()
        activityBinding?.searchToolbar?.searchView?.setQuery(presenter.query, false)

        if (presenter.query.isBlank()) {
            activityBinding?.searchToolbar?.searchView?.requestFocus()
        }

        setOnQueryTextChangeListener(
            activityBinding?.searchToolbar?.searchView,
            onlyOnSubmit = true,
            hideKbOnSubmit = true,
            onTextChange = { searchHistory.setVisible(it.isNullOrBlank()) },
        ) {
            if (!searchHistory.consumeSuppressSave()) {
                preferences.addToSearchHistory(it ?: "")
            }
            searchHistory.setVisible(false)
            val query = it ?: ""
            // If the query is a manga URL from an already-installed source, open it directly
            // instead of running a full search across every enabled source.
            if (!presenter.trySearchMangaByUrl(query)) {
                // Otherwise, try to hand the URL off to the extension's own deep link
                applicationContext?.extensionIntentForText(query)?.let { intent ->
                    safeStartActivity(intent)
                }
                presenter.search(query)
            }
            setTitle() // Update toolbar title
            true
        }
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter && isControllerVisible) {
            val searchView = activityBinding?.searchToolbar?.searchView ?: return
            val searchItem = activityBinding?.searchToolbar?.searchItem ?: return
            searchItem.expandActionView()
            searchView.setQuery(presenter.query, false)
            if (presenter.query.isNotBlank()) {
                searchView.clearFocus()
            }
        }
        if (type == ControllerChangeType.POP_ENTER && lastPosition > -1) {
            val holder = binding.recycler.findViewHolderForAdapterPosition(lastPosition) as? GlobalSearchHolder
            holder?.updateAll()
            lastPosition = -1
        }
    }

    // search is always expanded here, so this only kicks in once the query is cleared
    override fun onActionViewExpand(item: MenuItem?) {
        val searchView = activityBinding?.searchToolbar?.searchView ?: return
        searchView.setQuery(presenter.query, false)
        searchHistory.setVisible(presenter.query.isBlank())
    }

    override fun onActionViewCollapse(item: MenuItem?) {
        if (activity is SearchActivity) {
            (activity as? SearchActivity)?.onBackPressedDispatcher?.onBackPressed()
        } else if (customTitle == null) {
            router.popCurrentController()
        }
    }

    /**
     * Called when the view is created
     *
     * @param view view of controller
     */
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        adapter = GlobalSearchAdapter(this)

        binding.recycler.updatePaddingRelative(
            top = (toolbarHeight ?: 0) +
                (activityBinding?.root?.rootWindowInsetsCompat?.getInsets(systemBars())?.top ?: 0),
        )

        setupFilterHeader()

        // Create recycler and set adapter.
        binding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(view.context)
        binding.recycler.adapter = adapter
        searchHistory.setUp()
        scrollViewWith(
            binding.recycler,
            padBottom = true,
            afterInsets = { searchHistory.updatePadding() },
        )
        if (extensionFilter != null) {
            customTitle = view.context?.getString(MR.strings.loading)
            setTitle()
        }

        // try to handle the query as a manga URL
        // Prevent infinite loop: only launch deep link if this is the first controller creation
        if (initialQuery == null && !presenter.trySearchMangaByUrl(presenter.query)) {
            applicationContext?.extensionIntentForText(presenter.query)?.let { intent ->
                safeStartActivity(intent)
            }
        }
    }

    override fun onDestroyView(view: View) {
        adapter = null
        searchHistory.onDestroyView()
        super.onDestroyView(view)
    }

    override fun onSaveViewState(view: View, outState: Bundle) {
        super.onSaveViewState(view, outState)
        adapter?.onSaveInstanceState(outState)
    }

    override fun onRestoreViewState(view: View, savedViewState: Bundle) {
        super.onRestoreViewState(view, savedViewState)
        adapter?.onRestoreInstanceState(savedViewState)
    }

    /**
     * Returns the view holder for the given manga.
     *
     * @param source used to find holder containing source
     * @return the holder of the manga or null if it's not bound.
     */
    private fun getHolder(source: CatalogueSource): GlobalSearchHolder? {
        val adapter = adapter ?: return null

        adapter.allBoundViewHolders.forEach { holder ->
            val item = adapter.getItem(holder.flexibleAdapterPosition) as? GlobalSearchItem
            if (item != null && source.id == item.source.id) {
                return holder as GlobalSearchHolder
            }
        }

        return null
    }

    /**
     * Add search result to adapter.
     *
     * @param searchResult result of search.
     */
    fun setItems(searchResult: List<GlobalSearchItem>) {
        if (extensionFilter != null) {
            val results = searchResult.firstOrNull()?.results
            if (results != null && searchResult.size == 1 && results.size == 1) {
                val manga = results.first().manga
                router.replaceTopController(
                    MangaDetailsController(manga, true, shouldLockIfNeeded = true)
                        .withFadeTransaction(),
                )
                return
            } else if (results != null) {
                (activity as? SearchActivity)?.setFloatingToolbar(true)
                customTitle = null
                setTitle()
                activity?.invalidateOptionsMenu()
                activityBinding?.appBar?.updateAppBarAfterY(binding.recycler)
            }
        }
        lastSearchResult = searchResult
        adapter?.updateDataSet(applyResultsFilter(searchResult))
        updateFooterAndEmptyState(searchResult)
    }

    /**
     * Called from the presenter when a manga is initialized.
     *
     * @param manga the initialized manga.
     */
    fun onMangaInitialized(source: CatalogueSource, manga: Manga) {
        getHolder(source)?.setImage(manga)
    }

    private fun applyResultsFilter(searchResult: List<GlobalSearchItem>): List<GlobalSearchItem> =
        if (showOnlyResults) searchResult.filter { !it.results.isNullOrEmpty() } else searchResult

    private fun updateFooterAndEmptyState(searchResult: List<GlobalSearchItem>) {
        val loadingCount = searchResult.count { it.results == null }
        // only touch the footer on an actual show/hide transition - removing and re-adding it on
        // every source that finishes (even though its content never changes) makes RecyclerView's
        // item animator fade it out and back in each time
        val shouldShowFooter = showOnlyResults && loadingCount > 0
        val footerShown = adapter?.scrollableFooters?.isNotEmpty() == true
        if (shouldShowFooter && !footerShown) {
            adapter?.addScrollableFooter(GlobalSearchLoadingFooterItem())
        } else if (!shouldShowFooter && footerShown) {
            adapter?.removeAllScrollableFooters()
        }

        val showEmpty =
            showOnlyResults &&
                loadingCount == 0 &&
                searchResult.isNotEmpty() &&
                applyResultsFilter(searchResult).isEmpty()
        binding.emptyView.isVisible = showEmpty
        if (showEmpty) {
            binding.emptyView.show(Icons.Outlined.SearchOff, MR.strings.no_results_found)
        }
    }

    private fun setHasResultsFilter(enabled: Boolean) {
        showOnlyResults = enabled
        preferences.onlySearchWithResults().set(enabled)
        adapter?.updateDataSet(applyResultsFilter(lastSearchResult))
        updateFooterAndEmptyState(lastSearchResult)
    }

    private fun setPinnedOnlyFilter(enabled: Boolean) {
        preferences.onlySearchPinned().set(enabled)
        presenter.refreshSourceFilter()
    }

    private fun setupFilterHeader() {
        // no header needed for extension intent search, since they shouldn't apply
        if (extensionFilter.isNullOrEmpty()) {
            showOnlyResults = preferences.onlySearchWithResults().get()
            adapter?.addScrollableHeader(
                GlobalSearchFilterHeaderItem(
                    // sourcesToUse (e.g. migration search) bypasses the pinned/all filter entirely
                    showPinnedButton = { presenter.sourceFilterEnabled },
                    isPinnedOnly = { preferences.onlySearchPinned().get() },
                    isHasResults = { showOnlyResults },
                    onPinnedClick = ::setPinnedOnlyFilter,
                    onHasResultsClick = ::setHasResultsFilter,
                ),
            )
        }
        // in case items were already pushed by the presenter before this ran
        adapter?.updateDataSet(applyResultsFilter(lastSearchResult))
        updateFooterAndEmptyState(lastSearchResult)
    }

    /**
     * Called from the presenter right before it starts resolving a pasted manga URL, so the user
     * sees a spinner instead of a blank list while the fetch is in flight.
     */
    fun showUrlSearchLoading() {
        binding.progress.isVisible = true
    }

    /**
     * Called from the presenter when the searched query resolved to a manga URL from an
     * already-installed source. Opens the manga directly, skipping the search results list.
     */
    fun openMangaFromUrl(manga: Manga) {
        router.replaceTopController(
            MangaDetailsController(manga, true, shouldLockIfNeeded = activity is SearchActivity)
                .withFadeTransaction(),
        )
    }

    /**
     * Called from the presenter when resolving a manga URL failed, so the query falls back to a
     * regular search instead of leaving the user on a blank screen.
     */
    fun onUrlSearchFailed(query: String) {
        binding.progress.isVisible = false
        // presenter.query was already set to `query` by trySearchMangaByUrl, so search() would
        // no-op on its "nothing changed" guard unless reset first.
        presenter.query = ""
        presenter.search(query)
        setTitle()
    }
}
