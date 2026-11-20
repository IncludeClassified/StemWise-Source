package app.stemwise.enrichment

internal const val ENRICHMENT_STAGING_JOURNAL_NAME = "active-import.json"
internal const val MAX_SONG_BUNDLE_BYTES = MAX_RECORDING_BYTES + 32L * 1024L * 1024L
internal const val MAX_SONG_INTAKE_BYTES = 8L * MAX_SONG_BUNDLE_BYTES
internal const val MAX_SONG_INTAKE_ARCHIVES = 512
internal const val SONG_INTAKE_RESERVE_BYTES = 128L * 1024L * 1024L

private val IMPORT_STAGING_FILE = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}-" +
        "[0-9]+\\.(?:part|json|zip)$"
)

/** Filename agreement only; callers still own path, type and deletion admission. */
internal fun isEnrichmentStagingName(name: String): Boolean =
    name == ENRICHMENT_STAGING_JOURNAL_NAME || IMPORT_STAGING_FILE.matches(name)
