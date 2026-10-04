/**
 * Lumen2D — core utilities: logging (with an in-editor console ring buffer), profiling,
 * signals, object pools and allocations helpers used across the engine.
 */
package dev.lumen2d.core.util

import dev.lumen2d.core.math.MathUtil

// ---------------------------------------------------------------------------- logging

enum class LogLevel(val severity: Int, val label: String) {
    DEBUG(0, "DEBUG"), INFO(1, "INFO"), WARN(2, "WARN"), ERROR(3, "ERROR");

    companion object {
        fun parse(text: String): LogLevel = entries.firstOrNull { it.label.equals(text, true) } ?: INFO
    }
}

data class LogRecord(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timeMillis: Long,
    val timeText: String,
)

/**
 * Global logger. Everything the engine prints goes through here so the editor can show a
 * real console panel (see [records]) and the runtime player can keep quiet in release builds.
 */
object Log {
    private const val MAX_RECORDS = 2000
    private val ring = ArrayDeque<LogRecord>(MAX_RECORDS)
    private val listeners = ArrayList<(LogRecord) -> Unit>(4)

    var minLevel: LogLevel = LogLevel.DEBUG
    /** Set to false by the Android backend to also mirror into logcat. */
    var printToStdout: Boolean = true

    val records: List<LogRecord> get() = ring.toList()

    fun addListener(l: (LogRecord) -> Unit) { listeners.add(l) }
    fun removeListener(l: (LogRecord) -> Unit) { listeners.remove(l) }
    fun clear() { ring.clear() }

    fun d(tag: String, message: String) = write(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = write(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = write(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write(LogLevel.ERROR, tag, throwable?.let { "$message\n${it.stackTraceToString()}" } ?: message)
    }

    fun write(level: LogLevel, tag: String, message: String) {
        if (level.severity < minLevel.severity) return
        val now = System.currentTimeMillis()
        val record = LogRecord(level, tag, message, now, formatClock(now))
        if (ring.size >= MAX_RECORDS) ring.removeFirst()
        ring.addLast(record)
        if (printToStdout) println("[${record.timeText}] ${level.label}/$tag: $message")
        for (l in listeners) runCatching { l(record) }
    }

    private fun formatClock(millis: Long): String {
        val totalSeconds = millis / 1000
        val ms = millis % 1000
        val s = totalSeconds % 60
        val m = (totalSeconds / 60) % 60
        val h = (totalSeconds / 3600) % 24
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }
}

// -------------------------------------------------------------------------- profiling

/**
 * Frame profiler. Samples are recorded per frame into a rolling history so the editor's
 * profiler panel can draw graphs for frame time, draw calls, active nodes and more.
 */
object Profiler {
    const val HISTORY = 120

    class Sample(val name: String) {
        var lastNanos: Long = 0
        var avgNanos: Double = 0.0
        var peakNanos: Long = 0
        var calls: Int = 0
        val history = FloatArray(HISTORY)
        private var historyIndex = 0
        private var accNanos = 0L
        private var accCalls = 0

        fun record(nanos: Long) {
            lastNanos = nanos
            accNanos += nanos; accCalls++
            if (nanos > peakNanos) peakNanos = nanos
        }

        internal fun endFrame() {
            avgNanos = if (accCalls > 0) accNanos.toDouble() / accCalls else 0.0
            history[historyIndex] = lastNanos / 1_000_000f
            historyIndex = (historyIndex + 1) % HISTORY
            accNanos = 0; accCalls = 0
        }

        val lastMillis: Float get() = lastNanos / 1_000_000f
        val avgMillis: Float get() = (avgNanos / 1_000_000.0).toFloat()
        val peakMillis: Float get() = peakNanos / 1_000_000f
        /** History oldest -> newest. */
        val historyOrdered: FloatArray get() = FloatArray(HISTORY) { history[(historyIndex + it) % HISTORY] }
    }

    private val samples = LinkedHashMap<String, Sample>()
    val frameCount get() = frames
    private var frames = 0L
    private var frameStart = 0L
    private var lastFrameNanos = 0L

    /** Counters shown in the profiler overlay (draw calls, sprites, bodies, ...). */
    private val counters = LinkedHashMap<String, Float>()
    private val counterPeaks = LinkedHashMap<String, Float>()

    val all: Collection<Sample> get() = samples.values

    fun sample(name: String): Sample = samples.getOrPut(name) { Sample(name) }

    fun beginFrame() {
        frameStart = System.nanoTime()
        counters.clear()
    }
    fun endFrame() {
        val now = System.nanoTime()
        lastFrameNanos = now - frameStart
        sample("frame").record(lastFrameNanos)
        frames++
        for (s in samples.values) s.endFrame()
        for ((k, v) in counters) counterPeaks[k] = maxOf(counterPeaks.getOrDefault(k, 0f), v)
    }

    val lastFrameMillis: Float get() = lastFrameNanos / 1_000_000f
    val fps: Float get() = if (lastFrameNanos <= 0) 0f else 1_000_000_000f / lastFrameNanos

    /** Adds to this frame's counter (renderers report draw calls, sprites, bodies, ...). */
    fun count(name: String, value: Float) { counters[name] = (counters[name] ?: 0f) + value }
    fun counter(name: String): Float = counters[name] ?: 0f
    fun counterPeak(name: String): Float = counterPeaks[name] ?: 0f
    fun countersSnapshot(): Map<String, Float> = LinkedHashMap(counters)

    inline fun <T> measure(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            sample(name).record(System.nanoTime() - start)
        }
    }

    fun reset() { samples.clear(); counters.clear(); counterPeaks.clear(); frames = 0 }
}

// ---------------------------------------------------------------------------- signals

/**
 * Lightweight typed signal (a multicast callback list) — Lumen2D's equivalent of Godot
 * signals or UnityEvents. Kept allocation-free while emitting by copying only when the
 * listener list is mutated during dispatch.
 */
class Signal<T> {
    private var listeners: MutableList<(T) -> Unit> = ArrayList(2)
    private var emitting = false
    private var pendingRemoval: MutableList<(T) -> Unit>? = null

