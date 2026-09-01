package eu.kanade.tachiyomi.data.download

import android.content.Context
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Thrown when a source returns an empty page list for a chapter (locked, removed, or
 * broken chapter on the source's side). Reflects a source/site problem rather than a
 * Rokku bug, so [yokai.core.CrashlyticsLogWriter] skips reporting it.
 */
class NoPagesException(context: Context) : Exception(context.getString(MR.strings.no_pages_found))
