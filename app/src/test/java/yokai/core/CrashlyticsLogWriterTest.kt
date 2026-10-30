package yokai.core

import eu.kanade.tachiyomi.data.backup.create.BackupCreateException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.SourceNotFoundException
import eu.kanade.tachiyomi.ui.source.browse.NoResultsException
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException

class CrashlyticsLogWriterTest {

    private val writer = CrashlyticsLogWriter()

    private fun ignored(t: Throwable) = with(writer) { t.isIgnoredForCrashlytics() }

    @Test
    fun `cover fetcher HTTP status wrapped as a plain IOException is ignored`() {
        ignored(IOException("HTTP 404 Not Found (url=https://example.org/cover.jpg)")) shouldBe true
        ignored(IOException("HTTP 503  (url=https://example.org/cover.jpg)")) shouldBe true
    }

    @Test
    fun `transient network conditions surfaced as IOException messages are ignored`() {
        ignored(IOException("stream was reset: PROTOCOL_ERROR")) shouldBe true
        ignored(IOException("stream was reset: CANCEL")) shouldBe true
        ignored(IOException("unexpected end of stream on https://example.org/...")) shouldBe true
        ignored(ProtocolException("Too many follow-up requests: 21")) shouldBe true
        ignored(IOException("Failed to bypass Cloudflare")) shouldBe true
        ignored(IOException("Chapter locked")) shouldBe true
    }

    @Test
    fun `missing or invalid cover metadata is ignored`() {
        ignored(IllegalStateException("Invalid image")) shouldBe true
        ignored(IllegalStateException("No cover specified")) shouldBe true
    }

    @Test
    fun `an evicted online-reader disk cache entry is ignored`() {
        val path = "/data/user/0/app.rokku/cache/chapter_disk_cache/9e90b9fb.0"
        ignored(FileNotFoundException("$path: open failed: ENOENT (No such file or directory)")) shouldBe true
    }

    @Test
    fun `expected source and browse outcomes are ignored by type`() {
        ignored(NoResultsException()) shouldBe true
        ignored(SourceNotFoundException("Source not installed", 123L)) shouldBe true
        ignored(BackupCreateException("Backup location is no longer accessible")) shouldBe true
    }

    @Test
    fun `HttpException is ignored only for auth, server, and not-found codes`() {
        ignored(HttpException(404)) shouldBe true
        ignored(HttpException(403)) shouldBe true
        ignored(HttpException(500)) shouldBe true
        ignored(HttpException(418)) shouldBe false
    }

    @Test
    fun `call timeouts and cancellations are ignored`() {
        ignored(SocketTimeoutException("timeout")) shouldBe true
        ignored(InterruptedIOException("timeout")) shouldBe true
        ignored(IOException("Canceled")) shouldBe true
    }

    @Test
    fun `source, cover file and local library conditions are ignored`() {
        ignored(IOException("Can't open InputStream")) shouldBe true
        ignored(Exception("Timed out waiting for WebView after 30s")) shouldBe true
        ignored(Exception("The Amazing Spider-Man (1962 - 1995) is not a valid directory")) shouldBe true
        ignored(Exception("Unrecognized archive format")) shouldBe true
    }

    @Test
    fun `a source returning HTML where JSON was expected is ignored`() {
        ignored(
            SerializationException(
                "Unexpected JSON token at offset 11: Expected EOF after parsing, but had h instead\n" +
                    "JSON input: <!DOCTYPE html><html lang=\"en\"><head>....",
            ),
        ) shouldBe true
    }

    @Test
    fun `a source extension's own login and token validation is ignored`() {
        ignored(IOException("Missing username or password")) shouldBe true
        ignored(IOException("Open webview to refresh token")) shouldBe true
    }

    @Test
    fun `an extension's encrypted preference failing to decrypt before JSON parsing is ignored`() {
        ignored(
            SerializationException(
                "Unexpected JSON token at offset 0: Expected start of the object '{', but had 'e' instead at path: \$\n" +
                    "JSON input: enc:v1:abafd3384362e833:jMmsq9.....",
            ),
        ) shouldBe true
    }

