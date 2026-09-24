package eu.kanade.tachiyomi.ui.source.searchhistory

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.isIncognitoModeForSource
import kotlinx.serialization.Serializable

private const val SEARCH_HISTORY_LIMIT = 20

/**
 * A remembered search - either an auto-recorded recent query, or a user-named saved one. [name]
 * is only ever non-null for the latter; the item/view layer reads its presence to tell which kind
 * a given entry is.
 *
 * @param id each entry's identity (and its recency, since a bump replaces it with a fresh one).
 * @param showOnAllSources only meaningful when [name] is set - whether a saved search is offered
 * on every source, or only [sourceId].
 */
@Serializable
data class SearchHistoryEntry(
    val id: Long,
    val query: String,
    val sourceId: Long? = null,
    val name: String? = null,
    val showOnAllSources: Boolean = false,
)

fun PreferencesHelper.addToSearchHistory(
    query: String,
    sourceId: Long? = null,
) {
    if (!showBrowseSearchHistory().get()) return
    if (isIncognitoModeForSource(sourceId, this)) return
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return
    val pref = browseSearchHistory()
    val entry = SearchHistoryEntry(System.currentTimeMillis(), trimmedQuery, sourceId)
    val history = listOf(entry) + pref.get().filterNot { it.query.equals(trimmedQuery, true) }
    pref.set(history.take(SEARCH_HISTORY_LIMIT))
}

fun PreferencesHelper.removeFromSearchHistory(entry: SearchHistoryEntry) {
    val pref = browseSearchHistory()
    pref.set(pref.get().filterNot { it.id == entry.id })
}

fun PreferencesHelper.reinsertIntoSearchHistory(entry: SearchHistoryEntry) {
    val pref = browseSearchHistory()
    pref.set((pref.get() + entry).sortedByDescending { it.id }.take(SEARCH_HISTORY_LIMIT))
}

fun PreferencesHelper.clearSearchHistory() = browseSearchHistory().delete()
