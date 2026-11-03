package eu.kanade.tachiyomi.data.connections.discord

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.ui.util.fastAny
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.models.Category.Companion.UNCATEGORIZED_ID
import yokai.domain.connections.service.ConnectionsPreferences
import yokai.domain.connections.service.DiscordButton
import yokai.domain.connections.service.DiscordProgressMode
import yokai.i18n.MR
import yokai.util.lang.getString

class DiscordRPCService : Service() {

    private val connectionsManager: ConnectionsManager by injectLazy()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        if (!notification(this)) return

        if (!DiscordRpcManager.engineActivitySet) {
            Log.w(TAG, "No engine activity in this process, stopping")
            stopSelf()
            return
        }

        if (!DiscordRpcManager.isInitialized()) {
            DiscordRpcManager.init(applicationContext)
        }

        // Get stored OAuth token for the native SDK
        val effectiveToken = DiscordTokenStore.retrieve()
        val effectiveRefreshToken = DiscordTokenStore.retrieveRefreshToken()

        if (!effectiveToken.isNullOrBlank() || !effectiveRefreshToken.isNullOrBlank()) {
            if (!DiscordRpcManager.isAuthorized()) {
                DiscordRpcManager.reconnectWithToken(effectiveToken ?: "")
            }

            // Listen for connection ready state and update RPC automatically when established
            launchIO {
                DiscordRpcManager.connectionStatus.collect { status ->
                    if (status == DiscordRpcManager.Status.Connected && !isPaused) {
                        try {
                            DiscordRpcManager.setOnlineStatus(
                                when (connectionsPreferences.discordRPCStatus().get()) {
                                    -1 -> DiscordRpcManager.StatusType.Dnd
                                    0 -> DiscordRpcManager.StatusType.Idle
                                    else -> DiscordRpcManager.StatusType.Online
                                },
                            )
                            val data = activeReaderData ?: ReaderData()
                            setScreen(this@DiscordRPCService, currentScreen, data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating presence on connection ready: ${e.message}", e)
                        }
                    }
                }
            }
        } else {
            connectionsPreferences.enableDiscordRPC().set(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCORD_TOGGLE_PAUSE_RESUME -> {
                isPaused = !isPaused
                notification(this)
                if (isPaused) {
                    DiscordRpcManager.clear()
                } else {
                    val data = activeReaderData ?: ReaderData()
                    launchIO { setScreen(this@DiscordRPCService, currentScreen, data) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationReceiver.dismissNotification(this, Notifications.ID_DISCORD_RPC)
        DiscordRpcManager.destroy()
        since = 0L
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun notification(context: Context): Boolean {
        val toggleIcon = if (isPaused) R.drawable.ic_play_arrow_24dp else R.drawable.ic_pause_24dp
        val toggleText = if (isPaused) getString(R.string.resume) else getString(R.string.pause)
        val builder = context.notificationBuilder(Notifications.CHANNEL_DISCORD_RPC) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setSmallIcon(R.drawable.ic_discord_24dp)
            setContentText(context.resources.getString(R.string.discord_rpc))
            setAutoCancel(false)
            setOngoing(true)
            setWhen(since)
            setShowWhen(true)
            setUsesChronometer(true)
            addAction(toggleIcon, toggleText, togglePauseResumePendingIntent(context))
        }

        return try {
            startForeground(Notifications.ID_DISCORD_RPC, builder.build())
            true
        } catch (_: Exception) {
            DiscordRpcManager.clear()
            stopSelf()
            false
        }
    }

    private fun togglePauseResumePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DiscordRPCService::class.java).apply {
            this.action = ACTION_DISCORD_TOGGLE_PAUSE_RESUME
        }
        return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {

        private val connectionsPreferences: ConnectionsPreferences by injectLazy()
        private val handler = Handler(Looper.getMainLooper())

        internal var isPaused = false

        const val ACTION_DISCORD_TOGGLE_PAUSE_RESUME = "eu.kanade.tachiyomi.action.DISCORD_RPC_TOGGLE_PAUSE_RESUME"

        private const val ACTIVITY_UPDATE_DELAY_MS = 5_000L
        private var pendingActivityUpdate: Job? = null
        private var since = 0L
        private var activeReaderData: ReaderData? = null
        internal var currentScreen = DiscordScreen.APP

        private const val TAG = "DiscordRPCService"

        internal var lastUsedScreen = DiscordScreen.APP
            set(value) {
                field = if (value == DiscordScreen.MANGA) field else value
            }

        fun start(context: Context) {
            handler.removeCallbacksAndMessages(null)
            if (connectionsPreferences.enableDiscordRPC().get()) {
                if (since == 0L) {
                    since = System.currentTimeMillis()
                }
                context.startForegroundService(Intent(context, DiscordRPCService::class.java))
            }
        }

        fun stop(context: Context, delay: Long = 30000L) {
            handler.postDelayed(
                { context.stopService(Intent(context, DiscordRPCService::class.java)) },
                delay,
            )
        }

        private var lastRefreshAttempt = 0L

        internal suspend fun ensureTokenValid() {
            if (!DiscordRpcManager.isReady()) return
            if (DiscordTokenStore.isExpired() && !DiscordTokenStore.retrieveRefreshToken().isNullOrBlank()) {
                val now = System.currentTimeMillis()
                if (now - lastRefreshAttempt > 60_000L) {
                    lastRefreshAttempt = now
                    Log.i(TAG, "Token is expired or close to expiry, attempting refresh in background...")
                    withIOContext {
                        if (DiscordRpcManager.refreshAccessTokenSync()) {
                            val newToken = DiscordRpcManager.getAccessToken()
                            if (newToken != null) {
                                DiscordRpcManager.reconnectWithToken(newToken)
                            }
                        }
                    }
                }
            }
        }

        internal suspend fun setScreen(
            context: Context,
            discordScreen: DiscordScreen,
            readerData: ReaderData = ReaderData(),
        ) {
            if (!connectionsPreferences.enableDiscordRPC().get()) return
            ensureTokenValid()
            currentScreen = discordScreen
            if (discordScreen != DiscordScreen.MANGA) {
                lastUsedScreen = discordScreen
                activeReaderData = null
            }
            if (!DiscordRpcManager.isReady() || isPaused) return
            updateDiscordRPC(context, readerData, discordScreen)
        }

        private suspend fun updateDiscordRPC(
            context: Context,
            readerData: ReaderData,
            discordScreen: DiscordScreen,
        ) {
            val appName = context.getString(MR.strings.app_name)
            val customMessage = connectionsPreferences.discordCustomMessage().get()
            val showAppIcon = connectionsPreferences.discordShowAppIcon().get()
            val progressMode = connectionsPreferences.discordProgressMode().get()
            val buttons = connectionsPreferences.discordButtons().get()
            val showMangaButton = DiscordButton.MANGA in buttons
            val showDownloadButton = DiscordButton.DOWNLOAD in buttons

            val details = sanitizeField(
                when {
                    readerData.browsingOnly -> context.getString(MR.strings.browsing)
                    readerData.mangaTitle != null -> readerData.mangaTitle
                    else -> context.getString(discordScreen.details)
                },
            )

            val state = sanitizeField(
                when {
                    readerData.browsingOnly -> readerData.mangaTitle ?: context.getString(discordScreen.state)
                    else -> context.getString(discordScreen.state)
                },
            )

            val imageUrl = resolveDisplayImage(context, readerData, discordScreen.imageUrl)

            val button1Label = if (showMangaButton && readerData.sourceUrl != null) {
                if (discordScreen == DiscordScreen.MANGA && !readerData.browsingOnly) {
                    context.getString(READ_CHAPTER_BUTTON_LABEL)
                } else {
                    readerData.seriesType?.let {
                        context.getString(MR.strings.view_series, it)
                    }
                }
            } else {
                null
            }

            val button1Url = if (showMangaButton && readerData.sourceUrl != null) {
                readerData.sourceUrl
            } else {
                null
            }

            val button2Label = if (showDownloadButton) {
                context.getString(DOWNLOAD_BUTTON_LABEL)
            } else {
                null
            }

            val button2Url = if (showDownloadButton) {
                DOWNLOAD_BUTTON_URL
            } else {
                null
            }

            val isReading = discordScreen == DiscordScreen.MANGA && !readerData.browsingOnly && !readerData.incognitoMode

            val readingDetails = sanitizeField(getChapterLabel(context, readerData))
                ?: sanitizeField(
                    context.getString(
                        MR.strings.chapter_,
                        ChapterUtil.formatChapterNumber(readerData.chapterNumber.toDouble()),
                    ),
                )

            val readingState = sanitizeField(getProgressText(context, readerData, progressMode))
                ?: sanitizeField(context.getString(discordScreen.state))

            val customTitle = sanitizeField(customMessage)
            val title = customTitle
                ?: if (isReading) readerData.mangaTitle ?: appName else appName

            DiscordRpcManager.setActivity(
                DiscordNativeActivity(
                    activityType = DiscordNativeActivity.TYPE_WATCHING,
                    name = title,
                    details = if (isReading) readingDetails else details,
                    state = if (isReading) readingState else state,
                    startTimestamp = since / 1000L,
                    largeImage = imageUrl,
                    largeText = appName,
                    smallImage = if (showAppIcon) DiscordScreen.APP.imageUrl else null,
                    smallText = if (showAppIcon) context.getString(DiscordScreen.APP.text) else null,
                    button1Label = button1Label,
                    button1Url = button1Url,
                    button2Label = button2Label,
                    button2Url = button2Url,
                ),
            )
        }

        @OptIn(DelicateCoroutinesApi::class)
        fun updateReaderActivity(context: Context, readerData: ReaderData = ReaderData()) {
            pendingActivityUpdate?.cancel()
            pendingActivityUpdate = launchIO {
                delay(ACTIVITY_UPDATE_DELAY_MS)
                setReaderActivity(context, readerData)
            }
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        internal suspend fun setReaderActivity(context: Context, readerData: ReaderData = ReaderData()) {
            if (!connectionsPreferences.enableDiscordRPC().get()) return
            if (readerData.mangaId == null) return
            try {
                val categories = Injekt.get<GetCategories>()
                    .awaitByMangaId(readerData.mangaId)
                    .map { it.id.toString() }
                    .run { ifEmpty { plus(UNCATEGORIZED_ID.toString()) } }

                val discordIncognito = isIncognito(categories, readerData.incognitoMode)
                val mangaTitle = readerData.mangaTitle.takeUnless { discordIncognito }
                val mangaThumbnail = if (discordIncognito) null else readerData.thumbnailUrl.takeUnless { it.isNullOrBlank() }

                val sourceUrl = readerData.sourceUrl.takeUnless { discordIncognito }
                val data = ReaderData(
                    incognitoMode = discordIncognito,
                    mangaId = readerData.mangaId.takeUnless { discordIncognito },
                    mangaTitle = mangaTitle,
                    chapterNumber = readerData.chapterNumber,
                    chapterProgress = readerData.chapterProgress,
                    chapterIndex = readerData.chapterIndex,
                    totalChapters = readerData.totalChapters,
                    chapterTitle = readerData.chapterTitle,
                    thumbnailUrl = mangaThumbnail,
                    sourceUrl = sourceUrl,
                    browsingOnly = readerData.browsingOnly,
                    seriesType = readerData.seriesType.takeUnless { discordIncognito },
                )

                currentScreen = DiscordScreen.MANGA
                activeReaderData = data

                if (!DiscordRpcManager.isReady() || isPaused) return
                withIOContext { setScreen(context, DiscordScreen.MANGA, data) }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting reader activity: ${e.message}", e)
            }
        }

        private suspend fun resolveDisplayImage(context: Context, readerData: ReaderData, fallback: String): String {
            if (connectionsPreferences.discordUploadLocalCovers().get() && readerData.mangaId != null) {
                val customCover = Injekt.get<CoverCache>().getCustomCoverFile(readerData.mangaId)
                if (customCover.exists()) {
                    DiscordImageUploader.resolveImageUrl(context, customCover)?.let { return it }
                }
            }

            val thumbnailUrl = readerData.thumbnailUrl
            if (thumbnailUrl.isNullOrBlank()) return fallback
            if (thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://")) return thumbnailUrl
            if (!connectionsPreferences.discordUploadLocalCovers().get()) return fallback
            return DiscordImageUploader.resolveImageUrl(context, thumbnailUrl) ?: fallback
        }

        private fun getChapterLabel(context: Context, readerData: ReaderData): String? =
            if (connectionsPreferences.useChapterTitles().get()) {
                readerData.chapterTitle
            } else {
                context.getString(
                    MR.strings.chapter_,
                    ChapterUtil.formatChapterNumber(readerData.chapterNumber.toDouble()),
                )
            }

        private fun getProgressText(context: Context, readerData: ReaderData, progressMode: Int): String? =
            when (progressMode) {
                DiscordProgressMode.PAGES -> {
                    val (currentPage, totalPages) = readerData.chapterProgress
                    context.getString(MR.strings.page_count, currentPage.toString(), totalPages.toString())
                }

                DiscordProgressMode.CHAPTERS -> if (readerData.totalChapters > 0) {
                    context.getString(
                        MR.strings.chapter_progress,
                        readerData.chapterIndex.toString(),
                        readerData.totalChapters.toString(),
                    )
                } else {
                    null
                }

                else -> null
            }

        private fun sanitizeField(value: String?): String? {
            if (value == null) return null
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.length < 2) return "$trimmed "
            if (trimmed.length > 128) return trimmed.take(128)
            return trimmed
        }

        private fun isIncognito(categories: List<String>, incognitoMode: Boolean): Boolean {
            val discordIncognitoMode = connectionsPreferences.discordRPCIncognito().get()
            val incognitoCategories = connectionsPreferences.discordRPCIncognitoCategories().get()
            val incognitoCategory = categories.fastAny { it in incognitoCategories }
            return discordIncognitoMode || incognitoMode || incognitoCategory
        }
    }
}
