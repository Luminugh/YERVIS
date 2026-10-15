package com.yezvi.assistant.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.presentation.memory.MemoryUiState
import com.yezvi.assistant.presentation.memory.MemorySelectionUiState
import com.yezvi.assistant.presentation.memory.MemoryDeleteSelectionStatus
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.theme.LocalVisualMetrics
import com.yezvi.assistant.ui.theme.Motion
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryScreen(
    state: MemoryUiState,
    callbacks: MemoryUiCallbacks,
    onRetry: () -> Unit,
    selectionState: MemorySelectionUiState = MemorySelectionUiState(),
    onToggleSelection: (String) -> Unit = {},
    onSelectAll: (Set<String>) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRequestDeleteSelected: () -> Unit = {},
    onDismissDeleteSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
) {
    val entries = (state as? MemoryUiState.Content)?.entries.orEmpty()
    val grouped = remember(entries) { groupEntriesByDate(entries) }
    val allIds = remember(entries) { entries.mapTo(linkedSetOf()) { it.id } }
    val metrics = LocalVisualMetrics.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.nav_memory),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                text = stringResource(R.string.ux_memory_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                if (selectionState.selectedIds.isNotEmpty()) {
                    SelectionActions(
                        count = selectionState.selectedIds.size,
                        allSelected = allIds.isNotEmpty() && selectionState.selectedIds.containsAll(allIds),
                        enabled = selectionState.deleteStatus == MemoryDeleteSelectionStatus.IDLE,
                        onSelectAll = { onSelectAll(allIds) },
                        onDelete = onRequestDeleteSelected,
                        onCancel = onClearSelection,
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (state is MemoryUiState.Content) {
                        Modifier.testTag("memory_content_loaded")
                    } else {
                        Modifier
                    },
                ),
            contentPadding = PaddingValues(
                start = metrics.screenPadding,
                end = metrics.screenPadding,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.itemSpacing),
        ) {
            when (state) {
                MemoryUiState.Loading -> item(key = "loading", contentType = "state") { LoadingTimelineState() }
                MemoryUiState.Empty -> item(key = "empty", contentType = "state") { EmptyTimelineState() }
                MemoryUiState.Error -> item(key = "error", contentType = "state") { ErrorTimelineState(onRetry = onRetry) }
                is MemoryUiState.Content -> grouped.forEach { (dateKey, dayEntries) ->
                    stickyHeader(key = "day_$dateKey", contentType = "day") {
                        DateHeader(dateKey = dateKey, count = dayEntries.size)
                    }
                    items(dayEntries, key = { it.id }, contentType = { "memory" }) { entry ->
                        TimelineItemCard(
                            entry = entry,
                            displayText = state.currentTextById[entry.id] ?: entry.rawText,
                            selected = entry.id in selectionState.selectedIds,
                            selecting = selectionState.selectedIds.isNotEmpty(),
                            onEntryClick = {
                                if (selectionState.selectedIds.isNotEmpty()) onToggleSelection(entry.id)
                                else callbacks.onMemoryEntrySelected(entry.id)
                            },
                            onEntryLongClick = { onToggleSelection(entry.id) },
                        )
                    }
                }
            }
        }
    }

    if (selectionState.deleteStatus != MemoryDeleteSelectionStatus.IDLE) {
        val deleting = selectionState.deleteStatus == MemoryDeleteSelectionStatus.DELETING
        val failed = selectionState.deleteStatus == MemoryDeleteSelectionStatus.FAILED
        AlertDialog(
            onDismissRequest = { if (!deleting) onDismissDeleteSelected() },
            title = { Text(stringResource(R.string.memory_delete_selected_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (deleting) stringResource(R.string.memory_delete_selected_in_progress)
                        else stringResource(
                            R.string.memory_delete_selected_message,
                            selectionState.selectedIds.size,
                        ),
                    )
                    if (failed) {
                        Text(
                            stringResource(R.string.memory_delete_selected_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                when {
                    deleting -> Unit
                    failed -> TextButton(onClick = onDeleteSelected) {
                        Text(stringResource(R.string.memory_delete_selected_retry))
                    }
                    else -> Button(
                        onClick = onDeleteSelected,
                        modifier = Modifier.testTag("memory_delete_selected_confirm"),
                    ) { Text(stringResource(R.string.memory_delete_selected)) }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissDeleteSelected,
                    enabled = !deleting,
                    modifier = Modifier.testTag("memory_delete_selected_cancel"),
                ) { Text(stringResource(R.string.entry_delete_cancel)) }
            },
            modifier = Modifier.testTag("memory_delete_selected_dialog"),
        )
    }
}

@Composable
private fun SelectionActions(
    count: Int,
    allSelected: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
            .testTag("memory_selection_actions"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.memory_selected_count, count, count),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(
                    onClick = onCancel,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("memory_selection_cancel"),
                ) { Text(stringResource(R.string.memory_selection_cancel)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onSelectAll,
                    enabled = enabled && !allSelected,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("memory_select_all"),
                ) {
                    Text(
                        text = stringResource(if (allSelected) R.string.memory_all_selected else R.string.memory_select_all),
                        maxLines = 2,
                    )
                }
                Button(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("memory_delete_selected"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.memory_delete_selected), maxLines = 2)
                }
            }
        }
    }
}

