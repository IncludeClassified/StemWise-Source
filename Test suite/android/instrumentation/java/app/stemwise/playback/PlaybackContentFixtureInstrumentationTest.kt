package app.stemwise.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.model.Song
import app.stemwise.model.SongState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class PlaybackContentFixtureInstrumentationTest {
    @Test
    fun retiredProviderBytesAreRecreatedAndEveryGeneratedCacheChildIsRemoved() {
        requireDisposablePlaybackTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as StemwiseApplication
        application.services.audioPlayback.resetForInstrumentationIsolation(instrumentation)
        PlaybackContentFixture.clear(application)
        val directory = File(application.cacheDir, "playback-fixture-lifetime").apply { check(mkdirs()) }
        val source = File(directory, "source.wav")
        val expected = byteArrayOf(3, 1, 4, 1, 5, 9)
        try {
            PlaybackContentFixture.own(application, source)
            source.writeBytes(expected)
            val first = PlaybackContentFixture.publish(application, source)
            val verifier = checkNotNull(application.services.audioPlayback.debug.verifyPlaybackSong)
            val song = Song(id = "fixture-verification", videoId = "fixture-verification",
                title = "Synthetic verification", artist = "StemWise", durationMs = 1_000L,
                state = SongState.READY, accent = Color.Cyan, artworkGlyph = "F", sourcePath = first)
            val verified = runBlocking { verifier(song) }
            assertEquals(song.copy(sourceHash = PlaybackContentFixture.sourceHash(source)), verified)
            assertTrue("Registration accepted an invented Original hash", runCatching {
                runBlocking { verifier(song.copy(sourceHash = "0".repeat(64))) }
            }.isFailure)
            val stemVerifier = checkNotNull(application.services.audioPlayback.debug.verifyPlaybackStems)
            val stems = listOf(app.stemwise.data.StemAssetEntity(id = "synthetic:vocals",
                separationSetId = "synthetic", providerLabel = "Vocals", normalizedCapability = "vocals",
                localPath = first, sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(expected)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }, durationMs = 1_000L, codec = "wav",
                sampleRateHz = 48_000, channelCount = 2, validated = true))
            assertSame(stems, runBlocking { stemVerifier(song.id, stems) })
            assertTrue("Registration accepted an invented part hash", runCatching {
                runBlocking { stemVerifier(song.id, stems.map { it.copy(sha256 = "0".repeat(64)) }) }
            }.isFailure)
            assertTrue("An unregistered part bypassed real package verification", runCatching {
                runBlocking { stemVerifier(song.id, stems.map { it.copy(localPath = "$first-unregistered") }) }
            }.isFailure)
            assertTrue("An unregistered Song bypassed real package verification", runCatching {
                runBlocking { verifier(song.copy(id = "fixture-unregistered", sourcePath = "$first-unregistered")) }
            }.isFailure)
            val uri = android.net.Uri.parse(first)
            check(android.provider.DocumentsContract.deleteDocument(application.contentResolver, uri))
            assertTrue("Registration invented readability after provider deletion", runCatching {
                runBlocking { verifier(song) }
            }.isFailure)
            assertTrue("Registration invented part readability after provider deletion", runCatching {
                runBlocking { stemVerifier(song.id, stems) }
            }.isFailure)
            val restored = PlaybackContentFixture.publish(application, source)
            assertEquals(first, restored)
            assertEquals(verified, runBlocking { verifier(song) })
            val bytes = application.contentResolver.openInputStream(android.net.Uri.parse(restored))!!.use { it.readBytes() }
            assertArrayEquals(expected, bytes)
            // A partial sibling created after publication belongs to the same per-test cache child.
            File(directory, "partial.wav").writeBytes(byteArrayOf(1))
        } finally {
            application.services.audioPlayback.resetForInstrumentationIsolation(instrumentation)
            PlaybackContentFixture.clear(application)
        }
        assertFalse(directory.exists())
        assertNull(application.services.audioPlayback.debug.verifyPlaybackSong)
        assertNull(application.services.audioPlayback.debug.verifyPlaybackStems)
    }
}
