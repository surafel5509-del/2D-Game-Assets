// Android library stubs — androidx.core (type-check only, never shipped).
//
// The app's only AndroidX use is FileProvider, which turns the app-private export folder into
// content:// URIs so an exported .lumenzip can be shared to Drive, Gmail, Telegram, … .
@file:Suppress("unused")

package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File

open class FileProvider {
    companion object {
        @JvmStatic
        fun getUriForFile(context: Context, authority: String, file: File): Uri =
            Uri.parse("content://$authority/${file.name}")
    }
}
