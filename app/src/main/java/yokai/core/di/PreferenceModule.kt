package yokai.core.di

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.core.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.core.storage.AndroidStorageFolderProvider
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackPreferences
import eu.kanade.tachiyomi.network.NetworkPreferences
import org.koin.dsl.module
import yokai.domain.backup.BackupPreferences
import yokai.domain.base.BasePreferences
import yokai.domain.connections.service.ConnectionsPreferences
import yokai.domain.download.DownloadPreferences
import yokai.domain.library.LibraryPreferences
import yokai.domain.recents.RecentsPreferences
import yokai.domain.source.SourcePreferences
import yokai.domain.storage.StoragePreferences
import yokai.domain.ui.UiPreferences
import yokai.domain.ui.settings.ReaderPreferences

fun preferenceModule(application: Application) = module {
    single<PreferenceStore> { AndroidPreferenceStore(application) }

    single { BasePreferences(get()) }

    single { ConnectionsPreferences(get()) }

    single { SourcePreferences(get()) }

    single { TrackPreferences(get()) }

    single { UiPreferences(get()) }

    single { ReaderPreferences(get()) }

    single { RecentsPreferences(get()) }

    single { DownloadPreferences(get()) }

    single {
        NetworkPreferences(
            get(),
            BuildConfig.FLAVOR == "dev" || BuildConfig.DEBUG,
        )
    }

    single { SecurityPreferences(get()) }

    single { BackupPreferences(get()) }

    single { LibraryPreferences(get()) }

    single {
        PreferencesHelper(
            context = application,
            preferenceStore = get(),
        )
    }

    single {
        StoragePreferences(
            folderProvider = get<AndroidStorageFolderProvider>(),
            preferenceStore = get(),
        )
    }
}
