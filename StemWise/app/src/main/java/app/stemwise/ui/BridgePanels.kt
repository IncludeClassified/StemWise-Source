package app.stemwise.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stemwise.R
import app.stemwise.bridge.BridgeModelCatalog
import app.stemwise.bridge.BridgeSongSubmission
import app.stemwise.bridge.bridgeHasMultipleLinks
import app.stemwise.bridge.BridgeSongTask
import app.stemwise.bridge.BridgeUiState
import app.stemwise.bridge.BridgeConnectionProfile
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.OutlineStrong
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun BridgeSongControls(
    state: BridgeUiState,
    onSubmit: suspend (BridgeSongSubmission) -> Result<Unit>,
    onLoadModels: suspend () -> Result<BridgeModelCatalog>,
    initialText: String = "",
    onBusyChanged: (Boolean) -> Unit = {},
    onWakeForOptions: (suspend () -> Result<Unit>)? = null,
    initialSubmission: BridgeSongSubmission? = null,
    canReplaceSubmission: Boolean = false,
    dialogActions: StemwiseDialogActionScope? = null
) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    var modelId by rememberSaveable(initialSubmission) { mutableStateOf(initialSubmission?.model.orEmpty()) }
    var effort by rememberSaveable(initialSubmission) { mutableStateOf(initialSubmission?.effort.orEmpty()) }
    var serviceTier by rememberSaveable(initialSubmission) { mutableStateOf(initialSubmission?.serviceTier.orEmpty()) }
    var initialized by rememberSaveable(initialSubmission) { mutableStateOf(initialSubmission != null) }
    var catalog by remember { mutableStateOf<BridgeModelCatalog?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    var catalogRevision by remember { mutableStateOf(0) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var waking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val initialCatalogLoading = loadingModels && catalog == null
    var showModelSkeleton by remember(initialCatalogLoading) { mutableStateOf(false) }
    LaunchedEffect(initialCatalogLoading) {
        if (initialCatalogLoading) {
            delay(300)
            showModelSkeleton = true
        }
    }
    val scope = rememberCoroutineScope()
    val addFailure = stringResource(R.string.bridge_add_failure)
    LaunchedEffect(state.enrollmentRequired, catalogRevision) {
        if (state.enrollmentRequired) return@LaunchedEffect
        loadingModels = true
        catalogError = null
        try {
            captureUserOperation { onLoadModels().getOrThrow() }
                .onSuccess { loaded ->
                    catalog = loaded
                    if (!initialized) {
                        loaded.models.singleOrNull { it.isDefault }?.let { model ->
                            modelId = model.id
                            effort = model.defaultEffort.orEmpty()
                            serviceTier = model.defaultServiceTier.orEmpty()
                        }
                        initialized = true
                    }
                }
                .onFailure { catalogError = bridgeOperationFailureMessage(it, "Could not load Bridge models. Retry to choose processing options.") }
        } finally { loadingModels = false }
    }
    val model = catalog?.models?.singleOrNull { it.id == modelId }
    val submission = BridgeSongSubmission(text, modelId, effort, serviceTier)
    val validationError = when {
        bridgeHasMultipleLinks(text) -> "Enter one song link. Multiple links cannot be sent together."
        catalog == null -> null
        else -> runCatching { checkNotNull(catalog).validate(submission) }.exceptionOrNull()?.let {
            it.message ?: "Review the song link and processing options before sending."
        }
    }
    val canSubmit = text.isNotBlank() && catalog != null && catalogError == null &&
        validationError == null && !submitting && !waking && !loadingModels && !state.loading && !state.enrollmentRequired
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text(stringResource(R.string.bridge_song_link)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None
            ),
            enabled = !submitting && initialSubmission == null,
            isError = text.isNotBlank() && validationError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.connection != null || catalog != null || (loadingModels && catalogError == null)) {
            BridgeChoiceMenu(
                label = "Model", selected = model?.label ?: modelId.ifBlank { "Choose model" },
                loading = showModelSkeleton,
                options = catalog?.models.orEmpty().map { it.id to it.label },
                enabled = (initialSubmission == null || canReplaceSubmission) && !submitting && !loadingModels && catalogError == null,
                onSelect = { id ->
                    modelId = id
                    val selected = catalog?.models?.singleOrNull { it.id == id }
                    effort = selected?.defaultEffort.orEmpty()
                    serviceTier = selected?.defaultServiceTier.orEmpty()
                    error = null
                }
            )
            BridgeChoiceMenu(
                label = "Intelligence", selected = bridgeEffortLabel(effort).ifBlank { "Choose intelligence" },
                loading = showModelSkeleton,
                options = model?.efforts.orEmpty().map { it to bridgeEffortLabel(it) },
                enabled = (initialSubmission == null || canReplaceSubmission) && !submitting && !loadingModels && catalogError == null,
                onSelect = { effort = it; error = null }
            )
            BridgeChoiceMenu(
                label = "Speed", selected = model?.serviceTiers?.singleOrNull { it.id == serviceTier }?.name
                    ?: serviceTier.ifBlank { if (model == null) "Choose speed" else "Default speed" },
                loading = showModelSkeleton,
                options = if (model == null) emptyList() else listOf("" to "Default speed") + model.serviceTiers.map { it.id to it.name },
                enabled = (initialSubmission == null || canReplaceSubmission) && !submitting && !loadingModels && catalogError == null,
                onSelect = { serviceTier = it; error = null }
            )
            model?.serviceTiers?.singleOrNull { it.id == serviceTier }?.description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = TextSecondary, fontSize = 13.sp)
            }
        }
        if (model != null && model.efforts.isEmpty()) {
            BridgeError("Bridge has not advertised intelligence options for this model. Refresh options or choose another model.")
        }
        listOfNotNull(catalogError, state.error, state.connectionIssue).distinct().forEach { BridgeError(it) }
        if (state.connection != null && !state.enrollmentRequired && (loadingModels || catalogError != null)) {
            StemwiseButton(enabled = !submitting && !loadingModels,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CircleShape,
                onClick = { catalogRevision++ }) { Text(when {
                    loadingModels -> "Loading Bridge options…"
                    catalogError != null -> "Retry Bridge options"
                    else -> "Refresh Bridge options"
                }) }
        }
        if (state.connection != null && catalogError != null && onWakeForOptions != null) {
            StemwiseButton(enabled = !waking && !submitting && !loadingModels,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CircleShape, onClick = {
                if (waking || submitting || dialogActions?.beginBusyAction() == false) return@StemwiseButton
                waking = true
                onBusyChanged(true)
                scope.launch {
                    try {
                        captureUserOperation { onWakeForOptions().getOrThrow() }
                            .onSuccess { catalogRevision++ }
                            .onFailure { error = bridgeOperationFailureMessage(it, "Could not wake the PC. Try again when it is reachable.") }
                    } finally { waking = false; onBusyChanged(false); dialogActions?.endBusyAction() }
                }
            }) { Text(if (waking) "Waking PC…" else "Wake PC and load options") }
        }
        if (text.isNotBlank()) validationError?.let { BridgeError(it) }
        StemwiseButton(
            onClick = {
                if (submitting || !canSubmit || dialogActions?.beginBusyAction() == false) return@StemwiseButton
                submitting = true
                onBusyChanged(true)
                error = null
                scope.launch {
                    try {
                        captureUserOperation { onSubmit(submission).getOrThrow() }
                            .onSuccess { text = "" }
                            .onFailure { error = bridgeOperationFailureMessage(it, addFailure) }
                    } finally { submitting = false; onBusyChanged(false); dialogActions?.endBusyAction() }
                }
            },
            enabled = canSubmit && dialogActions?.actionsEnabled != false,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(stringResource(if (submitting) R.string.bridge_sending else R.string.bridge_send)) }
        if (state.enrollmentRequired) {
            Text(stringResource(R.string.bridge_connect_to_add_song), color = TextSecondary, fontSize = 13.sp)
        }
        error?.takeUnless { it in listOf(catalogError, state.error, state.connectionIssue) }?.let { BridgeError(it) }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
