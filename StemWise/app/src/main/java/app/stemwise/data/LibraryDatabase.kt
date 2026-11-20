package app.stemwise.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

@Entity(tableName = "library_root_identity")
data class LibraryRootIdentityEntity(
    @PrimaryKey val slot: String,
    val libraryId: String,
    val treeUri: String,
    val epoch: Long,
    val state: String,
    val updatedAtEpochMs: Long
)

/** App-private source reservation/deletion authority retained independently for each Library. */
@Entity(
    tableName = "source_bindings",
    primaryKeys = ["libraryId", "sourceId"],
    indices = [Index(value = ["libraryId", "state"])]
)
data class SourceBindingEntity(
    val libraryId: String,
    val sourceId: String,
    val packageId: String,
    val state: String,
    val transactionId: String,
    val updatedAtEpochMs: Long
)

internal const val RETAINED_OPERATION_ROOT_REQUIRED = "RETAINED_OPERATION_ROOT_REQUIRED"

/** Selected playlist is durable app authority; catalog projection may be rebuilt independently. */
@Entity(tableName = "selected_playlists")
data class SelectedPlaylistEntity(
    @PrimaryKey val libraryId: String,
    val playlistId: String,
    val playlistUrl: String,
    val displayName: String,
    val lastSyncEpochMs: Long?,
    val lastSyncError: String?
)

@Entity(
    tableName = "songs",
    indices = [
        Index("youtubeVideoId"),
        Index(value = ["packageDocumentUri", "canonicalExternal"]),
        Index(value = ["canonicalExternal", "createdAtEpochMs", "id"]),
        Index(value = ["manifestFileSha256"], unique = false)
    ]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val youtubeVideoId: String,
    val playlistItemId: String?,
    val canonicalUrl: String,
    val sourceTitle: String,
    val sourceArtist: String,
    val displayTitle: String?,
    val displayArtist: String?,
    val displayAlbum: String? = null,
    val displayYear: String? = null,
    @ColumnInfo(defaultValue = "'{}'")
    val additionalInfoJson: String = "{}",
    val durationMs: Long,
    val artworkPath: String?,
    val currentlyInSourcePlaylist: Boolean,
    val highLevelState: String,
    val acquiredAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val canonicalExternal: Boolean = false,
    val packageDocumentUri: String? = null,
    val manifestFileSha256: String? = null,
    val contentRevision: Long? = null,
    val manifestState: String? = null,
    val pcDiscoveryDismissed: Boolean = false
)

@Entity(
    tableName = "source_versions",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("songId"),
        Index("sha256"),
        Index(value = ["songId", "sha256"], unique = true)
    ]
)
data class SourceVersionEntity(
    @PrimaryKey val id: String,
    val songId: String,
    val localPath: String,
    val sha256: String,
    val codec: String,
    val container: String,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val engineVersion: String,
    val isCurrent: Boolean,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "separation_sets",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SourceVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceVersionId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("songId"), Index("sourceVersionId"), Index("remoteJobId")]
)
data class SeparationSetEntity(
    @PrimaryKey val id: String,
    val songId: String,
    val sourceVersionId: String,
    val sourceSha256: String,
    val provider: String,
    val modelId: String,
    val modelName: String,
    val remoteJobId: String?,
    val state: String,
    val isActive: Boolean,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "stem_assets",
    foreignKeys = [
        ForeignKey(
            entity = SeparationSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["separationSetId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("separationSetId")]
)
data class StemAssetEntity(
    @PrimaryKey val id: String,
    val separationSetId: String,
    val providerLabel: String,
    val normalizedCapability: String?,
    val localPath: String,
    val sha256: String,
    val durationMs: Long,
    val codec: String,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val validated: Boolean,
    val encoderDelaySamples: Int? = null,
    val encoderPaddingSamples: Int? = null
)

@Entity(
    tableName = "processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SourceVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceVersionId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("songId"),
        Index("sourceVersionId"),
        Index("state"),
        Index("remoteJobId")
    ]
)
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
    val songId: String,
    val sourceVersionId: String?,
    val kind: String,
    val provider: String?,
    val modelId: String?,
    val state: String,
    val remoteJobId: String?,
    val attempts: Int,
    val nextAttemptAtEpochMs: Long?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val updatedAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val libraryId: String? = null
)

@Entity(
    tableName = "analysis",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["songId"], unique = true), Index("sourceSha256")]
)
data class AnalysisEntity(
    @PrimaryKey val id: String,
    val songId: String,
    val sourceSha256: String,
    val algorithmVersion: String,
    val primaryBpm: Double?,
    val musicalKey: String?,
    val keyConfidence: Double?,
    val likelyMeter: String?,
    val tuningCents: Double?,
    val analyzedAtEpochMs: Long,
    val keyStatus: String? = null,
    val keyReason: String? = null,
    val tuningStatus: String? = null,
    val tuningReason: String? = null
)

