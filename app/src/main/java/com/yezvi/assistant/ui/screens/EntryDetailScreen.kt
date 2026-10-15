package com.yezvi.assistant.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.data.demo.DemoMemoryFixtures
import com.yezvi.assistant.presentation.memory.CorrectionSaveStatus
import com.yezvi.assistant.presentation.memory.EntryDeleteUiState
import com.yezvi.assistant.presentation.memory.EntryCorrectionUiState
import com.yezvi.assistant.presentation.memory.EntryDetailUiState
import com.yezvi.assistant.domain.memory.MemoryCorrection
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.ui.theme.Motion
import com.yezvi.assistant.ui.theme.YERVISTheme
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailScreen(
    state: EntryDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    deleteState: EntryDeleteUiState = EntryDeleteUiState.Idle,
    onDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    correctionState: EntryCorrectionUiState = EntryCorrectionUiState(),
    onRetryCorrections: () -> Unit = {},
    onBeginCorrection: () -> Unit = {},
    onCorrectionDraftChange: (String) -> Unit = {},
    onSaveCorrection: () -> Unit = {},
    onCancelCorrection: () -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val isSavingCorrection = correctionState.saveStatus == CorrectionSaveStatus.SAVING
    val handleBack = {
        when {
            isSavingCorrection || deleteState == EntryDeleteUiState.Deleting -> Unit
            correctionState.isEditing -> onCancelCorrection()
            showDeleteConfirmation -> {
                showDeleteConfirmation = false
                onCancelDelete()
            }
            else -> onBack()
        }
    }

    BackHandler(enabled = correctionState.isEditing) {
        if (!isSavingCorrection) onCancelCorrection()
    }

    LaunchedEffect(state, deleteState) {
        if (state !is EntryDetailUiState.Loaded && deleteState != EntryDeleteUiState.Deleted) {
            showDeleteConfirmation = false
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
                        TextButton(
                            onClick = handleBack,
                            modifier = Modifier.heightIn(min = 48.dp),
                            enabled = !isSavingCorrection && deleteState != EntryDeleteUiState.Deleting,
                        ) {
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
                EntryDetailUiState.Loading -> DetailLoadingState(contentPadding = innerPadding)
                EntryDetailUiState.NotFound -> DetailMessageState(
                    title = stringResource(R.string.entry_detail_empty_title),
                    hint = stringResource(R.string.entry_detail_empty_hint),
                    actionLabel = stringResource(R.string.entry_detail_back_to_memory),
                    onAction = onBack,
                    contentPadding = innerPadding,
                )
                EntryDetailUiState.Error -> DetailMessageState(
                    title = stringResource(R.string.entry_detail_error_title),
                    hint = stringResource(R.string.entry_detail_error_hint),
                    actionLabel = stringResource(R.string.entry_detail_retry),
                    onAction = onRetry,
                    contentPadding = innerPadding,
                )
                is EntryDetailUiState.Loaded -> DetailContent(
                    entry = current.entry,
                    correctionState = correctionState,
                    contentPadding = innerPadding,
                    onDeleteRequested = {
                        if (!correctionState.isEditing && !isSavingCorrection) {
                            showDeleteConfirmation = true
                        }
                    },
                    onRetryCorrections = onRetryCorrections,
                    onBeginCorrection = onBeginCorrection,
                    onCorrectionDraftChange = onCorrectionDraftChange,
                    onSaveCorrection = onSaveCorrection,
                    onCancelCorrection = onCancelCorrection,
                )
            }
        }
    }

    if (showDeleteConfirmation || deleteState == EntryDeleteUiState.Deleted) {
        val isDeleting = deleteState == EntryDeleteUiState.Deleting
        val isDeleted = deleteState == EntryDeleteUiState.Deleted
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting && !isDeleted) {
                    showDeleteConfirmation = false
                    onCancelDelete()
                }
            },
            title = {
                Text(
                    text = stringResource(
                        if (isDeleted) R.string.entry_delete_success_title
                        else R.string.entry_delete_confirm_title,
                    ),
                )
            },
            text = {
                Text(
                    text = when {
                        isDeleted -> stringResource(R.string.entry_delete_success_message)
                        isDeleting -> stringResource(R.string.entry_delete_in_progress)
                        deleteState == EntryDeleteUiState.Failed ->
                            stringResource(R.string.entry_delete_error)
                        else -> stringResource(R.string.entry_delete_confirm_message)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { if (isDeleted) onBack() else onDelete() },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("entry_delete_confirm_action"),
                    enabled = !isDeleting,
                ) {
                    Text(
                        text = when {
                            isDeleted -> stringResource(R.string.entry_delete_success_action)
                            deleteState == EntryDeleteUiState.Failed ->
                                stringResource(R.string.entry_delete_retry)
                            else -> stringResource(R.string.entry_delete_confirm_action)
                        },
                    )
                }
            },
            dismissButton = {
                if (!isDeleted) {
                    TextButton(
                        onClick = {
                            if (!isDeleting) {
                                showDeleteConfirmation = false
                                onCancelDelete()
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("entry_delete_cancel_action"),
                        enabled = !isDeleting,
                    ) {
                        Text(text = stringResource(R.string.entry_delete_cancel))
                    }
                }
            },
        )
    }
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
    correctionState: EntryCorrectionUiState,
    contentPadding: PaddingValues,
    onDeleteRequested: () -> Unit,
    onRetryCorrections: () -> Unit,
    onBeginCorrection: () -> Unit,
    onCorrectionDraftChange: (String) -> Unit,
    onSaveCorrection: () -> Unit,
    onCancelCorrection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.entry_current_version_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = correctionState.currentVersion?.text ?: entry.rawText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("entry_current_text"),
                    )
                    Spacer(Modifier.height(12.dp))
                    when {
                        correctionState.isLoading -> Text(
                            text = stringResource(R.string.entry_corrections_loading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        correctionState.loadFailed -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.entry_corrections_load_error),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onRetryCorrections,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.entry_detail_retry))
                            }
                        }
                        correctionState.isEditing -> {
                            OutlinedTextField(
                                value = correctionState.draft,
                                onValueChange = onCorrectionDraftChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("entry_correction_editor"),
                                label = { Text(stringResource(R.string.entry_correction_editor_label)) },
                                supportingText = {
                                    Text(stringResource(R.string.entry_correction_editor_hint))
                                },
                                minLines = 4,
                                maxLines = 8,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { onSaveCorrection() }),
                                enabled = !isSavingCorrection(correctionState),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = onCancelCorrection,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("entry_correction_cancel"),
                                    enabled = !isSavingCorrection(correctionState),
                                ) {
                                    Text(stringResource(R.string.entry_correction_cancel))
                                }
                                Button(
                                    onClick = onSaveCorrection,
                                    enabled = correctionState.draft.isNotBlank() &&
                                        correctionState.draft != correctionState.currentVersion?.text &&
                                        !isSavingCorrection(correctionState),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("entry_correction_save"),
                                ) {
                                    Text(
                                        stringResource(
                                            if (isSavingCorrection(correctionState)) {
                                                R.string.entry_correction_saving
                                            } else {
                                                R.string.entry_correction_save
                                            },
                                        ),
                                    )
                                }
                            }
                            if (correctionState.saveStatus == CorrectionSaveStatus.FAILED) {
                                Text(
                                    text = stringResource(R.string.entry_correction_save_error),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("entry_correction_error"),
                                )
                            }
                        }
                        else -> {
                            if (correctionState.saveStatus == CorrectionSaveStatus.SAVED) {
                                Text(
                                    text = stringResource(R.string.entry_correction_saved),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("entry_correction_saved"),
                                )
                            }
                            TextButton(
                                onClick = onBeginCorrection,
                                enabled = correctionState.currentVersion != null,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("entry_correction_open"),
                            ) {
                                Text(stringResource(R.string.entry_correction_open))
                            }
                        }
                    }
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.entry_original_version_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        entry.rawText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("entry_original_text"),
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
                        text = stringResource(R.string.entry_correction_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    when {
                        correctionState.isLoading -> Unit
                        correctionState.loadFailed -> Text(
                            text = stringResource(R.string.entry_corrections_history_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        correctionState.history.isEmpty() -> Text(
                            text = stringResource(R.string.entry_correction_history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> correctionState.history.forEachIndexed { index, correction ->
                            CorrectionHistoryItem(
                                index = index + 1,
                                correction = correction,
                                timezoneId = entry.timezoneId,
                            )
                        }
                    }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceBadge(entry.sourceType)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatEntryTime(entry.capturedAtEpochMillis, entry.timezoneId),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
        item {
            TextButton(
                onClick = onDeleteRequested,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("entry_delete_open_action"),
                enabled = !correctionState.isEditing,
            ) {
                Text(text = stringResource(R.string.entry_delete_open_action))
            }
        }
    }
}

@Composable
private fun CorrectionHistoryItem(
    index: Int,
    correction: MemoryCorrection,
    timezoneId: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("entry_correction_history_${correction.correctionId}")
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(
                R.string.entry_correction_history_item,
                index,
                formatEntryDate(correction.modifiedAtEpochMillis, timezoneId),
                formatEntryTime(correction.modifiedAtEpochMillis, timezoneId),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(correction.correctedText, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun isSavingCorrection(state: EntryCorrectionUiState): Boolean =
    state.saveStatus == CorrectionSaveStatus.SAVING

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
    YERVISTheme(dynamicColor = false) {
        val entry = DemoMemoryFixtures.entries.first()
        EntryDetailScreen(
            state = EntryDetailUiState.Loaded(entry),
            onBack = {},
            onRetry = {},
        )
    }
}