    val size: Int get() = listeners.size
    val hasListeners: Boolean get() = listeners.isNotEmpty()

    fun connect(listener: (T) -> Unit) {
        if (emitting) { listeners = ArrayList(listeners) }
        listeners.add(listener)
    }

    fun disconnect(listener: (T) -> Unit) {
        if (emitting) {
            (pendingRemoval ?: ArrayList<(T) -> Unit>(2).also { pendingRemoval = it }).add(listener)
        } else listeners.remove(listener)
    }

    fun clear() { if (emitting) { pendingRemoval = ArrayList(listeners) } else listeners.clear() }

    /** Removes every listener attached by [owner] via [connectOwned]. */
    fun disconnectAll(owner: Any) { owned[owner]?.forEach { disconnect(it) }; owned.remove(owner) }

    private val owned = HashMap<Any, MutableList<(T) -> Unit>>(2)

    /** Connects a listener that is automatically removed when the owning node is freed. */
    fun connectOwned(owner: Any, listener: (T) -> Unit) {
        connect(listener)
        (owned[owner] ?: ArrayList<(T) -> Unit>(2).also { owned[owner] = it }).add(listener)
    }

    fun emit(value: T) {
        if (listeners.isEmpty()) return
        emitting = true
        try {
            for (i in listeners.indices) {
                @Suppress("UNCHECKED_CAST")
                (listeners[i] as ((T) -> Unit))(value)
            }
        } finally {
            emitting = false
            pendingRemoval?.let { rem -> rem.forEach { listeners.remove(it) }; pendingRemoval = null }
        }
    }

