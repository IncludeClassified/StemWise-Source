package app.stemwise.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

@app.stemwise.qualification.ManagedAudioTest
class PlaybackQueueTest {
    private val ids = listOf("a", "b", "c", "d", "e")

    @Test
    fun restoredQueueRetainsExactOrderCursorAndNavigationWithoutReshuffling() {
        val saved = PlaybackQueueSnapshot(listOf("d", "a", "e", "b", "c"), 2, true, -91L, RepeatMode.ALL)
        val restored = PlaybackQueueController()
        assertEquals(saved, restored.restore(saved))
        assertTrue(restored.contains("e"))
        assertEquals(QueueDecision.Move("b", 3), restored.preview(QueueTransition.EXPLICIT_NEXT))
        assertEquals(QueueDecision.Move("a", 1), restored.preview(QueueTransition.EXPLICIT_PREVIOUS))
        assertEquals(saved.copy(order = listOf("d", "e", "b", "c"), currentIndex = 1),
            restored.updateLibrary(listOf("b", "c", "d", "e")))
        assertEquals("c", restored.select("c").currentId)
        assertEquals(QueueDecision.Move("d", 0), restored.previewAutomaticEnd())
    }

    @Test
    fun invalidRestoreCannotReplaceCurrentQueueAndEmptyQueueRestoresOnlyWithoutCursor() {
        val queue = PlaybackQueueController()
        val valid = queue.start(ids, "c", shuffle = false)
        for (invalid in listOf(
            valid.copy(order = listOf("a", "a")), valid.copy(order = listOf("a", " ")),
            valid.copy(currentIndex = -1), valid.copy(currentIndex = valid.order.size),
            valid.copy(order = emptyList(), currentIndex = 0)
        )) {
            assertThrows(IllegalArgumentException::class.java) { queue.restore(invalid) }
            assertEquals(valid, queue.snapshot())
            assertTrue(queue.contains("c"))
        }
        assertEquals(PlaybackQueueSnapshot(), queue.restore(PlaybackQueueSnapshot()))
        assertFalse(queue.contains("c"))
    }

    @Test
    fun restoredOrderDoesNotShareTheCallersMutableList() {
        val order = mutableListOf("a", "b")
        val queue = PlaybackQueueController()
        queue.restore(PlaybackQueueSnapshot(order, 1))
        order.clear()
        assertEquals(listOf("a", "b"), queue.snapshot().order)
        assertEquals("b", queue.snapshot().currentId)
        assertTrue(queue.contains("b"))
    }

    @Test
    fun pendingNavigationPreviewUsesItsOwnCursorWithoutMovingTheAdmittedQueueOwner() {
        val queue = PlaybackQueueController(73L)
        queue.start(ids, startId = "a", shuffle = false)
        assertEquals(QueueDecision.Move("c", 2), queue.preview(QueueTransition.EXPLICIT_NEXT, "b"))
        assertEquals("a", queue.snapshot().currentId)
        assertEquals(QueueDecision.Move("b", 1), queue.preview(QueueTransition.EXPLICIT_PREVIOUS, "c"))
        queue.setRepeatMode(RepeatMode.ALL)
        assertEquals(QueueDecision.Move("a", 0), queue.preview(QueueTransition.EXPLICIT_NEXT, "e"))
        assertEquals(QueueDecision.Move("e", 4), queue.preview(QueueTransition.EXPLICIT_PREVIOUS, "a"))
        queue.setRepeatMode(RepeatMode.ONE)
        assertEquals(QueueDecision.Restart("c"), queue.preview(QueueTransition.AUTOMATIC_END, "c"))
    }

    @Test
    fun seededShuffleVisitsEverySongOnceAndPreviousRetracesHistory() {
        val queue = PlaybackQueueController(73L)
        queue.start(ids, shuffle = true, seed = 73L)
        val shuffled = queue.snapshot().order
        assertEquals(ids.toSet(), shuffled.toSet())
        assertEquals(ids.size, shuffled.distinct().size)
        assertNotEquals(ids, shuffled)

        val forward = mutableListOf(requireNotNull(queue.snapshot().currentId))
        repeat(ids.lastIndex) {
            forward += (queue.decide(QueueTransition.EXPLICIT_NEXT) as QueueDecision.Move).mediaId
        }
        assertEquals(shuffled, forward)

        val retraced = mutableListOf<String>()
        repeat(ids.lastIndex) {
            retraced +=
                (queue.decide(QueueTransition.EXPLICIT_PREVIOUS) as QueueDecision.Move).mediaId
        }
        assertEquals(shuffled.dropLast(1).reversed(), retraced)
    }

    @Test
    fun sameSeedProducesSameStableOrder() {
        val first = PlaybackQueueController().start(ids, shuffle = true, seed = 91L).order
        val second = PlaybackQueueController().start(ids, shuffle = true, seed = 91L).order
        assertEquals(first, second)
    }

