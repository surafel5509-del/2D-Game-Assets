// Android framework stubs — android.content (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.content

import android.content.res.AssetManager
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.io.OutputStream

open class Context {
    open val packageName: String get() = "stub"
    open val filesDir: File get() = File("/tmp")
    open val cacheDir: File get() = File("/tmp")
    open val noBackupFilesDir: File get() = File("/tmp")
    open val assets: AssetManager get() = AssetManager()
    open val resources: Resources get() = Resources()
    open val contentResolver: ContentResolver get() = ContentResolver()
    open val mainLooper: Looper get() = Looper.getMainLooper()
    open val packageManager: android.content.pm.PackageManager get() = android.content.pm.PackageManager()
    open val applicationContext: Context get() = this
    open var theme: android.content.res.Resources.Theme? = null
    open fun getSystemService(name: String): Any? = null
    open fun getString(resId: Int): String = ""
    open fun getString(resId: Int, vararg formatArgs: Any?): String = ""
    open fun getColor(resId: Int): Int = 0
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()
    open fun openFileInput(name: String): java.io.FileInputStream? = null
    open fun openFileOutput(name: String, mode: Int): java.io.FileOutputStream? = null
    open fun startActivity(intent: Intent) {}
    open fun getExternalFilesDir(type: String?): File? = File("/tmp")
    companion object {
        const val VIBRATOR_SERVICE = "vibrator"
        const val AUDIO_SERVICE = "audio"
        const val CLIPBOARD_SERVICE = "clipboard"
        const val WINDOW_SERVICE = "window"
        const val ACTIVITY_SERVICE = "activity"
        const val MODE_PRIVATE = 0
    }
}

class Intent {
    var action: String? = null
    var type: String? = null
    var data: Uri? = null
    var flags: Int = 0
    var component: ComponentName? = null
    constructor()
    constructor(action: String)
    constructor(action: String, uri: Uri?)
    constructor(context: Context, cls: Class<*>)
    constructor(o: Intent?)
    fun addFlags(flags: Int): Intent = this
    fun addCategory(category: String): Intent = this
    fun removeExtra(name: String): Unit {}
    fun setFlags(flags: Int): Intent = this
    fun setClass(context: Context, cls: Class<*>): Intent = this
    fun setData(uri: Uri?): Intent = this
    fun setDataAndType(uri: Uri?, type: String): Intent = this
    fun putExtra(name: String, value: String?): Intent = this
    fun putExtra(name: String, value: Int): Intent = this
    fun putExtra(name: String, value: Long): Intent = this
    fun putExtra(name: String, value: Boolean): Intent = this
    fun putExtra(name: String, value: Float): Intent = this
    fun putStringArrayListExtra(name: String, value: ArrayList<String>?): Intent = this
    fun putExtra(name: String, value: Array<String>?): Intent = this
    fun putExtra(name: String, value: android.os.Parcelable?): Intent = this
    fun getStringExtra(name: String): String? = null
    fun getIntExtra(name: String, defaultValue: Int): Int = defaultValue
    fun getLongExtra(name: String, defaultValue: Long): Long = defaultValue
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean = defaultValue
    fun getStringArrayListExtra(name: String): ArrayList<String>? = TODO()
    fun hasExtra(name: String): Boolean = false
    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SENDTO = "android.intent.action.SENDTO"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE"
        const val ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT"
        const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
        fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}

class ComponentName(val packageName: String, val className: String)

interface DialogInterface {
    fun dismiss() {}
    fun cancel() {}
    fun interface OnClickListener { fun onClick(dialog: DialogInterface?, which: Int) }
    fun interface OnDismissListener { fun onDismiss(dialog: DialogInterface?) }
    interface OnCancelListener { fun onCancel(dialog: DialogInterface?) }
    companion object {
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_NEUTRAL = -3
    }
}

class ClipData {
    companion object { fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData() }
    val itemCount: Int get() = 0
    fun getItemAt(index: Int): Item = Item()
    class Item { fun coerceToText(context: Context): CharSequence = "" }
}

class ClipboardManager {
    // Mirrors the framework: the getter is nullable, the setter takes a non-null ClipData, so
    // Kotlin cannot synthesise a `var` — code must call setPrimaryClip() explicitly.
    val primaryClip: ClipData? = null
    fun setPrimaryClip(clip: ClipData) {}
    fun clearPrimaryClip() {}
    fun hasPrimaryClip(): Boolean = false
}

class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = TODO()
    fun openOutputStream(uri: Uri): OutputStream? = TODO()
    fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): android.database.Cursor? = TODO()
    fun takePersistableUriPermission(uri: Uri, flags: Int) {}
}

open class SharedPreferences {
    open fun getString(key: String, def: String?): String? = def
    open fun getInt(key: String, def: Int): Int = def
    open fun getBoolean(key: String, def: Boolean): Boolean = def
    open fun getLong(key: String, def: Long): Long = def
    open fun edit(): Editor = Editor()
    open fun contains(key: String): Boolean = false
    open fun getAll(): MutableMap<String, *> = HashMap<String, Any?>()
    class Editor {
        fun putString(key: String, value: String?): Editor = this
        fun putInt(key: String, value: Int): Editor = this
        fun putBoolean(key: String, value: Boolean): Editor = this
        fun putLong(key: String, value: Long): Editor = this
        fun remove(key: String): Editor = this
        fun clear(): Editor = this
        fun apply() {}
        fun commit(): Boolean = true
    }
}
