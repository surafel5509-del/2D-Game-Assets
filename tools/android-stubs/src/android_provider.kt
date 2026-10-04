// Android framework stubs — android.provider (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.provider

import android.content.ContentResolver

object Settings {
    object System {
        const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
        fun getString(resolver: ContentResolver, name: String): String? = null
        fun getInt(resolver: ContentResolver, name: String, def: Int): Int = def
        fun putString(resolver: ContentResolver, name: String, value: String?): Boolean = true
    }
    object Global {
        fun getString(resolver: ContentResolver, name: String): String? = null
        fun getInt(resolver: ContentResolver, name: String, def: Int): Int = def
    }
    object Secure {
        fun getString(resolver: ContentResolver, name: String): String? = null
        fun getInt(resolver: ContentResolver, name: String, def: Int): Int = def
    }
}
