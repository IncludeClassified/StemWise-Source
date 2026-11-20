package app.stemwise.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.stemwise.ui.design.StemwiseButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stemwise.model.StemSource
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.model.StemSourceGroup
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseComposeGlyphs
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.design.stemwiseAuraToggleableAction
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary

/** Source-only consent: provider routing and model settings stay in the preparation owner. */
@Composable
internal fun SourceSelectionDialog(
    songId: String,
    availableSources: List<StemSource>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onConfirm: (List<String>) -> Boolean,
    onDismiss: () -> Unit,
    initialSelection: List<String> = StemSourceCatalogue.defaultSelection,
    songTitle: String = ""
) {
    var selectedIds by rememberSaveable(songId) { mutableStateOf(initialSelection.toList()) }
    var initialized by rememberSaveable(songId) { mutableStateOf(false) }
    val availableIds = availableSources.map { it.id }
    LaunchedEffect(loading, error, initialSelection) {
        if (!initialized && !loading && error == null) {
            selectedIds = initialSelection.toList()
            initialized = true
        }
    }
    val selectionIssue = sourceSelectionIssue(selectedIds, availableSources)
    val canPrepare = initialized && !loading && error == null && selectionIssue == null

    var query by rememberSaveable(songId) { mutableStateOf("") }
    var category by rememberSaveable(songId) { mutableStateOf<String?>(null) }
    var accepted by remember(songId) { mutableStateOf(false) }
    val visibleSources = availableSources.filter { source ->
        (category == null || source.group.name == category) &&
            (query.isBlank() || source.label.contains(query.trim(), ignoreCase = true))
    }
    Dialog(onDismissRequest = { if (!accepted) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SurfaceRaised, modifier = Modifier.safeDrawingPadding()
            .widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose song parts", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("source_selection_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (songTitle.isNotBlank()) item(key = "song-title") {
                        Text(songTitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    item(key = "description") {
                        Text("Unselected parts stay together as Remaining. Timing against Original is unverified.",
                            color = TextSecondary)
                    }
                    item(key = "search") {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("Search parts") }, modifier = Modifier.fillMaxWidth()
                                .testTag("source_selection_search"))
                    }
                    item(key = "categories") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                            StemSourceGroup.entries.forEach { group ->
                                FilterChip(selected = category == group.name, onClick = { category = group.name },
                                    label = { Text(group.label) })
                            }
                        }
                    }
                    val unavailable = selectedIds.filter { it !in availableIds }
                    if (initialized && !loading && error == null && unavailable.isNotEmpty()) {
                        item(key = "unavailable-selection") {
                            Text("Selected parts are no longer available. Remove them to review a new set.", color = TextSecondary)
                        }
                        items(unavailable, key = { "unavailable-$it" }) { id ->
                            val label = StemSourceCatalogue.sources.firstOrNull { it.id == id }?.label ?: id
                            StemwiseTextButton(onClick = { selectedIds = selectedIds.filterNot { it == id } },
                                modifier = Modifier.testTag("source_selection_remove_$id")) { Text("Remove $label") }
                        }
                    }
                    when {
                        loading -> item(key = "loading") {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text("Loading song parts…")
                            }
                        }
                        error != null || availableSources.isEmpty() -> item(key = "unavailable") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (error != null) "Could not load song parts. Your choices are kept. Try again."
                                    else "No song parts are available. Try again.",
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                StemwiseTextButton(onClick = onRetry) { Text("Try again") }
                            }
                        }
                        else -> {
                            if (visibleSources.isEmpty()) item(key = "no-matches") {
                                Text("No parts match. Try another search or category.")
                            }
                            StemSourceGroup.entries.forEach { group ->
                                val sources = visibleSources.filter { it.group == group }
                                if (sources.isNotEmpty()) {
                                    item(key = "group-${group.name}") {
                                        Text(group.label, Modifier.padding(top = 12.dp, bottom = 4.dp),
                                            style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                    }
                                    items(sources, key = { it.id }) { source ->
                                        SourceSelectionRow(source, source.id in selectedIds,
                                            sourceSelectionCandidateIssue(source.id, selectedIds, availableSources)) {
                                            selectedIds = sourceSelectionAfterToggle(source.id, selectedIds, availableSources)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(if (initialized) "${selectedIds.size} of ${StemSourceCatalogue.MAX_TARGETS} selected"
                    else "Loading current choices…",
                    style = MaterialTheme.typography.labelLarge, color = TextSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                selectionIssue?.takeIf { initialized && !loading && error == null }
                    ?.let { Text(it, color = TextSecondary) }
                StemwiseButton(enabled = canPrepare && !accepted, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                    val selection = selectedIds.toList()
                    if (!accepted && canPrepare && sourceSelectionIssue(selection, availableSources) == null) {
                        accepted = true
                        if (onConfirm(selection)) onDismiss() else accepted = false
                    }
                }) { Text("Prepare parts") }
                StemwiseTextButton(onClick = onDismiss, enabled = !accepted,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Back") }
            }
        }
    }
}

@Composable
private fun SourceSelectionRow(
    source: StemSource,
    checked: Boolean,
    issue: String?,
    onToggle: () -> Unit
) {
    val enabled = issue == null
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .testTag("source_selection_${source.id}")
            .stemwiseAuraToggleableAction(
                interactionSource = interactionSource,
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.stemWiseInteractionAura(
                enabled = enabled,
                selected = checked,
                interactionSource = interactionSource,
                reducedMotion = !LocalStemwiseMotion.current.enabled
            )
        )
        Icon(
            imageVector = StemwiseComposeGlyphs.icon(sourcePartIconRole(source.id).icon),
            contentDescription = null,
            tint = if (enabled) TextPrimary else TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(source.label, color = if (enabled) TextPrimary else TextSecondary)
            if (issue != null) {
                Text(issue, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}
