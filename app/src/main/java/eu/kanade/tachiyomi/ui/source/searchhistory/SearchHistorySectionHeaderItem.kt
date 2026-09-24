package eu.kanade.tachiyomi.ui.source.searchhistory

import android.view.View
import androidx.core.view.isVisible
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.SearchHistorySectionHeaderBinding
import eu.kanade.tachiyomi.util.view.setAnimVectorCompat

class SearchHistorySectionHeaderItem(
    private val titleRes: Int,
    private val showClearAll: Boolean = false,
    private val onClearAll: () -> Unit = {},
    private val collapsible: Boolean = false,
    private val collapsed: Boolean = false,
    private val onToggleCollapsed: () -> Boolean = { false },
) : AbstractItem<FastAdapter.ViewHolder<SearchHistorySectionHeaderItem>>() {
    override val type: Int = R.id.header_title

    override val layoutRes: Int = R.layout.search_history_section_header

    override var identifier = titleRes.toLong()

    override var isSelectable = false

    override fun getViewHolder(v: View): FastAdapter.ViewHolder<SearchHistorySectionHeaderItem> = ViewHolder(v)

    class ViewHolder(
        view: View,
    ) : FastAdapter.ViewHolder<SearchHistorySectionHeaderItem>(view) {
        private val binding = SearchHistorySectionHeaderBinding.bind(view)

        override fun bindView(
            item: SearchHistorySectionHeaderItem,
            payloads: List<Any>,
        ) {
            binding.headerTitle.setText(item.titleRes)
            binding.clearAllButton.isVisible = item.showClearAll
            binding.clearAllButton.setOnClickListener { item.onClearAll() }
            binding.collapseChevron.isVisible = item.collapsible
            if (item.collapsible) {
                binding.collapseChevron.setImageResource(
                    if (item.collapsed) R.drawable.ic_expand_more_24dp else R.drawable.ic_expand_less_24dp,
                )
                binding.root.setOnClickListener {
                    val nowCollapsed = item.onToggleCollapsed()
                    binding.collapseChevron.setAnimVectorCompat(
                        if (nowCollapsed) R.drawable.anim_expand_less_to_more else R.drawable.anim_expand_more_to_less,
                    )
                }
            } else {
                binding.root.setOnClickListener(null)
            }
        }

        override fun unbindView(item: SearchHistorySectionHeaderItem) {
            binding.clearAllButton.setOnClickListener(null)
            binding.root.setOnClickListener(null)
        }
    }
}
