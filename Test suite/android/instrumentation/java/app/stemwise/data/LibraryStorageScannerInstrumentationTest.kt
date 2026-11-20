package app.stemwise.data

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryStorageScannerInstrumentationTest {
    @Test
    fun realScannerCountsPrivateBytesOnceAcrossOverlappingStorageRoots() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = IsolatedStorageContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val id = "a".repeat(32)
            fun write(path: String, size: Int): File = File(context.noBackupFilesDir, path).apply {
                check(parentFile!!.isDirectory || parentFile!!.mkdirs())
                writeBytes(ByteArray(size))
            }
            write("pc-song-jobs/$id.json", 11)
            write("pc-song-jobs/$id.new", 4)
            val zip = write("pc-song-jobs/$id.zip", 7)
            val guide = write("pc-song-jobs/$id.md", 3)
            write("bridge-node/bridge-incoming/pi/$id/metadata.json", 5)
            val incoming = write("bridge-node/bridge-incoming/pi/$id/content", 13)
            write("bridge-node/bridge-outgoing/pi/.incoming-$id/metadata.json", 2)
            val outgoing = write("bridge-node/bridge-outgoing/pi/.incoming-$id/content", 17)
            write("bridge-node/credentials", 19)
            File(context.cacheDir, "cache.bin").writeBytes(ByteArray(9))
            File(context.codeCacheDir, "code.bin").writeBytes(ByteArray(5))
            val scanner = LibraryStorageScanner(context, BackgroundWorkCoordinator())
            val actual = scanner.scanAppPrivateStorage()
            assertEquals(95L, actual.totalBytes)
            assertEquals(81L, actual.workingBytes)
            assertEquals(14L, actual.cacheBytes)
            listOf(zip, guide, incoming, outgoing).forEach { check(it.delete()) }
            val retainedMetadata = scanner.scanAppPrivateStorage()
            assertEquals(55L, retainedMetadata.totalBytes)
            write("bridge-node/bridge-incoming/unowned.txt", 1)
            assertEquals(56L, scanner.scanAppPrivateStorage().totalBytes)
        } finally { context.destroy() }
    }

    private class IsolatedStorageContext(base: Context) : ContextWrapper(base) {
        private val root = File(base.cacheDir, "storage-scan-${UUID.randomUUID()}").apply { check(mkdir()) }
        private val files = File(root, "files").apply { check(mkdir()) }
        private val noBackup = File(root, "no-backup").apply { check(mkdir()) }
        private val cache = File(root, "cache").apply { check(mkdir()) }
        private val codeCache = File(cache, "code").apply { check(mkdir()) }
        private val databases = File(root, "databases").apply { check(mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getCacheDir(): File = cache
        override fun getCodeCacheDir(): File = codeCache
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(baseContext.applicationInfo).apply {
            dataDir = root.absolutePath
        }
        fun destroy() {
            check(root.canonicalFile.parentFile == baseContext.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
}
