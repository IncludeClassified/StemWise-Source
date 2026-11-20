package app.stemwise.ui.player.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.updateLayerBlock
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import app.stemwise.ui.PlayerMotionPolicy
import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

private val playerSurfaceGenerationSerial = AtomicLong(0L)
private val playerSurfaceRevisionSerial = AtomicLong(0L)

internal fun nextPlayerSurfaceGeneration(): Long =
    playerSurfaceGenerationSerial.incrementAndGet().also { generation ->
        check(generation > 0L) { "Player surface generation space exhausted" }
    }

internal fun nextPlayerSurfaceRevision(): Long =
    playerSurfaceRevisionSerial.incrementAndGet().also { revision ->
        check(revision > 0L) { "Player surface revision space exhausted" }
    }

/** Stable 64-bit FNV-1a key; unlike String.hashCode it remains practical for 400-item queues. */
internal fun playerSurfaceQueueKey(songId: String): Long {
    var hash = -0x340d631b7bdddcdbL
    var index = 0
    while (index < songId.length) {
        hash = hash xor songId[index].code.toLong()
        hash *= 0x100000001b3L
        index++
    }
    return hash
}

/**
 * Plain root-coordinate bridge between the transparent Compose layout and one native View.
 *
 * Layout callbacks never write Snapshot state. The View receives exact local control bounds
 * immediately, including a status-card insertion or a detail-panel resize, without restarting the
 * player composition. Accessibility geometry is sampled separately at stable route endpoints.
 */
internal enum class PlayerToolMotionComposeHostRole {
    STATUS,
    DETAIL_INCOMING,
    DETAIL_OUTGOING
}

/**
 * Scalar-only policy shared by the direct Android holder driver and host tests. The translation
 * follows the same native control envelope as the retained timeline/transport layers. A stale
 * owner is deliberately left at its endpoint instead of borrowing motion from another song.
 */
internal fun playerToolMotionHostTranslationY(
    role: PlayerToolMotionComposeHostRole,
    motionGeneration: Long,
    hostGeneration: Long,
    targetBoundsValid: Boolean,
    currentControlTopPx: Float,
    currentControlBottomPx: Float,
    targetControlTopPx: Float,
    targetControlBottomPx: Float
): Float {
    if (motionGeneration != hostGeneration || !targetBoundsValid) return 0f
    val translation = when (role) {
        PlayerToolMotionComposeHostRole.STATUS,
        PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
        PlayerToolMotionComposeHostRole.DETAIL_OUTGOING ->
            currentControlBottomPx - targetControlBottomPx
    }
    return if (translation.isFinite()) translation else 0f
}

/** Independent content-clock policy for the incoming and outgoing retained detail bodies. */
internal fun playerToolMotionDetailAlpha(
    role: PlayerToolMotionComposeHostRole,
    motionGeneration: Long,
    motionSelectionEpoch: Long,
    hostGeneration: Long,
    hostSelectionEpoch: Long,
    selectedTool: PlayerSurfaceTool,
    hostedTool: PlayerSurfaceTool,
    panelTransition: PlayerSurfacePanelTransition,
    panelContentEasedFraction: Float
): Float {
    if (role == PlayerToolMotionComposeHostRole.STATUS) return 1f
    if (
        motionGeneration != hostGeneration ||
        motionSelectionEpoch != hostSelectionEpoch
    ) return 0f
    val fraction = when {
        !panelContentEasedFraction.isFinite() -> 1f
        panelContentEasedFraction <= 0f -> 0f
        panelContentEasedFraction >= 1f -> 1f
        else -> panelContentEasedFraction
    }
    return when (role) {
        PlayerToolMotionComposeHostRole.STATUS -> 1f
        PlayerToolMotionComposeHostRole.DETAIL_INCOMING ->
            when {
                selectedTool == PlayerSurfaceTool.NONE || hostedTool != selectedTool -> 0f
                panelTransition == PlayerSurfacePanelTransition.NONE -> 1f
                else -> fraction
            }
        PlayerToolMotionComposeHostRole.DETAIL_OUTGOING -> {
            val visible = hostedTool != PlayerSurfaceTool.NONE && hostedTool != selectedTool &&
                (panelTransition == PlayerSurfacePanelTransition.CLOSE ||
                    panelTransition == PlayerSurfacePanelTransition.REFLOW)
            if (visible) 1f - fraction else 0f
        }
    }
}

