package eu.kanade.tachiyomi.ui.source.searchhistory

import android.graphics.Canvas
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SwipeDeleteCallback(
    private val onSwiped: (position: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ) = false

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int = if (viewHolder is ISwipeableViewHolder) super.getSwipeDirs(recyclerView, viewHolder) else 0

    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int,
    ) {
        val position = viewHolder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) onSwiped.invoke(position)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }
        val swipeable = viewHolder as? ISwipeableViewHolder
        val swipeableView = swipeable?.swipeableView ?: viewHolder.itemView
        swipeable?.leftBackView?.isVisible = dX > 0
        swipeable?.rightBackView?.isVisible = dX < 0
        // the rear card itself only needs to be drawn while actually swiped - at rest (or
        // settling back to rest) the front view fully covers it anyway
        (swipeable?.leftBackView?.parent as? View)?.isVisible = dX != 0f
        swipeableView.translationX = dX
    }
}
