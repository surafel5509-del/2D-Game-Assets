// Android framework stubs — android.net (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.net

import java.io.File

class Uri : android.os.Parcelable {
    fun toString(force: Boolean = true): String = "stub://"
    val lastPathSegment: String? get() = null
    val path: String? get() = null
    companion object {
        fun parse(uriString: String): Uri = Uri()
        fun fromFile(file: File): Uri = Uri()
        fun withAppendedPath(base: Uri, pathSegment: String): Uri = Uri()
    }
}
