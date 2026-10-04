/**
 * Lumen2D — Android audio sink.
 *
 * Streams the mixer's 16-bit PCM frames into an [AudioTrack] in streaming mode. The same
 * [dev.lumen2d.core.platform.AudioOutput] contract is implemented by JavaSound on the desktop, so
 * the mixer, buses and mixing code are identical on both platforms.
 *
 * The engine never blocks the game loop on audio: the track is opened with a buffer of
 * [bufferMillis] and a failed write is dropped rather than retried, which keeps the frame time
 * stable on devices with a busy audio HAL. No `AudioTrack` at all (some emulators) leaves a silent
 * sink, exactly like a headless CI run.
 */
package dev.lumen2d.android

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.lumen2d.core.platform.AudioOutput
import dev.lumen2d.core.util.Log

class AndroidAudioOutput(
    override val sampleRate: Int,
    override val channels: Int,
    /** Approximate buffer latency in milliseconds. */
    private val bufferMillis: Int = 60,
) : AudioOutput {

    private var track: AudioTrack? = null
    private var failures = 0

    override val latencyMillis: Int get() = bufferMillis

    /** True once the underlying [AudioTrack] reported a healthy state. */
    val isOpen: Boolean get() = track?.state == AudioTrack.STATE_INITIALIZED

    /** Frames written since [start]; shown in the studio's diagnostics panel. */
    var framesWritten: Long = 0L
        private set

    override fun start() {
        if (track != null) return
        val channelMask = if (channels > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBytes = (sampleRate * channels * 2 / 1000) * bufferMillis
        runCatching {
            val bufferBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                .let { if (it > minBytes) it else minBytes }
                .coerceAtLeast(2048)
            @Suppress("DEPRECATION")
            val created = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STREAM,
            )
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                Log.w("Audio", "AudioTrack could not be initialised — running silent")
                return
            }
            created.play()
            track = created
        }.onFailure {
            Log.w("Audio", "Audio output unavailable: ${it.message}")
        }
    }

    override fun write(buffer: ShortArray, samples: Int) {
        val target = track ?: return
        val count = samples.coerceAtMost(buffer.size)
        if (count <= 0) return
        runCatching {
            // WRITE_BLOCKING is what keeps the mixer in step with the device clock, but a full
            // buffer must not stall the game loop: if the track is busy the frame is dropped.
            val written = target.write(buffer, 0, count, AudioTrack.WRITE_NON_BLOCKING)
            if (written > 0) {
                framesWritten += written / channels.coerceAtLeast(1)
            } else {
                failures++
            }
        }.onFailure {
            failures++
        }
    }

    override fun stop() {
        val target = track ?: return
        runCatching { target.stop() }
        runCatching { target.flush() }
        runCatching { target.release() }
        track = null
    }

    /** Number of dropped writes, surfaced in the diagnostics panel. */
    val droppedWrites: Int get() = failures
}
