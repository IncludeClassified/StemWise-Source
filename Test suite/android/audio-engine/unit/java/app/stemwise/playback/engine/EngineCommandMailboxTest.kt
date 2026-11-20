package app.stemwise.playback.engine

import app.stemwise.model.StemRoleContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class EngineCommandMailboxTest {
    @Test
    fun fourThousandSelectionsOccupyOneBoundedDesiredSlot() {
        val gate = EngineOwnerCommitGate()
        val store = EngineDesiredStateStore()
        val mailbox = EngineCommandMailbox()
        var finalState: EngineDesiredState? = null

        repeat(4_000) { index ->
            val songIndex = index % 400
            val selection = gate.request(
                "song-$songIndex",
                "rev-$songIndex",
                "/song-$songIndex.ogg",
                180_000L,
                0L,
                true
            )
            finalState = store.install(selection)
            mailbox.offerDesired(requireNotNull(finalState))
        }

        val drained = mailbox.drain()
        assertEquals(1, drained.size)
        assertEquals(finalState, (drained.single() as EngineMailboxEntry.DesiredState).state)
        assertFalse(mailbox.hasPendingWork())
    }

    @Test
    fun stopAndRestartRetainTheirOrderAcrossDesiredStateConflation() {
        val gate = EngineOwnerCommitGate()
        val store = EngineDesiredStateStore()
        val mailbox = EngineCommandMailbox()
        val old = store.install(
            gate.request("old", "old-rev", "/old.ogg", 10_000L, 0L, true)
        )
        mailbox.offerDesired(old)
        val stop = stopRequest(1L)
        mailbox.offerStop(stop)
        val latest = store.install(
            gate.request("latest", "latest-rev", "/latest.ogg", 10_000L, 0L, true)
        )
        mailbox.offerDesired(latest)

        val drained = mailbox.drain()

        assertEquals(2, drained.size)
        assertSame(stop, (drained[0] as EngineMailboxEntry.Stop).request)
        assertEquals(latest, (drained[1] as EngineMailboxEntry.DesiredState).state)
    }

    @Test
    fun stopAfterSelectionRemainsTheLastCausalEntry() {
        val gate = EngineOwnerCommitGate()
        val store = EngineDesiredStateStore()
        val mailbox = EngineCommandMailbox()
        val desired = store.install(
            gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        )
        mailbox.offerDesired(desired)
        val stop = stopRequest(1L)
        mailbox.offerStop(stop)

        val drained = mailbox.drain()

        assertTrue(drained[0] is EngineMailboxEntry.DesiredState)
        assertSame(stop, (drained[1] as EngineMailboxEntry.Stop).request)
    }

    @Test
    fun lifecycleBoundariesAreNeverConflated() {
        val mailbox = EngineCommandMailbox()
        val expected = listOf(
            EngineLifecycleAction.SUSPEND_FOR_AUDIO_FOCUS,
            EngineLifecycleAction.RESUME_AFTER_AUDIO_FOCUS,
            EngineLifecycleAction.CLOSE
        )
        mailbox.offerLifecycle(expected[0])
        mailbox.offerLifecycle(expected[1])
        val stop = stopRequest(1L)
        mailbox.offerStop(stop)
        mailbox.offerLifecycle(expected[2])

        val drained = mailbox.drain()
        val actual = drained.filterIsInstance<EngineMailboxEntry.Lifecycle>().map { it.action }

        assertEquals(expected, actual)
        assertEquals(4, drained.size)
        assertSame(stop, (drained[2] as EngineMailboxEntry.Stop).request)
    }

    @Test
    fun repeatedLifecycleStormRemainsBoundedAndKeepsLatestCausalOrder() {
        val mailbox = EngineCommandMailbox()
        repeat(100_000) { index ->
            mailbox.offerLifecycle(
                if (index % 2 == 0) {
                    EngineLifecycleAction.SUSPEND_FOR_AUDIO_FOCUS
                } else {
                    EngineLifecycleAction.RESUME_AFTER_AUDIO_FOCUS
                }
            )
            mailbox.offerStop(stopRequest(index + 1L))
        }

        val drained = mailbox.drain()
        assertTrue(drained.size <= EngineLifecycleAction.entries.size + 1)
        assertEquals(
            listOf(
                EngineLifecycleAction.SUSPEND_FOR_AUDIO_FOCUS,
                EngineLifecycleAction.RESUME_AFTER_AUDIO_FOCUS
            ),
            drained.filterIsInstance<EngineMailboxEntry.Lifecycle>().map { it.action }
        )
        assertEquals(100_000L, (drained.last() as EngineMailboxEntry.Stop).request.id.value)
    }

    @Test
    fun closeIsTerminalAndIdempotent() {
        val mailbox = EngineCommandMailbox()
        val first = mailbox.offerLifecycle(EngineLifecycleAction.CLOSE)
        assertEquals(first, mailbox.offerLifecycle(EngineLifecycleAction.CLOSE))
        assertTrue(
            runCatching { mailbox.offerStop(stopRequest(1L)) }.isFailure
        )
    }

    @Test
    fun tenThousandControlChangesLeaveOnlyTheNewestRevision() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        val store = EngineDesiredStateStore()
        val mailbox = EngineCommandMailbox()
        store.install(selection)

        repeat(10_000) { index ->
            val state = requireNotNull(
                store.update(selection.token) { previous ->
                    previous.copy(
                        requestedPositionMs = (index * 7L) % selection.durationMs,
                        seekRevision = previous.seekRevision + 1L
                    )
                }
            )
            mailbox.offerDesired(state)
        }

        val state = (mailbox.drain().single() as EngineMailboxEntry.DesiredState).state
        assertEquals(10_001L, state.revision)
        assertEquals(10_000L, state.seekRevision)
    }

    @Test
    fun sourceEdgeRevisionSurvivesConflationBackToTheActorsPreviousMode() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        val store = EngineDesiredStateStore()
        val mailbox = EngineCommandMailbox()
        val initial = store.install(selection)
        mailbox.offerDesired(initial)
        mailbox.drain()

        repeat(4) { index ->
            val lanes = currentCanonicalStems()
            val next = requireNotNull(store.update(selection.token) { previous ->
                previous.copy(
                    sourceMode = if (index % 2 == 0) {
                        EngineSourceMode.STEMS
                    } else {
                        EngineSourceMode.ORIGINAL
                    },
                    stemLanes = if (index % 2 == 0) lanes else previous.stemLanes,
                    stemControls = if (index % 2 == 0) {
                        lanes.associate { it.id to EngineStemControl() }
                    } else {
                        previous.stemControls
                    }
                )
            })
            mailbox.offerDesired(next)
        }

        val conflated = (mailbox.drain().single() as EngineMailboxEntry.DesiredState).state
        assertEquals(EngineSourceMode.ORIGINAL, conflated.sourceMode)
        assertEquals(conflated.revision, conflated.sourceIntentRevision)
        assertTrue(conflated.sourceIntentRevision > initial.sourceIntentRevision)
    }

    @Test
    fun stemPackageEdgeRevisionSurvivesConflationBackToTheActorsPreviousIdentity() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        val store = EngineDesiredStateStore()
        val mailbox = EngineCommandMailbox()
        store.install(selection)
        val lanes = currentCanonicalStems()
        val packageA = requireNotNull(store.update(selection.token) { previous ->
            previous.copy(stemLanes = lanes, stemRevision = "package-a")
        })
        mailbox.offerDesired(packageA)
        mailbox.drain()

        mailbox.offerDesired(requireNotNull(store.update(selection.token) { previous ->
            previous.copy(stemRevision = "package-b")
        }))
        mailbox.offerDesired(requireNotNull(store.update(selection.token) { previous ->
            previous.copy(stemRevision = "package-a")
        }))

        val conflated = (mailbox.drain().single() as EngineMailboxEntry.DesiredState).state
        assertEquals("package-a", conflated.stemRevision)
        assertEquals(conflated.revision, conflated.stemIntentRevision)
        assertTrue(conflated.stemIntentRevision > packageA.stemIntentRevision)
    }

    @Test
    fun loopLaneConflatesIndependentlyFromDesiredState() {
        val gate = EngineOwnerCommitGate()
        val store = EngineDesiredStateStore()
        val mailbox = EngineCommandMailbox()
        val desired = store.install(
            gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        )
        mailbox.offerDesired(desired)
        repeat(100) { index ->
            mailbox.offerExplicitLoop(
                EngineExplicitLoopState(
                    desired.selection.token,
                    index + 1L,
                    EngineLoopConfig(index.toLong(), index + 1_000L)
                )
            )
        }

        val drained = mailbox.drain()
        assertEquals(desired, (drained[0] as EngineMailboxEntry.DesiredState).state)
        val loop = (drained[1] as EngineMailboxEntry.ExplicitLoop).state
        assertEquals(100L, loop.revision)
        assertEquals(99L, loop.config?.startMs)
    }

    private fun stopRequest(id: Long) = NativeEngineStopRequest(NativeEngineStopRequestId(id), 0L)

    private fun currentCanonicalStems(): List<EngineStemLane> = listOf("vocals", "drums", "bass", "guitar", "piano", "remaining").map { role ->
        EngineStemLane(
            id = role,
            normalizedRole = role,
            location = "/$role.ogg",
            contentSha256 = "a".repeat(64)
        )
    }
}
