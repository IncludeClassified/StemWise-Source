package app.stemwise.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.playback.StemMixState
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongPlaybackSettingsStoreInstrumentationTest {
    private val identity = SongPlaybackSettingsIdentity("library-a", "content://fixture/root-a", "song-a", "a".repeat(64))
    private val range = SongTrimRange(1_250L, 24_000L)
    private val durationMs = 30_000L
    private val authored = SongPlaybackSettings(trim = range, loop = SongLoopPreference(5_000L, 9_000L, true),
        tempo = 1.25f, semitones = 3, metronome = SongMetronomeSettings(true, .2f, 4, 7, 8),
        stemMix = mapOf("vocals" to StemMixState(.37f, false, true), "bass" to StemMixState(.64f, true, false)))

    @Test fun restartAndReimportRetainOnlyTheExactOriginalAndLibrary() = withStore { directory, store ->
        assertNull(store.read(identity, durationMs))
        assertEquals(authored, store.update(identity, durationMs) { authored })
        val restarted = SongPlaybackSettingsStore(directory)
        assertEquals(authored, restarted.read(identity.copy(), durationMs))
        assertNull(restarted.read(identity.copy(libraryId = "library-b"), durationMs))
        assertNull(restarted.read(identity.copy(treeUri = "content://fixture/root-b"), durationMs))
        assertNull(restarted.read(identity.copy(songId = "song-b"), durationMs))
        val changedAudio = identity.copy(originalSha256 = "b".repeat(64))
        assertNull(restarted.read(changedAudio, 10_000L))
        val replacement = restarted.update(changedAudio, 10_000L) {
            assertEquals(SongPlaybackSettings(), it)
            it.copy(trim = SongTrimRange(0L, 10_000L), tempo = .75f)
        }
        assertEquals(SongPlaybackSettings(tempo = .75f), replacement)
        assertEquals(1, directory.listFiles()!!.size)
        assertNull(store.read(identity, durationMs))
        store.clear(identity)
        assertEquals(replacement, SongPlaybackSettingsStore(directory).read(changedAudio, 10_000L))
        store.clear(changedAudio)
        assertNull(SongPlaybackSettingsStore(directory).read(changedAudio, 10_000L))
        assertEquals(0, directory.listFiles()!!.size)
        val otherRoot = identity.copy(treeUri = "content://fixture/root-b")
        store.update(identity, durationMs) { authored }
        store.update(otherRoot, durationMs) { replacement }
        store.clear(otherRoot)
        assertEquals(authored, SongPlaybackSettingsStore(directory).read(identity, durationMs))
        assertNull(SongPlaybackSettingsStore(directory).read(otherRoot, durationMs))
    }

    @Test fun sectionUpdatesAndTrimResetPreserveAuthoredControlsAndAbsoluteLoopIntent() = withStore { directory, store ->
        store.update(identity, durationMs) { authored }
        val narrowed = store.update(identity, durationMs) { it.copy(trim = SongTrimRange(12_000L, 20_000L)) }
        assertEquals(authored.copy(trim = SongTrimRange(12_000L, 20_000L)), narrowed)
        assertEquals(SongLoopPreference(5_000L, 9_000L, true), narrowed.loop)
        val reset = store.update(identity, durationMs) { it.copy(trim = SongTrimRange(0L, durationMs)) }
        assertEquals(authored.copy(trim = null), reset)
        val off = store.update(identity, durationMs) {
            it.copy(loop = it.loop.copy(enabled = false), metronome = checkNotNull(it.metronome).copy(enabled = false))
        }
        assertEquals(reset.copy(loop = reset.loop.copy(enabled = false),
            metronome = checkNotNull(reset.metronome).copy(enabled = false)), off)
        assertEquals(off, SongPlaybackSettingsStore(directory).read(identity, durationMs))
        val changedTuning = store.update(identity, durationMs) { it.copy(tempo = 1f, semitones = 0) }
        assertEquals(off.copy(tempo = 1f, semitones = 0), changedTuning)
        assertEquals(changedTuning, SongPlaybackSettingsStore(directory).read(identity, durationMs))
    }

    @Test fun trimOnlySettingsKeepMetronomeUnauthoredAndPreserveLaterExplicitOff() = withStore { directory, store ->
        store.update(identity, durationMs) { it.copy(trim = range) }
        assertNull(SongPlaybackSettingsStore(directory).read(identity, durationMs)?.metronome)
        val off = store.update(identity, durationMs) { it.copy(metronome = SongMetronomeSettings(enabled = false)) }
        assertEquals(SongMetronomeSettings(enabled = false), off.metronome)
        store.update(identity, durationMs) { it.copy(trim = null) }
        assertEquals(off.copy(trim = null), SongPlaybackSettingsStore(directory).read(identity, durationMs))
    }

    @Test fun invalidDeltasAndFailedAtomicReplacementPreserveAllCommittedSettings() = withStore { directory, store ->
        store.update(identity, durationMs) { authored }
        val file = directory.listFiles()!!.single()
        val bytes = file.readBytes()
        val invalid = listOf<() -> Unit>(
            { SongTrimRange(-1L, 1L) }, { SongTrimRange(1L, 1L) }, { SongTrimRange(2L, 1L) },
            { store.update(identity, durationMs) { it.copy(trim = SongTrimRange(0L, durationMs + 1L)) } },
            { store.update(identity, durationMs) { it.copy(loop = SongLoopPreference(0L, durationMs + 1L, true)) } },
            { SongLoopPreference(0L, 99L, true) }, { SongLoopPreference(enabled = true) },
            { SongLoopPreference(pointB = 1_000L) }, { SongPlaybackSettings(tempo = Float.NaN) },
            { SongPlaybackSettings(tempo = 2.01f) }, { SongPlaybackSettings(semitones = 13) },
            { SongMetronomeSettings(volume = Float.POSITIVE_INFINITY) },
            { SongMetronomeSettings(subdivision = 3) }, { SongMetronomeSettings(numerator = 0) },
            { SongMetronomeSettings(denominator = 3) },
            { SongPlaybackSettings(stemMix = mapOf("Vocals" to StemMixState())) },
            { SongPlaybackSettings(stemMix = mapOf("unsupported" to StemMixState())) },
            { SongPlaybackSettings(stemMix = mapOf("vocals" to StemMixState(-.1f))) },
            { store.read(identity, 0L) }
        )
        invalid.forEach { operation ->
            assertThrows(IllegalArgumentException::class.java) { operation() }
            assertArrayEquals(bytes, file.readBytes())
        }
        assertThrows(IllegalStateException::class.java) { store.update(identity, durationMs) { error("DELTA_FAILED") } }
        assertArrayEquals(bytes, file.readBytes())
        check(directory.setWritable(false, false) && !directory.canWrite())
        try {
            assertThrows(Exception::class.java) { store.update(identity, durationMs) { it.copy(tempo = .75f) } }
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            check(directory.setWritable(true, true))
        }
        assertEquals(authored, SongPlaybackSettingsStore(directory).read(identity, durationMs))
        assertEquals(authored.copy(tempo = .75f), store.update(identity, durationMs) { it.copy(tempo = .75f) })
    }

    @Test fun boundaryValuesAndDetachedRoleMapsSurviveActualReadback() = withStore { directory, store ->
        val mutableMix = mutableMapOf("remaining" to StemMixState(-0f, true, false))
        val low = SongPlaybackSettings(loop = SongLoopPreference(0L, durationMs, true), tempo = .5f, semitones = -12,
            metronome = SongMetronomeSettings(true, -0f, 1, 1, 1), stemMix = mutableMix)
        val saved = store.update(identity, durationMs) { low }
        mutableMix["remaining"] = StemMixState(1f)
        assertEquals(StemMixState(0f, true, false), saved.stemMix["remaining"])
        assertEquals(0f, checkNotNull(saved.metronome).volume, 0f)
        assertEquals(saved, SongPlaybackSettingsStore(directory).read(identity, durationMs))
        val high = saved.copy(tempo = 2f, semitones = 12,
            metronome = SongMetronomeSettings(false, 1f, 4, 32, 32), stemMix = mapOf("remaining" to StemMixState(1f)))
        assertEquals(high, store.update(identity, durationMs) { high })
        assertEquals(high, SongPlaybackSettingsStore(directory).read(identity, durationMs))
    }

    @Test fun corruptAndMalformedRecordsFailWithoutOverwritingOrDeletingThem() = withStore { directory, store ->
        store.update(identity, durationMs) { authored }
        val file = directory.listFiles()!!.single()
        val valid = file.readText(Charsets.UTF_8)
        fun changed(change: (JSONObject) -> Unit) = JSONObject(valid).also(change).toString()
        for (malformed in listOf("{",
            changed { it.getJSONObject("settings").getJSONObject("trim").put("startMs", 1.5) },
            changed { it.getJSONObject("settings").getJSONObject("trim").put("endMs", 0L) },
            changed { it.getJSONObject("settings").getJSONObject("loop").put("enabled", "true") },
            changed { it.getJSONObject("settings").getJSONObject("metronome").put("volume", ".2") },
            changed { it.getJSONObject("settings").put("semitones", 1.5) },
            changed { it.getJSONObject("settings").put("tempo", "1.25") },
            changed { it.getJSONObject("settings").getJSONObject("stemMix").getJSONObject("vocals").put("solo", 1) },
            changed { it.getJSONObject("settings").put("beatGridMs", org.json.JSONArray()) },
            changed { it.put("libraryId", "other") }, changed { it.put("originalSha256", "invalid") })) {
            file.writeText(malformed, Charsets.UTF_8)
            val reopened = SongPlaybackSettingsStore(directory)
            assertThrows(Exception::class.java) { reopened.read(identity, durationMs) }
            assertThrows(Exception::class.java) { reopened.update(identity, durationMs) { it.copy(trim = null) } }
            assertThrows(Exception::class.java) { reopened.clear(identity) }
            assertEquals(malformed, file.readText(Charsets.UTF_8))
        }
    }

    @Test fun explicitMalformedResetQuarantinesOnlyThatSongAndPreservesReadableReplacementAudio() = withStore { directory, store ->
        val neighbor = identity.copy(songId = "neighbor")
        store.update(neighbor, durationMs) { authored }
        store.update(identity, durationMs) { authored }
        val file = directory.listFiles()!!.single { it.readText(Charsets.UTF_8).contains("\"song-a\"") }
        for (invalid in listOf("{", JSONObject(file.readText(Charsets.UTF_8)).apply {
            getJSONObject("settings").getJSONObject("trim").put("endMs", durationMs + 1L)
        }.toString())) {
            file.writeText(invalid, Charsets.UTF_8)
            assertThrows(AtomicJournalFormatException::class.java) { store.read(identity, durationMs) }
            assertEquals(true, store.resetMalformed(identity, durationMs))
            assertNull(store.read(identity, durationMs))
            assertEquals(true, directory.listFiles()!!.any { it.name.startsWith(file.name + ".invalid-") &&
                it.readText(Charsets.UTF_8) == invalid })
            assertEquals(authored, store.read(neighbor, durationMs))
            store.update(identity, durationMs) { authored }
        }
        val replacement = identity.copy(originalSha256 = "c".repeat(64))
        store.update(replacement, durationMs) { authored }
        assertEquals(false, store.resetMalformed(identity, durationMs))
        assertEquals(authored, store.read(replacement, durationMs))
    }

    private fun withStore(block: (File, SongPlaybackSettingsStore) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "song-playback-settings-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            block(directory, SongPlaybackSettingsStore(directory))
        } finally {
            check(directory.deleteRecursively())
        }
    }
}
