/**
 * Lumen2D — Android platform.
 *
 * Implements the engine [Platform] contract on top of the Android framework:
 *
 *  * `user://` and settings live in the app's private storage (`filesDir`),
 *  * `lib://` / `bundled://` read the asset packs that ship inside the APK (`assets/packs`),
 *  * audio is streamed through [AndroidAudioOutput] (`AudioTrack`),
 *  * device services cover vibration, clipboard, sharing, toasts and screen metrics,
 *  * the file picker is the Storage Access Framework, so importing packs never needs a permission.
 *
 * Nothing here needs a third-party dependency: everything is plain framework API, which keeps the
 * engine shippable on minSdk 24 without an AndroidX requirement of its own.
 */
package dev.lumen2d.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.platform.AudioOutput
import dev.lumen2d.core.platform.DeviceServices
import dev.lumen2d.core.platform.FileFileSystem
import dev.lumen2d.core.platform.JsonSettingsStore
import dev.lumen2d.core.platform.Platform
import dev.lumen2d.core.platform.PlatformFilePicker
import dev.lumen2d.core.platform.PrefixedFileSystem
import dev.lumen2d.core.platform.VirtualFileSystem
import dev.lumen2d.core.util.Log
import java.io.File

/**
 * @param context any context; the application context is used so long-lived game objects never
 *   leak an Activity.
 * @param contentRoot folder inside the APK assets that holds engine content. It is mounted as
 *   `lib://`, so with the default (the asset root) the paths match every other platform:
 *   `lib://packs/base/sprites/player.png` and the provenance index at `lib://sources/...`.
 * @param samplesRoot folder inside the APK assets holding the sample games (`samples/<game>`).
 */
open class AndroidPlatform(
    context: Context,
    private val contentRoot: String = DEFAULT_CONTENT_ROOT,
    private val samplesRoot: String = DEFAULT_SAMPLES_ROOT,
) : Platform {

    private val appContext: Context = context.applicationContext

    override val name: String = "android"
    override val isAndroid: Boolean = true

    /** Writable root for everything the engine persists (projects, saves, exported packs). */
    val dataDirectory: File = File(appContext.filesDir, "lumen")

    private val settingsVfs = FileFileSystem(dataDirectory)
    private val store = JsonSettingsStore(settingsVfs, "settings.json")

    override val settingsStore: MutableMap<String, String> get() = store

    override val userFileSystem: VirtualFileSystem = PrefixedFileSystem(FileFileSystem(dataDirectory), "user")

    /** Folder inside the APK that holds engine content (the asset root by default). */
    val assetsDirectory: String get() = contentRoot

    override val bundledContentRoot: String = "assets://$contentRoot"

    /**
     * `lib://` maps onto the APK's asset tree (`assets/packs`, `assets/sources`). The file list is
     * cached: walking the asset manager recurses through directories and is far too slow to do per
     * lookup.
     */
    override val bundledFileSystem: VirtualFileSystem by lazy {
        AssetFileSystem(appContext, contentRoot)
    }

    /** Sample projects shipped inside the APK (`assets/samples`), copied out on first run. */
    val bundledSamples: VirtualFileSystem by lazy {
        AssetFileSystem(appContext, samplesRoot)
    }

    override val filePicker: PlatformFilePicker? = AndroidFilePicker(appContext)

    override val device: DeviceServices = object : DeviceServices {
        override fun deviceName(): String =
            "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"

        override fun screenDensity(): Float = metrics().density

        override fun screenSize(): Rect {
            val m: DisplayMetrics = metrics()
            return Rect(0f, 0f, m.widthPixels.toFloat(), m.heightPixels.toFloat())
        }

        override fun preferredOrientation(): String = "sensorLandscape"

        override fun locale(): String = java.util.Locale.getDefault().language

        override fun setClipboard(text: String) {
            val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                ?: return
            // `primaryClip` is read-only in Kotlin: since API 29 the getter returns ClipData?
            // while the setter takes a non-null ClipData, so the synthetic property has no setter.
            manager.setPrimaryClip(android.content.ClipData.newPlainText("Lumen2D", text))
        }

        override fun clipboard(): String {
            val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                ?: return ""
            val clip = manager.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            return clip.getItemAt(0).coerceToText(appContext).toString()
        }

        override fun vibrate(millis: Long, amplitude: Int) {
            val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (amplitude > 0) {
                        android.os.VibrationEffect.createOneShot(millis, amplitude)
                    } else {
                        android.os.VibrationEffect.createOneShot(millis, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(millis)
                }
            }
        }

        override fun toast(message: String) {
            android.os.Handler(appContext.mainLooper).post {
                runCatching {
                    android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun openUrl(url: String) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }
        }

        override fun shareText(text: String, subject: String) {
            runCatching {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
                val chooser = Intent.createChooser(intent, subject)
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(chooser)
            }
        }
    }

    private var audio: AndroidAudioOutput? = null

    /** Most recent audio sink, so the editor can show its state in diagnostics. */
    val lastAudioOutput: AndroidAudioOutput? get() = audio

    override fun createAudioOutput(sampleRate: Int, channels: Int): AudioOutput? {
        val output = AndroidAudioOutput(sampleRate, channels)
        audio = output
        output.start()
        // A device without a usable output keeps the silent sink: the game still runs.
        return output
    }

    override fun saveSettings() {
        store.flush()
    }

    /** Diagnostics for the studio's "About this device" row. */
    fun describe(): String =
        "$name ${Build.VERSION.RELEASE} · ${device.deviceName()} · ${metrics().widthPixels}x${metrics().heightPixels}"

    override fun logInfo(message: String) {
        Log.i("Android", message)
        android.util.Log.i(LOG_TAG, message)
    }

    override fun logError(message: String, throwable: Throwable?) {
        Log.e("Android", message)
        android.util.Log.e(LOG_TAG, message, throwable)
    }

    private fun metrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        metrics.setTo(appContext.resources.displayMetrics)
        if (metrics.widthPixels == 0) {
            // Fall back to the window service on displays that report empty metrics.
            val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            manager?.defaultDisplay?.getMetrics(metrics)
        }
        return metrics
    }

    /** Reads a system setting (used by the studio's "reduce motion" and "force RTL" toggles). */
    fun systemSetting(key: String): String =
        runCatching { Settings.System.getString(appContext.contentResolver, key) ?: "" }.getOrDefault("")

    companion object {
        const val LOG_TAG = "Lumen2D"

        /** Asset root: `lib://` resolves `packs/…` and `sources/…` from here. */
        const val DEFAULT_CONTENT_ROOT = ""

        /** Folder inside the content root that holds the asset library packs. */
        const val LIBRARY_FOLDER = "packs"

        /** Folder inside the content root that holds the per-file provenance index. */
        const val SOURCES_FOLDER = "sources"

        /** Pack the engine's own art, fonts and sound live in. */
        const val BASE_PACK = "packs/base"

        const val DEFAULT_SAMPLES_ROOT = "samples"
    }
}

