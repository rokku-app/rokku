package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionReposBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepo
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.domain.manga.models.Manga
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.backup.BackupPreferences
import yokai.domain.manga.interactor.GetManga
import yokai.i18n.MR
import yokai.util.lang.getString
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

class BackupCreator(
    val context: Context,
    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    private val extensionReposBackupCreator: ExtensionReposBackupCreator = ExtensionReposBackupCreator(),
    private val getManga: GetManga = Injekt.get(),
) {

    val parser = ProtoBuf
    private val backupPreferences: BackupPreferences = Injekt.get()

    /**
     * Create backup Json file from database
     *
     * @param uri path of Uri
     * @param isAutoBackup backup called from scheduled backup job
     */
    suspend fun createBackup(uri: Uri, options: BackupOptions, isAutoBackup: Boolean): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                val numberOfBackups = backupPreferences.numberOfBackups().get()
                dir?.listFiles { _, filename -> Backup.filenameRegex.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(numberOfBackups - 1)
                    .forEach { it.delete() }

                // Create new file to place backup
                dir?.createFile(Backup.getBackupFilename())
            } else {
                UniFile.fromUri(context, uri)
            } ?: throw BackupCreateException(context.getString(MR.strings.backup_location_unavailable))

            if (!file.isFile) {
                throw BackupCreateException(context.getString(MR.strings.backup_location_unavailable))
            }

            val readNotFavorites = if (options.readManga) getManga.awaitReadNotFavorites() else emptyList()
            val backupManga = backupMangas(getManga.awaitFavorites() + readNotFavorites, options)
            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupSourcePreferences = backupSourcePreferences(options),
                backupExtensionRepo = backupExtensionRepos(options),
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.getString(MR.strings.empty_backup_error))
            }

            try {
                file.openOutputStream().also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }.sink().gzip().buffer().use { it.write(byteArray) }
            } catch (e: IOException) {
                throw BackupCreateException(context.getString(MR.strings.backup_location_unavailable), e)
            }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator().validate(context, fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp().set(Instant.now().toEpochMilli())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            Logger.e(e) { "Backup failed: ${e.message}" }
            file?.delete()
            throw e
        }
    }

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    private fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appPrefs) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.includePrivate)
    }

    private fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourcePrefs) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.includePrivate)
    }

    private suspend fun backupExtensionRepos(options: BackupOptions): List<BackupExtensionRepo> {
        if (!options.extensionRepos) return emptyList()

        return extensionReposBackupCreator()
    }
}
