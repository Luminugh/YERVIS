package com.yezvi.assistant.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.data.demo.DemoMemoryFixtures
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.ui.theme.Motion
import com.yezvi.assistant.ui.theme.YEZVITheme
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailScreen(
    entryId: String,
    onBack: () -> Unit,
    findEntryById: (String) -> MemoryEntry?,
) {
    var state by remember { mutableStateOf<EntryDetailState>(EntryDetailState.Loading) }
    var reloadTick by remember { mutableStateOf(0) }
    val reduceMotion = rememberReduceMotion()

    LaunchedEffect(entryId, reloadTick, findEntryById) {
        state = try {
            findEntryById(entryId)
                ?.let(EntryDetailState::Loaded)
                ?: EntryDetailState.NotFound
        } catch (expected: Exception) {
            EntryDetailState.Error
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.entry_detail_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(text = stringResource(R.string.entry_detail_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Crossfade(
            targetState = state,
            animationSpec = tween(
                durationMillis = motionDurationMillis(Motion.StateChangeMillis, reduceMotion),
            ),
            label = "entry-detail-state",
        ) { current ->
            when (current) {
                EntryDetailState.Loading -> DetailLoadingState(contentPadding = innerPadding)
                EntryDetailState.NotFound -> DetailMessageState(
                    title = stringResource(R.string.entry_detail_empty_title),
                    hint = stringResource(R.string.entry_detail_empty_hint),
                    actionLabel = stringResource(R.string.entry_detail_back_to_memory),
                    onAction = onBack,
                    contentPadding = innerPadding,
                )
                EntryDetailState.Error -> DetailMessageState(
                    title = stringResource(R.string.entry_detail_error_title),
                    hint = stringResource(R.string.entry_detail_error_hint),
                    actionLabel = stringResource(R.string.entry_detail_retry),
                    onAction = { reloadTick++ },
                    contentPadding = innerPadding,
                )
                is EntryDetailState.Loaded -> DetailContent(
                    entry = current.entry,
                    contentPadding = innerPadding,
                )
            }
        }
    }
}

private sealed interface EntryDetailState {
    data object Loading : EntryDetailState
    data class Loaded(val entry: MemoryEntry) : EntryDetailState
    data object NotFound : EntryDetailState
    data object Error : EntryDetailState
}

@Composable
private fun DetailLoadingState(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.entry_detail_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailMessageState(
    title: String,
    hint: String,
    actionLabel: String,
    onAction: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAction) {
            Text(text = actionLabel)
        }
    }
}

@Composable
private fun DetailContent(
    entry: MemoryEntry,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DemoNoticeBanner() }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
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
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.entry_detail_data_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    DetailRow(
                        label = stringResource(R.string.entry_detail_source),
                        value = when (entry.sourceType) {
                            CaptureSourceType.TEXT -> stringResource(R.string.memory_source_text)
                            CaptureSourceType.VOICE -> stringResource(R.string.memory_source_voice)
                        },
                        valueModifier = Modifier.testTag("entry_detail_source_value"),
                    )
                    DetailRow(
                        label = stringResource(R.string.entry_detail_date),
                        value = formatEntryDate(entry.capturedAtEpochMillis, entry.timezoneId),
                    )
                    DetailRow(
                        label = stringResource(R.string.entry_detail_time),
                        value = formatEntryTime(entry.capturedAtEpochMillis, entry.timezoneId),
                    )
                    DetailRow(
                        label = stringResource(R.string.entry_detail_timezone),
                        value = entry.timezoneId,
                    )
                    DetailRow(
                        label = stringResource(R.string.entry_detail_status),
                        value = statusLabel(entry.status),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueModifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = valueModifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun statusLabel(status: CaptureStatus): String = when (status) {
    CaptureStatus.IDLE -> stringResource(R.string.entry_detail_status_idle)
    CaptureStatus.SAVING -> stringResource(R.string.entry_detail_status_saving)
    CaptureStatus.SAVED -> stringResource(R.string.entry_detail_status_saved)
    CaptureStatus.FAILED -> stringResource(R.string.entry_detail_status_failed)
}

@Preview(showBackground = true, name = "Detalle — entrada demo")
@Composable
private fun EntryDetailScreenPreview() {
    YEZVITheme(dynamicColor = false) {
        val entry = DemoMemoryFixtures.entries.first()
        EntryDetailScreen(
            entryId = entry.id,
            onBack = {},
            findEntryById = { requestedId -> entry.takeIf { it.id == requestedId } },
        )
    }
}

@Composable
private fun DemoNoticeBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.entry_detail_demo_notice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
