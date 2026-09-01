package eu.kanade.tachiyomi.data.backup.create

/**
 * Thrown when a backup can't be written because the destination the user picked is no
 * longer usable (SAF permission revoked, folder moved/deleted, storage removed). Reflects
 * the user's environment rather than a Rokku bug, so [yokai.core.CrashlyticsLogWriter]
 * skips reporting it; the failure is still surfaced to the user as a notification.
 */
class BackupCreateException(message: String, cause: Throwable? = null) : Exception(message, cause)
