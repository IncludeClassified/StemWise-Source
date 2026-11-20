package app.stemwise.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stemwise.model.Song
import app.stemwise.model.LyricTrack
import app.stemwise.model.TimedLyricLine
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.SurfaceLow
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.stemwiseAuraSelectable
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.theme.Violet
import app.stemwise.ui.theme.StemwiseSystemFontFamily
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.semanticSpec
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.player.nativeplayer.*
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The retained player is inert until the lyrics sheet's actual exit has completed. */
@Composable
internal fun PlayerLyricsModalHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    song: Song,
    presentationGeneration: Long,
    positionState: State<Long>,
    onPresenceChanged: (Boolean) -> Unit = {},
    transport: @Composable () -> Unit = {},
    onSeek: (Long, Long) -> Boolean,
    modifier: Modifier = Modifier,
    underlay: @Composable () -> Unit
) {
    var overlayPresent by remember { mutableStateOf(false) }
    val blocked = visible || overlayPresent
    val currentPresenceChanged by rememberUpdatedState(onPresenceChanged)
    SideEffect { currentPresenceChanged(blocked) }
    DisposableEffect(Unit) { onDispose { currentPresenceChanged(false) } }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().playerToolMotionInteractionGuard(
            enabled = !blocked, generation = presentationGeneration, selectionEpoch = 0L
        )) { underlay() }
        ExpandedLyricsOverlay(visible = visible, onDismiss = onDismiss, song = song,
            presentationGeneration = presentationGeneration, positionState = positionState,
            onPresenceChanged = { overlayPresent = it }, transport = transport, onSeek = onSeek)
    }
}

@Composable
internal fun ExpandedLyricsOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    song: Song,
    presentationGeneration: Long,
    positionState: State<Long>,
    transport: @Composable () -> Unit = {},
    onPresenceChanged: (Boolean) -> Unit = {},
    onSeek: (Long, Long) -> Boolean
) {
    val currentSeek by rememberUpdatedState(onSeek)
    val currentGeneration by rememberUpdatedState(presentationGeneration)
    val motion = LocalStemwiseMotion.current
    val density = LocalDensity.current
    val paletteKey = remember(song.id, song.mediaRevision, song.artworkSource) {
        artworkAuraPaletteKey(song)
    }
    val observedPalette = remember(paletteKey) { ArtworkAuraPaletteCache.observe(paletteKey) }
    val palette = observedPalette.value ?: MissingArtworkAuraPalette
    val backgroundColors = remember(palette) { playerArtworkBackgroundColors(palette) }
    val backdropTop by animateColorAsState(
        targetValue = backgroundColors[0],
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.PALETTE_PROPAGATE),
        label = "lyrics-backdrop-top"
    )
    val backdropMiddle by animateColorAsState(
        targetValue = backgroundColors[1],
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.PALETTE_PROPAGATE),
        label = "lyrics-backdrop-middle"
    )
    val backdropBottom by animateColorAsState(
        targetValue = backgroundColors[2],
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.PALETTE_PROPAGATE),
        label = "lyrics-backdrop-bottom"
    )
    val backdropBrush = remember(backdropTop, backdropMiddle, backdropBottom) {
        Brush.verticalGradient(
            listOf(backdropTop, backdropMiddle, backdropBottom)
        )
    }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val dragScope = rememberCoroutineScope()
    val dragBy: (Float) -> Unit = { delta ->
        if (delta != 0f) settleJob?.cancel()
        dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f)
    }
    val visibilityState = remember { MutableTransitionState(false) }
    val currentPresenceChanged by rememberUpdatedState(onPresenceChanged)
    val present = visibilityState.currentState || visibilityState.targetState
    SideEffect { currentPresenceChanged(present) }
    LaunchedEffect(visible) {
        settleJob?.cancel()
        visibilityState.targetState = visible
        if (visible) dragOffsetPx = 0f
    }
    val dragState = rememberDraggableState(dragBy)
    val dismissDistancePx = with(density) { 72.dp.toPx() }
    val settleDrag: suspend (Float) -> Unit = { velocity ->
        settleJob?.cancel()
        if (dragOffsetPx >= dismissDistancePx ||
            (dragOffsetPx > 0f && velocity > dismissDistancePx * 8f)
        ) {
            onDismiss()
        } else {
            settleJob = dragScope.launch {
                animate(
                    initialValue = dragOffsetPx,
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = if (motion.enabled) spring(dampingRatio = .82f, stiffness = 420f) else snap()
                ) { value, _ -> dragOffsetPx = value }
            }
        }
    }
    val currentSettleDrag by rememberUpdatedState(settleDrag)
    val sheetScroll = remember {
        LyricsSheetScrollConnection(
            offset = { dragOffsetPx },
            drag = dragBy,
            settle = { currentSettleDrag(it) }
        )
    }
    PredictiveBackHandler(enabled = visibilityState.targetState) { events ->
        settleJob?.cancel()
        val start = dragOffsetPx
        try {
            events.collect { event ->
                dragOffsetPx = start + (sheetHeightPx - start) * event.progress.coerceIn(0f, 1f)
            }
            onDismiss()
        } catch (cancelled: CancellationException) {
            dragOffsetPx = start
            throw cancelled
        }
    }
    if (visibilityState.currentState || visibilityState.targetState) {
        AnimatedVisibility(
            visibleState = visibilityState,
            enter = slideInVertically(
                animationSpec = motion.semanticSpec(
                    StemwiseMotionEvent.PANEL_OPEN,
                    spatial = true
                ),
                initialOffsetY = { it }
            ),
            exit = slideOutVertically(
                animationSpec = motion.semanticSpec(
                    StemwiseMotionEvent.PANEL_CLOSE,
                    spatial = true
                ),
                targetOffsetY = { it }
            ),
            modifier = Modifier
                .fillMaxSize()
                .testTag("expanded_lyrics_sheet")
        ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                        .graphicsLayer { translationY = dragOffsetPx }
                        .background(backdropBrush)
                        .semantics { paneTitle = "Lyrics" }
                        .nestedScroll(sheetScroll)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                        )
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Vertical,
                                onDragStarted = { settleJob?.cancel() },
                                onDragStopped = { currentSettleDrag(it) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(TextSecondary.copy(alpha = .78f))
                        )
                    }
                    AnimatedContent(
                        targetState = song to presentationGeneration,
                        contentKey = { it.second },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        transitionSpec = {
                            fadeIn(
                                motion.semanticSpec(StemwiseMotionEvent.ARTWORK_CHANGE)
                            ).togetherWith(
                                fadeOut(motion.semanticSpec(StemwiseMotionEvent.ARTWORK_CHANGE))
                            )
                        },
                        label = "expanded-lyrics-song"
                    ) { (visibleSong, visibleGeneration) ->
                        LyricsPanel(
                            song = visibleSong,
                            positionState = positionState,
                            isolateScroll = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            seekEnabled = visibleGeneration == presentationGeneration,
                            onSeek = { target -> visibleGeneration == currentGeneration &&
                                currentSeek(visibleGeneration, target) }
                        )
                    }
                    transport()
                }
            }
    }
}

