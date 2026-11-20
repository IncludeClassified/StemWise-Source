package app.stemwise.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.stemwise.ui.design.StemwiseTextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.res.pluralStringResource
import app.stemwise.R
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import app.stemwise.model.isFlac
import app.stemwise.model.asClock
import app.stemwise.model.asHoursMinutes
import app.stemwise.ui.theme.Ink
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.SignalRed
import app.stemwise.ui.theme.PrimaryBright
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.OutlineStrong
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.SurfaceLow
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.theme.StemwiseSystemFontFamily
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.DragAuraState
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.design.StemwiseIconButton
import app.stemwise.ui.design.StemwiseRadii
import app.stemwise.ui.design.StemwiseSpacing
import app.stemwise.ui.design.stemwiseAuraClickable
import app.stemwise.ui.design.stemwiseAuraClickableAction
import app.stemwise.ui.design.stemwiseAuraClassifiedTapOrLongPressAction
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.design.semanticSpec
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

internal const val LIBRARY_SEARCH_CANVAS_MARKER_TAG = "library-search-canvas-marker"
internal const val LIBRARY_TRAILING_STATUS_OWNER_WIDTH_DP = 82f
internal const val LIBRARY_TRAILING_STATUS_END_INSET_DP = 18f
internal const val LIBRARY_HERO_ACTION_ICON_START_INSET_DP = 24f
internal const val LIBRARY_HERO_ACTION_ICON_SIZE_DP = 24f
internal const val LIBRARY_HERO_ACTION_ICON_LABEL_GAP_DP = 10f
internal const val LIBRARY_SELECTION_HANDOFF_MS = 180L

internal data class LibrarySearchFieldPresentation(
    val borderWidthDp: Float,
    val borderColor: Color,
    val focusedShadow: Boolean
)

internal fun librarySearchFieldPresentation(
    focused: Boolean
): LibrarySearchFieldPresentation = if (focused) {
    LibrarySearchFieldPresentation(1.5f, PrimaryPurple, focusedShadow = true)
} else {
    LibrarySearchFieldPresentation(1f, OutlineStrong, focusedShadow = false)
}

internal fun stemwiseContainsArabicScript(text: String): Boolean =
    text.codePoints().anyMatch { codePoint ->
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.ARABIC
    }

internal data class LibrarySearchRowGeometryDp(
    val minimumHeightDp: Float,
    val pitchDp: Float
)

internal fun librarySearchRowGeometryDp(
    fontScale: Float,
    query: String,
    lineCount: Int
): LibrarySearchRowGeometryDp {
    require(fontScale > 0f)
    require(lineCount >= 1)
    val searching = query.isNotBlank()
    val minimumHeight = when {
        fontScale >= 2f && searching -> (108 + lineCount * 18).toFloat()
        fontScale >= 2f -> 112f
        fontScale >= 1.3f && searching -> (72 + lineCount * 14).toFloat()
        fontScale >= 1.3f -> 84f
        searching && stemwiseContainsArabicScript(query) -> 82f
        searching -> 76f
        else -> 68f
    }
    val pitch = if (
        fontScale < 1.3f && searching && stemwiseContainsArabicScript(query)
    ) 86f else minimumHeight
    return LibrarySearchRowGeometryDp(minimumHeightDp = minimumHeight, pitchDp = pitch)
}

internal enum class StemwiseSearchHighlightMode {
    NONE,
    MATCHED_LETTERS
}

internal data class StemwiseSearchHighlightPresentation(
    val ranges: List<StemwiseSearchRange>,
    val mode: StemwiseSearchHighlightMode
)