    operator fun invoke(value: T) = emit(value)
}

/** Signal without a payload. */
class Event {
    private val inner = Signal<Unit>()
    val size: Int get() = inner.size
    fun connect(l: () -> Unit) { inner.connect { l() } }
    fun connectOwned(owner: Any, l: () -> Unit) { inner.connectOwned(owner) { l() } }
    fun disconnect(l: () -> Unit) { inner.disconnect { l() } }
    fun emit() { inner.emit(Unit) }
    operator fun invoke() = emit()
}

// ------------------------------------------------------------------------------ pools

/** Simple object pool used for particles, bullets, draw commands and script closures. */
class ObjectPool<T>(private val factory: () -> T, private val reset: (T) -> Unit = {}, initial: Int = 0) {
    private val free = ArrayDeque<T>(maxOf(initial, 8))
    private var created = 0

    init { repeat(initial) { free.addLast(factory()); created++ } }

    val pooled: Int get() = free.size
    val total: Int get() = created

    fun obtain(): T = if (free.isEmpty()) { created++; factory() } else free.removeLast()

    fun recycle(item: T) { reset(item); free.addLast(item) }

    fun clear() { free.clear() }
}

/** Generates small, stable, human-readable ids ("Player", "Player2", ...) — used by the editor. */
class NameRegistry(private val base: String = "Node") {
    private val used = HashSet<String>()

    fun register(name: String) { used.add(name) }
    fun unregister(name: String) { used.remove(name) }
    fun reset() { used.clear() }

    fun unique(preferred: String? = null): String {
        val stem = preferred?.takeIf { it.isNotBlank() } ?: base
        if (used.add(stem)) return stem
        var i = 2
        while (true) { val candidate = "$stem$i"; if (used.add(candidate)) return candidate; i++ }
    }
}

// ----------------------------------------------------------------------------- timing

/** Monotonic clock in seconds with pause support — all gameplay timing goes through here. */
class GameClock {
    private var last = System.nanoTime()
    private var accumulated = 0L

    var timeScale: Float = 1f
    var maxDelta: Float = 1f / 15f
    var paused: Boolean = false

    val elapsedSeconds: Float get() = accumulated / 1_000_000_000f
    var delta: Float = 0f; private set
    var unscaledDelta: Float = 0f; private set
    var frame: Long = 0L; private set

    fun tick(): Float {
        val now = System.nanoTime()
        val raw = (now - last) / 1_000_000_000f
        last = now
        unscaledDelta = MathUtil.clamp(raw, 0f, maxDelta)
        delta = if (paused) 0f else unscaledDelta * timeScale
        if (!paused) accumulated += (unscaledDelta * 1_000_000_000L * timeScale).toLong()
        frame++
        return delta
    }

    fun reset() { last = System.nanoTime(); accumulated = 0; frame = 0; delta = 0f }
}

// ---------------------------------------------------------------------------- misc

/** Encapsulates a value that changes rarely, notifying listeners — used by editor panels. */
class Observable<T>(initial: T) {
    private var value: T = initial
    val changed = Event()
    var get: T
        get() = value
        set(v) { if (v != value) { value = v; changed.emit() } }
    fun setWithoutNotify(v: T) { value = v }
}

/** Wraps a block with a nanosecond stopwatch, returning the result and elapsed nanos. */
inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - start)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) { value /= 1024; unitIndex++ }
    return "%.1f %s".format(value, units[unitIndex])
}

fun formatDuration(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val ms = ((seconds - totalSeconds) * 1000).toInt()
    val m = totalSeconds / 60; val s = totalSeconds % 60
    return if (m > 0) "%d:%02d.%03d".format(m, s, ms) else "%d.%03ds".format(s, ms)
}

/**
 * Engine identity, surfaced to scripts (`engine.version`), the editor's About box and exported
 * builds. Keep in sync with `gradle.properties`.
 */
object EngineInfo {
    const val NAME = "Lumen2D"
    const val VERSION = "1.0.0"
    const val API_VERSION = 1
    const val AUTHOR = "Lumen2D contributors"
    fun describe(): String = "$NAME $VERSION (api $API_VERSION)"
}
