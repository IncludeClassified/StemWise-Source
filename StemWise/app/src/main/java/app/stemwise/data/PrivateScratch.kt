package app.stemwise.data

import java.io.File
import java.security.MessageDigest

internal data class PrivateScratchInventory(
    val entries: Int,
    val payloadBytes: Long
)

internal fun checkedPrivateChildren(directory: File, errorCode: String): List<File> {
    check(!java.nio.file.Files.isSymbolicLink(directory.toPath())) { errorCode }
    if (!directory.exists()) return emptyList()
    check(directory.isDirectory) { errorCode }
    return directory.listFiles()?.toList() ?: error(errorCode)
}

/** Reads one stable app-private file without trusting its declared size after allocation. */
internal fun readBoundedPrivateFile(file: File, maximumBytes: Long, errorCode: String): ByteArray {
    require(maximumBytes in 1..Int.MAX_VALUE.toLong())
    check(file.isFile) { errorCode }
    val length = file.length()
    check(length in 1..maximumBytes) { errorCode }
    val bytes = ByteArray(length.toInt())
    file.inputStream().use { input ->
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            check(read > 0) { errorCode }
            offset += read
        }
        check(input.read() == -1) { errorCode }
    }
    return bytes
}

/** Validate ownership for the complete pass before deleting the first byte. */
internal fun cleanupExactPrivateChildren(
    directory: File,
    errorCode: String,
    preserve: Set<File> = emptySet(),
    isOwned: (File) -> Boolean
): PrivateScratchInventory {
    app.stemwise.integration.bundledToolRuntimeMutex.requireHealthy()
    app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(directory)
    val children = checkedPrivateChildren(directory, errorCode)
    val (cleanupMarkers, payloadChildren) = children.partition(::isWorkingMediaCleanupRefusalMarker)
    check(payloadChildren.all(isOwned)) { "${errorCode}_UNKNOWN_ENTRY" }
    val before = inventoryPrivateScratch(directory, errorCode)
    val preserved = preserve.mapTo(hashSetOf()) { it.canonicalFile }
    check(preserved.all { it.parentFile == directory.canonicalFile }) { errorCode }
    val preservedMarkers = preserved.mapTo(hashSetOf()) { workingMediaCleanupRefusalMarker(directory.canonicalFile, it) }
    payloadChildren.filterNot { it.canonicalFile in preserved }.sortedBy(File::getName).forEach { child ->
        deleteExactPrivateTree(directory, child, errorCode)
    }
    cleanupMarkers.filterNot { it.canonicalFile in preservedMarkers }.sortedBy(File::getName).forEach { marker ->
        // The corresponding payload deletion already reaps its own refusal marker.
        check(!marker.exists() || (isWorkingMediaCleanupRefusalMarker(marker) && marker.delete())) { errorCode }
    }
    check(checkedPrivateChildren(directory, errorCode).all {
        it.canonicalFile in preserved || it.canonicalFile in preservedMarkers
    }) { errorCode }
    return before
}

internal fun deleteExactPrivateTree(
    root: File,
    target: File,
    errorCode: String,
    delete: (File) -> Boolean = File::delete
) {
    app.stemwise.integration.bundledToolRuntimeMutex.requireHealthy()
    app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(target)
    val rootPath = root.toPath().toAbsolutePath().normalize()
    check(!java.nio.file.Files.isSymbolicLink(rootPath)) { errorCode }
    var current = target.toPath().toAbsolutePath().normalize()
    check(current != rootPath && current.startsWith(rootPath)) { errorCode }
    while (current != rootPath) {
        check(!java.nio.file.Files.isSymbolicLink(current)) { errorCode }
        current = checkNotNull(current.parent) { errorCode }
    }
    val canonicalRoot = root.canonicalFile
    val canonicalTarget = target.canonicalFile
    val refusalMarker = workingMediaCleanupRefusalMarker(canonicalRoot, canonicalTarget)
    try {
        deleteExactPrivateTreeUnchecked(canonicalRoot, canonicalTarget, errorCode, delete)
        if (refusalMarker.exists()) {
            check(refusalMarker.isFile && delete(refusalMarker)) { errorCode }
        }
    } catch (error: Throwable) {
        runCatching {
            check(canonicalRoot.isDirectory) { errorCode }
            if (!refusalMarker.exists()) check(refusalMarker.createNewFile()) { errorCode }
            check(refusalMarker.isFile) { errorCode }
        }.exceptionOrNull()?.let { markerError ->
            if (markerError !== error) error.addSuppressed(markerError)
        }
        throw error
    }
}

private fun deleteExactPrivateTreeUnchecked(
    canonicalRoot: File,
    canonicalTarget: File,
    errorCode: String,
    delete: (File) -> Boolean
) {
    check(!java.nio.file.Files.isSymbolicLink(canonicalTarget.toPath())) { errorCode }
    if (!canonicalTarget.exists()) return
    check(
        canonicalTarget != canonicalRoot &&
            canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
    ) { errorCode }
    if (canonicalTarget.isDirectory) {
        checkedPrivateChildren(canonicalTarget, errorCode)
            .sortedBy(File::getName)
            .forEach { child ->
                deleteExactPrivateTreeUnchecked(canonicalRoot, child, errorCode, delete)
            }
    } else {
        check(canonicalTarget.isFile) { errorCode }
    }
    check(delete(canonicalTarget)) { errorCode }
}

private fun workingMediaCleanupRefusalMarker(root: File, target: File): File {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(target.path.toByteArray(Charsets.UTF_8))
        .toHexString()
    return File(root, "$WORKING_MEDIA_CLEANUP_REFUSAL_PREFIX$digest")
}

internal fun isWorkingMediaCleanupRefusalMarker(file: File): Boolean {
    if (!file.name.startsWith(WORKING_MEDIA_CLEANUP_REFUSAL_PREFIX)) return false
    val suffix = file.name.removePrefix(WORKING_MEDIA_CLEANUP_REFUSAL_PREFIX)
    return suffix.length == 64 && suffix.all { it in '0'..'9' || it in 'a'..'f' } &&
        file.isFile && file.length() == 0L
}

private const val WORKING_MEDIA_CLEANUP_REFUSAL_PREFIX =
    ".stemwise-working-media-cleanup-refused-"

internal fun inventoryPrivateScratch(directory: File, errorCode: String): PrivateScratchInventory {
    if (!directory.exists()) return PrivateScratchInventory(0, 0L)
    var entries = 0
    var bytes = 0L
    fun visit(current: File) {
        checkedPrivateChildren(current, errorCode).forEach { child ->
            check(!java.nio.file.Files.isSymbolicLink(child.toPath())) { errorCode }
            entries += 1
            if (child.isDirectory) visit(child) else {
                check(child.isFile) { errorCode }
                bytes = Math.addExact(bytes, child.length().coerceAtLeast(0L))
            }
        }
    }
    visit(directory)
    return PrivateScratchInventory(entries, bytes)
}

/** Preserves the product failure and attaches cleanup refusal as secondary evidence. */
internal inline fun <T> withCheckedPrivateCleanup(
    cleanup: () -> Unit,
    block: () -> T
): T {
    var primary: Throwable? = null
    try {
        return block()
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try {
            cleanup()
        } catch (cleanupError: Throwable) {
            val productFailure = primary
            if (productFailure != null) {
                if (cleanupError !== productFailure) productFailure.addSuppressed(cleanupError)
            } else {
                throw cleanupError
            }
        }
    }
}
