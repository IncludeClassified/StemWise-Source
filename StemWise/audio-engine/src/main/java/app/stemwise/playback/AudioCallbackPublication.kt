package app.stemwise.playback

/** Exact callback ownership and revision identify the controls that actually reached output. */
fun callbackControlPublication(
    owner: app.stemwise.playback.engine.NativeRuntimeOwnership?,
    evidence: app.stemwise.playback.engine.NativeRuntimeAudibleControlEvidence?,
    publications: List<app.stemwise.playback.engine.NativeSourceControlPublicationDiagnostics>
): app.stemwise.playback.engine.NativeSourceControlPublicationDiagnostics? =
    evidence?.takeIf { it.ownership == owner }?.let { callback -> publications.lastOrNull {
        it.ownership == owner && it.nativeControlRevision == callback.controlRevision &&
            it.nativeMode == callback.actualSourceMode
    } }
