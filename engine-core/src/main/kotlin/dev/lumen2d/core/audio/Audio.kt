/**
 * Lumen2D — audio engine.
 *
 * A small software mixer with buses and DSP effects, so the same mix runs on Android
 * (AudioTrack), desktop (javax.sound) and inside automated tests (offline rendering):
 *  * [WavCodec] decodes/encodes PCM WAV without external libraries,
 *  * [AudioMixer] mixes voices -> buses -> master with volume/pan/pitch, fades and ducking,
 *  * effects: gain, pan, low/high pass, echo, reverb, distortion, bit-crush, limiter,
 *  * [Synth] generates sound effects and music procedurally (the engine's built-in
 *    royalty-free audio source used by the asset library and by sample games).
 */
package dev.lumen2d.core.audio

import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// --------------------------------------------------------------------------- wav codec

/**
 * Minimal RIFF/WAVE reader and writer.
 * Supports 8/16/24/32-bit PCM and 32-bit float, mono and stereo — the formats produced by
 * the engine's own generators and by the free asset packs shipped with it.
 */
object WavCodec {

    class WavException(message: String) : RuntimeException(message)

    fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte()

    fun decode(bytes: ByteArray, name: String = "clip"): AudioClip {
        if (!isWav(bytes)) throw WavException("Not a WAV file")
        var offset = 12
        var format = 1
        var channels = 1
        var sampleRate = 44100
        var bitsPerSample = 16
        var dataStart = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readIntLE(bytes, offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    format = readShortLE(bytes, body)
                    channels = readShortLE(bytes, body + 2)
                    sampleRate = readIntLE(bytes, body + 4)
                    bitsPerSample = readShortLE(bytes, body + 14)
                }
                "data" -> { dataStart = body; dataLength = min(size, bytes.size - body) }
            }
            offset = body + size + (size and 1)
        }
        if (dataStart < 0) throw WavException("WAV file has no data chunk")
        val frameCount = dataLength / (channels * bitsPerSample / 8)
        val samples = ShortArray(frameCount * channels)
        val bytesPerSample = bitsPerSample / 8
        for (i in 0 until frameCount * channels) {
            val p = dataStart + i * bytesPerSample
            val value: Float = when {
                format == 3 && bitsPerSample == 32 -> Float.fromBits(readIntLE(bytes, p))
                bitsPerSample == 16 -> readShortSignedLE(bytes, p) / 32768f
                bitsPerSample == 8 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                bitsPerSample == 24 -> {
                    val v = ((bytes[p + 2].toInt() shl 16) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF))
                    val signed = if (v and 0x800000 != 0) v or -0x1000000 else v
                    signed / 8388608f
                }
                bitsPerSample == 32 -> readIntLE(bytes, p) / 2147483648f
                else -> 0f
            }
            samples[i] = (value * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return AudioClip(name, sampleRate, channels, samples)
    }

    fun encode(clip: AudioClip): ByteArray {
        val dataSize = clip.samples.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataSize)
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeInt(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun writeShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        writeAscii("RIFF"); writeInt(36 + dataSize); writeAscii("WAVE")
        writeAscii("fmt "); writeInt(16); writeShort(1); writeShort(clip.channels)
        writeInt(clip.sampleRate); writeInt(clip.sampleRate * clip.channels * 2)
        writeShort(clip.channels * 2); writeShort(16)
        writeAscii("data"); writeInt(dataSize)
        for (s in clip.samples) writeShort(s.toInt())
        return out.toByteArray()
    }

    private fun readIntLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** Signed 16-bit read — PCM sample values are two's complement, unlike the unsigned header fields. */
    private fun readShortSignedLE(b: ByteArray, o: Int): Int = readShortLE(b, o).toShort().toInt()
}

