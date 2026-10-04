// Android framework stubs — android.app (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.app

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.Window

open class Activity : Context() {
    open val intent: Intent get() = Intent()
    open val window: Window get() = Window()
    open val isFinishing: Boolean get() = false
    open val isDestroyed: Boolean get() = false
    open val taskId: Int get() = 0
    open val display: android.view.Display? get() = null
    open val isTaskRoot: Boolean get() = true
    var requestedOrientation: Int = -1
    var volumeControlStream: Int = -1
    override val applicationContext: Context get() = this
    override val resources: Resources get() = Resources()
    override val filesDir: java.io.File get() = java.io.File("/tmp")
    override val cacheDir: java.io.File get() = java.io.File("/tmp")
    override val contentResolver: android.content.ContentResolver get() = android.content.ContentResolver()
    override val assets: android.content.res.AssetManager get() = android.content.res.AssetManager()
    override val mainLooper: android.os.Looper get() = android.os.Looper.getMainLooper()
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onRestart() {}
    open fun onDestroy() {}
    open fun onSaveInstanceState(outState: Bundle) {}
    open fun onRestoreInstanceState(savedInstanceState: Bundle) {}
    open fun onBackPressed() {}
    open fun onNewIntent(intent: Intent?) {}
    open fun onWindowFocusChanged(hasFocus: Boolean) {}
    open fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {}
    open fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {}
    open fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
    open fun setContentView(view: View?) {}
    open fun setContentView(layoutResID: Int) {}
    fun startActivityForResult(intent: Intent, requestCode: Int) {}
    fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {}
    fun <T : View> findViewById(id: Int): T? = null
    fun setResult(resultCode: Int) {}
    fun setResult(resultCode: Int, data: Intent) {}
    fun finish() {}
    fun finishAndRemoveTask() {}
    fun requestWindowFeature(featureId: Int): Boolean = true
    fun setTitle(titleResId: Int) {}
    fun runOnUiThread(action: Runnable) {}
    fun recreate() {}
    fun overridePendingTransition(enterAnim: Int, exitAnim: Int) {}
    companion object {
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
        const val RESULT_FIRST_USER = 1
    }
}

open class Application : Context() {
    override val applicationContext: Context get() = this
    open fun onCreate() {}
    open fun onTerminate() {}
    open fun onLowMemory() {}
    open fun onTrimMemory(level: Int) {}
}

open class Dialog(context: Context) : DialogInterface {
    open val window: Window get() = Window()
    open val isShowing: Boolean get() = false
    open var title: CharSequence? = null
    open var message: CharSequence? = null
    open fun setContentView(view: View) {}
    open fun show() {}
    open fun setCancelable(flag: Boolean) {}
    open fun setCanceledOnTouchOutside(cancel: Boolean) {}
    open fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {}
    override fun dismiss() {}
    override fun cancel() {}
}

open class AlertDialog(context: Context) : Dialog(context) {
    open fun setButton(whichButton: Int, text: CharSequence?, listener: DialogInterface.OnClickListener?) {}
    open fun setItems(items: Array<out CharSequence>, listener: DialogInterface.OnClickListener?) {}

    class Builder(private val context: Context) {
        private var titleValue: CharSequence? = null
        private var messageValue: CharSequence? = null
        fun setTitle(title: CharSequence?): Builder = this
        fun setTitle(titleId: Int): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setMessage(messageId: Int): Builder = this
        fun setView(view: View?): Builder = this
        fun setIcon(iconId: Int): Builder = this
        fun setCancelable(cancelable: Boolean): Builder = this
        fun setItems(items: Array<out CharSequence>, listener: DialogInterface.OnClickListener?): Builder = this
        fun setSingleChoiceItems(items: Array<out CharSequence>, checkedItem: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): Builder = this
        fun create(): AlertDialog = AlertDialog(context)
        fun show(): AlertDialog = AlertDialog(context)
    }

    companion object {
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_NEUTRAL = -3
    }
}

open class ProgressDialog(context: Context) : Dialog(context) {
    var isIndeterminate: Boolean = false
    var max: Int = 100
    var progress: Int = 0
    fun setProgressStyle(style: Int) {}
    companion object { const val STYLE_SPINNER = 0; const val STYLE_HORIZONTAL = 1 }
}