    @Test
    fun `a source rejecting a chapter or page request for reasons outside app control is ignored`() {
        ignored(IOException("Premium chapter. Not available.")) shouldBe true
        ignored(Exception("Chapter encryption unavailable")) shouldBe true
        ignored(RuntimeException("Set Tachidesk server url in extension settings")) shouldBe true
    }

    @Test
    fun `HttpException is ignored for malformed queries too`() {
        ignored(HttpException(400)) shouldBe true
    }

    @Test
    fun `an extension's bundled database missing an index is ignored`() {
        ignored(
            Exception(
                "m0: select `s`.`mangaID` ... from `mh01` as `s` - Can't find FULLTEXT index matching the column list",
            ),
        ) shouldBe true
    }

    @Test
    fun `a multisrc theme DTO missing fields is ignored`() {
        ignored(
            SerializationException(
                "Fields [value, data] are required for type with serial name " +
                    "'eu.kanade.tachiyomi.multisrc.mmrcms.SuggestionDto', but they were missing at path: \$[0]",
            ),
        ) shouldBe true
    }

    @Test
    fun `an extension failing to find its reader signer bindings is ignored`() {
        ignored(IOException("Reader signer bindings not found")) shouldBe true
    }

    @Test
    fun `an extension's own DTO missing a field is ignored`() {
        ignored(
            SerializationException(
                "Field 'baseUrl' is required for type with serial name " +
                    "'eu.kanade.tachiyomi.extension.en.comix.ChapterResponse.Pages', but it was missing at path: \$.result.pages",
            ),
        ) shouldBe true
    }

    @Test
    fun `Hikka's OAuth preference saved as the literal text null is ignored`() {
        ignored(
            SerializationException(
                "Unexpected JSON token at offset 0: Expected start of the object '{', but had 'n' instead at path: \$\n" +
                    "JSON input: null",
            ),
        ) shouldBe true
    }

    @Test
    fun `an extension's own source class missing from its installed APK is ignored`() {
        ignored(ClassNotFoundException("eu.kanade.tachiyomi.extension.ar.mangapro.ProComic")) shouldBe true
    }

    @Test
    fun `stale chapter metadata is ignored regardless of exact wording`() {
        ignored(IllegalStateException("Refresh Chapter List")) shouldBe true
        ignored(IllegalStateException("Refresh the chapter list.")) shouldBe true
        ignored(IllegalStateException("Could not find manga")) shouldBe true
        ignored(IllegalStateException("Refresh manga")) shouldBe true
    }

    @Test
    fun `extension-raised plain exceptions and corrupted CBZ reads are ignored`() {
        ignored(Exception("Nothing read from 2 site requests")) shouldBe true
        ignored(Exception("Chapter pages failed: HTTP 503")) shouldBe true
        ignored(
            Exception("Open in WebView to solve the captcha, or turn on \"Solve captchas\" in extension settings"),
        ) shouldBe true
        ignored(IllegalStateException("Reader pages HTTP 403: FORBIDDEN_EXCLUSIVE")) shouldBe true
        ignored(IllegalStateException("Locked chapter, unlock in WebView.")) shouldBe true
        ignored(HttpException(410)) shouldBe true
        ignored(IOException("Open in WebView to pass the site's verification")) shouldBe true
        ignored(Exception("Couldn't solve the site's captcha (HTTP error 500), open in WebView to solve it")) shouldBe true
        ignored(Exception("Chapter page 2 failed: HTTP 521")) shouldBe true
        ignored(Exception("Chapter page 2 failed: timeout")) shouldBe false
        ignored(IOException("User does not have the license of requested content")) shouldBe true
        ignored(
            IOException("Username or password cannot be empty.\nGo to Extensions > Details to input your credentials."),
        ) shouldBe true
        ignored(IOException("Open in WebView to set necessary cookies.")) shouldBe true
        ignored(Exception("WebView render process was killed")) shouldBe true
        ignored(ArchiveException("ZIP bad CRC: 0x7b114351 should be 0x3f25e0b2")) shouldBe true
        ignored(ArchiveException("Truncated ZIP file data")) shouldBe false
        ignored(ArchiveException("Damaged Zip archive")) shouldBe true
    }

