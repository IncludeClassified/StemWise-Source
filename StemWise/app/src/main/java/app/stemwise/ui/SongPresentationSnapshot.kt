package app.stemwise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.stemwise.analysis.BeatEvent
import app.stemwise.analysis.ImportedAnalysisRepository
import app.stemwise.analysis.ImportedAnalysisSummary
import app.stemwise.analysis.ImportedSongDetail
import app.stemwise.analysis.ImportedSongPresentation
import app.stemwise.analysis.MeterSegment
import app.stemwise.analysis.SongSection
import app.stemwise.data.SeparationSetEntity
import app.stemwise.data.StemAssetEntity
import app.stemwise.integration.SeparationRepository
import app.stemwise.model.Song
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.PlaybackControlLease
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Stable owner of every low-frequency value that can describe the visible player profile.
 *
 * Audio, manifest, and stem revisions are deliberately independent: metadata-only imports do not
 * restart audio, and a replacement stem package does not make an older analysis publication valid.
 */
@Immutable
internal data class SongPresentationOwner(
    val songId: String,
    val mediaRevision: String,
    val audioRevision: String,
    val stemRevision: String,
    val sourceHash: String?
) {
    init {
        require(songId.isNotBlank())
    }

    fun owns(song: Song): Boolean =
        song.id == songId &&
            song.mediaRevision == mediaRevision &&
            song.audioRevision == audioRevision &&
            song.stemRevision == stemRevision &&
            song.sourceHash == sourceHash

    fun owns(lease: PlaybackControlLease): Boolean {
        if (lease.songId != songId) return false
        val expectedAudioRevision = audioRevision.takeIf(String::isNotBlank)
            ?: sourceHash?.takeIf(String::isNotBlank)
        return expectedAudioRevision == null || lease.audioRevision == expectedAudioRevision
    }

    companion object {
        fun from(song: Song): SongPresentationOwner = SongPresentationOwner(
            songId = song.id,
            mediaRevision = song.mediaRevision,
            audioRevision = song.audioRevision,
            stemRevision = song.stemRevision,
            sourceHash = song.sourceHash
        )
    }
}

/** One immutable analysis revision; related timeline fields can never publish independently. */
@Immutable
internal data class PlayerAnalysisUiBundle(
    val beatEvents: List<BeatEvent> = emptyList(),
    val meterSegments: List<MeterSegment> = emptyList(),
    val waveformPeaks: List<Float> = emptyList(),
    val songSections: List<SongSection> = emptyList(),
    val summary: ImportedAnalysisSummary? = null
)

/**
 * Database/file projections tagged with the exact profile owner that requested them.
 *
 * [activeStemAssets] is empty during a database transition unless every asset belongs to the
 * active, complete, source-matching set. Showing no mixer briefly is safer than combining lanes
 * from one package with labels or controls from another.
 */
@Immutable
internal data class SongStemSeparationProjection(
    val owner: SongPresentationOwner,
    val usableSets: List<SeparationSetEntity> = emptyList(),
    val activeSet: SeparationSetEntity? = null,
    val activeStemAssets: List<StemAssetEntity> = emptyList()
) {
    init {
        require(usableSets.all { it.songId == owner.songId })
        require(activeSet == null || activeSet in usableSets)
        require(
            activeStemAssets.isEmpty() ||
                activeSet != null &&
                activeStemAssets.all { it.separationSetId == activeSet.id }
        )
    }
}

/**
 * The only low-frequency presentation publication consumed by PlayerScreen.
 *
 * A song switch seeds separation from current local metadata for the new revision. IO then fills
 * related analysis/detail fields together; separation continues observing that same owner.
 * An outgoing coroutine cannot attach another revision's content to this song.
 */
@Immutable
internal data class SongPresentationSnapshot(
    val owner: SongPresentationOwner,
    val song: Song,
    val analysis: PlayerAnalysisUiBundle,
    val detail: ImportedSongDetail,
    val separation: SongStemSeparationProjection,
    val contentLoaded: Boolean,
    val contentReadFailed: Boolean = false
) {
    init {
        require(owner.owns(song))
        require(separation.owner == owner)
    }

    companion object {
        fun initial(
            song: Song,
            sets: List<SeparationSetEntity> = emptyList(),
            activeStems: List<StemAssetEntity> = emptyList(),
            prepared: Result<ImportedSongPresentation>? = null
        ): SongPresentationSnapshot {
            val loaded = prepared?.getOrNull()
            val owner = SongPresentationOwner.from(song)
            return SongPresentationSnapshot(
                owner = owner,
                song = loaded?.let { song.withImportedSongDetail(it.detail) } ?: song,
                analysis = loaded?.let { PlayerAnalysisUiBundle(it.timeline.beats, it.timeline.meterSegments,
                    it.waveformPeaks, it.sections, it.summary) } ?: PlayerAnalysisUiBundle(),
                detail = loaded?.detail ?: ImportedSongDetail(),
                separation = projectSongSeparation(owner, sets, activeStems),
                contentLoaded = loaded != null,
                contentReadFailed = prepared?.isFailure == true
            )
        }
    }
}

