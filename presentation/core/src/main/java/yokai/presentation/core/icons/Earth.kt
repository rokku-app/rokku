package yokai.presentation.core.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Same globe as `ic_open_in_webview_24dp`, which the app uses for "Open in WebView".
 */
@Suppress("UnusedReceiverParameter")
val CustomIcons.Earth: ImageVector
    get() = earth

private val earth: ImageVector by lazy {
    ImageVector.Builder(
        name = "Earth",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).addPath(
        pathData = PathParser().parsePathString(
            "M17.9,17.39C17.64,16.59 16.89,16 16,16H15V13A1,1 0 0,0 14,12H8V10H10A1,1 0 0,0 11,9V7H13A2,2 0 0,0 " +
                "15,5V4.59C17.93,5.77 20,8.64 20,12C20,14.08 19.2,15.97 17.9,17.39M11,19.93C7.05,19.44 4,16.08 4,12" +
                "C4,11.38 4.08,10.78 4.21,10.21L9,15V16A2,2 0 0,0 11,18M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22" +
                "A10,10 0 0,0 22,12A10,10 0 0,0 12,2Z",
        ).toNodes(),
        fill = SolidColor(Color(0xFF000000)),
    ).build()
}