/** Incoming content waits for target geometry; outgoing content remains visible during that frame. */
internal fun playerToolMotionHostAlpha(
    role: PlayerToolMotionComposeHostRole,
    targetBoundsValid: Boolean,
    contentAlpha: Float
): Float = if (role == PlayerToolMotionComposeHostRole.DETAIL_INCOMING && !targetBoundsValid) {
    0f
} else {
    contentAlpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
}

/** Only the incoming low-density Loop/Tune content receives the .98 -> 1 profile scale. */
internal fun playerToolMotionDetailScale(
    role: PlayerToolMotionComposeHostRole,
    motionGeneration: Long,
    motionSelectionEpoch: Long,
    hostGeneration: Long,
    hostSelectionEpoch: Long,
    selectedTool: PlayerSurfaceTool,
    hostedTool: PlayerSurfaceTool,
    panelTransition: PlayerSurfacePanelTransition,
    panelContentEasedFraction: Float,
    spatialMotionEnabled: Boolean
): Float {
    if (
        role != PlayerToolMotionComposeHostRole.DETAIL_INCOMING ||
        motionGeneration != hostGeneration ||
        motionSelectionEpoch != hostSelectionEpoch ||
        selectedTool == PlayerSurfaceTool.NONE ||
        hostedTool != selectedTool ||
        !spatialMotionEnabled ||
        panelTransition == PlayerSurfacePanelTransition.NONE ||
        (hostedTool != PlayerSurfaceTool.LOOP && hostedTool != PlayerSurfaceTool.TUNE)
    ) return 1f
    val fraction = when {
        !panelContentEasedFraction.isFinite() -> 1f
        panelContentEasedFraction <= 0f -> 0f
        panelContentEasedFraction >= 1f -> 1f
        else -> panelContentEasedFraction
    }
    return .98f + (.02f * fraction)
}

/** One endpoint-composed layer node driven directly by the native tool clock. */
private class PlayerToolMotionComposeHostBinding(
    val role: PlayerToolMotionComposeHostRole
) {
    private var node: PlayerToolMotionLayerNode? = null
    var generation: Long = Long.MIN_VALUE
        private set
    var selectionEpoch: Long = Long.MIN_VALUE
        private set
    var targetTool: PlayerSurfaceTool = PlayerSurfaceTool.NONE
        private set

    fun attach(
        node: PlayerToolMotionLayerNode,
        generation: Long,
        selectionEpoch: Long,
        targetTool: PlayerSurfaceTool
    ) {
        this.node = node
        this.generation = generation
        this.selectionEpoch = selectionEpoch
        this.targetTool = targetTool
    }

    fun detach(node: PlayerToolMotionLayerNode) {
        if (this.node !== node) return
        this.node = null
        generation = Long.MIN_VALUE
        selectionEpoch = Long.MIN_VALUE
        targetTool = PlayerSurfaceTool.NONE
    }

    fun resolveNode(): PlayerToolMotionLayerNode? = node
}

/**
 * A normal Compose layout node with an owned render layer. Native Choreographer frames update the
 * layer block directly, so motion never publishes Snapshot state, recomposes, or remeasures. It
 * also remains in the original Compose hit-test tree instead of introducing an interop View that
 * can steal the physical native player's pointer stream.
 */
