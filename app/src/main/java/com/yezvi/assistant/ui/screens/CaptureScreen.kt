package com.yezvi.assistant.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.presentation.capture.CaptureSaveState
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.ui.navigation.YezviBottomBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureScreen(
    callbacks: MemoryUiCallbacks,
    viewModel: CaptureViewModel,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var reviewInput by remember { mutableStateOf<CaptureInput?>(null) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<AppDestination?>(null) }
    val focusRequester = remember { FocusRequester() }
    val saveState by viewModel.uiState.collectAsState()
    val reviewSaveState = reviewInput?.let(saveState::forCapture) ?: CaptureSaveState.Idle
    val isSaving = saveState is CaptureSaveState.Saving

    LaunchedEffect(saveState, reviewInput) {
        if (reviewSaveState is CaptureSaveState.Saved) {
            draft = ""
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BackHandler(enabled = draft.isNotBlank() && !showReviewDialog && !isSaving) {
        pendingNavigation = null
        showDiscardDialog = true
    }

    if (showReviewDialog) {
        reviewInput?.let { input ->
            ReviewDialog(
                input = input,
                saveState = reviewSaveState,
                onSave = { viewModel.save(input) },
                onRetry = viewModel::retry,
                onDismiss = {
                    if (reviewSaveState !is CaptureSaveState.Saving) {
                        if (reviewSaveState is CaptureSaveState.Saved) {
                            draft = ""
                            reviewInput = null
                        }
                        showReviewDialog = false
                    }
                },
            )
        }
    }

    if (showDiscardDialog) {
        DiscardDialog(
            onConfirm = {
                callbacks.onNavigateTo(pendingNavigation ?: AppDestination.TODAY)
                pendingNavigation = null
                draft = ""
                reviewInput = null
                showReviewDialog = false
                showDiscardDialog = false
            },
            onDismiss = {
                pendingNavigation = null
                showDiscardDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_capture),
                        modifier = Modifier.semantics { heading() },
                    )
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.imePadding()) {
                CaptureActionBar(
                    draft = draft,
                    enabled = !isSaving,
                    onReview = {
                        if (reviewInput?.rawText != draft) {
                            reviewInput = CaptureInput(
                                rawText = draft,
                                sourceType = CaptureSourceType.TEXT,
                                capturedAtEpochMillis = System.currentTimeMillis(),
                                timezoneId = TimeZone.getDefault().id,
                            )
                        }
                        showReviewDialog = true
                    },
                    onClear = {
                        draft = ""
                        reviewInput = null
                    },
                )
                YezviBottomBar(
                    currentDestination = AppDestination.CAPTURE,
                    onNavigateTo = { destination ->
                        if (!isSaving) {
                            if (draft.isNotBlank() && destination != AppDestination.CAPTURE) {
                                pendingNavigation = destination
                                showDiscardDialog = true
                            } else {
                                callbacks.onNavigateTo(destination)
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (reviewInput?.rawText != it) reviewInput = null
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .focusRequester(focusRequester)
                .imePadding()
                .testTag("capture_text_field"),
            enabled = !isSaving,
            label = { Text(text = stringResource(R.string.capture_label)) },
            placeholder = { Text(text = stringResource(R.string.capture_hint)) },
            supportingText = { Text(text = stringResource(R.string.capture_draft_notice)) },
            minLines = 6,
            maxLines = Int.MAX_VALUE,
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
        )
    }
}

@Composable
private fun CaptureActionBar(
    draft: String,
    enabled: Boolean,
    onReview: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onClear,
                enabled = draft.isNotBlank() && enabled,
            ) {
                Text(text = stringResource(R.string.capture_clear))
            }
            Button(
                onClick = onReview,
                enabled = draft.isNotBlank() && enabled,
            ) {
                Text(text = stringResource(R.string.capture_review))
            }
        }
    }
}

@Composable
private fun ReviewDialog(
    input: CaptureInput,
    saveState: CaptureSaveState,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isSaving = saveState is CaptureSaveState.Saving
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(text = stringResource(R.string.capture_review_title)) },
        text = {
            Column {
                Text(
                    text = input.rawText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("capture_review_body"),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        R.string.capture_review_meta,
                        if (input.sourceType == CaptureSourceType.TEXT) "Texto" else input.sourceType.name,
                        formatReviewDate(input.capturedAtEpochMillis, input.timezoneId),
                        input.timezoneId,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        when (saveState) {
                            CaptureSaveState.Idle -> R.string.capture_review_notice
                            is CaptureSaveState.Saving -> R.string.capture_saving_status
                            is CaptureSaveState.Saved -> R.string.capture_saved_status
                            is CaptureSaveState.Error -> R.string.capture_failed_status
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("capture_save_status"),
                )
            }
        },
        confirmButton = {
            when (saveState) {
                CaptureSaveState.Idle -> Button(
                    onClick = onSave,
                    modifier = Modifier.testTag("capture_save_button"),
                ) {
                    Text(text = stringResource(R.string.capture_save))
                }
                is CaptureSaveState.Saving -> Button(onClick = {}, enabled = false) {
                    Text(text = stringResource(R.string.capture_saving_action))
                }
                is CaptureSaveState.Saved -> TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("capture_finish_button"),
                ) {
                    Text(text = stringResource(R.string.capture_done))
                }
                is CaptureSaveState.Error -> Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("capture_retry_button"),
                ) {
                    Text(text = stringResource(R.string.capture_retry))
                }
            }
        },
        dismissButton = {
            if (saveState is CaptureSaveState.Idle || saveState is CaptureSaveState.Error) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.capture_close))
                }
            }
        },
    )
}

private fun CaptureSaveState.forCapture(input: CaptureInput): CaptureSaveState = when (this) {
    CaptureSaveState.Idle -> this
    is CaptureSaveState.Saving -> if (this.input == input) this else CaptureSaveState.Idle
    is CaptureSaveState.Saved -> if (entry.matches(input)) this else CaptureSaveState.Idle
    is CaptureSaveState.Error -> if (this.input == input) this else CaptureSaveState.Idle
}

private fun MemoryEntry.matches(input: CaptureInput): Boolean =
    rawText == input.rawText &&
        sourceType == input.sourceType &&
        capturedAtEpochMillis == input.capturedAtEpochMillis &&
        timezoneId == input.timezoneId

private fun formatReviewDate(epochMillis: Long, timezoneId: String): String =
    SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("es-ES"))
        .apply { timeZone = TimeZone.getTimeZone(timezoneId) }
        .format(Date(epochMillis))

@Composable
private fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.capture_discard_title)) },
        text = { Text(text = stringResource(R.string.capture_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.capture_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.capture_discard_cancel))
            }
        },
    )
}
