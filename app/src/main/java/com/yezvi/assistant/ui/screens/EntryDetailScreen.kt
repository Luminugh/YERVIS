package com.yezvi.assistant.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisPanel
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.theme.LocalVisualMetrics
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
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.entry_detail_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = handleBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                        enabled = !isSavingCorrection && deleteState != EntryDeleteUiState.Deleting,
                    ) {
                        YervisIcon(YervisSymbol.Back, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(text = stringResource(R.string.entry_detail_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        // Keep a loaded editor in one composition. Animating the complete state used to
        // duplicate the entire scrolling detail while a new Loaded value arrived.
        when (state) {
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
                    entry = state.entry,
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
    val metrics = LocalVisualMetrics.current
    val reduceMotion = rememberReduceMotion()
    val feedbackDuration = motionDurationMillis(Motion.StateChangeMillis, reduceMotion)
    val date = remember(entry.capturedAtEpochMillis, entry.timezoneId) {
        formatEntryDate(entry.capturedAtEpochMillis, entry.timezoneId)
    }
    val time = remember(entry.capturedAtEpochMillis, entry.timezoneId) {
        formatEntryTime(entry.capturedAtEpochMillis, entry.timezoneId)
    }
    val currentText = correctionState.currentVersion?.text ?: entry.rawText
    val hasCorrection = correctionState.currentVersion?.correctionId != null
    val canSave = correctionState.draft.isNotBlank() &&
        correctionState.draft != currentText && !isSavingCorrection(correctionState)

    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding().testTag("entry_detail_list"),
        contentPadding = PaddingValues(
            start = metrics.screenPadding,
            end = metrics.screenPadding,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(metrics.itemSpacing),
    ) {
        item(key = "capture_context", contentType = "context") {
            YervisPanel(highlighted = true, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(metrics.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        YervisIcon(
                            symbol = if (entry.status == CaptureStatus.SAVED) YervisSymbol.Shield else YervisSymbol.Info,
                            modifier = Modifier.size(20.dp),
                            tint = if (entry.status == CaptureStatus.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = statusLabel(entry.status),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (entry.status == CaptureStatus.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        YervisIcon(
                            symbol = if (entry.sourceType == CaptureSourceType.VOICE) YervisSymbol.Voice else YervisSymbol.Text,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                if (entry.sourceType == CaptureSourceType.VOICE) R.string.memory_source_voice
                                else R.string.memory_source_text,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("entry_detail_source_value"),
                        )
                        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = entry.timezoneId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "current_text", contentType = "editor") {
            YervisPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(metrics.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (hasCorrection) R.string.entry_current_version_title
                            else R.string.entry_original_version_title,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!correctionState.isEditing) {
                        // One visible text when the current version is the original; both
                        // source and current-version tags remain available to UI checks.
                        Box(modifier = if (!hasCorrection) Modifier.testTag("entry_original_text") else Modifier) {
                            Text(
                                text = currentText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("entry_current_text"),
                            )
                        }
                    }
                    when {
                        correctionState.isLoading -> Text(
                            text = stringResource(R.string.entry_corrections_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        correctionState.loadFailed -> {
                            Text(
                                text = stringResource(R.string.entry_corrections_load_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetryCorrections, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.entry_detail_retry))
                            }
                        }
                        correctionState.isEditing -> {
                            // The editable field is never placed inside AnimatedContent or
                            // animateContentSize: IME, selection and cursor keep one owner.
                            OutlinedTextField(
                                value = correctionState.draft,
                                onValueChange = onCorrectionDraftChange,
                                modifier = Modifier.fillMaxWidth().testTag("entry_correction_editor"),
                                label = { Text(stringResource(R.string.entry_correction_editor_label)) },
                                supportingText = { Text(stringResource(R.string.entry_correction_editor_hint)) },
                                minLines = 4,
                                maxLines = 10,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { if (canSave) onSaveCorrection() }),
                                enabled = !isSavingCorrection(correctionState),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = onCancelCorrection,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("entry_correction_cancel"),
                                    enabled = !isSavingCorrection(correctionState),
                                ) { Text(stringResource(R.string.entry_correction_cancel)) }
                                Button(
                                    onClick = onSaveCorrection,
                                    enabled = canSave,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("entry_correction_save"),
                                ) {
                                    Text(stringResource(
                                        if (isSavingCorrection(correctionState)) R.string.entry_correction_saving
                                        else R.string.entry_correction_save,
                                    ))
                                }
                            }
                        }
                        else -> TextButton(
                            onClick = onBeginCorrection,
                            enabled = correctionState.currentVersion != null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("entry_correction_open"),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                        ) {
                            YervisIcon(YervisSymbol.Edit, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.entry_correction_open))
                        }
                    }
                    AnimatedVisibility(
                        visible = correctionState.saveStatus == CorrectionSaveStatus.FAILED,
                        enter = fadeIn(tween(feedbackDuration)),
                        exit = fadeOut(tween(feedbackDuration)),
                    ) { CorrectionFeedback(failed = true) }
                    AnimatedVisibility(
                        visible = correctionState.saveStatus == CorrectionSaveStatus.SAVED,
                        enter = fadeIn(tween(feedbackDuration)),
                        exit = fadeOut(tween(feedbackDuration)),
                    ) { CorrectionFeedback(failed = false) }
                }
            }
        }
        if (hasCorrection) {
            item(key = "original_text", contentType = "source") {
                YervisPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(metrics.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.entry_original_version_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(R.string.ux_memory_original_preserved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.rawText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("entry_original_text"))
                    }
                }
            }
        }
        item(key = "history_header", contentType = "section") {
            Text(
                text = stringResource(R.string.entry_correction_history_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
        }
        when {
            correctionState.isLoading -> Unit
            correctionState.loadFailed -> item(key = "history_unavailable", contentType = "message") {
                Text(
                    stringResource(R.string.entry_corrections_history_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            correctionState.history.isEmpty() -> item(key = "history_empty", contentType = "message") {
                Text(
                    stringResource(R.string.entry_correction_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> itemsIndexed(
                correctionState.history,
                key = { _, correction -> "correction_${correction.correctionId}" },
                contentType = { _, _ -> "correction" },
            ) { index, correction ->
                CorrectionHistoryItem(index + 1, correction, entry.timezoneId)
            }
        }
        item(key = "delete_action", contentType = "action") {
            TextButton(
                onClick = onDeleteRequested,
                modifier = Modifier.heightIn(min = 48.dp).testTag("entry_delete_open_action"),
                enabled = !correctionState.isEditing,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            ) {
                YervisIcon(YervisSymbol.Delete, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.entry_delete_open_action))
            }
        }
    }
}

@Composable
private fun CorrectionHistoryItem(index: Int, correction: MemoryCorrection, timezoneId: String) {
    val date = remember(correction.modifiedAtEpochMillis, timezoneId) {
        formatEntryDate(correction.modifiedAtEpochMillis, timezoneId)
    }
    val time = remember(correction.modifiedAtEpochMillis, timezoneId) {
        formatEntryTime(correction.modifiedAtEpochMillis, timezoneId)
    }
    YervisPanel(modifier = Modifier.fillMaxWidth().testTag("entry_correction_history_${correction.correctionId}")) {
        Column(
            modifier = Modifier.padding(LocalVisualMetrics.current.panelPadding).semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.entry_correction_history_item, index, date, time),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(correction.correctedText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
private fun isSavingCorrection(state: EntryCorrectionUiState): Boolean =
    state.saveStatus == CorrectionSaveStatus.SAVING

@Composable
private fun CorrectionFeedback(failed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        YervisIcon(
            symbol = if (failed) YervisSymbol.Info else YervisSymbol.Check,
            modifier = Modifier.size(20.dp),
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(if (failed) R.string.entry_correction_save_error else R.string.entry_correction_saved),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(if (failed) "entry_correction_error" else "entry_correction_saved"),
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