/** Decoded PCM audio. Samples are interleaved 16-bit. */
class AudioClip(
    val name: String,
    val sampleRate: Int,
    val channels: Int,
    val samples: ShortArray,
) {
    val frameCount: Int get() = samples.size / channels
    val durationSeconds: Float get() = frameCount.toFloat() / sampleRate
    /** Loudness estimate used by the asset browser to show a waveform summary. */
    val peak: Float by lazy {
        var p = 0
        for (s in samples) { val a = abs(s.toInt()); if (a > p) p = a }
        p / 32768f
    }

    fun sampleAt(frame: Int, channel: Int): Float {
        if (frame < 0 || frame >= frameCount) return 0f
        val index = frame * channels + channel.coerceIn(0, channels - 1)
        return samples[index] / 32768f
    }

    /** Downsamples to mono float data for waveform drawing in the editor. */
    fun waveform(buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        if (frameCount == 0) return out
        val perBucket = frameCount / buckets
        for (b in 0 until buckets) {
            var peak = 0f
            for (i in 0 until perBucket) {
                val frame = b * perBucket + i
                if (frame >= frameCount) break
                for (c in 0 until channels) {
                    val v = abs(sampleAt(frame, c))
                    if (v > peak) peak = v
                }
            }
            out[b] = peak
        }
        return out
    }

    /** Applies a gain and returns a new clip (asset pipeline post-processing). */
    fun withGain(gain: Float): AudioClip = AudioClip(name, sampleRate, channels,
        ShortArray(samples.size) { (samples[it] * gain).coerceIn(-32768f, 32767f).toInt().toShort() })

    fun trimmedTo(maxSeconds: Float): AudioClip {
        val frames = min(frameCount, (maxSeconds * sampleRate).toInt())
        return AudioClip(name, sampleRate, channels, samples.copyOf(frames * channels))
    }
}

// ------------------------------------------------------------------------- dsp effects

/** Base class for bus effects. Each effect processes interleaved stereo float frames. */
abstract class AudioEffect(val name: String) {
    var enabled: Boolean = true
    var wet: Float = 1f
    abstract fun prepare(sampleRate: Int, channels: Int)
    abstract fun process(buffer: FloatArray, frames: Int, channels: Int)
    open fun reset() {}
    abstract fun serialize(): Map<String, Any?>
}

class GainEffect(var gain: Float = 1f) : AudioEffect("Gain") {
    override fun prepare(sampleRate: Int, channels: Int) {}
    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        if (gain == 1f) return
        for (i in 0 until frames * channels) buffer[i] *= gain
    }
    override fun serialize() = mapOf<String, Any?>("type" to "gain", "gain" to gain.toDouble())
}

class LowPassEffect(var cutoff: Float = 2000f, var resonance: Float = 0.7f) : AudioEffect("LowPass") {
    private var a1 = 0f; private var a2 = 0f; private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    override fun prepare(sampleRate: Int, channels: Int) {
        val w0 = 2.0 * Math.PI * cutoff / sampleRate
        val alpha = sin(w0) / (2.0 * resonance)
        val cosw = kotlin.math.cos(w0)
        val b0d = (1 - cosw) / 2; val b1d = 1 - cosw; val b2d = (1 - cosw) / 2
        val a0d = 1 + alpha; val a1d = -2 * cosw; val a2d = 1 - alpha
        b0 = (b0d / a0d).toFloat(); b1 = (b1d / a0d).toFloat(); b2 = (b2d / a0d).toFloat()
        a1 = (a1d / a0d).toFloat(); a2 = (a2d / a0d).toFloat()
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames * channels) {
            val x = buffer[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            buffer[i] = y * wet + x * (1f - wet)
        }
    }
    override fun serialize() = mapOf<String, Any?>("type" to "lowpass", "cutoff" to cutoff.toDouble(), "resonance" to resonance.toDouble())
}

class HighPassEffect(var cutoff: Float = 200f) : AudioEffect("HighPass") {
    private var alpha = 0.9f
    private var previousInput = 0f
    private var previousOutput = 0f

    override fun prepare(sampleRate: Int, channels: Int) {
        val rc = 1.0 / (2.0 * Math.PI * cutoff)
        val dt = 1.0 / sampleRate
        alpha = (rc / (rc + dt)).toFloat()
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames * channels) {
            val x = buffer[i]
            val y = alpha * (previousOutput + x - previousInput)
            previousInput = x; previousOutput = y
            buffer[i] = y * wet + x * (1f - wet)
        }
    }
    override fun serialize() = mapOf<String, Any?>("type" to "highpass", "cutoff" to cutoff.toDouble())
}

