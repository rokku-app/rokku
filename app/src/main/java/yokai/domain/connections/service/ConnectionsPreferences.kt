package yokai.domain.connections.service

import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.connections.ConnectionsService

class ConnectionsPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun connectionsUsername(sync: ConnectionsService) = preferenceStore.getString(
        connectionsUsername(sync.id),
        "",
    )

    fun connectionsPassword(sync: ConnectionsService) = preferenceStore.getString(
        connectionsPassword(sync.id),
        "",
    )

    fun setConnectionsCredentials(sync: ConnectionsService, username: String, password: String) {
        connectionsUsername(sync).set(username)
        connectionsPassword(sync).set(password)
    }

    fun connectionsToken(sync: ConnectionsService) = preferenceStore.getString(connectionsToken(sync.id), "")

    fun enableDiscordRPC() = preferenceStore.getBoolean("pref_enable_discord_rpc", false)

    fun discordRPCStatus() = preferenceStore.getInt("pref_discord_rpc_status", 1)

    fun discordRPCIncognito() = preferenceStore.getBoolean("pref_discord_rpc_incognito", false)

    fun discordRPCIncognitoCategories() = preferenceStore.getStringSet("discord_rpc_incognito_categories", emptySet())

    fun useChapterTitles() = preferenceStore.getBoolean("pref_discord_rpc_use_chapter_titles", true)

    fun discordUploadLocalCovers() = preferenceStore.getBoolean("pref_discord_upload_local_covers", false)

    fun discordCustomMessage() = preferenceStore.getString("pref_discord_custom_message", "")

    fun discordShowAppIcon() = preferenceStore.getBoolean("pref_discord_show_app_icon", true)

    fun discordProgressMode() = preferenceStore.getInt("pref_discord_progress_mode", DiscordProgressMode.PAGES)

    fun discordShowButtons() = preferenceStore.getBoolean("pref_discord_show_buttons", true)

    fun discordButtons() = preferenceStore.getStringSet(
        "pref_discord_buttons",
        setOf(DiscordButton.MANGA, DiscordButton.DOWNLOAD),
    )

    fun discordShowMangaButton() = preferenceStore.getBoolean("pref_discord_show_manga_button", true)

    fun discordAccounts() = preferenceStore.getString("discord_accounts", "")

    companion object {

        fun connectionsUsername(syncId: Long) = "pref_connections_username_$syncId"

        private fun connectionsPassword(syncId: Long) = "pref_connections_password_$syncId"

        private fun connectionsToken(syncId: Long) = "connection_token_$syncId"
    }
}

object DiscordProgressMode {
    const val OFF = 0
    const val PAGES = 1
    const val CHAPTERS = 2
}

object DiscordButton {
    const val MANGA = "manga"
    const val DOWNLOAD = "download"
}
