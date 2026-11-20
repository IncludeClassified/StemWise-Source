package app.stemwise.qualification

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import app.stemwise.StemwiseApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@app.stemwise.qualification.ManagedAudioTest
class StandardInstrumentationTargetGuardTest {
    @Test
    fun installedApkGuardRejectsAbsentWrongAndChangedBytes() {
        val apk = File.createTempFile("installed-apk-guard-", ".bin",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        try {
            apk.writeBytes("abc".toByteArray(Charsets.UTF_8))
            StandardInstrumentationTargetGuard.requireInstalledApk(expected.uppercase(), apk)
            for (invalid in listOf(null, "", "not-a-digest", "0".repeat(64))) {
                assertThrows(IllegalStateException::class.java) {
                    StandardInstrumentationTargetGuard.requireInstalledApk(invalid, apk)
                }
            }
            apk.appendText("d", Charsets.UTF_8)
            assertThrows(IllegalStateException::class.java) {
                StandardInstrumentationTargetGuard.requireInstalledApk(expected, apk)
            }
        } finally { check(apk.delete()) }
    }

    @Test
    fun currentTargetIsTheExactDisposablePackage() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StemwiseApplication
        val startup = async(start = CoroutineStart.UNDISPATCHED) { application.workerInitialization.await() }
        try {
            assertTrue("Standard runner admitted tests before application startup completed", startup.isCompleted)
        } finally { startup.cancelAndJoin() }
    }

    @Test
    fun guardRejectsOwnerAndUnexpectedTargets() {
        listOf(
            StandardInstrumentationTargetGuard.OWNER_PACKAGE,
            "app.stemwise.other",
            ""
        ).forEach { unsafeTarget ->
            assertThrows(IllegalStateException::class.java) {
                StandardInstrumentationTargetGuard.requireExactTargetPackage(unsafeTarget)
            }
        }
    }
}
