package yokai.presentation.settings.screen.about

import android.os.Build
import android.text.method.LinkMovementMethod
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.textview.MaterialTextView
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.updater.AppDownloadInstallJob
import eu.kanade.tachiyomi.ui.more.parseReleaseNotes
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.DialogHostState
import yokai.i18n.MR
import java.io.Serializable
import kotlin.coroutines.resume
import android.R as AR

/**
 * @property version the release's version, set when the dialog may offer to skip it
 */
data class NewUpdateData(
    val body: String,
    val url: String,
    val isBeta: Boolean?,
    val version: String? = null,
) : Serializable {

    companion object {
        // Also read from the intents of notifications posted by earlier versions
        const val BODY_KEY = "NewUpdateDialogController.body"
        const val URL_KEY = "NewUpdateDialogController.key"
        const val VERSION_KEY = "NewUpdateDialogController.version"
        const val IS_BETA = "NewUpdateDialogController.is_beta"
    }
}

suspend fun DialogHostState.awaitNewUpdateDialog(
    data: NewUpdateData,
    onDismiss: () -> Unit = {},
): Unit = dialog { cont ->
    val context = LocalContext.current
    val appContext = context.applicationContext

    val isOnA12 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    AlertDialog(
        onDismissRequest = {
            onDismiss()
            cont.cancel()
        },
        title = {
            Text(
                text = stringResource(
                    if (data.isBeta == true) {
                        MR.strings.new_beta_version_available
                    } else {
                        MR.strings.new_version_available
                    },
                ),
            )
        },
        // All buttons go in one slot so they share a single row, which the dialog would otherwise split in two
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (data.version != null) {
                    TextButton(
                        onClick = {
                            Injekt.get<PreferencesHelper>().skippedAppUpdate().set(data.version)
                            onDismiss()
                            cont.cancel()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.skip_this_version))
                    }
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.ignore))
                }
                TextButton(
                    onClick = {
                        AppDownloadInstallJob.start(appContext, data.url, true)
                        onDismiss()
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(if (isOnA12) MR.strings.update else MR.strings.download))
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                MarkdownText(data.body)
            }
        },
    )
}

@Composable
private fun MarkdownText(text: String) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            MaterialTextView(it)
        },
        update = {
            it.text = context.parseReleaseNotes(text)
            it.movementMethod = LinkMovementMethod.getInstance()
        },
    )
}

suspend fun DialogHostState.awaitNotificationPermissionDeniedDialog(): Unit = dialog { cont ->
    // cont.resume(Unit) so that new update dialog will be shown next
    AlertDialog(
        onDismissRequest = { if (cont.isActive) cont.resume(Unit) },
        title = { Text(text = stringResource(MR.strings.warning)) },
        text = { Text(text = stringResource(MR.strings.allow_notifications_recommended)) },
        confirmButton = {
            TextButton(onClick = { if (cont.isActive) cont.resume(Unit) }) {
                Text(text = stringResource(AR.string.ok))
            }
        },
    )
}
