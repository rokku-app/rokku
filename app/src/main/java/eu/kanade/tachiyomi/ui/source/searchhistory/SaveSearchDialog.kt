package eu.kanade.tachiyomi.ui.source.searchhistory

import android.app.Activity
import android.content.DialogInterface
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SaveSearchDialogBinding
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import uy.kohesive.injekt.injectLazy

/** Shows a dialog that creates a new or edit an existing [SearchHistoryEntry]. */
object SaveSearchDialog {
    private val preferences by injectLazy<PreferencesHelper>()
    private val sourceManager by injectLazy<SourceManager>()

    fun show(
        activity: Activity,
        entry: SearchHistoryEntry,
        isExisting: Boolean,
        onSaved: () -> Unit = {},
    ) {
        val binding = SaveSearchDialogBinding.inflate(activity.layoutInflater)
        binding.name.append(entry.name ?: "")

        if (entry.sourceId != null) {
            binding.showOnSourceBtn.text =
                binding.root.context.getString(R.string.only_x, sourceManager.getOrStub(entry.sourceId).name)
            binding.showOnGroup.check(if (entry.showOnAllSources) binding.showOnAllBtn.id else binding.showOnSourceBtn.id)
        } else {
            binding.showOnRow.isVisible = false
        }

        val dialog =
            activity
                .materialAlertDialog()
                .apply {
                    setTitle(if (isExisting) R.string.edit else R.string.save)
                    setView(binding.root)
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(R.string.save) { _, _ ->
                        val name = binding.name.text.toString().trim()
                        val showOnAllSources =
                            entry.sourceId == null || binding.showOnGroup.checkedButtonId == binding.showOnAllBtn.id
                        if (isExisting) {
                            preferences.updateSavedSearch(entry.id, name, showOnAllSources)
                        } else {
                            preferences.addSavedSearch(name, entry.query, entry.sourceId, showOnAllSources)
                        }
                        onSaved()
                    }
                }.create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)

            fun refreshPositiveButton() {
                val name = binding.name.text?.toString()?.trim().orEmpty()
                positiveButton?.isEnabled = name.isNotBlank()
                val conflicts =
                    name.isNotBlank() &&
                        preferences.savedSearches().get().findConflictingEntry(
                            name,
                            entry.sourceId,
                            excludingId = if (isExisting) entry.id else null,
                        ) != null
                positiveButton?.text = activity.getString(if (conflicts) R.string.replace else R.string.save)
            }
            refreshPositiveButton()
            binding.name.addTextChangedListener { refreshPositiveButton() }
        }
        dialog.show()
    }
}
