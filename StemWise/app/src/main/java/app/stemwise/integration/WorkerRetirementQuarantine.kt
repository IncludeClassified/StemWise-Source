package app.stemwise.integration

import java.io.File
import app.stemwise.playback.engine.WorkerRetirementException

/** Retains callback resources and file ownership while their worker remains unresolved. */
internal class WorkerResourceQuarantine {
    private data class Retained(
        val failure: WorkerRetirementException, val resources: List<Any>, val files: List<File>,
        val identityUncertain: Boolean
    )
    private val retained = mutableListOf<Retained>()

    @Synchronized
    fun retain(failure: WorkerRetirementException, resources: List<Any> = emptyList(), files: List<File> = emptyList()) {
        var identityUncertain = false
        val identities = files.map { file ->
            try { file.canonicalFile } catch (error: Throwable) {
                if (error !== failure) failure.addSuppressed(error)
                identityUncertain = true
                file.absoluteFile.normalize()
            }
        }
        retained += Retained(failure, resources, identities, identityUncertain)
    }

    @Synchronized
    fun failureFor(file: File): WorkerRetirementException? {
        val path = try { file.canonicalFile.toPath() } catch (error: Throwable) {
            val failure = retained.firstOrNull()?.failure
                ?: WorkerRetirementException("WORKER_RESOURCE_IDENTITY_UNAVAILABLE")
            if (error !== failure) failure.addSuppressed(error)
            return failure
        }
        return retained.firstOrNull { record -> record.identityUncertain || record.files.any {
            val owned = it.toPath()
            owned.startsWith(path) || path.startsWith(owned)
        } }?.failure
    }

    fun requireReleased(file: File) { failureFor(file)?.let { throw it } }
}

internal val WorkerRetirementQuarantine = WorkerResourceQuarantine()
