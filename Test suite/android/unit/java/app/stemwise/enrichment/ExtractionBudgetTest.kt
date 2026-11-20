package app.stemwise.enrichment

import org.junit.Assert.*
import org.junit.Test

class ExtractionBudgetTest {
    @Test fun nestedMaterializationsShareOneStreamingBudgetAndFailureDoesNotResetIt() {
        val pass = ExtractionBudget(10L)
        pass.add(4L) // selected child ZIP materialization
        pass.add(5L) // expanded media written before the entry ends
        assertThrows(ExtractionBudgetExceeded::class.java) { pass.add(2L) }
        pass.add(1L)
        assertThrows(ExtractionBudgetExceeded::class.java) { pass.add(1L) }
        assertThrows(IllegalArgumentException::class.java) { pass.add(-1L) }
        ExtractionBudget(10L).add(10L) // next independent validation/application pass
    }
    @Test fun additionCannotOverflowTheGlobalCounter() {
        val pass = ExtractionBudget(Long.MAX_VALUE)
        pass.add(Long.MAX_VALUE)
        assertThrows(ExtractionBudgetExceeded::class.java) { pass.add(1L) }
    }
}
