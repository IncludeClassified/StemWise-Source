package app.stemwise.playback.engine

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import app.stemwise.integration.runCancellableBlockingOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact advertised endpoint of an imported Remaining source; decoding still validates its PCM. */
internal suspend fun inspectNativeSourceFrameCount(
    context: Context,
    source: NativeMediaSource.Float32Audio
): Long = withContext(Dispatchers.IO) {
    val workContext = currentCoroutineContext()
    val signal = CancellationSignal()
    runCancellableBlockingOperation(cancelExact = signal::cancel) {
        source.withDescriptor(context, signal) { descriptor ->
            val stream = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
            stream.use { input ->
                val offset = descriptor.startOffset
                val length = descriptor.declaredLength.takeIf { it >= 0L } ?: (input.channel.size() - offset)
                val facts = inspectWavPackSlice(input.channel, offset, length) {
                    workContext.ensureActive()
                    signal.throwIfCanceled()
                }
                check(facts.sampleRate == 48_000 && facts.channels == 2 && facts.isFloat) {
                    "SOURCE_WINDOW_FORMAT_INVALID"
                }
                workContext.ensureActive()
                facts.audibleFrames
            }
        }
    }
}

