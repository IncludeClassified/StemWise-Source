package app.stemwise.data

import org.json.JSONObject
import org.json.JSONArray

data class LibraryInventory(
    val songsFound: Int = 0,
    val originalFilesFound: Int = 0,
    val stemFilesFound: Int = 0,
    val jsonFilesFound: Int = 0,
    val artworkFilesFound: Int = 0,
    val verifiedSongPackages: Int = 0,
    val verifiedOriginalFiles: Int = 0,
    val verifiedStemFiles: Int = 0,
    val verifiedJsonFiles: Int = 0,
    /** Canonical manifests that contain a PC enrichment import, not extra JSON files. */
    val pcEnrichedSongManifests: Int = 0,
    val verifiedArtworkFiles: Int = 0,
    val verifiedTotalBytes: Long = 0L,
    val actualLibraryBytes: Long = 0L,
    val temporaryOrUnclassifiedBytes: Long = 0L,
    val unknownSizeFiles: Int = 0,
    val unreadableDirectories: Int = 0,
    val appPrivateTotalBytes: Long = 0L,
    val appDatabaseBytes: Long = 0L,
    val appWorkingBytes: Long = 0L,
    val appCacheBytes: Long = 0L,
    val lastCheckedEpochMs: Long = 0L,
    val invalidPackages: Int = 0,
    val snapshotDirty: Boolean = false
)

internal data class LibraryStorageMeasurement(
    val songsFound: Int,
    val originalFilesFound: Int,
    val stemFilesFound: Int,
    val jsonFilesFound: Int,
    val artworkFilesFound: Int,
    val actualLibraryBytes: Long,
    val unknownSizeFiles: Int,
    val unreadableDirectories: Int,
    val appPrivateTotalBytes: Long,
    val appDatabaseBytes: Long,
    val appWorkingBytes: Long,
    val appCacheBytes: Long,
    val measuredAtEpochMs: Long
)

internal fun checkedLibraryByteTotal(values: Sequence<Long>): Long =
    values.fold(0L) { total, value ->
        require(value >= 0L) { "LIBRARY_SIZE_INVALID" }
        Math.addExact(total, value)
    }

/** Applies physical metadata without promoting unverified optional song.json into enrichment. */
internal fun mergeStorageMeasurement(
    previous: LibraryInventory,
    measurement: LibraryStorageMeasurement
): LibraryInventory = previous.copy(
    songsFound = measurement.songsFound,
    originalFilesFound = measurement.originalFilesFound,
    stemFilesFound = measurement.stemFilesFound,
    jsonFilesFound = measurement.jsonFilesFound,
    artworkFilesFound = measurement.artworkFilesFound,
    actualLibraryBytes = measurement.actualLibraryBytes,
    temporaryOrUnclassifiedBytes =
        (measurement.actualLibraryBytes - previous.verifiedTotalBytes).coerceAtLeast(0L),
    unknownSizeFiles = measurement.unknownSizeFiles,
    unreadableDirectories = measurement.unreadableDirectories,
    appPrivateTotalBytes = measurement.appPrivateTotalBytes,
    appDatabaseBytes = measurement.appDatabaseBytes,
    appWorkingBytes = measurement.appWorkingBytes,
    appCacheBytes = measurement.appCacheBytes,
    lastCheckedEpochMs = measurement.measuredAtEpochMs,
    snapshotDirty = previous.snapshotDirty ||
        measurement.songsFound != previous.verifiedSongPackages ||
        measurement.originalFilesFound != previous.verifiedOriginalFiles ||
        measurement.stemFilesFound != previous.verifiedStemFiles ||
        measurement.jsonFilesFound != previous.verifiedJsonFiles ||
        measurement.artworkFilesFound != previous.verifiedArtworkFiles
)

/** Metadata measurement cannot overwrite newer canonical package verification. */
internal fun preserveCanonicalInventory(measured: LibraryInventory, current: LibraryInventory): LibraryInventory =
    measured.copy(
        verifiedSongPackages = current.verifiedSongPackages,
        verifiedOriginalFiles = current.verifiedOriginalFiles,
        verifiedStemFiles = current.verifiedStemFiles,
        verifiedJsonFiles = current.verifiedJsonFiles,
        pcEnrichedSongManifests = current.pcEnrichedSongManifests,
        verifiedArtworkFiles = current.verifiedArtworkFiles,
        verifiedTotalBytes = current.verifiedTotalBytes,
        invalidPackages = current.invalidPackages,
        temporaryOrUnclassifiedBytes = (measured.actualLibraryBytes - current.verifiedTotalBytes).coerceAtLeast(0L),
        snapshotDirty = measured.snapshotDirty || current.snapshotDirty
    )