internal class PlayerToolMotionLayerNode(
    private var role: PlayerToolMotionComposeHostRole,
    private var generation: Long,
    private var selectionEpoch: Long,
    private var targetTool: PlayerSurfaceTool,
    private var bridge: PlayerSurfaceHostBridge
) : Modifier.Node(), LayoutModifierNode {
    private var layerTranslationY = 0f
    private var layerAlpha = 1f
    private var layerScale = 1f
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        translationY = layerTranslationY
        alpha = layerAlpha
        scaleX = layerScale
        scaleY = layerScale
    }

    override fun onAttach() {
        bridge.attachToolMotionLayer(
            role = role,
            node = this,
            generation = generation,
            selectionEpoch = selectionEpoch,
            targetTool = targetTool
        )
    }

    override fun onDetach() {
        bridge.detachToolMotionLayer(role, this)
    }

    fun updateIdentity(
        role: PlayerToolMotionComposeHostRole,
        generation: Long,
        selectionEpoch: Long,
        targetTool: PlayerSurfaceTool,
        bridge: PlayerSurfaceHostBridge
    ) {
        val previousBridge = this.bridge
        val previousRole = this.role
        if (isAttached && (previousBridge !== bridge || previousRole != role)) {
            previousBridge.detachToolMotionLayer(previousRole, this)
        }
        this.role = role
        this.generation = generation
        this.selectionEpoch = selectionEpoch
        this.targetTool = targetTool
        this.bridge = bridge
        if (isAttached) {
            bridge.attachToolMotionLayer(
                role = role,
                node = this,
                generation = generation,
                selectionEpoch = selectionEpoch,
                targetTool = targetTool
            )
        }
    }

    fun applyMotion(translationY: Float, alpha: Float, scale: Float) {
        val resolvedTranslation = translationY.takeIf(Float::isFinite) ?: 0f
        val resolvedAlpha = alpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        val resolvedScale = scale.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 1f
        if (
            layerTranslationY.toRawBits() == resolvedTranslation.toRawBits() &&
            layerAlpha.toRawBits() == resolvedAlpha.toRawBits() &&
            layerScale.toRawBits() == resolvedScale.toRawBits()
        ) return
        layerTranslationY = resolvedTranslation
        layerAlpha = resolvedAlpha
        layerScale = resolvedScale
        updateLayerBlock(layerBlock)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeRelativeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}

private data class PlayerToolMotionLayerElement(
    val role: PlayerToolMotionComposeHostRole,
    val generation: Long,
    val selectionEpoch: Long,
    val targetTool: PlayerSurfaceTool,
    val bridge: PlayerSurfaceHostBridge
) : ModifierNodeElement<PlayerToolMotionLayerNode>() {
    override fun create(): PlayerToolMotionLayerNode = PlayerToolMotionLayerNode(
        role = role,
        generation = generation,
        selectionEpoch = selectionEpoch,
        targetTool = targetTool,
        bridge = bridge
    )

    override fun update(node: PlayerToolMotionLayerNode) {
        node.updateIdentity(
            role = role,
            generation = generation,
            selectionEpoch = selectionEpoch,
            targetTool = targetTool,
            bridge = bridge
        )
    }
}

private fun Modifier.playerToolMotionLayer(
    role: PlayerToolMotionComposeHostRole,
    generation: Long,
    selectionEpoch: Long,
    targetTool: PlayerSurfaceTool,
    bridge: PlayerSurfaceHostBridge
): Modifier = then(
    PlayerToolMotionLayerElement(
        role = role,
        generation = generation,
        selectionEpoch = selectionEpoch,
        targetTool = targetTool,
        bridge = bridge
    )
)

