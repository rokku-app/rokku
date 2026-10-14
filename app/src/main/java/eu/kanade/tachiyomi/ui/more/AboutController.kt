package eu.kanade.tachiyomi.ui.more

import android.content.Context
import android.text.Spanned
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import io.noties.markwon.Markwon
import yokai.presentation.settings.screen.about.AboutScreen

class AboutController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = AboutScreen(),
            content = {
                CrossfadeTransition(navigator = it)
            },
        )
    }
}

fun Context.parseReleaseNotes(releaseNotes: String): Spanned {
    val releaseBody = releaseNotes.replace("""---(\R|.)*Checksums(\R|.)*""".toRegex(), "")
    return Markwon.create(this).toMarkdown(releaseBody)
}
