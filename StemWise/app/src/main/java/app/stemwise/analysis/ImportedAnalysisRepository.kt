package app.stemwise.analysis

import android.content.Context
import android.net.Uri
import app.stemwise.data.authority
import app.stemwise.data.RootAuthority
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.projectEnrichmentLyricTracks
import app.stemwise.data.toDisplayInformation
import app.stemwise.data.readBoundedProviderDocument
import app.stemwise.enrichment.VerifiedInstalledEnrichment
import app.stemwise.enrichment.installedEnrichmentVerificationCache
import app.stemwise.enrichment.parseStrictManifestJson
import app.stemwise.model.LyricTrack
import app.stemwise.suspendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import app.stemwise.model.Song
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SongSection(
    val label: String,
    val startMs: Long,
    val endMs: Long
)

data class WaveformWindow(
    val startMs: Long,
    val endMs: Long,
    val frameDurationMs: Long,
    val peaks: List<Float>
)

private const val IMPORTED_WAVEFORM_FRAME_BYTES = 6

internal fun importedWaveformByteCountMatches(byteCount: Int, frameCount: Int): Boolean =
    frameCount > 0 &&
        byteCount.toLong() == frameCount.toLong() * IMPORTED_WAVEFORM_FRAME_BYTES.toLong()

data class BeatEvent(
    val timeMs: Long,
    val isDownbeat: Boolean
)

data class MeterSegment(
    val startMs: Long,
    val endMs: Long,
    val numerator: Int,
    val denominator: Int
)

data class MetronomeTimeline(
    val beats: List<BeatEvent> = emptyList(),
    val meterSegments: List<MeterSegment> = emptyList()
)

/** Beat timing needs at least two usable events to define a metronome interval. */
internal fun metronomeAvailableFor(beatEventCount: Int): Boolean = beatEventCount >= 2

/** The audible grid is the BPM authority; metadata is only the fallback for sparse grids. */
internal fun effectiveGridBpm(metadataBpm: Float?, beatTimesMs: List<Long>): Float {
    val intervals = beatTimesMs
        .asSequence()
        .zipWithNext { first, second -> second - first }
        .filter { it > 0L }
        .sorted()
        .toList()
    val gridBpm = intervals.takeIf(List<Long>::isNotEmpty)?.let { sorted ->
        val middle = sorted.size / 2
        val medianMs = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle].toFloat()
        }
        60_000f / medianMs
    }
    return gridBpm ?: metadataBpm?.takeIf { it.isFinite() && it > 0f } ?: 120f
}

internal fun projectImportedMetronomeTimeline(rhythm: JSONObject?): MetronomeTimeline {
    if (rhythm == null) return MetronomeTimeline()
    val beats = rhythm.optJSONArray("beats")?.objectsForProjection().orEmpty()
        .mapNotNull { beat ->
            val time = beat.optLong("timeMs", -1L).takeIf { it >= 0L }
                ?: return@mapNotNull null
            BeatEvent(time, beat.optBoolean("isDownbeat", false))
        }
        .distinctBy(BeatEvent::timeMs)
        .sortedBy(BeatEvent::timeMs)
    val segments = rhythm.optJSONArray("meterSegments")?.objectsForProjection().orEmpty()
        .mapNotNull { segment ->
            val start = segment.optLong("startMs", -1L).takeIf { it >= 0L }
                ?: return@mapNotNull null
            val end = segment.optLong("endMs", -1L).takeIf { it > start }
                ?: return@mapNotNull null
            val numerator = segment.optInt("numerator", 0)
            val denominator = segment.optInt("denominator", 0)
            if (numerator !in 1..32 || denominator !in setOf(1, 2, 4, 8, 16, 32)) null
            else MeterSegment(start, end, numerator, denominator)
        }
        .sortedBy(MeterSegment::startMs)
    return MetronomeTimeline(beats, segments)
}

internal fun decodeImportedWaveformLevel(level: JSONObject?): List<Float> =
    decodeImportedWaveformSamples(level).asList()

