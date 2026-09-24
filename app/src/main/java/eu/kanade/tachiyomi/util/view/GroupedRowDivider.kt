package eu.kanade.tachiyomi.util.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isLTR

/**
 * Draws a divider between consecutive rows belonging to the same grouped/merged card
 * (see [makeContainerShape])
 *
 * @param isGroupedRow returns true for a view holder that participates in the grouped card list.
 * @param isRowInset when false, draws the divider flush to the recycler's edges instead of inset
 * to the card's padding
 * @param maskGapWithBackground paints over the seam with the theme background color so two flush
 * cards read as one surface. A screen that tints its own divider (like manga details)
 * should pass false and draw that mask itself.
 * @param isActive lets a screen turn the decoration off entirely at draw time, e.g. the library's
 * blank placeholder row reuses the list layout even while shown in grid mode.
 */
@SuppressLint("UseKtx")
class GroupedRowDivider(
    context: Context,
    private val isGroupedRow: (RecyclerView.ViewHolder) -> Boolean,
    private val isRowInset: (RecyclerView.ViewHolder) -> Boolean = { true },
    private val maskGapWithBackground: Boolean = true,
    private val isActive: () -> Boolean = { true },
) : RecyclerView.ItemDecoration() {
    private val divider: Drawable
    private val padding: Int = 12.dpToPx
    private val baseDividerColor = ContextCompat.getColor(context, R.color.divider)

    /** Shifts the divider's hue to match an accent color, keeping its original alpha/lightness. */
    var accentColor: Int? = null
        set(value) {
            field = value
            divider.setTint(
                value?.let {
                    val hsl = FloatArray(3)
                    ColorUtils.colorToHSL(baseDividerColor, hsl)
                    val accentHsl = FloatArray(3)
                    ColorUtils.colorToHSL(it, accentHsl)
                    hsl[0] = accentHsl[0]
                    ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), Color.alpha(baseDividerColor))
                } ?: baseDividerColor,
            )
        }

    init {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
        divider = a.getDrawable(0)!!.mutate()
        a.recycle()
    }

    override fun onDraw(
        c: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (!isActive()) return
        val childCount = parent.childCount
        for (i in 0 until childCount - 1) {
            val child = parent.getChildAt(i)
            val holder = parent.getChildViewHolder(child)
            if (!isGroupedRow(holder) || !isGroupedRow(parent.getChildViewHolder(parent.getChildAt(i + 1)))) {
                continue
            }
            val inset = isRowInset(holder)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val top = child.bottom + params.bottomMargin
            val bottom: Int
            val left: Int
            val right: Int
            if (inset) {
                bottom = top + divider.intrinsicHeight + 1.dpToPx
                left = parent.paddingStart + padding
                right = parent.width - parent.paddingEnd - padding
            } else {
                bottom = top + divider.intrinsicHeight
                left = parent.paddingStart + if (parent.context.resources.isLTR) padding else 0
                right = parent.width - parent.paddingEnd - if (!parent.context.resources.isLTR) padding else 0
            }
            divider.setBounds(left, top, right, bottom)
            divider.draw(c)
            if (inset && maskGapWithBackground) {
                c.drawColor(parent.context.getResourceColor(R.attr.background))
            }
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: android.view.View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val holder = parent.getChildViewHolder(view)
        if (!isActive() || !isGroupedRow(holder)) {
            outRect.setEmpty()
            return
        }
        val extra = if (isRowInset(holder)) 1.dpToPx else 0
        outRect.set(0, 0, 0, divider.intrinsicHeight + extra)
    }
}

/**
 * The top/bottom-of-group flags a grouped card list needs for [makeContainerShape]: true where
 * the neighboring adapter position isn't part of the same run (a header, a footer, or nothing).
 *
 * @param isMember returns whether a given adapter item belongs to this row's group -- usually a
 * simple `it is SomeItem` type check, but can be more specific (recents footer rows share a class
 * with real rows, so it also checks for a null manga id there).
 */
fun groupEdges(
    adapter: FlexibleAdapter<out IFlexible<*>>,
    position: Int,
    isMember: (IFlexible<*>?) -> Boolean,
): Pair<Boolean, Boolean> {
    val setTop = !isMember(adapter.getItem(position - 1))
    val setBottom = !isMember(adapter.getItem(position + 1))
    return setTop to setBottom
}
