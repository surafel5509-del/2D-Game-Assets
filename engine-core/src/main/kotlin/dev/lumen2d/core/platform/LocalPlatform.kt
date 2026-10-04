/**
 * Lumen2D — JVM file-backed platform pieces.
 *
 * Android and desktop share these helpers: a plain `java.io` filesystem, a JSON settings store
 * and a base [Platform] implementation that only needs the platform-specific bits
 * (audio output, file picker, device services) filled in.
 */
package dev.lumen2d.core.platform

import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.util.Json
import java.io.File

/** `java.io.File`-backed virtual filesystem; the workhorse for desktop, tests and project folders. */
class FileFileSystem(private val root: File) : VirtualFileSystem {
    override val writable: Boolean get() = root.isDirectory || root.mkdirs()

    private fun file(path: String): File {
        val clean = path.substringAfter("://", path).trimStart('/').trimEnd('/')
        return if (clean.isEmpty()) root else File(root, clean)
    }

    override fun exists(path: String) = file(path).exists()
    override fun isDirectory(path: String) = file(path).isDirectory
    override fun list(path: String): List<String> =
        (file(path).listFiles() ?: emptyArray()).map { it.name }.sorted()

    override fun readBytes(path: String): ByteArray = file(path).readBytes()
    override fun writeBytes(path: String, bytes: ByteArray) {
        val target = file(path)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    override fun mkdirs(path: String) { file(path).mkdirs() }

    override fun delete(path: String, recursive: Boolean) {
        val target = file(path)
        if (recursive) target.deleteRecursively() else target.delete()
    }

    override fun rename(from: String, to: String) {
        val target = file(to)
        target.parentFile?.mkdirs()
        if (!file(from).renameTo(target)) {
            writeBytes(to, readBytes(from)); delete(from)
        }
    }

    override fun size(path: String): Long = file(path).length()
    override fun lastModified(path: String): Long = file(path).lastModified()
    override fun resolve(path: String) = file(path).absolutePath
    override fun toString() = "FileFileSystem(${root.absolutePath})"
}

/** JSON-backed key/value store persisted to a file in the user filesystem. */
class JsonSettingsStore(
    private val vfs: VirtualFileSystem,
    private val path: String = "user://settings.json",
) : MutableMap<String, String> {
    private val data = LinkedHashMap<String, String>()
    private var loaded = false
    var dirty: Boolean = false
        private set

    private fun ensureLoaded(): LinkedHashMap<String, String> {
        if (loaded) return data
        loaded = true
        if (vfs.exists(path)) {
            runCatching {
                val parsed = Json.parse(vfs.readText(path))
                (parsed as? Map<*, *>)?.forEach { (key, value) -> data[key.toString()] = value?.toString() ?: "" }
            }
        }
        return data
    }

    override val size: Int get() = ensureLoaded().size
    override val keys: MutableSet<String> get() = ensureLoaded().keys
    override val values: MutableCollection<String> get() = ensureLoaded().values
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>> get() = ensureLoaded().entries

    override fun containsKey(key: String): Boolean = ensureLoaded().containsKey(key)
    override fun containsValue(value: String): Boolean = ensureLoaded().containsValue(value)
    override fun get(key: String): String? = ensureLoaded()[key]
    override fun isEmpty(): Boolean = ensureLoaded().isEmpty()

    override fun clear() { ensureLoaded().clear(); dirty = true }
    override fun put(key: String, value: String): String? {
        dirty = true
        return ensureLoaded().put(key, value)
    }
    override fun putAll(from: Map<out String, String>) { dirty = true; ensureLoaded().putAll(from) }
    override fun remove(key: String): String? { dirty = true; return ensureLoaded().remove(key) }

    fun flush() {
        if (!dirty && vfs.exists(path)) return
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) vfs.mkdirs(parent)
        vfs.writeText(path, Json.stringify(LinkedHashMap<String, Any?>(ensureLoaded())))
        dirty = false
    }

    fun getBool(key: String, fallback: Boolean = false): Boolean =
        this[key]?.equals("true", true) ?: fallback

    fun getFloat(key: String, fallback: Float = 0f): Float = this[key]?.toFloatOrNull() ?: fallback
    fun getInt(key: String, fallback: Int = 0): Int = this[key]?.toIntOrNull() ?: fallback
    fun putBool(key: String, value: Boolean) { put(key, if (value) "true" else "false") }
    fun putFloat(key: String, value: Float) { put(key, value.toString()) }
    fun putInt(key: String, value: Int) { put(key, value.toString()) }
}

/**
 * Base [Platform] for JVM backends. Subclasses provide audio and (optionally) richer device
 * services; everything else — settings, user storage, bundled content — is handled here.
 */
abstract class LocalPlatform(
    override val name: String,
    private val baseDirectory: File,
    override val bundledContentRoot: String = baseDirectory.absolutePath,
) : Platform {
    override val isAndroid: Boolean get() = false

    private val settingsVfs = FileFileSystem(baseDirectory)
    private val store = JsonSettingsStore(settingsVfs, "settings.json")
    override val settingsStore: MutableMap<String, String> get() = store

    override val userFileSystem: VirtualFileSystem =
        PrefixedFileSystem(FileFileSystem(baseDirectory), "user")

    /** `lib://` reads the engine's bundled content folder (asset library, docs, samples). */
    override val bundledFileSystem: VirtualFileSystem? by lazy {
        val directory = File(bundledContentRoot)
        if (directory.isDirectory) FileFileSystem(directory) else null
    }

    override val filePicker: PlatformFilePicker? = null
    override val device: DeviceServices = object : DeviceServices {
        override fun deviceName(): String = name
        override fun screenSize(): Rect = Rect(0f, 0f, 1280f, 720f)
    }

    override fun saveSettings() { store.flush() }
    override fun logInfo(message: String) = println("[lumen] $message")
    override fun logError(message: String, throwable: Throwable?) =
        println("[lumen:error] $message${throwable?.let { ": ${it.message}" } ?: ""}")

    /** Directory that holds project data, exported builds and the editor's recent list. */
    val dataDirectory: File get() = baseDirectory
}

/** Assets bundled inside an APK / jar, exposed read-only through a lookup lambda. */
class BundledFileSystem(
    private val entries: () -> List<String>,
    private val reader: (String) -> ByteArray,
) : VirtualFileSystem {
    override val writable: Boolean = false
    override fun exists(path: String) = entries().any { it.equals(path, true) }
    override fun isDirectory(path: String): Boolean {
        val prefix = "$path/"
        return entries().any { it.startsWith(prefix) }
    }
    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return entries().filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotEmpty() }.distinct().sorted()
    }
    override fun readBytes(path: String): ByteArray = reader(path)
    override fun writeBytes(path: String, bytes: ByteArray) =
        throw UnsupportedOperationException("Bundled content is read-only")
    override fun mkdirs(path: String) {}
    override fun delete(path: String, recursive: Boolean) {}
    override fun rename(from: String, to: String) = throw UnsupportedOperationException("read-only")
    override fun size(path: String): Long = runCatching { readBytes(path).size.toLong() }.getOrDefault(0L)
    override fun lastModified(path: String): Long = 0L
    override fun walk(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return entries().filter { it.startsWith(prefix) }.sorted()
    }
}

/** Small helper used by the desktop/Android platforms to describe the device. */
class SimpleDeviceServices(
    private val name: String,
    private val size: Rect,
    private val density: Float = 1f,
    private val orientation: String = "landscape",
) : DeviceServices {
    override fun deviceName(): String = name
    override fun screenSize(): Rect = size
    override fun screenDensity(): Float = density
    override fun preferredOrientation(): String = orientation
}
