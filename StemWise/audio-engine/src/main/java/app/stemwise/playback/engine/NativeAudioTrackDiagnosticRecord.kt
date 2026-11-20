package app.stemwise.playback.engine

import java.util.concurrent.atomic.AtomicBoolean

/** Optional single-writer trace. PCM service only appends primitive fields; readers format off-thread. */
internal class NativeAudioTrackDiagnosticRecord(
    capacity: Int,
    private val clockNanos: () -> Long = System::nanoTime
) {
    enum class Operation { PREPARE, WRITE, ACCEPT, PLATFORM_OBSERVE, NATIVE_PUBLICATION, PLAY, PAUSE, PARK, CONSUMER, DISCARD, RESET_COMPLETE, SOURCE_TRANSITION, TRANSPORT_PREPARE, TRANSPORT_ACCEPT }
    /** SOURCE_PREPARED identifies the Core path, not proof that every frame was valid source. */
    enum class PcmKind { NONE, SOURCE_PREPARED, LOGICAL_PAUSE_SILENCE, SOURCE_TRANSITION_SILENCE }
    enum class Phase { ENTER, EXIT, INSTANT }
    enum class ParkReason { NONE, CURRENT, ZERO_PROGRESS, IDLE, EXHAUSTED }

    data class Event(
        val timeNanos: Long,
        val operation: Operation,
        val phase: Phase,
        val parkReason: ParkReason,
        val instance: Long,
        val resetSerial: Long,
        val requestedResetSerial: Long,
        /** High-water producer generation, not inferred ownership of the pending PCM. -1 is unavailable. */
        val producerGeneration: Long,
        val blockSequence: Long,
        val pendingFrames: Long,
        val submittedFrames: Long,
        val requestedFrames: Long,
        val resultFrames: Long,
        val rawResult: Long,
        val playing: Boolean,
        val playingRequested: Boolean,
        val consumerActive: Boolean,
        val headFrames: Long,
        /** Conservative acquisition lower bound: platform-observation entry. */
        val headSampleNanos: Long,
        /** Upper bound on the observed head's age, not a claim of exact hardware occupancy. */
        val headAgeNanos: Long,
        val headValid: Boolean,
        val headResetSerial: Long,
        val timestampFrame: Long,
        val timestampNanos: Long,
        val rawUnderruns: Long,
        /** Discovery upper bound: platform-observation return. */
        val rawSampleNanos: Long,
        val rawAvailable: Boolean,
        val pcmKind: PcmKind,
        val transportPendingFrames: Long
    )

    data class Snapshot(
        val events: List<Event>,
        val overflowCount: Long,
        /** False while a feeder is live or any event was lost. This does not assert audio health. */
        val complete: Boolean
    )

    private val capacity = capacity.also { require(it > 0) }
    private val fields = LongArray(Math.multiplyExact(capacity, FIELD_COUNT))
    private val writing = AtomicBoolean(false)
    @Volatile private var retired = false
    @Volatile private var published = 0
    @Volatile private var overflow = 0L

    internal fun begin() {
        check(writing.compareAndSet(false, true)) { "A diagnostic record already has a feeder" }
        retired = false
    }

    internal fun finish() { retired = true; writing.set(false) }
    internal fun now(): Long = clockNanos()

    internal fun append(
        timeNanos: Long, operation: Operation, phase: Phase, parkReason: ParkReason,
        instance: Long, resetSerial: Long, requestedResetSerial: Long, producerGeneration: Long,
        blockSequence: Long, pendingFrames: Long, submittedFrames: Long,
        requestedFrames: Long, resultFrames: Long, rawResult: Long,
        playing: Boolean, playingRequested: Boolean, consumerActive: Boolean,
        headFrames: Long, headSampleNanos: Long, headAgeNanos: Long, headValid: Boolean,
        headResetSerial: Long, timestampFrame: Long, timestampNanos: Long,
        rawUnderruns: Long, rawSampleNanos: Long, rawAvailable: Boolean,
        pcmKind: PcmKind, transportPendingFrames: Long
    ) {
        val index = published
        if (index >= capacity) {
            if (overflow < Long.MAX_VALUE) ++overflow
            return
        }
        var field = index * FIELD_COUNT
        fields[field++] = timeNanos
        fields[field++] = operation.ordinal.toLong()
        fields[field++] = phase.ordinal.toLong()
        fields[field++] = parkReason.ordinal.toLong()
        fields[field++] = instance
        fields[field++] = resetSerial
        fields[field++] = requestedResetSerial
        fields[field++] = producerGeneration
        fields[field++] = blockSequence
        fields[field++] = pendingFrames
        fields[field++] = submittedFrames
        fields[field++] = requestedFrames
        fields[field++] = resultFrames
        fields[field++] = rawResult
        fields[field++] = if (playing) 1 else 0
        fields[field++] = if (playingRequested) 1 else 0
        fields[field++] = if (consumerActive) 1 else 0
        fields[field++] = headFrames
        fields[field++] = headSampleNanos
        fields[field++] = headAgeNanos
        fields[field++] = if (headValid) 1 else 0
        fields[field++] = headResetSerial
        fields[field++] = timestampFrame
        fields[field++] = timestampNanos
        fields[field++] = rawUnderruns
        fields[field++] = rawSampleNanos
        fields[field++] = if (rawAvailable) 1 else 0
        fields[field++] = pcmKind.ordinal.toLong()
        fields[field] = transportPendingFrames
        // Published entries never change. The volatile prefix is safe even for an incomplete live read.
        published = index + 1
    }

    fun snapshot(): Snapshot {
        val liveAtStart = writing.get()
        val count = published
        val events = List(count) { index ->
            var field = index * FIELD_COUNT
            Event(fields[field++], Operation.entries[fields[field++].toInt()],
                Phase.entries[fields[field++].toInt()], ParkReason.entries[fields[field++].toInt()],
                fields[field++], fields[field++], fields[field++], fields[field++], fields[field++],
                fields[field++], fields[field++], fields[field++], fields[field++], fields[field++],
                fields[field++] != 0L, fields[field++] != 0L, fields[field++] != 0L,
                fields[field++], fields[field++], fields[field++], fields[field++] != 0L,
                fields[field++], fields[field++], fields[field++], fields[field++], fields[field++], fields[field++] != 0L,
                PcmKind.entries[fields[field++].toInt()], fields[field])
        }
        val lost = overflow
        return Snapshot(events, lost, !liveAtStart && !writing.get() && retired &&
            count == published && lost == 0L)
    }

    private companion object { const val FIELD_COUNT = 29 }
}
