/**
 * Lumen2D — desktop platform.
 *
 * Implements the engine [Platform] contract on a plain JVM:
 *  * user storage and settings live in a real directory ([FileFileSystem]),
 *  * audio goes through `javax.sound.sampled` (JavaSound), the only dependency-free
 *    sink available on every desktop JDK,
 *  * the file picker and device services use AWT,
 *  * everything degrades gracefully in headless environments (CI, `tools/build-local.sh`),
 *    so the same code produces the documentation screenshots.
 */
package dev.lumen2d.desktop

import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.platform.AudioOutput
import dev.lumen2d.core.platform.DeviceServices
import dev.lumen2d.core.platform.LocalPlatform
import dev.lumen2d.core.platform.PickedLocation
import dev.lumen2d.core.platform.PlatformFilePicker
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** True when an AWT display is available (a window can actually be opened). */
val hasDisplay: Boolean
    get() = runCatching { !GraphicsEnvironment.isHeadless() }.getOrDefault(false)

/**
 * JavaSound sink: the engine mixer pushes interleaved 16-bit PCM frames here.
 * Returns silently when no line is available so headless runs never crash.
 */
class JavaSoundOutput(
    override val sampleRate: Int,
    override val channels: Int,
    /** Approximate buffer latency in milliseconds (small enough for gameplay, large enough to avoid dropouts). */
    private val bufferMillis: Int = 60,
) : AudioOutput {

    private var line: SourceDataLine? = null
    private var byteBuffer = ByteArray(0)

    override val latencyMillis: Int get() = bufferMillis

    val isOpen: Boolean get() = line != null

    override fun start() {
        if (line != null) return
        runCatching {
            val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return
            val opened = AudioSystem.getLine(info) as SourceDataLine
            val bufferBytes = (sampleRate * channels * 2 / 1000) * bufferMillis
            opened.open(format, bufferBytes.coerceAtLeast(1024))
            opened.start()
            line = opened
            byteBuffer = ByteArray(bufferBytes.coerceAtLeast(1024))
        }.onFailure { lastError = it }
    }

    /** Last failure while opening the device (surfaced in the editor's diagnostics panel). */
    var lastError: Throwable? = null
        private set

    override fun write(buffer: ShortArray, samples: Int) {
        val target = line ?: return
        val needed = samples * 2
        if (byteBuffer.size < needed) byteBuffer = ByteArray(needed)
        for (i in 0 until samples) {
            val v = buffer[i].toInt()
            byteBuffer[i * 2] = (v and 0xFF).toByte()
            byteBuffer[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        runCatching { target.write(byteBuffer, 0, needed) }
    }

    override fun stop() {
        line?.let { l ->
            runCatching { l.drain() }
            runCatching { l.stop() }
            runCatching { l.close() }
        }
        line = null
    }
}

/** AWT folder/file chooser used by the editor's "import asset pack" and "open project" actions. */
class AwtFilePicker(private val parent: () -> java.awt.Frame? = { null }) : PlatformFilePicker {

    override fun canPick(): Boolean = hasDisplay

    override fun pickFolder(): PickedLocation? {
        if (!hasDisplay) return null
        val dialog = java.awt.FileDialog(parent(), "Choose a folder", java.awt.FileDialog.LOAD)
        dialog.isMultipleMode = false
        runCatching { dialog.setFilenameFilter { _, _ -> true } }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file
        val file = if (name.isNullOrEmpty()) File(dir) else File(dir, name)
        return PickedLocation(file.name, file.absolutePath, file.canWrite())
    }

    override fun pickFile(mimeTypes: List<String>): PickedLocation? {
        if (!hasDisplay) return null
        val dialog = java.awt.FileDialog(parent(), "Choose a file", java.awt.FileDialog.LOAD)
        dialog.isMultipleMode = false
        mimeTypes.firstOrNull()?.let { filter ->
            val extension = filter.substringAfterLast('/', "").removePrefix("*.")
            if (extension.isNotEmpty()) dialog.setFilenameFilter { _, name -> name.endsWith(extension) }
        }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val file = File(dir, name)
        return PickedLocation(file.name, file.absolutePath, true)
    }
}

/**
 * Finds the engine content folder: the folder that *contains* the pack root `packs/` (and, when
 * present, the provenance index in `sources/`).
 *
 * Looked up in order: an explicit `LUMEN2D_CONTENT` environment variable, the working directory and
 * its ancestors (a repo checkout has `assets-library/packs` next to the sources), a `packs/` folder
 * next to the binary, and finally the per-user content folder the installer populates.
 *
 * The returned folder is mounted as `lib://`, so a scene that says
 * `lib://packs/base/sprites/player.png` resolves through the same path on desktop and on Android.
 */
fun defaultBundledRoot(baseDirectory: File): String {
    System.getenv("LUMEN2D_CONTENT")?.takeIf { it.isNotEmpty() }?.let { return it }
    val candidates = ArrayList<File>()
    // The CLI is regularly started *inside* a sample game (`--check .` from sample-games/<game>),
    // which is two levels below `assets-library/`, so walk the ancestors instead of guessing.
    var directory: File? = File("").absoluteFile
    var levels = 0
    while (directory != null && levels < 6) {
        candidates.add(File(directory, "assets-library"))
        directory = directory.parentFile
        levels++
    }
    candidates.add(File(""))
    candidates.add(File(baseDirectory, "content"))
    return candidates.firstOrNull { File(it, "packs/base/pack.json").isFile }?.absolutePath
        ?: File(baseDirectory, "content").absolutePath
}

/** Desktop platform: real files, real audio, optional AWT dialogs. */
class DesktopPlatform(
    /** Everything the app writes lives here (settings, saves, exported builds). */
    baseDirectory: File = File(System.getProperty("user.home"), ".lumen2d"),
    /** Engine content folder holding `packs/` (the repo's `assets-library`). */
    bundledRoot: String = defaultBundledRoot(baseDirectory),
) : LocalPlatform("desktop", baseDirectory, bundledRoot) {

    private var audio: JavaSoundOutput? = null

    /** The most recently created audio sink (the editor shows its state in the diagnostics panel). */
    val lastAudioOutput: JavaSoundOutput? get() = audio

    override val filePicker: PlatformFilePicker? = if (hasDisplay) AwtFilePicker() else null

    override val device: DeviceServices = object : DeviceServices {
        override fun deviceName(): String =
            "${System.getProperty("os.name") ?: "Desktop"} (${System.getProperty("os.arch") ?: "jvm"})"

        override fun screenDensity(): Float = runCatching { Toolkit.getDefaultToolkit().screenResolution / 96f }.getOrDefault(1f)

        override fun screenSize(): Rect = runCatching {
            val size = Toolkit.getDefaultToolkit().screenSize
            Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        }.getOrDefault(Rect(0f, 0f, 1280f, 720f))

        override fun setClipboard(text: String) {
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        }

        override fun clipboard(): String = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: ""
        }.getOrDefault("")

        override fun openUrl(url: String) {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)) }
        }

        override fun toast(message: String) = logInfo(message)
    }

    override fun createAudioOutput(sampleRate: Int, channels: Int): AudioOutput? {
        val out = JavaSoundOutput(sampleRate, channels)
        audio = out
        out.start()
        // Headless runs (CI previews, tests) simply keep a silent sink.
        return out
    }

    /** Convenience for the CLI: the directory holding settings and user files. */
    val home: File get() = dataDirectory
}