internal fun stemwiseSearchHighlightPresentation(
    text: String,
    query: String,
    explicitRanges: List<StemwiseSearchRange>? = null
): StemwiseSearchHighlightPresentation {
    val ranges = (explicitRanges ?: stemwiseSearchRanges(text, query)).filter { range ->
        range.start < text.length && range.endExclusive <= text.length
    }
    val mode = if (ranges.isEmpty()) StemwiseSearchHighlightMode.NONE
        else StemwiseSearchHighlightMode.MATCHED_LETTERS
    return StemwiseSearchHighlightPresentation(ranges, mode)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    songs: List<Song>,
    onSongClick: (Song) -> Boolean,
    onSongManage: (Song) -> Unit = {},
    onOpenActivity: () -> Unit = {},
    onSongPrepareParts: ((Song) -> Unit)? = null,
    onSongDelete: ((Song) -> Unit)? = null,
    onSongDeleteStems: ((Song) -> Unit)? = null,
    songSettingsNeedReset: (Song) -> Boolean = { false },
    onResetSongSettings: ((Song) -> Unit)? = null,
    currentSongId: String? = null,
    currentSongPlaying: Boolean = false,
    onSetupAction: () -> Unit = {},
    playlistName: String,
    playlistConfigured: Boolean = true,
    syncing: Boolean,
    syncError: String?,
    waitingForNetwork: Boolean = false,
    onPlayAll: () -> Unit = {},
    onShuffle: () -> Unit = {},
    searchBackEnabled: Boolean = true,
    onOpeningArtworkSettled: () -> Unit = {},
    ambientMotionEnabled: Boolean = true
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val selectionScope = rememberCoroutineScope()
    var selectionHandoffJob by remember { mutableStateOf<Job?>(null) }
    var selectionFeedbackSongId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val motion = LocalStemwiseMotion.current
    val artworkAuraMotionEnabled by remember(ambientMotionEnabled) {
        derivedStateOf { ambientMotionEnabled && !listState.isScrollInProgress }
    }
    val songSnapshot = remember(songs) { songs.toList() }
    val normalizedQuery = remember(query) { librarySearchQueryKey(query) }
    val searchIndexRequested = searchFocused || normalizedQuery != null
    val searchPreparation = remember(selectionScope) { LibrarySearchPreparationOwner(selectionScope) }
    val songSearchResult by searchPreparation.result.collectAsStateWithLifecycle()
    LaunchedEffect(songSnapshot, normalizedQuery, searchIndexRequested) {
        searchPreparation.request(songSnapshot, normalizedQuery, searchIndexRequested)
    }
    val visibleSongs = libraryVisibleSearchSongs(
        snapshot = songSnapshot,
        query = normalizedQuery,
        result = songSearchResult
    )
    val searchPreparing = normalizedQuery != null &&
        (songSearchResult?.snapshot !== songSnapshot ||
            songSearchResult?.query != normalizedQuery)
    var anchorSongId by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorQuery by rememberSaveable { mutableStateOf("") }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var restoreAnchor by remember { mutableStateOf(true) }
    LaunchedEffect(songSnapshot, searchPreparing, normalizedQuery, syncing, playlistConfigured) {
        if (restoreAnchor && !searchPreparing && (songSnapshot.isNotEmpty() || !syncing)) {
            if (anchorQuery == query) {
                val songIndex = visibleSongs.indexOfFirst { it.id == anchorSongId }
                if (songIndex >= 0) {
                    // Search, sync status, hero, and results heading precede every song.
                    val headerCount = 4
                    listState.scrollToItem(headerCount + songIndex, anchorOffset)
                }
            }
            restoreAnchor = false
        }
    }
    LaunchedEffect(listState, visibleSongs, query, searchPreparing, restoreAnchor) {
        if (restoreAnchor || searchPreparing) return@LaunchedEffect
        val songIds = visibleSongs.mapTo(HashSet()) { it.id }
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
                ?.let { it.key to listState.firstVisibleItemScrollOffset }
        }.distinctUntilChanged().collect { item ->
            if (item != null) {
                anchorSongId = (item.first as? String)?.takeIf(songIds::contains)
                anchorOffset = item.second.coerceAtLeast(0)
                anchorQuery = query
            }
        }
    }
    var menuSongId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(songSnapshot, menuSongId) {
        if (menuSongId != null && songSnapshot.none { it.id == menuSongId }) menuSongId = null
    }
    fun performMenuAction(expectedSongId: String, action: (Song) -> Unit) {
        if (menuSongId != expectedSongId) return
        val target = songSnapshot.firstOrNull { it.id == expectedSongId }
        menuSongId = null
        target?.let(action)
    }
    val totalDuration = remember(songSnapshot) {
        songSnapshot.sumOf { it.durationMs.coerceAtLeast(0L) }
    }
    val labelMediumStyle = MaterialTheme.typography.labelMedium
    val durationTextStyle = remember(labelMediumStyle) {
        labelMediumStyle.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")
    }
    val hasSongs = songSnapshot.isNotEmpty()
    val openingContentView = LocalView.current
    var openingContentReported by remember(songSnapshot) { mutableStateOf(false) }
    fun reportOpeningContentReady() {
        if (openingContentReported) return
        openingContentReported = true
        onOpeningArtworkSettled()
    }
    val openingHeroOutsideViewport by remember(listState, songSnapshot) {
        derivedStateOf {
            // A restored scroll position need not compose the hero. Wait for measurement,
            // then stop observing scroll once this opening has reported readiness.
            !openingContentReported && listState.layoutInfo.visibleItemsInfo.let { items ->
                items.isNotEmpty() && items.none { it.key == "playlist-hero" }
            }
        }
    }
    LaunchedEffect(
        hasSongs,
        openingContentReported,
        query.isNotBlank(),
        openingHeroOutsideViewport
    ) {
        if (openingContentReported) return@LaunchedEffect
        if (!hasSongs || query.isNotBlank() || openingHeroOutsideViewport) {
            reportOpeningContentReady()
        } else {
            // Compose can defer an unchanged-size subtree's pending measure to dispatchDraw.
            // The platform splash blocks that draw until the lazy hero is ready, so request
            // an Android layout pass to measure it before releasing the splash.
            openingContentView.requestLayout()
        }
    }
    val showNoSearchResults = hasSongs && query.isNotBlank() &&
        !searchPreparing && visibleSongs.isEmpty()
    BackHandler(enabled = searchBackEnabled && (searchFocused || query.isNotBlank())) {
        // Search owns the first Back while it has focus or a filter. Close that transient state
        // before Android is allowed to leave the app; the expanded player disables this handler
        // through [searchBackEnabled] so its own Back action remains authoritative.
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        query = ""
        searchFocused = false
    }
    var searchHadText by remember { mutableStateOf(query.isNotBlank()) }
    LaunchedEffect(query.isBlank()) {
        val searchWasCleared = librarySearchWasCleared(searchHadText, query)
        searchHadText = query.isNotBlank()
        if (searchWasCleared) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
            searchFocused = false
            listState.animateScrollToItem(0)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val cappedContentWidth = minOf(maxWidth, 520.dp)
    val horizontalMargin = 12.dp
    val innerContentWidth = cappedContentWidth - horizontalMargin * 2
    // The playlist cover follows the real page viewport, not only the screen width. The mini
    // player, navigation, or a smaller window can therefore reclaim vertical space without
    // leaving a nearly full-width cover pinned at its original size.
    val responsiveCoverSize = minOf(
        innerContentWidth,
        maxHeight * .42f,
        if (cappedContentWidth >= 480.dp) 296.dp else 196.dp
    ).coerceAtLeast(180.dp)
    val libraryScrollEnabled by remember {
        derivedStateOf {
            libraryScrollRangeAvailable(
                canScrollBackward = listState.canScrollBackward,
                canScrollForward = listState.canScrollForward
            )
        }
    }
    Column(
        Modifier
            .width(cappedContentWidth)
            .fillMaxSize()
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .imePadding()
    ) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            // Name the actual scroll owner, not its page parent. This keeps direct indexed
            // accessibility/test scrolling valid while the search field remains pinned above it.
            .semantics { contentDescription = "Song library" },
        state = listState,
        userScrollEnabled = libraryScrollEnabled,
        contentPadding = PaddingValues(
            start = 0.dp,
            end = 0.dp,
            top = if (hasSongs) 8.dp else 24.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        stickyHeader(key = "library-search", contentType = "search") {
            Column(Modifier.fillMaxWidth().background(Ink).padding(horizontal = horizontalMargin)) {
                PlaylistSearch(query = query, focused = searchFocused, enabled = true,
                    onQueryChange = { query = it }, onFocusChange = { searchFocused = it })
            }
        }
        if (!playlistConfigured && !hasSongs) item {
            Column(
                Modifier.padding(horizontal = horizontalMargin).animateItem(
                    fadeInSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_INSERT),
                    fadeOutSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_REMOVE),
                    placementSpec = motion.semanticSpec(
                        StemwiseMotionEvent.LIST_REORDER,
                        spatial = true
                    )
                )
            ) {
                SetupProgressStrip()
                Spacer(Modifier.height(16.dp))
            }
        }
        if (hasSongs) item(key = "playlist-sync-state") {
            AnimatedContent(
                targetState = Triple(syncing, waitingForNetwork, syncError),
                modifier = Modifier.padding(horizontal = horizontalMargin).animateItem(
                    fadeInSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_INSERT),
                    fadeOutSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_REMOVE),
                    placementSpec = motion.semanticSpec(
                        StemwiseMotionEvent.LIST_REORDER,
                        spatial = true
                    )
                ),
                transitionSpec = {
                    fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)).togetherWith(
                        fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    )
                },
                label = "library-sync-state"
            ) { (isSyncing, isWaiting, error) ->
                if (isSyncing || isWaiting || error != null) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(
                                horizontal = StemwiseSpacing.xs,
                                vertical = StemwiseSpacing.xs
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color = PrimaryPurple,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    if (isWaiting) StemwiseIcons.Download else StemwiseIcons.Error,
                                    contentDescription = null,
                                    tint = if (isWaiting) TextSecondary else Coral,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                when {
                                    isWaiting -> WAITING_FOR_UNMETERED_NETWORK
                                    isSyncing -> "Syncing playlist"
                                    else -> "Could not refresh the library."
                                },
                                color = if (isSyncing || isWaiting) TextSecondary else Coral,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = StemwiseSpacing.xs),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isSyncing) StemwiseTextButton(onClick = onSetupAction) { Text("Review settings") }
                        Spacer(Modifier.height(StemwiseSpacing.xs))
                    }
                } else {
                    Spacer(Modifier.height(0.dp))
                }
            }
        }
        if (hasSongs) item(key = "playlist-hero", contentType = "hero") {
            AnimatedVisibility(
                visible = query.isBlank(),
                modifier = Modifier.padding(horizontal = horizontalMargin),
                enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)) +
                    expandVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW, spatial = true)),
                exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)) +
                    shrinkVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW, spatial = true))
            ) {
                Column {
                    PlaylistHero(
                        playlistName = if (playlistConfigured) playlistName else "Local Library",
                        songs = songs,
                        totalDuration = totalDuration,
                        coverSize = responsiveCoverSize,
                        onPlayAll = onPlayAll,
                        onShuffle = onShuffle,
                        onOpeningArtworkSettled = ::reportOpeningContentReady,
                        artworkAuraMotionEnabled = artworkAuraMotionEnabled
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (hasSongs) {
            item(key = "search-results-heading", contentType = "heading") {
                AnimatedVisibility(
                    visible = query.isNotBlank() && !searchPreparing,
                    enter = fadeIn(
                        motion.semanticSpec(StemwiseMotionEvent.SEARCH_RESULTS_CHANGE)
                    ) + expandVertically(
                        motion.semanticSpec(
                            StemwiseMotionEvent.SEARCH_RESULTS_CHANGE,
                            spatial = true
                        )
                    ),
                    exit = fadeOut(
                        motion.semanticSpec(StemwiseMotionEvent.SEARCH_RESULTS_CHANGE)
                    ) + shrinkVertically(
                        motion.semanticSpec(
                            StemwiseMotionEvent.SEARCH_RESULTS_CHANGE,
                            spatial = true
                        )
                    )
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = horizontalMargin),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_search_results),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 22.sp,
                                lineHeight = 28.sp
                            ),
                            modifier = Modifier.semantics { heading() }
                        )
                        AnimatedContent(
                            targetState = visibleSongs.size,
                            transitionSpec = {
                                fadeIn(
                                    motion.semanticSpec(StemwiseMotionEvent.SEARCH_RESULTS_CHANGE)
                                ).togetherWith(
                                    fadeOut(
                                        motion.semanticSpec(
                                            StemwiseMotionEvent.SEARCH_RESULTS_CHANGE
                                        )
                                    )
                                )
                            },
                            label = "library-search-result-count"
                        ) { resultCount ->
                            Text(
                                "$resultCount ${if (resultCount == 1) "song" else "songs"}",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                ),
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                }
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }

        if (hasSongs) {
            itemsIndexed(
                items = visibleSongs,
                key = { _, song -> song.id },
                contentType = { _, _ -> "song" }
            ) { index, song ->
                val current = song.id == currentSongId
                val presentation = remember(song) { librarySongRowPresentation(song) }
                PlaylistSongRow(
                    song = song,
                    presentation = presentation,
                    current = current,
                    selectionFeedback = selectionFeedbackSongId == song.id,
                    playing = current && currentSongPlaying,
                    query = query,
                    searchContext = remember(song, query) { librarySearchContext(song, query) },
                    durationTextStyle = durationTextStyle,
                    phaseOrdinal = LIBRARY_ROW_PHASE_BASE + index,
                    artworkAuraMotionEnabled = artworkAuraMotionEnabled,
                    onPlay = {
                        val accepted = onSongClick(song)
                        if (accepted) {
                            selectionHandoffJob?.cancel()
                            selectionFeedbackSongId = song.id
                            keyboard?.hide()
                            if (searchFocused) focusManager.clearFocus(force = true)
                            selectionHandoffJob = selectionScope.launch {
                                delay(LIBRARY_SELECTION_HANDOFF_MS)
                                if (selectionFeedbackSongId == song.id) {
                                    selectionFeedbackSongId = null
                                }
                            }
                        }
                        accepted
                    },
                    onManage = { menuSongId = song.id },
                    menuExpanded = menuSongId == song.id,
                    onMenuDismiss = { menuSongId = null },
                    onDetails = { performMenuAction(song.id, onSongManage) },
                    onResetSettings = onResetSongSettings?.takeIf { songSettingsNeedReset(song) }
                        ?.let { action -> { performMenuAction(song.id, action) } },
                    onViewIssue = { songSnapshot.firstOrNull { it.id == song.id }?.let(onSongManage) },
                    onPrepareParts = onSongPrepareParts?.let { action -> { performMenuAction(song.id, action) } },
                    onDeleteSong = onSongDelete?.let { action -> { performMenuAction(song.id, action) } },
                    onDeleteStems = onSongDeleteStems?.takeIf { song.hasStems || song.hasSourceParts }
                        ?.let { action -> { performMenuAction(song.id, action) } },
                    // The unfiltered Library is a stable, keyed list. Attaching appearance and
                    // placement animation state to every newly materialized row during a fling
                    // creates work without representing any data change. Keep item motion only
                    // while search is actively inserting/removing/repositioning results.
                    modifier = if (query.isBlank()) {
                        Modifier
                    } else {
                        Modifier
                            .animateItem(
                            fadeInSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_INSERT),
                            fadeOutSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_REMOVE),
                            placementSpec = motion.semanticSpec(
                                StemwiseMotionEvent.LIST_REORDER,
                                spatial = true
                            )
                            )
                    }
                )
            }
        }

        if (showNoSearchResults) item(key = "no-search-results", contentType = "empty") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = horizontalMargin, vertical = 32.dp)
                    .testTag(LIBRARY_SEARCH_CANVAS_MARKER_TAG)
                    .semantics { contentDescription = "No songs match this search." },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(StemwiseIcons.Search, contentDescription = null,
                    tint = OutlineStrong, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No songs match this search.", color = TextPrimary,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                StemwiseTextButton(onClick = { query = "" }) { Text("Clear search") }
            }
        }

        if (!hasSongs) {
            item(contentType = "empty") {
                AnimatedContent(
                    targetState = Triple(syncing, waitingForNetwork, syncError),
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)).togetherWith(
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        )
                    },
                    label = "library-empty-state"
                ) { (isSyncing, isWaiting, error) ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                Modifier.size(28.dp),
                                color = PrimaryPurple,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                StemwiseIcons.QueueMusic,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                tint = TextSecondary
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                isWaiting -> WAITING_FOR_UNMETERED_NETWORK
                                isSyncing -> "Reading your library"
                                error != null -> "Could not refresh the library"
                                else -> "Your library is empty."
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                isWaiting -> "Playlist refresh resumes when the configured network is available."
                                isSyncing -> "This can take a moment on first launch."
                                error != null -> "Review your connection and playlist settings, then try again."
                                else -> "Import a song ZIP or send a song to your PC from Activity."
                            },
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        if (!isSyncing) StemwiseButton(
                            onClick = if (isWaiting || error != null) onSetupAction else onOpenActivity,
                            shape = StemwiseRadii.card
                        ) { Text(if (isWaiting || error != null) "Review settings" else "Open Activity") }
                    }
                }
            }
        }
    }
    }
    }
}