@Entity(
    tableName = "asset_observations",
    primaryKeys = ["packageId", "relativePath"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["packageId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("packageId"), Index("expectedSha256"), Index("documentUri")]
)
data class AssetObservationEntity(
    val packageId: String,
    val relativePath: String,
    val documentUri: String,
    val byteLength: Long,
    val lastModifiedEpochMs: Long,
    val expectedSha256: String,
    val manifestFileSha256: String,
    val verifiedAtEpochMs: Long
)

data class CanonicalStorageTotals(val packages: Int, val stems: Int, val verifiedBytes: Long)

@Entity(tableName = "remote_playlists")
data class RemotePlaylistEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val canonicalUrl: String,
    val displayName: String,
    val snapshotRevision: Long,
    val synchronizedAtEpochMs: Long
)

@Entity(
    tableName = "remote_playlist_items",
    primaryKeys = ["playlistId", "remoteItemId"],
    foreignKeys = [
        ForeignKey(
            entity = RemotePlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["packageId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("playlistId"),
        Index("packageId"),
        Index(value = ["playlistId", "position"], unique = true),
        Index(value = ["playlistId", "sourceId"])
    ]
)
data class RemotePlaylistItemEntity(
    val playlistId: String,
    val remoteItemId: String,
    val sourceId: String,
    val packageId: String?,
    val position: Int,
    val available: Boolean
)

data class PlaylistRoomAuthority(
    val libraryCount: Int,
    val currentSourceCount: Int,
    val membershipSha256: String,
    val remotePlaylistCount: Int,
    val remoteItemCount: Int,
    val remoteSnapshotSha256: String
)

private fun MessageDigest.updateFramed(value: String?) {
    if (value == null) {
        update(byteArrayOf(-1, -1, -1, -1))
        return
    }
    val bytes = value.toByteArray(Charsets.UTF_8)
    val length = bytes.size
    update(
        byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte()
        )
    )
    update(bytes)
}

private fun MessageDigest.hexDigest(): String =
    digest().toHexString()

internal fun playlistMembershipSha256(songs: List<SongEntity>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    songs.sortedBy(SongEntity::id).forEach { song ->
        digest.updateFramed(song.id)
        digest.updateFramed(song.youtubeVideoId)
        digest.updateFramed(song.playlistItemId)
        digest.update((if (song.currentlyInSourcePlaylist) 1 else 0).toByte())
    }
    return digest.hexDigest()
}

internal fun remotePlaylistSnapshotSha256(
    playlists: List<RemotePlaylistEntity>,
    items: List<RemotePlaylistItemEntity>
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    playlists.sortedBy(RemotePlaylistEntity::id).forEach { playlist ->
        digest.updateFramed(playlist.id)
        digest.updateFramed(playlist.provider)
        digest.updateFramed(playlist.canonicalUrl)
        digest.updateFramed(playlist.displayName)
        digest.updateFramed(playlist.snapshotRevision.toString())
        digest.updateFramed(playlist.synchronizedAtEpochMs.toString())
    }
    items.sortedWith(compareBy(RemotePlaylistItemEntity::playlistId)
        .thenBy(RemotePlaylistItemEntity::position)
        .thenBy(RemotePlaylistItemEntity::remoteItemId)).forEach { item ->
        digest.updateFramed(item.playlistId)
        digest.updateFramed(item.remoteItemId)
        digest.updateFramed(item.sourceId)
        digest.updateFramed(item.packageId)
        digest.updateFramed(item.position.toString())
        digest.update((if (item.available) 1 else 0).toByte())
    }
    return digest.hexDigest()
}

internal fun playlistRoomAuthority(
    songs: List<SongEntity>,
    playlists: List<RemotePlaylistEntity>,
    items: List<RemotePlaylistItemEntity>
): PlaylistRoomAuthority = PlaylistRoomAuthority(
    libraryCount = songs.size,
    currentSourceCount = songs.count(SongEntity::currentlyInSourcePlaylist),
    membershipSha256 = playlistMembershipSha256(songs),
    remotePlaylistCount = playlists.size,
    remoteItemCount = items.size,
    remoteSnapshotSha256 = remotePlaylistSnapshotSha256(playlists, items)
)


data class SongSnapshot(
    @Embedded val song: SongEntity,
    val sourceHash: String?,
    val sourcePath: String?,
    val sourceCodec: String?,
    val sourceContainer: String?,
    val primaryBpm: Double?,
    val musicalKey: String?,
    val likelyMeter: String?,
    val playlistPosition: Int?,
    val hasStems: Boolean,
    val stemRevision: String?,
    val keyStatus: String? = null,
    val keyReason: String? = null
)

data class CanonicalPackageProjectionCommit(
    val song: SongEntity,
    val analysis: AnalysisEntity?
)

internal fun promotedCanonicalPackageProjection(
    song: SongEntity,
    analysis: AnalysisEntity?,
    existingSong: SongEntity?
): CanonicalPackageProjectionCommit {
    require(song.canonicalExternal && song.manifestState == "enriched") {
        "COMPLETE_CANONICAL_PACKAGE_REQUIRED"
    }
    val promotedSong = if (existingSong != null) {
        song.copy(
            youtubeVideoId = existingSong.youtubeVideoId,
            playlistItemId = existingSong.playlistItemId,
            canonicalUrl = existingSong.canonicalUrl,
            sourceTitle = existingSong.sourceTitle,
            sourceArtist = existingSong.sourceArtist,
            currentlyInSourcePlaylist = existingSong.currentlyInSourcePlaylist,
            createdAtEpochMs = existingSong.createdAtEpochMs,
            acquiredAtEpochMs = existingSong.acquiredAtEpochMs ?: song.acquiredAtEpochMs,
            artworkPath = publishedArtworkSource(
                primarySource = song.artworkPath,
                additionalInfoJson = "{}",
                youtubeVideoId = null
            ) ?: publishedArtworkSource(
                primarySource = existingSong.artworkPath,
                additionalInfoJson = "{}",
                youtubeVideoId = null
            )
        )
    } else song
    return CanonicalPackageProjectionCommit(
        song = promotedSong,
        analysis = analysis
    )
}

data class CanonicalProjectionReuseFacts(
    val songsById: Map<String, SongEntity>,
    val currentSourcesBySongId: Map<String, SourceVersionEntity>,
    val sourceVersionsBySongId: Map<String, List<SourceVersionEntity>>,
    val observationsByPackageId: Map<String, List<AssetObservationEntity>>,
    val setsById: Map<String, SeparationSetEntity>,
    val setsBySongId: Map<String, List<SeparationSetEntity>>,
    val stemsBySetId: Map<String, List<StemAssetEntity>>,
    val analysesBySongId: Map<String, AnalysisEntity>,
    val songIdsNeedingCatalogSanitization: Set<String>
)

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertLibraryRootIdentity(value: LibraryRootIdentityEntity)

    @Query("SELECT * FROM library_root_identity WHERE slot = :slot LIMIT 1")
    suspend fun libraryRootIdentity(slot: String = "active"): LibraryRootIdentityEntity?

    /** Selection and its global projection change together; external folder writes follow this commit. */
    @Transaction
    suspend fun commitRootSelection(
        expected: LibraryRootIdentityEntity?,
        selected: LibraryRootIdentityEntity
    ) {
        check(libraryRootIdentity() == expected) { "ROOT_IDENTITY_CHANGED" }
        check(selected.slot == "active" && selected.libraryId.isNotBlank() &&
            selected.treeUri.isNotBlank() && selected.epoch > 0L && selected.state == "RESTORING") {
            "ROOT_IDENTITY_INVALID"
        }
        if (expected?.libraryId != selected.libraryId || expected.treeUri != selected.treeUri) {
            check(!hasRootSwitchBlockingSeparationSafetyFacts()) { RETAINED_OPERATION_ROOT_REQUIRED }
            clearActiveLibraryProjection()
        }
        upsertLibraryRootIdentity(selected)
        check(libraryRootIdentity() == selected) { "ROOT_IDENTITY_COMMIT_FAILED" }
    }

    @Query("UPDATE library_root_identity SET state = :state, updatedAtEpochMs = :updatedAtEpochMs " +
        "WHERE slot = 'active' AND libraryId = :libraryId AND treeUri = :treeUri AND epoch = :epoch")
    suspend fun markExactLibraryRootState(
        libraryId: String,
        treeUri: String,
        epoch: Long,
        state: String,
        updatedAtEpochMs: Long
    ): Int

    @Query(
        "SELECT * FROM source_bindings " +
            "WHERE libraryId = :libraryId AND sourceId = :sourceId LIMIT 1"
    )
    suspend fun sourceBinding(libraryId: String, sourceId: String): SourceBindingEntity?

    @Query(
        "SELECT * FROM source_bindings " +
            "WHERE libraryId = :libraryId AND state = 'ACTIVE' ORDER BY sourceId"
    )
    suspend fun activeSourceBindings(libraryId: String): List<SourceBindingEntity>

    @Query(
        "SELECT * FROM source_bindings WHERE libraryId = :libraryId " +
            "AND state = 'DELETED' ORDER BY sourceId"
    )
    suspend fun blockedSourceBindings(libraryId: String): List<SourceBindingEntity>

    @Query("SELECT * FROM source_bindings WHERE libraryId = :libraryId ORDER BY sourceId")
    suspend fun sourceBindingsForLibrary(libraryId: String): List<SourceBindingEntity>

    @Query("SELECT count(*) FROM source_bindings WHERE libraryId = :libraryId")
    suspend fun sourceBindingCount(libraryId: String): Int

    @Upsert
    suspend fun upsertSourceBinding(value: SourceBindingEntity)

    @Upsert
    suspend fun upsertSourceBindings(values: List<SourceBindingEntity>)


    @Query(
        "DELETE FROM source_bindings " +
            "WHERE libraryId = :libraryId AND sourceId IN (:sourceIds)"
    )
    suspend fun deleteSourceBindings(libraryId: String, sourceIds: List<String>): Int

    /** Replaces verified live rows while retaining exact deletion tombstones and eliding no-ops. */
    @Transaction
    suspend fun rebuildVerifiedSourceBindings(
        libraryId: String,
        verifiedBindings: List<SourceBindingEntity>
    ): Int {
        val desired = verifiedBindings.associateBy(SourceBindingEntity::sourceId)
        check(desired.size == verifiedBindings.size) { "SOURCE_BINDING_AUTHORITY_INVALID" }
        val current = sourceBindingsForLibrary(libraryId)
        current.forEach { validateSourceBindingEntity(it, libraryId) }
        verifiedBindings.forEach { value ->
            validateSourceBindingEntity(value, libraryId)
            check(value.state == "ACTIVE") { "SOURCE_BINDING_AUTHORITY_INVALID" }
        }
        val currentBySource = current.associateBy(SourceBindingEntity::sourceId)
        val tombstones = current.asSequence()
            .filter { it.state == "DELETED" }
            .mapTo(hashSetOf(), SourceBindingEntity::sourceId)
        val stale = current.asSequence()
            .filter { it.state != "DELETED" && it.sourceId !in desired }
            .map(SourceBindingEntity::sourceId)
            .toList()
        var changed = 0
        stale.chunked(512).forEach { sourceIds ->
            changed += deleteSourceBindings(libraryId, sourceIds)
        }
        val writes = verifiedBindings.filter { desiredBinding ->
            if (desiredBinding.sourceId in tombstones) return@filter false
            val existing = currentBySource[desiredBinding.sourceId]
            existing == null || existing.state != "ACTIVE" ||
                existing.packageId != desiredBinding.packageId
        }
        if (writes.isNotEmpty()) upsertSourceBindings(writes)
        return changed + writes.size
    }

    @Query(
        """
        UPDATE library_root_identity
        SET state = 'UNAVAILABLE', updatedAtEpochMs = :updatedAtEpochMs
        WHERE slot = 'active'
          AND libraryId = :libraryId
          AND treeUri = :treeUri
          AND epoch = :epoch
        """
    )
    suspend fun markExactLibraryRootUnavailable(
        libraryId: String,
        treeUri: String,
        epoch: Long,
        updatedAtEpochMs: Long
    ): Int

    @Query("SELECT * FROM selected_playlists WHERE libraryId = :libraryId LIMIT 1")
    suspend fun selectedPlaylist(libraryId: String): SelectedPlaylistEntity?

    @Upsert
    suspend fun upsertSelectedPlaylist(value: SelectedPlaylistEntity)

    @Query(
        """
        SELECT
            songs.id AS id,
            songs.youtubeVideoId AS youtubeVideoId,
            songs.playlistItemId AS playlistItemId,
            songs.canonicalUrl AS canonicalUrl,
            substr(songs.sourceTitle, 1, 512) AS sourceTitle,
            substr(songs.sourceArtist, 1, 512) AS sourceArtist,
            substr(songs.displayTitle, 1, 512) AS displayTitle,
            substr(songs.displayArtist, 1, 512) AS displayArtist,
            substr(songs.displayAlbum, 1, 256) AS displayAlbum,
            substr(songs.displayYear, 1, 64) AS displayYear,
            CASE WHEN length(songs.additionalInfoJson) <= 16384
                THEN songs.additionalInfoJson ELSE '{}' END AS additionalInfoJson,
            songs.durationMs AS durationMs,
            songs.artworkPath AS artworkPath,
            songs.currentlyInSourcePlaylist AS currentlyInSourcePlaylist,
            songs.highLevelState AS highLevelState,
            songs.acquiredAtEpochMs AS acquiredAtEpochMs,
            songs.createdAtEpochMs AS createdAtEpochMs,
            songs.canonicalExternal AS canonicalExternal,
            songs.packageDocumentUri AS packageDocumentUri,
            songs.manifestFileSha256 AS manifestFileSha256,
            songs.contentRevision AS contentRevision,
            songs.manifestState AS manifestState,
            songs.pcDiscoveryDismissed AS pcDiscoveryDismissed,
            current_source.sha256 AS sourceHash,
            current_source.localPath AS sourcePath,
            current_source.codec AS sourceCodec,
            current_source.container AS sourceContainer,
            analysis.primaryBpm AS primaryBpm,
            substr(analysis.musicalKey, 1, 64) AS musicalKey,
            analysis.keyStatus AS keyStatus,
            substr(analysis.keyReason, 1, 256) AS keyReason,
            substr(analysis.likelyMeter, 1, 64) AS likelyMeter,
            (
                SELECT MIN(remote_playlist_items.position) FROM remote_playlist_items
                WHERE remote_playlist_items.packageId = songs.id
            ) AS playlistPosition,
            EXISTS(
                SELECT 1 FROM separation_sets
                WHERE separation_sets.songId = songs.id
                    AND separation_sets.sourceVersionId = current_source.id
                    AND separation_sets.isActive = 1
                    AND separation_sets.state = 'COMPLETE'
            ) AS hasStems,
            (
                SELECT separation_sets.id FROM separation_sets
                WHERE separation_sets.songId = songs.id
                    AND separation_sets.sourceVersionId = current_source.id
                    AND separation_sets.isActive = 1
                    AND separation_sets.state = 'COMPLETE'
                LIMIT 1
            ) AS stemRevision
        FROM songs
        LEFT JOIN source_versions AS current_source
            ON current_source.songId = songs.id
            AND current_source.isCurrent = 1
        LEFT JOIN analysis
            ON analysis.songId = songs.id
            AND analysis.sourceSha256 = current_source.sha256
        WHERE songs.canonicalExternal = 1 AND songs.manifestState = 'enriched'
        ORDER BY
            songs.currentlyInSourcePlaylist DESC,
            CASE WHEN playlistPosition IS NULL THEN 1 ELSE 0 END ASC,
            playlistPosition ASC,
            songs.createdAtEpochMs ASC,
            songs.id ASC
        """
    )
    fun observeSongSnapshots(): Flow<List<SongSnapshot>>

    @Query(
        """
        SELECT
            id,
            youtubeVideoId,
            playlistItemId,
            canonicalUrl,
            substr(sourceTitle, 1, 512) AS sourceTitle,
            substr(sourceArtist, 1, 512) AS sourceArtist,
            substr(displayTitle, 1, 512) AS displayTitle,
            substr(displayArtist, 1, 512) AS displayArtist,
            substr(displayAlbum, 1, 256) AS displayAlbum,
            substr(displayYear, 1, 64) AS displayYear,
            CASE WHEN length(additionalInfoJson) <= 16384
                THEN additionalInfoJson ELSE '{}' END AS additionalInfoJson,
            durationMs,
            artworkPath,
            currentlyInSourcePlaylist,
            highLevelState,
            acquiredAtEpochMs,
            createdAtEpochMs,
            canonicalExternal,
            packageDocumentUri,
            manifestFileSha256,
            contentRevision,
            manifestState,
            pcDiscoveryDismissed
        FROM songs ORDER BY createdAtEpochMs
        """
    )
    suspend fun allSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE highLevelState = 'AWAITING_PC' ORDER BY createdAtEpochMs")
    suspend fun pendingPcSongs(): List<SongEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM songs WHERE youtubeVideoId = :videoId AND highLevelState = 'AWAITING_PC')")
    suspend fun hasPendingPcSong(videoId: String): Boolean

    @Query("UPDATE songs SET highLevelState = 'DISCOVERED', pcDiscoveryDismissed = 1 WHERE id = :id AND highLevelState = 'AWAITING_PC'")
    suspend fun clearPendingPcSong(id: String): Int

    @Query("UPDATE songs SET highLevelState = 'NEEDS_ATTENTION', manifestState = 'invalid' WHERE id = :id AND canonicalExternal = 1")
    suspend fun invalidateCanonicalProjection(id: String): Int

    @Query(
        """
        SELECT
            id,
            youtubeVideoId,
            playlistItemId,
            canonicalUrl,
            substr(sourceTitle, 1, 512) AS sourceTitle,
            substr(sourceArtist, 1, 512) AS sourceArtist,
            substr(displayTitle, 1, 512) AS displayTitle,
            substr(displayArtist, 1, 512) AS displayArtist,
            substr(displayAlbum, 1, 256) AS displayAlbum,
            substr(displayYear, 1, 64) AS displayYear,
            CASE WHEN length(additionalInfoJson) <= 16384
                THEN additionalInfoJson ELSE '{}' END AS additionalInfoJson,
            durationMs,
            artworkPath,
            currentlyInSourcePlaylist,
            highLevelState,
            acquiredAtEpochMs,
            createdAtEpochMs,
            canonicalExternal,
            packageDocumentUri,
            manifestFileSha256,
            contentRevision,
            manifestState,
            pcDiscoveryDismissed
        FROM songs WHERE id IN (:songIds)
        """
    )
    suspend fun songsByIds(songIds: List<String>): List<SongEntity>


    @Query("SELECT * FROM source_versions WHERE songId IN (:songIds)")
    suspend fun sourceVersionsForSongs(songIds: List<String>): List<SourceVersionEntity>

    @Query(
        """
        SELECT id, songId, sourceSha256, algorithmVersion, primaryBpm,
            substr(musicalKey, 1, 64) AS musicalKey, keyConfidence,
            substr(likelyMeter, 1, 64) AS likelyMeter, tuningCents,
            analyzedAtEpochMs, keyStatus, keyReason, tuningStatus, tuningReason
        FROM analysis WHERE songId IN (:songIds)
        """
    )
    suspend fun analysesForSongs(songIds: List<String>): List<AnalysisEntity>


    @Query(
        """
        SELECT id FROM songs WHERE id IN (:songIds) AND (
            length(additionalInfoJson) > 16384 OR
            length(sourceTitle) > 512 OR length(sourceArtist) > 512 OR
            length(displayTitle) > 512 OR length(displayArtist) > 512 OR
            length(displayAlbum) > 256 OR length(displayYear) > 64
        )
        """
    )
    suspend fun songIdsNeedingCatalogSanitization(songIds: List<String>): List<String>

    @Query("SELECT * FROM remote_playlists ORDER BY id")
    suspend fun allRemotePlaylists(): List<RemotePlaylistEntity>

    @Query(
        """
        SELECT * FROM remote_playlist_items
        ORDER BY playlistId, position, remoteItemId
        """
    )
    suspend fun allRemotePlaylistItems(): List<RemotePlaylistItemEntity>

    @Transaction
    suspend fun playlistRoomAuthority(): PlaylistRoomAuthority = playlistRoomAuthority(
        songs = allSongs(),
        playlists = allRemotePlaylists(),
        items = allRemotePlaylistItems()
    )

    @Query("""
        WITH admitted AS (SELECT id FROM songs WHERE canonicalExternal = 1 AND highLevelState = 'READY' AND manifestState = 'enriched')
        SELECT (SELECT COUNT(*) FROM admitted) AS packages,
            (SELECT COUNT(*) FROM stem_assets INNER JOIN separation_sets ON separation_sets.id = stem_assets.separationSetId
                WHERE separation_sets.songId IN (SELECT id FROM admitted)) AS stems,
            (SELECT COALESCE(SUM(byteLength), 0) FROM asset_observations WHERE packageId IN (SELECT id FROM admitted)) AS verifiedBytes
    """)
    suspend fun canonicalStorageTotals(): CanonicalStorageTotals

    @Query("""
        WITH admitted AS (SELECT id FROM songs WHERE canonicalExternal = 1 AND highLevelState = 'READY'
            AND manifestState = 'enriched' AND id IN (:packageIds))
        SELECT (SELECT COUNT(*) FROM admitted) AS packages,
            (SELECT COUNT(*) FROM stem_assets INNER JOIN separation_sets ON separation_sets.id = stem_assets.separationSetId
                WHERE separation_sets.songId IN (SELECT id FROM admitted)) AS stems,
            (SELECT COALESCE(SUM(byteLength), 0) FROM asset_observations WHERE packageId IN (SELECT id FROM admitted)) AS verifiedBytes
    """)
    suspend fun canonicalStorageTotalsForPackages(packageIds: List<String>): CanonicalStorageTotals

    @Query("SELECT id AS packageId, packageDocumentUri AS documentUri FROM songs WHERE canonicalExternal = 1 AND packageDocumentUri IN (:uris) UNION SELECT packageId, documentUri FROM asset_observations WHERE documentUri IN (:uris)")
    suspend fun canonicalPackagesForDocuments(uris: List<String>): List<CanonicalPackageDocument>

    @Query("SELECT id FROM songs WHERE canonicalExternal = 1")
    suspend fun canonicalExternalSongIds(): List<String>

    @Query("SELECT * FROM asset_observations WHERE packageId = :packageId")
    suspend fun assetObservationsForPackage(packageId: String): List<AssetObservationEntity>

    @Query("SELECT * FROM asset_observations WHERE packageId IN (:packageIds)")
    suspend fun assetObservationsForPackages(
        packageIds: List<String>
    ): List<AssetObservationEntity>

    @Upsert
    suspend fun upsertAssetObservations(observations: List<AssetObservationEntity>)

    @Query("DELETE FROM asset_observations WHERE packageId = :packageId")
    suspend fun deleteAssetObservations(packageId: String)

    @Query(
        "SELECT * FROM processing_jobs " +
            "WHERE state NOT IN ('COMPLETE', 'OBSOLETE') ORDER BY createdAtEpochMs"
    )
    fun observeActiveJobs(): Flow<List<ProcessingJobEntity>>

    @Query("SELECT DISTINCT songId FROM processing_jobs WHERE state NOT IN ('COMPLETE', 'OBSOLETE')")
    suspend fun activeProcessingSongIds(): List<String>

    @Query(
        """
        SELECT * FROM processing_jobs
        WHERE kind = 'SOURCE_SELECTION'
            AND state NOT IN ('COMPLETE', 'FAILED', 'OBSOLETE')
        ORDER BY createdAtEpochMs, id
        """
    )
    suspend fun automaticPreparationOwnerJobs(): List<ProcessingJobEntity>

    @Query(
        """
        SELECT processing_jobs.id AS jobId,
            processing_jobs.songId AS songId,
            songs.youtubeVideoId AS videoId,
            processing_jobs.sourceVersionId AS sourceVersionId,
            job_source.songId AS sourceSongId,
            job_source.sha256 AS sourceSha256,
            current_source.id AS currentSourceVersionId,
            current_source.sha256 AS currentSourceSha256,
            processing_jobs.kind AS kind,
            processing_jobs.provider AS provider,
            processing_jobs.modelId AS modelId,
            processing_jobs.state AS state,
            processing_jobs.libraryId AS libraryId,
            processing_jobs.remoteJobId AS remoteJobId,
            processing_jobs.nextAttemptAtEpochMs AS nextAttemptAtEpochMs,
            processing_jobs.lastErrorCode AS lastErrorCode,
            processing_jobs.createdAtEpochMs AS createdAtEpochMs
        FROM processing_jobs
        LEFT JOIN songs ON songs.id = processing_jobs.songId
        LEFT JOIN source_versions AS job_source
            ON job_source.id = processing_jobs.sourceVersionId
        LEFT JOIN source_versions AS current_source
            ON current_source.songId = processing_jobs.songId
            AND current_source.isCurrent = 1
        WHERE processing_jobs.kind = 'SOURCE_SELECTION'
            AND processing_jobs.state NOT IN ('COMPLETE', 'FAILED', 'OBSOLETE')
        ORDER BY processing_jobs.createdAtEpochMs, processing_jobs.id
        """
    )
    suspend fun automaticPreparationOwnerFacts(): List<PreparationJobFact>


    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM processing_jobs
            WHERE kind = 'SOURCE_SELECTION'
                AND state NOT IN ('COMPLETE', 'OBSOLETE')
                AND (
                    (remoteJobId IS NOT NULL AND TRIM(remoteJobId) != '')
                    OR state NOT IN ('CREATED', 'FAILED')
                )
        )
        """
    )
    suspend fun hasRootSwitchBlockingSeparationSafetyFacts(): Boolean

    @Query("SELECT * FROM processing_jobs WHERE songId = :songId AND libraryId = :libraryId " +
        "AND kind = 'SOURCE_SELECTION' AND state NOT IN ('COMPLETE', 'OBSOLETE')")
    suspend fun unresolvedSourceJobsForSong(songId: String, libraryId: String): List<ProcessingJobEntity>

    @Query("SELECT * FROM processing_jobs WHERE id = :jobId LIMIT 1")
    suspend fun findJobById(jobId: String): ProcessingJobEntity?

    @Query(
        "SELECT * FROM processing_jobs WHERE id = :jobId AND libraryId = :libraryId LIMIT 1"
    )
    suspend fun findJobByIdForLibrary(jobId: String, libraryId: String): ProcessingJobEntity?


    @Query(
        """
        SELECT * FROM processing_jobs
        WHERE kind = :kind
            AND state NOT IN ('COMPLETE', 'FAILED', 'OBSOLETE', 'SUBMISSION_UNKNOWN')
            AND (nextAttemptAtEpochMs IS NULL OR nextAttemptAtEpochMs <= :now)
        ORDER BY createdAtEpochMs
        """
    )
    suspend fun dueOpenJobs(kind: String, now: Long): List<ProcessingJobEntity>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun findSongById(songId: String): SongEntity?

    @Query("SELECT * FROM source_versions WHERE songId = :songId AND isCurrent = 1 LIMIT 1")
    suspend fun currentSource(songId: String): SourceVersionEntity?

    @Query(
        """
        SELECT * FROM source_versions
        WHERE songId = :songId
        ORDER BY isCurrent DESC, createdAtEpochMs DESC
        """
    )
    suspend fun sourcesForSong(songId: String): List<SourceVersionEntity>


    @Query("UPDATE source_versions SET localPath = :path WHERE id = :sourceId")
    suspend fun updateSourcePath(sourceId: String, path: String)



    @Query("SELECT * FROM separation_sets WHERE songId = :songId ORDER BY createdAtEpochMs")
    suspend fun separationSetsForSong(songId: String): List<SeparationSetEntity>

    @Query("SELECT * FROM separation_sets WHERE songId IN (:songIds)")
    suspend fun separationSetsForSongs(songIds: List<String>): List<SeparationSetEntity>

    @Query("SELECT * FROM analysis WHERE songId = :songId ORDER BY analyzedAtEpochMs DESC LIMIT 1")
    suspend fun latestAnalysis(songId: String): AnalysisEntity?

    @Query("SELECT * FROM separation_sets WHERE id = :setId LIMIT 1")
    suspend fun separationSetById(setId: String): SeparationSetEntity?

    @Query("SELECT * FROM stem_assets WHERE separationSetId = :setId")
    suspend fun stemsForSet(setId: String): List<StemAssetEntity>

    @Query(
        "SELECT stem_assets.* FROM stem_assets " +
            "INNER JOIN separation_sets ON separation_sets.id = stem_assets.separationSetId " +
            "WHERE separation_sets.songId IN (:songIds)"
    )
    suspend fun stemAssetsForSongs(songIds: List<String>): List<StemAssetEntity>

    @Transaction
    suspend fun canonicalProjectionReuseFacts(
        songIds: List<String>
    ): CanonicalProjectionReuseFacts {
        require(songIds.isNotEmpty())
        val sources = sourceVersionsForSongs(songIds)
        val sets = separationSetsForSongs(songIds)
        return CanonicalProjectionReuseFacts(
            songsById = songsByIds(songIds).associateBy(SongEntity::id),
            currentSourcesBySongId = sources.filter(SourceVersionEntity::isCurrent)
                .associateBy(SourceVersionEntity::songId),
            sourceVersionsBySongId = sources.groupBy(SourceVersionEntity::songId),
            observationsByPackageId = assetObservationsForPackages(songIds)
                .groupBy(AssetObservationEntity::packageId),
            setsById = sets.associateBy(SeparationSetEntity::id),
            setsBySongId = sets.groupBy(SeparationSetEntity::songId),
            stemsBySetId = stemAssetsForSongs(songIds)
                .groupBy(StemAssetEntity::separationSetId),
            analysesBySongId = analysesForSongs(songIds).associateBy(AnalysisEntity::songId),
            songIdsNeedingCatalogSanitization =
                songIdsNeedingCatalogSanitization(songIds).toSet()
        )
    }

    @Query("SELECT localPath FROM source_versions WHERE songId = :songId")
    suspend fun sourcePathsForSong(songId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSong(song: SongEntity)

    @Upsert
    suspend fun upsertSongs(songs: List<SongEntity>)

    @Query("UPDATE songs SET packageDocumentUri = :uri WHERE id = :songId")
    suspend fun bindPackageDocumentUri(songId: String, uri: String): Int

    @Transaction
    suspend fun bindPublishedPackageDocument(fallback: SongEntity) {
        val current = findSongById(fallback.id)
        check(current?.packageDocumentUri.isNullOrBlank() ||
            current?.packageDocumentUri == fallback.packageDocumentUri) { "PACKAGE_DIRECTORY_IDENTITY_COMMIT_FAILED" }
        upsertSongs(listOf((current ?: fallback).copy(packageDocumentUri = fallback.packageDocumentUri)))
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSource(source: SourceVersionEntity)

    @Upsert
    suspend fun upsertJob(job: ProcessingJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSeparationSet(set: SeparationSetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStemAssets(stems: List<StemAssetEntity>)

    @Upsert
    suspend fun upsertAnalysis(analysis: AnalysisEntity)


    @Query("UPDATE songs SET currentlyInSourcePlaylist = 0, playlistItemId = NULL")
    suspend fun markAllSongsOutsideSourcePlaylist()



    /**
     * Rebuilds one canonical package projection from its external song.json.
     * The external package is committed first; this transaction is disposable.
     */
    @Transaction
    suspend fun replaceCanonicalPackageProjection(
        song: SongEntity,
        source: SourceVersionEntity,
        sets: List<SeparationSetEntity>,
        stems: List<StemAssetEntity>,
        analysis: AnalysisEntity?
    ): CanonicalPackageProjectionCommit {
        return replaceCanonicalPackageProjectionRows(
            song,
            source,
            sets,
            stems,
            analysis
        )
    }

    suspend fun replaceCanonicalPackageProjectionRows(
        song: SongEntity,
        source: SourceVersionEntity,
        sets: List<SeparationSetEntity>,
        stems: List<StemAssetEntity>,
        analysis: AnalysisEntity?
    ): CanonicalPackageProjectionCommit {
        require(song.canonicalExternal && song.manifestState == "enriched") {
            "COMPLETE_CANONICAL_PACKAGE_REQUIRED"
        }
        require(source.songId == song.id && source.isCurrent)
        require(sets.all { it.songId == song.id && it.sourceVersionId == source.id })
        require(stems.all { stem -> sets.any { it.id == stem.separationSetId } })
        val existing = findSongById(song.id)
        val promoted = promotedCanonicalPackageProjection(
            song = song,
            analysis = analysis,
            existingSong = existing
        )
        upsertSongs(listOf(promoted.song))
        val priorSource = currentSource(song.id)
        val sameSource = priorSource?.id == source.id && priorSource.sha256 == source.sha256
        if (sameSource) {
            // Replacing an unchanged original would SET NULL the source of an active optional
            // stems job. Only its disposable separation projection needs rebuilding.
            deleteSeparationSetsForSong(song.id)
            updateSourcePath(source.id, source.localPath)
        } else {
            // A genuinely replaced source retires the prior graph under the existing policy.
            deleteSourcesForSong(song.id)
            insertSource(source)
        }
        deleteAnalysisForSong(song.id)
        for (set in sets) insertSeparationSet(set)
        if (stems.isNotEmpty()) insertStemAssets(stems)
        promoted.analysis?.let { upsertAnalysis(it) }
        return promoted
    }

    @Transaction
    suspend fun replaceAssetObservations(
        packageId: String,
        observations: List<AssetObservationEntity>
    ) {
        require(observations.all { it.packageId == packageId })
        deleteAssetObservations(packageId)
        if (observations.isNotEmpty()) upsertAssetObservations(observations)
    }

    @Upsert
    suspend fun upsertRemotePlaylist(playlist: RemotePlaylistEntity)

    @Upsert
    suspend fun upsertRemotePlaylistItems(items: List<RemotePlaylistItemEntity>)

    @Transaction
    suspend fun removeMissingCanonicalPackageProjections(retainedPackageIds: Set<String>) {
        canonicalExternalSongIds()
            .filterNot(retainedPackageIds::contains)
            .forEach { deleteSongRow(it) }
    }

    @Transaction
    suspend fun removeCanonicalPackageProjection(packageId: String) {
        val song = findSongById(packageId) ?: return
        if (song.canonicalExternal) deleteSongRow(packageId)
    }

    @Query("DELETE FROM separation_sets WHERE songId = :songId")
    suspend fun deleteSeparationSetsForSong(songId: String)

    @Query("DELETE FROM analysis WHERE songId = :songId")
    suspend fun deleteAnalysisForSong(songId: String)

    @Query("DELETE FROM source_versions WHERE songId = :songId")
    suspend fun deleteSourcesForSong(songId: String)

    @Query("""DELETE FROM songs WHERE canonicalExternal = 0 AND (packageDocumentUri IS NULL OR trim(packageDocumentUri) = '')
        AND currentlyInSourcePlaylist = 0 AND highLevelState != 'AWAITING_PC' AND id NOT IN
        (SELECT songId FROM processing_jobs WHERE state NOT IN ('COMPLETE', 'OBSOLETE'))""")
    suspend fun deleteUnreferencedDiscoverySongs()

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSongRow(songId: String)

    @Query("DELETE FROM remote_playlist_items")
    suspend fun clearRemotePlaylistItems()

    @Query("DELETE FROM remote_playlists")
    suspend fun clearRemotePlaylists()

    @Query("DELETE FROM processing_jobs")
    suspend fun clearProcessingJobs()

    @Query("DELETE FROM songs")
    suspend fun clearSongRows()

    /** Clears the single active media projection; per-library source tombstones remain durable. */
    @Transaction
    suspend fun clearActiveLibraryProjection() {
        clearRemotePlaylistItems()
        clearRemotePlaylists()
        clearProcessingJobs()
        clearSongRows()
    }


}

@Database(
    entities = [
        LibraryRootIdentityEntity::class,
        SongEntity::class,
        SourceVersionEntity::class,
        SeparationSetEntity::class,
        StemAssetEntity::class,
        ProcessingJobEntity::class,
        AnalysisEntity::class,
        AssetObservationEntity::class,
        RemotePlaylistEntity::class,
        RemotePlaylistItemEntity::class,
        SelectedPlaylistEntity::class,
        SourceBindingEntity::class
    ],
    version = 11,
    exportSchema = true
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: buildFreshProjectionDatabase(context, "stemwise-library.db")
                    .also { instance = it }
            }

        internal fun buildFreshProjectionDatabase(
            context: Context,
            databaseName: String
        ): LibraryDatabase = Room.databaseBuilder(
            context.applicationContext,
            LibraryDatabase::class.java,
            databaseName
        )
            .build()
    }
}

data class CanonicalPackageDocument(val packageId: String, val documentUri: String)
