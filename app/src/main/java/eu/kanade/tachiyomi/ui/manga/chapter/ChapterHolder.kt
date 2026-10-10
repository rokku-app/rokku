package eu.kanade.tachiyomi.ui.manga.chapter

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import com.materialkolor.hct.Hct
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.ChaptersItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.manga.MangaDetailsAdapter
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.view.makeContainerShape
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class ChapterHolder(
    view: View,
    private val adapter: MangaDetailsAdapter,
) : BaseChapterHolder(view, adapter) {

    private val binding = ChaptersItemBinding.bind(view)
    private var localSource = false

    init {
        binding.downloadButton.downloadButton.setOnLongClickListener {
            adapter.delegate.startDownloadRange(flexibleAdapterPosition)
            true
        }
    }

    fun bind(item: ChapterItem, manga: Manga) {
        val chapter = item.chapter
        val isLocked = item.isLocked
        itemView.transitionName = "details chapter ${chapter.id ?: 0L} transition"
        binding.chapterTitle.text =
            chapter.preferredChapterName(itemView.context, manga, adapter.preferences)

        binding.downloadButton.downloadButton.isVisible = !manga.isLocal() && !isLocked
        localSource = manga.isLocal()

        ChapterUtil.setTextViewForChapter(binding.chapterTitle, item, hideStatus = isLocked)

        val statuses = mutableListOf<String>()

        ChapterUtil.relativeDate(chapter)?.let { statuses.add(it) }

        val showPagesLeft = !chapter.read && chapter.last_page_read > 0 && !isLocked

        if (showPagesLeft) {
            statuses.add(
                itemView.context.getString(
                    MR.strings.page_x_of_y,
                    chapter.last_page_read + 1,
                    chapter.pages_left + chapter.last_page_read,
                ),
            )
        }

        if (chapter.scanlator?.isNotBlank() == true) {
            statuses.add(chapter.scanlator!!)
        }

        if (getFrontView().translationX == 0f) {
            binding.read.setImageResource(
                if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
            )
            binding.bookmark.setImageResource(
                if (item.bookmark) R.drawable.ic_bookmark_off_24dp else R.drawable.ic_bookmark_24dp,
            )
        }
        ChapterUtil.setTextViewForChapter(
            binding.chapterScanlator,
            item,
            showBookmark = false,
            hideStatus = isLocked,
            isDetails = true,
        )
        binding.chapterScanlator.text = statuses.joinToString(" • ")

        val status = when {
            adapter.isSelected(flexibleAdapterPosition) -> Download.State.CHECKED
            else -> item.status
        }

        notifyStatus(status, item.isLocked, item.progress)
        resetFrontView()
        if (flexibleAdapterPosition == 1) {
            if (!adapter.hasShownSwipeTut.get()) showSlideAnimation()
        }
    }

    private fun showSlideAnimation() {
        val slide = 100f.dpToPx
        val animatorSet = AnimatorSet()
        val anim1 = slideAnimation(0f, slide)
        anim1.startDelay = 1000
        anim1.doOnStart { binding.startView.isVisible = true }
        val anim2 = slideAnimation(slide, -slide)
        anim2.duration = 600
        anim2.startDelay = 500
        anim2.addUpdateListener {
            if (binding.startView.isVisible && getFrontView().translationX <= 0) {
                binding.startView.isVisible = false
                binding.endView.isVisible = true
            }
        }
        val anim3 = slideAnimation(-slide, 0f)
        anim3.startDelay = 750
        animatorSet.playSequentially(anim1, anim2, anim3)
        animatorSet.doOnEnd { adapter.hasShownSwipeTut.set(true) }
        animatorSet.start()
    }

    private fun slideAnimation(from: Float, to: Float): ObjectAnimator {
        return ObjectAnimator.ofFloat(getFrontView(), View.TRANSLATION_X, from, to)
            .setDuration(300)
    }

    override fun getFrontView(): View {
        return binding.chapterCard
    }

    override fun getRearEndView(): View {
        return binding.endView
    }

    override fun getRearStartView(): View {
        return binding.startView
    }

    private fun resetFrontView() {
        if (getFrontView().translationX != 0f) {
            itemView.post {
                androidx.transition.TransitionManager.endTransitions(adapter.recyclerView)
                adapter.notifyItemChanged(flexibleAdapterPosition)
            }
        }
    }

    fun notifyStatus(status: Download.State, locked: Boolean, progress: Int, animated: Boolean = false) = with(
        binding.downloadButton.downloadButton,
    ) {
        applyCardBackground()
        adapter.delegate.accentColor()?.let {
            binding.startView.setCardBackgroundColor(it)
            binding.bookmark.imageTintList = ColorStateList.valueOf(
                context.getResourceColor(AR.attr.textColorPrimaryInverse),
            )
            TextViewCompat.setCompoundDrawableTintList(
                binding.chapterTitle,
                ColorStateList.valueOf(it),
            )
            accentColor = it
        }
        if (locked) {
            isVisible = false
            return@with
        }
        isVisible = !localSource
        setDownloadStatus(status, progress, animated)
    }

    fun setCorners(top: Boolean, bottom: Boolean) {
        val grouped = adapter.preferences.groupedChapterCards().get()
        val cards = listOf(binding.chapterCard, binding.startView, binding.endView)
        val horizontalMargin = if (grouped) 10.dpToPx else 0
        cards.forEach { card ->
            card.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = horizontalMargin
                marginEnd = horizontalMargin
            }
        }
        if (grouped) {
            val shapeModel = binding.chapterCard.makeContainerShape(top, bottom, clipContentTo = binding.frontView)
            cards.forEach { it.shapeAppearanceModel = shapeModel }
            binding.frontView.setBackgroundResource(R.drawable.transparent_item_selector)
        } else {
            val flat = binding.chapterCard.shapeAppearanceModel.toBuilder().setAllCornerSizes(0f).build()
            cards.forEach { it.shapeAppearanceModel = flat }
            binding.frontView.clipToOutline = false
            binding.frontView.setBackgroundResource(R.drawable.list_item_selector)
        }
        applyCardBackground()
    }

    private fun applyCardBackground() {
        if (!adapter.preferences.groupedChapterCards().get()) {
            binding.chapterCard.setCardBackgroundColor(Color.TRANSPARENT)
            binding.frontView.backgroundTintList =
                adapter.delegate.pageBackgroundColor()?.let { ColorStateList.valueOf(it) }
            return
        }
        binding.frontView.backgroundTintList = null
        adapter.delegate.accentColor()?.let {
            val context = binding.chapterCard.context
            val page = adapter.delegate.pageBackgroundColor() ?: context.getResourceColor(R.attr.background)
            val base = Hct.fromInt(page)
            val tone = (base.tone + if (context.isInNightMode()) -4 else 4).coerceIn(0.0, 100.0)
            binding.chapterCard.setCardBackgroundColor(Hct.from(Hct.fromInt(it).hue, base.chroma, tone).toInt())
        }
    }
}
