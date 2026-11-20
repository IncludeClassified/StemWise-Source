package app.stemwise.qualification

import android.os.Bundle
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runners.Suite

/** AndroidJUnitRunner constructs this with its Bundle, while the real suite owns its lifecycle. */
class FocusedSuiteMethodFilter(arguments: Bundle) : Filter() {
    private val methods = requireNotNull(requestedMethods(arguments)) {
        "Focused suite filter requires exact stemwiseFocusedMethods."
    }

    override fun shouldRun(description: Description): Boolean =
        if (description.isTest) {
            "${description.className}#${description.methodName}" in methods
        } else {
            description.children.any(::shouldRun)
        }

    override fun describe(): String = "exact suite methods: ${methods.joinToString()}"

    companion object {
        internal const val METHODS_ARGUMENT = "stemwiseFocusedMethods"
        private const val UNIFIED = "app.stemwise.qualification.UnifiedPlayerPressureSuite"
        private const val ISOLATED = "app.stemwise.qualification.IsolatedDataEngineSurfaceSuite"

        internal fun requestedMethods(arguments: Bundle): Set<String>? {
            val raw = arguments.getString(METHODS_ARGUMENT) ?: return null
            val wrapper = arguments.getString("class")
            require(wrapper == UNIFIED || wrapper == ISOLATED) {
                "Focused methods must run through exactly one protected suite wrapper."
            }
            require(arguments.getString("filter") == FocusedSuiteMethodFilter::class.java.name) {
                "Focused methods require the exact suite filter."
            }
            val listeners = arguments.getString("listener").orEmpty().split(',').map(String::trim)
            require(FocusedSuiteMethodRunListener::class.java.name in listeners) {
                "Focused methods require the completion ledger listener."
            }
            require(wrapper != UNIFIED || ProcessAudioHealthRunListener::class.java.name in listeners) {
                "Focused playback methods require process-wide audio health observation."
            }
            require(arguments.getString("stemwiseDisposableTarget") == "true") {
                "Focused methods require a disposable target."
            }
            val selected = raw.split(',')
            require(selected.isNotEmpty() && selected.none(String::isBlank) &&
                selected.size == selected.toSet().size) { "Focused methods must be nonempty and unique." }
            val children = requireNotNull(
                Class.forName(requireNotNull(wrapper)).getAnnotation(Suite.SuiteClasses::class.java)
            ).value
            val eligible = children.flatMap { child ->
                val type = child.java
                if (type.isAnnotationPresent(Ignore::class.java)) emptyList() else type.methods
                    .filter { it.isAnnotationPresent(Test::class.java) && !it.isAnnotationPresent(Ignore::class.java) }
                    .map { "${type.name}#${it.name}" }
            }.toSet()
            require(selected.all { it in eligible }) {
                "Focused methods must be runnable Test leaves belonging to $wrapper."
            }
            return selected.toSet()
        }
    }
}

internal class FocusedSuiteMethodLedger(private val expected: Set<String>) {
    private val completed = linkedMapOf<String, Int>()
    private val failures = linkedSetOf<String>()

    fun finished(id: String) { completed[id] = (completed[id] ?: 0) + 1 }
    fun failed(reason: String) { failures += reason }
    fun violations(): List<String> = buildList {
        expected.filter { completed[it] != 1 }.forEach { add("focused method $it completed ${completed[it] ?: 0} times") }
        completed.keys.filter { it !in expected }.forEach { add("unexpected focused method $it") }
        addAll(failures)
    }
}

/** Records actual JUnit callbacks; the wrapper fails if a filter omits, duplicates or skips a leaf. */
class FocusedSuiteMethodRunListener : RunListener() {
    override fun testFinished(description: Description) {
        ledger?.finished("${description.className}#${description.methodName}")
    }
    override fun testFailure(failure: Failure) { ledger?.failed("focused test failure: ${failure.description.displayName}") }
    override fun testAssumptionFailure(failure: Failure) { ledger?.failed("focused test skipped: ${failure.description.displayName}") }
    override fun testIgnored(description: Description) { ledger?.failed("focused test ignored: ${description.displayName}") }

    companion object {
        private var ledger: FocusedSuiteMethodLedger? = null

        internal fun begin(arguments: Bundle, wrapper: String) {
            ledger = null
            val methods = FocusedSuiteMethodFilter.requestedMethods(arguments) ?: return
            check(arguments.getString("class") == wrapper) { "Focused suite ledger belongs to another wrapper." }
            ledger = FocusedSuiteMethodLedger(methods)
        }

        internal fun violations(): List<String> = ledger?.violations().orEmpty()
        internal fun requireComplete() {
            val violations = violations()
            check(violations.isEmpty()) { violations.joinToString("; ") }
        }
    }
}
