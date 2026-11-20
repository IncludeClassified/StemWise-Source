package app.stemwise.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import app.stemwise.model.Song
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.semanticSpec

internal data class PlaylistConfirmationPresentation(
    val playlistName: String,
    val candidateSongSummary: String,
    val existingLibrarySummary: String,
    val prompt: String
)

internal fun playlistConfirmationPresentation(
    playlistName: String,
    candidateSongCount: Int,
    currentPlaylistCount: Int,
    existingLibraryCount: Int,
    localOnlyCount: Int
): PlaylistConfirmationPresentation {
    require(playlistName.isNotBlank()) { "PLAYLIST_NAME_UNAVAILABLE" }
    require(candidateSongCount >= 0) { "PLAYLIST_COUNT_INVALID" }
    require(currentPlaylistCount >= 0 && localOnlyCount >= 0) {
        "EXISTING_LIBRARY_COUNT_INVALID"
    }
    require(existingLibraryCount == currentPlaylistCount + localOnlyCount) {
        "EXISTING_LIBRARY_PARTITION_INCONSISTENT"
    }
    val candidateNoun = if (candidateSongCount == 1) "song" else "songs"
    val existingNoun = if (currentPlaylistCount == 1) "song" else "songs"
    val otherLibraryNoun = if (localOnlyCount == 1) "song" else "songs"
    return PlaylistConfirmationPresentation(
        playlistName = playlistName,
        candidateSongSummary = "$candidateSongCount $candidateNoun found",
        existingLibrarySummary =
            "$currentPlaylistCount current-playlist $existingNoun · " +
                "$localOnlyCount other Library $otherLibraryNoun",
        prompt = "Keep imported songs and use this playlist for ordering and metadata? " +
            "To add playable songs, connect the PC Bridge in Settings, then use Send to PC in Activity. " +
            "Review discovered song drafts before sending, or import a complete song ZIP manually."
    )
}

private data class PlaylistOfferVisualState(
    val presentation: PlaylistConfirmationPresentation?,
    val checking: Boolean,
    val error: String?,
    val editable: Boolean
)

private enum class PlaylistOfferActionState { CANCEL, RETRY, CHECK, CONFIRM }