@Stable
internal class PlayerSurfaceHostBridge {
    private var view: PlayerSurfaceView? = null
    private var pendingSelectionArtwork: Triple<String, String, String?>? = null
    private var surfaceCoordinates: LayoutCoordinates? = null
    private var controlCoordinates: LayoutCoordinates? = null
    private var scheduledBoundsView: PlayerSurfaceView? = null
    private var hasToolMotionTarget = false
    private var toolMotionEpoch = Long.MIN_VALUE
    private var toolMotionGeneration = Long.MIN_VALUE
    private var toolMotionSelectedTool = PlayerSurfaceTool.NONE
    private var toolMotionDurationMs = 0
    private var toolMotionPanelTransition = PlayerSurfacePanelTransition.NONE
    private var toolMotionPanelContentDurationMs = 0
    private var toolMotionSpatialMotionEnabled = true
    private var toolMotionArtworkSizePx = 0f
    private var toolMotionMixerCardHeightPx = 0f
    private var toolMotionMixerSpacingPx = 0f
    private var toolMotionListSpacingPx = 0f
    private var toolMotionSeekerTrackHeightPx = 0f
    private var toolMotionTransportHeightPx = 0f
    private var toolMotionDetailHeightPx = 0f
    private var targetControlBoundsValid = false
    private var targetControlBoundsGeneration = Long.MIN_VALUE
    private var targetControlBoundsEpoch = Long.MIN_VALUE
    private var targetControlTopPx = 0f
    private var targetControlBottomPx = 0f
    private var motionGeneration = Long.MIN_VALUE
    private var motionSelectionEpoch = Long.MIN_VALUE
    private var motionPanelContentEasedFraction = 1f
    private var motionCurrentControlTopPx = 0f
    private var motionCurrentControlBottomPx = 0f
    private var mixerScrollListener: ((PlayerSurfaceSemanticsGeometry?) -> Unit)? = null
    private val statusComposeHost = PlayerToolMotionComposeHostBinding(
        PlayerToolMotionComposeHostRole.STATUS
    )
    private val incomingDetailComposeHost = PlayerToolMotionComposeHostBinding(
        PlayerToolMotionComposeHostRole.DETAIL_INCOMING
    )
    private val outgoingDetailComposeHost = PlayerToolMotionComposeHostBinding(
        PlayerToolMotionComposeHostRole.DETAIL_OUTGOING
    )
    private val settledToolMotionEpoch = MutableStateFlow(Long.MIN_VALUE)
    private val toolMotionListener = object : PlayerSurfaceToolMotionListener {
        override fun onToolMotionStarted(
            generation: Long,
            selectionEpoch: Long,
            currentControlTopPx: Float,
            currentControlBottomPx: Float
        ) {
            if (generation != toolMotionGeneration || selectionEpoch != toolMotionEpoch) return
            motionGeneration = generation
            motionSelectionEpoch = selectionEpoch
            motionPanelContentEasedFraction =
                if (toolMotionPanelTransition == PlayerSurfacePanelTransition.NONE) 1f else 0f
            motionCurrentControlTopPx = currentControlTopPx
            motionCurrentControlBottomPx = currentControlBottomPx
            applyToolMotionComposeHosts()
        }

        override fun onToolMotionFrame(
            generation: Long,
            selectionEpoch: Long,
            panelContentEasedFraction: Float,
            currentControlTopPx: Float,
            currentControlBottomPx: Float
        ) {
            if (
                motionGeneration != generation ||
                motionSelectionEpoch != selectionEpoch
            ) return
            motionPanelContentEasedFraction = panelContentEasedFraction
            motionCurrentControlTopPx = currentControlTopPx
            motionCurrentControlBottomPx = currentControlBottomPx
            applyToolMotionComposeHosts()
        }

        override fun onToolMotionSettled(generation: Long, selectionEpoch: Long) {
            if (generation != toolMotionGeneration || selectionEpoch != toolMotionEpoch) return
            if (
                motionGeneration == generation &&
                motionSelectionEpoch == selectionEpoch
            ) {
                motionPanelContentEasedFraction = 1f
                applyToolMotionComposeHosts()
            }
            if (selectionEpoch > settledToolMotionEpoch.value) {
                settledToolMotionEpoch.value = selectionEpoch
            }
        }
    }
    private val publishBounds = Runnable {
        val target = scheduledBoundsView
        scheduledBoundsView = null
        if (target == null || view !== target) return@Runnable
        publishControlBoundsNow(target)
    }

    fun attach(view: PlayerSurfaceView) {
        if (this.view === view) return
        scheduledBoundsView?.removeCallbacks(publishBounds)
        scheduledBoundsView = null
        this.view = view
        view.setToolMotionListener(toolMotionListener)
        view.setMixerScrollListener(mixerScrollListener)
        publishOrScheduleControlBounds()
    }

    fun detach(view: PlayerSurfaceView) {
        if (this.view !== view) return
        scheduledBoundsView?.removeCallbacks(publishBounds)
        scheduledBoundsView = null
        view.setToolMotionListener(null)
        view.setMixerScrollListener(null)
        this.view = null
        targetControlBoundsValid = false
        targetControlBoundsGeneration = Long.MIN_VALUE
        targetControlBoundsEpoch = Long.MIN_VALUE
    }

    fun setSurfaceCoordinates(coordinates: LayoutCoordinates) {
        surfaceCoordinates = coordinates
        publishOrScheduleControlBounds()
    }

    fun setControlCoordinates(coordinates: LayoutCoordinates) {
        controlCoordinates = coordinates
        publishOrScheduleControlBounds()
    }

    internal fun attachToolMotionLayer(
        role: PlayerToolMotionComposeHostRole,
        node: PlayerToolMotionLayerNode,
        generation: Long,
        selectionEpoch: Long,
        targetTool: PlayerSurfaceTool
    ) {
        composeHost(role).attach(node, generation, selectionEpoch, targetTool)
        applyToolMotionComposeHost(composeHost(role))
    }

    internal fun detachToolMotionLayer(
        role: PlayerToolMotionComposeHostRole,
        node: PlayerToolMotionLayerNode
    ) {
        composeHost(role).detach(node)
    }

