package app.stemwise.playback

import app.stemwise.playback.engine.EngineStemLane

data class DerivedStemPackageIdentity(
    val packageSignature: String,
    val stemRevision: String
)

/** The declared assets, rather than a possibly stale Song row, own package identity. */
fun derivedStemPackageIdentity(
    songId: String,
    lanes: List<EngineStemLane>
): DerivedStemPackageIdentity {
    require(songId.isNotBlank())
    val canonical = requireNotNull(app.stemwise.playback.engine.canonicalEngineStemLanes(lanes))
    val signature = app.stemwise.playback.engine.engineStemPackageSignature(canonical)
    return DerivedStemPackageIdentity(
        packageSignature = signature,
        stemRevision = playbackStableDigest("stem-package-v1|$songId|$signature")
    )
}
