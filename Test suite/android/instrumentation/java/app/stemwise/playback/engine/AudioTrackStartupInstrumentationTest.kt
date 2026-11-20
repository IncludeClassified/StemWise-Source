package app.stemwise.playback.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the current output buffer independently of StemWise's renderer. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class AudioTrackStartupInstrumentationTest {
    @Test
    fun prefilledNormalBufferStartsResumesAndRestartsWithoutUnderruns() {
        val pcm = FloatArray(FRAMES * CHANNELS) { sample ->
            (.005 * sin(2.0 * PI * 375.0 * (sample / CHANNELS) / RATE)).toFloat()
        }
        repeat(25) { cycle ->
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(FRAMES * CHANNELS * Float.SIZE_BYTES)
                .build()
            try {
                assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
                assertEquals(RATE, track.sampleRate)
                assertEquals(CHANNELS, track.channelCount)
                assertEquals("Actual buffer must match the approved 2048-frame configuration",
                    FRAMES, track.setBufferSizeInFrames(FRAMES))
                assertTrue("Unsupported actual performance mode: ${track.performanceMode}",
                    track.performanceMode == AudioTrack.PERFORMANCE_MODE_NONE ||
                        track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                val threshold = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    track.setStartThresholdInFrames(START_THRESHOLD_FRAMES) else null
                if (threshold != null) assertEquals(START_THRESHOLD_FRAMES, threshold)
                assertEquals("A fresh track cannot inherit underruns", 0, track.underrunCount)
                for (phase in listOf("fresh", "resume", "flushed")) {
                    if (phase == "flushed") track.flush()
                    exercise(track, pcm, cycle, phase, threshold)
                }
            } finally {
                track.release()
            }
        }
    }

    private fun exercise(track: AudioTrack, pcm: FloatArray, cycle: Int, phase: String, threshold: Int?) {
        // Nonblocking writes permit priming a paused track without waiting for playback.
        var primed = 0
        val primeDeadline = SystemClock.elapsedRealtime() + 2_000L
        do {
            val written = track.write(pcm, primed, pcm.size - primed, AudioTrack.WRITE_NON_BLOCKING)
            assertTrue("Priming failed: $written", written >= 0 && written % CHANNELS == 0)
            primed += written
            if (phase == "resume" || primed == pcm.size) break
            // Flush acknowledgement can precede the server making its buffer writable.
            SystemClock.sleep(1L)
        } while (SystemClock.elapsedRealtime() < primeDeadline)
        if (phase != "resume") assertEquals("Empty output must accept the complete startup buffer", pcm.size, primed)
        val running = AtomicBoolean(true)
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val writer = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                ready.countDown()
                var offset = 0
                while (running.get()) {
                    val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0 && written % CHANNELS == 0) { "Output write failed: $written" }
                    if (written == 0) LockSupport.parkNanos(500_000L)
                    else offset = (offset + written) % pcm.size
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                ready.countDown()
            }
        }, "AudioTrackStartupWriter").apply { isDaemon = true }
        var head = frameHead(track)
        val startingHead = head
        val timestamp = AudioTimestamp()
        var firstTimestampFrame = -1L
        var firstTimestampNanos = -1L
        var timestampAdvanced = false
        try {
            writer.start()
            assertTrue("Output writer did not start", ready.await(2L, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Output writer failed", it) }
            track.play()
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                failure.get()?.let { throw AssertionError("Output writer failed", it) }
                head = frameHead(track)
                if (track.getTimestamp(timestamp) && timestamp.framePosition > startingHead && timestamp.nanoTime > 0L) {
                    if (firstTimestampFrame < 0L) {
                        firstTimestampFrame = timestamp.framePosition
                        firstTimestampNanos = timestamp.nanoTime
                    } else if (timestamp.framePosition > firstTimestampFrame && timestamp.nanoTime > firstTimestampNanos) {
                        timestampAdvanced = true
                    }
                }
                if (head - startingHead >= RATE / 4 && timestampAdvanced) break
                SystemClock.sleep(10L)
            }
        } finally {
            // Pause before retiring the writer so cleanup cannot manufacture an empty running track.
            try {
                track.pause()
            } finally {
                running.set(false)
                LockSupport.unpark(writer)
                writer.join(2_000L)
            }
        }
        assertTrue("Output writer failed to retire", !writer.isAlive)
        failure.get()?.let { throw AssertionError("Output writer failed", it) }
        val underruns = track.underrunCount
        Log.i("StemwiseAudioTrackProbe", "cycle=$cycle phase=$phase rate=${track.sampleRate} " +
            "channels=${track.channelCount} buffer=${track.bufferSizeInFrames} " +
            "capacity=${track.bufferCapacityInFrames} threshold=$threshold performance=${track.performanceMode} " +
            "primed=${primed / CHANNELS} frames=${head - startingHead} timestampAdvanced=$timestampAdvanced " +
            "underruns=$underruns")
        assertTrue("$cycle/$phase playback head did not advance", head - startingHead >= RATE / 4)
        assertTrue("$cycle/$phase physical timestamp did not advance", timestampAdvanced)
        assertEquals("$cycle/$phase output underruns", 0, underruns)
    }

    private fun frameHead(track: AudioTrack): Long = track.playbackHeadPosition.toLong() and 0xffff_ffffL

    private companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val FRAMES = 2_048
        const val START_THRESHOLD_FRAMES = 512
    }
}