    /**
     * Publishes the immutable target specification for one selection epoch. Coordinates arrive
     * from the endpoint probe in a later layout pass; a post-on-animation fallback handles tools
     * whose capped endpoint geometry is identical and therefore does not move that probe.
     */
    fun setToolMotionTargetSpec(
        epoch: Long,
        generation: Long,
        selectedTool: PlayerSurfaceTool,
        durationMs: Int,
        panelTransition: PlayerSurfacePanelTransition,
        panelContentDurationMs: Int,
        spatialMotionEnabled: Boolean,
        expandedArtworkSizePx: Float,
        mixerCardHeightPx: Float,
        mixerSpacingPx: Float,
        listSpacingPx: Float,
        seekerTrackHeightPx: Float,
        transportHeightPx: Float,
        detailViewportHeightPx: Float
    ) {
        if (epoch < toolMotionEpoch) return
        val newMotionEpoch = generation != toolMotionGeneration || epoch != toolMotionEpoch
        if (newMotionEpoch) {
            targetControlBoundsValid = false
            targetControlBoundsGeneration = Long.MIN_VALUE
            targetControlBoundsEpoch = Long.MIN_VALUE
            // Prime the new owner before the native View's first callback. This keeps the
            // outgoing retained body visible on the composition frame of a direct tool switch.
            motionGeneration = generation
            motionSelectionEpoch = epoch
            motionPanelContentEasedFraction =
                if (panelTransition == PlayerSurfacePanelTransition.NONE) 1f else 0f
        }
        hasToolMotionTarget = true
        toolMotionEpoch = epoch
        toolMotionGeneration = generation
        toolMotionSelectedTool = selectedTool
        toolMotionDurationMs = durationMs.coerceAtLeast(0)
        toolMotionPanelTransition = panelTransition
        toolMotionPanelContentDurationMs = panelContentDurationMs.coerceAtLeast(0)
        toolMotionSpatialMotionEnabled = spatialMotionEnabled
        toolMotionArtworkSizePx = expandedArtworkSizePx
        toolMotionMixerCardHeightPx = mixerCardHeightPx
        toolMotionMixerSpacingPx = mixerSpacingPx
        toolMotionListSpacingPx = listSpacingPx
        toolMotionSeekerTrackHeightPx = seekerTrackHeightPx
        toolMotionTransportHeightPx = transportHeightPx
        toolMotionDetailHeightPx = detailViewportHeightPx
        applyToolMotionComposeHosts()
        scheduleControlBounds()
    }

    suspend fun awaitToolMotionSettled(generation: Long, epoch: Long) {
        if (
            motionGeneration == generation &&
            settledToolMotionEpoch.value >= epoch
        ) return
        settledToolMotionEpoch.first { settled -> settled >= epoch }
    }

    fun setMixerScrollListener(listener: ((PlayerSurfaceSemanticsGeometry?) -> Unit)?) {
        mixerScrollListener = listener
        view?.setMixerScrollListener(listener)
    }

    fun scrollMixerBy(generation: Long, deltaPx: Float): Boolean =
        view?.scrollMixerBy(generation, deltaPx) == true

    fun semanticsGeometry(): PlayerSurfaceSemanticsGeometry? = view?.semanticsGeometry()

    /** Allocation-free readiness probe used while a native source/package bind is catching up. */
    fun semanticsMixerLaneCount(): Int? = view?.semanticsMixerLaneCount()

    /**
     * Redraws the timeline once when an external, paused seek changes the position provider.
     * Playing transport already owns a Choreographer callback, so this remains a cheap no-op
     * between the accessibility clock's coarse publications.
     */
    fun notifyTimelinePositionChanged() {
        view?.onExternalTimelinePositionChanged()
    }

    /** Paints feedback for an action already accepted by the generation-aware semantic sink. */
    fun pulseSemanticFeedback(
        generation: Long,
        target: PlayerSurfaceSemanticFeedbackTarget
    ): Boolean = view?.pulseSemanticFeedback(generation, target) == true

    fun acceptMixerFlags(generation: Long, lane: Int, volume: Float, muted: Boolean, solo: Boolean): Boolean =
        view?.acceptMixerFlags(generation, lane, volume, muted, solo) == true

    fun pulseCompactOpenFeedback(generation: Long): Boolean =
        view?.pulseCompactOpenFeedback(generation) == true

    fun prioritizeSelectionArtwork(
        songId: String,
        mediaRevision: String,
        artworkSource: String?
    ): Boolean {
        if (songId.isBlank() || artworkSource.isNullOrBlank()) return false
        pendingSelectionArtwork = Triple(songId, mediaRevision, artworkSource)
        applyPendingSelectionArtwork()
        return true
    }

