package eu.kanade.tachiyomi.ui.source.searchhistory

import android.view.MenuItem
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.Controller
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.moveRecyclerViewUp
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Wires a [SearchHistoryView] for a controller
 *
 * @param container the ViewGroup the history view should be added to
 * @param recycler the recycler view behind the history view
 * @param isEnabled whether this controller supports the feature at all
 * @param extraShouldShow an extra condition for the history to show
 * @param requireSearchExpanded whether the search toolbar must be expanded before showing
 * @param extraBottomPadding extra clearance for whatever floats over the bottom of [recycler]
 * @param currentSourceId the single source currently being browsed, if any
 * @param onHidden called whenever the history overlay goes from shown to hidden
 */
class SearchHistoryDelegate(
    private val controller: Controller,
    private val container: () -> ViewGroup,
    private val recycler: () -> RecyclerView?,
    private val isEnabled: () -> Boolean = { true },
    private val extraShouldShow: () -> Boolean = { true },
    private val requireSearchExpanded: Boolean = true,
    private val extraBottomPadding: () -> Int = { 0 },
    private val currentSourceId: () -> Long? = { null },
    private val onHidden: () -> Unit = {},
) {
    private val preferences: PreferencesHelper by lazy { Injekt.get() }

    var view: SearchHistoryView? = null
        private set

    private var suppressNextSave = false

    fun consumeSuppressSave(): Boolean {
        val value = suppressNextSave
        suppressNextSave = false
        return value
    }

    private fun searchView(): SearchView? = controller.activityBinding?.searchToolbar?.searchView

    fun setUp(): SearchHistoryView? {
        view?.let { return it }
        if (!isEnabled() || !preferences.showBrowseSearchHistory().get()) return null
        view =
            SearchHistoryView(container().context).apply {
                isVisible = false
                onQueryClicked = { entry ->
                    if (entry.name != null) suppressNextSave = true
                    searchView()?.setQuery(entry.query, true)
                }
                onQueryFilled = { searchView()?.setQuery(it, false) }
                onHistoryEmptied = { setVisible(false) }
                onSaveHistoryEntry = { entry -> openSaveDialog(entry, isExisting = false) }
                onEditSavedSearch = { entry -> openSaveDialog(entry, isExisting = true) }
                container().addView(this, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        return view
    }

    private fun openSaveDialog(
        entry: SearchHistoryEntry,
        isExisting: Boolean,
    ) {
        val activity = controller.activity ?: return
        SaveSearchDialog.show(activity, entry, isExisting)
    }

    fun updatePadding() {
        val recycler = recycler() ?: return
        val appBar = controller.activityBinding?.appBar
        val visibleAppBarHeight =
            if (appBar != null) {
                (appBar.height + appBar.translationY).toInt().coerceAtLeast(0)
            } else {
                recycler.paddingTop
            }
        view?.setContentPadding(top = visibleAppBarHeight, bottom = recycler.paddingBottom + extraBottomPadding())
    }

    fun setVisible(show: Boolean) {
        val historyView = setUp() ?: return
        val sourceId = currentSourceId()
        historyView.currentSourceId = sourceId
        val shouldShow =
            show &&
                extraShouldShow() &&
                (!requireSearchExpanded || controller.activityBinding?.searchToolbar?.isSearchExpanded == true) &&
                SearchHistoryView.hasHistory(preferences, sourceId = sourceId)
        if (historyView.historyShown == shouldShow) return
        historyView.setAnimatedVisible(shouldShow)
        if (!shouldShow) {
            recycler()?.suppressLayout(false)
            onHidden()
            return
        }
        val revealHistory = {
            recycler()?.suppressLayout(true)
            updatePadding()
            historyView.scrollToTop()
        }
        if (controller.activityBinding?.appBar?.y != 0f) {
            controller.moveRecyclerViewUp()
            recycler()?.post { revealHistory() }
        } else {
            revealHistory()
        }
    }

    fun onActionViewExpand(item: MenuItem?) = setVisible(true)

    fun onActionViewCollapse(item: MenuItem?) = setVisible(false)

    fun onDestroyView() {
        view = null
    }
}
