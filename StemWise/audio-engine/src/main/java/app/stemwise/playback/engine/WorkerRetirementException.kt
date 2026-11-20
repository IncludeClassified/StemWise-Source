package app.stemwise.playback.engine

/** Cancellation does not waive proof that the external owner has retired. */
class WorkerRetirementException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
