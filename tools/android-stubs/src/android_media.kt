// Android framework stubs — android.media (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.media

open class AudioTrack {
    constructor(streamType: Int, sampleRateInHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int, mode: Int)
    constructor(attributes: AudioAttributes, format: AudioFormat, bufferSizeInBytes: Int, mode: Int, sessionId: Int)
    val state: Int get() = STATE_INITIALIZED
    val playState: Int get() = PLAYSTATE_STOPPED
    val playbackHeadPosition: Int get() = 0
    var stereoVolume: Float = 1f
    var volume: Float = 1f
    fun play() {}
    fun stop() {}
    fun pause() {}
    fun flush() {}
    fun release() {}
    fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int = sizeInShorts
    fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, writeMode: Int): Int = sizeInShorts
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = sizeInBytes
    fun setPlaybackHeadPosition(positionInFrames: Int): Int = 0
    companion object {
        const val MODE_STREAM = 1
        const val MODE_STATIC = 0
        const val STATE_INITIALIZED = 1
        const val STATE_UNINITIALIZED = 0
        const val PLAYSTATE_STOPPED = 1
        const val PLAYSTATE_PLAYING = 3
        const val WRITE_BLOCKING = 0
        const val WRITE_NON_BLOCKING = 1
        const val ERROR = -1
        const val SUCCESS = 0
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
        fun getMaxVolume(): Float = 1f
        fun getMinVolume(): Float = 0f
    }
}

class AudioFormat {
    constructor(sampleRate: Int, channelMask: Int, encoding: Int)
    companion object {
        const val ENCODING_PCM_8BIT = 3
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
        const val CHANNEL_OUT_MONO = 4
        const val CHANNEL_OUT_STEREO = 12
        const val CHANNEL_IN_MONO = 16
        const val CHANNEL_IN_STEREO = 12
        const val SAMPLE_RATE_HZ_MIN = 4000
        const val SAMPLE_RATE_HZ_MAX = 192000
    }
}

class AudioAttributes {
    val usage: Int get() = 0
    val contentType: Int get() = 0
    class Builder {
        fun setUsage(usage: Int): Builder = this
        fun setContentType(contentType: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }
    companion object {
        const val USAGE_GAME = 14
        const val USAGE_MEDIA = 1
        const val CONTENT_TYPE_SONIFICATION = 4
        const val CONTENT_TYPE_MUSIC = 2
    }
}

open class AudioManager {
    open fun getStreamVolume(streamType: Int): Int = 0
    open fun getStreamMaxVolume(streamType: Int): Int = 0
    open fun setStreamVolume(streamType: Int, index: Int, flags: Int) {}
    open fun requestAudioFocus(listener: Any?, streamType: Int, durationHint: Int): Int = 1
    open fun abandonAudioFocus(listener: Any?): Int = 0
    companion object {
        const val STREAM_MUSIC = 3
        const val STREAM_ALARM = 4
        const val STREAM_SYSTEM = 1
        const val ADJUST_RAISE = 1
        const val ADJUST_LOWER = -1
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
    }
}

open class MediaPlayer {
    companion object {
        fun create(context: android.content.Context, resId: Int): MediaPlayer = MediaPlayer()
    }
    var isLooping: Boolean = false
    var volume: Float = 1f
    fun setDataSource(path: String) {}
    fun prepare() {}
    fun start() {}
    fun stop() {}
    fun pause() {}
    fun release() {}
}

class SoundPool
