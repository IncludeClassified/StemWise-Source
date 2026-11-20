package app.stemwise.ui

import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.stemwise.model.StemRoleContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stemwise.BuildConfig
import app.stemwise.bridge.BridgeUiState
import app.stemwise.enrichment.EnrichmentImportResult
import app.stemwise.enrichment.SharedEnrichmentImportState
import app.stemwise.data.PlaylistSettings
import app.stemwise.data.LibraryRootState
import app.stemwise.data.LibraryInventory
import app.stemwise.data.LibraryInventoryAvailability
import app.stemwise.data.LibraryStorageRefreshState
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.data.PLAYLIST_ITEM_UNAVAILABLE_CODE
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import app.stemwise.model.QueueJobAction
import app.stemwise.model.QueueOperationPhase
import app.stemwise.model.QueueJob
import app.stemwise.model.Song
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import app.stemwise.ui.theme.Amber
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.PrimaryBright
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.OutlineStrong
import app.stemwise.ui.theme.Sky
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.SurfaceLow
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.theme.Violet
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.design.StemwiseIconButton
import app.stemwise.ui.design.StemwiseRadii
import app.stemwise.ui.design.StemwiseSpacing
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.stemwiseAuraClickable
import app.stemwise.ui.design.stemwiseAuraClickableAction
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.design.emitAcceptedAuraFeedback
import app.stemwise.ui.design.semanticSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun SettingsScreen(
    playlistSettings: PlaylistSettings,
    mvsepState: MvsepConnectionState,
    bridgeState: BridgeUiState,
    onSaveBridgeConnection: suspend (app.stemwise.bridge.BridgeConnectionProfile) -> Result<Unit>,
    onEnrollBridge: suspend () -> Result<String?>,
    onRefreshBridge: suspend () -> Result<Unit>,
    onWakeBridgePc: suspend () -> Result<Unit>,
    onPairBridgeAgain: (suspend () -> Result<Unit>)? = null,
    libraryRootState: LibraryRootState,
    storageRefreshState: LibraryStorageRefreshState,
    onChooseLibrary: () -> Unit,
    onRefreshStorage: () -> Unit,
    onUpdatePlaylistTools: suspend () -> Result<Unit>,
    onSaveMvsepToken: suspend (String) -> Result<Unit>,
    onRetryMvsep: suspend () -> Result<Unit>,
    onRemoveMvsepToken: suspend () -> Result<Unit>,
    playbackSnapshotExport: PlaybackSnapshotExportUi? = null,
    initialSection: SystemSurfaceSection? = null
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motion = LocalStemwiseMotion.current
    LaunchedEffect(initialSection) {
        initialSection?.let { section ->
            listState.scrollToItem(when (section) {
                SystemSurfaceSection.CONNECTIONS -> 1
                SystemSurfaceSection.LIBRARY_STORAGE -> 4
                SystemSurfaceSection.TROUBLESHOOTING -> if (libraryRootState.ready) 7 else 8
            })
        }
    }
    var updatingPlaylistTools by remember { mutableStateOf(false) }
    var playlistToolsResult by remember { mutableStateOf<String?>(null) }
    // Token drafts and their reveal/error UI must never enter saved instance state. Recreation
    // closes the sensitive surface and discards the draft instead of serializing plaintext.
    var showMvsepDialog by remember { mutableStateOf(false) }
    var mvsepToken by remember { mutableStateOf("") }
    var mvsepSaving by remember { mutableStateOf(false) }
    var mvsepDialogError by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var showMvsepToken by remember { mutableStateOf(false) }
    LaunchedEffect(libraryRootState.libraryId, libraryRootState.treeUri,
        libraryRootState.epoch, libraryRootState.ready) {
        // Root replacement invalidates inventory before publishing the next selected root.
        // Re-entry can reuse its clean measurement; explicit Refresh still always measures.
        val inventory = storageRefreshState.inventory
        if (libraryRootState.ready && !storageRefreshState.refreshing &&
            (storageRefreshState.availability != LibraryInventoryAvailability.VERIFIED ||
                inventory.lastCheckedEpochMs <= 0L || inventory.snapshotDirty ||
                storageRefreshState.errorCode != null || storageRefreshState.errorMessage != null)
        ) onRefreshStorage()
    }
    val libraryInventory = storageRefreshState.inventory
    val libraryPath = remember(libraryRootState.treeUri, libraryRootState.displayName) {
        readableLibraryPath(libraryRootState)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboard.current
    var youtubeOpenError by remember { mutableStateOf<String?>(null) }
    val openYouTubeMusic: () -> Unit = {
        youtubeOpenError = null
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://music.youtube.com/".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { youtubeOpenError = "Could not open YouTube Music on this device." }
        Unit
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val contentWidth = minOf(maxWidth, 480.dp)
    val horizontalMargin = if (contentWidth < 392.dp) 16.dp else 20.dp
    LazyColumn(
        modifier = Modifier.width(contentWidth).align(Alignment.TopCenter),
        state = listState,
        contentPadding = PaddingValues(
            start = horizontalMargin,
            end = horizontalMargin,
            top = 12.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Text(
                "Settings",
                color = TextPrimary,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.stemwiseMarquee().semantics { heading() }
            )
        }
        item(key = "connections-heading") {
            SystemGroupHeader(SystemSurfaceSection.CONNECTIONS.label, "PC Bridge and music services")
        }
        item {
            val mvsepAction: () -> Unit = {
                mvsepToken = ""
                showMvsepToken = false
                mvsepDialogError = null
                showMvsepDialog = true
            }
            val mvsepDetail = when (mvsepState.status) {
                MvsepConnectionStatus.NOT_CONFIGURED -> "Not connected"
                MvsepConnectionStatus.CHECKING -> "Verifying account"
                MvsepConnectionStatus.SAVED_UNVERIFIED -> "Saved locally"
                MvsepConnectionStatus.READY -> "Account linked"
                MvsepConnectionStatus.ERROR -> mvsepState.error ?: "Needs attention"
            }
            val mvsepStateLabel = when (mvsepState.status) {
                MvsepConnectionStatus.NOT_CONFIGURED -> "Connect"
                MvsepConnectionStatus.CHECKING -> "Checking"
                MvsepConnectionStatus.SAVED_UNVERIFIED -> "Verify"
                MvsepConnectionStatus.READY -> "Ready"
                MvsepConnectionStatus.ERROR -> when {
                    mvsepState.waitingForUnmeteredNetwork -> "Waiting"
                    mvsepState.tokenStored || mvsepState.localLoadFailed -> "Retry"
                    else -> "Connect"
                }
            }
            val mvsepStatusKind = when (mvsepState.status) {
                MvsepConnectionStatus.READY -> SystemConnectionStatusVisualKind.READY
                MvsepConnectionStatus.CHECKING, MvsepConnectionStatus.SAVED_UNVERIFIED -> SystemConnectionStatusVisualKind.PENDING
                MvsepConnectionStatus.ERROR -> when {
                    mvsepState.waitingForUnmeteredNetwork -> SystemConnectionStatusVisualKind.PENDING
                    mvsepState.tokenStored || mvsepState.localLoadFailed -> SystemConnectionStatusVisualKind.ERROR
                    else -> SystemConnectionStatusVisualKind.NEUTRAL
                }
                else -> SystemConnectionStatusVisualKind.NEUTRAL
            }
            val mvsepAccent = systemMvsepStatusAccent(mvsepState.status)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SystemConnectionCard(
                    icon = StemwiseIcons.QueueMusic,
                    title = "YouTube Music",
                    detail = if (playlistSettings.configured) playlistSettings.displayName else "No playlist connected",
                    state = if (playlistSettings.configured) "Ready" else "Not connected",
                    statusKind = if (playlistSettings.configured) SystemConnectionStatusVisualKind.READY else SystemConnectionStatusVisualKind.NEUTRAL,
                    accent = if (playlistSettings.configured) PrimaryPurple else TextSecondary
                ) {
                    Text("Share a playlist from YouTube Music to connect it to this Library.",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    youtubeOpenError?.let { BridgeError(it) }
                    StemwiseTextButton(onClick = openYouTubeMusic) { Text("Open YouTube Music") }
                }
                SystemConnectionCard(
                    icon = StemwiseIcons.Stems,
                    title = "MVSEP",
                    detail = mvsepState.accountName?.takeIf(String::isNotBlank)
                        ?: if (mvsepState.tokenStored) "API token saved on this device" else "No API token saved",
                    state = mvsepStateLabel,
                    statusKind = mvsepStatusKind,
                    accent = mvsepAccent
                ) {
                    Text("Uploads Original audio to create selected parts plus Remaining.",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    if (mvsepState.status == MvsepConnectionStatus.ERROR) {
                        BridgeError(mvsepState.error ?: "Could not verify MVSEP. Edit the connection to retry.")
                    } else Text(mvsepDetail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    StemwiseTextButton(onClick = mvsepAction) {
                        Text(if (mvsepState.tokenStored || mvsepState.localLoadFailed) "Edit MVSEP connection" else "Connect MVSEP")
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            BridgeConnectionPanel(
                state = bridgeState,
                onSaveConnection = onSaveBridgeConnection,
                onEnroll = onEnrollBridge,
                onRefresh = onRefreshBridge,
                onWakePc = onWakeBridgePc,
                onPairAgain = onPairBridgeAgain
            )
        }
        item(key = "library-storage-heading") {
            SystemGroupHeader(SystemSurfaceSection.LIBRARY_STORAGE.label,
                if (libraryRootState.ready) "Library connected" else "Library access needs attention")
        }
        if (!libraryRootState.ready) item(key = "library-attention") {
            Column(
                Modifier.animateItem(
                    fadeInSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_INSERT),
                    fadeOutSpec = motion.semanticSpec(StemwiseMotionEvent.LIST_REMOVE),
                    placementSpec = motion.semanticSpec(
                        StemwiseMotionEvent.LIST_REORDER,
                        spatial = true
                    )
                )
            ) {
                Spacer(Modifier.height(12.dp))
                LibraryAttentionBanner(onChooseLibrary = onChooseLibrary)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            StorageDashboardCard(
                libraryInventory = libraryInventory,
                refreshing = storageRefreshState.refreshing,
                availability = storageRefreshState.availability,
                refreshError = storageRefreshFailureMessage(storageRefreshState.errorCode, storageRefreshState.errorMessage),
                onRefresh = {
                    if (!storageRefreshState.refreshing) {
                        onRefreshStorage()
                    }
                }
            )
        }
        item {
            Spacer(Modifier.height(14.dp))
            PlaylistToolRow(
                icon = StemwiseIcons.FolderOpen,
                title = "Library folder",
                detail = libraryPath,
                accent = PrimaryPurple,
                onClick = onChooseLibrary
            )
        }
        item(key = "troubleshooting-heading") {
            SystemGroupHeader(SystemSurfaceSection.TROUBLESHOOTING.label, "Tools and playback diagnostics")
        }
        item {
            StemwiseTextButton(enabled = libraryRootState.ready && !updatingPlaylistTools, onClick = update@{
                if (updatingPlaylistTools || !libraryRootState.ready) return@update
                updatingPlaylistTools = true
                scope.launch {
                    try {
                        playlistToolsResult = onUpdatePlaylistTools().fold(
                            onSuccess = { "Playlist tools are ready" },
                            onFailure = { "Could not update playlist tools. Try again later." }
                        )
                    } finally { updatingPlaylistTools = false }
                }
            }) { Text(if (updatingPlaylistTools) "Checking playlist tools…" else "Check playlist tools for updates") }
            playlistToolsResult?.let { Text(it, color = TextSecondary, fontSize = 13.sp) }
            playbackSnapshotExport?.let { export ->
                export.toggleDetailedCapture?.let { toggle ->
                    StemwiseTextButton(enabled = !export.busy, onClick = toggle) {
                        Text(if (export.detailedCaptureActive) "Stop detailed capture" else "Start detailed capture")
                    }
                }
                StemwiseTextButton(enabled = !export.busy, onClick = export.export) {
                    Text(if (export.busy) "Saving playback snapshot…" else "Export playback snapshot")
                }
                export.cancel?.let { cancel ->
                    StemwiseTextButton(onClick = cancel) { Text("Cancel export") }
                }
                export.message?.let { Text(it, color = TextSecondary, fontSize = 13.sp) }
            }
            AboutFooter()
        }
    }
    }

    if (showMvsepDialog) {
        val mvsepDialogTitle = if (mvsepState.tokenStored) "Replace MVSEP token" else "Connect MVSEP"
        StemwiseAlertDialog(
            onDismissRequest = {
                showMvsepDialog = false
                mvsepToken = ""
                showMvsepToken = false
                mvsepDialogError = null
            },
            dismissEnabled = !mvsepSaving,
            title = {
                AnimatedContent(
                    targetState = mvsepDialogTitle,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "mvsep-dialog-title"
                ) { title ->
                    Text(title, modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                }
            },
            text = {
                Column(
                    Modifier
                        .animateContentSize(
                            animationSpec = motion.semanticSpec(
                                StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                                spatial = true
                            )
                        )
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(MVSEP_TOKEN_PRIVACY_COPY, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = mvsepToken,
                            onValueChange = {
                                mvsepToken = it
                                mvsepDialogError = null
                            },
                            label = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_api_token)) },
                            singleLine = true,
                            visualTransformation = if (showMvsepToken) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                            enabled = !mvsepSaving,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StemwiseTextButton(onClick = {
                                scope.launch {
                                    clipboard.getClipEntry()
                                        ?.clipData
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                        ?.toString()
                                        ?.let { pasted -> runIfOpen { mvsepToken = pasted.trim() } }
                                }
                            }) {
                                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_paste), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                            }
                            StemwiseTextButton(onClick = {
                                runIfOpen { showMvsepToken = !showMvsepToken }
                            }) {
                                AnimatedContent(
                                    targetState = showMvsepToken,
                                    transitionSpec = {
                                        fadeIn(
                                            motion.semanticSpec(
                                                StemwiseMotionEvent.STATUS_CHANGE
                                            )
                                        ) togetherWith fadeOut(
                                            motion.semanticSpec(
                                                StemwiseMotionEvent.STATUS_CHANGE
                                            )
                                        )
                                    },
                                    label = "mvsep-token-visibility"
                                ) { visible ->
                                    Text(
                                        if (visible) "Hide token" else "Show token",
                                        modifier = Modifier.stemwiseMarquee(),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    mvsepInformationalActions.forEach { action ->
                        StemwiseTextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, action.url.toUri())
                                    )
                                }.onFailure {
                                    mvsepDialogError = MVSEP_HELP_OPEN_ERROR
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(action.label, modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                        }
                    }
                    AnimatedContent(
                        targetState = mvsepDialogError,
                        transitionSpec = {
                            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        },
                        label = "mvsep-error"
                    ) { error ->
                        if (error != null) Text(error, color = Coral, fontSize = 12.sp)
                    }
                    if ((mvsepState.tokenStored || mvsepState.localLoadFailed) && mvsepState.status != MvsepConnectionStatus.READY) {
                        StemwiseTextButton(enabled = actionsEnabled && !mvsepSaving, onClick = retry@{
                            if (mvsepSaving || !beginBusyAction()) return@retry
                            mvsepSaving = true
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    onRetryMvsep().onFailure {
                                        mvsepDialogError = it.message ?: "Could not reach MVSEP"
                                    }
                                } finally {
                                    mvsepSaving = false
                                    endBusyAction()
                                }
                            }
                        }) { Text(if (mvsepState.localLoadFailed) "Retry saved connection" else
                            androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_verify_connection)) }
                    }
                    AnimatedVisibility(
                        visible = mvsepState.tokenStored,
                        enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                            expandVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)),
                        exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                            shrinkVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW))
                    ) {
                        StemwiseTextButton(
                            onClick = {
                                runIfOpen { showDisconnectConfirmation = true }
                            },
                            enabled = !mvsepSaving
                        ) {
                            Text(
                                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_disconnect),
                                modifier = Modifier.stemwiseMarquee(),
                                color = Coral,
                                maxLines = 1
                            )
                        }
                    }
                }
            },
            confirmButton = {
                StemwiseButton(
                    enabled = actionsEnabled && mvsepToken.isNotBlank() && !mvsepSaving,
                    onClick = save@{
                        if (mvsepSaving || !beginBusyAction()) return@save
                        mvsepSaving = true
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                onSaveMvsepToken(mvsepToken).onSuccess {
                                    endBusyAction()
                                    closeThen {
                                        mvsepToken = ""
                                        showMvsepToken = false
                                        showMvsepDialog = false
                                    }
                                }.onFailure { error ->
                                    mvsepDialogError = error.message ?: "Could not verify this token"
                                }
                            } finally {
                                mvsepSaving = false
                                endBusyAction()
                            }
                        }
                    }
                ) {
                    val saveLabel = when {
                        mvsepSaving -> "Checking…"
                        mvsepState.tokenStored -> "Replace"
                        else -> "Connect"
                    }
                    AnimatedContent(
                        targetState = saveLabel,
                        transitionSpec = {
                            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        },
                        label = "mvsep-save-state"
                    ) { label ->
                        Text(label, modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                    }
                }
            },
            dismissButton = {
                StemwiseTextButton(
                    enabled = !mvsepSaving,
                    onClick = { dismiss() }
                ) {
                    Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_cancel), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                }
            },
            containerColor = SurfaceRaised,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            modifier = Modifier.imePadding()
        )
    }

    if (showDisconnectConfirmation) {
        StemwiseAlertDialog(
            onDismissRequest = { showDisconnectConfirmation = false },
            dismissEnabled = !mvsepSaving,
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_disconnect_mvsep)) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_new_songs_will_no_longer_be_separated)
                )
            },
            confirmButton = {
                StemwiseTextButton(
                    enabled = !mvsepSaving,
                    onClick = disconnect@{
                        if (mvsepSaving || !beginBusyAction()) return@disconnect
                        mvsepSaving = true
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                val outcome = onRemoveMvsepToken()
                                endBusyAction()
                                closeThen {
                                    outcome.onSuccess {
                                        mvsepToken = ""
                                        showMvsepToken = false
                                        mvsepDialogError = null
                                        showMvsepDialog = false
                                    }.onFailure { error ->
                                        mvsepDialogError = error.message ?: "Could not disconnect MVSEP"
                                    }
                                    showDisconnectConfirmation = false
                                }
                            } finally {
                                mvsepSaving = false
                                endBusyAction()
                            }
                        }
                    }
                ) {
                    AnimatedContent(
                        targetState = mvsepSaving,
                        transitionSpec = {
                            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        },
                        label = "mvsep-disconnect-state"
                    ) { saving ->
                        Text(
                            if (saving) "Disconnecting…" else "Disconnect",
                            modifier = Modifier.stemwiseMarquee(),
                            maxLines = 1,
                            color = Coral
                        )
                    }
                }
            },
            dismissButton = {
                StemwiseTextButton(enabled = !mvsepSaving, onClick = { dismiss() }) {
                    Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_cancel), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                }
            },
            containerColor = SurfaceRaised
        )
    }

}

