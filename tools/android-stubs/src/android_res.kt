// Android framework stubs — android.content.res (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.content.res

import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import java.io.InputStream

open class AssetManager {
    open fun open(fileName: String): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    open fun open(fileName: String, accessMode: Int): InputStream = open(fileName)
    // AssetManager.list() returns null when the directory does not exist (real SDK signature).
    open fun list(path: String): Array<String>? = null
    open fun list(path: String, options: Int): Array<String>? = null
    open fun close() {}
    companion object { const val ACCESS_STREAMING = 1; const val ACCESS_BUFFER = 3 }
}

open class Resources {
    open val configuration: Configuration get() = Configuration()
    open val assets: AssetManager get() = AssetManager()
    open val displayMetrics: DisplayMetrics get() = DisplayMetrics()
    open fun getString(id: Int): String = ""
    open fun getString(id: Int, vararg formatArgs: Any?): String = ""
    open fun getStringArray(id: Int): Array<String> = emptyArray()
    open fun getText(id: Int): CharSequence = ""
    open fun getColor(id: Int): Int = 0
    open fun getDimension(id: Int): Float = 0f
    open fun getInteger(id: Int): Int = 0
    open fun getBoolean(id: Int): Boolean = false
    open fun getDrawable(id: Int): Drawable? = TODO()
    open fun getQuantityString(id: Int, quantity: Int): String = ""
    class Theme
}

open class Configuration {
    open var orientation: Int = ORIENTATION_LANDSCAPE
    open var screenWidthDp: Int = 0
    open var screenHeightDp: Int = 0
    open var densityDpi: Int = 160
    open var fontScale: Float = 1f
    open var uiMode: Int = 0
    open var screenLayout: Int = 0
    companion object {
        const val ORIENTATION_LANDSCAPE = 2
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_UNDEFINED = 0
        const val UI_MODE_NIGHT_YES = 0x20
        const val SCREENLAYOUT_LONG_YES = 0x02
    }
}

class ColorStateList {
    companion object { fun valueOf(color: Int): ColorStateList = ColorStateList() }
}
