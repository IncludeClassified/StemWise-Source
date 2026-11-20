package app.stemwise.qualification

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description

class FocusedSuiteMethodFilterInstrumentationTest {
    @Test
    fun exactFilterKeepsEnclosingSuiteAndOnlyTheRequestedLeaf() {
        val filter = FocusedSuiteMethodFilter(arguments())
        val selected = Description.createTestDescription(IMPORT_CLASS, IMPORT_METHOD)
        val other = Description.createTestDescription(IMPORT_CLASS, "nativeFlacReplacesOpusInTheSameLibraryEntryAndRetiresOldStems")
        val child = Description.createSuiteDescription(IMPORT_CLASS).apply {
            addChild(selected)
            addChild(other)
        }
        val wrapper = Description.createSuiteDescription(ISOLATED).apply { addChild(child) }
        assertTrue(filter.shouldRun(wrapper))
        assertTrue(filter.shouldRun(child))
        assertTrue(filter.shouldRun(selected))
        assertFalse(filter.shouldRun(other))
        assertFalse(filter.shouldRun(Description.createSuiteDescription("unselected")))
    }

    @Test
    fun focusedContractRejectsEmptyUnknownDuplicateOrUngatedSelections() {
        val invalid = listOf(
            arguments().apply { putString("stemwiseFocusedMethods", "") },
            arguments().apply { putString("stemwiseFocusedMethods", "$IMPORT_CLASS#missing") },
            arguments().apply { putString("stemwiseFocusedMethods", "$IMPORT_CLASS#$IMPORT_METHOD,$IMPORT_CLASS#$IMPORT_METHOD") },
            arguments().apply { putString("class", IMPORT_CLASS) },
            arguments().apply { remove("filter") },
            arguments().apply { remove("listener") },
            arguments().apply { putString("stemwiseDisposableTarget", "false") }
        )
        invalid.forEach { assertTrue(runCatching { FocusedSuiteMethodFilter(it) }.isFailure) }
    }

    @Test
    fun callbackLedgerRejectsOmissionsDuplicatesUnexpectedMethodsAndSkips() {
        val id = "$IMPORT_CLASS#$IMPORT_METHOD"
        val complete = FocusedSuiteMethodLedger(setOf(id)).apply { finished(id) }
        assertEquals(emptyList<String>(), complete.violations())
        assertTrue(FocusedSuiteMethodLedger(setOf(id)).violations().isNotEmpty())
        complete.finished(id)
        assertTrue(complete.violations().any { "completed 2 times" in it })
        val unexpected = FocusedSuiteMethodLedger(setOf(id)).apply { finished(id); finished("unexpected#method") }
        assertTrue(unexpected.violations().any { "unexpected focused method" in it })
        val skipped = FocusedSuiteMethodLedger(setOf(id)).apply { finished(id); failed("skipped") }
        assertEquals(listOf("skipped"), skipped.violations())
    }

    private fun arguments() = Bundle().apply {
        putString("class", ISOLATED)
        putString("filter", FocusedSuiteMethodFilter::class.java.name)
        putString("listener", FocusedSuiteMethodRunListener::class.java.name)
        putString("stemwiseFocusedMethods", "$IMPORT_CLASS#$IMPORT_METHOD")
        putString("stemwiseDisposableTarget", "true")
    }

    private companion object {
        const val ISOLATED = "app.stemwise.qualification.IsolatedDataEngineSurfaceSuite"
        const val IMPORT_CLASS = "app.stemwise.enrichment.OriginalEnrichmentImportInstrumentationTest"
        const val IMPORT_METHOD = "nativeFlacEnrichmentImportsReopensAndRejectsWrongRecordingWithoutMutation"
    }
}
