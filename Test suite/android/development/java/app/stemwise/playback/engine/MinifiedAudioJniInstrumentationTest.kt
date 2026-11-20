package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.inspectPreparedSourceAudio
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinifiedAudioJniInstrumentationTest {
    @Test fun publicSessionRunsDecoderJniOutputPauseSeekResumeAndStop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("minified-session-", ".wav", context.cacheDir)
        app.stemwise.integration.MergerOracleFixture.writeWave(file,
            IntArray(8 * 48_000 * 2) { index -> if (index % 2 == 0) 2_000 + index / 2 % 1_024 else -4_000 - index / 2 % 2_048 },
            bits = 16, channels = 2)
        val sha = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val session = NativeAudioPlaybackSessionAdapter.create(context,
            packetReaderFactory = AudioPacketReaderFactory { _, _ -> null })
        try {
            val token = requireNotNull(session.trySelect(
                NativeEngineSelectionRequest("minified", "fixture-v1", file.absolutePath, 8_000,
                    startPositionMs = 0, autoplay = true, forceReload = false, originalContentSha256 = sha),
                PlaybackPublicationMetadata("minified", "fixture-v1", 1, "minified")))
            await("minified exact source reaches real presentation") {
                session.snapshot().publication.committed?.selection?.token == token &&
                    session.diagnosticsSnapshot()?.output?.lastPresentedSelectionToken == token.value
            }
            val lease = requireNotNull(session.acquireCommandLease("minified"))
            assertNotNull(session.setPlayWhenReadyIfCurrent(token, false))
            await("minified pause reaches actual sink") { session.diagnosticsSnapshot()?.output?.playing == false }
            val seek = requireNotNull(session.seekToIfCurrent(lease, 2_000))
            await("minified paused seek commits the exact receipt") {
                val committed = session.snapshot().publication.committed
                committed?.selection?.token == token && committed.transport.completedSeekRevision >= seek.seekRevision
            }
            assertNotNull(session.setPlayWhenReadyIfCurrent(token, true))
            await("minified seek resumes exact owner") {
                val output = session.diagnosticsSnapshot()?.output
                val committed = session.snapshot().publication.committed
                committed != null && output?.playing == true && output.lastPresentedSelectionToken == token.value &&
                    output.lastPresentedGeneration == committed.owner.ownership.generation &&
                    committed.transport.completedSeekRevision >= seek.seekRevision &&
                    output.lastPresentedTimelineFrame >= 2_000 * 48
            }
            val stop = session.stop()
            await("minified STOP retires acknowledged owners") { stop.isComplete() }
            assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
            await("minified session receives the exact STOP publication") {
                session.snapshot().publication.committed == null &&
                    session.snapshot().lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE
            }
        } finally { session.close(); check(file.delete()) }
    }

    @Test fun wavPackReadInvokesKeptJniAndPreservesIndependentSampleBits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("minified-wavpack-", ".wv", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("encoder/remaining-float32.wv").use { input ->
                file.outputStream().use(input::copyTo)
            }
            val facts = inspectPreparedSourceAudio(file)
            RandomAccessFile(file, "r").use { input ->
                WavPackReader(input.fd, 0, input.length(), facts) {}.use { reader ->
                    val pcm = ByteBuffer.allocateDirect(17 * 8).order(ByteOrder.nativeOrder())
                    assertEquals(17, reader.readFrames(48_000, pcm, 17))
                    repeat(34) { offset ->
                        val index = 96_000 + offset
                        val expected = ((index * 2654435761L).toInt() and 0x7fffff) or
                            ((112 + index % 20) shl 23) or ((index and 1) shl 31)
                        assertEquals("minified WavPack sample $index", expected, pcm.int)
                    }
                }
            }
        } finally { check(file.delete()) }
    }

    private fun await(boundary: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(boundary, condition())
    }
}
