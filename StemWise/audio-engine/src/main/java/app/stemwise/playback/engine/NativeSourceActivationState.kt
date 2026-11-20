package app.stemwise.playback.engine

/** Exact actor publication mapped to a later whole-callback native evidence tuple. */
internal data class SourceControlPublication(
    val nativeControlRevision: Long,
    val ownership: NativeRuntimeOwnership,
    val token: EngineSelectionToken,
    val desiredRevision: Long,
    val requestedMode: EngineSourceMode,
    val nativeMode: NativeRuntimeSourceMode,
    val stemPackageIdentity: String?,
    val stemRevision: String?,
    val stemRequestRevision: Long?
) {
    init {
        require(nativeControlRevision > 0L && desiredRevision > 0L)
        require(token.value == ownership.selectionToken)
        require(listOf(stemPackageIdentity, stemRevision, stemRequestRevision).all { it == null } ||
            listOf(stemPackageIdentity, stemRevision, stemRequestRevision).none { it == null })
        require(stemPackageIdentity == null || stemPackageIdentity.length == 64)
    }
}

/** The existing actor owns these transitions; native acceptance and audible proof stay separate. */
internal class NativeSourceActivationState {
    var applied: SourceActivationEndpoint? = null
        private set
    var prepared: SourceActivationEndpoint? = null
        private set
    var published: NativeSourcePublication? = null
        private set
    private var accepted: SourceControlPublication? = null
    private var retired = false

    fun publish(status: NativeRuntimePublishStatus, publication: SourceControlPublication,
        desiredRevision: Long): Boolean {
        if (status != NativeRuntimePublishStatus.ACCEPTED || desiredRevision < publication.desiredRevision) return false
        val prior = accepted
        if (prior != null) {
            if (publication.ownership != prior.ownership) {
                if (publication.ownership.generation <= prior.ownership.generation) return false
            } else if (desiredRevision < requireNotNull(published).revision ||
                publication.desiredRevision < prior.desiredRevision ||
                (publication.desiredRevision == prior.desiredRevision && publication.requestedMode != prior.requestedMode) ||
                publication.nativeControlRevision < prior.nativeControlRevision) return false
        }
        if (publication.ownership != prior?.ownership) retirePhysicalEvidence()
        accepted = publication
        published = NativeSourcePublication(desiredRevision, publication.nativeMode)
        retired = false
        return true
    }

    fun prepare(ownership: NativeRuntimeOwnership, playing: Boolean): Boolean {
        val publication = accepted ?: return false
        if (retired || playing || ownership != publication.ownership ||
            publication.nativeMode.toEngineMode() != publication.requestedMode) return false
        val endpoint = SourceActivationEndpoint(publication.desiredRevision, publication.requestedMode)
        if (prepared == endpoint) return false
        prepared = endpoint
        return true
    }

    fun observeAudible(publicOwnership: NativeRuntimeOwnership?, evidence: NativeRuntimeAudibleControlEvidence,
        publication: SourceControlPublication, currentIntent: SourceActivationEndpoint): Boolean {
        val latest = accepted ?: return false
        if (retired || evidence.ownership != publicOwnership || evidence.ownership != latest.ownership ||
            publication.ownership != latest.ownership ||
            publication.nativeControlRevision != evidence.controlRevision ||
            publication.nativeControlRevision > latest.nativeControlRevision ||
            publication.nativeMode != evidence.actualSourceMode ||
            publication.nativeMode.toEngineMode() != currentIntent.mode ||
            SourceActivationEndpoint(publication.desiredRevision, publication.requestedMode) != currentIntent ||
            SourceActivationEndpoint(latest.desiredRevision, latest.requestedMode) != currentIntent ||
            applied == currentIntent) return false
        applied = currentIntent
        return true
    }

    fun retirePhysicalEvidence() { applied = null; prepared = null; retired = true }

    private fun NativeRuntimeSourceMode.toEngineMode(): EngineSourceMode = when (this) {
        NativeRuntimeSourceMode.ORIGINAL -> EngineSourceMode.ORIGINAL
        NativeRuntimeSourceMode.STEMS -> EngineSourceMode.STEMS
    }
}
