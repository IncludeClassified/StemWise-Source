package app.stemwise.playback

import app.stemwise.playback.engine.EngineSourceMode
import app.stemwise.playback.engine.PlaybackCommittedPublication
import app.stemwise.playback.engine.PlaybackPublicationSourceState
import app.stemwise.playback.engine.PlaybackStemPackagePhase

/** Resolves one manual request without letting a superseded lookup alter current admission. */
suspend fun <T : Any> resolveCurrentManualSelection(
    resolve: suspend () -> T?,
    isCurrent: () -> Boolean,
    onUnavailable: (Throwable?) -> Unit
): T? {
    val resolved = try {
        resolve()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (isCurrent()) onUnavailable(cancelled)
        throw cancelled
    } catch (failure: Throwable) {
        if (isCurrent()) onUnavailable(failure)
        return null
    }
    if (!isCurrent()) return null
    if (resolved == null) onUnavailable(null)
    return resolved
}


/** A hot source switch reuses its installed package without another package-ready event. */
private fun stemPackageIsPrepared(
    phase: PlaybackStemPackagePhase,
    installedMatches: Boolean
): Boolean = phase == PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE ||
    (phase == PlaybackStemPackagePhase.INSTALLED && installedMatches)

fun stemActivationSourceIsReady(
    source: PlaybackPublicationSourceState,
    phase: PlaybackStemPackagePhase,
    playWhenReady: Boolean,
    sourceRevision: Long,
    installedMatches: Boolean
): Boolean {
    if (sourceRevision <= 0L || source.desiredRevision != sourceRevision ||
        source.desiredMode != EngineSourceMode.STEMS
    ) return false
    return (source.appliedRevision == sourceRevision &&
        source.appliedMode == EngineSourceMode.STEMS && installedMatches &&
        phase == PlaybackStemPackagePhase.INSTALLED) ||
        (!playWhenReady && source.preparedRevision == sourceRevision &&
            source.preparedMode == EngineSourceMode.STEMS &&
            stemPackageIsPrepared(phase, installedMatches))
}


data class PreparedSourceResumeProof(
    val ownerToken: Long,
    val desiredRevision: Long,
    val mode: EngineSourceMode,
    val stemPackageSignature: String? = null,
    val stemRevision: String? = null
)

enum class StemPresentationPackageAuthority { NONE, PREPARED, INSTALLED }

data class ProjectedSourcePresentation(
    val mode: EngineSourceMode,
    val preparedMode: EngineSourceMode?,
    val stemPackageAuthority: StemPresentationPackageAuthority,
    val resumeProof: PreparedSourceResumeProof?
)

/**
 * One source/package presentation transaction. Transport may bridge an exact paused preparation
 * into Play, but it cannot choose a different source mode or mixer package.
 */