class EchoEffect(var delaySeconds: Float = 0.28f, var feedback: Float = 0.35f, var mixLevel: Float = 0.3f) : AudioEffect("Echo") {
    private var buffer = FloatArray(0)
    private var writeIndex = 0
    private var delayFrames = 0

    override fun prepare(sampleRate: Int, channels: Int) {
        delayFrames = max(1, (delaySeconds * sampleRate).toInt()) * channels
        buffer = FloatArray(delayFrames * 2)
        writeIndex = 0
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        if (this.buffer.isEmpty()) return
        val out = buffer
        for (i in 0 until frames * channels) {
            val readIndex = (writeIndex + this.buffer.size - delayFrames) % this.buffer.size
            val delayed = this.buffer[readIndex]
            this.buffer[writeIndex] = out[i] + delayed * feedback
            writeIndex = (writeIndex + 1) % this.buffer.size
            out[i] = out[i] * (1f - mixLevel * wet) + delayed * mixLevel * wet
        }
    }

    override fun reset() { buffer.fill(0f); writeIndex = 0 }
    override fun serialize() = mapOf<String, Any?>(
        "type" to "echo", "delay" to delaySeconds.toDouble(), "feedback" to feedback.toDouble(), "mix" to mixLevel.toDouble())
}

/** Schroeder-style reverb: four comb filters plus two all-passes per channel. */
class ReverbEffect(var roomSize: Float = 0.6f, var damping: Float = 0.4f, var mixLevel: Float = 0.25f) : AudioEffect("Reverb") {
    private class Comb(var size: Int) {
        lateinit var buffer: FloatArray
        var index = 0
        var filterStore = 0f
        fun process(input: Float, feedback: Float, damp: Float): Float {
            val output = buffer[index]
            filterStore = output * (1f - damp) + filterStore * damp
            buffer[index] = input + filterStore * feedback
            index = (index + 1) % size
            return output
        }
    }

    private class AllPass(var size: Int, val gain: Float = 0.5f) {
        lateinit var buffer: FloatArray
        var index = 0
        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = input + buffered * gain
            index = (index + 1) % size
            return output
        }
    }

    private val combs = ArrayList<Comb>(8)
    private val allPasses = ArrayList<AllPass>(4)

    override fun prepare(sampleRate: Int, channels: Int) {
        combs.clear(); allPasses.clear()
        for (ch in 0 until channels) {
            val base = if (ch == 0) 1f else 1.03f
            combs.add(Comb((sampleRate * 0.0297f * base).toInt()).also { it.buffer = FloatArray(it.size) })
            combs.add(Comb((sampleRate * 0.0371f * base).toInt()).also { it.buffer = FloatArray(it.size) })
            combs.add(Comb((sampleRate * 0.0411f * base).toInt()).also { it.buffer = FloatArray(it.size) })
            combs.add(Comb((sampleRate * 0.0437f * base).toInt()).also { it.buffer = FloatArray(it.size) })
            allPasses.add(AllPass((sampleRate * 0.005f * base).toInt()).also { it.buffer = FloatArray(it.size) })
            allPasses.add(AllPass((sampleRate * 0.0017f * base).toInt()).also { it.buffer = FloatArray(it.size) })
        }
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        if (combs.isEmpty()) return
        val feedback = 0.7f + roomSize * 0.28f
        val damp = damping * 0.4f
        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                val i = frame * channels + ch
                val dry = buffer[i]
                var acc = 0f
                for (c in 0 until 4) acc += combs[ch * 4 + c].process(dry, feedback, damp)
                acc *= 0.25f
                for (a in 0 until 2) acc = allPasses[ch * 2 + a].process(acc)
                buffer[i] = dry * (1f - mixLevel * wet) + acc * mixLevel * wet
            }
        }
    }

    override fun reset() {
        combs.forEach { it.buffer.fill(0f); it.index = 0 }
        allPasses.forEach { it.buffer.fill(0f); it.index = 0 }
    }
    override fun serialize() = mapOf<String, Any?>(
        "type" to "reverb", "room" to roomSize.toDouble(), "damping" to damping.toDouble(), "mix" to mixLevel.toDouble())
}