internal fun libraryScrollRangeAvailable(
    canScrollBackward: Boolean,
    canScrollForward: Boolean
): Boolean = canScrollBackward || canScrollForward

internal enum class LibraryHeroAction(val label: String) {
    SHUFFLE("Shuffle"),
    PLAY("Play")
}

internal fun libraryHeroActionOrder(): List<LibraryHeroAction> =
    listOf(LibraryHeroAction.SHUFFLE, LibraryHeroAction.PLAY)

@Composable
private fun SetupProgressStrip() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription =
                    "Setup progress: Folder complete, Import current, Selected parts optional"
            }
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(StemwisePalette.preparationStepFill),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    StemwiseIcons.Selected,
                    contentDescription = null,
                    tint = PrimaryPurple,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box(
                Modifier.weight(1f).height(1.dp)
                    .background(StemwisePalette.preparationStepConnector)
            )
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(PrimaryPurple),
                contentAlignment = Alignment.Center
            ) { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_2), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Box(Modifier.weight(1f).height(1.dp).background(Outline))
            Box(
                Modifier.size(24.dp).clip(CircleShape).border(1.dp, Outline, CircleShape),
                contentAlignment = Alignment.Center
            ) { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_3), color = TextSecondary, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_folder),
                modifier = Modifier.weight(1f).stemwiseMarquee(),
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                textAlign = TextAlign.Start
            )
            Text(
                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_import),
                modifier = Modifier.weight(1f).stemwiseMarquee(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Text(
                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_stems),
                modifier = Modifier.weight(1f).stemwiseMarquee(),
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun PlaylistHero(
    playlistName: String,
    songs: List<Song>,
    totalDuration: Long,
    coverSize: androidx.compose.ui.unit.Dp,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onOpeningArtworkSettled: () -> Unit,
    artworkAuraMotionEnabled: Boolean
) {
    val songCountLabel = pluralStringResource(R.plurals.song_count, songs.size, songs.size)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
            PlaylistCover(
                coverSize,
                onSettled = onOpeningArtworkSettled,
                artworkAuraMotionEnabled = artworkAuraMotionEnabled
            )
            Spacer(Modifier.height(StemwiseSpacing.xs))
            Text(
                playlistName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 25.sp,
                    lineHeight = 30.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(songCountLabel)
                    if (songs.isNotEmpty()) {
                        val durationLabel = if (totalDuration < 60_000L) {
                            totalDuration.asClock()
                        } else {
                            totalDuration.asHoursMinutes()
                        }
                        append(" · $durationLabel")
                    }
                },
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 1,
                modifier = Modifier.stemwiseMarquee(artworkAuraMotionEnabled),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            val actions: @Composable (Modifier) -> Unit = { actionModifier ->
                libraryHeroActionOrder().forEach { action ->
                    PlaylistAction(
                        label = action.label,
                        icon = when (action) {
                            LibraryHeroAction.SHUFFLE -> StemwiseIcons.Shuffle
                            LibraryHeroAction.PLAY -> StemwiseIcons.Play
                        },
                        primary = action == LibraryHeroAction.PLAY,
                        enabled = songs.isNotEmpty(),
                        interactive = songs.isNotEmpty(),
                        modifier = actionModifier,
                        onClick = when (action) {
                            LibraryHeroAction.SHUFFLE -> onShuffle
                            LibraryHeroAction.PLAY -> onPlayAll
                        }
                    )
                }
            }
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    actions(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    actions(Modifier.weight(1f))
                }
            }
    }
}

