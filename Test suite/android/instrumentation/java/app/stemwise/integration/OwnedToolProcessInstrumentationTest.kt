package app.stemwise.integration

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedToolProcessInstrumentationTest {
    @org.junit.Before fun bindRuntimeOwnership() {
        bundledToolRuntimeMutex.bind(ApplicationProvider.getApplicationContext<android.content.Context>().noBackupFilesDir)
    }
    @Test
    fun separateProcessDeathRecoversOnlyAfterTheExactToolGroupRetires() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val work = File(context.cacheDir, "tool-restart-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
        val spawnedRoots = mutableListOf<File>()
        fun probe(root: File, mode: String): Pair<Int, String> {
            val builder = ProcessBuilder("/system/bin/app_process", "/system/bin",
                OwnedToolCrashProbe::class.java.name, root.path, mode).redirectErrorStream(true)
            builder.environment()["CLASSPATH"] = context.applicationInfo.sourceDir + File.pathSeparator +
                instrumentation.context.applicationInfo.sourceDir
            builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val process = builder.start()
            try {
                assertTrue("Separate process did not terminate", process.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS))
                return process.exitValue() to process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } finally { process.destroyForcibly() }
        }
        try {
            val noChild = File(work, "no-child").apply { check(mkdir()) }
            assertEquals(17, probe(noChild, "halt-before-spawn").first)
            assertEquals(0, probe(noChild, "check").first)
            val failedPublication = File(work, "failed-publication").apply { check(mkdir()) }
            val failed = probe(failedPublication, "failed-publication")
            assertEquals(failed.second, 0, failed.first)
            assertFalse(File(failedPublication, "must-not-execute").exists())
            assertEquals(0, probe(failedPublication, "check").first)
            for (mode in listOf("live-tool", "orphan-without-lease")) {
                val root = File(work, mode).apply { check(mkdir()) }
                spawnedRoots += root
                val input = File(root, "scratch/input.pcm").apply { check(parentFile!!.mkdir()); writeText("preserved", Charsets.UTF_8) }
                var ownedPid: Int? = null
                try {
                    val crashed = probe(root, mode)
                    assertEquals(crashed.second, 17, crashed.first)
                    ownedPid = Regex("TOOL_PID=(\\d+)").find(crashed.second)!!.groupValues[1].toInt()
                    repeat(2) {
                        val blocked = probe(root, "cleanup")
                        assertEquals(blocked.second, 23, blocked.first)
                        assertEquals("preserved", input.readText(Charsets.UTF_8))
                    }
                    android.system.Os.kill(-ownedPid, android.system.OsConstants.SIGKILL)
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5L)
                    var recovered: Pair<Int, String>
                    do {
                        recovered = probe(root, "cleanup")
                        if (recovered.first == 0) break
                        Thread.sleep(20L)
                    } while (System.nanoTime() < deadline)
                    assertEquals(recovered.second, 0, recovered.first)
                    assertFalse(input.exists())
                    assertFalse(File(root, ".stemwise-tool-owner").exists())
                    ownedPid = null
                } finally {
                    ownedPid?.let { runCatching { android.system.Os.kill(-it, android.system.OsConstants.SIGKILL) } }
                }
            }
            for (receipt in listOf("", "TOOL_PROCESS_RETIREMENT_UNCONFIRMED\n", "STEMWISE_TOOL_OWNER_1\n${"x".repeat(36)}\n1\n1\n")) {
                val root = File(work, "invalid-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
                File(root, ".stemwise-tool-owner").writeText(receipt, Charsets.UTF_8)
                assertEquals(23, probe(root, "check").first)
                assertEquals(receipt, File(root, ".stemwise-tool-owner").readText(Charsets.UTF_8))
            }
        } finally {
            var allRetired = true
            for (root in spawnedRoots) {
                val receipt = File(root, ".stemwise-tool-owner")
                if (receipt.isFile) receipt.readLines(Charsets.UTF_8).getOrNull(2)?.toIntOrNull()?.let {
                    runCatching { android.system.Os.kill(-it, android.system.OsConstants.SIGKILL) }
                }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5L)
                var lease: Int
                do {
                    lease = OwnedToolNative.acquireLease(root.path)
                    if (lease >= 0) break
                    Thread.sleep(20L)
                } while (System.nanoTime() < deadline)
                if (lease < 0 || !OwnedToolNative.releaseLease(lease, root.path)) allRetired = false
            }
            assertTrue("Unretired test workers retained scratch in ${work.path}", allRetired)
            assertTrue(work.deleteRecursively())
        }
    }

    @Test
    fun argvEnvironmentAndWorkingDirectoryReachTheActualTool() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = runOwnedToolProcess(listOf("/system/bin/sh", "-c",
            "printf '%s\\n' \"\$1\" \"\$STEMWISE_TOOL_MARKER\"; pwd", "owned-tool", "music-🎵-مرحبا"),
            mapOf("STEMWISE_TOOL_MARKER" to "exact value; no shell expansion"), 2L, 4096L, cwd = context.cacheDir)
        assertEquals(0, result.exitCode)
        val actual = result.stdout.trimEnd().lines()
        assertEquals(3, actual.size)
        assertEquals(listOf("music-🎵-مرحبا", "exact value; no shell expansion"), actual.take(2))
        assertEquals(context.cacheDir.canonicalFile, File(actual[2]).canonicalFile)
    }

    @Test
    fun deadlineIncludesASilentChildAndDescendantsHoldingInheritedPipes() = runBlocking {
        for (body in listOf("sleep 30", "(sleep 30) & exit 0")) {
            val start = System.nanoTime()
            val error = runCatching { runOwnedToolProcess(listOf("/system/bin/sh", "-c", body),
                emptyMap(), 1L, 4096L) }.exceptionOrNull()
            assertEquals("TOOL_PROCESS_TIMEOUT", error?.message)
            assertTrue("Process or inherited pipes exceeded runtime plus retirement deadline",
                (System.nanoTime() - start) / 1_000_000L < 6_000L)
        }
        assertTrue(Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "stemwise-tool-output" })
    }

    @Test
    fun anUnterminatedOutputLineCannotExceedTheByteBudget() = runBlocking {
        val error = runCatching { runOwnedToolProcess(listOf("/system/bin/head", "-c", "65536", "/dev/zero"),
            emptyMap(), 2L, 32768L) }.exceptionOrNull()
        assertEquals("YT_DLP_METADATA_OUTPUT_LIMIT", error?.message)
        assertTrue(Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "stemwise-tool-output" })
    }
}