    @Test
    fun `expired tracker sessions and a missing extension page list are ignored`() {
        ignored(IOException("java.lang.Exception: MangaBaka: The token is expired", Exception("MangaBaka: The token is expired"))) shouldBe true
        ignored(Exception("Hikka: The token is expired")) shouldBe true
        ignored(Exception("Page list not found")) shouldBe true
        ignored(Exception("Page list not found in chapter 12")) shouldBe false
        ignored(Exception("MangaBaka: Not authenticated")) shouldBe false
    }

    @Test
    fun `extension server errors, WebView prompts and logged-out Hikka are ignored`() {
        ignored(Exception("HTTP error 503")) shouldBe true
        ignored(Exception("HTTP error 521")) shouldBe true
        ignored(Exception("HTTP error 418")) shouldBe false
        ignored(Exception("HTTP error 5000")) shouldBe false
        ignored(IllegalStateException("Could not load images. Open in WebView and try again.")) shouldBe true
        ignored(IllegalStateException("Could not load images")) shouldBe false
        ignored(IOException("Manga catalog not found")) shouldBe true
        ignored(IOException("Manga catalog not found in cache dir")) shouldBe false
        ignored(IOException("java.lang.Exception: Hikka: You are not authorized", Exception("Hikka: You are not authorized"))) shouldBe true
        ignored(Exception("Hikka: Auth token info failed")) shouldBe false
    }

    @Test
    fun `extension captcha failures are ignored`() {
        ignored(Exception("Captcha Failed! Challenge failed in this browser (code: 300010)")) shouldBe true
        ignored(IllegalStateException("Captcha needs interaction, but the app isn't in the foreground")) shouldBe true
        ignored(IllegalStateException("Captcha needs interaction")) shouldBe false
        ignored(Exception("Captcha Failed! Unexpected response")) shouldBe false
    }

    @Test
    fun `legal blocks, localized extension HTTP hints and unconfigured extensions are ignored`() {
        ignored(HttpException(451)) shouldBe true
        ignored(HttpException(418)) shouldBe false
        ignored(
            IOException("HTTP error 404. Проверьте сайт. Попробуйте авторизоваться через WebView и обновите список."),
        ) shouldBe true
        ignored(IOException("HTTP error 404. Something else")) shouldBe false
        ignored(Exception("Set Address in Settings")) shouldBe true
        ignored(Exception("Set Address in Settings, then restart")) shouldBe false
        ignored(Exception("Log in via WebView and subscribe to the website's service.")) shouldBe true
        ignored(Exception("Log in via WebView")) shouldBe false
    }

    @Test
    fun `an image format the platform cannot region-decode is ignored`() {
        ignored(IOException("Image format not supported")) shouldBe true
        ignored(IOException("Image format not supported by the codec")) shouldBe false
    }

    @Test
    fun `a localized Cloudflare bypass failure is ignored through its cause`() {
        ignored(IOException("Fallo al evitar Cloudflare", CloudflareBypassException())) shouldBe true
        ignored(IOException("Fallo al evitar Cloudflare")) shouldBe false
    }

    // Same simple name as libarchive's exception, which the filter matches by name.
    private class ArchiveException(message: String) : Exception(message)

    // Same simple name as the private exception thrown by CloudflareInterceptor.
    private class CloudflareBypassException : Exception()

    @Test
    fun `the whole cause chain is walked`() {
        val wrapped = IOException("failed to load cover", IOException("stream was reset: INTERNAL_ERROR"))
        ignored(wrapped) shouldBe true
    }

    @Test
    fun `real bugs are still reported`() {
        ignored(IOException("write failed: ENOSPC (No space left on device)")) shouldBe false
        ignored(IllegalStateException("ViewModel was cleared")) shouldBe false
        ignored(NullPointerException("Attempt to read from null array")) shouldBe false
        ignored(IndexOutOfBoundsException("Index -1 out of bounds for length 17")) shouldBe false
    }
}
