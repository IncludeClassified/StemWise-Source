package app.stemwise.qualification

import org.junit.runner.Result
import org.junit.runner.notification.RunListener

/** Restores the wrapper's final residue gate when its children run as one direct batch. */
class StandardInstrumentationResidueRunListener : RunListener() {
    override fun testRunFinished(result: Result) {
        StandardInstrumentationResidue.assertKnownFixturesAbsent()
    }
}
