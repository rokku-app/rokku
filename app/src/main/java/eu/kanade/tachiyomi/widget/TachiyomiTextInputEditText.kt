package eu.kanade.tachiyomi.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import androidx.core.view.inputmethod.EditorInfoCompat
import com.google.android.material.textfield.TextInputEditText
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.source.isIncognitoModeForSource
import eu.kanade.tachiyomi.ui.base.controller.currentIncognitoSourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * A custom [TextInputEditText] that sets [EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING] to imeOptions
 * if [PreferencesHelper.incognitoMode] is true. Some IMEs may not respect this flag.
 *
 * @see setIncognito
 */
class TachiyomiTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.editTextStyle,
) : TextInputEditText(context, attrs, defStyleAttr) {

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        setIncognito(scope!!) { context.currentIncognitoSourceId() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope?.cancel()
        scope = null
    }

    companion object {
        /**
         * Sets Flow to this [EditText] that sets [EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING] to imeOptions
         * if incognito mode is on - either the global toggle, or per-extension incognito for
         * [sourceId]'s source, if one is given. Some IMEs may not respect this flag.
         */
        fun EditText.setIncognito(
            viewScope: CoroutineScope,
            sourceId: () -> Long? = { null },
        ) {
            try {
                val preferences = Injekt.get<PreferencesHelper>()

                fun applyIncognito(incognito: Boolean) {
                    imeOptions =
                        if (incognito) {
                            imeOptions or EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
                        } else {
                            imeOptions and EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
                        }
                }

                applyIncognito(isIncognitoModeForSource(sourceId(), preferences))
                merge(preferences.incognitoMode().changes(), preferences.incognitoExtensions().changes())
                    .onEach { applyIncognito(isIncognitoModeForSource(sourceId(), preferences)) }
                    .launchIn(viewScope)
            } catch (_: Exception) {
            }
        }
    }
}