class DistortionEffect(var drive: Float = 4f, var level: Float = 0.7f) : AudioEffect("Distortion") {
    override fun prepare(sampleRate: Int, channels: Int) {}
    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames * channels) {
            val x = buffer[i] * drive
            val shaped = x / (1f + abs(x))
            buffer[i] = buffer[i] * (1f - wet) + shaped * level * wet
        }
    }
    override fun serialize() = mapOf<String, Any?>("type" to "distortion", "drive" to drive.toDouble(), "level" to level.toDouble())
}

class BitCrushEffect(var bits: Int = 8, var sampleRateDivisor: Int = 2) : AudioEffect("BitCrush") {
    private var holdCounter = 0
    private var holdValue = 0f
    override fun prepare(sampleRate: Int, channels: Int) {}
    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val levels = (1 shl bits).toFloat()
        for (i in 0 until frames * channels) {
            if (holdCounter % sampleRateDivisor == 0) holdValue = kotlin.math.round(buffer[i] * levels) / levels
            holdCounter++
            buffer[i] = holdValue
        }
    }
    override fun serialize() = mapOf<String, Any?>("type" to "bitcrush", "bits" to bits, "divisor" to sampleRateDivisor)
}

/** Brick-wall limiter that keeps the master bus from clipping. */
class LimiterEffect(var ceiling: Float = 0.98f) : AudioEffect("Limiter") {
    override fun prepare(sampleRate: Int, channels: Int) {}
    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (i in 0 until frames * channels) {
            val x = buffer[i]
            buffer[i] = when {
                x > ceiling -> ceiling
                x < -ceiling -> -ceiling
                else -> x
            }
        }
    }
    override fun serialize() = mapOf<String, Any?>("type" to "limiter", "ceiling" to ceiling.toDouble())
}

// ------------------------------------------------------------------------------ mixer

/** A mix bus: master, music, sfx, ui, ambient... with its own effects chain. */
class AudioBus(val name: String, var volume: Float = 1f, var muted: Boolean = false) {
    var parent: AudioBus? = null
    val effects = ArrayList<AudioEffect>()
    /** Voice ducking multiplier applied by cutscenes/announcers. */
    var duck: Float = 1f

    fun addEffect(effect: AudioEffect): AudioBus { effects.add(effect); return this }

    fun effectiveVolume(): Float {
        var v = volume * duck
        if (muted) return 0f
        var p = parent
        while (p != null) { if (p.muted) return 0f; v *= p.volume * p.duck; p = p.parent }
        return v
    }

    fun prepare(sampleRate: Int, channels: Int) = effects.forEach { it.prepare(sampleRate, channels) }

    fun process(buffer: FloatArray, frames: Int, channels: Int) {
        for (effect in effects) if (effect.enabled) effect.process(buffer, frames, channels)
    }

    fun serialize(): Map<String, Any?> = linkedMapOf(
        "name" to name, "volume" to volume.toDouble(), "muted" to muted,
        "effects" to effects.map { it.serialize() })
}

