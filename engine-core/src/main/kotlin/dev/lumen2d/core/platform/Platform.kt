/**
 * Lumen2D — platform abstraction.
 *
 * The engine core never talks to Android, the JVM, or any OS API directly: it goes through
 * [Platform] and the small interfaces below. That is what lets the exact same gameplay,
 * physics, renderer and editor logic run inside the Android app, on a desktop JVM
 * (for CI smoke tests and screenshots) and in a pure-headless unit test.
 */
package dev.lumen2d.core.platform

import dev.lumen2d.core.math.Rect

/** Where a game project lives and how the engine reads/writes files. */
interface VirtualFileSystem {
    /** True when the underlying storage can be written to. */
    val writable: Boolean

    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun list(path: String): List<String>
    fun readBytes(path: String): ByteArray
    fun readText(path: String): String = String(readBytes(path), Charsets.UTF_8)
    fun writeBytes(path: String, bytes: ByteArray)
    fun writeText(path: String, text: String) = writeBytes(path, text.toByteArray(Charsets.UTF_8))
    fun mkdirs(path: String)
    fun delete(path: String, recursive: Boolean = false)
    fun rename(from: String, to: String)
    fun size(path: String): Long
    fun lastModified(path: String): Long
    /** All files under [path] (recursive), as absolute virtual paths. */
    fun walk(path: String): List<String> = buildList {
        fun visit(p: String) {
            for (child in list(p)) {
                val full = if (p.endsWith("/")) "$p$child" else "$p/$child"
                if (isDirectory(full)) visit(full) else add(full)
            }
        }
        if (exists(path)) visit(path)
    }
    /** Resolves a path relative to the project root (handles "user://", "lib://", relative paths). */
    fun resolve(path: String): String = path
}

/** Well-known virtual mount prefixes understood by the bundled [VirtualFileSystem] stacks. */
object VfsScheme {
    const val PROJECT = "project://"
    const val BUNDLED = "bundled://"   // read-only content shipped inside the APK
    const val USER = "user://"         // save games, settings, autosaves
    const val LIBRARY = "lib://"       // the shared asset library
    const val EXTERNAL = "device://"   // user picked folder via SAF (writable)
}

/** The result of a user-picked folder/file through the platform document picker. */
data class PickedLocation(val displayName: String, val uri: String, val writable: Boolean)

interface PlatformFilePicker {
    fun pickFolder(): PickedLocation?
    fun pickFile(mimeTypes: List<String>): PickedLocation?
    fun canPick(): Boolean = true
}

/** Desktop/Android audio sink: the mixer pushes 16-bit PCM frames into this. */
interface AudioOutput {
    val sampleRate: Int
    val channels: Int
    fun start()
    fun write(buffer: ShortArray, samples: Int)
    fun stop()
    val latencyMillis: Int
}

/** Optional device services. Android implements all of them; desktop stubs them out. */
interface DeviceServices {
    fun vibrate(millis: Long, amplitude: Int = -1) {}
    fun setClipboard(text: String) {}
    fun clipboard(): String = ""
    fun deviceName(): String = "Unknown"
    fun screenDensity(): Float = 1f
    fun screenSize(): Rect = Rect(0f, 0f, 1280f, 720f)
    fun preferredOrientation(): String = "landscape"
    fun openUrl(url: String) {}
    fun shareText(text: String, subject: String) {}
    fun toast(message: String) {}
    /** File picker used by the editor to import external asset packs. */
    val picker: PlatformFilePicker? get() = null
    /** Localized display strings ("en", "am", ...). */
    fun locale(): String = "en"
}

/**
 * The single entry point every backend implements.
 * @see dev.lumen2d.desktop.DesktopPlatform
 * @see dev.lumen2d.android.AndroidPlatform
 */
interface Platform {
    val name: String
    val isAndroid: Boolean
    /** Persisted app-level storage (settings, recent projects). */
    val settingsStore: MutableMap<String, String>
    /** Writable filesystem rooted at the app's private storage. */
    val userFileSystem: VirtualFileSystem
    val filePicker: PlatformFilePicker?
    val device: DeviceServices
    /** Creates the audio sink; returns null on devices without audio. */
    fun createAudioOutput(sampleRate: Int, channels: Int): AudioOutput?
    /** Base directory that engine content (asset library, sample games) ships in. */
    val bundledContentRoot: String
    /**
     * Read-only filesystem mounted as `lib://` — the shared asset library the engine ships
     * (`packs/base`, documentation, sample projects). Null when the platform has no bundled content.
     */
    val bundledFileSystem: VirtualFileSystem? get() = null
    /** Flush any buffered settings to disk. */
    fun saveSettings()
    fun logInfo(message: String)
    fun logError(message: String, throwable: Throwable? = null)
}

