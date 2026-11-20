package app.stemwise.playback.engine

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

internal class NativeSourceWindowReadGate {
    private val gate = Mutex()
    private var retirement: NativePreparationRetirement? = null

    fun tryBegin(): Boolean {
        if (!gate.tryLock()) return false
        try {
            if (retirement?.await(0, TimeUnit.MILLISECONDS) == false) { gate.unlock(); return false }
            retirement = null
            return true
        } catch (failure: Throwable) {
            retirement = null
            gate.unlock()
            throw IllegalStateException("SOURCE_WINDOW_RETIREMENT_FAILED", failure)
        }
    }
    fun retain(owner: NativePreparationRetirement) { retirement = owner }
    fun end() = gate.unlock()
}
private val sourceWindowGate = NativeSourceWindowReadGate()

/** Reads an independent pre-DSP source window without seeking any playback-owned decoder. */
suspend fun readNativeSourcePcmWindow(
    context: Context,
    source: NativeMediaSource,
    startFrame48k: Long,
    frameCount: Int,
    packetReaderFactory: AudioPacketReaderFactory,
    readCached: suspend () -> SourcePcmWindowRead = { SourcePcmWindowRead.Unsupported }
): FloatArray = withContext(Dispatchers.IO) {
    require(startFrame48k >= 0L && frameCount in 1..480_000)
    val endFrame48k = Math.addExact(startFrame48k, frameCount.toLong())
    val workContext = currentCoroutineContext()
    workContext.ensureActive()
    val cached = when (val read = readCached()) {
        is SourcePcmWindowRead.Ready -> read.samples.also {
            check(it.size % 2 == 0 && it.size / 2 <= frameCount && it.all(Float::isFinite)) {
                "SOURCE_WINDOW_CACHE_RECEIPT_INVALID"
            }
        }
        SourcePcmWindowRead.Delayed, SourcePcmWindowRead.Unsupported -> null
        SourcePcmWindowRead.Stale -> throw CancellationException("SOURCE_WINDOW_STALE")
        is SourcePcmWindowRead.Corrupt -> throw IllegalStateException(read.diagnostic)
    }
    workContext.ensureActive()
    val covered = (cached?.size ?: 0) / 2
    if (covered == frameCount) return@withContext requireNotNull(cached)
    val decodeFirst = startFrame48k + covered
    // Whole seconds preserve SRC phase; the preceding second warms its finite filter.
    val decodeStartSeconds = (decodeFirst / 48_000L - 1L).coerceAtLeast(0L)
    check(sourceWindowGate.tryBegin()) { "SOURCE_WINDOW_RETIRING" }
    try {
        val decoded = readSourceWindowOwned(context, source, decodeFirst, frameCount - covered,
            endFrame48k, decodeStartSeconds, packetReaderFactory)
        if (cached == null) decoded else FloatArray(frameCount * 2).also {
            cached.copyInto(it)
            decoded.copyInto(it, cached.size)
        }
    } finally { sourceWindowGate.end() }
}

