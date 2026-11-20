package app.stemwise.playback.engine

import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Trace
import android.util.Log
import app.stemwise.audioengine.BuildConfig
import java.nio.ByteBuffer

/** Requests low latency tuning and retains the platform's measured buffer and threshold. */
internal class AndroidAudioTrackSink(
    bufferFrames: Int = DEFAULT_BUFFER_FRAMES,
    preferredDevice: AudioDeviceInfo? = null,
    private val detailedCaptureRequested: () -> Boolean = { false }
) : NativeAudioTrackFeeder.Sink {
    companion object { const val DEFAULT_BUFFER_FRAMES = 2_048 }
    init { require(bufferFrames >= NativeAudioTrackFeeder.BLOCK_FRAMES) }

    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(NativeAudioTrackFeeder.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .setBufferSizeInBytes(Math.multiplyExact(bufferFrames, NativeAudioTrackFeeder.BYTES_PER_FRAME))
        .build()
    val routedDevice: AudioDeviceInfo? get() = track.routedDevice
    private val timestamp = AudioTimestamp()
    private val framePosition = AudioTrackFramePosition()
    private var submittedTrackFrames = 0L
    private var nextStartCase = "fresh"
    override val configuration: NativeAudioTrackFeeder.Configuration

    init {
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialize" }
            check(track.setBufferSizeInFrames(bufferFrames) > 0) {
                "AudioTrack did not accept the requested $bufferFrames-frame buffer"
            }
            check(track.setStartThresholdInFrames(NativeAudioTrackFeeder.BLOCK_FRAMES) > 0) { "AudioTrack did not accept the 512-frame threshold" }
            configuration = NativeAudioTrackFeeder.Configuration(
                sampleRate = track.sampleRate,
                channels = track.channelCount,
                encoding = track.audioFormat,
                bufferFrames = track.bufferSizeInFrames,
                capacityFrames = track.bufferCapacityInFrames,
                startThresholdFrames = track.startThresholdInFrames,
                performanceMode = track.performanceMode,
                // Java AudioTrack exposes no actual burst-size observation.
                framesPerBurst = -1
            )
            check(configuration.sampleRate == NativeAudioTrackFeeder.SAMPLE_RATE &&
                configuration.channels == NativeAudioTrackFeeder.CHANNELS &&
                configuration.encoding == AudioFormat.ENCODING_PCM_FLOAT &&
                configuration.bufferFrames in NativeAudioTrackFeeder.BLOCK_FRAMES..NativeAudioTrackFeeder.MAXIMUM_BUFFER_FRAMES &&
                configuration.bufferFrames <= configuration.capacityFrames &&
                configuration.startThresholdFrames in 1..configuration.bufferFrames &&
                (configuration.performanceMode == AudioTrack.PERFORMANCE_MODE_NONE ||
                    configuration.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)) {
                "AudioTrack selected an unsupported format/buffer/threshold/performance mode"
            }
            if (preferredDevice != null) check(track.setPreferredDevice(preferredDevice)) { "AudioTrack rejected the selected route" }
            traceBoundary()
        } catch (error: Throwable) {
            track.release()
            throw error
        }
    }

    override fun write(pcm: ByteBuffer): Int {
        val tracing = BuildConfig.BUILD_TYPE == "development" && Trace.isEnabled()
        if (tracing) Trace.beginSection("StemWise.audio.track.write")
        try {
            val bytes = track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (bytes > 0 && bytes % NativeAudioTrackFeeder.BYTES_PER_FRAME == 0)
                submittedTrackFrames += bytes / NativeAudioTrackFeeder.BYTES_PER_FRAME
            return bytes
        } finally { if (tracing) Trace.endSection() }
    }
    override fun play() {
        val tracing = BuildConfig.BUILD_TYPE == "development" && Trace.isEnabled()
        if (tracing) Trace.beginSection("StemWise.audio.track.platform_play")
        try {
            traceBoundary()
            if (BuildConfig.BUILD_TYPE == "development" && detailedCaptureRequested()) {
                val head = framePosition.head(track.playbackHeadPosition.toLong() and 0xffff_ffffL)
                Log.i("StemWiseAudioTrack", "session=${track.audioSessionId} case=$nextStartCase " +
                    "configuration=$configuration acceptedTrackFrames=$submittedTrackFrames " +
                    "queuedHeadEstimate=${submittedTrackFrames - head} rawUnderruns=${track.underrunCount} " +
                    "routeId=${track.routedDevice?.id} routeType=${track.routedDevice?.type}")
            }
            track.play()
            nextStartCase = "resume"
            traceBoundary()
        } finally { if (tracing) Trace.endSection() }
    }
    override fun pause() {
        val tracing = BuildConfig.BUILD_TYPE == "development" && Trace.isEnabled()
        if (tracing) Trace.beginSection("StemWise.audio.track.platform_pause")
        try { traceBoundary(); track.pause(); traceBoundary() }
        finally { if (tracing) Trace.endSection() }
    }
    override fun observe(actual: IntArray, position: LongArray) {
        val tracing = BuildConfig.BUILD_TYPE == "development" && Trace.isEnabled()
        if (tracing) Trace.beginSection("StemWise.audio.track.platform_observation")
        try {
            val playState = track.playState
            actual[NativePacketSchema.TRACK_ACTUAL_STATE] = when (playState) {
                AudioTrack.PLAYSTATE_PLAYING -> NativeAudioStreamState.STARTED.rawValue
                AudioTrack.PLAYSTATE_PAUSED -> NativeAudioStreamState.PAUSED.rawValue
                AudioTrack.PLAYSTATE_STOPPED -> NativeAudioStreamState.OPEN.rawValue
                else -> NativeAudioStreamState.UNKNOWN.rawValue
            }
            actual[NativePacketSchema.TRACK_ACTUAL_BUFFER] = configuration.bufferFrames
            actual[NativePacketSchema.TRACK_ACTUAL_CAPACITY] = configuration.capacityFrames
            actual[NativePacketSchema.TRACK_ACTUAL_RATE] = configuration.sampleRate
            actual[NativePacketSchema.TRACK_ACTUAL_PERFORMANCE] = configuration.performanceMode
            actual[NativePacketSchema.TRACK_ACTUAL_UNDERRUNS] = track.underrunCount
            val rawHead = track.playbackHeadPosition.toLong() and 0xffff_ffffL
            position[NativePacketSchema.TRACK_POSITION_HEAD] = framePosition.head(rawHead)
            traceObservation(actual[NativePacketSchema.TRACK_ACTUAL_UNDERRUNS], rawHead, playState)
            if (track.getTimestamp(timestamp) && timestamp.nanoTime > 0L) {
                position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_FRAME] = framePosition.timestamp(timestamp.framePosition, position[NativePacketSchema.TRACK_POSITION_HEAD])
                position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_NANOS] = timestamp.nanoTime
            }
        } finally { if (tracing) Trace.endSection() }
    }

    override fun close() {
        val tracing = BuildConfig.BUILD_TYPE == "development" && Trace.isEnabled()
        if (tracing) Trace.beginSection("StemWise.audio.track.platform_close")
        try { traceBoundary(); track.release() }
        finally { if (tracing) Trace.endSection() }
    }

    private fun traceBoundary() {
        if (BuildConfig.BUILD_TYPE == "development" && Trace.isEnabled()) traceObservation(track.underrunCount,
            track.playbackHeadPosition.toLong() and 0xffff_ffffL, track.playState)
    }

    private fun traceObservation(rawUnderruns: Int, rawHead: Long, playState: Int) {
        if (BuildConfig.BUILD_TYPE != "development" || !Trace.isEnabled()) return
        val route = track.routedDevice
        Trace.setCounter("StemWise.audio.track.platform_session", track.audioSessionId.toLong())
        Trace.setCounter("StemWise.audio.track.platform_route_id", route?.id?.toLong() ?: 0L)
        Trace.setCounter("StemWise.audio.track.platform_route_type", route?.type?.toLong() ?: 0L)
        Trace.setCounter("StemWise.audio.track.platform_raw_xruns", rawUnderruns.toLong())
        Trace.setCounter("StemWise.audio.track.platform_raw_head", rawHead)
        Trace.setCounter("StemWise.audio.track.platform_submitted", submittedTrackFrames)
        Trace.setCounter("StemWise.audio.track.platform_play_state", playState.toLong())
    }
}

/** AudioTrack's unsigned 32-bit playback head is continuous for one physical track instance. */
internal class AudioTrackFramePosition {
    private var previous = 0L
    private var wrapBase = 0L

    fun head(raw: Long): Long {
        require(raw in 0L..MASK)
        if (raw < previous) {
            check(previous - raw > HALF_RANGE) { "AudioTrack playback head reset within a physical track instance" }
            wrapBase += MODULUS
        }
        previous = raw
        return wrapBase + raw
    }

    fun timestamp(raw: Long, head: Long): Long {
        if (raw < 0L || raw > MASK) return raw
        var extended = (head and MASK.inv()) + raw
        if (extended - head > HALF_RANGE) extended -= MODULUS
        else if (head - extended > HALF_RANGE) extended += MODULUS
        return extended
    }

    private companion object {
        const val MASK = 0xffff_ffffL
        const val MODULUS = 0x1_0000_0000L
        const val HALF_RANGE = 0x8000_0000L
    }
}
