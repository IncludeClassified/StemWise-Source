package app.stemwise.ui

import app.stemwise.data.SeparationSetEntity
import app.stemwise.data.StemAssetEntity
import app.stemwise.integration.SeparationRepository
import app.stemwise.model.StemRoleContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPresentationSnapshotTest {
    private val owner = SongPresentationOwner(
        songId = "song-a",
        mediaRevision = "media-2",
        audioRevision = "audio-1",
        stemRevision = "stems-3",
        sourceHash = "source-hash"
    )

    @Test fun importedDetailKeepsAllThreeAuthoritativeTracksAndAdditionalInformation() {
        val tracks = listOf(
            app.stemwise.model.LyricTrack("original", "original", "en", "plain", text = "One\nTwo"),
            app.stemwise.model.LyricTrack("translation", "translation", "es", "plain", text = "Uno\nDos",
                basedOnTrackId = "original"),
            app.stemwise.model.LyricTrack("ipa", "transliteration", "en-fonipa", "plain", text = "wʌn\ntuː",
                basedOnTrackId = "original")
        )
        val song = app.stemwise.model.Song("song-a", "Video000001", "Title", "Artist", 10_000L,
            app.stemwise.model.SongState.READY, androidx.compose.ui.graphics.Color.White, artworkGlyph = "T")
        val detail = app.stemwise.analysis.ImportedSongDetail(tracks, mapOf("Composer" to "Fixture"))
        val hydrated = song.withImportedSongDetail(detail)
        assertEquals(tracks, resolvedLyricTracks(hydrated))
        assertEquals(detail.additionalInfo, hydrated.additionalInfo)
        assertEquals(listOf("original", "ipa", "translation"),
            plainLyricsDisplayOrder(resolvedLyricTracks(hydrated)).map { it.id })
    }

    @Test fun preparedIncomingSnapshotPublishesTimingAndDetailsOnItsFirstValue() {
        val song = app.stemwise.model.Song("prepared", "Video000001", "Title", "Artist", 10_000L,
            app.stemwise.model.SongState.READY, androidx.compose.ui.graphics.Color.White, artworkGlyph = "P")
        val prepared = app.stemwise.analysis.ImportedSongPresentation(
            app.stemwise.analysis.MetronomeTimeline(listOf(app.stemwise.analysis.BeatEvent(0, true),
                app.stemwise.analysis.BeatEvent(500, false))), listOf(.1f, .8f), emptyList(), null,
            app.stemwise.analysis.ImportedSongDetail(additionalInfo = mapOf("Composer" to "Fixture")))
        val initial = SongPresentationSnapshot.initial(song, prepared = Result.success(prepared))
        assertTrue(initial.contentLoaded)
        assertEquals(prepared.timeline.beats, initial.analysis.beatEvents)
        assertEquals(prepared.waveformPeaks, initial.analysis.waveformPeaks)
        assertEquals(prepared.detail.additionalInfo, initial.song.additionalInfo)
        val failure = SongPresentationSnapshot.initial(song, prepared = Result.failure(java.io.IOException("unavailable")))
        assertTrue(failure.contentReadFailed)
        assertEquals(false, failure.contentLoaded)
    }

    @Test
    fun incomingSnapshotUsesExistingStemMetadataWithoutWaitingForAnObserverRoundTrip() {
        val song = app.stemwise.model.Song(owner.songId, "Video000001", "Title", "Artist", 10_000L,
            app.stemwise.model.SongState.READY, androidx.compose.ui.graphics.Color.White, artworkGlyph = "T",
            sourceHash = owner.sourceHash, mediaRevision = owner.mediaRevision,
            audioRevision = owner.audioRevision, stemRevision = owner.stemRevision)
        val set = separationSet()
        val assets = listOf("vocals", "remaining").map(::stemAsset)
        val initial = SongPresentationSnapshot.initial(song, listOf(set), assets)
        assertEquals(assets, initial.separation.activeStemAssets)
        assertEquals(set, initial.separation.activeSet)
        assertEquals(false, initial.contentLoaded)
        val changedOriginal = SongPresentationSnapshot.initial(song.copy(sourceHash = "new-source"), listOf(set), assets)
        assertTrue(changedOriginal.separation.activeStemAssets.isEmpty())
        assertTrue(changedOriginal.separation.usableSets.isEmpty())
        val retiringSet = SongPresentationSnapshot.initial(song, listOf(set),
            assets.map { it.copy(separationSetId = "superseded-set") })
        assertTrue(retiringSet.separation.activeStemAssets.isEmpty())
    }

    @Test
    fun completeValidatedSourceOwnedPackageIsAcceptedAsOneProjection() {
        val set = separationSet()
        val assets = listOf("lead-vocals", "backing-vocals", "drums", "bass", "electric-guitar", "remaining").map(::stemAsset)

        assertEquals(
            assets,
            coherentStemAssetsForOwner(owner, set, assets)
        )
    }

    @Test
    fun selectedPartsWithRemainingKeepTheirLabelsAndOwnerValidation() {
        val set = separationSet()
        val assets = listOf(
            stemAsset("lead-vocals").copy(providerLabel = "Lead vocals"),
            stemAsset("backing-vocals").copy(providerLabel = "Backing vocals"),
            stemAsset("remaining").copy(providerLabel = "Remaining")
        )
        assertEquals(assets, coherentStemAssetsForOwner(owner, set, assets))
        assertTrue(coherentStemAssetsForOwner(owner, set, assets.dropLast(1)).isEmpty())
        assertTrue(coherentStemAssetsForOwner(owner, set.copy(sourceSha256 = "old-source"), assets).isEmpty())
        assertTrue(coherentStemAssetsForOwner(owner, set,
            assets.mapIndexed { index, asset -> if (index == 2) asset.copy(validated = false) else asset }
        ).isEmpty())
    }

    @Test
    fun partialUnvalidatedOrWrongSourcePackageNeverLeaksIntoMixerPresentation() {
        val set = separationSet()
        val complete = listOf("lead-vocals", "backing-vocals", "drums", "bass", "electric-guitar", "remaining").map(::stemAsset)

        assertTrue(coherentStemAssetsForOwner(owner, set, complete.dropLast(1)).isEmpty())
        assertTrue(
            coherentStemAssetsForOwner(
                owner,
                set,
                complete.mapIndexed { index, asset ->
                    if (index == 0) asset.copy(validated = false) else asset
                }
            ).isEmpty()
        )
        assertTrue(
            coherentStemAssetsForOwner(
                owner,
                set.copy(sourceSha256 = "outgoing-source"),
                complete
            ).isEmpty()
        )
        assertTrue(
            coherentStemAssetsForOwner(
                owner,
                set,
                complete.mapIndexed { index, asset ->
                    if (index == 0) asset.copy(separationSetId = "other-set") else asset
                }
            ).isEmpty()
        )
    }

    private fun separationSet() = SeparationSetEntity(
        id = "set-a",
        songId = owner.songId,
        sourceVersionId = "source-version-a",
        sourceSha256 = requireNotNull(owner.sourceHash),
        provider = "fixture",
        modelId = "model-a",
        modelName = "Fixture",
        remoteJobId = null,

        state = SeparationRepository.STATE_COMPLETE,
        isActive = true,
        createdAtEpochMs = 1L
    )

    private fun stemAsset(role: String) = StemAssetEntity(
        id = "set-a-$role",
        separationSetId = "set-a",
        providerLabel = role,
        normalizedCapability = role,
        localPath = "/fixture/$role.opus",
        sha256 = "$role-hash",
        durationMs = 180_000L,
        codec = "opus",
        sampleRateHz = 48_000,
        channelCount = 2,
        validated = true
    )
}
