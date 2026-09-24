package eu.kanade.tachiyomi.ui.source.filter

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R

/** Pinned atop the filter sheet's list when there's at least one saved search applicable here. */
class SavedSearchesHeaderItem(
    private val onClicked: () -> Unit,
) : AbstractFlexibleItem<SavedSearchesHeaderItem.Holder>() {
    override fun getLayoutRes(): Int = R.layout.saved_searches_header_item

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): Holder = Holder(view, adapter)

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: Holder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.itemView.setOnClickListener { onClicked() }
    }

    override fun equals(other: Any?): Boolean = other is SavedSearchesHeaderItem

    override fun hashCode(): Int = javaClass.hashCode()

    class Holder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ) : FlexibleViewHolder(view, adapter)
}