@Composable
internal fun LyricsPanel(
    song: Song,
    positionState: State<Long>,
    modifier: Modifier = Modifier,
    isolateScroll: Boolean = true,
    seekEnabled: Boolean = true,
    scrollState: LazyListState? = null,
    onSeek: (Long) -> Boolean
) {
    val tracks = remember(song) { resolvedLyricTracks(song) }
    val canonicalTracks = remember(tracks) { canonicalLyricModeTracks(tracks) }
    val original = canonicalTracks.firstOrNull { it.kind == "original" }
    val translation = canonicalTracks.firstOrNull { it.kind == "translation" }
    val ipa = canonicalTracks.firstOrNull(::isIpaTrack)
        ?: canonicalTracks.firstOrNull { it.kind == "transliteration" }
    var showTranslation by remember(song.id, song.mediaRevision) {
        mutableStateOf(false)
    }
    var showIpa by remember(song.id, song.mediaRevision) {
        mutableStateOf(false)
    }
    val visibleTracks = buildList {
        original?.let(::add)
        if (showIpa) ipa?.takeUnless { it.id == original?.id }?.let(::add)
        if (showTranslation) translation?.takeUnless { it.id == original?.id }?.let(::add)
    }
    val displayTracks = canonicalTracks.ifEmpty { tracks }
    val visibleTrackIds = visibleTracks.mapTo(mutableSetOf()) {
        it.id
    }
    val selectedTabBrush = remember {
        Brush.linearGradient(
            listOf(
                PrimaryPurple.copy(alpha = .58f),
                PrimaryPurple.copy(alpha = .58f)
            )
        )
    }
    Box(if (isolateScroll) modifier.isolateLyricsVerticalScroll() else modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (song.lyricsStatus == "partial") {
                Column(Modifier.fillMaxWidth().heightIn(max = 112.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    Text("Partial lyrics", style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = TextSecondary)
                    Text(song.lyricsReason?.trim()?.takeIf(String::isNotBlank) ?: "Some lyrics are unavailable.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 2.dp)
            ) {
                if (visibleTracks.isEmpty()) {
                    Text(if (translation != null || ipa != null)
                        "Original lyrics are unavailable. You can show Translation or Pronunciation below."
                        else androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_no_lyrics_to_display), color = TextSecondary)
                } else {
                    CombinedLyricsContent(
                        tracks = displayTracks,
                        visibleTrackIds = visibleTrackIds,
                        songId = song.id,
                        positionState = positionState,
                        seekEnabled = seekEnabled,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxSize(),
                        scrollState = scrollState
                    )
                }
            }
            val filters = listOfNotNull(
                translation?.let { Triple(it, "Translation", showTranslation) },
                ipa?.let { Triple(it, "Pronunciation", showIpa) }
            )
            if (filters.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("lyrics_track_group")
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceLow)
                        .border(1.dp, PrimaryPurple.copy(alpha = .48f), RoundedCornerShape(14.dp)),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    filters.forEach { (track, label, selected) ->
                        val selectedProgress = semanticSelectedProgress(
                            selected = selected,
                            label = "lyrics-filter-${track.id}"
                        )
                        Box(
                            Modifier
                                .testTag(lyricTrackTabTag(track.id))
                                .weight(1f)
                                .fillMaxHeight()
                                .stemwiseAuraSelectable(
                                    selected = selected,
                                    role = Role.Checkbox,
                                    anchor = AuraAnchor.VisibleControl,
                                    onClick = {
                                        if (track.kind == "translation") {
                                            showTranslation = !showTranslation
                                        } else {
                                            showIpa = !showIpa
                                        }
                                    }
                                )
                                .semanticSelectedSurface(
                                    progress = selectedProgress,
                                    shape = RectangleShape,
                                    selectedBrush = selectedTabBrush,
                                    baseColor = Color.Transparent,
                                    baseOutline = Color.Transparent,
                                    selectedOutline = Color.Transparent
                                )
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = lerp(
                                    lyricTrackColor(track),
                                    lerp(lyricTrackColor(track), TextPrimary, .58f),
                                    selectedProgress
                                ),
                                fontSize = 12.sp,
                                fontWeight = if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Resolve the one authoritative ordered v2 lyric-track representation. */
internal fun resolvedLyricTracks(song: Song): List<LyricTrack> = song.lyricTracks

/** Canonical tab order is stable even when imported tracks arrive in arbitrary order. */
internal fun canonicalLyricModeTracks(tracks: List<LyricTrack>): List<LyricTrack> = buildList {
    tracks.firstOrNull { it.kind == "original" }?.let(::add)
    addAll(tracks.filter { it.kind == "translation" })
    addAll(tracks.filter { it.kind == "transliteration" && !isIpaTrack(it) })
    addAll(tracks.filter(::isIpaTrack))
}.distinctBy(LyricTrack::id)

@Composable
private fun LyricTrackHeading(track: LyricTrack) {
    Text(
        lyricTrackLongLabel(track).uppercase(Locale.ROOT),
        color = lyricTrackColor(track),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

/** Exactly aligned plain companions inherit the referenced original's safe line boundaries. */
internal fun effectiveTimedLyricsLines(
    track: LyricTrack,
    tracks: List<LyricTrack>
): List<TimedLyricLine> {
    if (track.lines.isNotEmpty()) return track.lines
    val textLines = track.text?.lines()?.takeIf { lines ->
        lines.isNotEmpty() && lines.all(String::isNotBlank)
    } ?: return emptyList()
    val original = tracks.singleOrNull { candidate ->
        candidate.id == track.basedOnTrackId &&
            candidate.kind == "original" &&
            candidate.lines.isNotEmpty()
    } ?: return emptyList()
    if (textLines.size != original.lines.size) return emptyList()
    return original.lines.zip(textLines) { line, text ->
        line.copy(text = text, words = emptyList())
    }
}

/** Keep visible rows through their shared exit, then remove the lazy items and their spacing. */
@Composable
private fun lyricTrackVisibility(tracks: List<LyricTrack>, visibleIds: Set<String>): Map<String, Transition<Boolean>> =
    tracks.associate { track ->
        track.id to key(track.id) {
            updateTransition(track.id in visibleIds, label = "lyrics-track-${track.id}")
        }
    }

@Composable
private fun CombinedLyricsContent(
    tracks: List<LyricTrack>,
    visibleTrackIds: Set<String>,
    songId: String,
    positionState: State<Long>,
    seekEnabled: Boolean,
    onSeek: (Long) -> Boolean,
    modifier: Modifier = Modifier,
    scrollState: LazyListState? = null
) {
    val motion = LocalStemwiseMotion.current
    val primary = tracks.firstOrNull { it.kind == "original" && it.lines.isNotEmpty() }
        ?: tracks.firstOrNull { it.lines.isNotEmpty() }
    if (primary == null) {
        val orderedTracks = plainLyricsDisplayOrder(tracks)
        val visibility = lyricTrackVisibility(orderedTracks, visibleTrackIds)
        LazyColumn(
            modifier = modifier.testTag("lyrics_content_scroll"),
            state = scrollState ?: rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            orderedTracks.forEach { track ->
                val transition = visibility.getValue(track.id)
                if (!transition.currentState && !transition.targetState) return@forEach
                item(key = "${track.id}:heading") {
                    transition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        ) + expandVertically(
                            motion.semanticSpec(
                                StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                spatial = true
                            )
                        ),
                        exit = fadeOut(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        ) + shrinkVertically(
                            motion.semanticSpec(
                                StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                spatial = true
                            )
                        )
                    ) { LyricTrackHeading(track) }
                }
                itemsIndexed(
                    plainLyricLines(track.text),
                    key = { index, _ -> "${track.id}:plain:$index" }
                ) { _, line ->
                    transition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        ),
                        exit = fadeOut(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        )
                    ) {
                        SelectionContainer {
                            PlainLyricLine(line, lyricTrackColor(track))
                        }
                    }
                }
            }
        }
        return
    }
    val attachedCompanions = tracks.mapNotNull { track ->
        if (track.id == primary.id || track.basedOnTrackId != primary.id) {
            return@mapNotNull null
        }
        effectiveTimedLyricsLines(track, tracks).takeIf { it.isNotEmpty() }?.let { lines ->
            track.copy(lines = lines)
        }
    }
    val attachedIds = attachedCompanions.mapTo(mutableSetOf(), LyricTrack::id)
    val trailingTracks = tracks.filter {
        it.id != primary.id &&
            it.id !in attachedIds
    }
    TimedLyricsList(
        trackKey = "$songId:${primary.id}",
        lines = primary.lines,
        companions = attachedCompanions,
        visibleCompanionIds = visibleTrackIds,
        positionState = positionState,
        seekEnabled = seekEnabled,
        onSeek = onSeek,
        modifier = modifier,
        trailingTracks = trailingTracks,
        providedListState = scrollState
    )
}

/**
 * Keep the combined plain-lyrics hierarchy predictable even when a producer emits tracks in a
 * different JSON order: original words first, then pronunciation and meaning.
 */
internal fun plainLyricsDisplayOrder(tracks: List<LyricTrack>): List<LyricTrack> =
    tracks.withIndex()
        .sortedWith(
            compareBy<IndexedValue<LyricTrack>> { (_, track) ->
                when {
                    track.kind == "original" -> 0
                    isIpaTrack(track) -> 1
                    track.kind == "translation" -> 2
                    else -> 3
                }
            }.thenBy(IndexedValue<LyricTrack>::index)
        )
        .map(IndexedValue<LyricTrack>::value)

@Composable
private fun TimedLyricsList(
    trackKey: String,
    lines: List<TimedLyricLine>,
    companions: List<LyricTrack>,
    visibleCompanionIds: Set<String>,
    positionState: State<Long>,
    seekEnabled: Boolean,
    onSeek: (Long) -> Boolean,
    modifier: Modifier = Modifier,
    trailingTracks: List<LyricTrack> = emptyList(),
    providedListState: LazyListState? = null
) {
    val trailingVisibility = lyricTrackVisibility(trailingTracks, visibleCompanionIds)
    val timingIndex = remember(lines) { LyricLineTimingIndex(lines) }
    val activeIndex by remember(timingIndex, positionState) {
        derivedStateOf { timingIndex.activeAt(positionState.value) }
    }
    val listState = providedListState ?: rememberLazyListState()
    val scope = rememberCoroutineScope()
    val controller = remember(listState, scope) {
        LyricsViewportController(listState = listState, scope = scope)
    }
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var touchExplorationEnabled by remember(accessibilityManager) {
        mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true)
    }
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val layoutSignature = remember(companions, trailingTracks, visibleCompanionIds) {
        Triple(companions, trailingTracks, visibleCompanionIds)
    }
    var previousLayoutSignature by remember(controller) { mutableStateOf(layoutSignature) }
    var previousViewportSize by remember(controller) { mutableStateOf(IntSize.Zero) }
    var previousDensity by remember(controller) {
        mutableStateOf(density.density to density.fontScale)
    }
    val motion = LocalStemwiseMotion.current
    SideEffect {
        controller.updateEnvironment(
            touchExploration = touchExplorationEnabled,
            animationsEnabled = motion.enabled,
            lifecycleStarted = lifecycleStarted
        )
    }
    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            touchExplorationEnabled = enabled
        }
        accessibilityManager?.addTouchExplorationStateChangeListener(listener)
        onDispose {
            accessibilityManager?.removeTouchExplorationStateChangeListener(listener)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleStarted = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(trackKey, lines) {
        controller.onLyricsLoaded(trackKey, lines, positionState.value)
    }
    LaunchedEffect(positionState, controller) {
        snapshotFlow { positionState.value }
            .distinctUntilChanged()
            .collect(controller::onPlaybackPosition)
    }
    LaunchedEffect(listState, controller) {
        listState.interactionSource.interactions.collect(controller::onListInteraction)
    }
    LaunchedEffect(listState, controller, activeIndex) {
        snapshotFlow { controller.captureSnapshot(activeIndex) }
            .conflate()
            .distinctUntilChanged()
            .collect(controller::onViewportSnapshot)
    }
    LaunchedEffect(layoutSignature) {
        if (previousLayoutSignature != layoutSignature) {
            previousLayoutSignature = layoutSignature
            controller.onLayoutChanged()
        }
    }
    LaunchedEffect(density.density, density.fontScale) {
        val current = density.density to density.fontScale
        if (previousDensity != current) {
            previousDensity = current
            controller.onLayoutChanged()
        }
    }
    DisposableEffect(controller) {
        controller.onScreenVisibilityChanged(true)
        onDispose { controller.onScreenVisibilityChanged(false) }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val topPadding = 12.dp
        val bottomPadding = maxHeight * .58f
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("lyrics_content_scroll")
                .observeLyricsViewport(controller)
                .onSizeChanged { size ->
                    if (previousViewportSize != IntSize.Zero && previousViewportSize != size) {
                        controller.onLayoutChanged()
                    }
                    previousViewportSize = size
                },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
        ) {
            itemsIndexed(lines, key = { index, line -> "${line.startMs}:$index" }) {
                    index, line ->
                val active = index == activeIndex
                val activeProgress by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = motion.semanticSpec(StemwiseMotionEvent.LYRICS_ACTIVE_LINE),
                    label = "lyric-line-$index"
                )
                val interactionSource = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .animateItem(
                            fadeInSpec = motion.semanticSpec(
                                StemwiseMotionEvent.PANEL_CONTENT_REFLOW
                            ),
                            fadeOutSpec = motion.semanticSpec(
                                StemwiseMotionEvent.PANEL_CONTENT_REFLOW
                            ),
                            placementSpec = motion.semanticSpec(
                                StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                spatial = true
                            )
                        )
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        // stemwise-aura-exclusion: lyric seeking intentionally has no tap motion.
                        .clickable(
                            enabled = seekEnabled && line.supportedSeekMs != null,
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            if (line.supportedSeekMs?.let(onSeek) == true) {
                                controller.onLyricTapRequested(timingIndex.activeAt(positionState.value))
                            }
                        }
                        .semantics {
                            contentDescription = if (active) {
                                "Current lyric: ${line.text}"
                            } else if (line.supportedSeekMs == null) {
                                "${line.timingLabel ?: "Lyrics"}: ${line.text}"
                            } else {
                                "Seek to lyric: ${line.text}"
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(42.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PrimaryPurple.copy(alpha = .72f * activeProgress))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    line.timingLabel?.let { label ->
                        Text(label, color = TextSecondary, fontSize = 12.sp,
                            fontFamily = StemwiseSystemFontFamily)
                    }
                    Text(
                        line.text,
                        color = lerp(
                            TextSecondary,
                            TextPrimary,
                            activeProgress
                        ),
                        fontSize = 26.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = StemwiseSystemFontFamily
                    )
                    companions.filter(::isIpaTrack).forEach { companion ->
                        companionTextForPrimaryLine(
                            track = companion,
                            primaryIndex = index
                        )?.let { text ->
                            AnimatedVisibility(
                                visible = companion.id in visibleCompanionIds,
                                enter = fadeIn(
                                    motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                                ) + expandVertically(
                                    motion.semanticSpec(
                                        StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                        spatial = true
                                    )
                                ),
                                exit = fadeOut(
                                    motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                                ) + shrinkVertically(
                                    motion.semanticSpec(
                                        StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                        spatial = true
                                    )
                                )
                            ) {
                                Text(
                                    text,
                                    color = lerp(TextSecondary, lyricTrackColor(companion), activeProgress),
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    fontFamily = StemwiseSystemFontFamily
                                )
                            }
                        }
                    }
                    companions.filterNot(::isIpaTrack).forEach { companion ->
                        companionTextForPrimaryLine(
                            track = companion,
                            primaryIndex = index
                        )?.let { text ->
                            AnimatedVisibility(
                                visible = companion.id in visibleCompanionIds,
                                enter = fadeIn(
                                    motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                                ) + expandVertically(
                                    motion.semanticSpec(
                                        StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                        spatial = true
                                    )
                                ),
                                exit = fadeOut(
                                    motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                                ) + shrinkVertically(
                                    motion.semanticSpec(
                                        StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                        spatial = true
                                    )
                                )
                            ) {
                                Text(
                                    text,
                                    color = lerp(TextSecondary, lyricTrackColor(companion), activeProgress),
                                    fontSize = 18.sp,
                                    lineHeight = 26.sp,
                                    fontFamily = StemwiseSystemFontFamily
                                )
                            }
                        }
                    }
                    }
                }
            }
            trailingTracks.forEach { track ->
                val transition = trailingVisibility.getValue(track.id)
                if (!transition.currentState && !transition.targetState) return@forEach
                item(key = "${track.id}:heading") {
                    transition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        ),
                        exit = fadeOut(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        )
                    ) {
                        LyricTrackHeading(track)
                    }
                }
                itemsIndexed(
                    plainLyricLines(track.text),
                    key = { index, _ -> "${track.id}:plain:$index" }
                ) { _, line ->
                    transition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        ),
                        exit = fadeOut(
                            motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                        )
                    ) {
                        SelectionContainer {
                            PlainLyricLine(line, lyricTrackColor(track))
                        }
                    }
                }
            }
        }
        LyricsViewportDebugOverlay(controller, Modifier.fillMaxSize())
    }
}