internal fun Song.withImportedSongDetail(detail: ImportedSongDetail): Song {
    return copy(
        lyricTracks = detail.lyricTracks,
        additionalInfo = detail.additionalInfo,
        lyricsStatus = detail.lyricsStatus,
        lyricsReason = detail.lyricsReason
    )
}

private fun projectSongSeparation(
    owner: SongPresentationOwner,
    sets: List<SeparationSetEntity>,
    activeStems: List<StemAssetEntity>
): SongStemSeparationProjection {
    val usableSets = sets.asSequence()
        .filter { set ->
            set.songId == owner.songId &&
                set.state == SeparationRepository.STATE_COMPLETE &&
                set.sourceSha256 == owner.sourceHash
        }
        .toList()
    val activeSet = usableSets.firstOrNull(SeparationSetEntity::isActive)
    val coherentAssets = coherentStemAssetsForOwner(
        owner = owner,
        owningSet = activeSet,
        activeStems = activeStems
    )
    return SongStemSeparationProjection(
        owner = owner,
        usableSets = usableSets.toList(),
        activeSet = activeSet,
        activeStemAssets = coherentAssets.toList()
    )
}

/** Shared repository/runtime gate for a complete declared package owned by this song revision. */
internal fun coherentStemAssetsForOwner(
    owner: SongPresentationOwner,
    owningSet: SeparationSetEntity?,
    activeStems: List<StemAssetEntity>
): List<StemAssetEntity> {
    if (
        owningSet == null ||
        owningSet.songId != owner.songId ||
        owningSet.state != SeparationRepository.STATE_COMPLETE ||
        owningSet.sourceSha256 != owner.sourceHash ||
        activeStems.any { asset ->
            asset.separationSetId != owningSet.id || !asset.validated
        } ||
        activeStems.distinctBy(StemAssetEntity::id).size != activeStems.size ||
        !StemRoleContract.isPlayable(
            activeStems.map { asset ->
                asset.normalizedCapability
                    ?.takeIf(String::isNotBlank)
                    ?: asset.providerLabel
            }
        )
    ) {
        return emptyList()
    }
    return activeStems.toList()
}

/**
 * Builds the song profile off the drawing/gesture path and publishes it as one atomic value.
 * Content revisions key hydration; scalar preparation progress reuses the exact loaded owner.
 * A metadata revision cannot display the preceding revision while its new reads start.
 */
@Composable
internal fun rememberSongPresentationSnapshot(
    song: Song,
    separationRepository: SeparationRepository,
    analysisRepository: ImportedAnalysisRepository,
    retryKey: Int = 0
): State<SongPresentationSnapshot> {
    val owner = SongPresentationOwner.from(song)
    val preparationIdentity = analysisRepository.presentationIdentity(song)
    val result = remember(owner, preparationIdentity, separationRepository, analysisRepository) {
        val (sets, activeStems) = separationRepository.currentStemMetadata(owner.songId)
        mutableStateOf(SongPresentationSnapshot.initial(song, sets, activeStems,
            analysisRepository.preparedPresentation(song)))
    }

    LaunchedEffect(owner, preparationIdentity, analysisRepository, retryKey) {
        try {
            val loaded = analysisRepository.presentation(owner.songId, owner.mediaRevision, owner.sourceHash.orEmpty(), retryFailure = retryKey > 0)
            currentCoroutineContext().ensureActive()
            if (result.value.owner == owner && owner.owns(song)) {
                result.value = result.value.copy(
                    song = song.withImportedSongDetail(loaded.detail),
                    analysis = PlayerAnalysisUiBundle(loaded.timeline.beats, loaded.timeline.meterSegments,
                        loaded.waveformPeaks, loaded.sections, loaded.summary),
                    detail = loaded.detail,
                    contentLoaded = true,
                    contentReadFailed = false
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (result.value.owner == owner) result.value = result.value.copy(contentReadFailed = true)
        }
    }
    LaunchedEffect(owner, separationRepository) {
        combine(separationRepository.observeActiveStems(owner.songId), separationRepository.observeSets(owner.songId)) { activeStems, sets ->
            projectSongSeparation(owner, sets, activeStems)
        }.distinctUntilChanged().collect { separation ->
            if (result.value.owner == owner && owner.owns(song)) result.value = result.value.copy(separation = separation)
        }
    }
    // Preparation progress can replace Song many times without changing any content
    // revision. Project those scalar updates into the already loaded whole snapshot instead of
    // reparsing the manifest and briefly emptying waveform/stem UI for every percentage tick.
    return remember(result, song) {
        derivedStateOf {
            val loaded = result.value
            val currentSong = song.withImportedSongDetail(loaded.detail)
            if (loaded.song == currentSong) loaded else loaded.copy(song = currentSong)
        }
    }
}

@Composable
internal fun PlayerPresentationRetry(failed: Boolean, onRetry: () -> Unit) {
    if (failed) {
        androidx.compose.material3.Text("Could not read song details", color = app.stemwise.ui.theme.TextSecondary)
        app.stemwise.ui.design.StemwiseTextButton(onClick = onRetry) {
            androidx.compose.material3.Text("Retry song details")
        }
    }
}
