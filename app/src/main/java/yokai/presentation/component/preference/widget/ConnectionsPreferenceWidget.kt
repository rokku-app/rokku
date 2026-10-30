package yokai.presentation.component.preference.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsService
import yokai.i18n.MR
import yokai.presentation.component.ConnectionsLogoIcon
import yokai.presentation.component.preference.LocalPreferenceHighlighted

@Composable
fun ConnectionsPreferenceWidget(
    service: ConnectionsService,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    isLoading: Boolean = false,
) {
    val highlighted = LocalPreferenceHighlighted.current

    Box(modifier = Modifier.highlightBackground(highlighted)) {
        Row(
            modifier = modifier
                .clickable(
                    enabled = onClick != null && !isLoading,
                    onClick = { onClick?.invoke() },
                )
                .fillMaxWidth()
                .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionsLogoIcon(service)

            Text(
                text = stringResource(service.nameRes()),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                maxLines = 1,
                style = MaterialTheme.typography.titleLarge,
                fontSize = TitleFontSize,
            )

            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }

                checked -> {
                    Icon(
                        imageVector = Icons.Outlined.Done,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(32.dp),
                        tint = Color(0xFF4CAF50),
                        contentDescription = stringResource(
                            MR.strings.successfully_logged_in,
                        ),
                    )
                }
            }
        }
    }
}
