package app.stemwise.qualification

import android.os.Build
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.Trace
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.playback.engine.NativeAudioDiagnostics
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Opt-in whole-process measurements, including test setup/cleanup; never a release verdict. */
@RequiresApi(29)
class PlaybackPerformanceRunListener : RunListener() {
    private var sequence = 0
    private var section = ""

    override fun testStarted(description: Description) {
        ownedOutputSelected = false
        lastOwnedOutput = null
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.stemwise.development" &&
            InstrumentationRegistry.getArguments().getString("stemwiseDisposableTarget") == "true")
        check(Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) { "Active Perfetto trace required" }
        section = "StemWise.test:${description.methodName}".take(127)
        Trace.beginAsyncSection(section, ++sequence)
        counters()
        environment(1L)
    }

    override fun testFinished(description: Description) {
        try {
            counters()
            environment(2L)
        } finally {
            Trace.endAsyncSection(section, sequence)
        }
    }

    private fun counters() {
        Trace.setCounter("StemWise.cpu_ms", Process.getElapsedCpuTime())
        for (stat in listOf("bytes-allocated", "gc-count", "blocking-gc-count")) {
            val value = checkNotNull(Debug.getRuntimeStat("art.gc.$stat")?.toLongOrNull()) {
                "ART metric unavailable: $stat"
            }
            Trace.setCounter("StemWise.$stat", value)
        }
        Trace.setCounter("StemWise.native_heap_bytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun environment(phase: Long) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        Trace.beginSection("StemWise.audio.environment")
        try {
            Trace.setCounter("StemWise.environment.phase", phase)
            val power = checkNotNull(target.getSystemService(PowerManager::class.java))
            Trace.setCounter("StemWise.environment.thermal_status", power.currentThermalStatus.toLong())
            Trace.setCounter("StemWise.environment.power_save", if (power.isPowerSaveMode) 1L else 0L)
            val battery = checkNotNull(target.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
            check(battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
            Trace.setCounter("StemWise.environment.battery_temperature_deci_c",
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).toLong())
            Trace.setCounter("StemWise.environment.battery_level", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).toLong())
            Trace.setCounter("StemWise.environment.battery_scale", battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1).toLong())
            val owned = lastOwnedOutput
            val output = if (ownedOutputSelected) owned?.output else
                (target.applicationContext as StemwiseApplication).services.audioPlayback.nativeDiagnosticsSnapshotForTest()?.output
            val receivedNanos = if (ownedOutputSelected) owned?.receivedNanos else System.nanoTime()
            val available = output?.outputConfigurationObserved == true && output.physicalRunSequence > 0L
            Trace.setCounter("StemWise.output.captured", if (available) 1L else 0L)
            if (available) {
                val actual = checkNotNull(output)
                Trace.setCounter("StemWise.output.sample_rate_hz", actual.sampleRateHz.toLong())
                Trace.setCounter("StemWise.output.frames_per_burst", actual.framesPerBurst.toLong())
                Trace.setCounter("StemWise.output.buffer_capacity_frames", actual.outputBufferCapacityFrames.toLong())
                Trace.setCounter("StemWise.output.buffer_size_frames", actual.outputBufferSizeFrames.toLong())
                Trace.setCounter("StemWise.output.backend", actual.backend.rawValue.toLong())
                Trace.setCounter("StemWise.output.performance_mode", actual.performanceMode.toLong())
                Trace.setCounter("StemWise.output.xrun_count", actual.xRunCount.toLong())
                Trace.setCounter("StemWise.output.physical_run", actual.physicalRunSequence)
                Trace.setCounter("StemWise.output.observation_kind", if (ownedOutputSelected) 1L else 0L)
                Trace.setCounter("StemWise.output.observation_received_nanos", checkNotNull(receivedNanos))
            }
        } finally {
            Trace.endSection()
        }
    }

    companion object {
        private data class OwnedOutput(val output: NativeAudioDiagnostics?, val receivedNanos: Long)
        @Volatile private var ownedOutputSelected = false
        @Volatile private var lastOwnedOutput: OwnedOutput? = null

        /** A fixture owns its tracks; a missing final observation cannot fall back to the app singleton. */
        internal fun beginOwnedOutputObservation() { lastOwnedOutput = null; ownedOutputSelected = true }

        /** Called after feeder retirement/final sampling and before native owner deletion. */
        internal fun recordOwnedOutputObservation(result: Result<NativeAudioDiagnostics>) {
            check(ownedOutputSelected)
            lastOwnedOutput = OwnedOutput(result.getOrNull(), System.nanoTime())
        }
    }
}
