package app.stemwise.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PermanentDeletionCoordinatorTest {
    @Test
    fun `only confirmed deletion can rebase onto the same root after an epoch change`() {
        val current = RootAuthority(
            libraryId = "00000000-0000-4000-8000-000000000001",
            treeUri = "content://provider/tree/library",
            epoch = 9L
        )

        assertEquals(
            DeletionJournalReplayMode.CURRENT,
            deletionJournalReplayMode(
                current,
                current.libraryId,
                current.treeUri,
                current.epoch,
                "SOURCE_BINDING_COMMIT"
            )
        )
        assertEquals(
            DeletionJournalReplayMode.REBASE_CONFIRMED,
            deletionJournalReplayMode(
                current,
                current.libraryId,
                current.treeUri,
                7L,
                "DELETE_CONFIRMED"
            )
        )
        assertEquals(
            DeletionJournalReplayMode.SETTLE_PREPARED,
            deletionJournalReplayMode(
                current,
                current.libraryId,
                current.treeUri,
                7L,
                "PREPARED"
            )
        )
        assertEquals(
            DeletionJournalReplayMode.REBASE_CONFIRMED,
            deletionJournalReplayMode(
                current,
                current.libraryId,
                current.treeUri,
                7L,
                "SOURCE_BINDING_COMMIT"
            )
        )
        assertEquals(
            DeletionJournalReplayMode.SETTLE_ABSENT,
            deletionJournalReplayMode(
                current,
                current.libraryId,
                current.treeUri,
                7L,
                "COMPLETE"
            )
        )
        assertEquals(
            DeletionJournalReplayMode.RETAIN,
            deletionJournalReplayMode(
                current,
                current.libraryId,
                "content://provider/tree/replacement",
                7L,
                "DELETE_CONFIRMED"
            )
        )
        assertEquals(
            DeletionJournalReplayMode.RETAIN,
            deletionJournalReplayMode(
                current,
                "00000000-0000-4000-8000-000000000002",
                current.treeUri,
                7L,
                "DELETE_CONFIRMED"
            )
        )
    }

    @Test
    fun `only a complete root snapshot can prove a stale deletion target absent`() {
        val current = deletionAuthority()
        val plan = deletionPlan()

        assertFalse(
            completeDeletionSnapshotProvesTargetAbsent(
                current,
                current.libraryId,
                current.treeUri,
                "abc123DEF_-",
                plan,
                recordedDirectoryBoundToCurrentRoot = true,
                completeDirectories = null
            )
        )
        assertTrue(
            completeDeletionSnapshotProvesTargetAbsent(
                current,
                current.libraryId,
                current.treeUri,
                "abc123DEF_-",
                plan,
                recordedDirectoryBoundToCurrentRoot = true,
                completeDirectories = emptyList()
            )
        )
    }

    @Test
    fun `absence finalization retains changed authority or any surviving target identity`() {
        val current = deletionAuthority()
        val plan = deletionPlan()
        val survivingPackage = DeletionDirectoryIdentity(
            packageId = "abc123DEF_-",
            directoryUri = "content://provider/tree/library/document/replacement",
            directoryId = "replacement"
        )
        val survivingRecordedDirectory = DeletionDirectoryIdentity(
            packageId = "ZYX987abc_-",
            directoryUri = plan.directoryUri,
            directoryId = plan.directoryId
        )

        assertFalse(
            completeDeletionSnapshotProvesTargetAbsent(
                current,
                current.libraryId,
                "content://provider/tree/other",
                "abc123DEF_-",
                plan,
                recordedDirectoryBoundToCurrentRoot = true,
                completeDirectories = emptyList()
            )
        )
        assertFalse(
            completeDeletionSnapshotProvesTargetAbsent(
                current,
                current.libraryId,
                current.treeUri,
                "abc123DEF_-",
                plan,
                recordedDirectoryBoundToCurrentRoot = false,
                completeDirectories = emptyList()
            )
        )
        assertFalse(
            completeDeletionSnapshotProvesTargetAbsent(
                current,
                current.libraryId,
                current.treeUri,
                "abc123DEF_-",
                plan,
                recordedDirectoryBoundToCurrentRoot = true,
                completeDirectories = listOf(survivingPackage)
            )
        )
        assertFalse(
            completeDeletionSnapshotProvesTargetAbsent(
                current,
                current.libraryId,
                current.treeUri,
                "abc123DEF_-",
                plan,
                recordedDirectoryBoundToCurrentRoot = true,
                completeDirectories = listOf(survivingRecordedDirectory)
            )
        )
    }

    @Test
    fun `stale tombstone finalization is idempotent and never crosses authority`() {
        val current = deletionAuthority()
        val transactionId = rebasedDeletionTransactionId(current.epoch, "old-transaction")

        repeat(2) {
            assertEquals(
                DeletionJournalReplayMode.REBASE_CONFIRMED,
                deletionJournalReplayMode(
                    current,
                    current.libraryId,
                    current.treeUri,
                    current.epoch - 1L,
                    "SOURCE_BINDING_COMMIT"
                )
            )
            assertEquals(
                transactionId,
                rebasedDeletionTransactionId(current.epoch, "old-transaction")
            )
        }
        assertEquals(
            DeletionJournalReplayMode.RETAIN,
            deletionJournalReplayMode(
                current,
                "00000000-0000-4000-8000-000000000002",
                current.treeUri,
                current.epoch - 1L,
                "SOURCE_BINDING_COMMIT"
            )
        )
    }

    @Test
    fun `rebased deletion retries use one safe deterministic transaction id`() {
        val first = rebasedDeletionTransactionId(9L, "old-transaction")

        assertEquals(first, rebasedDeletionTransactionId(9L, "old-transaction"))
        assertNotEquals(first, rebasedDeletionTransactionId(10L, "old-transaction"))
        assertTrue(first.matches(Regex("^[A-Za-z0-9._-]{1,128}$")))
    }

    @Test
    fun `rebased deletion refuses changed content or document identity`() {
        val plan = deletionPlan()
        val original = plan.children.getValue("ORIGINAL.mp3")

        assertTrue(
            deletionTargetMatchesPlan(
                plan.directoryUri,
                plan.directoryId,
                plan.children,
                plan,
                requireContentIdentity = true
            )
        )
        assertFalse(
            deletionTargetMatchesPlan(
                plan.directoryUri,
                plan.directoryId,
                plan.children + ("ORIGINAL.mp3" to original.copy(sha256 = "b".repeat(64))),
                plan,
                requireContentIdentity = true
            )
        )
        assertFalse(
            deletionTargetMatchesPlan(
                plan.directoryUri,
                plan.directoryId,
                plan.children + ("ORIGINAL.mp3" to original.copy(documentId = "changed")),
                plan,
                requireContentIdentity = true
            )
        )
        assertFalse(
            deletionTargetMatchesPlan(
                plan.directoryUri,
                plan.directoryId,
                plan.children,
                plan.copy(contentIdentityRecorded = false),
                requireContentIdentity = true
            )
        )
    }

    @Test
    fun `rebased deletion accepts matching partial progress and rejects unexpected children`() {
        val plan = deletionPlan()

        assertTrue(
            deletionTargetMatchesPlan(
                plan.directoryUri,
                plan.directoryId,
                mapOf("ORIGINAL.mp3" to plan.children.getValue("ORIGINAL.mp3")),
                plan,
                requireContentIdentity = true
            )
        )
        assertTrue(
            deletionTargetMatchesPlan(
                plan.directoryUri,
                plan.directoryId,
                emptyMap(),
                plan,
                requireContentIdentity = true
            )
        )
        assertFalse(
            deletionTargetMatchesPlan(
                plan.directoryUri,
                plan.directoryId,
                plan.children + (
                    "unexpected.bin" to DeletionChildIdentity(
                        "unexpected-document",
                        "c".repeat(64),
                        1L
                    )
                ),
                plan,
                requireContentIdentity = true
            )
        )
    }

    @Test
    fun `same UUID at a different URI converges portable tombstone supersets and subsets`() {
        val libraryId = "00000000-0000-4000-8000-000000000001"
        val roomOnly = binding(libraryId, "abc123DEF_-", "DELETED", 1L)
        val portableOnly = binding(libraryId, "ZYX987abc_-", "DELETED", 2L)

        assertTrue(
            mergePortableBlockedBindings(libraryId, listOf(roomOnly), listOf(portableOnly)).toSet() ==
                setOf(roomOnly, portableOnly)
        )
        assertTrue(
            mergePortableBlockedBindings(
                libraryId,
                listOf(roomOnly, portableOnly),
                listOf(roomOnly)
            ).toSet() == setOf(roomOnly, portableOnly)
        )
    }

    @Test
    fun `portable tombstones dominate live Room rows and reject source package conflicts`() {
        val libraryId = "00000000-0000-4000-8000-000000000001"
        val live = binding(libraryId, "abc123DEF_-", "ACTIVE", 3L)
        val deleted = binding(libraryId, "abc123DEF_-", "DELETED", 2L)
        assertTrue(
            mergePortableBlockedBindings(libraryId, listOf(live), listOf(deleted)) ==
                listOf(deleted)
        )

        assertThrows(IllegalStateException::class.java) {
            mergePortableBlockedBindings(
                libraryId,
                listOf(live),
                listOf(
                    SourceBindingEntity(
                        libraryId,
                        live.sourceId,
                        "ZYX987abc_-",
                        "DELETED",
                        "test",
                        4L
                    )
                )
            )
        }
    }

    @Test
    fun `physical deletion failure remains a failure and does not alter projection`() = runBlocking {
        val error = IllegalStateException("provider rejected delete")
        var projectionTouched = false

        val result = completePermanentDeletion(
            songWorkCoordinator = SongWorkCoordinator(),
            packageId = "AbCdEf_12-3",
            deleteCanonicalPackage = { Result.failure(error) },
            removeCommittedProjection = { projectionTouched = true }
        )

        assertSame(error, result.exceptionOrNull())
        assertFalse(projectionTouched)
    }

    @Test
    fun successWithoutCanonicalCommitCannotRemoveTheProjection() = runBlocking {
        var projectionTouched = false
        val result = completePermanentDeletion(SongWorkCoordinator(), "AbCdEf_12-3",
            deleteCanonicalPackage = { Result.success(Unit) },
            removeCommittedProjection = { projectionTouched = true })
        assertEquals("DELETE_COMMIT_UNCONFIRMED", result.exceptionOrNull()?.message)
        assertFalse(projectionTouched)
    }

    @Test
    fun failureAfterCanonicalCommitStillSettlesProjectionAndRemainsAFailure() = runBlocking {
        val error = java.io.IOException("committed journal cleanup needs retry")
        var projectionTouched = false
        val result = completePermanentDeletion(SongWorkCoordinator(), "AbCdEf_12-3",
            deleteCanonicalPackage = { onCommitted -> onCommitted(); Result.failure(error) },
            removeCommittedProjection = { projectionTouched = true })
        assertSame(error, result.exceptionOrNull())
        assertTrue(projectionTouched)
    }

    @Test
    fun `committed deletion remains successful when projection refresh must retry`() = runBlocking {
        val result = completePermanentDeletion(
            songWorkCoordinator = SongWorkCoordinator(),
            packageId = "AbCdEf_12-3",
            deleteCanonicalPackage = { onCommitted -> onCommitted(); Result.success(Unit) },
            removeCommittedProjection = { error("database temporarily unavailable") }
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().projectionRefreshPending)
    }

    @Test
    fun `committed deletion with immediate projection removal needs no pending warning`() = runBlocking {
        var projectionTouched = false
        val result = completePermanentDeletion(
            songWorkCoordinator = SongWorkCoordinator(),
            packageId = "AbCdEf_12-3",
            deleteCanonicalPackage = { onCommitted -> onCommitted(); Result.success(Unit) },
            removeCommittedProjection = { projectionTouched = true }
        )

        assertTrue(projectionTouched)
        assertFalse(result.getOrThrow().projectionRefreshPending)
    }

    @Test
    fun `deletion waits for the same song publication lane before it can commit`() = runBlocking {
        val coordinator = SongWorkCoordinator()
        val publisherEntered = CompletableDeferred<Unit>()
        val releasePublisher = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        coroutineScope {
            val publisher = async {
                coordinator.withSong("AbCdEf_12-3") {
                    coordinator.preparationLane.withLock {
                        events += "publisher-entered"
                        publisherEntered.complete(Unit)
                        kotlinx.coroutines.withTimeout(5_000L) { releasePublisher.await() }
                        events += "publisher-committed"
                    }
                }
            }
            kotlinx.coroutines.withTimeout(5_000L) { publisherEntered.await() }
            val deletion = async {
                coordinator.withSong("AbCdEf_12-3") {
                    coordinator.preparationLane.withLock { events += "deletion-committed" }
                }
            }
            assertFalse(deletion.isCompleted)
            releasePublisher.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000L) { publisher.await() }
            kotlinx.coroutines.withTimeout(5_000L) { deletion.await() }
        }

        assertTrue(events == listOf("publisher-entered", "publisher-committed", "deletion-committed"))
    }

    private fun binding(
        libraryId: String,
        packageId: String,
        state: String,
        updatedAtEpochMs: Long
    ) = SourceBindingEntity(
        libraryId = libraryId,
        sourceId = "youtube-video:$packageId",
        packageId = packageId,
        state = state,
        transactionId = "test",
        updatedAtEpochMs = updatedAtEpochMs
    )

    private fun deletionPlan() = DeletionPlan(
        directoryUri = "content://provider/tree/library/document/package",
        directoryId = "package",
        children = mapOf(
            "ORIGINAL.mp3" to DeletionChildIdentity(
                documentId = "original-document",
                sha256 = "a".repeat(64),
                byteLength = 123L
            ),
            "COVER.jpg" to DeletionChildIdentity(
                documentId = "cover-document",
                sha256 = "d".repeat(64),
                byteLength = 456L
            )
        ),
        contentIdentityRecorded = true
    )

    private fun deletionAuthority() = RootAuthority(
        libraryId = "00000000-0000-4000-8000-000000000001",
        treeUri = "content://provider/tree/library",
        epoch = 9L
    )
}
