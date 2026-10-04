/**
 * Sharing an exported project.
 *
 * On Android the app-private export folder is invisible to the user, so an exported `.lumenzip` is
 * useless unless it can leave the app. `FileProvider` (declared in the manifest) hands the file to
 * any installed app as a `content://` URI — Drive, Gmail, Telegram, a file manager, another
 * device over Bluetooth — which is how projects travel from phone to desktop and back.
 */
package dev.lumen2d.studio

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ProjectSharing {

    /** `.lumenzip` is a zip; announce it as one so archivers and cloud apps accept it. */
    const val ARCHIVE_MIME = "application/zip"

    /** The authority declared in AndroidManifest.xml, derived from the application id. */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * Opens the system share sheet for [file]. Returns false when the file is missing or no app
     * claims the type, so callers can tell the user instead of failing silently.
     */
    fun share(context: Context, file: File, title: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = ARCHIVE_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, "Lumen2D project archive: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, title))
            true
        }.getOrDefault(false)
    }
}
