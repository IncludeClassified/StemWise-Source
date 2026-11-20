package app.stemwise.ui.player.surface

internal object PlayerSurfaceTransportButtons {
    const val SHUFFLE = 0
    const val PREVIOUS = 1
    const val PLAY = 2
    const val NEXT = 3
    const val REPEAT = 4
}

internal fun playerSurfaceBoundsAt(bounds: FloatArray, index: Int): PlayerSurfaceBounds {
    val base = index * 4
    return PlayerSurfaceBounds(bounds.getOrElse(base) { 0f }, bounds.getOrElse(base + 1) { 0f },
        bounds.getOrElse(base + 2) { 0f }, bounds.getOrElse(base + 3) { 0f })
}

/** One physical control registry; drawing, pointer ownership and accessibility read these bounds. */
internal class PlayerSurfaceControlGeometry {
    class Control(count: Int) {
        val visual = FloatArray(count * 4)
        val hit = FloatArray(count * 4)
    }
    val cards = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES * 4)
    val sliders = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES * 4)
    val mute = Control(PLAYER_SURFACE_MAX_MIXER_LANES)
    val solo = Control(PLAYER_SURFACE_MAX_MIXER_LANES)
    val transport = Control(5)
    val tools = Control(4)
}

/** Projects the exact rendered bounds; it cannot acquire a different snapshot or action owner. */
internal fun PlayerSurfaceControlGeometry.projectSemantics(
    width: Int, height: Int, compact: Boolean,
    route: PlayerSurfaceRouteGeometry,
    artwork: PlayerSurfaceBounds, identity: PlayerSurfaceBounds, seeker: PlayerSurfaceBounds,
    laneCount: Int,
    mixerViewport: PlayerSurfaceBounds, mixerOffset: Float, mixerMaximum: Float
): PlayerSurfaceSemanticsGeometry? {
    val empty = PlayerSurfaceBounds(0f, 0f, 0f, 0f)
    fun expanded(bounds: PlayerSurfaceBounds) = if (compact) empty else bounds
    fun mixerBounds(bounds: FloatArray, lane: Int) =
        playerSurfaceMixerScrolledBounds(playerSurfaceBoundsAt(bounds, lane), mixerViewport, 0f)
    val count = if (compact) 0 else laneCount
    val lanes = List(count) { lane ->
        PlayerSurfaceMixerLaneSemanticsBounds(mixerBounds(sliders, lane), mixerBounds(mute.hit, lane), mixerBounds(solo.hit, lane))
    }
    val middle = (identity.top + identity.bottom) * .5f
    return PlayerSurfaceSemanticsGeometry.createOrNull(
        widthPx = width, heightPx = height,
        page = PlayerSurfaceBounds(route.pagerLeft, route.pagerTop, route.pagerRight, route.pagerBottom),
        artwork = artwork, identity = identity,
        title = PlayerSurfaceBounds(identity.left, identity.top, identity.right, middle),
        artist = PlayerSurfaceBounds(identity.left, middle, identity.right, identity.bottom),
        original = expanded(route.sourceOriginalHitBounds), stems = expanded(route.sourceStemsHitBounds),
        seeker = expanded(seeker),
        shuffle = expanded(playerSurfaceBoundsAt(transport.hit, PlayerSurfaceTransportButtons.SHUFFLE)),
        previous = expanded(playerSurfaceBoundsAt(transport.hit, PlayerSurfaceTransportButtons.PREVIOUS)),
        playPause = expanded(playerSurfaceBoundsAt(transport.hit, PlayerSurfaceTransportButtons.PLAY)),
        miniPlayPause = route.miniPlayHitBounds,
        next = expanded(playerSurfaceBoundsAt(transport.hit, PlayerSurfaceTransportButtons.NEXT)),
        repeat = expanded(playerSurfaceBoundsAt(transport.hit, PlayerSurfaceTransportButtons.REPEAT)),
        collapse = expanded(route.collapseHitBounds), info = expanded(route.infoHitBounds),
        loopTool = expanded(playerSurfaceBoundsAt(tools.hit, 0)), tuneTool = expanded(playerSurfaceBoundsAt(tools.hit, 1)),
        lyricsTool = expanded(playerSurfaceBoundsAt(tools.hit, 2)), metronomeTool = expanded(playerSurfaceBoundsAt(tools.hit, 3)),
        mixerLanes = lanes, mixerViewport = if (count == 0) empty else mixerViewport,
        mixerScrollOffsetPx = if (count == 0) 0f else mixerOffset,
        mixerScrollMaximumPx = if (count == 0) 0f else mixerMaximum)
}