/** A no-op platform used by unit tests and headless tools that provide their own backends. */
object HeadlessPlatform : Platform {
    override val name: String = "headless"
    override val isAndroid: Boolean = false
    val settings = LinkedHashMap<String, String>()
    override val settingsStore: MutableMap<String, String> get() = settings
    override val userFileSystem: VirtualFileSystem = InMemoryFileSystem()
    override val filePicker: PlatformFilePicker? = null
    override val device: DeviceServices = object : DeviceServices {}
    override fun createAudioOutput(sampleRate: Int, channels: Int): AudioOutput? = null
    override val bundledContentRoot: String = "."
    override fun saveSettings() {}
    override fun logInfo(message: String) = println("[lumen] $message")
    override fun logError(message: String, throwable: Throwable?) = println("[lumen:error] $message ${throwable ?: ""}")
}

/** In-memory VFS: fast, dependency-free and used by tests, CI previews and the asset validator. */
class InMemoryFileSystem : VirtualFileSystem {
    private val files = LinkedHashMap<String, ByteArray>()
    private val dirs = LinkedHashSet<String>(listOf(""))
    override val writable: Boolean = true

    private fun normalize(path: String) = path.replace('\\', '/').trim('/')

    override fun exists(path: String): Boolean = files.containsKey(normalize(path)) || dirs.contains(normalize(path))
    override fun isDirectory(path: String): Boolean = dirs.contains(normalize(path))
    override fun list(path: String): List<String> {
        val base = normalize(path)
        val prefix = if (base.isEmpty()) "" else "$base/"
        return (files.keys + dirs)
            .mapNotNull { key ->
                if (key == base || !key.startsWith(prefix)) null
                else key.removePrefix(prefix).substringBefore('/').takeIf { it.isNotEmpty() }
            }
            .distinct()
            .sorted()
    }
    override fun readBytes(path: String): ByteArray =
        files[normalize(path)] ?: throw NoSuchElementException("No such file: $path")
    override fun writeBytes(path: String, bytes: ByteArray) {
        val key = normalize(path)
        var parent = key.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) { dirs.add(parent); parent = parent.substringBeforeLast('/', "") }
        dirs.add(key.substringBeforeLast('/', ""))
        files[key] = bytes
    }
    override fun mkdirs(path: String) {
        var p = normalize(path)
        while (p.isNotEmpty()) { dirs.add(p); p = p.substringBeforeLast('/', "") }
    }
    override fun delete(path: String, recursive: Boolean) {
        val key = normalize(path)
        files.remove(key)
        val prefix = "$key/"
        files.keys.filter { it.startsWith(prefix) }.toList().forEach { files.remove(it) }
        dirs.filter { it == key || it.startsWith(prefix) }.toList().forEach { dirs.remove(it) }
    }
    override fun rename(from: String, to: String) {
        val data = readBytes(from); writeBytes(to, data); delete(from)
    }
    override fun size(path: String): Long = files[normalize(path)]?.size?.toLong() ?: 0L
    override fun lastModified(path: String): Long = System.currentTimeMillis()
}

/** Maps virtual schemes ("user://", "lib://") onto a delegate filesystem rooted at [root]. */
class PrefixedFileSystem(
    private val delegate: VirtualFileSystem,
    private val root: String,
) : VirtualFileSystem by delegate {
    override fun resolve(path: String): String {
        val clean = path.substringAfter("://", path).trimStart('/')
        return if (root.isEmpty()) clean else "$root/$clean"
    }
    private fun real(path: String) = resolve(path)
    override fun exists(path: String) = delegate.exists(real(path))
    override fun isDirectory(path: String) = delegate.isDirectory(real(path))
    override fun list(path: String) = delegate.list(real(path))
    override fun readBytes(path: String) = delegate.readBytes(real(path))
    override fun writeBytes(path: String, bytes: ByteArray) = delegate.writeBytes(real(path), bytes)
    override fun mkdirs(path: String) = delegate.mkdirs(real(path))
    override fun delete(path: String, recursive: Boolean) = delegate.delete(real(path), recursive)
    override fun rename(from: String, to: String) = delegate.rename(real(from), real(to))
    override fun size(path: String) = delegate.size(real(path))
    override fun lastModified(path: String) = delegate.lastModified(real(path))
    override fun walk(path: String) = delegate.walk(real(path))
}