    @Test
    fun clearRemovesCursorShuffleAndRepeatState() {
        val queue = PlaybackQueueController(91L)
        queue.start(ids, startId = "c", shuffle = true, seed = 91L)
        queue.setRepeatMode(RepeatMode.ONE)

        val cleared = queue.clear()

        assertTrue(cleared.order.isEmpty())
        assertEquals(-1, cleared.currentIndex)
        assertFalse(cleared.shuffleEnabled)
        assertEquals(RepeatMode.OFF, cleared.repeatMode)
    }

    @Test
    fun everyShuffleRepeatAndTransitionCombinationMatchesQueueContract() {
        for (shuffle in listOf(false, true)) {
            for (repeatMode in RepeatMode.entries) {
                val queue = PlaybackQueueController()
                queue.start(ids, shuffle = shuffle, seed = 37L)
                queue.setRepeatMode(repeatMode)
                val order = queue.snapshot().order

                queue.select(order.last())
                val manualNext = queue.decide(QueueTransition.EXPLICIT_NEXT)
                val expectedManualNext = if (repeatMode == RepeatMode.ALL) {
                    QueueDecision.Move(order.first(), 0)
                } else {
                    QueueDecision.Boundary
                }
                assertEquals("$shuffle/$repeatMode manual next", expectedManualNext, manualNext)

                queue.select(order.last())
                val automatic = queue.decide(QueueTransition.AUTOMATIC_END)
                val expectedAutomatic = when (repeatMode) {
                    RepeatMode.OFF -> QueueDecision.Stop
                    RepeatMode.ALL -> QueueDecision.Move(order.first(), 0)
                    RepeatMode.ONE -> QueueDecision.Restart(order.last())
                }
                assertEquals("$shuffle/$repeatMode automatic", expectedAutomatic, automatic)

                queue.select(order.first())
                val manualPrevious = queue.decide(QueueTransition.EXPLICIT_PREVIOUS)
                val expectedPrevious = if (repeatMode == RepeatMode.ALL) {
                    QueueDecision.Move(order.last(), order.lastIndex)
                } else {
                    QueueDecision.Boundary
                }
                assertEquals(
                    "$shuffle/$repeatMode manual previous",
                    expectedPrevious,
                    manualPrevious
                )
            }
        }
    }

    @Test
    fun automaticEndPreviewIsExactAndNonMutatingAcrossEveryQueuePolicy() {
        for (shuffle in listOf(false, true)) {
            for (repeatMode in RepeatMode.entries) {
                val probe = PlaybackQueueController()
                probe.start(ids, shuffle = shuffle, seed = 37L)
                probe.setRepeatMode(repeatMode)
                val order = probe.snapshot().order

                for (index in order.indices) {
                    val queue = PlaybackQueueController()
                    queue.start(ids, startId = order[index], shuffle = shuffle, seed = 37L)
                    queue.setRepeatMode(repeatMode)
                    val before = queue.snapshot()
                    val current = requireNotNull(before.currentId)
                    val expected = when {
                        repeatMode == RepeatMode.ONE -> QueueDecision.Restart(current)
                        index < order.lastIndex -> QueueDecision.Move(order[index + 1], index + 1)
                        repeatMode == RepeatMode.ALL -> QueueDecision.Move(order.first(), 0)
                        else -> QueueDecision.Stop
                    }

                    val preview = queue.previewAutomaticEnd()
                    assertEquals("$shuffle/$repeatMode/index=$index preview", expected, preview)
                    assertEquals("Preview mutated queue state", before, queue.snapshot())
                    assertEquals(
                        "Preview drifted from committed decision",
                        preview,
                        queue.decide(QueueTransition.AUTOMATIC_END)
                    )
                }
            }
        }
    }

    @Test
    fun previewCoversEmptySingleSongAndExplicitBoundaryWithoutMutation() {
        val empty = PlaybackQueueController()
        val emptyBefore = empty.snapshot()
        assertEquals(QueueDecision.Stop, empty.previewAutomaticEnd())
        assertEquals(emptyBefore, empty.snapshot())

        for (repeatMode in RepeatMode.entries) {
            val single = PlaybackQueueController()
            single.start(listOf("only"), shuffle = false)
            single.setRepeatMode(repeatMode)
            val before = single.snapshot()
            val expected = if (repeatMode == RepeatMode.OFF) {
                QueueDecision.Stop
            } else {
                QueueDecision.Restart("only")
            }
            assertEquals(expected, single.previewAutomaticEnd())
            assertEquals(before, single.snapshot())
        }

        val boundary = PlaybackQueueController()
        boundary.start(ids, startId = ids.last(), shuffle = false)
        val beforeBoundary = boundary.snapshot()
        assertEquals(
            QueueDecision.Boundary,
            boundary.preview(QueueTransition.EXPLICIT_NEXT)
        )
        assertEquals(beforeBoundary, boundary.snapshot())
    }

