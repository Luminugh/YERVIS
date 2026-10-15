package com.yezvi.assistant.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.presentation.memory.MemoryUiState
import com.yezvi.assistant.presentation.memory.MemorySelectionUiState
import com.yezvi.assistant.presentation.memory.MemoryDeleteSelectionStatus
import com.yezvi.assistant.ui.navigation.YezviBottomBar
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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.nav_memory),
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                )
                if (selectionState.selectedIds.isNotEmpty()) {
                    val allIds = entries.mapTo(linkedSetOf()) { it.id }
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
        bottomBar = {
            YezviBottomBar(
                currentDestination = AppDestination.MEMORY,
                onNavigateTo = callbacks.onNavigateTo,
            )
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
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                MemoryUiState.Loading -> item { LoadingTimelineState() }
                MemoryUiState.Empty -> item { EmptyTimelineState() }
                MemoryUiState.Error -> item { ErrorTimelineState(onRetry = onRetry) }
                is MemoryUiState.Content -> grouped.forEach { (dateKey, dayEntries) ->
                    stickyHeader(key = dateKey) {
                        DateHeader(dateKey = dateKey)
                    }
                    items(dayEntries, key = { it.id }) { entry ->
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
        .map { (_, dayEntries) ->
            val representative = dayEntries.first()
            keyFormatter.timeZone = TimeZone.getTimeZone(representative.timezoneId)
            keyFormatter.format(Date(representative.capturedAtEpochMillis)) to dayEntries
        }
}

@Composable
private fun DateHeader(dateKey: String, modifier: Modifier = Modifier) {
    val text = remember(dateKey) { formatDateHeader(dateKey) }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
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
                stateDescription = if (selected) "Seleccionado" else "No seleccionado"
            },
        colors = CardDefaults.cardColors(containerColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(entry.sourceType)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatEntryTime(entry.capturedAtEpochMillis, entry.timezoneId),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
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
        Text(
            text = when (sourceType) {
                CaptureSourceType.TEXT -> stringResource(R.string.memory_source_text)
                CaptureSourceType.VOICE -> stringResource(R.string.memory_source_voice)
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
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
