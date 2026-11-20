package app.stemwise.ui.player.surface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.os.Process
import app.stemwise.artwork.ArtworkDiskCacheIdentity
import app.stemwise.artwork.artworkDiskCacheIdentity
import app.stemwise.artwork.artworkMemoryCacheKey
import app.stemwise.artwork.normalizeArtworkPhysicalSource
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.request.SuccessResult
import coil.memory.MemoryCache
import coil.size.Precision
import coil.size.Scale
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One route-independent cover decode target; mini/full and detail morphs only scale this bitmap. */
internal fun playerSurfaceArtworkDecodeSizePx(surfaceWidthPx: Int, density: Float): Int =
    maxOf(
        48,
        (surfaceWidthPx.coerceAtLeast(0) - 24f * density.coerceAtLeast(.1f)).roundToInt()
    )

/**
 * One exact artwork request for a native player slot.
 *
 * [owner], [source], and the decode dimensions all participate in delivery qualification. A
 * result can therefore never be mistaken for another generation, revision, source, or resize of
 * the same slot.
 */
internal data class PlayerSurfaceArtworkRequest(
    val owner: PlayerSurfaceOwnerKey,
    val source: String,
    val widthPx: Int,
    val heightPx: Int
) {
    init {
        require(source.isNotBlank())
        require(widthPx > 0)
        require(heightPx > 0)
    }
}

/** A decoded, read-only bitmap paired with the exact slot request that admitted it. */
internal data class PlayerSurfaceArtworkResult(
    val slot: Int,
    val request: PlayerSurfaceArtworkRequest,
    val bitmap: Bitmap
)

/** Exact bridge to the library/Compose thumbnail already retained by the shared Coil loader. */
internal fun playerSurfaceArtworkPreviewMemoryCacheKey(
    request: PlayerSurfaceArtworkRequest
): String = artworkMemoryCacheKey(
    request.owner.songId,
    request.owner.mediaRevision,
    request.source
)

internal fun interface PlayerSurfaceArtworkCallback {
    fun onArtworkReady(result: PlayerSurfaceArtworkResult)
}

/** Exact-request lifecycle used to suppress repeated player-frame artwork bindings. */
internal class PlayerSurfaceArtworkRequestBinding<
    Request : Any,
    Authority : Any,
    Value : Any
>(
    val request: Request,
    initialAuthority: Authority,
    private val isLive: (Value) -> Boolean
) {
    enum class IdenticalRequestState {
        /** The exact request is still pending, failed previously, or lost its resident value. */
        UNDELIVERED,

        /** The exact request already delivered a value that remains live. */
        DELIVERED
    }

    var authority: Authority = initialAuthority
        private set

    private var deliveredValue: Value? = null
    private var revoked = false

    /**
     * Transfer callback authority for an exact repeat without minting a new binding.
     *
     * A non-identical request and a revoked binding return null so the caller must create and
     * qualify a new binding through its normal cache/decode path.
     */
    fun updateAuthorityIfIdentical(
        candidate: Request,
        replacementAuthority: Authority
    ): IdenticalRequestState? {
        if (revoked || candidate != request) return null
        authority = replacementAuthority
        val delivered = deliveredValue
        if (delivered != null && isLive(delivered)) {
            return IdenticalRequestState.DELIVERED
        }
        deliveredValue = null
        return IdenticalRequestState.UNDELIVERED
    }

    /** Record a successful value only while this binding still owns callback authority. */
    fun recordSuccessfulDelivery(value: Value): Boolean {
        if (revoked || !isLive(value)) return false
        deliveredValue = value
        return true
    }

    /** Permanently prevent a cancelled/replaced binding from accepting a late completion. */
    fun revoke() {
        revoked = true
        deliveredValue = null
    }
}

/** Both collections must already be capped and duplicate-free. Ordering does not affect work. */
internal fun <Request : Any> sameNormalizedPredictiveArtworkSet(
    current: Collection<Request>,
    replacement: Collection<Request>
): Boolean = current.size == replacement.size && current.containsAll(replacement)