@Immutable
internal data class LibrarySongRowPresentation(
    val detail: String,
    val artistLine: String,
    val albumLine: String,
    val duration: String,
    val baseAccessibilityDescription: String
)

internal fun librarySongRowPresentation(song: Song): LibrarySongRowPresentation {
    val duration = song.durationMs.asClock()
    val accessibilityDescription = buildList {
        // Keep the selectable row's stable identity concise. Album remains a distinct visible
        // search line, but is not part of the row action label used to select the song.
        listOf(song.title, song.artist, song.album)
            .filterTo(this) { value -> value.isNotBlank() }
        if (song.isFlac) add("FLAC")
        add(duration)
    }.joinToString(", ")
    return LibrarySongRowPresentation(
        // The populated list owns one secondary line. Search owns the accepted distinct Artist
        // and `Album · value` lines so matching never has to highlight a collapsed composite.
        detail = song.artist.ifBlank { song.album },
        artistLine = song.artist,
        albumLine = song.album.takeIf(String::isNotBlank)?.let { "Album · $it" }.orEmpty(),
        duration = duration,
        baseAccessibilityDescription = accessibilityDescription
    )
}

@Composable
private fun PlaylistCover(
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onSettled: () -> Unit = {},
    artworkAuraMotionEnabled: Boolean = true
) {
    val policy = remember { libraryHeroArtworkPolicy() }
    val artworkPainter = painterResource(policy.drawableRes)
    LaunchedEffect(Unit) {
        // painterResource decoded the bundled hero during composition. Releasing now lets the
        // first permitted pre-draw contain the complete artwork instead of deadlocking on an
        // asynchronous callback behind that same pre-draw gate.
        onSettled()
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { clip = false },
        contentAlignment = Alignment.Center
    ) {
        ArtworkAuraEndpointCrossfadeLayer(
            previousPalette = policy.fallbackPalette,
            targetPalette = policy.fallbackPalette,
            transitionProgress = 1f,
            compact = false,
            phaseOrdinal = LIBRARY_HERO_PHASE_ORDINAL,
            cornerRadius = 18.dp,
            ambientMotionEnabled = artworkAuraMotionEnabled,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            policy.fallbackPalette.primary.copy(alpha = .28f),
                            policy.fallbackPalette.tertiary.copy(alpha = .52f),
                            Surface
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                StemwiseIcons.MissingArtwork,
                contentDescription = null,
                modifier = Modifier.size(54.dp),
                tint = StemwisePalette.artworkPlaceholderHighlight
            )
            Image(
                painter = artworkPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

internal const val LIBRARY_HERO_ARTWORK_SHA256 =
    "630e9da44670f94ff3653f0b4db734b6f38f8ac403f1e5f56ece970cd10cd48a"
internal const val LIBRARY_HERO_PHASE_ORDINAL = 0
internal const val LIBRARY_ROW_PHASE_BASE = 16

@Immutable
internal data class LibraryHeroArtworkPolicy(
    val drawableRes: Int,
    val ownerKey: String,
    val fallbackPalette: ArtworkAuraPalette
)

internal fun libraryHeroArtworkPolicy(): LibraryHeroArtworkPolicy = LibraryHeroArtworkPolicy(
    drawableRes = R.drawable.stemwise_library_artwork,
    ownerKey = "library-hero:$LIBRARY_HERO_ARTWORK_SHA256",
    // This asset is immutable and SHA-bound above. Keeping its sampled edge colors here avoids a
    // second 1,254px decode on every cold launch while preserving artwork-owned aura color.
    fallbackPalette = ArtworkAuraPalette(
        primary = Color(0xFF32158C),
        secondary = Color(0xFF2F1D8C),
        tertiary = Color(0xFF29168C)
    )
)

@Composable
private fun PlaylistAction(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    interactive: Boolean = enabled,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember(label) { MutableInteractionSource() }
    val motion = LocalStemwiseMotion.current
    val iconForeground = when {
        !enabled -> StemwisePalette.disabledLibraryActionContent
        primary -> PrimaryBright
        else -> TextPrimary
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier
                .heightIn(min = 52.dp)
                // The visual fade never changes target geometry. Preserve interaction until the
                // shared visibility boundary withdraws the entire hero semantics subtree.
                .stemWiseInteractionAura(
                    enabled = enabled && interactive,
                    selected = false,
                    interactionSource = interactionSource,
                    anchor = AuraAnchor.VisibleControl,
                    reducedMotion = !motion.enabled,
                )
                .stemwiseAuraClickableAction(
                    interactionSource = interactionSource,
                    enabled = enabled && interactive,
                    role = Role.Button,
                    onClick = onClick
                )
                .background(
                    color = when {
                        !enabled -> StemwisePalette.disabledLibraryActionFill
                        primary -> StemwisePalette.activeControlFill
                        else -> SurfaceLow
                    },
                    shape = StemwiseRadii.card
                )
                .border(
                    if (primary) 1.5.dp else 1.dp,
                    when {
                        !enabled -> StemwisePalette.disabledLibraryActionOutline
                        primary -> PrimaryPurple
                        else -> Outline
                    },
                    StemwiseRadii.card
                )
                .padding(start = LIBRARY_HERO_ACTION_ICON_START_INSET_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                Modifier
                    .size(LIBRARY_HERO_ACTION_ICON_SIZE_DP.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(LIBRARY_HERO_ACTION_ICON_SIZE_DP.dp),
                    tint = iconForeground
                )
            }
            Spacer(Modifier.width(LIBRARY_HERO_ACTION_ICON_LABEL_GAP_DP.dp))
            Text(
                label,
                color = if (enabled) {
                    TextPrimary
                } else {
                    StemwisePalette.disabledLibraryActionContent
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.stemwiseMarquee()
            )
        }
    }
}

@Composable
internal fun PlaylistSearch(
    query: String,
    focused: Boolean,
    enabled: Boolean = true,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val motion = LocalStemwiseMotion.current
    val searchShape = remember { RoundedCornerShape(18.dp) }
    val presentation = librarySearchFieldPresentation(focused)
    val borderColor by animateColorAsState(
        targetValue = presentation.borderColor,
        animationSpec = motion.semanticSpec(
            StemwiseMotionEvent.STATUS_CHANGE
        ),
        label = "library-search-border"
    )
    val iconColor by animateColorAsState(
        targetValue = if (focused) PrimaryPurple else TextSecondary,
        animationSpec = motion.semanticSpec(
            StemwiseMotionEvent.STATUS_CHANGE
        ),
        label = "library-search-icon"
    )
    val borderWidth by animateDpAsState(
        targetValue = presentation.borderWidthDp.dp,
        animationSpec = motion.semanticSpec(
            StemwiseMotionEvent.STATUS_CHANGE,
            spatial = true
        ),
        label = "library-search-border-width"
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (presentation.focusedShadow) 10.dp else 0.dp,
        animationSpec = motion.semanticSpec(
            StemwiseMotionEvent.STATUS_CHANGE,
            spatial = true
        ),
        label = "library-search-shadow"
    )
    Column(if (enabled) modifier else modifier.clearAndSetSemantics { }) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 15.sp,
                fontFamily = StemwiseSystemFontFamily
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .shadow(
                    elevation = shadowElevation,
                    shape = searchShape,
                    clip = false,
                    ambientColor = PrimaryPurple.copy(alpha = .20f),
                    spotColor = PrimaryPurple.copy(alpha = .26f)
                )
                .background(Surface, searchShape)
                .border(
                    borderWidth,
                    borderColor,
                    searchShape
                )
                .testTag("library-search-field")
                .semantics { contentDescription = "Search title, artist, or album" }
                .padding(start = 16.dp, end = 4.dp),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        StemwiseIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconColor
                    )
                    Box(Modifier.weight(1f).padding(start = 12.dp)) {
                        AnimatedContent(
                            targetState = query.isBlank(),
                            transitionSpec = {
                                fadeIn(
                                    motion.semanticSpec(
                                        StemwiseMotionEvent.STATUS_CHANGE
                                    )
                                ).togetherWith(
                                    fadeOut(
                                        motion.semanticSpec(
                                            StemwiseMotionEvent.STATUS_CHANGE
                                        )
                                    )
                                )
                            },
                            label = "library-search-placeholder"
                        ) { visible ->
                            if (visible) {
                                Text(
                                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_title_artist_or_album),
                                    modifier = Modifier.stemwiseMarquee(),
                                    color = TextSecondary,
                                    fontSize = 15.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        inner()
                    }
                    // The trailing lane never enters or leaves layout. Previously the first typed
                    // character inserted a 48 dp IconButton, shrinking the editable width and
                    // increasing the field height in the same frame.
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = query.isNotBlank(),
                            transitionSpec = {
                                (fadeIn(
                                    motion.semanticSpec(
                                        StemwiseMotionEvent.STATUS_CHANGE
                                    )
                                ) + scaleIn(
                                    motion.semanticSpec(
                                        StemwiseMotionEvent.STATUS_CHANGE,
                                        spatial = true
                                    ),
                                    initialScale = .88f
                                )).togetherWith(
                                    fadeOut(
                                        motion.semanticSpec(
                                            StemwiseMotionEvent.STATUS_CHANGE
                                        )
                                    ) + scaleOut(
                                        motion.semanticSpec(
                                            StemwiseMotionEvent.STATUS_CHANGE,
                                            spatial = true
                                        ),
                                        targetScale = .88f
                                    )
                                )
                            },
                            label = "library-search-clear"
                        ) { visible ->
                            if (visible) {
                                StemwiseIconButton(
                                    onClick = { onQueryChange("") },
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        StemwiseIcons.Clear,
                                        contentDescription = "Clear search",
                                        tint = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
internal fun HighlightedSearchText(
    text: String,
    query: String,
    explicitRanges: List<StemwiseSearchRange>? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val presentation = remember(text, query, explicitRanges) {
        stemwiseSearchHighlightPresentation(text, query, explicitRanges)
    }
    val resolvedStyle = style.merge(TextStyle(fontFamily = StemwiseSystemFontFamily))
    if (presentation.mode == StemwiseSearchHighlightMode.NONE) {
        // The unfiltered Library is the hot fling path. Use the String overload directly so a
        // recycled or unmatched field does not allocate an AnnotatedString with no spans.
        Text(
            text = text,
            modifier = modifier,
            style = resolvedStyle,
            color = color,
            fontSize = fontSize,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    val highlighted = remember(text, presentation.ranges) {
        buildAnnotatedString {
            append(text)
            presentation.ranges.forEach { range ->
                // Color changes do not alter Arabic shaping, metrics, or the unmatched letters.
                addStyle(SpanStyle(color = PrimaryPurple), range.start, range.endExclusive)
            }
        }
    }
    Text(
        text = highlighted,
        modifier = modifier,
        style = resolvedStyle,
        color = color,
        fontSize = fontSize,
        maxLines = maxLines,
        overflow = overflow
    )
}

@Composable
private fun PlaylistSongRow(
    song: Song,
    presentation: LibrarySongRowPresentation,
    current: Boolean,
    selectionFeedback: Boolean,
    playing: Boolean,
    query: String,
    searchContext: LibrarySearchContext?,
    durationTextStyle: TextStyle,
    phaseOrdinal: Int,
    artworkAuraMotionEnabled: Boolean,
    onPlay: () -> Boolean,
    onManage: () -> Unit,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onDetails: () -> Unit,
    onResetSettings: (() -> Unit)?,
    onViewIssue: () -> Unit,
    onPrepareParts: (() -> Unit)?,
    onDeleteSong: (() -> Unit)?,
    onDeleteStems: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    val rowInteractionSource = remember(song.id) { MutableInteractionSource() }
    val rowHoldInteractionSource = remember(song.id) { MutableInteractionSource() }
    val rowHoldAuraState = remember(song.id) { mutableStateOf<DragAuraState?>(null) }
    var rowAuraSize by remember(song.id) { mutableStateOf(Size.Zero) }
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val largeText = fontScale >= 1.3f
    val titleMatchRanges = remember(song.title, query) {
        stemwiseSearchRanges(song.title, query)
    }
    val searchLineCount = 1 +
        (if (presentation.artistLine.isNotBlank()) 1 else 0) +
        (if (presentation.albumLine.isNotBlank() || searchContext != null) 1 else 0)
    val rowGeometry = librarySearchRowGeometryDp(fontScale, query, searchLineCount)
    val rowPitchGapDp = rowGeometry.pitchDp - rowGeometry.minimumHeightDp
    val selectionProgress by animateFloatAsState(
        targetValue = if (current || selectionFeedback) 1f else 0f,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT),
        label = "library-row-selection"
    )
    val selectedRowBrush = remember {
        Brush.linearGradient(
            listOf(
                StemwisePalette.selectedLibraryRowGradientStart,
                StemwisePalette.selectedLibraryRowGradientEnd
            )
        )
    }
    val trailingStatus: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                // Keep the authored minimum while letting scaled or longer durations grow.
                Modifier
                    .widthIn(min = LIBRARY_TRAILING_STATUS_OWNER_WIDTH_DP.dp)
                    .width(IntrinsicSize.Max)
                    .padding(end = LIBRARY_TRAILING_STATUS_END_INSET_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlayingIndicator(
                    playing = playing,
                    selected = current,
                    ambientMotionEnabled = artworkAuraMotionEnabled,
                    tag = "library-playing-bars:${song.id}"
                )
                Spacer(Modifier.width(5.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        presentation.duration,
                        modifier = Modifier.fillMaxWidth(),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        style = durationTextStyle
                    )
                }
            }
            Box {
                StemwiseIconButton(onClick = onManage,
                    modifier = Modifier.size(48.dp).testTag("library-song-menu:${song.id}")
                        .semantics { contentDescription = "Song menu for ${song.title}" }) {
                    Canvas(Modifier.size(24.dp)) {
                        val radius = 1.75.dp.toPx()
                        for (dot in -1..1) drawCircle(TextSecondary, radius,
                            Offset(size.width * .5f, size.height * .5f + dot * 7.dp.toPx()))
                    }
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                    DropdownMenuItem(text = { Text("Song details") }, onClick = onDetails)
                    onResetSettings?.let { DropdownMenuItem(text = { Text("Reset damaged playback settings") }, onClick = it) }
                    onPrepareParts?.let { DropdownMenuItem(text = { Text("Choose parts") }, onClick = it) }
                    onDeleteStems?.let { DropdownMenuItem(text = { Text("Delete stems only") }, onClick = it) }
                    onDeleteSong?.let { DropdownMenuItem(text = { Text("Delete song with everything") }, onClick = it) }
                }
            }
        }
    }
    Column(
        modifier
            .then(
                if (rowPitchGapDp > 0f) {
                    Modifier.padding(bottom = rowPitchGapDp.dp)
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .stemwiseAuraClassifiedTapOrLongPressAction(
                interactionSource = rowInteractionSource,
                onClickLabel = "Play song",
                onLongClickLabel = "Song menu",
                onHoldAuraChanged = { active ->
                    rowHoldAuraState.value = if (
                        active && rowAuraSize.width > 0f && rowAuraSize.height > 0f
                    ) {
                        DragAuraState(
                            center = Offset(rowAuraSize.width * .5f, rowAuraSize.height * .5f),
                            active = true,
                            visibleSize = rowAuraSize
                        )
                    } else {
                        null
                    }
                },
                onTap = onPlay,
                onLongPress = onManage
            )
            .stemWiseInteractionAura(
                enabled = true,
                selected = false,
                interactionSource = rowInteractionSource,
                anchor = AuraAnchor.VisibleControl,
                reducedMotion = !motion.enabled,
            )
            .stemWiseInteractionAura(
                enabled = true,
                selected = false,
                interactionSource = rowHoldInteractionSource,
                dragState = rowHoldAuraState,
                anchor = AuraAnchor.Movable,
                reducedMotion = !motion.enabled,
            )
            .onSizeChanged { measured ->
                rowAuraSize = Size(measured.width.toFloat(), measured.height.toFloat())
            }
            .heightIn(min = rowGeometry.minimumHeightDp.dp)
            .testTag("library-song:${song.id}")
            .semantics {
                val ownerDescription = when {
                    playing -> "Now playing, ${presentation.baseAccessibilityDescription}"
                    current -> "Current song, ${presentation.baseAccessibilityDescription}"
                    else -> presentation.baseAccessibilityDescription
                }
                val contextualDescription = searchContext?.let { context ->
                    "$ownerDescription, ${context.displayText}"
                } ?: ownerDescription
                contentDescription = contextualDescription
            }
            .padding(horizontal = 6.dp)
            .background(
                brush = selectedRowBrush,
                shape = StemwiseRadii.card,
                alpha = selectionProgress
            )
            .border(
                width = 1.5.dp,
                color = PrimaryPurple.copy(alpha = selectionProgress),
                shape = StemwiseRadii.card
            )
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(8.dp))
            val artworkSize = 52.dp
            Box(
                Modifier
                    .size(artworkSize)
                    .graphicsLayer { clip = false }
            ) {
                Artwork(
                    song = song,
                    size = artworkSize,
                    fillAvailable = true,
                    phaseOrdinal = phaseOrdinal,
                    // The row surface and now-playing mark already carry selection. Keeping this tiny
                    // artwork on the compact aura avoids allocating the hero blur during handoff.
                    localAuraOnly = true,
                    auraMotionEnabled = artworkAuraMotionEnabled,
                    cornerRadius = if (current) 5.dp else 10.dp,
                    // Virtualized remounts render the owner-qualified final bitmap immediately.
                    // Artwork() still animates a genuine mounted source/revision owner change.
                    crossfadeOnLoad = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("library-artwork:${song.id}")
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HighlightedSearchText(
                        text = song.title,
                        query = query,
                        explicitRanges = titleMatchRanges,
                        modifier = Modifier.weight(1f).stemwiseMarquee(artworkAuraMotionEnabled && !largeText),
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (song.isFlac && !largeText) {
                        Text(
                            androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_flac),
                            modifier = Modifier.padding(start = 8.dp).testTag("library-flac:${song.id}"),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                if (query.isBlank()) {
                    Spacer(Modifier.height(2.dp))
                    HighlightedSearchText(
                        text = presentation.detail,
                        query = query,
                        modifier = Modifier.stemwiseMarquee(artworkAuraMotionEnabled),
                        // Actual artist bylines use the same pink token as both player sizes. An
                        // album-only fallback remains secondary metadata rather than impersonating
                        // an artist.
                        color = if (presentation.artistLine.isNotBlank()) {
                            PrimaryBright
                        } else {
                            TextSecondary
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    if (presentation.artistLine.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        HighlightedSearchText(
                            text = presentation.artistLine,
                            query = query,
                            modifier = Modifier.stemwiseMarquee(
                                artworkAuraMotionEnabled && fontScale < 1.3f
                            ),
                            color = PrimaryBright,
                            fontSize = 12.sp,
                            maxLines = if (fontScale >= 1.3f) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Hidden indexed metadata substitutes for the nonmatching album line. Search rows
                    // remain the authored 76dp/three-line component at scale 1.
                    if (searchContext == null && presentation.albumLine.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        HighlightedSearchText(
                            text = presentation.albumLine,
                            query = query,
                            modifier = Modifier.stemwiseMarquee(
                                artworkAuraMotionEnabled && fontScale < 1.3f
                            ),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = if (fontScale >= 1.3f) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    searchContext?.let { context ->
                        Spacer(Modifier.height(1.dp))
                        HighlightedSearchText(
                            text = context.displayText,
                            query = query,
                            explicitRanges = context.displayRanges,
                            modifier = Modifier.stemwiseMarquee(
                                artworkAuraMotionEnabled && fontScale < 1.3f
                            ),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = if (fontScale >= 1.3f) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (song.state == SongState.NEEDS_ATTENTION) {
                    Text("This song needs attention.", color = Coral, fontSize = 12.sp)
                    StemwiseTextButton(onClick = onViewIssue) { Text("View issue") }
                }
            }
            if (!largeText) trailingStatus()
        }
        if (largeText) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (song.isFlac) {
                    Text(
                        androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_flac),
                        modifier = Modifier.testTag("library-flac:${song.id}"),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(Modifier.weight(1f))
                trailingStatus()
            }
        }
    }
}

@Composable
private fun NowPlayingIndicator(
    playing: Boolean,
    selected: Boolean,
    ambientMotionEnabled: Boolean,
    tag: String
) {
    val motion = LocalStemwiseMotion.current
    val phase = remember(tag) { Animatable(0f) }
    LaunchedEffect(selected, playing, motion.ambientMotionEnabled, ambientMotionEnabled) {
        if (!selected) {
            phase.snapTo(0f)
            return@LaunchedEffect
        }
        if (!playing || !motion.ambientMotionEnabled || !ambientMotionEnabled) return@LaunchedEffect
        while (true) {
            val remainingMs = ((1f - phase.value) * 1_080f).roundToInt().coerceAtLeast(1)
            phase.animateTo(1f, tween(durationMillis = remainingMs, easing = LinearEasing))
            phase.snapTo(0f)
        }
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT)),
            exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val barWidth = 3.dp.toPx()
                val gap = 2.dp.toPx()
                val color = if (playing) PrimaryPurple else TextSecondary
                repeat(3) { index ->
                    val shifted = (phase.value + index * .23f) % 1f
                    val wave = 1f - abs(shifted * 2f - 1f)
                    val height = (5f + wave * 9f).dp.toPx()
                    val left = 1.5.dp.toPx() + index * (barWidth + gap)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, (size.height - height) * .5f),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(barWidth * .5f, barWidth * .5f)
                    )
                }
            }
        }
    }
}