@Composable
internal fun PlaylistOfferDialog(
    presentation: PlaylistConfirmationPresentation?,
    checking: Boolean,
    error: String?,
    initialEditableText: String?,
    onDismissRequest: () -> Unit,
    onRetry: (() -> Unit)?,
    onCheckEdited: ((String) -> Unit)?,
    keepAndAddEnabled: Boolean,
    onKeepAndAdd: () -> Boolean,
    onKeepAndAddClosed: () -> Unit
) {
    val motion = LocalStemwiseMotion.current
    var editedText by rememberSaveable(initialEditableText) {
        mutableStateOf(initialEditableText.orEmpty())
    }
    val visualState = PlaylistOfferVisualState(
        presentation = presentation,
        checking = checking,
        error = error,
        editable = initialEditableText != null && onCheckEdited != null
    )
    val actionState = when {
        presentation != null -> PlaylistOfferActionState.CONFIRM
        onCheckEdited != null -> PlaylistOfferActionState.CHECK
        error != null && onRetry != null -> PlaylistOfferActionState.RETRY
        else -> PlaylistOfferActionState.CANCEL
    }
    StemwiseAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { PlaylistDialogTitle() },
        text = {
            AnimatedContent(
                targetState = visualState,
                transitionSpec = {
                    fadeIn(
                        motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)
                    ).togetherWith(
                        fadeOut(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW))
                    )
                },
                label = "playlist-offer-content"
            ) { state ->
                val confirmed = state.presentation
                if (confirmed == null) {
                    Column {
                        if (state.editable) {
                            OutlinedTextField(
                                value = editedText,
                                onValueChange = { editedText = it.take(16_384) },
                                label = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_playlist_link)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    autoCorrectEnabled = false,
                                    capitalization = KeyboardCapitalization.None
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (state.checking || state.error == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = PrimaryPurple,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_checking_playlist_details),
                                    color = TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        state.error?.let { message ->
                            Text(
                                text = message,
                                color = Coral,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_nothing_was_changed),
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    Column {
                        Text(
                            text = confirmed.playlistName,
                            color = PrimaryPurple,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = confirmed.candidateSongSummary,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(28.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 88.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Surface)
                                .border(1.dp, Outline, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_existing_library),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = confirmed.existingLibrarySummary,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = confirmed.prompt,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            val dialogActions = this
            AnimatedContent(
                targetState = actionState,
                transitionSpec = {
                    fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)).togetherWith(
                        fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    )
                },
                label = "playlist-offer-actions"
            ) { state ->
                when (state) {
                    PlaylistOfferActionState.CONFIRM -> PlaylistConfirmationActions(
                        onCancel = dialogActions::dismiss,
                        keepAndAddEnabled = keepAndAddEnabled,
                        onKeepAndAdd = {
                            dialogActions.closeAfterStarting(
                                startAction = onKeepAndAdd,
                                afterClose = onKeepAndAddClosed
                            )
                        }
                    )
                    PlaylistOfferActionState.CHECK,
                    PlaylistOfferActionState.RETRY -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StemwiseTextButton(
                            onClick = dialogActions::dismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_cancel), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                        }
                        StemwiseTextButton(
                            onClick = {
                                dialogActions.runIfOpen {
                                    if (state == PlaylistOfferActionState.CHECK) {
                                        onCheckEdited?.invoke(editedText)
                                    } else {
                                        onRetry?.invoke()
                                    }
                                }
                            },
                            enabled = dialogActions.actionsEnabled &&
                                (state != PlaylistOfferActionState.CHECK || editedText.isNotBlank()),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (state == PlaylistOfferActionState.CHECK) {
                                    "Check link"
                                } else {
                                    "Try again"
                                },
                                modifier = Modifier.stemwiseMarquee(),
                                maxLines = 1
                            )
                        }
                    }
                    PlaylistOfferActionState.CANCEL -> StemwiseTextButton(
                        onClick = dialogActions::dismiss
                    ) {
                        Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_cancel), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                    }
                }
            }
        },
        containerColor = SurfaceRaised
    )
}

@Composable
private fun PlaylistDialogTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = StemwiseIcons.QueueMusic,
            contentDescription = null,
            tint = PrimaryPurple,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_connect_this_playlist),
            color = TextPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PlaylistConfirmationActions(
    onCancel: () -> Unit,
    keepAndAddEnabled: Boolean,
    onKeepAndAdd: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocusRequester.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 344.dp || LocalDensity.current.fontScale >= 1.3f
        if (stacked) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlaylistDialogActionButton(
                    label = "Cancel",
                    enabled = true,
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(cancelFocusRequester)
                )
                PlaylistDialogActionButton(
                    label = "Keep & add",
                    enabled = keepAndAddEnabled,
                    emphasized = true,
                    onClick = onKeepAndAdd,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlaylistDialogActionButton(
                    label = "Cancel",
                    enabled = true,
                    onClick = onCancel,
                    modifier = Modifier
                        .width(104.dp)
                        .focusRequester(cancelFocusRequester)
                )
                PlaylistDialogActionButton(
                    label = "Keep & add",
                    enabled = keepAndAddEnabled,
                    emphasized = true,
                    onClick = onKeepAndAdd,
                    modifier = Modifier.width(110.dp)
                )
            }
        }
    }
}

@Composable
private fun PlaylistDialogActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    emphasized: Boolean = false,
    blockedReason: String? = null
) {
    val shape = remember { RoundedCornerShape(16.dp) }
    StemwiseButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                if (!enabled && blockedReason != null) {
                    stateDescription = blockedReason
                }
            },
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (emphasized && enabled) {
                StemwisePalette.selectedLibraryRowGradientStart
            } else {
                Surface
            },
            contentColor = if (emphasized) TextPrimary else TextSecondary,
            disabledContainerColor = Surface,
            disabledContentColor = TextSecondary.copy(alpha = .48f)
        ),
        border = BorderStroke(
            1.dp,
            if (emphasized && enabled) PrimaryPurple else Outline
        ),
        contentPadding = PaddingValues(horizontal = 10.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.stemwiseMarquee(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
