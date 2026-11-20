package app.stemwise.work

import android.app.Instrumentation
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import app.stemwise.StemwiseApplication
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkerInitializationInstrumentationTest {
    @Test
    fun realWorkersSuspendBeforeReadingUninitializedServices() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = RootWorkIdentity("worker-library", "content://provider/tree/worker", 1L)
        for (preparation in listOf(false, true)) {
            val application = uninitializedApplication(target)
            val input = if (preparation) MvsepPreparationWorker.initialRequest(identity).workSpec.input
                else PlaylistSyncWorker.manualRequest(identity.libraryId).workSpec.input
            val parameters = parameters(input, object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo): ListenableFuture<Void> =
                    error("Initialization cannot start foreground work")
            })
            val worker: CoroutineWorker = if (preparation) MvsepPreparationWorker(application, parameters)
                else PlaylistSyncWorker(application, parameters)
            val execution = async(start = CoroutineStart.UNDISPATCHED) { runCatching { worker.doWork() } }
            try {
                assertFalse("Worker accessed services before initialization", execution.isCompleted)
                application.workerInitialization.complete()
                val result = withTimeout(2_000L) { execution.await() }
                assertTrue("Worker did not resume into its real service owner: $result",
                    result.exceptionOrNull() is UninitializedPropertyAccessException)
            } finally {
                execution.cancel()
                withTimeout(2_000L) { execution.join() }
            }
        }
    }

    @Test
    fun realPreparationForegroundBoundaryRetriesOnlyDeniedStarts() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        for (failure in listOf(null, ForegroundServiceStartNotAllowedException("background"),
            IllegalStateException("unrelated failure"), CancellationException("cancelled"))) {
            var calls = 0
            val worker = MvsepPreparationWorker(uninitializedApplication(target), parameters(Data.EMPTY, object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo): ListenableFuture<Void> {
                    calls++
                    return SettableFuture.create<Void>().apply {
                        when (failure) {
                            null -> set(null)
                            is CancellationException -> cancel(false)
                            else -> setException(failure)
                        }
                    }
                }
            }))
            val actual = runCatching { withTimeout(2_000L) { worker.enterForegroundOrRetry() } }
            assertEquals(1, calls)
            when (failure) {
                null -> assertNull(actual.getOrThrow())
                is ForegroundServiceStartNotAllowedException -> assertEquals(ListenableWorker.Result.retry(), actual.getOrThrow())
                is CancellationException -> assertTrue(actual.exceptionOrNull() is CancellationException &&
                    actual.exceptionOrNull() !is kotlinx.coroutines.TimeoutCancellationException)
                else -> assertSame(failure, actual.exceptionOrNull())
            }
        }
    }

    private fun uninitializedApplication(target: Context): StemwiseApplication {
        lateinit var application: StemwiseApplication
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = application
            override fun startService(service: Intent): ComponentName? = null
            override fun stopService(service: Intent): Boolean = false
        }
        application = Instrumentation.newApplication(StemwiseApplication::class.java, context) as StemwiseApplication
        return application
    }

    private fun parameters(input: Data, foreground: ForegroundUpdater): WorkerParameters {
        val executor = Executor { it.run() }
        return WorkerParameters(UUID.randomUUID(), input, emptyList(),
            WorkerParameters.RuntimeExtras(), 0, 0, executor, EmptyCoroutineContext,
            WorkManagerTaskExecutor(executor), object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker? = null
            }, object : ProgressUpdater {
                override fun updateProgress(context: Context, id: UUID, data: Data): ListenableFuture<Void> =
                    error("Initialization cannot publish progress")
            }, foreground)
    }
}
