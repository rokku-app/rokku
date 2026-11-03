package yokai.core

import android.database.sqlite.SQLiteConstraintException
import co.touchlab.kermit.DefaultFormatter
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Message
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import com.google.firebase.crashlytics.FirebaseCrashlytics
import eu.kanade.tachiyomi.data.backup.create.BackupCreateException
import eu.kanade.tachiyomi.data.download.InvalidDownloadLocationException
import eu.kanade.tachiyomi.data.download.NoPagesException
import eu.kanade.tachiyomi.data.track.myanimelist.MALTokenExpired
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.isAuthError
import eu.kanade.tachiyomi.network.isServerError
import eu.kanade.tachiyomi.source.SourceNotFoundException
import eu.kanade.tachiyomi.ui.reader.loader.MissingDownloadedPageException
import eu.kanade.tachiyomi.ui.reader.loader.SourceNotInstalledException
import eu.kanade.tachiyomi.ui.source.browse.NoResultsException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.jsoup.HttpStatusException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class CrashlyticsLogWriter : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean = severity >= Severity.Info

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        try {
            FirebaseCrashlytics.getInstance().log(DefaultFormatter.formatMessage(severity, Tag(tag), Message(message)))
            if (throwable != null && severity >= Severity.Error && !throwable.isIgnoredForCrashlytics()) {
                FirebaseCrashlytics.getInstance().recordException(throwable)
            }
        } catch (_: Exception) {
            // Probably crashlytics not yet initialized or disabled
        }
    }

    /**
     * Skips exceptions that are never actionable from app code: coroutine cancellation
     * (normal control flow), pure device/network connectivity failures (DNS, timeout,
     * TLS handshake, connection reset), a source's own server erroring out (5xx, whether
     * raised via our own HttpException or a source/extension's own HTTP client),
     * rejecting auth (401/403), answering 404 for a resource that isn't there (a
     * dead or misconfigured extension repo URL, or a legacy repo with no repo.json -
     * ExtensionApi and ExtensionRepoService both fall back cleanly), or rate-limiting a
     * request (429 - ExtensionApi.fetchStoreExtensions already falls back to the legacy
     * index), and the user's
     * downloads folder, a downloaded page file, or the backup destination
     * (BackupCreateException) becoming inaccessible (permission revoked, file/folder
     * moved/deleted, storage removed - the backup failure is still shown to the user as a
     * notification) - none of these reflect a Rokku bug, and recording them buries real
     * non-fatals in noise. Walks the whole cause chain since some call sites (e.g.
     * MangaCoverFetcher) wrap these in a generic IOException before it reaches here.
     *
     * Also skips two more transient, self-healing cases: an extension's dex not being
     * readable yet (empty DexPathList) when ExtensionManager's startup scan races an
     * in-progress install/update - ExtensionInstallReceiver reloads that package on its
     * own once the real PACKAGE_ADDED/PACKAGE_REPLACED broadcast lands - and a downloaded
     * page file disappearing between the post-download success check and CBZ archiving
     * (external storage/media scanner interference), which Downloader already surfaces
     * to the user as a failed download.
     *
     * Also skips a MyAnimeList tracker session expiring (MyAnimeListInterceptor already
     * surfaces a clear re-login message to the user instead) and a source extension's own
     * auth interceptor rejecting a request as unauthorized (a 401 the extension raises as
     * a plain IOException rather than through our HttpException, so it doesn't reach the
     * isAuthError check above) - neither reflects a Rokku bug.
     *
     * Also skips Hikka/MangaBaka trying to JSON-decode their saved OAuth token preference
     * on first use when the user never logged in (empty preference, decoding fails as EOF) -
     * Hikka.loadOAuth and MangaBaka.restoreToken already catch this and return null, so the
     * tracker just starts logged out. Also skips a JSON parse failing because the source
     * answered with an HTML page (Cloudflare interstitial / error page) instead of JSON.
     *
     * Also skips AniList's tracker refresh not finding the manga in the user's list anymore
     * (e.g. removed/unlinked directly on AniList's site) - not a Rokku bug.
     *
     * Also skips the OS refusing to promote a library update job to a foreground service
     * (android.app.ForegroundServiceStartNotAllowedException, API 31+, matched by class name
     * since it doesn't extend IllegalStateException) - tryToSetForeground() already treats
     * this as best-effort and lets the update proceed without the progress notification.
     *
     * Also skips a history upsert hitting a FOREIGN KEY constraint failure - the chapter the
     * reader/recents/backup was recording progress for was deleted from the database in the
     * meantime (chapter list refreshed, manga removed). HistoryRepositoryImpl already catches
     * this and skips the row; there's simply no chapter left to attach the history to.
     *
     * Also skips a source extension's own login/token validation rejecting a request before it
     * even reaches the network: missing login credentials the user never configured in the
     * extension's preferences ("Missing username or password") and a tracker/extension
     * signaling it needs an interactive webview relogin to refresh an expired token ("Open
     * webview to refresh token", same category as MALTokenExpired above) - neither reflects a
     * Rokku bug. Also skips a source extension's own encrypted preference (a saved cookie/token)
     * failing to decrypt before being handed to the JSON decoder - e.g. the Android Keystore key
     * that encrypted it was invalidated by a backup restore or reinstall - leaving the raw
     * ciphertext ("enc:v1:...") to fail JSON parsing; not a Rokku bug either.
     *
     * Also skips a source extension rejecting a chapter/page request for a reason outside the
     * app's control: content requiring a paid/premium account on the source's site ("Premium
     * chapter. Not available.", same category as "Chapter locked" above), a chapter whose
     * content is wrapped in a scheme the extension couldn't unwrap ("Chapter encryption
     * unavailable", "Reader signer bindings not found"), and a self-hosted bridge extension (e.g. Tachidesk) that isn't configured
     * yet ("Set Tachidesk server url in extension settings", same category as "Missing username
     * or password" above) - none of these reflect a Rokku bug. Also skips a source's own search
     * backend erroring out on a malformed query, whether as an HTTP 400 (HttpException) or as a
     * raised SQL error from the extension's bundled database missing an index ("Can't find
     * FULLTEXT index matching the column list").
     *
     * Also skips a `multisrc` theme shared by several Keiyoushi extensions failing to decode a
     * site's response into one of its DTOs (MissingFieldException with a serial name under
     * "eu.kanade.tachiyomi.multisrc.", or a single extension's own DTO under
     * "eu.kanade.tachiyomi.extension.") - the site's own API/markup changed; not a Rokku bug.
     * Also skips Hikka's OAuth token preference round-tripping through `saveOAuth(null)` (which
     * encodes the literal text "null") and then failing to decode as an HKOAuth object on the
     * next `loadOAuth()` - same already-handled "user never logged in" case as the EOF one above,
     * just a different literal.
     *
     * Also skips an extension's own source class no longer existing in its installed APK
     * (ClassNotFoundException for a class under "eu.kanade.tachiyomi.extension.", not just the
     * empty-DexPathList race above) - ExtensionLoader.loadExtension already catches this per
     * extension and marks it as a load error without crashing; the extension's packaging is
     * simply out of sync with its repo metadata.
     *
     * Also skips a batch of cover/reader/browse conditions that only reflect a source or the
     * network misbehaving, never a Rokku bug: MangaCoverFetcher raising a source's HTTP error
     * status ("HTTP 4xx/5xx …"), a missing/invalid cover URL in the source's metadata
     * ("Invalid image", "No cover specified"), an HTTP/2 stream reset ("stream was reset: …",
     * including covers cancelled mid-scroll), a redirect loop ("Too many follow-up requests"),
     * a source's own Cloudflare challenge not clearing ("Failed to bypass Cloudflare"), a
     * source returning an empty page list (NoPagesException) or a locked chapter
     * ("Chapter locked"), a browse query with no matches (NoResultsException), the related-
     * manga / deep-link path resolving to an uninstalled source (SourceNotFoundException), and
     * an online reader page's disk-cache entry being evicted between listing and reading it
     * (FileNotFoundException under chapter_disk_cache - the page just reloads), and a cover
     * write racing the OS over the covers cache directory (FileNotFoundException under
     * files/covers - the cover just fails to cache that one time).
     *
     * Also skips: any InterruptedIOException (an okhttp call timeout or cancellation - covers
     * the former SocketTimeoutException too), an okhttp connection dropped mid-request
     * (ConnectionShutdownException, "Canceled"), a local cover file that can't be opened
     * ("Can't open InputStream" - moved/permission revoked), a Cloudflare/WebView challenge
     * that never resolved ("Timed out waiting for WebView"), a local-library entry whose
     * folder is gone or holds a non-archive file ("… is not a valid directory",
     * "Unrecognized archive format"), and an extension signaling its own manga/chapter list
     * needs a refresh before continuing ("Refresh manga").
     *
     * Also skips three plain `java.lang.Exception`s raised by an extension's own code (only
     * the message identifies them): its site probe finding nothing to read ("Nothing read from
     * N site requests"), its page-list request failing with the site's HTTP status ("Chapter
     * pages failed: HTTP 5xx"), and its WebView client asking the user to solve a captcha
     * ("Open in WebView to solve the captcha…"). Also skips libarchive reporting a bad CRC
     * while reading a downloaded CBZ - the file on the user's storage is corrupted and the
     * reader already shows its page error.
     *
     * Also skips an extension's reader-page request rejected by the site ("Reader pages HTTP
     * 403: FORBIDDEN_EXCLUSIVE"), an extension reporting a locked chapter that must be unlocked
     * in the WebView ("Locked chapter, unlock in WebView.", same category as "Chapter locked"),
     * and a source answering HTTP 410 Gone (same category as 404).
     *
     * Also skips an extension's anti-bot prompts and per-page failures: "Open in WebView to
     * pass the site's verification", "Couldn't solve the site's captcha (HTTP error 500), open
     * in WebView to solve it" (same category as the captcha message above) and "Chapter page N
     * failed: HTTP 5xx" (same category as "Chapter pages failed: HTTP 5xx").
     *
     * Also skips more extension-raised messages the user has to resolve on their side: content
     * the account has no license for ("User does not have the license of requested content"),
     * credentials not filled in yet ("Username or password cannot be empty. Go to Extensions >
     * Details...", same category as "Missing username or password"), cookies that must be set
     * through the WebView ("Open in WebView to set necessary cookies."), and the system WebView's
     * render process being killed by the OS ("WebView render process was killed", raised from
     * the extension's WebViewClient.onRenderProcessGone).
     *
     * Also skips an extension's own HTTP client giving up on a 5xx answer from its site (a plain
     * `Exception("HTTP error 503")`, same category as "Chapter pages failed: HTTP 5xx"), an
     * extension asking the user to open the chapter in the WebView ("Could not load images. Open
     * in WebView and try again.", same category as "Locked chapter, unlock in WebView."), an
     * extension finding no manga catalog on its site ("Manga catalog not found"), and the Hikka
     * tracker being used while logged out ("Hikka: You are not authorized" - the tracking sheet
     * already shows that message to the user in its empty view).
     *
     * Also skips an extension's captcha handling giving up: the site's challenge failing inside
     * the WebView ("Captcha Failed! Challenge failed in this browser (code: 300010)") and a
     * challenge that needs the user's input while the app is in the background ("Captcha needs
     * interaction, but the app isn't in the foreground") - same category as the captcha
     * messages above, the user solves it in the WebView.
     *
     * Also skips a source answering HTTP 451 (blocked for legal reasons, same category as 404),
     * an extension's own HTTP interceptor failing with a localized hint
     * ("HTTP error 404. Проверьте сайт. Попробуйте авторизоваться через WebView…", same category
     * as the HTTP status messages above), the self-hosted Komga extension when its
     * server address was never filled in ("Set Address in Settings", same category as "Set
     * Tachidesk server url in extension settings"), and an extension asking the user to log in
     * through the WebView and subscribe to the site's service.
     *
     * Also skips a tracker session expiring (Hikka/MangaBaka interceptors log the user out and
     * the tracking sheet toasts the message, same category as MALTokenExpired), an extension
     * not finding a chapter's page list on its site ("Page list not found", same category as
     * NoPagesException), and libarchive reporting a damaged ZIP while reading a downloaded
     * CBZ ("Damaged Zip archive", same category as "ZIP bad CRC"), and BitmapRegionDecoder
     * rejecting a downloaded page's format while splitting a tall image (the page is kept unsplit).
     *
     * Also skips WorkManager refusing tryToSetForeground() because the worker already finished or
     * was stopped before the foreground promotion completed ("Calls to setForegroundAsync() must
     * complete before a ListenableWorker signals completion of work", same best-effort category as
     * ForegroundServiceStartNotAllowedException), and an extension's own manifest request failing
     * with the site's HTTP status ("Error: manifest 428", same category as "Chapter pages failed:
     * HTTP 5xx").
     */
    internal fun Throwable.isIgnoredForCrashlytics(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            when (current) {
                is CancellationException,
                is UnknownHostException,
                is InterruptedIOException, // also covers SocketTimeoutException (a call timeout or cancellation, never a bug)
                is ConnectException,
                is SocketException,
                is SSLException,
                is InvalidDownloadLocationException,
                is BackupCreateException,
                is MissingDownloadedPageException,
                is SourceNotInstalledException,
                is SourceNotFoundException,
                is MALTokenExpired,
                is NoResultsException,
                is NoPagesException,
                -> return true

                is HttpException -> if (
                    current.isAuthError || current.isServerError ||
                    current.code == 400 || current.code == 404 || current.code == 410 || current.code == 429 ||
                    current.code == 451
                ) {
                    return true
                }

                is HttpStatusException -> if (current.statusCode in 500..599 || current.statusCode == 404) return true

                is SQLiteConstraintException -> if (
                    current.message?.contains("FOREIGN KEY constraint failed") == true
                ) {
                    return true
                }

                is FileNotFoundException -> if (
                    current.message?.contains("chapter_disk_cache") == true ||
                    current.message?.contains("files/covers") == true
                ) {
                    return true
                }

                is IOException -> if (
                    current.message?.contains("SETTINGS preface") == true ||
                    current.message == "Unauthorized" ||
                    current.message == "Canceled" ||
                    current.message == "Chapter locked" ||
                    current.message == "Can't open InputStream" ||
                    current.message == "Failed to bypass Cloudflare" ||
                    current.message == "Image format not supported" ||
                    current.message == "Missing username or password" ||
                    current.message == "Open webview to refresh token" ||
                    current.message == "Premium chapter. Not available." ||
                    current.message == "Reader signer bindings not found" ||
                    current.message?.startsWith("stream was reset: ") == true ||
                    current.message?.startsWith("Too many follow-up requests") == true ||
                    current.message?.startsWith("unexpected end of stream on ") == true ||
                    current.message?.matches(HTTP_STATUS_MESSAGE) == true ||
                    current.message?.matches(EXTENSION_HTTP_HINT) == true ||
                    current.message?.matches(EXTENSION_MANIFEST_ERROR) == true
                ) {
                    return true
                }

                is IllegalStateException -> if (
                    current.message == "Invalid image" ||
                    current.message == "No cover specified" ||
                    current.message?.startsWith("Calls to setForegroundAsync() must complete before") == true
                ) {
                    return true
                }

                is SerializationException -> if (
                    current.message?.contains("had 'EOF' instead") == true ||
                    // A source returned an HTML page (Cloudflare / error page) where JSON was expected.
                    current.message?.contains("<!DOCTYPE") == true ||
                    current.message?.contains("<html") == true ||
                    // A source extension's own encrypted preference (cookie/token) failed to
                    // decrypt and the raw ciphertext was handed to the JSON decoder instead.
                    current.message?.contains("JSON input: enc:") == true ||
                    // Hikka's saveOAuth(null) encodes the literal text "null" as the saved
                    // token preference; decoding it back as HKOAuth fails the same way the
                    // EOF case above does.
                    current.message?.contains("JSON input: null") == true ||
                    // A multisrc theme (shared by several extensions) failing to decode a
                    // site's response into its own DTO - the site's API/markup changed.
                    current.message?.contains("eu.kanade.tachiyomi.multisrc.") == true ||
                    // Same for a single extension's own DTO.
                    current.message?.contains("serial name 'eu.kanade.tachiyomi.extension.") == true
                ) {
                    return true
                }

                is ClassNotFoundException -> if (
                    current.message?.contains("DexPathList[[]") == true ||
                    // The extension's installed APK no longer contains this source class -
                    // ExtensionLoader.loadExtension already catches this per extension.
                    current.message?.startsWith("eu.kanade.tachiyomi.extension.") == true
                ) {
                    return true
                }

                is IllegalArgumentException -> if (
                    current.message?.contains("is child of") == true &&
                    current.message?.contains("FileNotFoundException") == true
                ) {
                    return true
                }
            }

            if (
                current.message == "Refresh Chapter List" ||
                current.message == "Could not find manga" ||
                current.message == "Refresh manga" ||
                current.message == "Chapter encryption unavailable" ||
                current.message == "Set Tachidesk server url in extension settings" ||
                current.message == "Set Address in Settings" ||
                current.message == "Log in via WebView and subscribe to the website's service." ||
                current.message == "Hikka: You are not authorized" ||
                current.message == "Hikka: The token is expired" ||
                current.message == "MangaBaka: The token is expired" ||
                current.message == "Page list not found" ||
                current.message == "Could not load images. Open in WebView and try again." ||
                current.message == "Manga catalog not found" ||
                // The full message is "<prefix>: <raw SQL query> - Can't find FULLTEXT index ...".
                current.message?.endsWith("Can't find FULLTEXT index matching the column list") == true ||
                // Extensions phrase this differently (e.g. "Refresh the chapter list."); match loosely.
                (
                    current.message?.contains("refresh", ignoreCase = true) == true &&
                        current.message?.contains("chapter list", ignoreCase = true) == true
                    )
            ) {
                return true
            }
            // A source's Cloudflare/WebView challenge not resolving in time, or a local-library
            // folder that was renamed/deleted or has a non-archive file in it - not a Rokku bug.
            if (current.message?.startsWith("Timed out waiting for WebView") == true) return true
            if (current.message?.startsWith("Nothing read from ") == true) return true
            if (current.message?.startsWith("Chapter pages failed: HTTP ") == true) return true
            if (current.message?.startsWith("Reader pages HTTP ") == true) return true
            if (current.message?.startsWith("Locked chapter, unlock in WebView") == true) return true
            if (current.message?.startsWith("Open in WebView to solve the captcha") == true) return true
            if (current.message?.startsWith("Open in WebView to pass the site's verification") == true) return true
            if (current.message?.startsWith("Couldn't solve the site's captcha") == true) return true
            if (current.message?.startsWith("Captcha Failed! Challenge failed in this browser") == true) return true
            if (current.message == "Captcha needs interaction, but the app isn't in the foreground") return true
            if (current.message?.matches(EXTENSION_PAGE_HTTP_FAILURE) == true) return true
            if (current.message?.matches(EXTENSION_SERVER_ERROR) == true) return true
            if (current.message?.startsWith("User does not have the license of requested content") == true) return true
            if (current.message?.startsWith("Username or password cannot be empty") == true) return true
            if (current.message?.startsWith("Open in WebView to set necessary cookies") == true) return true
            if (current.message?.startsWith("WebView render process was killed") == true) return true
            if (
                current.javaClass.simpleName == "ArchiveException" &&
                (current.message?.startsWith("ZIP bad CRC") == true || current.message == "Damaged Zip archive")
            ) {
                return true
            }
            if (current.message?.endsWith("is not a valid directory") == true) return true
            if (current.message == "Unrecognized archive format") return true
            if (current.javaClass.simpleName == "ForegroundServiceStartNotAllowedException") return true
            // WorkManager's own foreground-promotion service call (startService, not
            // startForegroundService) getting the same OS-imposed background-start limit.
            if (current.javaClass.simpleName == "BackgroundServiceStartNotAllowedException") return true
            if (current.javaClass.simpleName == "ConnectionShutdownException") return true
            // The wrapping IOException's message is localized, so only the private cause class is stable.
            if (current.javaClass.simpleName == "CloudflareBypassException") return true

            current = current.cause?.takeIf { it !== current }
        }
        return false
    }

    private companion object {
        /** Matches the "HTTP 404 …" message MangaCoverFetcher raises as a plain IOException. */
        val HTTP_STATUS_MESSAGE = Regex("""HTTP \d{3}\b.*""")

        /** Matches "Chapter page 2 failed: HTTP 521", raised by an extension for a single page request. */
        val EXTENSION_PAGE_HTTP_FAILURE = Regex("""Chapter page \d+ failed: HTTP \d{3}\b.*""")
        val EXTENSION_SERVER_ERROR = Regex("""HTTP error 5\d{2}""")

        /** Matches "Error: manifest 428", an extension's IOException for a failed manifest request. */
        val EXTENSION_MANIFEST_ERROR = Regex("""Error: manifest \d{3}""")

        /** Matches "HTTP error 404. Проверьте сайт. …", an extension's interceptor message for a failed request. */
        val EXTENSION_HTTP_HINT = Regex("""HTTP error \d{3}\. Проверьте сайт.*""")
    }
}
