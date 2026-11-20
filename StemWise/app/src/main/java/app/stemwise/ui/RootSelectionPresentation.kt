package app.stemwise.ui

import app.stemwise.data.RETAINED_OPERATION_ROOT_REQUIRED

internal data class LibraryRecoveryPresentation(
    val summary: String,
    val reason: String,
    val nextStep: String,
    val actionLabel: String,
    val reconnectsFolder: Boolean,
    val operationalNotice: String?,
    val restartRequired: Boolean = false
)

internal fun libraryRecoveryPresentation(
    code: String,
    message: String
): LibraryRecoveryPresentation = when (code) {
    app.stemwise.data.LIBRARY_OWNER_RETIREMENT_PENDING -> LibraryRecoveryPresentation(
        summary = "The previous Library operation has not stopped.",
        reason = "StemWise could not safely finish changing Libraries",
        nextStep = "Force stop StemWise, then reopen it before trying again. Your Library files were preserved.",
        actionLabel = "Close StemWise",
        reconnectsFolder = false,
        operationalNotice = message.takeIf(String::isNotBlank),
        restartRequired = true
    )
    RETAINED_OPERATION_ROOT_REQUIRED -> LibraryRecoveryPresentation(
        summary = "A pending operation still uses the selected Library.",
        reason = "An online stem job is still attached to the original folder",
        nextStep = "Resolve the pending operation before changing Library folders.",
        actionLabel = "Retry",
        reconnectsFolder = false,
        operationalNotice = message.takeIf(String::isNotBlank)
    )
    "ROOT_PERMISSION_LOST" -> LibraryRecoveryPresentation(
        summary = "The Library folder could not be opened.",
        reason = "Folder permission expired",
        nextStep = "Reconnect the same folder to continue.",
        actionLabel = "Reconnect",
        reconnectsFolder = true,
        operationalNotice = message.takeIf(String::isNotBlank)
    )
    "ROOT_UNAVAILABLE" -> LibraryRecoveryPresentation(
        summary = "The Library folder could not be opened.",
        reason = "Library folder unavailable",
        nextStep = "Connect the storage and reconnect the same folder.",
        actionLabel = "Reconnect",
        reconnectsFolder = true,
        operationalNotice = message.takeIf(String::isNotBlank)
    )
    else -> LibraryRecoveryPresentation(
        summary = "The Library could not finish opening.",
        reason = message.trim().takeIf(String::isNotEmpty)
            ?: "Library recovery was interrupted",
        nextStep = "Try recovery again to continue.",
        actionLabel = "Retry",
        reconnectsFolder = false,
        operationalNotice = null
    )
}

internal fun libraryRootFailureNeedsReconnect(
    hasRememberedRoot: Boolean,
    errorCode: String?
): Boolean = hasRememberedRoot && errorCode in setOf("ROOT_PERMISSION_LOST", "ROOT_UNAVAILABLE")

internal data class LibraryRootGatePresentation(
    val heading: String,
    val actionLabel: String,
    val operationalError: String?
)

internal fun libraryRootGatePresentation(
    errorCode: String?,
    operationalError: String?
): LibraryRootGatePresentation = LibraryRootGatePresentation(
    heading = "Choose your Library folder",
    actionLabel = "Choose folder",
    operationalError = operationalError?.takeIf(String::isNotBlank) ?: when (errorCode) {
        "ROOT_MANIFEST_INVALID", "ROOT_LAYOUT_UNSUPPORTED" ->
            "This folder cannot be used. Choose a different folder."
        "ROOT_PERMISSION_LOST" ->
            "Folder access was not granted. Choose a folder to continue."
        else -> null
    }
)