/** A playing sound instance. */
class Voice(
    val clip: AudioClip,
    val bus: AudioBus,
    var volume: Float = 1f,
    var pitch: Float = 1f,
    var pan: Float = 0f,
    var loop: Boolean = false,
    /** Start offset inside the clip, in seconds. */
    var startOffset: Float = 0f,
) {
    var playing: Boolean = true
    var position: Double = startOffset.toDouble() * clip.sampleRate
    var fadeIn: Float = 0f
    var fadeOut: Float = 0f
    private var fadeElapsed = 0f
    private var fadeOutElapsed = 0f
    var onClickFinished: (() -> Unit)? = null
    /** Loop region support for music with intro (loopStart..loopEnd). */
    var loopStart: Float = 0f
    var loopEnd: Float = 0f
    var tag: String = ""
    /** True when this voice may be stolen when the voice budget is exceeded. */
    var stealable: Boolean = true

    val durationSeconds: Float get() = clip.durationSeconds
    val progress: Float get() = if (clip.frameCount == 0) 0f else (position / clip.sampleRate / clip.durationSeconds).toFloat()

    fun stop(fadeSeconds: Float = 0f) {
        if (fadeSeconds <= 0f) playing = false else { fadeOut = fadeSeconds; fadeOutElapsed = 0f }
    }

    internal fun advance(deltaSeconds: Float): Boolean {
        if (fadeIn > 0f) fadeElapsed = min(fadeIn, fadeElapsed + deltaSeconds)
        if (fadeOut > 0f) {
            fadeOutElapsed += deltaSeconds
            if (fadeOutElapsed >= fadeOut) { playing = false; return false }
        }
        position += (clip.sampleRate * deltaSeconds * pitch).toDouble()
        val endFrame: Double = if (loopEnd > 0f) (loopEnd * clip.sampleRate).toDouble() else clip.frameCount.toDouble()
        if (position >= endFrame) {
            if (loop) {
                val startFrame = (loopStart * clip.sampleRate).toDouble()
                position = if (startFrame < endFrame) startFrame else 0.0
            } else {
                playing = false
                return false
            }
        }
        return true
    }

    internal fun currentGain(): Float {
        var g = volume
        if (fadeIn > 0f) g *= MathUtil.clamp01(fadeElapsed / fadeIn)
        if (fadeOut > 0f) g *= 1f - MathUtil.clamp01(fadeOutElapsed / fadeOut)
        return g
    }
}

/** Bus snapshot used by the editor's audio mixer panel. */
class MixerMeter {
    var peakLeft: Float = 0f
    var peakRight: Float = 0f
    var rms: Float = 0f
    fun decay(factor: Float = 0.85f) {
        peakLeft *= factor; peakRight *= factor; rms *= factor
    }
}

/**
 * The mixer. [mix] fills an interleaved interleaved stereo float buffer; the audio backend
 * then converts it to 16-bit and hands it to the device. Voices, buses and effects all run
 * here, which keeps audio identical across platforms and fully testable offline.
 */
class AudioMixer(val sampleRate: Int = 44100, val channels: Int = 2) {

    val master = AudioBus("Master")
    private val buses: LinkedHashMap<String, AudioBus> = linkedMapOf("Master" to master)

    private val voices = ArrayList<Voice>(32)
    private val backgroundMusic = LinkedHashMap<String, Voice>()

    var masterVolume: Float = 1f
    var muted: Boolean = false
    var maxVoices: Int = 32
    /** Global pitch/time scale (music slows down when the game pauses). */
    var speed: Float = 1f

    val meters = HashMap<String, MixerMeter>()
    var activeVoiceCount: Int = 0
        private set
    private var silenceFrames = 0

    init {
        master.addEffect(LimiterEffect())
        master.prepare(sampleRate, channels)
        for (name in listOf("Master", "Music", "SFX", "UI", "Ambient")) meters[name] = MixerMeter()
    }

    // ------------------------------------------------------------------------- buses

    /** Mixer state (buses, volumes, mutes) for project settings and save games. */
    fun serializeState(): Map<String, Any?> = linkedMapOf(
        "masterVolume" to masterVolume, "muted" to muted, "speed" to speed,
        "buses" to buses.values.map { bus ->
            linkedMapOf<String, Any?>("name" to bus.name, "volume" to bus.volume, "muted" to bus.muted)
        },
    )

    fun loadState(data: Map<String, Any?>) {
        (data["masterVolume"] as? Number)?.let { masterVolume = it.toFloat() }
        (data["muted"] as? Boolean)?.let { muted = it }
        (data["speed"] as? Number)?.let { speed = it.toFloat() }
        (data["buses"] as? List<*>)?.forEach { raw ->
            val map = raw as? Map<*, *> ?: return@forEach
            val name = map["name"]?.toString() ?: return@forEach
            val b = bus(name)
            (map["volume"] as? Number)?.let { b.volume = it.toFloat() }
            (map["muted"] as? Boolean)?.let { b.muted = it }
        }
    }

