package eu.kanade.tachiyomi.ui.extension.details

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.ConcatAdapter
import co.touchlab.kermit.Logger
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.preference.minusAssign
import eu.kanade.tachiyomi.core.preference.plusAssign
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.databinding.ExtensionDetailControllerBinding
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.preferenceKey
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.setting.DSL
import eu.kanade.tachiyomi.ui.setting.addThenInit
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.iconRes
import eu.kanade.tachiyomi.ui.setting.iconTint
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.openInBrowser
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.widget.LinearLayoutManagerAccurateOffset
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import eu.kanade.tachiyomi.widget.preference.EditTextResetPreference
import eu.kanade.tachiyomi.widget.preference.ListMatPreference
import eu.kanade.tachiyomi.widget.preference.MultiListMatPreference
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString

@SuppressLint("RestrictedApi")
class ExtensionDetailsController(bundle: Bundle? = null) :
    BaseCoroutineController<ExtensionDetailControllerBinding, ExtensionDetailsPresenter>(bundle),
    PreferenceManager.OnDisplayPreferenceDialogListener,
    DialogPreference.TargetFragment {

    private var lastOpenPreferencePosition: Int = 0

    private var preferenceScreen: PreferenceScreen? = null

    private val preferences: PreferencesHelper = Injekt.get()
    private val network: NetworkHelper by injectLazy()

    init {
        setHasOptionsMenu(true)
    }

    constructor(pkgName: String) : this(
        Bundle().apply {
            putString(PKGNAME_KEY, pkgName)
        },
    )

    override fun createBinding(inflater: LayoutInflater) =
        ExtensionDetailControllerBinding.inflate(inflater.cloneInContext(getPreferenceThemeContext()))

    override val presenter = ExtensionDetailsPresenter(args.getString(PKGNAME_KEY)!!)

    override fun getTitle(): String? {
        return view?.context?.getString(MR.strings.extension_info)
    }

    @SuppressLint("PrivateResource")
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        scrollViewWith(binding.extensionPrefsRecycler, padBottom = true)

        val extension = presenter.extension ?: return
        val context = view.context

        val themedContext by lazy { getPreferenceThemeContext() }
        val manager = PreferenceManager(themedContext)
        val dataStore = SharedPreferencesDataStore(
            context.getSharedPreferences(extension.getPreferenceKey(), Context.MODE_PRIVATE),
        )
        manager.preferenceDataStore = dataStore
        manager.onDisplayPreferenceDialogListener = this
        val screen = manager.createPreferenceScreen(themedContext)
        preferenceScreen = screen

        val incognitoPreference = addIncognitoPreference(screen, extension.pkgName)

        val multiSource = extension.sources.size > 1
        val isMultiLangSingleSource = multiSource && extension.sources.map { it.name }.distinct().size == 1
        val languages = preferences.enabledLanguages().get()

        for (source in extension.sources.sortedByDescending { it.isLangEnabled(languages) }) {
            addPreferencesForSource(screen, source, multiSource, isMultiLangSingleSource)
        }

        manager.setPreferences(screen)
        // PreferenceManager.setPreferences() re-dispatches each preference's initial value as it
        // attaches the whole screen, which stomps isChecked a second time even with addThenInit
        // below handling the first stomp at addPreference() - so the real value has to be set
        // after this call is the only way it survives.
        incognitoPreference.isChecked = extension.pkgName in preferences.incognitoExtensions().get()

        binding.extensionPrefsRecycler.layoutManager = LinearLayoutManagerAccurateOffset(context)
        val concatAdapterConfig = ConcatAdapter.Config.Builder()
            .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
            .build()
        screen.setShouldUseGeneratedIds(true)
        val extHeaderAdapter = ExtensionDetailsHeaderAdapter(presenter)
        extHeaderAdapter.setHasStableIds(true)
        binding.extensionPrefsRecycler.adapter = ConcatAdapter(
            concatAdapterConfig,
            extHeaderAdapter,
            PreferenceGroupAdapter(screen),
        )
        binding.extensionPrefsRecycler.addItemDecoration(ExtensionSettingsDividerItemDecoration(context))
    }

    override fun onDestroyView(view: View) {
        preferenceScreen = null
        super.onDestroyView(view)
    }

    fun onExtensionUninstalled() {
        router.popCurrentController()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(LASTOPENPREFERENCE_KEY, lastOpenPreferencePosition)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        lastOpenPreferencePosition = savedInstanceState.getInt(LASTOPENPREFERENCE_KEY)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.extension_details, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val item = menu.findItem(R.id.action_open_repo)
        item.isVisible = presenter.extension?.repoUrl != null
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_open_repo -> openRepo()
            R.id.action_clear_cookies -> clearCookies()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openRepo() {
        val url = getUrl(presenter.extension?.repoUrl) ?: return
        openInBrowser(url)
    }

    private fun getUrl(repoUrl: String?): String? {
        val regex = """https://raw.githubusercontent.com/(.+?)/(.+?)/.+""".toRegex()
        return regex.find(repoUrl.orEmpty())?.let {
            val (user, repo) = it.destructured
            "https://github.com/$user/$repo"
        } ?: repoUrl
    }

    private fun clearCookies() {
        val urls = presenter.extension?.sources
            ?.filterIsInstance<HttpSource>()
            ?.map { it.baseUrl }
            ?.distinct() ?: emptyList()

        val cleared = urls.sumOf {
            network.cookieJar.remove(it.toHttpUrl())
        }

        Logger.d { "Cleared $cleared cookies for: ${urls.joinToString()}" }
        val context = view?.context ?: return
        binding.coordinator.snack(context.getString(MR.strings.cookies_cleared))
    }

    /**
     * Adds a switch to pause reading history/tracking for every source in this extension,
     * mirroring the global incognito toggle but scoped to just this package.
     *
     * Its real `isChecked` value is set by the caller, after [PreferenceManager.setPreferences] -
     * both that call and this screen's [PreferenceManager] having a manager-level
     * [androidx.preference.PreferenceDataStore] (see [onViewCreated]) independently trigger
     * androidx's `Preference.dispatchSetInitialValue()`, which reads through that store
     * regardless of `isPersistent` and resets `isChecked` to unchecked - even though the
     * underlying [PreferencesHelper.incognitoExtensions] value is unaffected. Setting it any
     * earlier (even via [addThenInit], which only survives the first of those two resets) gets
     * silently overwritten, leaving the switch visually stuck off no matter what it's set to.
     */
    private fun addIncognitoPreference(screen: PreferenceScreen, pkgName: String): SwitchPreferenceCompat {
        val context = screen.context
        return screen.addThenInit(SwitchPreferenceCompat(context)) {
            key = "${pkgName}_incognito"
            title = context.getString(MR.strings.incognito_mode)
            summary = context.getString(MR.strings.pauses_reading_history)
            isIconSpaceReserved = true
            iconRes = R.drawable.ic_incognito_circle_24dp
            iconTint = context.getResourceColor(R.attr.colorPrimary)
            isPersistent = false

            onChange { newValue ->
                val enable = newValue as Boolean
                if (enable) {
                    preferences.incognitoExtensions() += pkgName
                } else {
                    preferences.incognitoExtensions() -= pkgName
                }
                true
            }
        }
    }

    private fun addPreferencesForSource(screen: PreferenceScreen, source: Source, isMultiSource: Boolean, isMultiLangSingleSource: Boolean) {
        val context = screen.context

        val prefs = mutableListOf<Preference>()
        val block: (@DSL SwitchPreferenceCompat).() -> Unit = {
            key = source.preferenceKey() + "_enabled"
            title = when {
                isMultiSource && !isMultiLangSingleSource -> source.toString()
                else -> LocaleHelper.getSourceDisplayName(source.lang, context)
            }
            isPersistent = false
            isChecked = source.isEnabled()

            onChange { newValue ->
                if (source.isLangEnabled()) {
                    val checked = newValue as Boolean
                    toggleSource(source, checked)
                    prefs.forEach { it.isVisible = checked }
                    true
                } else {
                    binding.coordinator.snack(
                        context.getString(
                            MR.strings._must_be_enabled_first,
                            title?.toString() ?: "",
                        ),
                        Snackbar.LENGTH_LONG,
                    ) {
                        setAction(MR.strings.enable) {
                            preferences.enabledLanguages() += source.lang
                            // Re-trigger the switch's own click flow now that the language is
                            // enabled, instead of patching isChecked/toggleSource manually here -
                            // that manual patch races with the hiddenSources().changes() listener
                            // below and can leave the switch visually unchecked despite the
                            // preference being enabled correctly.
                            performClick()
                        }
                    }
                    false
                }
            }

            // React to enable/disable all changes
            preferences.hiddenSources().changes()
                .onEach {
                    val enabled = source.isEnabled()
                    isChecked = enabled
                }
                .launchIn(viewScope)
        }

        screen.switchPreference(block)
        if (source is ConfigurableSource) {
            val newScreen = screen.preferenceManager.createPreferenceScreen(context)
            source.setupPreferenceScreen(newScreen)

            val dataStore = SharedPreferencesDataStore(source.sourcePreferences())
            // Reparent the preferences
            while (newScreen.preferenceCount != 0) {
                val pref = newScreen.getPreference(0)
                pref.isIconSpaceReserved = true
                pref.fragment = "source_${source.id}"
                pref.order = Int.MAX_VALUE
                pref.preferenceDataStore = dataStore
                pref.isVisible = source.isEnabled()

                // Apply incognito IME for EditTextPreference
                if (pref is EditTextPreference) {
                    pref.setOnBindEditTextListener {
                        it.setIncognito(viewScope) { source.id }
                    }
                }

                prefs.add(pref)
                newScreen.removePreference(pref)
                screen.addPreference(pref)
            }
        }
    }

    private fun toggleSource(source: Source, enable: Boolean) {
        if (enable) {
            preferences.hiddenSources() -= source.id.toString()
        } else {
            preferences.hiddenSources() += source.id.toString()
        }
    }

    private fun getPreferenceThemeContext(): Context {
        val tv = TypedValue()
        activity!!.theme.resolveAttribute(R.attr.preferenceTheme, tv, true)
        return ContextThemeWrapper(activity, tv.resourceId)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (!isAttached) return

        val screen = preference.parent!!

        lastOpenPreferencePosition = (0 until screen.preferenceCount).indexOfFirst {
            screen.getPreference(it) === preference
        }

        val context = preferences.context
        val matPref = when (preference) {
            is EditTextPreference -> EditTextResetPreference(activity, context).apply {
                dialogSummary = preference.dialogMessage
                // Forward to the listener with the original EditTextPreference, not this
                // wrapper - extensions' listeners expect the preference they registered on
                // and crash with a ClassCastException otherwise. Also persist the new value
                // back onto the original preference, matching the ListPreference case below -
                // otherwise the change is only ever seen by the listener and never actually saved.
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    if (preference.callChangeListener(newValue)) {
                        preference.text = newValue as? String
                        true
                    } else {
                        false
                    }
                }
            }

            is ListPreference -> ListMatPreference(activity, context).apply {
                isPersistent = false
                defaultValue = preference.value
                entries = preference.entries.map { it.toString() }
                entryValues = preference.entryValues.map { it.toString() }
                onChange {
                    if (preference.callChangeListener(it)) {
                        preference.value = it as? String
                        true
                    } else {
                        false
                    }
                }
            }

            is MultiSelectListPreference -> MultiListMatPreference(activity, context).apply {
                isPersistent = false
                defaultValue = preference.values
                entries = preference.entries.map { it.toString() }
                entryValues = preference.entryValues.map { it.toString() }
                onChange { newValue ->
                    if (newValue is Set<*> && preference.callChangeListener(newValue)) {
                        preference.values = newValue.map { it.toString() }.toSet()
                        true
                    } else {
                        false
                    }
                }
            }

            else -> throw IllegalArgumentException(
                "Tried to display dialog for unknown " +
                    "preference type. Did you forget to override onDisplayPreferenceDialog()?",
            )
        }
        matPref.apply {
            key = preference.key
            preferenceDataStore = preference.preferenceDataStore
            title = (preference as? DialogPreference)?.dialogTitle ?: preference.title
        }.performClick()
    }

    private fun Source.isEnabled(): Boolean {
        return id.toString() !in preferences.hiddenSources().get() && isLangEnabled()
    }

    private fun Source.isLangEnabled(langs: Set<String>? = null): Boolean {
        return lang in (langs ?: preferences.enabledLanguages().get())
    }

    private fun Extension.getPreferenceKey(): String = "extension_$pkgName"

    @Suppress("UNCHECKED_CAST")
    override fun <T : Preference> findPreference(key: CharSequence): T {
        // We track [lastOpenPreferencePosition] when displaying the dialog
        // [key] isn't useful since there may be duplicates
        return preferenceScreen!!.getPreference(lastOpenPreferencePosition) as T
    }

    private companion object {
        const val PKGNAME_KEY = "pkg_name"
        const val LASTOPENPREFERENCE_KEY = "last_open_preference"
    }
}
