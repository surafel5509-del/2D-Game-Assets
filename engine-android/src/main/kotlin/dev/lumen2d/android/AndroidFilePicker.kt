/**
 * Lumen2D — Android file picker (Storage Access Framework).
 *
 * Android cannot block the calling thread while a document picker is open, so this implementation
 * is callback based: [requestFile] / [requestFolder] launch the SAF UI through [launcher] (the
 * studio wires it to `Activity.startActivityForResult`) and [deliver] hands the result back when
 * `onActivityResult` fires. The synchronous `PlatformFilePicker` methods always return null on
 * Android — they exist for the desktop and for tools — which is why the studio drives imports
 * through the callbacks.
 *
 * Nothing here needs a storage permission: SAF grants per-URI access to whatever the user picked,
 * including folders on a real SD card.
 */
package dev.lumen2d.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.lumen2d.core.platform.PickedLocation
import dev.lumen2d.core.platform.PlatformFilePicker
import dev.lumen2d.core.util.Log
import java.io.File

class AndroidFilePicker(
    private val context: Context,
    /** Set by the host activity: launches [intent] for [requestCode]. */
    var launcher: ((Intent, Int) -> Unit)? = null,
) : PlatformFilePicker {

    private var pending: ((PickedLocation?) -> Unit)? = null
    private var pendingMime: List<String> = emptyList()

    /** True once a host activity is attached; the editor hides the import button otherwise. */
    override fun canPick(): Boolean = launcher != null

    /** Attaches the host activity's launcher (call from `Activity.onCreate`). */
    fun attach(activity: Activity, requestCode: Int = REQUEST_FILE) {
        launcher = { intent, code -> activity.startActivityForResult(intent, if (code == 0) requestCode else code) }
        Log.d("Android", "File picker attached to ${activity.javaClass.simpleName}")
    }

    /** Opens the SAF document picker for a single file. */
    fun requestFile(mimeTypes: List<String>, onPicked: (PickedLocation?) -> Unit): Boolean {
        val launch = launcher ?: return false
        pending = onPicked
        pendingMime = mimeTypes
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.firstOrNull() ?: "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(intent, REQUEST_FILE)
        return true
    }

    /** Opens the SAF folder picker (used to import a whole asset pack folder). */
    fun requestFolder(onPicked: (PickedLocation?) -> Unit): Boolean {
        val launch = launcher ?: return false
        pending = onPicked
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        launch(intent, REQUEST_FOLDER)
        return true
    }

    /** Called from the host activity's `onActivityResult`. Returns the delivered location. */
    fun deliver(data: Intent?, ok: Boolean): PickedLocation? {
        val callback = pending
        pending = null
        if (!ok || data?.data == null) {
            callback?.invoke(null)
            return null
        }
        val uri = data.data!!
        // Persist the grant so an imported pack keeps working after a reboot.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val location = PickedLocation(
            displayName = displayName(uri) ?: uri.lastPathSegment.orEmpty(),
            uri = uri.toString(),
            writable = (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0,
        )
        callback?.invoke(location)
        return location
    }

    /** Streams a picked document into app storage so plain `java.io` code can read it. */
    fun copyToCache(uri: String, fileName: String): File? = runCatching {
        val source = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
        val target = File(context.cacheDir, fileName.ifEmpty { "import.bin" })
        source.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target
    }.getOrNull()

    /** Reads a document's size without copying it, for the import confirmation dialog. */
    fun sizeOf(uri: String): Long = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri)).use { stream ->
            var total = 0L
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream?.read(buffer) ?: -1
                if (read <= 0) break
                total += read
            }
            total
        }
    }.getOrDefault(0L)

    private fun displayName(uri: Uri): String? = runCatching {
        val cursor = context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
        cursor?.use { if (it.moveToFirst()) return it.getString(0) }
        null
    }.getOrNull()

    // Synchronous API: Android apps only get results on the next event loop turn.
    override fun pickFolder(): PickedLocation? = null
    override fun pickFile(mimeTypes: List<String>): PickedLocation? = null

    companion object {
        const val REQUEST_FILE = 0x4C01
        const val REQUEST_FOLDER = 0x4C02
        /** Mime types used by the studio's import buttons. */
        val ARCHIVE_MIME = listOf("application/zip", "application/octet-stream", "*/*")
    }
}