/**
 * Bounded decode-work state for the already-normalized predictive request set.
 *
 * A successful predictive decode stays satisfied even if the small bitmap LRU later evicts it;
 * repeatedly rebuilding the same player snapshot must not start an impossible five-cover cache
 * loop. A failed decode is deliberately left unsatisfied so a later identical prefetch may retry.
 */
internal class PlayerSurfacePredictiveWorkLedger<Key : Any> {
    private val active = LinkedHashSet<Key>()
    private val satisfied = HashSet<Key>()

    fun replace(keys: Iterable<Key>): Set<Key> {
        val replacement = keys.toCollection(LinkedHashSet())
        if (!sameNormalizedPredictiveArtworkSet(active, replacement)) {
            active.clear()
            active.addAll(replacement)
            satisfied.retainAll(replacement)
        }
        return active.filterNotTo(LinkedHashSet(), satisfied::contains)
    }

    fun recordSuccessful(key: Key) {
        if (key in active) satisfied += key
    }

    fun clear() {
        active.clear()
        satisfied.clear()
    }
}

internal data class PlayerSurfaceUnversionedArtworkOwner(
    val songId: String,
    val generation: Long
)

/** Revisioned physical artwork is cache-shareable; unversioned artwork remains owner-scoped. */
internal fun playerSurfaceUnversionedArtworkOwner(
    owner: PlayerSurfaceOwnerKey
): PlayerSurfaceUnversionedArtworkOwner? = if (owner.mediaRevision.trim().isEmpty()) {
    PlayerSurfaceUnversionedArtworkOwner(owner.songId, owner.generation)
} else {
    null
}

internal fun playerSurfaceArtworkDiskCacheIdentity(
    request: PlayerSurfaceArtworkRequest
): ArtworkDiskCacheIdentity? = artworkDiskCacheIdentity(
    source = request.source,
    mediaRevision = request.owner.mediaRevision,
    songId = request.owner.songId
)

internal enum class PlayerSurfaceArtworkDecodeLane {
    CURRENT,
    OFFSCREEN
}

internal enum class PlayerSurfaceArtworkPriorityAdmission {
    REJECTED_STALE_EPOCH,
    ALREADY_CURRENT,
    APPLIED
}

/** Latest-owner fence for the one artwork candidate allowed to borrow current-cover QoS. */
internal class PlayerSurfaceArtworkPriorityGate<Key : Any> {
    private var epoch = Long.MIN_VALUE
    private var key: Key? = null

    fun admit(candidateEpoch: Long, candidateKey: Key?): PlayerSurfaceArtworkPriorityAdmission {
        if (candidateEpoch < epoch) {
            return PlayerSurfaceArtworkPriorityAdmission.REJECTED_STALE_EPOCH
        }
        if (candidateEpoch == epoch && candidateKey == key) {
            return PlayerSurfaceArtworkPriorityAdmission.ALREADY_CURRENT
        }
        epoch = candidateEpoch
        key = candidateKey
        return PlayerSurfaceArtworkPriorityAdmission.APPLIED
    }

    fun clear() {
        epoch = Long.MIN_VALUE
        key = null
    }
}

/** A newer current owner preempts every different task that still occupies the current lane. */
internal fun <Key : Any> playerSurfaceArtworkTaskIsSupersededCurrent(
    activeLane: PlayerSurfaceArtworkDecodeLane,
    activeKey: Key,
    requestedCurrentKey: Key?
): Boolean = activeLane == PlayerSurfaceArtworkDecodeLane.CURRENT &&
    activeKey != requestedCurrentKey

/** The same physical key is promoted rather than waiting in the hidden-artwork lane. */
internal fun playerSurfaceArtworkTaskNeedsPromotion(
    activeLane: PlayerSurfaceArtworkDecodeLane,
    requestedLane: PlayerSurfaceArtworkDecodeLane
): Boolean = requestedLane == PlayerSurfaceArtworkDecodeLane.CURRENT &&
    activeLane == PlayerSurfaceArtworkDecodeLane.OFFSCREEN

/**
 * One process-lifetime QoS lane for covers that are not currently visible.
 *
 * The View can detach and later reattach, so closing a per-View dispatcher would strand accepted
 * work during reattachment. This daemon is instead created lazily, remains bounded to one thread,
 * and dies with the app process. Current-song artwork never enters this dispatcher.
 */
private val playerSurfaceOffscreenDecodeDispatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Executors.newSingleThreadExecutor { command ->
        Thread(
            {
                // Hidden preloads must yield CPU and memory bandwidth to 120 Hz player controls.
                // Failure to adjust priority cannot be allowed to strand the decode executor.
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                }
                command.run()
            },
            "StemwiseArtBg"
        ).apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}

/**
 * Bounded asynchronous artwork owner for the three native pager slots.
 *
 * The caller owns each slot's placeholder and must install it synchronously when changing a slot
 * request. This class never targets a View and never publishes an on-start/error drawable. It only
 * publishes a successfully decoded [Bitmap], on the main thread, after re-checking the exact live
 * slot binding. Decode work is performed by Coil and is never part of drawing or frame dispatch.
 *
 * The strong LRU holds the current/lower/higher working set plus two predictive entries. Its key
 * intentionally omits song and generation when a non-blank media revision is available: an exact
 * physical source at an exact revision is safe to share across slots and owners. Unversioned
 * requests add song and generation to the key, preventing unsafe reuse across owner changes.
 */
internal class PlayerSurfaceArtworkLoader(
    context: Context,
    private val imageLoader: ImageLoader = context.imageLoader,
    maxCacheEntries: Int = DEFAULT_CACHE_ENTRIES,
    maxCacheBytes: Long = DEFAULT_CACHE_BYTES
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val currentDecodePermit = Semaphore(1)
    private val offscreenDecodePermit = Semaphore(1)
    private val cache = BoundedPlayerSurfaceLru<DecodeKey, Bitmap>(
        maxEntries = maxCacheEntries,
        maxWeight = maxCacheBytes,
        weightOf = ::bitmapAllocationBytes
    )
    private val bindings = arrayOfNulls<SlotBinding>(SLOT_COUNT)
    private val predictive = LinkedHashMap<PlayerSurfaceArtworkRequest, DecodeSpec>(
        MAX_PREDICTIVE_REQUESTS
    )
    private val predictiveWork = PlayerSurfacePredictiveWorkLedger<DecodeKey>()
    private val inFlight = LinkedHashMap<DecodeKey, DecodeTask>()
    private val priorityGate = PlayerSurfaceArtworkPriorityGate<DecodeKey>()

    private var attached = true

    /**
     * Bind [request] to one of [SLOT_LOWER], [SLOT_CURRENT], or [SLOT_HIGHER].
     *
     * Rebinding a slot immediately revokes its old callback. A resident physical bitmap may be
     * delivered synchronously; every callback is nevertheless on the main thread because all
     * public lifecycle methods are main-thread-only.
     */
    fun request(
        slot: Int,
        request: PlayerSurfaceArtworkRequest,
        callback: PlayerSurfaceArtworkCallback
    ) {
        checkMainThread()
        requireSlot(slot)
        if (!attached) return

        val existing = bindings[slot]
        val identicalState = existing?.lifecycle?.updateAuthorityIfIdentical(request, callback)
        if (identicalState == PlayerSurfaceArtworkRequestBinding.IdenticalRequestState.DELIVERED) {
            // The caller still owns the already-installed live bitmap. Updating callback authority
            // above is sufficient; probing the LRU or publishing the same bitmap again only adds
            // work to every player profile/frame update.
            return
        }

        val spec = decodeSpec(request)
        val binding = if (
            identicalState == PlayerSurfaceArtworkRequestBinding.IdenticalRequestState.UNDELIVERED
        ) {
            checkNotNull(existing)
        } else {
            existing?.lifecycle?.revoke()
            SlotBinding(slot, request, spec?.key, callback).also { replacement ->
                bindings[slot] = replacement
                cancelOrphanedDecodes()
            }
        }
        if (slot == SLOT_CURRENT) {
            preemptSupersededCurrentDecodes(spec?.key)
        }
        if (spec == null) return

        // An exact repeated binding must only transfer callback authority while its existing work
        // remains live. In particular, do not probe the LRU on every high-frequency profile bind.
        if (
            identicalState == PlayerSurfaceArtworkRequestBinding.IdenticalRequestState.UNDELIVERED &&
            spec.key in inFlight
        ) {
            return
        }

        cachedBitmap(spec.key)?.let { bitmap ->
            deliver(binding, bitmap)
            return
        }
        if (slot == SLOT_CURRENT) {
            imageLoader.memoryCache?.get(MemoryCache.Key(spec.previewMemoryCacheKey))?.bitmap
                ?.takeIf { !it.isRecycled && it.config != Bitmap.Config.HARDWARE }
                ?.let { deliverPreview(binding, it) }
        }
        ensureDecode(
            spec,
            if (slot == SLOT_CURRENT) {
                PlayerSurfaceArtworkDecodeLane.CURRENT
            } else {
                PlayerSurfaceArtworkDecodeLane.OFFSCREEN
            }
        )
    }

    /**
     * Replace the predictive working set without changing any visible slot.
     *
     * Only two exact requests are retained, so three visible slots plus prediction cannot grow
     * the loader's work set without bound. Duplicate physical requests still share one decode.
     */
    fun prefetch(requests: Iterable<PlayerSurfaceArtworkRequest>) {
        checkMainThread()
        if (!attached) return

        val replacement = LinkedHashMap<PlayerSurfaceArtworkRequest, DecodeSpec>(
            MAX_PREDICTIVE_REQUESTS
        )
        for (request in requests) {
            if (replacement.size == MAX_PREDICTIVE_REQUESTS) break
            if (request in replacement) continue
            decodeSpec(request)?.let { replacement[request] = it }
        }
        if (!sameNormalizedPredictiveArtworkSet(predictive.keys, replacement.keys)) {
            predictive.clear()
            predictive.putAll(replacement)
            cancelOrphanedDecodes()
        }
        val pendingKeys = predictiveWork.replace(predictive.values.map(DecodeSpec::key))
        predictive.values.forEach { spec ->
            if (spec.key !in pendingKeys) return@forEach
            if (cachedBitmap(spec.key) != null) {
                predictiveWork.recordSuccessful(spec.key)
            } else {
                ensureDecode(spec, PlayerSurfaceArtworkDecodeLane.OFFSCREEN)
            }
        }
        touchVisibleEntries()
    }

    /** Promote one already-bound candidate into the existing current lane without displaying it. */
    fun prioritize(epoch: Long, request: PlayerSurfaceArtworkRequest): Boolean {
        checkMainThread()
        if (!attached) return false
        val spec = decodeSpec(request) ?: return false
        if (priorityGate.admit(epoch, spec.key) ==
            PlayerSurfaceArtworkPriorityAdmission.REJECTED_STALE_EPOCH
        ) return false
        preemptSupersededCurrentDecodes(spec.key)
        ensureDecode(spec, PlayerSurfaceArtworkDecodeLane.CURRENT)
        return true
    }

    /** Give current-cover QoS back to the exact visible owner after preview cancellation. */
    fun restoreCurrentPriority(epoch: Long) {
        checkMainThread()
        if (!attached) return
        val binding = bindings[SLOT_CURRENT]
        val key = binding?.decodeKey
        if (priorityGate.admit(epoch, key) ==
            PlayerSurfaceArtworkPriorityAdmission.REJECTED_STALE_EPOCH
        ) return
        preemptSupersededCurrentDecodes(key)
        if (binding != null && key != null) {
            decodeSpec(binding.request)?.let { spec ->
                ensureDecode(spec, PlayerSurfaceArtworkDecodeLane.CURRENT)
            }
        }
    }

    /** Revoke one slot and cancel its decode when no other visible/predictive owner needs it. */
    fun cancelSlot(slot: Int) {
        checkMainThread()
        requireSlot(slot)
        bindings[slot]?.lifecycle?.revoke()
        bindings[slot] = null
        cancelOrphanedDecodes()
    }

    /** Revoke all slot and predictive requests while keeping resident bitmaps reusable. */
    fun cancel() {
        checkMainThread()
        bindings.forEach { it?.lifecycle?.revoke() }
        bindings.fill(null)
        predictive.clear()
        predictiveWork.clear()
        priorityGate.clear()
        cancelAllDecodes()
    }

    /** Allow this loader to be used again after [detach]. */
    fun attach() {
        checkMainThread()
        attached = true
    }

    /**
     * Revoke all callback authority and cancel every pending decode.
     *
     * The small resident LRU is retained for a later attach; it remains byte- and entry-bounded.
     */
    fun detach() {
        checkMainThread()
        attached = false
        bindings.forEach { it?.lifecycle?.revoke() }
        bindings.fill(null)
        predictive.clear()
        predictiveWork.clear()
        priorityGate.clear()
        cancelAllDecodes()
    }

    private fun ensureDecode(spec: DecodeSpec, lane: PlayerSurfaceArtworkDecodeLane) {
        if (cachedBitmap(spec.key) != null) return

        inFlight[spec.key]?.let { active ->
            if (!playerSurfaceArtworkTaskNeedsPromotion(active.lane, lane)) return

            // A prefetched neighbour has become the authoritative current song. Promote it so a
            // hidden/background decode can never delay the visible cover. Exact task identity in
            // completeDecode rejects any completion racing out of the cancelled predecessor.
            inFlight.remove(spec.key)
            active.job.cancel()
        }

        lateinit var task: DecodeTask
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val bitmap = try {
                val permit = if (lane == PlayerSurfaceArtworkDecodeLane.CURRENT) {
                    currentDecodePermit
                } else {
                    offscreenDecodePermit
                }
                permit.withPermit { decode(spec, lane) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            completeDecode(task, bitmap)
        }
        task = DecodeTask(spec, lane, job)
        inFlight[spec.key] = task
        job.start()
    }

    /**
     * Revoke any older key still occupying the urgent lane before admitting a new current cover.
     * If that key is also owned by a side/predictive request, restart it as background work; an
     * old current decode can therefore never queue ahead of the latest song during rapid swipes.
     */
    private fun preemptSupersededCurrentDecodes(requestedCurrentKey: DecodeKey?) {
        val superseded = inFlight.values.filter { task ->
            playerSurfaceArtworkTaskIsSupersededCurrent(
                activeLane = task.lane,
                activeKey = task.spec.key,
                requestedCurrentKey = requestedCurrentKey
            )
        }
        superseded.forEach { task ->
            if (inFlight[task.spec.key] !== task) return@forEach
            inFlight.remove(task.spec.key)
            task.job.cancel()
            if (decodeKeyHasOwner(task.spec.key)) {
                ensureDecode(task.spec, PlayerSurfaceArtworkDecodeLane.OFFSCREEN)
            }
        }
    }

    private fun decodeKeyHasOwner(key: DecodeKey): Boolean =
        bindings.any { binding -> binding?.decodeKey == key } ||
            predictive.values.any { spec -> spec.key == key }

    private suspend fun decode(
        spec: DecodeSpec,
        lane: PlayerSurfaceArtworkDecodeLane
    ): Bitmap? {
        val request = ImageRequest.Builder(applicationContext)
            .data(spec.data)
            .size(spec.key.widthPx, spec.key.heightPx)
            .scale(Scale.FILL)
            .precision(Precision.EXACT)
            .allowHardware(false)
            .crossfade(false)
            // This loader already owns the exact byte/entry-bounded working set. Letting the
            // application-wide Coil memory cache retain the same software bitmaps under 400
            // revision-specific keys duplicates that cache and grows process PSS unboundedly
            // during a complete library traversal.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .memoryCacheKey(spec.coilCacheKey)
            .diskCacheKey(spec.diskCacheIdentity.canonicalKey)
            .tag(spec.diskCacheIdentity)
            .apply {
                if (lane == PlayerSurfaceArtworkDecodeLane.OFFSCREEN) {
                    // Coil confines BitmapFactory's SourceResult decode to this dispatcher;
                    // fetching, cache keys, dimensions, pixels, and current-cover QoS remain
                    // unchanged.
                    decoderDispatcher(playerSurfaceOffscreenDecodeDispatcher)
                }
            }
            .build()
        val result = imageLoader.execute(request)
        val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        return bitmap?.takeUnless(Bitmap::isRecycled)
    }

    private fun completeDecode(task: DecodeTask, bitmap: Bitmap?) {
        if (inFlight[task.spec.key] !== task) return
        inFlight.remove(task.spec.key)
        if (!attached || bitmap == null || bitmap.isRecycled) return

        // Keep visible entries newer than stale/predictive residents before admitting this result.
        touchVisibleEntries()
        cache.put(task.spec.key, bitmap)
        predictiveWork.recordSuccessful(task.spec.key)

        // A callback is allowed to rebind another slot, so qualify again immediately before each
        // individual delivery instead of trusting a precomputed list of recipients.
        bindings.indices.forEach { slot ->
            val binding = bindings[slot]
            if (binding?.decodeKey == task.spec.key) deliver(binding, bitmap)
        }
    }

    private fun deliver(binding: SlotBinding, bitmap: Bitmap) {
        if (!attached || bitmap.isRecycled || bindings[binding.slot] !== binding) return
        if (!binding.lifecycle.recordSuccessfulDelivery(bitmap)) return
        binding.lifecycle.authority.onArtworkReady(
            PlayerSurfaceArtworkResult(
                slot = binding.slot,
                request = binding.request,
                bitmap = bitmap
            )
        )
    }

    /** A library thumbnail removes the placeholder but never satisfies the exact-size request. */
    private fun deliverPreview(binding: SlotBinding, bitmap: Bitmap) {
        if (!attached || bitmap.isRecycled || bindings[binding.slot] !== binding) return
        binding.lifecycle.authority.onArtworkReady(
            PlayerSurfaceArtworkResult(binding.slot, binding.request, bitmap)
        )
    }

    private fun cachedBitmap(key: DecodeKey): Bitmap? {
        val bitmap = cache[key] ?: return null
        if (!bitmap.isRecycled) return bitmap
        cache.remove(key)
        return null
    }

    private fun touchVisibleEntries() {
        bindings.forEach { binding ->
            binding?.decodeKey?.let(cache::get)
        }
    }

    private fun cancelOrphanedDecodes() {
        val visibleKeys = bindings.mapNotNullTo(HashSet(SLOT_COUNT)) { it?.decodeKey }
        val predictiveKeys = predictive.values.mapTo(HashSet(MAX_PREDICTIVE_REQUESTS)) { it.key }
        val iterator = inFlight.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in visibleKeys && entry.key !in predictiveKeys) {
                iterator.remove()
                entry.value.job.cancel()
            }
        }
    }

    private fun cancelAllDecodes() {
        val tasks = inFlight.values.toList()
        inFlight.clear()
        tasks.forEach { it.job.cancel() }
    }

    private fun decodeSpec(request: PlayerSurfaceArtworkRequest): DecodeSpec? {
        val resolved = resolveSource(request.source) ?: return null
        val width = request.widthPx.coerceAtMost(MAX_DECODE_DIMENSION_PX)
        val height = request.heightPx.coerceAtMost(MAX_DECODE_DIMENSION_PX)
        val revision = request.owner.mediaRevision.trim()
        val unversionedOwner = playerSurfaceUnversionedArtworkOwner(request.owner)
        val key = DecodeKey(
            physicalSource = resolved.identity,
            mediaRevision = revision,
            unversionedOwner = unversionedOwner,
            widthPx = width,
            heightPx = height
        )
        return DecodeSpec(
            key = key,
            data = resolved.data,
            coilCacheKey = key.coilKey(),
            previewMemoryCacheKey = playerSurfaceArtworkPreviewMemoryCacheKey(request),
            diskCacheIdentity = playerSurfaceArtworkDiskCacheIdentity(request) ?: return null
        )
    }

    private fun resolveSource(raw: String): ResolvedSource? {
        val candidate = raw.trim().takeIf(String::isNotEmpty) ?: return null
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        return when (scheme) {
            null -> {
                val file = File(candidate).absoluteFile
                ResolvedSource(file, "file:${file.path}")
            }
            "content", "file" -> {
                val normalized = parsed.normalizeScheme()
                ResolvedSource(normalized, normalized.toString())
            }
            "http", "https" -> {
                val normalized = parsed.normalizeScheme().toString()
                ResolvedSource(normalized, normalized)
            }
            else -> null
        }
    }

    private fun requireSlot(slot: Int) {
        require(slot in 0 until SLOT_COUNT) {
            "Artwork slot must be SLOT_LOWER, SLOT_CURRENT, or SLOT_HIGHER"
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "PlayerSurfaceArtworkLoader lifecycle methods must run on the main thread"
        }
    }

    private class SlotBinding(
        val slot: Int,
        val request: PlayerSurfaceArtworkRequest,
        val decodeKey: DecodeKey?,
        callback: PlayerSurfaceArtworkCallback
    ) {
        val lifecycle = PlayerSurfaceArtworkRequestBinding(
            request = request,
            initialAuthority = callback,
            isLive = { bitmap: Bitmap -> !bitmap.isRecycled }
        )
    }

    private data class ResolvedSource(val data: Any, val identity: String)

    private data class DecodeKey(
        val physicalSource: String,
        val mediaRevision: String,
        val unversionedOwner: PlayerSurfaceUnversionedArtworkOwner?,
        val widthPx: Int,
        val heightPx: Int
    ) {
        fun coilKey(): String = buildString(physicalSource.length + mediaRevision.length + 80) {
            append("player-surface-artwork-v1|")
            append(physicalSource.length).append(':').append(physicalSource).append('|')
            append(mediaRevision.length).append(':').append(mediaRevision).append('|')
            unversionedOwner?.let { owner ->
                append(owner.songId.length).append(':').append(owner.songId)
                    .append(':').append(owner.generation)
            }
            append('|').append(widthPx).append('x').append(heightPx)
        }
    }

    private data class DecodeSpec(
        val key: DecodeKey,
        val data: Any,
        val coilCacheKey: String,
        val previewMemoryCacheKey: String,
        val diskCacheIdentity: ArtworkDiskCacheIdentity
    )

    private data class DecodeTask(
        val spec: DecodeSpec,
        val lane: PlayerSurfaceArtworkDecodeLane,
        val job: Job
    )

    companion object {
        const val SLOT_LOWER: Int = 0
        const val SLOT_CURRENT: Int = 1
        const val SLOT_HIGHER: Int = 2

        private const val SLOT_COUNT = 3
        private const val MAX_PREDICTIVE_REQUESTS = 2
        private const val MAX_DECODE_DIMENSION_PX = 1_536
        private const val DEFAULT_CACHE_ENTRIES = SLOT_COUNT + MAX_PREDICTIVE_REQUESTS
        private const val DEFAULT_CACHE_BYTES = 32L * 1_024L * 1_024L

        private fun bitmapAllocationBytes(bitmap: Bitmap): Long = runCatching {
            bitmap.allocationByteCount.toLong()
        }.getOrElse {
            bitmap.rowBytes.toLong() * bitmap.height.toLong()
        }.coerceAtLeast(1L)
    }
}

