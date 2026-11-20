package app.stemwise.playback.engine

import app.stemwise.model.StemRoleContract
import app.stemwise.playback.derivedStemPackageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class DynamicStemPlaybackContractTest {
    private val roles = listOf(
        "lead-vocals", "backing-vocals", "drums", "bass", "acoustic-guitar", "electric-guitar",
        "harp", "mandolin", "remaining"
    )
    private fun lanes() = roles.map { role ->
        EngineStemLane("id-$role", role, "/$role.opus", "hash-$role")
    }

    @Test
    fun declaredPartsHaveOneSharedOrderAndRequireTheirRemainingLane() {
        assertEquals("lead-vocals", StemRoleContract.normalize("Lead vocals"))
        assertEquals("backing-vocals", StemRoleContract.normalize("backing-vocals"))
        assertEquals("remaining", StemRoleContract.normalize("Remaining"))
        assertTrue(StemRoleContract.isPlayable(roles))
        assertEquals(roles, StemRoleContract.orderedPlayableRoles(roles.reversed()))
        assertTrue(StemRoleContract.isPlayable(listOf("piano", "remaining")))
        assertFalse(StemRoleContract.isPlayable(roles.dropLast(1)))
        assertFalse(StemRoleContract.isPlayable(listOf("vocals", "backing-vocals", "remaining")))
        assertFalse(StemRoleContract.isPlayable(listOf("piano", "piano", "remaining")))
        assertFalse(StemRoleContract.isPlayable(roles + "harmonica"))
        assertFalse(StemRoleContract.isPlayable(listOf("unknown", "remaining")))
    }

    @Test
    fun desiredSourceAndIdentityUseTheWholeDeclaredPackage() {
        val selection = EngineOwnerCommitGate().request("dynamic", "audio", "/original.opus", 180_000L, 0L, false)
        val state = EngineDesiredState(selection, 1L, sourceMode = EngineSourceMode.STEMS, stemLanes = lanes())
        assertEquals(9, state.stemLanes.size)
        assertThrows(IllegalArgumentException::class.java) {
            state.copy(stemLanes = lanes().dropLast(1))
        }
        val identity = derivedStemPackageIdentity("dynamic", lanes())
        val reversed = derivedStemPackageIdentity("dynamic", lanes().reversed())
        assertEquals(identity, reversed)
        assertTrue(identity.packageSignature.startsWith("source-v1["))
        assertTrue(identity.packageSignature.contains("remaining"))
        assertFalse(identity == derivedStemPackageIdentity("dynamic",
            lanes().mapIndexed { index, lane -> if (index == 8) lane.copy(contentSha256 = "new-remix") else lane }))
    }

    @Test
    fun oversizedPackagesCannotConsumeTheRenderBudgetEvenInOriginalMode() {
        val selection = EngineOwnerCommitGate().request("dynamic", "audio", "/original.opus", 180_000L, 0L, false)
        val oversized = lanes().dropLast(1) +
            EngineStemLane("id-harmonica", "harmonica", "/harmonica.flac", "hash-harmonica") + lanes().last()
        assertFalse(StemRoleContract.isPlayable(oversized.map { it.normalizedRole }))
        EngineSourceMode.entries.forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                EngineDesiredState(selection, 1L, sourceMode = mode, stemLanes = oversized)
            }
        }
        assertEquals(9, StemRoleContract.maximumStemCount)
    }

    @Test
    fun nativeBankDeclarationAndControlCapacityMatchTheDynamicAbi() {
        assertEquals(9, NativeRuntimeControls(1L).stems.size)
        assertEquals(NativeRuntimeLoopHeadBeginStatus.STOPPED, NativeRuntimeLoopHeadBeginStatus.fromNative(5))
        assertEquals(NativeRuntimeLoopHeadBeginStatus.ALLOCATION_FAILURE,
            NativeRuntimeLoopHeadBeginStatus.fromNative(6))
        val owner = NativeRuntimeOwnership(1L, 1L)
        val bank = NativeRuntimeBankIntent(1L, owner, songIdentityHash = 1L,
            originalContentHash = 2L, stemPackageHash = 3L, stemLaneCount = 9, cacheFrameUpperBound = 48_000L)
        assertThrows(IllegalArgumentException::class.java) { bank.copy(cacheFrameUpperBound = 0L) }
        assertEquals(9, bank.stemLaneCount)
        assertThrows(IllegalArgumentException::class.java) { bank.copy(stemLaneCount = 10) }
        assertThrows(IllegalArgumentException::class.java) { bank.copy(stemPackageHash = 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            NativeRuntimeBankIntent.unassigned(2L).copy(stemLaneCount = 1)
        }
        assertEquals(0, NativeRuntimeBankIntent(2L, owner,
            songIdentityHash = 1L, originalContentHash = 2L, cacheFrameUpperBound = 48_000L).stemLaneCount)
    }
}
