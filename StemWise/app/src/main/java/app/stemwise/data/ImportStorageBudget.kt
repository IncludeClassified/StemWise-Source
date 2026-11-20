package app.stemwise.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.stemwise.integration.queryCancellable
import kotlinx.coroutines.CancellationException

internal const val IMPORT_STORAGE_RESERVE_BYTES = 128L * 1024L * 1024L
internal val IMPORT_STORAGE_FAILURE_CODES = setOf("STORAGE_RESERVE_LOW", "LIBRARY_STORAGE_LOW")

internal fun isImportStorageFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(16).any {
        it.message in IMPORT_STORAGE_FAILURE_CODES || it is java.io.IOException &&
            (it.message.orEmpty().contains("ENOSPC", ignoreCase = true) ||
                it.message.orEmpty().contains("No space left on device", ignoreCase = true))
    }

internal fun requireImportStorageCapacity(availableBytes: Long?, additionalBytes: Long, errorCode: String) {
    require(additionalBytes >= 0L)
    check(availableBytes == null || availableBytes >= additionalBytes &&
        availableBytes - additionalBytes >= IMPORT_STORAGE_RESERVE_BYTES) { errorCode }
}

/** A provider may omit capacity; only an exact advertised root supplies admission evidence. */
internal suspend fun providerImportAvailableBytes(context: Context, treeUri: Uri): Long? = try {
    val documentId = DocumentsContract.getTreeDocumentId(treeUri)
    context.contentResolver.queryCancellable(DocumentsContract.buildRootsUri(checkNotNull(treeUri.authority)),
        arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)) { cursor ->
        val identity = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
        val available = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        if (identity < 0 || available < 0) return@queryCancellable null
        while (cursor.moveToNext()) {
            if (cursor.getString(identity) == documentId && !cursor.isNull(available)) {
                return@queryCancellable cursor.getLong(available).takeIf { it >= 0L }
            }
        }
        null
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: UnsupportedOperationException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: java.io.FileNotFoundException) {
    null
}
