package eu.kanade.tachiyomi.data.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.updater.AppDownloadInstallJob
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionInstallerJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.chapter.updateTrackChapterMarkedAsRead
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.getParcelableCompat
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.notificationManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.manga.interactor.GetManga
import yokai.presentation.core.Constants
import yokai.presentation.settings.screen.about.NewUpdateData
import java.io.File
import eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID as ID

/**
 * Global [BroadcastReceiver] that runs on UI thread
 * Pending Broadcasts should be made from here.
 * NOTE: Use local broadcasts if possible.
 */
class NotificationReceiver : BroadcastReceiver() {
    private val getChapter: GetChapter by injectLazy()
    private val updateChapter: UpdateChapter by injectLazy()
    private val getManga: GetManga by injectLazy()

    /**
     * Download manager.
     */
    private val downloadManager: DownloadManager by injectLazy()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS_NOTIFICATION -> dismissNotification(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1))

            ACTION_RESUME_DOWNLOADS -> DownloadJob.start(context)

            ACTION_PAUSE_DOWNLOADS -> {
                downloadManager.pauseDownloads()
            }

            ACTION_CLEAR_DOWNLOADS -> downloadManager.clearQueue()

            ACTION_DELETE_IMAGE -> deleteImage(
                context,
                intent.getStringExtra(EXTRA_FILE_LOCATION)!!,
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1),
            )

            ACTION_CANCEL_LIBRARY_UPDATE -> cancelLibraryUpdate(context)

            ACTION_CANCEL_EXTENSION_UPDATE -> cancelExtensionUpdate(context)

            ACTION_START_EXTENSION_INSTALL -> startExtensionUpdater(context, intent)

            ACTION_CANCEL_UPDATE_DOWNLOAD -> cancelDownloadUpdate(context)

            ACTION_START_APP_UPDATE -> startAppUpdate(context, intent)

            ACTION_CANCEL_RESTORE -> cancelRestoreUpdate(context)

            ACTION_SHARE_BACKUP ->
                shareBackup(
                    context,
                    intent.getParcelableCompat(EXTRA_URI, Uri::class.java)!!,
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1),
                )

            ACTION_MARK_AS_READ -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId > -1) {
                    dismissNotification(
                        context,
                        notificationId,
                        intent.getIntExtra(EXTRA_GROUP_ID, 0),
                    )
                }
                val urls = intent.getStringArrayExtra(EXTRA_CHAPTER_URL) ?: return
                val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1)
                markAsRead(urls, mangaId)
            }

            ACTION_SHARE_CRASH_LOG ->
                shareFile(
                    context,
                    intent.getParcelableCompat(EXTRA_URI, Uri::class.java)!!,
                    "text/plain",
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1),
                )
        }
    }

    /**
     * Dismiss the notification
     *
     * @param notificationId the id of the notification
     */
    private fun dismissNotification(context: Context, notificationId: Int) {
        context.notificationManager.cancel(notificationId)
    }

    /**
     * Called to start share intent to share backup file
     *
     * @param context context of application
     * @param path path of file
     * @param notificationId id of notification
     */
    private fun shareBackup(context: Context, uri: Uri, notificationId: Int) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/x-protobuf+gzip"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        dismissNotification(context, notificationId)
        context.startActivity(Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Called to start share intent to share backup file
     *
     * @param context context of application
     * @param path path of file
     * @param notificationId id of notification
     */
    private fun shareFile(context: Context, uri: Uri, fileMimeType: String, notificationId: Int) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            type = fileMimeType
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        dismissNotification(context, notificationId)
        context.startActivity(Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Called to delete image
     *
     * @param path path of file
     * @param notificationId id of notification
     */
    private fun deleteImage(context: Context, path: String, notificationId: Int) {
        dismissNotification(context, notificationId)

        val file = File(path)
        file.delete()

        DiskUtil.scanMedia(context, file)
    }

    /**
     * Method called when user wants to stop a library update
     *
     * @param context context of application
     * @param notificationId id of notification
     */
    private fun cancelLibraryUpdate(context: Context) {
        LibraryUpdateJob.stop(context)
    }

    /**
     * Method called when user wants to stop a library update
     *
     * @param context context of application
     */
    private fun cancelExtensionUpdate(context: Context) {
        dismissNotification(context, Notifications.ID_EXTENSION_PROGRESS)
        ExtensionInstallerJob.stop(context)
    }

    private fun startExtensionUpdater(context: Context, intent: Intent) {
        val extensions = IntentCompat.getParcelableArrayListExtra(
            intent,
            ExtensionInstallerJob.KEY_EXTENSION,
            ExtensionManager.ExtensionInfo::class.java,
        ) as? ArrayList<ExtensionManager.ExtensionInfo> ?: return
        ExtensionInstallerJob.startJob(context, extensions, 1)
    }

    /**
     * Method called when user wants to mark as read
     *
     * @param context context of application
     * @param notificationId id of notification
     */
    private fun markAsRead(chapterUrls: Array<String>, mangaId: Long) {
        val preferences: PreferencesHelper = Injekt.get()

        launchIO {
            val manga = getManga.awaitById(mangaId) ?: return@launchIO
            val chapters = chapterUrls.map {
                val chapter = getChapter.awaitByUrlAndMangaId(it, mangaId, false) ?: return@launchIO
                chapter.read = true
                updateChapter.await(chapter.toProgressUpdate())
                if (preferences.removeAfterMarkedAsRead().get()) {
                    val sourceManager: SourceManager = Injekt.get()
                    val source = sourceManager.get(manga.source) ?: return@launchIO
                    downloadManager.deleteChapters(listOf(chapter), manga, source)
                }
                return@map chapter
            }
            val newLastChapter = chapters.maxByOrNull { it.chapter_number.toInt() }
            LibraryUpdateJob.updateMutableFlow.tryEmit(manga.id)
            updateTrackChapterMarkedAsRead(preferences, newLastChapter, mangaId, 0)
        }
    }

    /** Method called when user wants to stop a restore
     *
     * @param context context of application
     */
    private fun cancelRestoreUpdate(context: Context) {
        BackupRestoreJob.stop(context)
    }

    private fun cancelDownloadUpdate(context: Context) {
        AppDownloadInstallJob.stop(context)
        dismissNotification(context, Notifications.ID_UPDATER)
    }

    private fun startAppUpdate(context: Context, intent: Intent) {
        val url = intent.getStringExtra(AppDownloadInstallJob.EXTRA_DOWNLOAD_URL) ?: return
        val notifyOnInstall =
            intent.getBooleanExtra(AppDownloadInstallJob.EXTRA_NOTIFY_ON_INSTALL, false)
        AppDownloadInstallJob.start(context, url, notifyOnInstall)
    }

    companion object {
        private const val NAME = "NotificationReceiver"

        private const val ACTION_DELETE_IMAGE = "$ID.$NAME.DELETE_IMAGE"

        private const val ACTION_SHARE_BACKUP = "$ID.$NAME.SEND_BACKUP"

        private const val ACTION_SHARE_CRASH_LOG = "$ID.$NAME.SEND_CRASH_LOG"

        private const val ACTION_CANCEL_LIBRARY_UPDATE = "$ID.$NAME.CANCEL_LIBRARY_UPDATE"

        private const val ACTION_CANCEL_EXTENSION_UPDATE = "$ID.$NAME.CANCEL_EXTENSION_UPDATE"

        private const val ACTION_START_EXTENSION_INSTALL = "$ID.$NAME.START_EXTENSION_INSTALL"

        private const val ACTION_CANCEL_UPDATE_DOWNLOAD = "$ID.$NAME.CANCEL_UPDATE_DOWNLOAD"

        private const val ACTION_START_APP_UPDATE = "$ID.$NAME.START_APP_UPDATE"

        private const val ACTION_MARK_AS_READ = "$ID.$NAME.MARK_AS_READ"

        private const val ACTION_CANCEL_RESTORE = "$ID.$NAME.CANCEL_RESTORE"

        private const val EXTRA_FILE_LOCATION = "$ID.$NAME.FILE_LOCATION"

        private const val ACTION_RESUME_DOWNLOADS = "$ID.$NAME.ACTION_RESUME_DOWNLOADS"

        private const val ACTION_PAUSE_DOWNLOADS = "$ID.$NAME.ACTION_PAUSE_DOWNLOADS"

        private const val ACTION_CLEAR_DOWNLOADS = "$ID.$NAME.ACTION_CLEAR_DOWNLOADS"

        private const val ACTION_DISMISS_NOTIFICATION = "$ID.$NAME.ACTION_DISMISS_NOTIFICATION"

        private const val EXTRA_URI = "$ID.$NAME.URI"

        private const val EXTRA_NOTIFICATION_ID = "$ID.$NAME.NOTIFICATION_ID"

        private const val EXTRA_GROUP_ID = "$ID.$NAME.EXTRA_GROUP_ID"

        private const val EXTRA_MANGA_ID = "$ID.$NAME.EXTRA_MANGA_ID"

        private const val EXTRA_CHAPTER_ID = "$ID.$NAME.EXTRA_CHAPTER_ID"

        private const val EXTRA_CHAPTER_URL = "$ID.$NAME.EXTRA_CHAPTER_URL"

        /**
         * Returns a [PendingIntent] that resumes the download of a chapter
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun resumeDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_RESUME_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that pauses the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun pauseDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_PAUSE_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns a [PendingIntent] that clears the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun clearDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_CLEAR_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which dismissed the notification
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun dismissNotificationPendingBroadcast(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_DISMISS_NOTIFICATION
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which dismissed the notification
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun dismissNotification(
            context: Context,
            notificationId: Int,
            groupId: Int? =
                null,
        ) {
            val groupKey = context.notificationManager.activeNotifications.find {
                it.id == notificationId
            }?.groupKey
            if (groupId != null && groupId != 0 && groupKey != null && groupKey.isNotEmpty()) {
                val notifications = context.notificationManager.activeNotifications.filter {
                    it.groupKey == groupKey
                }
                if (notifications.size == 2) {
                    context.notificationManager.cancel(groupId)
                    return
                }
            }
            context.notificationManager.cancel(notificationId)
        }

        /**
         * Returns [PendingIntent] that starts a service which cancels the notification and starts a share activity
         *
         * @param context context of application
         * @param path location path of file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun shareImagePendingBroadcast(context: Context, path: String, notificationId: Int): PendingIntent {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                val uri = File(path).getUriCompat(context)
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
                clipData = ClipData.newRawUri(null, uri)
                type = "image/*"
            }
            return PendingIntent.getActivity(
                context,
                0,
                Intent.createChooser(shareIntent, null),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which removes an image from disk
         *
         * @param context context of application
         * @param path location path of file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun deleteImagePendingBroadcast(context: Context, path: String, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_DELETE_IMAGE
                putExtra(EXTRA_FILE_LOCATION, path)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a reader activity containing chapter.
         *
         * @param context context of application
         * @param manga manga of chapter
         * @param chapter chapter that needs to be opened
         */
        internal fun openChapterPendingActivity(
            context: Context,
            manga: Manga,
            chapter: Chapter,
        ): PendingIntent {
            val newIntent = ReaderActivity.newIntent(context, manga, chapter)
            return PendingIntent.getActivity(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens release notes for the next update.
         *
         * @param context context of application
         * @param notes notes of the release
         * @param downloadLink download link to the apk
         * @param version version of the release
         */
        internal fun openUpdatePendingActivity(context: Context, notes: String, downloadLink: String, version: String): PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setPackage(context.packageName).setAction(MainActivity.SHORTCUT_UPDATE_NOTES)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(NewUpdateData.BODY_KEY, notes)
                    .putExtra(NewUpdateData.URL_KEY, downloadLink)
                    .putExtra(NewUpdateData.VERSION_KEY, version)
            return PendingIntent.getActivity(
                context,
                downloadLink.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the manga details controller.
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun openChapterPendingActivity(context: Context, manga: Manga, groupId: Int): PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setPackage(context.packageName).setAction(Constants.SHORTCUT_MANGA)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(Constants.MANGA_EXTRA, manga.id)
                    .putExtra("notificationId", manga.id.hashCode())
                    .putExtra("groupId", groupId)
            return PendingIntent.getActivity(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the error or skipped log file in an external viewer
         *
         * @param context context of application
         * @param uri uri of error or skipped log file
         * @return [PendingIntent]
         */
        internal fun openErrorOrSkippedLogPendingActivity(context: Context, uri: Uri): PendingIntent {
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "text/plain")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            return PendingIntent.getActivity(
                context,
                uri.hashCode(),
                Intent.createChooser(intent, null),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the extensions controller,
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun openExtensionsPendingActivity(context: Context): PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setPackage(context.packageName).setAction(MainActivity.SHORTCUT_EXTENSIONS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the library update errors screen.
         *
         * @param context context of application
         */
        internal fun openLibraryUpdateErrorsPendingActivity(context: Context): PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setPackage(context.packageName).setAction(MainActivity.SHORTCUT_LIBRARY_UPDATE_ERRORS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that marks a chapter as read and deletes it if preferred
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun markAsReadPendingBroadcast(
            context: Context,
            manga: Manga,
            chapters: Array<Chapter>,
            groupId: Int,
        ): PendingIntent {
            val newIntent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_MARK_AS_READ
                putExtra(EXTRA_CHAPTER_URL, chapters.map { it.url }.toTypedArray())
                putExtra(EXTRA_MANGA_ID, manga.id)
                putExtra(EXTRA_NOTIFICATION_ID, manga.id.hashCode())
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            return PendingIntent.getBroadcast(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which stops the library update
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun cancelLibraryUpdatePendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_CANCEL_LIBRARY_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which stops the library update
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun cancelExtensionUpdatePendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_CANCEL_EXTENSION_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun startExtensionUpdatePendingJob(context: Context, extensions: List<Extension.Available>): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                val info = extensions.map(ExtensionManager::ExtensionInfo)
                action = ACTION_START_EXTENSION_INSTALL
                putParcelableArrayListExtra(ExtensionInstallerJob.KEY_EXTENSION, ArrayList(info))
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun startAppUpdatePendingJob(context: Context, url: String, notifyOnInstall: Boolean = false): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_START_APP_UPDATE
                putExtra(AppDownloadInstallJob.EXTRA_DOWNLOAD_URL, url)
                putExtra(AppDownloadInstallJob.EXTRA_NOTIFY_ON_INSTALL, notifyOnInstall)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun dismissFailThenStartAppUpdatePendingJob(context: Context, url: String, notifyOnInstall: Boolean = false): PendingIntent {
            dismissNotification(context, Notifications.ID_UPDATER_FAILED)
            return startAppUpdatePendingJob(context, url, notifyOnInstall)
        }

        /**
         * Returns [PendingIntent] that cancels the download for a Tachiyomi update
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun cancelUpdateDownloadPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_CANCEL_UPDATE_DOWNLOAD
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a share activity for a backup file.
         *
         * @param context context of application
         * @param uri uri of backup file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun shareBackupPendingBroadcast(context: Context, uri: Uri, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_SHARE_BACKUP
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a share activity for a crash log dump file.
         *
         * @param context context of application
         * @param uri uri of file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun shareCrashLogPendingBroadcast(context: Context, uri: Uri, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_SHARE_CRASH_LOG
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that cancels a backup restore job.
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun cancelRestorePendingBroadcast(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setPackage(context.packageName).apply {
                action = ACTION_CANCEL_RESTORE
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
