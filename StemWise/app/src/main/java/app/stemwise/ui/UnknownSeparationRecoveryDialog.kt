package app.stemwise.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.stemwise.R
import app.stemwise.data.RootAuthority
import app.stemwise.integration.SeparationRecoveryReview
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun UnknownSeparationRecoveryDialog(
    jobId: String,
    expectedRoot: RootAuthority?,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onReview: suspend (String) -> Result<SeparationRecoveryReview>,
    onRecover: suspend (SeparationRecoveryReview) -> Result<Unit>,
    onRecovered: () -> Unit
) {
    var remoteJobId by remember(jobId) { mutableStateOf("") }
    var recovering by remember(jobId) { mutableStateOf(false) }
    var review by remember(jobId) { mutableStateOf<SeparationRecoveryReview?>(null) }
    var recoveryError by remember(jobId) { mutableStateOf<String?>(null) }
    StemwiseAlertDialog(
        onDismissRequest = onDismiss,
        dismissEnabled = !recovering,
        title = { Text(stringResource(R.string.ui_retry_an_uncertain_mvsep_request)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.ui_mvsep_may_already_be_processing_the_first))
                OutlinedTextField(
                    value = remoteJobId,
                    onValueChange = { value -> runIfOpen { remoteJobId = value; recoveryError = null; review = null } },
                    label = { Text("MVSEP job ID") },
                    singleLine = true,
                    enabled = actionsEnabled && !recovering,
                    isError = recoveryError != null
                )
                review?.let { current ->
                    Text("Local source: ${current.songTitle}")
                    Text(current.sourceName, color = TextSecondary)
                    Text("Source fingerprint: ${current.sourceSha256}", color = TextSecondary, fontSize = 12.sp)
                    Text("Frozen configuration: ${current.configuration}", color = TextSecondary)
                    Text("Provider source: ${current.providerSourceName ?: "Not supplied"}", color = TextSecondary)
                    Text("Provider model: ${current.providerModelName ?: "Not supplied"}", color = TextSecondary)
                    Text("Confirm that this MVSEP job uses the local source above. Matching duration and file format cannot prove it is the same song.")
                }
                recoveryError?.let { Text(it, color = Coral) }
            }
        },
        confirmButton = {
            StemwiseTextButton(
                enabled = actionsEnabled && !recovering && remoteJobId.isNotBlank() && expectedRoot != null,
                onClick = recover@{
                    if (recovering || remoteJobId.isBlank() || expectedRoot == null || !beginBusyAction()) return@recover
                    val acceptedReview = review
                    val acceptedRemoteJobId = remoteJobId.trim()
                    recovering = true
                    scope.launch {
                        try {
                            if (acceptedReview == null) {
                                onReview(acceptedRemoteJobId)
                                    .onSuccess { review = it }
                                    .onFailure { recoveryError = operationFailureMessage(it, "Could not review this MVSEP job") }
                            } else {
                                onRecover(acceptedReview)
                                    .onSuccess {
                                        endBusyAction()
                                        closeThen(onRecovered)
                                    }
                                    .onFailure { recoveryError = operationFailureMessage(it, "Could not recover this MVSEP job") }
                            }
                        } finally {
                            recovering = false
                            endBusyAction()
                        }
                    }
                }
            ) { Text(if (review == null) "Review job" else "Confirm source and recover") }
        },
        dismissButton = {
            StemwiseTextButton(enabled = actionsEnabled && !recovering, onClick = { dismiss() }) {
                Text(stringResource(R.string.ui_cancel_2), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
            }
        },
        containerColor = SurfaceRaised
    )
}
