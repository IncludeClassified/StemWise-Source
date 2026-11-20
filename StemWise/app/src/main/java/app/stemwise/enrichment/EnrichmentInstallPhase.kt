package app.stemwise.enrichment

/** Private install journal phases shared by the writer and restart recovery. */
internal object EnrichmentInstallPhase {
    const val PREPARED = "PREPARED"
    const val COMMITTING = "COMMITTING"
    const val SOURCE_BINDING_COMMIT = "SOURCE_BINDING_COMMIT"
    const val COMPLETE = "COMPLETE"
    const val PREPARATION_ABORTED = "PREPARATION_ABORTED"
    const val ROLLED_BACK = "ROLLED_BACK"
    val recoveryStates = setOf(PREPARED, COMMITTING, SOURCE_BINDING_COMMIT, COMPLETE, ROLLED_BACK, PREPARATION_ABORTED)
}

internal fun requireEnrichmentRecoveryState(state: String) {
    check(state in EnrichmentInstallPhase.recoveryStates) { "RECOVERY_PENDING" }
}
