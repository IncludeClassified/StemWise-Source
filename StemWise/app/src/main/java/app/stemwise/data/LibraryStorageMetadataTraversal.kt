package app.stemwise.data

/** Metadata returned by one row of a DocumentsProvider child query. */
internal data class StorageDocumentMetadata(
    val documentId: String,
    val displayName: String,
    val directory: Boolean,
    val sizeBytes: Long?
)

internal const val STORAGE_SCAN_MAXIMUM_CONCURRENT_LISTINGS = 16
