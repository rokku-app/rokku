package eu.kanade.tachiyomi.util.system

import android.content.Context
import androidx.annotation.CallSuper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

object AuthenticatorUtil {

    /**
     * A check to avoid double authentication on older APIs when confirming settings changes since
     * the biometric prompt is launched in a separate activity outside of the app.
     */
    var isAuthenticating = false

    /**
     * Launches biometric prompt.
     *
     * @param title String title that will be shown on the prompt
     * @param subtitle Optional string subtitle that will be shown on the prompt
     * @param confirmationRequired Whether require explicit user confirmation after passive biometric is recognized
     * @param callback Callback object to handle the authentication events
     */
    fun FragmentActivity.startAuthentication(
        title: String,
        subtitle: String? = null,
        confirmationRequired: Boolean = true,
        callback: AuthenticationCallback,
    ) {
        isAuthenticating = true
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            callback,
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_WEAK,
            )
            .setConfirmationRequired(confirmationRequired)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Suspending variant of [startAuthentication]. Resolves to `true` immediately if
     * authentication isn't supported/set up on the device, otherwise resolves once the
     * biometric prompt succeeds or errors out.
     */
    suspend fun FragmentActivity.authenticate(
        title: String,
        subtitle: String? = null,
        confirmationRequired: Boolean = true,
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (!isAuthenticationSupported()) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }

        startAuthentication(
            title,
            subtitle,
            confirmationRequired,
            callback = object : AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    toast(errString.toString())
                    cont.resume(false)
                }
            },
        )
    }

    /**
     * Returns true if Class 2 biometric or credential lock is set and available to use
     */
    fun Context.isAuthenticationSupported(): Boolean {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * [AuthenticationCallback] with extra check
     *
     * @see isAuthenticating
     */
    abstract class AuthenticationCallback : BiometricPrompt.AuthenticationCallback() {
        @CallSuper
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            isAuthenticating = false
        }

        @CallSuper
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            isAuthenticating = false
        }

        @CallSuper
        override fun onAuthenticationFailed() {
            isAuthenticating = false
        }
    }
}