@Composable
private fun LibraryAttentionBanner(onChooseLibrary: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, StemwiseRadii.card)
            .border(1.dp, Coral, StemwiseRadii.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            StemwiseIcons.Error,
            contentDescription = null,
            tint = Coral,
            modifier = Modifier.size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_library_needs_attention),
                modifier = Modifier.stemwiseMarquee(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_reconnect_the_library_folder),
                modifier = Modifier.stemwiseMarquee(),
                color = Coral,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        StemwiseTextButton(onClick = onChooseLibrary) {
            Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_choose), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
        }
    }
}

@Composable
private fun SystemGroupHeader(title: String, summary: String) {
    Spacer(Modifier.height(18.dp))
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary,
            modifier = Modifier.semantics { heading() })
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun SystemSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = TextSecondary,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .8.sp,
        maxLines = 1,
        modifier = modifier.stemwiseMarquee().semantics { heading() }
    )
}

@Composable
internal fun SystemConnectionCard(
    icon: ImageVector,
    title: String,
    detail: String,
    state: String,
    statusKind: SystemConnectionStatusVisualKind,
    accent: Color,
    content: @Composable () -> Unit
) {
    val canonicalScale = LocalDensity.current.fontScale < 1.3f
    Column(
        Modifier.fillMaxWidth()
            .background(Surface, StemwiseRadii.card)
            .border(1.dp, OutlineStrong, StemwiseRadii.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val headerModifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = state }
        if (canonicalScale) {
            Row(headerModifier, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                Text(title, modifier = Modifier.weight(1f).semantics { heading() }, color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium)
                SystemConnectionStatusBadge(state = state, canonicalScale = true,
                    exactReadyPill = statusKind == SystemConnectionStatusVisualKind.READY && state == "Ready",
                    statusVisual = systemConnectionStatusVisual(statusKind))
            }
        } else {
            Column(headerModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                    Text(title, modifier = Modifier.weight(1f).semantics { heading() }, color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium)
                }
                SystemConnectionStatusBadge(state = state, canonicalScale = false,
                    modifier = Modifier.align(Alignment.End), exactReadyPill = false,
                    statusVisual = systemConnectionStatusVisual(statusKind))
            }
        }
        Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

internal fun systemConnectionStatusBadgeMinimumWidthDp(kind: SystemConnectionStatusVisualKind): Float = when (kind) {
    SystemConnectionStatusVisualKind.NEUTRAL -> 92f
    SystemConnectionStatusVisualKind.PENDING -> 72f
    SystemConnectionStatusVisualKind.READY, SystemConnectionStatusVisualKind.ERROR -> SYSTEM_CONNECTION_READY_PILL_WIDTH_DP
}

@Composable
internal fun SystemConnectionStatusBadge(
    state: String,
    canonicalScale: Boolean,
    modifier: Modifier = Modifier,
    statusVisual: SystemConnectionStatusVisual,
    exactReadyPill: Boolean = statusVisual.kind == SystemConnectionStatusVisualKind.READY && canonicalScale
) {
    val motion = LocalStemwiseMotion.current
    val animatedFill by animateColorAsState(
        targetValue = statusVisual.fill,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
        label = "system-status-fill"
    )
    val animatedBorder by animateColorAsState(
        targetValue = statusVisual.border,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
        label = "system-status-border"
    )
    val animatedText by animateColorAsState(
        targetValue = statusVisual.text,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
        label = "system-status-text"
    )
    Box(
        modifier
            .animateContentSize(
                animationSpec = motion.semanticSpec(
                    StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                    spatial = true
                )
            )
            .then(
                if (exactReadyPill) {
                    Modifier.size(
                        width = SYSTEM_CONNECTION_READY_PILL_WIDTH_DP.dp,
                        height = SYSTEM_CONNECTION_READY_PILL_HEIGHT_DP.dp
                    )
                } else {
                    Modifier
                        .widthIn(min = systemConnectionStatusBadgeMinimumWidthDp(statusVisual.kind).dp)
                        .heightIn(min = SYSTEM_CONNECTION_READY_PILL_HEIGHT_DP.dp)
                }
            )
            .clip(CircleShape)
            .background(animatedFill)
            .border(1.dp, animatedBorder, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.then(
                if (exactReadyPill) Modifier else Modifier.padding(horizontal = 8.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = state.uppercase(),
                transitionSpec = {
                    fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                        fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                },
                label = "system-status-label"
            ) { visibleState ->
                Text(
                    visibleState,
                    modifier = Modifier.stemwiseMarquee(),
                    color = animatedText,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun AboutFooter() {
    Text(
        "StemWise ${BuildConfig.VERSION_NAME} · local library · this device",
        color = TextSecondary,
        fontSize = 10.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .stemwiseMarquee()
            .padding(top = 58.dp, bottom = 12.dp)
    )
}

internal fun storageRefreshFailureMessage(code: String?, detail: String?): String? = if (detail == null) null else when (code) {
    "ROOT_UNAVAILABLE", "ROOT_PERMISSION_LOST" -> "Reconnect the Library folder to refresh storage totals"
    else -> "Could not refresh storage totals"
}

internal data class StorageLastUpdatedPresentation(
    val visibleText: String,
    val accessibilityText: String
)

internal fun storageLastUpdatedPresentation(
    lastSuccessfulUpdateEpochMs: Long,
    refreshing: Boolean,
    latestRefreshFailed: Boolean,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault()
): StorageLastUpdatedPresentation {
    val hasSuccessfulUpdate = lastSuccessfulUpdateEpochMs > 0L
    val formattedTimestamp = if (hasSuccessfulUpdate) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).run {
            this.timeZone = timeZone
            format(Date(lastSuccessfulUpdateEpochMs))
        }
    } else {
        null
    }
    val visibleText = formattedTimestamp?.let { "Last updated: $it" } ?: "Last updated: Never"
    val accessibilityText = when {
        formattedTimestamp == null && refreshing ->
            "Storage totals have not completed an update; refresh in progress"
        formattedTimestamp == null && latestRefreshFailed ->
            "Storage totals have never updated successfully; latest refresh failed"
        formattedTimestamp == null -> "Storage totals have not updated yet"
        refreshing -> "Storage totals last updated $formattedTimestamp; refresh in progress"
        latestRefreshFailed ->
            "Storage totals last updated $formattedTimestamp; latest refresh failed"
        else -> "Storage totals last updated $formattedTimestamp"
    }
    return StorageLastUpdatedPresentation(visibleText, accessibilityText)
}

@Composable
internal fun StorageLastUpdatedLabel(
    presentation: StorageLastUpdatedPresentation,
    announceChanges: Boolean,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    AnimatedContent(
        targetState = presentation.visibleText,
        transitionSpec = {
            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
        },
        label = "storage-last-updated",
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = presentation.accessibilityText
                if (announceChanges) liveRegion = LiveRegionMode.Polite
            }
    ) { visibleText ->
        Text(
            text = visibleText,
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().stemwiseMarquee()
        )
    }
}

internal const val SYSTEM_REFRESH_OWNER_SIZE_DP = 48f
internal const val SYSTEM_REFRESH_GLYPH_SIZE_DP = 24f

@Composable
private fun StorageDashboardCard(
    libraryInventory: LibraryInventory,
    refreshing: Boolean,
    availability: LibraryInventoryAvailability,
    refreshError: String?,
    onRefresh: () -> Unit
) {
    val motion = LocalStemwiseMotion.current
    val summary = systemStorageSummary(libraryInventory)
    val lastUpdated = storageLastUpdatedPresentation(
        lastSuccessfulUpdateEpochMs = libraryInventory.lastCheckedEpochMs,
        refreshing = refreshing,
        latestRefreshFailed = refreshError != null ||
            availability == LibraryInventoryAvailability.UNAVAILABLE
    )
    val availabilityMessage = when (availability) {
        LibraryInventoryAvailability.RESTORING ->
            "Checking the Library folder; cached totals remain visible."
        LibraryInventoryAvailability.UNAVAILABLE ->
            "Library totals are unavailable until the folder is reconnected."
        LibraryInventoryAvailability.VERIFIED -> null
    }

    Column(
        Modifier.animateContentSize(
            animationSpec = motion.semanticSpec(
                StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                spatial = true
            )
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SystemSectionLabel("Storage", Modifier.weight(1f))
            StemwiseIconButton(
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier.size(SYSTEM_REFRESH_OWNER_SIZE_DP.dp)
            ) {
                AnimatedContent(
                    targetState = refreshing,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "storage-refresh-state"
                ) { active ->
                    if (active) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(SYSTEM_REFRESH_GLYPH_SIZE_DP.dp),
                            color = PrimaryPurple,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            StemwiseIcons.Refresh,
                            contentDescription = "Refresh storage totals",
                            tint = PrimaryPurple,
                            modifier = Modifier.size(SYSTEM_REFRESH_GLYPH_SIZE_DP.dp)
                        )
                    }
                }
            }
        }
        StorageLastUpdatedLabel(
            presentation = lastUpdated,
            announceChanges = availability == LibraryInventoryAvailability.VERIFIED &&
                refreshError == null,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        AnimatedContent(
            targetState = refreshError,
            transitionSpec = {
                fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                    fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
            },
            label = "storage-refresh-error"
        ) { message ->
            if (message != null) {
                Text(
                    message,
                    color = Coral,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (availability == LibraryInventoryAvailability.VERIFIED) {
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 214.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Surface)
                .border(1.dp, OutlineStrong, RoundedCornerShape(22.dp))
                .padding(start = 29.dp, end = 24.dp, top = 31.dp, bottom = 31.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stackSummary = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f
                if (stackSummary) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(StemwiseSpacing.md)
                    ) {
                        StorageDonut(
                            libraryBytes = summary.libraryBytes,
                            appBytes = summary.appBytes
                        )
                        StorageSummary(
                            libraryBytes = summary.libraryBytes,
                            appBytes = summary.appBytes,
                            artworkAndJsonCount = summary.artworkAndJsonCount,
                            verifiedStems = summary.verifiedStems,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(29.dp)
                    ) {
                        StorageDonut(
                            libraryBytes = summary.libraryBytes,
                            appBytes = summary.appBytes
                        )
                        StorageSummary(
                            libraryBytes = summary.libraryBytes,
                            appBytes = summary.appBytes,
                            artworkAndJsonCount = summary.artworkAndJsonCount,
                            verifiedStems = summary.verifiedStems,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            AnimatedContent(
                targetState = availabilityMessage,
                transitionSpec = {
                    fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                        fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                },
                label = "storage-availability"
            ) { message ->
                if (message != null) {
                    Text(
                        message,
                        color = if (availability == LibraryInventoryAvailability.UNAVAILABLE) {
                            Coral
                        } else {
                            TextSecondary
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageDonut(
    libraryBytes: Long,
    appBytes: Long,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    val safeLibraryBytes = libraryBytes.coerceAtLeast(0L)
    val safeAppBytes = appBytes.coerceAtLeast(0L)
    val totalBytes = safeLibraryBytes + safeAppBytes
    val libraryFraction by animateFloatAsState(
        targetValue = if (totalBytes > 0L) {
            safeLibraryBytes.toFloat() / totalBytes.toFloat()
        } else {
            0f
        },
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.PROGRESS_CHANGE),
        label = "storage-library-fraction"
    )
    val visibleFraction by animateFloatAsState(
        targetValue = if (totalBytes > 0L) 1f else 0f,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.PROGRESS_CHANGE),
        label = "storage-used-fraction"
    )
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Column(
        modifier
            .clearAndSetSemantics {
                contentDescription = "${formatStorageBytes(totalBytes)} used"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(126.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round)
                val arcInset = stroke.width * .5f
                val arcTopLeft = Offset(arcInset, arcInset)
                val arcSize = Size(
                    width = (size.width - stroke.width).coerceAtLeast(0f),
                    height = (size.height - stroke.width).coerceAtLeast(0f)
                )
                drawArc(
                    color = Outline,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = stroke
                )
                if (visibleFraction > 0f) {
                    val librarySweep = 360f * libraryFraction * visibleFraction
                    val appSweep = 360f * (1f - libraryFraction) * visibleFraction
                    if (librarySweep > 0f) {
                        drawArc(
                            color = PrimaryPurple,
                            startAngle = -90f,
                            sweepAngle = librarySweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = stroke
                        )
                    }
                    if (appSweep > 0f) {
                        drawArc(
                            color = SYSTEM_CONNECTION_SECONDARY_ACCENT,
                            startAngle = -90f + librarySweep,
                            sweepAngle = appSweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = stroke
                        )
                    }
                }
            }
            if (!largeText) StorageDonutTotal(totalBytes)
        }
        if (largeText) StorageDonutTotal(totalBytes)
    }
}

@Composable
private fun StorageDonutTotal(totalBytes: Long) {
    val motion = LocalStemwiseMotion.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = formatStorageBytes(totalBytes),
            transitionSpec = {
                fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                    fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
            },
            label = "storage-total"
        ) { total ->
            Text(
                total,
                modifier = Modifier.stemwiseMarquee(),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1
            )
        }
        Text(
            androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_used),
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StorageSummary(
    libraryBytes: Long,
    appBytes: Long,
    artworkAndJsonCount: Int,
    verifiedStems: Int,
    modifier: Modifier = Modifier
) {
    val labels = listOf(
        // Inventory currently owns the aggregate Library byte total. Do not relabel it as
        // originals until storage indexing publishes a type-specific original-audio total.
        "Library files" to formatStorageBytes(libraryBytes),
        "App data" to formatStorageBytes(appBytes),
        "Artwork + JSON" to androidx.compose.ui.res.pluralStringResource(app.stemwise.R.plurals.item_count, artworkAndJsonCount, artworkAndJsonCount)
    )
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            labels.forEach { (label, value) ->
                StorageSummaryRow(label = label, value = value)
            }
            ThinDivider()
            StorageVerifiedStems(verifiedStems, Modifier.fillMaxWidth())
        }
    } else {
        Box(modifier.height(SYSTEM_STORAGE_SUMMARY_HEIGHT_DP.dp)) {
            labels.forEachIndexed { index, (label, value) ->
                StorageSummaryRow(
                    label = label,
                    value = value,
                    modifier = Modifier.offset(y = (index * 40).dp)
                )
            }
            ThinDivider(Modifier.offset(y = SYSTEM_STORAGE_SUMMARY_DIVIDER_TOP_DP.dp))
            StorageVerifiedStems(
                verifiedStems,
                Modifier
                    .fillMaxWidth()
                    .offset(y = SYSTEM_STORAGE_SUMMARY_VERIFIED_STEMS_TOP_DP.dp)
            )
        }
    }
}

internal const val SYSTEM_STORAGE_SUMMARY_HEIGHT_DP = 152f
internal const val SYSTEM_STORAGE_SUMMARY_DIVIDER_TOP_DP = 115f
internal const val SYSTEM_STORAGE_SUMMARY_VERIFIED_STEMS_TOP_DP = 125f

@Composable
private fun StorageSummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).stemwiseMarquee(),
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                    fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
            },
            label = "storage-summary-value"
        ) { visibleValue ->
            Text(
                visibleValue,
                modifier = Modifier.stemwiseMarquee(),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StorageVerifiedStems(count: Int, modifier: Modifier = Modifier) {
    val motion = LocalStemwiseMotion.current
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
        },
        label = "storage-verified-stems",
        modifier = modifier
    ) { visibleCount ->
        Text(
            androidx.compose.ui.res.pluralStringResource(app.stemwise.R.plurals.stems_verified, visibleCount, visibleCount),
            color = PrimaryBright,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().stemwiseMarquee()
        )
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val decimals = if (value >= 100.0 || unit == 0) 0 else 1
    return String.format(java.util.Locale.ROOT, "%.${decimals}f %s", value, units[unit])
}

private fun readableLibraryPath(state: LibraryRootState): String {
    val documentId = state.treeUri?.let { uri ->
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    }
    if (documentId.isNullOrBlank()) return state.displayName ?: "Selected folder"
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
    val relative = documentId.substringAfter(':', missingDelimiterValue = documentId)
        .trim('/')
        .replace("/", " / ")
    val root = when (volume.lowercase()) {
        "primary" -> "Internal storage"
        "home" -> "Documents"
        "" -> ""
        else -> state.displayName ?: "Storage"
    }
    return listOf(root, relative)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
        .ifBlank { state.displayName ?: "Selected folder" }
}

@Composable
internal fun ThinDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(StemwisePalette.subtleDivider))
}
