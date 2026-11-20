package app.stemwise.work

import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticNetworkWorkPolicyTest {
    @Test
    fun recoveryRequestsRunOfflineWhilePlaylistDiscoveryWaitsForNetwork() {
        val requests = linkedMapOf<String, WorkRequest>(
            "MVSEP preparation" to MvsepPreparationWorker.initialRequest(ROOT),
            "MVSEP preparation continuation" to MvsepPreparationWorker.continuationRequest(
                ROOT,
                45,
                TimeUnit.SECONDS
            ),
            "periodic playlist sync" to PlaylistSyncWorker.periodicRequest(LIBRARY_ID)
        )

        requests.forEach { (path, request) ->
            if (path.startsWith("MVSEP")) {
                assertEquals(BackoffPolicy.LINEAR, request.workSpec.backoffPolicy)
                assertEquals(45_000L, request.workSpec.backoffDelayDuration)
            }
            val constraints = request.workSpec.constraints
            assertEquals(
                "$path must wait for an unmetered network",
                if (path.startsWith("MVSEP")) NetworkType.NOT_REQUIRED else NetworkType.UNMETERED,
                constraints.requiredNetworkType
            )
            assertTrue(
                "$path must wait for a healthy battery",
                constraints.requiresBatteryNotLow()
            )
            assertTrue(
                "$path must wait for healthy storage",
                constraints.requiresStorageNotLow()
            )
        }
    }

    @Test
    fun deliberateManualSyncRequiresOnlyAConnectedNetwork() {
        val constraints = PlaylistSyncWorker.manualRequest(LIBRARY_ID).workSpec.constraints

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresStorageNotLow())
    }

    @Test
    fun sharedFactoryAlwaysBuildsTheCompletePolicy() {
        val constraints = automaticNetworkWorkConstraints()

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun retainedMvsepCredentialRetriesTransientAccountOrNetworkWait() {
        assertTrue(waitingForAccountNeedsRetry(tokenStored = true))
        assertFalse(waitingForAccountNeedsRetry(tokenStored = false))
    }

    @Test
    fun everyPreparationHandoffPersistsTheExactRootTriple() {
        listOf(
            MvsepPreparationWorker.initialRequest(ROOT),
            MvsepPreparationWorker.continuationRequest(ROOT, 45, TimeUnit.SECONDS)
        ).forEach { request ->
            assertEquals(ROOT.libraryId, request.workSpec.input.getString("library_id"))
            assertEquals(
                ROOT.treeUriIdentity,
                request.workSpec.input.getString("tree_uri_sha256")
            )
            assertEquals(null, request.workSpec.input.getString("tree_uri"))
            assertEquals(ROOT.epoch, request.workSpec.input.getLong("root_epoch", -1L))
        }
    }

    @Test
    fun actualPreparationRequestInputsAdmitOnlyTheirExactReadyRoot() {
        listOf(MvsepPreparationWorker.initialRequest(ROOT),
            MvsepPreparationWorker.continuationRequest(ROOT, 45, TimeUnit.SECONDS)).forEach { request ->
            val expected = checkNotNull(MvsepPreparationWorker.rootExpectation(request.workSpec.input))
            fun admission(root: RootWorkIdentity) = workerRootAdmission(expected, root,
                root.libraryId, root.treeUri, root.epoch, null)
            assertEquals(WorkerRootAdmission.RUN, admission(ROOT))
            assertEquals(WorkerRootAdmission.COMPLETE, admission(ROOT.copy(treeUri = ROOT.treeUri + "-other")))
            assertEquals(WorkerRootAdmission.COMPLETE, admission(ROOT.copy(epoch = ROOT.epoch + 1)))
        }
        assertEquals(null, MvsepPreparationWorker.rootExpectation(Data.EMPTY))
    }

    @Test
    fun maximumValidNonAsciiTreeUriKeepsEveryPreparationRequestUnderWorkDataLimit() {
        val root = RootWorkIdentity(
            libraryId = LIBRARY_ID,
            treeUri = "content://provider/tree/" + "ج".repeat(4_096),
            epoch = 7L
        )

        listOf(
            MvsepPreparationWorker.initialRequest(root),
            MvsepPreparationWorker.continuationRequest(root, 45, TimeUnit.SECONDS)
        ).forEach { request ->
            assertTrue(request.workSpec.input.toByteArray().size <= Data.MAX_DATA_BYTES)
            assertEquals(64, request.workSpec.input.getString("tree_uri_sha256")?.length)
            assertEquals(null, request.workSpec.input.getString("tree_uri"))
        }
    }

    private companion object {
        const val LIBRARY_ID = "data-policy-test-library"
        val ROOT = RootWorkIdentity(
            libraryId = LIBRARY_ID,
            treeUri = "content://stemwise.test/tree/root",
            epoch = 7L
        )
    }
}