    fun applyPendingSelectionArtwork(): Boolean {
        val target = pendingSelectionArtwork ?: return false
        val targetView = view ?: return false
        if (!targetView.prioritizeSelectionArtwork(target.first, target.second, target.third)) {
            return false
        }
        pendingSelectionArtwork = null
        return true
    }

    fun restoreSelectionArtworkPriority() {
        pendingSelectionArtwork = null
        view?.restoreSelectionArtworkPriority()
    }

    fun performSemanticNavigation(
        generation: Long,
        targetIndex: Int?,
        offsetFraction: Float,
        commit: () -> Boolean
    ): Boolean = view?.performSemanticNavigation(
        generation = generation,
        targetIndex = targetIndex,
        offsetFraction = offsetFraction,
        commit = commit
    ) == true

    private fun scheduleControlBounds() {
        val target = view ?: return
        if (scheduledBoundsView === target) return
        scheduledBoundsView?.removeCallbacks(publishBounds)
        scheduledBoundsView = target
        target.postOnAnimation(publishBounds)
    }

    /**
     * LayoutCoordinates are live handles into the current tree. The second onGloballyPositioned
     * callback in a route frame can therefore resolve both nodes immediately, before drawing.
     * Posting every successful update to the next frame made artwork use geometry N while the
     * lower controls still used N-1, which was visible as endpoint flash/touch drift. A post is
     * retained only as the attach/order fallback when either coordinate is not ready yet.
     */
    fun publishOrScheduleControlBounds() {
        val target = view ?: return
        if (!publishControlBoundsNow(target)) scheduleControlBounds()
    }

    private fun publishControlBoundsNow(target: PlayerSurfaceView): Boolean {
        if (view !== target) return false
        val surface = surfaceCoordinates ?: return false
        val controls = controlCoordinates ?: return false
        if (!surface.isAttached || !controls.isAttached) return false
        val local = runCatching {
            surface.localBoundingBoxOf(controls, clipBounds = false)
        }.getOrNull() ?: return false
        targetControlBoundsValid = true
        targetControlBoundsGeneration = toolMotionGeneration
        targetControlBoundsEpoch = toolMotionEpoch
        targetControlTopPx = local.top
        targetControlBottomPx = local.bottom
        applyToolMotionComposeHosts()
        if (hasToolMotionTarget) {
            if (!target.retargetToolMotion(
                PlayerSurfaceToolMotionTarget(
                generation = toolMotionGeneration,
                selectionEpoch = toolMotionEpoch,
                selectedTool = toolMotionSelectedTool,
                durationMs = toolMotionDurationMs,
                panelTransition = toolMotionPanelTransition,
                panelContentDurationMs = toolMotionPanelContentDurationMs,
                expandedArtworkSizePx = toolMotionArtworkSizePx,
                mixerCardHeightPx = toolMotionMixerCardHeightPx,
                mixerSpacingPx = toolMotionMixerSpacingPx,
                listSpacingPx = toolMotionListSpacingPx,
                seekerTrackHeightPx = toolMotionSeekerTrackHeightPx,
                transportHeightPx = toolMotionTransportHeightPx,
                controlBounds = PlayerSurfaceBounds(
                    left = local.left,
                    top = local.top,
                    right = local.right,
                    bottom = local.bottom
                ),
                detailViewportHeightPx = toolMotionDetailHeightPx
                )
            )) return false
        } else {
            target.setControlBounds(local.left, local.top, local.right, local.bottom)
        }
        scheduledBoundsView?.removeCallbacks(publishBounds)
        scheduledBoundsView = null
        return true
    }

    private fun composeHost(
        role: PlayerToolMotionComposeHostRole
    ): PlayerToolMotionComposeHostBinding = when (role) {
        PlayerToolMotionComposeHostRole.STATUS -> statusComposeHost
        PlayerToolMotionComposeHostRole.DETAIL_INCOMING -> incomingDetailComposeHost
        PlayerToolMotionComposeHostRole.DETAIL_OUTGOING -> outgoingDetailComposeHost
    }

    private fun applyToolMotionComposeHosts() {
        applyToolMotionComposeHost(statusComposeHost)
        applyToolMotionComposeHost(incomingDetailComposeHost)
        applyToolMotionComposeHost(outgoingDetailComposeHost)
    }

