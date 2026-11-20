package app.stemwise.qualification

import org.junit.Test

class StandardInstrumentationResidueTest {
    @Test
    fun knownFixturePatternsLeaveNoResidue() {
        StandardInstrumentationResidue.assertKnownFixturesAbsent()
    }
}
