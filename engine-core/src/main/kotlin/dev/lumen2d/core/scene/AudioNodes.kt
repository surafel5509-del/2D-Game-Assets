/**
 * Lumen2D — audio nodes.
 *
 * [AudioPlayerNode] plays sounds and music on the mixer bus system; [AudioPlayer2DNode] adds
 * positional behaviour (distance falloff + stereo panning around the camera).
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.audio.AudioClip
import dev.lumen2d.core.audio.Voice
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.util.Signal

/** Non-positional sound/music player. */
open class AudioPlayerNode(name: String = "AudioPlayer") : Node(name) {
    var clipId: String = ""
    var bus: String = "SFX"
    var volume: Float = 1f
    var pitch: Float = 1f
    var loop: Boolean = false
    var autoplay: Boolean = false
    var fadeIn: Float = 0f

    private var voice: Voice? = null

    val finished = Signal<Unit>()
    /** Set by the runtime so scripts can ask whether this voice is still audible. */
    val isPlaying: Boolean get() = voice?.playing == true

    override fun onReady() { if (autoplay) play() }

    fun play(clip: String = clipId, fromSeconds: Float = 0f): Voice? {
        val mixer = tree?.resources?.audio ?: return null
        val audio = tree?.resources?.audioClip(clip) ?: return null
        stop()
        val v = mixer.play(audio, bus, volume, pitch, 0f, loop, tag = "player:${instanceId}", fadeIn = fadeIn)
        v?.startOffset = fromSeconds
        v?.position = (fromSeconds * audio.sampleRate).toDouble()
        v?.onClickFinished = { finished.emit(Unit) }
        voice = v
        return v
    }

    fun playMusic(clip: String = clipId, key: String = "main"): Voice? {
        val mixer = tree?.resources?.audio ?: return null
        val audio = tree?.resources?.audioClip(clip) ?: return null
        val v = mixer.playMusicNamed(key, audio, bus, volume, loop, fadeIn)
        voice = v
        return v
    }

    fun stop(fadeSeconds: Float = 0.1f) {
        voice?.stop(fadeSeconds)
        voice = null
    }

    fun setVolumeDb(db: Float) { volume = db; voice?.volume = db }