/** Small pure access-ordered cache; both entry count and retained weight are hard bounds. */
internal class BoundedPlayerSurfaceLru<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val weightOf: (V) -> Long
) {
    private data class WeightedValue<V>(val value: V, val weight: Long)

    private val values = LinkedHashMap<K, WeightedValue<V>>(maxEntries, 0.75f, true)

    var currentWeight: Long = 0L
        private set

    val entryCount: Int get() = values.size

    init {
        require(maxEntries > 0)
        require(maxWeight > 0L)
    }

    operator fun get(key: K): V? = values[key]?.value

    fun put(key: K, value: V) {
        remove(key)
        val weight = weightOf(value)
        require(weight >= 0L) { "Cache entry weight cannot be negative" }
        if (weight > maxWeight) return

        values[key] = WeightedValue(value, weight)
        currentWeight += weight
        trimToBounds()
    }

    fun remove(key: K): V? {
        val removed = values.remove(key) ?: return null
        currentWeight -= removed.weight
        return removed.value
    }

    fun clear() {
        values.clear()
        currentWeight = 0L
    }

    private fun trimToBounds() {
        val iterator = values.entries.iterator()
        while ((values.size > maxEntries || currentWeight > maxWeight) && iterator.hasNext()) {
            val removed = iterator.next().value
            iterator.remove()
            currentWeight -= removed.weight
        }
    }
}
