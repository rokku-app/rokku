package eu.kanade.tachiyomi.ui.source.searchhistory

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.GenericItem
import com.mikepenz.fastadapter.adapters.ItemAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SearchHistoryViewBinding
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.clipboardManager
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.GroupedRowDivider
import eu.kanade.tachiyomi.util.view.snack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class SearchHistoryView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val preferences: PreferencesHelper by injectLazy()
    private val binding: SearchHistoryViewBinding
    private val savedHeaderAdapter = ItemAdapter<GenericItem>()
    private val savedItemsAdapter = ItemAdapter<GenericItem>()
    private val recentHeaderAdapter = ItemAdapter<GenericItem>()
    private val recentItemsAdapter = ItemAdapter<GenericItem>()
    private val fastAdapter: FastAdapter<GenericItem> =
        FastAdapter.with(listOf(savedHeaderAdapter, savedItemsAdapter, recentHeaderAdapter, recentItemsAdapter))
    private var scope: CoroutineScope? = null
    private var visibilityAnimator: ViewPropertyAnimator? = null
    private var lastHistory: List<SearchHistoryEntry> = emptyList()
    private var lastSaved: List<SearchHistoryEntry> = emptyList()
    private var savedSearchesCollapsed = false

    var historyShown = false
        private set

    var onQueryClicked: (SearchHistoryEntry) -> Unit = { _ -> }
    var onQueryFilled: (String) -> Unit = { _ -> }
    var onHistoryEmptied: () -> Unit = { }
    var onSaveHistoryEntry: (SearchHistoryEntry) -> Unit = { _ -> }
    var onEditSavedSearch: (SearchHistoryEntry) -> Unit = { _ -> }
    var currentSourceId: Long? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getResourceColor(R.attr.background))
        binding = SearchHistoryViewBinding.inflate(LayoutInflater.from(context), this)
        binding.recycler.layoutManager = LinearLayoutManager(context)
        binding.recycler.adapter = fastAdapter
        binding.recycler.addItemDecoration(
            GroupedRowDivider(
                context,
                isGroupedRow = { it is SearchRowItem.ViewHolder },
            ),
        )
        fastAdapter.onClickListener = { _, _, item, _ ->
            if (item is SearchRowItem) {
                val entry = item.entry
                if (entry.name == null) {
                    preferences.addToSearchHistory(entry.query, entry.sourceId)
                }
                onQueryClicked(entry)
            }
            true
        }
        fastAdapter.onLongClickListener = { view, _, item, _ ->
            if (item is SearchRowItem) {
                showRowPopup(view, item.entry)
                true
            } else {
                false
            }
        }

        val swipeCallback =
            SwipeDeleteCallback { position ->
                (fastAdapter.getItem(position) as? SearchRowItem)?.entry?.let { deleteEntry(it) }
            }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recycler)

        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        context
                            .getSystemService<InputMethodManager>()
                            ?.hideSoftInputFromWindow(windowToken, 0)
                    }
                }
            },
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        combine(
            preferences.browseSearchHistory().changes(),
            preferences.savedSearches().changes(),
            ::setHistory,
        ).launchIn(newScope)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope?.cancel()
        scope = null
    }

    fun setAnimatedVisible(visible: Boolean) {
        if (historyShown == visible) return
        historyShown = visible
        visibilityAnimator?.cancel()
        if (visible) {
            alpha = 0f
            isVisible = true
            if (isLaidOut) {
                fadeIn()
            } else {
                doOnNextLayout { if (historyShown) fadeIn() }
            }
        } else {
            visibilityAnimator =
                animate()
                    .alpha(0f)
                    .setDuration(FADE_DURATION)
                    .withEndAction {
                        isVisible = false
                        visibilityAnimator = null
                    }
            visibilityAnimator?.start()
        }
    }

    private fun fadeIn() {
        visibilityAnimator =
            animate()
                .alpha(1f)
                .setDuration(FADE_DURATION)
                .withEndAction { visibilityAnimator = null }
        visibilityAnimator?.start()
    }

    fun setContentPadding(
        top: Int,
        bottom: Int,
    ) {
        binding.recycler.updatePaddingRelative(top = top, bottom = bottom)
    }

    fun scrollToTop() = binding.recycler.scrollToPosition(0)

    private fun showRowPopup(
        anchor: View,
        entry: SearchHistoryEntry,
    ) {
        val isSaved = entry.name != null
        val popup = PopupMenu(anchor.context, anchor, Gravity.NO_GRAVITY)
        popup.menu.add(0, 0, 0, if (isSaved) R.string.edit else R.string.save)
        popup.menu.add(0, 1, 1, R.string.copy_value)
        popup.menu.add(0, 2, 2, R.string.remove)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> if (isSaved) onEditSavedSearch(entry) else onSaveHistoryEntry(entry)
                1 -> copyQuery(entry.query)
                else -> deleteEntry(entry)
            }
            true
        }
        popup.show()
    }

    private fun copyQuery(query: String) {
        if (query.isBlank()) return
        val label = context.getString(R.string.search)
        context.clipboardManager.setPrimaryClip(ClipData.newPlainText(label, query))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            snack(context.getString(R.string._copied_to_clipboard, label))
                .moveAboveSafeAreas(context)
        }
    }

    private fun deleteEntry(entry: SearchHistoryEntry) {
        val isSaved = entry.name != null
        if (isSaved) preferences.removeSavedSearch(entry.id) else preferences.removeFromSearchHistory(entry)
        val undoSnack =
            snack(R.string.search_removed) {
                setAction(R.string.undo) {
                    if (isSaved) preferences.reinsertSavedSearch(entry) else preferences.reinsertIntoSearchHistory(entry)
                }
            }
        undoSnack.moveAboveSafeAreas(context)
        (context as? MainActivity)?.setUndoSnackBar(undoSnack)
    }

    private fun computeShownSaved(): List<SearchHistoryEntry> =
        lastSaved
            .applicableTo(currentSourceId)
            .distinctBy { it.id }
            .sortedBy { it.name?.lowercase() ?: "" }

    private fun buildRowItems(
        entries: List<SearchHistoryEntry>,
        onTrailingClicked: (SearchHistoryEntry) -> Unit,
    ): List<GenericItem> =
        entries.mapIndexed { index, entry ->
            SearchRowItem(
                entry = entry,
                isTopOfGroup = index == 0,
                isBottomOfGroup = index == entries.lastIndex,
                onFillClicked = { onQueryFilled(it) },
                onTrailingClicked = onTrailingClicked,
            )
        }

    private fun refreshSavedItems() {
        val shownSaved = computeShownSaved()
        savedItemsAdapter.set(
            if (savedSearchesCollapsed) emptyList() else buildRowItems(shownSaved, onEditSavedSearch),
        )
    }

    private fun setHistory(
        history: List<SearchHistoryEntry>,
        saved: List<SearchHistoryEntry>,
    ) {
        lastHistory = history
        lastSaved = saved
        val shownHistory = history.distinctBy { it.id }
        val shownSaved = computeShownSaved()

        savedHeaderAdapter.set(
            if (shownSaved.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    SearchHistorySectionHeaderItem(
                        R.string.save_search_title,
                        collapsible = true,
                        collapsed = savedSearchesCollapsed,
                        onToggleCollapsed = {
                            savedSearchesCollapsed = !savedSearchesCollapsed
                            refreshSavedItems()
                            savedSearchesCollapsed
                        },
                    ),
                )
            },
        )
        refreshSavedItems()
        recentHeaderAdapter.set(
            if (shownHistory.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    SearchHistorySectionHeaderItem(
                        R.string.recent_searches,
                        showClearAll = true,
                        onClearAll = { preferences.clearSearchHistory() },
                    ),
                )
            },
        )
        recentItemsAdapter.set(buildRowItems(shownHistory, onSaveHistoryEntry))
        if (shownHistory.isEmpty() && shownSaved.isEmpty() && isVisible) {
            onHistoryEmptied()
        }
    }

    companion object {
        fun hasHistory(
            preferences: PreferencesHelper = Injekt.get(),
            sourceId: Long? = null,
        ): Boolean =
            preferences.showBrowseSearchHistory().get() &&
                (
                    preferences.browseSearchHistory().get().isNotEmpty() ||
                        preferences.savedSearches().get().applicableTo(sourceId).isNotEmpty()
                    )
    }
}

fun Snackbar.moveAboveSafeAreas(context: Context): Snackbar {
    val mainActivity = context as? MainActivity
    val bottomNav = mainActivity?.binding?.bottomNav?.takeIf { it.isVisible }
    val bottomNavHeight = bottomNav?.let { (it.height - it.translationY).toInt().coerceAtLeast(0) } ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(view) { snackView, insets ->
        val bottomInset =
            insets
                .getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
                .bottom
        snackView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = maxOf(bottomInset, bottomNavHeight)
        }
        insets
    }
    return this
}

private const val FADE_DURATION = 150L
