package app.stemwise.integration

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs updater interruption checks in an isolated process with the framework Application.
 * This runner targets only the updater process, away from the main process and its providers.
 */
class IsolatedUpdaterInstrumentationRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application = super.newApplication(cl, Application::class.java.name, context)
}
