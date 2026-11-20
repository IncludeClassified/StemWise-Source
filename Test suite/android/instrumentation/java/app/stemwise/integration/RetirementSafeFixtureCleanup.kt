package app.stemwise.integration

import java.io.File

/** A failed worker still owns its test inputs, including after a process restart. */
internal fun File.deleteAfterWorkerRetirement(): Boolean {
    bundledToolRuntimeMutex.requireHealthy()
    WorkerRetirementQuarantine.requireReleased(this)
    return deleteRecursively()
}