    @Test
    fun explicitMovePreviewKeepsCurrentOwnerUntilResolvedDestinationCommits() {
        val queue = PlaybackQueueController()
        queue.start(ids, startId = "c", shuffle = false)

        val beforeResolution = queue.snapshot()
        val proposed = queue.preview(QueueTransition.EXPLICIT_NEXT)

        assertEquals(QueueDecision.Move("d", 3), proposed)
        assertEquals(beforeResolution, queue.snapshot())

        queue.select((proposed as QueueDecision.Move).mediaId)
        assertEquals("d", queue.snapshot().currentId)
    }

    @Test
    fun repeatOneDoesNotTrapExplicitNavigation() {
        val queue = PlaybackQueueController()
        queue.start(ids, startId = "c", shuffle = false)
        queue.setRepeatMode(RepeatMode.ONE)
        assertEquals(
            QueueDecision.Move("d", 3),
            queue.decide(QueueTransition.EXPLICIT_NEXT)
        )
        assertEquals(
            QueueDecision.Move("c", 2),
            queue.decide(QueueTransition.EXPLICIT_PREVIOUS)
        )
    }

    @Test
    fun repeatAllRestartsASingleSongOnlyAtAutomaticCompletion() {
        val queue = PlaybackQueueController()
        queue.start(listOf("a"), shuffle = false)
        queue.setRepeatMode(RepeatMode.ALL)

        assertFalse(queue.snapshot().hasPrevious)
        assertFalse(queue.snapshot().hasNext)
        assertEquals(
            QueueDecision.Boundary,
            queue.decide(QueueTransition.EXPLICIT_PREVIOUS)
        )
        assertEquals(
            QueueDecision.Boundary,
            queue.decide(QueueTransition.EXPLICIT_NEXT)
        )
        assertEquals(
            QueueDecision.Restart("a"),
            queue.decide(QueueTransition.AUTOMATIC_END)
        )
    }

    @Test
    fun explicitNavigationUsesQueuePolicy() {
        val queue = PlaybackQueueController()
        queue.start(ids, startId = "c", shuffle = false)
        queue.setRepeatMode(RepeatMode.ALL)
        assertEquals(
            QueueDecision.Move("d", 3),
            queue.decide(QueueTransition.EXPLICIT_NEXT)
        )
    }

    @Test
    fun songsAddedDuringShuffleEnterUnplayedTailWithoutChangingCurrent() {
        val queue = PlaybackQueueController()
        queue.start(ids.take(3), shuffle = true, seed = 17L)
        val current = requireNotNull(queue.snapshot().currentId)
        val existingOrder = queue.snapshot().order
        queue.updateLibrary(ids)
        val updated = queue.snapshot()
        assertEquals(current, updated.currentId)
        assertEquals(existingOrder, updated.order.take(existingOrder.size))
        assertEquals(setOf("d", "e"), updated.order.takeLast(2).toSet())
        assertTrue(updated.order.distinct().size == updated.order.size)
    }

    @Test
    fun enablingShuffleRebasesCurrentAndIncludesEveryOtherSong() {
        val queue = PlaybackQueueController()
        queue.start(ids, startId = "c", shuffle = false)

        val shuffled = queue.setShuffle(true, seed = 123L)

        assertEquals(0, shuffled.currentIndex)
        assertEquals("c", shuffled.order.first())
        assertEquals("c", shuffled.currentId)
        assertEquals(ids.filterNot { it == "c" }.toSet(), shuffled.order.drop(1).toSet())
        assertEquals(ids.size, shuffled.order.distinct().size)
    }

    @Test
    fun hundredRapidTransitionsHaveNoDuplicateNavigation() {
        val hundred = (0 until 100).map(Int::toString)
        val queue = PlaybackQueueController()
        queue.start(hundred, shuffle = true, seed = 1_337L)
        val visited = mutableListOf(requireNotNull(queue.snapshot().currentId))
        repeat(99) {
            visited += (queue.decide(QueueTransition.EXPLICIT_NEXT) as QueueDecision.Move).mediaId
        }
        assertEquals(100, visited.size)
        assertEquals(100, visited.distinct().size)
        assertEquals(queue.snapshot().order, visited)
    }

    @Test
    fun removingCurrentChoosesOneDeterministicSuccessor() {
        val queue = PlaybackQueueController()
        queue.start(ids, startId = "c", shuffle = false)
        val updated = queue.updateLibrary(listOf("a", "b", "d", "e"))
        assertEquals("a", updated.currentId)
        assertEquals(updated.order.indexOf("a"), updated.currentIndex)
    }

