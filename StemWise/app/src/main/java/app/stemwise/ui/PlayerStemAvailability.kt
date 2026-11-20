package app.stemwise.ui

import app.stemwise.data.StemAssetEntity
import app.stemwise.model.StemRoleContract

internal enum class PlayerStemAvailability(val hasCompleteSet: Boolean, val settled: Boolean) {
    RESOLVING(false, false), MISSING(false, true), PREPARING(true, false), READY(true, true)
}

internal fun playerStemAvailability(
    assets: List<StemAssetEntity>,
    playbackPackagePresent: Boolean,
    contentLoaded: Boolean,
    hasUsableSeparationSets: Boolean,
    currentAudio: Boolean,
    controllerReady: Boolean
): PlayerStemAvailability {
    val complete = StemRoleContract.isPlayable(assets.map {
        it.normalizedCapability?.takeIf(String::isNotBlank) ?: it.providerLabel
    })
    val resolved = playbackPackagePresent || contentLoaded && (complete || !hasUsableSeparationSets)
    return when {
        complete && resolved && currentAudio && controllerReady -> PlayerStemAvailability.READY
        complete -> PlayerStemAvailability.PREPARING
        resolved -> PlayerStemAvailability.MISSING
        else -> PlayerStemAvailability.RESOLVING
    }
}
