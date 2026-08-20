package eu.kanade.tachiyomi.ui.setting.controllers.legacy

import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceScreen
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.listPreference
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.requireAuthentication
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

class SettingsSecurityLegacyController : SettingsLegacyController() {
    private val securityPreferences: SecurityPreferences by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.security

        if (context.isAuthenticationSupported()) {
            switchPreference {
                bindTo(securityPreferences.useBiometrics())
                titleRes = MR.strings.lock_with_biometrics
                defaultValue = false

                requireAuthentication(
                    activity as? FragmentActivity,
                    context.getString(MR.strings.lock_with_biometrics),
                    confirmationRequired = false,
                )
            }
            intListPreference(activity) {
                key = PreferenceKeys.lockAfter
                titleRes = MR.strings.lock_when_idle
                val values = listOf(0, 2, 5, 10, 20, 30, 60, 90, 120, -1)
                entries = values.map {
                    when (it) {
                        0 -> context.getString(MR.strings.always)

                        -1 -> context.getString(MR.strings.never)

                        else -> context.getString(
                            MR.plurals.after_minutes,
                            it,
                            it,
                        )
                    }
                }
                entryValues = values
                defaultValue = 0

                securityPreferences.useBiometrics().changesIn(viewScope) { isVisible = it }
            }
        }

        switchPreference {
            key = PreferenceKeys.hideNotificationContent
            titleRes = MR.strings.hide_notification_content
            defaultValue = false
        }

        listPreference(activity) {
            bindTo(preferences.secureScreen())
            titleRes = MR.strings.secure_screen
            entriesRes = PreferenceValues.SecureScreenMode.entries.map { it.titleResId }.toTypedArray()
            entryValues = PreferenceValues.SecureScreenMode.entries.map { it.name }

            onChange {
                it as String
                SecureActivityDelegate.setSecure(activity)
                true
            }
        }

        infoPreference(MR.strings.secure_screen_summary)
    }
}
