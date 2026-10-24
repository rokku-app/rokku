package eu.kanade.tachiyomi.data.connections.discord

import android.graphics.Color
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR

class Discord(id: Long) : ConnectionsService(id) {

    override fun nameRes() = MR.strings.connections_discord

    override fun getLogo() = R.drawable.ic_discord_24dp

    override fun getLogoColor() = Color.rgb(88, 101, 242)

    override fun logout() {
        val accounts = getAccounts().toMutableList()
        val activeAccount = accounts.find { it.isActive }

        if (activeAccount != null) {
            accounts.removeAll { it.id == activeAccount.id }
        }

        if (accounts.isEmpty()) {
            super.logout()
            connectionsPreferences.connectionsToken(this).delete()
            DiscordRpcManager.logout()
            return
        }

        saveAccounts(accounts)

        val nextAccount = accounts.first()
        setActiveAccount(nextAccount.id)
    }

    override suspend fun login(username: String, password: String) {
        // Not Needed
    }

    private val json = Injekt.get<Json>()

    fun getAccounts(): List<DiscordAccount> {
        val accountsJson = connectionsPreferences.discordAccounts().get()
        return try {
            if (accountsJson.isNotBlank()) {
                json.decodeFromString<List<DiscordAccount>>(accountsJson)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addAccount(account: DiscordAccount) {
        val accounts = getAccounts().toMutableList()
        Logger.d("Discord") { "Adding account: ${account.username}" }

        if (account.isActive) {
            accounts.replaceAll { it.copy(isActive = false) }
            connectionsPreferences.connectionsToken(this).set(account.token)
            syncTokenStore(account)
        }

        val index = accounts.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            accounts[index] = account
        } else {
            accounts.add(account)
        }

        Logger.d("Discord") { "Updated accounts: ${accounts.map { it.username }}" }
        saveAccounts(accounts)
    }

    fun removeAccount(accountId: String) {
        val accounts = getAccounts().toMutableList()
        accounts.removeAll { it.id == accountId }
        saveAccounts(accounts)
    }

    fun setActiveAccount(accountId: String) {
        val accounts = getAccounts().toMutableList()
        accounts.replaceAll { it.copy(isActive = it.id == accountId) }
        saveAccounts(accounts)
        accounts.find { it.id == accountId }?.let { account ->
            connectionsPreferences.connectionsToken(this).set(account.token)
            syncTokenStore(account)
            DiscordRpcManager.clear()
            DiscordRpcManager.reconnectWithToken(account.token)
        }
    }

    fun updateToken(token: String, refreshToken: String?, expiresAt: Long) {
        val accounts = getAccounts().toMutableList()
        val activeIndex = accounts.indexOfFirst { it.isActive }
        if (activeIndex >= 0) {
            val active = accounts[activeIndex]
            accounts[activeIndex] = active.copy(
                token = token,
                refreshToken = refreshToken ?: active.refreshToken,
                expiresAt = if (expiresAt > 0) expiresAt else active.expiresAt,
            )
            connectionsPreferences.connectionsToken(this).set(token)
            saveAccounts(accounts)
        }
    }

    private fun syncTokenStore(account: DiscordAccount) {
        DiscordTokenStore.clear()
        DiscordTokenStore.store(account.token, account.refreshToken)
        if (account.expiresAt > 0L) {
            DiscordTokenStore.storeExpiresAt(account.expiresAt)
        }
    }

    fun restartRichPresence() {
        connectionsPreferences.enableDiscordRPC().set(false)
        connectionsPreferences.enableDiscordRPC().set(true)
    }

    private fun saveAccounts(accounts: List<DiscordAccount>) {
        try {
            val accountsJson = json.encodeToString(accounts)
            connectionsPreferences.discordAccounts().set(accountsJson)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