    /** Voices currently producing sound (used by the game loop to skip silent mixing). */
    val voiceCount: Int get() = voices.count { it.playing }
    val playing: Boolean get() = voiceCount > 0

    fun bus(name: String): AudioBus = buses.getOrPut(name) { AudioBus(name, 1f, false).also {
        it.parent = master; it.prepare(sampleRate, channels)
    } }

    fun buses(): Collection<AudioBus> = buses.values
    fun busNames(): List<String> = buses.keys.toList()

    fun setBusVolume(name: String, volume: Float) { bus(name).volume = MathUtil.clamp01(volume) }
    fun setBusMuted(name: String, muted: Boolean) { bus(name).muted = muted }

    fun loadMixFromJson(data: Map<String, Any?>) {
        (data["masterVolume"] as? Number)?.let { masterVolume = it.toFloat() }
        (data["muted"] as? Boolean)?.let { muted = it }
        (data["buses"] as? List<*>)?.forEach { entry ->
            val map = entry as? Map<*, *> ?: return@forEach
            val name = map["name"]?.toString() ?: return@forEach
            val b = bus(name)
            (map["volume"] as? Number)?.let { b.volume = it.toFloat() }
            (map["muted"] as? Boolean)?.let { b.muted = it }
            b.effects.clear()
            (map["effects"] as? List<*>)?.forEach { effectData ->
                (effectData as? Map<*, *>)?.let { d ->
                    @Suppress("UNCHECKED_CAST")
                    effectFromJson(d as Map<String, Any?>)?.let { b.addEffect(it) }
                }
            }
            b.prepare(sampleRate, channels)
        }
    }

    fun mixToJson(): Map<String, Any?> = mapOf(
        "masterVolume" to masterVolume.toDouble(),
        "muted" to muted,
        "buses" to buses.values.filter { it !== master }.map { it.serialize() },
    )

    private fun effectFromJson(data: Map<String, Any?>): AudioEffect? = when (data["type"] as? String) {
        "gain" -> GainEffect((data["gain"] as? Number)?.toFloat() ?: 1f)
        "lowpass" -> LowPassEffect((data["cutoff"] as? Number)?.toFloat() ?: 2000f, (data["resonance"] as? Number)?.toFloat() ?: 0.7f)
        "highpass" -> HighPassEffect((data["cutoff"] as? Number)?.toFloat() ?: 200f)
        "echo" -> EchoEffect(
            (data["delay"] as? Number)?.toFloat() ?: 0.28f,
            (data["feedback"] as? Number)?.toFloat() ?: 0.35f,
            (data["mix"] as? Number)?.toFloat() ?: 0.3f)
        "reverb" -> ReverbEffect(
            (data["room"] as? Number)?.toFloat() ?: 0.6f,
            (data["damping"] as? Number)?.toFloat() ?: 0.4f,
            (data["mix"] as? Number)?.toFloat() ?: 0.25f)
        "distortion" -> DistortionEffect((data["drive"] as? Number)?.toFloat() ?: 4f, (data["level"] as? Number)?.toFloat() ?: 0.7f)
        "bitcrush" -> BitCrushEffect((data["bits"] as? Number)?.toInt() ?: 8, (data["divisor"] as? Number)?.toInt() ?: 2)
        "limiter" -> LimiterEffect((data["ceiling"] as? Number)?.toFloat() ?: 0.98f)
        else -> null
    }

    // ------------------------------------------------------------------------ voices

    fun play(
        clip: AudioClip,
        busName: String = "SFX",
        volume: Float = 1f,
        pitch: Float = 1f,
        pan: Float = 0f,
        loop: Boolean = false,
        tag: String = "",
        fadeIn: Float = 0f,
    ): Voice? {
        if (muted || masterVolume <= 0f) return null
        // Voice stealing: drop the oldest finished/stealable voice when at budget.
        if (voices.size >= maxVoices) {
            val victim = voices.firstOrNull { !it.playing } ?: voices.firstOrNull { it.stealable && it.bus !== master }
            if (victim != null) voices.remove(victim)
        }
        val voice = Voice(clip, bus(busName), volume, pitch, pan, loop).also { it.tag = tag; it.fadeIn = fadeIn }
        voices.add(voice)
        return voice
    }

