package eu.kanade.tachiyomi.ui.extension

import android.animation.AnimatorInflater
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.scale
import androidx.core.view.isGone
import androidx.core.view.isVisible
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.size.SizeResolver
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.CoverViewTarget
import eu.kanade.tachiyomi.databinding.ExtensionCardItemBinding
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.InstalledExtensionsOrder
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.contextCompatDrawable
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import eu.kanade.tachiyomi.util.view.resetStrokeColor
import eu.kanade.tachiyomi.util.view.setText
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class ExtensionHolder(view: View, val adapter: ExtensionAdapter) :
    BaseFlexibleViewHolder(view, adapter) {

    private val context = view.context
    private val binding = ExtensionCardItemBinding.bind(view)

    // Identifies whatever this view is currently showing as an icon, so a bind() that doesn't
    // actually change the icon (e.g. every list refresh triggered by onActivityResumed, which
    // fires on any app resume - some OEM battery/background managers do this very frequently)
    // doesn't dispose a perfectly fine icon and restart loading it from scratch.
    private var boundIconKey: String? = null

    init {
        binding.extButton.setOnClickListener {
            adapter.buttonClickListener.onButtonClick(flexibleAdapterPosition)
        }
        binding.cancelButton.setOnClickListener {
            adapter.buttonClickListener.onCancelClick(flexibleAdapterPosition)
        }
        binding.webviewButton.setOnClickListener {
            adapter.buttonClickListener.onWebViewClick(flexibleAdapterPosition)
        }
    }

    fun bind(item: ExtensionItem) {
        val extension = item.extension

        // Set source name

        val infoText = mutableListOf(extension.versionName)
        binding.date.isVisible = false
        if (extension is Extension.Installed && !extension.hasUpdate) {
            when (InstalledExtensionsOrder.fromValue(adapter.installedSortOrder)) {
                InstalledExtensionsOrder.RecentlyUpdated -> {
                    ExtensionLoader.extensionUpdateDate(itemView.context, extension)
                        .takeUnless { it == 0L }?.let {
                            binding.date.isVisible = true
                            binding.date.text = itemView.context.timeSpanFromNow(MR.strings.updated_, it)
                            infoText.add("")
                        }
                }

                InstalledExtensionsOrder.RecentlyInstalled -> {
                    ExtensionLoader.extensionInstallDate(itemView.context, extension)
                        .takeUnless { it == 0L }?.let {
                            binding.date.isVisible = true
                            binding.date.text =
                                itemView.context.timeSpanFromNow(
                                    if (extension.isShared) {
                                        MR.strings.installed_
                                    } else {
                                        MR.strings.added_
                                    },
                                    it,
                                )
                            infoText.add("")
                        }
                }

                else -> binding.date.isVisible = false
            }
        } else {
            binding.date.isVisible = false
        }
        binding.lang.isVisible = binding.date.isGone
        binding.extTitle.text = if (infoText.size > 1) {
            buildSpannedString {
                append(extension.name + " ")
                color(binding.extTitle.context.getResourceColor(AR.attr.textColorSecondary)) {
                    scale(0.75f) {
                        append(LocaleHelper.getLocalizedDisplayName(extension.lang))
                    }
                }
            }
        } else {
            extension.name
        }

        binding.version.text = infoText.joinToString(" • ")
        binding.lang.text = LocaleHelper.getLocalizedDisplayName(extension.lang)
        val context = itemView.context
        val errorColor = context.getResourceColor(R.attr.colorError)
        val secondaryColor = context.getResourceColor(AR.attr.textColorSecondary)
        val warnings = buildList {
            extension.contentWarning.label?.let {
                add(context.getString(it.title) to context.getResourceColor(it.colorAttr))
            }
            if (extension is Extension.Untrusted) {
                add(context.getString(MR.strings.untrusted) to errorColor)
            } else if (extension is Extension.Installed && extension.isObsolete) {
                add(context.getString(MR.strings.obsolete) to errorColor)
            }
            if (extension is Extension.Installed && !extension.isShared) {
                add(context.getString(MR.strings.ext_installer_private) to secondaryColor)
            }
        }
        binding.warning.isVisible = warnings.isNotEmpty()
        binding.warning.text = buildSpannedString {
            warnings.forEachIndexed { index, (text, textColor) ->
                if (index > 0) append(" - ")
                color(textColor) { append(text) }
            }
        }
        val repoUrl = when (extension) {
            is Extension.Available -> extension.repoUrl
            is Extension.Installed -> extension.repoUrl
            is Extension.Untrusted -> null
        }
        val repoName = repoUrl?.let { adapter.repoNames[it] }
        binding.repoName.text = repoName
        binding.repoName.isVisible = repoName != null
        binding.installProgress.progress = item.sessionProgress ?: 0
        binding.installProgress.isVisible = item.sessionProgress != null
        binding.cancelButton.isVisible = item.sessionProgress != null
        binding.webviewButton.isVisible =
            item.sessionProgress == null &&
            extension is Extension.Available &&
            extension.sources.any { it.baseUrl.isNotBlank() }

        val iconKey = when (extension) {
            is Extension.Available -> "available:${extension.pkgName}:${extension.iconUrl}"
            is Extension.Installed -> "installed:${extension.pkgName}:${extension.versionCode}"
            is Extension.Untrusted -> "untrusted:${extension.pkgName}"
        }
        if (iconKey != boundIconKey) {
            boundIconKey = iconKey

            binding.sourceImage.dispose()
            // dispose() only cancels the in-flight request; it doesn't clear whatever this
            // recycled ImageView was last showing. Without this, a view that just displayed an
            // installed extension's icon keeps showing it while an available extension's network
            // icon is still loading (or never finishes), making the wrong icon appear to "belong"
            // to the wrong row.
            binding.sourceImage.setImageDrawable(null)

            when (extension) {
                is Extension.Available -> {
                    binding.sourceImage.load(extension.iconUrl) {
                        target(CoverViewTarget(binding.sourceImage))
                        // The default crossfade can get stuck mid-fade (showing nothing) when
                        // the result lands while this row is off-screen during a fast scroll -
                        // the row only paints again once it's rebound, e.g. by scrolling back
                        // over it a second time. These are small static icons; skip the fade.
                        crossfade(false)
                        // Icons are tiny; decode at their real size instead of letting Coil
                        // constrain them to the (much larger) measured ImageView size.
                        size(SizeResolver.ORIGINAL)
                    }
                }

                is Extension.Installed -> {
                    binding.sourceImage.load(extension.icon) {
                        crossfade(false)
                    }
                }

                is Extension.Untrusted -> {
                    binding.sourceImage.setImageDrawable(
                        context.contextCompatDrawable(R.drawable.ic_report_24dp),
                    )
                }
            }
        }
        bindButton(item)
    }

    @Suppress("ResourceType")
    fun bindButton(item: ExtensionItem) = with(binding.extButton) {
        if (item.installStep == InstallStep.Done) return@with
        isEnabled = true
        isClickable = true
        isActivated = false

        binding.installProgress.progress = item.sessionProgress ?: 0
        binding.cancelButton.isVisible = item.sessionProgress != null
        binding.installProgress.isVisible = item.sessionProgress != null
        val extension = item.extension
        val installStep = item.installStep
        binding.webviewButton.isVisible =
            item.sessionProgress == null &&
            extension is Extension.Available &&
            extension.sources.any { it.baseUrl.isNotBlank() }
        strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
        rippleColor = ColorStateList.valueOf(context.getResourceColor(R.attr.colorControlHighlight))
        stateListAnimator = null
        if (installStep != null) {
            setText(
                when (installStep) {
                    InstallStep.Pending -> MR.strings.pending
                    InstallStep.Downloading -> MR.strings.downloading
                    InstallStep.Loading -> MR.strings.loading
                    InstallStep.Installing -> MR.strings.installing
                    InstallStep.Installed -> MR.strings.installed
                    InstallStep.Error -> MR.strings.retry
                    else -> return@with
                },
            )
            if (installStep != InstallStep.Error) {
                isEnabled = false
                isClickable = false
            }
        } else if (extension is Extension.Installed) {
            when {
                extension.hasUpdate -> {
                    isActivated = true
                    stateListAnimator =
                        AnimatorInflater.loadStateListAnimator(context, R.animator.icon_btn_state_list_anim)
                    rippleColor = ColorStateList.valueOf(context.getColor(R.color.on_secondary_highlight))
                    setText(MR.strings.update)
                }

                else -> {
                    setText(MR.strings.settings)
                }
            }
        } else if (extension is Extension.Untrusted) {
            resetStrokeColor()
            setText(MR.strings.trust)
        } else {
            resetStrokeColor()
            setText(if (adapter.installPrivately) MR.strings.add else MR.strings.install)
        }
    }
}