/** Separate ART processes exercise the production Kotlin runtime and JNI, without killing instrumentation. */
object OwnedToolCrashProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0])
        if (args[1] == "close-lease-and-fork") {
            android.os.ParcelFileDescriptor.adoptFd(args[2].toInt()).close()
            ProcessBuilder("/system/bin/sleep", "30")
                .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null")).start()
            return
        }
        bundledToolRuntimeMutex.bind(root)
        try {
            when (args[1]) {
                "check" -> bundledToolRuntimeMutex.requireHealthy()
                "cleanup" -> app.stemwise.data.cleanupExactPrivateChildren(File(root, "scratch"), "TEST_CLEANUP_FAILED") { it.name == "input.pcm" }
                "failed-publication" -> {
                    check(File(root, ".stemwise-tool-owner-pending").mkdir())
                    val result = runBlocking {
                        runOwnedToolProcess(listOf("/system/bin/sh", "-c", "touch must-not-execute"),
                            emptyMap(), 2L, 4096L, cwd = root)
                    }
                    check(result.exitCode == 126 && !File(root, "must-not-execute").exists())
                }
                else -> bundledToolRuntimeMutex.withProcessLease { lease, directory ->
                    if (args[1] != "halt-before-spawn") {
                        val command = if (args[1] == "orphan-without-lease") arrayOf(
                            "/system/bin/app_process", "/system/bin", OwnedToolCrashProbe::class.java.name,
                            root.path, "close-lease-and-fork", lease.toString())
                        else arrayOf("/system/bin/sh", "-c", "sleep 30")
                        val fields = checkNotNull(OwnedToolNative.spawn(command,
                            System.getenv().map { (key, value) -> "$key=$value" }.toTypedArray(), "", lease, directory))
                        if (args[1] == "orphan-without-lease") {
                            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3L)
                            while (OwnedToolNative.poll(fields[0]) == -1 && System.nanoTime() < deadline) Thread.sleep(5L)
                            check(OwnedToolNative.poll(fields[0]) == 0) { "DESCENDANT_FIXTURE_FAILED" }
                        }
                        println("TOOL_PID=${fields[0]}")
                        System.out.flush()
                    }
                    Runtime.getRuntime().halt(17)
                }
            }
        } catch (_: app.stemwise.playback.engine.WorkerRetirementException) {
            kotlin.system.exitProcess(23)
        }
    }
}
