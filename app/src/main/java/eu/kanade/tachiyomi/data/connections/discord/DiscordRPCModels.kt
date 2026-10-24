package eu.kanade.tachiyomi.data.connections.discord

import dev.icerock.moko.resources.StringResource
import yokai.i18n.MR

const val RICH_PRESENCE_TAG = "discord_rpc"

val DOWNLOAD_BUTTON_LABEL = MR.strings.download_app
const val DOWNLOAD_BUTTON_URL = "https://github.com/rokku-app/rokku/releases"

val READ_CHAPTER_BUTTON_LABEL = MR.strings.read_chapter_button

data class ReaderData(
    val incognitoMode: Boolean = false,
    val mangaId: Long? = null,
    val mangaTitle: String? = null,
    val chapterNumber: Float = 0f,
    val chapterProgress: Pair<Int, Int> = Pair(0, 0),
    val chapterIndex: Int = 0,
    val totalChapters: Int = 0,
    val chapterTitle: String? = null,
    val thumbnailUrl: String? = null,
    val browsingOnly: Boolean = false,
    val sourceUrl: String? = null,
    val seriesType: String? = null,
)

enum class DiscordScreen(
    val text: StringResource,
    val details: StringResource,
    val state: StringResource,
    val imageUrl: String,
) {
    APP(MR.strings.app_name, MR.strings.browsing, MR.strings.library, ROKKU_IMAGE_URL),
    LIBRARY(MR.strings.app_name, MR.strings.browsing, MR.strings.library, LIBRARY_IMAGE_URL),
    RECENTS(MR.strings.app_name, MR.strings.scrolling, MR.strings.recents, RECENTS_IMAGE_URL),
    BROWSE(MR.strings.app_name, MR.strings.browsing, MR.strings.browse, BROWSE_IMAGE_URL),
    MANGA(MR.strings.app_name, MR.strings.comic, MR.strings.reading, MANGA_IMAGE_URL),
    MORE(MR.strings.app_name, MR.strings.messing, MR.strings.settings, MORE_IMAGE_URL),

    // temporarily deactivated
    // WEBVIEW(MR.strings.app_name, MR.strings.browsing, MR.strings.action_web_view, WEBVIEW_IMAGE_URL),
    // UPDATES(MR.strings.app_name, MR.strings.scrolling, MR.strings.recents, updatesImageUrl),
}

private const val CDN = "https://cdn.discordapp.com/"
private const val ROKKU_IMAGE_URL = "${CDN}emojis/1553410036825464893.webp?quality=lossless"
private const val LIBRARY_IMAGE_URL = "${CDN}emojis/1556127745619132456.webp?quality=lossless"
private const val RECENTS_IMAGE_URL = "${CDN}emojis/1556127752191737906.webp?quality=lossless"
private const val BROWSE_IMAGE_URL = "${CDN}emojis/1556127749855252540.webp?quality=lossless"
private const val MANGA_IMAGE_URL = "${CDN}emojis/1556127747686932521.webp?quality=lossless"
private const val MORE_IMAGE_URL = "${CDN}emojis/1558387170761375824.webp?quality=lossless"

// temporarily deactivated
// private const val WEBVIEW_IMAGE_URL = "${CDN}emojis/1391952048223817791.webp?quality=lossless"
// private const val updatesImageUrl = "${CDN}emojis/1391945005194674237.webp?quality=lossless"