private fun decodeImportedWaveformSamples(level: JSONObject?): FloatArray {
    if (level == null) return FloatArray(0)
    val frameCount = level.optInt("frameCount", -1)
    val bytes = runCatching {
        java.util.Base64.getDecoder().decode(level.getString("data"))
    }.getOrNull() ?: return FloatArray(0)
    if (!importedWaveformByteCountMatches(bytes.size, frameCount)) return FloatArray(0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(frameCount) {
        buffer.short
        buffer.short
        (buffer.short.toInt() and 0xffff).coerceAtMost(32767) / 32767f
    }
}

internal fun projectImportedSongSections(analysis: JSONObject?): List<SongSection> =
    analysis?.optJSONObject("structure")
        ?.optJSONArray("sections")
        ?.objectsForProjection()
        ?.mapNotNull {
            val startMs = it.optLong("startMs", -1L).takeIf { value -> value >= 0L }
                ?: return@mapNotNull null
            val endMs = it.optLong("endMs", -1L).takeIf { value -> value >= 0L }
                ?: return@mapNotNull null
            if (endMs <= startMs) null else SongSection(
                label = it.optString("label").ifBlank { "Section" },
                startMs = startMs,
                endMs = endMs
            )
        }
        .orEmpty()

private fun JSONArray.objectsForProjection(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

data class ImportedAnalysisSummary(
    val status: String,
    val tuningFrequencyHz: Double? = null,
    val tuningCentsFromA440: Double? = null,
    val integratedLufs: Double? = null,
    val loudnessRangeLu: Double? = null,
    val truePeakDbtp: Double? = null,
    val sectionCount: Int = 0,
    val keyStatus: String? = null,
    val keyReason: String? = null,
    val tuningStatus: String? = null,
    val tuningReason: String? = null
)

data class ImportedSongDetail(
    val lyricTracks: List<LyricTrack> = emptyList(),
    val additionalInfo: Map<String, String> = emptyMap(),
    val lyricsStatus: String? = null,
    val lyricsReason: String? = null
)

/** Related player fields derived from one successfully verified manifest read. */
data class ImportedSongPresentation(
    val timeline: MetronomeTimeline,
    val waveformPeaks: List<Float>,
    val sections: List<SongSection>,
    val summary: ImportedAnalysisSummary?,
    val detail: ImportedSongDetail
)

internal fun projectImportedSongDetail(installed: JSONObject): ImportedSongDetail {
    val lyrics = installed.optJSONObject("lyrics")
    val tracks = lyrics?.optJSONArray("tracks")
        ?.objectsForProjection()
        .orEmpty()
    return ImportedSongDetail(
        lyricTracks = projectEnrichmentLyricTracks(tracks),
        additionalInfo = installed.optJSONObject("metadata")?.toDisplayInformation().orEmpty(),
        lyricsStatus = lyrics?.opt("status") as? String,
        lyricsReason = lyrics?.opt("reason") as? String
    )
}

/**
 * Read-only consumer of the current installed enrichment analysis document.
 * This repository never schedules, estimates, repairs, or overwrites musical data.
 */
class ImportedAnalysisRepository(
    private val context: Context,
    private val roots: LibraryRootManager,
    private val readManifestBytes: suspend (Context, Uri, Long?, Long) -> ByteArray = { owner, uri, size, maximum ->
        readBoundedProviderDocument(owner, uri, size, maximum)
    }
) {
    internal data class PresentationIdentity(val root: RootAuthority, val songId: String,
        val sourceSha256: String, val mediaRevision: String)
    private class PreparedPresentation {
        @Volatile var result: Result<ImportedSongPresentation>? = null
        val completion = CompletableDeferred<Unit>()
    }
    private val preparationLock = Any()
    private var queuePresentations = emptySet<PresentationIdentity>()
    private var selectionPresentation: PresentationIdentity? = null
    private val preparations = mutableMapOf<PresentationIdentity, PreparedPresentation>()
    private val mutablePreparationRevision = MutableStateFlow(0L)
    internal val preparationRevision = mutablePreparationRevision.asStateFlow()

    private fun presentationIdentity(song: Song, root: RootAuthority) =
        PresentationIdentity(root, song.id, song.sourceHash.orEmpty(), song.mediaRevision)

    internal fun presentationIdentity(song: Song): PresentationIdentity? =
        roots.captureAuthority()?.let { presentationIdentity(song, it) }

    /** Only compact immutable UI projections are retained, scoped to the exact current queue. */
    internal fun retainQueuePresentations(songs: List<Song>) {
        val root = roots.captureAuthority()
        val next = root?.let { songs.map { song -> presentationIdentity(song, it) }.toSet() }.orEmpty()
        synchronized(preparationLock) {
            val selected = selectionPresentation?.takeIf { it.root == root }
            if (next == queuePresentations && selected == selectionPresentation) return
            queuePresentations = next
            selectionPresentation = selected
            preparations.keys.retainAll(next + listOfNotNull(selectionPresentation))
            mutablePreparationRevision.update { Math.addExact(it, 1L) }
        }
    }

    /** One foreground target may resolve before verified queue insertion. It owns no queue cursor. */
    internal fun retainSelectionPresentation(song: Song?) {
        val next = song?.let(::presentationIdentity)
        synchronized(preparationLock) {
            if (selectionPresentation == next) return
            selectionPresentation = next
            preparations.keys.retainAll(queuePresentations + listOfNotNull(next))
            mutablePreparationRevision.update { Math.addExact(it, 1L) }
        }
    }

    internal fun preparedPresentation(song: Song): Result<ImportedSongPresentation>? =
        presentationIdentity(song)?.let { key -> synchronized(preparationLock) { preparations[key]?.result } }

    private suspend fun preparedOrReadPresentation(songId: String, mediaRevision: String,
        sourceSha256: String, retryFailure: Boolean): ImportedSongPresentation {
        val root = roots.captureAuthority() ?: throw java.io.IOException("SONG_PRESENTATION_READ_UNAVAILABLE")
        val key = PresentationIdentity(root, songId, sourceSha256, mediaRevision)
        val (entry, ownsRead) = synchronized(preparationLock) {
            if (key !in queuePresentations && key != selectionPresentation) null to false else {
                val current = preparations[key]?.takeUnless { retryFailure && it.result?.isFailure == true }
                if (current != null) current to false else PreparedPresentation().also { preparations[key] = it } to true
            }
        }
        if (entry == null) return readPresentation(songId, mediaRevision, sourceSha256)
        if (ownsRead) {
            try {
                val result = try {
                    val value = readPresentation(songId, mediaRevision, sourceSha256)
                    roots.requireSameLibrary(root)
                    Result.success(value)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
                synchronized(preparationLock) {
                    entry.result = result
                    if (preparations[key] === entry) mutablePreparationRevision.update { Math.addExact(it, 1L) }
                }
                entry.completion.complete(Unit)
            } catch (cancelled: CancellationException) {
                synchronized(preparationLock) { if (preparations[key] === entry) preparations.remove(key) }
                entry.completion.cancel(cancelled)
                throw cancelled
            }
        }
        try { entry.completion.await() }
        catch (cancelled: CancellationException) {
            // A replacement queue flight may join the retiring reader. Its own live caller
            // retries against the current entry; caller cancellation still stops immediately.
            currentCoroutineContext().ensureActive()
            return preparedOrReadPresentation(songId, mediaRevision, sourceSha256, retryFailure)
        }
        return checkNotNull(entry.result).getOrThrow()
    }

    suspend fun metronomeTimeline(
        songId: String,
        mediaRevision: String,
        sourceSha256: String
    ): MetronomeTimeline = presentation(songId, mediaRevision, sourceSha256).timeline

    suspend fun presentation(songId: String, mediaRevision: String, sourceSha256: String,
        retryFailure: Boolean = false): ImportedSongPresentation =
        preparedOrReadPresentation(songId, mediaRevision, sourceSha256, retryFailure)

    private suspend fun readPresentation(songId: String, mediaRevision: String, sourceSha256: String): ImportedSongPresentation =
        withContext(Dispatchers.IO) {
            val installed = manifest(songId, mediaRevision, sourceSha256)
                ?: throw java.io.IOException("SONG_PRESENTATION_READ_UNAVAILABLE")
            val analysis = installed.analysis
            val level = analysis?.optJSONObject("waveform")?.optJSONArray("levels")?.objects()
                ?.firstOrNull { it.optLong("frameDurationMs") == 50L }
            ImportedSongPresentation(
                timeline = projectImportedMetronomeTimeline(analysis?.optJSONObject("rhythm")),
                waveformPeaks = installed.waveform(50L) { decodeImportedWaveformSamples(level) },
                sections = projectImportedSongSections(analysis),
                summary = projectSummary(analysis),
                detail = projectImportedSongDetail(installed.detail)
            )
        }

    /** Hydrates manifest-sized presentation fields only for one exact visible revision. */
    suspend fun songDetail(
        songId: String,
        mediaRevision: String,
        sourceSha256: String
    ): ImportedSongDetail = withContext(Dispatchers.IO) {
        val installed = manifest(songId, mediaRevision, sourceSha256)
            ?: throw java.io.IOException("SONG_DETAIL_READ_UNAVAILABLE")
        projectImportedSongDetail(installed.detail)
    }

    suspend fun fineWindow5ms(
        songId: String,
        centerMs: Long,
        radiusMs: Long = 5_000L,
        mediaRevision: String,
        sourceSha256: String
    ): WaveformWindow? = withContext(Dispatchers.IO) {
        val songManifest = manifest(songId, mediaRevision, sourceSha256)
            ?: return@withContext null
        val allPeaks = songManifest.waveform(5L) {
            val level = songManifest.analysis
                ?.optJSONObject("waveform")?.optJSONArray("levels")?.objects()
                ?.firstOrNull { it.optLong("frameDurationMs") == 5L }
            decodeImportedWaveformSamples(level)
        }
        val frameCount = allPeaks.size
        if (frameCount <= 0) return@withContext null
        val startMs = (centerMs - radiusMs).coerceAtLeast(0L)
        val endMs = centerMs + radiusMs
        val first = (startMs / 5L).toInt().coerceIn(0, frameCount)
        val exclusive = ((endMs + 4L) / 5L).toInt().coerceIn(first, frameCount)
        val requestedFrames = exclusive - first
        if (requestedFrames <= 0 || requestedFrames > MAX_FINE_WINDOW_FRAMES) {
            return@withContext null
        }
        currentCoroutineContext().ensureActive()
        val decoded = List(requestedFrames) { offset -> allPeaks[first + offset] }
        WaveformWindow(
            startMs = first * 5L,
            endMs = exclusive * 5L,
            frameDurationMs = 5L,
            peaks = decoded
        )
    }

    suspend fun summary(
        songId: String,
        mediaRevision: String,
        sourceSha256: String
    ): ImportedAnalysisSummary? = withContext(Dispatchers.IO) {
        val installed = manifest(songId, mediaRevision, sourceSha256)
            ?: throw java.io.IOException("SONG_ANALYSIS_READ_UNAVAILABLE")
        projectSummary(installed.analysis)
    }

    private fun projectSummary(analysis: JSONObject?): ImportedAnalysisSummary? {
        if (analysis == null) return null
        val status = analysis.optString("status").takeIf(String::isNotBlank)
            ?: return null
        val tuning = analysis.optJSONObject("tuning")
        val key = analysis.optJSONObject("key")
        val loudness = analysis.optJSONObject("loudness")
        return ImportedAnalysisSummary(
            status = status,
            keyStatus = key?.optString("status")?.takeIf(String::isNotBlank),
            keyReason = key?.optString("reason")?.takeIf(String::isNotBlank),
            tuningStatus = tuning?.optString("status")?.takeIf(String::isNotBlank),
            tuningReason = tuning?.optString("reason")?.takeIf(String::isNotBlank),
            tuningFrequencyHz = tuning.finiteDouble("frequencyHz"),
            tuningCentsFromA440 = tuning.finiteDouble("centsFromA440"),
            integratedLufs = loudness.finiteDouble("integratedLufs"),
            loudnessRangeLu = loudness.finiteDouble("loudnessRangeLu"),
            truePeakDbtp = loudness.finiteDouble("truePeakDbtp"),
            sectionCount = analysis.optJSONObject("structure")
                ?.optJSONArray("sections")
                ?.length()
                ?: 0
        )
    }

    private suspend fun manifest(
        songId: String,
        mediaRevision: String,
        sourceSha256: String
    ): VerifiedInstalledEnrichment? {
        val handle = roots.captureHandle() ?: return null
        val expectedRevision = mediaRevision.trim().takeIf(::isAnalysisManifestRevision)
            ?: return null
        val expectedSourceSha256 = sourceSha256.trim().takeIf(::isAnalysisManifestRevision)
            ?: return null
        installedEnrichmentVerificationCache.find(handle.authority(), songId,
            expectedSourceSha256, expectedRevision)?.let { cached ->
            roots.requireSameLibrary(handle)
            return cached
        }
        val file = roots.checkedAuthoritativeSongSnapshot(handle, songId)
            ?.file("song.json")
            ?: return null
        val bytes = suspendResult {
            readManifestBytes(context, file.uri, file.sizeBytes, MAX_MANIFEST_BYTES.toLong())
        }.getOrNull() ?: return null
        val verified = parseActiveInstalledAnalysisManifest(handle.authority(), bytes, songId,
            expectedRevision, expectedSourceSha256) ?: return null
        roots.requireSameLibrary(handle)
        currentCoroutineContext().ensureActive()
        return verified
    }

    private fun JSONArray.objects(): List<JSONObject> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }

    private fun JSONObject?.finiteDouble(key: String): Double? = this
        ?.optDouble(key, Double.NaN)
        ?.takeIf(Double::isFinite)

    companion object {
        private const val MAX_MANIFEST_BYTES = app.stemwise.enrichment.ENRICHMENT_ARTIFACT_MAX_BYTES
        private const val MAX_FINE_WINDOW_FRAMES = 4_096
    }
}

/**
 * Applies the same generated-data integrity boundary as canonical projection.
 * Analysis consumers must not reopen waveform/rhythm/structure data that the
 * Library scanner deliberately quarantined because its digest or ordering is
 * invalid.
 */
internal fun parseActiveInstalledAnalysisManifest(
    root: RootAuthority,
    bytes: ByteArray,
    expectedSongId: String,
    expectedMediaRevision: String,
    expectedSourceSha256: String,
    decode: (ByteArray) -> JSONObject = ::parseStrictManifestJson
): VerifiedInstalledEnrichment? {
    val revision = expectedMediaRevision.trim().takeIf(::isAnalysisManifestRevision)
        ?: return null
    val sourceSha256 = expectedSourceSha256.trim().takeIf(::isAnalysisManifestRevision)
        ?: return null
    return runCatching {
        installedEnrichmentVerificationCache.verify(bytes, root, expectedSongId, sourceSha256, revision, decode)
    }.getOrNull()
}

private fun isAnalysisManifestRevision(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
