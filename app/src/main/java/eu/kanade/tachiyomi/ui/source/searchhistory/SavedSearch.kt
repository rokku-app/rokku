package eu.kanade.tachiyomi.ui.source.searchhistory

import eu.kanade.tachiyomi.data.preference.PreferencesHelper

// other sources can have the same name even if it's global
fun List<SearchHistoryEntry>.findConflictingEntry(
    name: String,
    sourceId: Long?,
    excludingId: Long? = null,
): SearchHistoryEntry? = find { it.id != excludingId && it.sourceId == sourceId && it.name?.equals(name.trim(), ignoreCase = true) == true }

fun PreferencesHelper.addSavedSearch(
    name: String,
    query: String,
    sourceId: Long?,
    showOnAllSources: Boolean,
): SearchHistoryEntry {
    val trimmedName = name.trim()
    val entry = SearchHistoryEntry(System.currentTimeMillis(), query, sourceId, trimmedName, showOnAllSources)
    val pref = savedSearches()
    pref.set(pref.get().filterNot { it.sourceId == sourceId && it.name.equals(trimmedName, ignoreCase = true) } + entry)
    return entry
}

fun PreferencesHelper.updateSavedSearch(
    id: Long,
    name: String,
    showOnAllSources: Boolean,
) {
    val trimmedName = name.trim()
    val pref = savedSearches()
    val list = pref.get()
    val target = list.find { it.id == id } ?: return
    val updated = target.copy(name = trimmedName, showOnAllSources = showOnAllSources)
    val withoutConflicts =
        list.filterNot { it.id == id || (it.sourceId == target.sourceId && it.name.equals(trimmedName, ignoreCase = true)) }
    pref.set(withoutConflicts + updated)
}

fun PreferencesHelper.removeSavedSearch(id: Long) {
    val pref = savedSearches()
    pref.set(pref.get().filterNot { it.id == id })
}

fun PreferencesHelper.reinsertSavedSearch(entry: SearchHistoryEntry) {
    val pref = savedSearches()
    pref.set(pref.get() + entry)
}

fun List<SearchHistoryEntry>.applicableTo(sourceId: Long?): List<SearchHistoryEntry> =
    filter { it.showOnAllSources || it.sourceId == sourceId }