/**
 * Read-only [VirtualFileSystem] over `assets/` inside the APK.
 *
 * AssetManager cannot be asked for a directory listing of arbitrary depth, so the tree is walked
 * once and cached; asset packs are small (a few hundred files) and rarely change at runtime.
 *
 * [root] is the folder inside `assets/` this instance is mounted at, and every path it answers —
 * including the ones `walk` returns — is relative to that root: on a filesystem rooted at
 * `samples`, `walk("hello-lumen2d")` answers `hello-lumen2d/project.lumen`, which [readBytes]
 * takes back directly.
 */
class AssetFileSystem(
    private val context: Context,
    private val root: String = AndroidPlatform.DEFAULT_CONTENT_ROOT,
) : VirtualFileSystem {

    private val manager get() = context.assets

    override val writable: Boolean = false

    private val fileList: List<String> by lazy { scanTree(root) }

    /** [root] as a path prefix (`"samples/"`), or empty when this filesystem is the whole assets folder. */
    private val rootPrefix: String = if (root.isEmpty()) "" else "$root/"

    /** [path] as the asset manager sees it, i.e. with the root this filesystem is mounted at. */
    private fun clean(path: String): String {
        val trimmed = path.substringAfter("://", path).trimStart('/').trimEnd('/')
        return when {
            trimmed.isEmpty() -> root
            root.isEmpty() -> trimmed
            // A path that already carries the root is kept as written — but only at a component
            // boundary, so a folder called `samples-extra` is not mistaken for `samples/…`.
            trimmed == root || trimmed.startsWith(rootPrefix) -> trimmed
            else -> "$root/$trimmed"
        }
    }

    private fun scanTree(directory: String): List<String> = buildList {
        val children: Array<String> = runCatching { manager.list(directory) }.getOrNull() ?: emptyArray()
        for (name in children) {
            val full = if (directory.isEmpty()) name else "$directory/$name"
            // AssetManager reports file and directory entries the same way: a nested list() that
            // returns something means it is a directory.
            val nested: Array<String> = runCatching { manager.list(full) }.getOrNull() ?: emptyArray()
            if (nested.isNotEmpty()) addAll(scanTree(full)) else add(full)
        }
    }

    override fun exists(path: String): Boolean {
        val full = clean(path)
        return fileList.any { it == full } || fileList.any { it.startsWith("$full/") }
    }

    override fun isDirectory(path: String): Boolean {
        val full = clean(path)
        return fileList.any { it.startsWith("$full/") }
    }

    override fun list(path: String): List<String> {
        val full = clean(path)
        val prefix = if (full.isEmpty()) "" else "$full/"
        return fileList.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    override fun readBytes(path: String): ByteArray {
        val full = clean(path)
        return manager.open(full).use { it.readBytes() }
    }

    override fun writeBytes(path: String, bytes: ByteArray) =
        throw UnsupportedOperationException("APK assets are read-only (copy the pack into user://packs to edit it)")

    override fun mkdirs(path: String) {}
    override fun delete(path: String, recursive: Boolean) {}
    override fun rename(from: String, to: String) = throw UnsupportedOperationException("APK assets are read-only")

    override fun size(path: String): Long = runCatching { readBytes(path).size.toLong() }.getOrDefault(0L)
    override fun lastModified(path: String): Long = 0L

    override fun walk(path: String): List<String> {
        val full = clean(path)
        val prefix = if (full.isEmpty()) "" else "$full/"
        // Paths come back *relative to this filesystem's root*, like every other VirtualFileSystem,
        // so a caller can hand one straight back to readBytes/size. Answering with the asset path
        // instead made the sample installer nest each project under its own folder.
        return fileList.filter { it.startsWith(prefix) }
            .map { it.removePrefix(rootPrefix) }
            .filter { it.isNotEmpty() }
            .sorted()
    }

    override fun resolve(path: String): String = clean(path)
    override fun toString(): String = "AssetFileSystem(assets/$root)"
}
