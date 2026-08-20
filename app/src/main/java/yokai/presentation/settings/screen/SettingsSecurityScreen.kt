package yokai.presentation.settings.screen

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.settings.ComposableSettings
import yokai.util.lang.getString

object SettingsSecurityScreen : ComposableSettings() {

    private fun readResolve() = SettingsSecurityScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = MR.strings.security

    @Composable
    override fun getPreferences(): List<Preference> {
        val securityPreferences: SecurityPreferences by injectLazy()
        val preferences: PreferencesHelper by injectLazy()

        return listOf(getSecurityGroup(securityPreferences, preferences))
    }

    @Composable
    private fun getSecurityGroup(
        securityPreferences: SecurityPreferences,
        preferences: PreferencesHelper,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val authSupported = remember { context.isAuthenticationSupported() }
        val useBiometricsPref = securityPreferences.useBiometrics()
        val useBiometrics by useBiometricsPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.security),
            preferenceItems = buildList {
                if (authSupported) {
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            pref = useBiometricsPref,
                            title = stringResource(MR.strings.lock_with_biometrics),
                            onValueChanged = {
                                (context as FragmentActivity).authenticate(
                                    title = context.getString(MR.strings.lock_with_biometrics),
                                    confirmationRequired = false,
                                )
                            },
                        ),
                    )
                    if (useBiometrics) {
                        add(
                            Preference.PreferenceItem.ListPreference(
                                pref = preferences.lockAfter(),
                                title = stringResource(MR.strings.lock_when_idle),
                                entries = LockAfterValues.associateWith {
                                    when (it) {
                                        0 -> context.getString(MR.strings.always)
                                        -1 -> context.getString(MR.strings.never)
                                        else -> context.getString(MR.plurals.after_minutes, it, it)
                                    }
                                }.toImmutableMap(),
                            ),
                        )
                    }
                }

                add(
                    Preference.PreferenceItem.SwitchPreference(
                        pref = preferences.hideNotificationContent(),
                        title = stringResource(MR.strings.hide_notification_content),
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        pref = preferences.secureScreen(),
                        title = stringResource(MR.strings.secure_screen),
                        entries = PreferenceValues.SecureScreenMode.entries
                            .associateWith { stringResource(it.titleResId) }
                            .toImmutableMap(),
                        onValueChanged = {
                            SecureActivityDelegate.setSecure(context as? Activity)
                            true
                        },
                    ),
                )
                add(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.secure_screen_summary)))
            }.toImmutableList(),
        )
    }
}

private val LockAfterValues = listOf(0, 2, 5, 10, 20, 30, 60, 90, 120, -1)