    fun playMusic(clip: AudioClip, busName: String = "Music", volume: Float = 0.8f, loop: Boolean = true, fadeIn: Float = 0.6f): Voice? {
        playMusicNamed("main", clip, busName, volume, loop, fadeIn)
        return backgroundMusic["main"]
    }

    fun playMusicNamed(key: String, clip: AudioClip, busName: String = "Music", volume: Float = 0.8f, loop: Boolean = true, fadeIn: Float = 0.6f): Voice? {
        // Crossfade out the previous track on the same slot.
        backgroundMusic[key]?.stop(fadeSeconds = 0.8f)
        val voice = play(clip, busName, volume, 1f, 0f, loop, tag = "music:$key", fadeIn = fadeIn) ?: return null
        voice.stealable = false
        backgroundMusic[key] = voice
        return voice
    }

    fun stopMusic(key: String = "main", fadeSeconds: Float = 0.8f) {
        backgroundMusic[key]?.stop(fadeSeconds)
        backgroundMusic.remove(key)
    }

    fun stopTag(tag: String, fadeSeconds: Float = 0f) {
        voices.filter { it.tag == tag }.forEach { it.stop(fadeSeconds) }
    }

    fun stopAll(fadeSeconds: Float = 0f) { voices.forEach { it.stop(fadeSeconds) } }

    fun isPlayingTag(tag: String): Boolean = voices.any { it.tag == tag && it.playing }

    fun voicesSnapshot(): List<Voice> = voices.toList()

    fun pauseAll() { voices.forEach { it.playing = false } }
    /** Pause without ending: keeps positions so playback can resume (menu pause). */
    fun setPaused(paused: Boolean) { pausedFlag = paused }
    private var pausedFlag = false

    /** Ducks a bus for [seconds] (used for dramatic moments and announcer lines). */
    fun duck(name: String, amount: Float = 0.35f, seconds: Float = 1.5f) {
        val bus = bus(name)
        bus.duck = MathUtil.clamp01(amount)
        duckTimers[bus] = seconds
    }
    private val duckTimers = HashMap<AudioBus, Float>()

    // --------------------------------------------------------------------- processing

    /** Advances voice bookkeeping (call once per frame on the main thread). */
    fun update(delta: Float) {
        val deltaSeconds = delta * speed
        val iterator = voices.iterator()
        while (iterator.hasNext()) {
            val voice = iterator.next()
            if (!voice.playing) { voice.onClickFinished?.invoke(); iterator.remove(); continue }
            if (pausedFlag) continue
            voice.advance(deltaSeconds)
            if (!voice.playing) { voice.onClickFinished?.invoke() }
        }
        activeVoiceCount = voices.size
        // Duck release.
        val duckIterator = duckTimers.entries.iterator()
        while (duckIterator.hasNext()) {
            val entry = duckIterator.next()
            entry.setValue(entry.value - delta)
            if (entry.value <= 0f) { entry.key.duck = 1f; duckIterator.remove() }
        }
        for (meter in meters.values) meter.decay()
    }