    /** Called by the native Choreographer listener; keep this path scalar and allocation-free. */
    private fun applyToolMotionComposeHost(binding: PlayerToolMotionComposeHostBinding) {
        val node = binding.resolveNode() ?: return
        val targetBoundsReady = targetControlBoundsValid &&
            targetControlBoundsGeneration == binding.generation &&
            targetControlBoundsEpoch == binding.selectionEpoch
        val translationY = playerToolMotionHostTranslationY(
            role = binding.role,
            motionGeneration = motionGeneration,
            hostGeneration = binding.generation,
            targetBoundsValid = targetBoundsReady,
            currentControlTopPx = motionCurrentControlTopPx,
            currentControlBottomPx = motionCurrentControlBottomPx,
            targetControlTopPx = targetControlTopPx,
            targetControlBottomPx = targetControlBottomPx
        )
        val alpha = playerToolMotionHostAlpha(
            role = binding.role,
            targetBoundsValid = targetBoundsReady,
            contentAlpha = playerToolMotionDetailAlpha(
                role = binding.role,
                motionGeneration = motionGeneration,
                motionSelectionEpoch = motionSelectionEpoch,
                hostGeneration = binding.generation,
                hostSelectionEpoch = binding.selectionEpoch,
                selectedTool = toolMotionSelectedTool,
                hostedTool = binding.targetTool,
                panelTransition = toolMotionPanelTransition,
                panelContentEasedFraction = motionPanelContentEasedFraction
            )
        )
        val scale = playerToolMotionDetailScale(
            role = binding.role,
            motionGeneration = motionGeneration,
            motionSelectionEpoch = motionSelectionEpoch,
            hostGeneration = binding.generation,
            hostSelectionEpoch = binding.selectionEpoch,
            selectedTool = toolMotionSelectedTool,
            hostedTool = binding.targetTool,
            panelTransition = toolMotionPanelTransition,
            panelContentEasedFraction = motionPanelContentEasedFraction,
            spatialMotionEnabled = toolMotionSpatialMotionEnabled
        )
        node.applyMotion(
            translationY = translationY,
            alpha = alpha,
            scale = scale
        )
    }
}

/**
 * Hosts endpoint-composed tool content in a normal Compose-owned render layer. Native frames
 * update only that layer's translation/alpha through [LayoutModifierNode.updateLayerBlock].
 */
@Composable
internal fun PlayerToolMotionComposeHost(
    role: PlayerToolMotionComposeHostRole?,
    generation: Long,
    selectionEpoch: Long,
    targetTool: PlayerSurfaceTool,
    bridge: PlayerSurfaceHostBridge,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = if (role == null) modifier else modifier.playerToolMotionLayer(
            role = role,
            generation = generation,
            selectionEpoch = selectionEpoch,
            targetTool = targetTool,
            bridge = bridge
        )
    ) {
        content()
    }
}

/**
 * Stable attach-order buffer. The native View owns reference refresh and snapshot admission.
 */
@Stable
private class PlayerSurfaceSnapshotBindingDriver {
    private var view: PlayerSurfaceView? = null
    private var snapshot: FullPlayerSnapshot? = null
    private var actionSink: FullPlayerActionSink? = null
    private var positionProvider: PlayerSurfacePositionProvider? = null
    private var widthPx = 0
    private var heightPx = 0
    private var density = 0f
    private var direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT

    fun updateRouteProgress(progress: Float) {
        view?.updateRouteExpansionProgress(progress)
    }

    fun attach(view: PlayerSurfaceView) {
        if (this.view === view) return
        this.view = view
        publish()
    }

    fun detach(view: PlayerSurfaceView) {
        if (this.view === view) this.view = null
    }

    fun update(
        snapshot: FullPlayerSnapshot,
        actionSink: FullPlayerActionSink,
        positionProvider: PlayerSurfacePositionProvider,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        direction: PlayerSurfaceLayoutDirection
    ) {
        this.snapshot = snapshot
        this.actionSink = actionSink
        this.positionProvider = positionProvider
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.density = density
        this.direction = direction
        publish()
    }

    private fun publish() {
        val target = view ?: return
        val nextSnapshot = snapshot ?: return
        val nextSink = actionSink ?: return
        val nextPositionProvider = positionProvider ?: return
        target.bind(
            snapshot = nextSnapshot,
            actionSink = nextSink,
            positionProvider = nextPositionProvider,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            direction = direction
        )
    }
}