internal fun companionTextForPrimaryLine(
    track: LyricTrack,
    primaryIndex: Int
): String? = track.lines.getOrNull(primaryIndex)?.text

internal fun lyricTrackTabTag(trackId: String): String = "lyrics_track_tab_$trackId"

private fun lyricTrackLongLabel(track: LyricTrack): String {
    val language = displayLanguageName(track.language)
    return when (track.kind) {
        "original" -> listOfNotNull("Original", language).joinToString(" · ")
        "translation" -> listOfNotNull("Translation", language).joinToString(" · ")
        "transliteration" -> listOfNotNull(
            if (isIpaTrack(track)) "IPA" else "Transliteration",
            language
        ).joinToString(" · ")
        else -> language ?: "Lyrics"
    }
}

private fun lyricTrackColor(track: LyricTrack): Color = when (track.kind) {
    "translation" -> Violet
    "transliteration" -> PrimaryPurple
    else -> TextPrimary
}

private fun isIpaTrack(track: LyricTrack): Boolean = track.kind == "transliteration" && listOfNotNull(
    track.language,
    track.id
).any { value ->
    val normalized = value.lowercase(Locale.ROOT)
    normalized.split(Regex("[^a-z]+")) .any { it == "ipa" || it == "fonipa" || it == "phonetic" }
}

private fun plainLyricLines(text: String?): List<String> =
    text.orEmpty().lineSequence().map { line -> line.ifEmpty { " " } }.toList()

@Composable
private fun PlainLyricLine(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 24.sp,
        lineHeight = 34.sp,
        textAlign = TextAlign.Start,
        fontFamily = StemwiseSystemFontFamily,
        modifier = Modifier.fillMaxWidth()
    )
}
