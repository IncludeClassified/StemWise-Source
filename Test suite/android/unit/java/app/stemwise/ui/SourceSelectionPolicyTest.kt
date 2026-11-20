package app.stemwise.ui

import app.stemwise.model.StemSourceCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSelectionPolicyTest {
    private val available = StemSourceCatalogue.sources

    @Test fun sharedAndTransitiveCoverageCannotBeAdded() {
        listOf("strings" to "bass", "keys" to "percussion", "plucked-strings" to "double-bass")
            .forEach { (left, right) ->
                for ((selected, addition) in listOf(left to right, right to left)) {
                    assertNotNull(sourceSelectionCandidateIssue(addition, listOf(selected), available))
                    assertEquals(listOf(selected), sourceSelectionAfterToggle(addition, listOf(selected), available))
                }
            }
    }

    @Test
    fun rejectedOverlapKeepsTheExistingSelection() {
        val selected = listOf("vocals", "drums")
        assertNotNull(sourceSelectionCandidateIssue("backing-vocals", selected, available))
        assertEquals(selected, sourceSelectionAfterToggle("backing-vocals", selected, available))
        assertEquals(listOf("drums"), sourceSelectionAfterToggle("vocals", selected, available))
        assertEquals(listOf("drums", "backing-vocals"),
            sourceSelectionAfterToggle("backing-vocals", listOf("drums"), available))
    }

    @Test
    fun checkedPartsRemainRemovableEvenWhenSavedSelectionIsInvalid() {
        val invalid = listOf("vocals", "backing-vocals")
        assertNull(sourceSelectionCandidateIssue("vocals", invalid, available))
        assertEquals(listOf("backing-vocals"),
            sourceSelectionAfterToggle("vocals", invalid, available))
        assertNull(sourceSelectionIssue(listOf("backing-vocals"), available))
    }

    @Test
    fun unavailablePartCannotBeAddedOrSubmittedFromStaleSavedState() {
        val limited = available.filter { it.id in setOf("vocals", "drums") }
        val selected = listOf("vocals")
        assertNotNull(sourceSelectionCandidateIssue("piano", selected, limited))
        assertEquals(selected, sourceSelectionAfterToggle("piano", selected, limited))
        assertNotNull(sourceSelectionIssue(listOf("piano"), limited))
        assertNull(sourceSelectionIssue(selected, limited))
    }

    @Test
    fun addingBeyondThePlaybackBudgetDoesNotReplaceAnySelectedPart() {
        val selected = listOf(
            "lead-vocals", "backing-vocals", "drums", "bass", "acoustic-guitar", "electric-guitar",
            "harp", "mandolin"
        )
        assertNull(sourceSelectionIssue(selected, available))
        assertNotNull(sourceSelectionCandidateIssue("harmonica", selected, available))
        assertEquals(selected, sourceSelectionAfterToggle("harmonica", selected, available))
        assertEquals(selected.dropLast(1), sourceSelectionAfterToggle("mandolin", selected, available))
    }

    @Test
    fun confirmationRequiresAnAvailableNonemptyDistinctSelection() {
        assertNotNull(sourceSelectionIssue(emptyList(), available))
        assertNotNull(sourceSelectionIssue(listOf("vocals", "vocals"), available))
        assertNotNull(sourceSelectionIssue(listOf("not-a-source"), available))
        assertNull(sourceSelectionIssue(listOf("lead-vocals", "backing-vocals"), available))
    }
}