internal fun BridgeSongDialog(
    state: BridgeUiState,
    onSubmit: suspend (BridgeSongSubmission) -> Result<Unit>,
    onLoadModels: suspend () -> Result<BridgeModelCatalog>,
    onDismiss: () -> Unit,
    initialText: String = "",
    title: String = "Send to PC",
    onWakeForOptions: (suspend () -> Result<Unit>)? = null,
    initialSubmission: BridgeSongSubmission? = null,
    canReplaceSubmission: Boolean = false
) {
    var busy by remember { mutableStateOf(false) }
    StemwiseAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
        title = { Text(title) },
        text = {
            val dialogActions = this
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (initialSubmission != null && !canReplaceSubmission) Text("Saved request · cancel in Activity to change these options.", color = TextSecondary)
                state.connection?.let { Text("PC: ${it.host}", color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall) }
                BridgeSongControls(state, initialText = initialText, onLoadModels = onLoadModels,
                    onWakeForOptions = onWakeForOptions, initialSubmission = initialSubmission, canReplaceSubmission = canReplaceSubmission,
                    dialogActions = dialogActions,
                    onBusyChanged = { busy = it }, onSubmit = { submission ->
                        onSubmit(submission).onSuccess { onDismiss() }
                    })
            }
        },
        confirmButton = {},
        dismissButton = { StemwiseTextButton(enabled = !busy, onClick = { if (!busy) dismiss() }) { Text("Close") } },
        containerColor = SurfaceRaised
    )
}