/** One fill-size, allocation-free physical surface. Compose supplies no competing pixels/touch. */
@Composable
internal fun PlayerSurfaceNativeLayer(
    snapshot: FullPlayerSnapshot,
    actionSink: FullPlayerActionSink,
    positionProvider: PlayerSurfacePositionProvider,
    bridge: PlayerSurfaceHostBridge,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    fontScale: Float,
    direction: PlayerSurfaceLayoutDirection,
    expansionProgress: Float,
    artworkAlpha: Float,
    expandedHeaderHeightPx: Float,
    expandedHeaderGapPx: Float,
    expandedIdentityHeightPx: Float,
    expandedIdentityGapPx: Float,
    compactChrome: Boolean,
    listSpacingPx: Float,
    seekerClockTopClearancePx: Float,
    seekerTrackHeightPx: Float,
    transportHeightPx: Float,
    transportPlaySizePx: Float,
    ambientMotionAllowed: Boolean,
    externalTransport: Boolean = false,
    artworkPaletteListener: PlayerSurfaceArtworkPaletteListener?,
    routeExpansionState: State<Float>? = null,
    mixerWorkspaceProgress: Float? = null,
    modifier: Modifier = Modifier
) {
    val snapshotDriver = remember { PlayerSurfaceSnapshotBindingDriver() }
    val currentArtworkPaletteListener = rememberUpdatedState(artworkPaletteListener)
    val nativeArtworkPaletteListener = remember {
        PlayerSurfaceArtworkPaletteListener { generation, songId, mediaRevision, physicalSource,
            primaryArgb, secondaryArgb, tertiaryArgb, dominantArgb ->
            currentArtworkPaletteListener.value?.onCurrentArtworkPalette(
                generation,
                songId,
                mediaRevision,
                physicalSource,
                primaryArgb,
                secondaryArgb,
                tertiaryArgb,
                dominantArgb
            )
        }
    }
    SideEffect {
        snapshotDriver.update(
            snapshot,
            actionSink,
            positionProvider,
            widthPx,
            heightPx,
            density,
            direction
        )
        bridge.applyPendingSelectionArtwork()
        bridge.publishOrScheduleControlBounds()
    }
    key(bridge) {
        AndroidView(
            factory = { context ->
                PlayerSurfaceView(context).also { surface ->
                    surface.setArtworkPaletteListener(nativeArtworkPaletteListener)
                }
            },
            update = { surface ->
                val resolvedExpansion = routeExpansionState?.value ?: expansionProgress
                val resolvedArtworkAlpha = mixerWorkspaceProgress?.let { mixerProgress ->
                    PlayerMotionPolicy.artworkAlpha(resolvedExpansion, mixerProgress)
                } ?: artworkAlpha
                // The View owns scalar normalization/equality gating. Artwork and mixer
                // detail-height fields remain owned by the post-layout bridge frame.
                surface.updateRouteBaseMetrics(
                    expansionProgress = resolvedExpansion,
                    artworkAlpha = resolvedArtworkAlpha,
                    expandedHeaderHeightPx = expandedHeaderHeightPx,
                    expandedHeaderGapPx = expandedHeaderGapPx,
                    expandedIdentityHeightPx = expandedIdentityHeightPx,
                    expandedIdentityGapPx = expandedIdentityGapPx,
                    compactChrome = compactChrome,
                    listSpacingPx = listSpacingPx,
                    fontScale = fontScale,
                    seekerClockTopClearancePx = seekerClockTopClearancePx,
                    seekerTrackHeightPx = seekerTrackHeightPx,
                    transportHeightPx = transportHeightPx,
                    transportPlaySizePx = transportPlaySizePx
                )
                surface.setExternalTransport(externalTransport)
                snapshotDriver.attach(surface)
                bridge.attach(surface)
                surface.updateAmbientMotionPolicy(ambientMotionAllowed)
            },
            onRelease = { surface ->
                surface.setArtworkPaletteListener(null)
                bridge.detach(surface)
                snapshotDriver.detach(surface)
            },
            modifier = modifier.drawWithContent {
                // The shared element follows the same draw-phase route state as its sheet body.
                snapshotDriver.updateRouteProgress(routeExpansionState?.value ?: expansionProgress)
                drawContent()
            }
                .fillMaxSize()
                .onGloballyPositioned(bridge::setSurfaceCoordinates)
        )
    }
}
