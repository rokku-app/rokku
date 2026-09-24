package eu.kanade.tachiyomi.ui.source.searchhistory

import android.view.View
import androidx.core.view.isVisible
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.SearchHistoryItemBinding

class SearchRowItem(
    val entry: SearchHistoryEntry,
    private val isTopOfGroup: Boolean,
    private val isBottomOfGroup: Boolean,
    private val onFillClicked: (String) -> Unit,
    private val onTrailingClicked: (SearchHistoryEntry) -> Unit,
) : AbstractItem<FastAdapter.ViewHolder<SearchRowItem>>() {
    override val type: Int = R.id.history_card

    override val layoutRes: Int = R.layout.search_history_item

    override var identifier = entry.id

    override fun getViewHolder(v: View): FastAdapter.ViewHolder<SearchRowItem> = ViewHolder(v)

    class ViewHolder(
        view: View,
    ) : FastAdapter.ViewHolder<SearchRowItem>(view),
        ISwipeableViewHolder {
        private val binding = SearchHistoryItemBinding.bind(view)

        override val swipeableView: View = binding.historyCard
        override val leftBackView: View = binding.leftBackView
        override val rightBackView: View = binding.rightBackView

        override fun bindView(
            item: SearchRowItem,
            payloads: List<Any>,
        ) {
            val entry = item.entry
            val isSaved = entry.name != null
            binding.historyCard.translationX = 0f
            binding.backView.isVisible = false
            binding.historyIcon.setImageResource(if (isSaved) R.drawable.ic_star_24dp else R.drawable.ic_history_24dp)
            binding.title.isVisible = true
            binding.title.text = entry.name ?: entry.query

            // a recent row's title already shows the query - a saved row's title is its name
            // instead, so the query only shows here
            binding.subtitle.isVisible = isSaved && entry.query.isNotBlank()
            binding.subtitle.text = entry.query

            binding.fillButton.isVisible = true
            binding.fillButton.setOnClickListener { item.onFillClicked(entry.query) }

            binding.saveButton.contentDescription =
                binding.root.context.getString(if (isSaved) R.string.edit else R.string.save)
            binding.saveButton.setIconResource(if (isSaved) R.drawable.ic_edit_24dp else R.drawable.ic_outline_save_24dp)
            binding.saveButton.setOnClickListener { item.onTrailingClicked(entry) }
        }

        override fun unbindView(item: SearchRowItem) {
            binding.title.text = null
            binding.fillButton.setOnClickListener(null)
            binding.saveButton.setOnClickListener(null)
            binding.historyCard.translationX = 0f
            binding.backView.isVisible = false
        }
    }
}

public interface ISwipeableViewHolder {
    public abstract val swipeableView: View
    public abstract val leftBackView: View
    public abstract val rightBackView: View
}