private fun bridgeEffortLabel(value: String): String = when (value) {
    "low" -> "Low"
    "medium" -> "Balanced"
    "high" -> "High"
    "xhigh" -> "Very high"
    "max" -> "Maximum"
    else -> value
}

@Composable
private fun BridgeChoiceMenu(label: String, selected: String, options: List<Pair<String, String>>,
    enabled: Boolean, loading: Boolean = false, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().testTag("bridge-choice-$label")) {
        StemwiseTextButton(enabled = enabled && options.isNotEmpty(), shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$label: $selected", color = TextPrimary, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = if (loading) Modifier.clearAndSetSemantics {
                        contentDescription = "$label loading"
                    }.drawWithContent {
                        drawRoundRect(OutlineStrong, topLeft = Offset(0f, size.height * .2f),
                            size = Size(size.width, size.height * .6f), cornerRadius = CornerRadius(4.dp.toPx()))
                    }.weight(1f) else Modifier.weight(1f))
                Icon(StemwiseIcons.Disclosure, contentDescription = null, modifier = Modifier.size(20.dp), tint = TextSecondary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

@Composable
internal fun BridgeTaskCard(task: BridgeSongTask, onRetry: suspend (String) -> Result<Unit>,
    onForget: suspend (String) -> Result<Unit>,
    onCancel: suspend (String) -> Result<Unit>, diagnostics: suspend (String) -> Result<String>,
    onReview: () -> Unit = {},
    onRetryWake: suspend (String) -> Result<Unit> = { Result.failure(IllegalStateException("Wake is unavailable")) }) {
    var pendingAction by remember(task.id) { mutableStateOf<String?>(null) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var detailsExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val presentation = bridgeTaskPresentation(task)
    val scope = rememberCoroutineScope()
    val resumeFailure = stringResource(R.string.bridge_resume_failure)
    val context = androidx.compose.ui.platform.LocalContext.current
    fun runAction(kind: String, failure: String, action: suspend () -> Unit) {
        if (pendingAction != null) return
        pendingAction = kind
        error = null
        scope.launch {
            try {
                captureUserOperation { action() }
                    .onFailure { error = bridgeOperationFailureMessage(it, failure) }
            } finally { pendingAction = null }
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Surface, RoundedCornerShape(18.dp))
            .border(1.dp, OutlineStrong, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {
            stateDescription = presentation.status
        }) {
            Text(presentation.title, style = MaterialTheme.typography.titleMedium,
                color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(presentation.status, style = MaterialTheme.typography.bodyLarge,
                color = if (presentation.tone == BridgeTaskTone.ERROR) Coral else TextSecondary)
            if (task.deliveryPending) {
                Text("Delivery confirmation pending", color = TextSecondary, fontSize = 13.sp)
            }
            if (task.deliveryUnavailable) {
                Text("Delivery confirmation unavailable", color = TextSecondary, fontSize = 13.sp)
            }
        }
        presentation.issue?.let {
            val completed = task.phase == app.stemwise.bridge.BridgeSongPhase.IMPORTED
            if (completed) Text("Task notices", color = TextSecondary,
                style = MaterialTheme.typography.labelLarge)
            BridgeError(it.detail, isError = !completed && presentation.tone != BridgeTaskTone.PROGRESS)
        }
        if (task.isDraft || task.phase == app.stemwise.bridge.BridgeSongPhase.REVIEW_REQUIRED) {
            StemwiseButton(onClick = onReview, enabled = pendingAction == null,
                modifier = Modifier.fillMaxWidth()) { Text("Review and send") }
        }
        if (task.canRetryWake) {
            StemwiseTextButton(enabled = pendingAction == null, onClick = {
                runAction("wake", "Could not wake the PC. The reviewed request is kept.") { onRetryWake(task.id).getOrThrow() }
            }) { Text(if (pendingAction == "wake") "Waking PC…" else "Retry wake") }
        }
        if (task.canRetry) {
            StemwiseTextButton(
                enabled = pendingAction == null,
                onClick = {
                    runAction("resume", resumeFailure) { onRetry(task.id).getOrThrow() }
                }
            ) { Text(if (task.localImportPending) {
                if (pendingAction == "resume") "Retrying import…" else "Retry import"
            } else stringResource(if (pendingAction == "resume") R.string.bridge_resuming else R.string.bridge_resume_song)) }
        }
        StemwiseTextButton(onClick = { detailsExpanded = !detailsExpanded }) {
            Text(if (detailsExpanded) "Hide task details" else "Task details")
        }
        if (detailsExpanded) {
            Text(task.sourceUrl.ifBlank { "YouTube ID: ${task.videoId}" },
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text("Status: ${task.state}\nStage: ${task.stage}",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (task.canCancel) {
                StemwiseTextButton(enabled = pendingAction == null, onClick = {
                    runAction("cancel", "Could not request cancellation") { onCancel(task.id).getOrThrow() }
                }) { Text(if (pendingAction == "cancel") "Requesting cancellation…" else "Cancel PC task") }
            }
            if (!task.id.startsWith("damaged:")) {
                StemwiseTextButton(enabled = pendingAction == null, onClick = {
                    runAction("diagnostics", "Could not copy task diagnostics") {
                        val text = diagnostics(task.id).getOrThrow()
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("StemWise task diagnostics", text))
                    }
                }) { Text(if (pendingAction == "diagnostics") "Copying task diagnostics…" else "Copy task diagnostics") }
            }
            if (task.canForget) {
                StemwiseTextButton(enabled = pendingAction == null, onClick = {
                    runAction("forget", resumeFailure) { onForget(task.id).getOrThrow() }
                }) { Text(if (pendingAction == "forget") "Forgetting task…" else stringResource(
                    if (task.state == "OUTBOX_DAMAGED") R.string.bridge_discard_damaged_record else R.string.bridge_forget_task)) }
            }
        }
        error?.let { BridgeError(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BridgeConnectionPanel(
    state: BridgeUiState,
    onSaveConnection: suspend (BridgeConnectionProfile) -> Result<Unit>,
    onEnroll: suspend () -> Result<String?>,
    onRefresh: suspend () -> Result<Unit>,
    onWakePc: suspend () -> Result<Unit>,
    onPairAgain: (suspend () -> Result<Unit>)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actionFailure = stringResource(R.string.bridge_action_failure)
    val missingAuthorization = stringResource(R.string.bridge_authorization_missing)
    val invalidAuthorization = stringResource(R.string.bridge_authorization_invalid)
    val unknown = stringResource(R.string.bridge_unknown)
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var editingConnection by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable(state.connection) { mutableStateOf(state.connection?.host.orEmpty()) }
    var bridgeId by rememberSaveable(state.connection) { mutableStateOf(state.connection?.bridgeId.orEmpty()) }
    var pcId by rememberSaveable(state.connection) { mutableStateOf(state.connection?.pcId.orEmpty()) }
    val busy = pendingAction != null
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    var resumeRevision by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                now = android.os.SystemClock.elapsedRealtime()
                resumeRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.observedAtElapsedMs, state.validForMs, resumeRevision) {
        now = android.os.SystemClock.elapsedRealtime()
        if (bridgeConnectionFresh(state, now)) {
            val remaining = state.validForMs - (now - checkNotNull(state.observedAtElapsedMs))
            delay(remaining)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    val fresh = bridgeConnectionFresh(state, now)
    fun runAction(kind: String, action: suspend () -> Unit) {
        if (pendingAction != null || state.loading) return
        pendingAction = kind
        error = null
        scope.launch {
            try {
                captureUserOperation { action() }
                    .onFailure { error = bridgeOperationFailureMessage(it, actionFailure) }
            } finally {
                pendingAction = null
            }
        }
    }
    val connectionStatus = when {
        state.loading -> "Checking"
        busy -> "Working"
        state.connection == null -> "Not connected"
        state.enrollmentRequired || state.loginUrl != null -> "Authorize"
        state.error != null || state.connectionIssue != null -> "Retry"
        fresh -> "Checked"
        else -> "Unknown"
    }
    val statusKind = when {
        state.loading || busy -> SystemConnectionStatusVisualKind.PENDING
        state.error != null || state.connectionIssue != null -> SystemConnectionStatusVisualKind.ERROR
        fresh && state.connection != null && !state.enrollmentRequired && state.loginUrl == null -> SystemConnectionStatusVisualKind.READY
        else -> SystemConnectionStatusVisualKind.NEUTRAL
    }
    SystemConnectionCard(
        icon = StemwiseIcons.CloudUpload,
        title = stringResource(R.string.bridge_title),
        detail = state.connection?.host ?: "No PC connection saved",
        state = connectionStatus,
        statusKind = statusKind,
        accent = if (statusKind == SystemConnectionStatusVisualKind.ERROR) Coral else app.stemwise.ui.theme.PrimaryPurple
    ) {
        state.connection?.let { profile ->
            Text("Bridge: ${profile.bridgeId} · PC: ${profile.pcId}",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (state.connection != null) Text(stringResource(R.string.bridge_connection_status,
            if (fresh) state.piStatus else unknown, if (fresh) state.pcStatus else unknown),
            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StemwiseTextButton(enabled = !busy && !state.loading, onClick = {
                host = state.connection?.host.orEmpty()
                bridgeId = state.connection?.bridgeId.orEmpty()
                pcId = state.connection?.pcId.orEmpty()
                error = null
                editingConnection = true
            }) { Text(stringResource(R.string.bridge_edit_connection)) }
            if (state.enrollmentRequired || state.loginUrl != null) {
                StemwiseTextButton(enabled = !busy && !state.loading && !editingConnection && state.connection != null, onClick = {
                    runAction("connect") {
                        val url = state.loginUrl ?: onEnroll().getOrThrow()
                            ?: throw IllegalStateException(missingAuthorization)
                        check(validBridgeAuthorizationUrl(url)) { invalidAuthorization }
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                }) { Text(stringResource(if (pendingAction == "connect") R.string.bridge_connecting else R.string.bridge_connect)) }
            }
            StemwiseTextButton(enabled = !busy && !state.loading && !editingConnection && state.connection != null,
                onClick = { runAction("refresh") { onRefresh().getOrThrow() } }) {
                Text(stringResource(if (state.loading || pendingAction == "refresh") R.string.bridge_checking else R.string.bridge_check_connection))
            }
            onPairAgain?.let { pair ->
                StemwiseTextButton(enabled = !busy && !state.loading && !editingConnection && state.connection != null,
                    onClick = { runAction("pair") { pair().getOrThrow() } }) { Text("Pair again") }
            }
            StemwiseTextButton(enabled = !busy && !state.loading && !state.enrollmentRequired &&
                !editingConnection && state.connection != null,
                onClick = { runAction("wake") { onWakePc().getOrThrow() } }) {
                Text(stringResource(if (pendingAction == "wake") R.string.bridge_waking else R.string.bridge_wake_pc))
            }
        }
        state.error?.let { BridgeError(it) }
        state.connectionIssue?.takeUnless { it == state.error }?.let { BridgeError(it) }
        if (!editingConnection) error?.takeUnless { it == state.error || it == state.connectionIssue }?.let { BridgeError(it) }
    }
    if (editingConnection) StemwiseAlertDialog(
        onDismissRequest = { editingConnection = false; error = null },
        dismissEnabled = !busy,
        title = { Text(stringResource(R.string.bridge_edit_connection)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(host, { host = it; error = null },
                    label = { Text(stringResource(R.string.bridge_address)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    enabled = !busy && !state.loading, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bridgeId, { bridgeId = it; error = null },
                    label = { Text(stringResource(R.string.bridge_identity)) }, singleLine = true,
                    enabled = !busy && !state.loading, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pcId, { pcId = it; error = null },
                    label = { Text(stringResource(R.string.bridge_pc_identity)) }, singleLine = true,
                    enabled = !busy && !state.loading, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.bridge_connection_scope), color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
                error?.let { BridgeError(it) }
            }
        },
        confirmButton = {
            StemwiseButton(enabled = actionsEnabled && !busy && !state.loading &&
                host.isNotBlank() && bridgeId.isNotBlank() && pcId.isNotBlank(),
                onClick = save@{
                    if (busy || state.loading || !beginBusyAction()) return@save
                    runAction("save") {
                        try {
                            onSaveConnection(BridgeConnectionProfile(host.trim(), bridgeId.trim(), pcId.trim())).getOrThrow()
                            endBusyAction()
                            closeThen { editingConnection = false; error = null }
                        } finally { endBusyAction() }
                    }
                }) { Text(stringResource(if (pendingAction == "save") R.string.bridge_saving_connection else R.string.bridge_save_connection)) }
        },
        dismissButton = {
            StemwiseTextButton(enabled = !busy, onClick = { dismiss() }) { Text(stringResource(android.R.string.cancel)) }
        },
        containerColor = SurfaceRaised
    )
}

@Composable
internal fun BridgeError(message: String, isError: Boolean = true) {
    val issue = bridgeIssuePresentation(message)
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    Text(issue.summary, color = if (isError) Coral else TextSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (issue.detail != issue.summary) {
        StemwiseTextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide error details" else "Error details")
        }
        if (expanded) Text(issue.detail, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}
