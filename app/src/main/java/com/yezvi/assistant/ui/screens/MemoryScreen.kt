package com.yezvi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.heading
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
) {
    val entries = (state as? MemoryUiState.Content)?.entries.orEmpty()
    val grouped = remember(entries) { groupEntriesByDate(entries) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_memory),
                        modifier = Modifier.semantics { heading() },
                    )
                },
            )
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
                            onEntryClick = callbacks.onMemoryEntrySelected,
                        )
                    }
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
    onEntryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onEntryClick(entry.id) },
        modifier = modifier
            .fillMaxWidth()
            .testTag("timeline_entry_${entry.id}")
            .semantics(mergeDescendants = true) { },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                text = entry.rawText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
