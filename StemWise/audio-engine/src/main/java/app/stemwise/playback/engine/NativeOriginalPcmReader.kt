package app.stemwise.playback.engine

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

suspend fun consumeNativeOriginal48kPcm(
    context: Context, original: File, stream: OutputStream, expectedFrames: Long,
    closeStream: Boolean = false,
    packetReaderFactory: AudioPacketReaderFactory
) {
    require(expectedFrames in 1..app.stemwise.MAX_ENRICHMENT_PCM_FRAMES_48K)
    val workContext = currentCoroutineContext()
    val bank = try { NativeSongLaneIngressBank() } catch (error: Throwable) {
        if (closeStream) stream.close()
        throw error
    }
    val pool = try { NativeDecoderWorkerPool.dedicated("OriginalPreparation") } catch (error: Throwable) {
        bank.close()
        if (closeStream) stream.close()
        throw error
    }
    var adapter: DecoderNativeLaneAdapter? = null
    var decoder: MediaCodecTrackDecoder? = null
    try {
        val laneAdapter = DecoderNativeLaneAdapter.forNativeBank("remaining-original", "original", bank, 0, 1L)
        adapter = laneAdapter
        val terminal = CompletableDeferred<Unit>()
        val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.nativeOrder())
        val bytes = ByteArray(buffer.capacity())
        val metadata = LongArray(3)
        var writtenFrames = 0L
        fun drain(endOfStream: Boolean) {
            while (true) {
                workContext.ensureActive()
                buffer.clear()
                val count = bank.pullOriginal48kForPreparation(1L, buffer, metadata)
                if (count > 0) {
                    if (metadata[0] != writtenFrames || count > expectedFrames - writtenFrames)
                        throw IllegalStateException("SOURCE_TIMELINE_MISMATCH", IllegalStateException(
                            "Original PCM start=${metadata[0]}, count=$count, written=$writtenFrames, expected=$expectedFrames, status=${metadata[1]}"))
                    buffer.position(0)
                    buffer.get(bytes, 0, count * 8)
                    stream.write(bytes, 0, count * 8)
                    writtenFrames += count
                }
                when (metadata[1]) {
                    0L, 4L -> check(count > 0 || metadata[2] > 0L) { "ORIGINAL_PCM_DRAIN_STALLED" }
                    1L -> { check(!endOfStream) { "ORIGINAL_PCM_EOS_MISSING" }; return }
                    2L -> {
                        if (!endOfStream || writtenFrames != expectedFrames) throw IllegalStateException(
                            "SOURCE_TIMELINE_MISMATCH", IllegalStateException(
                                "Original PCM EOS=$endOfStream, written=$writtenFrames, expected=$expectedFrames, decoder=${decoder?.diagnostics()}"))
                        return
                    }
                    else -> error("ORIGINAL_PCM_RENDER_FAILED: status=${metadata[1]}")
                }
            }
        }
        val openedDecoder = MediaCodecTrackDecoder(context, "remaining-original", "original",
            NativeMediaSource.LocalFile(original), NativePcmSink { block ->
                try {
                    workContext.ensureActive()
                    val accepted = laneAdapter.offer(block)
                    check(laneAdapter.diagnostics().failure == null) { "ORIGINAL_PCM_DECODE_FAILED" }
                    drain(endOfStream = false)
                    accepted
                } catch (error: Throwable) {
                    terminal.completeExceptionally(error)
                    throw error
                }
            }, listener = object : NativeMediaCodecDecoderListener by laneAdapter {
                override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) {
                    try {
                        laneAdapter.onEndOfStream(seekGeneration, finalPresentationTimeUs)
                        check(laneAdapter.diagnostics().state == DecoderNativeLaneAdapterState.ENDED) {
                            "ORIGINAL_PCM_EOS_REJECTED"
                        }
                        drain(endOfStream = true)
                        terminal.complete(Unit)
                    } catch (error: Throwable) {
                        terminal.completeExceptionally(error)
                    }
                }
                override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                    laneAdapter.onError(failure, diagnostics)
                    terminal.completeExceptionally(IllegalStateException(
                        "ORIGINAL_PCM_DECODE_FAILED: ${failure.code}: ${failure.message}", failure))
                }
            }, workerPool = pool,
                packetReaderFactory = packetReaderFactory)
        decoder = openedDecoder
        check(openedDecoder.start()) { "ORIGINAL_PCM_DECODE_FAILED" }
        terminal.await()
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            val retirement = NativePreparationRetirement(
                stop = { decoder?.close() },
                isJoined = { decoder?.awaitClosed(100, TimeUnit.MILLISECONDS) ?: true },
                release = {
                    adapter?.beginCancellation()
                    adapter?.confirmDecoderJoined()
                    try { bank.close() } finally { pool.close() }
                }
            )
            try {
                if (!retirement.await(NATIVE_DECODER_JOIN_WATCHDOG_MS, TimeUnit.MILLISECONDS))
                    throw WorkerRetirementException("ORIGINAL_PCM_CANCELLATION_TIMED_OUT")
            } catch (error: Throwable) {
                val failure = error as? WorkerRetirementException
                    ?: WorkerRetirementException("ORIGINAL_PCM_RETIREMENT_FAILED", error)
                throw failure
            }
            if (closeStream) stream.close()
        }
    }
}
