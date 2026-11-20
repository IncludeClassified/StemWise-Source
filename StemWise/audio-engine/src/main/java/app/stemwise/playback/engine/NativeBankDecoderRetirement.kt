package app.stemwise.playback.engine

/** Actor-owned decoder drain, entered only after the exact bank unassignment is acknowledged. */
internal class NativeBankDecoderRetirement(private val watchdogMs: Long) {
    init { require(watchdogMs > 0L) }

    private sealed interface Phase {
        data object Ready : Phase
        data class Joining(val deadlineMs: Long) : Phase
        data object Retired : Phase
        data class TimedOut(val result: Result.TimedOut) : Phase
    }
    sealed interface Result {
        data object Pending : Result
        data object Retired : Result
        data class TimedOut(val retained: List<NativeEngineDecoder>) : Result
    }
    private var phase: Phase = Phase.Ready

    fun poll(nowMs: Long, decoders: Collection<NativeEngineDecoder>, release: (NativeEngineDecoder) -> Unit): Result {
        when (val current = phase) {
            Phase.Ready -> {
                phase = Phase.Joining(if (nowMs > Long.MAX_VALUE - watchdogMs) Long.MAX_VALUE else nowMs + watchdogMs)
                decoders.forEach(NativeEngineDecoder::requestCancel)
            }
            Phase.Retired -> return Result.Retired
            is Phase.TimedOut -> return current.result
            is Phase.Joining -> Unit
        }
        val (joined, retained) = decoders.partition { it.awaitJoin(0L) == NativeEngineDecoderJoinStatus.JOINED }
        joined.forEach { decoder ->
            check(decoder.safeToReleaseLane())
            release(decoder)
        }
        if (retained.isEmpty()) {
            phase = Phase.Retired
            return Result.Retired
        }
        if (nowMs >= (phase as Phase.Joining).deadlineMs) {
            val result = Result.TimedOut(retained)
            phase = Phase.TimedOut(result)
            return result
        }
        return Result.Pending
    }
}

/** Actor-owned bank retirement: exact native ACK, preparation retirement, then joined lanes.
 * Completed plans leave this owner before controller promotion; timeouts retain their resources. */
internal class NativeBankRetirementOwner(
    private val environment: NativeEngineEnvironment,
    private val watchdogMs: Long
) {
    private data class Plan(val record: BankRecord, val unassignmentRevision: Long,
        val retirement: NativeBankDecoderRetirement)
    private val plans = linkedMapOf<NativeRuntimeBank, Plan>()
    val size: Int get() = plans.size
    val records: List<BankRecord> get() = plans.values.map(Plan::record)
    operator fun contains(bank: NativeRuntimeBank): Boolean = bank in plans
    fun isEmpty(): Boolean = plans.isEmpty()
    fun isNotEmpty(): Boolean = plans.isNotEmpty()
    fun unassignmentRevision(bank: NativeRuntimeBank): Long? = plans[bank]?.unassignmentRevision

    fun begin(record: BankRecord, exactUnassignmentRevision: Long) {
        check(record.bank !in plans)
        plans[record.bank] = Plan(record, exactUnassignmentRevision, NativeBankDecoderRetirement(watchdogMs))
    }

    fun poll(nowMs: Long, preparationRetired: (BankRecord) -> Boolean,
        completed: (BankRecord, NativeBankDecoderRetirement.Result) -> Unit) {
        for (plan in plans.values.toList()) {
            val record = plan.record
            if (!environment.hasAppliedUnassignment(record.bank, plan.unassignmentRevision) ||
                !preparationRetired(record)) continue
            val result = plan.retirement.poll(nowMs, record.decoders.values) { decoder ->
                environment.releaseDecoder(record.bank, decoder)
                record.decoders.remove(decoder.laneIndex)
            }
            if (result == NativeBankDecoderRetirement.Result.Retired) plans.remove(record.bank)
            if (result != NativeBankDecoderRetirement.Result.Pending) completed(record, result)
        }
    }

    /** Only the administrative stopped-runtime path may supersede all exact native ACKs. */
    fun clearAfterRuntimeJoined() = plans.clear()
}