fun projectedSourcePresentation(
    committed: PlaybackCommittedPublication,
    requestedStemCandidateMatches: Boolean,
    installedStemCandidateAvailable: Boolean,
    installedStemPackageMatchesDesired: Boolean,
    previousResumeProof: PreparedSourceResumeProof?
): ProjectedSourcePresentation {
    val sourceIssue = committed.issue?.category == "source-mode" ||
        committed.issue?.category == "stem-package"
    val exactPreparedSource = committed.source.desiredRevision > 0L &&
        committed.source.preparedRevision == committed.source.desiredRevision &&
        committed.source.preparedMode == committed.source.desiredMode
    val pausedPreparedOriginal = !sourceIssue &&
        pausedPreparedOriginalMatches(
            playWhenReady = committed.transport.playWhenReady,
            desiredRevision = committed.source.desiredRevision,
            desiredSourceMode = committed.source.desiredMode,
            preparedRevision = committed.source.preparedRevision,
            preparedSourceMode = committed.source.preparedMode
        )
    val pausedPreparedStems = !sourceIssue && !committed.transport.playWhenReady &&
        committed.source.desiredMode == EngineSourceMode.STEMS && exactPreparedSource &&
        stemPackageIsPrepared(committed.stems.phase, installedStemPackageMatchesDesired) &&
        requestedStemCandidateMatches
    // A seek's source acknowledgement can precede its separate package-install event.
    val acknowledgedPreparedStems = !sourceIssue && committed.transport.playWhenReady &&
        committed.source.desiredMode == EngineSourceMode.STEMS && exactPreparedSource &&
        committed.source.appliedRevision == committed.source.desiredRevision &&
        committed.source.appliedMode == EngineSourceMode.STEMS &&
        committed.stems.phase == PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE &&
        requestedStemCandidateMatches
    val desiredStemPackage = committed.stems.desired
    val exactSourceAudiblySettled = !sourceIssue &&
        committed.source.appliedRevision == committed.source.desiredRevision &&
        committed.source.appliedMode == committed.source.desiredMode &&
        (committed.source.desiredMode == EngineSourceMode.ORIGINAL ||
            (committed.stems.phase == PlaybackStemPackagePhase.INSTALLED &&
                installedStemPackageMatchesDesired))
    val resumeProof = when {
        exactSourceAudiblySettled -> null
        pausedPreparedOriginal -> PreparedSourceResumeProof(
            ownerToken = committed.selection.token.value,
            desiredRevision = committed.source.desiredRevision,
            mode = EngineSourceMode.ORIGINAL
        )
        pausedPreparedStems || acknowledgedPreparedStems -> PreparedSourceResumeProof(
            ownerToken = committed.selection.token.value,
            desiredRevision = committed.source.desiredRevision,
            mode = EngineSourceMode.STEMS,
            stemPackageSignature = desiredStemPackage?.packageSignature,
            stemRevision = desiredStemPackage?.stemRevision
        )
        preparedSourceResumeProofMatches(
            proof = previousResumeProof,
            ownerToken = committed.selection.token.value,
            desiredRevision = committed.source.desiredRevision,
            desiredSourceMode = committed.source.desiredMode,
            preparedRevision = committed.source.preparedRevision,
            preparedSourceMode = committed.source.preparedMode,
            stemPhase = committed.stems.phase,
            installedStemPackageMatchesDesired = installedStemPackageMatchesDesired,
            desiredPackageSignature = desiredStemPackage?.packageSignature,
            desiredStemRevision = desiredStemPackage?.stemRevision,
            issueCategory = committed.issue?.category
        ) -> previousResumeProof
        else -> null
    }
    val preparedMode = when {
        pausedPreparedOriginal || pausedPreparedStems || acknowledgedPreparedStems -> committed.source.desiredMode
        resumeProof != null -> resumeProof.mode
        else -> null
    }
    val stemPackageAuthority = when {
        preparedMode == EngineSourceMode.STEMS -> StemPresentationPackageAuthority.PREPARED
        installedStemCandidateAvailable -> StemPresentationPackageAuthority.INSTALLED
        else -> StemPresentationPackageAuthority.NONE
    }
    return ProjectedSourcePresentation(
        mode = preparedMode ?: committed.source.appliedMode,
        preparedMode = preparedMode,
        stemPackageAuthority = stemPackageAuthority,
        resumeProof = resumeProof
    )
}

fun <T> projectedStemPresentationValue(
    authority: StemPresentationPackageAuthority,
    installed: T?,
    prepared: T?
): T? = when (authority) {
    StemPresentationPackageAuthority.NONE -> null
    StemPresentationPackageAuthority.PREPARED -> prepared
    StemPresentationPackageAuthority.INSTALLED -> installed
}


fun pausedPreparedOriginalMatches(
    playWhenReady: Boolean,
    desiredRevision: Long,
    desiredSourceMode: EngineSourceMode,
    preparedRevision: Long,
    preparedSourceMode: EngineSourceMode
): Boolean = !playWhenReady &&
    desiredRevision > 0L &&
    desiredSourceMode == EngineSourceMode.ORIGINAL &&
    preparedRevision == desiredRevision &&
    preparedSourceMode == EngineSourceMode.ORIGINAL

fun preparedSourceResumeProofMatches(
    proof: PreparedSourceResumeProof?,
    ownerToken: Long,
    desiredRevision: Long,
    desiredSourceMode: EngineSourceMode,
    preparedRevision: Long,
    preparedSourceMode: EngineSourceMode,
    stemPhase: PlaybackStemPackagePhase,
    installedStemPackageMatchesDesired: Boolean,
    desiredPackageSignature: String?,
    desiredStemRevision: String?,
    issueCategory: String?
): Boolean {
    if (proof == null || issueCategory == "source-mode" || issueCategory == "stem-package") {
        return false
    }
    if (proof.ownerToken != ownerToken || proof.desiredRevision != desiredRevision ||
        proof.mode != desiredSourceMode || preparedRevision != desiredRevision ||
        preparedSourceMode != desiredSourceMode
    ) return false
    return when (desiredSourceMode) {
        EngineSourceMode.ORIGINAL ->
            proof.stemPackageSignature == null && proof.stemRevision == null
        EngineSourceMode.STEMS ->
            stemPackageIsPrepared(stemPhase, installedStemPackageMatchesDesired) &&
                !desiredPackageSignature.isNullOrBlank() &&
                !desiredStemRevision.isNullOrBlank() &&
                proof.stemPackageSignature == desiredPackageSignature &&
                proof.stemRevision == desiredStemRevision
    }
}