internal fun libraryInventorySnapshotJson(value: LibraryInventory): JSONObject = JSONObject()
    .put("snapshotVersion", 3)
    .put("songsFound", value.songsFound)
    .put("originalsFound", value.originalFilesFound)
    .put("stemsFound", value.stemFilesFound)
    .put("jsonFound", value.jsonFilesFound)
    .put("artworkFound", value.artworkFilesFound)
    .put("ready", value.verifiedSongPackages)
    .put("originals", value.verifiedOriginalFiles)
    .put("stems", value.verifiedStemFiles)
    .put("json", value.verifiedJsonFiles)
    .put("pcEnriched", value.pcEnrichedSongManifests)
    .put("artwork", value.verifiedArtworkFiles)
    .put("recognized", value.verifiedTotalBytes)
    .put("actual", value.actualLibraryBytes)
    .put("unclassified", value.temporaryOrUnclassifiedBytes)
    .put("unknown", value.unknownSizeFiles)
    .put("unreadableDirectories", value.unreadableDirectories)
    .put("appTotal", value.appPrivateTotalBytes)
    .put("database", value.appDatabaseBytes)
    .put("working", value.appWorkingBytes)
    .put("cache", value.appCacheBytes)
    .put("checked", value.lastCheckedEpochMs)
    .put("invalid", value.invalidPackages)
    .put("dirty", value.snapshotDirty)

private val inventorySnapshotV3Keys = setOf(
    "snapshotVersion", "songsFound", "originalsFound", "stemsFound", "jsonFound",
    "artworkFound", "ready", "originals", "stems", "json", "pcEnriched", "artwork",
    "recognized", "actual", "unclassified", "unknown", "unreadableDirectories", "appTotal",
    "database", "working", "cache", "checked", "invalid", "dirty"
)

private fun JSONObject.exactKeys(): Set<String> = keys().asSequence().toSet()

private fun JSONObject.exactNonnegativeLong(key: String): Long =
    checkNotNull(exactAtomicJournalLong(opt(key))) { "INVENTORY_SNAPSHOT_INVALID" }
        .also { check(it >= 0L) { "INVENTORY_SNAPSHOT_INVALID" } }

private fun JSONObject.exactNonnegativeInt(key: String): Int =
    exactNonnegativeLong(key).also {
        check(it <= Int.MAX_VALUE) { "INVENTORY_SNAPSHOT_INVALID" }
    }.toInt()

private fun JSONObject.exactBoolean(key: String): Boolean =
    (opt(key) as? Boolean) ?: error("INVENTORY_SNAPSHOT_INVALID")



/** Strict trust-boundary decoder; malformed cache is ignored and replaced by an authoritative scan. */
internal fun decodeLibraryInventorySnapshot(json: JSONObject): LibraryInventory {
    check(exactAtomicJournalLong(json.opt("snapshotVersion")) == 3L &&
        json.exactKeys() == inventorySnapshotV3Keys) { "INVENTORY_SNAPSHOT_INVALID" }

    return LibraryInventory(
        songsFound = json.exactNonnegativeInt("songsFound"),
        originalFilesFound = json.exactNonnegativeInt("originalsFound"),
        stemFilesFound = json.exactNonnegativeInt("stemsFound"),
        jsonFilesFound = json.exactNonnegativeInt("jsonFound"),
        artworkFilesFound = json.exactNonnegativeInt("artworkFound"),
        verifiedSongPackages = json.exactNonnegativeInt("ready"),
        verifiedOriginalFiles = json.exactNonnegativeInt("originals"),
        verifiedStemFiles = json.exactNonnegativeInt("stems"),
        verifiedJsonFiles = json.exactNonnegativeInt("json"),
        pcEnrichedSongManifests = json.exactNonnegativeInt("pcEnriched"),
        verifiedArtworkFiles = json.exactNonnegativeInt("artwork"),
        verifiedTotalBytes = json.exactNonnegativeLong("recognized"),
        actualLibraryBytes = json.exactNonnegativeLong("actual"),
        temporaryOrUnclassifiedBytes = json.exactNonnegativeLong("unclassified"),
        unknownSizeFiles = json.exactNonnegativeInt("unknown"),
        unreadableDirectories = json.exactNonnegativeInt("unreadableDirectories"),
        appPrivateTotalBytes = json.exactNonnegativeLong("appTotal"),
        appDatabaseBytes = json.exactNonnegativeLong("database"),
        appWorkingBytes = json.exactNonnegativeLong("working"),
        appCacheBytes = json.exactNonnegativeLong("cache"),
        lastCheckedEpochMs = json.exactNonnegativeLong("checked"),
        invalidPackages = json.exactNonnegativeInt("invalid"),
        snapshotDirty = json.exactBoolean("dirty")
    )
}