    @Test
    fun noncurrentRemovalDoesNotChangeCurrentOrHistoryPrefix() {
        val queue = PlaybackQueueController()
        queue.start(ids, startId = "c", shuffle = true, seed = 44L)
        val before = queue.snapshot()
        val removed = before.order.last { it != before.currentId }
        val updated = queue.updateLibrary(ids - removed)
        assertEquals(before.currentId, updated.currentId)
        assertEquals(before.order.filterNot { it == removed }, updated.order)
    }

    @Test
    fun fourHundredSongQueueSurvivesShuffleUpdatesAndSelectionStorms() {
        val library = (0 until 400).map { "song-${it.toString().padStart(3, '0')}" }
        val queue = PlaybackQueueController()
        val shuffled = queue.start(library, shuffle = true, seed = 400L)
        assertEquals(400, shuffled.order.size)
        assertEquals(library.toSet(), shuffled.order.toSet())

        val random = Random(400L)
        var lastRequested = requireNotNull(shuffled.currentId)
        repeat(4_000) { iteration ->
            lastRequested = library[random.nextInt(library.size)]
            queue.select(lastRequested)
            if (iteration % 37 == 0) {
                val rotated = library.drop(iteration % library.size) +
                    library.take(iteration % library.size)
                queue.updateLibrary(rotated)
            }
        }

        val settled = queue.snapshot()
        assertEquals(lastRequested, settled.currentId)
        assertEquals(400, settled.order.size)
        assertEquals(400, settled.order.distinct().size)
        assertEquals(library.toSet(), settled.order.toSet())
    }

    @Test
    fun directSelectionAtThreeHundredThenShuffleVisitsTheCompleteFourHundredSongLibrary() {
        val library = (0 until 400).map { "song-${it.toString().padStart(3, '0')}" }
        val queue = PlaybackQueueController()
        queue.start(library, shuffle = false)
        queue.select(library[300])

        val shuffled = queue.setShuffle(true, seed = 40_300L)
        assertEquals(library[300], shuffled.currentId)
        assertEquals(0, shuffled.currentIndex)

        val visited = mutableListOf(requireNotNull(shuffled.currentId))
        while (true) {
            when (val decision = queue.decide(QueueTransition.EXPLICIT_NEXT)) {
                is QueueDecision.Move -> visited += decision.mediaId
                QueueDecision.Boundary -> break
                else -> error("Repeat Off explicit Next returned $decision")
            }
        }

        assertEquals(400, visited.size)
        assertEquals(400, visited.distinct().size)
        assertEquals(library.toSet(), visited.toSet())
        assertEquals(library[300], visited.first())
    }

    @Test
    fun fourThousandMixedMembershipProbesDoNotMutateFourHundredSongQueue() {
        val library = (0 until 400).map { "song-${it.toString().padStart(3, '0')}" }
        val queue = PlaybackQueueController()
        queue.start(library, shuffle = true, seed = 4_000L)
        val before = queue.snapshot()

        repeat(4_000) { probe ->
            if (probe % 2 == 0) {
                assertTrue(queue.contains(library[(probe * 37) % library.size]))
            } else {
                assertFalse(queue.contains("missing-$probe"))
            }
        }

        assertEquals(before, queue.snapshot())
    }

    @Test
    fun fiftyThousandSongQueueAnswersFiftyThousandMixedMembershipProbes() {
        val library = (0 until 50_000).map { "large-song-${it.toString().padStart(5, '0')}" }
        val queue = PlaybackQueueController()
        queue.start(library, shuffle = false)
        val before = queue.snapshot()

        repeat(50_000) { probe ->
            if (probe % 2 == 0) {
                assertTrue(queue.contains(library[(probe * 101) % library.size]))
            } else {
                assertFalse(queue.contains("missing-large-$probe"))
            }
        }

        assertEquals(before, queue.snapshot())
    }

    @Test
    fun completeLargeLibraryShuffleIsDeterministicUntruncatedAndDirectlySelectable() {
        val library = (0 until 50_000).map { "large-song-${it.toString().padStart(5, '0')}" }
        val first = PlaybackQueueController().start(library, shuffle = true, seed = 50_000L)
        val replay = PlaybackQueueController().start(library, shuffle = true, seed = 50_000L)

        assertEquals(library.size, first.order.size)
        assertEquals(library.size, first.order.distinct().size)
        assertEquals(library.toHashSet(), first.order.toHashSet())
        assertEquals(first.order, replay.order)

        val queue = PlaybackQueueController()
        queue.start(library, shuffle = true, seed = 50_000L)
        val random = Random(50_000L)
        var lastRequested = requireNotNull(first.currentId)
        repeat(100_000) {
            lastRequested = library[random.nextInt(library.size)]
            assertEquals(lastRequested, queue.select(lastRequested).currentId)
        }

        assertEquals(lastRequested, queue.snapshot().currentId)
        assertEquals(first.order, queue.snapshot().order)
    }
}