internal fun groupEntriesByDate(
    entries: List<MemoryEntry>,
): List<Pair<String, List<MemoryEntry>>> {
    val keyFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return entries
        .groupBy { entry ->
            keyFormatter.timeZone = TimeZone.getTimeZone(entry.timezoneId)
            keyFormatter.format(Date(entry.capturedAtEpochMillis))
        }
        .map { (dateKey, dayEntries) ->
            dateKey to dayEntries.sortedWith(
                compareByDescending<MemoryEntry> { it.capturedAtEpochMillis }
                    .thenByDescending { it.id },
            )
        }
        .sortedWith(
            compareByDescending<Pair<String, List<MemoryEntry>>> {
                it.second.first().capturedAtEpochMillis
            }.thenByDescending { it.second.first().id },
        )
}

@Composable
private fun DateHeader(dateKey: String, count: Int, modifier: Modifier = Modifier) {
    val text = remember(dateKey) { formatDateHeader(dateKey) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDateHeader(dateKey: String): String {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val date = parser.parse(dateKey) ?: return dateKey
    val formatter = SimpleDateFormat("EEEE d 'de' MMMM", Locale.forLanguageTag("es-ES"))
    return formatter.format(date).replaceFirstChar { it.uppercase() }
}

internal fun formatEntryDate(epochMillis: Long, timezoneId: String): String {
    val formatter = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-ES"))
    formatter.timeZone = TimeZone.getTimeZone(timezoneId)
    return formatter.format(Date(epochMillis))
}

@Composable
private fun TimelineItemCard(
    entry: MemoryEntry,
    displayText: String,
    selected: Boolean,
    selecting: Boolean,
    onEntryClick: (String) -> Unit,
    onEntryLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = remember(entry.capturedAtEpochMillis, entry.timezoneId) {
        formatEntryTime(entry.capturedAtEpochMillis, entry.timezoneId)
    }
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var hasOverflow by remember(displayText) { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val metrics = LocalVisualMetrics.current
    val selectedLabel = stringResource(if (selected) R.string.ux_memory_selected else R.string.ux_memory_not_selected)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("timeline_entry_${entry.id}")
            .combinedClickable(
                role = Role.Button,
                onClick = { onEntryClick(entry.id) },
                onLongClick = onEntryLongClick,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = selectedLabel
            },
        colors = CardDefaults.cardColors(containerColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(entry.sourceType)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) hasOverflow = it.hasVisualOverflow },
                    modifier = Modifier.animateContentSize(
                        animationSpec = tween(motionDurationMillis(Motion.StateChangeMillis, reduceMotion)),
                    ),
                )
                if ((hasOverflow || expanded) && !selecting) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("timeline_expand_${entry.id}"),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text(stringResource(if (expanded) R.string.ux_memory_show_less else R.string.ux_memory_show_more))
                        YervisIcon(
                            symbol = if (expanded) YervisSymbol.ExpandLess else YervisSymbol.ExpandMore,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp),
                        )
                    }
                }
            }
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
            else YervisIcon(YervisSymbol.Next, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun formatEntryTime(epochMillis: Long, timezoneId: String): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.forLanguageTag("es-ES"))
    formatter.timeZone = TimeZone.getTimeZone(timezoneId)
    return formatter.format(Date(epochMillis))
}

@Composable
internal fun SourceBadge(sourceType: CaptureSourceType, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YervisIcon(
                symbol = if (sourceType == CaptureSourceType.VOICE) YervisSymbol.Voice else YervisSymbol.Text,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = when (sourceType) {
                    CaptureSourceType.TEXT -> stringResource(R.string.memory_source_text)
                    CaptureSourceType.VOICE -> stringResource(R.string.memory_source_voice)
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun LoadingTimelineState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
            .testTag("memory_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.memory_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyTimelineState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
            .testTag("memory_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        YervisIcon(YervisSymbol.Memory, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.memory_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.memory_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorTimelineState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
            .testTag("memory_error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_error_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.memory_error_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.memory_retry))
        }
    }
}