    /**
     * Fills [buffer] with interleaved stereo samples (values in -1..1).
     * Called from the audio thread; safe because it only touches voice state with simple math.
     */
    fun mix(buffer: FloatArray, frames: Int) {
        java.util.Arrays.fill(buffer, 0, frames * channels, 0f)
        if (muted) return
        val busBuffers = HashMap<String, FloatArray>()
        val masterVolumeValue = masterVolume

        for (voice in voices) {
            if (!voice.playing || pausedFlag) continue
            val gain = voice.currentGain() * voice.bus.effectiveVolume() * masterVolumeValue
            if (gain <= 0.0001f) continue
            val startFrame = voice.position.toInt()
            val pitch = voice.pitch
            val clip = voice.clip
            val panLeft = if (voice.pan <= 0f) 1f else 1f - voice.pan
            val panRight = if (voice.pan >= 0f) 1f else 1f + voice.pan

            val target = if (voice.bus === master) null else busBuffers.getOrPut(voice.bus.name) {
                FloatArray(frames * channels)
            }

            for (frame in 0 until frames) {
                var sourceFrame = startFrame + (frame * pitch).toInt()
                if (sourceFrame >= clip.frameCount) {
                    if (!voice.loop) break
                    sourceFrame %= clip.frameCount
                }
                val left = clip.sampleAt(sourceFrame, 0) * gain * panLeft
                val right = (if (clip.channels > 1) clip.sampleAt(sourceFrame, 1) else clip.sampleAt(sourceFrame, 0)) * gain * panRight
                if (target == null) {
                    buffer[frame * channels] += left
                    if (channels > 1) buffer[frame * channels + 1] += right
                } else {
                    target[frame * channels] += left
                    if (channels > 1) target[frame * channels + 1] += right
                }
            }
        }

        // Process non-master buses (effects + volume already applied per voice) into master,
        // then run the master chain's effects.
        for ((name, data) in busBuffers) {
            val bus = buses[name] ?: continue
            bus.process(data, frames, channels)
            for (i in 0 until frames * channels) buffer[i] += data[i]
            val meter = meters[name]
            if (meter != null) updateMeter(meter, data, frames)
        }
        master.process(buffer, frames, channels)

        val masterMeter = meters["Master"]
        if (masterMeter != null) updateMeter(masterMeter, buffer, frames)

        // Silence detection keeps the backend from spinning when nothing is audible.
        silenceFrames = if (voices.isEmpty()) silenceFrames + frames else 0
    }

    private fun updateMeter(meter: MixerMeter, buffer: FloatArray, frames: Int) {
        var peakL = 0f; var peakR = 0f; var sum = 0f
        for (frame in 0 until frames) {
            val l = abs(buffer[frame * channels]); val r = abs(buffer[frame * channels + 1])
            if (l > peakL) peakL = l
            if (r > peakR) peakR = r
            sum += (l + r) * 0.5f
        }
        meter.peakLeft = max(meter.peakLeft, peakL)
        meter.peakRight = max(meter.peakRight, peakR)
        meter.rms = max(meter.rms, sum / max(1, frames))
    }

    val isSilent: Boolean get() = silenceFrames > sampleRate / 2

    fun reset() {
        voices.clear(); backgroundMusic.clear(); duckTimers.clear()
        buses.values.forEach { it.effects.forEach { e -> e.reset() } }
    }
}

/**
 * Convenience helper that keeps a mixer fed from a platform [dev.lumen2d.core.platform.AudioOutput].
 * Backends create one and call [pump] from their audio thread.
 */
class AudioPump(
    private val mixer: AudioMixer,
    private val output: dev.lumen2d.core.platform.AudioOutput,
    private val framesPerBuffer: Int = 1024,
) {
    private val floatBuffer = FloatArray(framesPerBuffer * mixer.channels)
    private val shortBuffer = ShortArray(framesPerBuffer * mixer.channels)
    @Volatile private var running = false

    fun start() {
        running = true
        output.start()
        Log.i("AudioPump", "Audio started: ${output.sampleRate} Hz, ${output.channels} ch, latency ${output.latencyMillis}ms")
    }

    fun stop() { running = false; output.stop() }

    /** Produces one buffer; call repeatedly from the audio thread. */
    fun pump() {
        if (!running) return
        mixer.mix(floatBuffer, framesPerBuffer)
        for (i in shortBuffer.indices) {
            val v = floatBuffer[i]
            shortBuffer[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        output.write(shortBuffer, framesPerBuffer)
    }

    /** Offline render (used by tests and by the WAV export tools). */
    fun renderOffline(seconds: Float): AudioClip {
        val frames = (seconds * mixer.sampleRate).toInt()
        val out = ShortArray(frames * mixer.channels)
        val chunk = 1024
        val fb = FloatArray(chunk * mixer.channels)
        var written = 0
        while (written < frames) {
            val count = min(chunk, frames - written)
            mixer.mix(fb, count)
            for (i in 0 until count * mixer.channels) {
                out[written * mixer.channels + i] = (fb[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            written += count
        }
        return AudioClip("offline", mixer.sampleRate, mixer.channels, out)
    }
}