    /** Convenience one-shot helper used by scripts: `play_sfx("jump")`. */
    fun playOneShot(clip: String, gain: Float = 1f, pitchScale: Float = 1f): Voice? {
        val mixer = tree?.resources?.audio ?: return null
        val audio = tree?.resources?.audioClip(clip) ?: return null
        return mixer.play(audio, bus, volume * gain, pitch * pitchScale, 0f, false, tag = "oneshot")
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("clipId", PropertyType.AUDIO, "Sound", "", category = "Audio", hint = "asset:audio"),
        PropertyDef("bus", PropertyType.ENUM, "Bus", "SFX", category = "Audio",
            enumValues = listOf("Master", "Music", "SFX", "UI", "Ambient")),
        PropertyDef("volume", PropertyType.FLOAT, "Volume", 1f, min = 0f, max = 2f, step = 0.05f, category = "Audio"),
        PropertyDef("pitch", PropertyType.FLOAT, "Pitch", 1f, min = 0.25f, max = 4f, step = 0.05f, category = "Audio"),
        PropertyDef("loop", PropertyType.BOOL, "Loop", false, category = "Audio"),
        PropertyDef("autoplay", PropertyType.BOOL, "Autoplay", false, category = "Audio"),
        PropertyDef("fadeIn", PropertyType.FLOAT, "Fade in", 0f, min = 0f, max = 10f, step = 0.05f, category = "Audio"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "clipId" -> clipId
        "bus" -> bus
        "volume" -> volume
        "pitch" -> pitch
        "loop" -> loop
        "autoplay" -> autoplay
        "fadeIn" -> fadeIn
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "clipId" -> { clipId = value?.toString() ?: ""; return true }
            "bus" -> { bus = value?.toString() ?: "SFX"; return true }
            "volume" -> { volume = asFloat(value, 1f); voice?.volume = volume; return true }
            "pitch" -> { pitch = asFloat(value, 1f); voice?.pitch = pitch; return true }
            "loop" -> { loop = asBool(value, false); return true }
            "autoplay" -> { autoplay = asBool(value, false); return true }
            "fadeIn" -> { fadeIn = asFloat(value, 0f); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Positional player: volume and pan follow the distance to the active camera. */
open class AudioPlayer2DNode(name: String = "AudioPlayer2D") : Node2D(name) {
    var clipId: String = ""
    var bus: String = "SFX"
    var volume: Float = 1f
    var pitch: Float = 1f
    var loop: Boolean = false
    var autoplay: Boolean = false
    var maxDistance: Float = 600f
    var attenuation: Float = 1.6f

    private var voice: Voice? = null

    override fun onReady() { if (autoplay) play() }

    fun play(clip: String = clipId): Voice? {
        val mixer = tree?.resources?.audio ?: return null
        val audio = tree?.resources?.audioClip(clip) ?: return null
        stop()
        voice = mixer.play(audio, bus, volume, pitch, 0f, loop, tag = "player2d:${instanceId}").also {
            it?.stealable = true
        }
        updateSpatial()
        return voice
    }

    fun stop(fadeSeconds: Float = 0.1f) { voice?.stop(fadeSeconds); voice = null }

    private fun updateSpatial() {
        val v = voice ?: return
        val camera = tree?.activeCamera?.camera
        val distance = if (camera != null) globalPosition.distanceTo(camera.position) else 0f
        val falloff = (1f - (distance / maxDistance)).coerceIn(0f, 1f)
        v.volume = volume * falloff * falloff
        if (camera != null) {
            v.pan = MathUtil.clamp((globalPosition.x - camera.position.x) / (maxDistance * 0.5f), -1f, 1f)
        }
    }

    override fun onPhysicsProcess(delta: Float) { updateSpatial() }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("clipId", PropertyType.AUDIO, "Sound", "", category = "Audio", hint = "asset:audio"),
        PropertyDef("bus", PropertyType.ENUM, "Bus", "SFX", category = "Audio",
            enumValues = listOf("Master", "Music", "SFX", "UI", "Ambient")),
        PropertyDef("volume", PropertyType.FLOAT, "Volume", 1f, min = 0f, max = 2f, step = 0.05f, category = "Audio"),
        PropertyDef("pitch", PropertyType.FLOAT, "Pitch", 1f, min = 0.25f, max = 4f, step = 0.05f, category = "Audio"),
        PropertyDef("loop", PropertyType.BOOL, "Loop", false, category = "Audio"),
        PropertyDef("autoplay", PropertyType.BOOL, "Autoplay", false, category = "Audio"),
        PropertyDef("maxDistance", PropertyType.FLOAT, "Max distance", 600f, min = 32f, max = 4096f, step = 8f, category = "Audio"),
        PropertyDef("attenuation", PropertyType.FLOAT, "Attenuation", 1.6f, min = 0.2f, max = 4f, step = 0.1f, category = "Audio"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "clipId" -> clipId
        "bus" -> bus
        "volume" -> volume
        "pitch" -> pitch
        "loop" -> loop
        "autoplay" -> autoplay
        "maxDistance" -> maxDistance
        "attenuation" -> attenuation
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "clipId" -> { clipId = value?.toString() ?: ""; return true }
            "bus" -> { bus = value?.toString() ?: "SFX"; return true }
            "volume" -> { volume = asFloat(value, 1f); return true }
            "pitch" -> { pitch = asFloat(value, 1f); voice?.pitch = pitch; return true }
            "loop" -> { loop = asBool(value, false); return true }
            "autoplay" -> { autoplay = asBool(value, false); return true }
            "maxDistance" -> { maxDistance = asFloat(value, 600f); return true }
            "attenuation" -> { attenuation = asFloat(value, 1.6f); return true }
        }
        return super.setProperty(property, value)
    }
}
