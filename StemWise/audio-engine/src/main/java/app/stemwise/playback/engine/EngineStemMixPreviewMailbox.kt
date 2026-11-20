package app.stemwise.playback.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/** Immutable, gesture-rate authority. No instance is created from MOVE or a display callback. */
class EngineStemMixPreviewAuthority(
    val id: Long,
    val token: EngineSelectionToken,
    val ownership: NativeRuntimeOwnership,
    val packageSignature: String,
    val packageRequestRevision: Long
) {
    init {
        require(id > 0L)
        require(ownership.selectionToken == token.value)
        require(packageSignature.isNotBlank())
        require(packageRequestRevision > 0L)
    }
}

/** One independently fenced pointer lane within a shared source-preview authority. */
data class EngineStemMixPreviewLease(
    val authority: EngineStemMixPreviewAuthority,
    val laneIndex: Int,
    val laneEpoch: Long
) {
    init {
        require(laneIndex in 0 until ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
        require(laneEpoch > 0L)
    }
}

/** Actor-owned reusable destination; [readLatestInto] never returns a mutable mailbox slot. */
internal class EngineStemMixPreviewSnapshot {
    var authority: EngineStemMixPreviewAuthority? = null
        internal set
    var sequence: Long = 0L
        internal set
    var changedMask: Int = 0
        internal set
    val laneEpochs = LongArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
    val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
}

/**
 * Single-producer/single-consumer latest-only ingress for full-player stem-slider previews.
 *
 * DOWN/UP may use ordinary synchronized ownership gates. MOVE publishes only primitive values
 * into one of three preallocated slots. A reader hazard protects its current slot while the
 * producer replaces the published slot, so the actor can never observe a torn lane tuple.
 */
internal class EngineStemMixPreviewMailbox {
    private class Slot {
        var authority: EngineStemMixPreviewAuthority? = null
        var sequence = 0L
        var changedMask = 0
        val laneEpochs = LongArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
    }

    private val slots = Array(SLOT_COUNT) { Slot() }
    private val currentAuthority = AtomicReference<EngineStemMixPreviewAuthority?>(null)
    private val activeLaneEpochs = AtomicLongArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
    private val nextAuthorityId = AtomicLong(0L)
    private val nextLaneEpoch = AtomicLong(0L)
    private val nextPublicationSequence = AtomicLong(0L)
    private val publishedSlot = AtomicInteger(NO_SLOT)
    private val readerSlot = AtomicInteger(NO_SLOT)
    private var producerCursor = 0

    @Synchronized
    fun begin(
        token: EngineSelectionToken,
        ownership: NativeRuntimeOwnership,
        packageSignature: String,
        packageRequestRevision: Long,
        laneIndex: Int
    ): EngineStemMixPreviewLease? {
        if (laneIndex !in 0 until ENGINE_STEM_MIX_PREVIEW_LANE_COUNT ||
            ownership.selectionToken != token.value || packageSignature.isBlank() ||
            packageRequestRevision <= 0L
        ) return null
        var authority = currentAuthority.get()
        if (authority == null || authority.token != token || authority.ownership != ownership ||
            authority.packageSignature != packageSignature ||
            authority.packageRequestRevision != packageRequestRevision
        ) {
            clearActiveLanes()
            authority = EngineStemMixPreviewAuthority(
                id = allocate(nextAuthorityId, "stem-mix preview authority"),
                token = token,
                ownership = ownership,
                packageSignature = packageSignature,
                packageRequestRevision = packageRequestRevision
            )
            currentAuthority.set(authority)
        }
        val laneEpoch = allocate(nextLaneEpoch, "stem-mix preview lane epoch")
        activeLaneEpochs.set(laneIndex, laneEpoch)
        return EngineStemMixPreviewLease(authority, laneIndex, laneEpoch)
    }

    /** MOVE/display-boundary path: fixed copies plus release/acquire atomics only. */
    fun offer(
        lease: EngineStemMixPreviewLease,
        changedMask: Int,
        gains: FloatArray
    ): Boolean {
        val authority = currentAuthority.get()
        if (authority !== lease.authority ||
            activeLaneEpochs.get(lease.laneIndex) != lease.laneEpoch ||
            gains.size < ENGINE_STEM_MIX_PREVIEW_LANE_COUNT
        ) return false
        val activeMask = activeMask(authority)
        val acceptedMask = changedMask and activeMask and ENGINE_STEM_MIX_PREVIEW_ALL_LANES_MASK
        if (acceptedMask == 0) return false
        var lane = 0
        while (lane < ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) {
            if (acceptedMask and (1 shl lane) != 0 && !gains[lane].isFinite()) return false
            lane++
        }

        val slotIndex = acquireProducerSlot()
        val slot = slots[slotIndex]
        val previousIndex = publishedSlot.get()
        val previous = if (previousIndex == NO_SLOT) null else slots[previousIndex]
        slot.authority = authority
        slot.changedMask = acceptedMask
        lane = 0
        while (lane < ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) {
            val bit = 1 shl lane
            slot.laneEpochs[lane] = activeLaneEpochs.get(lane)
            if (acceptedMask and bit != 0) {
                slot.gains[lane] = gains[lane].coerceIn(0f, 1f)
            } else if (previous != null && previous.authority === authority &&
                previous.changedMask and bit != 0 &&
                slot.laneEpochs[lane] > 0L &&
                previous.laneEpochs[lane] == slot.laneEpochs[lane]
            ) {
                // A later MOVE on another lane must retain this lease's latest endpoint.
                slot.changedMask = slot.changedMask or bit
                slot.gains[lane] = previous.gains[lane]
            }
            lane++
        }
        slot.sequence = allocate(nextPublicationSequence, "stem-mix preview publication")
        publishedSlot.set(slotIndex)
        producerCursor = slotIndex
        return true
    }

    @Synchronized
    fun end(lease: EngineStemMixPreviewLease): Boolean {
        if (currentAuthority.get() !== lease.authority) return false
        return activeLaneEpochs.compareAndSet(lease.laneIndex, lease.laneEpoch, 0L)
    }

    @Synchronized
    fun clear() {
        clearActiveLanes()
        currentAuthority.set(null)
    }

    fun authority(): EngineStemMixPreviewAuthority? = currentAuthority.get()

    fun activeLaneEpoch(authority: EngineStemMixPreviewAuthority, laneIndex: Int): Long =
        if (currentAuthority.get() === authority &&
            laneIndex in 0 until ENGINE_STEM_MIX_PREVIEW_LANE_COUNT
        ) activeLaneEpochs.get(laneIndex) else 0L

    fun activeMask(authority: EngineStemMixPreviewAuthority? = currentAuthority.get()): Int {
        if (authority == null || currentAuthority.get() !== authority) return 0
        var mask = 0
        var lane = 0
        while (lane < ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) {
            if (activeLaneEpochs.get(lane) > 0L) mask = mask or (1 shl lane)
            lane++
        }
        return mask
    }

    /** Copies only a publication newer than [target.sequence]. */
    fun readLatestInto(target: EngineStemMixPreviewSnapshot): Boolean {
        while (true) {
            val slotIndex = publishedSlot.get()
            if (slotIndex == NO_SLOT) return false
            readerSlot.set(slotIndex)
            if (publishedSlot.get() != slotIndex) {
                readerSlot.compareAndSet(slotIndex, NO_SLOT)
                continue
            }
            val slot = slots[slotIndex]
            if (slot.sequence <= target.sequence) {
                readerSlot.compareAndSet(slotIndex, NO_SLOT)
                return false
            }
            target.authority = slot.authority
            target.sequence = slot.sequence
            target.changedMask = slot.changedMask
            var lane = 0
            while (lane < ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) {
                target.laneEpochs[lane] = slot.laneEpochs[lane]
                target.gains[lane] = slot.gains[lane]
                lane++
            }
            readerSlot.compareAndSet(slotIndex, NO_SLOT)
            return true
        }
    }

    private fun acquireProducerSlot(): Int {
        val published = publishedSlot.get()
        val reading = readerSlot.get()
        var offset = 1
        while (offset <= SLOT_COUNT) {
            val candidate = (producerCursor + offset) % SLOT_COUNT
            if (candidate != published && candidate != reading) return candidate
            offset++
        }
        error("Three-slot stem-mix mailbox lost its free producer slot")
    }

    private fun clearActiveLanes() {
        var lane = 0
        while (lane < ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) {
            activeLaneEpochs.set(lane, 0L)
            lane++
        }
    }

    /** CAS allocation avoids the captured [AtomicLong.updateAndGet] lambda on the MOVE path. */
    private fun allocate(counter: AtomicLong, label: String): Long {
        while (true) {
            val current = counter.get()
            check(current < Long.MAX_VALUE) { "$label space exhausted" }
            val next = current + 1L
            if (counter.compareAndSet(current, next)) return next
        }
    }

    private companion object {
        const val SLOT_COUNT = 3
        const val NO_SLOT = -1
    }
}

const val ENGINE_STEM_MIX_PREVIEW_LANE_COUNT = app.stemwise.model.StemRoleContract.maximumStemCount
internal const val ENGINE_STEM_MIX_PREVIEW_ALL_LANES_MASK =
    (1 shl ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) - 1