private suspend fun readSourceWindowOwned(
    context: Context, source: NativeMediaSource, startFrame48k: Long, frameCount: Int,
    endFrame48k: Long, decodeStartSeconds: Long, packetReaderFactory: AudioPacketReaderFactory
): FloatArray {
    val workContext = currentCoroutineContext()
    val bank = NativeSongLaneIngressBank()
    val pool = try { NativeDecoderWorkerPool.dedicated("SourceWindow") } catch (error: Throwable) {
        bank.close()
        throw error
    }
    var adapter: DecoderNativeLaneAdapter? = null
    var decoder: MediaCodecTrackDecoder? = null
    return try {
        val result = FloatArray(Math.multiplyExact(frameCount, 2))
        val laneAdapter = DecoderNativeLaneAdapter.forNativeBank("source-window", "original", bank, 0, 1L)
        adapter = laneAdapter
        val terminal = CompletableDeferred<Unit>()
        val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.nativeOrder())
        val metadata = LongArray(3)
        var nextOutputFrame48k = decodeStartSeconds * 48_000L
        var copiedFrames = 0
        fun drain(endOfStream: Boolean) {
            while (!terminal.isCompleted) {
                workContext.ensureActive()
                buffer.clear()
                val count = bank.pullOriginal48kForPreparation(1L, buffer, metadata)
                if (count > 0) {
                    check(metadata[0] == nextOutputFrame48k) { "SOURCE_WINDOW_TIMELINE_MISMATCH" }
                    val outputEnd = Math.addExact(metadata[0], count.toLong())
                    val copyStart = maxOf(metadata[0], startFrame48k)
                    val copyEnd = minOf(outputEnd, endFrame48k)
                    if (copyEnd > copyStart) {
                        check(copyStart == startFrame48k + copiedFrames) { "SOURCE_WINDOW_TIMELINE_MISMATCH" }
                        val samples = buffer.asFloatBuffer()
                        samples.position(Math.multiplyExact((copyStart - metadata[0]).toInt(), 2))
                        val copyFrames = (copyEnd - copyStart).toInt()
                        repeat(copyFrames * 2) { sample ->
                            val value = samples.get()
                            check(value.isFinite()) { "SOURCE_WINDOW_NONFINITE" }
                            result[copiedFrames * 2 + sample] = value
                        }
                        copiedFrames += copyFrames
                    }
                    nextOutputFrame48k = outputEnd
                    if (copiedFrames == frameCount) {
                        terminal.complete(Unit)
                        decoder?.close()
                        return
                    }
                }
                when (metadata[1]) {
                    0L, 4L -> check(count > 0 || metadata[2] > 0L) { "SOURCE_WINDOW_DRAIN_STALLED" }
                    1L -> { check(!endOfStream) { "SOURCE_WINDOW_EOS_MISSING" }; return }
                    2L -> error("SOURCE_WINDOW_PREMATURE_EOS")
                    else -> error("SOURCE_WINDOW_RENDER_FAILED: status=${metadata[1]}")
                }
            }
        }
        val openedDecoder = MediaCodecTrackDecoder(
            context, "source-window", "original", source,
            NativePcmSink { block ->
                if (terminal.isCompleted) NativePcmSinkResult.all(block) else try {
                    workContext.ensureActive()
                    val accepted = laneAdapter.offer(block)
                    check(laneAdapter.diagnostics().failure == null) { "SOURCE_WINDOW_DECODE_FAILED" }
                    drain(endOfStream = false)
                    accepted
                } catch (error: Throwable) {
                    terminal.completeExceptionally(error)
                    throw error
                }
            },
            listener = object : NativeMediaCodecDecoderListener by laneAdapter {
                override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) {
                    if (terminal.isCompleted) return
                    try {
                        laneAdapter.onEndOfStream(seekGeneration, finalPresentationTimeUs)
                        check(laneAdapter.diagnostics().state == DecoderNativeLaneAdapterState.ENDED) {
                            "SOURCE_WINDOW_EOS_REJECTED"
                        }
                        drain(endOfStream = true)
                    } catch (error: Throwable) {
                        terminal.completeExceptionally(error)
                    }
                }

                override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                    laneAdapter.onError(failure, diagnostics)
                    terminal.completeExceptionally(IllegalStateException(
                        "SOURCE_WINDOW_DECODE_FAILED: ${failure.code}: ${failure.message}", failure))
                }
            },
            config = MediaCodecTrackDecoderConfig(initialPositionUs = Math.multiplyExact(decodeStartSeconds, 1_000_000L)),
            workerPool = pool,
            packetReaderFactory = packetReaderFactory
        )
        decoder = openedDecoder
        check(openedDecoder.start()) { "SOURCE_WINDOW_DECODE_FAILED" }
        terminal.await()
        workContext.ensureActive()
        result
    } finally {
        val cancellationPending = !currentCoroutineContext().isActive
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
            sourceWindowGate.retain(retirement)
            check(retirement.await(NATIVE_DECODER_JOIN_WATCHDOG_MS, TimeUnit.MILLISECONDS) || cancellationPending) {
                "SOURCE_WINDOW_RETIRING"
            }
        }
    }
}
